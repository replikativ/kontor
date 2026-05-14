(ns kontor.asset.asset
  "kontor-asset register + lifecycle transactors — ADR-053.

   GL-free: ADR-053 records the :asset register, the :asset-event
   immutable mid-life facts, and drives the :asset/status lifecycle
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
   `legal-hold/place!` and `retention/define-policy!` use."
  (:require [clojure.string]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.status-machine :as sm]))

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

(defn acquire!
  "Create an :asset. Status nil → :planned (default) or nil →
   :in-service when `:in-service?` is true. The acquirer is stamped
   as `:create/uid` so the ADR-038 :no-self-approval rule can fire
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
     :changed-by-uid        ref/eid of :create/uid
     :vt-from / :vt-to      valid-time bounds (default :vt-from =
                            :acquisition-date)"
  [conn {:keys [code name class acquisition-cost acquisition-commodity
                acquisition-date in-service? in-service-date salvage-value
                asset-account accumulated-account expense-account
                cost-center entity parent origin-transaction origin-document
                serial-number location note changed-by-uid vt-from vt-to]}]
  (when-not code                  (throw (ex-info ":code required" {})))
  (when-not name                  (throw (ex-info ":name required" {})))
  (when-not class                 (throw (ex-info ":class required" {})))
  (when-not acquisition-cost      (throw (ex-info ":acquisition-cost required" {})))
  (when-not acquisition-commodity (throw (ex-info ":acquisition-commodity required" {})))
  (when-not acquisition-date      (throw (ex-info ":acquisition-date required" {})))
  (let [db (d/db conn)
        target-state (if in-service? :in-service :planned)
        isd (when in-service? (or in-service-date acquisition-date))
        asset-tempid "asset-1"
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
              ;; The acquirer IS the creator — stamp :create/uid so
              ;; ADR-038 :no-self-approval can fire on disposal.
              changed-by-uid      (assoc :create/uid changed-by-uid))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity asset-tempid
                            :entity-type :asset
                            :facet :asset/status
                            :from :nil
                            :to target-state
                            :changed-at (java.util.Date.)
                            :reason :asset-acquired}
                     changed-by-uid (assoc :changed-by-uid changed-by-uid)
                     origin-document (assoc :supporting-doc origin-document)))]
    (d/transact conn (kbt/with-vt (into [row] status-tx)
                       (or vt-from acquisition-date)
                       (or vt-to kbt/forever)))))

(defn place-in-service!
  "Transition a :planned asset to :in-service, stamping
   `:asset/in-service-date`. The depreciation clock starts here
   (ADR-054).

   Required opts: :asset (code or eid), :in-service-date,
                  :changed-by-uid.
   Optional: :reason-note, :supporting-doc, :vt-from, :vt-to."
  [conn {:keys [asset in-service-date changed-by-uid reason-note
                supporting-doc vt-from vt-to]}]
  (when-not in-service-date (throw (ex-info ":in-service-date required" {})))
  (when-not changed-by-uid  (throw (ex-info ":changed-by-uid required" {})))
  (let [db (d/db conn)
        eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))
        now (java.util.Date.)
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity eid
                            :entity-type :asset
                            :facet :asset/status
                            :from :planned
                            :to :in-service
                            :changed-at now
                            :changed-by-uid changed-by-uid
                            :reason :asset-placed-in-service}
                     reason-note    (assoc :reason-note reason-note)
                     supporting-doc (assoc :supporting-doc supporting-doc)))]
    (d/transact conn (kbt/with-vt (into [{:db/id eid
                                          :asset/in-service-date in-service-date}]
                                        status-tx)
                       (or vt-from in-service-date)
                       (or vt-to kbt/forever)))))

;; ============================================================================
;; Lifecycle-changing events — dispose / transfer (drive :asset/status)
;; ============================================================================

(defn- record-event-tx
  "Build the :asset-event entity map (a tempid the caller can ignore —
   the event is identified by its eid)."
  [asset-eid {:keys [kind date amount commodity new-useful-life-months
                     transaction justification note]}]
  (cond-> {:db/id "asset-event-1"
           :asset-event/asset asset-eid
           :asset-event/kind kind
           :asset-event/date date}
    amount                 (assoc :asset-event/amount amount)
    commodity              (assoc :asset-event/commodity commodity)
    new-useful-life-months (assoc :asset-event/new-useful-life-months
                                  new-useful-life-months)
    transaction            (assoc :asset-event/transaction transaction)
    justification          (assoc :asset-event/justification justification)
    note                   (assoc :asset-event/note note)))

(defn dispose!
  "Dispose of an asset — write-off, sale, or scrap. Records an
   immutable `:asset-event :disposal` AND drives `:asset/status`
   :in-service → :disposed (or :fully-depreciated → :disposed), so
   the ADR-038 approval policy fires (`:requires-supporting-doc` +
   `:no-self-approval`).

   Required opts:
     :asset           code or eid
     :date            instant (disposal date)
     :changed-by-uid  ref to :create/uid (must differ from the
                      acquirer per :no-self-approval)
     :justification   ref to :audit-doc (the disposal authorisation)

   Optional:
     :proceeds        bigdec — sale proceeds (recorded as
                      :asset-event/amount; 0 for a scrap/write-off)
     :commodity       ref to :commodity
     :transaction     ref to :transaction (the GL reversal entry —
                      ADR-054's posting helper builds it)
     :reason-note     free-text
     :vt-from / :vt-to  valid-time bounds (default :vt-from = :date)"
  [conn {:keys [asset date changed-by-uid justification proceeds commodity
                transaction reason-note vt-from vt-to]}]
  (when-not date           (throw (ex-info ":date required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not justification  (throw (ex-info ":justification required (disposal authorisation)" {})))
  (let [db (d/db conn)
        eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))
        from (:asset/status (d/pull db [:asset/status] eid))
        event (record-event-tx eid {:kind :disposal :date date
                                    :amount (or proceeds 0M)
                                    :commodity commodity
                                    :transaction transaction
                                    :justification justification
                                    :note reason-note})
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
    (d/transact conn (kbt/with-vt (into [event] status-tx)
                       (or vt-from date)
                       (or vt-to kbt/forever)))))

(defn transfer!
  "Transfer an asset to another legal entity (ADR-031). Records an
   `:asset-event :transfer` AND drives `:asset/status` :in-service →
   :transferred.

   Required opts: :asset, :date, :changed-by-uid, :to-entity.
   Optional: :justification, :reason-note, :transaction,
             :vt-from, :vt-to."
  [conn {:keys [asset date changed-by-uid to-entity justification
                reason-note transaction vt-from vt-to]}]
  (when-not date           (throw (ex-info ":date required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not to-entity      (throw (ex-info ":to-entity required" {})))
  (let [db (d/db conn)
        eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))
        event (record-event-tx eid {:kind :transfer :date date
                                    :transaction transaction
                                    :justification justification
                                    :note reason-note})
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
    (d/transact conn (kbt/with-vt (into [event
                                         {:db/id eid :asset/entity to-entity}]
                                        status-tx)
                       (or vt-from date)
                       (or vt-to kbt/forever)))))

;; ============================================================================
;; In-service events — impair / revalue / revise-useful-life / addition
;; (no :asset/status change; inline required-arg guards)
;; ============================================================================

(defn- record-event!
  "Transact a single :asset-event, wrapped in kbt/with-vt. Returns
   the tx-report. Used by the in-service event transactors that do
   NOT change :asset/status."
  [conn asset-eid {:keys [date vt-from vt-to] :as spec}]
  (d/transact conn (kbt/with-vt [(record-event-tx asset-eid spec)]
                     (or vt-from date)
                     (or vt-to kbt/forever))))

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
             :vt-from, :vt-to."
  [conn {:keys [asset date amount commodity justification reason-note
                transaction vt-from vt-to]}]
  (when-not date          (throw (ex-info ":date required" {})))
  (when-not amount        (throw (ex-info ":amount required" {})))
  (when-not commodity     (throw (ex-info ":commodity required" {})))
  (when-not justification (throw (ex-info ":justification required (impairment-test memo)" {})))
  (when (clojure.string/blank? (or reason-note ""))
    (throw (ex-info ":reason-note required" {})))
  (let [db (d/db conn)
        eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))]
    (record-event! conn eid {:kind :impairment :date date :amount amount
                             :commodity commodity :transaction transaction
                             :justification justification :note reason-note
                             :vt-from vt-from :vt-to vt-to})))

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

   Optional: :transaction, :vt-from, :vt-to."
  [conn {:keys [asset date amount commodity justification reason-note
                transaction vt-from vt-to]}]
  (when-not date          (throw (ex-info ":date required" {})))
  (when-not amount        (throw (ex-info ":amount required" {})))
  (when-not commodity     (throw (ex-info ":commodity required" {})))
  (when-not justification (throw (ex-info ":justification required (valuation report)" {})))
  (when (clojure.string/blank? (or reason-note ""))
    (throw (ex-info ":reason-note required" {})))
  (let [db (d/db conn)
        eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))]
    (record-event! conn eid {:kind :revaluation :date date :amount amount
                             :commodity commodity :transaction transaction
                             :justification justification :note reason-note
                             :vt-from vt-from :vt-to vt-to})))

(defn revise-useful-life!
  "Record an IAS 16 useful-life revision (the annual review). The
   asset stays :in-service; ADR-054's runner re-plans the remaining
   schedule prospectively from `:new-useful-life-months`.

   Required opts: :asset, :date, :new-useful-life-months,
                  :changed-by-uid.
   Optional: :justification, :reason-note, :vt-from, :vt-to."
  [conn {:keys [asset date new-useful-life-months changed-by-uid
                justification reason-note vt-from vt-to]}]
  (when-not date                   (throw (ex-info ":date required" {})))
  (when-not new-useful-life-months (throw (ex-info ":new-useful-life-months required" {})))
  (when-not changed-by-uid         (throw (ex-info ":changed-by-uid required" {})))
  (let [db (d/db conn)
        eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))]
    (record-event! conn eid {:kind :useful-life-revision :date date
                             :new-useful-life-months new-useful-life-months
                             :justification justification :note reason-note
                             :vt-from vt-from :vt-to vt-to})))

(defn record-addition!
  "Record a subsequent capitalised addition (a major improvement
   that extends the asset's value/life — not a repair, which is
   expensed). The asset stays :in-service; ADR-054's runner re-plans
   the schedule with the increased base.

   Required opts: :asset, :date, :amount, :commodity.
   Optional: :transaction, :justification, :reason-note,
             :vt-from, :vt-to."
  [conn {:keys [asset date amount commodity transaction justification
                reason-note vt-from vt-to]}]
  (when-not date      (throw (ex-info ":date required" {})))
  (when-not amount    (throw (ex-info ":amount required" {})))
  (when-not commodity (throw (ex-info ":commodity required" {})))
  (let [db (d/db conn)
        eid (resolve-asset db asset)
        _ (when-not eid (throw (ex-info "Asset not found" {:spec asset})))]
    (record-event! conn eid {:kind :addition :date date :amount amount
                             :commodity commodity :transaction transaction
                             :justification justification :note reason-note
                             :vt-from vt-from :vt-to vt-to})))

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
