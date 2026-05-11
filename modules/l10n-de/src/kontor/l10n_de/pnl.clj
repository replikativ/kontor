(ns kontor.l10n-de.pnl
  "German P&L — Gewinn- und Verlustrechnung (HGB §275 Abs. 2,
   Gesamtkostenverfahren / total-cost method).

   The HGB §275 Abs. 2 layout is the standard for SMB / Kapitalge-
   sellschaften; the §275 Abs. 3 (cost-of-sales method) is allowed
   too but rare in DE practice. This module ships the §275 Abs. 2
   shape as the default; consumers can build their own
   :statement/sections vector against the kernel for the alternative.

   Account-code prefixes target SKR04. Adjust for SKR03 in a per-
   tenant override. We use prefix patterns ('4%' = all 4xxx) where
   possible to be tolerant of customer-added accounts."
  (:require [kontor.financial-statements :as fs]))

(def gkv-definition
  "Gesamtkostenverfahren — total-cost method P&L per HGB §275 Abs. 2."
  {:statement/name    "Gewinn- und Verlustrechnung (Gesamtkostenverfahren)"
   :statement/country "DE"
   :statement/sections
   [{:section/code  "1"
     :section/label "Umsatzerlöse"
     :section/lines
     [{:line/code "1.1" :line/label "Erlöse 19% USt"
       :line/codes ["4400" "4410" "4420" "4430"]}
      {:line/code "1.2" :line/label "Erlöse 7% USt"
       :line/codes ["4300" "4310"]}
      {:line/code "1.3" :line/label "Steuerfreie Umsätze §4 UStG"
       :line/codes ["4200" "4120" "4125"]}]}

    {:section/code  "2"
     :section/label "Sonstige betriebliche Erträge"
     :section/lines
     [{:line/code "2.1" :line/label "Sonstige Erträge / Erstattungen"
       :line/codes ["4830" "4840" "4850" "4900"]}]}

    {:section/code  "3"
     :section/label "Materialaufwand"
     :section/lines
     [{:line/code "3.1" :line/label "Wareneingang"
       :line/codes ["5400" "5410" "5420" "5500"]}]}

    {:section/code  "4"
     :section/label "Personalaufwand"
     :section/lines
     [{:line/code "4.1" :line/label "Löhne und Gehälter"
       :line/codes ["6020" "6010" "6100" "6110"]}
      {:line/code "4.2" :line/label "Soziale Abgaben"
       :line/codes ["6120" "6130" "6140"]}]}

    {:section/code  "5"
     :section/label "Abschreibungen"
     :section/lines
     [{:line/code "5.1" :line/label "Abschreibungen auf Sachanlagen"
       :line/codes ["6200" "6220" "6240"]}]}

    {:section/code  "6"
     :section/label "Sonstige betriebliche Aufwendungen"
     :section/lines
     [{:line/code "6.1" :line/label "Raumkosten (Miete, Nebenkosten)"
       :line/codes ["6300" "6310" "6400"]}
      {:line/code "6.2" :line/label "Versicherungen / Beiträge"
       :line/codes ["6520" "6530" "6540"]}
      {:line/code "6.3" :line/label "Werbung / Marketing"
       :line/codes ["6600" "6610" "6620"]}
      {:line/code "6.4" :line/label "Reisekosten"
       :line/codes ["6650" "6660"]}
      {:line/code "6.5" :line/label "Bewirtung"
       :line/codes ["6670"]}
      {:line/code "6.6" :line/label "Bürobedarf / Telekommunikation"
       :line/codes ["6800" "6815" "6820" "6825"]}
      {:line/code "6.7" :line/label "Beratungskosten / Steuerberater"
       :line/codes ["6850" "6855"]}
      {:line/code "6.8" :line/label "Sonstige Aufwendungen"
       :line/codes ["6900" "6910" "6990"]}]}]})

(defn compute
  "Compute the GKV P&L over [from, to). All section subtotals are in
   EUR. Total = (1+2) - (3+4+5+6) = Gewinn vor Steuern.

   Use `:total-sign-map` so income sections add and expense sections
   subtract:
     income sections {1, 2}        → :+
     expense sections {3, 4, 5, 6} → :-"
  ([conn opts]
   (compute conn gkv-definition opts))
  ([conn definition opts]
   (let [sign-map {"1" :+ "2" :+ "3" :- "4" :- "5" :- "6" :-}]
     (fs/compute-statement conn definition
                           (assoc opts :total-sign-map sign-map)))))
