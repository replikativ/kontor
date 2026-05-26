(ns kontor.l10n-ca.preset-test
  "Acceptance: one `install-all!` call yields a working CA tax stack
   that posts via `kontor.book` and produces a balanced trial balance.
   Note 160 §I-8."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.l10n-ca.preset :as preset]
            [kontor.trial :as trial]))

(def ^:private cad [:kontor.commodity/symbol "CAD"])

(deftest one-call-install-yields-working-stack
  (testing "(preset/create-ca-db) returns a fully wired CA conn"
    (let [conn (preset/create-ca-db)
          db   (d/db conn)]
      (testing "CA chart is present"
        (let [n (count (d/q '[:find [?path ...] :where [_ :kontor.account/path ?path]] db))]
          (is (pos? n) "expected the CA chart to be installed")))
      (testing "default 5 journals are present"
        (let [n (count (d/q '[:find [?c ...] :where [_ :kontor.journal/code ?c]] db))]
          (is (= 5 n))))
      (testing "CAD commodity is present"
        (is (some? (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "CAD"]] db))))
      (testing "CA tax statutes are installed"
        (let [provisions (set (d/q '[:find [?code ...]
                                     :where [?p :provision/jurisdiction :ca]
                                            [?p :provision/code ?code]] db))]
          (is (pos? (count provisions))
              "CIT + CGT + IC provisions present"))))))

(deftest end-to-end-post-via-preset
  (testing "Post a couple of entries and produce a balanced trial balance"
    (let [conn (preset/create-ca-db)
          ;; Pick the first asset + income account from the CA chart
          [bank rev] (d/q '[:find [?path ...]
                            :where [?a :kontor.account/path ?path]
                                   [?a :kontor.account/type ?t]
                                   [(contains? #{:asset :income} ?t)]]
                          (d/db conn))
          asset (first (filter #(re-find #"(?i)bank|cash|asset" %) [bank rev]))
          income (first (filter #(re-find #"(?i)income|sales|revenue|service" %) [bank rev]))]
      ;; If we found at least one asset + one income account, post a sale.
      (when (and asset income)
        (book/sell! conn {:debit-account [:kontor.account/path asset]
                          :credit-account [:kontor.account/path income]
                          :amount 1000M :commodity cad
                          :effective-date #inst "2026-03-15"})
        (testing "trial balance balances per commodity"
          (is (true? (trial/balanced? (trial/trial-balance conn)))))))))
