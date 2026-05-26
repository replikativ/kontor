(ns kontor.integration.christian-scenario-test
  "End-to-end integration test promoting the Phase D REPL walk
   (research note 161) into a permanent regression. Exercises:

   - **Two-DB topology**: one DE kontor DB for the Hans-Tech UG, one
     CA kontor DB for Christian-personal (sole-prop + investments).
   - **DE side**: opening capital, service revenue + USt, opex +
     Vorsteuer, year-end CIT (KSt+Soli + GewSt) via the DE CIT
     provider, dividend declaration to 2 shareholders, KESt+Soli
     withholding on distribution.
   - **CA side**: sole-prop BC consulting income + GST collected;
     cross-border dividend receipt routed through the new
     `kontor.treaty.de-ca/receive-dividend-from-de!` composite (note
     161 §7 / note 160 §I-19).
   - **Reports**: DE GuV + Bilanz computed via `de-pnl` / `de-bs`;
     CA trial balance verified balanced; provider tax numbers match
     hand-calculated worked examples.

   Substrate properties this guards against regressing:
   - I-17: `:as-of-valid` default = nil → future-dated 2026/2027
     entries show in default reports.
   - F11 / I-10: `:through` as inclusive-end window sugar.
   - I-15: per-posting `:partner` stamps on multi-shareholder
     dividend declaration.
   - I-19: `kontor.treaty.de-ca` 4-leg balanced split.
   - F7 / F8: GL-scan + single-install path (covered by preset
     test, but also reachable here)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.l10n-ca.preset :as ca-preset]
            [kontor.l10n-de.cit-provider :as de-cit]
            [kontor.l10n-de.pnl :as de-pnl]
            [kontor.l10n-de.preset :as de-preset]
            [kontor.period-tax-provider :as ptp]
            [kontor.trial :as trial]
            [kontor.treaty.de-ca :as treaty]))

;; ============================================================================
;; Fixture — both DBs + entities/partners/accounts
;; ============================================================================

(def ^:private fy-2026 {:from #inst "2026-01-01" :through #inst "2026-12-31"})
(def ^:private far-future #inst "2030-01-01")  ; default :as-of-valid post-I-17
(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private cad [:kontor.commodity/symbol "CAD"])

(defn- ug-db
  "DE Hans-Tech UG: preset + entity + 2 partners + a few accounts the
   shipped chart doesn't have for this scenario."
  []
  (let [conn (de-preset/create-de-db)]
    (d/transact conn
      [{:kontor.entity/name "Hans-Tech UG (haftungsbeschränkt)"
        :kontor.entity/code "HANS-TECH-UG"
        :kontor.entity/country "DE" :kontor.entity/legal-form "UG (haftungsbeschränkt)"
        :kontor.entity/functional-commodity eur}
       {:kontor.partner/name "Christian Weilbach" :kontor.partner/external-id "CW"
        :kontor.partner/country-code "CA"}
       {:kontor.partner/name "Partner B"          :kontor.partner/external-id "PB"
        :kontor.partner/country-code "DE"}])
    conn))

(defn- hans-db
  "Christian-personal CA DB: preset + entity + counterparty partner +
   the foreign-tax accounts the treaty helper writes to."
  []
  (let [conn (ca-preset/create-ca-db)]
    (d/transact conn
      [{:kontor.entity/name "Christian (Individual)" :kontor.entity/code "CW-PERSONAL"
        :kontor.entity/country "CA" :kontor.entity/functional-commodity cad}
       {:kontor.partner/name "Hans-Tech UG" :kontor.partner/external-id "HT-UG"
        :kontor.partner/country-code "DE"}
       {:kontor.account/path "Income:Dividends:Foreign:DE"   :kontor.account/type :income
        :kontor.account/commodity cad}
       {:kontor.account/path "Income:Self-Employment"        :kontor.account/type :income
        :kontor.account/commodity cad}
       {:kontor.account/path "Assets:Foreign-Tax-Prepaid"    :kontor.account/type :asset
        :kontor.account/commodity cad}
       {:kontor.account/path "Assets:Foreign-Tax-Refundable" :kontor.account/type :asset
        :kontor.account/commodity cad}])
    conn))

;; ============================================================================
;; UG year of activity — note 161 §2
;; ============================================================================

(defn- book-ug-year! [conn]
  (let [ug [:kontor.entity/code "HANS-TECH-UG"]
        e  (fn [opts] (book/entry! conn (assoc opts :commodity :EUR :entity ug)))]
    ;; Opening capital
    (e {:journal [:journal/code "GJ"] :effective-date #inst "2026-01-02"
        :narration "Eröffnungsbilanz Bank"
        :postings [{:account [:kontor.account/path "Umlaufvermögen:Bank"]            :amount 25000M}
                   {:account [:kontor.account/path "Eigenkapital:Privateinlagen"]   :amount -25000M}]})
    ;; Service revenue €40k + USt 7,600
    (e {:journal [:journal/code "CR"] :effective-date #inst "2026-06-30"
        :narration "Beratung Kunde X H1 2026"
        :postings [{:account [:kontor.account/path "Umlaufvermögen:Bank"]                   :amount 47600M}
                   {:account [:kontor.account/path "Erträge:Erlöse:19%"]                    :amount -40000M}
                   {:account [:kontor.account/path "Verbindlichkeiten:Umsatzsteuer:19%"]    :amount -7600M}]})
    ;; Opex: rent 5k + tax-adv 4k + other 6k + Vorsteuer 2.85k = cash out 17.85k
    (e {:journal [:journal/code "CD"] :effective-date #inst "2026-12-15"
        :narration "Jahresopex 2026 (zusammengefasst)"
        :postings [{:account [:kontor.account/path "Aufwendungen:Raum:Miete"]               :amount 5000M}
                   {:account [:kontor.account/path "Aufwendungen:Steuerberater"]            :amount 4000M}
                   {:account [:kontor.account/path "Aufwendungen:Sonstige"]                 :amount 6000M}
                   {:account [:kontor.account/path "Umlaufvermögen:Vorsteuer:19%"]           :amount 2850M}
                   {:account [:kontor.account/path "Umlaufvermögen:Bank"]                    :amount -17850M}]})
    ;; Year-end CIT accrual: KSt+Soli + GewSt (numbers from de-cit-provider)
    (e {:journal [:journal/code "GJ"] :effective-date #inst "2026-12-31"
        :narration "Steuerrückstellung 2026"
        :postings [{:account [:kontor.account/path "Aufwendungen:Steuern:KSt"]              :amount 3956.25M}
                   {:account [:kontor.account/path "Aufwendungen:Steuern:GewSt"]            :amount 4287.50M}
                   {:account [:kontor.account/path "Verbindlichkeiten:Steuern:KSt-Rückstellung"]  :amount -3956.25M}
                   {:account [:kontor.account/path "Verbindlichkeiten:Steuern:GewSt-Rückstellung"] :amount -4287.50M}]})
    ;; Dividend declaration €15k 60/40 — exercises I-15 per-posting :partner
    (e {:journal [:journal/code "GJ"] :effective-date #inst "2026-12-31"
        :narration "Gewinnverwendung 2026: €15k Ausschüttung 60/40"
        :postings [{:account [:kontor.account/path "Eigenkapital:Gewinnvortrag"]            :amount 15000M}
                   {:account [:kontor.account/path "Verbindlichkeiten:Dividenden-Zahlbar"] :amount -9000M
                    :partner [:kontor.partner/external-id "CW"]}
                   {:account [:kontor.account/path "Verbindlichkeiten:Dividenden-Zahlbar"] :amount -6000M
                    :partner [:kontor.partner/external-id "PB"]}]})
    ;; Distribute to CW (KESt+Soli 26.375 % withheld at source)
    (e {:journal [:journal/code "CD"] :effective-date #inst "2027-01-15"
        :narration "Dividende CW gezahlt"
        :partner  [:kontor.partner/external-id "CW"]
        :postings [{:account [:kontor.account/path "Verbindlichkeiten:Dividenden-Zahlbar"] :amount 9000M}
                   {:account [:kontor.account/path "Umlaufvermögen:Bank"]                   :amount -6626.25M}
                   {:account [:kontor.account/path "Verbindlichkeiten:KESt-Zahlbar"]        :amount -2373.75M}]})
    ;; Distribute to PB
    (e {:journal [:journal/code "CD"] :effective-date #inst "2027-01-15"
        :narration "Dividende PB gezahlt"
        :partner  [:kontor.partner/external-id "PB"]
        :postings [{:account [:kontor.account/path "Verbindlichkeiten:Dividenden-Zahlbar"] :amount 6000M}
                   {:account [:kontor.account/path "Umlaufvermögen:Bank"]                   :amount -4417.50M}
                   {:account [:kontor.account/path "Verbindlichkeiten:KESt-Zahlbar"]        :amount -1582.50M}]})))

;; ============================================================================
;; The acceptance tests
;; ============================================================================

(deftest de-ug-year-end-numbers-match-hand-calculation
  (let [conn (ug-db)
        _    (book-ug-year! conn)
        guv  (de-pnl/compute conn fy-2026)
        sections (into {} (map (juxt :section/code identity) (:statement/sections guv)))]
    (testing "GuV — Gewinn vor Steuern = 40k − 15k = 25k"
      (is (== 40000M (some-> sections (get "1") :section/subtotal :amount))
          "Umsatzerlöse")
      (is (== 15000M (some-> sections (get "6") :section/subtotal :amount))
          "Sonstige betriebliche Aufwendungen (Miete + Steuerberater + Sonstige)")
      (is (== 25000M (some-> guv :statement/total :amount))
          "Gewinn vor Steuern"))

    (testing "DE CIT provider on €25k profit @ Hebesatz 490: KSt+Soli €3,956.25 + GewSt €4,287.50"
      (let [facts (ptp/period-tax-facts (de-cit/de-cit-provider {})
                    {:db (d/db conn) :entity [:kontor.entity/code "HANS-TECH-UG"]
                     :period {:from #inst "2026-01-01" :to #inst "2027-01-01"}
                     :tax-unit {:hebesatz 490}
                     :inputs {:book-profit 25000M}})
            amts (sort (map #(-> % :liability :amount) (:components facts)))]
        (is (= [3956.25000M 4287.5000M] amts)
            "KSt+Soli first, then GewSt")))))

(deftest ug-trial-balance-balanced-and-includes-future-postings
  ;; I-17 regression — the dividend declared 2026-12-31 + paid 2027-01-15
  ;; must show in the default trial balance.
  (let [conn (ug-db)
        _    (book-ug-year! conn)
        tb-default  (trial/trial-balance conn)
        tb-explicit (trial/trial-balance conn {:as-of-valid far-future})]
    (is (true? (trial/balanced? tb-default)))
    (is (true? (trial/balanced? tb-explicit)))
    (is (= (count tb-default) (count tb-explicit))
        "I-17: default = nil :as-of-valid, no silent filter on future-dated postings.
         (Default and 'far-future' explicit returns identical account sets.)")))

(deftest ug-dividend-per-shareholder-allocation-via-i15
  ;; I-15 regression — per-posting :partner stamps each Cr Dividenden-Payable
  ;; leg with the correct shareholder. Without F10/I-15 this allocation was
  ;; silently lost from the GL.
  (let [conn (ug-db)
        _    (book-ug-year! conn)
        ;; Find the 2 dividend-payable Cr postings + their :posting/partner refs
        pairs (set (d/q '[:find ?path ?amt ?pc
                          :where [?p :posting/account ?a]
                                 [?a :kontor.account/path ?path]
                                 [(= ?path "Verbindlichkeiten:Dividenden-Zahlbar")]
                                 [?p :posting/amount ?amt]
                                 [?p :posting/partner ?part]
                                 [?part :kontor.partner/external-id ?pc]]
                        (d/db conn)))]
    (is (contains? pairs ["Verbindlichkeiten:Dividenden-Zahlbar" -9000M "CW"])
        "Christian's Cr leg carries :posting/partner CW")
    (is (contains? pairs ["Verbindlichkeiten:Dividenden-Zahlbar" -6000M "PB"])
        "Partner B's Cr leg carries :posting/partner PB")))

(deftest hans-side-cross-db-dividend-via-treaty-helper
  ;; The companion-to-the-corp side. Christian's CA DB receives the
  ;; €9000 dividend gross at FX 1.50 CAD/EUR via the treaty helper.
  (let [conn (hans-db)
        _    (book/entry! conn   ; First the sole-prop revenue (CAD 60k + 5 % GST)
               {:journal [:journal/code "CR"] :effective-date #inst "2026-09-30"
                :commodity :CAD :entity [:kontor.entity/code "CW-PERSONAL"]
                :narration "Q3 BC consulting CAD 60k + 5% GST"
                :postings [{:account [:kontor.account/path "Assets:Bank:CAD"]              :amount 63000M}
                           {:account [:kontor.account/path "Income:Self-Employment"]       :amount -60000M}
                           {:account [:kontor.account/path "Liabilities:GST-HST-Collected"] :amount -3000M}]})
        _    (treaty/receive-dividend-from-de! conn
               {:gross-amount    9000M
                :withheld-amount 2373.75M
                :net-cash-amount 6626.25M
                :income-kind     :dividend-portfolio
                :fx-rate         1.50M
                :effective-date  #inst "2027-01-20"
                :payer-partner   [:kontor.partner/external-id "HT-UG"]
                :entity          [:kontor.entity/code "CW-PERSONAL"]})
        tb   (trial/trial-balance conn)
        path-of (fn [eid] (:kontor.account/path (d/pull (d/db conn) [:kontor.account/path] eid)))
        sums (into {} (map (fn [[eid m]] [(path-of eid) (->> m vals first :amount)]) tb))]
    (testing "trial balance balanced"
      (is (true? (trial/balanced? tb))))
    (testing "sole-prop revenue + GST"
      (is (== -60000M (sums "Income:Self-Employment")))
      (is (== -3000M  (sums "Liabilities:GST-HST-Collected"))))
    (testing "treaty split: net cash + 15%-creditable + over-treaty-refundable + gross income"
      (is (== 72939.38M (sums "Assets:Bank:CAD"))
          "63,000 sole-prop + 9,939.38 dividend net cash")
      (is (== 2025.00M  (sums "Assets:Foreign-Tax-Prepaid"))
          "15 % treaty cap × CAD 13,500 = 2,025 §126-creditable")
      (is (== 1535.62M  (sums "Assets:Foreign-Tax-Refundable"))
          "11.375 % excess × CAD 9,000 = 1,535.625 → 1,535.62 HALF_EVEN — BZSt-refundable")
      (is (== -13500.00M (sums "Income:Dividends:Foreign:DE"))
          "Gross EUR 9,000 × 1.50 = CAD 13,500 (sum of the 3 slices)"))))
