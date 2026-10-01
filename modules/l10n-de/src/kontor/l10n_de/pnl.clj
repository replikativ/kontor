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
       :line/codes ["4400" "4410" "4449" "4499"]}
      {:line/code "1.2" :line/label "Erlöse 7% USt"
       :line/codes ["4300" "4331" "4333" "4334" "4335"]}
      {:line/code "1.3" :line/label "Steuerfreie und nicht steuerbare Umsätze"
       :line/codes ["4100" "4105" "4110" "4120" "4125" "4130" "4135" "4136" "4138" "4139" "4140" "4150" "4160" "4165" "4180" "4185" "4186" "4336" "4337" "4338" "4339"]}
      {:line/code "1.4" :line/label "Sonstige Umsatzerlöse, Erlösschmälerungen"
       :line/codes ["4200" "4501" "4502" "4503" "4504" "4505" "4510" "4520" "4560" "4564" "4565" "4566" "4569" "4570" "4574" "4575" "4576" "4579" "4581" "4582" "4589" "4600" "4605" "4610" "4616" "4619" "4620" "4630" "4636" "4637" "4638" "4639" "4640" "4645" "4646" "4650" "4656" "4659" "4660" "4670" "4676" "4679" "4680" "4686" "4689" "4690" "4695" "4699" "4700" "4701" "4702" "4703" "4704" "4705" "4706" "4710" "4720" "4724" "4725" "4726" "4727" "4730" "4731" "4732" "4735" "4736" "4738" "4741" "4742" "4743" "4745" "4746" "4747" "4748" "4749" "4750" "4760" "4769" "4770" "4780" "4790"]}]}

    {:section/code  "2"
     :section/label "Sonstige betriebliche Erträge"
     :section/lines
     [{:line/code "2.0" :line/label "Bestandsveränderungen, aktivierte Eigenleistungen"
       :line/codes ["4810" "4815" "4816" "4818" "4824" "4825"]}
      {:line/code "2.1" :line/label "Sonstige betriebliche Erträge"
       :line/codes ["4830" "4832" "4833" "4834" "4835" "4836" "4837" "4838" "4839" "4840" "4841" "4842" "4843" "4844" "4845" "4847" "4848" "4849" "4850" "4851" "4852" "4855" "4856" "4857" "4858" "4860" "4861" "4862" "4865" "4866" "4867" "4869" "4900" "4901" "4905" "4906" "4910" "4911" "4912" "4913" "4914" "4915" "4916" "4920" "4923" "4925" "4927" "4928" "4929" "4930" "4932" "4935" "4937" "4938" "4940" "4941" "4945" "4946" "4947" "4948" "4949" "4960" "4970" "4972" "4975" "4980" "4981" "4982" "4987" "4989" "4992"]}]}

    {:section/code  "3"
     :section/label "Materialaufwand"
     :section/lines
     [{:line/code "3.1" :line/label "Roh-, Hilfs- und Betriebsstoffe, Waren"
       :line/codes ["5100" "5110" "5129" "5130" "5160" "5162" "5166" "5167" "5170" "5171" "5175" "5176" "5189" "5190" "5191" "5192" "5300" "5347" "5348" "5349" "5400" "5418" "5419" "5420" "5425" "5430" "5435" "5440" "5505" "5540" "5550" "5551" "5552" "5553" "5558" "5559" "5560" "5565" "5600" "5610" "5660" "5700" "5701" "5710" "5714" "5715" "5717" "5718" "5720" "5724" "5725" "5730" "5731" "5732" "5733" "5734" "5735" "5736" "5737" "5738" "5739" "5740" "5741" "5742" "5743" "5745" "5746" "5747" "5748" "5749" "5750" "5753" "5754" "5755" "5760" "5769" "5770" "5780" "5783" "5784" "5785" "5788" "5789" "5790" "5792" "5793" "5794" "5796" "5797" "5798" "5800" "5820" "5840" "5860" "5880" "5881" "5885"]}
      {:line/code "3.2" :line/label "Fremdleistungen"
       :line/codes ["5900" "5906" "5908" "5909" "5913" "5915" "5920" "5923" "5925" "5930" "5933" "5935" "5940" "5943" "5945" "5950" "5951" "5952" "5953" "5954" "5955" "5960" "5965" "5970" "5975" "5980" "5985"]}]}

    {:section/code  "4"
     :section/label "Personalaufwand"
     :section/lines
     [{:line/code "4.1" :line/label "Löhne und Gehälter"
       :line/codes ["6010" "6020" "6024" "6026" "6027" "6028" "6029" "6030" "6035" "6036" "6037" "6038" "6039" "6040" "6045" "6050" "6060" "6066" "6067" "6068" "6069" "6070" "6071" "6072" "6073" "6074" "6075" "6076" "6077" "6078" "6079" "6080" "6090"]}
      {:line/code "4.2" :line/label "Soziale Abgaben und Altersversorgung"
       :line/codes ["6100" "6110" "6118" "6120" "6130" "6140" "6147" "6148" "6149" "6150" "6160" "6170" "6171"]}]}

    {:section/code  "5"
     :section/label "Abschreibungen"
     :section/lines
     [{:line/code "5.1" :line/label "Abschreibungen"
       :line/codes ["6201" "6205" "6209" "6210" "6211" "6220" "6221" "6222" "6223" "6230" "6231" "6232" "6233" "6240" "6241" "6242" "6243" "6244" "6245" "6249" "6250" "6260" "6262" "6264" "6266" "6272" "6278" "6279" "6280" "6281" "6286" "6290" "6291"]}]}

    {:section/code  "6"
     :section/label "Sonstige betriebliche Aufwendungen"
     :section/lines
     [{:line/code "6.1" :line/label "Raumkosten"
       :line/codes ["6305" "6310" "6312" "6313" "6315" "6316" "6317" "6318" "6319" "6320" "6325" "6330" "6335" "6340" "6345" "6348" "6349" "6350" "6352" "6390" "6391" "6392" "6393" "6394" "6395" "6397" "6398"]}
      {:line/code "6.2" :line/label "Versicherungen, Beiträge"
       :line/codes ["6400" "6405" "6410" "6420" "6430" "6436" "6437" "6440" "6450" "6460" "6470" "6475" "6490" "6495" "6498"]}
      {:line/code "6.3" :line/label "Fahrzeugkosten"
       :line/codes ["6500" "6520" "6530" "6540" "6550" "6560" "6565" "6570" "6580" "6590" "6595"]}
      {:line/code "6.4" :line/label "Werbekosten"
       :line/codes ["6600" "6605" "6610" "6611" "6612" "6620" "6621" "6622" "6625" "6629" "6630"]}
      {:line/code "6.5" :line/label "Bewirtung"
       :line/codes ["6640" "6641" "6642" "6643" "6644" "6645"]}
      {:line/code "6.6" :line/label "Reisekosten"
       :line/codes ["6650" "6660" "6663" "6664" "6668" "6670" "6672" "6673" "6674" "6680" "6688" "6689" "6690" "6691"]}
      {:line/code "6.7" :line/label "Kosten der Warenabgabe"
       :line/codes ["6700" "6710" "6740" "6760" "6770" "6780" "6790"]}
      {:line/code "6.8" :line/label "Porto, Telefon, Bürobedarf"
       :line/codes ["6800" "6805" "6810" "6815" "6820" "6821" "6822" "6823" "6824"]}
      {:line/code "6.9" :line/label "Rechts-, Beratungs- und Buchführungskosten"
       :line/codes ["6825" "6827" "6830" "6833" "6834"]}
      {:line/code "6.10" :line/label "Sonstige betriebliche Aufwendungen"
       :line/codes ["6300" "6302" "6303" "6304" "6835" "6836" "6837" "6838" "6840" "6845" "6850" "6854" "6855" "6856" "6857" "6858" "6859" "6860" "6865" "6871" "6875" "6876" "6879" "6880" "6881" "6883" "6884" "6885" "6888" "6889" "6890" "6891" "6892" "6895" "6896" "6897" "6898" "6900" "6903" "6905" "6906" "6907" "6908" "6910" "6912" "6918" "6920" "6922" "6923" "6924" "6927" "6928" "6929" "6930" "6931" "6932" "6933" "6936" "6938" "6960" "6967" "6968" "6969" "6972" "6974" "6976" "6978" "6979" "6980" "6982" "6984" "6986" "6988" "6989" "6992" "6994" "6999"]}]}

    ;; § 275 Abs. 2 Nr. 9-11
    {:section/code  "9"
     :section/label "Erträge aus Beteiligungen, Wertpapieren und Zinsen"
     :section/lines
     [{:line/code "9.1" :line/label "Finanzerträge"
       :line/codes ["7002" "7003" "7004" "7005" "7006" "7008" "7009" "7011" "7012" "7013" "7014" "7015" "7016" "7017" "7018" "7019" "7020" "7030" "7103" "7104" "7105" "7106" "7107" "7109" "7110" "7115" "7119" "7120" "7129" "7130" "7139" "7140" "7141" "7142" "7143" "7144" "7145" "7192" "7194"]}]}

    {:section/code  "12"
     :section/label "Abschreibungen auf Finanzanlagen und Wertpapiere"
     :section/lines
     [{:line/code "12.1" :line/label "Abschreibungen auf Finanzanlagen"
       :line/codes ["7201" "7204" "7207" "7208" "7210" "7214" "7217" "7250" "7255"]}]}

    {:section/code  "13"
     :section/label "Zinsen und ähnliche Aufwendungen"
     :section/lines
     [{:line/code "13.1" :line/label "Zinsen und ähnliche Aufwendungen"
       :line/codes ["7302" "7303" "7304" "7305" "7306" "7308" "7309" "7310" "7311" "7313" "7316" "7317" "7318" "7319" "7320" "7323" "7324" "7325" "7326" "7327" "7328" "7329" "7330" "7339" "7340" "7349" "7350" "7351" "7355" "7360" "7361" "7362" "7363" "7364" "7365" "7366" "7392" "7394" "7398" "7399"]}]}

    ;; § 275 Abs. 2 Nr. 14. KSt, GewSt and SolZ all belong here — the
    ;; special item takes precedence over Nr. 16 sonstige Steuern.
    {:section/code  "14"
     :section/label "Steuern vom Einkommen und vom Ertrag"
     :section/lines
     [{:line/code "14.1" :line/label "Körperschaftsteuer (inkl. SolZ)"
       :line/codes ["7600" "7603" "7604" "7607" "7608" "7609"]}
      {:line/code "14.2" :line/label "Gewerbesteuer"
       :line/codes ["7610"]}
      {:line/code "14.3" :line/label "Kapitalertragsteuer und Sonstige"
       :line/codes ["7630" "7633" "7638" "7639" "7641" "7643" "7645" "7646" "7648" "7649"]}]}

    ;; § 275 Abs. 2 Nr. 16
    {:section/code  "16"
     :section/label "Sonstige Steuern"
     :section/lines
     [{:line/code "16.1" :line/label "Sonstige Steuern"
       :line/codes ["7675" "7678" "7680" "7685" "7690" "7692" "7694"]}]}]})

(def ^:private sign-map
  "Income sections add, expense sections subtract. Including the tax
   blocks makes `:statement/total` the § 275 Abs. 2 Nr. 17
   Jahresüberschuss/Jahresfehlbetrag — the statutory bottom line."
  {"1" :+ "2" :+ "3" :- "4" :- "5" :- "6" :- "9" :+ "12" :- "13" :- "14" :- "16" :-})

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
