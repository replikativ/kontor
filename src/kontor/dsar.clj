(ns kontor.dsar
  "Data-subject-access requests + the bitemporal `collect` walk
   (ADR-052).

   A `:dsar-request` tracks a GDPR/CCPA/LGPD-style request — the
   subject, the kind (access / erasure / portability / …), the
   statutory deadline, and the status-machine state through to
   fulfillment or denial.

   `collect` is the flagship: it answers 'everything we held about
   this subject as of the request date' in one bitemporal query.
   Other systems struggle with this because subject data is
   scattered across silos; in kontor it is one walk over one
   bitemporal datalog DB.

   ## The companion-registered partner-attribute registry

   Partner references are pervasive AND many live in companion
   modules the kernel does not import. `*partner-attrs*` is an atom
   seeded with the kernel's own partner-referencing attributes; each
   companion calls `(register-partner-attr! :collection-case/partner)`
   etc. at load time. `collect` iterates the registry — the kernel
   ships the mechanism, companions extend it.

   ## Composition

   - `legal-hold/entity-held?` (ADR-049) — held data still appears
     in a DSAR *access* response but cannot be deleted by an
     *erasure* request. `collect` reports `:on-legal-hold?` + the
     covering `:legal-holds`.
   - `audit-doc/visible-to?` (ADR-051) — privileged docs the viewer
     can't see are moved to a `:privileged` side-band, not the main
     `:references` map (the DSAR-vs-privilege edge case).
   - `:partner-merge` (ADR-039) — `collect` walks one level of the
     merge chain so a canonical partner's package includes data
     that referenced its merged-from duplicates.
   - The status machine (ADR-034) + approval policy (ADR-038) govern
     fulfillment and denial."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.legal-hold :as legal-hold]
            [kontor.status-machine :as sm])
  (:import [java.time ZoneOffset]
           [java.util Date]))

;; ============================================================================
;; Partner-attribute registry
;; ============================================================================

(def ^:private kernel-partner-attrs
  "Partner-referencing attributes the kernel itself ships. Companion
   modules register their own (`:collection-case/partner`,
   `:order/bill-to-partner`, `:person/partner`, …) at load time via
   `register-partner-attr!`."
  #{:transaction/partner
    :posting/partner
    :invoice/buyer
    :invoice/seller
    :partner-bank-account/partner
    :partner-tax-id/partner
    :partner-tag/partner
    :partner-merge/duplicate-of
    :partner-merge/superseded})

(defonce ^{:doc "Atom holding the set of attributes referencing
   :partner/* that `collect` walks. Seeded with the kernel attrs;
   companions conj their own at load time via
   `register-partner-attr!`."}
  partner-attrs-registry
  (atom kernel-partner-attrs))

(defn register-partner-attr!
  "Register a partner-referencing attribute so `collect` walks it.
   A companion module calls this for each of its own
   :partner-referencing attrs at load time."
  [attr]
  (swap! partner-attrs-registry conj attr))

(defn partner-attrs
  "The current set of registered partner-referencing attributes."
  []
  @partner-attrs-registry)

;; ============================================================================
;; Status-transition + approval-policy seeds
;; ============================================================================

(def status-transition-seeds
  "ADR-034 :status-transition rows for the :dsar-request/state facet."
  [{:status-transition/entity-type :dsar-request
    :status-transition/facet :dsar-request/state
    :status-transition/from :nil :status-transition/to :received
    :status-transition/active true :status-transition/name "Receive Request"}
   {:status-transition/entity-type :dsar-request
    :status-transition/facet :dsar-request/state
    :status-transition/from :received :status-transition/to :verifying-identity
    :status-transition/active true :status-transition/name "Begin Identity Verification"}
   {:status-transition/entity-type :dsar-request
    :status-transition/facet :dsar-request/state
    :status-transition/from :received :status-transition/to :withdrawn
    :status-transition/active true :status-transition/name "Subject Withdrew Request"}
   {:status-transition/entity-type :dsar-request
    :status-transition/facet :dsar-request/state
    :status-transition/from :received :status-transition/to :extended
    :status-transition/active true :status-transition/name "Extend Deadline (GDPR 60-day)"}
   {:status-transition/entity-type :dsar-request
    :status-transition/facet :dsar-request/state
    :status-transition/from :verifying-identity :status-transition/to :in-progress
    :status-transition/active true :status-transition/name "Identity Verified"}
   {:status-transition/entity-type :dsar-request
    :status-transition/facet :dsar-request/state
    :status-transition/from :verifying-identity :status-transition/to :denied
    :status-transition/active true :status-transition/name "Identity Not Verified"}
   {:status-transition/entity-type :dsar-request
    :status-transition/facet :dsar-request/state
    :status-transition/from :extended :status-transition/to :in-progress
    :status-transition/active true :status-transition/name "Resume After Extension"}
   {:status-transition/entity-type :dsar-request
    :status-transition/facet :dsar-request/state
    :status-transition/from :in-progress :status-transition/to :awaiting-legal-review
    :status-transition/active true :status-transition/name "Privileged Data — Counsel Review"}
   {:status-transition/entity-type :dsar-request
    :status-transition/facet :dsar-request/state
    :status-transition/from :in-progress :status-transition/to :fulfilled
    :status-transition/active true :status-transition/name "Fulfill Request"}
   {:status-transition/entity-type :dsar-request
    :status-transition/facet :dsar-request/state
    :status-transition/from :in-progress :status-transition/to :denied
    :status-transition/active true :status-transition/name "Deny Request"}
   {:status-transition/entity-type :dsar-request
    :status-transition/facet :dsar-request/state
    :status-transition/from :awaiting-legal-review :status-transition/to :fulfilled
    :status-transition/active true :status-transition/name "Fulfill After Legal Review"}
   {:status-transition/entity-type :dsar-request
    :status-transition/facet :dsar-request/state
    :status-transition/from :awaiting-legal-review :status-transition/to :denied
    :status-transition/active true :status-transition/name "Deny After Legal Review"}])

(def approval-policy-seeds
  "ADR-038 :approval-policy rows. Fulfillment and denial are the
   governed edges — fulfilling needs SoD (intake person ≠ fulfiller)
   + the produced bundle; denying needs the written rationale."
  (vec
   (concat
    ;; * → :fulfilled — no-self-approval + the produced bundle
    (for [from [:in-progress :awaiting-legal-review]
          rule [:no-self-approval :requires-supporting-doc]]
      {:approval-policy/entity-type     :dsar-request
       :approval-policy/facet           :dsar-request/state
       :approval-policy/transition-from from
       :approval-policy/transition-to   :fulfilled
       :approval-policy/rule            rule
       :approval-policy/active          true})
    ;; * → :denied — the written denial rationale
    (for [from [:verifying-identity :in-progress :awaiting-legal-review]
          rule [:requires-supporting-doc :requires-non-empty-reason-note]]
      {:approval-policy/entity-type     :dsar-request
       :approval-policy/facet           :dsar-request/state
       :approval-policy/transition-from from
       :approval-policy/transition-to   :denied
       :approval-policy/rule            rule
       :approval-policy/active          true}))))

(defn install-seeds!
  "Idempotently transact the :dsar-request status-transition +
   approval-policy seeds. Called from `kontor.core/install-schema!`.
   Guarded with a presence check."
  [conn]
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where
                         [?e :status-transition/entity-type :dsar-request]]
                       db))]
    (when-not already?
      (d/transact conn (vec (concat status-transition-seeds
                                    approval-policy-seeds))))))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  "Resolve a :dsar-request eid by its :dsar-request/external-id."
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :dsar-request/external-id ?xid]]
       db external-id))

(defn- resolve-request
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-external-id db spec)
    :else          spec))

;; ============================================================================
;; collect — the bitemporal walk
;; ============================================================================

(defn- plus-days
  ^Date [^Date d n]
  (-> (.toInstant d)
      (.atZone ZoneOffset/UTC)
      (.toLocalDate)
      (.plusDays n)
      (.atStartOfDay ZoneOffset/UTC)
      (.toInstant)
      (Date/from)))

(defn- merged-from-partners
  "One level of the :partner-merge chain — the duplicate (merged-FROM)
   partners whose data should be folded into the canonical partner's
   DSAR package."
  [db canonical-eid]
  (d/q '[:find [?dup ...]
         :in $ ?canonical
         :where
         [?m :partner-merge/duplicate-of ?canonical]
         [?m :partner-merge/superseded ?dup]]
       db canonical-eid))

(defn- holds-covering
  "Active legal holds (ADR-049) whose scope includes `partner-eid` —
   via the explicit :scope-eids set or a matching :scope-query."
  [db partner-eid]
  (filterv (fn [hold-eid]
             (or (some? (d/q '[:find ?e .
                               :in $ ?h ?e
                               :where [?h :legal-hold/scope-eids ?e]]
                             db hold-eid partner-eid))
                 (contains? (legal-hold/expand-scope-query db hold-eid)
                            partner-eid)))
           (legal-hold/active-holds db)))

(defn collect
  "Return everything the DB holds about `partner-eid`, snapshotted at
   `:as-of-tx` (default: current). The bitemporal answer to 'produce
   all data we KNEW about this subject as of date D' — one query
   over one bitemporal datalog DB.

   Returns:
     {:partner        <pulled partner entity>
      :merged-from    [<eid> …]      ; one-level :partner-merge chain
      :references     {<partner-attr> [<pulled entity> …] …}
      :legal-holds    [<hold-eid> …] ; active holds covering the subject
      :on-legal-hold? <bool>}

   `:references` is keyed by the registered partner-attr (the kernel
   does not hardcode companion entity types — companions register
   their own attrs via `register-partner-attr!`).

   `:legal-holds` / `:on-legal-hold?` (ADR-049): held data still
   appears in a DSAR *access* response — the right of access is not
   waived by a hold — but an *erasure* request cannot delete it. The
   consumer's erasure-fulfillment bundles everything, purges only
   the unheld portion (the ADR-050 sweeper would itself refuse held
   data — the invariant is structural), and emits a denial-rationale
   for the held portion.

   Privilege filtering (ADR-051) is consumer-side: when assembling
   the fulfillment bundle, run any included `:audit-doc`s through
   `kontor.audit-doc/filter-by-privilege` — privileged docs are not
   auto-included in a subject's package; they need counsel review
   (the `:awaiting-legal-review` state). `collect` returns the raw
   reference walk; the consumer owns bundle assembly.

   opts:
     :as-of-tx          datahike d/as-of snapshot (default: now-db)
     :include-merged?   walk the partner-merge chain (default true)"
  [db partner-eid {:keys [as-of-tx include-merged?]
                   :or {include-merged? true}}]
  (let [db (if as-of-tx (d/as-of db as-of-tx) db)
        merged-from (if include-merged?
                      (vec (merged-from-partners db partner-eid))
                      [])
        subjects (cons partner-eid merged-from)
        ;; For each registered partner-attr, find every entity that
        ;; references any of the subject partners.
        refs
        (into {}
              (keep (fn [attr]
                      (let [eids (->> subjects
                                      (mapcat (fn [subj]
                                                (d/q '[:find [?e ...]
                                                       :in $ ?attr ?p
                                                       :where [?e ?attr ?p]]
                                                     db attr subj)))
                                      distinct)]
                        (when (seq eids)
                          [attr (mapv #(d/pull db '[*] %) eids)]))))
              @partner-attrs-registry)
        holds (holds-covering db partner-eid)]
    {:partner        (d/pull db '[*] partner-eid)
     :merged-from    merged-from
     :references     refs
     :legal-holds    holds
     :on-legal-hold? (boolean (seq holds))}))

;; ============================================================================
;; Transactors
;; ============================================================================

(defn file-request!
  "File a new data-subject-access request. Status nil → :received;
   `:deadline-at` is computed from `:received-at` + `:deadline-days`.

   Required opts:
     :external-id    string (unique)
     :partner        ref to :partner (the subject)
     :kind           keyword (:access | :erasure | :portability | …)
     :received-at    instant (the statutory clock starts here)
     :deadline-days  long (GDPR 30, CCPA 45, LGPD 15, …)

   Optional:
     :jurisdiction   ref to :country
     :received-via   keyword (:email | :portal | :postal | :api)
     :supporting-doc ref to :audit-doc (the intake form)
     :notes          string
     :changed-by-uid ref to :create/uid
     :vt-from / :vt-to  valid-time bounds (default :vt-from = now)"
  [conn {:keys [external-id partner kind received-at deadline-days
                jurisdiction received-via supporting-doc notes
                changed-by-uid vt-from vt-to]}]
  (when-not external-id   (throw (ex-info ":external-id required" {})))
  (when-not partner       (throw (ex-info ":partner required" {})))
  (when-not kind          (throw (ex-info ":kind required" {})))
  (when-not received-at   (throw (ex-info ":received-at required" {})))
  (when-not deadline-days (throw (ex-info ":deadline-days required" {})))
  (let [db (d/db conn)
        now (Date.)
        req-tempid "dsar-1"
        row (cond-> {:db/id req-tempid
                     :dsar-request/external-id external-id
                     :dsar-request/partner partner
                     :dsar-request/kind kind
                     :dsar-request/received-at received-at
                     :dsar-request/deadline-days deadline-days
                     :dsar-request/deadline-at (plus-days received-at deadline-days)
                     :dsar-request/state :received}
              jurisdiction   (assoc :dsar-request/jurisdiction jurisdiction)
              received-via   (assoc :dsar-request/received-via received-via)
              supporting-doc (assoc :dsar-request/supporting-doc supporting-doc)
              notes          (assoc :dsar-request/notes notes)
              ;; The intake person IS the creator — stamp :create/uid
              ;; so ADR-038 :no-self-approval can fire on fulfillment
              ;; (the intake person can't also be the fulfiller).
              changed-by-uid (assoc :create/uid changed-by-uid))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity req-tempid
                            :entity-type :dsar-request
                            :facet :dsar-request/state
                            :from :nil
                            :to :received
                            :changed-at now
                            :reason :dsar-received}
                     changed-by-uid (assoc :changed-by-uid changed-by-uid)))]
    (d/transact conn (kbt/with-vt (into [row] status-tx)
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

(defn advance-state!
  "Drive a `:dsar-request` through the status machine. The generic
   transition transactor — verify-identity / start / extend /
   awaiting-legal-review / fulfill / deny / withdraw all go through
   here. ADR-038 approval policy fires on the `:fulfilled` and
   `:denied` edges.

   Required opts: :request (eid or external-id), :to, :changed-by-uid.

   Optional:
     :reason / :reason-note / :supporting-doc — ADR-038
     :fulfilled-package  ref to :audit-doc — set on :to :fulfilled
     :denied-reason      keyword — set on :to :denied
     :vt-from / :vt-to   valid-time bounds (default :vt-from = now)

   On :to :in-progress from :verifying-identity, stamps
   :identity-verified-at. On :to :fulfilled, stamps :fulfilled-at."
  [conn {:keys [request to changed-by-uid reason reason-note supporting-doc
                fulfilled-package denied-reason vt-from vt-to]}]
  (when-not request        (throw (ex-info ":request required" {})))
  (when-not to             (throw (ex-info ":to required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (let [db (d/db conn)
        req-eid (resolve-request db request)
        _ (when-not req-eid
            (throw (ex-info "DSAR request not found" {:spec request})))
        from (:dsar-request/state (d/pull db [:dsar-request/state] req-eid))
        now (Date.)
        ;; Side-effect attrs that ride along with specific transitions.
        update (cond-> {:db/id req-eid}
                 (and (= to :in-progress) (= from :verifying-identity))
                 (assoc :dsar-request/identity-verified-at now)

                 (= to :fulfilled)
                 (assoc :dsar-request/fulfilled-at now)

                 fulfilled-package (assoc :dsar-request/fulfilled-package fulfilled-package)
                 denied-reason     (assoc :dsar-request/denied-reason denied-reason))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity req-eid
                            :entity-type :dsar-request
                            :facet :dsar-request/state
                            :to to
                            :changed-at now
                            :changed-by-uid changed-by-uid}
                     reason         (assoc :reason reason)
                     reason-note    (assoc :reason-note reason-note)
                     supporting-doc (assoc :supporting-doc supporting-doc)))]
    (d/transact conn (kbt/with-vt (into [update] status-tx)
                       (or vt-from now)
                       (or vt-to kbt/forever)))))
