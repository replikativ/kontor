(ns kontor.lease.runner-test
  "ADR-063: kontor-lease balance-sheet recognition + the period runner.

   Covers:
   - present-value — the annuity PV; in-advance > in-arrears; a
     reasonably-certain purchase option lifts it.
   - EffectiveInterestProvider — the unwind splits each payment into
     interest + principal, the balance lands exactly on zero, and an
     :in-advance period 1 (the payment made AT commencement) carries
     no interest.
   - commence! — a :draft lease becomes :active: the single ROU
     :asset, one :lease-liability book + one ROU :asset-depreciation
     book per ledger, a balanced day-one recognition entry. The
     not-:draft guard fires on a second commence!.
   - run-lease! — a FINANCE lease unwinds the liability and
     straight-lines the ROU asset; the GL balances; re-running is
     idempotent.
   - an OPERATING lease (ASC 842) recognises ONE straight-line lease-
     expense line — the interest leg and the ROU plug both land in the
     single lease-expense account — and at end of term the liability
     and the ROU asset are both fully unwound; the lease is :expired.
   - multi-book — the SAME lease classified :finance on the IFRS
     ledger and :operating on the US-GAAP ledger: each ledger balances
     independently, the one ROU :asset carries two depreciation books."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.asset.depreciation :as asset-dep]
            [kontor.asset.schema :as asset-schema]
            [kontor.core :as core]
            [kontor.lease.core :as lease]
            [kontor.lease.lease-provider :as lp]
            [kontor.lease.liability :as liability]
            [kontor.lease.report :as lreport]
            [kontor.lease.runner :as lrun]
            [kontor.lease.schema :as lease-schema]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (asset-schema/install! conn)
    (lease-schema/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:kontor.partner/external-id "U-cfo"  :kontor.partner/name "CFO"}
                 {:kontor.partner/external-id "L-acme" :kontor.partner/name "Acme Properties"}
                 {:db/id "led-ifrs"   :kontor.ledger/code "ifrs"    :kontor.ledger/name "IFRS 16"
                  :kontor.ledger/framework :ifrs}
                 {:db/id "led-usgaap" :kontor.ledger/code "us-gaap" :kontor.ledger/name "ASC 842"
                  :kontor.ledger/framework :us-gaap}
                 {:db/id "class-rou" :kontor.asset-class/code "rou-property"
                  :kontor.asset-class/name "Right-of-Use — Property"}
                 {:db/id "doc-lease" :kontor.audit-doc/code "LEASE-CONTRACT-1"
                  :kontor.audit-doc/type :lease-contract
                  :kontor.audit-doc/storage-uri "s3://docs/lease-1"
                  :kontor.audit-doc/uploaded-at #inst "2026-01-01"}
                 {:db/id "a-rou"    :kontor.account/code "0250" :kontor.account/name "ROU Asset"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "a-rouacc" :kontor.account/code "0259"
                  :kontor.account/name "ROU Accumulated Amortisation"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "a-liab"   :kontor.account/code "1750"
                  :kontor.account/name "Lease Liability"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:db/id "a-int"    :kontor.account/code "7300"
                  :kontor.account/name "Interest Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "a-dep"    :kontor.account/code "6200"
                  :kontor.account/name "Depreciation Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "a-lexp"   :kontor.account/code "6740"
                  :kontor.account/name "Lease Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "a-cash"   :kontor.account/code "1800" :kontor.account/name "Bank"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "j-gen" :kontor.journal/code "GEN" :kontor.journal/name "General"
                  :kontor.journal/type :general}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- commodity  [db] (ref-eid db :kontor.commodity/symbol "EUR"))
(defn- p          [db code] (ref-eid db :kontor.partner/external-id code))
(defn- acct       [db code] (ref-eid db :kontor.account/code code))
(defn- journal    [db] (ref-eid db :kontor.journal/code "GEN"))
(defn- class-eid  [db] (ref-eid db :kontor.asset-class/code "rou-property"))
(defn- adoc       [db] (ref-eid db :kontor.audit-doc/code "LEASE-CONTRACT-1"))
(defn- ledger     [db code] (ref-eid db :kontor.ledger/code code))

(defn- bd-cmp
  "Scale-insensitive BigDecimal comparison — 0.00M and 0M are the same
   money; `=` on BigDecimal is not."
  ^long [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (.compareTo a b))

(defn- ledger-balance
  "Sum of `:kontor.posting/amount` for `account` on `ledger` over :posted
   transactions — the per-ledger balance kontor.reporting.balance does not (yet)
   filter by."
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
;; present-value
;; ============================================================================

(deftest present-value-annuity
  (testing "ordinary annuity — 3 payments of 1000 at 1%/period"
    (is (= 2940.99M (lease/present-value 1000M 0.01M 3 :in-arrears))))
  (testing "annuity-due is worth more — each payment discounted one period less"
    (is (= 2970.40M (lease/present-value 1000M 0.01M 3 :in-advance)))
    (is (pos? (.compareTo (lease/present-value 1000M 0.01M 3 :in-advance)
                          (lease/present-value 1000M 0.01M 3 :in-arrears)))))
  (testing "a reasonably-certain purchase option lifts the PV"
    (is (pos? (.compareTo
               (lease/present-value 1000M 0.01M 3 :in-arrears {:final-value 5000M})
               (lease/present-value 1000M 0.01M 3 :in-arrears))))))

;; ============================================================================
;; EffectiveInterestProvider — the unwind
;; ============================================================================

(deftest effective-interest-unwind
  (let [conn (bootstrap)
        db   (d/db conn)]
    (lease/define-lease! conn
      {:code "LSE-FIN" :name "Berlin office" :lessor (p db "L-acme")
       :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
       :term-months 36 :payment-amount 1000.00M :payment-frequency :monthly
       :payment-timing :in-arrears :commodity (commodity db)
       :discount-rate 0.06M :origin-document (adoc db)
       :changed-by-uid (p db "U-cfo")})
    (lrun/commence! conn
                    {:lease "LSE-FIN" :journal (journal db) :changed-by-uid (p db "U-cfo")
                     :rou-asset-account (acct db "0250")
                     :rou-accumulated-account (acct db "0259")
                     :books [{:ledger (ledger db "ifrs") :classification :finance
                              :liability-account (acct db "1750")
                              :interest-account (acct db "7300")
                              :rou-expense-account (acct db "6200")}]})
    (let [book (liability/book-for (d/db conn) "LSE-FIN" (ledger (d/db conn) "ifrs"))
          plan (lp/plan-for-book (d/db conn) book)
          periods (:periods plan)]
      (testing "the plan covers every payment period"
        (is (= 36 (count periods))))
      (testing "each non-final payment is the level amount, split interest + principal"
        (let [p1 (first periods)]
          (is (= 1000.00M (:payment p1)))
          (is (= 164.36M (:interest p1)) "32871.02 × 0.5% ≈ 164.36")
          (is (= 835.64M (:principal p1)))
          (is (= 1000.00M (.add (:interest p1) (:principal p1))))))
      (testing "the balance unwinds exactly to zero"
        (is (= 0.00M (:balance-remaining (last periods)))))
      (testing "Σ principal = the opening liability (the PV)"
        (let [opening (:kontor.lease-liability/opening-liability
                       (liability/pull-book (d/db conn) book))
              sum-principal (reduce (fn [a x] (.add a (:principal x))) 0M periods)]
          (is (= opening sum-principal))))
      (testing "the operating-lease single cost = the level payment for a level lease"
        (is (= 1000.00M (:straight-line-expense plan)))))))

(deftest in-advance-period-one-has-no-interest
  (let [conn (bootstrap)
        db   (d/db conn)]
    (lease/define-lease! conn
      {:code "LSE-ADV" :name "Equipment" :lessor (p db "L-acme")
       :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
       :term-months 12 :payment-amount 500.00M :payment-frequency :monthly
       :payment-timing :in-advance :commodity (commodity db)
       :discount-rate 0.08M :origin-document (adoc db)
       :changed-by-uid (p db "U-cfo")})
    (lrun/commence! conn
                    {:lease "LSE-ADV" :journal (journal db) :changed-by-uid (p db "U-cfo")
                     :rou-asset-account (acct db "0250")
                     :rou-accumulated-account (acct db "0259")
                     :books [{:ledger (ledger db "ifrs") :classification :finance
                              :liability-account (acct db "1750")
                              :interest-account (acct db "7300")
                              :rou-expense-account (acct db "6200")}]})
    (let [book (liability/book-for (d/db conn) "LSE-ADV" (ledger (d/db conn) "ifrs"))
          periods (:periods (lp/plan-for-book (d/db conn) book))]
      (testing "the payment made AT commencement carries no interest — it is all principal"
        (is (= 0M (:interest (first periods))))
        (is (= 500.00M (:principal (first periods)))))
      (testing "later periods do accrue interest"
        (is (pos? (.signum (:interest (second periods))))))
      (testing "the balance still lands on zero"
        (is (= 0.00M (:balance-remaining (last periods))))))))

;; ============================================================================
;; commence!
;; ============================================================================

(deftest commence-recognises-a-finance-lease
  (let [conn (bootstrap)
        db   (d/db conn)
        _ (lease/define-lease! conn
            {:code "LSE-FIN" :name "Berlin office" :lessor (p db "L-acme")
             :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
             :term-months 36 :payment-amount 1000.00M :payment-frequency :monthly
             :payment-timing :in-arrears :commodity (commodity db)
             :discount-rate 0.06M :origin-document (adoc db)
             :changed-by-uid (p db "U-cfo")})
        result (lrun/commence! conn
                               {:lease "LSE-FIN" :journal (journal db) :changed-by-uid (p db "U-cfo")
                                :rou-asset-account (acct db "0250")
                                :rou-accumulated-account (acct db "0259")
                                :books [{:ledger (ledger db "ifrs") :classification :finance
                                         :liability-account (acct db "1750")
                                         :interest-account (acct db "7300")
                                         :rou-expense-account (acct db "6200")}]})
        db' (d/db conn)
        ifrs (ledger db' "ifrs")]
    (testing "the lease moves :draft → :active"
      (is (= :active (:kontor.lease/status (lease/pull-lease db' "LSE-FIN")))))
    (testing "a single Right-of-Use :asset is created and linked"
      (is (some? (:rou-asset result)))
      (is (= (:rou-asset result)
             (:db/id (:kontor.lease/rou-asset (lease/pull-lease db' "LSE-FIN"))))))
    (testing "one :lease-liability book + one ROU :asset-depreciation book exist"
      (is (= 1 (count (liability/books-of db' "LSE-FIN"))))
      (is (= 1 (count (asset-dep/books-of db' (:rou-asset result))))))
    (testing "the opening liability is the PV of the payments"
      (let [book (liability/book-for db' "LSE-FIN" ifrs)]
        (is (= 32871.02M (:kontor.lease-liability/opening-liability
                          (liability/pull-book db' book))))))
    (testing "the day-one recognition entry is balanced on the IFRS ledger"
      (is (zero? (.signum (reduce (fn [a code] (.add a (ledger-balance db' (acct db' code) ifrs)))
                                  0M ["0250" "0259" "1750" "7300" "6200" "1800"])))))
    (testing "ROU asset debited with its cost; liability credited with the PV"
      (is (= 32871.02M (ledger-balance db' (acct db' "0250") ifrs)))
      (is (= -32871.02M (ledger-balance db' (acct db' "1750") ifrs))))))

(deftest commence-rejects-a-non-draft-lease
  (let [conn (bootstrap)
        db   (d/db conn)
        _ (lease/define-lease! conn
            {:code "LSE-FIN" :name "Berlin office" :lessor (p db "L-acme")
             :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
             :term-months 12 :payment-amount 1000.00M :payment-frequency :monthly
             :payment-timing :in-arrears :commodity (commodity db)
             :discount-rate 0.06M :origin-document (adoc db)
             :changed-by-uid (p db "U-cfo")})
        commence! #(lrun/commence! conn
                                   {:lease "LSE-FIN" :journal (journal (d/db conn))
                                    :changed-by-uid (p (d/db conn) "U-cfo")
                                    :rou-asset-account (acct (d/db conn) "0250")
                                    :rou-accumulated-account (acct (d/db conn) "0259")
                                    :books [{:ledger (ledger (d/db conn) "ifrs") :classification :finance
                                             :liability-account (acct (d/db conn) "1750")
                                             :interest-account (acct (d/db conn) "7300")
                                             :rou-expense-account (acct (d/db conn) "6200")}]})]
    (commence!)
    (testing "a second commence! on an already-:active lease is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not :draft"
                            (commence!))))))

;; ============================================================================
;; run-lease! — finance
;; ============================================================================

(deftest run-lease-finance-unwinds-and-balances
  (let [conn (bootstrap)
        db   (d/db conn)
        _ (lease/define-lease! conn
            {:code "LSE-FIN" :name "Berlin office" :lessor (p db "L-acme")
             :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
             :term-months 36 :payment-amount 1000.00M :payment-frequency :monthly
             :payment-timing :in-arrears :commodity (commodity db)
             :discount-rate 0.06M :origin-document (adoc db)
             :changed-by-uid (p db "U-cfo")})
        _ (lrun/commence! conn
                          {:lease "LSE-FIN" :journal (journal db) :changed-by-uid (p db "U-cfo")
                           :rou-asset-account (acct db "0250")
                           :rou-accumulated-account (acct db "0259")
                           :books [{:ledger (ledger db "ifrs") :classification :finance
                                    :liability-account (acct db "1750")
                                    :interest-account (acct db "7300")
                                    :rou-expense-account (acct db "6200")}]})
        ifrs (ledger (d/db conn) "ifrs")
        result (lrun/run-lease! conn
                                {:lease "LSE-FIN" :ledger ifrs :journal (journal (d/db conn))
                                 :cash-account (acct (d/db conn) "1800")
                                 :changed-by-uid (p (d/db conn) "U-cfo")
                                 :as-of #inst "2026-04-15"})
        db' (d/db conn)]
    (testing "three liability payments + three ROU depreciation charges fire"
      (is (= [1 2 3] (:fired (:liability result))))
      (is (= [1 2 3] (:fired (:rou result)))))
    (testing "the GL balances on the IFRS ledger"
      (is (zero? (.signum (reduce (fn [a code] (.add a (ledger-balance db' (acct db' code) ifrs)))
                                  0M ["0250" "0259" "1750" "7300" "6200" "1800"])))))
    (testing "the liability + ROU postings reconcile to the run summary"
      ;; liability ledger balance = −PV + Σ principal paid.
      (is (= (:total-principal (:liability result))
             (.add (ledger-balance db' (acct db' "1750") ifrs) 32871.02M)))
      (is (= (:total-interest (:liability result))
             (ledger-balance db' (acct db' "7300") ifrs)))
      (is (= (:total (:rou result))
             (ledger-balance db' (acct db' "6200") ifrs))))
    (testing "cash out = three level payments"
      (is (= -3000.00M (ledger-balance db' (acct db' "1800") ifrs))))
    (testing "outstanding-liability equals −(GL 1750) — the control account"
      ;; This used to compare `outstanding-liability` to the very plan it
      ;; is derived from, which is a tautology: any drift between the
      ;; subledger and the ledger passes it unchanged (note 198 HIGH-5).
      ;; The only assertion with teeth is against the GL.
      ;; Hand derivation, 36 × €1,000 in arrears @ 6% (0.5%/period):
      ;;   PV  = 1000 × (1 − 1.005⁻³⁶)/0.005            = 32,871.02
      ;;   p1  int = round2(32,871.02 × .005) = 164.36 → 32,035.38
      ;;   p2  int = round2(32,035.38 × .005) = 160.18 → 31,195.56
      ;;   p3  int = round2(31,195.56 × .005) = 155.98 → 30,351.54
      (let [book (liability/book-for db' "LSE-FIN" ifrs)
            sub  (lp/outstanding-liability db' book)
            gl   (ledger-balance db' (acct db' "1750") ifrs)
            recon (lreport/reconcile-liability conn {:book book})]
        (is (zero? (bd-cmp 30351.54M sub)))
        (is (zero? (bd-cmp sub
                           (.negate ^java.math.BigDecimal gl))))
        (is (:ok? recon) (pr-str recon))))
    (testing "re-running the same window is idempotent — in the LEDGER, not just the log"
      (let [again (lrun/run-lease! conn
                                   {:lease "LSE-FIN" :ledger ifrs :journal (journal db')
                                    :cash-account (acct db' "1800")
                                    :changed-by-uid (p db' "U-cfo") :as-of #inst "2026-04-15"})
            db'' (d/db conn)]
        (is (= 0 (:count (:liability again))))
        (is (= 0 (:count (:rou again))))
        ;; `ledger-balance` was already in scope 10 lines above and never
        ;; re-read: a double-post would have left the counts at 0 and the
        ;; ledger doubled. Assert the numbers, not the bookkeeping row.
        (is (zero? (bd-cmp -30351.54M
                           (ledger-balance db'' (acct db'' "1750") ifrs))))
        (is (zero? (bd-cmp -3000.00M
                           (ledger-balance db'' (acct db'' "1800") ifrs))))
        (is (:ok? (lreport/reconcile-liability
                   conn {:book (liability/book-for db'' "LSE-FIN" ifrs)})))))))

;; ============================================================================
;; run-lease! — operating (ASC 842 single straight-line cost)
;; ============================================================================

(deftest operating-lease-recognises-one-straight-line-cost
  (let [conn (bootstrap)
        db   (d/db conn)
        _ (lease/define-lease! conn
            {:code "LSE-OP" :name "Warehouse" :lessor (p db "L-acme")
             :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
             :term-months 24 :payment-amount 2000.00M :payment-frequency :monthly
             :payment-timing :in-arrears :commodity (commodity db)
             :discount-rate 0.05M :initial-direct-costs 1200.00M
             :origin-document (adoc db) :changed-by-uid (p db "U-cfo")})
        _ (lrun/commence! conn
                          {:lease "LSE-OP" :journal (journal db) :changed-by-uid (p db "U-cfo")
                           :rou-asset-account (acct db "0250")
                           :rou-accumulated-account (acct db "0259")
                           :cash-account (acct db "1800")
             ;; operating book: interest leg AND ROU plug both → the
             ;; single lease-expense account.
                           :books [{:ledger (ledger db "us-gaap") :classification :operating
                                    :liability-account (acct db "1750")
                                    :interest-account (acct db "6740")
                                    :rou-expense-account (acct db "6740")}]})
        usgaap (ledger (d/db conn) "us-gaap")
        book   (liability/book-for (d/db conn) "LSE-OP" usgaap)
        plan   (lp/plan-for-book (d/db conn) book)
        ;; run the full 24-month term.
        result (lrun/run-lease! conn
                                {:lease "LSE-OP" :ledger usgaap :journal (journal (d/db conn))
                                 :cash-account (acct (d/db conn) "1800")
                                 :changed-by-uid (p (d/db conn) "U-cfo")
                                 :as-of #inst "2028-06-01"})
        db' (d/db conn)]
    (testing "the single straight-line cost = (Σ payments + IDC) / n"
      (is (= 2050.00M (:straight-line-expense plan))))
    (testing "every period fires; the lease ends :expired"
      (is (= 24 (:count (:liability result))))
      (is (= 24 (:count (:rou result))))
      (is (:completed? result))
      (is (= :expired (:kontor.lease/status (lease/pull-lease db' "LSE-OP")))))
    (testing "the liability is fully unwound and the ROU asset fully amortised"
      (is (= 0.00M (ledger-balance db' (acct db' "1750") usgaap)))
      (is (= 46787.80M (ledger-balance db' (acct db' "0250") usgaap)))
      (is (= -46787.80M (ledger-balance db' (acct db' "0259") usgaap))))
    (testing "the P&L shows ONE lease-expense line — interest leg + ROU plug both land in 6740"
      (is (= (.add (:total-interest (:liability result)) (:total (:rou result)))
             (ledger-balance db' (acct db' "6740") usgaap)))
      ;; ≈ 24 × 2050; rounding drift over the term is sub-cent-per-period.
      (is (>= 0.05M (.abs (.subtract (ledger-balance db' (acct db' "6740") usgaap)
                                     49200.00M)))))
    (testing "the us-gaap ledger balances"
      (is (zero? (.signum (reduce (fn [a code] (.add a (ledger-balance db' (acct db' code) usgaap)))
                                  0M ["0250" "0259" "1750" "6740" "1800"])))))))

;; ============================================================================
;; Multi-book — same lease, :finance on IFRS + :operating on US-GAAP
;; ============================================================================

(deftest multi-book-lease-parallel-ledgers
  (let [conn (bootstrap)
        db   (d/db conn)
        _ (lease/define-lease! conn
            {:code "LSE-MB" :name "Dual-GAAP equipment" :lessor (p db "L-acme")
             :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
             :term-months 12 :payment-amount 500.00M :payment-frequency :monthly
             :payment-timing :in-advance :commodity (commodity db)
             :discount-rate 0.08M :origin-document (adoc db)
             :changed-by-uid (p db "U-cfo")})
        result (lrun/commence! conn
                               {:lease "LSE-MB" :journal (journal db) :changed-by-uid (p db "U-cfo")
                                :rou-asset-account (acct db "0250")
                                :rou-accumulated-account (acct db "0259")
                                :books [{:ledger (ledger db "ifrs") :classification :finance
                                         :liability-account (acct db "1750")
                                         :interest-account (acct db "7300")
                                         :rou-expense-account (acct db "6200")}
                                        {:ledger (ledger db "us-gaap") :classification :operating
                                         :liability-account (acct db "1750")
                                         :interest-account (acct db "6740")
                                         :rou-expense-account (acct db "6740")}]})
        db' (d/db conn)
        ifrs (ledger db' "ifrs")
        usgaap (ledger db' "us-gaap")]
    (testing "one ROU :asset carries TWO :asset-depreciation books — one per ledger"
      (is (= 2 (count (asset-dep/books-of db' (:rou-asset result))))))
    (testing "two :lease-liability books — one per ledger — with the right classification"
      (is (= #{:finance :operating}
             (set (map #(:kontor.lease-liability/classification (liability/pull-book db' %))
                       (liability/books-of db' "LSE-MB"))))))
    ;; run month 1 on both ledgers.
    (lrun/run-lease! conn {:lease "LSE-MB" :ledger ifrs :journal (journal db')
                           :cash-account (acct db' "1800")
                           :changed-by-uid (p db' "U-cfo") :as-of #inst "2026-01-15"})
    (lrun/run-lease! conn {:lease "LSE-MB" :ledger usgaap :journal (journal db')
                           :cash-account (acct db' "1800")
                           :changed-by-uid (p db' "U-cfo") :as-of #inst "2026-01-15"})
    (let [db'' (d/db conn)]
      (testing "each ledger balances independently (ADR-021 parallel books)"
        (is (zero? (.signum (reduce (fn [a code] (.add a (ledger-balance db'' (acct db'' code) ifrs)))
                                    0M ["0250" "0259" "1750" "7300" "6200" "1800"]))))
        (is (zero? (.signum (reduce (fn [a code] (.add a (ledger-balance db'' (acct db'' code) usgaap)))
                                    0M ["0250" "0259" "1750" "6740" "1800"]))))))))

(deftest multi-book-lease-with-DIFFERING-discount-rates
  ;; Parallel books may discount at different rates (a subsidiary's IBR
  ;; is not the parent's). Then the PV — and hence the ROU cost — is
  ;; per-book, while the ROU :asset carries a SINGLE
  ;; :kontor.asset/acquisition-cost. runner.clj documents that this
  ;; scalar matches only the PRIMARY (first) book; nothing tested it, so
  ;; a consumer disposing the non-primary book through
  ;; `kontor.asset.asset/dispose!` would silently net against the wrong
  ;; cost. This pins the shape so the caveat cannot rot into a surprise.
  (let [conn (bootstrap)
        db   (d/db conn)
        _ (lease/define-lease! conn
            {:code "LSE-2R" :name "Two rates" :lessor (p db "L-acme")
             :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
             :term-months 12 :payment-amount 500.00M :payment-frequency :monthly
             :payment-timing :in-arrears :commodity (commodity db)
             :discount-rate 0.06M :origin-document (adoc db)
             :changed-by-uid (p db "U-cfo")})
        result (lrun/commence! conn
                               {:lease "LSE-2R" :journal (journal db) :changed-by-uid (p db "U-cfo")
                                :rou-asset-account (acct db "0250")
                                :rou-accumulated-account (acct db "0259")
                                :books [{:ledger (ledger db "ifrs") :classification :finance
                                         :liability-account (acct db "1750")
                                         :interest-account (acct db "7300")
                                         :rou-expense-account (acct db "6200")}
                                        {:ledger (ledger db "us-gaap") :classification :finance
                                         :discount-rate 0.08M
                                         :liability-account (acct db "1750")
                                         :interest-account (acct db "7300")
                                         :rou-expense-account (acct db "6200")}]})
        db'    (d/db conn)
        ifrs   (ledger db' "ifrs")
        usgaap (ledger db' "us-gaap")]
    ;; Hand derivation — ordinary annuity, 12 payments of 500:
    ;;   @6% → i = .06/12 = .005          PV = 500 × (1 − 1.005⁻¹²)/.005
    ;;                                       = 500 × 11.618932 = 5,809.47
    ;;   @8% → i = .08/12 = .006666666667 PV = 500 × (1 − (1+i)⁻¹²)/i
    ;;                                       = 500 × 11.495782 = 5,747.89
    (testing "each book measures its own PV at its own rate"
      (is (zero? (bd-cmp 5809.47M
                         (:pv (first (:books result))))))
      (is (zero? (bd-cmp 5747.89M
                         (:pv (second (:books result))))))
      (is (zero? (bd-cmp 5809.47M
                         (:kontor.lease-liability/opening-liability
                          (liability/pull-book db' (liability/book-for db' "LSE-2R" ifrs))))))
      (is (zero? (bd-cmp 5747.89M
                         (:kontor.lease-liability/opening-liability
                          (liability/pull-book db' (liability/book-for db' "LSE-2R" usgaap)))))))
    (testing "each ROU depreciation book carries its OWN depreciable base"
      (is (zero? (bd-cmp 5809.47M
                         (:kontor.asset-depreciation/depreciable-base
                          (d/pull db' [:kontor.asset-depreciation/depreciable-base]
                                  (:rou-dep-book (first (:books result))))))))
      (is (zero? (bd-cmp 5747.89M
                         (:kontor.asset-depreciation/depreciable-base
                          (d/pull db' [:kontor.asset-depreciation/depreciable-base]
                                  (:rou-dep-book (second (:books result)))))))))
    (testing "the :asset's single :acquisition-cost matches ONLY the primary book"
      (let [cost (:kontor.asset/acquisition-cost
                  (d/pull db' [:kontor.asset/acquisition-cost] (:rou-asset result)))]
        (is (zero? (bd-cmp 5809.47M cost)))
        (is (not (zero? (bd-cmp 5747.89M cost)))
            "62.42 adrift of the us-gaap book — dispose! needs an explicit :asset-account-cost")))
    (testing "each ledger recognises its own liability, and each ties"
      (is (zero? (bd-cmp -5809.47M
                         (ledger-balance db' (acct db' "1750") ifrs))))
      (is (zero? (bd-cmp -5747.89M
                         (ledger-balance db' (acct db' "1750") usgaap))))
      (is (:ok? (lreport/reconcile-liability
                 conn {:book (liability/book-for db' "LSE-2R" ifrs)})))
      (is (:ok? (lreport/reconcile-liability
                 conn {:book (liability/book-for db' "LSE-2R" usgaap)}))))
    (testing "the control-account tie-out sums BOTH books on one ledger's account"
      ;; Per-ledger: only that ledger's book counts.
      (let [r (lreport/reconcile-liability
               conn {:ledger usgaap :liability-account (acct db' "1750")
                     :commodity (commodity db')})]
        (is (:ok? r) (pr-str r))
        (is (zero? (bd-cmp 5747.89M (:subledger r))))))))

;; ============================================================================
;; import-lease! — ADR-069 mid-life portfolio import
;; ============================================================================

(deftest import-lease-onboards-a-mid-life-lease
  (let [conn (bootstrap)
        db   (d/db conn)
        ;; Picture: a 36-month lease that started 2024-01-01, payment 1000
        ;; in-arrears monthly at 6%. By 2026-05-01 the prior system has
        ;; fired 28 payments; 8 remain. The remaining PV (at the original
        ;; rate, period 29 onwards) ≈ 7891.86; the remaining ROU base ≈
        ;; the original 32871.02 × (8/36) = 7304.67 (the prior system's
        ;; carrying NBV for the ROU asset). For the test we pass these
        ;; as opaque "carrying amounts" the import takes at face value.
        _ (lease/define-lease! conn
            {:code "LSE-IMP" :name "Mid-life imported office"
             :lessor (p db "L-acme") :asset-class (class-eid db)
             :commencement-date #inst "2026-05-01"   ; re-anchored
             :term-months 8                         ; REMAINING months
             :payment-amount 1000.00M :payment-frequency :monthly
             :payment-timing :in-arrears :commodity (commodity db)
             :discount-rate 0.06M :origin-document (adoc db)
             :imported? true
             :imported-as-of #inst "2026-05-01"
             :imported-original-commencement-date #inst "2024-01-01"
             :imported-original-term-months 36
             :changed-by-uid (p db "U-cfo")})
        result (lrun/import-lease! conn
                                   {:lease "LSE-IMP" :changed-by-uid (p db "U-cfo")
                                    :rou-asset-account (acct db "0250")
                                    :rou-accumulated-account (acct db "0259")
                                    :books [{:ledger (ledger db "ifrs") :classification :finance
                                             :liability-account (acct db "1750")
                                             :interest-account (acct db "7300")
                                             :rou-expense-account (acct db "6200")
                                             :remaining-pv 7891.86M
                                             :remaining-rou-base 7304.67M
                                             :pre-import-accumulated 25566.35M}]})
        db' (d/db conn)
        ifrs (ledger db' "ifrs")]
    (testing "the lease moves :draft → :active via :lease-imported"
      (is (= :active (:kontor.lease/status (lease/pull-lease db' "LSE-IMP")))))
    (testing "the audit denorms are preserved on the lease"
      (let [l (lease/pull-lease db' "LSE-IMP")]
        (is (true? (:kontor.lease/imported? l)))
        (is (= #inst "2024-01-01" (:kontor.lease/imported-original-commencement-date l)))
        (is (= 36 (:kontor.lease/imported-original-term-months l)))))
    (testing "a single Right-of-Use :asset is created and linked"
      (is (some? (:rou-asset result)))
      (is (= (:rou-asset result)
             (:db/id (:kontor.lease/rou-asset (lease/pull-lease db' "LSE-IMP"))))))
    (testing "one :lease-liability book + one ROU :asset-depreciation book"
      (is (= 1 (count (liability/books-of db' "LSE-IMP"))))
      (is (= 1 (count (asset-dep/books-of db' (:rou-asset result))))))
    (testing "the liability book's :opening-liability IS the imported remaining PV"
      (let [book (liability/book-for db' "LSE-IMP" ifrs)]
        (is (= 7891.86M (:kontor.lease-liability/opening-liability
                         (liability/pull-book db' book))))))
    (testing "import-lease! posts NO day-one GL entry — the GL is the consumer's bridge"
      (is (zero? (.signum (ledger-balance db' (acct db' "0250") ifrs))))
      (is (zero? (.signum (ledger-balance db' (acct db' "1750") ifrs)))))))

(deftest import-lease-rejects-negative-carrying-amounts
  (let [conn (bootstrap)
        db   (d/db conn)
        _ (lease/define-lease! conn
            {:code "LSE-NEG" :name "Mis-signed import" :lessor (p db "L-acme")
             :asset-class (class-eid db) :commencement-date #inst "2026-05-01"
             :term-months 6 :payment-amount 1000.00M :payment-frequency :monthly
             :payment-timing :in-arrears :commodity (commodity db)
             :discount-rate 0.06M :origin-document (adoc db)
             :imported? true
             :imported-as-of #inst "2026-05-01"
             :imported-original-commencement-date #inst "2024-01-01"
             :imported-original-term-months 36
             :changed-by-uid (p db "U-cfo")})
        base-args {:lease "LSE-NEG" :changed-by-uid (p db "U-cfo")
                   :rou-asset-account (acct db "0250")
                   :rou-accumulated-account (acct db "0259")}
        a-book {:ledger (ledger db "ifrs") :classification :finance
                :liability-account (acct db "1750")
                :interest-account (acct db "7300")
                :rou-expense-account (acct db "6200")}]
    (testing "import-lease! rejects a negative :remaining-pv (mis-signed export)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":remaining-pv"
           (lrun/import-lease! conn
                               (assoc base-args :books
                                      [(assoc a-book :remaining-pv -100M
                                              :remaining-rou-base 100M)])))))
    (testing "import-lease! rejects a negative :remaining-rou-base"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":remaining-rou-base"
           (lrun/import-lease! conn
                               (assoc base-args :books
                                      [(assoc a-book :remaining-pv 100M
                                              :remaining-rou-base -100M)])))))
    (testing "import-lease! rejects a negative :pre-import-accumulated"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":pre-import-accumulated"
           (lrun/import-lease! conn
                               (assoc base-args :books
                                      [(assoc a-book :remaining-pv 100M
                                              :remaining-rou-base 100M
                                              :pre-import-accumulated -50M)])))))))

(deftest import-lease-rejects-a-non-imported-lease
  (let [conn (bootstrap)
        db   (d/db conn)
        _ (lease/define-lease! conn
            {:code "LSE-NORMAL" :name "Regular new lease"
             :lessor (p db "L-acme") :asset-class (class-eid db)
             :commencement-date #inst "2026-01-01"
             :term-months 12 :payment-amount 500.00M :payment-frequency :monthly
             :payment-timing :in-arrears :commodity (commodity db)
             :discount-rate 0.06M :origin-document (adoc db)
             :changed-by-uid (p db "U-cfo")})]
    (testing "import-lease! refuses a lease without :imported? true"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":kontor.lease/imported\?"
           (lrun/import-lease! conn
                               {:lease "LSE-NORMAL" :changed-by-uid (p db "U-cfo")
                                :rou-asset-account (acct db "0250")
                                :rou-accumulated-account (acct db "0259")
                                :books [{:ledger (ledger db "ifrs")
                                         :classification :finance
                                         :liability-account (acct db "1750")
                                         :interest-account (acct db "7300")
                                         :rou-expense-account (acct db "6200")
                                         :remaining-pv 1000M
                                         :remaining-rou-base 1000M}]}))))))

(deftest imported-lease-runs-the-remaining-tail
  (let [conn (bootstrap)
        db   (d/db conn)
        _ (lease/define-lease! conn
            {:code "LSE-IMP2" :name "Mid-life imported"
             :lessor (p db "L-acme") :asset-class (class-eid db)
             :commencement-date #inst "2026-05-01"
             :term-months 3
             :payment-amount 1000.00M :payment-frequency :monthly
             :payment-timing :in-arrears :commodity (commodity db)
             :discount-rate 0.06M :origin-document (adoc db)
             :imported? true
             :imported-as-of #inst "2026-05-01"
             :imported-original-commencement-date #inst "2024-01-01"
             :imported-original-term-months 36
             :changed-by-uid (p db "U-cfo")})
        _ (lrun/import-lease! conn
                              {:lease "LSE-IMP2" :changed-by-uid (p db "U-cfo")
                               :rou-asset-account (acct db "0250")
                               :rou-accumulated-account (acct db "0259")
                               :books [{:ledger (ledger db "ifrs") :classification :finance
                                        :liability-account (acct db "1750")
                                        :interest-account (acct db "7300")
                                        :rou-expense-account (acct db "6200")
                                        :remaining-pv 2970.40M
                                        :remaining-rou-base 2740.92M
                                        :pre-import-accumulated 30130.10M}]})
        ifrs (ledger (d/db conn) "ifrs")
        db1 (d/db conn)
        rou-dep-book (asset-dep/book-for db1
                                         (:db/id (:kontor.lease/rou-asset
                                                  (lease/pull-lease db1 "LSE-IMP2")))
                                         ifrs)
        result (lrun/run-lease! conn
                                {:lease "LSE-IMP2" :ledger ifrs :journal (journal (d/db conn))
                                 :cash-account (acct (d/db conn) "1800")
                                 :changed-by-uid (p (d/db conn) "U-cfo")
                                 :as-of #inst "2026-08-15"})]
    (testing "the ROU dep book carries the pre-import accumulated as a scalar"
      (let [b (d/pull db1 [:kontor.asset-depreciation/opening-accumulated
                           :kontor.asset-depreciation/depreciable-base
                           :kontor.asset-depreciation/useful-life-months]
                      rou-dep-book)]
        (is (= 30130.10M (:kontor.asset-depreciation/opening-accumulated b)))
        (is (= 2740.92M  (:kontor.asset-depreciation/depreciable-base b)))
        (is (= 3 (:kontor.asset-depreciation/useful-life-months b)))))
    (testing "the remaining three payments fire on the imported tail"
      (is (= [1 2 3] (:fired (:liability result))))
      (is (= [1 2 3] (:fired (:rou result)))))
    (testing "the lease auto-expires at the last fired payment"
      (is (true? (:completed? result)))
      (is (= :expired (:kontor.lease/status (lease/pull-lease (d/db conn) "LSE-IMP2")))))))
