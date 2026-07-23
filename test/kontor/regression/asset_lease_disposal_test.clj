(ns kontor.regression.asset-lease-disposal-test
  "Regression suite — fixed-asset + lease + disposal lifecycle.

   Locks in the intended behaviour of the three companions with
   realistic, hand-computed scenarios:

   - ASSET: acquire → open a depreciation book → run-depreciation!
     across periods; assert accumulated-depreciation, net-book-value,
     that the GL postings balance per ledger, and salvage-value +
     declining-balance mechanics.
   - LEASE: present-value of an annuity (in-arrears vs in-advance, with
     a purchase option), the effective-interest unwind split
     (interest + principal, balance → 0), and a balanced day-one
     recognition entry.
   - DISPOSAL: record + recognize a disposal of a depreciated asset and
     assert the realised gain/loss (proceeds − basis − rollover), plus
     the by-bucket summary.

   Expected figures are hand-computed from the depreciation / annuity /
   effective-interest formulas and cross-checked against the numbers
   already asserted in the modules' own tests (see comments per test).

   Bugs discovered here that are NOT the note-196 F1..F8 issues are
   tagged ^:kaocha/pending PENDING(NEW)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.asset.asset :as asset]
            [kontor.asset.depreciation :as dep]
            [kontor.asset.depreciation-provider :as dp]
            [kontor.asset.runner :as arun]
            [kontor.asset.schema :as asset-schema]
            [kontor.core :as core]
            [kontor.disposal :as disp]
            [kontor.lease.core :as lease]
            [kontor.lease.lease-provider :as lp]
            [kontor.lease.liability :as liability]
            [kontor.lease.runner :as lrun]
            [kontor.lease.schema :as lease-schema]))

;; ============================================================================
;; Fixture — all three companions on one connection
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (asset-schema/install! conn)
    (lease-schema/install! conn)
    (disp/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR"
                  :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 ;; Actors (partner entities as actor stand-ins — kernel convention).
                 {:kontor.partner/external-id "U-buyer"   :kontor.partner/name "Asset Buyer"}
                 {:kontor.partner/external-id "U-manager" :kontor.partner/name "Asset Manager"}
                 {:kontor.partner/external-id "U-cfo"     :kontor.partner/name "CFO"}
                 {:kontor.partner/external-id "L-acme"    :kontor.partner/name "Acme Properties"}
                 ;; Legal entity for disposals (needs a functional commodity).
                 {:db/id "entity-co"
                  :kontor.entity/code "ACME" :kontor.entity/name "Acme GmbH"
                  :kontor.entity/kind :company :kontor.entity/country "DE"
                  :kontor.entity/functional-commodity [:kontor.commodity/symbol "EUR"]}
                 ;; GL accounts.
                 {:kontor.account/code "0210" :kontor.account/name "Machinery"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:kontor.account/code "0299" :kontor.account/name "Accumulated Depreciation"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:kontor.account/code "6220" :kontor.account/name "Depreciation Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "0250" :kontor.account/name "ROU Asset"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:kontor.account/code "0259" :kontor.account/name "ROU Accumulated Amortisation"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:kontor.account/code "1750" :kontor.account/name "Lease Liability"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:kontor.account/code "7300" :kontor.account/name "Interest Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "6200" :kontor.account/name "ROU Depreciation"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "1800" :kontor.account/name "Bank"
                  :kontor.account/type :asset :kontor.account/active true}
                 ;; Ledger + journal.
                 {:kontor.ledger/code "hgb" :kontor.ledger/name "Handelsbilanz"
                  :kontor.ledger/type :primary :kontor.ledger/framework :HGB
                  :kontor.ledger/active true}
                 {:kontor.ledger/code "ifrs" :kontor.ledger/name "IFRS 16"
                  :kontor.ledger/framework :ifrs}
                 {:kontor.journal/code "GEN" :kontor.journal/name "General"
                  :kontor.journal/type :general}
                 ;; Asset class + ROU class.
                 {:kontor.asset-class/code "machinery"
                  :kontor.asset-class/name "Machinery & Equipment"
                  :kontor.asset-class/default-useful-life-months 120}
                 {:kontor.asset-class/code "rou-property"
                  :kontor.asset-class/name "Right-of-Use — Property"}
                 ;; Supporting docs.
                 {:kontor.audit-doc/code "ASSET-INV-001"
                  :kontor.audit-doc/type :acquisition-invoice
                  :kontor.audit-doc/storage-uri "s3://docs/asset-inv-001"
                  :kontor.audit-doc/uploaded-at #inst "2026-01-15"}
                 {:kontor.audit-doc/code "LEASE-CONTRACT-1"
                  :kontor.audit-doc/type :lease-contract
                  :kontor.audit-doc/storage-uri "s3://docs/lease-1"
                  :kontor.audit-doc/uploaded-at #inst "2026-01-01"}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- commodity [db]       (ref-eid db :kontor.commodity/symbol "EUR"))
(defn- p         [db code]  (ref-eid db :kontor.partner/external-id code))
(defn- acct      [db code]  (ref-eid db :kontor.account/code code))
(defn- hgb       [db]       (ref-eid db :kontor.ledger/code "hgb"))
(defn- ifrs      [db]       (ref-eid db :kontor.ledger/code "ifrs"))
(defn- journal   [db]       (ref-eid db :kontor.journal/code "GEN"))
(defn- class-eid [db code]  (ref-eid db :kontor.asset-class/code code))
(defn- adoc      [db code]  (ref-eid db :kontor.audit-doc/code code))

(def ^:private far-future #inst "2060-01-01")
(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private acme [:kontor.entity/code "ACME"])

(defn- acquire-machine!
  "Acquire an in-service machinery asset; return its eid."
  ([conn code cost] (acquire-machine! conn code cost 0M))
  ([conn code cost salvage]
   (let [db (d/db conn)]
     (asset/acquire! conn
                     {:code code
                      :name (str "Machine " code)
                      :class (class-eid db "machinery")
                      :acquisition-cost cost
                      :acquisition-commodity (commodity db)
                      :acquisition-date #inst "2026-01-15"
                      :in-service? true
                      :salvage-value salvage
                      :asset-account (acct db "0210")
                      :accumulated-account (acct db "0299")
                      :expense-account (acct db "6220")
                      :origin-document (adoc db "ASSET-INV-001")
                      :changed-by-uid (p db "U-buyer")})
     (asset/by-code (d/db conn) code))))

(defn- ledger-balance
  "Σ :kontor.posting/amount for an account on a ledger over :posted txs."
  [db account ledger-eid]
  (or (d/q '[:find (sum ?amt) .
             :with ?p
             :in $ ?acct ?led
             :where
             [?p :kontor.posting/account ?acct]
             [?p :kontor.posting/ledger ?led]
             [?p :kontor.posting/amount ?amt]
             [?p :kontor.posting/transaction ?tx]
             [?tx :kontor.transaction/state :posted]]
           db account ledger-eid)
      0M))

;; ============================================================================
;; ASSET — straight-line depreciation over periods
;; ============================================================================

(deftest straight-line-24-months-accumulated-and-nbv
  ;; €120,000 machine, 120-month life, no salvage → €1,000/month.
  ;; After 24 monthly charges: accumulated 24,000; NBV 96,000.
  (let [conn (bootstrap)
        _ (acquire-machine! conn "SL-24" 120000.00M)
        _ (dep/open-book! conn {:asset "SL-24" :ledger (hgb (d/db conn))
                                :provider-id :straight-line
                                :useful-life-months 120})
        book (dep/book-for (d/db conn) "SL-24" (hgb (d/db conn)))
        ;; run the first 24 occurrences (2026-01-15 … 2027-12-15).
        result (arun/run-depreciation! conn book
                                       {:journal (journal (d/db conn))
                                        :as-of #inst "2028-01-01"
                                        :changed-by-uid (p (d/db conn) "U-buyer")})
        db' (d/db conn)]
    (testing "24 monthly charges fire at €1,000"
      (is (= 24 (:count result)))
      (is (= 24000.00M (:total result)))
      (is (not (:completed? result)) "the 120-month asset is not yet fully depreciated"))
    (testing "accumulated depreciation + net book value"
      (is (= 24000.00M (dep/accumulated-depreciation db' book)))
      (is (= 96000.00M (dep/net-book-value db' book))
          "NBV = cost 120,000 − accumulated 24,000"))
    (testing "the depreciation postings balance on the HGB ledger"
      (let [hgb-eid (hgb db')]
        (is (= 24000.00M (ledger-balance db' (acct db' "6220") hgb-eid))
            "expense debited 24 × 1,000")
        (is (= -24000.00M (ledger-balance db' (acct db' "0299") hgb-eid))
            "accumulated credited 24 × 1,000")
        (is (zero? (.signum (.add (ledger-balance db' (acct db' "6220") hgb-eid)
                                  (ledger-balance db' (acct db' "0299") hgb-eid))))
            "expense + contra-asset = 0 — the entry is balanced")))))

(deftest straight-line-salvage-value-floors-nbv-at-salvage
  ;; €100,000 cost, €10,000 salvage, 60-month life.
  ;; Depreciable base = 90,000 → €1,500/month; full run leaves NBV = salvage.
  (let [conn (bootstrap)
        _ (acquire-machine! conn "SL-SALV" 100000.00M 10000.00M)
        _ (dep/open-book! conn {:asset "SL-SALV" :ledger (hgb (d/db conn))
                                :provider-id :straight-line
                                :useful-life-months 60})
        book (dep/book-for (d/db conn) "SL-SALV" (hgb (d/db conn)))
        plan (dp/plan-schedule (dp/provider-for :straight-line)
                               {:db (d/db conn) :book book})]
    (testing "the depreciable base excludes salvage"
      (is (= 90000.00M (:total plan)) "Σ = cost − salvage = 90,000")
      (is (= 60 (count (:periods plan))))
      (is (= #{1500.00M} (set (map :amount (:periods plan))))
          "90,000 / 60 = €1,500 equal periods"))
    (testing "a full run leaves NBV at the salvage value, not zero"
      (let [result (arun/run-depreciation! conn book
                                           {:journal (journal (d/db conn))
                                            :as-of far-future
                                            :changed-by-uid (p (d/db conn) "U-buyer")})
            db' (d/db conn)]
        (is (:completed? result))
        (is (= 90000.00M (dep/accumulated-depreciation db' book)))
        (is (= 10000.00M (dep/net-book-value db' book))
            "NBV = 100,000 − 90,000 = 10,000 salvage floor")
        (is (= :fully-depreciated
               (:kontor.asset/status (asset/pull-asset db' "SL-SALV"))))))))

(deftest declining-balance-200pct-declines-and-sums-to-base
  ;; €60,000, 60-month life, 200% declining balance.
  ;; Σ = base; amounts strictly decline; every charge is positive.
  (let [conn (bootstrap)
        _ (acquire-machine! conn "DB-200" 60000.00M)
        _ (dep/open-book! conn {:asset "DB-200" :ledger (hgb (d/db conn))
                                :provider-id :declining-balance
                                :useful-life-months 60
                                :method-params {:kontor.asset-method-params/rate-multiple 2M}})
        book (dep/book-for (d/db conn) "DB-200" (hgb (d/db conn)))
        plan (dp/plan-schedule (dp/provider-for :declining-balance)
                               {:db (d/db conn) :book book})
        amts (mapv :amount (:periods plan))]
    (testing "the schedule spans the useful life and sums to base"
      (is (= 60 (count amts)))
      (is (= 60000.00M (:total plan)) "Σ = depreciable-base exactly"))
    (testing "declining-balance is accelerated"
      (is (> (first amts) (nth amts 1)) "period 1 > period 2")
      (is (> (nth amts 1) (nth amts 12)) "still declining a year in")
      (is (every? #(>= (.signum ^java.math.BigDecimal %) 0) amts)
          "no negative charge"))
    (testing "the final period drives book value to salvage (0)"
      (is (= 0M (:basis-remaining (last (:periods plan))))))))

;; ============================================================================
;; LEASE — present value + effective-interest unwind
;; ============================================================================

(deftest present-value-annuity-and-purchase-option
  ;; Hand figures cross-checked against modules/lease runner_test:
  ;;   ordinary annuity  1000 × 3 @ 1%/period, in-arrears = 2940.99
  ;;   annuity-due (in-advance)                            = 2970.40
  (testing "ordinary annuity (payment in arrears)"
    (is (= 2940.99M (lease/present-value 1000M 0.01M 3 :in-arrears))))
  (testing "annuity-due is worth more (each payment discounted one period less)"
    (is (= 2970.40M (lease/present-value 1000M 0.01M 3 :in-advance)))
    (is (pos? (.compareTo (lease/present-value 1000M 0.01M 3 :in-advance)
                          (lease/present-value 1000M 0.01M 3 :in-arrears)))))
  (testing "a reasonably-certain purchase option lifts the PV"
    (is (pos? (.compareTo
               (lease/present-value 1000M 0.01M 3 :in-arrears {:final-value 5000M})
               (lease/present-value 1000M 0.01M 3 :in-arrears)))))
  (testing "36 monthly payments of 1,000 at 6%/yr (0.5%/period) in-arrears = 32,871.02"
    ;; This is the opening liability commence! records (runner_test asserts it).
    (is (= 32871.02M (lease/present-value 1000M 0.005M 36 :in-arrears)))))

(deftest finance-lease-effective-interest-unwind
  ;; 36-month lease, €1,000/month in-arrears, 6%/yr. PV = 32,871.02.
  ;; Period-1 interest = 32,871.02 × 0.5% = 164.36; principal = 835.64.
  ;; The balance unwinds exactly to 0 and Σ principal = the PV.
  (let [conn (bootstrap)
        db   (d/db conn)
        _ (lease/define-lease! conn
            {:code "LSE-FIN" :name "Berlin office" :lessor (p db "L-acme")
             :asset-class (class-eid db "rou-property")
             :commencement-date #inst "2026-01-01"
             :term-months 36 :payment-amount 1000.00M :payment-frequency :monthly
             :payment-timing :in-arrears :commodity (commodity db)
             :discount-rate 0.06M :origin-document (adoc db "LEASE-CONTRACT-1")
             :changed-by-uid (p db "U-cfo")})
        _ (lrun/commence! conn
                          {:lease "LSE-FIN" :journal (journal db) :changed-by-uid (p db "U-cfo")
                           :rou-asset-account (acct db "0250")
                           :rou-accumulated-account (acct db "0259")
                           :books [{:ledger (ifrs db) :classification :finance
                                    :liability-account (acct db "1750")
                                    :interest-account (acct db "7300")
                                    :rou-expense-account (acct db "6200")}]})
        db' (d/db conn)
        book (liability/book-for db' "LSE-FIN" (ifrs db'))
        plan (lp/plan-for-book db' book)
        periods (:periods plan)]
    (testing "the opening liability is the PV of the payments"
      (is (= 32871.02M (:kontor.lease-liability/opening-liability
                        (liability/pull-book db' book)))))
    (testing "the plan covers all 36 payment periods"
      (is (= 36 (count periods))))
    (testing "period 1 splits into interest + principal"
      (let [p1 (first periods)]
        (is (= 1000.00M (:payment p1)))
        (is (= 164.36M (:interest p1)) "32,871.02 × 0.5% ≈ 164.36")
        (is (= 835.64M (:principal p1)))
        (is (= 1000.00M (.add (:interest p1) (:principal p1))))))
    (testing "the balance unwinds exactly to zero"
      (is (= 0.00M (:balance-remaining (last periods)))))
    (testing "Σ principal reconciles to the opening liability"
      (is (= 32871.02M (reduce (fn [a x] (.add a (:principal x))) 0M periods))))
    (testing "the day-one recognition entry is balanced on the IFRS ledger"
      (is (= 32871.02M (ledger-balance db' (acct db' "0250") (ifrs db')))
          "ROU asset debited with the PV")
      (is (= -32871.02M (ledger-balance db' (acct db' "1750") (ifrs db')))
          "lease liability credited with the PV"))))

;; ============================================================================
;; DISPOSAL — realised gain/loss on a depreciated asset
;; ============================================================================

(deftest dispose-depreciated-asset-books-the-gain
  ;; Acquire €120,000; depreciate 24 months (NBV 96,000). Sell for
  ;; €100,000 → realised gain = proceeds 100,000 − basis (NBV) 96,000.
  (let [conn (bootstrap)
        _ (acquire-machine! conn "DISP-1" 120000.00M)
        _ (dep/open-book! conn {:asset "DISP-1" :ledger (hgb (d/db conn))
                                :provider-id :straight-line
                                :useful-life-months 120})
        book (dep/book-for (d/db conn) "DISP-1" (hgb (d/db conn)))
        _ (arun/run-depreciation! conn book
                                  {:journal (journal (d/db conn))
                                   :as-of #inst "2028-01-01"
                                   :changed-by-uid (p (d/db conn) "U-buyer")})
        nbv (dep/net-book-value (d/db conn) book)]
    (testing "NBV after 24 months is 96,000"
      (is (= 96000.00M nbv)))
    (let [asset-eid (asset/by-code (d/db conn) "DISP-1")]
      (disp/record-disposal!
       conn {:entity acme
             :external-id "DISP-1-sale"
             :kind :sale
             :subject asset-eid
             :subject-kind :fixed-asset
             :acquired-on #inst "2026-01-15"
             :disposed-on #inst "2028-01-20"
             :proceeds {:amount 100000.00M :commodity eur}
             :basis    {:amount nbv :commodity eur}
             :recorded-by-uid "U-manager"})
      (let [dm (disp/pull-disposal (d/db conn) "DISP-1-sale")]
        (testing "the disposal records at :recorded with a €4,000 realised gain"
          (is (= :recorded (:kontor.disposal/state dm)))
          (is (== 4000.00M (disp/realized-gain dm))
              "100,000 proceeds − 96,000 carrying value"))
        (testing "recognize! advances the disposal to :recognized"
          (disp/recognize! conn {:disposal "DISP-1-sale"
                                 :transaction asset-eid ; stand-in realizing tx
                                 :recorded-by-uid "U-manager"})
          (let [dm' (disp/pull-disposal (d/db conn) "DISP-1-sale")]
            (is (= :recognized (:kontor.disposal/state dm')))
            (is (some? (:kontor.disposal/realizing-tx dm')))))))))

(deftest disposal-loss-rollover-and-bucket-summary
  ;; Three disposals in 2028:
  ;;   sale at a loss:      proceeds 50,000 − basis 90,000        = −40,000
  ;;   sale with rollover:  proceeds 100,000 − basis 60,000 − 30,000 defer = 10,000
  ;;   plain gain:          proceeds 20,000 − basis 12,000        = 8,000
  ;; Summary groups by :loss-bucket.
  (let [conn (bootstrap)]
    (disp/record-disposal!
     conn {:entity acme :external-id "d-loss" :kind :sale
           :subject eur :subject-kind :fixed-asset
           :acquired-on #inst "2024-01-01" :disposed-on #inst "2028-03-15"
           :proceeds {:amount 50000M :commodity eur}
           :basis    {:amount 90000M :commodity eur}
           :loss-bucket :ordinary
           :recorded-by-uid "U-manager"})
    (disp/record-disposal!
     conn {:entity acme :external-id "d-rollover" :kind :sale
           :subject eur :subject-kind :fixed-asset
           :acquired-on #inst "2024-01-01" :disposed-on #inst "2028-06-15"
           :proceeds {:amount 100000M :commodity eur}
           :basis    {:amount 60000M :commodity eur}
           :rollover {:into-asset eur :amount 30000M :commodity eur
                      :deadline #inst "2029-06-15"}
           :loss-bucket :capital
           :recorded-by-uid "U-manager"})
    (disp/record-disposal!
     conn {:entity acme :external-id "d-gain" :kind :sale
           :subject eur :subject-kind :fixed-asset
           :acquired-on #inst "2024-01-01" :disposed-on #inst "2028-09-15"
           :proceeds {:amount 20000M :commodity eur}
           :basis    {:amount 12000M :commodity eur}
           :loss-bucket :capital
           :recorded-by-uid "U-manager"})
    (testing "single realised figures"
      (is (== -40000M (disp/realized-gain (disp/pull-disposal (d/db conn) "d-loss"))))
      (is (== 10000M (disp/realized-gain (disp/pull-disposal (d/db conn) "d-rollover")))
          "40,000 gross gain − 30,000 rolled into the replacement asset")
      (is (== 8000M (disp/realized-gain (disp/pull-disposal (d/db conn) "d-gain")))))
    (testing "the by-bucket summary nets each loss-bucket"
      (let [summary (disp/realized-gain-summary (d/db conn)
                                                {:from #inst "2028-01-01"
                                                 :to   #inst "2029-01-01"})]
        (is (== -40000M (:ordinary summary)))
        (is (== 18000M (:capital summary)) "10,000 rollover + 8,000 gain")))
    (testing "a voided disposal drops out of the period summary"
      (disp/void! conn {:disposal "d-gain" :recorded-by-uid "U-manager"})
      (let [summary (disp/realized-gain-summary (d/db conn)
                                                {:from #inst "2028-01-01"
                                                 :to   #inst "2029-01-01"})]
        (is (== 10000M (:capital summary)) "only the rollover disposal remains")))))
