(ns kontor.reporting.financial-statements
  "Generic Profit & Loss and Balance Sheet builders.

   Both reports are *just* aggregations of postings keyed by account-
   code prefix, so we sit on top of `report.clj`'s :account-codes
   engine and add:

     - section grouping (lines roll up into named sections; sections
       roll up into a grand total)
     - point-in-time semantics for BS (cumulative since beginning of
       time, optionally as-of a tx-snapshot)
     - period semantics for P&L (between :from and :to dates)
     - a deterministic line ordering (preserved from the definition)

   Why no l10n bias here: P&L and BS are universal accounting concepts;
   only the *line layout* + the *account-code prefixes* are country-
   specific. So consumers (e.g. l10n-de.pnl, l10n-fr.pnl) supply a
   definition and we run it.

   Definition shape:

       {:statement/name \"Gewinn- und Verlustrechnung\"
        :statement/country \"DE\"
        :statement/sections
        [{:section/code   \"I\"
          :section/label  \"Umsatzerlöse\"
          :section/lines
          [{:line/code   \"1.1\"
            :line/label  \"Erlöse 19%\"
            :line/codes  [\"4400\" \"4410\"]}        ; or just \"4400%\" prefix
           {:line/code   \"1.2\"
            :line/label  \"Erlöse 7%\"
            :line/codes  [\"4300\"]}]}
         …]}

   Line :sign defaults to :inflow (natural balance: income credits +,
   debits -; expenses debits +, credits -). For BS, asset/liability
   sections likewise use :inflow so they read as positive presented
   numbers.

   `compute-statement` returns:

       {:statement/name str
        :statement/country str
        :statement/window {:from Date :to Date}
        :statement/sections
        [{:section/code, :section/label,
          :section/lines [{:line/code, :line/label, :line/value Money,
                           :line/postings [eid …]} …],
          :section/subtotal Money}]
        :statement/total Money}

   Total semantics:
     P&L total = Σ income − Σ expense = net income / loss
     BS total  = Σ assets − Σ (liabilities + equity)
                 = should be 0 for a balanced ledger; non-zero
                 surfaces an out-of-balance condition."
  (:require [datahike.api :as d]
            [kontor.money :as money]
            [kontor.reporting.report :as report]))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- line->expression
  "Translate a line spec to the :account-codes report-engine input."
  [{:line/keys [codes sign commodity strict-commodity?]
    :or {sign :inflow commodity :EUR}}]
  (cond-> {:engine    :account-codes
           :codes     codes
           :sign      sign
           :commodity commodity}
    ;; opt-in per line; a report-level :strict-commodity? supplies the
    ;; default for lines that do not set it (see compute-report)
    (some? strict-commodity?) (assoc :strict-commodity? strict-commodity?)))

(defn- line->report-line [line]
  {:line/code       (:line/code line)
   :line/label      (:line/label line)
   :line/expression (line->expression line)})

(defn- definition->report-def
  "Flatten a multi-section statement definition into a flat report
   definition for `report.clj`. Sections are tracked separately so we
   can re-bucket the computed lines."
  [statement]
  {:report/name    (:statement/name statement)
   :report/country (:statement/country statement)
   :report/lines   (->> (:statement/sections statement)
                        (mapcat :section/lines)
                        (mapv line->report-line))})

(defn- bucket-by-section
  [statement computed]
  (let [computed-by-code (into {} (map (juxt :line/code identity))
                               (:report/lines computed))]
    (mapv
     (fn [section]
       (let [lines (mapv (fn [l]
                           (let [c (computed-by-code (:line/code l))
                                 v (:line/value c)
                                 ;; :line/negate flips the sign of a
                                 ;; line — the knob the indirect
                                 ;; cash-flow + equity statements need
                                 ;; for working-capital / dividend
                                 ;; lines (a window aggregation that
                                 ;; reduces cash / equity).
                                 v (if (and v (:line/negate l))
                                     (update v :amount
                                             #(money/negate-amount %))
                                     v)]
                             {:line/code     (:line/code l)
                              :line/label    (:line/label l)
                              :line/value    v
                              :line/postings (:line/postings c)}))
                         (:section/lines section))
             subtotal (reduce (fn [acc l]
                                (money/add acc (:line/value l)))
                              (money/zero (or (:section/commodity section) :EUR))
                              lines)]
         {:section/code     (:section/code section)
          :section/label    (:section/label section)
          :section/lines    lines
          :section/subtotal subtotal}))
     (:statement/sections statement))))

(defn compute-statement
  "Run a P&L or BS definition against `conn`.

   EVERY option `report/compute-report` accepts is forwarded — `:from`
   `:to` `:through` `:as-of-tx` `:include-states` `:posting-filter`
   `:ledger` `:entity` `:translate-to` `:fx-provider` `:rate-type` — see
   that fn's docstring for each. Note in particular that `:to` is
   EXCLUSIVE and `:through` is the inclusive form: for calendar 2026 pass
   `:through #inst \"2026-12-31\"`, not `:to #inst \"2026-12-31\"`, or
   every entry posted ON December 31 (year-end depreciation, accruals)
   drops out of the statement.

   This fn adds one option of its own:
     :total-sign-map — optional map {section-code → :+ | :-}; if
                       provided, the :statement/total is computed as
                       Σ(sign × subtotal). If absent, we sum all
                       subtotals as-is.

   Unknown options throw `:report/unknown-option` rather than being
   ignored — see `report/check-options!` for why that matters here.

   Line specs may carry :line/negate true to flip a line's sign — the
   knob the indirect cash-flow + equity statements use for
   working-capital / dividend lines."
  ([conn statement] (compute-statement conn statement {}))
  ([conn statement {:keys [total-sign-map] :as opts}]
   (let [report-def (definition->report-def statement)
         ;; Forward everything EXCEPT this fn's own option. Rebuilding
         ;; the map from an allowlist here is what used to discard
         ;; :through / :translate-to / :posting-filter silently.
         computed (report/compute-report conn report-def
                                         (dissoc opts :total-sign-map))
         sections (bucket-by-section statement computed)
         currency (or (some-> sections first :section/subtotal :commodity)
                      :EUR)
         total (if total-sign-map
                 (reduce
                  (fn [acc s]
                    (let [sign (get total-sign-map (:section/code s) :+)
                          v (:section/subtotal s)]
                      (case sign
                        :+ (money/add acc v)
                        :- (money/sub acc v))))
                  (money/zero currency)
                  sections)
                 (reduce (fn [acc s] (money/add acc (:section/subtotal s)))
                         (money/zero currency)
                         sections))]
     {:statement/name      (:statement/name statement)
      :statement/country   (:statement/country statement)
      ;; Take the window from the computed report, not from the raw opts:
      ;; the engine has already resolved :through into the canonical
      ;; exclusive :to, so this reports the bounds actually applied.
      :statement/window    (:report/window computed)
      :statement/sections  sections
      :statement/total     total
      :statement/computed-at (now)})))

(defn section-subtotal
  "Convenience: pull a section's subtotal Money from a computed
   statement, by section :section/code."
  [computed code]
  (some (fn [s] (when (= code (:section/code s)) (:section/subtotal s)))
        (:statement/sections computed)))

(defn line-value
  "Convenience: pull a line's Money value by section + line code."
  [computed section-code line-code]
  (some (fn [s]
          (when (= section-code (:section/code s))
            (some (fn [l] (when (= line-code (:line/code l)) (:line/value l)))
                  (:section/lines s))))
        (:statement/sections computed)))

;; ============================================================================
;; Cash-flow statement (indirect method) — ADR-056
;; ============================================================================

(defn- account-codes-money
  "Sum the postings whose `:kontor.account/code` matches `codes` over the
   given window / ledger, via the `:account-codes` report engine.
   Always returns a Money (zero when nothing matches)."
  [conn codes {:keys [sign commodity] :or {sign :inflow commodity :EUR} :as opts}]
  (let [rep {:report/name "_internal"
             :report/lines [{:line/code "v"
                             :line/label "v"
                             :line/expression {:engine    :account-codes
                                               :codes     codes
                                               :sign      sign
                                               :commodity commodity}}]}
        ;; `:sign` / `:commodity` are consumed here into the line
        ;; expression; everything else is a real engine option and gets
        ;; forwarded verbatim.
        computed (report/compute-report conn rep (dissoc opts :sign :commodity))]
    (or (report/line-value computed "v") (money/zero commodity))))

(defn compute-cash-flow
  "Indirect-method cash-flow statement (Kapitalflussrechnung / DRS 21
   / IAS 7).

   The definition is an ordinary `compute-statement` definition —
   but EVERY line is a WINDOW DELTA: the sum of postings to those
   account codes between `:from` and `:to`. That works because the
   change in any account over a window IS the sum of its postings in
   that window — net income, the depreciation add-back, and every
   working-capital movement are all window aggregations. l10n
   supplies the definition (which account → which section, sign via
   `:line/negate`); the kernel runs it.

   Requires `:from` and `:to` — it is a window statement.

   Opts (forwarded to `compute-statement`): `:from` `:to` `:as-of-tx`
   `:include-states` `:ledger` `:total-sign-map`. Plus:
     :reconcile-codes — optional account-code patterns for the cash /
                        cash-equivalent accounts. When set, the actual
                        cash delta over the window is computed and
                        returned as :statement/reconciliation
                        {:expected :actual :difference :ok?} — the
                        indirect-method statement should reconcile to
                        the real movement on the cash accounts."
  ([conn statement] (compute-cash-flow conn statement {}))
  ([conn statement {:keys [from to through reconcile-codes] :as opts}]
   (when-not (and from (or to through))
     (throw (ex-info "compute-cash-flow requires :from and :to/:through (it is a window statement)"
                     {:from from :to to :through through})))
   (let [computed   (compute-statement conn statement (dissoc opts :reconcile-codes))
         net-change (:statement/total computed)
         recon (when reconcile-codes
                 (let [actual (account-codes-money
                               conn reconcile-codes
                               (assoc (dissoc opts :reconcile-codes :total-sign-map)
                                      :sign :inflow
                                      :commodity (:commodity net-change)))
                       diff (money/sub actual net-change)]
                   {:expected   net-change
                    :actual     actual
                    :difference diff
                    :ok?        (money/zero? diff)}))]
     (cond-> (assoc computed :statement/kind :cash-flow)
       recon (assoc :statement/reconciliation recon)))))

;; ============================================================================
;; Statement of changes in equity (Eigenkapitalspiegel) — ADR-056
;; ============================================================================

(defn compute-equity-changes
  "Statement of changes in equity (Eigenkapitalspiegel / DRS 22 /
   IAS 1) — needed because the IAS 16 revaluation surplus flows
   through OCI/equity, not P&L.

   The definition is a `:statement/components` vector — one entry per
   equity component (share capital, retained earnings, revaluation
   surplus, OCI):

     {:statement/name \"Eigenkapitalspiegel\"
      :statement/country \"DE\"
      :statement/components
      [{:component/code \"revaluation-surplus\"
        :component/label \"Neubewertungsrücklage\"
        :component/codes [\"2920\"]               ; the equity account(s)
        :component/movements
        [{:movement/code \"reval\" :movement/label \"Neubewertung\"
          :movement/codes [\"2920\"]}             ; a window aggregation
         {:movement/code \"div\"  :movement/label \"Ausschüttung\"
          :movement/codes [\"2920\"] :movement/negate true}]}
       …]}

   Per component the kernel computes the opening balance
   (point-in-time at `:from`), each movement (a window aggregation
   over `:from`..`:to`), the closing balance (point-in-time at `:to`),
   and `:component/reconciles?` — whether opening + Σmovements equals
   closing. If the l10n definition's movement lines partition the
   component's window activity, it reconciles; the kernel just runs
   it and checks.

   Requires `:from` and `:to` (or `:through`, the inclusive form). Every
   other `report/compute-report` option is forwarded."
  ([conn statement] (compute-equity-changes conn statement {}))
  ([conn statement opts]
   ;; This fn INTERPRETS the window — it re-queries each component at
   ;; :from and at :to — so it must normalise :through up front rather
   ;; than forward it, or `to` below reads nil on a bounded request.
   (let [{:keys [from to] :as opts} (report/resolve-window opts)
         _ (when-not (and from to)
             (throw (ex-info "compute-equity-changes requires :from and :to/:through"
                             {:from from :to to})))
         ;; the window is supplied per query below, so strip it here
         base (dissoc opts :from :to)
         components
         (mapv
          (fn [comp]
            (let [codes   (:component/codes comp)
                  opening (account-codes-money conn codes
                                               (assoc base :to from :sign :inflow))
                  closing (account-codes-money conn codes
                                               (assoc base :to to :sign :inflow))
                  movements
                  (mapv (fn [m]
                          (let [v (account-codes-money
                                   conn (:movement/codes m)
                                   (assoc base :from from :to to :sign :inflow))
                                v (if (:movement/negate m)
                                    (update v :amount
                                            #(money/negate-amount %))
                                    v)]
                            {:movement/code  (:movement/code m)
                             :movement/label (:movement/label m)
                             :movement/value v}))
                        (:component/movements comp))
                  sum-mov (reduce (fn [acc m] (money/add acc (:movement/value m)))
                                  (money/zero (:commodity opening))
                                  movements)
                  expected (money/add opening sum-mov)]
              {:component/code        (:component/code comp)
               :component/label       (:component/label comp)
               :component/opening     opening
               :component/movements   movements
               :component/closing     closing
               :component/reconciles? (money/zero? (money/sub closing expected))}))
          (:statement/components statement))
         currency (or (some-> components first :component/opening :commodity) :EUR)
         total-opening (reduce (fn [acc c] (money/add acc (:component/opening c)))
                               (money/zero currency) components)
         total-closing (reduce (fn [acc c] (money/add acc (:component/closing c)))
                               (money/zero currency) components)]
     {:statement/name        (:statement/name statement)
      :statement/country     (:statement/country statement)
      :statement/kind        :equity-changes
      :statement/window      {:from from :to to}
      :statement/components  components
      :statement/total-opening total-opening
      :statement/total-closing total-closing
      :statement/computed-at (now)})))

;; ============================================================================
;; Definition ↔ chart coverage
;; ============================================================================

(defn statement-coverage
  "Check a statement DEFINITION against the accounts actually in `db`.

   A statement line claims a set of account codes. Nothing has ever
   checked that claim against the chart, and both directions bite:

   - An account matched by NO line is money the statement cannot show.
     If it is a balance-sheet account, the statement stops balancing —
     silently, and by exactly that account's balance. This is how the
     shipped DE Bilanz came to omit the tax-provision accounts that
     `doc/quickstart.md` tells the reader to post to (note 194 §1).
   - An account matched by MORE THAN ONE line inside the same statement
     is counted twice.
   - A code no account matches is a line that renders as a permanent
     zero. That is not always a defect — a definition may deliberately
     cover a fuller chart than the module ships — so it is reported
     separately and judged by the caller.

   Returns

     {:covered        {account-eid [line-code …]}
      :uncovered      [{:code :path :type :eid} …]
      :double-counted [{:code :path :lines [line-code …]} …]
      :dangling       [pattern …]}

   `opts`:
     :account-types — set of `:kontor.account/type` values to require
                      coverage of. A balance sheet passes
                      `#{:asset :liability :equity}`, a P&L
                      `#{:income :expense}`; without it every account in
                      the chart must be covered.

   Accounts carrying no `:kontor.account/code` are skipped — the
   `:account-codes` engine cannot see them either."
  ([db statement] (statement-coverage db statement {}))
  ([db statement {:keys [account-types]}]
   (let [lines    (mapcat :section/lines (:statement/sections statement))
         accounts (->> (d/q '[:find ?a ?code
                              :where
                              [?a :kontor.account/code ?code]]
                            db)
                       (mapv (fn [[eid code]]
                               (merge {:eid eid :code code}
                                      (d/pull db [:kontor.account/path
                                                  :kontor.account/type] eid)))))
         in-scope (if account-types
                    (filter #(contains? account-types (:kontor.account/type %)) accounts)
                    accounts)
         hits     (fn [{:keys [code]}]
                    (into [] (keep (fn [l]
                                     (when (report/code-prefix-match? code (:line/codes l))
                                       (:line/code l))))
                          lines))
         by-acct  (into {} (map (juxt identity hits)) in-scope)]
     {:covered (into {} (keep (fn [[a ls]] (when (seq ls) [(:eid a) ls]))) by-acct)
      :uncovered (->> by-acct
                      (keep (fn [[a ls]]
                              (when (empty? ls)
                                {:code (:code a) :path (:kontor.account/path a)
                                 :type (:kontor.account/type a) :eid (:eid a)})))
                      (sort-by :code) vec)
      :double-counted (->> by-acct
                           (keep (fn [[a ls]]
                                   (when (> (count ls) 1)
                                     {:code (:code a) :path (:kontor.account/path a)
                                      :lines ls})))
                           (sort-by :code) vec)
      :dangling (->> lines
                     (mapcat :line/codes)
                     distinct
                     (remove (fn [pattern]
                               (some #(report/code-prefix-match? (:code %) [pattern])
                                     accounts)))
                     sort vec)})))
