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
  "Default IAS 21 / ASC 830 rate-type for each :account/type.

     :asset      → :closing   (monetary BS items — cash, AR, AP)
     :liability  → :closing
     :equity     → :historical
     :income     → :average   (P&L over the period)
     :expense    → :average
     :other      → :closing   (conservative default)

   NOTE: this lumps all assets as monetary by default. Real IAS 21
   distinguishes monetary assets (cash, AR, loans) from non-monetary
   (inventory at cost, PP&E, prepaid expenses) — non-monetary should
   use the historical rate at acquisition. Customers with material
   non-monetary holdings should ship a per-account override map
   instead of this default; see [[translate-trial-balance-tx-data]]
   :rate-type-by-account opt."
  {:asset     :closing
   :liability :closing
   :equity    :historical
   :income    :average
   :expense   :average})

(defn- coerce-commodity
  "Accept commodity as eid, lookup-ref, or :commodity/symbol string."
  [db c]
  (cond
    (number? c) c
    (string? c) (:db/id (d/entity db [:commodity/symbol c]))
    (vector? c) (:db/id (d/entity db c))
    :else c))

;; ============================================================================
;; translate-trial-balance-tx-data
;; ============================================================================

(defn- account-type-of
  [db account-eid]
  (or (:account/type (d/pull db [:account/type] account-eid))
      :other))

(defn- pick-rate-type
  "Resolve the rate-type for an (account-eid, account-type) given the
   :rate-type-by-account override map + :rate-type-by-account-type
   fallback."
  [rate-type-by-account rate-type-by-account-type account-eid account-type]
  (or (get rate-type-by-account account-eid)
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
     :rate-type-by-account-type   — override map keyed by :account/type.
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

   Returns: tx-data (vector of maps) that, when transacted, creates
   one :transaction (kind :translation) and N :postings.

   Throws: when the input trial balance is empty, when the source
   entity has no :entity/functional-commodity set, or when any
   account-type's rate-type lookup returns nil."
  [{:keys [db source-entity consolidation-entity presentation-commodity
           fx-provider at-date journal cta-account
           rate-type-by-account-type rate-type-by-account
           trial-balance tx-tempid]
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
                    :let [acct-type (account-type-of db acct)
                          rt (pick-rate-type rate-type-by-account
                                             rate-type-by-account-type
                                             acct
                                             acct-type)
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
    (into [{:db/id                                    tx-tempid
            :transaction/journal                      journal
            :transaction/effective-date               at-date
            :transaction/consolidation-source-entity  source-entity
            :transaction/consolidation-kind           :translation
            :transaction/state                        :draft
            :transaction/narration
            (str "Consolidation translation — source entity "
                 (or (:entity/code (d/pull db [:entity/code] source-entity))
                     source-entity))}]
          (cond-> line-postings
            plug-posting (conj plug-posting)))))

;; ============================================================================
;; eliminate-intercompany-pair-tx-data
;; ============================================================================

(defn- find-pair-postings
  "Pull every posting from every tx tagged with the given
   :transaction/intercompany-pair-id. Returns a sequence of pulled
   posting maps with at least :db/id, :posting/account, :posting/amount,
   :posting/commodity, :posting/entity."
  [db pair-id]
  (let [tx-eids (d/q '[:find [?t ...]
                       :in $ ?pid
                       :where [?t :transaction/intercompany-pair-id ?pid]]
                     db pair-id)]
    (when (empty? tx-eids)
      (throw (ex-info "eliminate-intercompany-pair-tx-data: no transactions found with pair-id"
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
     :tx-tempid           — optional string tempid (default \"elim-tx\")"
  [{:keys [db pair-id elimination-entity journal date tx-tempid]
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
    (into [{:db/id                              tx-tempid
            :transaction/journal                journal
            :transaction/effective-date         date
            :transaction/intercompany-pair-id   pair-id
            :transaction/consolidation-kind     :elimination
            :transaction/state                  :draft
            :transaction/narration              (str "Intercompany elimination (pair " pair-id ")")}]
          elim-postings)))

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
  (let [db (d/db conn)
        family (entity/family db group-root)
        ;; Find :operating entities (skip consolidation + elimination).
        operating (->> family
                       (filter (fn [e]
                                 (and (not= e consolidation-entity)
                                      (not= e elimination-entity)
                                      (= :operating
                                         (or (:entity/kind
                                              (d/pull db [:entity/kind] e))
                                             :operating)))))
                       sort)
        translations
        (vec
         (keep-indexed
          (fn [i e]
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
                  :tx-tempid                 (str "cons-trans-" i)}))))
          operating))
        pair-ids (->> (d/q '[:find [?pid ...]
                             :in $ [?e ...]
                             :where
                             [?p :posting/entity ?e]
                             [?p :posting/transaction ?tx]
                             [?tx :transaction/intercompany-pair-id ?pid]]
                           db (vec family))
                      sort)
        eliminations
        (mapv (fn [i pid]
                (eliminate-intercompany-pair-tx-data
                 {:db                 db
                  :pair-id            pid
                  :elimination-entity elimination-entity
                  :journal            journal
                  :date               at-date
                  :tx-tempid          (str "elim-" i)}))
              (range) pair-ids)]
    (into translations eliminations)))

(defn consolidate!
  "Commit a consolidation cycle via [[kontor.process/run-process]] —
   each fragment runs as a step under one gate, so any validation
   failure rolls the whole cycle back.

   Inputs mirror [[consolidate-tx-data]].

   Returns the `run-process` result map (per `kontor.process`).
   The consolidation transactions land with `:transaction/state :draft`
   by default; callers post them via subsequent
   `kontor.posting/post-transaction!` cycles."
  [input]
  (when-not (:conn input)
    (throw (ex-info "consolidate!: :conn required" {})))
  (let [conn (:conn input)
        fragments (consolidate-tx-data input)
        steps (mapv (fn [frag] (constantly frag)) fragments)]
    (process/run-process conn {:steps steps})))
