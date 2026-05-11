(ns kontor.l10n-de.eur
  "Anlage EÜR — Einnahmen-Überschuss-Rechnung.

   Cash-basis income calculation per §4 Abs. 3 EStG; the simplified
   filing form Freiberufler (freelancers / sole proprietors below
   the bookkeeping threshold §141 AO) submit instead of the full
   double-entry P&L + BS.

   Form layout: BMF Anlage EÜR (canonical 2024 / 2025 version).
   Box numbers below match the official PDF; we ship the high-traffic
   subset that 80% of small businesses actually use. Field codes are
   strings (the form uses both numeric and 1.x-style notations
   depending on tax year).

   IMPORTANT scope cuts:

     - Cash basis assumed (the kernel does NOT enforce cash-basis;
       you must only post when money moves, OR mentally accept that
       the EÜR result is approximate for businesses that book on
       accrual). A future cash-basis filter can plug in here.

     - Private-Anteil (private use of business assets) is out of scope
       — needs a per-account adjustment factor that doesn't fit the
       current schema. Add when a real freelancer brings the case.

     - Investitionsabzugsbetrag (IAB §7g EStG) and Sonderabschreibungen
       are out of scope — they require non-trivial account-driven
       computation.

   Source: BMF Anlage EÜR Vordruck + Anleitung 2024 (publicly
   downloadable from formulare-bfinv.de). Box numbers and German
   labels are factual data not under copyright."
  (:require [kontor.financial-statements :as fs]
            [kontor.money :as money]))

(def eur-definition
  "EÜR-as-statement: each section corresponds to a sub-area of the
   form. Lines map directly to numbered boxes (Zeilen).

   The form's actual numerical answers are box-by-box, so consumers
   typically read individual line/values rather than section subtotals."
  {:statement/name    "Einnahmen-Überschuss-Rechnung (Anlage EÜR)"
   :statement/country "DE"
   :statement/sections
   [{:section/code  "Einnahmen"
     :section/label "Betriebseinnahmen"
     :section/lines
     [{:line/code "11" :line/label "Umsatzsteuerpflichtige Betriebseinnahmen (netto)"
       :line/codes ["4400" "4410" "4420" "4430" "4300" "4310"]}
      {:line/code "12" :line/label "Umsatzsteuerfreie / nicht steuerbare Einnahmen"
       :line/codes ["4200" "4120" "4125"]}
      {:line/code "14" :line/label "Vereinnahmte Umsatzsteuer (auf Einnahmen + Erstattung FA)"
       :line/codes ["3801" "3806" "3820"]
       :line/sign :raw} ; liability, raw signed amount = USt collected
      {:line/code "15" :line/label "Privatentnahmen / Sachentnahmen"
       :line/codes ["2000"]}
      {:line/code "16" :line/label "Sonstige Sach-, Nutzungs- und Leistungsentnahmen"
       :line/codes ["4830" "4840" "4850"]}]}

    {:section/code  "Ausgaben"
     :section/label "Betriebsausgaben"
     :section/lines
     [{:line/code "22" :line/label "Wareneinkäufe (netto)"
       :line/codes ["5400" "5410" "5500"]}
      {:line/code "23" :line/label "Personalkosten (Löhne, Gehälter, Soziale Abgaben)"
       :line/codes ["6020" "6010" "6100" "6110" "6120" "6130"]}
      {:line/code "26" :line/label "Bürobedarf"
       :line/codes ["6800"]}
      {:line/code "27" :line/label "Miete / Pacht von Geschäftsräumen"
       :line/codes ["6300" "6310"]}
      {:line/code "28" :line/label "Reisekosten"
       :line/codes ["6650" "6660"]}
      {:line/code "29" :line/label "Bewirtungskosten (70% abziehbar; voll erfassen, kürzen extern)"
       :line/codes ["6670"]}
      {:line/code "30" :line/label "Werbung / Marketing"
       :line/codes ["6600" "6610" "6620"]}
      {:line/code "31" :line/label "Kfz-Kosten"
       :line/codes ["6825" "6830"]}
      {:line/code "32" :line/label "Telekommunikation / Internet"
       :line/codes ["6820"]}
      {:line/code "33" :line/label "Software / IT / Cloud"
       :line/codes ["6815"]}
      {:line/code "34" :line/label "Versicherungen"
       :line/codes ["6520"]}
      {:line/code "35" :line/label "Beratungskosten / Steuerberater"
       :line/codes ["6850" "6855"]}
      {:line/code "36" :line/label "Beiträge / Mitgliedschaften"
       :line/codes ["6530" "6540"]}
      {:line/code "44" :line/label "Abschreibungen Sachanlagen"
       :line/codes ["6200" "6220" "6240"]}
      {:line/code "45" :line/label "GWG (geringwertige Wirtschaftsgüter)"
       :line/codes ["0690"]}
      {:line/code "49" :line/label "Vorsteuer (gezahlte USt auf Eingangsrechnungen)"
       :line/codes ["1571" "1576" "1577" "1578"]}
      {:line/code "50" :line/label "An das Finanzamt gezahlte USt"
       :line/codes ["3801" "3806"]
       :line/sign :inflow} ; sub from collected — paid USt is a debit
      {:line/code "60" :line/label "Übrige Betriebsausgaben"
       :line/codes ["6900" "6910" "6990"]}]}]})

(defn compute
  "Compute the EÜR for the period [from, to). Returns the raw statement
   *plus* the canonical Gewinn = Σ Einnahmen − Σ Ausgaben.

   The bookkeeper / Steuerberater then transcribes line-by-line into
   ELSTER or hands the resulting EDN to a downstream EÜR XML/PDF
   generator."
  ([conn opts]
   (compute conn eur-definition opts))
  ([conn definition {:keys [from to as-of-tx include-states] :as opts}]
   (let [computed (fs/compute-statement conn definition
                                        (cond-> {}
                                          from           (assoc :from from)
                                          to             (assoc :to to)
                                          as-of-tx       (assoc :as-of-tx as-of-tx)
                                          include-states (assoc :include-states include-states)))
         einnahmen (fs/section-subtotal computed "Einnahmen")
         ausgaben  (fs/section-subtotal computed "Ausgaben")
         gewinn    (money/sub einnahmen ausgaben)]
     (assoc computed
            :eur/einnahmen einnahmen
            :eur/ausgaben  ausgaben
            :eur/gewinn    gewinn))))

(defn line-by-box
  "Pull a Money value by EÜR box number. Convenience for ELSTER mapping."
  [computed box-code]
  (or (fs/line-value computed "Einnahmen" box-code)
      (fs/line-value computed "Ausgaben" box-code)))
