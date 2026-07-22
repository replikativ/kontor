(ns kontor.l10n-de.bs
  "German Bilanz (Balance Sheet) per HGB §266.

   §266 Abs. 2 = Aktiva (assets) side:
     A. Anlagevermögen (fixed assets)         — SKR04 0xxx
     B. Umlaufvermögen (current assets)       — SKR04 1xxx
     C. Rechnungsabgrenzungsposten (RAP)      — SKR04 1900s

   §266 Abs. 3 = Passiva (equity + liabilities) side:
     A. Eigenkapital (equity)                 — SKR04 2xxx, 9000s
     B. Rückstellungen (provisions)           — SKR04 3000s
     C. Verbindlichkeiten (liabilities)       — SKR04 33xx, 38xx
     D. RAP                                   — SKR04 3900s

   The §266 Abs. 1 size-class abridgements (kleinst, klein, mittel,
   groß) are out of scope here — we ship the full layout; size-class
   compression is just a cosmetic re-bucketing of the same numbers.

   ## A.V and the current-period result

   § 266 Abs. 3 A. runs I. Gezeichnetes Kapital, II. Kapitalrücklage,
   III. Gewinnrücklagen, IV. Gewinnvortrag/Verlustvortrag,
   V. Jahresüberschuß/Jahresfehlbetrag — so an un-appropriated period
   result has its own statutory equity position, and presenting it
   there is the HGB baseline: § 268 Abs. 1 (\"Die Bilanz darf auch
   unter Berücksichtigung der ... Verwendung des Jahresergebnisses
   aufgestellt werden\") is a Wahlrecht that DISPLACES that default, not
   the other way round. Under teilweiser Verwendung, Bilanzgewinn/
   Bilanzverlust substitutes for A.IV and A.V together.

   Before the fiscal year is closed, the period result still sits in the
   4xxx-7xxx accounts rather than in equity, so A.V is computed from
   them here (Erträge on A.V.a, Aufwendungen negated on A.V.b). Once
   `closing/close-fiscal-year!` has rolled them into the Gewinnvortrag
   account both lines compute to zero and A.IV carries the amount —
   the statement balances either side of the close.

   Caveat worth stating plainly: HGB regulates the *Jahresabschluss*.
   It says nothing about interim balance sheets, so using the A.V slot
   for a running mid-year result is a defensible convention by analogy
   with the vor-Ergebnisverwendung default — not something § 268 Abs. 1
   authorises."
  (:require [kontor.reporting.financial-statements :as fs]))

(def aktiva-definition
  {:statement/name    "Bilanz — Aktiva"
   :statement/country "DE"
   :statement/sections
   [{:section/code  "A"
     :section/label "Anlagevermögen"
     :section/lines
     [{:line/code "A.I"   :line/label "Immaterielle Vermögensgegenstände"
       :line/codes ["0100" "0110" "0120" "0130" "0140" "0150"]}
      {:line/code "A.II"  :line/label "Sachanlagen"
       :line/codes ["0200" "0240" "0410" "0420" "0440" "0670" "0680" "0690"]}
      {:line/code "A.III" :line/label "Finanzanlagen"
       :line/codes ["0900" "0920" "0940" "0960"]}]}

    {:section/code  "B"
     :section/label "Umlaufvermögen"
     :section/lines
     [{:line/code "B.I"   :line/label "Vorräte"
       :line/codes ["1100" "1110" "1130" "1140" "1150"]}
      {:line/code "B.II"  :line/label "Forderungen und sonstige Vermögensgegenstände"
       :line/codes ["1400" "1410" "1500" "1571" "1576" "1577" "1578"]}
      {:line/code "B.III" :line/label "Kassenbestand, Bankguthaben"
       :line/codes ["1000" "1200" "1210" "1220" "1230" "1290"]}]}

    {:section/code  "C"
     :section/label "Rechnungsabgrenzungsposten"
     :section/lines
     [{:line/code "C.1" :line/label "Aktive RAP"
       :line/codes ["1900" "1910" "1920"]}]}]})

(def passiva-definition
  "Passivseite per HGB § 266 Abs. 3 — A. Eigenkapital (I–V), B.
   Rückstellungen (1–3), C. Verbindlichkeiten (1–8), D. RAP.

   Section letters and roman/arabic numerals below are the statutory
   ones, verbatim from gesetze-im-internet.de/hgb/__266.html."
  {:statement/name    "Bilanz — Passiva"
   :statement/country "DE"
   :statement/sections
   [{:section/code  "A"
     :section/label "Eigenkapital"
     :section/lines
     [{:line/code "A.I"   :line/label "Gezeichnetes Kapital"
       :line/codes ["2910" "2920"]}
      {:line/code "A.II"  :line/label "Kapitalrücklage"
       :line/codes ["2930"]}
      {:line/code "A.III" :line/label "Gewinnrücklagen"
       :line/codes ["2940" "2950" "2960"]}
      ;; 2900 is "Gewinnvortrag vor Verwendung" in the shipped chart —
      ;; § 266 Abs. 3 A.IV, not A.I where it used to sit.
      {:line/code "A.IV"  :line/label "Gewinnvortrag/Verlustvortrag"
       :line/codes ["2900" "2970" "2978"]}
      ;; A.V is the statutory home of an un-appropriated period result.
      ;; See the namespace docstring for why it is computed from the P&L
      ;; accounts rather than read off an equity account.
      {:line/code "A.V.a" :line/label "Jahresüberschuss — Erträge"
       :line/codes ["4%" "7000" "7100" "7200"]}
      {:line/code "A.V.b" :line/label "Jahresüberschuss — Aufwendungen"
       :line/codes ["5%" "6%" "7300" "7610" "7681"]
       :line/negate true}
      ;; Not a § 266 position: Einzelunternehmen / Personengesellschaft
      ;; only, where §§ 264 ff. do not bind. A Kapitalgesellschaft chart
      ;; has no Privatkonten and this line computes to zero.
      {:line/code "A.VI"  :line/label "Privatentnahmen / -einlagen (nicht-KapG)"
       :line/codes ["2000" "2100"]}]}

    {:section/code  "B"
     :section/label "Rückstellungen"
     :section/lines
     [{:line/code "B.1" :line/label "Rückstellungen für Pensionen und ähnliche Verpflichtungen"
       :line/codes ["3020" "3030"]}
      ;; § 266 Abs. 3 B.2 verbatim: "Steuerrückstellungen". The shipped
      ;; chart books KSt/GewSt provisions to 3050/3060; the older codes
      ;; are kept for charts that carry them.
      {:line/code "B.2" :line/label "Steuerrückstellungen"
       :line/codes ["3000" "3010" "3040" "3050" "3060" "3065"]}
      {:line/code "B.3" :line/label "Sonstige Rückstellungen"
       :line/codes ["3070" "3074" "3075" "3090"]}]}

    {:section/code  "C"
     :section/label "Verbindlichkeiten"
     :section/lines
     [{:line/code "C.2" :line/label "Verbindlichkeiten gegenüber Kreditinstituten"
       :line/codes ["3150" "3160"]}
      {:line/code "C.3" :line/label "Erhaltene Anzahlungen auf Bestellungen"
       :line/codes ["3270" "3280"]}
      {:line/code "C.4" :line/label "Verbindlichkeiten aus Lieferungen und Leistungen"
       :line/codes ["3300" "3310"]}
      ;; § 266 Abs. 3 C.8. A declared but unpaid dividend has no
      ;; statutory line of its own — the word "Gesellschafter" does not
      ;; occur in § 266 Abs. 3 — so it lands here (C.6/C.7 take
      ;; precedence where the shareholder is a verbundenes or
      ;; Beteiligungs-Unternehmen). § 264c Abs. 1 makes a separate
      ;; disclosure mandatory for a KapCoGes; a consumer that needs it
      ;; adds the line.
      {:line/code "C.8"   :line/label "Sonstige Verbindlichkeiten"
       :line/codes ["3680" "3700" "3820"]}
      ;; the statute expresses these as davon-Vermerke of C.8, not as
      ;; separate positions — shown separately here so the numbers are
      ;; visible, and summed into the same section
      {:line/code "C.8.a" :line/label "davon aus Steuern"
       :line/codes ["3500" "3691" "3801" "3806"]}]}

    {:section/code  "D"
     :section/label "Rechnungsabgrenzungsposten"
     :section/lines
     [{:line/code "D.1" :line/label "Passive RAP"
       :line/codes ["3900" "3910"]}]}]})

(defn compute-aktiva
  "Compute the Aktiva (assets) side as-of `to` (point-in-time)."
  [conn {:keys [to as-of-tx include-states] :as opts}]
  (fs/compute-statement conn aktiva-definition
                        (cond-> {:from nil}
                          to             (assoc :to to)
                          as-of-tx       (assoc :as-of-tx as-of-tx)
                          include-states (assoc :include-states include-states))))

(defn compute-passiva
  "Compute the Passiva (equity + liabilities) side as-of `to`."
  [conn {:keys [to as-of-tx include-states] :as opts}]
  (fs/compute-statement conn passiva-definition
                        (cond-> {:from nil}
                          to             (assoc :to to)
                          as-of-tx       (assoc :as-of-tx as-of-tx)
                          include-states (assoc :include-states include-states))))

(defn balance-check
  "Run both sides and return {:aktiva _ :passiva _ :balanced? bool
   :delta Money}. Standard double-entry sanity check — should hold
   if all transactions sum to zero."
  [conn opts]
  (let [a (compute-aktiva conn opts)
        p (compute-passiva conn opts)
        ;; Aktiva totals are debit-natural (positive = asset value);
        ;; Passiva totals are credit-natural (positive = obligation).
        ;; In a balanced book Σaktiva = Σpassiva.
        delta (let [{:keys [amount commodity]} (:statement/total a)
                    pa (:amount (:statement/total p))]
                {:amount    (.subtract amount pa)
                 :commodity commodity})]
    {:bs/aktiva    a
     :bs/passiva   p
     :bs/balanced? (zero? (.signum ^java.math.BigDecimal (:amount delta)))
     :bs/delta     delta}))
