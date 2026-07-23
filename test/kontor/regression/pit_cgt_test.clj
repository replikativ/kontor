(ns kontor.regression.pit-cgt-test
  "Regression suite — personal income tax + capital-gains tax across
   US / DE / CA. Locks in authority-sourced worked examples already
   validated in the per-jurisdiction module tests, and EXTENDS each with
   a fresh challenging scenario (short-vs-long split, loss carryforward,
   partial LCGE, CGT→PIT integration) computed by hand from the same
   statutory rate tables.

   Domains exercised:

   - **US PIT** — §1(j) 7-bracket federal, std deduction, CGT fold into
     the PIT base. Provider: `kontor.l10n-us.pit-provider`.
   - **US CGT** — §1(h) 0/15/20 LT brackets × filing-status, ST fold,
     §1411 NIIT 3.8 % surtax, LT loss carryforward.
   - **DE CGT** — §20 Abgeltungsteuer 25 % + Soli 5.5 %, §23 private
     speculation (10-y RE / 1-y movable cutoffs).
   - **CA CGT** — 50 % inclusion, LCGE ($1.275 M 2026 cap) with prior
     consumption, CCA recapture / capital split.

   Expected figures: reused values cite the module test they come from;
   new values are hand-computed from the cited statutory rate tables and
   annotated inline.

   Every money comparison uses `==` (numeric) — never `=` on doubles."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.provider :as disp-provider]
            [kontor.tax.period-tax-provider :as ptp]
            ;; US
            [kontor.l10n-us.pit-provider :as us-pit]
            [kontor.l10n-us.pit-statute :as us-pit-statute]
            [kontor.l10n-us.cgt-provider :as us-cgt]
            [kontor.l10n-us.cgt-statute :as us-cgt-statute]
            ;; DE
            [kontor.l10n-de.cgt-provider :as de-cgt]
            [kontor.l10n-de.cgt-statute :as de-cgt-statute]
            [kontor.l10n-de.cit-statute :as de-cit-statute]
            ;; CA
            [kontor.l10n-ca.cgt-provider :as ca-cgt]
            [kontor.l10n-ca.cgt-statute :as ca-cgt-statute]))

;; ============================================================================
;; Shared fixtures + helpers
;; ============================================================================

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- holdco-eid [conn]
  (d/q '[:find ?e . :where [?e :kontor.entity/code "HOLDCO"]] (d/db conn)))

(defn- component-by-lane
  "First component whose `:jurisdiction-specific-codes :lane` = `lane`."
  [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ---- US PIT --------------------------------------------------------------

(defn- us-pit-facts
  "Run the US PIT provider over `inputs` + `tax-unit` (as-of 2025-12-31)."
  ([tax-unit inputs] (us-pit-facts tax-unit inputs #inst "2025-12-31"))
  ([tax-unit inputs as-of]
   (let [conn (core/create-test-db)]
     (us-pit-statute/install! conn)
     (ptp/period-tax-facts
      (us-pit/us-pit-provider {})
      {:entity   :individual
       :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- pit-liability [facts]
  (-> facts :components first :liability :amount))

;; ---- US CGT --------------------------------------------------------------

(defn- us-cgt-db []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (us-cgt-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
                       :kontor.commodity/precision 2}
                      {:kontor.entity/code "HOLDCO" :kontor.entity/name "HoldCo"
                       :kontor.entity/kind :company :kontor.entity/country "US"
                       :kontor.entity/functional-commodity [:kontor.commodity/symbol "USD"]}])
    conn))

(defn- us-record! [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          [:kontor.entity/code "HOLDCO"]
                :kind            :sale
                :subject         [:kontor.commodity/symbol "USD"]
                :subject-kind    :fixed-asset
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity [:kontor.commodity/symbol "USD"]}
                :basis           {:amount 0M :commodity [:kontor.commodity/symbol "USD"]}}
               opts)))

(defn- us-cgt-facts [conn kind & [extra-ctx]]
  (let [source   (disp-provider/datahike-provider conn)
        provider (case kind
                   :individual  (us-cgt/us-individual-cgt-provider {:source source})
                   :corporation (us-cgt/us-corporate-cgt-provider  {:source source}))]
    (ptp/period-tax-facts
     provider (merge {:db (d/db conn) :entity (holdco-eid conn) :period p2026}
                     extra-ctx))))

;; ---- DE CGT --------------------------------------------------------------

(defn- de-cgt-db []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (de-cit-statute/install! conn)        ; DE.Soli.rate / DE.KSt.rate refs
    (de-cgt-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                       :kontor.commodity/precision 2}
                      {:kontor.entity/code "HOLDCO" :kontor.entity/name "HoldCo"
                       :kontor.entity/kind :company :kontor.entity/country "DE"
                       :kontor.entity/functional-commodity [:kontor.commodity/symbol "EUR"]}])
    conn))

(defn- de-record! [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          [:kontor.entity/code "HOLDCO"]
                :kind            :sale
                :subject         [:kontor.commodity/symbol "EUR"]
                :subject-kind    :participation
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity [:kontor.commodity/symbol "EUR"]}
                :basis           {:amount 0M :commodity [:kontor.commodity/symbol "EUR"]}}
               opts)))

(defn- de-cgt-facts [conn kind & [extra-ctx]]
  (let [source   (disp-provider/datahike-provider conn)
        provider (case kind
                   :individual  (de-cgt/de-personal-cgt-provider  {:source source})
                   :corporation (de-cgt/de-corporate-cgt-provider {:source source}))]
    (ptp/period-tax-facts
     provider (merge {:db (d/db conn) :entity (holdco-eid conn) :period p2026}
                     extra-ctx))))

;; ---- CA CGT --------------------------------------------------------------

(defn- ca-cgt-db []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (ca-cgt-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "CAD" :kontor.commodity/name "Canadian Dollar"
                       :kontor.commodity/precision 2}
                      {:kontor.entity/code "HOLDCO" :kontor.entity/name "HoldCo"
                       :kontor.entity/kind :company :kontor.entity/country "CA"
                       :kontor.entity/functional-commodity [:kontor.commodity/symbol "CAD"]}])
    conn))

(defn- ca-record! [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          [:kontor.entity/code "HOLDCO"]
                :kind            :sale
                :subject         [:kontor.commodity/symbol "CAD"]
                :subject-kind    :fixed-asset
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity [:kontor.commodity/symbol "CAD"]}
                :basis           {:amount 0M :commodity [:kontor.commodity/symbol "CAD"]}}
               opts)))

(defn- ca-cgt-facts [conn kind & [extra-ctx]]
  (let [source   (disp-provider/datahike-provider conn)
        provider (case kind
                   :individual  (ca-cgt/ca-individual-cgt-provider  {:source source})
                   :corporation (ca-cgt/ca-corporate-cgt-provider   {:source source}))]
    (ptp/period-tax-facts
     provider (merge {:db (d/db conn) :entity (holdco-eid conn) :period p2026}
                     extra-ctx))))

(defn- ca-summary [facts]
  (get-in (first (:components facts)) [:jurisdiction-specific-codes :cgt-summary]))

;; ============================================================================
;; §1. US PIT — §1(j) federal bracket fold (baseline lock + MFJ)
;; ============================================================================

(deftest us-pit-baseline-single-and-mfj-2025
  (testing "single $80k gross − $15k std → taxable $65k → $9 214.00
            (Rev. Proc. 2024-40; reuse pit-provider-test §2)"
    (is (== 9214.00M (pit-liability
                      (us-pit-facts {:filing-status :single}
                                    {:gross-income 80000M})))))
  (testing "MFJ $200k itemized → $33 828.00 (Rev. Proc. 2024-40 Table 1;
            reuse pit-provider-test §3)"
    (is (== 33828.00M (pit-liability
                       (us-pit-facts {:filing-status :mfj :itemized? true}
                                     {:gross-income 200000M})))))
  (testing "NEW — single $120k gross − $15k std → taxable $105k.
            §1(j) 2025 single: 11925@10 +(48475-11925)@12 +(103350-48475)@22
            +(105000-103350)@24 = 1192.50+4386.00+12072.50+396.00 = $18 047.00"
    (is (== 18047.00M (pit-liability
                       (us-pit-facts {:filing-status :single}
                                     {:gross-income 120000M}))))))

;; ============================================================================
;; §2. US CGT→PIT integration — a short-term gain folds into the PIT base
;; ============================================================================

(deftest us-cgt-short-term-gain-folds-into-pit-liability
  (testing "END-TO-END: a 6-month (short-term) $4k gain routes through the
            US CGT provider's :st lane into :pit-base-additions, which the
            US PIT provider then taxes at the ordinary marginal rate.

            Step 1 — CGT provider yields ST pit-base-additions [4000].
            Step 2 — PIT single $80k gross − $15k std + $4k ST = $69k.
            §1(j) 2025 single: 9214.00 (on 65k) + 4000@22% (880.00)
                             = $10 094.00."
    (let [conn (us-cgt-db)]
      (us-record! conn {:external-id "st-fold"
                        :acquired-on #inst "2025-12-01"
                        :disposed-on #inst "2026-04-01"
                        :proceeds {:amount 10000M :commodity [:kontor.commodity/symbol "USD"]}
                        :basis    {:amount  6000M :commodity [:kontor.commodity/symbol "USD"]}})
      (let [cgt (us-cgt-facts conn :individual {:tax-unit {:filing-status :single}})
            st  (component-by-lane cgt :st)
            st-add (first (get-in st [:jurisdiction-specific-codes :pit-base-additions]))]
        (testing "ST lane carries the +$4 000 with no standalone liability"
          (is (== 0M (-> st :liability :amount)))
          (is (== 4000M st-add)))
        ;; Feed the ST addition into the PIT provider.
        (let [pit (us-pit-facts {:filing-status :single}
                                {:gross-income 80000M
                                 :cgt-pit-base-additions st-add})]
          (is (== 10094.00M (pit-liability pit))
              "ordinary-rate tax on the folded ST gain"))))))

;; ============================================================================
;; §3. US CGT — §1(h) long-term brackets × filing status + loss carryforward
;; ============================================================================

(deftest us-cgt-long-term-1h-brackets
  (testing "single mid LT gain $200k → §1(h): 49450@0% + 150550@15%
            = $22 582.50 (reuse cgt-provider-test §3)"
    (let [conn (us-cgt-db)]
      (us-record! conn {:external-id "lt-mid"
                        :acquired-on #inst "2020-01-01"
                        :disposed-on #inst "2026-06-15"
                        :proceeds {:amount 300000M :commodity [:kontor.commodity/symbol "USD"]}
                        :basis    {:amount 100000M :commodity [:kontor.commodity/symbol "USD"]}})
      (let [lt (component-by-lane (us-cgt-facts conn :individual
                                                {:tax-unit {:filing-status :single}}) :lt)]
        (is (== 200000M (-> lt :base :amount)))
        (is (== 22582.5M (-> lt :liability :amount))))))

  (testing "MFJ small LT gain $100k → 98900@0% + 1100@15% = $165
            (reuse cgt-provider-test §3 MFJ threshold)"
    (let [conn (us-cgt-db)]
      (us-record! conn {:external-id "lt-mfj"
                        :acquired-on #inst "2020-01-01"
                        :disposed-on #inst "2026-06-15"
                        :proceeds {:amount 130000M :commodity [:kontor.commodity/symbol "USD"]}
                        :basis    {:amount  30000M :commodity [:kontor.commodity/symbol "USD"]}})
      (let [lt (component-by-lane (us-cgt-facts conn :individual
                                                {:tax-unit {:filing-status :mfj}}) :lt)]
        (is (== 165M (-> lt :liability :amount))))))

  (testing "NEW — single LT gain $200k with a $50k LT loss carry-in →
            base $150k → §1(h): 49450@0% + (150000-49450=100550)@15%
            = $15 082.50"
    (let [conn (us-cgt-db)]
      (us-record! conn {:external-id "lt-carry"
                        :acquired-on #inst "2020-01-01"
                        :disposed-on #inst "2026-06-15"
                        :proceeds {:amount 300000M :commodity [:kontor.commodity/symbol "USD"]}
                        :basis    {:amount 100000M :commodity [:kontor.commodity/symbol "USD"]}})
      (let [lt (component-by-lane
                (us-cgt-facts conn :individual
                              {:tax-unit {:filing-status :single}
                               :inputs   {:capital-loss-carryforward {:long 50000M}}}) :lt)]
        (is (== 150000M (-> lt :base :amount)))
        (is (== 15082.5M (-> lt :liability :amount)))))))

;; ============================================================================
;; §4. US CGT — §1411 NIIT 3.8 % surtax
;; ============================================================================

(deftest us-cgt-niit-surtax
  (testing "high-MAGI single incurs 3.8% NIIT on min(NII, MAGI-excess).
            NII 200k, MAGI 500k, threshold 200k → excess 300k →
            taxable min(200k,300k)=200k → 200k × 3.8% = $7 600
            (reuse cgt-provider-test §7)"
    (let [conn (us-cgt-db)]
      (us-record! conn {:external-id "niit-1"
                        :acquired-on #inst "2020-01-01"
                        :disposed-on #inst "2026-06-15"
                        :proceeds {:amount 300000M :commodity [:kontor.commodity/symbol "USD"]}
                        :basis    {:amount 100000M :commodity [:kontor.commodity/symbol "USD"]}})
      (let [niit (component-by-lane
                  (us-cgt-facts conn :individual
                                {:tax-unit {:filing-status :single}
                                 :inputs   {:net-investment-income 200000M
                                            :magi 500000M}}) :niit)]
        (is (some? niit))
        (is (== 7600M (-> niit :liability :amount))))))

  (testing "NEW — NIIT is capped by MAGI-excess when the excess is the
            binding constraint. NII 200k, MAGI 260k, threshold 200k →
            excess 60k → taxable min(200k,60k)=60k → 60k × 3.8% = $2 280"
    (let [conn (us-cgt-db)]
      (us-record! conn {:external-id "niit-capped"
                        :acquired-on #inst "2020-01-01"
                        :disposed-on #inst "2026-06-15"
                        :proceeds {:amount 300000M :commodity [:kontor.commodity/symbol "USD"]}
                        :basis    {:amount 100000M :commodity [:kontor.commodity/symbol "USD"]}})
      (let [niit (component-by-lane
                  (us-cgt-facts conn :individual
                                {:tax-unit {:filing-status :single}
                                 :inputs   {:net-investment-income 200000M
                                            :magi 260000M}}) :niit)]
        (is (some? niit) "NIIT still fires (MAGI 260k > 200k threshold)")
        (is (== 2280M (-> niit :liability :amount))
            "capped at min(NII, excess) = 60k × 3.8%")))))

;; ============================================================================
;; §5. DE CGT — §20 Abgeltungsteuer 25 % + Soli 5.5 %
;; ============================================================================

(deftest de-cgt-section-20-abgeltungsteuer
  (testing "€10k §20 stock gain → 25% = €2 500 + Soli 5.5% = €137.50
            → €2 637.50 (reuse de cgt-provider-test §5)"
    (let [conn (de-cgt-db)]
      (de-record! conn {:external-id "§20-a"
                        :asset-class :de-§20-stock
                        :subject-kind :securities-stock
                        :acquired-on #inst "2024-01-01"
                        :disposed-on #inst "2026-06-15"
                        :proceeds {:amount 30000M :commodity [:kontor.commodity/symbol "EUR"]}
                        :basis    {:amount 20000M :commodity [:kontor.commodity/symbol "EUR"]}})
      (let [§20 (component-by-lane (de-cgt-facts conn :individual) :de-§20)]
        (is (== 10000M (-> §20 :base :amount)))
        (is (== 2500M (-> §20 :gross-liability :amount)))
        (is (== 2637.50M (-> §20 :liability :amount))))))

  (testing "NEW — €50k §20 stock gain → 25% = €12 500 + Soli 5.5% of
            €12 500 = €687.50 → liability €13 187.50"
    (let [conn (de-cgt-db)]
      (de-record! conn {:external-id "§20-big"
                        :asset-class :de-§20-stock
                        :subject-kind :securities-stock
                        :acquired-on #inst "2024-01-01"
                        :disposed-on #inst "2026-06-15"
                        :proceeds {:amount 60000M :commodity [:kontor.commodity/symbol "EUR"]}
                        :basis    {:amount 10000M :commodity [:kontor.commodity/symbol "EUR"]}})
      (let [§20 (component-by-lane (de-cgt-facts conn :individual) :de-§20)]
        (is (== 50000M (-> §20 :base :amount)))
        (is (== 12500M (-> §20 :gross-liability :amount)))
        (is (== 13187.50M (-> §20 :liability :amount)))))))

;; ============================================================================
;; §6. DE CGT — §23 private speculation (RE 10-y / movable 1-y cutoffs)
;; ============================================================================

(deftest de-cgt-section-23-speculation-windows
  (testing "residential property held 8y5m (inside 10-y window) → €170k
            gain folds into PIT base (reuse de cgt-provider-test §6)"
    (let [conn (de-cgt-db)]
      (de-record! conn {:external-id "§23-re"
                        :asset-class :de-§23-real-estate
                        :subject-kind :real-estate-private
                        :acquired-on #inst "2018-03-15"
                        :disposed-on #inst "2026-08-20"
                        :proceeds {:amount 420000M :commodity [:kontor.commodity/symbol "EUR"]}
                        :basis    {:amount 250000M :commodity [:kontor.commodity/symbol "EUR"]}})
      (let [§23 (component-by-lane (de-cgt-facts conn :individual) :de-§23)]
        (is (some? §23))
        (is (== 170000M (-> §23 :base :amount)))
        (is (= [170000M] (get-in §23 [:jurisdiction-specific-codes :pit-base-additions]))))))

  (testing "same property held 12y (past the 10-y cutoff) → TAX-FREE →
            no §23 component (reuse de cgt-provider-test §6)"
    (let [conn (de-cgt-db)]
      (de-record! conn {:external-id "§23-cleared"
                        :asset-class :de-§23-real-estate
                        :acquired-on #inst "2014-01-01"
                        :disposed-on #inst "2026-06-15"
                        :proceeds {:amount 420000M :commodity [:kontor.commodity/symbol "EUR"]}
                        :basis    {:amount 250000M :commodity [:kontor.commodity/symbol "EUR"]}})
      (is (nil? (component-by-lane (de-cgt-facts conn :individual) :de-§23)))))

  (testing "NEW — movable asset (crypto) held 5 months (inside 1-y
            window) → €40k gain taxable; a second movable held 2 years
            is tax-free and does NOT add to the lane → base = €40 000"
    (let [conn (de-cgt-db)]
      (de-record! conn {:external-id "§23-crypto-fast"
                        :asset-class :de-§23-movable
                        :subject-kind :movable-private
                        :acquired-on #inst "2026-01-01"
                        :disposed-on #inst "2026-06-15"
                        :proceeds {:amount 50000M :commodity [:kontor.commodity/symbol "EUR"]}
                        :basis    {:amount 10000M :commodity [:kontor.commodity/symbol "EUR"]}})
      (de-record! conn {:external-id "§23-gold-slow"
                        :asset-class :de-§23-movable
                        :acquired-on #inst "2024-01-01"
                        :disposed-on #inst "2026-06-15"
                        :proceeds {:amount 25000M :commodity [:kontor.commodity/symbol "EUR"]}
                        :basis    {:amount  5000M :commodity [:kontor.commodity/symbol "EUR"]}})
      (let [§23 (component-by-lane (de-cgt-facts conn :individual) :de-§23)]
        (is (== 40000M (-> §23 :base :amount)))))))

;; ============================================================================
;; §7. CA CGT — 50 % inclusion + LCGE (2026 $1.275 M cap)
;; ============================================================================

(deftest ca-cgt-fifty-percent-and-lcge
  (testing "$10k gain → 50% = $5 000 taxable capital gain
            (reuse ca cgt-provider-test §2)"
    (let [conn (ca-cgt-db)]
      (ca-record! conn {:external-id "base"
                        :acquired-on #inst "2020-01-01"
                        :disposed-on #inst "2026-06-15"
                        :asset-class :ca-public-shares
                        :proceeds {:amount 50000M :commodity [:kontor.commodity/symbol "CAD"]}
                        :basis    {:amount 40000M :commodity [:kontor.commodity/symbol "CAD"]}})
      (let [s (ca-summary (ca-cgt-facts conn :individual))]
        (is (== 10000M (:gross-capital s)))
        (is (== 5000M  (:taxable-capital s))))))

  (testing "Mr. Singh QSBC sale $1.5M (basis $100), $0 prior LCGE →
            $1.275M sheltered, $224 900 net, $112 450 taxable
            (reuse ca cgt-provider-test §3 / Note 127 §2 Ex.A)"
    (let [conn (ca-cgt-db)]
      (ca-record! conn {:external-id "singh"
                        :acquired-on #inst "2019-01-01"
                        :disposed-on #inst "2026-06-15"
                        :asset-class :ca-qsbcs
                        :subject-form :corp
                        :proceeds {:amount 1500000M :commodity [:kontor.commodity/symbol "CAD"]}
                        :basis    {:amount 100M :commodity [:kontor.commodity/symbol "CAD"]}
                        :exemption-claimed #{:ca-lcge-qsbcs}})
      (let [s (ca-summary (ca-cgt-facts conn :individual {:inputs {:lcge-claimed-prior 0M}}))]
        (is (== 1499900M (:gross-capital s)))
        (is (== 1275000M (:lcge-applied s)))
        (is (== 224900M  (:net-capital s)))
        (is (== 112450M  (:taxable-capital s))))))

  (testing "NEW — QSBC gain $899 900 (proceeds $900 000, basis $100) with
            $700 000 prior LCGE already claimed → remaining cap
            $1 275 000 − $700 000 = $575 000 applied → net $324 900 →
            taxable 50% = $162 450"
    (let [conn (ca-cgt-db)]
      (ca-record! conn {:external-id "singh-partial"
                        :acquired-on #inst "2019-01-01"
                        :disposed-on #inst "2026-06-15"
                        :asset-class :ca-qsbcs
                        :proceeds {:amount 900000M :commodity [:kontor.commodity/symbol "CAD"]}
                        :basis    {:amount 100M :commodity [:kontor.commodity/symbol "CAD"]}
                        :exemption-claimed #{:ca-lcge-qsbcs}})
      (let [s (ca-summary (ca-cgt-facts conn :individual
                                        {:inputs {:lcge-claimed-prior 700000M}}))]
        (is (== 899900M (:gross-capital s)))
        (is (== 575000M (:lcge-applied s)) "only the remaining lifetime cap")
        (is (== 324900M (:net-capital s)))
        (is (== 162450M (:taxable-capital s)))))))

;; ============================================================================
;; §8. CA CGT — CCA recapture / capital split on depreciable property
;; ============================================================================

(deftest ca-cgt-depreciable-recapture-split
  (testing "capital cost $100k, NBV $40k (dep $60k), sold $150k →
            recapture $60k (ordinary) + capital $50k (50% → $25k taxable);
            PIT base receives both [25 000 60 000]
            (reuse ca cgt-provider-test §7)"
    (let [conn (ca-cgt-db)]
      (ca-record! conn {:external-id "cca"
                        :acquired-on #inst "2018-01-01"
                        :disposed-on #inst "2026-06-15"
                        :asset-class :ca-depreciable
                        :proceeds {:amount 150000M :commodity [:kontor.commodity/symbol "CAD"]}
                        :basis    {:amount  40000M :commodity [:kontor.commodity/symbol "CAD"]}
                        :depreciation-taken {:amount 60000M :commodity [:kontor.commodity/symbol "CAD"]}})
      (let [facts (ca-cgt-facts conn :individual)
            comp  (first (:components facts))
            s     (ca-summary facts)]
        (is (== 60000M (:ordinary-recapture s)))
        (is (== 50000M (:gross-capital s)))
        (is (== 25000M (:taxable-capital s)))
        (is (= [25000M 60000M]
               (get-in comp [:jurisdiction-specific-codes :pit-base-additions])))))))
