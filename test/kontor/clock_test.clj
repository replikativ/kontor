(ns kontor.clock-test
  "ADR-171 — the kernel's `*-tx-data` builders must be functions of their
   inputs.

   The bug these pin was invisible in the ordinary way: a builder that reads
   the wall clock still returns well-formed tx-data, still balances, still
   transacts. It differs only between two calls, and nothing in the value says
   so. Every existing builder test called the builder ONCE, so none of them
   could see it.

   The first test is the reproduction, and it fails on the pre-ADR-171 tree."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io]
            [clojure.string]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.book.build :as bb]
            [kontor.clock :as clock]
            [kontor.core :as core]))

(def ^:private pinned #inst "2026-06-01T12:00:00.000-00:00")

(def ^:private entry-opts
  {:journal        [:kontor.journal/code "GEN"]
   :effective-date #inst "2026-01-01"
   :narration      "determinism probe"
   :postings       [{:account [:kontor.account/path "Assets:Cash"]
                     :amount 100M :commodity [:kontor.commodity/symbol "EUR"]}
                    {:account [:kontor.account/path "Income:Sales"]
                     :amount -100M :commodity [:kontor.commodity/symbol "EUR"]}]})

(defn- fresh-book []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2}
                 {:kontor.journal/code "GEN" :kontor.journal/type :general}
                 {:kontor.account/path "Assets:Cash"  :kontor.account/type :asset}
                 {:kontor.account/path "Income:Sales" :kontor.account/type :income}])
    conn))

(deftest entry-tx-data-is-a-function-of-its-inputs
  (testing "THE regression. `kontor.book/entry-tx-data` is documented as the
            pure ADR-068 builder, and before ADR-171 it was not: two calls
            milliseconds apart differed in `:kontor.transaction/posted-at` and
            in `:kontor.posting/posted-at` on every leg.

            Reverting `kontor.posting.build/post-transaction-tx-data` to a
            direct `(java.util.Date.)` makes this fail -- the sleep is what
            guarantees the two wall-clock reads land in different
            milliseconds, and without it the bug hides behind a fast machine."
    (binding [clock/*now* pinned]
      (let [a (bb/entry-tx-data entry-opts)
            _ (Thread/sleep 25)
            b (bb/entry-tx-data entry-opts)]
        (is (= a b)
            "identical inputs, identical tx-data")
        (is (every? #(= pinned %)
                    (keep :kontor.transaction/posted-at a))
            "and the transaction carries the bound instant, not the wall clock")
        (is (every? #(= pinned %)
                    (keep :kontor.posting/posted-at a))
            "as does every posting -- the default propagates to both legs")))))

(deftest an-unbound-clock-behaves-exactly-as-before
  (testing "the non-breaking half, and the reason `*now*` defaults to nil
            rather than to a fixed instant. A caller who never heard of
            `kontor.clock` must keep getting wall-clock timestamps, or this
            change would silently backdate every existing consumer's books."
    (let [before (java.util.Date.)
          tx     (bb/entry-tx-data entry-opts)
          after  (java.util.Date.)
          stamps (keep :kontor.transaction/posted-at tx)]
      (is (seq stamps) "a timestamp is still stamped")
      (is (every? #(and (not (.before ^java.util.Date % before))
                        (not (.after ^java.util.Date % after)))
                  stamps)
          "and it is the real wall clock, bracketed by two reads around the call"))))

(deftest the-clock-reaches-through-the-transacting-path
  (testing "`entry!` and the gate, not just the builder in isolation. This is
            what a consumer replaying historical data actually calls, and it
            is the path where a stray wall-clock read would still reach the
            database after the builder was fixed."
    (let [conn (fresh-book)]
      (binding [clock/*now* pinned]
        (book/entry! conn entry-opts))
      (is (= [pinned]
             (d/q '[:find [?at ...]
                    :where [?t :kontor.transaction/posted-at ?at]]
                  (d/db conn)))
          "the stored seal is the bound instant"))))

(defn- builders-reading-the-wall-clock
  "Kernel `*-tx-data` builders whose body contains a no-arg clock read.

   A source scan rather than a call, because the alternative is fixture setup
   for nine builders across six clusters -- invoices, bank lines, side-effect
   intents, audit docs -- and the property is the same for all of them. This
   catches a reintroduction anywhere in the kernel, including in a builder
   written after this test.

   Deliberately NOT a check that the file is free of `(java.util.Date.)`:
   the read-side `as-of-valid` defaults are legitimate and stay."
  []
  (for [f     (file-seq (clojure.java.io/file "src/kontor"))
        :when (and (.isFile ^java.io.File f)
                   (re-find #"\.cljc?$" (.getName ^java.io.File f)))
        :let  [lines (clojure.string/split-lines (slurp f))]
        [enclosing line]
        (->> lines
             (reduce (fn [{:keys [cur out]} l]
                       (let [cur (or (second (re-find #"^\(defn-?\s+([^\s\)]+)" l)) cur)]
                         {:cur cur :out (conj out [cur l])}))
                     {:cur nil :out []})
             :out)
        :when (and enclosing
                   (clojure.string/ends-with? enclosing "-tx-data")
                   (re-find #"\(java\.util\.Date\.\)|\(js/Date\.\)" line))]
    (str (.getName ^java.io.File f) " / " enclosing)))

(deftest no-kernel-builder-reads-the-wall-clock
  (testing "the property generalises past the one builder that was reported.
            NINE `*-tx-data` builders defaulted a timestamp this way --
            `post-transaction-tx-data`, `reverse-tx-data`, `create-doc-tx-data`,
            `reclassify-privilege-tx-data`, `cross-tx-intent-tx-data`,
            `reconcile-lines-tx-data`, and the payment-application trio -- so
            fixing only the reported one would have left the ADR-068 contract
            broken in six other clusters.

            A source scan is the honest shape here: it holds for builders
            nobody has written yet, which a per-builder fixture cannot."
    (is (empty? (builders-reading-the-wall-clock))
        (str "these builders read the wall clock directly and so are not "
             "functions of their inputs; default from `kontor.clock/now` "
             "instead: " (vec (builders-reading-the-wall-clock))))))
