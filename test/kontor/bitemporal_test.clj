(ns kontor.bitemporal-test
  "Tests for kontor.bitemporal — tx-meta valid-time + read-time
   resolver. Matches XTDB v2 polygon semantics on stock datahike."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.core :as core]))

(def ^:dynamic *conn* nil)

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (d/transact *conn* kbt/schema)
    (d/transact *conn*
                [{:db/ident :invoice/code
                  :db/valueType :db.type/string
                  :db/cardinality :db.cardinality/one
                  :db/unique :db.unique/identity}
                 {:db/ident :invoice/status
                  :db/valueType :db.type/keyword
                  :db/cardinality :db.cardinality/one}
                 {:db/ident :invoice/amount
                  :db/valueType :db.type/bigdec
                  :db/cardinality :db.cardinality/one}])
    (f)))

(use-fixtures :each bootstrap)

(defn- inv [code]
  (d/q '[:find ?e .
         :in $ ?c
         :where [?e :invoice/code ?c]]
       (d/db *conn*) code))

;; ============================================================================
;; Single-attribute polygon
;; ============================================================================

(deftest single-attr-real-time-write
  (testing "Real-time write — no tx-meta — vt defaults to :db/txInstant"
    (d/transact *conn* [{:invoice/code "A" :invoice/amount 100M}])
    (let [eid (inv "A")
          now (java.util.Date.)]
      (testing "Value at now"
        (is (= 100M (kbt/value-at (d/db *conn*) eid :invoice/amount now))))
      (testing "Value at far past"
        (is (nil? (kbt/value-at (d/db *conn*) eid :invoice/amount
                                 #inst "2020-01-01")))))))

(deftest single-attr-backdated-correction
  (testing "Backdated correction shadows the original assertion at later vt"
    (d/transact *conn* [{:invoice/code "B" :invoice/amount 100M}])
    (Thread/sleep 5)
    (d/transact *conn* (kbt/with-vt [{:invoice/code "B" :invoice/amount 250M}]
                                    #inst "2026-03-15"))
    (let [eid (inv "B")]
      (testing "Before correction's vf — value nil"
        (is (nil? (kbt/value-at (d/db *conn*) eid :invoice/amount
                                 #inst "2026-02-01")))
        (is (nil? (kbt/value-at (d/db *conn*) eid :invoice/amount
                                 #inst "2026-03-14")))) ;; one day before
      (testing "After correction's vf — corrected value wins"
        (is (= 250M (kbt/value-at (d/db *conn*) eid :invoice/amount
                                   #inst "2026-03-15"))) ;; exact start (inclusive)
        (is (= 250M (kbt/value-at (d/db *conn*) eid :invoice/amount
                                   #inst "2026-04-01"))))
      (testing "After original's tx-time — correction STILL wins (later tx)"
        (is (= 250M (kbt/value-at (d/db *conn*) eid :invoice/amount
                                   #inst "2030-01-01"))))) ))

(deftest closed-interval-correction
  (testing ":tx/valid-to closes the interval — the prior assertion
            resurfaces past vt-to"
    (d/transact *conn* (kbt/with-vt [{:invoice/code "C" :invoice/amount 50M}]
                                    #inst "2026-01-01"))
    (Thread/sleep 5)
    (d/transact *conn* (kbt/with-vt [{:invoice/code "C" :invoice/amount 999M}]
                                    #inst "2026-03-01"
                                    #inst "2026-04-30"))
    (let [eid (inv "C")]
      (testing "Before window — background value"
        (is (= 50M (kbt/value-at (d/db *conn*) eid :invoice/amount
                                  #inst "2026-02-15")))) ;; 50 is current; 999's vf>cutoff
      (testing "Inside window — corrected value"
        (is (= 999M (kbt/value-at (d/db *conn*) eid :invoice/amount
                                   #inst "2026-03-15"))))
      (testing "At vt-to exactly — exclusive; background resurfaces"
        (is (= 50M (kbt/value-at (d/db *conn*) eid :invoice/amount
                                  #inst "2026-04-30"))))
      (testing "After window — background wins"
        (is (= 50M (kbt/value-at (d/db *conn*) eid :invoice/amount
                                  #inst "2026-05-15"))))) ))

;; ============================================================================
;; Per-attribute resolution
;; ============================================================================

(deftest per-attribute-resolution
  (testing "Each attribute's history is independent"
    (d/transact *conn* [{:invoice/code "D"
                         :invoice/status :sent
                         :invoice/amount 100M}])
    (Thread/sleep 5)
    ;; Correct only status, backdated
    (d/transact *conn* (kbt/with-vt [{:invoice/code "D" :invoice/status :draft}]
                                    #inst "2026-03-01"))
    (Thread/sleep 5)
    ;; Correct only amount, backdated to a different vt
    (d/transact *conn* (kbt/with-vt [{:invoice/code "D" :invoice/amount 150M}]
                                    #inst "2026-04-15"))
    (let [eid (inv "D")]
      (testing "Feb 15 — neither correction applies"
        (is (nil? (kbt/value-at (d/db *conn*) eid :invoice/status
                                  #inst "2026-02-15")))
        (is (nil? (kbt/value-at (d/db *conn*) eid :invoice/amount
                                  #inst "2026-02-15"))))
      (testing "Mar 15 — only status correction in effect"
        (is (= :draft (kbt/value-at (d/db *conn*) eid :invoice/status
                                      #inst "2026-03-15"))))
      (testing "Apr 20 — both corrections in effect, independently"
        (is (= :draft (kbt/value-at (d/db *conn*) eid :invoice/status
                                      #inst "2026-04-20")))
        (is (= 150M (kbt/value-at (d/db *conn*) eid :invoice/amount
                                    #inst "2026-04-20"))))) ))

;; ============================================================================
;; Bitemporal (vt + tx)
;; ============================================================================

(deftest bitemporal-time-travel
  (testing "Compose d/as-of (tx-time) with value-at (valid-time)"
    (d/transact *conn* (kbt/with-vt [{:invoice/code "E" :invoice/amount 100M}]
                                    #inst "2026-01-01"))
    (Thread/sleep 5)
    (let [tx1-id (d/q '[:find ?tx . :where [?tx :tx/valid-from #inst "2026-01-01"]]
                       (d/history (d/db *conn*)))]
      ;; Now correct, after tx1
      (d/transact *conn* (kbt/with-vt [{:invoice/code "E" :invoice/amount 999M}]
                                      #inst "2026-03-01"))
      (let [eid (inv "E")
            now (d/db *conn*)
            then (d/as-of now tx1-id)]
        (testing "At current tx — Mar 15 sees the correction"
          (is (= 999M (kbt/value-at now eid :invoice/amount
                                      #inst "2026-03-15"))))
        (testing "At tx1 — Mar 15 sees only the original (no correction yet)"
          (is (= 100M (kbt/value-at then eid :invoice/amount
                                      #inst "2026-03-15"))))))))

;; ============================================================================
;; Timeline + values-between
;; ============================================================================

(deftest timeline-orders-by-vt
  (d/transact *conn* (kbt/with-vt [{:invoice/code "T" :invoice/amount 100M}]
                                  #inst "2026-01-01"))
  (Thread/sleep 5)
  (d/transact *conn* (kbt/with-vt [{:invoice/code "T" :invoice/amount 200M}]
                                  #inst "2026-02-01"))
  (Thread/sleep 5)
  (d/transact *conn* (kbt/with-vt [{:invoice/code "T" :invoice/amount 300M}]
                                  #inst "2026-03-01"))
  (let [eid (inv "T")
        tl (kbt/timeline (d/db *conn*) eid :invoice/amount)]
    (testing "3 assertions in ascending vt order"
      (is (= 3 (count tl)))
      (is (= [100M 200M 300M] (mapv :value tl))))))

(deftest values-between-window
  (d/transact *conn* (kbt/with-vt [{:invoice/code "W" :invoice/amount 100M}]
                                  #inst "2026-01-01"))
  (Thread/sleep 5)
  (d/transact *conn* (kbt/with-vt [{:invoice/code "W" :invoice/amount 200M}]
                                  #inst "2026-02-01"))
  (Thread/sleep 5)
  (d/transact *conn* (kbt/with-vt [{:invoice/code "W" :invoice/amount 300M}]
                                  #inst "2026-03-01"
                                  #inst "2026-03-31"))
  (let [eid (inv "W")]
    (testing "Window [Feb 15, Mar 15) catches the Feb 1 and Mar 1 assertions"
      (let [vs (kbt/values-between (d/db *conn*) eid :invoice/amount
                                    #inst "2026-02-15" #inst "2026-03-15")]
        (is (= #{200M 300M} (set (map :value vs))))))))

;; ============================================================================
;; Period predicates
;; ============================================================================

(deftest period-predicates
  (let [a {:from #inst "2026-01-01" :to #inst "2026-03-01"}
        b {:from #inst "2026-02-01" :to #inst "2026-04-01"}
        c {:from #inst "2026-03-01" :to #inst "2026-05-01"}  ;; meets a
        d {:from #inst "2026-04-01" :to #inst "2026-06-01"}  ;; after b
        e {:from #inst "2026-01-15" :to #inst "2026-02-15"}] ;; contained in a
    (testing "overlaps"
      (is (kbt/vt-overlaps? a b))
      (is (not (kbt/vt-overlaps? a d))))
    (testing "contains"
      (is (kbt/vt-contains? a e))
      (is (not (kbt/vt-contains? e a))))
    (testing "precedes / immediately-precedes"
      (is (kbt/vt-precedes? a c)) ;; touches but half-open → precedes
      (is (kbt/vt-immediately-precedes? a c))
      (is (kbt/vt-strictly-precedes? a d)))))
