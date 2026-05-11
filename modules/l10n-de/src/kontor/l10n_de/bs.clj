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
   compression is just a cosmetic re-bucketing of the same numbers."
  (:require [kontor.financial-statements :as fs]))

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
  {:statement/name    "Bilanz — Passiva"
   :statement/country "DE"
   :statement/sections
   [{:section/code  "A"
     :section/label "Eigenkapital"
     :section/lines
     [{:line/code "A.I"   :line/label "Gezeichnetes Kapital"
       :line/codes ["2900" "2910" "2920"]}
      {:line/code "A.II"  :line/label "Kapitalrücklage"
       :line/codes ["2930"]}
      {:line/code "A.III" :line/label "Gewinnrücklagen"
       :line/codes ["2940" "2950" "2960"]}
      {:line/code "A.IV"  :line/label "Gewinn-/Verlustvortrag"
       :line/codes ["2970" "2978"]}
      {:line/code "A.V"   :line/label "Privatentnahmen / -einlagen"
       :line/codes ["2000" "2100"]}]}

    {:section/code  "B"
     :section/label "Rückstellungen"
     :section/lines
     [{:line/code "B.1" :line/label "Rückstellungen für Steuern"
       :line/codes ["3000" "3010"]}
      {:line/code "B.2" :line/label "Sonstige Rückstellungen"
       :line/codes ["3070" "3090"]}]}

    {:section/code  "C"
     :section/label "Verbindlichkeiten"
     :section/lines
     [{:line/code "C.1" :line/label "Verbindlichkeiten gegenüber Kreditinstituten"
       :line/codes ["3150" "3160"]}
      {:line/code "C.2" :line/label "Verbindlichkeiten aus Lieferungen und Leistungen"
       :line/codes ["3300" "3310"]}
      {:line/code "C.3" :line/label "Sonstige Verbindlichkeiten (inkl. USt)"
       :line/codes ["3801" "3806" "3820" "3500" "3700"]}]}

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
