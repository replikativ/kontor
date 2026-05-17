(ns kontor.schedule-test
  "Tests for ADR-032: :schedule entity + occurrence log + :cost-center
   analytic-plan bootstrap. Exercises a typical use case (monthly
   depreciation schedule) to validate the cross-cutting primitive."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.schedule :as schedule]))

;; ============================================================================
;; Bootstrap — :cost-center plan
;; ============================================================================

(deftest cost-center-plan-bootstrapped
  (let [conn (core/create-test-db)
        db (d/db conn)
        plan (d/q '[:find ?e .
                    :where [?e :analytic-plan/code "cost-center"]]
                  db)]
    (is (some? plan))
    (let [pulled (d/pull db '[*] plan)]
      (is (= "cost-center" (:analytic-plan/code pulled)))
      (is (= "Cost centers" (:analytic-plan/name pulled)))
      (is (true? (:analytic-plan/active pulled))))))

(deftest cost-center-plan-idempotent
  (let [conn (core/create-test-db)
        ;; Re-run install-schema: should be a no-op for the plan
        _ (core/install-schema! conn)
        db (d/db conn)
        n (d/q '[:find (count ?e) .
                 :where [?e :analytic-plan/code "cost-center"]]
               db)]
    (is (= 1 n))))

;; ============================================================================
;; Schedule entity
;; ============================================================================

(defn- setup-asset!
  "Plant a synthetic `:asset`-like entity (using :account/path as a
   stand-in since the kernel doesn't define :asset). Returns the eid."
  [conn]
  (d/transact conn
              [{:account/path "Asset:Fixed:Building-100"
                :account/name "Office Building 100"
                :account/type :asset
                :account/active true}])
  (:db/id (d/entity (d/db conn) [:account/path "Asset:Fixed:Building-100"])))

(deftest schedule-basic-crud
  (let [conn (core/create-test-db)
        asset-eid (setup-asset! conn)
        _ (d/transact conn
                      [{:db/id -1 :commodity/symbol "EUR" :commodity/name "Euro"
                        :commodity/precision 2 :commodity/iso-4217 "EUR"}])
        eur (:db/id (d/entity (d/db conn) [:commodity/symbol "EUR"]))
        _ (d/transact conn
                      [{:schedule/code           "bldg-100-dep"
                        :schedule/name           "Building 100 — straight-line 40yr"
                        :schedule/kind           :depreciation
                        :schedule/origin-entity  asset-eid
                        :schedule/start-date     #inst "2026-06-01"
                        :schedule/end-date       #inst "2066-05-31"
                        :schedule/frequency      :monthly
                        :schedule/total-amount   480000.00M
                        :schedule/total-commodity eur
                        :schedule/state          :active
                        :schedule/active         true}])
        db (d/db conn)
        sched (schedule/by-code db "bldg-100-dep")]
    (is (some? sched))
    (is (= "bldg-100-dep" (:schedule/code (d/entity db sched))))
    (is (= :depreciation (:schedule/kind (d/entity db sched))))
    (is (= sched (schedule/resolve-schedule db "bldg-100-dep")))
    (is (= sched (schedule/resolve-schedule db sched)))
    (is (nil? (schedule/resolve-schedule db nil)))))

;; ============================================================================
;; Date arithmetic
;; ============================================================================

(deftest date-of-occurrence-monthly
  (testing "Monthly frequency: occurrence N is start + (N-1) months"
    (is (= #inst "2026-06-01" (schedule/date-of-occurrence
                               #inst "2026-06-01" :monthly 1)))
    (is (= #inst "2026-07-01" (schedule/date-of-occurrence
                               #inst "2026-06-01" :monthly 2)))
    (is (= #inst "2027-06-01" (schedule/date-of-occurrence
                               #inst "2026-06-01" :monthly 13)))))

(deftest date-of-occurrence-quarterly
  (is (= #inst "2026-09-01" (schedule/date-of-occurrence
                             #inst "2026-06-01" :quarterly 2))))

(deftest date-of-occurrence-annual
  (is (= #inst "2030-06-01" (schedule/date-of-occurrence
                             #inst "2026-06-01" :annual 5))))

(deftest date-of-occurrence-rejects-custom
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Custom frequency"
       (schedule/date-of-occurrence #inst "2026-06-01" :custom 1))))

(deftest date-of-occurrence-rejects-zero-or-negative
  (is (thrown? clojure.lang.ExceptionInfo
               (schedule/date-of-occurrence #inst "2026-06-01" :monthly 0))))

;; ============================================================================
;; Occurrence log + idempotency
;; ============================================================================

(defn- minimal-fixture!
  "Plant the minimum to record a real depreciation occurrence."
  [conn]
  (let [asset-eid (setup-asset! conn)
        _ (d/transact conn
                      [{:db/id -1 :commodity/symbol "EUR" :commodity/name "Euro"
                        :commodity/precision 2 :commodity/iso-4217 "EUR"}
                       {:db/id -2 :account/path "Expense:Depreciation"
                        :account/name "Depreciation expense" :account/type :expense
                        :account/active true}
                       {:db/id -3 :account/path "Asset:AccumulatedDepreciation"
                        :account/name "Accumulated depreciation" :account/type :asset
                        :account/active true}
                       {:db/id -4 :journal/code "DEP"
                        :journal/name "Depreciation" :journal/type :general
                        :journal/active true}])
        db (d/db conn)]
    {:asset asset-eid
     :commodity (:db/id (d/entity db [:commodity/symbol "EUR"]))
     :dep-expense (:db/id (d/entity db [:account/path "Expense:Depreciation"]))
     :accum-dep (:db/id (d/entity db [:account/path "Asset:AccumulatedDepreciation"]))
     :journal (:db/id (d/entity db [:journal/code "DEP"]))}))

(defn- dep-tx-data
  "Build a minimal depreciation journal entry. Tempid -1 is the
   :transaction; `:schedule-occurrence/transaction` will point at it.
   Valid-time anchored on the tx via :db.valid/from = date."
  [{:keys [dep-expense accum-dep commodity journal]} amount date]
  [{:db/id "datomic.tx"
    :db.valid/from date
    :db.valid/to #inst "9999-12-31T23:59:59.999-00:00"}
   {:db/id -1
    :transaction/journal journal
    :transaction/effective-date date
    :transaction/narration (str "Depreciation " (.toString ^java.util.Date date))
    :transaction/state :posted
    ;; :transaction/posted-at is required by the state-machine gate
    ;; alongside :state :posted (matches the production builders'
    ;; convention; was missing in this fixture pre-Stage-P).
    :transaction/posted-at date}
   {:db/id -10
    :posting/account dep-expense
    :posting/amount amount
    :posting/commodity commodity
    :posting/transaction -1
    :posting/display-type :product
    :posting/posted-at date}
   {:db/id -11
    :posting/account accum-dep
    :posting/amount (.negate ^java.math.BigDecimal amount)
    :posting/commodity commodity
    :posting/transaction -1
    :posting/display-type :product
    :posting/posted-at date}])

(deftest record-occurrence-creates-occurrence-and-transaction
  (let [conn (core/create-test-db)
        cat (minimal-fixture! conn)
        _ (d/transact conn
                      [{:schedule/code "bldg-dep"
                        :schedule/name "Building dep"
                        :schedule/kind :depreciation
                        :schedule/origin-entity (:asset cat)
                        :schedule/start-date #inst "2026-06-01"
                        :schedule/frequency :monthly
                        :schedule/total-amount 480000.00M
                        :schedule/total-commodity (:commodity cat)
                        :schedule/state :active
                        :schedule/active true}])
        sched (schedule/by-code (d/db conn) "bldg-dep")
        amount 1000.00M
        date #inst "2026-06-30"
        _ (schedule/record-occurrence! conn "bldg-dep" 1 date amount (:commodity cat)
                                       (dep-tx-data cat amount date))
        db (d/db conn)
        occ (d/q '[:find ?o .
                   :in $ ?s
                   :where
                   [?o :schedule-occurrence/schedule ?s]
                   [?o :schedule-occurrence/sequence 1]]
                 db sched)]
    (is (some? occ))
    (let [pulled (d/pull db '[*] occ)]
      (is (= 1 (:schedule-occurrence/sequence pulled)))
      (is (= date (:schedule-occurrence/scheduled-date pulled)))
      (is (= 0 (.compareTo (bigdec "1000.00")
                           (:schedule-occurrence/amount pulled))))
      (is (some? (:schedule-occurrence/transaction pulled))
          "Occurrence references the kernel transaction it produced"))))

(deftest record-occurrence-is-idempotent
  (testing "Re-recording (schedule, sequence) collapses via composite identity"
    (let [conn (core/create-test-db)
          cat (minimal-fixture! conn)
          _ (d/transact conn
                        [{:schedule/code "bldg-dep"
                          :schedule/name "Building dep"
                          :schedule/kind :depreciation
                          :schedule/origin-entity (:asset cat)
                          :schedule/start-date #inst "2026-06-01"
                          :schedule/frequency :monthly
                          :schedule/state :active
                          :schedule/active true}])
          amount 1000.00M
          date #inst "2026-06-30"
          _ (schedule/record-occurrence! conn "bldg-dep" 1 date amount (:commodity cat)
                                         (dep-tx-data cat amount date))
          _ (schedule/record-occurrence! conn "bldg-dep" 1 date amount (:commodity cat)
                                         (dep-tx-data cat amount date))
          db (d/db conn)
          n (d/q '[:find (count ?o) .
                   :where [?o :schedule-occurrence/sequence 1]]
                 db)]
      (is (= 1 n)
          "Composite identity collapses duplicates"))))

(deftest fired-and-pending
  (testing "fired-sequences + next-pending-sequence + pending-occurrences"
    (let [conn (core/create-test-db)
          cat (minimal-fixture! conn)
          _ (d/transact conn
                        [{:schedule/code "bldg-dep"
                          :schedule/name "Building dep"
                          :schedule/kind :depreciation
                          :schedule/origin-entity (:asset cat)
                          :schedule/start-date #inst "2026-06-01"
                          :schedule/frequency :monthly
                          :schedule/state :active
                          :schedule/active true}])
          sched (schedule/by-code (d/db conn) "bldg-dep")
          ;; Fire 3 monthly occurrences
          _ (doseq [n [1 2 3]]
              (let [date (schedule/date-of-occurrence #inst "2026-06-01" :monthly n)]
                (schedule/record-occurrence! conn "bldg-dep" n date 1000.00M
                                             (:commodity cat)
                                             (dep-tx-data cat 1000.00M date))))
          db (d/db conn)]
      (is (= #{1 2 3} (schedule/fired-sequences db sched)))
      (is (= 3 (schedule/last-fired-sequence db sched)))
      (is (= 4 (schedule/next-pending-sequence db sched)))
      (testing "pending as of 2026-09-15 → occurrences 4 (Sept) due"
        (let [pending (schedule/pending-occurrences
                       db sched #inst "2026-09-15")]
          (is (= [4] (mapv :sequence pending)))
          (is (= [#inst "2026-09-01"] (mapv :date pending)))))
      (testing "pending as of 2026-12-15 → occurrences 4, 5, 6, 7"
        (let [pending (schedule/pending-occurrences
                       db sched #inst "2026-12-15")]
          (is (= [4 5 6 7] (mapv :sequence pending))))))))

(deftest pending-respects-state
  (testing ":paused / :cancelled schedules return empty pending list"
    (let [conn (core/create-test-db)
          cat (minimal-fixture! conn)
          _ (d/transact conn
                        [{:schedule/code "bldg-dep"
                          :schedule/name "Building dep"
                          :schedule/kind :depreciation
                          :schedule/origin-entity (:asset cat)
                          :schedule/start-date #inst "2026-06-01"
                          :schedule/frequency :monthly
                          :schedule/state :paused
                          :schedule/active true}])
          sched (schedule/by-code (d/db conn) "bldg-dep")
          pending (schedule/pending-occurrences
                   (d/db conn) sched #inst "2026-12-15")]
      (is (empty? pending)
          "Paused schedules return no pending occurrences"))))

(deftest pending-respects-end-date
  (testing "Occurrences past :schedule/end-date are excluded"
    (let [conn (core/create-test-db)
          cat (minimal-fixture! conn)
          _ (d/transact conn
                        [{:schedule/code "short-dep"
                          :schedule/name "3-month dep"
                          :schedule/kind :depreciation
                          :schedule/origin-entity (:asset cat)
                          :schedule/start-date #inst "2026-06-01"
                          :schedule/end-date   #inst "2026-08-31"
                          :schedule/frequency :monthly
                          :schedule/state :active
                          :schedule/active true}])
          sched (schedule/by-code (d/db conn) "short-dep")
          pending (schedule/pending-occurrences
                   (d/db conn) sched #inst "2027-01-01")]
      (is (= [1 2 3] (mapv :sequence pending))
          "Only 3 occurrences fit between Jun and Aug 2026"))))

(deftest lifecycle-transitions
  (let [conn (core/create-test-db)
        cat (minimal-fixture! conn)
        _ (d/transact conn
                      [{:schedule/code "lf-dep"
                        :schedule/name "Lifecycle dep"
                        :schedule/kind :depreciation
                        :schedule/origin-entity (:asset cat)
                        :schedule/start-date #inst "2026-06-01"
                        :schedule/frequency :monthly
                        :schedule/state :active
                        :schedule/active true}])
        state #(:schedule/state (d/entity (d/db conn)
                                          (schedule/by-code (d/db conn) "lf-dep")))]
    (is (= :active (state)))
    (schedule/mark-paused! conn "lf-dep")
    (is (= :paused (state)))
    (schedule/mark-completed! conn "lf-dep")
    (is (= :completed (state)))
    (schedule/mark-cancelled! conn "lf-dep")
    (is (= :cancelled (state)))))
