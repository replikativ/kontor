(ns kontor.clearance-test
  "Tests for ADR-024 (multi-attestation), ADR-025 (document
   composition / complementos), and ADR-026 (effective-dated tax
   rates). These three ADRs are kernel-only schema lifts; the
   per-jurisdiction wiring lives in the l10n modules."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]))

;; ============================================================================
;; ADR-024 — Multi-attestation
;; ============================================================================

(defn- minimal-tx!
  "Plant a balanced 2-line transaction so we have something to attest."
  [conn]
  (d/transact conn
              [{:db/id -1 :kontor.commodity/symbol "INR" :kontor.commodity/name "Indian Rupee"
                :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "INR"}
               {:db/id -2 :account/path "Assets:Receivable"
                :account/name "AR" :account/type :asset :account/active true}
               {:db/id -3 :account/path "Income:Sales"
                :account/name "Sales" :account/type :income :account/active true}
               {:db/id -4 :journal/code "INV-IN" :journal/name "Sales India"
                :journal/type :sale :journal/active true}
               {:db/id -10
                :transaction/external-id    "INV-2026-IN-0001"
                :transaction/journal        -4
                :transaction/effective-date #inst "2026-05-11"
                :transaction/narration      "Test invoice"}])
  (:db/id (d/entity (d/db conn) [:transaction/external-id "INV-2026-IN-0001"])))

(deftest single-attestation-roundtrip
  (testing "Brazilian NF-e style (one attestation per transaction)"
    (let [conn (core/create-test-db)
          tx   (minimal-tx! conn)]
      (d/transact conn
                  [{:db/id -100
                    :attestation/transaction tx
                    :attestation/format      :br/nfe-44
                    :attestation/token       "35260112345678000100550010000000011234567897"
                    :attestation/state       :issued
                    :attestation/issued-at   #inst "2026-05-11T10:00:00Z"}
                   {:db/id tx :transaction/attestations -100}])
      (let [db (d/db conn)
            tx-entity (d/entity db tx)
            attestations (:transaction/attestations tx-entity)]
        (is (= 1 (count attestations)))
        (let [a (first attestations)]
          (is (= :br/nfe-44 (:attestation/format a)))
          (is (= :issued    (:attestation/state a))))))))

(deftest india-irn-plus-ewb-with-depends-on
  (testing "India: IRN attestation + EWB attestation, EWB depends-on IRN"
    (let [conn (core/create-test-db)
          tx   (minimal-tx! conn)
          ;; First the IRN
          _ (d/transact conn
                        [{:db/id -100
                          :attestation/transaction tx
                          :attestation/format      :in/irn
                          :attestation/token       "f8b3a1c9-irn-hash-for-test"
                          :attestation/state       :issued
                          :attestation/issued-at   #inst "2026-05-11T10:23:00Z"}
                         {:db/id tx :transaction/attestations -100}])
          irn-eid (d/q '[:find ?a .
                         :in $ ?t
                         :where
                         [?a :attestation/transaction ?t]
                         [?a :attestation/format :in/irn]]
                       (d/db conn) tx)
          ;; Then the EWB Part A, depends-on the IRN
          _ (d/transact conn
                        [{:db/id -200
                          :attestation/transaction tx
                          :attestation/format      :in/ewb-part-a
                          :attestation/token       "1234567890123"
                          :attestation/state       :issued
                          :attestation/issued-at   #inst "2026-05-11T10:24:00Z"
                          :attestation/valid-from  #inst "2026-05-11T10:24:00Z"
                          ;; 400 km @ 1d/200km → ~48h validity
                          :attestation/valid-until #inst "2026-05-13T10:24:00Z"
                          :attestation/depends-on  [irn-eid]}
                         {:db/id tx :transaction/attestations -200}])]
      (let [db (d/db conn)
            tx-entity (d/entity db tx)
            attestations (:transaction/attestations tx-entity)
            by-format (into {} (map (juxt :attestation/format identity) attestations))]
        (is (= 2 (count attestations)))
        (is (contains? by-format :in/irn))
        (is (contains? by-format :in/ewb-part-a))
        (let [ewb (by-format :in/ewb-part-a)
              deps (set (map :db/id (:attestation/depends-on ewb)))]
          (is (= #{irn-eid} deps)
              "EWB Part A depends on the IRN")
          (is (some? (:attestation/valid-from ewb)))
          (is (some? (:attestation/valid-until ewb))))))))

(deftest attestation-identity-collapses-reissue
  (testing "Re-issuing an attestation for the same (transaction, format)
            updates rather than duplicates — composite identity"
    (let [conn (core/create-test-db)
          tx   (minimal-tx! conn)
          _ (d/transact conn
                        [{:attestation/transaction tx
                          :attestation/format      :in/irn
                          :attestation/token       "first-token"
                          :attestation/state       :issued}])
          _ (d/transact conn
                        [{:attestation/transaction tx
                          :attestation/format      :in/irn
                          :attestation/token       "first-token"
                          :attestation/state       :revoked
                          :attestation/note        "Re-issued"}])
          db (d/db conn)
          n (d/q '[:find (count ?a) .
                   :in $ ?t
                   :where
                   [?a :attestation/transaction ?t]
                   [?a :attestation/format :in/irn]]
                 db tx)
          a (d/entity db (d/q '[:find ?a .
                                :in $ ?t
                                :where
                                [?a :attestation/transaction ?t]
                                [?a :attestation/format :in/irn]]
                              db tx))]
      (is (= 1 n) "Re-issuing must not duplicate")
      (is (= :revoked (:attestation/state a))
          "State on the original entity updates"))))

(deftest attestation-payload-and-hash
  (testing "Cryptographic-stamp regimes (KSA, Korea, Turkey) require
            byte-exact payload storage"
    (let [conn (core/create-test-db)
          tx   (minimal-tx! conn)
          payload "<canonical-xml>...</canonical-xml>"
          hash   "fd34c9...abc123"]   ; Test value; real impl computes SHA-256
      (d/transact conn
                  [{:attestation/transaction tx
                    :attestation/format      :sa/zatca-icv
                    :attestation/token       "INV-1"
                    :attestation/state       :issued
                    :attestation/payload     payload
                    :attestation/payload-hash hash}])
      (let [a (d/entity (d/db conn)
                        (d/q '[:find ?a . :where
                               [?a :attestation/format :sa/zatca-icv]]
                             (d/db conn)))]
        (is (= payload (:attestation/payload a)))
        (is (= hash    (:attestation/payload-hash a)))))))

;; ============================================================================
;; ADR-025 — Document composition / complementos
;; ============================================================================

(deftest mexico-cfdi-with-three-complementos
  (testing "CFDI envelope + Pagos + Carta Porte + TimbreFiscalDigital
            — three complementos in defined order"
    (let [conn (core/create-test-db)
          tx   (minimal-tx! conn)
          _ (d/transact conn
                        [{:db/id -100
                          :complemento/transaction tx
                          :complemento/namespace   "http://www.sat.gob.mx/Pagos20"
                          :complemento/format      :mx/cfdi-pagos-2.0
                          :complemento/sequence    100
                          :complemento/payload     "<pago20:Pagos>...</pago20:Pagos>"
                          :complemento/active      true}
                         {:db/id -200
                          :complemento/transaction tx
                          :complemento/namespace   "http://www.sat.gob.mx/CartaPorte31"
                          :complemento/format      :mx/cfdi-carta-porte-3.1
                          :complemento/sequence    200
                          :complemento/payload     "<cartaporte31:CartaPorte>...</cartaporte31:CartaPorte>"
                          :complemento/active      true}
                         {:db/id -300
                          :complemento/transaction tx
                          :complemento/namespace   "http://www.sat.gob.mx/TimbreFiscalDigital"
                          :complemento/format      :mx/cfdi-tfd-1.1
                          :complemento/sequence    9999       ; TFD always last
                          :complemento/payload     "<tfd:TimbreFiscalDigital ... UUID=\"...\"/>"
                          :complemento/active      true}
                         {:db/id tx
                          :transaction/complementos [-100 -200 -300]}])
          db (d/db conn)
          comps (->> (d/q '[:find [?c ...]
                            :in $ ?t
                            :where [?c :complemento/transaction ?t]]
                          db tx)
                     (map #(d/pull db '[*] %))
                     (sort-by :complemento/sequence))]
      (is (= 3 (count comps)))
      (is (= [:mx/cfdi-pagos-2.0 :mx/cfdi-carta-porte-3.1 :mx/cfdi-tfd-1.1]
             (mapv :complemento/format comps))))))

(deftest complemento-identity-by-namespace
  (testing "One complemento per (transaction, namespace) — re-emit replaces"
    (let [conn (core/create-test-db)
          tx   (minimal-tx! conn)
          _ (d/transact conn
                        [{:complemento/transaction tx
                          :complemento/namespace   "http://www.sat.gob.mx/Pagos20"
                          :complemento/format      :mx/cfdi-pagos-2.0
                          :complemento/sequence    100
                          :complemento/payload     "<v1/>"
                          :complemento/active      true}])
          _ (d/transact conn
                        [{:complemento/transaction tx
                          :complemento/namespace   "http://www.sat.gob.mx/Pagos20"
                          :complemento/format      :mx/cfdi-pagos-2.0
                          :complemento/sequence    100
                          :complemento/payload     "<v2-corrected/>"
                          :complemento/active      true}])
          db (d/db conn)
          n (d/q '[:find (count ?c) .
                   :in $ ?t
                   :where [?c :complemento/transaction ?t]]
                 db tx)
          c (d/entity db (d/q '[:find ?c .
                                :in $ ?t
                                :where [?c :complemento/transaction ?t]]
                              db tx))]
      (is (= 1 n) "Composite identity must collapse")
      (is (= "<v2-corrected/>" (:complemento/payload c))))))

;; ============================================================================
;; ADR-026 — Effective-dated tax rates
;; ============================================================================

(deftest tax-effective-window-attrs-present
  (testing ":tax/effective-from / :tax/effective-until are usable attrs"
    (let [conn (core/create-test-db)]
      (d/transact conn
                  [{:db/id -1 :tax/name "IGST 18% (pre-GST-2.0)"
                    :tax/country-code "IN"
                    :tax/type-tax-use :sale
                    :tax/amount-type :percent
                    :tax/amount 18.00M
                    :tax/effective-until #inst "2025-09-22T00:00:00+05:30"
                    :tax/active true}
                   {:db/id -2 :tax/name "IGST 18% (post-GST-2.0)"
                    :tax/country-code "IN"
                    :tax/type-tax-use :sale
                    :tax/amount-type :percent
                    :tax/amount 18.00M
                    :tax/effective-from #inst "2025-09-22T00:00:00+05:30"
                    :tax/active true}])
      (let [db (d/db conn)
            ;; Pre-cutover: 2025-09-01 falls in the OLD record's window.
            pre  (d/q '[:find [?n ...]
                        :in $ ?d
                        :where
                        [?t :tax/name ?n]
                        [?t :tax/country-code "IN"]
                        ;; Effective-until > D (or absent)
                        [(get-else $ ?t :tax/effective-from #inst "1970-01-01") ?ef]
                        [(<= ?ef ?d)]
                        [(get-else $ ?t :tax/effective-until #inst "9999-12-31") ?eu]
                        [(< ?d ?eu)]]
                      db #inst "2025-09-01")
            ;; Post-cutover: 2026-01-01 falls in the NEW record's window.
            post (d/q '[:find [?n ...]
                        :in $ ?d
                        :where
                        [?t :tax/name ?n]
                        [?t :tax/country-code "IN"]
                        [(get-else $ ?t :tax/effective-from #inst "1970-01-01") ?ef]
                        [(<= ?ef ?d)]
                        [(get-else $ ?t :tax/effective-until #inst "9999-12-31") ?eu]
                        [(< ?d ?eu)]]
                      db #inst "2026-01-01")]
        (is (= ["IGST 18% (pre-GST-2.0)"]  pre)
            "2025-09-01 must resolve to the pre-GST-2.0 rate")
        (is (= ["IGST 18% (post-GST-2.0)"] post)
            "2026-01-01 must resolve to the post-GST-2.0 rate")))))

(deftest tax-without-window-is-always-effective
  (testing "Legacy taxes without effective-from/until match any date"
    (let [conn (core/create-test-db)]
      (d/transact conn
                  [{:tax/name "VAT 19% (always)"
                    :tax/country-code "DE"
                    :tax/type-tax-use :sale
                    :tax/amount-type :percent
                    :tax/amount 19.00M
                    :tax/active true}])
      (let [db (d/db conn)
            hits (d/q '[:find [?n ...]
                        :in $ ?d
                        :where
                        [?t :tax/name ?n]
                        [(get-else $ ?t :tax/effective-from #inst "1970-01-01") ?ef]
                        [(<= ?ef ?d)]
                        [(get-else $ ?t :tax/effective-until #inst "9999-12-31") ?eu]
                        [(< ?d ?eu)]]
                      db #inst "2024-06-15")]
        (is (= ["VAT 19% (always)"] hits))))))
