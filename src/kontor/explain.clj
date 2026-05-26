(ns kontor.explain
  "Graph-walk helpers — \"explain this number\" — ADR-091.

   Substrate-only read namespace. The kernel keeps the audit trail
   (postings → transaction → lifecycle history → supporting audit-docs
   → legal-holds → retention-policy) as a connected graph; the consumer
   asking \"how did this balance come to be?\" should not have to
   re-implement the walk at every consumer.

   `explain-balance` returns the postings that compose a balance.
   `explain-posting` returns the upstream transaction + lifecycle
   history + supporting audit-docs + legal-holds + retention status
   for one posting. Both are bitemporal-aware (`:as-of-tx` /
   `:as-of-valid`) and read-only.

   The result shapes are deliberately plain Clojure maps with eids and
   keyword fields — McComb's data-centric framing says \"data outlives
   applications\"; the explain namespace returns *data*, not Clojure
   records or pretty strings. Consumers format / serialize.

   ## Why a separate namespace (not balance.clj + ledger.clj)

   `kontor.balance` and `kontor.ledger` already walk postings against
   one account. `kontor.explain` adds the cross-namespace walk: from a
   posting *back through* the lifecycle stack (status-history,
   audit-doc, legal-hold, retention-policy). It composes the read-only
   surfaces of those namespaces into one query consumers can pull
   without knowing the schema shape.

   ADR-090 (concept-iri seams) + ADR-091 (this) together close the
   McComb \"explain the number\" loop — a consumer with an XBRL filing
   IRI can walk it back to the postings that built it, via concept-iri
   on accounts/tags, and on to each posting's full provenance."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.ledger :as ledger]
            [kontor.legal-hold :as legal-hold]
            [kontor.retention :as retention])
  (:import [java.util Date]))

(defn- now ^Date [] (Date.))

;; ============================================================================
;; explain-balance — the postings that compose a balance
;; ============================================================================

(defn explain-balance
  "Return `{:account :balance :postings :as-of-valid :as-of-tx}` —
   the account-balance plus the ordered postings that compose it.

   The shape is data-only: callers format / serialize / display. The
   postings vector is exactly what `kontor.ledger/postings-against`
   returns (each entry carries `:posting`, `:transaction`, `:amount`,
   `:valid-from`, `:commodity`, `:narration`, `:partner`, `:tx-state`);
   the balance is the map `{commodity-eid Money}` from
   `kontor.balance/account-balance`.

   The pair is the McComb-aligned \"explain this number\" answer for a
   single account: every datom contributing to the balance is reachable
   from the result.

   Options (all optional):
     :as-of-valid    Date  — default now
     :as-of-tx       Date  — default now
     :include-states set   — default #{:posted}
     :entity         eid   — restrict to one :kontor.posting/entity (ADR-031)
     :order          :asc  | :desc — default :asc on :valid-from

   ADR-091."
  ([conn account-eid] (explain-balance conn account-eid {}))
  ([conn account-eid {:keys [as-of-valid as-of-tx]
                      :as opts}]
   (let [as-of-valid (or as-of-valid (now))
         as-of-tx    (or as-of-tx (now))
         opts        (assoc opts :as-of-valid as-of-valid :as-of-tx as-of-tx)
         balance     (balance/account-balance conn account-eid opts)
         postings    (ledger/postings-against conn account-eid opts)]
     {:account      account-eid
      :balance      balance
      :postings     postings
      :as-of-valid  as-of-valid
      :as-of-tx     as-of-tx})))

;; ============================================================================
;; explain-posting — full provenance for one posting
;; ============================================================================

(defn- pull-posting
  "Pull the headline posting fields. Returns nil iff the posting does
   not exist in `db` (no `:kontor.posting/transaction` ref). Pull pattern is
   deliberately small + kernel-only — consumers wanting richer pulls
   compose with `d/pull` themselves; this is the substrate's
   'explain' default."
  [db posting-eid]
  (let [p (d/pull db
                  [:db/id
                   :kontor.posting/amount
                   :kontor.posting/commodity
                   :kontor.posting/account
                   :kontor.posting/partner
                   :kontor.posting/entity
                   :kontor.posting/ledger
                   :kontor.posting/narration
                   :kontor.posting/posted-at
                   :kontor.posting/transaction
                   :kontor.posting/tax-rep
                   :kontor.posting/tax-base]
                  posting-eid)]
    (when (:kontor.posting/transaction p) p)))

(defn- pull-transaction
  "Pull the transaction shape. The pull pattern captures the kernel-
   level transaction fields *and* the bitemporal :db.valid/from /
   :db.valid/to on the originating commit (which the caller can use to
   answer 'what was the valid-time when this posted?')."
  [db tx-eid]
  (d/pull db
          [:db/id
           :kontor.transaction/external-id
           :kontor.transaction/journal
           :kontor.transaction/effective-date
           :kontor.transaction/narration
           :kontor.transaction/partner
           :kontor.transaction/fiscal-position
           :kontor.transaction/state
           :kontor.transaction/posted-at
           :kontor.transaction/document-type
           :kontor.transaction/clearance-token
           :kontor.transaction/clearance-format
           :db.valid/from
           :db.valid/to]
          tx-eid))

(defn- status-history-for
  "Pull the status-history rows for `entity-eid` ordered by
   :kontor.status-history/changed-at ascending. Each row carries
   {:from :to :facet :changed-at :reason :reason-note :supporting-doc
    :changed-by-uid :origin-transaction}."
  [db entity-eid]
  (->> (d/q '[:find [?h ...]
              :in $ ?e
              :where [?h :kontor.status-history/entity ?e]]
            db entity-eid)
       (mapv (fn [eid]
               (d/pull db
                       [:db/id
                        :kontor.status-history/entity-type
                        :kontor.status-history/facet
                        :kontor.status-history/from
                        :kontor.status-history/to
                        :kontor.status-history/changed-at
                        :kontor.status-history/changed-by-uid
                        :kontor.status-history/reason
                        :kontor.status-history/reason-note
                        :kontor.status-history/supporting-doc
                        :kontor.status-history/origin-transaction]
                       eid)))
       (sort-by :kontor.status-history/changed-at)
       vec))

(defn- audit-docs-for
  "All :audit-doc entities referenced from `entity-eid`'s status-history
   rows via :kontor.status-history/supporting-doc — i.e., the supporting
   evidence chain. Deduped + ordered by :audit-doc/uploaded-at."
  [db entity-eid]
  (->> (d/q '[:find [?d ...]
              :in $ ?e
              :where
              [?h :kontor.status-history/entity ?e]
              [?h :kontor.status-history/supporting-doc ?d]]
            db entity-eid)
       set
       (mapv (fn [eid]
               (d/pull db
                       [:db/id
                        :audit-doc/code
                        :audit-doc/type
                        :audit-doc/title
                        :audit-doc/description
                        :audit-doc/storage-uri
                        :audit-doc/content-hash
                        :audit-doc/uploaded-by-uid
                        :audit-doc/uploaded-at
                        :audit-doc/privilege]
                       eid)))
       (sort-by :audit-doc/uploaded-at)
       vec))

(defn- legal-holds-for
  "Pull legal-hold summary for `entity-eid`s. Returns a vec of pulled
   :legal-hold maps (or empty)."
  [db entity-eids]
  (let [hold-eids (into #{}
                        (mapcat #(legal-hold/holds-covering db %))
                        entity-eids)]
    (->> hold-eids
         (mapv (fn [eid]
                 (d/pull db
                         [:db/id
                          :legal-hold/code
                          :legal-hold/matter
                          :legal-hold/state
                          :legal-hold/placed-at
                          :legal-hold/released-at
                          :legal-hold/placed-by-uid]
                         eid)))
         (sort-by :legal-hold/placed-at)
         vec)))

(defn- retention-summary-for
  "Look up retention policy + deadline + eligibility for `entity-eid`
   if any policy applies to its entity-type. Returns nil when no
   policy matches; otherwise a `{:policy :deadline :eligible?}` map.

   `entity-type` is the keyword used by `kontor.retention/policy-for`
   — usually the bare namespace of the entity's primary attribute
   (`:posting`, `:transaction`, etc.). Wrapped in try/catch so a
   schema gap on retention attrs doesn't break `explain-posting`."
  [db entity-eid entity-type]
  (try
    (let [policy-eid (retention/policy-for db entity-type {})]
      (when policy-eid
        (let [policy   (d/pull db
                               [:db/id
                                :retention-policy/code
                                :retention-policy/applies-to
                                :retention-policy/triggered-by
                                :retention-policy/duration-years
                                :retention-policy/expiry-action
                                :retention-policy/state]
                               policy-eid)
              deadline (retention/retention-deadline db entity-eid policy-eid)
              elig?    (retention/eligible? db entity-eid policy-eid {})]
          {:policy     policy
           :deadline   deadline
           :eligible?  elig?})))
    (catch Throwable _ nil)))

(defn explain-posting
  "Return the full provenance chain for one `:posting` entity.

   Result shape (keys present iff data exists):
     {:posting             pulled-posting-map
      :transaction         pulled-transaction-map
      :status-history      [{:from … :to … :changed-at … …}]
      :audit-docs          [pulled-audit-doc …]   ; from history support-docs
      :legal-holds         [pulled-legal-hold …]  ; active holds covering posting/tx
      :retention           {:policy :deadline :eligible?} or nil
      :origin-transactions [tx-eid …]             ; from status-history's
                                                  ; :origin-transaction back-refs
      :as-of-tx            Date}

   The result is read-only data; the caller decides what to render.

   Options:
     :as-of-tx        Date — pin to a tx-time snapshot (default now)

   Bitemporal note: we do *not* take `:as-of-valid` because explain is
   answering 'where did this come from?'; the contents of the chain
   are the *recorded* facts about this posting. A caller who wants to
   ask 'what did we know about this posting on date X' passes
   :as-of-tx; the kernel's valid-time discipline lives on the
   originating tx's :db.valid/from, which the result already carries.

   ADR-091."
  ([conn posting-eid] (explain-posting conn posting-eid {}))
  ([conn posting-eid {:keys [as-of-tx]
                      :or {as-of-tx (now)}}]
   (let [db        (d/db conn)
         tx-snap   (d/as-of db as-of-tx)
         posting   (pull-posting tx-snap posting-eid)]
     (when posting
       (let [tx-eid    (-> posting :kontor.posting/transaction :db/id)
             tx        (pull-transaction tx-snap tx-eid)
             history   (status-history-for tx-snap tx-eid)
             docs-from-tx       (audit-docs-for tx-snap tx-eid)
             docs-from-posting  (audit-docs-for tx-snap posting-eid)
             audit-docs         (->> (concat docs-from-tx docs-from-posting)
                                     (group-by :db/id)
                                     vals
                                     (map first)
                                     (sort-by :audit-doc/uploaded-at)
                                     vec)
             ;; status-history rows whose :origin-transaction points BACK
             ;; at this transaction — i.e., this tx caused changes on
             ;; *other* entities (e.g., invoice :pending → :posted).
             origin-tx-targets
             (set
              (d/q '[:find [?caused ...]
                     :in $ ?tx
                     :where
                     [?h :kontor.status-history/origin-transaction ?tx]
                     [?h :kontor.status-history/entity ?caused]]
                   tx-snap tx-eid))
             holds       (legal-holds-for tx-snap
                                          (cond-> #{posting-eid tx-eid}
                                            (seq origin-tx-targets)
                                            (set/union origin-tx-targets)))
             retention   (retention-summary-for tx-snap posting-eid :posting)]
         (cond-> {:posting   posting
                  :transaction tx
                  :as-of-tx  as-of-tx}
           (seq history)            (assoc :status-history history)
           (seq audit-docs)         (assoc :audit-docs audit-docs)
           (seq holds)              (assoc :legal-holds holds)
           retention                (assoc :retention retention)
           (seq origin-tx-targets)  (assoc :origin-transaction-targets
                                           (vec origin-tx-targets))))))))

;; ============================================================================
;; explain-by-concept-iri — McComb-aligned IRI dereference
;; ============================================================================

(defn entities-with-concept-iri
  "Reverse lookup: given an IRI string, return all kontor entities
   that bind to that concept across the substrate seams added by
   ADR-090. Returns `{:account :account-tag :partner :commodity :tax
   :document-type}` — each key carrying the vec of matching eids
   (empty when none).

   The McComb-aligned use case: a semantic-web consumer with an XBRL
   filing IRI (e.g. ifrs-full:Revenue) wants to know which kontor
   entities ground that concept. The kernel exposes the lookup
   without taking a position on resolution semantics; consumers
   compose with `explain-balance` / `explain-posting` to walk down
   from the entity to the underlying postings.

   ADR-090 + ADR-091."
  [db iri]
  (let [find-by (fn [attr]
                  (vec (d/q '[:find [?e ...]
                              :in $ ?attr ?iri
                              :where [?e ?attr ?iri]]
                            db attr iri)))]
    {:account        (find-by :kontor.account/concept-iri)
     :account-tag    (find-by :kontor.account-tag/concept-iri)
     :partner        (find-by :kontor.partner/concept-iri)
     :commodity      (find-by :kontor.commodity/concept-iri)
     :tax            (find-by :tax/concept-iri)
     :document-type  (find-by :document-type/concept-iri)}))
