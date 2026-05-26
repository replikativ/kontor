(ns kontor.l10n-de.datev-test
  "DATEV EXTF export — diff against a hand-crafted expected fixture
   (per the user's chosen acceptance: generate DATEV CSV, compare
   against a hand-crafted file, fail on drift)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-de.chart :as chart]
            [kontor.l10n-de.datev :as datev]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(def jan-1   #inst "2026-01-01T00:00:00Z")
(def jan-15  #inst "2026-01-15T00:00:00Z")
(def jan-25  #inst "2026-01-25T00:00:00Z")
(def feb-1   #inst "2026-02-01T00:00:00Z")
(def fixed-timestamp
  "Pinned timestamp so the generated EXTF header is reproducible
   for the bytewise diff against the expected fixture."
  (java.time.LocalDateTime/of 2026 2 1 0 0 0 0))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:journal/code "INV"
                       :journal/name "Sales"
                       :journal/type :sale
                       :journal/active true}])
    conn))

(defn- account-eid [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- post-fixture-book!
  "Two transactions covering the canonical hand-crafted DATEV file:
     Jan 15 — INV-1: €1000 net sale @ 19% (1400 ← 4400 + 3801)
     Jan 25 — BILL-1: €200 net vendor bill @ 19% (6800 + 1576 → 3300)"
  [conn]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        rec (account-eid db "1400") rev (account-eid db "4400")
        ust (account-eid db "3801")
        exp (account-eid db "6800") vor (account-eid db "1576")
        pay (account-eid db "3300")]
    (v/transact-with-validation
     conn
     (-> (posting/build-transaction
          {:transaction
           {:transaction/external-id "INV-1"
            :transaction/journal jnl
            :transaction/effective-date jan-15
            :transaction/narration "INV-1"
            :transaction/state :posted
            :transaction/posted-at jan-15}
           :postings
           [{:posting/account rec :posting/amount 1190M :posting/commodity eur}
            {:posting/account rev :posting/amount -1000M :posting/commodity eur}
            {:posting/account ust :posting/amount -190M :posting/commodity eur}]})
         (->> (mapv #(if (some? (:posting/account %))
                       (assoc % :posting/posted-at jan-15) %)))))
    (v/transact-with-validation
     conn
     (-> (posting/build-transaction
          {:transaction
           {:transaction/external-id "BILL-1"
            :transaction/journal jnl
            :transaction/effective-date jan-25
            :transaction/narration "BILL-1"
            :transaction/state :posted
            :transaction/posted-at jan-25}
           :postings
           [{:posting/account exp :posting/amount  200M :posting/commodity eur}
            {:posting/account vor :posting/amount   38M :posting/commodity eur}
            {:posting/account pay :posting/amount -238M :posting/commodity eur}]})
         (->> (mapv #(if (some? (:posting/account %))
                       (assoc % :posting/posted-at jan-25) %)))))))

;; ============================================================================
;; Smoke
;; ============================================================================

(deftest datev-columns-count
  (is (>= (count datev/datev-columns) 120)
      "DATEV Buchungsstapel schema 510 v21 ships 122 columns; we
       cover the load-bearing 120 (omit two group-account columns
       outside SMB scope)."))

(deftest export-empty-book-produces-only-header
  (let [conn (bootstrap)
        out (datev/export-buchungsstapel
             conn
             {:from jan-1 :to feb-1 :year 2026
              :company-name "DATEV Test GmbH"
              :client-number "1234567"
              :timestamp fixed-timestamp})
        lines (str/split-lines out)]
    (is (= 2 (count (remove str/blank? lines)))
        "Empty book → header line + column-name line only.")))

;; ============================================================================
;; Bytewise diff against expected fixture
;; ============================================================================

(defn- normalize
  "Normalize line endings (\\r\\n vs \\n) so the diff is purely
   semantic. Other than that the bytes must match."
  [^String s]
  (str/replace s "\r\n" "\n"))

(deftest export-matches-hand-crafted-fixture
  (testing "Generated DATEV EXTF for the canonical Jan-2026 fixture
            book matches the hand-crafted oracle byte-for-byte
            (modulo line-ending normalization)."
    (let [conn (bootstrap)
          _ (post-fixture-book! conn)
          out (datev/export-buchungsstapel
               conn
               {:from jan-1 :to feb-1 :year 2026
                :company-name "DATEV Test GmbH"
                :client-number "1234567"
                :timestamp fixed-timestamp})
          ;; Expected fixture is in ISO-8859-1 (DATEV's required
          ;; encoding); read it explicitly so umlauts decode correctly.
          expected (slurp (io/resource "datev/expected-jan-2026.csv")
                          :encoding "ISO-8859-1")]
      (is (= (normalize expected) (normalize out))
          (str "Generated DATEV output drifted from the expected fixture.\n"
               "Compare resources/datev/expected-jan-2026.csv against the\n"
               "freshly generated output.")))))
