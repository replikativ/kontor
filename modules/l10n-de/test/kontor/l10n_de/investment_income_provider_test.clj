(ns kontor.l10n-de.investment-income-provider-test
  "Tests for the DE investment-income provider (ADR-099 + ADR-101,
   research note 147). Coverage:

   - §1 Standalone Abgeltungsteuer (no church) + Soli — Frau Schmidt
     worked example (note 147 §2.1).
   - §2 Kirchensteuer 8 % and 9 % paths — §32d Abs. 1 formula
     `(e − 4q) / (4 + k)`.
   - §3 Sparer-Pauschbetrag single (€1 000) and joint (€2 000) from 2023.
   - §4 Günstigerprüfung — suppress standalone + fold into PIT base.
   - §5 Teileinkünfteverfahren — 60 % slice into PIT base.
   - §6 §20-other carry-in netting — note 147 §1.6.
   - §7 Herr Weber retiree (note 147 §2.2) — Path A standalone."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.l10n-de.cgt-statute :as cgt-statute]
            [kontor.l10n-de.cit-statute :as cit-statute]
            [kontor.l10n-de.investment-income-provider :as inv]
            [kontor.l10n-de.investment-income-statute :as inv-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the DE CIT statute (for `DE.Soli.rate`), CGT
   statute (for `DE.EStG.§20.flat-rate` + `DE.EStG.§17.inclusion-rate`),
   and DE investment-income statute installed."
  []
  (let [conn (core/create-test-db)]
    (cit-statute/install! conn)
    (cgt-statute/install! conn)
    (inv-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                       :kontor.commodity/precision 2}])
    conn))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})
;; p2022 uses :to inside 2022 so as-of (defaulted to :to) lands BEFORE
;; the 2023-01-01 Sparer-Pauschbetrag cutover.
(def ^:private p2022 {:from #inst "2022-01-01" :to #inst "2022-12-31"})

(defn- run-provider
  "Build the provider and call `period-tax-facts` with the supplied
   bases pre-injected via `:inputs :investment-income-bases` (the test
   path — skips the GL marginalize). Extra ctx is merged on top."
  [conn bases & [extra-ctx]]
  (let [provider (inv/de-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db     (d/db conn)
             :entity nil
             :period p2026
             :inputs (merge {:investment-income-bases bases}
                            (:inputs extra-ctx))}
            (dissoc extra-ctx :inputs)))))

(defn- component-by-lane [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ============================================================================
;; §1. Frau Schmidt — note 147 §2.1 — standalone Abgeltungsteuer + Soli
;; ============================================================================

(deftest standalone-abgeltungsteuer-no-church-note-147-§2-1
  (testing "single, no church, no Günstigerprüfung: €5080 → €1076.10"
    (let [conn  (fresh)
          facts (run-provider conn
                              {:dividends           3500M
                               :interest            1580M    ; 1200 bond + 380 savings
                               :fund-distributions  0M
                               :royalties           0M}
                              {:tax-unit {:filing-status :single
                                          :church-tax-rate 0M}})
          §20   (component-by-lane facts :de-§20-income)]
      (is (some? §20) "the standalone §20-income component is present")
      (is (== 4080M (-> §20 :base :amount))
          "5080 − 1000 Sparer-Pauschbetrag = 4080 taxable base")
      (is (== 1020M (-> §20 :gross-liability :amount))
          "25 % × 4080 = 1020 Abgeltungsteuer")
      (is (== 1076.10M (-> §20 :liability :amount))
          "+ 5.5 % Soli (56.10) = 1076.10 total liability")
      (is (= :abgeltungsteuer (:regime §20)))
      (is (some #(and (= :soli-on-§20-income (:code %))
                      (== 56.10M (:amount %)))
                (:surtaxes §20))
          "Soli surtax line records 5.5 % × 1020 = 56.10")
      ;; KiSt provision still fires for audit purposes when k=0; amount is 0.
      (is (some #(and (= :kist-on-§20 (:code %))
                      (== 0M (:amount %)))
                (:surtaxes §20))
          "KiSt provision fires (audit) but contributes 0 when k=0"))))

(deftest no-component-emitted-when-base-is-zero
  (testing "no §20 income at all → no components emitted"
    (let [conn  (fresh)
          facts (run-provider conn
                              {:dividends           0M
                               :interest            0M
                               :fund-distributions  0M
                               :royalties           0M}
                              {:tax-unit {:filing-status :single
                                          :church-tax-rate 0M}})]
      (is (empty? (:components facts))))))

(deftest sparer-pauschbetrag-fully-absorbs-small-base
  (testing "€800 in dividends < €1000 Sparer-Pauschbetrag → 0 tax, no component"
    (let [conn  (fresh)
          facts (run-provider conn
                              {:dividends 800M :interest 0M
                               :fund-distributions 0M :royalties 0M}
                              {:tax-unit {:filing-status :single
                                          :church-tax-rate 0M}})]
      ;; gross-§20 = 800; taxable = max(0, 800 − 1000) = 0; component
      ;; suppressed (no taxable, no loss-bucket-contribution).
      (is (empty? (:components facts))))))

;; ============================================================================
;; §2. Kirchensteuer paths — §32d Abs. 1 formula
;; ============================================================================

(deftest kirchensteuer-9pct-formula
  (testing "Catholic single, k=0.09: 4080/(4+0.09) × (1 + 0.055 + 0.09)"
    (let [conn  (fresh)
          facts (run-provider conn
                              {:dividends 3500M :interest 1580M
                               :fund-distributions 0M :royalties 0M}
                              {:tax-unit {:filing-status :single
                                          :church-tax-rate 0.09M}})
          §20   (component-by-lane facts :de-§20-income)
          ;; Reference computation (BigDecimal exact):
          base  4080M
          k     0.09M
          rate  (with-precision 34 (/ 1M (+ 4M k)))   ; 1/4.09
          abgst (* base rate)
          soli  (* abgst 0.055M)
          kist  (* abgst k)
          total (+ abgst soli kist)]
      (is (some? §20))
      (is (== base (-> §20 :base :amount)))
      (is (== abgst (-> §20 :gross-liability :amount))
          "Abgeltungsteuer = base / (4 + k)")
      (is (== total (-> §20 :liability :amount))
          "liability = Abgst + Soli + KiSt"))))

(deftest kirchensteuer-8pct-by-bw
  (testing "Bavarian single, k=0.08: confirms the 8% rate path"
    (let [conn  (fresh)
          facts (run-provider conn
                              {:dividends 3500M :interest 1580M
                               :fund-distributions 0M :royalties 0M}
                              {:tax-unit {:filing-status :single
                                          :church-tax-rate 0.08M}})
          §20   (component-by-lane facts :de-§20-income)
          base  4080M
          k     0.08M
          rate  (with-precision 34 (/ 1M (+ 4M k)))
          abgst (* base rate)
          kist  (* abgst k)]
      (is (== abgst (-> §20 :gross-liability :amount)))
      (is (some #(and (= :kist-on-§20 (:code %))
                      (== kist (:amount %)))
                (:surtaxes §20))
          "KiSt surtax line is 8 % × Abgeltungsteuer"))))

;; ============================================================================
;; §3. Sparer-Pauschbetrag — single (€1000) vs joint (€2000)
;; ============================================================================

(deftest sparer-pauschbetrag-joint-2000
  (testing "joint filers get €2000 Sparer-Pauschbetrag (from 2023)"
    (let [conn  (fresh)
          facts (run-provider conn
                              {:dividends 2500M :interest 1000M
                               :fund-distributions 0M :royalties 0M}
                              {:tax-unit {:filing-status :joint
                                          :church-tax-rate 0M}})
          §20   (component-by-lane facts :de-§20-income)]
      ;; 3500 - 2000 = 1500 taxable; 25% = 375; +5.5% Soli = 395.625
      (is (== 1500M (-> §20 :base :amount)))
      (is (== 375M (-> §20 :gross-liability :amount)))
      (is (== 395.625M (-> §20 :liability :amount))))))

(deftest sparer-pauschbetrag-pre-2023-single-801
  (testing "for periods before 2023-01-01 the single SP was €801"
    (let [conn  (fresh)
          facts (run-provider conn
                              {:dividends 1000M :interest 0M
                               :fund-distributions 0M :royalties 0M}
                              {:tax-unit {:filing-status :single
                                          :church-tax-rate 0M}
                               :period p2022})
          §20   (component-by-lane facts :de-§20-income)]
      ;; 1000 - 801 = 199 taxable; 25% = 49.75; +5.5% = 52.486...
      (is (== 199M (-> §20 :base :amount))
          "pre-2023 Sparer-Pauschbetrag was €801"))))

;; ============================================================================
;; §4. Günstigerprüfung — §32d Abs. 6 EStG
;; ============================================================================

(deftest günstigerprüfung-suppresses-standalone-folds-into-pit
  (testing ":abgeltungsteuer-elect-marginal? true → §20 folds into PIT base"
    (let [conn  (fresh)
          facts (run-provider conn
                              {:dividends 2800M :interest 1600M
                               :fund-distributions 0M :royalties 0M}
                              {:tax-unit {:filing-status :single
                                          :church-tax-rate 0.08M
                                          :abgeltungsteuer-elect-marginal? true}})
          standalone (component-by-lane facts :de-§20-income)
          günstig    (component-by-lane facts :de-§20-günstig)]
      (is (nil? standalone)
          "standalone Abgeltungsteuer component suppressed")
      (is (some? günstig)
          "Günstigerprüfung fold component is emitted")
      ;; 4400 - 1000 Sparer-Pauschbetrag (survives, BFH VIII R 14/13) = 3400
      (is (== 3400M (-> günstig :base :amount))
          "Sparer-Pauschbetrag survives Günstigerprüfung — BFH VIII R 14/13")
      (is (= [3400M] (get-in günstig [:jurisdiction-specific-codes :pit-base-additions]))
          "consumer's PIT provider absorbs 3400 into the marginal base")
      (is (= :günstigerprüfung (:regime günstig))))))

;; ============================================================================
;; §5. Teileinkünfteverfahren — §32d Abs. 2 Nr. 3
;; ============================================================================

(deftest teileinkünfte-slice-60pct-folds-into-pit
  (testing "elected dividends are removed from the Abgst base + folded at 60% into PIT"
    (let [conn  (fresh)
          ;; 5000 in dividends, of which 2000 elected for Teileinkünfte.
          ;; Standalone Abgeltungsteuer base = (5000 − 2000) + 0 = 3000;
          ;; minus Sparer-Pauschbetrag = 2000 taxable.
          ;; Teileinkünfte fold = 2000 × 60% = 1200 into PIT.
          facts (run-provider conn
                              {:dividends         5000M
                               :elected-dividends 2000M
                               :interest          0M
                               :fund-distributions 0M
                               :royalties         0M}
                              {:tax-unit {:filing-status :single
                                          :church-tax-rate 0M}})
          §20         (component-by-lane facts :de-§20-income)
          teilein     (component-by-lane facts :de-§20-teileinkünfte)]
      (is (some? §20))
      (is (== 2000M (-> §20 :base :amount))
          "elected slice removed from Abgst base; 3000 gross − 1000 SP = 2000")
      (is (some? teilein))
      (is (== 1200M (-> teilein :base :amount))
          "60 % × 2000 elected dividends = 1200")
      (is (= [1200M] (get-in teilein [:jurisdiction-specific-codes :pit-base-additions]))))))

(deftest teileinkünfte-suppressed-under-günstigerprüfung
  (testing "Günstigerprüfung folds EVERYTHING — Teileinkünfte slice is absorbed"
    (let [conn  (fresh)
          facts (run-provider conn
                              {:dividends         5000M
                               :elected-dividends 2000M
                               :interest          0M
                               :fund-distributions 0M
                               :royalties         0M}
                              {:tax-unit {:filing-status :single
                                          :church-tax-rate 0M
                                          :abgeltungsteuer-elect-marginal? true}})]
      ;; Under Günstigerprüfung the standalone + Teileinkünfte fold are
      ;; both replaced by the single §20-günstig component covering the
      ;; non-elected slice (the Teileinkünfte election remains a per-issuer
      ;; structural matter the PIT provider can re-fold; v1 only emits
      ;; the standalone-replacement component, NOT a duplicate Teileinkünfte
      ;; fold).
      (is (nil? (component-by-lane facts :de-§20-teileinkünfte))
          "Teileinkünfte fold NOT emitted under Günstigerprüfung")
      (is (some? (component-by-lane facts :de-§20-günstig))))))

;; ============================================================================
;; §6. §20-other carry-in netting — § 20 Abs. 6 EStG
;; ============================================================================

(deftest de-§20-other-carry-in-reduces-base
  (testing "shared :de-§20-other carry-in (note 147 §1.6) is consumed"
    (let [conn  (fresh)
          facts (run-provider conn
                              {:dividends 3000M :interest 2000M
                               :fund-distributions 0M :royalties 0M}
                              {:tax-unit {:filing-status :single
                                          :church-tax-rate 0M}
                               :inputs {:capital-loss-carryforward
                                        {:de-§20-other 1500M}}})
          §20   (component-by-lane facts :de-§20-income)]
      ;; 5000 - 1000 SP - 1500 carry-in = 2500 taxable
      (is (== 2500M (-> §20 :base :amount))))))

;; ============================================================================
;; §7. Herr Weber retiree — note 147 §2.2 Path A
;; ============================================================================

(deftest herr-weber-path-a-note-147-§2-2
  (testing "BY retiree, Catholic, single, k=0.08: 4400 → ~945.83"
    (let [conn  (fresh)
          facts (run-provider conn
                              {:dividends 2800M :interest 1600M
                               :fund-distributions 0M :royalties 0M}
                              {:tax-unit {:filing-status :single
                                          :church-tax-rate 0.08M}})
          §20   (component-by-lane facts :de-§20-income)
          base  3400M
          k     0.08M
          rate  (with-precision 34 (/ 1M (+ 4M k)))
          abgst (* base rate)
          soli  (* abgst 0.055M)
          kist  (* abgst k)
          total (+ abgst soli kist)]
      (is (== base (-> §20 :base :amount)))
      (is (== abgst (-> §20 :gross-liability :amount)))
      (is (== total (-> §20 :liability :amount)))
      ;; Note 147 §2.2 quotes the values rounded to cents: 833.33 + 45.83
      ;; + 66.67 = 945.83. Confirm we are within 1 cent of that.
      (let [expected 945.83M
            diff     (.abs (.subtract ^java.math.BigDecimal (bigdec total) expected))]
        (is (< diff 0.01M)
            (str "liability " total " is within 1 cent of note 147 §2.2's 945.83"))))))

;; ============================================================================
;; §8. Component kind is :investment-income-tax
;; ============================================================================

(deftest components-use-investment-income-tax-kind
  (testing "all emitted components carry :kind :investment-income-tax"
    (let [conn  (fresh)
          facts (run-provider conn
                              {:dividends 5000M :elected-dividends 2000M
                               :interest 1000M :fund-distributions 500M
                               :royalties 0M}
                              {:tax-unit {:filing-status :single
                                          :church-tax-rate 0M}})]
      (is (seq (:components facts)) "at least one component emitted")
      (is (every? #(= :investment-income-tax (:kind %)) (:components facts))
          "every component carries the period-tax kind"))))

;; ============================================================================
;; §9. Provider id + statute string
;; ============================================================================

(deftest provider-shape
  (let [provider (inv/de-investment-income-provider {})]
    (is (= :de-investment-income (ptp/provider-id provider)))
    (is (= :EUR (:commodity provider)))
    (is (= :de-finanzamt (:authority provider)))))

;; ============================================================================
;; §8. GL-scan integration — F7 regression guard (note 159)
;;
;; The canonical chart-of-accounts convention is `:account/path` (unique
;; identity). Pre-fix (commits 0ad48aa / 4a5158b), the IC provider's
;; GL-scan marginalized on `:account/code`, so a consumer using the
;; documented chart got silent zero income detected — no error, no tax.
;; This test seeds a real GL with `:account/path` accounts, posts via
;; `kontor.book`, calls `period-tax-facts` WITHOUT `:investment-income-
;; bases` (so the GL-scan path fires), and verifies non-zero tax.
;; ============================================================================

(deftest single-install-path-yields-full-soli-stack
  (testing "F8 regression (note 159): `inv-statute/install!` alone must ship
            BOTH KiSt AND Soli provisions; consumer dividend → full 25 % +
            5.5 % Soli (note 168 §S2 — single install entry per concept)"
    (let [conn (core/create-test-db)]
      (cit-statute/install! conn)
      (cgt-statute/install! conn)
      (inv-statute/install! conn)        ; ← the statute ns directly
      (d/transact conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                         :kontor.commodity/precision 2}])
      (let [provider (inv/de-investment-income-provider {})
            facts (ptp/period-tax-facts
                   provider
                   {:db (d/db conn) :entity nil
                    :period p2026
                    :tax-unit {:filing-status :single :church-tax-rate 0M}
                    :inputs {:investment-income-bases
                             {:dividends 4000M :interest 0M
                              :fund-distributions 0M :royalties 0M
                              :elected-dividends 0M}}})
            §20   (component-by-lane facts :de-§20-income)]
        (is (some? §20))
        (is (== 3000M (-> §20 :base :amount))
            "base = 4000 − 1000 SP = 3000")
        (is (== 750M (-> §20 :gross-liability :amount))
            "25 % × 3000 = 750 Abgst")
        (is (== 791.25M (-> §20 :liability :amount))
            "+ 5.5 % Soli (41.25) = 791.25 — Soli MUST fire from inv-statute/install! alone")
        (is (some #(= :soli-on-§20-income (:code %)) (:surtaxes §20))
            "Soli surtax line present (F8 regression — no silent omission)")))))

(deftest gl-scan-resolves-against-account-path-convention
  (testing "GL-scan picks up dividend postings on `:account/path`-keyed chart"
    (let [conn (fresh)
          eur  [:kontor.commodity/symbol "EUR"]]
      ;; Minimal chart using the canonical :account/path convention.
      (d/transact conn
        [{:account/path "Assets:Bank"               :account/type :asset
          :account/commodity eur}
         {:account/path "Income:Dividends"          :account/type :income
          :account/commodity eur}
         {:journal/code "CR" :journal/type :cash :journal/name "Cash Receipts"}])
      ;; Record €4,000 of dividend income.
      (book/entry! conn
        {:journal [:journal/code "CR"]
         :effective-date #inst "2026-06-15"
         :commodity eur
         :narration "Acme dividend"
         :postings [{:account [:account/path "Assets:Bank"]
                     :amount 4000M}
                    {:account [:account/path "Income:Dividends"]
                     :amount -4000M}]})
      ;; Provider with NO pre-supplied :investment-income-bases —
      ;; forces the GL-scan path.
      (let [provider (inv/de-investment-income-provider {})
            facts (ptp/period-tax-facts
                   provider
                   {:db (d/db conn) :conn conn :entity nil
                    :period p2026
                    :tax-unit {:filing-status :single :church-tax-rate 0M}})
            §20   (component-by-lane facts :de-§20-income)]
        (is (some? §20)
            "F7 regression: GL-scan must produce a §20 component from
             :account/path-keyed dividend postings")
        ;; €4,000 dividends − €1,000 Sparer-Pauschbetrag = €3,000 base
        (is (== 3000M (-> §20 :base :amount))
            "base = 4000 − 1000 SP = 3000")
        ;; 25 % × 3000 = €750 Abgeltungsteuer
        (is (== 750M (-> §20 :gross-liability :amount)))
        ;; + 5.5 % Soli on 750 = 41.25 → liability 791.25
        (is (== 791.25M (-> §20 :liability :amount)))))))
