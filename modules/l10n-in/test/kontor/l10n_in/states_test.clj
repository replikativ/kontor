(ns kontor.l10n-in.states-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-in.states :as states]))

(deftest install-creates-india-and-37-states
  (let [conn (core/create-test-db)
        _ (states/install! conn)
        db (d/db conn)
        india (d/entity db [:kontor.country/code "IN"])
        n-states (d/q '[:find (count ?s) .
                        :where
                        [?c :kontor.country/code "IN"]
                        [?s :state/country ?c]]
                      db)]
    (is (= "India" (:kontor.country/name india)))
    (is (= "IND"   (:kontor.country/code-iso3 india)))
    (is (>= n-states 37)
        "Should install at least the 37 active state codes (plus pseudo-codes 96/97)")))

(deftest gst-codes-resolved
  (let [conn (core/create-test-db)
        _ (states/install! conn)
        db (d/db conn)]
    (testing "Maharashtra = 27"
      (let [mh (states/by-gst-code db "27")]
        (is (some? mh))
        (is (= "27" (states/gst-code-of db mh)))
        (is (= "Maharashtra" (:state/name (d/entity db mh))))))
    (testing "Delhi = 07 (Union Territory)"
      (let [dl (states/by-gst-code db "07")]
        (is (some? dl))
        (is (states/union-territory? db dl))))
    (testing "Ladakh = 38 (Union Territory, added 2019)"
      (let [la (states/by-gst-code db "38")]
        (is (some? la))
        (is (states/union-territory? db la))))
    (testing "Karnataka = 29 (not a UT)"
      (let [ka (states/by-gst-code db "29")]
        (is (some? ka))
        (is (not (states/union-territory? db ka)))))
    (testing "Pseudo-code 96 (Foreign Country)"
      (let [fc (states/by-gst-code db "96")]
        (is (some? fc))
        (is (= "Foreign Country" (:state/name (d/entity db fc))))))))

(deftest install-is-idempotent
  (let [conn (core/create-test-db)
        _ (states/install! conn)
        _ (states/install! conn)
        db (d/db conn)
        n-mh (d/q '[:find (count ?s) .
                    :where
                    [?sc :state-code/regulator :in/gst]
                    [?sc :state-code/code "27"]
                    [?sc :state-code/state ?s]]
                  db)]
    (is (= 1 n-mh)
        "Re-installing must collapse via composite identity")))
