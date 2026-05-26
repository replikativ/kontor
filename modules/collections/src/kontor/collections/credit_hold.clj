(ns kontor.collections.credit-hold
  "Per-(partner, entity) credit-hold overlay — ADR-043, ported to the
   status machine per the 2026-05-13 P0-5 review fix.

   Composes with ADR-039's `:kontor.partner/credit-status` scalar as the
   default. The resolver `credit-status-for` walks:

     1. Any active `:credit-hold` row for (partner, entity) at
        `:as-of-valid` — i.e. `:placed-at ≤ as-of` AND
        `:kontor.credit-hold/state == :placed` (or unreleased) at as-of AND
        not yet expired by `:expires-at`.
     2. Otherwise the partner's `:kontor.partner/credit-status` scalar
        (ADR-039 default — `:open | :hold | :review | :closed`).

   This makes single-entity tenants experience zero complexity (they
   never write `:credit-hold` rows; the scalar suffices) while multi-
   entity tenants can place a hold for one subsidiary without
   blocking the same partner across other subsidiaries.

   Also hosts the live credit-utilization query (ADR-043 §Per-entity
   credit-hold overlay). Live = computed from current `:posting`s
   against AR for the (partner, entity), bitemporally; never a
   cached snapshot. SAP / D365 cache the value; kontor reads it."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.payment-application :as papp]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation]))

;; ============================================================================
;; Queries
;; ============================================================================

(defn- expired?
  "True iff `:expires-at` is set and ≤ as-of-ms (auto-release)."
  [h as-of-ms]
  (when-let [exp (:kontor.credit-hold/expires-at h)]
    (<= (.getTime ^java.util.Date exp) as-of-ms)))

(defn- state-at
  "Resolve `:kontor.credit-hold/state` at valid-time `as-of` via the
   bitemporal resolver — answers 'what was the state, as known now,
   at as-of-valid'. Returns the keyword or nil if the entity didn't
   exist yet at as-of."
  [db hold-eid ^java.util.Date as-of]
  (:kontor.credit-hold/state
   (d/pull (d/valid-at db as-of) [:kontor.credit-hold/state] hold-eid)))

(defn active-holds-for
  "Pulled `:credit-hold` rows that are active at `:as-of-valid`
   (default: now) for (partner, entity).

   Active = placed-at ≤ as-of AND `:kontor.credit-hold/state` at as-of-valid
   is `:placed` AND not yet expired (per ADR-043 :expires-at auto-
   release; P1-8 fix)."
  ([db {:keys [partner entity as-of-valid]}]
   (let [as-of (or as-of-valid (java.util.Date.))
         as-of-ms (.getTime ^java.util.Date as-of)
         rows (d/q '[:find [?h ...]
                     :in $ ?p ?e ?as-of-ms
                     :where
                     [?h :kontor.credit-hold/partner ?p]
                     [?h :kontor.credit-hold/entity ?e]
                     [?h :kontor.credit-hold/placed-at ?placed]
                     [(.getTime ^java.util.Date ?placed) ?placed-ms]
                     [(<= ?placed-ms ?as-of-ms)]]
                   db partner entity as-of-ms)]
     (->> rows
          (map #(d/pull db '[*] %))
          (filter #(= :placed (state-at db (:db/id %) as-of)))
          (remove #(expired? % as-of-ms))
          (sort-by :kontor.credit-hold/placed-at)
          vec))))

(defn credit-status-for
  "Resolve effective credit-status for a (partner, entity) pair.

   Returns one of:
     :open | :hold | :review | :closed

   Walks per-(partner, entity) `:credit-hold` overlay first; falls
   back to the `:kontor.partner/credit-status` scalar (ADR-039)."
  [db {:keys [partner entity as-of-valid] :as opts}]
  (let [active (active-holds-for db opts)]
    (cond
      ;; An active overlay row forces :hold regardless of partner
      ;; scalar.
      (seq active) :hold

      :else
      (or (:kontor.partner/credit-status (d/pull db [:kontor.partner/credit-status] partner))
          :open))))

(defn current-hold
  "Return the most-recent active hold row for (partner, entity), or
   nil if none active."
  [db opts]
  (last (active-holds-for db opts)))

(defn credit-utilization
  "Live AR + open-invoice balance for (partner, entity) at
   :as-of-valid (default now). The `:kontor.partner/credit-limit` (ADR-039)
   plus this number gives the utilization ratio.

   Returns BigDecimal — sum of open-amounts across all
   :sent/:partially-paid sales invoices for this partner+entity.

   ADR-043 P0-4 fix: numeric query, not categorical. Bitemporal
   via :kontor.payment-application/applied-at. Live = computed from
   :payment-application + :invoice rows; never a cached snapshot."
  ([db {:keys [partner entity as-of-valid]}]
   (let [as-of-valid (or as-of-valid (java.util.Date.))
         eids (d/q '[:find [?i ...]
                     :in $ ?b ?e
                     :where
                     [?i :kontor.invoice/buyer ?b]
                     [?i :kontor.invoice/entity ?e]
                     (or [?i :kontor.invoice/status :sent]
                         [?i :kontor.invoice/status :partially-paid])]
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

   ADR-043 P0-3 fix. Bitemporal via :kontor.payment-application/applied-at
   and :kontor.transaction/effective-date (per ADR-048 the kernel valid-time
   anchor is :tx/valid-from on the writing tx, which equals
   :kontor.transaction/effective-date for kernel builders)."
  ([db {:keys [partner cash-account-eid as-of-valid commodity-eid]}]
   (when-not partner          (throw (ex-info ":partner required" {})))
   (when-not cash-account-eid (throw (ex-info ":cash-account-eid required
   — chart-of-accounts coupling; consumer passes the bank-clearing
   or AR-cash account eid." {})))
   (let [as-of-valid (or as-of-valid (java.util.Date.))
         as-of-ms (.getTime ^java.util.Date as-of-valid)
         ;; Sum :kontor.posting/amount on cash account for this partner up
         ;; to as-of-valid, anchored on :kontor.transaction/effective-date
         ;; (equal to the writing tx's :tx/valid-from per ADR-048).
         received (or (if commodity-eid
                        (d/q '[:find (sum ?amt) .
                               :in $ ?acct ?p ?c ?as-of-ms
                               :where
                               [?ps :kontor.posting/account ?acct]
                               [?ps :kontor.posting/amount ?amt]
                               [?ps :kontor.posting/partner ?p]
                               [?ps :kontor.posting/commodity ?c]
                               [?ps :kontor.posting/transaction ?tx]
                               [?tx :kontor.transaction/effective-date ?eff]
                               [(.getTime ^java.util.Date ?eff) ?eff-ms]
                               [(<= ?eff-ms ?as-of-ms)]]
                             db cash-account-eid partner commodity-eid as-of-ms)
                        (d/q '[:find (sum ?amt) .
                               :in $ ?acct ?p ?as-of-ms
                               :where
                               [?ps :kontor.posting/account ?acct]
                               [?ps :kontor.posting/amount ?amt]
                               [?ps :kontor.posting/partner ?p]
                               [?ps :kontor.posting/transaction ?tx]
                               [?tx :kontor.transaction/effective-date ?eff]
                               [(.getTime ^java.util.Date ?eff) ?eff-ms]
                               [(<= ?eff-ms ?as-of-ms)]]
                             db cash-account-eid partner as-of-ms))
                      0M)
         applied (or (d/q '[:find (sum ?amt) .
                            :with ?app
                            :in $ ?p ?cutoff-ms
                            :where
                            [?app :kontor.payment-application/payment ?tx]
                            [?tx :kontor.transaction/partner ?p]
                            [?app :kontor.payment-application/amount ?amt]
                            [?app :kontor.payment-application/applied-at ?when]
                            [(.getTime ^java.util.Date ?when) ?when-ms]
                            [(<= ?when-ms ?cutoff-ms)]]
                          db partner as-of-ms)
                     0M)]
     (.subtract ^java.math.BigDecimal received
                ^java.math.BigDecimal applied))))

;; ============================================================================
;; Transactors
;; ============================================================================

(declare place-hold-tx-data release-hold-tx-data)

(defn place-hold!
  "Place a per-(partner, entity) credit hold.

   Status machine: nil → :placed. Writes a :status-history row +
   stamps :tx/valid-from on the writing tx (default = now; override
   via :vt-from for backdated placements).

   Required opts:
     :partner          ref/eid
     :entity           ref/eid
     :reason-code      keyword
     :placed-by-uid    ref to :kontor.audit/create-uid

   Optional opts:
     :approver-uid    ref to :kontor.audit/create-uid (distinct from :placed-by-
                      uid for ADR-038 :no-self-approval enforcement
                      at the policy layer)
     :expires-at      instant (auto-release boundary; nil = manual)
     :notes           string
     :supporting-doc  ref to :audit-doc
     :placed-at       instant the hold was placed (default = now).
                      The active-check compares `:placed-at` against
                      a query's `:as-of-valid`, so a backdated
                      placement should set this alongside `:vt-from`.
     :vt-from         valid-time start (default = now)
     :vt-to           valid-time end (default = kbt/forever)

   The pure tx-data builder is `place-hold-tx-data`."
  [conn {:keys [vt-from vt-to placed-at] :as opts}]
  (let [placed-at (or placed-at (java.util.Date.))]
    (validation/transact-with-validation
     conn (kbt/with-vt (place-hold-tx-data
                        (d/db conn) (assoc opts :placed-at placed-at))
                       (or vt-from placed-at)
                       (or vt-to kbt/forever)))))

(defn place-hold-tx-data
  "Pure tx-data builder for `place-hold!` (ADR-068). Optional
   `:tempid` (default `\"hold-1\"`) and `:placed-at` (default now)."
  [db {:keys [partner entity reason-code placed-by-uid approver-uid
              expires-at notes supporting-doc tempid placed-at]
       :or {tempid "hold-1"}}]
  (when-not partner       (throw (ex-info ":partner required" {})))
  (when-not entity        (throw (ex-info ":entity required" {})))
  (when-not reason-code   (throw (ex-info ":reason-code required" {})))
  (when-not placed-by-uid (throw (ex-info ":placed-by-uid required" {})))
  (let [placed-at (or placed-at (java.util.Date.))
        row (cond-> {:db/id tempid
                     :kontor.credit-hold/partner partner
                     :kontor.credit-hold/entity entity
                     :kontor.credit-hold/reason-code reason-code
                     :kontor.credit-hold/placed-at placed-at
                     :kontor.credit-hold/placed-by-uid placed-by-uid
                     :kontor.credit-hold/state :placed}
              approver-uid   (assoc :kontor.credit-hold/approver-uid approver-uid)
              expires-at     (assoc :kontor.credit-hold/expires-at expires-at)
              notes          (assoc :kontor.credit-hold/notes notes)
              supporting-doc (assoc :kontor.credit-hold/supporting-doc supporting-doc))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity tempid
                            :entity-type :credit-hold
                            :facet :kontor.credit-hold/state
                            :from :nil
                            :to :placed
                            :changed-at placed-at
                            :changed-by-uid placed-by-uid
                            :reason reason-code}
                     supporting-doc (assoc :supporting-doc supporting-doc)))]
    (into [row] status-tx)))

(defn release-hold!
  "Release a specific `:credit-hold` row. Status machine: :placed →
   :released. The row stays in the DB for audit; who/when/why is on
   the :status-history row driving the transition.

   Required opts:
     :hold-eid          the :credit-hold eid (resolve via
                        `current-hold` or `active-holds-for`)
     :released-by-uid   ref to :kontor.audit/create-uid (recorded as
                        :kontor.status-history/changed-by-uid)

   Optional:
     :reason           transition reason keyword (default :hold-released)
     :reason-note      free-text
     :notes            update the hold row's notes (denorm)
     :supporting-doc   ref to :audit-doc
     :vt-from / :vt-to valid-time bounds (default :vt-from = now)

   The pure tx-data builder is `release-hold-tx-data`."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (release-hold-tx-data
                        (d/db conn) (assoc opts :released-at now))
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

(defn release-hold-tx-data
  "Pure tx-data builder for `release-hold!` (ADR-068)."
  [db {:keys [hold-eid released-by-uid reason reason-note notes
              supporting-doc released-at]}]
  (when-not hold-eid         (throw (ex-info ":hold-eid required" {})))
  (when-not released-by-uid  (throw (ex-info ":released-by-uid required" {})))
  (let [now (or released-at (java.util.Date.))
        update (cond-> {:db/id hold-eid}
                 notes          (assoc :kontor.credit-hold/notes notes)
                 supporting-doc (assoc :kontor.credit-hold/supporting-doc
                                       supporting-doc))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity hold-eid
                            :entity-type :credit-hold
                            :facet :kontor.credit-hold/state
                            :to :released
                            :changed-at now
                            :changed-by-uid released-by-uid
                            :reason (or reason :hold-released)}
                     reason-note    (assoc :reason-note reason-note)
                     supporting-doc (assoc :supporting-doc supporting-doc)))]
    (into [update] status-tx)))

(defn release-all-for-tx-data
  "Pure tx-data builder for `release-all-for!` (ADR-068). Resolves
   every active hold for (partner, entity) against `db` and produces
   ONE tx-data vector concatenating each release's
   `release-hold-tx-data`. Lets a consumer atomically release every
   hold (typically when a customer's account is reinstated) inside a
   `kontor.process` step list — the loop-of-`!`-calls form in
   `release-all-for!` cannot give that atomicity.
   Returns `[]` when no holds are active."
  [db {:keys [released-by-uid notes released-at] :as opts}]
  (when-not released-by-uid
    (throw (ex-info ":released-by-uid required" {})))
  (let [holds (active-holds-for db opts)
        rel-at (or released-at (java.util.Date.))]
    (vec (mapcat (fn [h]
                   (release-hold-tx-data
                    db {:hold-eid (:db/id h)
                        :released-by-uid released-by-uid
                        :notes notes
                        :released-at rel-at}))
                 holds))))

(defn release-all-for!
  "Convenience: release every active hold for (partner, entity). Uses
   one `release-hold!` call per hold — each is its own tx (the
   per-hold gate validation runs independently). Use
   `release-all-for-tx-data` instead when atomicity is required.

   Returns the count of holds released. The pure tx-data builder is
   `release-all-for-tx-data` (ADR-068)."
  [conn {:keys [partner entity released-by-uid notes]
         :as opts}]
  (let [holds (active-holds-for (d/db conn) opts)]
    (doseq [h holds]
      (release-hold! conn
                     {:hold-eid (:db/id h)
                      :released-by-uid released-by-uid
                      :notes notes}))
    (count holds)))
