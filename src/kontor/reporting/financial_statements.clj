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
  (:require [kontor.money :as money]
            [kontor.reporting.report :as report])
  (:import [java.util Date]))

(defn- now ^Date [] (Date.))

(defn- line->expression
  "Translate a line spec to the :account-codes report-engine input."
  [{:line/keys [codes sign commodity] :or {sign :inflow commodity :EUR}}]
  {:engine    :account-codes
   :codes     codes
   :sign      sign
   :commodity commodity})

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
                                             #(.negate ^java.math.BigDecimal %))
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
  "Run a P&L or BS definition against `conn`. Options forwarded to
   `report/compute-report`:
     :from / :to           — window (BS typically uses :from nil, :to
                              the as-of cutoff)
     :as-of-tx             — datahike snapshot
     :include-states       — set; defaults to #{:posted}
     :total-sign-map       — optional map {section-code → :+ | :-}; if
                              provided, the :statement/total is computed
                              as Σ(sign × subtotal). If absent, we sum
                              all subtotals as-is.
     :ledger               — optional ledger eid / lookup-ref; restrict
                              the statement to one parallel book (the
                              HGB-vs-IFRS Jahresabschluss prerequisite —
                              ADR-021). A nil-ledger posting counts as
                              the primary book.
     :entity               — optional entity eid / lookup-ref (ADR-031);
                              restrict the statement to a single legal
                              entity. Trans-national groups produce
                              per-entity statements before consolidation.

   Line specs may carry :line/negate true to flip a line's sign — the
   knob the indirect cash-flow + equity statements use for
   working-capital / dividend lines."
  ([conn statement] (compute-statement conn statement {}))
  ([conn statement {:keys [from to as-of-tx include-states total-sign-map ledger entity]}]
   (let [report-def (definition->report-def statement)
         computed (report/compute-report conn report-def
                                         (cond-> {}
                                           from           (assoc :from from)
                                           to             (assoc :to to)
                                           as-of-tx       (assoc :as-of-tx as-of-tx)
                                           include-states (assoc :include-states include-states)
                                           ledger         (assoc :ledger ledger)
                                           entity         (assoc :entity entity)))
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
      :statement/window    {:from from :to to}
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
  [conn codes {:keys [from to as-of-tx include-states ledger sign commodity]
               :or {sign :inflow commodity :EUR}}]
  (let [rep {:report/name "_internal"
             :report/lines [{:line/code "v"
                             :line/label "v"
                             :line/expression {:engine    :account-codes
                                               :codes     codes
                                               :sign      sign
                                               :commodity commodity}}]}
        computed (report/compute-report
                  conn rep
                  (cond-> {}
                    from           (assoc :from from)
                    to             (assoc :to to)
                    as-of-tx       (assoc :as-of-tx as-of-tx)
                    include-states (assoc :include-states include-states)
                    ledger         (assoc :ledger ledger)))]
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
  ([conn statement {:keys [from to reconcile-codes] :as opts}]
   (when-not (and from to)
     (throw (ex-info "compute-cash-flow requires :from and :to (it is a window statement)"
                     {:from from :to to})))
   (let [computed   (compute-statement conn statement opts)
         net-change (:statement/total computed)
         recon (when reconcile-codes
                 (let [actual (account-codes-money
                               conn reconcile-codes
                               (assoc (select-keys opts [:as-of-tx :include-states :ledger])
                                      :from from :to to :sign :inflow
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

   Requires `:from` and `:to`. Opts: `:as-of-tx` `:include-states`
   `:ledger`."
  ([conn statement] (compute-equity-changes conn statement {}))
  ([conn statement {:keys [from to] :as opts}]
   (when-not (and from to)
     (throw (ex-info "compute-equity-changes requires :from and :to"
                     {:from from :to to})))
   (let [base (select-keys opts [:as-of-tx :include-states :ledger])
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
                                            #(.negate ^java.math.BigDecimal %))
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
