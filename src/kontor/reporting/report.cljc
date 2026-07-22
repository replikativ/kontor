(ns kontor.reporting.report
  "Declarative report engine.

   A report is a (typically named) tree of expressions, each
   computed by an *engine* (see `engines` below). The two kernel
   engines, per the user's Phase-1.5 scope cut:

     :account-codes — sum postings whose `:kontor.account/code` matches
                      any of the given prefix patterns. The DE UStVA
                      uses this for boxes that key off konto-number
                      patterns (e.g. \"all 4400/4410/4420 sales 19%\").

     :tax-tags      — sum postings whose `:kontor.posting/account-tags`
                      (or whose account's `:kontor.account/tags`) include
                      any of the given tag keywords. Used by the
                      DE UStVA for box-keyed aggregations
                      (e.g. :ust-81 = sales 19%, :ust-86 = sales 7%,
                      :ust-66 = total deductible Vorsteuer).

   A report definition is plain data:

     {:report/name \"UStVA 2026 (monatlich)\"
      :report/country \"DE\"
      :report/lines
      [{:line/code   \"81\"
        :line/label  \"Steuerpflichtige Umsätze 19%\"
        :line/expression {:engine :tax-tags
                          :tags   [:ust-81]
                          :sign   :inflow}}
       {:line/code   \"86\"
        :line/label  \"Steuerpflichtige Umsätze 7%\"
        :line/expression {:engine :tax-tags
                          :tags   [:ust-86]
                          :sign   :inflow}}
       …]}

   `:sign` can be `:inflow` (sum the natural-balance side of the
   account class — credits for income/liabilities, debits for
   asset/expense) or `:raw` (sum the raw signed amounts as stored).
   Defaults to `:raw`.

   The engine produces, per expression:
     {:value Money :postings [<eid> ...]}

   so consumers can drill from a report line into the contributing
   postings — important for audit defense.

   `compute-report` is bitemporal-aware (same axes as balance.clj).
   Default = today/today.

   Future engines (`:aggregation`, `:domain`) come when a real
   need surfaces; per ADR-014's spirit we don't build the broader
   Odoo shape until a second country forces it."
  (:require [clojure.set]
            [clojure.string :as str]
            [datahike.api :as d]
            [kontor.fx.fx :as fx]
            [kontor.money :as money]
            [kontor.reporting.balance :as balance]))

;; ============================================================================
;; Internal: bitemporal posting fetch
;; ============================================================================

(def ^:private default-included-states #{:posted})

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- ->ms [x] #?(:clj (.getTime ^java.util.Date x) :cljs (if (number? x) x (inst-ms x))))
(defn- before-or-eq? [a b] (<= (->ms a) (->ms b)))
(defn- on-or-after?  [a b] (>= (->ms a) (->ms b)))
(defn- date-from-millis [ms] #?(:clj (java.util.Date. (long ms)) :cljs (js/Date. ms)))

(defn- pull-posting
  "Pull the posting + its account's commodity/code/type/tags + tx state.
   Returns a flat map suitable for predicate filtering. Adds
   `:valid-from` derived from the creating tx's `:tx/valid-from`
   (kontor.bitemporal) and `:ledger-eid` for the optional :ledger
   filter (ADR-021 — a nil :kontor.posting/ledger is the primary book)."
  [db p]
  (let [pulled (d/pull db
                       [:db/id
                        :kontor.posting/amount
                        :kontor.posting/commodity
                        :kontor.posting/transaction
                        {:kontor.posting/ledger [:db/id]}
                        {:kontor.posting/entity [:db/id]}
                        {:kontor.posting/partner [:db/id]}
                        {:kontor.posting/account [:kontor.account/code
                                                  :kontor.account/path
                                                  :kontor.account/type
                                                  :kontor.account/tags]}
                        {:kontor.posting/account-tags [:kontor.account-tag/name]}
                        {:kontor.posting/dimensions [:kontor.posting-dimension/axis
                                                     :kontor.posting-dimension/value]}]
                       p)
        tx-state (some-> (-> pulled :kontor.posting/transaction :db/id)
                         (#(d/pull db [:kontor.transaction/state] %))
                         :kontor.transaction/state)
        account (:kontor.posting/account pulled)
        vf (d/q '[:find ?vf .
                  :in $ ?p
                  :where
                  [?p :kontor.posting/transaction _ ?tx]
                  [?tx :db/txInstant ?ti]
                  [(get-else $ ?tx :db.valid/from ?ti) ?vf]]
                db p)
        ;; Account tags from the M2M; flatten to keywords
        acct-tag-names (->> (:kontor.account/tags account)
                            (map (fn [t]
                                   (or (:kontor.account-tag/name (d/pull db [:kontor.account-tag/name] (:db/id t)))
                                       (:kontor.account-tag/name t))))
                            (filter some?)
                            (map keyword)
                            set)
        ;; Posting-level tags (materialized at posting time)
        posting-tag-names (->> (:kontor.posting/account-tags pulled)
                               (map :kontor.account-tag/name)
                               (filter some?)
                               (map keyword)
                               set)
        ;; ADR-097 classification dimensions → {axis #{values}}
        dimensions (reduce (fn [acc d]
                             (update acc (:kontor.posting-dimension/axis d)
                                     (fnil conj #{}) (:kontor.posting-dimension/value d)))
                           {}
                           (:kontor.posting/dimensions pulled))]
    (assoc pulled
           :valid-from vf
           :tx-state tx-state
           :ledger-eid (:db/id (:kontor.posting/ledger pulled))
           :entity-eid (:db/id (:kontor.posting/entity pulled))
           :partner-eid (:db/id (:kontor.posting/partner pulled))
           :account-code (:kontor.account/code account)
           :account-path (:kontor.account/path account)
           :account-type (:kontor.account/type account)
           :dimensions dimensions
           :all-tags (clojure.set/union acct-tag-names posting-tag-names))))

(defn- in-window?
  "Check valid-from is in [from, to-exclusive)."
  [posting from to-exclusive]
  (let [vf (:valid-from posting)]
    (and (some? vf)
         (or (nil? from) (on-or-after? vf from))
         (or (nil? to-exclusive) (before-or-eq? vf (date-from-millis (dec (->ms to-exclusive)))))
         ;; the (dec) makes this strictly before; cleaner-looking than `< end`
         )))

;; ============================================================================
;; Posting → Money sign per :sign mode
;; ============================================================================

(defn- natural-sign
  "Per accounting convention:
     :asset / :expense  → debit-natural  (positive amounts grow)
     :liability / :equity / :income → credit-natural (negative amounts grow)
   `:inflow` reports the *natural* increase, so for credit-natural
   accounts we negate the stored signed amount."
  [account-type stored-amount]
  (case account-type
    (:liability :equity :income) (money/negate-amount stored-amount)
    stored-amount))

;; ============================================================================
;; Engines — every report line is a quotient epimorphism σ_E (ADR-096)
;;
;; A report line sums the postings of ONE class under some partition.
;; `sum-postings` is the shared fold; `marginalize` is the full σ_E
;; (every class at once); the `run-engine` methods are the per-line
;; views — `:account-codes` and `:tax-tags` (the historical engines,
;; kept behaviour-identical) and the generic `:dimension`.
;; ============================================================================

(defn- amount-of
  "The signed BigDecimal a posting contributes under `sign`
   (`:raw` = stored; `:inflow` = natural-increase side)."
  [p sign]
  (let [stored (:kontor.posting/amount p)]
    (case sign
      :inflow (natural-sign (:account-type p) stored)
      :raw    stored)))

(defn- sum-postings
  "Fold a seq of pulled postings into `{:value Money :postings [eid…]}`
   — the shared tail of every engine.

   With `:strict-commodity? true`, throws if the postings span more
   than one `:kontor.posting/commodity` (treating nil as its own
   value). Default false preserves pre-S1 silent-sum behaviour
   (every caller that already knew its postings were monocommodity
   stays green); opt-in lets consumers fail loudly on mixed-commodity
   input rather than getting a silently-meaningless Money wrapped in
   the caller-supplied `:commodity`. Note 168 §2 S1 P1.a."
  ([postings sign commodity]
   (sum-postings postings sign commodity nil))
  ([postings sign commodity {:keys [strict-commodity?]}]
   (when strict-commodity?
     (let [cs (into #{} (map :kontor.posting/commodity) postings)]
       (when (> (count cs) 1)
         (throw (ex-info "sum-postings: mixed commodities under :strict-commodity?"
                         {:type :report/mixed-commodity
                          :commodities cs
                          :postings (mapv :db/id postings)})))))
   (let [sum (reduce (fn [acc p]
                       (money/add-amount acc (amount-of p sign)))
                     (money/zero-amount)
                     postings)]
     {:value    (money/money sum (or commodity :EUR))
      :postings (mapv :db/id postings)})))

;; The built-in classification dimensions a `marginalize` / `:dimension`
;; engine can partition over. Each maps an axis keyword to a function
;; pulled-posting → class. `:account-tags` is set-valued — a posting
;; carries several tags, so a tag-marginalization is a covering, not a
;; strict partition (it may double-count; that is expected for tags).
(def ^:private dimension-extractors
  {:account-type :account-type
   :account-code :account-code
   :account-path :account-path
   :ledger       :ledger-eid
   :entity       :entity-eid
   :commodity    :kontor.posting/commodity
   :partner      :partner-eid
   :account-tags :all-tags})

(def ^:private set-valued-dimensions #{:account-tags})

(defn- resolve-dimension
  "Resolve a `dimension` argument to `[extract-fn set-valued?]`.
   `dimension` is a `posting→class` function, a built-in axis keyword
   (see `dimension-extractors`), or — for any other keyword — a
   `:kontor.posting/dimensions` classification axis (ADR-097), which is
   set-valued (a posting may carry several values on one axis)."
  [dimension]
  (cond
    (fn? dimension)
    [dimension false]

    (dimension-extractors dimension)
    [(dimension-extractors dimension) (contains? set-valued-dimensions dimension)]

    (keyword? dimension)
    [#(get (:dimensions %) dimension) true]

    :else
    (throw (ex-info "report: unresolvable dimension" {:dimension dimension}))))

(defn marginalize
  "The quotient epimorphism σ_E: partition `postings` by
   `dimension` and sum within each class. `dimension` is a built-in
   axis keyword (see `dimension-extractors`), a `:kontor.posting/dimensions`
   classification axis keyword (ADR-097), or a function posting→class.
   Returns `{class {:value Money :postings [eid…]}}`.

   For a scalar axis this is a true partition — every posting lands in
   exactly one class, and the classes' values sum to the grand total.
   For a set-valued axis (`:account-tags` and any `:kontor.posting/dimensions`
   axis) a posting contributes to each class it carries (a covering).

   Opts: `:sign` (`:raw` | `:inflow`, default `:raw`), `:commodity`
   (default `:EUR`), and `:strict-commodity?` (default false — when
   true, throws if any class contains postings spanning more than one
   commodity; note 168 §2 S1 P1.a)."
  ([postings dimension] (marginalize postings dimension {}))
  ([postings dimension {:keys [sign commodity strict-commodity?]
                        :or {sign :raw commodity :EUR}}]
   (let [[extract set-axis?] (resolve-dimension dimension)
         grouped  (reduce (fn [acc p]
                            (let [v (extract p)]
                              (if set-axis?
                                (reduce #(update %1 %2 (fnil conj []) p) acc (or v #{}))
                                (update acc v (fnil conj []) p))))
                          {}
                          postings)]
     (update-vals grouped #(sum-postings % sign commodity
                                         {:strict-commodity? strict-commodity?})))))

(defmulti run-engine
  "Dispatch on (:engine expression). Each engine receives the
   already-fetched seq of pulled postings and the expression spec,
   and returns {:value Money :postings [<eid>...]}."
  (fn [_postings expression _opts] (:engine expression)))

(defn- code-prefix-match?
  [^String code patterns]
  (some (fn [^String p]
          (cond
            (str/ends-with? p "%")
            (str/starts-with? code (subs p 0 (dec (count p))))

            :else
            (= code p)))
        patterns))

(defmethod run-engine :account-codes
  [postings {:keys [codes sign commodity strict-commodity?]
             :or {sign :raw commodity :EUR}} _opts]
  (sum-postings (filter (fn [p]
                          (let [code (:account-code p)]
                            (and (some? code) (code-prefix-match? code codes))))
                        postings)
                sign commodity {:strict-commodity? strict-commodity?}))

(defmethod run-engine :tax-tags
  [postings {:keys [tags sign commodity strict-commodity?]
             :or {sign :raw commodity :EUR}} _opts]
  (let [tag-set (set tags)]
    (sum-postings (filter (fn [p]
                            (seq (clojure.set/intersection tag-set (:all-tags p))))
                          postings)
                  sign commodity {:strict-commodity? strict-commodity?})))

(defmethod run-engine :dimension
  ;; The generic σ_E line: sum the postings of one class under a
  ;; built-in axis OR a `:kontor.posting/dimensions` axis (ADR-097). `:match`
  ;; is the class value (or a collection — any of). For a set-valued
  ;; axis, `:match` is matched by intersection.
  [postings {:keys [dimension match sign commodity strict-commodity?]
             :or {sign :raw commodity :EUR}} _opts]
  (let [[extract set-axis?] (resolve-dimension dimension)
        match-set (if (coll? match) (set match) #{match})]
    (sum-postings (filter (fn [p]
                            (let [v (extract p)]
                              (if set-axis?
                                (seq (clojure.set/intersection match-set (or v #{})))
                                (contains? match-set v))))
                          postings)
                  sign commodity {:strict-commodity? strict-commodity?})))

(defmethod run-engine :default
  [_ expression _opts]
  (throw (ex-info "Unknown report engine"
                  {:type :report/unknown-engine
                   :expression expression
                   :supported (-> (methods run-engine) keys set)})))

;; ============================================================================
;; Top-level: compute-report
;; ============================================================================

(defn- ledger-filter-pred
  "Posting predicate for the optional `:ledger` report filter.

   The RULE (nil ledger = primary book, per ADR-021) lives in
   `balance/ledger-match-fn` so the report engine and the balance-side
   readers cannot disagree about it; this just applies it to the shape
   `pull-posting` produces."
  [db ledger-spec]
  (comp (balance/ledger-match-fn db ledger-spec) :ledger-eid))

(defn- entity-filter-pred
  "Build a posting predicate for the optional `:entity` report filter.
   `entity-spec` is an eid or lookup-ref. Per ADR-031 every balance-
   affecting posting in multi-entity mode carries `:kontor.posting/entity`; in
   single-entity mode no posting does. A posting without an entity is
   *not* matched by an entity filter (it represents the global single-
   entity book, which doesn't belong to any specific entity). Returns
   `(constantly true)` when no entity filter is requested."
  [db entity-spec]
  (if (nil? entity-spec)
    (constantly true)
    (let [{:keys [db/id]} (d/pull db [:db/id] entity-spec)]
      (when-not id
        (throw (ex-info "compute-report: :entity not found" {:entity entity-spec})))
      (fn [p] (= id (:entity-eid p))))))

(defn- ->day-after
  "Inclusive `through` → exclusive `to`: midnight of the following day.
   Note 160 §I-10."
  [through]
  (date-from-millis (+ (->ms through) (* 1000 60 60 24))))

(defn resolve-window
  "Translate `:through` (inclusive end) into the canonical exclusive
   `:to` the engine uses. Errors if both are supplied — pick one.
   Note 160 §I-10.

   Public because a wrapper that INTERPRETS the window — rather than
   forwarding it — has to normalise first, or it reads `:to` as nil
   while the caller believes they bounded the period."
  [{:keys [to through] :as opts}]
  (when (and to through)
    (throw (ex-info "kontor.report: pass either :to (exclusive) or :through (inclusive), not both"
                    {:to to :through through})))
  (cond-> opts
    through (-> (dissoc :through)
                (assoc :to (->day-after through)))))

;; ============================================================================
;; Option contract
;;
;; Every option the read side understands, declared once. `check-options!`
;; rejects anything else, which is what makes a WRAPPER's mistake loud:
;; a layer that rebuilds this map from an allowlist (`(cond-> {} from
;; (assoc :from from) …)`) silently drops every key it was not written to
;; know about, and the caller gets a plausible number computed under
;; different semantics than they asked for. That is how `:through` came to
;; be accepted here, documented here, and discarded by `compute-statement`
;; — a statement scoped `:through #inst "2026-12-31"` silently fell back
;; to no upper bound at all and pulled in later fiscal years.
;;
;; So: wrappers FORWARD the option map (dissoc'ing only their own keys)
;; instead of rebuilding it, and this predicate turns any key nobody
;; recognises into an error rather than a silent default. New engine
;; options must be added here, and a wrapper that intercepts an option of
;; its own must dissoc it before forwarding.
;; ============================================================================

(def known-options
  "Every option key `compute-report` / `report-postings` accept. See
   `compute-report`'s docstring for the meaning of each."
  #{:from :to :through :as-of-tx :include-states :posting-filter
    :ledger :entity :translate-to :fx-provider :rate-type
    :strict-commodity?})

(defn check-options!
  "Throw `:report/unknown-option` if `opts` carries a key outside
   [[known-options]]. `context` names the caller in the error.

   Deliberately strict: a mistyped or dropped option is otherwise
   indistinguishable from an intentional default, and the result is a
   number that looks right."
  ([opts] (check-options! opts "kontor.report"))
  ([opts context]
   (let [unknown (clojure.set/difference (set (keys opts)) known-options)]
     (when (seq unknown)
       (throw (ex-info (str context ": unknown option " (pr-str (vec (sort-by str unknown))))
                       {:type    :report/unknown-option
                        :unknown unknown
                        :known   known-options
                        :context context}))))
   opts))

(defn report-postings
  "Fetch + bitemporally filter the postings a report sees, returning a
   vector of pulled posting maps — the shape the `run-engine` engines
   and `marginalize` (the σ_E primitive) consume. Exposed so a
   consumer can `marginalize` directly without going through a full
   report definition.

   Options (the posting-selection subset of `compute-report`'s):
   `:from` `:to` `:through` `:as-of-tx` `:include-states` `:ledger`
   `:entity` `:posting-filter` — see `compute-report` for each.

   `:through` is inclusive sugar over `:to`: pass `:through #inst
   \"2026-12-31\"` to mean \"the FY 2026 ends Dec 31\" without having
   to remember `:to` is exclusive."
  ([conn] (report-postings conn {}))
  ([conn opts]
   (let [{:keys [from to as-of-tx include-states ledger entity posting-filter]
          :or   {include-states default-included-states}}
         (resolve-window (check-options! opts "kontor.report/report-postings"))
         as-of-tx    (or as-of-tx (now))
         db          (-> conn d/db (d/as-of as-of-tx))
         ledger-pred (ledger-filter-pred db ledger)
         entity-pred (entity-filter-pred db entity)
         all-pids    (d/q (into '[:find [?p ...] :where [?p :kontor.posting/account _]]
                                (or posting-filter []))
                          db)]
     (into []
           (comp (map #(pull-posting db %))
                 (filter (fn [p]
                           (and (in-window? p from to)
                                (contains? include-states (:tx-state p))
                                (ledger-pred p)
                                (entity-pred p)))))
           all-pids))))

(defn compute-report
  "Run a report definition against `conn` and return:

     {:report/name str
      :report/lines [{:line/code str :line/label str
                      :line/value Money :line/postings [eid ...]} ...]
      :report/window {:from Date :to Date}
      :report/computed-at Date}

   Options:
     :from           — inclusive lower bound on the posting's valid-from
                       (resolved via :tx/valid-from on the creating tx).
                       Default: nil = beginning of time.
     :to             — EXCLUSIVE upper bound. Default nil = NO upper
                       bound at all (future-dated postings included),
                       matching `balance/account-balance` per note 160
                       §I-17. Pass `:to #inst \"2027-01-01\"` for FY 2026.
     :through        — INCLUSIVE upper bound (sugar over :to). Pass
                       `:through #inst \"2026-12-31\"` for FY 2026 —
                       reads natural. Mutually exclusive with :to.
                       Note 160 §I-10.
     :as-of-tx       — datahike snapshot timestamp (default: now)
     :include-states — set of :kontor.transaction/state values to include
                       (default: #{:posted}). Drafts excluded so the
                       report reflects what's actually been posted.
     :posting-filter — optional vector of extra datalog :where clauses
                       (the posting is bound to `?p`) appended to the
                       candidate-posting query. Lets a consumer narrow
                       the pull-all-postings scan at the datalog level
                       — e.g. `[[?p :kontor.posting/posted-at ?pa]
                       [(< ?pa #inst \"2027-01-01\")]]`, or by a
                       literal ledger/entity eid. nil = scan all.
                       (A materialized / incremental report is a
                       deferred follow-up; this is the cheap mitigation.)
     :ledger         — optional ledger eid / lookup-ref. When set,
                       only postings on that ledger are summed (ADR-021
                       parallel books — the HGB-vs-IFRS Jahresabschluss
                       prerequisite). A nil-ledger posting counts as
                       the primary book.
     :entity         — optional entity eid / lookup-ref (ADR-031). When
                       set, only postings with that `:kontor.posting/entity`
                       are summed — trans-national per-entity reports.
     :translate-to   — optional ISO-4217 string (e.g. \"EUR\"). When
                       set, each line's `:line/value` Money is
                       translated into this commodity via the
                       FxRateProvider supplied as `:fx-provider`,
                       using `:rate-type` (default `:closing` —
                       conservative IAS 21 default for mixed BS/PL
                       reports; pass `:average` for pure-P&L reports).
                       The translated value is added as
                       `:line/value-translated`; the original
                       per-commodity `:line/value` is preserved for
                       audit. The `at-date` for the translation
                       defaults to the report's `:to` (or `now` if
                       no `:to` is supplied). Requires `:fx-provider`
                       when `:translate-to` is set.
     :fx-provider    — an FxRateProvider (ADR-072). Required when
                       `:translate-to` is set; ignored otherwise.
     :rate-type      — IAS 21 rate-type keyword for translation
                       (default `:closing`)."
  ([conn report] (compute-report conn report {}))
  ([conn report {:keys [translate-to fx-provider rate-type]
                 :or   {rate-type :closing}
                 :as   opts}]
   (when (and translate-to (nil? fx-provider))
     (throw (ex-info "compute-report: :translate-to requires :fx-provider"
                     {:translate-to translate-to})))
   (check-options! opts "kontor.report/compute-report")
   ;; Resolve :through → :to up front so the :report/window payload
   ;; reflects the canonical exclusive bound.
   (let [opts         (resolve-window opts)
         {:keys [from to]} opts
         filtered     (report-postings conn opts)
         translate-at (or to (now))
         lines (mapv (fn [{:keys [:line/code :line/label :line/expression]}]
                       ;; A report-level :strict-commodity? is a DEFAULT for
                       ;; every line: the engines read the flag off the
                       ;; expression, so without this it would be an option
                       ;; accepted and then ignored — the exact failure
                       ;; `check-options!` exists to prevent. A line that
                       ;; sets it explicitly still wins.
                       (let [expression (cond-> expression
                                          (and (contains? opts :strict-commodity?)
                                               (not (contains? expression :strict-commodity?)))
                                          (assoc :strict-commodity? (:strict-commodity? opts)))
                             {:keys [value postings]} (run-engine filtered expression {})
                             line {:line/code code
                                   :line/label label
                                   :line/value value
                                   :line/postings postings}]
                         (if translate-to
                           (assoc line :line/value-translated
                                  (fx/convert value fx-provider
                                              {:to translate-to
                                               :at-date translate-at
                                               :rate-type rate-type}))
                           line)))
                     (:report/lines report))]
     {:report/name (:report/name report)
      :report/country (:report/country report)
      :report/window {:from from :to to}
      :report/lines lines
      :report/translated-to translate-to
      :report/computed-at (now)})))

(defn line-value
  "Convenience: pull the Money value for `code` out of a computed
   report. Returns nil if the line isn't present."
  [computed code]
  (some (fn [l] (when (= code (:line/code l)) (:line/value l)))
        (:report/lines computed)))
