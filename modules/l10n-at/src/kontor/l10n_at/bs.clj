(ns kontor.l10n-at.bs
  "Austrian balance sheet — Bilanz per UGB § 224, classified form.

   § 224 UGB fixes the Gliederung. Abs. 2 = Aktiva (assets), in
   increasing order of liquidity as the statute lists them:

     A. Anlagevermögen  (fixed assets)              — class 0
     B. Umlaufvermögen  (current assets)            — class 2
     C. Rechnungsabgrenzungsposten  (accruals)      — (no accounts yet)

   Abs. 3 = Passiva (equity + liabilities + accruals):

     A. Eigenkapital    (equity)                    — class 9
     B. Rückstellungen  (provisions)                — (no accounts yet)
     C. Verbindlichkeiten (liabilities)             — class 3
     D. Rechnungsabgrenzungsposten                  — (no accounts yet)

   Account codes target the RLG-aligned Einheitskontenrahmen skeleton in
   `kontor.l10n-at.chart`. Class boundaries map cleanly onto the § 224
   positions, so prefix patterns (`\"0%\"` = all class-0 Anlagevermögen)
   pick up consumer-added accounts without touching this definition.

   Vorsteuer (input VAT, class 25xx) is an asset — a receivable from the
   Finanzamt — so it sits under B. Umlaufvermögen; Umsatzsteuer (output
   VAT, class 35xx) is a liability under C. Verbindlichkeiten. Neither
   touches the P&L (see `kontor.l10n-at.pnl`).

   ## Why Eigenkapital carries a Periodenergebnis line

   A balance sheet only balances when the period's result is IN equity.
   Under § 224 Abs. 3 A.IV the un-appropriated result lives in the
   Bilanzgewinn/-verlust position; but until the fiscal year is closed
   (`kontor.l10n-at.closing`) the 4xxx–7xxx accounts still hold it,
   sitting OUTSIDE equity, so assets would exceed Eigenkapital +
   Verbindlichkeiten by exactly the period's Jahresüberschuss.

   Section C therefore carries the current-period result as two lines —
   the Erträge (4xxx) and the Aufwendungen (5xxx–7xxx) flipped by
   `:line/negate` — so the statement balances both before and after a
   close. Once the P&L accounts are rolled into the Bilanzgewinn account
   those two lines compute to zero and A.IV (Bilanzgewinn) carries the
   amount instead. HGB/UGB regulate the Jahresabschluss, not interim
   balance sheets, so using the A.IV slot for a running mid-year result
   is a defensible convention by analogy with the vor-Ergebnis-
   verwendung default, not something § 224 mandates.

   `check` reports whether Σ Aktiva = Σ (Eigenkapital + Verbindlich-
   keiten) actually holds — see its docstring for what a non-zero
   difference means.

   Note-196 F5: commodity is derived from the postings by the report
   engine, so no `:line/`/`:section/commodity` is stamped."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def definition
  "Classified Bilanz over the `kontor.l10n-at.chart` codes — one
   statement with both sides, summed to zero via `sign-map`."
  {:statement/name    "Bilanz"
   :statement/country "AT"
   :statement/sections
   [{:section/code  "A"
     :section/label "Anlagevermögen"
     :section/lines
     [{:line/code "A.II" :line/label "Sachanlagen"
       :line/codes ["0%"]}]}

    {:section/code  "B"
     :section/label "Umlaufvermögen"
     :section/lines
     [{:line/code "B.II.1" :line/label "Forderungen aus Lieferungen und Leistungen"
       :line/codes ["20%"]}
      {:line/code "B.II.2" :line/label "Sonstige Forderungen (Vorsteuer)"
       :line/codes ["25%"]}
      {:line/code "B.IV" :line/label "Kassenbestand, Guthaben bei Kreditinstituten"
       :line/codes ["27%" "28%"]}]}

    {:section/code  "C"
     :section/label "Eigenkapital"
     :section/lines
     [{:line/code "C.I"   :line/label "Nennkapital / Stammkapital"
       :line/codes ["9000"]}
      {:line/code "C.II"  :line/label "Kapitalrücklagen" :line/codes ["9100"]}
      {:line/code "C.III" :line/label "Gewinnrücklagen" :line/codes ["9200"]}
      {:line/code "C.IV"  :line/label "Bilanzgewinn (Vortrag)" :line/codes ["9460"]}
      ;; Current-period result, held outside equity until the fiscal year
      ;; is closed — see the namespace docstring. C.V.a is revenue gross,
      ;; C.V.b the negated expense side; their sum is the Jahresüberschuss.
      {:line/code "C.V.a" :line/label "Periodenergebnis — Erträge"
       :line/codes ["4%"]}
      {:line/code "C.V.b" :line/label "Periodenergebnis — Aufwendungen"
       :line/codes ["5%" "6%" "7%"]
       :line/negate true}]}

    {:section/code  "D"
     :section/label "Verbindlichkeiten"
     :section/lines
     [{:line/code "D.1" :line/label "Verbindlichkeiten aus Lieferungen und Leistungen"
       :line/codes ["3300"]}
      {:line/code "D.2" :line/label "Umsatzsteuer (Zahllast)"
       :line/codes ["35%"]}]}]})

(def ^:private sign-map
  "Aktiva add; Eigenkapital and Verbindlichkeiten subtract. Total =
   Σ Aktiva − Σ (Eigenkapital + Verbindlichkeiten), which is zero for a
   balanced book."
  {"A" :+ "B" :+ "C" :- "D" :-})

(defn compute
  "Compute the Bilanz as of a point in time.

   A balance sheet is cumulative since inception, so pass the as-of date
   and leave `:from` nil. Other opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:as-of-tx` `:include-states` `:ledger` `:entity`).

   `:to` is EXCLUSIVE — for a Dec 31 Bilanz pass `:through #inst
   \"2026-12-31\"` (inclusive) or `:to #inst \"2027-01-01\"`.

   Returns the standard computed-statement map plus:

     :at.bs/summe-aktiva     = sections A + B
     :at.bs/eigenkapital     = section C
     :at.bs/verbindlichkeiten= section D
     :at.bs/difference       = Aktiva − (Eigenkapital + Verbindlich-
                               keiten); zero for a balanced book
     :at.bs/balanced?        = whether that difference is zero"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         aktiva   (reduce money/add (map sub ["A" "B"]))
         ek       (sub "C")
         vb       (sub "D")
         diff     (money/sub aktiva (money/add ek vb))]
     (assoc computed
            :at.bs/summe-aktiva      aktiva
            :at.bs/eigenkapital      ek
            :at.bs/verbindlichkeiten vb
            :at.bs/difference        diff
            :at.bs/balanced?         (money/zero? diff)))))

(defn check
  "Assert the accounting equation for the book as of `:to`/`:through`.
   Returns the `:at.bs/*` summary of [[compute]].

   A non-zero `:at.bs/difference` means real money is unaccounted for by
   this DEFINITION — most often an account whose code falls outside every
   `:line/codes` pattern above (a customer-added account in a range the
   definition does not cover). It does NOT mean an unbalanced ledger: the
   kernel's transact gate refuses any entry whose postings do not sum to
   zero, so the ledger itself cannot drift. Compare against
   `kontor.reporting.trial/trial-balance` to find the stray account."
  [conn opts]
  (-> (compute conn opts)
      (select-keys [:at.bs/summe-aktiva :at.bs/eigenkapital
                    :at.bs/verbindlichkeiten :at.bs/difference
                    :at.bs/balanced?])))
