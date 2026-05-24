(ns kontor.statute
  "ADR-101 — statute-as-data substrate.

   The evaluator that consumes `:tax-concept` / `:provision` / `:regime` /
   `:parameter` entities. Provider authoring becomes: write data
   (provisions + parameters) per jurisdiction; the evaluator does the
   fold. Existing `defrecord`-shaped providers continue to work
   unchanged; new providers are configurations of provision data.

   Wire-shape recap (from `schema.clj`):

   - `:tax-concept` — the cross-jurisdiction catalogue; closed-by-ADR.
     A `:tax-concept/code` like `:participation-exemption` is the
     stable identifier a provision points at.
   - `:provision` — one law contribution. Carries citation, condition
     (closed-vocab EDN predicate), consequence (op + amount source),
     priority, optional `:exception-of`, optional `:regime` binding,
     effective-from / -until date guards.
   - `:regime` — elective container (IN old-vs-new, FR PME-vs-std).
     Election rides ADR-034 status-machine — no parallel namespace.
   - `:parameter` / `:parameter-value` / `:parameter-bracket` — the
     date-keyed value history (OpenFisca-style). Rates / brackets /
     thresholds change yearly; the schema carries the timeline so
     amendments are data adds, not code edits.

   The evaluator's central API:

     (applicable-provisions db {:concept ... :jurisdiction ... :as-of ...
                                :regime ...} ctx)
       → seq of provision pull-maps that pass the condition + regime
         filters and are in effect at as-of, sorted by :priority.

     (apply-provisions db query ctx)
       → {:items [...] :provisions-applied [...]}
         folds the applicable provisions, suppresses defaults whose
         exceptions apply, detects same-priority ambiguity, resolves
         each consequence to an item shape ready for
         `kontor.tax-schedule/apply-base-adjustments` or
         `apply-adjustments` (based on the item's :op).

     (parameter-value-at db code as-of)
       → BigDecimal at the asked instant (or nil).

     (parameter-brackets-at db code as-of)
       → [{:rate ... :upper ...} ...] sorted by :index.

   Predicate vocabulary (closed; escape hatch via
   `:provision/compute-fn`): `:and` `:or` `:not` `:eq` `:in` `:leq`
   `:geq` `:lt` `:gt` `:between` `:status-is` `true` `false`.

   See ADR-101 + research notes 117 / 118 / 119."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Closed predicate vocabulary + the eval-condition interpreter
;; ============================================================================

(def predicate-vocab
  "The closed v1 predicate set. Adding a predicate is an ADR-101 §D2
   decision; the alternative is the `:provision/compute-fn` escape hatch."
  #{:and :or :not :eq :in :leq :geq :lt :gt :between :status-is})

(defn- fact
  "Read `fact-key` from `ctx`. A vector key is treated as `get-in`."
  [ctx fact-key]
  (if (vector? fact-key)
    (get-in ctx fact-key)
    (get ctx fact-key)))

(defn- cmp
  "Three-way compare that returns nil if either operand is nil. Returns
   negative / 0 / positive when both are non-nil."
  [a b]
  (when (and (some? a) (some? b))
    (compare a b)))

(defn eval-condition
  "Interpret a condition expression against `ctx`. Returns boolean.
   See the namespace docstring for the grammar. `nil` is true (an
   unconditional provision). Throws on unknown predicates so authoring
   typos are caught at evaluation time, not silently mistaxed."
  [expr ctx]
  (cond
    (nil? expr)   true
    (true? expr)  true
    (false? expr) false
    (vector? expr)
    (let [[op & args] expr]
      (case op
        :and       (every? #(eval-condition % ctx) args)
        :or        (boolean (some #(eval-condition % ctx) args))
        :not       (not (eval-condition (first args) ctx))
        :eq        (= (fact ctx (first args)) (second args))
        :status-is (= (fact ctx (first args)) (second args))
        :in        (contains? (set (second args)) (fact ctx (first args)))
        :leq       (let [c (cmp (fact ctx (first args)) (second args))]
                     (and (some? c) (<= c 0)))
        :geq       (let [c (cmp (fact ctx (first args)) (second args))]
                     (and (some? c) (>= c 0)))
        :lt        (let [c (cmp (fact ctx (first args)) (second args))]
                     (and (some? c) (neg? c)))
        :gt        (let [c (cmp (fact ctx (first args)) (second args))]
                     (and (some? c) (pos? c)))
        :between   (let [v (fact ctx (first args))
                         lo (second args)
                         hi (nth args 2)]
                     (and (some? v)
                          (some? (cmp lo v)) (<= (cmp lo v) 0)
                          (some? (cmp v hi)) (<= (cmp v hi) 0)))
        (throw (ex-info "kontor.statute/eval-condition: unknown predicate"
                        {:op op :expr expr :vocabulary predicate-vocab}))))
    :else
    (throw (ex-info "kontor.statute/eval-condition: invalid expression"
                    {:expr expr}))))

;; ============================================================================
;; Compute-fn registry (the closed-vocab escape hatch — ADR-101 §D2)
;; ============================================================================

(defonce ^{:doc "Registered escape-hatch compute-fns. Modules call
   `register-compute-fn!` at load time. Use sparingly — every
   registration is a documented deviation from data-only provisions."}
  compute-fns (atom {}))

(defn register-compute-fn!
  "Register a compute-fn under `kw`. The fn receives `[ctx]` and
   returns the resolved consequence amount (BigDecimal) — or a richer
   item map for advanced cases. ADR-101 §D2 + note 118 Q3 (per-module
   registry)."
  [kw f]
  (swap! compute-fns assoc kw f))

(defn resolve-compute-fn
  "Look up a registered compute-fn by keyword. Throws if not registered
   (caller error — easier to debug than a silent nil)."
  [kw]
  (or (get @compute-fns kw)
      (throw (ex-info "kontor.statute: compute-fn not registered"
                      {:kw kw :registered (set (keys @compute-fns))}))))

;; ============================================================================
;; Parameter resolution — the date-keyed value history (OpenFisca pattern)
;; ============================================================================

(defn- ^Long instant-ms
  "Coerce an :instant (java.util.Date) to milliseconds."
  [^java.util.Date d]
  (.getTime d))

(defn parameter-value-at
  "Look up the scalar value of `:parameter/code` effective at `as-of`.
   Returns the BigDecimal (`:parameter-value/decimal-value`) or nil if
   no value is in effect at the asked instant. The chosen value is the
   one whose `[:effective-from, :effective-until)` half-open range
   contains as-of (open `:effective-until` ⇒ still in effect)."
  [db parameter-code ^java.util.Date as-of]
  (let [param-eid (d/q '[:find ?p .
                         :in $ ?code
                         :where [?p :parameter/code ?code]]
                       db parameter-code)]
    (when param-eid
      (let [as-ms  (instant-ms as-of)
            values (d/q '[:find (pull ?v [:parameter-value/effective-from
                                          :parameter-value/effective-until
                                          :parameter-value/decimal-value])
                          :in $ ?p
                          :where [?v :parameter-value/parameter ?p]]
                        db param-eid)]
        (some (fn [[{:parameter-value/keys [effective-from effective-until decimal-value]}]]
                (when (and (<= (instant-ms effective-from) as-ms)
                           (or (nil? effective-until)
                               (< as-ms (instant-ms effective-until))))
                  decimal-value))
              values)))))

(defn parameter-brackets-at
  "Look up the bracket scale of `:parameter/code` (parent parameter
   must have `:parameter/unit :bracket-scale`) effective at `as-of`.
   Returns a vector `[{:rate <bigdec> :upper <bigdec>|nil} …]` sorted
   by `:parameter-bracket/index`, ready to feed
   `kontor.tax-schedule/progressive`."
  [db parameter-code ^java.util.Date as-of]
  (let [param-eid (d/q '[:find ?p .
                         :in $ ?code
                         :where [?p :parameter/code ?code]]
                       db parameter-code)]
    (when param-eid
      (let [as-ms    (instant-ms as-of)
            brackets (d/q '[:find (pull ?b [:parameter-bracket/index
                                            :parameter-bracket/rate
                                            :parameter-bracket/upper
                                            :parameter-bracket/effective-from
                                            :parameter-bracket/effective-until])
                            :in $ ?p
                            :where [?b :parameter-bracket/parameter ?p]]
                          db param-eid)
            in-effect (filter (fn [[{:parameter-bracket/keys [effective-from effective-until]}]]
                                (and (<= (instant-ms effective-from) as-ms)
                                     (or (nil? effective-until)
                                         (< as-ms (instant-ms effective-until)))))
                              brackets)]
        (when (seq in-effect)
          (->> in-effect
               (map first)
               (sort-by :parameter-bracket/index)
               (mapv (fn [{:parameter-bracket/keys [rate upper]}]
                       {:rate rate :upper upper}))))))))

;; ============================================================================
;; Provision applicability — query + condition fold + regime gate
;; ============================================================================

(defn- read-edn
  "Parse a stringified-EDN attr (`:provision/condition` /
   `:provision/consequence`) — returns nil for nil/empty. Uses
   `read-string` since the producer is trusted (kernel writes these)."
  [s]
  (when (and s (not= "" s))
    (read-string s)))

(defn- in-effect?
  "True iff `as-of` lies in the half-open `[effective-from, effective-until)`
   window (open boundaries are nil)."
  [^java.util.Date effective-from ^java.util.Date effective-until ^java.util.Date as-of]
  (let [as-ms (instant-ms as-of)]
    (and (or (nil? effective-from) (<= (instant-ms effective-from) as-ms))
         (or (nil? effective-until) (< as-ms (instant-ms effective-until))))))

(defn regime-chain
  "Compute the closure of regimes reachable from `regime-code` via
   `:regime/extends`. Returns a set of `:regime/code` keywords (always
   including `regime-code` itself when it exists in db). Raises
   `kontor.tax/cyclic-regime` if the chain has a cycle.

   The semantic: electing regime A means all provisions bound to A or
   to any regime A transitively extends apply (the OpenFisca reform-
   overlay pattern — a reform regime that extends current law inherits
   all current-law provisions except those it explicitly overrides via
   `:provision/exception-of`)."
  [db regime-code]
  (when regime-code
    (loop [chain     #{}
           to-visit  [regime-code]]
      (cond
        (empty? to-visit) chain
        (contains? chain (first to-visit))
        (throw (ex-info "kontor.tax/cyclic-regime"
                        {:cycle-at (first to-visit) :chain-so-far chain}))
        :else
        (let [code   (first to-visit)
              parent (d/q '[:find ?parent-code .
                            :in $ ?code
                            :where
                            [?r :regime/code ?code]
                            [?r :regime/extends ?p]
                            [?p :regime/code ?parent-code]]
                          db code)]
          (recur (conj chain code)
                 (cond-> (rest to-visit)
                   parent (concat [parent]))))))))

(defn applicable-provisions
  "Find provisions matching `concept` + `jurisdiction`, in effect at
   `as-of`, gated by the elected `regime` (nil = no regime elected; only
   regime-free provisions are candidates), whose `:provision/condition`
   evaluates true against `ctx`. Returns provisions sorted by
   `:provision/priority` ascending.

   `:exception-of` pairs are NOT yet pruned here — that's
   `apply-provisions`'s job (a default is suppressed only when its
   exception is also in the applicable set)."
  [db {:keys [concept jurisdiction as-of regime]} ctx]
  (let [candidates (->> (d/q '[:find (pull ?p [* {:provision/concept [:tax-concept/code]
                                                  :provision/regime  [:regime/code]
                                                  :provision/exception-of [:db/id :provision/code]}])
                               :in $ ?concept-code ?juris
                               :where
                               [?p :provision/concept ?c]
                               [?c :tax-concept/code ?concept-code]
                               [?p :provision/jurisdiction ?juris]]
                             db concept jurisdiction)
                        (mapv first))]
    (->> candidates
         (filter (fn [p]
                   (in-effect? (:provision/effective-from p)
                               (:provision/effective-until p)
                               as-of)))
         (filter (let [chain (regime-chain db regime)]
                   (fn [p]
                     (let [pr (some-> p :provision/regime :regime/code)]
                       (cond
                         (nil? pr)     true        ; regime-free provision always candidate
                         (nil? regime) false       ; no regime elected: regime-bound provisions skip
                         :else         (contains? chain pr)))))) ; pr in elected regime's chain
         (filter (fn [{cond-str :provision/condition}]
                   (eval-condition (read-edn cond-str) ctx)))
         (sort-by :provision/priority))))

;; ============================================================================
;; Consequence resolution — turn :provision/consequence into a fold-ready item
;; ============================================================================

(defn- resolve-amount
  "Resolve `:amount` from a consequence map. Supports four shapes:

     {:amount-from :literal           :amount <bigdec>}
     {:amount-from :parameter         :parameter <code>}
     {:amount-from :tax-context-fact  :fact <kw-or-vector>}
     {:amount-from :compute-fn        :fn <kw>}

   Parameter shape resolves at `as-of`; tax-context-fact reads `ctx`
   (vector `:fact` keys read via `get-in` — same shape as
   `eval-condition`'s nested-fact-key support, so a provision can read
   `:inputs :gewst-interest-post-freibetrag` without flattening); the
   compute-fn variant invokes the registered fn with `ctx` and returns
   whatever it returns (literal bigdec OR a fn-of-ctx-with-running for
   late-binding — apply-adjustments / apply-base-adjustments handle
   the latter via their existing `(fn? raw)` branch)."
  [db consequence ctx ^java.util.Date as-of]
  (case (:amount-from consequence)
    :literal          (bigdec (:amount consequence))
    :parameter        (parameter-value-at db (:parameter consequence) as-of)
    :tax-context-fact (let [k (:fact consequence)
                            v (if (vector? k) (get-in ctx k) (get ctx k))]
                        (some-> v bigdec))
    :compute-fn       ((resolve-compute-fn (:fn consequence)) ctx)
    (throw (ex-info "kontor.statute/resolve-amount: unknown :amount-from"
                    {:consequence consequence
                     :supported #{:literal :parameter :tax-context-fact :compute-fn}}))))

(defn resolve-consequence
  "Turn a `:provision/consequence` EDN map into a fold-ready item the
   `kontor.tax-schedule` adjustment folds will consume. The output item
   shape:

     {:code :label :op :amount :provenance}

   where `:provenance` is `{:provision/code <id> :provision/citation <url>}`
   so the auditable resolved-items list traces every applied number back
   to the statute. The fold caller (often the per-jurisdiction provider)
   appends `:refundable?` for credits if needed."
  [db provision ctx ^java.util.Date as-of]
  (let [consequence (read-edn (:provision/consequence provision))]
    (-> consequence
        (select-keys [:op :code :label :refundable?])
        (assoc :amount     (resolve-amount db consequence ctx as-of)
               :provenance {:provision/code     (:provision/code provision)
                            :provision/citation (:provision/citation provision)}))))

;; ============================================================================
;; The fold — apply-provisions with exception-of suppression + ambiguity trap
;; ============================================================================

(defn- prune-defaults
  "Suppress default provisions whose exceptions also apply.

   For each provision in `applicable`, if some OTHER provision in
   `applicable` names it as its `:provision/exception-of` target, the
   default is dropped. The exception fires instead."
  [applicable]
  (let [excepted-ids (set (keep #(some-> % :provision/exception-of :db/id) applicable))]
    (remove (fn [p] (contains? excepted-ids (:db/id p))) applicable)))

(defn- assert-no-ambiguity!
  "Raise `kontor.tax/ambiguous-provision` if any two provisions in
   `provisions` share the same `:priority`. The exception carries both
   citations so a user can resolve by editing one provision's priority."
  [provisions]
  (let [groups (group-by :provision/priority provisions)
        ambiguous (filter #(> (count (second %)) 1) groups)]
    (when (seq ambiguous)
      (throw (ex-info "kontor.tax/ambiguous-provision"
                      {:ambiguities (mapv (fn [[priority ps]]
                                            {:priority priority
                                             :provisions (mapv #(select-keys % [:provision/code
                                                                                :provision/citation])
                                                               ps)})
                                          ambiguous)})))))

(defn apply-provisions
  "Resolve applicable provisions to fold-ready items.

   Pipeline: applicable-provisions → prune defaults whose exceptions
   apply → assert no same-priority ambiguity → resolve each consequence
   to an item map. Returns `{:items [<item>] :provisions [<provision>]}`
   — the items feed `kontor.tax-schedule/apply-base-adjustments` or
   `apply-adjustments` (based on each item's `:op`); the provisions
   list is the audit trail (citations).

   The caller typically splits the items by `:op` and runs the two
   folds (base-side, then schedule, then tax-side) in the surrounding
   provider's pipeline."
  [db query ctx]
  (let [applicable  (applicable-provisions db query ctx)
        post-prune  (prune-defaults applicable)
        _           (assert-no-ambiguity! post-prune)
        as-of       (:as-of query)
        items       (mapv #(resolve-consequence db % ctx as-of) post-prune)]
    {:items      items
     :provisions post-prune}))

;; ============================================================================
;; Starter concept catalogue — installed by core/install-schema!
;; ============================================================================

(def starter-concept-catalogue
  "The 14-concept starter set per ADR-101 §D6 + note 119. Cross-
   jurisdiction patterns surfaced in research notes 108-115 + the two
   schema-derivation reads (117 Catala / 118 OpenFisca). Closed-by-ADR:
   adding a concept is a one-row migration + an ADR addendum reviewing
   the cross-jurisdiction case."
  [{:tax-concept/code        :participation-exemption
    :tax-concept/label       "Participation exemption"
    :tax-concept/family      :exemption
    :tax-concept/description "Partial or full exemption from tax on
                              gains / dividends from holdings in another
                              corporation. DE §8b KStG (95%), UK SSE
                              (10%+ shareholding, 12-month hold)."}

   {:tax-concept/code        :rollover-relief
    :tax-concept/label       "Rollover relief"
    :tax-concept/family      :relief
    :tax-concept/description "Deferral of gain on disposal by acquiring
                              a replacement asset within a prescribed
                              window. US §1031 like-kind, DE §6b reserve,
                              UK TCGA s152, JP §36-2."}

   {:tax-concept/code        :like-kind-exchange
    :tax-concept/label       "Like-kind exchange"
    :tax-concept/family      :relief
    :tax-concept/description "Narrower than :rollover-relief — the
                              specific US §1031 form requiring like-kind
                              property (real-property only post-TCJA)."}

   {:tax-concept/code        :replacement-property
    :tax-concept/label       "Replacement property relief"
    :tax-concept/family      :relief
    :tax-concept/description "Involuntary-conversion deferral — US §1033,
                              DE §6b sub-concept. Asset is forced out
                              (compulsory purchase, casualty); replacement
                              acquired within the prescribed window."}

   {:tax-concept/code        :loss-bucket
    :tax-concept/label       "Loss bucket"
    :tax-concept/family      :base-adjustment
    :tax-concept/description "Compartmentalisation of losses for offset
                              purposes. DE four-bucket walls (§8b / §17 /
                              §20 / §23), UK capital-vs-income, US
                              capital-loss $3k/year, JP per-class."}

   {:tax-concept/code        :lifetime-cap
    :tax-concept/label       "Lifetime cap on preferential treatment"
    :tax-concept/family      :relief
    :tax-concept/description "Cumulative limit across the taxpayer's
                              lifetime on a preferential rate or
                              exclusion. UK BADR (£1M), US §1202 QSBS
                              (greater of $10M or 10× basis)."}

   {:tax-concept/code        :holding-period-preference
    :tax-concept/label       "Holding-period preferential rate"
    :tax-concept/family      :relief
    :tax-concept/description "A different (usually lower) rate applies
                              once an asset has been held for the
                              prescribed period. US LT-vs-ST (1 year),
                              DE §23 (10y real estate / 1y other), JP
                              real estate (5y, measured at Jan 1)."}

   {:tax-concept/code        :non-refundable-credit
    :tax-concept/label       "Non-refundable tax credit"
    :tax-concept/family      :credit
    :tax-concept/description "Reduces tax liability but not below zero.
                              The non-refundable form of most jurisdictions'
                              standard credits."}

   {:tax-concept/code        :refundable-credit
    :tax-concept/label       "Refundable tax credit"
    :tax-concept/family      :credit
    :tax-concept/description "Reduces tax liability and may go below
                              zero (a refund / transfer to the taxpayer).
                              US EITC, CA SR&ED for CCPCs, FR CIR."}

   {:tax-concept/code        :surtax
    :tax-concept/label       "Surtax on a prior tax"
    :tax-concept/family      :surtax
    :tax-concept/description "A tax on a tax (rate applied to a
                              previously-computed liability). DE Soli,
                              JP local CIT, IN/BR cess."}

   {:tax-concept/code        :minimum-tax
    :tax-concept/label       "Alternative minimum tax"
    :tax-concept/family      :minimum-tax
    :tax-concept/description "A floor on the tax liability computed on
                              an alternative base. US CAMT, IN MAT."}

   {:tax-concept/code        :base-transform-add
    :tax-concept/label       "Pre-schedule base addition"
    :tax-concept/family      :base-adjustment
    :tax-concept/description "Addition to the taxable base before the
                              schedule fires. DE §10 KStG non-deductible
                              expenses; DE §8 GewSt add-backs."}

   {:tax-concept/code        :base-transform-deduct
    :tax-concept/label       "Pre-schedule base deduction"
    :tax-concept/family      :base-adjustment
    :tax-concept/description "Deduction from the taxable base before
                              the schedule fires. DE §9 GewSt reductions;
                              standard / itemized deductions."}

   {:tax-concept/code        :elective-regime
    :tax-concept/label       "Elective regime"
    :tax-concept/family      :elective-regime
    :tax-concept/description "A taxpayer-elected alternative regime
                              that swaps in a different set of provisions.
                              IN old-vs-new income tax, FR PME-vs-std
                              IS, US itemized-vs-standard deduction."}])

(defn install-seeds!
  "Install the starter `:tax-concept` catalogue into `conn`. Idempotent
   — runs on every `create-test-db` via `kontor.core/install-schema!`,
   and on production deployments via the same code path."
  [conn]
  (d/transact conn starter-concept-catalogue))
