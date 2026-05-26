(ns kontor.geo-test
  "Tests for the country / state / place-of-supply entities (ADR-023).

   The kernel ships no geo data; l10n modules install the slice they
   need. These tests transact a small representative sample inline."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]))

;; ============================================================================
;; Country
;; ============================================================================

(defn- install-inr! [conn]
  (d/transact conn
              [{:kontor.commodity/symbol    "INR"
                :kontor.commodity/name      "Indian Rupee"
                :kontor.commodity/precision 2
                :kontor.commodity/iso-4217  "INR"}])
  (d/db conn))

(deftest country-iso-2-is-unique-identity
  (let [conn (core/create-test-db)
        _    (install-inr! conn)
        inr  (:db/id (d/entity (d/db conn) [:kontor.commodity/symbol "INR"]))]
    (d/transact conn
                [{:kontor.country/code              "IN"
                  :kontor.country/code-iso3         "IND"
                  :kontor.country/name              "India"
                  :kontor.country/default-commodity inr
                  :kontor.country/active            true}])
    (let [db (d/db conn)
          e1 (d/entity db [:kontor.country/code "IN"])
          e2 (d/entity db [:kontor.country/code-iso3 "IND"])]
      (is (some? e1))
      (is (= (:db/id e1) (:db/id e2))
          "ISO-2 and ISO-3 must both resolve to the same entity")
      (is (= "India" (:kontor.country/name e1)))
      (is (= inr (:db/id (:kontor.country/default-commodity e1)))))))

(deftest country-external-codes-roundtrip
  (testing "Per-regulator codes attach as :country-code entities with
            composite identity on (country, regulator) — ADR-019 mirror"
    (let [conn (core/create-test-db)
          _ (d/transact conn
                        [{:db/id -1 :kontor.country/code "IN" :kontor.country/name "India" :kontor.country/active true}
                         {:kontor.country-code/country   -1
                          :kontor.country-code/regulator :iso-3166-1-numeric
                          :kontor.country-code/code      "356"}
                         {:kontor.country-code/country   -1
                          :kontor.country-code/regulator :un/m49
                          :kontor.country-code/code      "356"}])
          db (d/db conn)
          india (d/entity db [:kontor.country/code "IN"])
          codes (->> (d/q '[:find ?reg ?code
                            :in $ ?c
                            :where
                            [?cc :kontor.country-code/country ?c]
                            [?cc :kontor.country-code/regulator ?reg]
                            [?cc :kontor.country-code/code ?code]]
                          db (:db/id india))
                     (into {}))]
      (is (= "356" (codes :iso-3166-1-numeric)))
      (is (= "356" (codes :un/m49))))))

(deftest country-code-identity-rejects-duplicate
  (testing "(country, regulator) is unique — second insert with the same
            pair overwrites rather than creating a duplicate"
    (let [conn (core/create-test-db)
          _ (d/transact conn
                        [{:db/id -1 :kontor.country/code "IN" :kontor.country/name "India" :kontor.country/active true}
                         {:kontor.country-code/country -1
                          :kontor.country-code/regulator :iso-3166-1-numeric
                          :kontor.country-code/code "356"}])
          _ (d/transact conn
                        [{:kontor.country-code/country [:kontor.country/code "IN"]
                          :kontor.country-code/regulator :iso-3166-1-numeric
                          :kontor.country-code/code "356"
                          :kontor.country-code/note "Re-asserted with note"}])
          db (d/db conn)
          n (d/q '[:find (count ?cc) .
                   :where
                   [?cc :kontor.country-code/regulator :iso-3166-1-numeric]]
                 db)]
      (is (= 1 n) "Composite identity must collapse duplicates"))))

;; ============================================================================
;; Country groups
;; ============================================================================

(deftest country-groups-many-to-many
  (testing "EU + EEA membership modeled as data, not flags"
    (let [conn (core/create-test-db)
          _ (d/transact conn
                        [{:db/id -1 :kontor.country-group/code "EU"   :kontor.country-group/name "European Union"}
                         {:db/id -2 :kontor.country-group/code "EEA"  :kontor.country-group/name "European Economic Area"}
                         {:db/id -3 :kontor.country-group/code "EFTA" :kontor.country-group/name "European Free Trade Association"}
                         {:kontor.country/code "DE" :kontor.country/name "Germany" :kontor.country/active true
                          :kontor.country/groups [-1 -2]}
                         {:kontor.country/code "NO" :kontor.country/name "Norway"  :kontor.country/active true
                          :kontor.country/groups [-2 -3]}
                         {:kontor.country/code "IN" :kontor.country/name "India"   :kontor.country/active true}])
          db (d/db conn)
          eu-members (d/q '[:find [?code ...]
                            :where
                            [?g :kontor.country-group/code "EU"]
                            [?c :kontor.country/groups ?g]
                            [?c :kontor.country/code ?code]]
                          db)
          eea-members (d/q '[:find [?code ...]
                             :where
                             [?g :kontor.country-group/code "EEA"]
                             [?c :kontor.country/groups ?g]
                             [?c :kontor.country/code ?code]]
                           db)]
      (is (= #{"DE"} (set eu-members)))
      (is (= #{"DE" "NO"} (set eea-members))
          "Norway is EEA but not EU; Germany is in both"))))

;; ============================================================================
;; State
;; ============================================================================

(deftest state-composite-identity
  (testing "(country, state-code) is unique; the same code in two
            countries is allowed (US-CA = California; CA-CA could also
            exist alongside it under a different country)"
    (let [conn (core/create-test-db)
          _ (d/transact conn
                        [{:db/id -1 :kontor.country/code "US" :kontor.country/name "United States" :kontor.country/active true}
                         {:db/id -2 :kontor.country/code "CA" :kontor.country/name "Canada"        :kontor.country/active true}
                         {:state/country -1 :state/code "CA" :state/name "California" :state/active true}
                         {:state/country -2 :state/code "QC" :state/name "Quebec"     :state/active true}])
          db (d/db conn)
          ;; Composite tuple lookup-refs don't auto-resolve nested
          ;; lookups, so query directly.
          us-ca (d/q '[:find ?s .
                       :where
                       [?c :kontor.country/code "US"]
                       [?s :state/country ?c]
                       [?s :state/code "CA"]]
                     db)
          ca-qc (d/q '[:find ?s .
                       :where
                       [?c :kontor.country/code "CA"]
                       [?s :state/country ?c]
                       [?s :state/code "QC"]]
                     db)]
      (is (some? us-ca))
      (is (some? ca-qc))
      (is (not= us-ca ca-qc))
      (testing "Same state-code in a different country is independent"
        ;; A CA-CA (Canadian "CA" state-code) could be added without
        ;; conflicting with US-CA — the tuple identity is on the pair.
        (d/transact conn
                    [{:state/country [:kontor.country/code "CA"] :state/code "CA"
                      :state/name "(fake CA-CA for test)" :state/active true}])
        (let [db2 (d/db conn)
              ca-ca (d/q '[:find ?s .
                           :where
                           [?c :kontor.country/code "CA"]
                           [?s :state/country ?c]
                           [?s :state/code "CA"]]
                         db2)]
          (is (some? ca-ca))
          (is (not= ca-ca us-ca)
              "US-CA and CA-CA are distinct entities"))))))

(deftest state-external-codes-india-gst
  (testing "Maharashtra (Indian state code 27 in GSTN) — the canonical
            ADR-023 example"
    (let [conn (core/create-test-db)
          _ (d/transact conn
                        [{:db/id -1 :kontor.country/code "IN" :kontor.country/name "India" :kontor.country/active true}
                         {:db/id -2 :state/country -1 :state/code "MH"
                          :state/name "Maharashtra" :state/active true}
                         {:state-code/state     -2
                          :state-code/regulator :in/gst
                          :state-code/code      "27"}
                         {:state-code/state     -2
                          :state-code/regulator :iso-3166-2
                          :state-code/code      "IN-MH"}])
          db (d/db conn)
          mh-eid (:db/id (d/entity db [:state/identity [[:kontor.country/code "IN"] "MH"]]))
          codes (->> (d/q '[:find ?reg ?code
                            :in $ ?s
                            :where
                            [?sc :state-code/state ?s]
                            [?sc :state-code/regulator ?reg]
                            [?sc :state-code/code ?code]]
                          db mh-eid)
                     (into {}))]
      (is (= "27"    (codes :in/gst)))
      (is (= "IN-MH" (codes :iso-3166-2))))))

(deftest state-from-three-jurisdictions
  (testing "Same schema serves Indian state, Brazilian UF, Canadian
            province with their respective external codes"
    (let [conn (core/create-test-db)
          _ (d/transact conn
                        [{:db/id -1 :kontor.country/code "IN" :kontor.country/name "India"  :kontor.country/active true}
                         {:db/id -2 :kontor.country/code "BR" :kontor.country/name "Brazil" :kontor.country/active true}
                         {:db/id -3 :kontor.country/code "CA" :kontor.country/name "Canada" :kontor.country/active true}
                         {:db/id -10 :state/country -1 :state/code "MH" :state/name "Maharashtra" :state/active true}
                         {:db/id -20 :state/country -2 :state/code "SP" :state/name "São Paulo"   :state/active true}
                         {:db/id -30 :state/country -3 :state/code "QC" :state/name "Quebec"     :state/active true}
                         {:state-code/state -10 :state-code/regulator :in/gst    :state-code/code "27"}
                         {:state-code/state -20 :state-code/regulator :br/ibge   :state-code/code "35"}
                         {:state-code/state -30 :state-code/regulator :ca/cra    :state-code/code "13"}])
          db (d/db conn)
          lookup (fn [code-iso2 code-st]
                   ;; find-tuple form: `[?reg ?code] .` returns a single tuple
                   (d/q '[:find [?reg ?code]
                          :in $ ?iso ?st
                          :where
                          [?c :kontor.country/code ?iso]
                          [?s :state/country ?c]
                          [?s :state/code ?st]
                          [?sc :state-code/state ?s]
                          [?sc :state-code/regulator ?reg]
                          [?sc :state-code/code ?code]]
                        db code-iso2 code-st))]
      (is (= [:in/gst  "27"] (lookup "IN" "MH")))
      (is (= [:br/ibge "35"] (lookup "BR" "SP")))
      (is (= [:ca/cra  "13"] (lookup "CA" "QC"))))))

;; ============================================================================
;; Partner / transaction refs
;; ============================================================================

(deftest partner-state-roundtrip
  (let [conn (core/create-test-db)
        _ (d/transact conn
                      [{:db/id -1 :kontor.country/code "IN" :kontor.country/name "India" :kontor.country/active true}
                       {:db/id -2 :state/country -1 :state/code "MH"
                        :state/name "Maharashtra" :state/active true}
                       {:db/id -3
                        :kontor.partner/external-id "CUST-001"
                        :kontor.partner/name        "Acme India Pvt Ltd"
                        :kontor.partner/kind        :customer
                        :kontor.partner/country-code "IN"
                        :kontor.partner/state       -2}])
        db (d/db conn)
        p  (d/entity db [:kontor.partner/external-id "CUST-001"])]
    (is (= "Maharashtra" (-> p :kontor.partner/state :state/name)))
    (is (= "IN"          (-> p :kontor.partner/state :state/country :kontor.country/code))
        "Country dereferences cleanly via the state ref")))

(deftest transaction-place-of-supply-roundtrip
  (testing "POS may differ from partner state — the canonical India
            services case (partner registered in MH, service delivered
            to KA)"
    (let [conn (core/create-test-db)
          _ (d/transact conn
                        [{:db/id -1 :kontor.country/code "IN" :kontor.country/name "India" :kontor.country/active true}
                         {:db/id -10 :state/country -1 :state/code "MH" :state/name "Maharashtra" :state/active true}
                         {:db/id -20 :state/country -1 :state/code "KA" :state/name "Karnataka"   :state/active true}
                         {:db/id -100 :journal/code "INV-IN" :journal/name "Sales India"
                          :journal/type :sale :journal/active true}
                         {:db/id -200
                          :transaction/external-id    "INV-2026-001"
                          :transaction/journal        -100
                          :transaction/effective-date #inst "2026-05-11"
                          :transaction/narration      "Consulting MH → KA"
                          :transaction/place-of-supply -20}])
          db (d/db conn)
          tx (d/entity db [:transaction/external-id "INV-2026-001"])]
      (is (= "KA" (-> tx :transaction/place-of-supply :state/code)))
      (is (= "IN" (-> tx :transaction/place-of-supply :state/country :kontor.country/code))))))

(deftest schema-attr-shapes
  (testing "ADR-023 attributes are present with expected typing"
    (let [conn (core/create-test-db)
          db   (d/db conn)
          ;; refs
          state-attr (d/pull db '[*] :kontor.partner/state)
          pos-attr   (d/pull db '[*] :transaction/place-of-supply)
          ;; tuple composite
          state-id   (d/pull db '[*] :state/identity)
          country-id (d/pull db '[*] :kontor.country-code/identity)]
      (is (= :db.type/ref         (:db/valueType state-attr)))
      (is (= :db.cardinality/one  (:db/cardinality state-attr)))
      (is (= :db.type/ref         (:db/valueType pos-attr)))
      (is (= :db.cardinality/one  (:db/cardinality pos-attr)))
      (is (= :db.type/tuple       (:db/valueType state-id)))
      (is (= [:state/country :state/code] (:db/tupleAttrs state-id)))
      (is (= :db.unique/identity  (:db/unique state-id)))
      (is (= :db.type/tuple       (:db/valueType country-id))))))
