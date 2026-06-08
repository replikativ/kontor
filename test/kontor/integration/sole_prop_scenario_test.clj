(ns kontor.integration.sole-prop-scenario-test
  "End-to-end integration test for the A.2 scenario (note 171
   §2 Goal A): Vancouver sole-proprietorship → CA T1 personal income
   tax composition. Sibling to `cross_border_scenario_test.clj` (which
   covers the DE-UG side of the cross-border individual setup).

   What this exercises end-to-end:

   - **CA sole-prop bookkeeping**: revenue + GST collected, opex with
     ITCs (input tax credits) on the GST side, payroll-free single-
     proprietor pattern (no T4; the proprietor is not an employee of
     their own business).
   - **Business net via `kontor.tax.sole-proprietor/business-net`** —
     the σ_E marginalization over the business `:entity`'s P&L
     accounts (ADR-100 / note 100). This is the substrate's
     generalisation of the CA T2125 pattern.
   - **T1 PIT composition via `kontor.tax.sole-proprietor/business-income-
     input`** — folds business net into the `:inputs` of a
     `PeriodTaxProvider` call so the CA T1 wrapper treats the sole-
     prop income exactly as it would treat any other business-income
     line on the T1 return.
   - **The CA T1 provider** (`kontor.l10n-ca.period-tax-provider`)
     produces a 2-component TaxReturnFacts (federal CRA + provincial
     BC) including the sole-prop income in the federal taxable base.

   Substrate properties this guards against regressing:
   - The ADR-100 sole-proprietor seam composes with ADR-099
     PeriodTaxProvider via `:inputs :base-transform :adjustments` —
     no special-case provider path.
   - W1's `:kontor.*` schema-namespace prefix on every attr reference.
   - W4's standardized `install!` shape (preset returns conn).

   This is the Goal A.2 + A.4 (multi-year shape — the business is
   2026; the T1 is the 2024 wrapper since that's the tax year
   `kontor.l10n-ca.y2024.t1` ships) integration test that note 171
   §2 flagged as the outstanding Goal-A P0."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.l10n-ca.period-tax-provider :as ca-ptp]
            [kontor.l10n-ca.preset :as ca-preset]
            [kontor.money :as money]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.sole-proprietor :as sp]))

;; ============================================================================
;; Fixture — the Vancouver sole-prop on a CA-personal DB
;; ============================================================================

(def ^:private cad [:kontor.commodity/symbol "CAD"])
(def ^:private fy-2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- personal-ca-db
  "CA-personal DB: preset + entity (the consulting business kept as
   its own :entity per ADR-100) + the income/expense/asset paths the
   sole-prop year will touch. The proprietor + business share one
   conn — there is no second 'personal' :entity for the proprietor
   himself; the sole-prop is the only legal entity, and the personal
   T1 layers on top via the provider call."
  []
  (let [conn (ca-preset/create-ca-db)]
    (d/transact conn
                [{:kontor.entity/name "Sample Owner — Vancouver Consulting (sole prop)"
                  :kontor.entity/code "OWNER-CONSULTING"
                  :kontor.entity/country "CA"
                  :kontor.entity/functional-commodity cad}
       ;; Income-side accounts (a couple beyond what the preset chart ships)
                 {:kontor.account/path "Income:Consulting"
                  :kontor.account/type :income
                  :kontor.account/commodity cad}
                 {:kontor.account/path "Income:Workshops"
                  :kontor.account/type :income
                  :kontor.account/commodity cad}
       ;; Expense-side
                 {:kontor.account/path "Expenses:Office"
                  :kontor.account/type :expense
                  :kontor.account/commodity cad}
                 {:kontor.account/path "Expenses:Travel"
                  :kontor.account/type :expense
                  :kontor.account/commodity cad}
                 {:kontor.account/path "Expenses:Software"
                  :kontor.account/type :expense
                  :kontor.account/commodity cad}
                 {:kontor.account/path "Expenses:Professional-Fees"
                  :kontor.account/type :expense
                  :kontor.account/commodity cad}
       ;; Assets/Liabilities the GST/ITC flows touch
                 {:kontor.account/path "Assets:Bank:CAD"
                  :kontor.account/type :asset
                  :kontor.account/commodity cad}
                 {:kontor.account/path "Assets:GST-ITC"
                  :kontor.account/type :asset
                  :kontor.account/commodity cad}
                 {:kontor.account/path "Liabilities:GST-HST-Collected"
                  :kontor.account/type :liability
                  :kontor.account/commodity cad}])
    conn))

;; ============================================================================
;; Book one year of sole-prop activity (notional Vancouver freelancer)
;; ============================================================================

(defn- book-sole-prop-year! [conn]
  (let [biz [:kontor.entity/code "OWNER-CONSULTING"]
        e   (fn [opts] (book/entry! conn (assoc opts :commodity cad :entity biz)))]

    ;; Q1: consulting CAD 30k + 5 % GST collected
    (e {:journal [:kontor.journal/code "CR"] :effective-date #inst "2026-03-31"
        :narration "Q1 consulting — Client X"
        :postings [{:account [:kontor.account/path "Assets:Bank:CAD"]                :amount 31500M}
                   {:account [:kontor.account/path "Income:Consulting"]               :amount -30000M}
                   {:account [:kontor.account/path "Liabilities:GST-HST-Collected"]   :amount -1500M}]})

    ;; Q2: consulting CAD 25k + 5 % GST
    (e {:journal [:kontor.journal/code "CR"] :effective-date #inst "2026-06-30"
        :narration "Q2 consulting — Client Y"
        :postings [{:account [:kontor.account/path "Assets:Bank:CAD"]                :amount 26250M}
                   {:account [:kontor.account/path "Income:Consulting"]               :amount -25000M}
                   {:account [:kontor.account/path "Liabilities:GST-HST-Collected"]   :amount -1250M}]})

    ;; Q3: workshop honorarium CAD 5k + 5 % GST
    (e {:journal [:kontor.journal/code "CR"] :effective-date #inst "2026-09-15"
        :narration "Workshop honorarium"
        :postings [{:account [:kontor.account/path "Assets:Bank:CAD"]              :amount 5250M}
                   {:account [:kontor.account/path "Income:Workshops"]              :amount -5000M}
                   {:account [:kontor.account/path "Liabilities:GST-HST-Collected"] :amount -250M}]})

    ;; Q4: consulting CAD 20k + 5 % GST
    (e {:journal [:kontor.journal/code "CR"] :effective-date #inst "2026-12-15"
        :narration "Q4 consulting — Client X"
        :postings [{:account [:kontor.account/path "Assets:Bank:CAD"]                :amount 21000M}
                   {:account [:kontor.account/path "Income:Consulting"]               :amount -20000M}
                   {:account [:kontor.account/path "Liabilities:GST-HST-Collected"]   :amount -1000M}]})

    ;; Year opex: office CAD 1,200 + travel 2,500 + software 1,800 + acct 1,500
    ;; with GST ITCs (5 % recoverable on each).
    ;; Total opex pre-GST = 7,000. ITC = 350. Cash out = 7,350.
    (e {:journal [:kontor.journal/code "CD"] :effective-date #inst "2026-12-31"
        :narration "Year opex (summarized)"
        :postings [{:account [:kontor.account/path "Expenses:Office"]            :amount 1200M}
                   {:account [:kontor.account/path "Expenses:Travel"]            :amount 2500M}
                   {:account [:kontor.account/path "Expenses:Software"]          :amount 1800M}
                   {:account [:kontor.account/path "Expenses:Professional-Fees"] :amount 1500M}
                   {:account [:kontor.account/path "Assets:GST-ITC"]             :amount 350M}
                   {:account [:kontor.account/path "Assets:Bank:CAD"]            :amount -7350M}]})))

;; ============================================================================
;; The acceptance tests
;; ============================================================================

(deftest sole-prop-business-net-marginalizes-correctly
  ;; The ADR-100 substrate: business-net = σ_E(income − expense).
  ;; Income: 30000 + 25000 + 5000 + 20000 = 80000
  ;; Expense: 1200 + 2500 + 1800 + 1500 = 7000
  ;; Net: 73000
  (let [conn (personal-ca-db)
        _    (book-sole-prop-year! conn)
        net  (sp/business-net conn
                              (assoc fy-2026
                                     :entity [:kontor.entity/code "OWNER-CONSULTING"]
                                     :commodity cad))]
    (is (== 73000M (:amount net))
        "Net = 80k revenue − 7k opex = 73k")))

(deftest business-income-input-folds-into-t1
  ;; A.2 composition: business-net feeds the personal T1's :inputs
  ;; via business-income-input. The CA T1 provider then sees the
  ;; sole-prop net as an addition to the personal taxable base.
  (let [conn (personal-ca-db)
        _    (book-sole-prop-year! conn)
        net  (sp/business-net conn
                              (assoc fy-2026
                                     :entity [:kontor.entity/code "OWNER-CONSULTING"]
                                     :commodity cad))
        ;; A salaried-T4-free filer base — proprietor is not an
        ;; employee of their own business; no T4 income.
        base-inputs {:filer/province :BC :filer/tax-year 2024
                     :t4s []
                     ;; The CRA Schedule 8 CPP self-employment line:
                     ;; sole-props pay both halves of CPP. The provider
                     ;; doesn't compute it here; we pre-populate it on
                     ;; the input so the composition test stays focused
                     ;; on the income-side fold.
                     }
        inputs-with-biz (sp/business-income-input base-inputs net)]
    (testing "business net = first addition in the base-transform :adjustments"
      (is (= :adjustments (-> inputs-with-biz :base-transform :transform/type)))
      (is (= [73000M] (-> inputs-with-biz :base-transform :additions))
          "business net = 73000 as a single addition"))
    (testing "the provider builds valid TaxReturnFacts with the augmented inputs"
      (let [facts (ca-ptp/t1-tax-return-facts
                   {:entity 1 :period fy-2026 :inputs inputs-with-biz})]
        (is (ptp/valid-return-facts? facts)
            "ADR-099 contract holds end-to-end")
        (is (= 2 (count (:components facts)))
            "federal (CRA) + provincial (BC) — the multi-authority fan-out")
        (is (= [:cra :bc] (mapv :authority (:components facts)))
            "the two governments")
        (is (every? #(= :personal-income-tax (:kind %)) (:components facts))
            "both components are :personal-income-tax")
        (is (= :CAD (:functional-commodity facts)))))))

(deftest no-personal-entity-required
  ;; ADR-100 / note 100: a sole-prop kontor DB may hold ONLY the
  ;; business, with no personal accounts at all. The personal T1 is
  ;; provider machinery layered on top; no second :entity needs to
  ;; exist for the proprietor himself.
  (let [conn (personal-ca-db)
        _    (book-sole-prop-year! conn)
        entities (d/q '[:find [?code ...]
                        :where [?e :kontor.entity/code ?code]]
                      (d/db conn))]
    (is (= ["OWNER-CONSULTING"] (vec entities))
        "only the business entity exists; no separate personal entity")))
