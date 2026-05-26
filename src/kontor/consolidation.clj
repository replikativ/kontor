(ns kontor.consolidation
  "Multi-entity consolidation primitive — ADR-073.

   Two operations on an entity family (per `kontor.entity/family`):

     1. [[translate-trial-balance-tx-data]]
        Given an :operating entity whose functional commodity ≠ the
        group's presentation commodity, translate that entity's trial
        balance to the presentation commodity per IAS 21 (closing rate
        for monetary BS items, average for P&L, historical for equity).
        Emits ONE consolidation transaction stamped with
        `:posting/entity = consolidation-entity`. The cumulative
        translation adjustment (CTA) is the plug that makes the entry
        balance per commodity.

     2. [[eliminate-intercompany-pair-tx-data]]
        Given a `:transaction/intercompany-pair-id` shared by two (or
        more) transactions on different :operating entities, emit
        reversing entries on the elimination entity that offset each
        side's intercompany postings. Sum-to-zero per (entity, commodity)
        holds automatically because the source txs each balance and
        we just negate.

   Both are *pure* tx-data builders per ADR-068; the caller commits
   them via `kontor.process/run-process` or `datahike.api/transact`.

   The [[consolidate-tx-data]] composer walks an entity family and
   stitches translation + elimination into ONE atomic tx ready for
   `run-process`. [[consolidate!]] is the obvious `(run-process conn
   (consolidate-tx-data ...))` wrapper.

   ## What this primitive is NOT

   - Not a full IAS 27 / IFRS 10 / ASC 810 control-and-ownership
     engine. We translate + eliminate; the *family* the caller passes
     in is assumed to be the right scope. Ownership percentages,
     minority interest, and acquisition-vs-pooling accounting are
     consumer-layer concerns (the future `kontor-consolidation`
     companion).
   - Not a deferred-tax / transfer-pricing engine. Those are
     `kontor-tax-provision` companion (research note 69 §4 Gap 5/7).
   - Not Pillar 2 GloBE. Same companion.

   The kernel ships the *translation* and *elimination* mechanics;
   companions ship the policy that decides *which* entities consolidate
   together, *what* percentages they consolidate at, and *how* to file
   the result.

   See ADR-073 for design rationale + the IAS 21 rate-type matrix this
   uses by default."
  (:require [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.bitemporal :as kbt]
            [kontor.entity :as entity]
            [kontor.fx :as fx]
            [kontor.money :as money]
            [kontor.process :as process]
            [kontor.trial :as trial]))

;; ============================================================================
;; IAS 21 / ASC 830 default rate-type matrix
;;
;; Customers override via :rate-type-by-account-type to match their
;; accounting policy (some shops use closing for everything for
;; simplicity; some use historical for non-monetary like inventory).
;; ============================================================================

(def default-rate-type-by-account-type
  "Default IAS 21 / ASC 830 rate-type by :kontor.account/type for MONETARY
   accounts (the substrate consults `:kontor.account/monetary?` per-account
   first; this map is the fallback when the attr is absent).

     :asset      → :closing   (monetary BS items — cash, AR, AP)
     :liability  → :closing
     :equity     → :historical
     :income     → :average   (P&L over the period)
     :expense    → :average

   For NON-MONETARY asset/liability (per `:kontor.account/monetary? false`),
   `pick-rate-type` overrides this default and returns `:historical` —
   the IAS-21-correct treatment for PP&E, inventory-at-cost, prepaid
   expenses, etc. Per ADR-073 review P1-73-1."
  {:asset     :closing
   :liability :closing
   :equity    :historical
   :income    :average
   :expense   :average})

(defn- coerce-commodity
  "Accept commodity as eid, lookup-ref, or :kontor.commodity/symbol string."
  [db c]
  (cond
    (number? c) c
    (string? c) (:db/id (d/entity db [:kontor.commodity/symbol c]))
    (vector? c) (:db/id (d/entity db c))
    :else c))

;; ============================================================================
;; translate-trial-balance-tx-data
;; ============================================================================

(defn- account-info
  "Pull both :kontor.account/type and :kontor.account/monetary? in one shot."
  [db account-eid]
  (d/pull db [:kontor.account/type :kontor.account/monetary?] account-eid))

(defn- pick-rate-type
  "Resolve the rate-type for an (account-eid, account-type) given:

     1. The :rate-type-by-account override map (per-account-eid wins).
     2. The account's :kontor.account/monetary? — if explicitly `false` on an
        :asset or :liability, return :historical (IAS 21 non-monetary).
        If explicitly `true` or absent on those types, fall through
        to the type-default.
     3. The :rate-type-by-account-type override map.
     4. The kernel default-rate-type-by-account-type."
  [rate-type-by-account rate-type-by-account-type
   account-eid account-type monetary?]
  (or (get rate-type-by-account account-eid)
      ;; ADR-073 P1-73-1: explicit non-monetary asset/liability →
      ;; historical rate. We treat false (explicit) differently from
      ;; nil (absent); only explicit false flips the type-default.
      (when (and (false? monetary?)
                 (#{:asset :liability} account-type))
        :historical)
      (get rate-type-by-account-type account-type)
      (get default-rate-type-by-account-type account-type)
      :closing))

(defn translate-trial-balance-tx-data
  "Pure tx-data builder. Translate one :operating entity's trial balance
   into the consolidation entity's functional commodity per IAS 21 /
   ASC 830 rate-types, and emit ONE balanced consolidation transaction
   stamped with :posting/entity = consolidation-entity-eid.

   The CTA (cumulative translation adjustment) is the plug — it absorbs
   the difference between the translated debits and credits per
   commodity, posted to :cta-account.

   Inputs (map):
     :db                          — db value (a snapshot — typically (d/db conn))
     :source-entity               — :db/id of the operating entity
                                     whose trial balance is being
                                     translated.
     :consolidation-entity        — :db/id of the consolidation /
                                     group entity (target).
     :presentation-commodity      — target commodity for the
                                     translated entry. Eid, lookup-ref,
                                     or symbol string.
     :fx-provider                 — an FxRateProvider (ADR-072).
     :at-date                     — Date for FX rate lookup.
     :journal                     — :db/id of the journal to post to.
     :cta-account                 — :db/id of the CTA account in the
                                     consolidation chart. Receives the
                                     translation-plug posting.
     :rate-type-by-account-type   — override map keyed by :kontor.account/type.
                                     Defaults to
                                     [[default-rate-type-by-account-type]].
     :rate-type-by-account        — override map keyed by account eid
                                     (wins over -by-account-type).
                                     Empty by default.
     :trial-balance               — REQUIRED pre-computed trial balance
                                     (the map returned by
                                     `kontor.trial/trial-balance`).
                                     The caller computes this because
                                     `trial-balance` takes a `conn`
                                     and this builder is pure-on-db.
     :tx-tempid                   — optional string tempid (default
                                     \"cons-trans-tx\").
     :vt-from / :vt-to            — optional bitemporal stamps for
                                     direct-caller use (per ADR-068:
                                     every business-write tx-data
                                     builder composes with bitemporal
                                     stamping). When set, the builder
                                     appends a `{:db/id \"datomic.tx\"
                                     :db.valid/from ... :db.valid/to ...}`
                                     map. The `consolidate!` orchestrator
                                     ignores these (it does its own
                                     `:vt-from` plumbing through
                                     `kontor.process/run-process`).

   Returns: tx-data (vector of maps) that, when transacted, creates
   one :transaction (kind :translation) and N :postings.

   Throws: when the input trial balance is empty, when the source
   entity has no :kontor.entity/functional-commodity set, or when any
   account-type's rate-type lookup returns nil."
  [{:keys [db source-entity consolidation-entity presentation-commodity
           fx-provider at-date journal cta-account
           rate-type-by-account-type rate-type-by-account
           trial-balance tx-tempid vt-from vt-to]
    :or   {rate-type-by-account-type default-rate-type-by-account-type
           rate-type-by-account      {}
           tx-tempid                 "cons-trans-tx"}}]
  (when-not db                     (throw (ex-info ":db required" {})))
  (when-not source-entity          (throw (ex-info ":source-entity required" {})))
  (when-not consolidation-entity   (throw (ex-info ":consolidation-entity required" {})))
  (when-not presentation-commodity (throw (ex-info ":presentation-commodity required" {})))
  (when-not fx-provider            (throw (ex-info ":fx-provider required" {})))
  (when-not at-date                (throw (ex-info ":at-date required" {})))
  (when-not journal                (throw (ex-info ":journal required" {})))
  (when-not cta-account            (throw (ex-info ":cta-account required" {})))
  (when-not trial-balance
    (throw (ex-info ":trial-balance required (caller computes via kontor.trial/trial-balance)"
                    {:source-entity source-entity})))
  (let [pres-eid (coerce-commodity db presentation-commodity)
        _ (when-not pres-eid
            (throw (ex-info "presentation-commodity not found" {:c presentation-commodity})))
        tb trial-balance
        ;; Walk per (account, commodity) and translate each to the
        ;; presentation commodity. Emit one posting per account
        ;; (summing across commodities). Track per-account translated
        ;; amount + the sum-of-translated-amounts (which IS the CTA
        ;; plug because the original trial balance sums to zero per
        ;; original-commodity but the translated amounts don't sum
        ;; to zero — that's the CTA).
        per-account-translated
        (into {}
              (for [[acct cmap] tb
                    :let [{acct-type :kontor.account/type
                           monetary? :kontor.account/monetary?} (account-info db acct)
                          acct-type (or acct-type :other)
                          rt (pick-rate-type rate-type-by-account
                                             rate-type-by-account-type
                                             acct
                                             acct-type
                                             monetary?)
                          translated (->> cmap
                                          (mapv (fn [[_c m]]
                                                  (fx/convert m fx-provider
                                                              {:to       pres-eid
                                                               :at-date  at-date
                                                               :rate-type rt})))
                                          (reduce money/add (money/zero pres-eid)))]]
                [acct translated]))
        ;; The translated amounts won't sum to zero in general — the
        ;; CTA plug bridges. plug = -(sum of translated amounts).
        plug-amount (->> per-account-translated
                         vals
                         (reduce money/add (money/zero pres-eid))
                         money/neg
                         :amount)
        ;; Posting tempids
        tempid (fn [i] (str tx-tempid "-p" i))
        line-postings
        (->> per-account-translated
             (filter (fn [[_a m]] (not (money/zero? m))))
             (map-indexed
              (fn [i [acct m]]
                {:db/id              (tempid i)
                 :posting/transaction tx-tempid
                 :posting/account    acct
                 :posting/amount     (:amount m)
                 :posting/commodity  pres-eid
                 :posting/entity     consolidation-entity
                 :posting/display-type :product})))
        plug-posting (when-not (zero? (.signum ^java.math.BigDecimal plug-amount))
                       {:db/id               (tempid (count line-postings))
                        :posting/transaction tx-tempid
                        :posting/account     cta-account
                        :posting/amount      plug-amount
                        :posting/commodity   pres-eid
                        :posting/entity      consolidation-entity
                        :posting/display-type :product})]
    (when (empty? line-postings)
      (throw (ex-info "translate-trial-balance-tx-data: empty trial balance — nothing to translate"
                      {:source-entity source-entity})))
    (let [base (into [{:db/id                                    tx-tempid
                       :transaction/journal                      journal
                       :transaction/effective-date               at-date
                       :transaction/consolidation-source-entity  source-entity
                       :transaction/consolidation-kind           :translation
                       :transaction/state                        :draft
                       :transaction/narration
                       (str "Consolidation translation — source entity "
                            (or (:kontor.entity/code (d/pull db [:kontor.entity/code] source-entity))
                                source-entity))}]
                     (cond-> line-postings
                       plug-posting (conj plug-posting)))]
      (cond
        (and vt-from vt-to) (conj base {:db/id "datomic.tx"
                                        :db.valid/from vt-from
                                        :db.valid/to vt-to})
        vt-from             (conj base {:db/id "datomic.tx"
                                        :db.valid/from vt-from})
        :else               base))))

;; ============================================================================
;; eliminate-intercompany-pair-tx-data
;; ============================================================================

(defn- find-pair-postings
  "Pull every posting from every POSTED SOURCE tx tagged with the given
   :transaction/intercompany-pair-id. **Excludes consolidation txs**
   (those with a :transaction/consolidation-kind attr) — without this
   exclusion a re-run of consolidate! would pick up the prior
   elimination tx (which we tag with the same pair-id for audit) and
   re-negate it, doubling the elimination on every cycle.

   Per ADR-073 review P1-73-3 we also filter `:transaction/state :posted`
   — drafts can still be edited (ADR-007 sealing story), and silently
   eliminating an in-flight draft would surprise the consumer.

   Returns a sequence of pulled posting maps with at least :db/id,
   :posting/account, :posting/amount, :posting/commodity, :posting/entity."
  [db pair-id]
  (let [tx-eids (d/q '[:find [?t ...]
                       :in $ ?pid
                       :where
                       [?t :transaction/intercompany-pair-id ?pid]
                       [?t :transaction/state :posted]
                       [(missing? $ ?t :transaction/consolidation-kind)]]
                     db pair-id)]
    (when (empty? tx-eids)
      (throw (ex-info "eliminate-intercompany-pair-tx-data: no POSTED source transactions found with pair-id"
                      {:pair-id pair-id})))
    (->> (d/q '[:find [?p ...]
                :in $ [?tx ...]
                :where [?p :posting/transaction ?tx]]
              db tx-eids)
         (mapv #(d/pull db [:db/id
                            :posting/amount
                            :posting/commodity
                            :posting/entity
                            {:posting/account [:db/id]}]
                        %)))))

(defn eliminate-intercompany-pair-tx-data
  "Pure tx-data builder. Given a `:transaction/intercompany-pair-id`,
   emit one elimination tx whose postings exactly negate the postings
   of all paired transactions — stamped with
   `:posting/entity = elimination-entity`.

   The math is straightforward: the source txs each balance per
   (entity, commodity), so the union of their negated postings balances
   too, just on the elimination entity. No FX needed — eliminations
   stay in their original commodities; the elimination entity itself is
   later translated by [[translate-trial-balance-tx-data]] if needed.

   Inputs:
     :db                  — db value
     :pair-id             — string :transaction/intercompany-pair-id
                            (matches at least 2 txs)
     :elimination-entity  — :db/id of the elimination entity
     :journal             — :db/id of the journal to post to
     :date                — effective date for the elimination tx
     :tx-tempid           — optional string tempid (default \"elim-tx\")
     :vt-from / :vt-to    — optional bitemporal stamps for direct
                            callers (ADR-068). `consolidate!` does
                            its own valid-time plumbing via
                            `kontor.process/run-process` and does NOT
                            consume these."
  [{:keys [db pair-id elimination-entity journal date tx-tempid vt-from vt-to]
    :or   {tx-tempid "elim-tx"}}]
  (when-not db                  (throw (ex-info ":db required" {})))
  (when-not pair-id             (throw (ex-info ":pair-id required" {})))
  (when-not elimination-entity  (throw (ex-info ":elimination-entity required" {})))
  (when-not journal             (throw (ex-info ":journal required" {})))
  (when-not date                (throw (ex-info ":date required" {})))
  (let [pair-postings (find-pair-postings db pair-id)
        tempid (fn [i] (str tx-tempid "-p" i))
        elim-postings
        (mapv (fn [i p]
                {:db/id                (tempid i)
                 :posting/transaction  tx-tempid
                 :posting/account      (-> p :posting/account :db/id)
                 :posting/amount       (.negate ^java.math.BigDecimal
                                        (:posting/amount p))
                 :posting/commodity    (:posting/commodity p)
                 :posting/entity       elimination-entity
                 :posting/display-type :product})
              (range)
              pair-postings)]
    (let [base (into [{:db/id                              tx-tempid
                       :transaction/journal                journal
                       :transaction/effective-date         date
                       :transaction/intercompany-pair-id   pair-id
                       :transaction/consolidation-kind     :elimination
                       :transaction/state                  :draft
                       :transaction/narration              (str "Intercompany elimination (pair " pair-id ")")}]
                     elim-postings)]
      (cond
        (and vt-from vt-to) (conj base {:db/id "datomic.tx"
                                        :db.valid/from vt-from
                                        :db.valid/to vt-to})
        vt-from             (conj base {:db/id "datomic.tx"
                                        :db.valid/from vt-from})
        :else               base))))

;; ============================================================================
;; consolidate-tx-data + consolidate!
;; ============================================================================

(defn consolidate-tx-data
  "Compose [[translate-trial-balance-tx-data]] over each :operating
   entity in the family + [[eliminate-intercompany-pair-tx-data]] for
   each unique intercompany pair-id. Returns a vector of tx-data
   fragments (one per translation + one per elimination) suitable for
   stitching into a `kontor.process` step list (each fragment must
   commit independently per the sum-to-zero invariant — multiple
   txs in one tx-data is fine, but they're independent transactions).

   Takes a `conn` (not `:db`) because we need to call
   `kontor.trial/trial-balance` per source entity, and that takes a
   conn. Internally takes ONE snapshot (`d/db conn`) and threads it
   through everything for consistency.

   Inputs (map):
     :conn                        — datahike connection
     :group-root                  — :db/id of the family root (typically
                                     the consolidation entity itself)
     :consolidation-entity        — :db/id of the entity that holds the
                                     translated postings. Usually the
                                     same as :group-root.
     :elimination-entity          — :db/id of the entity that holds the
                                     elimination postings. May equal
                                     :consolidation-entity for shops
                                     that don't separate them.
     :presentation-commodity      — target commodity for translation.
     :fx-provider                 — FxRateProvider (ADR-072).
     :at-date                     — Date for FX lookup + tx effective-date.
     :journal                     — journal eid for all consolidation txs.
     :cta-account                 — CTA account eid (per ADR-073).
     :rate-type-by-account-type   — optional override (see
                                     translate-trial-balance-tx-data).
     :rate-type-by-account        — optional override per account eid.

   Returns the vector of tx-data fragments. Callers stitch:
     `(reduce into [] (consolidate-tx-data ...))` for one flat tx-data,
     or thread fragments as separate `kontor.process` steps (the
     `consolidate!` wrapper does this)."
  [{:keys [conn group-root consolidation-entity elimination-entity
           presentation-commodity fx-provider at-date journal cta-account
           rate-type-by-account-type rate-type-by-account]
    :or   {rate-type-by-account-type default-rate-type-by-account-type
           rate-type-by-account      {}}}]
  (when-not conn                   (throw (ex-info ":conn required" {})))
  (when-not group-root             (throw (ex-info ":group-root required" {})))
  (when-not consolidation-entity   (throw (ex-info ":consolidation-entity required" {})))
  (when-not elimination-entity     (throw (ex-info ":elimination-entity required" {})))
  (when-not presentation-commodity (throw (ex-info ":presentation-commodity required" {})))
  (when-not fx-provider            (throw (ex-info ":fx-provider required" {})))
  (when-not at-date                (throw (ex-info ":at-date required" {})))
  (when-not journal                (throw (ex-info ":journal required" {})))
  (when-not cta-account            (throw (ex-info ":cta-account required" {})))
  ;; Per ADR-073 review P2-73-1: defensive — the consolidation +
  ;; elimination entities MUST be synthetic (:consolidation /
  ;; :elimination), not :operating. A misconfigured family where the
  ;; group entity is left as :operating would silently include itself
  ;; in the translation loop, cascading duplicate postings.
  (let [db (d/db conn)
        kind-of (fn [e]
                  (:kontor.entity/kind (d/pull db [:kontor.entity/kind] e)))
        cons-kind (kind-of consolidation-entity)
        elim-kind (kind-of elimination-entity)]
    (when (or (= :operating cons-kind) (nil? cons-kind))
      (throw (ex-info "consolidate-tx-data: :consolidation-entity must be :consolidation or :elimination kind"
                      {:consolidation-entity consolidation-entity
                       :found-kind cons-kind})))
    (when (and (not= consolidation-entity elimination-entity)
               (or (= :operating elim-kind) (nil? elim-kind)))
      (throw (ex-info "consolidate-tx-data: :elimination-entity must be :elimination kind (or = :consolidation-entity)"
                      {:elimination-entity elimination-entity
                       :found-kind elim-kind}))))
  (let [db (d/db conn)
        family (entity/family db group-root)
        ;; Find :operating entities (skip consolidation + elimination).
        operating (->> family
                       (filter (fn [e]
                                 (and (not= e consolidation-entity)
                                      (not= e elimination-entity)
                                      (= :operating
                                         (or (:kontor.entity/kind
                                              (d/pull db [:kontor.entity/kind] e))
                                             :operating)))))
                       sort)
        ;; P0-73-3: per (source-entity, at-date) idempotency — skip
        ;; producing a new translation entry if one already exists for
        ;; this date. Detects via :transaction/consolidation-kind
        ;; :translation + :transaction/consolidation-source-entity +
        ;; :transaction/effective-date.
        translation-exists?
        (fn [source-eid]
          (boolean
           (d/q '[:find ?t .
                  :in $ ?src ?date
                  :where
                  [?t :transaction/consolidation-kind :translation]
                  [?t :transaction/consolidation-source-entity ?src]
                  [?t :transaction/effective-date ?date]]
                db source-eid at-date)))
        ;; Similarly for eliminations — one per (pair-id, at-date,
        ;; elimination-entity). The elim tx also carries the pair-id;
        ;; with the find-pair-postings fix excluding consolidation txs,
        ;; we still want to skip emitting duplicates here so subsequent
        ;; runs are no-ops at the composer level.
        elimination-exists?
        (fn [pair-id]
          (boolean
           (d/q '[:find ?t .
                  :in $ ?pid ?date ?elim
                  :where
                  [?t :transaction/consolidation-kind :elimination]
                  [?t :transaction/intercompany-pair-id ?pid]
                  [?t :transaction/effective-date ?date]
                  [?p :posting/transaction ?t]
                  [?p :posting/entity ?elim]]
                db pair-id at-date elimination-entity)))
        translations
        (vec
         (keep-indexed
          (fn [i e]
            (when-not (translation-exists? e)
              (let [tb (trial/trial-balance conn {:entity e})]
                (when (seq tb)
                  (translate-trial-balance-tx-data
                   {:db                        db
                    :source-entity             e
                    :consolidation-entity      consolidation-entity
                    :presentation-commodity    presentation-commodity
                    :fx-provider               fx-provider
                    :at-date                   at-date
                    :journal                   journal
                    :cta-account               cta-account
                    :rate-type-by-account-type rate-type-by-account-type
                    :rate-type-by-account      rate-type-by-account
                    :trial-balance             tb
                    :tx-tempid                 (str "cons-trans-" i)})))))
          operating))
        pair-ids (->> (d/q '[:find [?pid ...]
                             :in $ [?e ...]
                             :where
                             [?p :posting/entity ?e]
                             [?p :posting/transaction ?tx]
                             [?tx :transaction/intercompany-pair-id ?pid]
                             [(missing? $ ?tx :transaction/consolidation-kind)]]
                           db (vec family))
                      sort)
        eliminations
        (vec
         (keep-indexed
          (fn [i pid]
            (when-not (elimination-exists? pid)
              (eliminate-intercompany-pair-tx-data
               {:db                 db
                :pair-id            pid
                :elimination-entity elimination-entity
                :journal            journal
                :date               at-date
                :tx-tempid          (str "elim-" i)})))
          pair-ids))]
    (into translations eliminations)))

(defn consolidate!
  "Commit a consolidation cycle via [[kontor.process/run-process]] —
   each fragment runs as a step under one gate, so any validation
   failure rolls the whole cycle back.

   Inputs mirror [[consolidate-tx-data]].

   The cycle's bitemporal `:db.valid/from` is set to `:at-date`, with
   `:db.valid/to` defaulting to `kontor.bitemporal/forever`. This means
   `(d/valid-at db t)` queries at any `t >= at-date` see the
   consolidation postings — matching the kernel's standard
   `post-transaction!` semantics (`posting.clj:371-373`).

   Returns the `run-process` result map (per `kontor.process`).
   The consolidation transactions land with `:transaction/state :draft`
   by default; callers post them via subsequent
   `kontor.posting/post-transaction!` cycles."
  [{:keys [conn at-date] :as input}]
  (when-not conn    (throw (ex-info "consolidate!: :conn required" {})))
  (when-not at-date (throw (ex-info "consolidate!: :at-date required" {})))
  (let [fragments (consolidate-tx-data input)
        steps (mapv (fn [frag] (constantly frag)) fragments)]
    (process/run-process conn {:steps    steps
                               :vt-from  at-date
                               :vt-to    kbt/forever})))
