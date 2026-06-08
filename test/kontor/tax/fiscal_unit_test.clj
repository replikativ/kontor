(ns kontor.tax.fiscal-unit-test
  "ADR-113 — fiscal-unit substrate.

   Substrate-only tests: schema installs, the closed-enum guards on
   elect-tx-data fire, member queries honour bitemporal windows, the
   status-transition seeds are present, and compose-aggregate-of
   composes elected/separate paths correctly. The DE Organschaft
   pilot (the first consumer end-to-end test) lands in a follow-up."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.tax.fiscal-unit :as fu]
            [kontor.tax.statute :as statute]))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:db/id "e-parent" :kontor.entity/code "P-Holding"
                  :kontor.entity/name "P Holding GmbH"}
                 {:db/id "e-sub1"   :kontor.entity/code "S1-Industries"
                  :kontor.entity/name "S1 Industries GmbH"}
                 {:db/id "e-sub2"   :kontor.entity/code "S2-Logistik"
                  :kontor.entity/name "S2 Logistik GmbH"}
                 {:db/id "e-outsider" :kontor.entity/code "X-External"
                  :kontor.entity/name "X External AG"}
                 {:db/id "doc-eav" :kontor.audit-doc/code "ORGANSCHAFT-EAV-2026"
                  :kontor.audit-doc/type :tax-election
                  :kontor.audit-doc/storage-uri "s3://docs/eav-1"
                  :kontor.audit-doc/uploaded-at #inst "2026-01-01"}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- ent [db code] (ref-eid db :kontor.entity/code code))

;; ============================================================================
;; Schema + status-transition seeds
;; ============================================================================

(deftest schema-installs
  (let [conn (core/create-test-db)
        db   (d/db conn)]
    (testing ":kontor.fiscal-unit/* schema attrs are present"
      (is (some? (d/pull db [:db/ident] :kontor.fiscal-unit/code)))
      (is (some? (d/pull db [:db/ident] :kontor.fiscal-unit/regime)))
      (is (some? (d/pull db [:db/ident] :kontor.fiscal-unit/computation-style)))
      (is (some? (d/pull db [:db/ident] :kontor.fiscal-unit/status))))
    (testing ":kontor.fiscal-unit-member/* schema attrs are present"
      (is (some? (d/pull db [:db/ident] :kontor.fiscal-unit-member/fiscal-unit)))
      (is (some? (d/pull db [:db/ident] :kontor.fiscal-unit-member/entity)))
      (is (some? (d/pull db [:db/ident] :kontor.fiscal-unit-member/joined-on))))
    (testing "tax-elimination attrs on :transaction"
      (is (some? (d/pull db [:db/ident] :kontor.transaction/elimination-style)))
      (is (some? (d/pull db [:db/ident] :kontor.transaction/elimination-reversal-trigger)))
      (is (some? (d/pull db [:db/ident] :kontor.transaction/elimination-components))))))

(deftest status-transitions-seeded
  (let [conn (core/create-test-db)
        db   (d/db conn)
        rows (d/q '[:find [(pull ?t [*]) ...]
                    :where [?t :kontor.status-transition/entity-type :fiscal-unit]]
                  db)]
    (testing "all 6 fiscal-unit transitions present"
      (is (= 6 (count rows))))
    (testing "lifecycle transitions present"
      (let [edges (set (map (juxt :kontor.status-transition/from
                                  :kontor.status-transition/to)
                            rows))]
        (is (contains? edges [:nil :proposed]))
        (is (contains? edges [:proposed :elected]))
        (is (contains? edges [:elected :active]))
        (is (contains? edges [:active :exiting]))
        (is (contains? edges [:exiting :exited]))
        (is (contains? edges [:active :voided-retro]))))))

(deftest install-seeds-is-idempotent
  (let [conn (core/create-test-db)
        before (d/q '[:find (count ?t) .
                      :where [?t :kontor.status-transition/entity-type :fiscal-unit]]
                    (d/db conn))]
    (fu/install-seeds! conn)
    (fu/install-seeds! conn)
    (let [after (d/q '[:find (count ?t) .
                       :where [?t :kontor.status-transition/entity-type :fiscal-unit]]
                     (d/db conn))]
      (is (= before after) "no duplicate rows after re-install"))))

;; ============================================================================
;; elect-tx-data — closed-enum guards
;; ============================================================================

(deftest elect-rejects-unknown-regime
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #":regime must be one of"
       (fu/elect-tx-data
        {:code "TEST-1" :parent-entity 1 :regime :xx-fake
         :computation-style :single-base
         :elected-from #inst "2026-01-01"
         :members [{:entity 2 :role :parent}]}))))

(deftest elect-rejects-unknown-computation-style
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #":computation-style must be one of"
       (fu/elect-tx-data
        {:code "TEST-2" :parent-entity 1 :regime :de-organschaft
         :computation-style :xx-fake
         :elected-from #inst "2026-01-01"
         :members [{:entity 2 :role :parent}]}))))

(deftest elect-rejects-missing-members
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #":members required"
       (fu/elect-tx-data
        {:code "TEST-3" :parent-entity 1 :regime :de-organschaft
         :computation-style :single-base
         :elected-from #inst "2026-01-01"
         :members []}))))

(deftest elect-rejects-bad-member-role
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #":role must be :parent\|:sub"
       (fu/elect-tx-data
        {:code "TEST-4" :parent-entity 1 :regime :de-organschaft
         :computation-style :single-base
         :elected-from #inst "2026-01-01"
         :members [{:entity 2 :role :grandparent}]}))))

;; ============================================================================
;; elect! → members → exit! round-trip
;; ============================================================================

(deftest elect-then-query-members
  (let [conn (bootstrap)
        db   (d/db conn)
        result (fu/elect! conn
                          {:code "DE-Hans-Organschaft-2026"
                           :name "Hans Tech Organschaft"
                           :parent-entity (ent db "P-Holding")
                           :regime :de-organschaft
                           :computation-style :single-base
                           :elected-from #inst "2026-01-01"
                           :minimum-term-ends #inst "2030-12-31"
                           :anchor-document (ref-eid db :kontor.audit-doc/code "ORGANSCHAFT-EAV-2026")
                           :members [{:entity (ent db "P-Holding") :role :parent}
                                     {:entity (ent db "S1-Industries") :role :sub
                                      :ownership-fraction 1M}
                                     {:entity (ent db "S2-Logistik") :role :sub
                                      :ownership-fraction 0.9M}]})
        fu-eid (get-in result [:tempids "fiscal-unit"])
        db2 (d/db conn)]
    (testing "the fiscal-unit row is created with :proposed status"
      (is (some? fu-eid))
      (let [u (d/pull db2 [:kontor.fiscal-unit/code :kontor.fiscal-unit/regime
                           :kontor.fiscal-unit/computation-style
                           :kontor.fiscal-unit/status :kontor.fiscal-unit/active]
                      fu-eid)]
        (is (= "DE-Hans-Organschaft-2026" (:kontor.fiscal-unit/code u)))
        (is (= :de-organschaft (:kontor.fiscal-unit/regime u)))
        (is (= :single-base (:kontor.fiscal-unit/computation-style u)))
        (is (= :proposed (:kontor.fiscal-unit/status u)))
        (is (false? (:kontor.fiscal-unit/active u)))))
    (testing "members returns all 3 at the as-of"
      (is (= 3 (count (fu/members db2 fu-eid :as-of #inst "2026-06-01")))))
    (testing "members :role filter works"
      (is (= 1 (count (fu/members db2 fu-eid :as-of #inst "2026-06-01"
                                  :role :parent))))
      (is (= 2 (count (fu/members db2 fu-eid :as-of #inst "2026-06-01"
                                  :role :sub)))))
    (testing "an entity may belong to multiple fiscal-units"
      ;; sanity: this query returns ONE unit for S1-Industries
      (is (= 1 (count (fu/fiscal-units-of db2 (ent db2 "S1-Industries"))))))
    (testing "outsider belongs to zero fiscal-units"
      (is (= 0 (count (fu/fiscal-units-of db2 (ent db2 "X-External"))))))

    ;; Exit one member mid-year + verify bitemporal-window query.
    (let [s2-member (d/q '[:find ?m .
                           :in $ ?fu ?ent
                           :where
                           [?m :kontor.fiscal-unit-member/fiscal-unit ?fu]
                           [?m :kontor.fiscal-unit-member/entity ?ent]]
                         db2 fu-eid (ent db2 "S2-Logistik"))]
      (fu/exit! conn {:fiscal-unit-member s2-member
                      :exit-date #inst "2026-09-30"})
      (let [db3 (d/db conn)]
        (testing "before exit-date — S2 still a member"
          (is (= 3 (count (fu/members db3 fu-eid :as-of #inst "2026-06-01")))))
        (testing "after exit-date — S2 no longer a member"
          (is (= 2 (count (fu/members db3 fu-eid :as-of #inst "2026-12-01")))))))))

;; ============================================================================
;; compose-aggregate-of (kontor.tax.statute)
;; ============================================================================

(deftest compose-aggregate-of-records-economic-delta
  ;; The canonical DE-Organschaft-shape test from note 189 §5.3:
  ;; group-KSt €237 375 vs separate sum €375 000 → economic delta
  ;; €137 625 (the value of the election).
  (let [elected  {:kind :kst :liability {:amount 237375M :commodity :EUR}}
        separate {:kind :kst-sum-of-separates :liability {:amount 375000M :commodity :EUR}}
        result   (statute/compose-aggregate-of elected separate)]
    (testing "the elected path's structure is preserved"
      (is (= :kst (:kind result)))
      (is (= 237375M (get-in result [:liability :amount]))))
    (testing ":composed-of records both kinds"
      (is (= [:kst :kst-sum-of-separates] (:composed-of result))))
    (testing ":composition.economic-delta = separate − elected (positive when electing saves)"
      (let [c (:composition result)]
        (is (= :aggregate-of (:method c)))
        (is (= :a (:elected c)))
        (is (= 237375M (:elected-liability c)))
        (is (= 375000M (:separate-liability c)))
        (is (= 137625M (:economic-delta c)))))))

(deftest compose-aggregate-of-handles-negative-delta
  ;; Sanity: substrate doesn't enforce that election is rational. If
  ;; electing costs MORE than filing separately, the delta is negative
  ;; and the elected path still prevails (taxpayers may elect for
  ;; non-tax reasons).
  (let [elected  {:kind :kst :liability {:amount 400000M :commodity :EUR}}
        separate {:kind :kst-sum-of-separates :liability {:amount 375000M :commodity :EUR}}
        result   (statute/compose-aggregate-of elected separate)]
    (is (= -25000M (get-in result [:composition :economic-delta])))
    (is (= 400000M (get-in result [:liability :amount])) "elected still prevails")))

(deftest compose-aggregate-of-handles-nil-liabilities
  ;; Both paths nil → both liabilities 0M → delta 0M.
  (let [result (statute/compose-aggregate-of
                {:kind :kst}
                {:kind :kst-sum-of-separates})]
    (is (= 0M (get-in result [:composition :elected-liability])))
    (is (= 0M (get-in result [:composition :separate-liability])))
    (is (= 0M (get-in result [:composition :economic-delta])))))

;; ============================================================================
;; run-group-tax! — v1 stub
;; ============================================================================

(deftest run-group-tax-per-member-with-netting-is-a-stub
  ;; v1 ships only :single-base; :per-member-with-netting (JP, ADR-
  ;; 115) and :loss-surrender (UK, ADR-117) throw until those ADRs
  ;; land. The :single-base happy path is exercised by the DE
  ;; Organschaft pilot tests (modules/l10n-de/.../organschaft_*).
  (let [conn (bootstrap)
        db   (d/db conn)
        ;; Set up a JP-shaped unit so the dispatch fires
        ;; :per-member-with-netting.
        _ (fu/elect! conn
                     {:code "JP-stub-2026"
                      :name "JP stub unit"
                      :parent-entity (ent db "P-Holding")
                      :regime :jp-group-tsuusan
                      :computation-style :per-member-with-netting
                      :elected-from #inst "2026-01-01"
                      :members [{:entity (ent db "P-Holding") :role :parent}]})
        fu-eid (ref-eid (d/db conn) :kontor.fiscal-unit/code "JP-stub-2026")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"not yet implemented"
         (fu/run-group-tax! conn
                            {:fiscal-unit fu-eid
                             :period {:from #inst "2026-01-01"
                                      :to   #inst "2026-12-31"}
                             :provider :stub})))))
