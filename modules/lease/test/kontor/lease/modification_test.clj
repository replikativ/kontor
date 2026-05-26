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
     P&L, cancels both schedules, and drives :lease/status →
     :terminated.
   - purchase! — exercising the purchase option settles the remaining
     liability in cash and drives :lease/status → :purchased.
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
            [kontor.lease.runner :as lrun]
            [kontor.lease.schema :as lease-schema]
            [kontor.schedule :as schedule]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (asset-schema/install! conn)
    (lease-schema/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:partner/external-id "U-cfo"  :partner/name "CFO"}
                 {:partner/external-id "U-ctrl" :partner/name "Controller"}
                 {:partner/external-id "L-acme" :partner/name "Acme Properties"}
                 {:db/id "led-ifrs" :ledger/code "ifrs" :ledger/name "IFRS 16"
                  :ledger/framework :ifrs}
                 {:db/id "class-rou" :asset-class/code "rou-property"
                  :asset-class/name "Right-of-Use — Property"}
                 {:db/id "doc-lease" :audit-doc/code "LEASE-CONTRACT-1"
                  :audit-doc/type :lease-contract
                  :audit-doc/storage-uri "s3://docs/lease-1"
                  :audit-doc/uploaded-at #inst "2026-01-01"}
                 {:db/id "a-rou"    :account/code "0250" :account/name "ROU Asset"
                  :account/type :asset :account/active true}
                 {:db/id "a-rouacc" :account/code "0259"
                  :account/name "ROU Accumulated Amortisation"
                  :account/type :asset :account/active true}
                 {:db/id "a-liab"   :account/code "1750"
                  :account/name "Lease Liability"
                  :account/type :liability :account/active true}
                 {:db/id "a-int"    :account/code "7300"
                  :account/name "Interest Expense"
                  :account/type :expense :account/active true}
                 {:db/id "a-dep"    :account/code "6200"
                  :account/name "Depreciation Expense"
                  :account/type :expense :account/active true}
                 {:db/id "a-gl"     :account/code "7400"
                  :account/name "Lease Modification Gain/Loss"
                  :account/type :expense :account/active true}
                 {:db/id "a-cash"   :account/code "1800" :account/name "Bank"
                  :account/type :asset :account/active true}
                 {:db/id "j-gen" :journal/code "GEN" :journal/name "General"
                  :journal/type :general}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- commodity [db] (ref-eid db :kontor.commodity/symbol "EUR"))
(defn- p         [db code] (ref-eid db :partner/external-id code))
(defn- acct      [db code] (ref-eid db :account/code code))
(defn- journal   [db] (ref-eid db :journal/code "GEN"))
(defn- class-eid [db] (ref-eid db :asset-class/code "rou-property"))
(defn- adoc      [db] (ref-eid db :audit-doc/code "LEASE-CONTRACT-1"))
(defn- ifrs      [db] (ref-eid db :ledger/code "ifrs"))

(defn- ledger-balance [db account ledger-eid]
  (or (d/q '[:find (sum ?amt) .
             :with ?p
             :in $ ?acct ?led
             :where
             [?p :posting/account ?acct]
             [?p :posting/ledger ?led]
             [?p :posting/amount ?amt]
             [?p :posting/transaction ?tx]
             [?tx :transaction/state :posted]]
           db account ledger-eid)
      0M))

(defn- ledger-sum [db ledger-eid codes]
  (reduce (fn [a code] (.add a (ledger-balance db (acct db code) ledger-eid)))
          0M codes))

(def ^:private gl-codes ["0250" "0259" "1750" "7300" "6200" "7400" "1800"])

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

(defn- run-through! [conn code as-of]
  (lrun/run-lease! conn
    {:lease code :ledger (ifrs (d/db conn)) :journal (journal (d/db conn))
     :cash-account (acct (d/db conn) "1800")
     :changed-by-uid (p (d/db conn) "U-cfo") :as-of as-of}))

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
        (is (= 1200.00M (:lease/payment-amount (lease/pull-lease db2 "LSE-RM"))))
        (is (some? (:modification result)))
        (is (= :index-reset
               (:lease-modification/kind
                (d/pull db2 [:lease-modification/kind] (:modification result))))))
      (testing "the book is re-anchored: opening-fired-through = the fired count"
        (let [b (liability/pull-book db2 book)]
          (is (= 6 (:lease-liability/opening-fired-through b)))
          (is (= (:new-liability (first (:books result)))
                 (:lease-liability/opening-liability b)))))
      (testing "the remeasurement adjustment is tagged with the book's ledger and balances"
        (is (zero? (.signum (ledger-sum db2 ifrs-eid gl-codes)))))
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
        (testing "the GL still balances and the lease is :expired"
          (is (zero? (.signum (ledger-sum db3 ifrs-eid gl-codes))))
          (is (= :expired (:lease/status (lease/pull-lease db3 "LSE-RM"))))))
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
      (is (= :terminated (:lease/status (lease/pull-lease db2 "LSE-T")))))
    (testing "the liability and the ROU asset are derecognised — both land on zero"
      (is (= 0.00M (ledger-balance db2 (acct db2 "1750") ifrs-eid)))
      (is (= 0.00M (.add (ledger-balance db2 (acct db2 "0250") ifrs-eid)
                         (ledger-balance db2 (acct db2 "0259") ifrs-eid)))))
    (testing "both schedules are cancelled"
      (let [b (liability/pull-book db2 book)]
        (is (= :cancelled (:schedule/state (:lease-liability/schedule b))))))
    (testing "the GL balances (penalty + gain/loss included)"
      (is (zero? (.signum (ledger-sum db2 ifrs-eid gl-codes)))))
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
      (is (= :purchased (:lease/status (lease/pull-lease db2 "LSE-P")))))
    (testing "the remaining liability is settled — it lands on zero"
      (is (= 0.00M (ledger-balance db2 (acct db2 "1750") ifrs-eid))))
    (testing "the GL balances"
      (is (zero? (.signum (ledger-sum db2 ifrs-eid gl-codes)))))
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
    (testing "the partial-termination adjustment balances"
      (is (zero? (.signum (ledger-sum db2 ifrs-eid gl-codes)))))
    (testing "a :partial-termination event records the scope decrease"
      (is (= 0.40M
             (:lease-modification/scope-decrease-pct
              (d/pull db2 [:lease-modification/scope-decrease-pct]
                      (:modification result))))))
    (run-through! conn "LSE-PT" #inst "2028-06-01")        ; fire to end of term
    (let [db3 (d/db conn)]
      (testing "the reduced lease still unwinds to exactly zero"
        (is (= 0.00M (ledger-balance db3 (acct db3 "1750") ifrs-eid)))
        (is (= 0.00M (.add (ledger-balance db3 (acct db3 "0250") ifrs-eid)
                           (ledger-balance db3 (acct db3 "0259") ifrs-eid)))))
      (testing "the GL balances end-to-end and the lease is :expired"
        (is (zero? (.signum (ledger-sum db3 ifrs-eid gl-codes))))
        (is (= :expired (:lease/status (lease/pull-lease db3 "LSE-PT"))))))))

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
        (is (= 4 (:lease-liability/opening-fired-through b))))
      (testing ":opening-liability is set to the new anchor"
        (is (= 4000.00M (:lease-liability/opening-liability b))))
      (testing "fired occurrences are untouched"
        (is (= 4 (count (schedule/fired-sequences
                         (d/db conn)
                         (:db/id (:lease-liability/schedule b))))))))))

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
    (testing "the operating-lease remeasurement adjustment balances"
      (is (some? (:modification result)))
      (is (zero? (.signum (ledger-sum db2 ifrs-eid gl-codes)))))
    (run-through! conn "LSE-OPR" #inst "2028-06-01")        ; fire to end of term
    (let [db3 (d/db conn)]
      (testing "the operating lease still unwinds the liability + ROU to zero"
        (is (= 0.00M (ledger-balance db3 (acct db3 "1750") ifrs-eid)))
        (is (= 0.00M (.add (ledger-balance db3 (acct db3 "0250") ifrs-eid)
                           (ledger-balance db3 (acct db3 "0259") ifrs-eid)))))
      (testing "the GL balances and the lease is :expired"
        (is (zero? (.signum (ledger-sum db3 ifrs-eid gl-codes))))
        (is (= :expired (:lease/status (lease/pull-lease db3 "LSE-OPR"))))))))

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
      (is (= 24 (:lease/term-months (lease/pull-lease db2 "LSE-TX")))))
    (testing "the term extension raises the liability and the adjustment balances"
      (is (pos? (.compareTo (:new-liability (first (:books result)))
                            (:old-outstanding (first (:books result))))))
      (is (zero? (.signum (ledger-sum db2 ifrs-eid gl-codes)))))
    (run-through! conn "LSE-TX" #inst "2029-06-01")         ; fire all 24 months
    (let [db3 (d/db conn)]
      (testing "all 24 periods fire and the extended lease unwinds to zero"
        (is (= 24 (count (schedule/fired-sequences
                          db3 (:db/id (:lease-liability/schedule
                                       (liability/pull-book db3 book)))))))
        (is (= 0.00M (ledger-balance db3 (acct db3 "1750") ifrs-eid)))
        (is (= 0.00M (.add (ledger-balance db3 (acct db3 "0250") ifrs-eid)
                           (ledger-balance db3 (acct db3 "0259") ifrs-eid))))
        (is (zero? (.signum (ledger-sum db3 ifrs-eid gl-codes))))))))

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
      (is (= :terminated (:lease/status (lease/pull-lease db3 "LSE-RT"))))
      (is (= 0.00M (ledger-balance db3 (acct db3 "1750") ifrs-eid)))
      (is (= 0.00M (.add (ledger-balance db3 (acct db3 "0250") ifrs-eid)
                         (ledger-balance db3 (acct db3 "0259") ifrs-eid))))
      (is (zero? (.signum (ledger-sum db3 ifrs-eid gl-codes))))
      (is (pos? (.signum (:derecognised-liability (first (:books result)))))))))

;; ============================================================================
;; Review-after coverage — period-lock enforcement on modifications
;; ============================================================================

(deftest modifications-refuse-to-post-into-a-locked-period
  (let [conn (a-finance-lease! {:code "LSE-LK" :term 24 :payment 1000.00M})
        _ (run-through! conn "LSE-LK" #inst "2026-07-15")   ; fire 6 months
        db1 (d/db conn)]
    ;; Soft-close 2026 — a 2026-dated GL posting must now be refused.
    (d/transact conn [{:period/start #inst "2026-01-01"
                       :period/end #inst "2027-01-01"
                       :period/locked-at #inst "2027-01-15"}])
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
          m (d/pull db2 [:lease-modification/liability-delta
                         :lease-modification/rou-delta
                         :lease-modification/pnl-delta]
                    mod-eid)]
      (testing "the modification persists the aggregated liability + ROU delta"
        (is (some? (:lease-modification/liability-delta m)))
        (is (= (:lease-modification/liability-delta m)
               (:lease-modification/rou-delta m))
            "remeasure! flows BS-only so liability + ROU deltas match"))
      (testing "remeasure!'s P&L delta is zero in the common case"
        (is (= 0M (:lease-modification/pnl-delta m)))))))

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
          m (d/pull db2 [:lease-modification/liability-delta
                         :lease-modification/rou-delta
                         :lease-modification/pnl-delta]
                    mod-eid)]
      (testing "termination derecognises the full outstanding liability"
        (is (neg? (.signum ^java.math.BigDecimal
                           (:lease-modification/liability-delta m)))))
      (testing "termination derecognises the full ROU carrying amount"
        (is (neg? (.signum ^java.math.BigDecimal
                           (:lease-modification/rou-delta m))))))))

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
          b (d/pull db1 [{:lease-liability/rate-rationale [:audit-doc/code]}] book)]
      (testing "the :rate-rationale audit-doc ref is persisted on the book"
        (is (= "LEASE-CONTRACT-1"
               (:audit-doc/code (:lease-liability/rate-rationale b))))))))
