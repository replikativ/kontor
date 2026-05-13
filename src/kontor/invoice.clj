(ns kontor.invoice
  "Kernel-side invoice lifecycle.

   Workflow:
     create!     — :draft state, no accounting impact
     send!       — :draft → :sent, creates the accounting transaction
                   via posting-builder (country-supplied; e.g.
                   l10n-de.invoice/posting-builder for SKR04)
     mark-paid!  — :sent → :paid (reconciliation calls this when a
                   bank-line settles the invoice's transaction)
     cancel!     — :sent → :cancelled, creates a reversal transaction
                   pointing at the original via :transaction/reverses

   Totals: caller may supply :invoice/total-net etc. directly, OR
   leave them blank and call `materialize-totals` to derive from
   line items. We don't auto-derive on create — letting the caller
   bring numbers from elsewhere (beleg's existing invoice schema,
   external billing system) avoids round-trip rounding mismatches."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.payment-term :as pt]
            [kontor.posting :as posting])
  (:import [java.util Date]))

;; ============================================================================
;; Total-line computation (from line items)
;; ============================================================================

(defn- line-net
  ^java.math.BigDecimal [{:invoice-line/keys [quantity unit-price]}]
  (.setScale (.multiply (bigdec quantity) (bigdec unit-price))
             2 java.math.RoundingMode/HALF_EVEN))

(defn- line-vat
  ^java.math.BigDecimal [{:invoice-line/keys [vat-rate] :as line}]
  (.setScale (.multiply (line-net line)
                        (.divide (bigdec vat-rate)
                                 (bigdec 100)
                                 6 java.math.RoundingMode/HALF_EVEN))
             2 java.math.RoundingMode/HALF_EVEN))

(defn materialize-totals
  "Compute :invoice/total-net + :total-vat + :total-gross from the
   invoice's line items, and return a map ready to merge onto the
   invoice. Pass to d/transact alongside the original create."
  [lines]
  (let [net (reduce #(.add ^java.math.BigDecimal %1 (line-net %2)) 0M lines)
        vat (reduce #(.add ^java.math.BigDecimal %1 (line-vat %2)) 0M lines)]
    {:invoice/total-net   net
     :invoice/total-vat   vat
     :invoice/total-gross (.add net vat)}))

;; ============================================================================
;; create!
;; ============================================================================

(defn create!
  "Create a draft invoice + its line items. `invoice-map` shape:

     {:invoice/external-id   String  ; required
      :invoice/issue-date    Date    ; required
      :invoice/seller        eid     ; partner ref
      :invoice/buyer         eid     ; partner ref
      :invoice/payment-term  eid     ; payment-term ref (optional)
      :invoice/currency      String  ; default \"EUR\"
      :invoice/lines         [<line-map> …]  ; required, ≥1
      :invoice/notes         [String]
      :invoice/buyer-reference String}

   Line maps:
     {:invoice-line/sequence  long
      :invoice-line/name      String
      :invoice-line/description String
      :invoice-line/quantity  bigdec
      :invoice-line/unit-code String
      :invoice-line/unit-price bigdec
      :invoice-line/vat-rate  bigdec
      :invoice-line/vat-category String
      :invoice-line/account   eid (optional override)}

   Auto-applies payment-term to compute :due-date + :discount-deadline
   when :payment-term is set.

   Auto-materializes :total-net / :total-vat / :total-gross from
   line items when not supplied.

   Returns the resulting tx-report; the new invoice's eid is in
   `(:tempids report)` keyed by the issued tempid."
  [conn invoice-map]
  (when-not (:invoice/external-id invoice-map)
    (throw (ex-info ":invoice/external-id is required" {:invoice invoice-map})))
  (when-not (:invoice/issue-date invoice-map)
    (throw (ex-info ":invoice/issue-date is required" {:invoice invoice-map})))
  (when-not (seq (:invoice/lines invoice-map))
    (throw (ex-info ":invoice/lines must be non-empty" {:invoice invoice-map})))
  (let [db (d/db conn)
        lines (:invoice/lines invoice-map)
        tempid (str "invoice-" (:invoice/external-id invoice-map))
        ;; Materialize totals from lines unless caller supplied them.
        totals (cond-> {}
                 (not (:invoice/total-net   invoice-map)) (merge (materialize-totals lines)))
        ;; Apply payment-term if supplied; produces due-date +
        ;; optionally discount-deadline.
        term-frag (when-let [term-eid (:invoice/payment-term invoice-map)]
                    (let [term (d/pull db [:db/id :payment-term/net-days
                                           :payment-term/discount-pct
                                           :payment-term/discount-days]
                                       term-eid)
                          frag (pt/apply-term (:invoice/issue-date invoice-map) term)
                          ;; pt/apply-term returns :transaction/* keys; rename to :invoice/*
                          rename {:transaction/payment-term :invoice/payment-term
                                  :transaction/due-date     :invoice/due-date
                                  :transaction/discount-deadline :invoice/discount-deadline}]
                      (into {} (map (fn [[k v]] [(rename k k) v])) frag)))
        ;; Build line-item entity maps with sequential tempids and a
        ;; :invoice-line/invoice backref to the parent.
        line-tx (mapv (fn [i line]
                        (-> line
                            (dissoc :invoice/lines)
                            (assoc :db/id (str "line-" (:invoice/external-id invoice-map) "-" i)
                                   :invoice-line/invoice tempid
                                   :invoice-line/sequence (or (:invoice-line/sequence line) (inc i)))))
                      (range)
                      lines)
        ;; The invoice entity itself: original map + totals + term + lines refs.
        invoice-tx (-> invoice-map
                       (dissoc :invoice/lines)
                       (assoc :db/id tempid
                              :invoice/status :draft
                              :invoice/currency (or (:invoice/currency invoice-map) "EUR"))
                       (merge totals)
                       (merge term-frag)
                       (assoc :invoice/lines (mapv :db/id line-tx)))]
    (d/transact conn (into [invoice-tx] line-tx))))

;; ============================================================================
;; send!
;; ============================================================================

(defn send!
  "Transition a :draft invoice to :sent. Calls `(posting-builder db
   invoice-pulled)` to produce the accounting tx-data (country
   module's job — knows the chart conventions). Transacts the
   resulting transaction + sets :invoice/transaction backref +
   moves status to :sent.

   `posting-builder` returns a map with :transaction + :postings,
   the same shape posting/build-transaction accepts.

   `opts`: optional `:vt-from` / `:vt-to` to stamp the status-
   transition tx with kontor.bitemporal valid-time. Default
   `:vt-from = now`."
  ([conn invoice-eid posting-builder]
   (send! conn invoice-eid posting-builder nil))
  ([conn invoice-eid posting-builder {:keys [vt-from vt-to]}]
  (let [db (d/db conn)
        inv (d/pull db
                    [:db/id :invoice/external-id :invoice/status
                     :invoice/issue-date :invoice/currency
                     :invoice/total-net :invoice/total-vat :invoice/total-gross
                     :invoice/buyer-reference
                     {:invoice/seller [:db/id :partner/external-id :partner/name
                                       :partner/tax-id]}
                     {:invoice/buyer  [:db/id :partner/external-id :partner/name
                                       :partner/tax-id]}
                     {:invoice/lines [:invoice-line/sequence
                                      :invoice-line/name
                                      :invoice-line/description
                                      :invoice-line/quantity
                                      :invoice-line/unit-code
                                      :invoice-line/unit-price
                                      :invoice-line/vat-rate
                                      :invoice-line/vat-category
                                      {:invoice-line/account [:db/id :account/code]}]}]
                    invoice-eid)]
    (when-not (= :draft (:invoice/status inv))
      (throw (ex-info "Only :draft invoices can be sent"
                      {:invoice-eid invoice-eid
                       :status (:invoice/status inv)})))
    (let [now (Date.)
          ;; Country module supplies the posting tx-data.
          tx-input (posting-builder db inv)
          tx (posting/build-transaction tx-input)
          ;; Resolve the resulting transaction's eid via its external-id
          ;; (set by posting-builder; convention is to mirror
          ;; :invoice/external-id).
          ext-id (or (-> tx-input :transaction :transaction/external-id)
                     (:invoice/external-id inv))
          {:keys [tempids]} (d/transact conn tx)
          tx-eid (or (->> tempids
                          (some (fn [[k v]]
                                  (when (and (string? k)
                                             (re-find (re-pattern (str "^" ext-id)) k))
                                    v))))
                     ;; Fallback: query for the tx by its external-id
                     (d/q '[:find ?t .
                            :in $ ?ext
                            :where [?t :transaction/external-id ?ext]]
                          (d/db conn) ext-id))]
      (d/transact conn
                  (kbt/with-vt
                    [{:db/id invoice-eid
                      :invoice/status :sent
                      :invoice/transaction tx-eid}]
                    (or vt-from now)
                    (or vt-to kbt/forever)))
      {:transaction-eid tx-eid}))))

;; ============================================================================
;; mark-paid! / cancel!
;; ============================================================================

(defn mark-paid!
  "Flip a :sent invoice to :paid. Called by the reconciliation hook
   when a bank-line settles the invoice's transaction; can also be
   invoked directly when payment is recorded out-of-band.

   `opts`: optional `:vt-from` / `:vt-to` to stamp the tx with
   kontor.bitemporal valid-time. Default `:vt-from = now`."
  ([conn invoice-eid]
   (mark-paid! conn invoice-eid nil))
  ([conn invoice-eid {:keys [vt-from vt-to]}]
   (let [now (Date.)]
     (d/transact conn
                 (kbt/with-vt
                   [{:db/id invoice-eid :invoice/status :paid}]
                   (or vt-from now)
                   (or vt-to kbt/forever))))))

(defn cancel!
  "Cancel a :sent or :draft invoice. For :draft, just flips status.
   For :sent, also creates a reversal transaction that negates each
   posting and points at the original via :transaction/reverses
   (kernel ADR-007: never edit posted entries; reverse + re-post).

   `opts`: optional `:vt-from` / `:vt-to` to stamp both the reversal
   tx and the status-flip tx with kontor.bitemporal valid-time.
   Default `:vt-from = now`."
  ([conn invoice-eid]
   (cancel! conn invoice-eid nil))
  ([conn invoice-eid {:keys [vt-from vt-to]}]
  (let [db (d/db conn)
        inv (d/pull db [:invoice/status :invoice/external-id
                        {:invoice/transaction
                         [:db/id :transaction/external-id
                          {:transaction/journal [:db/id]}]}]
                    invoice-eid)
        now (Date.)
        vf  (or vt-from now)
        vt  (or vt-to kbt/forever)]
    (case (:invoice/status inv)
      :draft
      (d/transact conn
                  (kbt/with-vt
                    [{:db/id invoice-eid :invoice/status :cancelled}]
                    vf vt))
      :sent
      (let [original-tx (:invoice/transaction inv)
            original-tx-eid (:db/id original-tx)
            ;; Pull all postings of the original tx and negate.
            postings (->> (d/q '[:find ?p ?acct ?amt ?cur
                                 :in $ ?tx
                                 :where
                                 [?p :posting/transaction ?tx]
                                 [?p :posting/account ?acct]
                                 [?p :posting/amount ?amt]
                                 [?p :posting/commodity ?cur]]
                               db original-tx-eid)
                          (mapv (fn [[_p acct amt cur]]
                                  {:posting/account acct
                                   :posting/amount (.negate ^java.math.BigDecimal amt)
                                   :posting/commodity cur
                                   :posting/posted-at now})))
            reversal-ext (str (:invoice/external-id inv) "-REV")
            reversal-tx (posting/build-transaction
                         {:transaction
                          {:transaction/external-id reversal-ext
                           :transaction/journal (:db/id (:transaction/journal original-tx))
                           :transaction/effective-date now
                           :transaction/narration (str "Reversal of " (:invoice/external-id inv))
                           :transaction/state :posted
                           :transaction/posted-at now
                           :transaction/reverses original-tx-eid}
                          :postings postings})]
        (d/transact conn (kbt/with-vt reversal-tx vf vt))
        (d/transact conn
                    (kbt/with-vt
                      [{:db/id invoice-eid :invoice/status :cancelled}]
                      vf vt)))
      (throw (ex-info "Cannot cancel invoice in current status"
                      {:invoice-eid invoice-eid
                       :status (:invoice/status inv)}))))))

;; ============================================================================
;; Reconciliation hook
;; ============================================================================

(defn flip-paid-on-settlement
  "Call after a reconciliation/commit-match! that settled
   transactions which are linked to invoices. Walks each settled
   transaction's `:invoice/_transaction` backref and marks any
   referenced invoice :paid.

   Idempotent: marking an already-paid invoice is a no-op assertion.

   Typically called from a wrapper around recon/commit-match!
   See `(reconcile-and-mark-paid!)` below for the convenience form.

   `opts`: optional `:vt-from` / `:vt-to` to stamp the tx with
   kontor.bitemporal valid-time (e.g. bank-line's value-date)."
  ([conn settled-tx-eids]
   (flip-paid-on-settlement conn settled-tx-eids nil))
  ([conn settled-tx-eids {:keys [vt-from vt-to]}]
   (let [db (d/db conn)
         invoice-eids (d/q '[:find [?inv ...]
                             :in $ [?tx ...]
                             :where [?inv :invoice/transaction ?tx]]
                           db settled-tx-eids)
         now (Date.)
         vf  (or vt-from now)
         vt  (or vt-to kbt/forever)]
     (when (seq invoice-eids)
       (d/transact conn
                   (kbt/with-vt
                     (mapv (fn [eid]
                             {:db/id eid :invoice/status :paid})
                           invoice-eids)
                     vf vt))))))
