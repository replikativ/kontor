(ns kontor.book.build-test
  "The cheap canary on the write side's option contract — pure, no db, so it
   runs on the JVM (kaocha) AND on Node (`kontor.node-runner`).

   `posting-option-keys` is a promise with two halves, and both have already
   been broken here:

     - a key the set OMITS but `->posting` reads is refused outright by
       `check-keys!` (loud, harmless);
     - a key the set ADMITS but `->posting` ignores is silently dropped —
       `:ledger` (note 160), then `:period-tag` and
       `:analytic-distributions` (ADR-140), each of them a documented,
       schema-backed attribute that no `kontor.book` caller could actually
       write.

   The second half is the dangerous one, and it is exactly what this file
   tests: set each option to a sentinel in turn and require the built
   tx-data to CHANGE. An admitted-but-ignored key fails by producing the
   baseline; an omitted-but-read key fails by throwing out of
   `check-keys!`. Thirty lines that would have caught three shipped defects.

   `kontor.book.reversal-contract-test` is the db-bound other half — it
   asserts the round trip through `reverse!`. ADR-170."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [kontor.book.build :as build]))

(def ^:private base
  {:journal        [:kontor.journal/code "GJ"]
   :effective-date #inst "2026-03-15"
   :commodity      :EUR
   :postings       [{:account [:kontor.account/path "Expenses:Ops"] :amount "60.00"}
                    {:account [:kontor.account/path "Assets:Cash"] :amount "-60.00"}]})

(def ^:private sentinels
  "A value for every key in `posting-option-keys`, each DIFFERENT from what
   `base`'s first posting already yields — otherwise 'the output changed'
   would not be a signal."
  {:account                [:kontor.account/path "Expenses:Other"]
   :amount                 "61.00"
   :commodity              :USD
   :entity                 [:kontor.entity/code "E9"]
   :partner                [:kontor.partner/external-id "P9"]
   :ledger                 [:kontor.ledger/code "IFRS"]
   :dimensions             {:project "P9"}
   :period-tag             :adjustment-13
   :analytic-distributions [{:plan [:kontor.analytic-plan/code "CC"]
                             :account [:kontor.analytic-account/path "CC:Eng"]
                             :percent "100"}]
   :narration              "per-leg narration"
   :display-type           :tax})

(deftest every-posting-option-has-a-sentinel
  (is (= build/posting-option-keys (set (keys sentinels)))
      (str "a new posting option needs a sentinel here, or it is admitted by "
           "check-keys! and asserted by nothing")))

(deftest every-admitted-posting-option-reaches-the-tx-data
  (let [baseline (build/build-input base)]
    (doseq [k (sort-by str build/posting-option-keys)]
      (is (not= baseline
                (build/build-input (assoc-in base [:postings 0 k] (get sentinels k))))
          (str k " is accepted by posting-option-keys but changes nothing in "
               "the built tx-data — i.e. it is silently dropped, which is "
               "indistinguishable from an intentional default")))))

(deftest an-unadmitted-posting-key-is-refused
  ;; The other half of the promise, so the test above cannot be satisfied by
  ;; simply admitting everything.
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (build/build-input (assoc-in base [:postings 0 :not-an-option] 1)))))
