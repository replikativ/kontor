(ns kontor.banking.payment-application
  "Partial-payment primitive — ADR-043.

   A `:payment-application` row records that some amount of a cash-
   receipt transaction `:payment` was applied to a specific
   `:invoice`. The row is bitemporal via datahike tx-time: the
   `:applied-at` field gives the wall-clock instant of the
   application, and the tx-time gives the (immutable) recording
   instant.

   Three primitives close the procurement-era scope-cut at
   `kontor.banking.reconciliation:38-47`:

     - apply-payment!         create one application row + (when
                              the invoice's status transitions)
                              the matching :status-history row.
     - reverse-application!   write a negated row with :reversal-of
                              pointing at the original. Datahike's
                              tx-time gives free allocation replay.
     - open-amount-of         (invoice gross − Σ applied) per
                              invoice at any `:as-of-tx`/`:as-of-
                              valid`. Aging reads this.

   Lives in the kernel (not `kontor-collections`) because revrec
   (Stage M) and subscription (Stage N) consume the same primitive.

   ## Bitemporal interop (experimental, see kontor.bitemporal)

   The `:kontor.payment-application/applied-at` attribute IS the per-entity
   valid-time for an application — when the cash hit the books in
   the world. Classical `:as-of-valid` reads filter on it.

   `apply-payment!` / `reverse-application!` additionally accept
   `:vt-from` (and optional `:vt-to`) opts: when present the entire
   tx is stamped with kontor.bitemporal's `:tx/valid-from`. This
   propagates the same valid-time onto the status-history record(s)
   the tx writes, so `(:kontor.invoice/status (d/pull (d/valid-at db vt) [...] invoice))`
   reproduces what `:kontor.invoice/status` was at any historical valid-
   time. Defaults to `:applied-at` when `:vt-from` is omitted, so
   existing callers keep their semantics."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.fx.fx :as fx]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.workflow.process :as process]
            [kontor.workflow.status-machine :as sm]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn- resolve-invoice [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (d/q '[:find ?e .
                          :in $ ?xid
                          :where [?e :kontor.invoice/external-id ?xid]]
                        db spec)
    :else          spec))

(defn- commodity-symbol
  "Resolve a `:commodity` opt (eid, lookup-ref, keyword, or symbol string) to
   its ISO symbol string, for comparison with :kontor.invoice/currency."
  [db commodity]
  (cond
    (string? commodity)  commodity
    (keyword? commodity) (name commodity)
    :else                (:kontor.commodity/symbol (d/entity db commodity))))

(defn- pull-invoice-min [db invoice-eid]
  (d/pull db
          [:db/id :kontor.invoice/external-id :kontor.invoice/status :kontor.invoice/currency
           {:kontor.invoice/seller [:db/id]}
           {:kontor.invoice/buyer [:db/id]}]
          invoice-eid))

;; ============================================================================
;; Queries
;; ============================================================================

(defn applications-of
  "Pulled :payment-application rows for an invoice, oldest-first by
   :applied-at. Honors `:as-of-valid` opt (default now). Net = sum of
   :amount including any reversals."
  ([db invoice-spec] (applications-of db invoice-spec nil))
  ([db invoice-spec {:keys [as-of-valid]}]
   (when-let [invoice-eid (resolve-invoice db invoice-spec)]
     (let [as-of-valid (or as-of-valid (java.util.Date.))
           cutoff-millis (.getTime ^java.util.Date as-of-valid)
           rows (d/q '[:find [?app ...]
                       :in $ ?inv ?cutoff-ms
                       :where
                       [?app :kontor.payment-application/invoice ?inv]
                       [?app :kontor.payment-application/applied-at ?when]
                       [(.getTime ^java.util.Date ?when) ?when-ms]
                       [(<= ?when-ms ?cutoff-ms)]]
                     db invoice-eid cutoff-millis)]
       (->> rows
            (map #(d/pull db '[*] %))
            (sort-by :kontor.payment-application/applied-at)
            vec)))))

(defn applied-amount-of-invoice
  "Sum of :kontor.payment-application/amount for an invoice (positive +
   negative reversals net out), PLUS any `:write-off-amount` closed as part of
   those settlements (note 198 r3-recon-b) — a short payment written off to
   tolerance clears the open item just as cash does. Returns BigDecimal."
  ([db invoice-spec] (applied-amount-of-invoice db invoice-spec nil))
  ([db invoice-spec {:keys [as-of-valid]}]
   (let [apps (applications-of db invoice-spec {:as-of-valid as-of-valid})]
     (reduce (fn [^java.math.BigDecimal acc app]
               (-> acc
                   (.add ^java.math.BigDecimal
                    (or (:kontor.payment-application/amount app) 0M))
                   (.add ^java.math.BigDecimal
                    (or (:kontor.payment-application/write-off-amount app) 0M))))
             0M
             apps))))

(defn gross-of-invoice
  "The invoice's gross: `:kontor.invoice/total-gross` when set, else the
   sum of `:kontor.invoice-line/amount` across its lines. 0M when
   neither. Returns BigDecimal.

   THE `:with ?l` IS LOAD-BEARING (ADR-162). Datahike's `:find` has SET
   semantics, so `(sum ?amt)` over a relation that binds only `?amt`
   collapses equal values: a 2 x 500.00 invoice would report gross
   500.00. Binding the line entity in `:with` keeps the two rows
   distinct. The line-sum fallback is the LIVE path for bridge invoices,
   which never set `:total-gross`.

   Extracted so there is ONE such query. It had been copied inline three
   times and the copy in `kontor.collections.aging` was missing the
   `:with`."
  ^java.math.BigDecimal [db invoice-eid]
  (or (:kontor.invoice/total-gross
       (d/pull db [:kontor.invoice/total-gross] invoice-eid))
      (d/q '[:find (sum ?amt) .
             :with ?l
             :in $ ?inv
             :where
             [?l :kontor.invoice-line/invoice ?inv]
             [?l :kontor.invoice-line/amount ?amt]]
           db invoice-eid)
      0M))

(defn open-amount-of-invoice
  "Bitemporal open-amount = (invoice gross − applied). The invoice
   gross is `:kontor.invoice/total-gross` if present, else the sum of
   `:kontor.invoice-line/amount` across lines.

   NEGATIVE when the invoice is OVERPAID — that is a customer credit, and
   callers must surface it rather than filter it away (ADR-161).

   Returns BigDecimal."
  ([db invoice-spec] (open-amount-of-invoice db invoice-spec nil))
  ([db invoice-spec {:keys [as-of-valid] :as opts}]
   (when-let [invoice-eid (resolve-invoice db invoice-spec)]
     (let [gross (gross-of-invoice db invoice-eid)
           applied (applied-amount-of-invoice db invoice-eid opts)]
       (.subtract ^java.math.BigDecimal gross
                  ^java.math.BigDecimal applied)))))

;; NOTE: `unapplied-cash-balance` deferred to Stage L companion. The
;; kernel doesn't model "cash received from partner X" cleanly without
;; a sum-of-postings-against-cash-account query, which couples to
;; chart-of-accounts. The collections companion re-derives it from
;; postings on AR/cash accounts when it lands.

;; ============================================================================
;; Bitemporal reads — invoice status at any valid-time
;; ============================================================================

(defn invoice-status-at
  "Resolve `:kontor.invoice/status` at valid-time `cutoff` using kontor.
   bitemporal. Requires the kontor.bitemporal schema to be installed
   AND the txs that drove the status change to have been stamped
   with `:tx/valid-from` (which `apply-payment!` /
   `reverse-application!` do automatically when their `:applied-at`
   is set or `:vt-from` is passed).

   Composes with `(d/as-of db tx)` for full bitemporal time travel:

       (invoice-status-at (d/as-of db tx-id)
                          (resolve-invoice db inv)
                          #inst \"2026-04-01\")

   Returns the keyword status or nil if no assertion applies."
  [db invoice-spec cutoff]
  (when-let [eid (resolve-invoice db invoice-spec)]
    (:kontor.invoice/status (d/pull (d/valid-at db cutoff) [:kontor.invoice/status] eid))))

;; ============================================================================
;; Transactors
;; ============================================================================

(defn- next-status-for-application
  "Decide the invoice's new `:kontor.invoice/status` given the current
   status, the open-amount after this application, and whether
   reversals were involved."
  [current-status open-after-amount]
  (cond
    ;; Open balance went to zero or below → :paid.
    (and (or (= current-status :sent)
             (= current-status :partially-paid))
         (<= (.signum ^java.math.BigDecimal open-after-amount) 0))
    :paid

    ;; First positive application on a :sent invoice with remaining
    ;; balance → :partially-paid.
    (and (= current-status :sent)
         (pos? (.signum ^java.math.BigDecimal open-after-amount)))
    :partially-paid

    ;; Additional application on :partially-paid → stays :partially-paid
    ;; (the self-loop is legal).
    (= current-status :partially-paid) :partially-paid

    ;; Reversal that re-opens a previously :paid invoice → :sent
    ;; (caller should set this explicitly via reverse-application!).
    :else nil))

(declare apply-payment-tx-data)

(defn apply-payment!
  "Record a `:payment-application` row + drive the invoice's
   `:kontor.invoice/status` facet accordingly.

   Required opts:
     :payment        ref or eid to the cash-receipt :transaction.
     :invoice        ref/eid/external-id of the invoice.
     :amount         BigDecimal. Positive reduces the invoice's open
                     balance. Negative is a reversal — but prefer
                     `reverse-application!` which sets :reversal-of.
     :commodity      ref to :commodity. Must match invoice currency.
     :applied-by-uid ref to :kontor.audit/create-uid.

   Optional opts:
     :applied-at        instant, default now (also drives the tx's
                        :tx/valid-from when :vt-from is omitted).
     :strategy          :fifo | :customer-instruction | :proportional
                        | :cherry-pick | :reversal (default
                        :cherry-pick).
     :reason            keyword.
     :reason-note       string.
     :supporting-doc    ref to :audit-doc.
     :vt-from           instant — override tx-level valid-time
                        (kontor.bitemporal). Default: `:applied-at`.
     :vt-to             instant — optional upper bound for the tx's
                        valid-time interval. Default: open-ended.

   Returns the tx-report."
  [conn opts]
  (let [{:keys [vt-from vt-to applied-at]} opts
        applied-at (or applied-at (java.util.Date.))
        tx-data (apply-payment-tx-data
                 (d/db conn) (assoc opts :applied-at applied-at))
        effective-vt-from (or vt-from applied-at)
        final-tx (cond
                   (and effective-vt-from vt-to)
                   (kbt/with-vt tx-data effective-vt-from vt-to)
                   effective-vt-from
                   (kbt/with-vt tx-data effective-vt-from)
                   :else tx-data)]
    (validation/transact-with-validation conn final-tx)))

(defn apply-payment-tx-data
  "Pure tx-data builder for `apply-payment!` — the
   `:payment-application` row + the optional invoice-status change,
   without the `with-vt` wrap (ADR-067). Use as a `kontor.workflow.process`
   step (e.g. for `allocate-fifo!`); `apply-payment!` is the
   standalone wrapper.

   Optional `:tempid-suffix` — appended to the
   `:payment-application` tempid (default `\"\"`); pass a distinct
   suffix per application when several outputs compose into one
   process tx-data."
  [db {:keys [payment invoice amount commodity applied-by-uid
              applied-at strategy reason reason-note supporting-doc
              tempid-suffix write-off-amount write-off-account]
       :or {strategy :cherry-pick tempid-suffix ""}}]
  (when-not payment        (throw (ex-info ":payment required" {})))
  (when-not invoice        (throw (ex-info ":invoice required" {})))
  (when-not amount         (throw (ex-info ":amount required" {})))
  (when-not commodity      (throw (ex-info ":commodity required" {})))
  (when-not applied-by-uid (throw (ex-info ":applied-by-uid required" {})))
  (let [invoice-eid (resolve-invoice db invoice)
        _ (when-not invoice-eid
            (throw (ex-info "Invoice not found" {:spec invoice})))
        inv (pull-invoice-min db invoice-eid)
        ;; note 198 FX-C: a :payment-application nets its :amount against the
        ;; invoice gross number-for-number, so a payment in a DIFFERENT commodity
        ;; than the invoice currency would silently corrupt the open-item figure
        ;; (a 1,200 USD payment on a 1,000 EUR invoice reading open = -200). Until
        ;; cross-currency settlement (convert + realized-FX GL, FX-A/FX-B) lands
        ;; on this path, refuse the mismatch loudly — honouring the docstring
        ;; contract and matching reconciliation.cljc's DEFER-don't-corrupt stance.
        inv-currency (:kontor.invoice/currency inv)
        pay-symbol   (commodity-symbol db commodity)
        _ (when (and inv-currency pay-symbol (not= inv-currency pay-symbol))
            (throw (ex-info (str "apply-payment!: payment commodity " pay-symbol
                                 " ≠ invoice currency " inv-currency
                                 " — cross-currency settlement is not yet supported on the "
                                 "payment-application path (it would need a realized-FX posting). "
                                 "Settle in the invoice currency, or convert the payment first.")
                            {:type              :payment-application/commodity-mismatch
                             :invoice-currency  inv-currency
                             :payment-commodity pay-symbol
                             :invoice           invoice-eid})))
        current-status (:kontor.invoice/status inv)
        applied-at (or applied-at (java.util.Date.))
        app-tempid (str "pay-app" tempid-suffix)
        app-row (cond-> {:db/id app-tempid
                         :kontor.payment-application/payment payment
                         :kontor.payment-application/invoice invoice-eid
                         :kontor.payment-application/amount amount
                         :kontor.payment-application/commodity commodity
                         :kontor.payment-application/applied-at applied-at
                         :kontor.payment-application/applied-by-uid applied-by-uid
                         :kontor.payment-application/strategy strategy}
                  reason         (assoc :kontor.payment-application/reason reason)
                  reason-note    (assoc :kontor.payment-application/reason-note reason-note)
                  supporting-doc (assoc :kontor.payment-application/supporting-doc supporting-doc)
                  ;; note 198 r3-recon-b — payment-linked write-off/tolerance leg
                  write-off-amount  (assoc :kontor.payment-application/write-off-amount
                                           write-off-amount)
                  write-off-account (assoc :kontor.payment-application/write-off-account
                                           write-off-account))
        already-applied (applied-amount-of-invoice db invoice-eid nil)
        gross (gross-of-invoice db invoice-eid)
        open-after (.subtract ^java.math.BigDecimal gross
                              ^java.math.BigDecimal
                              (-> ^java.math.BigDecimal already-applied
                                  (.add ^java.math.BigDecimal amount)
                                  (.add ^java.math.BigDecimal (or write-off-amount 0M))))
        next-status (next-status-for-application current-status open-after)
        status-tx (when (and next-status
                             (not= next-status current-status)
                             (sm/legal-transition? db :invoice
                                                   :kontor.invoice/status
                                                   current-status next-status))
                    (sm/record-status-change-tx-data
                     db
                     (cond-> {:entity invoice-eid
                              :entity-type :invoice
                              :facet :kontor.invoice/status
                              :from current-status
                              :to next-status
                              :changed-at applied-at
                              :changed-by-uid applied-by-uid}
                       reason      (assoc :reason reason)
                       reason-note (assoc :reason-note reason-note))))]
    (cond-> [app-row]
      status-tx (into status-tx))))

;; ============================================================================
;; Cross-currency settlement — realized FX (note 198 FX-A / FX-B)
;; ============================================================================
;;
;; `apply-payment!` above is TRACKING-only: it nets an open item, it writes no
;; GL. Settling an invoice in a commodity OTHER than its currency additionally
;; needs a general-ledger entry, because the cash received is worth something
;; different from the receivable's carrying amount and that difference is a
;; REALIZED FX gain/loss belonging in P&L.
;;
;; kontor balances sum-to-zero PER (ledger, commodity), so — unlike Odoo, which
;; balances in company currency with `amount_currency` as a free per-line tag —
;; a single entry cannot mix `Dr Bank 1200 USD / Cr AR 1000 EUR`. The bridge is
;; a POLYMORPHIC currency-clearing account (`:kontor.account/commodity` unset),
;; exactly Beancount's `Equity:Conversions` / GnuCash's Trading Accounts — the
;; right lineage for a kernel that ships a Beancount round-trip (ADR-009). One
;; transaction, five legs, each commodity netting to zero:
;;
;;   Dr  Bank:USD            +1200.00 USD
;;   Cr  FX-Clearing         -1200.00 USD     ; USD nets to 0
;;   Dr  FX-Clearing          +960.00 EUR
;;   Dr  FX-Loss               +40.00 EUR
;;   Cr  Receivable          -1000.00 EUR     ; EUR nets to 0
;;
;; The clearing account deliberately does NOT net to zero in its own
;; commodities — it holds the open currency position (−1200 USD / +960 EUR).
;; That is correct by construction; it is a TECHNICAL account and is excluded
;; from statement presentation by convention (note 198 decision (a)). Translated
;; at the settlement-date rate it is zero; at a later closing rate a spread
;; re-opens, which a consumer wanting IAS 21 monetary revaluation sweeps
;; separately.

(defn- commodity-eid-by-symbol [db sym]
  (:db/id (d/entity db [:kontor.commodity/symbol sym])))

(defn settle-invoice-tx-data
  "Pure tx-data builder for settling an invoice with a cash receipt, booking
   the GL entry AND the `:payment-application` tracking row in one transaction.

   Handles the cross-currency case: when `:commodity` differs from the
   invoice's currency the cash is converted at `:effective-date` and the
   realized FX difference is booked to `:fx-gain-loss-account`, bridged through
   the polymorphic `:fx-clearing-account` (see the commentary above). When the
   commodities match this collapses to the plain two-leg
   `Dr cash / Cr receivable` entry and neither FX account is touched.

   Required opts:
     :invoice              ref/eid/external-id of the invoice.
     :payment              ref or eid of the cash-receipt :transaction (the
                           `:payment-application` back-reference).
     :amount               BigDecimal — the cash actually received.
     :commodity            ref to the :commodity the cash is in.
     :cash-account         account eid the cash lands on (bank/cash).
     :receivable-account   account eid carrying the invoice's open item.
     :journal              journal eid for the settlement transaction.
     :applied-by-uid       ref to :kontor.audit/create-uid.

   Required only when `:commodity` ≠ the invoice currency:
     :fx-provider          an `FxRateProvider` (ADR-072) for the conversion.
     :fx-clearing-account  POLYMORPHIC account eid (no :kontor.account/commodity)
                           bridging the two commodities.
     :fx-gain-loss-account P&L account eid for the realized difference. Distinct
                           from consolidation's `:fx-gain-loss-account`, which
                           books UNREALIZED intragroup translation residuals
                           (IAS 21.45) on the elimination entity — do not share
                           one account between them or group P&L conflates them.

   Optional:
     :settles              BigDecimal in the INVOICE currency — how much of the
                           open balance this clears. Default: the full open
                           amount (the customer paid the contractual amount, so
                           the whole receivable clears and the difference is
                           FX). Pass explicitly for a partial settlement.
     :write-off-amount     BigDecimal in the INVOICE currency — a short
                           payment (rounding, agreed cash discount, bank fee
                           deducted at source, small bad-debt tolerance) closed
                           as PART of this settlement, so the invoice reaches
                           :paid instead of sticking at :partially-paid.
                           Books `Dr :write-off-account / Cr receivable`.
     :write-off-account    account eid the write-off is charged to. Required
                           whenever :write-off-amount is set.
     :effective-date       instant — the settlement date, used for both the
                           transaction and the FX rate. Default: now.
     :rate-type            FX rate-type. Default `:spot` — realized FX is always
                           settled at spot, never a closing/average rate.
     :narration :reason :reason-note :supporting-doc :strategy — as per
                           `apply-payment-tx-data`.

   Returns tx-data (transaction + postings + application row + status change)."
  [db {:keys [invoice payment amount commodity cash-account receivable-account
              fx-provider fx-clearing-account fx-gain-loss-account
              settles effective-date rate-type journal applied-by-uid
              narration write-off-amount write-off-account]
       :or   {rate-type :spot}
       :as   opts}]
  (when-not invoice            (throw (ex-info ":invoice required" {})))
  (when-not amount             (throw (ex-info ":amount required" {})))
  (when-not commodity          (throw (ex-info ":commodity required" {})))
  (when-not cash-account       (throw (ex-info ":cash-account required" {})))
  (when-not receivable-account (throw (ex-info ":receivable-account required" {})))
  (when-not journal            (throw (ex-info ":journal required" {})))
  (let [invoice-eid (or (resolve-invoice db invoice)
                        (throw (ex-info "Invoice not found" {:spec invoice})))
        inv          (pull-invoice-min db invoice-eid)
        ;; note 198 audit HIGH-4. The settlement must LINK to the invoice's GL
        ;; transaction, not merely offset it by amount. Kernel aging
        ;; (`kontor.banking.reconciliation/aging-rows` +
        ;; `open-receivables-by-tx`) computes the open amount by walking
        ;; `[?settler :kontor.transaction/settles ?settled]` and subtracting
        ;; the settler's AR-side legs. Without the ref a fully-settled invoice
        ;; still ages at its full gross while the GL receivable is already
        ;; zero — the open-item subledger and the control account disagree,
        ;; and the direction of the error flips depending on which of the two
        ;; write paths was used.
        ;;
        ;; NB the opts key `:settles` is a BigDecimal AMOUNT, unrelated to the
        ;; `:kontor.transaction/settles` REF built here. That collision is how
        ;; the missing link went unnoticed.
        invoice-tx   (:db/id (:kontor.invoice/transaction
                              (d/pull db [{:kontor.invoice/transaction [:db/id]}]
                                      invoice-eid)))
        inv-currency (:kontor.invoice/currency inv)
        inv-comm     (commodity-eid-by-symbol db inv-currency)
        pay-symbol   (commodity-symbol db commodity)
        settle-date  (or effective-date (java.util.Date.))
        cross?       (and inv-currency pay-symbol (not= inv-currency pay-symbol))
        ;; How much of the open item this clears, in the INVOICE currency.
        settles      (or settles
                         (if cross?
                           (open-amount-of-invoice db invoice-eid)
                           amount))
        ;; The invoice-currency value of the cash actually received.
        cash-in-inv  (if cross?
                       (do (when-not fx-provider
                             (throw (ex-info (str "settle-invoice: cross-currency settlement ("
                                                  pay-symbol " → " inv-currency
                                                  ") requires an :fx-provider")
                                             {:type :settlement/fx-provider-required
                                              :invoice-currency inv-currency
                                              :payment-commodity pay-symbol})))
                           (:amount (fx/convert (money/money amount pay-symbol)
                                                fx-provider
                                                {:to inv-currency
                                                 :at-date settle-date
                                                 :rate-type rate-type})))
                       amount)
        ;; Realized FX: what the receivable carried, less what the cash is
        ;; worth. Positive = loss (debit), negative = gain (credit).
        fx-diff      (.subtract ^java.math.BigDecimal settles
                                ^java.math.BigDecimal cash-in-inv)
        _ (when (and cross? (not (zero? (.signum ^java.math.BigDecimal fx-diff))))
            (when-not fx-gain-loss-account
              (throw (ex-info "settle-invoice: a realized FX difference needs :fx-gain-loss-account"
                              {:type :settlement/fx-account-required :fx-diff fx-diff})))
            (when-not fx-clearing-account
              (throw (ex-info "settle-invoice: cross-currency settlement needs :fx-clearing-account"
                              {:type :settlement/fx-clearing-required}))))
        wo (or write-off-amount 0M)
        _ (when (and (not (zero? (.signum ^java.math.BigDecimal wo)))
                     (nil? write-off-account))
            (throw (ex-info "settle-invoice: :write-off-amount needs :write-off-account"
                            {:type :settlement/write-off-account-required
                             :write-off-amount wo})))
        ;; The receivable clears by cash AND by any write-off leg.
        cleared (.add ^java.math.BigDecimal settles ^java.math.BigDecimal wo)
        wo-leg (when-not (zero? (.signum ^java.math.BigDecimal wo))
                 [{:kontor.posting/account write-off-account
                   :kontor.posting/amount wo
                   :kontor.posting/commodity inv-comm}])
        tx-tempid "settle-tx"
        postings
        (if cross?
          ;; five-leg bridge — each commodity nets to zero on its own
          (cond-> [{:kontor.posting/account cash-account
                    :kontor.posting/amount amount
                    :kontor.posting/commodity commodity}
                   {:kontor.posting/account fx-clearing-account
                    :kontor.posting/amount (.negate ^java.math.BigDecimal amount)
                    :kontor.posting/commodity commodity}
                   {:kontor.posting/account fx-clearing-account
                    :kontor.posting/amount cash-in-inv
                    :kontor.posting/commodity inv-comm}
                   {:kontor.posting/account receivable-account
                    :kontor.posting/amount (.negate ^java.math.BigDecimal cleared)
                    :kontor.posting/commodity inv-comm}]
            (not (zero? (.signum ^java.math.BigDecimal fx-diff)))
            (conj {:kontor.posting/account fx-gain-loss-account
                   :kontor.posting/amount fx-diff
                   :kontor.posting/commodity inv-comm})
            (seq wo-leg) (into wo-leg))
          ;; same-currency — the plain two-leg settlement
          (cond-> [{:kontor.posting/account cash-account
                    :kontor.posting/amount amount
                    :kontor.posting/commodity commodity}
                   {:kontor.posting/account receivable-account
                    :kontor.posting/amount (.negate ^java.math.BigDecimal cleared)
                    :kontor.posting/commodity commodity}]
            (seq wo-leg) (into wo-leg)))
        gl-tx (posting/build-transaction
               {:tx-tempid tx-tempid
                :transaction (cond-> {:kontor.transaction/journal journal
                                      :kontor.transaction/effective-date settle-date
                                      :kontor.transaction/state :posted
                                      :kontor.transaction/posted-at settle-date
                                      :kontor.transaction/narration
                                      (or narration (str "Settlement of invoice "
                                                         (or (:kontor.invoice/external-id inv)
                                                             invoice-eid)))}
                               invoice-tx
                               (assoc :kontor.transaction/settles [invoice-tx]))
                :postings (mapv #(assoc % :kontor.posting/posted-at settle-date) postings)})
        ;; The tracking row is recorded in the INVOICE currency, so
        ;; open-amount-of-invoice nets correctly and reverse-application!
        ;; round-trips without reintroducing the FX-C corruption.
        app-tx (apply-payment-tx-data
                db (merge (select-keys opts [:reason :reason-note :supporting-doc
                                             :strategy :tempid-suffix])
                          (cond-> {:payment payment
                                   :invoice invoice-eid
                                   :amount settles
                                   :commodity inv-comm
                                   :applied-by-uid applied-by-uid
                                   :applied-at settle-date}
                            (not (zero? (.signum ^java.math.BigDecimal wo)))
                            (assoc :write-off-amount wo
                                   :write-off-account write-off-account))))]
    (into (vec gl-tx) app-tx)))

(defn settle-invoice!
  "ADR-068 `!` wrapper for [[settle-invoice-tx-data]] — books the settlement GL
   entry (including any realized FX) and the `:payment-application` row in ONE
   gated transaction, stamped with the settlement date as valid-time.

   See [[settle-invoice-tx-data]] for the options."
  [conn opts]
  (let [settle-date (or (:effective-date opts) (java.util.Date.))
        tx-data (settle-invoice-tx-data (d/db conn)
                                        (assoc opts :effective-date settle-date))]
    (validation/transact-with-validation
     conn (kbt/with-vt tx-data settle-date))))

(declare reverse-application-tx-data)

(defn reverse-application!
  "Replayable reversal of a prior `:payment-application`. Writes a
   new row with `:reversal-of` pointing at the original and the
   negated amount.

   The invoice's status may move backwards (`:paid → :sent` if the
   reversal reopens the full balance, or `:paid → :partially-paid`
   if a previous partial remains).

   Required opts:
     :application-eid  ref/eid of the prior row.
     :applied-by-uid   ref to :kontor.audit/create-uid (whoever's reversing).

   Optional opts:
     :applied-at      instant, default now.
     :reason          keyword.
     :reason-note     string.
     :supporting-doc  ref to :audit-doc.
     :vt-from         instant — override tx-level valid-time
                      (kontor.bitemporal). Default: `:applied-at`.
     :vt-to           instant — optional upper bound."
  [conn {:keys [vt-from vt-to applied-at] :as opts}]
  (let [applied-at (or applied-at (java.util.Date.))
        opts (assoc opts :applied-at applied-at)
        tx-data (reverse-application-tx-data (d/db conn) opts)
        effective-vt-from (or vt-from applied-at)
        final-tx (cond
                   (and effective-vt-from vt-to)
                   (kbt/with-vt tx-data effective-vt-from vt-to)
                   effective-vt-from
                   (kbt/with-vt tx-data effective-vt-from)
                   :else tx-data)]
    (validation/transact-with-validation conn final-tx)))

(defn reverse-application-tx-data
  "Pure tx-data builder for `reverse-application!` (ADR-068)."
  [db {:keys [application-eid applied-by-uid applied-at reason
              reason-note supporting-doc tempid-suffix]
       :or {tempid-suffix ""}}]
  (when-not application-eid (throw (ex-info ":application-eid required" {})))
  (when-not applied-by-uid  (throw (ex-info ":applied-by-uid required" {})))
  (let [original (d/pull db '[* {:kontor.payment-application/invoice [:db/id :kontor.invoice/status]
                                 :kontor.payment-application/payment [:db/id]
                                 :kontor.payment-application/commodity [:db/id]}]
                         application-eid)
        _ (when-not (:db/id original)
            (throw (ex-info "Application not found" {:eid application-eid})))
        invoice-eid (get-in original [:kontor.payment-application/invoice :db/id])
        current-status (get-in original [:kontor.payment-application/invoice :kontor.invoice/status])
        original-amount (:kontor.payment-application/amount original)
        negated (.negate ^java.math.BigDecimal original-amount)
        applied-at (or applied-at (java.util.Date.))
        rev-tempid (str "pay-app-rev" tempid-suffix)
        rev-row (cond-> {:db/id rev-tempid
                         :kontor.payment-application/payment
                         (get-in original [:kontor.payment-application/payment :db/id])
                         :kontor.payment-application/invoice invoice-eid
                         :kontor.payment-application/amount negated
                         :kontor.payment-application/commodity
                         (get-in original [:kontor.payment-application/commodity :db/id])
                         :kontor.payment-application/applied-at applied-at
                         :kontor.payment-application/applied-by-uid applied-by-uid
                         :kontor.payment-application/strategy :reversal
                         :kontor.payment-application/reversal-of application-eid}
                  reason         (assoc :kontor.payment-application/reason reason)
                  reason-note    (assoc :kontor.payment-application/reason-note reason-note)
                  supporting-doc (assoc :kontor.payment-application/supporting-doc supporting-doc))
        ;; Compute new open-after: prior-net + negated.
        prior-applied (applied-amount-of-invoice db invoice-eid nil)
        new-applied (.add ^java.math.BigDecimal prior-applied
                          ^java.math.BigDecimal negated)
        gross (gross-of-invoice db invoice-eid)
        open-after (.subtract ^java.math.BigDecimal gross
                              ^java.math.BigDecimal new-applied)
        next-status (cond
                      (and (= current-status :paid)
                           (pos? (.signum ^java.math.BigDecimal open-after)))
                      (if (zero? (.compareTo ^java.math.BigDecimal new-applied 0M))
                        :sent
                        :partially-paid)

                      (and (= current-status :partially-paid)
                           (zero? (.compareTo ^java.math.BigDecimal
                                   new-applied 0M)))
                      :sent

                      (= current-status :partially-paid) :partially-paid

                      :else nil)
        status-tx (when (and next-status
                             (not= next-status current-status)
                             (sm/legal-transition? db :invoice
                                                   :kontor.invoice/status
                                                   current-status next-status))
                    (sm/record-status-change-tx-data
                     db
                     (cond-> {:entity invoice-eid
                              :entity-type :invoice
                              :facet :kontor.invoice/status
                              :from current-status
                              :to next-status
                              :changed-at applied-at
                              :changed-by-uid applied-by-uid}
                       reason      (assoc :reason reason)
                       reason-note (assoc :reason-note reason-note))))]
    (cond-> [rev-row]
      status-tx (into status-tx))))

;; ============================================================================
;; FIFO bulk allocation
;; ============================================================================

(defn- open-invoices-for-partner
  "List `:sent` and `:partially-paid` invoices for a partner ordered
   by due-date oldest-first (FIFO). Returns vec of
   {:invoice-eid :open-amount :due-date}.

   `:side` selects which leg the partner sits on (note 198 PAY-B):
     :ar (default) — receivables, partner is the `:kontor.invoice/buyer`
     :ap           — vendor bills, partner is the `:kontor.invoice/seller`
   Odoo's register-payment wizard is symmetric across inbound/outbound
   (`account.payment` `payment_type`); this makes kontor's allocator so too.

   `:currency` (ISO symbol string), when supplied, keeps only invoices
   denominated in it — a payment cannot settle a differently-denominated
   invoice 1:1 (note 198 PAY-NEW)."
  [db partner-eid {:keys [as-of-valid exclude-disputed? side currency]
                   :or   {side :ar}}]
  (let [as-of-valid (or as-of-valid (java.util.Date.))
        partner-attr (case side
                       :ar :kontor.invoice/buyer
                       :ap :kontor.invoice/seller
                       (throw (ex-info "open-invoices-for-partner: :side must be :ar or :ap"
                                       {:side side})))
        eids (d/q '[:find [?i ...]
                    :in $ ?p ?partner-attr
                    :where
                    [?i ?partner-attr ?p]
                    (or [?i :kontor.invoice/status :sent]
                        [?i :kontor.invoice/status :partially-paid])]
                  db partner-eid partner-attr)]
    (->> eids
         (map (fn [eid]
                (let [open (open-amount-of-invoice db eid {:as-of-valid as-of-valid})
                      pulled (d/pull db
                                     [:kontor.invoice/currency
                                      {:kontor.invoice/transaction
                                       [:kontor.transaction/due-date]}]
                                     eid)
                      due (or (get-in pulled
                                      [:kontor.invoice/transaction
                                       :kontor.transaction/due-date])
                              (java.util.Date. 0))]
                  {:invoice-eid eid
                   :open-amount open
                   :currency    (:kontor.invoice/currency pulled)
                   :due-date    due})))
         (filter #(pos? (.signum ^java.math.BigDecimal (:open-amount %))))
         ;; PAY-NEW: never net a payment 1:1 against a differently-denominated
         ;; invoice. Cross-currency settlement goes through `settle-invoice!`,
         ;; which converts and books the realized FX.
         (filter #(or (nil? currency) (nil? (:currency %))
                      (= currency (:currency %))))
         ;; note 198 audit H1. `:due-date` ALONE is not a total order, and the
         ;; ties are the common case rather than an edge: the date is read
         ;; from `:kontor.transaction/due-date`, which only `payment-term` and
         ;; `document.invoice` ever write — every invoice booked through a
         ;; `kontor.book` verb has none and falls back to the epoch, so they
         ;; all tie with each other. `allocate-fifo!` then walks this list
         ;; allocating a remainder sequentially, so a partial payment landed
         ;; on an ARBITRARY one of the tied invoices: one reads :paid, another
         ;; stays :sent, and aging and dunning point at the wrong document —
         ;; differently between runs. Same bug class as the DATEV contra pick.
         ;; The eid tie-break makes it total; it is stable within a DB, though
         ;; a re-import that renumbers eids may reorder same-due-date invoices.
         (sort-by (juxt :due-date :invoice-eid))
         vec)))

(defn allocate-fifo!
  "Apply a payment across N open invoices for a partner, oldest-first.

   Required opts:
     :payment         ref/eid to the cash-receipt :transaction.
     :partner         ref/eid to :partner (the payer; matches
                      :kontor.invoice/buyer).
     :total-amount    BigDecimal — the cash to allocate.
     :commodity       ref to :commodity.
     :applied-by-uid  ref to :kontor.audit/create-uid.

   Optional opts:
     :side               `:ar` (default — the partner is the invoice
                         `:buyer`; a customer receipt) or `:ap` (the
                         partner is the `:seller`; a vendor payment
                         allocated across open bills). note 198 PAY-B.
     :applied-at         instant, default now.
     :exclude-disputed?  filter open invoices with open :dispute rows
                         (Stage L companion will define :dispute;
                         kernel ignores until the companion is
                         installed).
     :reason             keyword.

   Returns a vec of `{:invoice-eid :allocated}` records describing
   the allocation. Tx-data is composed and transacted atomically.
   Underflow (cash &gt; sum of open amounts) leaves residual cash in
   the payment — `(unapplied-cash-balance ...)` reports it.

   Overflow (cash &lt; needed) means the oldest invoices get full
   allocation and the next one gets a partial; remaining invoices
   stay :sent/:partially-paid."
  [conn {:keys [payment partner total-amount commodity applied-by-uid
                applied-at exclude-disputed? reason side]
         :or   {side :ar}}]
  (when-not payment        (throw (ex-info ":payment required" {})))
  (when-not partner        (throw (ex-info ":partner required" {})))
  (when-not total-amount   (throw (ex-info ":total-amount required" {})))
  (when-not commodity      (throw (ex-info ":commodity required" {})))
  (when-not applied-by-uid (throw (ex-info ":applied-by-uid required" {})))
  (let [db (d/db conn)
        applied-at (or applied-at (java.util.Date.))
        openers (open-invoices-for-partner db partner
                                           {:exclude-disputed? exclude-disputed?
                                            :as-of-valid applied-at
                                            :side side
                                            :currency (commodity-symbol db commodity)})
        allocations (loop [remaining total-amount
                           candidates openers
                           out []]
                      (if (or (empty? candidates)
                              (zero? (.signum ^java.math.BigDecimal remaining)))
                        out
                        (let [{:keys [invoice-eid open-amount]} (first candidates)
                              alloc (if (>= (.compareTo ^java.math.BigDecimal open-amount
                                                        ^java.math.BigDecimal remaining) 0)
                                      remaining
                                      open-amount)]
                          (recur (.subtract ^java.math.BigDecimal remaining
                                            ^java.math.BigDecimal alloc)
                                 (rest candidates)
                                 (conj out {:invoice-eid invoice-eid
                                            :allocated alloc})))))
        ;; One atomic, gated process across all N applications (ADR-067).
        ;; Each step calls apply-payment-tx-data with a distinct
        ;; :tempid-suffix so the N :payment-application rows compose
        ;; without collision. The speculative db threads each prior
        ;; application's status change, so a multi-app-same-invoice run
        ;; sees the latest open-amount + status from prior steps.
        steps (when (seq allocations)
                (vec
                 (map-indexed
                  (fn [i {:keys [invoice-eid allocated]}]
                    (fn [sdb _ctx]
                      (apply-payment-tx-data
                       sdb {:payment payment
                            :invoice invoice-eid
                            :amount allocated
                            :commodity commodity
                            :applied-by-uid applied-by-uid
                            :applied-at applied-at
                            :strategy :fifo
                            :reason (or reason :fifo-allocation)
                            :tempid-suffix (str "-" i)})))
                  allocations)))]
    (when (seq steps)
      (process/run-process conn {:steps steps :vt-from applied-at}))
    allocations))
