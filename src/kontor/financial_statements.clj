(ns datahike-accounting.financial-statements
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
            [datahike-accounting.money :as money]
            [datahike-accounting.report :as report])
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
                           (let [c (computed-by-code (:line/code l))]
                             {:line/code     (:line/code l)
                              :line/label    (:line/label l)
                              :line/value    (:line/value c)
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
                              all subtotals as-is."
  ([conn statement] (compute-statement conn statement {}))
  ([conn statement {:keys [from to as-of-tx include-states total-sign-map]
                    :as opts}]
   (let [report-def (definition->report-def statement)
         computed (report/compute-report conn report-def
                                         (cond-> {}
                                           from           (assoc :from from)
                                           to             (assoc :to to)
                                           as-of-tx       (assoc :as-of-tx as-of-tx)
                                           include-states (assoc :include-states include-states)))
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
