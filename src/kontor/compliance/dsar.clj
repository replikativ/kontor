(ns kontor.compliance.dsar
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
   companion calls `(register-partner-attr! :kontor.collection-case/partner)`
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
            [kontor.compliance.legal-hold :as legal-hold]
            [kontor.workflow.status-machine :as sm]
            [kontor.validation :as validation])
  (:import [java.time ZoneOffset]
           [java.util Date]))

;; ============================================================================
;; Partner-attribute registry
;; ============================================================================

(def ^:private kernel-partner-attrs
  "Partner-referencing attributes the kernel itself ships. Companion
   modules register their own (`:kontor.collection-case/partner`,
   `:kontor.order/bill-to-partner`, `:kontor.person/partner`, …) at load time via
   `register-partner-attr!`."
  #{:kontor.transaction/partner
    :kontor.posting/partner
    :kontor.invoice/buyer
    :kontor.invoice/seller
    :kontor.partner-bank-account/partner
    :kontor.partner-tax-id/partner
    :kontor.partner-tag/partner
    :kontor.partner-merge/duplicate-of
    :kontor.partner-merge/superseded})

(defonce ^{:doc "Atom holding the set of attributes referencing
   :kontor.partner/* that `collect` walks. Seeded with the kernel attrs;
   companions conj their own at load time via
   `register-partner-attr!`."}
  partner-attrs-registry
  (atom kernel-partner-attrs))

(defn register-partner-attr!
  "Register a partner-referencing attribute so `collect` walks it
   (the direct axis). A companion module calls this for each of its
   own :partner-referencing attrs at load time."
  [attr]
  (swap! partner-attrs-registry conj attr))

(defn partner-attrs
  "The current set of registered partner-referencing attributes."
  []
  @partner-attrs-registry)

;; The indirect axis: a great deal of subject data does not reference
;; the :partner directly — it references a :transaction that, in turn,
;; references the subject. A :status-history row's
;; :kontor.status-history/origin-transaction, a companion :payment-application's
;; :kontor.payment-application/payment, … all point at a :transaction. `collect`
;; walks BOTH axes: direct partner-refs, then — from the subject's
;; transactions — every registered tx-referencing attribute. Same
;; companion-extends-the-kernel-registry pattern (P0-1 review fix,
;; research note 32).

(def ^:private kernel-tx-attrs
  "Transaction-referencing attributes the kernel ships. Companions
   register their own (`:kontor.payment-application/payment`,
   `:kontor.asset-event/transaction`, …) via `register-tx-attr!`."
  #{:kontor.status-history/origin-transaction
    :kontor.transaction/reverses})

(defonce ^{:doc "Atom holding the set of attributes referencing
   :transaction that `collect` walks on the indirect axis. Seeded
   with the kernel attrs; companions conj their own at load time."}
  tx-attrs-registry
  (atom kernel-tx-attrs))

(defn register-tx-attr!
  "Register a :transaction-referencing attribute so `collect` walks
   it on the indirect axis (from the subject's transactions outward)."
  [attr]
  (swap! tx-attrs-registry conj attr))

(defn tx-attrs
  "The current set of registered :transaction-referencing attributes."
  []
  @tx-attrs-registry)

;; ============================================================================
;; Extension collectors — companion-side walks the kernel can't express
;; via the (entity → partner) registry (note 86 P1-86-5)
;; ============================================================================
;; Some companion data is reached via a partner-FROM link rather than a
;; partner-TO ref. The canonical case is kontor-hr: `:kontor.partner/person`
;; lives ON the partner pointing AT the person, so the partner-attrs
;; registry (which matches `[?e ?attr <partner-eid>]`) can't see it.
;; Extension collectors close the gap — each is a pure fn
;; `(fn [db partner-eid opts])` returning a map of extension data; the
;; kernel `collect` merges all results under `:extensions <key>`.

(defonce ^{:doc "Atom holding companion extension collectors, keyed by
   the companion module's keyword (e.g. :hr). Each collector is a
   `(fn [db partner-eid opts])` returning a map (the companion's
   contribution to the DSAR bundle) or nil. Companions register at
   `install!` time."}
  extension-collectors-registry
  (atom {}))

(defn register-extension-collector!
  "Register a companion's DSAR extension collector. Idempotent: the
   key replaces any prior collector under the same key (so re-running
   `install!` keeps the latest impl)."
  [k collector-fn]
  (swap! extension-collectors-registry assoc k collector-fn))

(defn extension-collectors
  "The current map of registered extension collectors."
  []
  @extension-collectors-registry)

;; ============================================================================
;; Status-transition + approval-policy seeds
;; ============================================================================

(def status-transition-seeds
  "ADR-034 :status-transition rows for the :kontor.dsar-request/state facet."
  [{:kontor.status-transition/entity-type :dsar-request
    :kontor.status-transition/facet :kontor.dsar-request/state
    :kontor.status-transition/from :nil :kontor.status-transition/to :received
    :kontor.status-transition/active true :kontor.status-transition/name "Receive Request"}
   {:kontor.status-transition/entity-type :dsar-request
    :kontor.status-transition/facet :kontor.dsar-request/state
    :kontor.status-transition/from :received :kontor.status-transition/to :verifying-identity
    :kontor.status-transition/active true :kontor.status-transition/name "Begin Identity Verification"}
   {:kontor.status-transition/entity-type :dsar-request
    :kontor.status-transition/facet :kontor.dsar-request/state
    :kontor.status-transition/from :received :kontor.status-transition/to :withdrawn
    :kontor.status-transition/active true :kontor.status-transition/name "Subject Withdrew Request"}
   {:kontor.status-transition/entity-type :dsar-request
    :kontor.status-transition/facet :kontor.dsar-request/state
    :kontor.status-transition/from :received :kontor.status-transition/to :extended
    :kontor.status-transition/active true :kontor.status-transition/name "Extend Deadline (GDPR 60-day)"}
   {:kontor.status-transition/entity-type :dsar-request
    :kontor.status-transition/facet :kontor.dsar-request/state
    :kontor.status-transition/from :verifying-identity :kontor.status-transition/to :in-progress
    :kontor.status-transition/active true :kontor.status-transition/name "Identity Verified"}
   {:kontor.status-transition/entity-type :dsar-request
    :kontor.status-transition/facet :kontor.dsar-request/state
    :kontor.status-transition/from :verifying-identity :kontor.status-transition/to :denied
    :kontor.status-transition/active true :kontor.status-transition/name "Identity Not Verified"}
   {:kontor.status-transition/entity-type :dsar-request
    :kontor.status-transition/facet :kontor.dsar-request/state
    :kontor.status-transition/from :extended :kontor.status-transition/to :in-progress
    :kontor.status-transition/active true :kontor.status-transition/name "Resume After Extension"}
   {:kontor.status-transition/entity-type :dsar-request
    :kontor.status-transition/facet :kontor.dsar-request/state
    :kontor.status-transition/from :in-progress :kontor.status-transition/to :awaiting-legal-review
    :kontor.status-transition/active true :kontor.status-transition/name "Privileged Data — Counsel Review"}
   {:kontor.status-transition/entity-type :dsar-request
    :kontor.status-transition/facet :kontor.dsar-request/state
    :kontor.status-transition/from :in-progress :kontor.status-transition/to :fulfilled
    :kontor.status-transition/active true :kontor.status-transition/name "Fulfill Request"}
   {:kontor.status-transition/entity-type :dsar-request
    :kontor.status-transition/facet :kontor.dsar-request/state
    :kontor.status-transition/from :in-progress :kontor.status-transition/to :denied
    :kontor.status-transition/active true :kontor.status-transition/name "Deny Request"}
   {:kontor.status-transition/entity-type :dsar-request
    :kontor.status-transition/facet :kontor.dsar-request/state
    :kontor.status-transition/from :awaiting-legal-review :kontor.status-transition/to :fulfilled
    :kontor.status-transition/active true :kontor.status-transition/name "Fulfill After Legal Review"}
   {:kontor.status-transition/entity-type :dsar-request
    :kontor.status-transition/facet :kontor.dsar-request/state
    :kontor.status-transition/from :awaiting-legal-review :kontor.status-transition/to :denied
    :kontor.status-transition/active true :kontor.status-transition/name "Deny After Legal Review"}])

(def approval-policy-seeds
  "ADR-038 :approval-policy rows. Fulfillment and denial are the
   governed edges — fulfilling needs SoD (intake person ≠ fulfiller)
   + the produced bundle; denying needs the written rationale."
  (vec
   (concat
    ;; * → :fulfilled — no-self-approval + the produced bundle
    (for [from [:in-progress :awaiting-legal-review]
          rule [:no-self-approval :requires-supporting-doc]]
      {:kontor.approval-policy/entity-type     :dsar-request
       :kontor.approval-policy/facet           :kontor.dsar-request/state
       :kontor.approval-policy/transition-from from
       :kontor.approval-policy/transition-to   :fulfilled
       :kontor.approval-policy/rule            rule
       :kontor.approval-policy/active          true})
    ;; * → :denied — the written denial rationale
    (for [from [:verifying-identity :in-progress :awaiting-legal-review]
          rule [:requires-supporting-doc :requires-non-empty-reason-note]]
      {:kontor.approval-policy/entity-type     :dsar-request
       :kontor.approval-policy/facet           :kontor.dsar-request/state
       :kontor.approval-policy/transition-from from
       :kontor.approval-policy/transition-to   :denied
       :kontor.approval-policy/rule            rule
       :kontor.approval-policy/active          true}))))

(defn install-seeds!
  "Idempotently transact the :dsar-request status-transition +
   approval-policy seeds. Called from `kontor.core/install-schema!`.
   Guarded with a presence check."
  [conn]
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where
                         [?e :kontor.status-transition/entity-type :dsar-request]]
                       db))]
    (when-not already?
      (d/transact conn (vec (concat status-transition-seeds
                                    approval-policy-seeds))))))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  "Resolve a :dsar-request eid by its :kontor.dsar-request/external-id."
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :kontor.dsar-request/external-id ?xid]]
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
         [?m :kontor.partner-merge/duplicate-of ?canonical]
         [?m :kontor.partner-merge/superseded ?dup]]
       db canonical-eid))

(defn- refs-by-attr
  "For each attr in `attrs`, find every entity `?e` where `[?e attr v]`
   for any v in `vals`; pull each. Returns {attr [pulled-entity …]},
   dropping attrs with no matches."
  [db attrs vals]
  (into {}
        (keep (fn [attr]
                (let [eids (->> vals
                                (mapcat (fn [v]
                                          (d/q '[:find [?e ...]
                                                 :in $ ?attr ?v
                                                 :where [?e ?attr ?v]]
                                               db attr v)))
                                distinct)]
                  (when (seq eids)
                    [attr (mapv #(d/pull db '[*] %) eids)]))))
        attrs))

(defn collect
  "Return everything the DB holds about `partner-eid`, snapshotted at
   `:as-of-tx` (default: current). The bitemporal answer to 'produce
   all data we KNEW about this subject as of date D' — one query
   over one bitemporal datalog DB.

   Returns:
     {:partner             <pulled partner entity>
      :merged-from         [<eid> …]   ; one-level :partner-merge chain
      :references          {<partner-attr> [<pulled entity> …] …}
      :indirect-references {<tx-attr> [<pulled entity> …] …}
      :legal-holds         [<hold-eid> …]  ; active holds covering subject
      :on-legal-hold?      <bool>}

   Two axes (research note 32 P0-1):
   - `:references` — the DIRECT walk: entities referencing the
     subject :partner via a registered `partner-attrs` attribute.
   - `:indirect-references` — the INDIRECT walk: from the subject's
     transactions (anything with `:kontor.transaction/partner` = subject),
     every entity referencing those transactions via a registered
     `tx-attrs` attribute (`:kontor.status-history/origin-transaction`, a
     companion `:kontor.payment-application/payment`, …). A great deal of
     subject data — payment applications, status-history of the
     subject's invoices — references a `:transaction`, not the
     `:partner` directly; omitting it ships an incomplete access
     response.

   Both maps are keyed by the registered attribute — the kernel does
   not hardcode companion entity types; companions register their
   own attrs via `register-partner-attr!` / `register-tx-attr!`.

   `:legal-holds` / `:on-legal-hold?` (ADR-049): held data still
   appears in a DSAR *access* response — the right of access is not
   waived by a hold — but an *erasure* request cannot delete it. The
   consumer's erasure-fulfillment bundles everything, purges only
   the unheld portion (the ADR-050 sweeper would itself refuse held
   data — the invariant is structural), and emits a denial-rationale
   for the held portion.

   Privilege filtering (ADR-051) is consumer-side: when assembling
   the fulfillment bundle, run any included `:audit-doc`s through
   `kontor.compliance.audit-doc/filter-by-privilege` — privileged docs are not
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
        ;; Direct axis — entities referencing a subject :partner.
        refs (refs-by-attr db @partner-attrs-registry subjects)
        ;; Indirect axis — from the subject's transactions outward.
        subject-txs (->> subjects
                         (mapcat (fn [p]
                                   (d/q '[:find [?t ...]
                                          :in $ ?p
                                          :where [?t :kontor.transaction/partner ?p]]
                                        db p)))
                         distinct)
        indirect (if (seq subject-txs)
                   (refs-by-attr db @tx-attrs-registry subject-txs)
                   {})
        ;; Note 86 P1-86-5 — fire each companion's extension collector
        ;; (HR walks partner → :kontor.partner/person → :person → employments;
        ;; future companions can add their own under their key).
        extensions (into {}
                         (keep (fn [[k collector-fn]]
                                 (when-let [result (collector-fn db partner-eid
                                                                 {:as-of-tx as-of-tx})]
                                   [k result])))
                         @extension-collectors-registry)
        holds (legal-hold/holds-covering db partner-eid)]
    (cond-> {:partner             (d/pull db '[*] partner-eid)
             :merged-from         merged-from
             :references          refs
             :indirect-references indirect
             :legal-holds         holds
             :on-legal-hold?      (boolean (seq holds))}
      (seq extensions) (assoc :extensions extensions))))

;; ============================================================================
;; Transactors
;; ============================================================================

(declare file-request-tx-data advance-state-tx-data)

(defn file-request!
  "File a new data-subject-access request. Status nil → :received;
   `:deadline-at` is computed from `:received-at` + `:deadline-days`.

   Required opts:
     :external-id    string (unique)
     :partner        ref to :partner (the subject)
     :kind           keyword (:access | :erasure | :portability | …)
     :received-at    instant (the statutory clock starts here)
     :deadline-days  long (GDPR 30, CCPA 45, LGPD 15, …)
     :changed-by-uid the intake actor (ADR-153; see
                     `file-request-tx-data`)

   Optional:
     :jurisdiction   ref to :country
     :received-via   keyword (:email | :portal | :postal | :api)
     :supporting-doc ref to :audit-doc (the intake form)
     :notes          string
     :vt-from / :vt-to  valid-time bounds (default :vt-from = now)"
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (file-request-tx-data
                        (d/db conn) (assoc opts :received-state-at now))
            (or vt-from now)
            (or vt-to kbt/forever)))))

(defn file-request-tx-data
  "Pure tx-data builder for `file-request!` (ADR-068). Optional
   `:tempid` (default `\"dsar-1\"`) and `:received-state-at` (default
   now) thread cross-step references + the status-history changed-at.

   `:changed-by-uid` — the intake actor — is REQUIRED (ADR-153; it was
   optional through ADR-052). It is stamped as `:kontor.audit/create-uid`,
   and [[approval-policy-seeds]] — installed in EVERY kontor db via
   `kontor.core/install-schema!` — puts `:no-self-approval` on every
   `→ :fulfilled` edge. Since ADR-150 made that rule fail CLOSED on a nil
   creator, a request filed without an intake actor can never be
   fulfilled — and GDPR Art. 12(3) puts a one-month deadline on
   fulfillment, so the omission does not merely inconvenience the
   controller, it manufactures a regulatory breach. Required rather than
   defaulted for the reason any four-eyes anchor must be: a sentinel two
   people can both equal is not a second pair of eyes."
  [db {:keys [external-id partner kind received-at deadline-days
              jurisdiction received-via supporting-doc notes
              changed-by-uid tempid received-state-at]
       :or {tempid "dsar-1"}}]
  (when-not external-id   (throw (ex-info ":external-id required" {})))
  (when-not partner       (throw (ex-info ":partner required" {})))
  (when-not kind          (throw (ex-info ":kind required" {})))
  (when-not received-at   (throw (ex-info ":received-at required" {})))
  (when-not deadline-days (throw (ex-info ":deadline-days required" {})))
  (when-not changed-by-uid
    (throw (ex-info
            (str ":changed-by-uid required (ADR-153) — the intake actor is "
                 "stamped as :kontor.audit/create-uid, which the seeded "
                 ":no-self-approval policy on the → :fulfilled edge compares the "
                 "fulfilling actor against. Without it this request can never be "
                 "fulfilled, and the GDPR Art. 12(3) one-month clock runs anyway.")
            {:type :kontor.dsar-request/intake-actor-required
             :external-id external-id})))
  (let [row (cond-> {:db/id tempid
                     :kontor.dsar-request/external-id external-id
                     :kontor.dsar-request/partner partner
                     :kontor.dsar-request/kind kind
                     :kontor.dsar-request/received-at received-at
                     :kontor.dsar-request/deadline-days deadline-days
                     :kontor.dsar-request/deadline-at (plus-days received-at deadline-days)
                     :kontor.dsar-request/state :received}
              jurisdiction   (assoc :kontor.dsar-request/jurisdiction jurisdiction)
              received-via   (assoc :kontor.dsar-request/received-via received-via)
              supporting-doc (assoc :kontor.dsar-request/supporting-doc supporting-doc)
              notes          (assoc :kontor.dsar-request/notes notes))
        ;; The intake person IS the creator — stamp :kontor.audit/create-uid
        ;; so ADR-038 :no-self-approval can fire on fulfillment (the intake
        ;; person can't also be the fulfiller). UNCONDITIONAL since ADR-153.
        row (assoc row :kontor.audit/create-uid changed-by-uid)
        status-tx (sm/record-status-change-tx-data
                   db
                   {:entity tempid
                    :entity-type :dsar-request
                    :facet :kontor.dsar-request/state
                    :from :nil
                    :to :received
                    :changed-at (or received-state-at (Date.))
                    :changed-by-uid changed-by-uid
                    :reason :dsar-received})]
    (into [row] status-tx)))

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
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (advance-state-tx-data
                        (d/db conn) (assoc opts :changed-at now))
            (or vt-from now)
            (or vt-to kbt/forever)))))

(defn advance-state-tx-data
  "Pure tx-data builder for `advance-state!` (ADR-068)."
  [db {:keys [request to changed-by-uid reason reason-note supporting-doc
              fulfilled-package denied-reason changed-at]}]
  (when-not request        (throw (ex-info ":request required" {})))
  (when-not to             (throw (ex-info ":to required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (let [req-eid (resolve-request db request)
        _ (when-not req-eid
            (throw (ex-info "DSAR request not found" {:spec request})))
        from (:kontor.dsar-request/state (d/pull db [:kontor.dsar-request/state] req-eid))
        now (or changed-at (Date.))
        ;; Side-effect attrs that ride along with specific transitions.
        update (cond-> {:db/id req-eid}
                 (and (= to :in-progress) (= from :verifying-identity))
                 (assoc :kontor.dsar-request/identity-verified-at now)

                 (= to :fulfilled)
                 (assoc :kontor.dsar-request/fulfilled-at now)

                 fulfilled-package (assoc :kontor.dsar-request/fulfilled-package fulfilled-package)
                 denied-reason     (assoc :kontor.dsar-request/denied-reason denied-reason))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity req-eid
                            :entity-type :dsar-request
                            :facet :kontor.dsar-request/state
                            :to to
                            :changed-at now
                            :changed-by-uid changed-by-uid}
                     reason         (assoc :reason reason)
                     reason-note    (assoc :reason-note reason-note)
                     supporting-doc (assoc :supporting-doc supporting-doc)))]
    (into [update] status-tx)))
