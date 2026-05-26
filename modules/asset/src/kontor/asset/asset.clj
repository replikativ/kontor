(ns kontor.asset.asset
  "kontor-asset register + lifecycle transactors — ADR-053.

   GL-free: ADR-053 records the :asset register, the :asset-event
   append-only mid-life facts, and drives the :asset/status lifecycle
   status machine. ADR-054 adds the per-(asset, ledger) depreciation
   books, the depreciation runner, and the GL postings — in ADR-053
   the caller supplies :origin-transaction / event-transaction refs.

   Lifecycle (`:asset/status` facet, ADR-034):
     nil → :planned → :in-service → :fully-depreciated
                                  / :disposed / :transferred

   Governance: `:in-service → :disposed` is approval-policy-gated
   (ADR-038 — :requires-supporting-doc + :no-self-approval, via the
   status machine). `impair!` / `revalue!` keep the asset
   :in-service, so they use inline required-arg guards
   (:justification + :reason-note) — the same explicit-guard pattern
   `legal-hold/place!` and `retention/define-policy!` use.

   :asset-event entities are append-only BY CONVENTION — every
   transactor here only ever creates one, never retracts or edits —
   but this is not sealing-enforced (review-after market-pain P0-1).

   `revise-useful-life!` / `record-addition!` record the cross-book
   `:asset-event` only — they do NOT touch the per-(asset, ledger)
   depreciation books. To APPLY a revision to a book's schedule, call
   `kontor.asset.depreciation/revise-book!` per book (per-book because
   an HGB life ≠ an AfA-Tabelle life).

   Every `!` business-write transactor in this file follows ADR-068:
   a pure `xxx-tx-data [db opts]` builder returns the tx-data vector,
   and the `xxx!` wrapper applies `kbt/with-vt` and routes through
   `kontor.validation/transact-with-validation`."
  (:require [clojure.string]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-code
  "Resolve an :asset eid by its :asset/code."
  [db code]
  (d/q '[:find ?e . :in $ ?c
         :where [?e :asset/code ?c]]
       db code))

(defn resolve-asset
  "Coerce `spec` to an :asset eid (string → by-code lookup)."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-code db spec)
    :else          spec))

(defn pull-asset
  "Pull an :asset (by code or eid) with its class + status."
  [db spec]
  (when-let [eid (resolve-asset db spec)]
    (d/pull db
            '[* {:asset/class [:asset-class/code :asset-class/name]}]
            eid)))

;; ============================================================================
;; Acquisition
;; ============================================================================

(defn acquire-tx-data
  "Pure tx-data builder for `acquire!` — the entity-map construction
   without the `d/transact`/`with-vt` wrapper. Use as a
   `kontor.process` step (ADR-067); `acquire!` is the standalone
   wrapper. Takes the same opts as `acquire!` minus `:vt-from` /
   `:vt-to` (valid-time is owned by the caller / `run-process`),
   plus `:tempid` — the asset entity's tempid (default `\"asset-1\"`);
   pass a stable string when a later process step must reference the
   asset (e.g. `commence!`'s ROU asset).

   Optional `:changed-at` (default now) injects the timestamp so the
   builder is deterministic from `(db, opts)`."
  [db {:keys [code name class acquisition-cost acquisition-commodity
              acquisition-date in-service? in-service-date salvage-value
              asset-account accumulated-account expense-account
              cost-center entity parent origin-transaction origin-document
              serial-number location note changed-by-uid tempid changed-at]
       :or   {tempid "asset-1"}}]
  (when-not code                  (throw (ex-info ":code required" {})))
  (when-not name                  (throw (ex-info ":name required" {})))
  (when-not class                 (throw (ex-info ":class required" {})))
  (when-not acquisition-cost      (throw (ex-info ":acquisition-cost required" {})))
  (when-not acquisition-commodity (throw (ex-info ":acquisition-commodity required" {})))
  (when-not acquisition-date      (throw (ex-info ":acquisition-date required" {})))
  (let [target-state (if in-service? :in-service :planned)
        isd (when in-service? (or in-service-date acquisition-date))
        asset-tempid tempid
        row (cond-> {:db/id asset-tempid
                     :asset/code code
                     :asset/name name
                     :asset/class class
                     :asset/acquisition-cost acquisition-cost
                     :asset/acquisition-commodity acquisition-commodity
                     :asset/acquisition-date acquisition-date
                     :asset/salvage-value (or salvage-value 0M)
                     :asset/status target-state}
              isd                 (assoc :asset/in-service-date isd)
              asset-account       (assoc :asset/asset-account asset-account)
              accumulated-account (assoc :asset/accumulated-account accumulated-account)
              expense-account     (assoc :asset/expense-account expense-account)
              cost-center         (assoc :asset/cost-center cost-center)
              entity              (assoc :asset/entity entity)
              parent              (assoc :asset/parent parent)
              origin-transaction  (assoc :asset/origin-transaction origin-transaction)
              origin-document     (assoc :asset/origin-document origin-document)
              serial-number       (assoc :asset/serial-number serial-number)
              location            (assoc :asset/location location)
              note                (assoc :asset/note note)
              ;; The acquirer IS the creator — stamp :kontor.audit/create-uid so
              ;; ADR-038 :no-self-approval can fire on disposal.
              changed-by-uid      (assoc :kontor.audit/create-uid changed-by-uid))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity asset-tempid
                            :entity-type :asset
                            :facet :asset/status
                            :from :nil
                            :to target-state
                            :changed-at (or changed-at (java.util.Date.))
                            :reason :asset-acquired}
                     changed-by-uid (assoc :changed-by-uid changed-by-uid)
                     origin-document (assoc :supporting-doc origin-document)))]
    (into [row] status-tx)))

(defn acquire!
  "Create an :asset. Status nil → :planned (default) or nil →
   :in-service when `:in-service?` is true. The acquirer is stamped
   as `:kontor.audit/create-uid` so the ADR-038 :no-self-approval rule can fire
   on a later disposal.

   Required opts:
     :code                  string (unique)
     :name                  string
     :class                 ref/eid of :asset-class
     :acquisition-cost      bigdec
     :acquisition-commodity ref/eid of :commodity
     :acquisition-date      instant

   Optional opts:
     :in-service?           boolean (default false → :planned)
     :in-service-date       instant (default = :acquisition-date when
                            :in-service? true)
     :salvage-value         bigdec (default 0)
     :asset-account / :accumulated-account / :expense-account
                            ref/eid of :account (for ADR-054 postings)
     :cost-center           ref/eid of :analytic-account
     :entity                ref/eid of :entity (ADR-031 scope)
     :parent                ref/eid of :asset (componentisation)
     :origin-transaction    ref/eid of :transaction
     :origin-document       ref/eid of :audit-doc
     :serial-number / :location / :note  strings
     :changed-by-uid        ref/eid of :kontor.audit/create-uid
     :vt-from / :vt-to      valid-time bounds (default :vt-from =
                            :acquisition-date)

   The pure tx-data builder is `acquire-tx-data` (ADR-067 / ADR-068)."
  [conn {:keys [acquisition-date vt-from vt-to] :as opts}]
  (let [now (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (acquire-tx-data (d/db conn) (assoc opts :changed-at now))
            (or vt-from acquisition-date)
            (or vt-to kbt/forever)))))

(declare place-in-service-tx-data)

(defn place-in-service!
  "Transition a :planned asset to :in-service, stamping
   `:asset/in-service-date`. The depreciation clock starts here
   (ADR-054).

   Required opts: :asset (code or eid), :in-service-date,
                  :changed-by-uid.
   Optional: :reason-note, :supporting-doc, :vt-from, :vt-to.

   The pure tx-data builder is `place-in-service-tx-data` (ADR-068)."
  [conn {:keys [in-service-date vt-from vt-to] :as opts}]
  (let [now (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (place-in-service-tx-data (d/db conn)
                                                 (assoc opts :changed-at now))
            (or vt-from in-service-date)
            (or vt-to kbt/forever)))))

(defn place-in-service-tx-data
  "Pure tx-data builder for `place-in-service!` (ADR-068). Returns the
   tx-data vector (no `d/transact`, no `kbt/with-vt`). Use as a
   `kontor.process` step. `:changed-at` (default now) feeds the
   :status-history row."
  [db {:keys [asset in-service-date changed-by-uid reason-note
              supporting-doc changed-at]}]
  (when-not in-service-date (throw (ex-info ":in-service-date required" {})))
  (when-not changed-by-uid  (throw (ex-info ":changed-by-uid required" {})))
  (let [eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity eid
                            :entity-type :asset
                            :facet :asset/status
                            :from :planned
                            :to :in-service
                            :changed-at (or changed-at (java.util.Date.))
                            :changed-by-uid changed-by-uid
                            :reason :asset-placed-in-service}
                     reason-note    (assoc :reason-note reason-note)
                     supporting-doc (assoc :supporting-doc supporting-doc)))]
    (into [{:db/id eid
            :asset/in-service-date in-service-date}]
          status-tx)))

;; ============================================================================
;; Lifecycle-changing events — dispose / transfer (drive :asset/status)
;; ============================================================================

(defn- record-event-tx-data
  "Pure tx-data builder for a single :asset-event entity map (ADR-068).
   Returns a one-element vector with the entity-map ready for transact.
   No `d/transact`, no `kbt/with-vt`. Optional `:tempid` (default
   `\"asset-event-1\"`) so callers can compose multiple event builders
   without tempid collisions."
  [_db asset-eid {:keys [kind date amount commodity new-useful-life-months
                         transaction justification note tempid]
                  :or   {tempid "asset-event-1"}}]
  [(cond-> {:db/id tempid
            :asset-event/asset asset-eid
            :asset-event/kind kind
            :asset-event/date date}
     amount                 (assoc :asset-event/amount amount)
     commodity              (assoc :asset-event/commodity commodity)
     new-useful-life-months (assoc :asset-event/new-useful-life-months
                                   new-useful-life-months)
     transaction            (assoc :asset-event/transaction transaction)
     justification          (assoc :asset-event/justification justification)
     note                   (assoc :asset-event/note note))])

(declare dispose-tx-data)

(defn dispose!
  "Dispose of an asset — write-off, sale, or scrap. Records an
   append-only `:asset-event :disposal` AND drives `:asset/status`
   :in-service → :disposed (or :fully-depreciated → :disposed), so
   the ADR-038 approval policy fires (`:requires-supporting-doc` +
   `:no-self-approval`).

   Required opts:
     :asset           code or eid
     :date            instant (disposal date)
     :changed-by-uid  ref to :kontor.audit/create-uid (must differ from the
                      acquirer per :no-self-approval)
     :justification   ref to :audit-doc (the disposal authorisation)

   Optional:
     :proceeds        bigdec — sale proceeds (recorded as
                      :asset-event/amount; 0 for a scrap/write-off)
     :commodity       ref to :commodity
     :transaction     ref to :transaction (the GL reversal entry —
                      ADR-054's posting helper builds it)
     :reason-note     free-text
     :vt-from / :vt-to  valid-time bounds (default :vt-from = :date)

   The pure tx-data builder is `dispose-tx-data` (ADR-068)."
  [conn {:keys [date vt-from vt-to] :as opts}]
  (validation/transact-with-validation
   conn (kbt/with-vt (dispose-tx-data (d/db conn) opts)
          (or vt-from date)
          (or vt-to kbt/forever))))

(defn dispose-tx-data
  "Pure tx-data builder for `dispose!` (ADR-068). Returns the tx-data
   vector (no `d/transact`, no `kbt/with-vt`). Reads the current
   :asset/status from `db` to feed the status-machine `:from` slot.
   Optional `:event-tempid` (default `\"asset-event-1\"`) for
   composition."
  [db {:keys [asset date changed-by-uid justification proceeds commodity
              transaction reason-note event-tempid]
       :or   {event-tempid "asset-event-1"}}]
  (when-not date           (throw (ex-info ":date required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not justification  (throw (ex-info ":justification required (disposal authorisation)" {})))
  (let [eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))
        from (:asset/status (d/pull db [:asset/status] eid))
        event-tx (record-event-tx-data db eid
                                       {:kind :disposal :date date
                                        :amount (or proceeds 0M)
                                        :commodity commodity
                                        :transaction transaction
                                        :justification justification
                                        :note reason-note
                                        :tempid event-tempid})
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity eid
                            :entity-type :asset
                            :facet :asset/status
                            :from from
                            :to :disposed
                            :changed-at date
                            :changed-by-uid changed-by-uid
                            :reason :asset-disposed
                            :supporting-doc justification}
                     reason-note (assoc :reason-note reason-note)))]
    (into (vec event-tx) status-tx)))

(declare transfer-tx-data)

(defn transfer!
  "Transfer an asset to another legal entity (ADR-031). Records an
   `:asset-event :transfer` AND drives `:asset/status` :in-service →
   :transferred.

   Required opts: :asset, :date, :changed-by-uid, :to-entity.
   Optional: :justification, :reason-note, :transaction,
             :vt-from, :vt-to.

   The pure tx-data builder is `transfer-tx-data` (ADR-068)."
  [conn {:keys [date vt-from vt-to] :as opts}]
  (validation/transact-with-validation
   conn (kbt/with-vt (transfer-tx-data (d/db conn) opts)
          (or vt-from date)
          (or vt-to kbt/forever))))

(defn transfer-tx-data
  "Pure tx-data builder for `transfer!` (ADR-068). Returns the tx-data
   vector (no `d/transact`, no `kbt/with-vt`). Optional
   `:event-tempid` (default `\"asset-event-1\"`)."
  [db {:keys [asset date changed-by-uid to-entity justification
              reason-note transaction event-tempid]
       :or   {event-tempid "asset-event-1"}}]
  (when-not date           (throw (ex-info ":date required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not to-entity      (throw (ex-info ":to-entity required" {})))
  (let [eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))
        event-tx (record-event-tx-data db eid
                                       {:kind :transfer :date date
                                        :transaction transaction
                                        :justification justification
                                        :note reason-note
                                        :tempid event-tempid})
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity eid
                            :entity-type :asset
                            :facet :asset/status
                            :from :in-service
                            :to :transferred
                            :changed-at date
                            :changed-by-uid changed-by-uid
                            :reason :asset-transferred}
                     reason-note    (assoc :reason-note reason-note)
                     justification  (assoc :supporting-doc justification)))]
    (into (conj (vec event-tx)
                {:db/id eid :asset/entity to-entity})
          status-tx)))

;; ============================================================================
;; In-service events — impair / revalue / revise-useful-life / addition
;; (no :asset/status change; inline required-arg guards)
;; ============================================================================

(declare impair-tx-data)

(defn impair!
  "Record an impairment (IAS 36 / HGB §253 außerplanmäßige
   Abschreibung). The asset stays :in-service. Inline guards:
   `:justification` (the impairment-test memo) and `:reason-note`
   are required.

   Required opts:
     :asset         code or eid
     :date          instant
     :amount        bigdec — the impairment loss
     :commodity     ref to :commodity
     :justification ref to :audit-doc (impairment-test memo)
     :reason-note   free-text

   Optional: :transaction (the GL write-down — ADR-054 builds it),
             :vt-from, :vt-to.

   The pure tx-data builder is `impair-tx-data` (ADR-068)."
  [conn {:keys [date vt-from vt-to] :as opts}]
  (validation/transact-with-validation
   conn (kbt/with-vt (impair-tx-data (d/db conn) opts)
          (or vt-from date)
          (or vt-to kbt/forever))))

(defn impair-tx-data
  "Pure tx-data builder for `impair!` (ADR-068). Returns the tx-data
   vector (no `d/transact`, no `kbt/with-vt`). Optional
   `:event-tempid` (default `\"asset-event-1\"`)."
  [db {:keys [asset date amount commodity justification reason-note
              transaction event-tempid]
       :or   {event-tempid "asset-event-1"}}]
  (when-not date          (throw (ex-info ":date required" {})))
  (when-not amount        (throw (ex-info ":amount required" {})))
  (when-not commodity     (throw (ex-info ":commodity required" {})))
  (when-not justification (throw (ex-info ":justification required (impairment-test memo)" {})))
  (when (clojure.string/blank? (or reason-note ""))
    (throw (ex-info ":reason-note required" {})))
  (let [eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))]
    (record-event-tx-data db eid {:kind :impairment :date date :amount amount
                                  :commodity commodity :transaction transaction
                                  :justification justification :note reason-note
                                  :tempid event-tempid})))

(declare revalue-tx-data)

(defn revalue!
  "Record a revaluation (IAS 16 revaluation model). The asset stays
   :in-service. Inline guards: `:justification` (the valuation
   report) and `:reason-note` are required.

   Required opts:
     :asset         code or eid
     :date          instant
     :amount        bigdec — the revaluation delta (positive =
                    upward revaluation surplus → OCI; negative =
                    downward, to the extent of prior surplus)
     :commodity     ref to :commodity
     :justification ref to :audit-doc (valuation report)
     :reason-note   free-text

   Optional: :transaction, :vt-from, :vt-to.

   The pure tx-data builder is `revalue-tx-data` (ADR-068)."
  [conn {:keys [date vt-from vt-to] :as opts}]
  (validation/transact-with-validation
   conn (kbt/with-vt (revalue-tx-data (d/db conn) opts)
          (or vt-from date)
          (or vt-to kbt/forever))))

(defn revalue-tx-data
  "Pure tx-data builder for `revalue!` (ADR-068). Returns the tx-data
   vector (no `d/transact`, no `kbt/with-vt`). Optional
   `:event-tempid` (default `\"asset-event-1\"`)."
  [db {:keys [asset date amount commodity justification reason-note
              transaction event-tempid]
       :or   {event-tempid "asset-event-1"}}]
  (when-not date          (throw (ex-info ":date required" {})))
  (when-not amount        (throw (ex-info ":amount required" {})))
  (when-not commodity     (throw (ex-info ":commodity required" {})))
  (when-not justification (throw (ex-info ":justification required (valuation report)" {})))
  (when (clojure.string/blank? (or reason-note ""))
    (throw (ex-info ":reason-note required" {})))
  (let [eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))]
    (record-event-tx-data db eid {:kind :revaluation :date date :amount amount
                                  :commodity commodity :transaction transaction
                                  :justification justification :note reason-note
                                  :tempid event-tempid})))

(declare revise-useful-life-tx-data)

(defn revise-useful-life!
  "Record an IAS 16 useful-life revision (the annual review). The
   asset stays :in-service.

   This records the cross-book `:asset-event` ONLY. It does NOT
   touch any depreciation book — to apply the revision, call
   `kontor.asset.depreciation/revise-book!` for each affected
   (asset, ledger) book (per-book because an HGB life and an
   AfA-Tabelle life differ); the next `run-depreciation!` then
   re-plans the un-fired tail prospectively.

   Required opts: :asset, :date, :new-useful-life-months,
                  :changed-by-uid.
   Optional: :justification, :reason-note, :vt-from, :vt-to.

   The pure tx-data builder is `revise-useful-life-tx-data` (ADR-068)."
  [conn {:keys [date vt-from vt-to] :as opts}]
  (validation/transact-with-validation
   conn (kbt/with-vt (revise-useful-life-tx-data (d/db conn) opts)
          (or vt-from date)
          (or vt-to kbt/forever))))

(defn revise-useful-life-tx-data
  "Pure tx-data builder for `revise-useful-life!` (ADR-068). Returns
   the tx-data vector (no `d/transact`, no `kbt/with-vt`). Optional
   `:event-tempid` (default `\"asset-event-1\"`)."
  [db {:keys [asset date new-useful-life-months changed-by-uid
              justification reason-note event-tempid]
       :or   {event-tempid "asset-event-1"}}]
  (when-not date                   (throw (ex-info ":date required" {})))
  (when-not new-useful-life-months (throw (ex-info ":new-useful-life-months required" {})))
  (when-not changed-by-uid         (throw (ex-info ":changed-by-uid required" {})))
  (let [eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))]
    (record-event-tx-data db eid {:kind :useful-life-revision :date date
                                  :new-useful-life-months new-useful-life-months
                                  :justification justification :note reason-note
                                  :tempid event-tempid})))

(declare record-addition-tx-data)

(defn record-addition!
  "Record a subsequent capitalised addition (a major improvement
   that extends the asset's value/life — not a repair, which is
   expensed). The asset stays :in-service.

   Like `revise-useful-life!`, this records the cross-book
   `:asset-event` ONLY. To fold the addition into a book's
   depreciable base, call `kontor.asset.depreciation/revise-book!`
   with `:additional-base` for each affected book.

   Required opts: :asset, :date, :amount, :commodity.
   Optional: :transaction, :justification, :reason-note,
             :vt-from, :vt-to.

   The pure tx-data builder is `record-addition-tx-data` (ADR-068)."
  [conn {:keys [date vt-from vt-to] :as opts}]
  (validation/transact-with-validation
   conn (kbt/with-vt (record-addition-tx-data (d/db conn) opts)
          (or vt-from date)
          (or vt-to kbt/forever))))

(defn record-addition-tx-data
  "Pure tx-data builder for `record-addition!` (ADR-068). Returns the
   tx-data vector (no `d/transact`, no `kbt/with-vt`). Optional
   `:event-tempid` (default `\"asset-event-1\"`)."
  [db {:keys [asset date amount commodity transaction justification
              reason-note event-tempid]
       :or   {event-tempid "asset-event-1"}}]
  (when-not date      (throw (ex-info ":date required" {})))
  (when-not amount    (throw (ex-info ":amount required" {})))
  (when-not commodity (throw (ex-info ":commodity required" {})))
  (let [eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))]
    (record-event-tx-data db eid {:kind :addition :date date :amount amount
                                  :commodity commodity :transaction transaction
                                  :justification justification :note reason-note
                                  :tempid event-tempid})))

;; ============================================================================
;; Queries
;; ============================================================================

(defn events-of
  "All :asset-event entities for an asset, ordered by :asset-event/date."
  [db asset-spec]
  (when-let [eid (resolve-asset db asset-spec)]
    (->> (d/q '[:find [?e ...]
                :in $ ?a
                :where [?e :asset-event/asset ?a]]
              db eid)
         (map #(d/pull db '[*] %))
         (sort-by :asset-event/date)
         vec)))

(defn assets-by-status
  "All :asset eids currently in `status`."
  [db status]
  (set (d/q '[:find [?e ...]
              :in $ ?s
              :where [?e :asset/status ?s]]
            db status)))
