(ns kontor.banking.statement-tie-out
  "Control total for a parsed bank statement (ADR-131).

   A statement that ships a running-balance column is an oracle against
   ITSELF: the bank has already told you what every amount must be. If
   the parsed amounts reproduce the bank's balance column row by row,
   the parse is right — right sign, right decimal convention, no dropped
   row, no ignored column. If they don't, something is wrong and this
   says exactly where.

   This exists because the assertion it replaces —
   `(is (>= ratio 0.5))` on the count of rows that parsed non-zero —
   cannot distinguish +3850.00 from -3850.00, nor 82.40 from 8240, nor
   15 rows from 16. Every importer amount assertion in `modules/bank-*`
   is now a control total, never a ratio.

   Row order is INFERRED, not assumed: banks export both ascending
   (oldest first) and descending (newest first) and the same bank does
   both depending on the export screen.

   TWO THINGS THE BALANCE COLUMN CANNOT CATCH, both verified by test:

   1. **A uniform scale error.** If the amounts are all 100x too large
      the balance column is too, because it is parsed with the same
      `:number-format`. The chain stays self-consistent. What catches
      this is an EXACT expected amount, or cross-file agreement between
      two exports of the same statement in different decimal
      conventions.
   2. **Truncation at either end.** The opening balance is DERIVED from
      the first row, so dropping the first (or last) transaction just
      shifts the derived opening. Pass `:opening` / `:closing` from the
      statement's own preamble — most banks publish them — and the ends
      are pinned too.

   So this is the strongest oracle available, not a sufficient one. The
   importer tests pair it with exact signed amounts."
  (:require [clojure.string :as str]))

(defn- bd= [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (zero? (.compareTo a b)))

(defn- sum-amounts ^java.math.BigDecimal [candidates]
  (reduce (fn [^java.math.BigDecimal acc c]
            (.add acc ^java.math.BigDecimal (or (:amount c) 0M)))
          0M candidates))

(defn- breaks
  "Rows where the balance column and the parsed amount disagree, under
   the given direction hypothesis."
  [rows direction]
  (let [n (count rows)]
    (vec
     (for [i (range 1 n)
           :let [prev (nth rows (dec i))
                 cur  (nth rows i)
                 ;; ascending: bal[i] - bal[i-1] = amt[i]
                 ;; descending: bal[i-1] - bal[i] = amt[i-1]
                 [^java.math.BigDecimal expected ^java.math.BigDecimal actual owner]
                 (if (= :ascending direction)
                   [(.subtract ^java.math.BigDecimal (:balance cur)
                               ^java.math.BigDecimal (:balance prev))
                    (:amount cur) cur]
                   [(.subtract ^java.math.BigDecimal (:balance prev)
                               ^java.math.BigDecimal (:balance cur))
                    (:amount prev) prev])]
           :when (not (bd= expected actual))]
       {:index i
        :expected-from-balance expected
        :parsed-amount actual
        :description (:description owner)
        :raw-row (:raw-row owner)}))))

(defn statement-tie-out
  "Check parsed `candidates` (IN FILE ORDER) against the bank's own
   running-balance column.

   Returns a map:
     :ok?        — every adjacent balance delta equals the parsed amount
                   AND the aggregate Σ equals closing − opening
     :direction  — :ascending | :descending, inferred
     :opening    — balance before the first transaction
     :closing    — balance after the last transaction
     :sum        — Σ of the parsed amounts over the balance-carrying rows
     :expected   — closing − opening
     :breaks     — the disagreeing rows (empty when :ok?)
     :n          — how many rows carried a balance
     :coverage   — n / (count candidates)

   `opts` may carry `:opening` and/or `:closing` — the balances the
   STATEMENT declares in its own preamble. Supplying them pins the ends
   of the chain, which the derived opening cannot do (see the namespace
   docstring): without them, dropping the first or last transaction is
   invisible. Mismatches land in `:breaks` tagged `:end`.

   When fewer than two rows carry a balance there is nothing to tie out
   and the result is `{:ok? false :reason :no-balance-column}` — a
   caller must not read that as a pass."
  ([candidates] (statement-tie-out candidates nil))
  ([candidates {declared-opening :opening declared-closing :closing}]
   (let [rows (vec (filter :balance candidates))
         n    (count rows)]
     (if (< n 2)
       {:ok? false :reason :no-balance-column :n n}
       (let [asc  (breaks rows :ascending)
             desc (breaks rows :descending)
            ;; The hypothesis with fewer breaks is the row order; a tie
            ;; at zero is impossible for a real statement (it would mean
            ;; every amount is zero), a tie above zero means neither
            ;; hypothesis holds and we report against ascending.
             direction (if (<= (count asc) (count desc)) :ascending :descending)
             chain     (if (= :ascending direction) asc desc)
             first-row (nth rows 0)
             last-row  (nth rows (dec n))
             [^java.math.BigDecimal opening ^java.math.BigDecimal closing]
             (if (= :ascending direction)
               [(.subtract ^java.math.BigDecimal (:balance first-row)
                           ^java.math.BigDecimal (:amount first-row))
                (:balance last-row)]
               [(.subtract ^java.math.BigDecimal (:balance last-row)
                           ^java.math.BigDecimal (:amount last-row))
                (:balance first-row)])
             end-breaks (cond-> []
                          (and declared-opening (not (bd= declared-opening opening)))
                          (conj {:index :opening :end true
                                 :expected-from-balance declared-opening
                                 :parsed-amount opening
                                 :description "declared opening balance"})
                          (and declared-closing (not (bd= declared-closing closing)))
                          (conj {:index :closing :end true
                                 :expected-from-balance declared-closing
                                 :parsed-amount closing
                                 :description "declared closing balance"}))
             bs       (into (vec end-breaks) chain)
             sum      (sum-amounts rows)
             expected (.subtract closing opening)]
         {:ok?       (and (empty? bs) (bd= sum expected))
          :direction direction
          :opening   opening
          :closing   closing
          :sum       sum
          :expected  expected
          :breaks    bs
          :n         n
          :coverage  (/ (double n) (max 1 (count candidates)))})))))

(defn explain
  "Human-readable failure text for a `statement-tie-out` result — used as
   the `is` message so a broken parse names the offending row instead of
   printing a bare `false`."
  [label result]
  (cond
    (= :no-balance-column (:reason result))
    (str label ": no running-balance column to tie out against")

    (:ok? result) (str label ": ok")

    :else
    (str label ": statement does not tie out. "
         "direction=" (name (:direction result))
         " opening=" (:opening result) " closing=" (:closing result)
         " Σparsed=" (:sum result) " expected=" (:expected result)
         (when (seq (:breaks result))
           (str "\n  " (count (:breaks result)) " row(s) disagree with the balance column:\n"
                (str/join "\n"
                          (for [b (take 5 (:breaks result))]
                            (str "    row " (:index b)
                                 " balance implies " (:expected-from-balance b)
                                 " but parsed " (:parsed-amount b)
                                 " — " (pr-str (:description b))))))))))

(defn total-tie-out
  "Σ of the parsed amounts. The control total for a layout with NO
   running-balance column: the caller supplies the golden Σ, verified
   cell-by-cell against the fixture's own amount column."
  ^java.math.BigDecimal [candidates]
  (sum-amounts candidates))
