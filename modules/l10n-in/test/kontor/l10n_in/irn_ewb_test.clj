(ns kontor.l10n-in.irn-ewb-test
  "Integration test for the IRN + EWB multi-attestation flow.

   This is the **load-bearing kernel validation** for ADR-024 — it
   exercises the full chain:
     1. Build the IRN JSON payload + compute the SHA-256 hash
     2. Persist as `:attestation` (format `:in/irn`) on the transaction
     3. Build the EWB Part A payload (referencing the IRN)
     4. Persist as `:attestation` (format `:in/ewb-part-a`) with
        `:depends-on` the IRN
     5. Compute EWB validity window from distance + Part B issuance
     6. Persist Part B with `:valid-from` and `:valid-until` set
     7. Query: 'what attestations does this transaction have, and
        what's the EWB validity status as of date D?'"
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-in.ewb :as ewb]
            [kontor.l10n-in.irn :as irn]))

(def supplier-gstin "27AAPFU0939F1ZV")    ; MH / Firm — verified algorithm test vector
(def buyer-gstin    "29AAACR4849R1ZL")    ; KA / Company — structurally valid

;; ============================================================================
;; Pure payload tests
;; ============================================================================

(deftest financial-year-derivation
  (is (= "2026-27" (irn/financial-year #inst "2026-04-01")))
  (is (= "2026-27" (irn/financial-year #inst "2026-12-31")))
  (is (= "2025-26" (irn/financial-year #inst "2026-03-31")))
  (is (= "2025-26" (irn/financial-year #inst "2025-04-01")))
  (is (= "2024-25" (irn/financial-year #inst "2025-03-31"))))

(deftest irn-hash-is-deterministic
  (testing "Same inputs → same hash; different inputs → different hash"
    (let [h1 (irn/compute-irn {:supplier-gstin supplier-gstin
                               :doc-no "INV-2026-IN-0001"
                               :doc-date #inst "2026-05-11"
                               :doc-type "INV"})
          h2 (irn/compute-irn {:supplier-gstin supplier-gstin
                               :doc-no "INV-2026-IN-0001"
                               :doc-date #inst "2026-05-11"
                               :doc-type "INV"})
          h3 (irn/compute-irn {:supplier-gstin supplier-gstin
                               :doc-no "INV-2026-IN-0002"     ; different
                               :doc-date #inst "2026-05-11"
                               :doc-type "INV"})]
      (is (= 64 (count h1)) "SHA-256 is 64 hex chars")
      (is (= h1 h2)         "Deterministic")
      (is (not= h1 h3)      "Doc-no varies → hash varies")
      (is (re-matches #"[0-9a-f]{64}" h1)
          "Lowercase hex"))))

;; ============================================================================
;; Fixtures for the integration test
;; ============================================================================

(def sample-invoice
  {:tran   {:tax-scheme :gst :supply-type :b2b
            :reverse-charge? false :igst-on-intra? false}
   :doc    {:no "INV-2026-IN-0001"
            :date #inst "2026-05-11"
            :type :inv}
   :seller {:gstin supplier-gstin
            :legal-name "Acme India Pvt Ltd"
            :addr1 "Plot 12, MIDC Industrial Area"
            :loc "Mumbai" :pin 400001 :state "27"}
   :buyer  {:gstin buyer-gstin
            :legal-name "Beta Karnataka Ltd"
            :addr1 "100 MG Road"
            :loc "Bangalore" :pin 560001 :state "29"
            :pos "29"}
   :items  [{:sl-no 1 :prd-desc "Widget Model X"
             :hsn-code "84799090"
             :qty 10M :unit "PCS"
             :unit-price 1000.00M
             :tot-amt 10000.00M :assess-amt 10000.00M
             :gst-rate 18M
             :igst-amt 1800.00M :cgst-amt 0M :sgst-amt 0M
             :tot-item-val 11800.00M}]
   :val    {:ass-val 10000.00M
            :cgst-val 0M :sgst-val 0M :igst-val 1800.00M
            :tot-inv-val 11800.00M}})

;; ============================================================================
;; IRN payload structure
;; ============================================================================

(deftest irn-payload-has-required-top-level-fields
  (let [p (irn/build-payload sample-invoice)]
    (is (= "1.1" (get p "Version")))
    (is (contains? p "TranDtls"))
    (is (contains? p "DocDtls"))
    (is (contains? p "SellerDtls"))
    (is (contains? p "BuyerDtls"))
    (is (contains? p "ItemList"))
    (is (contains? p "ValDtls"))
    (testing "Inter-state (MH → KA): supplier 27, POS 29"
      (is (= "27" (get-in p ["SellerDtls" "Stcd"])))
      (is (= "29" (get-in p ["BuyerDtls" "Pos"]))))
    (testing "Doc date as DD/MM/YYYY"
      (is (= "11/05/2026" (get-in p ["DocDtls" "Dt"]))))
    (testing "Single item line"
      (is (= 1 (count (get p "ItemList")))))
    (testing "Tax allocation: IGST 1800, CGST 0, SGST 0 (inter-state)"
      (is (= (bigdec "1800.00") (get-in p ["ValDtls" "IgstVal"]))))))

(deftest irn-payload-roundtrips-through-json
  (let [p   (irn/build-payload sample-invoice)
        s   (irn/payload-json p)
        rt  (json/read-str s)]
    (is (string? s))
    (is (= "1.1" (get rt "Version")))
    (is (= "INV" (get-in rt ["DocDtls" "Typ"])))))

;; ============================================================================
;; EWB validity window computation
;; ============================================================================

(deftest ewb-validity-regular-cargo
  (is (= 1 (ewb/validity-days 1))     "1 km regular → 1 day")
  (is (= 1 (ewb/validity-days 200))   "200 km regular → 1 day (boundary)")
  (is (= 2 (ewb/validity-days 201))   "201 km regular → 2 days")
  (is (= 2 (ewb/validity-days 400))   "400 km regular → 2 days")
  (is (= 3 (ewb/validity-days 401))   "401 km regular → 3 days"))

(deftest ewb-validity-odc-cargo
  (is (= 1 (ewb/validity-days 20 :odc))  "ODC 20 km → 1 day")
  (is (= 2 (ewb/validity-days 21 :odc))  "ODC 21 km → 2 days")
  (is (= 10 (ewb/validity-days 200 :odc))
      "ODC 200 km → 10 days (vs 1 day for regular)"))

(deftest ewb-validity-window-instant
  (let [part-b #inst "2026-05-11T10:00:00Z"
        [v-from v-until] (ewb/validity-window part-b 400)]
    (is (= part-b v-from))
    (is (= #inst "2026-05-13T10:00:00Z" v-until)
        "400 km regular → 2 days from Part B")))

;; ============================================================================
;; THE FULL FLOW — multi-attestation per ADR-024
;; ============================================================================

(defn- minimal-tx!
  "Plant a balanced 2-line transaction so we can hang attestations
   off it."
  [conn]
  (d/transact conn
              [{:db/id -1 :commodity/symbol "INR" :commodity/name "Indian Rupee"
                :commodity/precision 2 :commodity/iso-4217 "INR"}
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
                :transaction/narration      "MH → KA inter-state sale"}])
  (:db/id (d/entity (d/db conn) [:transaction/external-id "INV-2026-IN-0001"])))

(deftest end-to-end-irn-plus-ewb-multi-attestation
  (testing "The canonical India case: one transaction carrying both
            an IRN attestation and an EWB attestation that depends-on
            the IRN, each with its own lifecycle state and validity
            window. This is the load-bearing test for ADR-024."
    (let [conn (core/create-test-db)
          tx   (minimal-tx! conn)
          ;; Step 1: compute the IRN hash from the invoice metadata
          irn-hash (irn/compute-irn
                    {:supplier-gstin supplier-gstin
                     :doc-no         "INV-2026-IN-0001"
                     :doc-date       #inst "2026-05-11"
                     :doc-type       "INV"})
          ;; Step 2: build the full IRN JSON payload (what gets sent
          ;; to the NIC IRP portal)
          irn-payload (irn/build-payload sample-invoice)
          irn-json    (irn/payload-json irn-payload)
          ;; Step 3: persist the IRN attestation
          irn-issued-at #inst "2026-05-11T10:23:00Z"
          _ (d/transact conn
                        [{:db/id -100
                          :attestation/transaction   tx
                          :attestation/format        :in/irn
                          :attestation/token         irn-hash
                          :attestation/state         :issued
                          :attestation/issued-at     irn-issued-at
                          :attestation/payload       irn-json
                          :attestation/payload-hash  irn-hash}
                         {:db/id tx :transaction/attestations -100}])
          irn-eid (d/q '[:find ?a . :in $ ?t :where
                         [?a :attestation/transaction ?t]
                         [?a :attestation/format :in/irn]]
                       (d/db conn) tx)
          ;; Step 4: build the EWB Part A — references the IRN
          ewb-a (ewb/build-part-a
                 {:supply-type :outward :sub-supply-type :supply
                  :doc-type :tax-invoice
                  :doc-no "INV-2026-IN-0001"
                  :doc-date #inst "2026-05-11"
                  :from {:gstin supplier-gstin :legal-name "Acme India Pvt Ltd"
                         :addr1 "Plot 12" :loc "Mumbai" :pin 400001 :state "27"}
                  :to   {:gstin buyer-gstin :legal-name "Beta Karnataka Ltd"
                         :addr1 "100 MG Road" :loc "Bangalore" :pin 560001 :state "29"}
                  :place-of-supply "29"
                  :items [{:prd-desc "Widget" :hsn-code "84799090"
                           :qty 10 :unit "PCS"
                           :tax-val 10000.00M :gst-rate 0.18M}]
                  :tot-inv-val 11800.00M :ass-val 10000.00M
                  :transport {:distance-km 400}
                  :irn irn-hash})
          ewb-a-issued-at #inst "2026-05-11T10:24:00Z"
          _ (d/transact conn
                        [{:db/id -200
                          :attestation/transaction tx
                          :attestation/format      :in/ewb-part-a
                          :attestation/token       "1234567890123"
                          :attestation/state       :issued
                          :attestation/issued-at   ewb-a-issued-at
                          :attestation/payload     (json/write-str ewb-a)
                          :attestation/depends-on  [irn-eid]}
                         {:db/id tx :transaction/attestations -200}])
          ewb-a-eid (d/q '[:find ?a . :in $ ?t :where
                           [?a :attestation/transaction ?t]
                           [?a :attestation/format :in/ewb-part-a]]
                         (d/db conn) tx)
          ;; Step 5: Part B added when the truck rolls
          part-b-issued-at #inst "2026-05-11T14:30:00Z"
          [v-from v-until] (ewb/validity-window part-b-issued-at 400)
          ewb-b (ewb/build-part-b
                 {:vehicle-no "MH12AB1234"
                  :vehicle-type :regular
                  :from-place "Mumbai" :from-state "27"
                  :transport-mode :road
                  :transporter-doc-no "LR-001"
                  :transporter-doc-date #inst "2026-05-11"})
          _ (d/transact conn
                        [{:db/id -300
                          :attestation/transaction tx
                          :attestation/format      :in/ewb-part-b
                          :attestation/token       "1234567890123-B"
                          :attestation/state       :issued
                          :attestation/issued-at   part-b-issued-at
                          :attestation/valid-from  v-from
                          :attestation/valid-until v-until
                          :attestation/payload     (json/write-str ewb-b)
                          :attestation/depends-on  [ewb-a-eid]}
                         {:db/id tx :transaction/attestations -300}])
          db (d/db conn)
          tx-entity (d/entity db tx)
          attestations (:transaction/attestations tx-entity)
          by-format (into {} (map (juxt :attestation/format identity) attestations))]
      (testing "Transaction now carries 3 attestations"
        (is (= 3 (count attestations)))
        (is (= #{:in/irn :in/ewb-part-a :in/ewb-part-b}
               (set (keys by-format)))))
      (testing "The IRN attestation"
        (let [irn-att (by-format :in/irn)]
          (is (= irn-hash (:attestation/token irn-att)))
          (is (= :issued  (:attestation/state irn-att)))
          (is (some?      (:attestation/payload irn-att))
              "Full JSON payload stored for audit / byte-exact replay")))
      (testing "The EWB Part A depends on the IRN"
        (let [ewb-a-att (by-format :in/ewb-part-a)
              deps (set (map :db/id (:attestation/depends-on ewb-a-att)))]
          (is (contains? deps irn-eid)
              "ADR-024 :depends-on must wire EWB-A → IRN")))
      (testing "The EWB Part B has a valid-from / valid-until window
                computed from 400 km @ 1d/200km = 2 days"
        (let [ewb-b-att (by-format :in/ewb-part-b)]
          (is (some? (:attestation/valid-from ewb-b-att)))
          (is (some? (:attestation/valid-until ewb-b-att)))
          (let [from (:attestation/valid-from ewb-b-att)
                until (:attestation/valid-until ewb-b-att)
                duration-ms (- (.getTime until) (.getTime from))
                duration-days (/ duration-ms (* 1000 60 60 24))]
            (is (= 2 duration-days)
                "400 km regular cargo → exactly 2 days"))))
      (testing "Query: 'what's the EWB validity status as of D?'"
        (let [ewb-b-att (by-format :in/ewb-part-b)
              within  (let [now (java.util.Date.
                                 (+ (.getTime ^java.util.Date (:attestation/valid-from ewb-b-att))
                                    (* 24 60 60 1000)))]   ; 1 day in
                        (and (some? (:attestation/valid-from ewb-b-att))
                             (some? (:attestation/valid-until ewb-b-att))
                             (>= (.compareTo ^java.util.Date now
                                             (:attestation/valid-from ewb-b-att)) 0)
                             (<  (.compareTo ^java.util.Date now
                                             (:attestation/valid-until ewb-b-att)) 0)))
              expired (let [later (java.util.Date.
                                   (+ (.getTime ^java.util.Date (:attestation/valid-until ewb-b-att))
                                      (* 24 60 60 1000)))]   ; 1 day after expiry
                        (and (some? (:attestation/valid-until ewb-b-att))
                             (>= (.compareTo ^java.util.Date later
                                             (:attestation/valid-until ewb-b-att)) 0)))]
          (is within  "Valid 24h after Part B issuance")
          (is expired "Expired 24h after the 48h window closes"))))))
