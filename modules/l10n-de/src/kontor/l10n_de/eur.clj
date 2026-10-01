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
  (:require [kontor.reporting.financial-statements :as fs]
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
       :line/codes ["4300" "4331" "4333" "4334" "4335" "4400" "4410" "4449" "4499"]}
      {:line/code "12" :line/label "Umsatzsteuerfreie / nicht steuerbare Einnahmen"
       :line/codes ["4100" "4105" "4110" "4120" "4125" "4130" "4135" "4136" "4138" "4139" "4140" "4150" "4160" "4165" "4180" "4185" "4186" "4336" "4337" "4338" "4339"]}
      {:line/code "14" :line/label "Vereinnahmte Umsatzsteuer (auf Einnahmen + Erstattung FA)"
       :line/codes ["3800" "3801" "3802" "3803" "3804" "3805" "3806" "3807" "3808" "3809" "3810" "3811" "3812" "3813" "3814" "3815" "3816" "3817" "3818" "3819" "3840" "3841" "3845" "3850" "3851" "3854" "3860" "3865"]
       :line/sign :raw} ; liability, raw signed amount = USt collected
      {:line/code "15" :line/label "Privatentnahmen / Sachentnahmen"
       :line/codes ["2100" "2101" "2110" "2111" "2120" "2121" "2130" "2131" "2140" "2141"]}
      {:line/code "16" :line/label "Unentgeltliche Wertabgaben (Sach-, Nutzungs- und Leistungsentnahmen)"
       :line/codes ["4600" "4605" "4610" "4616" "4619" "4620" "4630" "4636" "4637" "4638" "4639" "4640" "4645" "4646" "4650" "4656" "4659" "4660" "4670" "4676" "4679" "4680" "4686" "4689" "4690" "4695" "4699"]}
      {:line/code "17" :line/label "Sonstige Betriebseinnahmen"
       :line/codes ["4810" "4815" "4816" "4818" "4824" "4825" "4830" "4832" "4833" "4834" "4835" "4836" "4837" "4838" "4839" "4840" "4841" "4842" "4843" "4844" "4845" "4847" "4848" "4849" "4850" "4851" "4852" "4855" "4856" "4857" "4858" "4860" "4861" "4862" "4865" "4866" "4867" "4869" "4900" "4901" "4905" "4906" "4910" "4911" "4912" "4913" "4914" "4915" "4916" "4920" "4923" "4925" "4927" "4928" "4929" "4930" "4932" "4935" "4937" "4938" "4940" "4941" "4945" "4946" "4947" "4948" "4949" "4960" "4970" "4972" "4975" "4980" "4981" "4982" "4987" "4989" "4992"]}]}

    {:section/code  "Ausgaben"
     :section/label "Betriebsausgaben"
     :section/lines
     [{:line/code "22" :line/label "Wareneinkäufe (netto)"
       :line/codes ["5100" "5110" "5129" "5130" "5160" "5162" "5166" "5167" "5170" "5171" "5175" "5176" "5189" "5190" "5191" "5192" "5300" "5347" "5348" "5349" "5400" "5418" "5419" "5420" "5425" "5430" "5435" "5440" "5505" "5540" "5550" "5551" "5552" "5553" "5558" "5559" "5560" "5565" "5600" "5610" "5660" "5700" "5701" "5710" "5714" "5715" "5717" "5718" "5720" "5724" "5725" "5730" "5731" "5732" "5733" "5734" "5735" "5736" "5737" "5738" "5739" "5740" "5741" "5742" "5743" "5745" "5746" "5747" "5748" "5749" "5750" "5753" "5754" "5755" "5760" "5769" "5770" "5780" "5783" "5784" "5785" "5788" "5789" "5790" "5792" "5793" "5794" "5796" "5797" "5798" "5800" "5820" "5840" "5860" "5880" "5881" "5885" "5900" "5906" "5908" "5909" "5913" "5915" "5920" "5923" "5925" "5930" "5933" "5935" "5940" "5943" "5945" "5950" "5951" "5952" "5953" "5954" "5955" "5960" "5965" "5970" "5975" "5980" "5985"]}
      {:line/code "23" :line/label "Personalkosten (Löhne, Gehälter, Soziale Abgaben)"
       :line/codes ["6010" "6020" "6024" "6026" "6027" "6028" "6029" "6030" "6035" "6036" "6037" "6038" "6039" "6040" "6045" "6050" "6060" "6066" "6067" "6068" "6069" "6070" "6071" "6072" "6073" "6074" "6075" "6076" "6077" "6078" "6079" "6080" "6090" "6100" "6110" "6118" "6120" "6130" "6140" "6147" "6148" "6149" "6150" "6160" "6170" "6171"]}
      {:line/code "26" :line/label "Bürobedarf"
       :line/codes ["6815"]}
      {:line/code "27" :line/label "Miete / Pacht von Geschäftsräumen"
       :line/codes ["6310" "6312" "6313" "6315" "6316" "6317" "6318" "6319"]}
      {:line/code "28" :line/label "Reisekosten"
       :line/codes ["6650" "6660" "6663" "6664" "6668" "6670" "6672" "6673" "6674" "6680" "6688" "6689" "6690" "6691"]}
      {:line/code "29" :line/label "Bewirtungskosten (70% abziehbar; voll erfassen, kürzen extern)"
       :line/codes ["6640" "6641" "6642" "6643" "6644" "6645"]}
      {:line/code "30" :line/label "Werbung / Marketing"
       :line/codes ["6600" "6605" "6610" "6611" "6612" "6620" "6621" "6622" "6625" "6629" "6630"]}
      {:line/code "31" :line/label "Kfz-Kosten"
       :line/codes ["6500" "6520" "6530" "6540" "6550" "6560" "6565" "6570" "6580" "6590" "6595"]}
      {:line/code "32" :line/label "Telekommunikation / Internet"
       :line/codes ["6805" "6810"]}
      {:line/code "33" :line/label "Software / IT / Cloud"
       :line/codes ["6495" "6837"]}
      {:line/code "34" :line/label "Versicherungen"
       :line/codes ["6400" "6405" "6410"]}
      {:line/code "35" :line/label "Beratungskosten / Steuerberater"
       :line/codes ["6825" "6827" "6830" "6833" "6834"]}
      {:line/code "36" :line/label "Beiträge / Mitgliedschaften"
       :line/codes ["6420" "6430" "6436" "6437"]}
      {:line/code "44" :line/label "Abschreibungen Sachanlagen"
       :line/codes ["6201" "6205" "6209" "6210" "6211" "6220" "6221" "6222" "6223" "6230" "6231" "6232" "6233" "6240" "6241" "6242" "6243" "6244" "6245" "6249" "6250" "6260" "6262" "6264" "6266" "6272" "6278" "6279" "6280" "6281" "6286" "6290" "6291"]}
      {:line/code "45" :line/label "GWG (geringwertige Wirtschaftsgüter)"
       :line/codes ["0670" "0675"]}
      {:line/code "49" :line/label "Vorsteuer (gezahlte USt auf Eingangsrechnungen)"
       :line/codes ["1400" "1401" "1402" "1403" "1404" "1405" "1406" "1407" "1408" "1409" "1410" "1411" "1412" "1413" "1414" "1415" "1416" "1417" "1418" "1419" "1420" "1422" "1425" "1427" "1431" "1432" "1433" "1434" "1435" "1436"]}
      {:line/code "50" :line/label "An das Finanzamt gezahlte USt"
       :line/codes ["3820" "3830" "3831" "3832" "3834" "3835" "3837" "3838" "3839"]
       :line/sign :inflow} ; sub from collected — paid USt is a debit
      {:line/code "60" :line/label "Übrige Betriebsausgaben"
       :line/codes ["6300" "6302" "6303" "6304" "6320" "6325" "6330" "6335" "6340" "6345" "6348" "6349" "6350" "6352" "6390" "6391" "6392" "6393" "6394" "6395" "6397" "6398" "6440" "6450" "6460" "6470" "6475" "6490" "6498" "6700" "6710" "6740" "6760" "6770" "6780" "6790" "6800" "6835" "6836" "6838" "6840" "6845" "6850" "6854" "6855" "6856" "6857" "6858" "6859" "6860" "6865" "6871" "6875" "6876" "6879" "6880" "6881" "6883" "6884" "6885" "6888" "6889" "6890" "6891" "6892" "6895" "6896" "6897" "6898" "6900" "6903" "6905" "6906" "6907" "6908" "6910" "6912" "6918" "6920" "6922" "6923" "6924" "6927" "6928" "6929" "6930" "6931" "6932" "6933" "6936" "6938" "6960" "6967" "6968" "6969" "6972" "6974" "6976" "6978" "6979" "6980" "6982" "6984" "6986" "6988" "6989" "6992" "6994" "6999"]}]}]})

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
