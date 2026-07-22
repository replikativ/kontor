(ns kontor.payroll-at.chart-test
  "The payroll ↔ l10n chart contract, asserted where it actually bites.

   payroll-at shipped no chart of its own while needing thirteen accounts
   the l10n-at Einheitskontenrahmen base does not carry. The documented
   workaround was to hand-add them — and doing so put withheld Lohnsteuer
   on account 3500, which the base chart ships as `Umsatzsteuer 20 %`
   tagged `:uva-022-ust`. `:kontor.account/code` is not `:db/unique` and
   payroll resolves by code alone, so a payroll run credited withholding
   into the output-VAT account. Measured before the fix: box 022-ust of
   the filed UVA moved 0 → 5,000 on a €5,000 Lohnsteuer posting.

   The structural invariant \"payroll and l10n must not share codes\" would
   be wrong — 6000 Gehälter is shared ON PURPOSE, because payroll expense
   belongs on the book's own salary account. What must hold is semantic:
   **a payroll run must not move a tax-return box.** That is what is
   asserted here.

   Note 194 §1 P0-4."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.gate :as gate]
            [kontor.l10n-at.chart :as at-chart]
            [kontor.l10n-at.uva :as uva]
            [kontor.payroll-at.chart :as payroll-chart]
            [kontor.payroll-at.wage-types :as wt]))

(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private march #inst "2026-03-15")
(def ^:private q1 {:from #inst "2026-01-01" :to #inst "2026-04-01"})

(defn- book []
  (let [conn (core/create-test-db)]
    (at-chart/install! conn)
    (payroll-chart/install! conn)
    (gate/transact-with-validation
     conn [{:kontor.journal/code "GJ" :kontor.journal/type :general}])
    conn))

(defn- acct [conn code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] @conn code))

(defn- post! [conn debit credit amount]
  (gate/transact-with-validation
   conn
   [{:db/id -1 :kontor.transaction/journal [:kontor.journal/code "GJ"]
     :kontor.transaction/effective-date march :kontor.transaction/state :posted
     :kontor.transaction/posted-at march}
    {:db/id -100 :kontor.posting/transaction -1 :kontor.posting/account debit
     :kontor.posting/amount amount :kontor.posting/commodity eur
     :kontor.posting/posted-at march :kontor.posting/display-type :product}
    {:db/id -101 :kontor.posting/transaction -1 :kontor.posting/account credit
     :kontor.posting/amount (- amount) :kontor.posting/commodity eur
     :kontor.posting/posted-at march :kontor.posting/display-type :product}
    ;; book/entry! stamps this; hand-built tx-data must too, or the
    ;; posting falls outside every report window and reads as zero
    {:db/id "datomic.tx" :db.valid/from march}]))

(defn- uva-boxes [conn]
  (into {} (map (juxt :line/code (comp :amount :line/value)))
        (:report/lines (uva/compute conn q1))))

(deftest a-payroll-run-does-not-move-the-uva
  (let [conn (book)
        before (uva-boxes conn)]
    (testing "gross pay, withholding and employer contributions"
      ;; Resolve every account THROUGH the wage-type map rather than
      ;; naming codes here. A test that hard-codes the codes cannot catch
      ;; a mis-mapped wage type, which is the entire defect: the first cut
      ;; of this test did exactly that and passed against the bug.
      (let [wt-acct #(acct conn (wt/account-code-for %))
            pay-acct #(acct conn (wt/payable-code-for %))]
        (post! conn (wt-acct :grundgehalt) (wt-acct :lohnsteuer) 5000M)
        (post! conn (wt-acct :grundgehalt) (wt-acct :sv-arbeitnehmer) 3000M)
        (post! conn (wt-acct :kommunalsteuer) (pay-acct :kommunalsteuer) 900M)
        (post! conn (wt-acct :dienstgeberbeitrag-fond)
               (pay-acct :dienstgeberbeitrag-fond) 1200M)
        (post! conn (wt-acct :sv-arbeitgeber) (pay-acct :sv-arbeitgeber) 2000M)
        (post! conn (wt-acct :grundgehalt) (wt-acct :nettogehalt) 20000M)))
    (let [after (uva-boxes conn)]
      (is (= before after)
          (str "a payroll run changed the UVA. Deltas: "
               (pr-str (into {} (remove (fn [[k v]] (= v (get after k)))) before))))
      (testing "box 022-ust specifically — the one that was inflated"
        (is (= 0M (get after "022-ust")))))))

(deftest payroll-liability-codes-are-disjoint-from-the-l10n-tax-accounts
  ;; The structural half. Sharing an EXPENSE account with the base chart
  ;; is the point (6000 Gehälter); sharing a tax-tagged LIABILITY is the
  ;; bug, because that is what a tax return reads.
  (let [conn (book)
        tagged (into #{}
                     (map first)
                     (d/q '[:find ?code
                            :where
                            [?a :kontor.account/code ?code]
                            [?a :kontor.account/tags _]]
                          @conn))
        payroll-payables (set (vals (merge wt/default-rlg-1-map
                                           wt/default-payable-codes)))
        liability? (fn [code]
                     (= :liability (:kontor.account/type
                                    (d/pull @conn [:kontor.account/type]
                                            (acct conn code)))))
        offenders (filter #(and (liability? %) (contains? tagged %)) payroll-payables)]
    (is (= [] (vec offenders))
        (str "payroll routes a liability onto a tax-tagged account: "
             (pr-str (vec offenders))))))

(deftest the-starter-chart-covers-every-code-payroll-resolves
  ;; The other half of the original defect: payroll needed accounts that
  ;; existed nowhere, so consumers hand-added them and chose badly.
  (let [conn (book)
        needed (set (vals (merge wt/default-rlg-1-map wt/default-payable-codes)))
        missing (remove #(acct conn %) needed)]
    (is (= [] (vec missing))
        (str "wage-type map resolves codes no chart ships: " (pr-str (vec missing))))))
