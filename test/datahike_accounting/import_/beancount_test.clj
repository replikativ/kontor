(ns datahike-accounting.import-.beancount-test
  "Phase-1 acceptance: a representative .beancount file round-trips
   through datahike-accounting (parse → load → dump → re-parse →
   structural equality)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike-accounting.core :as core]
            [datahike-accounting.import-.beancount :as bc]))

;; ============================================================================
;; Parse smoke
;; ============================================================================

(deftest parse-mini-fixture
  (let [src (slurp (io/resource "beancount/mini.beancount"))
        directives (bc/parse-string src)
        by-tag (group-by :tag directives)]
    (is (= 2 (count (:option by-tag))))
    (is (= 3 (count (:open by-tag))))
    (is (= 3 (count (:transaction by-tag))))
    (is (= 3 (count (:balance by-tag))))))

(deftest parse-extracts-transaction-shape
  (let [src "2026-01-15 * \"ACME\" \"Invoice 1\"\n  Assets:Bank   100.00 EUR\n  Income:Sales -100.00 EUR\n"
        [d] (bc/parse-string src)]
    (is (= :transaction (:tag d)))
    (is (= "ACME" (:payee d)))
    (is (= "Invoice 1" (:narration d)))
    (is (= 2 (count (:postings d))))
    (let [[p1 p2] (:postings d)]
      (is (= "Assets:Bank" (:account p1)))
      (is (= (BigDecimal. "100.00") (:amount p1)))
      (is (= "EUR" (:currency p1)))
      (is (= (BigDecimal. "-100.00") (:amount p2))))))

;; ============================================================================
;; Load
;; ============================================================================

(deftest load-mini-fixture
  (let [conn (core/create-test-db)
        src  (slurp (io/resource "beancount/mini.beancount"))
        report (bc/load-string! conn src)]
    (is (= {:options 2 :commodities 1 :accounts 3 :closes 0
            :transactions 3 :balances 3}
           report))))

;; ============================================================================
;; Round-trip — the actual ADR-009 acceptance criterion
;; ============================================================================

(defn- normalize
  "Whitespace + comment normalization for the round-trip diff. Strips
   :; comment lines, blank lines, and collapses runs of internal
   spaces to a single space; trims trailing whitespace per line."
  [^String s]
  (->> (str/split-lines s)
       (remove (fn [l] (or (str/blank? l)
                           (re-matches #"^\s*;.*" l))))
       (map (fn [l]
              (-> l
                  (str/replace #"[ \t]+" " ")
                  str/trim)))
       (str/join "\n")))

(deftest mini-fixture-round-trips
  (testing "ADR-009: mini.beancount → load → dump → load → dump produces
            structurally-equivalent output (modulo whitespace/comments)."
    (let [conn-a (core/create-test-db)
          src   (slurp (io/resource "beancount/mini.beancount"))
          _     (bc/load-string! conn-a src)
          dumped (bc/dump conn-a)
          ;; Parse + load the dump into a SECOND db, dump again.
          conn-b (core/create-test-db)
          _      (bc/load-string! conn-b dumped)
          dumped-2 (bc/dump conn-b)]
      ;; The first dump may not byte-equal the source (it's our own
      ;; canonical formatter), but dumped == dumped-2 must hold.
      (is (= (normalize dumped) (normalize dumped-2))
          "Two consecutive dumps must agree (round-trip stable).")
      ;; Also: parsing the dump produces the same number of
      ;; directives as parsing the original.
      (let [orig-dirs (group-by :tag (bc/parse-string src))
            dump-dirs (group-by :tag (bc/parse-string dumped))]
        (is (= (count (:transaction orig-dirs))
               (count (:transaction dump-dirs)))
            "Same number of transactions.")
        (is (= (count (:open orig-dirs))
               (count (:open dump-dirs)))
            "Same number of accounts.")
        (is (= (count (:balance orig-dirs))
               (count (:balance dump-dirs)))
            "Same number of balance assertions.")))))
