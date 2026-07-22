(ns kontor.l10n-de.pnl
  "German P&L — Gewinn- und Verlustrechnung (HGB §275 Abs. 2,
   Gesamtkostenverfahren / total-cost method).

   The HGB §275 Abs. 2 layout is the standard for SMB / Kapitalge-
   sellschaften; the §275 Abs. 3 (cost-of-sales method) is allowed
   too but rare in DE practice. This module ships the §275 Abs. 2
   shape as the default; consumers can build their own
   :statement/sections vector against the kernel for the alternative.

   Account codes target SKR04. Adjust for SKR03 in a per-tenant
   override.

   Lines enumerate codes PER ACCOUNT rather than by number range, which
   is the German convention and not merely a style choice: DATEV's
   published SKR04 binds each account individually to its HGB position
   and its E-Bilanz taxonomy position, and adjacent accounts routinely
   diverge — 3040 Körperschaftsteuerrückstellung, 3050 Rückstellung für
   sonstige Steuern, 3060 Rückstellungen für latente Steuern and 3065
   passive latente Steuern land on four different targets, while 3810
   maps into the same family as 3050 across a number block. No prefix
   rule can express that. Since JStG 2024 the filing regime itself works
   per account: § 5b Abs. 1 EStG requires unverdichtete Kontennachweise
   mit Kontensalden for fiscal years beginning after 2024-12-31.

   This docstring previously claimed the definitions used prefix
   patterns \"to be tolerant of customer-added accounts\"; they never did,
   and per the above they should not. A code enumerated here that the
   shipped chart does not carry is deliberate — the definitions cover a
   fuller SKR04 than the module seeds — and
   `financial-statements/statement-coverage` reports those separately
   from accounts that no line covers, which is the real defect. Note 194."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

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
       :line/codes ["6900" "6910" "6990"]}]}

    ;; § 275 Abs. 2 Nr. 14. KSt, GewSt and SolZ all belong here — the
    ;; special item takes precedence over Nr. 16 sonstige Steuern.
    {:section/code  "14"
     :section/label "Steuern vom Einkommen und vom Ertrag"
     :section/lines
     [{:line/code "14.1" :line/label "Körperschaftsteuer (inkl. SolZ)"
       :line/codes ["7600" "7603" "7610"]}
      {:line/code "14.2" :line/label "Gewerbesteuer"
       :line/codes ["7620" "7681"]}]}

    ;; § 275 Abs. 2 Nr. 16
    {:section/code  "16"
     :section/label "Sonstige Steuern"
     :section/lines
     [{:line/code "16.1" :line/label "Sonstige Steuern"
       :line/codes ["7685" "7690"]}]}]})

(def ^:private sign-map
  "Income sections add, expense sections subtract. Including the tax
   blocks makes `:statement/total` the § 275 Abs. 2 Nr. 17
   Jahresüberschuss/Jahresfehlbetrag — the statutory bottom line."
  {"1" :+ "2" :+ "3" :- "4" :- "5" :- "6" :- "14" :- "16" :-})

(defn compute
  "Compute the GKV P&L over [from, to). All section subtotals are in EUR.

   `:statement/total` is the § 275 Abs. 2 Nr. 17 Jahresüberschuss/
   Jahresfehlbetrag. Two derived subtotals come alongside:

     :de.pnl/ergebnis-vor-steuern  = (1+2) − (3+4+5+6)
     :de.pnl/jahresueberschuss     = the statement total

   `ergebnis-vor-steuern` is the § 265 Abs. 5 voluntary Zwischensumme,
   not a § 275 position — there is no statutory item called \"Ergebnis
   vor Steuern\". It is exposed because it is the meaningful bottom line
   for an Einzelunternehmen or ordinary Personengesellschaft, which
   §§ 264 ff. do not bind and for whose owner income tax is a private
   matter rather than a company expense."
  ([conn opts]
   (compute conn gkv-definition opts))
  ([conn definition opts]
   (let [computed (fs/compute-statement conn definition
                                        (assoc opts :total-sign-map sign-map))
         sub  #(fs/section-subtotal computed %)
         pre  (reduce money/sub
                      (reduce money/add (map sub ["1" "2"]))
                      (map sub ["3" "4" "5" "6"]))]
     (assoc computed
            :de.pnl/ergebnis-vor-steuern pre
            :de.pnl/jahresueberschuss    (:statement/total computed)))))
