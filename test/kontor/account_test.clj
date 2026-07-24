(ns kontor.account-test
  "note 198 audit — `kontor.account/resolve-code`, the ONE strict
   `:kontor.account/code` → eid resolver.

   `:kontor.account/code` is `:db/index true` but NOT unique (ADR-119), and
   the shipped charts genuinely collide with each other (l10n-uk × l10n-us is
   34 codes; payroll-jp + l10n-jp both define \"610000\"). The scalar
   `:find ?a .` form every module used to carry returned an ARBITRARY member
   of the result set, and that eid went straight into
   `:kontor.posting/account`. These tests pin the replacement contract:
   one match → eid, none → nil, more than one → throw."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.account :as kacct]
            [kontor.core :as core]))

(defn- with-accounts [rows]
  (let [conn (core/create-test-db)]
    (d/transact conn rows)
    (d/db conn)))

(deftest resolve-code-exactly-one-match
  (let [db (with-accounts [{:kontor.account/path "Assets:Bank:Checking"
                            :kontor.account/code "1200"
                            :kontor.account/type :asset}])]
    (is (integer? (kacct/resolve-code db "1200")))
    (testing "the eid is the account carrying that code"
      (is (= "Assets:Bank:Checking"
             (:kontor.account/path
              (d/pull db [:kontor.account/path] (kacct/resolve-code db "1200"))))))))

(deftest resolve-code-no-match-is-nil
  (let [db (with-accounts [{:kontor.account/path "Assets:Bank:Checking"
                            :kontor.account/code "1200"
                            :kontor.account/type :asset}])]
    (testing "a code no chart in this book carries is a normal nil, not a throw
              — callers legitimately probe for optional accounts"
      (is (nil? (kacct/resolve-code db "9999"))))
    (testing "nil code is nil, not a scan for accounts without a code"
      (is (nil? (kacct/resolve-code db nil))))))

(deftest resolve-code-collision-throws
  ;; The measured real case: two charts cohabiting in one connection, both
  ;; using the same numeric code for entirely different accounts.
  (let [db (with-accounts [{:kontor.account/path "Assets:Bank:Checking"
                            :kontor.account/code "610000"
                            :kontor.account/type :asset}
                           {:kontor.account/path "Expenses:Salaries"
                            :kontor.account/code "610000"
                            :kontor.account/type :expense}])
        ex (is (thrown? clojure.lang.ExceptionInfo (kacct/resolve-code db "610000")))
        data (ex-data ex)]
    (testing "it refuses rather than posting into whichever account came first"
      (is (= :kontor.account/ambiguous-code (:type data)))
      (is (= "610000" (:code data)))
      (is (= 2 (count (:matches data)))))
    (testing "the error names BOTH candidate accounts by their unique path,
              so the operator can pick"
      (is (= ["Assets:Bank:Checking" "Expenses:Salaries"]
             (sort (:paths data)))))
    (testing "the message points at the fix (ADR-119: resolve on path)"
      (is (re-find #":kontor.account/path" (ex-message ex))))))

(deftest resolve-code-context-is-surfaced
  (let [db (with-accounts [{:kontor.account/path "A" :kontor.account/code "1"
                            :kontor.account/type :asset}
                           {:kontor.account/path "B" :kontor.account/code "1"
                            :kontor.account/type :asset}])
        ex (is (thrown? clojure.lang.ExceptionInfo
                        (kacct/resolve-code db "1" {:context "DE payroll (SKR04/SKR03)"})))]
    (testing "the calling module is named so the operator knows what tripped"
      (is (re-find #"DE payroll" (ex-message ex)))
      (is (= "DE payroll (SKR04/SKR03)" (:context (ex-data ex)))))))

(deftest resolve-code-bang-throws-on-missing
  (let [db (with-accounts [{:kontor.account/path "A" :kontor.account/code "1"
                            :kontor.account/type :asset}])]
    (is (= (kacct/resolve-code db "1") (kacct/resolve-code! db "1")))
    (let [ex (is (thrown? clojure.lang.ExceptionInfo (kacct/resolve-code! db "nope")))]
      (is (= :kontor.account/code-not-found (:type (ex-data ex)))))))

(deftest resolve-code-strict-is-the-same-fn
  (is (identical? kacct/resolve-code kacct/resolve-code-strict)))
