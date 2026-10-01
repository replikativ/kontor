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
       :line/codes ["0100" "0110" "0120" "0130" "0135" "0140" "0143" "0144" "0145" "0146" "0147" "0148" "0150" "0160" "0170" "0179"]}
      {:line/code "A.II"  :line/label "Sachanlagen"
       :line/codes ["0200" "0210" "0215" "0220" "0225" "0229" "0230" "0235" "0240" "0250" "0260" "0270" "0280" "0285" "0290" "0300" "0305" "0310" "0315" "0320" "0329" "0330" "0340" "0350" "0360" "0370" "0380" "0390" "0395" "0398" "0400" "0420" "0440" "0450" "0460" "0470" "0500" "0510" "0520" "0540" "0560" "0620" "0630" "0635" "0640" "0650" "0660" "0670" "0675" "0680" "0690" "0700" "0705" "0710" "0720" "0725" "0735" "0740" "0750" "0755" "0765" "0770" "0780" "0785" "0795"]}
      {:line/code "A.III" :line/label "Finanzanlagen"
       :line/codes ["0800" "0803" "0804" "0805" "0806" "0808" "0809" "0810" "0813" "0814" "0815" "0820" "0829" "0830" "0840" "0850" "0860" "0880" "0883" "0885" "0900" "0910" "0920" "0930" "0940" "0960" "0961" "0962" "0963" "0964" "0970" "0980" "0990"]}]}

    {:section/code  "B"
     :section/label "Umlaufvermögen"
     :section/lines
     [{:line/code "B.I"   :line/label "Vorräte"
       :line/codes ["1040" "1050" "1080" "1090" "1095" "1100" "1110" "1140" "1180" "1181" "1182" "1184" "1186" "1190"]}
      {:line/code "B.II"  :line/label "Forderungen und sonstige Vermögensgegenstände"
       :line/codes ["1200" "1201" "1210" "1215" "1216" "1217" "1218" "1219" "1220" "1221" "1225" "1230" "1231" "1232" "1235" "1240" "1241" "1245" "1246" "1247" "1248" "1249" "1250" "1251" "1255" "1258" "1259" "1260" "1261" "1265" "1266" "1267" "1268" "1269" "1270" "1271" "1275" "1276" "1277" "1280" "1281" "1285" "1286" "1287" "1288" "1289" "1290" "1291" "1295" "1296" "1297" "1298" "1299" "1300" "1301" "1305" "1307" "1308" "1309" "1310" "1311" "1315" "1317" "1318" "1319" "1320" "1321" "1325" "1328" "1329" "1330" "1331" "1335" "1337" "1338" "1339" "1340" "1341" "1345" "1349" "1350" "1351" "1355" "1360" "1361" "1365" "1369" "1370" "1374" "1375" "1376" "1377" "1378" "1380" "1381" "1382" "1383" "1390" "1391" "1393" "1394" "1395" "1396" "1397" "1398" "1399" "1400" "1401" "1402" "1403" "1404" "1405" "1406" "1407" "1408" "1409" "1410" "1411" "1412" "1413" "1414" "1415" "1416" "1417" "1418" "1419" "1420" "1422" "1425" "1427" "1431" "1432" "1433" "1434" "1435" "1436" "1440" "1450" "1456" "1457" "1460" "1461" "1480" "1481" "1482" "1483" "1484" "1486" "1487" "1490" "1491" "1494" "1495" "1498"]}
      {:line/code "B.III" :line/label "Kassenbestand, Bankguthaben"
       :line/codes ["1600" "1610" "1620" "1700" "1710" "1720" "1730" "1780" "1790" "1800" "1810" "1820" "1830" "1840" "1850" "1855" "1890"]}]}

    {:section/code  "C"
     :section/label "Rechnungsabgrenzungsposten"
     :section/lines
     [{:line/code "D" :line/label "Aktive latente Steuern"
       :line/codes ["1950"]}
      {:line/code "C.1" :line/label "Aktive RAP"
       :line/codes ["1920" "1930" "1940"]}]}]})

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
       :line/codes ["2900" "2901" "2902" "2903" "2906" "2907" "2908" "2909" "2910"]}
      {:line/code "A.II"  :line/label "Kapitalrücklage"
       :line/codes ["2925" "2926" "2927" "2928" "2929"]}
      {:line/code "A.III" :line/label "Gewinnrücklagen"
       :line/codes ["2935" "2937" "2950" "2959" "2960" "2961" "2962" "2963" "2964" "2965" "2966" "2967" "2968" "2969"]}
      ;; 2900 is "Gewinnvortrag vor Verwendung" in the shipped chart —
      ;; § 266 Abs. 3 A.IV, not A.I where it used to sit.
      {:line/code "A.IV"  :line/label "Gewinnvortrag/Verlustvortrag"
       :line/codes ["2970" "2975" "2977" "2978"]}
      ;; A.V is the statutory home of an un-appropriated period result.
      ;; See the namespace docstring for why it is computed from the P&L
      ;; accounts rather than read off an equity account.
      {:line/code "A.V.a" :line/label "Jahresüberschuss — Erträge"
       :line/codes ["4%" "7002" "7003" "7004" "7005" "7006" "7008" "7009" "7011" "7012" "7013" "7014" "7015" "7016" "7017" "7018" "7019" "7020" "7030" "7103" "7104" "7105" "7106" "7107" "7109" "7110" "7115" "7119" "7120" "7129" "7130" "7139" "7140" "7141" "7142" "7143" "7144" "7145" "7192" "7194" "7454" "7464"]}
      {:line/code "A.V.b" :line/label "Jahresüberschuss — Aufwendungen"
       :line/codes ["5%" "6%" "7201" "7204" "7207" "7208" "7210" "7214" "7217" "7250" "7255" "7302" "7303" "7304" "7305" "7306" "7308" "7309" "7310" "7311" "7313" "7316" "7317" "7318" "7319" "7320" "7323" "7324" "7325" "7326" "7327" "7328" "7329" "7330" "7339" "7340" "7349" "7350" "7351" "7355" "7360" "7361" "7362" "7363" "7364" "7365" "7366" "7392" "7394" "7398" "7399" "7552" "7553" "7554" "7561" "7563" "7600" "7603" "7604" "7607" "7608" "7609" "7610" "7630" "7633" "7638" "7639" "7641" "7643" "7645" "7646" "7648" "7649" "7675" "7678" "7680" "7685" "7690" "7692" "7694"]
       :line/negate true}
      ;; Not a § 266 position: Einzelunternehmen / Personengesellschaft
      ;; only, where §§ 264 ff. do not bind. A Kapitalgesellschaft chart
      ;; has no Privatkonten and this line computes to zero.
      {:line/code "A.VII" :line/label "Sonderposten"
       :line/codes ["2981" "2982" "2988" "2990" "2995" "2997" "2998" "2999"]}
      {:line/code "A.VI"  :line/label "Kapital- und Privatkonten (nicht-KapG)"
       :line/codes ["2001" "2010" "2011" "2060" "2100" "2101" "2110" "2111" "2120" "2121" "2130" "2131" "2140" "2141" "2150" "2151" "2160" "2161" "2170" "2171" "2180" "2181" "2190" "2191"]}]}

    {:section/code  "B"
     :section/label "Rückstellungen"
     :section/lines
     [{:line/code "B.1" :line/label "Rückstellungen für Pensionen und ähnliche Verpflichtungen"
       :line/codes ["3005" "3009" "3010" "3011" "3015"]}
      ;; § 266 Abs. 3 B.2 verbatim: "Steuerrückstellungen". The shipped
      ;; chart books KSt/GewSt provisions to 3040/3035.
      {:line/code "B.2" :line/label "Steuerrückstellungen"
       :line/codes ["3020" "3035" "3040" "3050" "3060" "3065"]}
      {:line/code "B.3" :line/label "Sonstige Rückstellungen"
       :line/codes ["3070" "3074" "3075" "3076" "3077" "3079" "3085" "3090" "3092" "3095" "3096" "3098" "3099"]}]}

    {:section/code  "C"
     :section/label "Verbindlichkeiten"
     :section/lines
     [{:line/code "C.1" :line/label "Anleihen"
       :line/codes ["3101" "3105" "3110" "3120" "3121" "3125" "3130"]}
      {:line/code "C.2" :line/label "Verbindlichkeiten gegenüber Kreditinstituten"
       :line/codes ["1895" "3150" "3151" "3160" "3170" "3180" "3181" "3190" "3200" "3210" "3249"]}
      {:line/code "C.3" :line/label "Erhaltene Anzahlungen auf Bestellungen"
       :line/codes ["3250" "3260" "3261" "3270" "3272" "3279" "3280" "3284" "3285"]}
      {:line/code "C.4" :line/label "Verbindlichkeiten aus Lieferungen und Leistungen"
       :line/codes ["3300" "3301" "3305" "3306" "3307" "3309" "3310" "3334" "3335" "3337" "3338" "3340" "3341" "3345" "3348" "3349"]}
      {:line/code "C.5" :line/label "Wechselverbindlichkeiten"
       :line/codes ["3350" "3351" "3380" "3390"]}
      {:line/code "C.6" :line/label "Verbindlichkeiten gegenüber verbundenen Unternehmen"
       :line/codes ["3400" "3401" "3405" "3410" "3420" "3421" "3425" "3430"]}
      {:line/code "C.7" :line/label "Verbindlichkeiten gegenüber Unternehmen, mit denen ein Beteiligungsverhältnis besteht"
       :line/codes ["3450" "3451" "3455" "3460" "3470" "3471" "3475" "3480"]}
      ;; § 266 Abs. 3 C.8. A declared but unpaid dividend has no
      ;; statutory line of its own — the word "Gesellschafter" does not
      ;; occur in § 266 Abs. 3 — so it lands here (C.6/C.7 take
      ;; precedence where the shareholder is a verbundenes or
      ;; Beteiligungs-Unternehmen). § 264c Abs. 1 makes a separate
      ;; disclosure mandatory for a KapCoGes; a consumer that needs it
      ;; adds the line.
      {:line/code "C.8"   :line/label "Sonstige Verbindlichkeiten"
       :line/codes ["3500" "3501" "3504" "3507" "3509" "3510" "3511" "3514" "3517" "3519" "3520" "3521" "3524" "3527" "3530" "3531" "3534" "3537" "3540" "3541" "3544" "3547" "3550" "3551" "3554" "3557" "3560" "3561" "3564" "3567" "3570" "3599" "3600" "3610" "3611" "3612" "3613" "3620" "3630" "3634" "3635" "3640" "3641" "3642" "3643" "3645" "3646" "3647" "3648" "3650" "3651" "3652" "3653" "3655" "3656" "3657" "3658" "3695"]}
      ;; the statute expresses these as davon-Vermerke of C.8, not as
      ;; separate positions — shown separately here so the numbers are
      ;; visible, and summed into the same section
      {:line/code "C.8.a" :line/label "davon aus Steuern"
       :line/codes ["3700" "3701" "3710" "3715" "3720" "3725" "3726" "3730" "3740" "3741" "3750" "3755" "3759" "3760" "3761" "3770" "3771" "3780" "3785" "3786" "3790" "3791" "3796" "3798" "3799" "3800" "3801" "3802" "3803" "3804" "3805" "3806" "3807" "3808" "3809" "3810" "3811" "3812" "3813" "3814" "3815" "3816" "3817" "3818" "3819" "3820" "3830" "3831" "3832" "3834" "3835" "3837" "3838" "3839" "3840" "3841" "3845" "3850" "3851" "3854" "3860" "3865"]}]}

    {:section/code  "D"
     :section/label "Rechnungsabgrenzungsposten"
     :section/lines
     [{:line/code "D.1" :line/label "Passive RAP"
       :line/codes ["3950"]}]}]})

(defn compute-aktiva
  "Compute the Aktiva (assets) side point-in-time. Accepts `:to`
   (EXCLUSIVE) or `:through` (INCLUSIVE, e.g. `:through #inst \"2026-12-31\"`
   for a year-end Bilanz that includes Dec-31 entries), plus `:as-of-tx`,
   `:include-states`, `:entity`, `:ledger`. Note-196 N6: `:through` was
   previously dropped here, so a year-end Bilanz silently omitted Dec-31."
  [conn {:keys [to through as-of-tx include-states entity ledger]}]
  (fs/compute-statement conn aktiva-definition
                        (cond-> {:from nil}
                          to             (assoc :to to)
                          through        (assoc :through through)
                          as-of-tx       (assoc :as-of-tx as-of-tx)
                          include-states (assoc :include-states include-states)
                          entity         (assoc :entity entity)
                          ledger         (assoc :ledger ledger))))

(defn compute-passiva
  "Compute the Passiva (equity + liabilities) side point-in-time. Same
   options as [[compute-aktiva]] (incl. `:through` — note-196 N6)."
  [conn {:keys [to through as-of-tx include-states entity ledger]}]
  (fs/compute-statement conn passiva-definition
                        (cond-> {:from nil}
                          to             (assoc :to to)
                          through        (assoc :through through)
                          as-of-tx       (assoc :as-of-tx as-of-tx)
                          include-states (assoc :include-states include-states)
                          entity         (assoc :entity entity)
                          ledger         (assoc :ledger ledger))))

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
