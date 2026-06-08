(ns kontor.bitemporal-test
  "Tests for the kontor.bitemporal write-side helpers — `with-vt`,
   `strip-tx-meta`, `forever`, and the `close-validity` family."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.core :as core]
            [kontor.validation :as v]))

(def jan-2 #inst "2026-01-02T00:00:00Z")
(def jan-15 #inst "2026-01-15T00:00:00Z")
(def jan-31 #inst "2026-01-31T00:00:00Z")
(def feb-1 #inst "2026-02-01T00:00:00Z")
(def feb-15 #inst "2026-02-15T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (d/transact conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}])
    conn))

(defn- post-tx-with-vt!
  "Post a trivial tx with the given vt-from and (optional) vt-to.
   Returns the resulting tx-eid via `kbt/commit-tx-eid`."
  [conn vt-from & [vt-to]]
  (let [tx-meta (cond-> {:db.valid/from vt-from}
                  vt-to (assoc :db.valid/to vt-to))
        r (d/transact conn {:tx-data [{:kontor.commodity/symbol (str "X-" (random-uuid))
                                       :kontor.commodity/precision 2}]
                            :tx-meta tx-meta})]
    (kbt/commit-tx-eid r)))

;; ============================================================================
;; with-vt + strip-tx-meta (existing surface)
;; ============================================================================

(deftest with-vt-appends-tx-meta-map
  (let [tx-data [{:kontor.commodity/symbol "EUR"}]
        result (kbt/with-vt tx-data jan-2)]
    (is (= 2 (count result)))
    (is (= {:db/id "datomic.tx" :db.valid/from jan-2}
           (last result)))))

(deftest with-vt-3-arity-appends-both-bounds
  (let [tx-data [{:kontor.commodity/symbol "EUR"}]
        result (kbt/with-vt tx-data jan-2 feb-1)]
    (is (= {:db/id "datomic.tx" :db.valid/from jan-2 :db.valid/to feb-1}
           (last result)))))

(deftest with-vt-replaces-prior-tx-meta
  (let [tx-data [{:kontor.commodity/symbol "EUR"}
                 {:db/id "datomic.tx" :db.valid/from jan-15}]
        result (kbt/with-vt tx-data jan-2)
        tx-metas (filter #(and (map? %) (= "datomic.tx" (:db/id %))) result)]
    (is (= 1 (count tx-metas))
        "old tx-meta is stripped, new one appended")
    (is (= jan-2 (:db.valid/from (first tx-metas))))))

;; ============================================================================
;; close-validity-tx-data — pure builder
;; ============================================================================

(deftest close-validity-tx-data-builds-the-datom
  (let [tx-data (kbt/close-validity-tx-data 12345 feb-1)]
    (is (= [{:db/id 12345 :db.valid/to feb-1}]
           tx-data))))

;; ============================================================================
;; commit-tx-eid — extract the datahike commit-tx eid from a tx-report
;; ============================================================================

(deftest commit-tx-eid-extracts-the-tx-entity-from-a-tx-report
  (let [conn (bootstrap)
        report (d/transact
                conn
                {:tx-data [{:kontor.commodity/symbol "TEST-COMMIT-TX" :kontor.commodity/precision 2}]
                 :tx-meta {:db.valid/from jan-2}})
        tx-eid (kbt/commit-tx-eid report)]
    (testing "returns a number (eid)"
      (is (number? tx-eid)))
    (testing "the eid carries :db/txInstant (it IS the commit tx)"
      (let [db (d/db conn)
            inst (d/q '[:find ?inst .
                        :in $ ?tx
                        :where [?tx :db/txInstant ?inst]]
                      db tx-eid)]
        (is (some? inst))))
    (testing "the eid carries the :db.valid/from we passed in tx-meta"
      (let [db (d/db conn)
            vf (d/q '[:find ?vf .
                      :in $ ?tx
                      :where [?tx :db.valid/from ?vf]]
                    db tx-eid)]
        (is (= jan-2 vf))))
    (testing "the eid is NOT the business entity's eid"
      (let [db (d/db conn)
            biz-eid (d/q '[:find ?e .
                           :in $ ?sym
                           :where [?e :kontor.commodity/symbol ?sym]]
                         db "TEST-COMMIT-TX")]
        (is (not= biz-eid tx-eid)
            "commit-tx eid differs from any business entity in the tx")))))

(deftest commit-tx-eid-composes-cleanly-with-close-validity!
  (testing "The canonical recipe: extract from report, then close later."
    (let [conn (bootstrap)
          report (d/transact
                  conn
                  {:tx-data [{:kontor.commodity/symbol "CT-RECIPE" :kontor.commodity/precision 2}]
                   :tx-meta {:db.valid/from jan-15}})
          tx-eid (kbt/commit-tx-eid report)]
      ;; Before close — visible
      (is (some? (d/q '[:find ?e . :in $ ?s
                        :where [?e :kontor.commodity/symbol ?s]]
                      (d/valid-at (d/db conn) feb-15) "CT-RECIPE")))
      (kbt/close-validity! conn tx-eid feb-1)
      ;; After close — not visible at feb-15
      (is (nil? (d/q '[:find ?e . :in $ ?s
                       :where [?e :kontor.commodity/symbol ?s]]
                     (d/valid-at (d/db conn) feb-15) "CT-RECIPE"))))))

(deftest commit-tx-eid-throws-on-malformed-report
  (testing "Missing :tx-data raises the structured error."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no :db/txInstant"
         (kbt/commit-tx-eid {:tempids {} :max-tx 0}))))
  (testing "Empty :tx-data raises the structured error."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no :db/txInstant"
         (kbt/commit-tx-eid {:tx-data []})))))

;; ============================================================================
;; close-validity! — side-effecting wrapper, end-to-end
;; ============================================================================

(deftest close-validity!-closes-prior-window
  (testing "After close-validity!, queries at vt past the closure don't
            see the prior tx; queries inside the window still do."
    (let [conn (bootstrap)
          tx-eid (post-tx-with-vt! conn jan-31)
          ;; Confirm the entity is visible at jan-31, jan-15 (no, before vf), feb-1, feb-15
          db0 (d/db conn)
          symbol-at (fn [db vt]
                      (-> (d/q '[:find ?s
                                 :in $ ?vt-tx
                                 :where
                                 [?e :kontor.commodity/symbol ?s ?vt-tx]]
                               db tx-eid)
                          first first))
          ;; Before close: visible at all vt >= vf
          before-close-jan-31 (symbol-at (d/valid-at db0 jan-31) jan-31)
          before-close-feb-15 (symbol-at (d/valid-at db0 feb-15) jan-31)]
      (is (some? before-close-jan-31))
      (is (some? before-close-feb-15))
      ;; Close at feb-1
      (kbt/close-validity! conn tx-eid feb-1)
      (let [db1 (d/db conn)]
        ;; In window [jan-31, feb-1) — jan-31 still visible
        (is (some? (symbol-at (d/valid-at db1 jan-31) tx-eid)))
        ;; At feb-1 and beyond — closed
        (is (nil? (symbol-at (d/valid-at db1 feb-1) tx-eid)))
        (is (nil? (symbol-at (d/valid-at db1 feb-15) tx-eid)))))))

(deftest close-validity!-rejects-invalid-window
  (testing "Closing with a vt that would produce vf >= vt is rejected
            by datahike's cross-tx vf<vt guard."
    (let [conn (bootstrap)
          tx-eid (post-tx-with-vt! conn jan-31)]
      (is (thrown-with-msg?
           Exception #"Invalid cross-tx valid-time window"
           (kbt/close-validity! conn tx-eid jan-15))
          "vt=jan-15 < existing vf=jan-31 — rejected"))))

(deftest close-validity!-records-an-auditable-commit
  (testing "The closing tx is a normal commit. The prior tx's data is
            unchanged; the closing commit added one datom asserting
            `:db.valid/to` on the prior tx-entity."
    (let [conn (bootstrap)
          tx-eid (post-tx-with-vt! conn jan-31)
          before-datoms (set (d/datoms (d/db conn) :eavt tx-eid))]
      (kbt/close-validity! conn tx-eid feb-15)
      (let [after-datoms (set (d/datoms (d/db conn) :eavt tx-eid))
            new-datoms (clojure.set/difference after-datoms before-datoms)]
        (is (= 1 (count new-datoms))
            "exactly one new datom on the prior tx-entity")
        (let [d (first new-datoms)]
          (is (= :db.valid/to (.-a d)))
          (is (= feb-15 (.-v d)))
          (is (not= tx-eid (.-tx d))
              "the closing datom carries the NEW tx's id, not the prior tx's"))))))
