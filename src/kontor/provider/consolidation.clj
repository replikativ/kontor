(ns kontor.provider.consolidation
  "Multi-entity consolidation primitive — ADR-073.

   Two operations on an entity family (per `kontor.entity/family`):

     1. [[translate-trial-balance-tx-data]]
        Given an :operating entity whose functional commodity ≠ the
        group's presentation commodity, translate that entity's trial
        balance to the presentation commodity per IAS 21 (closing rate
        for monetary BS items, average for P&L, historical for equity).
        Emits ONE consolidation transaction stamped with
        `:kontor.posting/entity = consolidation-entity`. The cumulative
        translation adjustment (CTA) is the plug that makes the entry
        balance per commodity.

     2. [[eliminate-intercompany-pair-tx-data]]
        Given a `:kontor.transaction/intercompany-pair-id` shared by two (or
        more) transactions on different :operating entities, emit
        reversing entries on the elimination entity that offset each
        side's intercompany postings. Sum-to-zero per (entity, commodity)
        holds automatically because the source txs each balance and
        we just negate.

   Both are *pure* tx-data builders per ADR-068; the caller commits
   them via `kontor.workflow.process/run-process` or `datahike.api/transact`.

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
     `kontor-tax-provision` companion.
   - Not Pillar 2 GloBE. Same companion.

   The kernel ships the *translation* and *elimination* mechanics;
   companions ship the policy that decides *which* entities consolidate
   together, *what* percentages they consolidate at, and *how* to file
   the result.

   See ADR-073 for design rationale + the IAS 21 rate-type matrix this
   uses by default."
  (:require [datahike.api :as d]
            [kontor.reporting.balance :as balance]
            [kontor.bitemporal :as kbt]
            [kontor.entity :as entity]
            [kontor.fx.fx :as fx]
            [kontor.money :as money]
            [kontor.workflow.process :as process]
            [kontor.reporting.trial :as trial]))

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
  "Normalize a commodity to a bare eid. Accepts an eid (number), a
   lookup-ref (vector), a symbol string (\"EUR\"), or a Money-style symbol
   keyword (:EUR). `:kontor.commodity/symbol` stores strings, so a keyword
   is resolved by its name. (note 196 N5 — one commodity shape out.)"
  [db c]
  (cond
    (number? c)  c
    (string? c)  (:db/id (d/entity db [:kontor.commodity/symbol c]))
    (keyword? c) (:db/id (d/entity db [:kontor.commodity/symbol (name c)]))
    (vector? c)  (:db/id (d/entity db c))
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
   stamped with :kontor.posting/entity = consolidation-entity-eid.

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
                                     `kontor.reporting.trial/trial-balance`).
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
                                     `kontor.workflow.process/run-process`).

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
    (throw (ex-info ":trial-balance required (caller computes via kontor.reporting.trial/trial-balance)"
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
                 :kontor.posting/transaction tx-tempid
                 :kontor.posting/account    acct
                 :kontor.posting/amount     (:amount m)
                 :kontor.posting/commodity  pres-eid
                 :kontor.posting/entity     consolidation-entity
                 :kontor.posting/display-type :product})))
        plug-posting (when-not (zero? (.signum ^java.math.BigDecimal plug-amount))
                       {:db/id               (tempid (count line-postings))
                        :kontor.posting/transaction tx-tempid
                        :kontor.posting/account     cta-account
                        :kontor.posting/amount      plug-amount
                        :kontor.posting/commodity   pres-eid
                        :kontor.posting/entity      consolidation-entity
                        :kontor.posting/display-type :product})]
    (when (empty? line-postings)
      (throw (ex-info "translate-trial-balance-tx-data: empty trial balance — nothing to translate"
                      {:source-entity source-entity})))
    (let [base (into [{:db/id                                    tx-tempid
                       :kontor.transaction/journal                      journal
                       :kontor.transaction/effective-date               at-date
                       :kontor.transaction/consolidation-source-entity  source-entity
                       :kontor.transaction/consolidation-kind           :translation
                       :kontor.transaction/state                        :draft
                       :kontor.transaction/narration
                       (str "Consolidation translation — source entity "
                            (or (:kontor.entity/code (d/pull db [:kontor.entity/code] source-entity))
                                source-entity))}]
                     (cond-> line-postings
                       plug-posting (conj plug-posting)))]
      (cond
        (and vt-from vt-to) (kbt/with-vt base vt-from vt-to)
        vt-from             (kbt/with-vt base vt-from)
        :else               base))))

;; ============================================================================
;; eliminate-intercompany-pair-tx-data
;; ============================================================================

(defn- find-pair-postings
  "Pull every posting from every POSTED SOURCE tx tagged with the given
   :kontor.transaction/intercompany-pair-id. **Excludes consolidation txs**
   (those with a :kontor.transaction/consolidation-kind attr) — without this
   exclusion a re-run of consolidate! would pick up the prior
   elimination tx (which we tag with the same pair-id for audit) and
   re-negate it, doubling the elimination on every cycle.

   Per ADR-073 review P1-73-3 we also filter `:kontor.transaction/state :posted`
   — drafts can still be edited (ADR-007 sealing story), and silently
   eliminating an in-flight draft would surprise the consumer.

   Returns a sequence of pulled posting maps with at least :db/id,
   :kontor.posting/account, :kontor.posting/amount, :kontor.posting/commodity, :kontor.posting/entity."
  [db pair-id]
  (let [tx-eids (d/q '[:find [?t ...]
                       :in $ ?pid
                       :where
                       [?t :kontor.transaction/intercompany-pair-id ?pid]
                       [?t :kontor.transaction/state :posted]
                       [(missing? $ ?t :kontor.transaction/consolidation-kind)]]
                     db pair-id)]
    (when (empty? tx-eids)
      (throw (ex-info "eliminate-intercompany-pair-tx-data: no POSTED source transactions found with pair-id"
                      {:pair-id pair-id})))
    (->> (d/q '[:find [?p ...]
                :in $ [?tx ...]
                :where [?p :kontor.posting/transaction ?tx]]
              db tx-eids)
         (mapv #(d/pull db [:db/id
                            :kontor.posting/amount
                            :kontor.posting/commodity
                            :kontor.posting/entity
                            {:kontor.posting/account [:db/id]}]
                        %)))))

(defn eliminate-intercompany-pair-tx-data
  "Pure tx-data builder. Given a `:kontor.transaction/intercompany-pair-id`,
   emit one elimination tx whose postings exactly negate the postings
   of all paired transactions — stamped with
   `:kontor.posting/entity = elimination-entity`.

   Same-currency pairs: the source txs each balance per (entity, commodity),
   so the union of their negated postings balances too, just on the
   elimination entity. No FX needed.

   Cross-currency pairs (note 197 / IAS 21.45): when `:presentation-commodity`
   + `:fx-provider` are supplied, each eliminated posting is TRANSLATED to the
   presentation commodity at its account-type rate (monetary → closing, P&L →
   average, equity → historical — the same rates as
   [[translate-trial-balance-tx-data]]) BEFORE the reversing entry is posted,
   so an intercompany pair booked in different currencies actually nets to zero
   in the presentation currency. Translating the two sides at different rates
   leaves a residual FX difference; per IAS 21.45 that intragroup-monetary FX
   difference is posted to `:fx-gain-loss-account` (P&L) — NOT to CTA (that is
   the general-translation difference, IAS 21.39) and NOT force-to-zero. A
   residual with no `:fx-gain-loss-account` supplied is an error.

   Inputs:
     :db                  — db value
     :pair-id             — string :kontor.transaction/intercompany-pair-id
                            (matches at least 2 txs)
     :elimination-entity  — :db/id of the elimination entity
     :journal             — :db/id of the journal to post to
     :date                — effective date for the elimination tx
     :tx-tempid           — optional string tempid (default \"elim-tx\")
     :presentation-commodity / :fx-provider / :at-date  — supply all three to
                            translate the elimination into presentation
                            currency (cross-currency case).
     :rate-type-by-account-type / :rate-type-by-account — optional rate
                            overrides (see translate-trial-balance-tx-data).
     :fx-gain-loss-account — P&L account eid for the intragroup FX residual
                            (required only when a cross-currency residual arises).
     :vt-from / :vt-to    — optional bitemporal stamps for direct
                            callers (ADR-068). `consolidate!` does
                            its own valid-time plumbing via
                            `kontor.workflow.process/run-process` and does NOT
                            consume these."
  [{:keys [db pair-id elimination-entity journal date tx-tempid vt-from vt-to
           presentation-commodity fx-provider at-date
           rate-type-by-account-type rate-type-by-account fx-gain-loss-account]
    :or   {tx-tempid "elim-tx"
           rate-type-by-account-type default-rate-type-by-account-type
           rate-type-by-account      {}}}]
  (when-not db                  (throw (ex-info ":db required" {})))
  (when-not pair-id             (throw (ex-info ":pair-id required" {})))
  (when-not elimination-entity  (throw (ex-info ":elimination-entity required" {})))
  (when-not journal             (throw (ex-info ":journal required" {})))
  (when-not date                (throw (ex-info ":date required" {})))
  (let [pair-postings (find-pair-postings db pair-id)
        ;; IAS 21.45 cross-currency elimination is OPT-IN via :fx-gain-loss-
        ;; account — supplying it (together with the presentation commodity +
        ;; provider) signals "translate the elimination into presentation
        ;; currency and route the FX residual to this P&L account." Without it,
        ;; the elimination stays in the source commodities (the pre-note-197
        ;; behaviour), correct for same-currency groups.
        translate?    (boolean (and presentation-commodity fx-provider fx-gain-loss-account))
        pres-eid      (when translate? (coerce-commodity db presentation-commodity))
        fx-date       (or at-date date)
        tempid (fn [i] (str tx-tempid "-p" i))
        ;; Per posting: negate, then (cross-currency) translate to the
        ;; presentation commodity at the account-type rate.
        elim-lines
        (mapv (fn [p]
                (let [acct    (-> p :kontor.posting/account :db/id)
                      neg-amt (.negate ^java.math.BigDecimal (:kontor.posting/amount p))
                      src     (-> p :kontor.posting/commodity :db/id)]
                  (if translate?
                    (let [{acct-type :kontor.account/type
                           monetary? :kontor.account/monetary?} (account-info db acct)
                          rt (pick-rate-type rate-type-by-account rate-type-by-account-type
                                             acct (or acct-type :other) monetary?)
                          m  (fx/convert (money/money neg-amt src) fx-provider
                                         {:to pres-eid :at-date fx-date :rate-type rt})]
                      {:acct acct :amount (:amount m) :commodity pres-eid})
                    {:acct acct :amount neg-amt :commodity src})))
              pair-postings)
        ;; Cross-currency residual = the translated postings no longer sum to
        ;; zero (each side at its own rate); IAS 21.45 → P&L, via a plug.
        residual (when translate?
                   (reduce (fn [^java.math.BigDecimal a l]
                             (.add a ^java.math.BigDecimal (:amount l)))
                           0M elim-lines))
        residual? (and residual (not (zero? (.signum ^java.math.BigDecimal residual))))
        _ (when (and residual? (nil? fx-gain-loss-account))
            (throw (ex-info (str "eliminate-intercompany-pair-tx-data: cross-currency "
                                 "elimination leaves an FX residual (" residual ") but no "
                                 ":fx-gain-loss-account was supplied (IAS 21.45 → P&L)")
                            {:pair-id pair-id :residual residual})))
        elim-postings
        (cond-> (into []
                      (map-indexed
                       (fn [i l]
                         {:db/id                       (tempid i)
                          :kontor.posting/transaction  tx-tempid
                          :kontor.posting/account      (:acct l)
                          :kontor.posting/amount       (:amount l)
                          :kontor.posting/commodity    (:commodity l)
                          :kontor.posting/entity       elimination-entity
                          :kontor.posting/display-type :product}))
                      elim-lines)
          residual?
          (conj {:db/id                       (tempid (count elim-lines))
                 :kontor.posting/transaction  tx-tempid
                 :kontor.posting/account      fx-gain-loss-account
                 ;; plug = −residual so the elimination tx balances; a debit
                 ;; (positive) here is an FX loss on the intragroup monetary item
                 :kontor.posting/amount       (.negate ^java.math.BigDecimal residual)
                 :kontor.posting/commodity    pres-eid
                 :kontor.posting/entity       elimination-entity
                 :kontor.posting/display-type :product}))]
    (let [base (into [{:db/id                              tx-tempid
                       :kontor.transaction/journal                journal
                       :kontor.transaction/effective-date         date
                       :kontor.transaction/intercompany-pair-id   pair-id
                       :kontor.transaction/consolidation-kind     :elimination
                       :kontor.transaction/state                  :draft
                       :kontor.transaction/narration              (str "Intercompany elimination (pair " pair-id ")")}]
                     elim-postings)]
      (cond
        (and vt-from vt-to) (kbt/with-vt base vt-from vt-to)
        vt-from             (kbt/with-vt base vt-from)
        :else               base))))

;; ============================================================================
;; consolidate-tx-data + consolidate!
;; ============================================================================

(defn consolidate-tx-data
  "Compose [[translate-trial-balance-tx-data]] over each :operating
   entity in the family + [[eliminate-intercompany-pair-tx-data]] for
   each unique intercompany pair-id. Returns a vector of tx-data
   fragments (one per translation + one per elimination) suitable for
   stitching into a `kontor.workflow.process` step list (each fragment must
   commit independently per the sum-to-zero invariant — multiple
   txs in one tx-data is fine, but they're independent transactions).

   Takes a `conn` (not `:db`) because we need to call
   `kontor.reporting.trial/trial-balance` per source entity, and that takes a
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
     :fx-gain-loss-account        — optional P&L account eid. Supplying it
                                     enables IAS 21.45 cross-currency
                                     elimination: each eliminated posting is
                                     translated to the presentation commodity
                                     and the intragroup FX residual is posted
                                     here. Omit for same-currency groups (the
                                     elimination then stays in source
                                     commodities). (note 197.)

   Returns the vector of tx-data fragments. Callers stitch:
     `(reduce into [] (consolidate-tx-data ...))` for one flat tx-data,
     or thread fragments as separate `kontor.workflow.process` steps (the
     `consolidate!` wrapper does this)."
  [{:keys [conn group-root consolidation-entity elimination-entity
           presentation-commodity fx-provider at-date journal cta-account
           rate-type-by-account-type rate-type-by-account fx-gain-loss-account]
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
        ;; this date. Detects via :kontor.transaction/consolidation-kind
        ;; :translation + :kontor.transaction/consolidation-source-entity +
        ;; :kontor.transaction/effective-date.
        translation-exists?
        (fn [source-eid]
          (boolean
           (d/q '[:find ?t .
                  :in $ ?src ?date
                  :where
                  [?t :kontor.transaction/consolidation-kind :translation]
                  [?t :kontor.transaction/consolidation-source-entity ?src]
                  [?t :kontor.transaction/effective-date ?date]]
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
                  [?t :kontor.transaction/consolidation-kind :elimination]
                  [?t :kontor.transaction/intercompany-pair-id ?pid]
                  [?t :kontor.transaction/effective-date ?date]
                  [?p :kontor.posting/transaction ?t]
                  [?p :kontor.posting/entity ?elim]]
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
                             [?p :kontor.posting/entity ?e]
                             [?p :kontor.posting/transaction ?tx]
                             [?tx :kontor.transaction/intercompany-pair-id ?pid]
                             [(missing? $ ?tx :kontor.transaction/consolidation-kind)]]
                           db (vec family))
                      sort)
        eliminations
        (vec
         (keep-indexed
          (fn [i pid]
            (when-not (elimination-exists? pid)
              (eliminate-intercompany-pair-tx-data
               {:db                        db
                :pair-id                   pid
                :elimination-entity        elimination-entity
                :journal                   journal
                :date                      at-date
                ;; IAS 21.45 (note 197): translate the elimination into the
                ;; presentation currency so cross-currency pairs actually net.
                :presentation-commodity    presentation-commodity
                :fx-provider               fx-provider
                :at-date                   at-date
                :rate-type-by-account-type rate-type-by-account-type
                :rate-type-by-account      rate-type-by-account
                :fx-gain-loss-account      fx-gain-loss-account
                :tx-tempid                 (str "elim-" i)})))
          pair-ids))]
    (into translations eliminations)))

(defn consolidate!
  "Commit a consolidation cycle via [[kontor.workflow.process/run-process]] —
   each fragment runs as a step under one gate, so any validation
   failure rolls the whole cycle back.

   Inputs mirror [[consolidate-tx-data]].

   The cycle's bitemporal `:db.valid/from` is set to `:at-date`, with
   `:db.valid/to` defaulting to `kontor.bitemporal/forever`. This means
   `(d/valid-at db t)` queries at any `t >= at-date` see the
   consolidation postings — matching the kernel's standard
   `post-transaction!` semantics (`posting.clj:371-373`).

   Returns the `run-process` result map (per `kontor.workflow.process`).
   The consolidation transactions land with `:kontor.transaction/state :draft`
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
