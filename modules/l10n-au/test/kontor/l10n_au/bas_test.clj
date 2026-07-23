(ns kontor.l10n-au.bas-test
  "Tests for kontor.l10n-au.bas — the cadence-aware BAS aggregator
   on top of kontor.l10n-au.gst/compute-return."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-au.bas :as bas]
            [kontor.l10n-au.chart :as chart]
            [kontor.l10n-au.invoice :as inv]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(defn- aud [s] (money/money (bigdec s) :AUD))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:kontor.journal/code "SJ" :kontor.journal/name "Sales"
                       :kontor.journal/type :sale :kontor.journal/active true}])
    conn))

;; ============================================================================
;; fy-period-bounds — AU financial year (1 July – 30 June)
;; ============================================================================

(deftest fy-quarter-bounds
  (testing "AU-FY Q1 = Jul-Sep of (fy-1)"
    (let [{:keys [from to kind fy quarter]} (bas/fy-period-bounds {:fy 2026 :quarter 1})]
      (is (= :quarterly kind))
      (is (= 2026 fy))
      (is (= 1 quarter))
      (is (= #inst "2025-07-01T00:00:00Z" from)
          "FY26 Q1 starts 1 July 2025")
      (is (= #inst "2025-10-01T00:00:00Z" to)
          "FY26 Q1 ends 1 October 2025 (exclusive)")))
  (testing "AU-FY Q3 = Jan-Mar of fy"
    (let [{:keys [from to]} (bas/fy-period-bounds {:fy 2026 :quarter 3})]
      (is (= #inst "2026-01-01T00:00:00Z" from))
      (is (= #inst "2026-04-01T00:00:00Z" to))))
  (testing "AU-FY Q4 = Apr-Jun of fy"
    (let [{:keys [from to]} (bas/fy-period-bounds {:fy 2026 :quarter 4})]
      (is (= #inst "2026-04-01T00:00:00Z" from))
      (is (= #inst "2026-07-01T00:00:00Z" to)))))

(deftest fy-monthly-bounds
  (testing "FY-month 1 = July of (fy-1)"
    (let [{:keys [from to kind month]} (bas/fy-period-bounds {:fy 2026 :month 1})]
      (is (= :monthly kind))
      (is (= 1 month))
      (is (= #inst "2025-07-01T00:00:00Z" from))
      (is (= #inst "2025-08-01T00:00:00Z" to))))
  (testing "FY-month 12 = June of fy"
    (let [{:keys [from to]} (bas/fy-period-bounds {:fy 2026 :month 12})]
      (is (= #inst "2026-06-01T00:00:00Z" from))
      (is (= #inst "2026-07-01T00:00:00Z" to)))))

(deftest fy-annual-bounds
  (testing "Annual FY spans 1 July (fy-1) → 1 July fy"
    (let [{:keys [from to kind fy]} (bas/fy-period-bounds {:fy 2026})]
      (is (= :annual kind))
      (is (= 2026 fy))
      (is (= #inst "2025-07-01T00:00:00Z" from))
      (is (= #inst "2026-07-01T00:00:00Z" to)))))

(deftest explicit-bounds-pass-through
  (let [from #inst "2026-01-01T00:00:00Z"
        to   #inst "2026-04-01T00:00:00Z"
        r (bas/fy-period-bounds {:from from :to to})]
    (is (= from (:from r)))
    (is (= to   (:to r)))
    (is (= :explicit (:kind r)))))

(deftest fy-period-bounds-rejects-empty-opts
  (is (thrown? clojure.lang.ExceptionInfo
               (bas/fy-period-bounds {}))))

;; ============================================================================
;; compute-bas — end-to-end via invoice posting
;; ============================================================================

(deftest empty-period-is-nil-return
  (testing "No postings in period → all labels zero, outcome :nil-return"
    (let [conn (bootstrap)
          r (bas/compute-bas conn {:fy 2026 :quarter 3})]
      (is (= "BAS" (:bas/form r)))
      (is (= :nil-return (:bas/outcome r)))
      (is (money/equiv? (aud "0.00") (:bas/net r)))
      (is (every? #(money/equiv? (aud "0.00") %) (vals (:bas/labels r)))))))

(deftest quarterly-bas-aggregates-gst-on-sales
  (testing "One A$10000 taxable invoice in FY26 Q3 → G1=10000 (net
              revenue per the chart's G1 tag — see note below),
              1A=1000, net=1000 (payable to ATO).

              Note: the ATO defines G1 as 'total sales incl. GST'. The
              shipped l10n-au chart tags revenue accounts with G1 but
              NOT the GST-payable account; consumers who lodge BAS
              literally need to add 1A back to G1 (or extend the chart
              to also tag 21500 with :au-bas-g1-total-sales). This is
              consistent with the existing gst-test fixture and is
              documented behaviour, not a regression."
    (let [conn (bootstrap)
          ;; Post in FY26 Q3 (Jan-Mar 2026).
          _ (inv/post-au-invoice! conn
                                  {:kontor.invoice/external-id "INV-Q3-1"
                                   :kontor.invoice/issue-date #inst "2026-02-15T00:00:00Z"
                                   :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                                    :kontor.invoice-line/unit-price 10000M}]})
          r (bas/compute-bas conn {:fy 2026 :quarter 3})]
      (is (= :quarterly (:bas/cadence r)))
      (is (money/equiv? (aud "10000.00") (get-in r [:bas/labels :G1]))
          "G1 picks up the revenue account tagged :au-bas-g1-total-sales")
      (is (money/equiv? (aud "1000.00")  (get-in r [:bas/labels :1A]))
          "1A = GST on sales")
      (is (money/equiv? (aud "1000.00")  (:bas/net r)))
      (is (= :payment (:bas/outcome r))))))

(deftest q4-bas-excludes-q3-activity
  (testing "Posting in FY26 Q3 does NOT show up in FY26 Q4 (period
              isolation)."
    (let [conn (bootstrap)
          _ (inv/post-au-invoice! conn
                                  {:kontor.invoice/external-id "INV-Q3-EX"
                                   :kontor.invoice/issue-date #inst "2026-02-15T00:00:00Z"
                                   :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                                    :kontor.invoice-line/unit-price 5000M}]})
          r (bas/compute-bas conn {:fy 2026 :quarter 4})]
      (is (money/equiv? (aud "0.00") (get-in r [:bas/labels :1A]))
          "FY26 Q4 = Apr-Jun 2026, sale on Feb 15 not included")
      (is (= :nil-return (:bas/outcome r))))))

;; ============================================================================
;; Simpler vs Full BAS mode
;; ============================================================================

(deftest simpler-bas-publishes-only-g1-1a-1b
  (testing "Simpler-BAS mode publishes G1, 1A, 1B and drops the rest"
    (let [conn (bootstrap)
          _ (inv/post-au-invoice! conn
                                  {:kontor.invoice/external-id "INV-SIMPLER"
                                   :kontor.invoice/issue-date #inst "2026-02-15T00:00:00Z"
                                   :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                                    :kontor.invoice-line/unit-price 100M}]})
          r (bas/compute-bas conn {:fy 2026 :quarter 3
                                   :bas/mode :simpler})]
      (is (= :simpler (:bas/mode r)))
      (is (= #{:G1 :1A :1B} (set (keys (:bas/labels r))))
          "Simpler BAS only ships these three labels"))))

(deftest full-bas-publishes-all-labels
  (testing "Full BAS publishes every documented label"
    (let [conn (bootstrap)
          r (bas/compute-bas conn {:fy 2026 :quarter 3 :bas/mode :full})]
      (is (= :full (:bas/mode r)))
      (is (every? (set (keys (:bas/labels r)))
                  [:G1 :G2 :G3 :G4 :G10 :G11 :1A :1B :W1 :W2])))))

;; ============================================================================
;; Monthly cadence
;; ============================================================================

(deftest monthly-cadence-supported
  (testing "Monthly BAS — typical for AUD 20M+ businesses"
    (let [conn (bootstrap)
          ;; Post in FY26 month 8 (Feb 2026).
          _ (inv/post-au-invoice! conn
                                  {:kontor.invoice/external-id "INV-MON-1"
                                   :kontor.invoice/issue-date #inst "2026-02-15T00:00:00Z"
                                   :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                                    :kontor.invoice-line/unit-price 1000M}]})
          r (bas/compute-bas conn {:fy 2026 :month 8})]
      (is (= :monthly (:bas/cadence r)))
      (is (money/equiv? (aud "100.00") (get-in r [:bas/labels :1A]))))))

;; ============================================================================
;; Refund outcome — GST on purchases > GST on sales
;; ============================================================================

(deftest refund-outcome-when-itc-exceeds-output
  (testing "When ITCs (1B) exceed output GST (1A), outcome is :refund.

              Posts a 1B-tagged purchase via posting/build-transaction
              (so the kernel attaches the bitemporal :db.valid/from
              the report engine reads): Dr GSTReceivable 100,
              Cr Bank 100. Stand-in for 'we paid GST on a purchase and
              can claim the ITC'."
    (let [conn (bootstrap)
          db (d/db conn)
          aud-eid (:db/id (d/entity db [:kontor.commodity/symbol "AUD"]))
          gst-recv (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db "11700")
          bank     (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db "11100")
          jnl (:db/id (d/entity db [:kontor.journal/code "SJ"]))
          date #inst "2026-02-15T00:00:00Z"
          tx-data (posting/build-transaction
                   {:transaction
                    {:kontor.transaction/external-id "PURCH-1"
                     :kontor.transaction/journal jnl
                     :kontor.transaction/effective-date date
                     :kontor.transaction/narration "Capital purchase ITC"
                     :kontor.transaction/state :posted
                     :kontor.transaction/posted-at date}
                    :postings
                    [{:kontor.posting/account gst-recv
                      :kontor.posting/amount 100M
                      :kontor.posting/commodity aud-eid
                      :kontor.posting/posted-at date}
                     {:kontor.posting/account bank
                      :kontor.posting/amount -100M
                      :kontor.posting/commodity aud-eid
                      :kontor.posting/posted-at date}]})
          _ (v/transact-with-validation conn tx-data)
          r (bas/compute-bas conn {:fy 2026 :quarter 3})]
      (is (money/equiv? (aud "100.00") (get-in r [:bas/labels :1B]))
          "1B picks up the ITC posting via the chart tag")
      (is (= :refund (:bas/outcome r))
          "1A=0, 1B=100 → net=-100 → refund"))))
