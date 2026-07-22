(ns kontor.reporting.aging-test
  "End-to-end aging report test:
     - install SKR04 + payment terms
     - post 5 sales invoices with NET30 terms across 4 months
     - run aging-by-partner as of a fixed date and verify each
       invoice lands in the right bucket."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.reporting.aging :as aging]
            [kontor.core :as core]
            [kontor.l10n-de.chart :as chart]
            [kontor.banking.payment-term :as pt]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

;; Reference frame: as-of = 2026-04-30
(def as-of #inst "2026-04-30T00:00:00Z")

;; Five invoices at varying invoice dates. With NET30, due-dates are
;; +30 days. Days overdue at as-of (Apr 30):
;;   INV-A invoice 2026-04-15 → due May 15 → -15 (not yet due)
;;   INV-B invoice 2026-03-20 → due Apr 19 → 11 (bucket 0-30)
;;   INV-C invoice 2026-02-15 → due Mar 17 → 44 (bucket 31-60)
;;   INV-D invoice 2026-01-10 → due Feb 9  → 80 (bucket 61-90)
;;   INV-E invoice 2025-11-01 → due Dec 1  → 150 (bucket 90+)
(def fixtures
  [{:ext-id "INV-A" :date #inst "2026-04-15T00:00:00Z" :net 1000 :partner "ACME"}
   {:ext-id "INV-B" :date #inst "2026-03-20T00:00:00Z" :net  500 :partner "ACME"}
   {:ext-id "INV-C" :date #inst "2026-02-15T00:00:00Z" :net  800 :partner "BETA"}
   {:ext-id "INV-D" :date #inst "2026-01-10T00:00:00Z" :net  300 :partner "BETA"}
   {:ext-id "INV-E" :date #inst "2025-11-01T00:00:00Z" :net  600 :partner "GAMMA"}])

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (pt/install-standard-terms! conn)
    (d/transact conn
                [{:kontor.journal/code "INV"
                  :kontor.journal/name "Sales invoices"
                  :kontor.journal/type :sale
                  :kontor.journal/active true}
                 {:kontor.partner/external-id "ACME"  :kontor.partner/name "ACME GmbH"
                  :kontor.partner/kind :customer :kontor.partner/country-code "DE"}
                 {:kontor.partner/external-id "BETA"  :kontor.partner/name "Beta AG"
                  :kontor.partner/kind :customer :kontor.partner/country-code "DE"}
                 {:kontor.partner/external-id "GAMMA" :kontor.partner/name "Gamma KG"
                  :kontor.partner/kind :customer :kontor.partner/country-code "DE"}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- post-invoice-with-term!
  [conn {:keys [ext-id date net partner] :as fix}]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        recv (ace db "1400") rev (ace db "4400") ust (ace db "3801")
        jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))
        partner-eid (:db/id (d/entity db [:kontor.partner/external-id partner]))
        net30 (pt/by-code db "NET30")
        net-bd (bigdec net)
        vat (.setScale (.multiply net-bd (bigdec "0.19"))
                       2 java.math.RoundingMode/HALF_EVEN)
        gross (.add net-bd vat)
        term-frag (pt/apply-term date net30)
        tx-map (-> {:kontor.transaction/external-id ext-id
                    :kontor.transaction/journal jnl
                    :kontor.transaction/effective-date date
                    :kontor.transaction/narration ext-id
                    :kontor.transaction/partner partner-eid
                    :kontor.transaction/state :posted
                    :kontor.transaction/posted-at date}
                   (merge term-frag))
        tx (-> (posting/build-transaction
                {:transaction tx-map
                 :postings
                 [{:kontor.posting/account recv :kontor.posting/amount gross
                   :kontor.posting/commodity eur :kontor.posting/posted-at date}
                  {:kontor.posting/account rev :kontor.posting/amount (.negate net-bd)
                   :kontor.posting/commodity eur :kontor.posting/posted-at date}
                  {:kontor.posting/account ust :kontor.posting/amount (.negate vat)
                   :kontor.posting/commodity eur :kontor.posting/posted-at date}]}))]
    (v/transact-with-validation conn tx)))

(defn- seed-fixtures [conn]
  (doseq [f fixtures] (post-invoice-with-term! conn f)))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest aging-rows-each-invoice-lands-in-correct-bucket
  (let [conn (bootstrap)
        _ (seed-fixtures conn)
        rows (aging/aging-rows (d/db conn) #{"1400"} :as-of as-of)
        by-ext (into {} (map (juxt :external-id :bucket)) rows)]
    (is (= 5 (count rows)))
    (is (= :not-yet-due (get by-ext "INV-A")))
    (is (= :0-30        (get by-ext "INV-B")))
    (is (= :31-60       (get by-ext "INV-C")))
    (is (= :61-90       (get by-ext "INV-D")))
    (is (= :90+         (get by-ext "INV-E")))))

(deftest aging-rows-include-due-date-and-days-overdue
  (let [conn (bootstrap)
        _ (seed-fixtures conn)
        rows (aging/aging-rows (d/db conn) #{"1400"} :as-of as-of)
        by-ext (into {} (map (juxt :external-id identity)) rows)
        inv-c (get by-ext "INV-C")]
    (is (= #inst "2026-03-17T00:00:00Z" (:due-date inv-c))
        "Feb 15 + 30 days = Mar 17")
    (is (= 44 (:days-overdue inv-c)) "Mar 17 → Apr 30 is 44 days")))

(deftest aging-summary-by-bucket-totals
  (let [conn (bootstrap)
        _ (seed-fixtures conn)
        db  (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        sum (aging/aging-summary-by-bucket db #{"1400"} :as-of as-of)]
    (is (= 1190.00M (:amount (:not-yet-due sum))) "INV-A 1000 + 19% = 1190")
    (is (= 595.00M  (:amount (:0-30 sum)))        "INV-B 500 + 19% = 595")
    (is (= 952.00M  (:amount (:31-60 sum)))       "INV-C 800 + 19% = 952")
    (is (= 357.00M  (:amount (:61-90 sum)))       "INV-D 300 + 19% = 357")
    (is (= 714.00M  (:amount (:90+ sum)))         "INV-E 600 + 19% = 714")
    (is (= 3808.00M (:amount (:total sum)))       "all five = 3808")
    (testing "values are Money, as the docstring has always claimed"
      ;; they used to be bare platform decimals with the commodity nowhere
      ;; in sight, so a EUR+USD ledger produced a silently blended total
      (is (= eur (:commodity (:total sum))))
      (is (every? #(= eur (:commodity (get sum %)))
                  [:not-yet-due :0-30 :31-60 :61-90 :90+])))))

(deftest aging-by-partner-groups-correctly
  (let [conn (bootstrap)
        _ (seed-fixtures conn)
        per-partner (aging/aging-by-partner (d/db conn) #{"1400"} :as-of as-of)
        by-name (into {} (map (juxt :partner-name identity)) per-partner)]
    (is (= 3 (count per-partner)) "ACME / BETA / GAMMA")
    (is (= 1785.00M (:amount (:total (get by-name "ACME GmbH"))))  "INV-A + INV-B")
    (is (= 1309.00M (:amount (:total (get by-name "Beta AG"))))    "INV-C + INV-D")
    (is (= 714.00M  (:amount (:total (get by-name "Gamma KG")))))
    ;; ACME has both not-yet-due and 0-30
    (let [acme-buckets (:buckets (get by-name "ACME GmbH"))]
      (is (= 1190.00M (:amount (:not-yet-due acme-buckets))))
      (is (= 595.00M  (:amount (:0-30 acme-buckets)))))))

(deftest aging-honours-due-date-when-payment-term-omitted
  (testing "If a transaction has no :payment-term but DOES have an
            explicit :due-date, the aging still works. Conversely,
            no :due-date at all falls back to :kontor.transaction/effective-
            date (treats it as due-on-receipt)."
    (let [conn (bootstrap)
          db (d/db conn)
          eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
          recv (ace db "1400") rev (ace db "4400") ust (ace db "3801")
          jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))
          part (:db/id (d/entity db [:kontor.partner/external-id "ACME"]))
          ;; Manual transaction with explicit due-date but no payment-term
          tx (-> (posting/build-transaction
                  {:transaction
                   {:kontor.transaction/external-id "INV-MAN"
                    :kontor.transaction/journal jnl
                    :kontor.transaction/effective-date #inst "2026-01-01T00:00:00Z"
                    :kontor.transaction/due-date #inst "2026-04-15T00:00:00Z"
                    :kontor.transaction/narration "manual due date"
                    :kontor.transaction/partner part
                    :kontor.transaction/state :posted
                    :kontor.transaction/posted-at #inst "2026-01-01T00:00:00Z"}
                   :postings
                   [{:kontor.posting/account recv :kontor.posting/amount 119.00M
                     :kontor.posting/commodity eur :kontor.posting/posted-at #inst "2026-01-01T00:00:00Z"}
                    {:kontor.posting/account rev :kontor.posting/amount -100.00M
                     :kontor.posting/commodity eur :kontor.posting/posted-at #inst "2026-01-01T00:00:00Z"}
                    {:kontor.posting/account ust :kontor.posting/amount -19.00M
                     :kontor.posting/commodity eur :kontor.posting/posted-at #inst "2026-01-01T00:00:00Z"}]}))
          _ (v/transact-with-validation conn tx)
          rows (aging/aging-rows (d/db conn) #{"1400"} :as-of as-of)
          inv-man (first (filter #(= "INV-MAN" (:external-id %)) rows))]
      (is (some? inv-man))
      (is (= #inst "2026-04-15T00:00:00Z" (:due-date inv-man)))
      (is (= 15 (:days-overdue inv-man)))
      (is (= :0-30 (:bucket inv-man))))))

(deftest empty-aging-still-reports-a-commodity
  ;; The happy case — everything paid — has no rows to infer a commodity
  ;; from, and a Money cannot be built without one. The AR accounts' own
  ;; :kontor.account/commodity denominates the zeros. Missed on the first
  ;; pass because every fixture here has open items; caught by CI in
  ;; end-to-end-demo-test.
  (let [conn (bootstrap)                     ; chart installed, no invoices
        db   (d/db conn)
        eur  (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        sum  (aging/aging-summary-by-bucket db #{"1400"} :as-of as-of)]
    (is (= 0M (:amount (:total sum))))
    (is (= eur (:commodity (:total sum)))
        "denominated from :kontor.account/commodity on the AR account")
    (is (every? #(= eur (:commodity (get sum %)))
                [:not-yet-due :0-30 :31-60 :61-90 :90+]))
    (testing "aging-by-partner is empty rather than erroring"
      (is (= [] (aging/aging-by-partner db #{"1400"} :as-of as-of))))
    (testing "an explicit :commodity wins"
      (let [usd-sum (aging/aging-summary-by-bucket db #{"1400"} :as-of as-of
                                                   :commodity :made-up)]
        (is (= :made-up (:commodity (:total usd-sum))))))))

(deftest empty-aging-over-unpinned-accounts-says-so
  ;; No rows and no pinned commodity: there is genuinely no answer, so it
  ;; says which accounts it could not denominate rather than inventing one.
  (let [conn (bootstrap)]
    (d/transact conn [{:kontor.account/path "Assets:Receivable:Unpinned"
                       :kontor.account/code "1498" :kontor.account/type :asset
                       :kontor.account/active true}])
    (let [e (try (aging/aging-summary-by-bucket (d/db conn) #{"1498"} :as-of as-of)
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (= :aging/indeterminate-commodity (:type (ex-data e))))
      (is (= #{"1498"} (:account-codes (ex-data e)))))
    (testing "and :commodity resolves it"
      (is (= :EUR (:commodity (:total (aging/aging-summary-by-bucket
                                       (d/db conn) #{"1498"} :as-of as-of
                                       :commodity :EUR))))))))
