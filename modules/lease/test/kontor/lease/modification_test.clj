(ns kontor.lease.modification-test
  "ADR-064: kontor-lease modifications, remeasurements + terminations.

   Covers:
   - remeasure! — an index reset mid-term re-anchors every
     :lease-liability book at the PV of the revised remaining
     payments, posts the difference against the ROU :asset, and the
     lease still unwinds to exactly zero by end of term; the GL
     balances throughout.
   - terminate! — full early termination derecognises the liability
     and the ROU asset, books the difference (and any penalty) to
     P&L, cancels both schedules, and drives :kontor.lease/status →
     :terminated.
   - purchase! — exercising the purchase option settles the remaining
     liability in cash and drives :kontor.lease/status → :purchased.
   - partial-terminate! — the proportional approach: the liability and
     the ROU asset are reduced by the scope-decrease fraction, the
     difference is a P&L gain/loss, and the remaining lease still
     unwinds to zero.
   - revise-liability-book! re-anchors the book (:opening-fired-through
     advances to the fired count, the schedule end-date follows the
     revised term)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.asset.schema :as asset-schema]
            [kontor.core :as core]
            [kontor.lease.core :as lease]
            [kontor.lease.lease-provider :as lp]
            [kontor.lease.liability :as liability]
            [kontor.lease.modification :as lmod]
            [kontor.lease.report :as lreport]
            [kontor.lease.runner :as lrun]
            [kontor.lease.schema :as lease-schema]
            [kontor.workflow.schedule :as schedule]))

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
                 {:kontor.partner/external-id "U-ctrl" :kontor.partner/name "Controller"}
                 {:kontor.partner/external-id "L-acme" :kontor.partner/name "Acme Properties"}
                 {:db/id "led-ifrs" :kontor.ledger/code "ifrs" :kontor.ledger/name "IFRS 16"
                  :kontor.ledger/framework :ifrs}
                 ;; Dual-ledger test fixtures — ASC 842
                 ;; operating + index-reset fork.
                 {:db/id "led-usgaap" :kontor.ledger/code "us-gaap"
                  :kontor.ledger/name "ASC 842"
                  :kontor.ledger/framework :us-gaap}
                 {:db/id "a-vle"    :kontor.account/code "7410"
                  :kontor.account/name "Variable Lease Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "a-vlp"    :kontor.account/code "1751"
                  :kontor.account/name "Variable Lease Payable"
                  :kontor.account/type :liability :kontor.account/active true}
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
                 {:db/id "a-gl"     :kontor.account/code "7400"
                  :kontor.account/name "Lease Modification Gain/Loss"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "a-cash"   :kontor.account/code "1800" :kontor.account/name "Bank"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "j-gen" :kontor.journal/code "GEN" :kontor.journal/name "General"
                  :kontor.journal/type :general}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- commodity [db] (ref-eid db :kontor.commodity/symbol "EUR"))
(defn- p         [db code] (ref-eid db :kontor.partner/external-id code))
(defn- acct      [db code] (ref-eid db :kontor.account/code code))
(defn- journal   [db] (ref-eid db :kontor.journal/code "GEN"))
(defn- class-eid [db] (ref-eid db :kontor.asset-class/code "rou-property"))
(defn- adoc      [db] (ref-eid db :kontor.audit-doc/code "LEASE-CONTRACT-1"))
(defn- ifrs      [db] (ref-eid db :kontor.ledger/code "ifrs"))
(defn- usgaap    [db] (ref-eid db :kontor.ledger/code "us-gaap"))

(defn- ledger-balance [db account ledger-eid]
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

(defn- ledger-sum [db ledger-eid codes]
  (reduce (fn [a code] (.add a (ledger-balance db (acct db code) ledger-eid)))
          0M codes))

(def ^:private gl-codes ["0250" "0259" "1750" "7300" "6200" "7400" "1800"])

;; ---------------------------------------------------------------------------
;; The assertion the old proxy could not make (note 198 HIGH-5)
;;
;; `(zero? (.signum (ledger-sum db led gl-codes)))` sums EVERY account of a
;; ledger. That is zero BY DOUBLE-ENTRY CONSTRUCTION — `build-transaction`
;; refuses to build an entry that does not balance, so the sum is zero for a
;; correct book and equally zero for a book whose liability subledger has
;; wandered 607.55 away from its control account. It cannot detect
;; subledger-vs-control-account drift, which is why HIGH-5 shipped green.
;;
;; What actually pins it: the liability subledger (the carrying amount the
;; provider reports) against the ONE account that carries it, per ledger.
;; ---------------------------------------------------------------------------

(defn- bd= [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (zero? (.compareTo a b)))

(defn- outstanding [db code ledger-eid]
  (lp/outstanding-liability db (liability/book-for db code ledger-eid)))

(defn- assert-liability-ties!
  "The lease-liability subledger MUST equal −(GL 1750) on this ledger,
   and `report/reconcile-liability` must agree. `label` names the
   moment so a failure says which one broke."
  [conn code ledger-eid label]
  (let [db  (d/db conn)
        sub (outstanding db code ledger-eid)
        gl  (ledger-balance db (acct db "1750") ledger-eid)
        r   (lreport/reconcile-liability
             conn {:book (liability/book-for db code ledger-eid)})]
    (is (bd= sub (.negate ^java.math.BigDecimal gl))
        (str label ": subledger " sub " vs GL 1750 " gl))
    (is (:ok? r) (str label ": reconcile-liability " (pr-str r)))))

(defn- a-finance-lease!
  "Define + commence a finance lease on the IFRS ledger. Returns the
   conn — tests fire periods explicitly with `run-through!`."
  [{:keys [code term payment purchase-option]}]
  (let [conn (bootstrap)
        db   (d/db conn)]
    (lease/define-lease! conn
      (cond-> {:code code :name code :lessor (p db "L-acme")
               :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
               :term-months term :payment-amount payment :payment-frequency :monthly
               :payment-timing :in-arrears :commodity (commodity db)
               :discount-rate 0.06M :origin-document (adoc db)
               :changed-by-uid (p db "U-cfo")}
        purchase-option (assoc :purchase-option-price purchase-option)))
    (lrun/commence! conn
                    {:lease code :journal (journal db) :changed-by-uid (p db "U-cfo")
                     :rou-asset-account (acct db "0250")
                     :rou-accumulated-account (acct db "0259")
                     :books [{:ledger (ifrs db) :classification :finance
                              :liability-account (acct db "1750")
                              :interest-account (acct db "7300")
                              :rou-expense-account (acct db "6200")}]})
    conn))

(defn- an-operating-lease!
  "Define + commence an OPERATING lease on the IFRS ledger — the
   interest leg and the ROU plug both route to the single
   lease-expense account 7400."
  [{:keys [code term payment]}]
  (let [conn (bootstrap)
        db   (d/db conn)]
    (lease/define-lease! conn
      {:code code :name code :lessor (p db "L-acme")
       :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
       :term-months term :payment-amount payment :payment-frequency :monthly
       :payment-timing :in-arrears :commodity (commodity db)
       :discount-rate 0.06M :origin-document (adoc db)
       :changed-by-uid (p db "U-cfo")})
    (lrun/commence! conn
                    {:lease code :journal (journal db) :changed-by-uid (p db "U-cfo")
                     :rou-asset-account (acct db "0250")
                     :rou-accumulated-account (acct db "0259")
                     :books [{:ledger (ifrs db) :classification :operating
                              :liability-account (acct db "1750")
                              :interest-account (acct db "7400")
                              :rou-expense-account (acct db "7400")}]})
    conn))

(defn- a-dual-ledger-lease!
  "Define + commence a lease with BOTH an IFRS 16 :finance book AND
   an ASC 842 :operating book. The bug-fix path is
   exercised by remeasure! :kind :index-reset — the IFRS book takes
   the historical remeasurement path; the ASC 842 operating book
   takes the variable-expense fork."
  [{:keys [code term payment]}]
  (let [conn (bootstrap)
        db   (d/db conn)]
    (lease/define-lease! conn
      {:code code :name code :lessor (p db "L-acme")
       :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
       :term-months term :payment-amount payment :payment-frequency :monthly
       :payment-timing :in-arrears :commodity (commodity db)
       :discount-rate 0.06M :origin-document (adoc db)
       :changed-by-uid (p db "U-cfo")})
    (lrun/commence! conn
                    {:lease code :journal (journal db) :changed-by-uid (p db "U-cfo")
                     :rou-asset-account (acct db "0250")
                     :rou-accumulated-account (acct db "0259")
                     :books [{:ledger (ifrs db) :classification :finance
                              :liability-account (acct db "1750")
                              :interest-account (acct db "7300")
                              :rou-expense-account (acct db "6200")}
                             {:ledger (usgaap db) :classification :operating
                              :liability-account (acct db "1750")
                              :interest-account (acct db "7400")
                              :rou-expense-account (acct db "7400")}]})
    conn))

(defn- run-through!
  "Fire a lease's periods on a ledger. The ledger arg used to be
   hard-wired to `ifrs`, so NO test ever ran the US-GAAP book to term
   — which is exactly why the ASC 842 fork's drift (note 198 HIGH-5)
   could not be seen: the drift only becomes a stranded control-account
   balance once the forked book is run out."
  ([conn code as-of] (run-through! conn code as-of (ifrs (d/db conn))))
  ([conn code as-of ledger-eid]
   (lrun/run-lease! conn
                    {:lease code :ledger ledger-eid :journal (journal (d/db conn))
                     :cash-account (acct (d/db conn) "1800")
                     :changed-by-uid (p (d/db conn) "U-cfo") :as-of as-of})))

;; ============================================================================
;; remeasure!
;; ============================================================================

(deftest remeasure-re-anchors-and-still-unwinds-to-zero
  (let [conn (bootstrap)
        db   (d/db conn)]
    (lease/define-lease! conn
      {:code "LSE-RM" :name "Office" :lessor (p db "L-acme")
       :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
       :term-months 24 :payment-amount 1000.00M :payment-frequency :monthly
       :payment-timing :in-arrears :commodity (commodity db)
       :discount-rate 0.06M :origin-document (adoc db)
       :changed-by-uid (p db "U-cfo")})
    (lrun/commence! conn
                    {:lease "LSE-RM" :journal (journal db) :changed-by-uid (p db "U-cfo")
                     :rou-asset-account (acct db "0250") :rou-accumulated-account (acct db "0259")
                     :books [{:ledger (ifrs db) :classification :finance
                              :liability-account (acct db "1750")
                              :interest-account (acct db "7300")
                              :rou-expense-account (acct db "6200")}]})
    (run-through! conn "LSE-RM" #inst "2026-07-15")        ; fire 6 months
    (let [db1 (d/db conn)
          ifrs-eid (ifrs db1)
          book (liability/book-for db1 "LSE-RM" ifrs-eid)
          outstanding-before (lp/outstanding-liability db1 book)
          result (lmod/remeasure! conn
                                  {:lease "LSE-RM" :date #inst "2026-07-20" :kind :index-reset
                                   :new-payment-amount 1200.00M :journal (journal db1)
                                   :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                                   :gain-loss-account (acct db1 "7400")})
          db2 (d/db conn)]
      (testing "the :lease contract fact is updated and an event recorded"
        (is (= 1200.00M (:kontor.lease/payment-amount (lease/pull-lease db2 "LSE-RM"))))
        (is (some? (:modification result)))
        (is (= :index-reset
               (:kontor.lease-modification/kind
                (d/pull db2 [:kontor.lease-modification/kind] (:modification result))))))
      (testing "the book is re-anchored: opening-fired-through = the fired count"
        (let [b (liability/pull-book db2 book)]
          (is (= 6 (:kontor.lease-liability/opening-fired-through b)))
          (is (= (:new-liability (first (:books result)))
                 (:kontor.lease-liability/opening-liability b)))))
      (testing "the remeasured subledger ties to the GL 1750 control account"
        ;; PV @1000/24 = 22,562.87; after 6 fired periods the unwound
        ;; balance is 17,172.77 (see the LSE-DUAL derivation below).
        ;; The adjustment credits 1750 by (new − old), so GL 1750 must
        ;; land on exactly −new-liability.
        (is (bd= 17172.77M outstanding-before))
        (is (bd= (.negate ^java.math.BigDecimal
                  (:new-liability (first (:books result))))
                 (ledger-balance db2 (acct db2 "1750") ifrs-eid)))
        (assert-liability-ties! conn "LSE-RM" ifrs-eid "after remeasure"))
      (testing "the new liability is the PV of the revised remaining payments"
        ;; 18 remaining payments of 1200 at 0.5%/period.
        (is (= (lease/present-value 1200.00M 0.005M 18 :in-arrears)
               (:new-liability (first (:books result))))))
      (run-through! conn "LSE-RM" #inst "2028-06-01")      ; fire to end of term
      (let [db3 (d/db conn)]
        (testing "the liability + the ROU asset both land on zero"
          (is (= 0.00M (ledger-balance db3 (acct db3 "1750") ifrs-eid)))
          (is (= 0.00M (.add (ledger-balance db3 (acct db3 "0250") ifrs-eid)
                             (ledger-balance db3 (acct db3 "0259") ifrs-eid)))))
        (testing "subledger and control account both land on zero"
          (is (bd= 0M (outstanding db3 "LSE-RM" ifrs-eid)))
          (assert-liability-ties! conn "LSE-RM" ifrs-eid "at term")
          (is (= :expired (:kontor.lease/status (lease/pull-lease db3 "LSE-RM"))))))
      (is (pos? (.compareTo (:new-liability (first (:books result)))
                            outstanding-before))
          "a payment increase raises the liability"))))

;; ============================================================================
;; terminate!
;; ============================================================================

(deftest terminate-derecognises-and-marks-terminated
  (let [conn (a-finance-lease! {:code "LSE-T" :term 12 :payment 500.00M :months 0})
        _ (run-through! conn "LSE-T" #inst "2026-04-15")   ; fire 3 months
        db1 (d/db conn)
        ifrs-eid (ifrs db1)
        book (liability/book-for db1 "LSE-T" ifrs-eid)
        result (lmod/terminate! conn
                                {:lease "LSE-T" :date #inst "2026-04-20" :journal (journal db1)
                                 :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                                 :gain-loss-account (acct db1 "7400")
                                 :penalty 200.00M :cash-account (acct db1 "1800")})
        db2 (d/db conn)]
    (testing "the lease is driven to :terminated"
      (is (= :terminated (:kontor.lease/status (lease/pull-lease db2 "LSE-T")))))
    (testing "the liability and the ROU asset are derecognised — both land on zero"
      (is (= 0.00M (ledger-balance db2 (acct db2 "1750") ifrs-eid)))
      (is (= 0.00M (.add (ledger-balance db2 (acct db2 "0250") ifrs-eid)
                         (ledger-balance db2 (acct db2 "0259") ifrs-eid)))))
    (testing "both schedules are cancelled"
      (let [b (liability/pull-book db2 book)]
        (is (= :cancelled (:kontor.schedule/state (:kontor.lease-liability/schedule b))))))
    (testing "the GL balances (penalty + gain/loss included) AND the subledger is zeroed"
      (is (zero? (.signum (ledger-sum db2 ifrs-eid gl-codes))))
      (is (bd= 0M (outstanding db2 "LSE-T" ifrs-eid)))
      (assert-liability-ties! conn "LSE-T" ifrs-eid "after termination"))
    (testing "the derecognised amounts are reported"
      (is (pos? (.signum (:derecognised-liability (first (:books result))))))
      (is (pos? (.signum (:derecognised-rou (first (:books result)))))))
    (testing "running a terminated lease fires nothing — the schedule is cancelled"
      (let [again (run-through! conn "LSE-T" #inst "2027-01-01")]
        (is (= 0 (:count (:liability again))))))))

;; ============================================================================
;; purchase!
;; ============================================================================

(deftest purchase-settles-the-liability-and-marks-purchased
  (let [conn (a-finance-lease! {:code "LSE-P" :term 12 :payment 500.00M :months 0
                                :purchase-option 1000.00M})
        _ (run-through! conn "LSE-P" #inst "2026-12-15")   ; fire 11 months
        db1 (d/db conn)
        ifrs-eid (ifrs db1)
        result (lmod/purchase! conn
                               {:lease "LSE-P" :date #inst "2026-12-31" :journal (journal db1)
                                :cash-account (acct db1 "1800") :changed-by-uid (p db1 "U-ctrl")
                                :gain-loss-account (acct db1 "7400") :justification (adoc db1)})
        db2 (d/db conn)]
    (testing "the lease is driven to :purchased"
      (is (= :purchased (:kontor.lease/status (lease/pull-lease db2 "LSE-P")))))
    (testing "the remaining liability is settled — it lands on zero"
      (is (= 0.00M (ledger-balance db2 (acct db2 "1750") ifrs-eid))))
    (testing "the GL balances AND the subledger is zeroed"
      (is (zero? (.signum (ledger-sum db2 ifrs-eid gl-codes))))
      (is (bd= 0M (outstanding db2 "LSE-P" ifrs-eid)))
      (assert-liability-ties! conn "LSE-P" ifrs-eid "after purchase"))
    (testing "the settled liability is reported"
      (is (pos? (.signum (:settled-liability (first (:books result)))))))))

;; ============================================================================
;; partial-terminate!
;; ============================================================================

(deftest partial-terminate-reduces-proportionally-and-still-unwinds
  (let [conn (a-finance-lease! {:code "LSE-PT" :term 24 :payment 1000.00M :months 0})
        _ (run-through! conn "LSE-PT" #inst "2026-07-15")  ; fire 6 months
        db1 (d/db conn)
        ifrs-eid (ifrs db1)
        result (lmod/partial-terminate! conn
                                        {:lease "LSE-PT" :date #inst "2026-07-20"
                                         :scope-decrease-pct 0.40M :new-payment-amount 600.00M
                                         :journal (journal db1) :changed-by-uid (p db1 "U-ctrl")
                                         :gain-loss-account (acct db1 "7400") :justification (adoc db1)})
        db2 (d/db conn)]
    (testing "giving up 40% of the asset for a 40%-lower payment reduces the liability"
      (is (pos? (.compareTo (:old-outstanding (first (:books result)))
                            (:new-liability (first (:books result)))))))
    (testing "the reduced subledger ties to the GL 1750 control account"
      (is (bd= (.negate ^java.math.BigDecimal
                (:new-liability (first (:books result))))
               (ledger-balance db2 (acct db2 "1750") ifrs-eid)))
      (assert-liability-ties! conn "LSE-PT" ifrs-eid "after partial termination"))
    (testing "a :partial-termination event records the scope decrease"
      (is (= 0.40M
             (:kontor.lease-modification/scope-decrease-pct
              (d/pull db2 [:kontor.lease-modification/scope-decrease-pct]
                      (:modification result))))))
    (run-through! conn "LSE-PT" #inst "2028-06-01")        ; fire to end of term
    (let [db3 (d/db conn)]
      (testing "the reduced lease still unwinds to exactly zero"
        (is (= 0.00M (ledger-balance db3 (acct db3 "1750") ifrs-eid)))
        (is (= 0.00M (.add (ledger-balance db3 (acct db3 "0250") ifrs-eid)
                           (ledger-balance db3 (acct db3 "0259") ifrs-eid)))))
      (testing "subledger and control account both land on zero"
        (is (bd= 0M (outstanding db3 "LSE-PT" ifrs-eid)))
        (assert-liability-ties! conn "LSE-PT" ifrs-eid "at term")
        (is (= :expired (:kontor.lease/status (lease/pull-lease db3 "LSE-PT"))))))))

;; ============================================================================
;; revise-liability-book! — the re-anchor primitive
;; ============================================================================

(deftest revise-liability-book-advances-the-fired-pointer
  (let [conn (a-finance-lease! {:code "LSE-RV" :term 12 :payment 500.00M :months 0})
        _ (run-through! conn "LSE-RV" #inst "2026-05-15")  ; fire 4 months
        db1 (d/db conn)
        book (liability/book-for db1 "LSE-RV" (ifrs db1))]
    (liability/revise-liability-book! conn
                                      {:book book :new-opening-liability 4000.00M :note "manual re-anchor"})
    (let [b (liability/pull-book (d/db conn) book)]
      (testing ":opening-fired-through advances to the fired-occurrence count"
        (is (= 4 (:kontor.lease-liability/opening-fired-through b))))
      (testing ":opening-liability is set to the new anchor"
        (is (= 4000.00M (:kontor.lease-liability/opening-liability b))))
      (testing "fired occurrences are untouched"
        (is (= 4 (count (schedule/fired-sequences
                         (d/db conn)
                         (:db/id (:kontor.lease-liability/schedule b))))))))))

;; ============================================================================
;; Review-after coverage — operating-lease modification, term change,
;; a modification into an already-modified book
;; ============================================================================

(deftest remeasure-on-an-operating-lease-still-unwinds
  ;; The operating-lease ROU plug re-anchor path — heavily arithmetic,
  ;; previously only probed by hand in review-after.
  (let [conn (an-operating-lease! {:code "LSE-OPR" :term 24 :payment 1000.00M})
        _ (run-through! conn "LSE-OPR" #inst "2026-07-15")  ; fire 6 months
        db1 (d/db conn)
        ifrs-eid (ifrs db1)
        result (lmod/remeasure! conn
                                {:lease "LSE-OPR" :date #inst "2026-07-20" :kind :index-reset
                                 :new-payment-amount 1150.00M :journal (journal db1)
                                 :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                                 :gain-loss-account (acct db1 "7400")})
        db2 (d/db conn)]
    (testing "the operating-lease remeasured subledger ties to GL 1750"
      ;; IFRS ledger ⇒ the ASC 842 fork does NOT fire; this book is
      ;; genuinely remeasured, so the adjustment must move 1750 to
      ;; exactly −new-liability.
      (is (some? (:modification result)))
      (is (bd= (.negate ^java.math.BigDecimal
                (:new-liability (first (:books result))))
               (ledger-balance db2 (acct db2 "1750") ifrs-eid)))
      (assert-liability-ties! conn "LSE-OPR" ifrs-eid "after remeasure"))
    (run-through! conn "LSE-OPR" #inst "2028-06-01")        ; fire to end of term
    (let [db3 (d/db conn)]
      (testing "the operating lease still unwinds the liability + ROU to zero"
        (is (bd= 0M (ledger-balance db3 (acct db3 "1750") ifrs-eid)))
        (is (bd= 0M (.add (ledger-balance db3 (acct db3 "0250") ifrs-eid)
                          (ledger-balance db3 (acct db3 "0259") ifrs-eid)))))
      (testing "subledger and control account both land on zero"
        (is (bd= 0M (outstanding db3 "LSE-OPR" ifrs-eid)))
        (assert-liability-ties! conn "LSE-OPR" ifrs-eid "at term")
        (is (= :expired (:kontor.lease/status (lease/pull-lease db3 "LSE-OPR"))))))))

(deftest remeasure-with-a-term-extension-reschedules-and-unwinds
  (let [conn (a-finance-lease! {:code "LSE-TX" :term 12 :payment 500.00M})
        _ (run-through! conn "LSE-TX" #inst "2026-05-15")   ; fire 4 months
        db1 (d/db conn)
        ifrs-eid (ifrs db1)
        book (liability/book-for db1 "LSE-TX" ifrs-eid)
        ;; extend the term 12 → 24 months at the same payment
        result (lmod/remeasure! conn
                                {:lease "LSE-TX" :date #inst "2026-05-20" :kind :term-change
                                 :new-term-months 24 :journal (journal db1)
                                 :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                                 :gain-loss-account (acct db1 "7400")})
        db2 (d/db conn)]
    (testing "the :lease term fact is updated"
      (is (= 24 (:kontor.lease/term-months (lease/pull-lease db2 "LSE-TX")))))
    (testing "the term extension raises the liability and 1750 follows it"
      (is (pos? (.compareTo (:new-liability (first (:books result)))
                            (:old-outstanding (first (:books result))))))
      (is (bd= (.negate ^java.math.BigDecimal
                (:new-liability (first (:books result))))
               (ledger-balance db2 (acct db2 "1750") ifrs-eid)))
      (assert-liability-ties! conn "LSE-TX" ifrs-eid "after term extension"))
    (run-through! conn "LSE-TX" #inst "2029-06-01")         ; fire all 24 months
    (let [db3 (d/db conn)]
      (testing "all 24 periods fire and the extended lease unwinds to zero"
        (is (= 24 (count (schedule/fired-sequences
                          db3 (:db/id (:kontor.lease-liability/schedule
                                       (liability/pull-book db3 book)))))))
        (is (bd= 0M (ledger-balance db3 (acct db3 "1750") ifrs-eid)))
        (is (bd= 0M (.add (ledger-balance db3 (acct db3 "0250") ifrs-eid)
                          (ledger-balance db3 (acct db3 "0259") ifrs-eid))))
        (is (bd= 0M (outstanding db3 "LSE-TX" ifrs-eid)))
        (assert-liability-ties! conn "LSE-TX" ifrs-eid "at term")))))

(deftest terminate-after-a-remeasure-balances
  ;; A modification into an already-re-anchored book.
  (let [conn (a-finance-lease! {:code "LSE-RT" :term 24 :payment 1000.00M})
        _ (run-through! conn "LSE-RT" #inst "2026-07-15")   ; fire 6 months
        db1 (d/db conn)
        _ (lmod/remeasure! conn
                           {:lease "LSE-RT" :date #inst "2026-07-20" :kind :index-reset
                            :new-payment-amount 1100.00M :journal (journal db1)
                            :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                            :gain-loss-account (acct db1 "7400")})
        _ (run-through! conn "LSE-RT" #inst "2026-10-15")   ; fire 3 more months
        db2 (d/db conn)
        ifrs-eid (ifrs db2)
        result (lmod/terminate! conn
                                {:lease "LSE-RT" :date #inst "2026-10-20" :journal (journal db2)
                                 :changed-by-uid (p db2 "U-ctrl") :justification (adoc db2)
                                 :gain-loss-account (acct db2 "7400")})
        db3 (d/db conn)]
    (testing "terminating an already-modified lease derecognises cleanly"
      (is (= :terminated (:kontor.lease/status (lease/pull-lease db3 "LSE-RT"))))
      (is (bd= 0M (ledger-balance db3 (acct db3 "1750") ifrs-eid)))
      (is (bd= 0M (.add (ledger-balance db3 (acct db3 "0250") ifrs-eid)
                        (ledger-balance db3 (acct db3 "0259") ifrs-eid))))
      ;; The derecognition debits 1750 for exactly what the SUBLEDGER
      ;; said was left — so the subledger must be zero too, not merely
      ;; the ledger. (A drifted subledger derecognises the wrong number
      ;; and strands the difference: note 198 HIGH-5.)
      (is (bd= 0M (outstanding db3 "LSE-RT" ifrs-eid)))
      (assert-liability-ties! conn "LSE-RT" ifrs-eid "after termination")
      (is (pos? (.signum (:derecognised-liability (first (:books result)))))))))

;; ============================================================================
;; Review-after coverage — period-lock enforcement on modifications
;; ============================================================================

(deftest modifications-refuse-to-post-into-a-locked-period
  (let [conn (a-finance-lease! {:code "LSE-LK" :term 24 :payment 1000.00M})
        _ (run-through! conn "LSE-LK" #inst "2026-07-15")   ; fire 6 months
        db1 (d/db conn)]
    ;; Soft-close 2026 — a 2026-dated GL posting must now be refused.
    (d/transact conn [{:kontor.period/start #inst "2026-01-01"
                       :kontor.period/end #inst "2027-01-01"
                       :kontor.period/locked-at #inst "2027-01-15"}])
    (testing "remeasure! into the soft-closed period is refused"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)period"
           (lmod/remeasure! conn
                            {:lease "LSE-LK" :date #inst "2026-08-01" :kind :index-reset
                             :new-payment-amount 1200.00M :journal (journal db1)
                             :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                             :gain-loss-account (acct db1 "7400")}))))
    (testing "terminate! into the soft-closed period is refused"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)period"
           (lmod/terminate! conn
                            {:lease "LSE-LK" :date #inst "2026-09-01" :journal (journal db1)
                             :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                             :gain-loss-account (acct db1 "7400")}))))))

;; ============================================================================
;; ADR-070 — disclosure-support deltas persisted on :lease-modification
;; ============================================================================

(deftest remeasure-persists-liability-and-rou-deltas
  (let [conn (bootstrap)
        db   (d/db conn)]
    (lease/define-lease! conn
      {:code "LSE-DR" :name "Office" :lessor (p db "L-acme")
       :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
       :term-months 24 :payment-amount 1000.00M :payment-frequency :monthly
       :payment-timing :in-arrears :commodity (commodity db)
       :discount-rate 0.06M :origin-document (adoc db)
       :changed-by-uid (p db "U-cfo")})
    (lrun/commence! conn
                    {:lease "LSE-DR" :journal (journal db) :changed-by-uid (p db "U-cfo")
                     :rou-asset-account (acct db "0250") :rou-accumulated-account (acct db "0259")
                     :books [{:ledger (ifrs db) :classification :finance
                              :liability-account (acct db "1750")
                              :interest-account (acct db "7300")
                              :rou-expense-account (acct db "6200")}]})
    (run-through! conn "LSE-DR" #inst "2026-07-15")
    (let [db1 (d/db conn)
          result (lmod/remeasure! conn
                                  {:lease "LSE-DR" :date #inst "2026-07-20" :kind :index-reset
                                   :new-payment-amount 1200.00M :journal (journal db1)
                                   :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                                   :gain-loss-account (acct db1 "7400")})
          mod-eid (:modification result)
          db2 (d/db conn)
          m (d/pull db2 [:kontor.lease-modification/liability-delta
                         :kontor.lease-modification/rou-delta
                         :kontor.lease-modification/pnl-delta]
                    mod-eid)]
      (testing "the modification persists the aggregated liability + ROU delta"
        (is (some? (:kontor.lease-modification/liability-delta m)))
        (is (= (:kontor.lease-modification/liability-delta m)
               (:kontor.lease-modification/rou-delta m))
            "remeasure! flows BS-only so liability + ROU deltas match"))
      (testing "remeasure!'s P&L delta is zero in the common case"
        (is (= 0M (:kontor.lease-modification/pnl-delta m)))))))

(deftest terminate-persists-derecognition-deltas
  (let [conn (bootstrap)
        db   (d/db conn)]
    (lease/define-lease! conn
      {:code "LSE-DT" :name "Office" :lessor (p db "L-acme")
       :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
       :term-months 12 :payment-amount 500.00M :payment-frequency :monthly
       :payment-timing :in-arrears :commodity (commodity db)
       :discount-rate 0.06M :origin-document (adoc db)
       :changed-by-uid (p db "U-cfo")})
    (lrun/commence! conn
                    {:lease "LSE-DT" :journal (journal db) :changed-by-uid (p db "U-cfo")
                     :rou-asset-account (acct db "0250") :rou-accumulated-account (acct db "0259")
                     :books [{:ledger (ifrs db) :classification :finance
                              :liability-account (acct db "1750")
                              :interest-account (acct db "7300")
                              :rou-expense-account (acct db "6200")}]})
    (run-through! conn "LSE-DT" #inst "2026-04-15")
    (let [db1 (d/db conn)
          result (lmod/terminate! conn
                                  {:lease "LSE-DT" :date #inst "2026-04-30" :journal (journal db1)
                                   :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                                   :gain-loss-account (acct db1 "7400")})
          mod-eid (:modification result)
          db2 (d/db conn)
          m (d/pull db2 [:kontor.lease-modification/liability-delta
                         :kontor.lease-modification/rou-delta
                         :kontor.lease-modification/pnl-delta]
                    mod-eid)]
      (testing "termination derecognises the full outstanding liability"
        (is (neg? (.signum ^java.math.BigDecimal
                   (:kontor.lease-modification/liability-delta m)))))
      (testing "termination derecognises the full ROU carrying amount"
        (is (neg? (.signum ^java.math.BigDecimal
                   (:kontor.lease-modification/rou-delta m))))))))

(deftest rate-rationale-audit-doc-is-persisted-on-the-liability-book
  (let [conn (bootstrap)
        db   (d/db conn)]
    (lease/define-lease! conn
      {:code "LSE-RR" :name "Office" :lessor (p db "L-acme")
       :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
       :term-months 12 :payment-amount 500.00M :payment-frequency :monthly
       :payment-timing :in-arrears :commodity (commodity db)
       :discount-rate 0.06M :origin-document (adoc db)
       :changed-by-uid (p db "U-cfo")})
    (lrun/commence! conn
                    {:lease "LSE-RR" :journal (journal db) :changed-by-uid (p db "U-cfo")
                     :rou-asset-account (acct db "0250") :rou-accumulated-account (acct db "0259")
                     :books [{:ledger (ifrs db) :classification :finance
                              :liability-account (acct db "1750")
                              :interest-account (acct db "7300")
                              :rou-expense-account (acct db "6200")
                              :rate-rationale (adoc db)}]})
    (let [db1 (d/db conn)
          ifrs-eid (ifrs db1)
          book (liability/book-for db1 "LSE-RR" ifrs-eid)
          b (d/pull db1 [{:kontor.lease-liability/rate-rationale [:kontor.audit-doc/code]}] book)]
      (testing "the :rate-rationale audit-doc ref is persisted on the book"
        (is (= "LEASE-CONTRACT-1"
               (:kontor.audit-doc/code (:kontor.lease-liability/rate-rationale b))))))))

;; ============================================================================
;; ASC 842 operating + :index-reset variable-expense fork
;; ============================================================================

(deftest index-reset-on-asc-842-operating-book-expenses-not-remeasures
  ;; A dual-reporting customer (IFRS parent + US filer subsidiary).
  ;; The lease has both an IFRS 16 :finance book and an ASC 842
  ;; :operating book on the SAME lease. An index reset
  ;; (CPI escalation) should:
  ;;   - IFRS book → remeasure normally (delta ≠ 0)
  ;;   - ASC 842 operating book → expense delta, no remeasurement
  ;; Before the fix this returned wrong numbers on the
  ;; ASC 842 ledger (both books were remeasured identically).
  (let [conn (a-dual-ledger-lease! {:code "LSE-DUAL" :term 24 :payment 1000.00M})
        ;; BOTH books get 6 periods. The old fixture only ever ran the
        ;; IFRS ledger, so the ASC 842 book had nothing fired and the
        ;; drift had nothing to drift from (note 198 HIGH-5).
        _ (run-through! conn "LSE-DUAL" #inst "2026-07-15" (ifrs (d/db conn)))
        _ (run-through! conn "LSE-DUAL" #inst "2026-07-15" (usgaap (d/db conn)))
        db1 (d/db conn)
        ifrs-eid   (ifrs db1)
        usgaap-eid (usgaap db1)
        result (lmod/remeasure! conn
                                {:lease "LSE-DUAL" :date #inst "2026-07-20" :kind :index-reset
                                 :new-payment-amount 1100.00M :journal (journal db1)
                                 :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                                 :gain-loss-account (acct db1 "7400")
                                 :variable-lease-expense-account (acct db1 "7410")
                                 :variable-lease-payable-account (acct db1 "1751")})
        db2 (d/db conn)
        ifrs-book   (first (filter #(= ifrs-eid (:ledger %))   (:books result)))
        usgaap-book (first (filter #(= usgaap-eid (:ledger %)) (:books result)))]
    (testing "the IFRS book remeasured normally (non-zero delta)"
      (is (not (:variable-expense? ifrs-book)))
      (is (not (zero? (.signum ^java.math.BigDecimal (:delta ifrs-book)))))
      (is (not= (:old-outstanding ifrs-book)
                (:new-liability  ifrs-book))))
    (testing "the ASC 842 operating book took the variable-expense fork"
      (is (true? (:variable-expense? usgaap-book)))
      (is (zero? (.signum ^java.math.BigDecimal (:delta usgaap-book)))
          "delta is exactly 0M — no liability movement")
      (is (= (:old-outstanding usgaap-book) (:new-liability usgaap-book))
          "outstanding liability is preserved")
      (is (bd= 1000.00M (:pinned-payment usgaap-book))
          "the book stays measured on the ORIGINAL 1,000 rent")
      (is (bd= 100.00M (:period-delta usgaap-book))
          "period-delta = 1100 − 1000 = 100, EVERY remaining period"))

    ;; ---- the note-198 HIGH-5 regression ----------------------------------
    ;; Hand derivation, ASC 842 operating book, 24 × €1,000 in arrears @ 6%
    ;; (0.5%/period), PV = 1000 × (1 − 1.005⁻²⁴)/0.005 = 22,562.87.
    ;;   p1 int = round2(22,562.87 × .005) = 112.81 → prin  887.19 → 21,675.68
    ;;   p2 int = round2(21,675.68 × .005) = 108.38 → prin  891.62 → 20,784.06
    ;;   p3 int = round2(20,784.06 × .005) = 103.92 → prin  896.08 → 19,887.98
    ;;   p4 int = round2(19,887.98 × .005) =  99.44 → prin  900.56 → 18,987.42
    ;;   p5 int = round2(18,987.42 × .005) =  94.94 → prin  905.06 → 18,082.36
    ;;   p6 int = round2(18,082.36 × .005) =  90.41 → prin  909.59 → 17,172.77
    ;; The index reset posts NOTHING, so GL 1750 stays −17,172.77 and the
    ;; subledger MUST stay 17,172.77. Before the fix the subledger re-planned
    ;; periods 1-6 at the new €1,100 and returned 16,565.22 — a drift of
    ;; 607.55, which is 100 × the 6-period annuity accumulation factor
    ;; ((1.005⁶ − 1)/.005 = 6.0755). It never healed: running to term left
    ;; 1750 stranded at −607.55 while the ROU still netted to zero.
    (testing "the index reset posts no GL entry and does not move the subledger"
      (is (nil? (:transaction usgaap-book))
          "an ASC 842 index reset is not a remeasurement — nothing to post")
      (is (bd= 17172.77M (outstanding db2 "LSE-DUAL" usgaap-eid)))
      (is (bd= -17172.77M (ledger-balance db2 (acct db2 "1750") usgaap-eid)))
      (assert-liability-ties! conn "LSE-DUAL" usgaap-eid "after index reset")
      (is (bd= 0M (ledger-balance db2 (acct db2 "7410") usgaap-eid))
          "variable lease cost is recognised WHEN PAID, not at the reset"))

    (testing "the US-GAAP book runs to term with the liability tied throughout"
      ;; 18 remaining periods × €100 delta = €1,800.00 of variable lease
      ;; cost, and the liability still unwinds on the ORIGINAL payments.
      (run-through! conn "LSE-DUAL" #inst "2028-06-01" usgaap-eid)
      (let [db3 (d/db conn)]
        (is (bd= 0M (outstanding db3 "LSE-DUAL" usgaap-eid)))
        (is (bd= 0M (ledger-balance db3 (acct db3 "1750") usgaap-eid))
            "no stranded control-account balance — this was −607.55")
        (assert-liability-ties! conn "LSE-DUAL" usgaap-eid "US-GAAP at term")
        (is (bd= 1800.00M (ledger-balance db3 (acct db3 "7410") usgaap-eid))
            "18 un-fired periods × (1100 − 1000)")
        ;; cash = 6 × 1000 + 18 × 1100 = 25,800.00, less the 0.01 the
        ;; FINAL period gives back: its liability service is
        ;; interest + remaining balance (999.99, not 1,000.00), which is
        ;; where the whole term's rounding drift is absorbed so the
        ;; liability lands exactly on zero. The €100 variable delta is a
        ;; flat add-on and is unaffected.
        (is (bd= -25799.99M (ledger-balance db3 (acct db3 "1800") usgaap-eid)))
        (is (zero? (.signum (ledger-sum db3 usgaap-eid
                                        ["0250" "0259" "1750" "7300" "6200"
                                         "7400" "1800" "7410" "1751"]))))))

    (testing "the IFRS book still unwinds to zero by end of term"
      ;; Sanity: the IFRS path was unaffected by the fork.
      (run-through! conn "LSE-DUAL" #inst "2028-06-01")
      (let [db3 (d/db conn)]
        (is (bd= 0M (ledger-balance db3 (acct db3 "1750") ifrs-eid)))
        (is (bd= 0M (outstanding db3 "LSE-DUAL" ifrs-eid)))
        (assert-liability-ties! conn "LSE-DUAL" ifrs-eid "IFRS at term")))))

(deftest operating-rou-plug-uses-ITS-OWN-ledgers-liability-book
  ;; note 198 (found while pinning HIGH-5). `kontor.lease.rou-provider`
  ;; destructured `:ledger` out of `kontor.asset.depreciation/
  ;; book-plan-inputs`, which has never returned that key — so the
  ;; ledger was ALWAYS nil, and `liability/book-for` with a nil ledger
  ;; does not narrow the join: `:find ?e .` then returns an arbitrary
  ;; liability book of the lease. One book per lease ⇒ accidentally
  ;; right, which is every pre-existing test. Two books — the ADR-021
  ;; parallel-ledger shape kontor-lease exists for — ⇒ the operating
  ;; ROU plug amortises against the OTHER framework's interest
  ;; schedule, silently.
  (let [conn (a-dual-ledger-lease! {:code "LSE-PLUG" :term 24 :payment 1000.00M})
        db (d/db conn)
        ifrs-eid (ifrs db)
        us-eid (usgaap db)]
    ;; Diverge the two books' plans so picking the wrong one cannot
    ;; coincidentally agree: fire + remeasure ONLY the IFRS book, which
    ;; re-anchors it to :opening-fired-through 6 (its plan then starts
    ;; at period 7 and has no period 1 at all).
    (run-through! conn "LSE-PLUG" #inst "2026-07-15" ifrs-eid)
    (let [db1 (d/db conn)]
      (lmod/remeasure! conn
                       {:lease "LSE-PLUG" :date #inst "2026-07-20" :kind :term-change
                        :new-term-months 24 :new-discount-rate 0.09M :journal (journal db1)
                        :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                        :gain-loss-account (acct db1 "7400")}))
    (let [db2 (d/db conn)]
      (testing "the two books' plans genuinely differ"
        (is (= 6 (:kontor.lease-liability/opening-fired-through
                  (liability/pull-book db2 (liability/book-for db2 "LSE-PLUG" ifrs-eid)))))
        (is (= 0 (:kontor.lease-liability/opening-fired-through
                  (liability/pull-book db2 (liability/book-for db2 "LSE-PLUG" us-eid)))))
        (is (= [7 24] ((juxt first last)
                       (mapv :sequence (:periods (lp/plan-for-book
                                                  db2 (liability/book-for db2 "LSE-PLUG" ifrs-eid))))))))
      (testing "the US-GAAP operating book still runs — on its OWN plan"
        ;; Against the IFRS plan this threw
        ;; :kontor.lease/rou-liability-misaligned on period 1.
        (let [r (run-through! conn "LSE-PLUG" #inst "2028-06-01" us-eid)]
          (is (= 24 (:count (:liability r))))
          (is (= 24 (:count (:rou r))))))
      (testing "and it ties: liability zero, ROU fully amortised"
        (let [db3 (d/db conn)]
          (is (bd= 0M (outstanding db3 "LSE-PLUG" us-eid)))
          (assert-liability-ties! conn "LSE-PLUG" us-eid "US-GAAP at term")
          (is (bd= 0M (.add (ledger-balance db3 (acct db3 "0250") us-eid)
                            (ledger-balance db3 (acct db3 "0259") us-eid)))))))))

(deftest book-for-refuses-a-nil-ledger
  ;; The primitive behind the bug above: without the guard this
  ;; returned an arbitrary book instead of failing.
  (let [conn (a-dual-ledger-lease! {:code "LSE-NL" :term 12 :payment 500.00M})
        db (d/db conn)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":ledger is required"
                          (liability/book-for db "LSE-NL" nil)))))

(deftest index-reset-on-an-asc-842-FINANCE-book-remeasures-normally
  ;; The fork is gated on (framework = :us-gaap AND classification =
  ;; :operating). An ASC 842 FINANCE book is remeasured like any other —
  ;; ASC 842-10-35-4 only exempts operating-lease index resets. This
  ;; combination had NO coverage at all.
  (let [conn (bootstrap)
        db   (d/db conn)]
    (lease/define-lease! conn
      {:code "LSE-USF" :name "US finance" :lessor (p db "L-acme")
       :asset-class (class-eid db) :commencement-date #inst "2026-01-01"
       :term-months 24 :payment-amount 1000.00M :payment-frequency :monthly
       :payment-timing :in-arrears :commodity (commodity db)
       :discount-rate 0.06M :origin-document (adoc db)
       :changed-by-uid (p db "U-cfo")})
    (lrun/commence! conn
                    {:lease "LSE-USF" :journal (journal db) :changed-by-uid (p db "U-cfo")
                     :rou-asset-account (acct db "0250") :rou-accumulated-account (acct db "0259")
                     :books [{:ledger (usgaap db) :classification :finance
                              :liability-account (acct db "1750")
                              :interest-account (acct db "7300")
                              :rou-expense-account (acct db "6200")}]})
    (let [us-eid (usgaap (d/db conn))]
      (run-through! conn "LSE-USF" #inst "2026-07-15" us-eid)   ; fire 6 months
      (let [db1 (d/db conn)
            result (lmod/remeasure! conn
                                    {:lease "LSE-USF" :date #inst "2026-07-20" :kind :index-reset
                                     :new-payment-amount 1100.00M :journal (journal db1)
                                     :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                                     :gain-loss-account (acct db1 "7400")})
            bk (first (:books result))
            db2 (d/db conn)]
        (testing "a us-gaap FINANCE book does NOT take the operating fork"
          (is (nil? (:variable-expense? bk)))
          (is (some? (:transaction bk)) "it posts a real remeasurement entry"))
        (testing "the liability is remeasured at the PV of the revised payments"
          ;; 18 remaining × 1,100 @ 0.5%:
          ;;   annuity factor (1 − 1.005⁻¹⁸)/0.005 = 17.172768... , so
          ;;   PV = 1100 × 17.172768 = 18,890.045 → 18,890.04 HALF-EVEN.
          ;; (Not 1.1 × 17,172.77 = 18,890.05: the 1,000-payment figure
          ;;  is itself already rounded, so scaling it re-rounds a
          ;;  rounded number. The builder discounts at 12dp and rounds
          ;;  once, which is the right order.)
          (is (bd= 17172.77M (:old-outstanding bk)))
          (is (bd= 18890.04M (:new-liability bk)))
          (is (bd= -18890.04M (ledger-balance db2 (acct db2 "1750") us-eid)))
          (assert-liability-ties! conn "LSE-USF" us-eid "after remeasure"))
        (testing "no payment pin and no variable lease cost are created"
          (is (nil? (:kontor.lease-liability/payment-amount
                     (liability/pull-book db2 (liability/book-for db2 "LSE-USF" us-eid)))))
          (is (bd= 0M (ledger-balance db2 (acct db2 "7410") us-eid))))
        (run-through! conn "LSE-USF" #inst "2028-06-01" us-eid)
        (let [db3 (d/db conn)]
          (testing "it unwinds to zero on both sides"
            (is (bd= 0M (outstanding db3 "LSE-USF" us-eid)))
            (assert-liability-ties! conn "LSE-USF" us-eid "at term")))))))

(deftest zero-delta-remeasurement-is-a-clean-re-anchor-not-a-crash
  ;; note 198 MED-3. For a level in-arrears lease `remaining-pv`
  ;; reproduces the unwound balance EXACTLY, so re-measuring at the same
  ;; payment and rate yields a liability leg and a ROU leg of zero — an
  ;; entry with no postings. That used to escape as an opaque
  ;; `build-transaction: input failed structural validation`. It is a
  ;; legitimate re-anchor with nothing to post.
  (let [conn (a-finance-lease! {:code "LSE-ZD" :term 24 :payment 1000.00M})
        _ (run-through! conn "LSE-ZD" #inst "2026-07-15")   ; fire 6 months
        db1 (d/db conn)
        ifrs-eid (ifrs db1)
        book (liability/book-for db1 "LSE-ZD" ifrs-eid)]
    ;; Hand derivation: after 6 periods the unwound balance is 17,172.77
    ;; (see the LSE-DUAL table above — same terms). The revised PV of the
    ;; 18 remaining payments is 1000 × (1 − 1.005⁻¹⁸)/0.005 = 17,172.77.
    ;; Identical ⇒ delta 0.
    (is (bd= 17172.77M (lp/outstanding-liability db1 book)))
    (is (bd= 17172.77M (lease/present-value 1000.00M 0.005M 18 :in-arrears)))
    (let [result (lmod/remeasure! conn
                                  {:lease "LSE-ZD" :date #inst "2026-07-20" :kind :index-reset
                                   :new-payment-amount 1000.00M :journal (journal db1)
                                   :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                                   :gain-loss-account (acct db1 "7400")})
          bk  (first (:books result))
          db2 (d/db conn)]
      (testing "the modification is recorded and the book re-anchored"
        (is (some? (:modification result)))
        (is (bd= 0M (:delta bk)))
        (is (= 6 (:kontor.lease-liability/opening-fired-through
                  (liability/pull-book db2 book)))))
      (testing "no GL entry is produced and none is back-referenced"
        (is (nil? (:transaction bk)))
        (is (empty? (:kontor.lease-modification/transaction
                     (d/pull db2 [:kontor.lease-modification/transaction]
                             (:modification result))))))
      (testing "the subledger is untouched and still ties"
        (is (bd= 17172.77M (outstanding db2 "LSE-ZD" ifrs-eid)))
        (assert-liability-ties! conn "LSE-ZD" ifrs-eid "after zero-delta remeasure"))
      (run-through! conn "LSE-ZD" #inst "2028-06-01")
      (let [db3 (d/db conn)]
        (testing "it still unwinds to zero"
          (is (bd= 0M (outstanding db3 "LSE-ZD" ifrs-eid)))
          (assert-liability-ties! conn "LSE-ZD" ifrs-eid "at term"))))))

(deftest terminate-refuses-to-derecognise-a-liability-the-gl-never-held
  ;; note 198 MED-4. `import-lease!` posts NO day-one GL entry by design
  ;; — the import-day bridge journal is the consumer's. Nothing used to
  ;; stop `terminate!` debiting 1750 for the full imported subledger
  ;; amount, stranding an equal credit on the control account forever.
  (let [conn (bootstrap)
        db   (d/db conn)]
    (lease/define-lease! conn
      {:code "LSE-IMPT" :name "Imported, un-bridged" :lessor (p db "L-acme")
       :asset-class (class-eid db) :commencement-date #inst "2026-05-01"
       :term-months 8 :payment-amount 1000.00M :payment-frequency :monthly
       :payment-timing :in-arrears :commodity (commodity db)
       :discount-rate 0.06M :origin-document (adoc db)
       :imported? true :imported-as-of #inst "2026-05-01"
       :imported-original-commencement-date #inst "2024-01-01"
       :imported-original-term-months 36
       :changed-by-uid (p db "U-cfo")})
    (lrun/import-lease! conn
                        {:lease "LSE-IMPT" :changed-by-uid (p db "U-cfo")
                         :rou-asset-account (acct db "0250")
                         :rou-accumulated-account (acct db "0259")
                         :books [{:ledger (ifrs db) :classification :finance
                                  :liability-account (acct db "1750")
                                  :interest-account (acct db "7300")
                                  :rou-expense-account (acct db "6200")
                                  :remaining-pv 7891.86M
                                  :remaining-rou-base 7304.67M}]})
    (let [db1 (d/db conn)
          ifrs-eid (ifrs db1)]
      (testing "the subledger carries 7,891.86 against an EMPTY control account"
        (is (bd= 7891.86M (outstanding db1 "LSE-IMPT" ifrs-eid)))
        (is (bd= 0M (ledger-balance db1 (acct db1 "1750") ifrs-eid)))
        (let [r (lreport/reconcile-liability
                 conn {:book (liability/book-for db1 "LSE-IMPT" ifrs-eid)})]
          (is (false? (:ok? r)))
          (is (bd= 7891.86M (:difference r)))))
      (testing "terminate! refuses rather than stranding the difference"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"(?i)does not tie to the GL"
             (lmod/terminate! conn
                              {:lease "LSE-IMPT" :date #inst "2026-06-01" :journal (journal db1)
                               :changed-by-uid (p db1 "U-ctrl") :justification (adoc db1)
                               :gain-loss-account (acct db1 "7400")}))))
      (testing ":allow-gl-mismatch? is the explicit, documented override"
        (is (some? (lmod/terminate! conn
                                    {:lease "LSE-IMPT" :date #inst "2026-06-01"
                                     :journal (journal db1) :changed-by-uid (p db1 "U-ctrl")
                                     :justification (adoc db1)
                                     :gain-loss-account (acct db1 "7400")
                                     :allow-gl-mismatch? true})))))))

(deftest index-reset-on-asc-842-operating-book-requires-variable-accounts
  ;; The fork demands :variable-lease-expense-account +
  ;; :variable-lease-payable-account when it fires. Missing them is
  ;; a clear error, not a silently-wrong remeasurement.
  (let [conn (a-dual-ledger-lease! {:code "LSE-DUE" :term 24 :payment 1000.00M})
        _ (run-through! conn "LSE-DUE" #inst "2026-07-15")
        db (d/db conn)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"variable-lease-expense-account"
         (lmod/remeasure! conn
                          {:lease "LSE-DUE" :date #inst "2026-07-20" :kind :index-reset
                           :new-payment-amount 1100.00M :journal (journal db)
                           :changed-by-uid (p db "U-ctrl") :justification (adoc db)
                           :gain-loss-account (acct db "7400")})))))
