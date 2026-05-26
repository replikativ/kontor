(ns kontor.entity-test
  "Tests for ADR-031: :entity entity + per-(entity, ledger, commodity)
   sum-to-zero invariant extension. Covers entity CRUD, hierarchy
   traversal, single-entity (unchanged) mode, multi-entity mode,
   intercompany cross-entity balance, and mixed-mode rejection."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.entity :as entity]
            [kontor.money :as m]
            [kontor.posting :as posting]))

;; ============================================================================
;; Entity entity — basic schema + helpers
;; ============================================================================

(deftest entity-basic-crud
  (let [conn (core/create-test-db)
        _ (d/transact conn
                      [{:kontor.entity/code  "acme-de"
                        :kontor.entity/name  "Acme Germany GmbH"
                        :kontor.entity/kind  :operating
                        :kontor.entity/active true}])
        db (d/db conn)
        eid (entity/by-code db "acme-de")]
    (is (some? eid))
    (is (= eid (entity/resolve-entity db "acme-de")))
    (is (= eid (entity/resolve-entity db eid)))
    (is (nil? (entity/resolve-entity db nil)))
    (is (nil? (entity/resolve-entity db "no-such-code")))))

(deftest entity-hierarchy
  (testing "Parent / ancestors / descendants over a 3-level tree"
    (let [conn (core/create-test-db)
          _ (d/transact conn
                        [{:db/id -1 :kontor.entity/code "acme-group"
                          :kontor.entity/name "Acme Group AG" :kontor.entity/kind :consolidation
                          :kontor.entity/active true}
                         {:db/id -2 :kontor.entity/code "acme-emea"
                          :kontor.entity/name "Acme EMEA Holding" :kontor.entity/kind :operating
                          :kontor.entity/parent-entity -1
                          :kontor.entity/active true}
                         {:db/id -3 :kontor.entity/code "acme-de"
                          :kontor.entity/name "Acme Germany GmbH" :kontor.entity/kind :operating
                          :kontor.entity/parent-entity -2
                          :kontor.entity/active true}
                         {:db/id -4 :kontor.entity/code "acme-fr"
                          :kontor.entity/name "Acme France SARL" :kontor.entity/kind :operating
                          :kontor.entity/parent-entity -2
                          :kontor.entity/active true}
                         {:db/id -5 :kontor.entity/code "acme-amer"
                          :kontor.entity/name "Acme Americas Holding" :kontor.entity/kind :operating
                          :kontor.entity/parent-entity -1
                          :kontor.entity/active true}
                         {:db/id -6 :kontor.entity/code "acme-us"
                          :kontor.entity/name "Acme US Inc." :kontor.entity/kind :operating
                          :kontor.entity/parent-entity -5
                          :kontor.entity/active true}])
          db (d/db conn)
          group (entity/by-code db "acme-group")
          emea  (entity/by-code db "acme-emea")
          de    (entity/by-code db "acme-de")
          fr    (entity/by-code db "acme-fr")
          us    (entity/by-code db "acme-us")]
      (testing "parent"
        (is (= group (entity/parent db emea)))
        (is (= emea  (entity/parent db de)))
        (is (nil? (entity/parent db group))))
      (testing "ancestors (walks to root, excludes self)"
        (is (= #{emea group} (entity/ancestors db de)))
        (is (= #{group} (entity/ancestors db emea)))
        (is (= #{} (entity/ancestors db group))))
      (testing "children (direct only)"
        (is (= #{de fr} (entity/children db emea)))
        (is (= 2 (count (entity/children db group)))))
      (testing "descendants (transitive, excludes self)"
        (is (= #{emea de fr (entity/by-code db "acme-amer") us}
               (entity/descendants db group)))
        (is (= #{de fr} (entity/descendants db emea)))
        (is (= #{} (entity/descendants db de))))
      (testing "family (self + descendants)"
        (is (contains? (entity/family db emea) emea))
        (is (contains? (entity/family db emea) de))
        (is (contains? (entity/family db emea) fr))))))

(deftest entity-kind-helpers
  (let [conn (core/create-test-db)
        _ (d/transact conn
                      [{:kontor.entity/code "acme-de" :kontor.entity/name "DE" :kontor.entity/kind :operating :kontor.entity/active true}
                       {:kontor.entity/code "acme-us" :kontor.entity/name "US" :kontor.entity/kind :operating :kontor.entity/active true}
                       {:kontor.entity/code "acme-elims" :kontor.entity/name "Eliminations" :kontor.entity/kind :elimination :kontor.entity/active true}
                       {:kontor.entity/code "acme-group" :kontor.entity/name "Group" :kontor.entity/kind :consolidation :kontor.entity/active true}])
        db (d/db conn)]
    (is (= 2 (count (entity/by-kind db :operating))))
    (is (= 1 (count (entity/by-kind db :elimination))))
    (is (= 1 (count (entity/by-kind db :consolidation))))
    (is (entity/operating? db (entity/by-code db "acme-de")))
    (is (not (entity/operating? db (entity/by-code db "acme-elims"))))))

;; ============================================================================
;; Single-entity mode (backward compat with ADR-021)
;; ============================================================================

(deftest single-entity-mode-unchanged
  (testing "When no posting carries :kontor.posting/entity, validate falls
            back to per-(ledger, commodity) — existing ADR-021
            behavior preserved exactly"
    (let [r (posting/validate
             {:transaction {:kontor.transaction/journal :kontor.journal/sales
                            :kontor.transaction/effective-date #inst "2026-05-11"
                            :kontor.transaction/narration "single-entity test"}
              :postings
              [{:kontor.posting/account :kontor.account/receivable
                :kontor.posting/amount 100.00M
                :kontor.posting/commodity :EUR}
               {:kontor.posting/account :kontor.account/revenue
                :kontor.posting/amount -100.00M
                :kontor.posting/commodity :EUR}]})]
      (is (:ok? r))
      (is (= :single-entity (:mode r))
          "Mode flag should report :single-entity")
      (is (empty? (:unbalanced r))))))

;; ============================================================================
;; Multi-entity mode — intercompany
;; ============================================================================

(def ifrs-ref [:ledger/code "ifrs"])
(def de-ref   [:kontor.entity/code "acme-de"])
(def us-ref   [:kontor.entity/code "acme-us"])

(deftest multi-entity-intercompany-balanced
  (testing "DE intercompany clearing 100 EUR → US intercompany clearing
            -100 EUR. Each entity's footprint sums to zero independently."
    (let [r (posting/validate
             {:transaction {:kontor.transaction/journal :kontor.journal/intercompany
                            :kontor.transaction/effective-date #inst "2026-05-11"
                            :kontor.transaction/narration "DE → US transfer"}
              :postings
              [;; DE side
               {:kontor.posting/account :kontor.account/de-due-from-us
                :kontor.posting/amount  100.00M
                :kontor.posting/commodity :EUR
                :kontor.posting/entity de-ref}
               {:kontor.posting/account :kontor.account/de-cash
                :kontor.posting/amount -100.00M
                :kontor.posting/commodity :EUR
                :kontor.posting/entity de-ref}
               ;; US side (intercompany matching counter)
               {:kontor.posting/account :kontor.account/us-cash
                :kontor.posting/amount  100.00M
                :kontor.posting/commodity :EUR
                :kontor.posting/entity us-ref}
               {:kontor.posting/account :kontor.account/us-due-to-de
                :kontor.posting/amount -100.00M
                :kontor.posting/commodity :EUR
                :kontor.posting/entity us-ref}]})]
      (is (:ok? r) (str "errors: " (:errors r)))
      (is (= :multi-entity (:mode r)))
      (is (empty? (:unbalanced r))))))

(deftest multi-entity-one-entity-unbalanced
  (testing "DE side balances; US side off by 1 EUR. The error
            identifies the US entity's unbalanced commodity."
    (let [r (posting/validate
             {:transaction {:kontor.transaction/journal :kontor.journal/intercompany
                            :kontor.transaction/effective-date #inst "2026-05-11"
                            :kontor.transaction/narration "Broken intercompany"}
              :postings
              [{:kontor.posting/account :a :kontor.posting/amount  100.00M :kontor.posting/commodity :EUR
                :kontor.posting/entity de-ref}
               {:kontor.posting/account :b :kontor.posting/amount -100.00M :kontor.posting/commodity :EUR
                :kontor.posting/entity de-ref}
               {:kontor.posting/account :a :kontor.posting/amount  100.00M :kontor.posting/commodity :EUR
                :kontor.posting/entity us-ref}
               {:kontor.posting/account :b :kontor.posting/amount  -99.00M :kontor.posting/commodity :EUR
                :kontor.posting/entity us-ref}]})]
      (is (not (:ok? r)))
      (is (= :multi-entity (:mode r)))
      (is (= #{us-ref} (set (keys (:unbalanced r))))
          "Only the US entity's footprint is unbalanced")
      (is (some #(= :unbalanced (:error %)) (:errors r))))))

(deftest multi-entity-cross-entity-cancellation-not-allowed
  (testing "Per ADR-031: a 5 EUR debit on DE does NOT net against
            a 5 EUR credit on US. Each entity self-balances."
    (let [r (posting/validate
             {:transaction {:kontor.transaction/journal :kontor.journal/intercompany
                            :kontor.transaction/effective-date #inst "2026-05-11"
                            :kontor.transaction/narration "Cross-entity netting attempt"}
              :postings
              [{:kontor.posting/account :a :kontor.posting/amount   5.00M :kontor.posting/commodity :EUR
                :kontor.posting/entity de-ref}
               {:kontor.posting/account :b :kontor.posting/amount  -5.00M :kontor.posting/commodity :EUR
                :kontor.posting/entity us-ref}]})]
      (is (not (:ok? r)))
      (is (= :multi-entity (:mode r)))
      (is (= #{de-ref us-ref} (set (keys (:unbalanced r))))
          "Both entities flagged — neither one self-balances"))))

(deftest multi-entity-ledger-x-entity-orthogonal
  (testing "DE-IFRS, DE-primary, US-IFRS, US-primary: four
            (entity, ledger) groups, each must balance independently"
    (let [r (posting/validate
             {:transaction {:kontor.transaction/journal :kontor.journal/intercompany
                            :kontor.transaction/effective-date #inst "2026-05-11"
                            :kontor.transaction/narration "Multi-ledger intercompany"}
              :postings
              ;; DE side — IFRS book
              [{:kontor.posting/account :a :kontor.posting/amount  100M :kontor.posting/commodity :EUR
                :kontor.posting/entity de-ref :kontor.posting/ledger ifrs-ref}
               {:kontor.posting/account :b :kontor.posting/amount -100M :kontor.posting/commodity :EUR
                :kontor.posting/entity de-ref :kontor.posting/ledger ifrs-ref}
               ;; DE side — primary (no explicit ledger ref; nil-keyed)
               {:kontor.posting/account :a :kontor.posting/amount  100M :kontor.posting/commodity :EUR
                :kontor.posting/entity de-ref}
               {:kontor.posting/account :b :kontor.posting/amount -100M :kontor.posting/commodity :EUR
                :kontor.posting/entity de-ref}
               ;; US side — IFRS book
               {:kontor.posting/account :a :kontor.posting/amount  100M :kontor.posting/commodity :EUR
                :kontor.posting/entity us-ref :kontor.posting/ledger ifrs-ref}
               {:kontor.posting/account :b :kontor.posting/amount -100M :kontor.posting/commodity :EUR
                :kontor.posting/entity us-ref :kontor.posting/ledger ifrs-ref}
               ;; US side — primary
               {:kontor.posting/account :a :kontor.posting/amount  100M :kontor.posting/commodity :EUR
                :kontor.posting/entity us-ref}
               {:kontor.posting/account :b :kontor.posting/amount -100M :kontor.posting/commodity :EUR
                :kontor.posting/entity us-ref}]})]
      (is (:ok? r) (str "errors: " (:errors r)))
      (is (= :multi-entity (:mode r)))
      (is (empty? (:unbalanced r))))))

;; ============================================================================
;; Mixed-mode rejection
;; ============================================================================

(deftest mixed-entity-mode-rejected
  (testing "Some postings tagged with entity, some not — ambiguous
            invariant, must be rejected"
    (let [r (posting/validate
             {:transaction {:kontor.transaction/journal :kontor.journal/sales
                            :kontor.transaction/effective-date #inst "2026-05-11"
                            :kontor.transaction/narration "Mixed mode (broken)"}
              :postings
              [{:kontor.posting/account :a :kontor.posting/amount  100M :kontor.posting/commodity :EUR
                :kontor.posting/entity de-ref}
               {:kontor.posting/account :b :kontor.posting/amount -100M :kontor.posting/commodity :EUR}
               ;; ^ no :kontor.posting/entity
               ]})]
      (is (not (:ok? r)))
      (is (some #(= :mixed-entity-mode (:error %)) (:errors r))
          "Validator must report :mixed-entity-mode error"))))

(deftest section-and-note-postings-dont-count-for-mode
  (testing "Display-only postings (:section, :note) are excluded
            from the mode-detection logic. A tx with entity-tagged
            balance-affecting postings + an untagged :note is still
            valid multi-entity mode."
    (let [r (posting/validate
             {:transaction {:kontor.transaction/journal :kontor.journal/intercompany
                            :kontor.transaction/effective-date #inst "2026-05-11"
                            :kontor.transaction/narration "With ui section"}
              :postings
              [{:kontor.posting/display-type :section :kontor.posting/narration "DE leg"}
               {:kontor.posting/account :a :kontor.posting/amount  100M :kontor.posting/commodity :EUR
                :kontor.posting/entity de-ref}
               {:kontor.posting/account :b :kontor.posting/amount -100M :kontor.posting/commodity :EUR
                :kontor.posting/entity de-ref}
               {:kontor.posting/display-type :note :kontor.posting/narration "End"}]})]
      (is (:ok? r) (str "errors: " (:errors r)))
      (is (= :multi-entity (:mode r))))))

;; ============================================================================
;; Schema attr shapes
;; ============================================================================

(deftest schema-attrs-installed
  (let [conn (core/create-test-db)
        db (d/db conn)]
    (testing ":kontor.entity/code is unique-identity"
      (let [a (d/pull db '[*] :kontor.entity/code)]
        (is (= :db.type/string (:db/valueType a)))
        (is (= :db.unique/identity (:db/unique a)))))
    (testing ":kontor.posting/entity / :ledger/entity / :valuation-book/entity all refs"
      (doseq [k [:kontor.posting/entity :ledger/entity :valuation-book/entity]]
        (let [a (d/pull db '[*] k)]
          (is (= :db.type/ref (:db/valueType a)) (str k))
          (is (= :db.cardinality/one (:db/cardinality a)) (str k)))))))
