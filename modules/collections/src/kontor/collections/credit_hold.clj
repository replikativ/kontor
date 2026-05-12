(ns kontor.collections.credit-hold
  "Per-(partner, entity) credit-hold overlay — ADR-043.

   Composes with ADR-039's `:partner/credit-status` scalar as the
   default. The resolver `credit-status-for` walks:

     1. Any active `:credit-hold` row for (partner, entity) at
        `:as-of-valid` — i.e. `:placed-at ≤ as-of` and (no
        `:released-at` OR `:released-at > as-of`).
     2. Otherwise the partner's `:partner/credit-status` scalar
        (ADR-039 default — `:open | :hold | :review | :closed`).

   This makes single-entity tenants experience zero complexity (they
   never write `:credit-hold` rows; the scalar suffices) while multi-
   entity tenants can place a hold for one subsidiary without
   blocking the same partner across other subsidiaries.

   Mirrors the org-override pattern of `:status-transition/applies-
   to-org` (status_machine.clj:71-86).

   Also hosts the live credit-utilization query (ADR-043 §Per-entity
   credit-hold overlay). Live = computed from current `:posting`s
   against AR for the (partner, entity), bitemporally; never a
   cached snapshot. SAP / D365 cache the value; kontor reads it."
  (:require [datahike.api :as d]
            [kontor.payment-application :as papp]))

;; ============================================================================
;; Queries
;; ============================================================================

(defn- expired?
  "True iff `:expires-at` is set and ≤ as-of-ms (auto-release)."
  [h as-of-ms]
  (when-let [exp (:credit-hold/expires-at h)]
    (<= (.getTime ^java.util.Date exp) as-of-ms)))

(defn- released?
  "True iff `:released-at` is set and ≤ as-of-ms (manual release)."
  [h as-of-ms]
  (when-let [rel (:credit-hold/released-at h)]
    (<= (.getTime ^java.util.Date rel) as-of-ms)))

(defn active-holds-for
  "Pulled `:credit-hold` rows that are active at `:as-of-valid`
   (default: now) for (partner, entity).

   Active = placed-at ≤ as-of AND not yet released AND not yet
   expired (per ADR-043 :expires-at auto-release; P1-8 fix)."
  ([db {:keys [partner entity as-of-valid]}]
   (let [as-of (or as-of-valid (java.util.Date.))
         as-of-ms (.getTime ^java.util.Date as-of)
         rows (d/q '[:find [?h ...]
                     :in $ ?p ?e ?as-of-ms
                     :where
                     [?h :credit-hold/partner ?p]
                     [?h :credit-hold/entity ?e]
                     [?h :credit-hold/placed-at ?placed]
                     [(.getTime ^java.util.Date ?placed) ?placed-ms]
                     [(<= ?placed-ms ?as-of-ms)]]
                   db partner entity as-of-ms)]
     (->> rows
          (map #(d/pull db '[*] %))
          (remove #(or (released? % as-of-ms)
                       (expired? % as-of-ms)))
          (sort-by :credit-hold/placed-at)
          vec))))

(defn credit-status-for
  "Resolve effective credit-status for a (partner, entity) pair.

   Returns one of:
     :open | :hold | :review | :closed

   Walks per-(partner, entity) `:credit-hold` overlay first; falls
   back to the `:partner/credit-status` scalar (ADR-039)."
  [db {:keys [partner entity as-of-valid] :as opts}]
  (let [active (active-holds-for db opts)]
    (cond
      ;; An active overlay row forces :hold regardless of partner
      ;; scalar.
      (seq active) :hold

      :else
      (or (:partner/credit-status (d/pull db [:partner/credit-status] partner))
          :open))))

(defn current-hold
  "Return the most-recent active hold row for (partner, entity), or
   nil if none active."
  [db opts]
  (last (active-holds-for db opts)))

(defn credit-utilization
  "Live AR + open-invoice balance for (partner, entity) at
   :as-of-valid (default now). The `:partner/credit-limit` (ADR-039)
   plus this number gives the utilization ratio.

   Returns BigDecimal — sum of open-amounts across all
   :sent/:partially-paid sales invoices for this partner+entity.

   ADR-043 P0-4 fix: numeric query, not categorical. Bitemporal
   via :payment-application/applied-at. Live = computed from
   :payment-application + :invoice rows; never a cached snapshot."
  ([db {:keys [partner entity as-of-valid]}]
   (let [as-of-valid (or as-of-valid (java.util.Date.))
         eids (d/q '[:find [?i ...]
                     :in $ ?b ?e
                     :where
                     [?i :invoice/buyer ?b]
                     [?i :invoice/entity ?e]
                     (or [?i :invoice/status :sent]
                         [?i :invoice/status :partially-paid])]
                   db partner entity)]
     (reduce (fn [^java.math.BigDecimal acc eid]
               (let [o (papp/open-amount-of-invoice
                        db eid {:as-of-valid as-of-valid})]
                 (.add acc ^java.math.BigDecimal (or o 0M))))
             0M eids))))

(defn unapplied-cash-balance
  "Pending-application cash for partner at :as-of-valid.

   Computed kernel-aligned: read postings on the cash-receipt
   accounts for this partner. Caller passes the cash-account-eid
   (typically the bank-clearing or AR-cash account; depends on
   chart). Sum of (positive cash receipts) minus sum of (applied
   payment-applications) = unapplied residual.

   Required opts:
     :partner            partner ref/eid
     :cash-account-eid   account eid for cash-receipt postings
                         (chart-dependent; caller's responsibility)

   Optional:
     :as-of-valid        instant cutoff (default now)
     :commodity-eid      filter postings on this commodity (default
                         no filter; sums across all commodities and
                         the caller's job to interpret if mixed)

   Returns BigDecimal. Positive = remittance sitting in suspense.

   ADR-043 P0-3 fix. Bitemporal via :payment-application/applied-at
   and :posting/valid-from (when set)."
  ([db {:keys [partner cash-account-eid as-of-valid commodity-eid]}]
   (when-not partner          (throw (ex-info ":partner required" {})))
   (when-not cash-account-eid (throw (ex-info ":cash-account-eid required
   — chart-of-accounts coupling; consumer passes the bank-clearing
   or AR-cash account eid." {})))
   (let [as-of-valid (or as-of-valid (java.util.Date.))
         as-of-ms (.getTime ^java.util.Date as-of-valid)
         ;; Sum :posting/amount on cash account for this partner
         ;; up to as-of-valid (using :posting/valid-from when set,
         ;; otherwise the parent :transaction/effective-date).
         received (or (if commodity-eid
                        (d/q '[:find (sum ?amt) .
                               :in $ ?acct ?p ?c ?as-of-ms
                               :where
                               [?ps :posting/account ?acct]
                               [?ps :posting/amount ?amt]
                               [?ps :posting/partner ?p]
                               [?ps :posting/commodity ?c]
                               [?ps :posting/transaction ?tx]
                               [?tx :transaction/effective-date ?eff]
                               [(.getTime ^java.util.Date ?eff) ?eff-ms]
                               [(<= ?eff-ms ?as-of-ms)]]
                             db cash-account-eid partner commodity-eid as-of-ms)
                        (d/q '[:find (sum ?amt) .
                               :in $ ?acct ?p ?as-of-ms
                               :where
                               [?ps :posting/account ?acct]
                               [?ps :posting/amount ?amt]
                               [?ps :posting/partner ?p]
                               [?ps :posting/transaction ?tx]
                               [?tx :transaction/effective-date ?eff]
                               [(.getTime ^java.util.Date ?eff) ?eff-ms]
                               [(<= ?eff-ms ?as-of-ms)]]
                             db cash-account-eid partner as-of-ms))
                      0M)
         applied (or (d/q '[:find (sum ?amt) .
                            :with ?app
                            :in $ ?p ?cutoff-ms
                            :where
                            [?app :payment-application/payment ?tx]
                            [?tx :transaction/partner ?p]
                            [?app :payment-application/amount ?amt]
                            [?app :payment-application/applied-at ?when]
                            [(.getTime ^java.util.Date ?when) ?when-ms]
                            [(<= ?when-ms ?cutoff-ms)]]
                          db partner as-of-ms)
                     0M)]
     (.subtract ^java.math.BigDecimal received
                ^java.math.BigDecimal applied))))

;; ============================================================================
;; Transactors
;; ============================================================================

(defn place-hold!
  "Place a per-(partner, entity) credit hold.

   Required opts:
     :partner          ref/eid
     :entity           ref/eid
     :reason-code      keyword
     :placed-by-uid    ref to :create/uid

   Optional opts:
     :approver-uid    ref to :create/uid (distinct from :placed-by-
                      uid for ADR-038 :no-self-approval enforcement
                      at the policy layer)
     :expires-at      instant (auto-release boundary; nil = manual)
     :notes           string
     :supporting-doc  ref to :audit-doc"
  [conn {:keys [partner entity reason-code placed-by-uid approver-uid
                expires-at notes supporting-doc]}]
  (when-not partner       (throw (ex-info ":partner required" {})))
  (when-not entity        (throw (ex-info ":entity required" {})))
  (when-not reason-code   (throw (ex-info ":reason-code required" {})))
  (when-not placed-by-uid (throw (ex-info ":placed-by-uid required" {})))
  (let [placed-at (java.util.Date.)
        row (cond-> {:credit-hold/partner partner
                     :credit-hold/entity entity
                     :credit-hold/reason-code reason-code
                     :credit-hold/placed-at placed-at
                     :credit-hold/placed-by-uid placed-by-uid}
              approver-uid   (assoc :credit-hold/approver-uid approver-uid)
              expires-at     (assoc :credit-hold/expires-at expires-at)
              notes          (assoc :credit-hold/notes notes)
              supporting-doc (assoc :credit-hold/supporting-doc supporting-doc))]
    (d/transact conn [row])))

(defn release-hold!
  "Release a specific `:credit-hold` row. Records :released-at +
   :released-by-uid; the row stays in the DB for audit.

   Required opts:
     :hold-eid         the :credit-hold eid (resolve via
                       `current-hold` or `active-holds-for`)
     :released-by-uid  ref to :create/uid

   Optional:
     :notes            string
     :supporting-doc   ref to :audit-doc"
  [conn {:keys [hold-eid released-by-uid notes supporting-doc]}]
  (when-not hold-eid         (throw (ex-info ":hold-eid required" {})))
  (when-not released-by-uid  (throw (ex-info ":released-by-uid required" {})))
  (let [update (cond-> {:db/id hold-eid
                        :credit-hold/released-at (java.util.Date.)
                        :credit-hold/released-by-uid released-by-uid}
                 notes          (assoc :credit-hold/notes notes)
                 supporting-doc (assoc :credit-hold/supporting-doc
                                       supporting-doc))]
    (d/transact conn [update])))

(defn release-all-for!
  "Convenience: release every active hold for (partner, entity)."
  [conn {:keys [partner entity released-by-uid notes]
         :as opts}]
  (let [holds (active-holds-for (d/db conn) opts)]
    (doseq [h holds]
      (release-hold! conn
                     {:hold-eid (:db/id h)
                      :released-by-uid released-by-uid
                      :notes notes}))
    (count holds)))
