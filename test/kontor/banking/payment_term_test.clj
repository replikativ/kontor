(ns kontor.banking.payment-term-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.banking.payment-term :as pt]))

(def jan-1  #inst "2026-01-01T00:00:00Z")
(def jan-11 #inst "2026-01-11T00:00:00Z")
(def jan-15 #inst "2026-01-15T00:00:00Z")
(def jan-31 #inst "2026-01-31T00:00:00Z")
(def feb-28 #inst "2026-02-28T00:00:00Z")
(def mar-2  #inst "2026-03-02T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (pt/install-standard-terms! conn)
    conn))

(deftest install-is-idempotent
  (let [conn (core/create-test-db)
        _ (pt/install-standard-terms! conn)
        n1 (count (d/q '[:find [?e ...] :where [?e :kontor.payment-term/code _]] (d/db conn)))
        _ (pt/install-standard-terms! conn)
        n2 (count (d/q '[:find [?e ...] :where [?e :kontor.payment-term/code _]] (d/db conn)))]
    (is (= n1 n2))
    (is (= n1 (count pt/standard-terms)))))

(deftest by-code-finds-standard-terms
  (let [conn (bootstrap)
        net30 (pt/by-code (d/db conn) "NET30")]
    (is (some? net30))
    (is (= 30 (:kontor.payment-term/net-days net30)))))

(deftest compute-due-date
  (let [conn (bootstrap)
        db (d/db conn)
        net30 (pt/by-code db "NET30")
        receipt (pt/by-code db "DUE-ON-RECEIPT")
        skonto (pt/by-code db "2/10-NET30")]
    (is (= jan-31 (pt/compute-due-date jan-1 net30))
        "Jan 1 + 30 days = Jan 31")
    (is (= jan-1 (pt/compute-due-date jan-1 receipt))
        "due-on-receipt = same day")
    (is (= jan-31 (pt/compute-due-date jan-1 skonto))
        "2/10-NET30 still has 30-day net deadline")))

(deftest compute-discount-deadline
  (let [conn (bootstrap)
        db (d/db conn)
        skonto (pt/by-code db "2/10-NET30")
        net30 (pt/by-code db "NET30")]
    (is (= jan-11 (pt/compute-discount-deadline jan-1 skonto))
        "10-day discount window from Jan 1 = Jan 11")
    (is (nil? (pt/compute-discount-deadline jan-1 net30))
        "no discount on plain NET30 → nil deadline")))

(deftest apply-term-builds-attribute-fragment
  (let [conn (bootstrap)
        db (d/db conn)
        skonto (pt/by-code db "2/10-NET30")
        frag (pt/apply-term jan-1 skonto)]
    (is (= (:db/id skonto) (:kontor.transaction/payment-term frag)))
    (is (= jan-31 (:kontor.transaction/due-date frag)))
    (is (= jan-11 (:kontor.transaction/discount-deadline frag)))))

(deftest leap-year-correctness
  (testing "2026 is not a leap year — Feb has 28 days. NET30 from
            Feb 28, 2026 should land on Mar 30."
    (let [conn (bootstrap)
          net30 (pt/by-code (d/db conn) "NET30")
          due (pt/compute-due-date feb-28 net30)]
      (is (= #inst "2026-03-30T00:00:00Z" due)))))
