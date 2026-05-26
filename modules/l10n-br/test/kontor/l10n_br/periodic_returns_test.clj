(ns kontor.l10n-br.periodic-returns-test
  "Tests for kontor.l10n-br.periodic-returns — the per-authority
   monthly aggregations for BR indirect-tax filing.

   Scenario: a fixture month with several B2B and services invoices,
   stamped onto Jan 2026. We verify the per-authority numbers each
   generator produces:
     - PIS / COFINS aggregation (EFD-Contribuições / DCTFWeb)
     - ICMS aggregation per-state (EFD ICMS/IPI / GIA)
     - IPI aggregation (manufacturing tax)
     - ISS aggregation (municipal)
     - DCTFWeb federal consolidation"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-br.chart :as chart]
            [kontor.l10n-br.invoice :as inv]
            [kontor.l10n-br.periodic-returns :as pr]
            [kontor.money :as money]
            [kontor.validation :as v]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def jan-1   #inst "2026-01-01T00:00:00Z")
(def jan-10  #inst "2026-01-10T00:00:00Z")
(def jan-15  #inst "2026-01-15T00:00:00Z")
(def jan-20  #inst "2026-01-20T00:00:00Z")
(def jan-25  #inst "2026-01-25T00:00:00Z")
(def feb-1   #inst "2026-02-01T00:00:00Z")
(def feb-15  #inst "2026-02-15T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:kontor.journal/code "INV"
                       :kontor.journal/name "Sales"
                       :kontor.journal/type :sale
                       :kontor.journal/active true}])
    conn))

(defn- ≈ [a b] (money/equiv? a b))

(defn- brl [s] (money/money (bigdec s) :BRL))

(defn- seed-january!
  "Post a month's invoice activity in Jan 2026:
     - SP → SP goods R$1000 on Jan 10 (ICMS 180, PIS 13.53, COFINS 62.32)
     - SP → SP goods R$2000 on Jan 15 (ICMS 360, PIS 27.06, COFINS 124.64)
     - Services R$500 on Jan 20 with ISS 5% (ISS 25, PIS 8.25, COFINS 38)
     - SP → SP manufactured R$1000 on Jan 25 (IPI 100, ICMS 198,
       PIS 14.88, COFINS 68.55)

     Aggregates:
       ICMS payable   = 180 + 360 + 198    = 738
       IPI  payable   = 100
       PIS  payable   = 13.53 + 27.06 + 8.25 + 14.88 = 63.72
       COFINS payable = 62.32 + 124.64 + 38 + 68.55  = 293.51
       ISS  payable   = 25"
  [conn]
  (inv/post-br-invoice!
   conn
   {:kontor.invoice/external-id "INV-1"
    :kontor.invoice/issue-date  jan-10
    :kontor.invoice/from-state  "SP" :kontor.invoice/to-state "SP"
    :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/tax-classification :goods}]})
  (inv/post-br-invoice!
   conn
   {:kontor.invoice/external-id "INV-2"
    :kontor.invoice/issue-date  jan-15
    :kontor.invoice/from-state  "SP" :kontor.invoice/to-state "SP"
    :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 2000M
                     :kontor.invoice-line/tax-classification :goods}]})
  (inv/post-br-invoice!
   conn
   {:kontor.invoice/external-id "INV-3"
    :kontor.invoice/issue-date  jan-20
    :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 500M
                     :kontor.invoice-line/tax-classification :services
                     :kontor.invoice-line/iss-rate 0.05M}]})
  (inv/post-br-invoice!
   conn
   {:kontor.invoice/external-id "INV-4"
    :kontor.invoice/issue-date  jan-25
    :kontor.invoice/from-state  "SP" :kontor.invoice/to-state "SP"
    :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/tax-classification :goods-manufactured
                     :kontor.invoice-line/ipi-rate 0.10M}]}))

;; ============================================================================
;; Period helpers
;; ============================================================================

(deftest period-bounds-monthly
  (testing "Jan 2026 monthly window"
    (let [{:keys [from to kind year month]} (pr/period-bounds {:year 2026 :month 1})]
      (is (= :monthly kind))
      (is (= 2026 year))
      (is (= 1 month))
      (is (= jan-1 from))
      (is (= feb-1 to))))
  (testing "Dec 2026 (year-boundary rollover) → Jan 1 2027"
    (let [{:keys [from to]} (pr/period-bounds {:year 2026 :month 12})]
      (is (= #inst "2026-12-01T00:00:00Z" from))
      (is (= #inst "2027-01-01T00:00:00Z" to)))))

(deftest period-bounds-quarterly
  (testing "Q1 2026 → Jan 1 to Apr 1"
    (let [{:keys [from to kind year quarter]}
          (pr/period-bounds {:year 2026 :quarter 1})]
      (is (= :quarterly kind))
      (is (= 2026 year))
      (is (= 1 quarter))
      (is (= jan-1 from))
      (is (= #inst "2026-04-01T00:00:00Z" to)))))

(deftest period-bounds-annual
  (testing "Annual 2026 → 2026 calendar year"
    (let [{:keys [from to kind year]} (pr/period-bounds {:year 2026})]
      (is (= :annual kind))
      (is (= 2026 year))
      (is (= jan-1 from))
      (is (= #inst "2027-01-01T00:00:00Z" to)))))

(deftest period-bounds-missing-args
  (testing "No :year → throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pr/period-bounds {})))))

;; ============================================================================
;; PIS + COFINS — EFD-Contribuições / DCTFWeb
;; ============================================================================

(deftest pis-cofins-monthly-aggregates
  (testing "January 2026 PIS + COFINS aggregation across 4 invoices.
              PIS payable   = 13.53 + 27.06 + 8.25 + 14.88 = 63.72
              COFINS payable = 62.32 + 124.64 + 38 + 68.55 = 293.51
              Form name matches EFD-Contribuições."
    (let [conn (bootstrap)
          _ (seed-january! conn)
          r (pr/generate-pis-cofins-return conn {:year 2026 :month 1})]
      (is (= "EFD-Contribuições" (:return/form r)))
      (is (≈ (brl "63.72")  (:return/pis-net r)))
      (is (≈ (brl "293.51") (:return/cofins-net r)))
      (is (≈ (brl "357.23") (:return/total-net r))
          "Total PIS + COFINS = 357.23 to remit")
      ;; Drill-down per line
      (is (≈ (brl "63.72") (-> r :return/lines :pis-output)))
      (is (≈ (brl "0") (-> r :return/lines :pis-input))
          "No input credits in this fixture (no purchase invoices)"))))

(deftest pis-cofins-empty-period
  (testing "No postings in the period → all zeros, total-net zero."
    (let [conn (bootstrap)
          r (pr/generate-pis-cofins-return conn {:year 2026 :month 1})]
      (is (≈ (brl "0") (:return/total-net r))))))

(deftest pis-cofins-explicit-window
  (testing "Explicit :from/:to overrides :year/:month shorthand."
    (let [conn (bootstrap)
          _ (seed-january! conn)
          r (pr/generate-pis-cofins-return conn
                                           {:from jan-1 :to jan-20})]
      ;; Only the Jan 10 + Jan 15 goods invoices (Jan 20 services is
      ;; at exactly the :to boundary, excluded by half-open window).
      ;; PIS = 13.53 + 27.06 = 40.59
      (is (≈ (brl "40.59") (:return/pis-net r))))))

;; ============================================================================
;; ICMS — EFD ICMS/IPI
;; ============================================================================

(deftest icms-monthly-aggregates
  (testing "January 2026 ICMS aggregation.
              ICMS payable = 180 (R$1000) + 360 (R$2000) + 198 (mfg R$1000+IPI)
                           = 738"
    (let [conn (bootstrap)
          _ (seed-january! conn)
          r (pr/generate-icms-return conn {:year 2026 :month 1})]
      (is (= "EFD ICMS/IPI" (:return/form r)))
      (is (≈ (brl "738") (:return/icms-net r))))))

(deftest icms-state-echoes-into-result
  (testing ":state opt echoes into the return-data so the consumer
              can route to the right GIA filing. Substrate does not
              yet split per-state postings (that's a kernel
              extension)."
    (let [conn (bootstrap)
          _ (seed-january! conn)
          r (pr/generate-icms-return conn
                                     {:year 2026 :month 1 :state "SP"})]
      (is (= "SP" (:return/state r))))))

;; ============================================================================
;; IPI — federal manufacturing tax
;; ============================================================================

(deftest ipi-monthly-aggregates
  (testing "January 2026 IPI aggregation.
              IPI payable = 100 (single manufactured-goods invoice)"
    (let [conn (bootstrap)
          _ (seed-january! conn)
          r (pr/generate-ipi-return conn {:year 2026 :month 1})]
      (is (= "EFD ICMS/IPI" (:return/form r)))
      (is (≈ (brl "100") (:return/ipi-net r))))))

;; ============================================================================
;; ISS — municipal service tax
;; ============================================================================

(deftest iss-monthly-aggregates
  (testing "January 2026 ISS aggregation.
              ISS payable = 25 (single services invoice R$500 @ 5%)"
    (let [conn (bootstrap)
          _ (seed-january! conn)
          r (pr/generate-iss-return conn {:year 2026 :month 1})]
      (is (= "ISS" (:return/form r)))
      (is (≈ (brl "25") (:return/iss-total r)))
      (is (map? (:return/by-municipality r))
          ":by-municipality is a map (empty at substrate tier)"))))

;; ============================================================================
;; DCTFWeb — federal consolidation
;; ============================================================================

(deftest dctf-web-aggregates-federal-components
  (testing "DCTFWeb consolidates PIS + COFINS + IPI for the period.
              PIS+COFINS net = 357.23; IPI net = 100; federal-total = 457.23."
    (let [conn (bootstrap)
          _ (seed-january! conn)
          r (pr/generate-dctf-web conn {:year 2026 :month 1})]
      (is (= "DCTFWeb" (:return/form r)))
      (is (≈ (brl "457.23") (:return/federal-total r)))
      (is (≈ (brl "357.23")
             (-> r :return/components :pis-cofins :return/total-net)))
      (is (≈ (brl "100")
             (-> r :return/components :ipi :return/ipi-net))))))

;; ============================================================================
;; Period scoping — invoices outside the window are excluded
;; ============================================================================

(deftest period-window-excludes-other-months
  (testing "An invoice in Feb 2026 should NOT contribute to a Jan
              aggregation."
    (let [conn (bootstrap)
          _ (seed-january! conn)
          _ (inv/post-br-invoice!
             conn
             {:kontor.invoice/external-id "INV-FEB-1"
              :kontor.invoice/issue-date  feb-15
              :kontor.invoice/from-state  "SP" :kontor.invoice/to-state "SP"
              :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                               :kontor.invoice-line/unit-price 10000M
                               :kontor.invoice-line/tax-classification :goods}]})
          jan (pr/generate-icms-return conn {:year 2026 :month 1})
          feb (pr/generate-icms-return conn {:year 2026 :month 2})]
      (is (≈ (brl "738")  (:return/icms-net jan))
          "Jan unaffected by Feb activity")
      (is (≈ (brl "1800") (:return/icms-net feb))
          "Feb captures the new R$10,000 goods invoice (ICMS 18%)"))))
