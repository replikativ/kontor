(ns kontor.regression.intercompany-fx-test
  "Regression suite — INTERCOMPANY + FX (cross-border consolidation).

   Flagship international scenario: a two-entity group —
     - DE parent  (functional commodity EUR)
     - US sub     (functional commodity USD)
   under a synthetic :consolidation group entity + a synthetic
   :elimination entity, all sharing ONE datahike connection (ADR-031).

   The suite exercises the full cross-border pipeline end-to-end:
     1. kontor.fx.fx/convert against a PERSISTED rate (real ECB figure).
     2. kontor.fx.fx/to-functional-currency — a US sub recording a
        EUR-denominated parent invoice in its USD functional books.
     3. eliminate-intercompany-pair-tx-data — the paired postings negate
        and sum to zero per (entity, commodity).
     4. translate-trial-balance-tx-data — an IAS 21 translation with a
        REAL, hand-computed cumulative-translation-adjustment (CTA) plug
        (monetary at closing / equity at historical / P&L at average).
     5. consolidate! — the atomic composer: translate every operating
        entity to EUR, eliminate every intercompany pair, and confirm the
        consolidated result BALANCES per commodity with an exact CTA.

   Every expected figure is hand-computed from the stated rate and is
   annotated with its source in a comment. Money is BigDecimal — compared
   numerically with `==` (scale-insensitive), never with `=` on doubles."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.fx.fx :as fx]
            [kontor.fx.fx-rate-provider :as fxp]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.provider.consolidation :as cons]
            [kontor.reporting.trial :as trial]
            [kontor.validation :as v]))

;; ---------------------------------------------------------------------------
;; Dates
;; ---------------------------------------------------------------------------

(def dec-29 #inst "2023-12-29T00:00:00Z")  ;; last ECB business day of 2023
(def dec-31 #inst "2023-12-31T00:00:00Z")  ;; group year-end / closing date

;; ---------------------------------------------------------------------------
;; Rates
;;
;; EUR→USD spot 1.1050 — ECB euro reference rate, 29 Dec 2023 (the last
;; published rate of 2023). Source: ECB eurofxref history.
;;
;; USD→EUR translation rate-types (stored direct, so the IAS 21 CTA math
;; is exact and free of 12-digit inverse noise). Plausible late-2023
;; levels (EUR/USD ≈ 1.09–1.10 → 1 USD ≈ 0.905–0.920 EUR):
;;   closing    1 USD = 0.9050 EUR   (monetary BS items)
;;   historical 1 USD = 0.9200 EUR   (equity — frozen at contribution)
;;   average    1 USD = 0.9100 EUR   (P&L over the period)
;; ---------------------------------------------------------------------------

(defn- seed-rates! [conn]
  (fxp/save-rates!
   conn
   [{:from "EUR" :to "USD" :at-date dec-29 :rate 1.1050M :rate-type :spot :source :ecb}
    {:from "USD" :to "EUR" :at-date dec-31 :rate 0.9050M :rate-type :closing :source :test}
    {:from "USD" :to "EUR" :at-date dec-31 :rate 0.9200M :rate-type :historical :source :test}
    {:from "USD" :to "EUR" :at-date dec-31 :rate 0.9100M :rate-type :average :source :test}]))

;; ---------------------------------------------------------------------------
;; Bootstrap — commodities, the entity tree, a shared chart of accounts
;; ---------------------------------------------------------------------------

(defn- bootstrap! []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
                 {:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
                  :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "USD"}])
    (seed-rates! conn)
    ;; Entity tree: group (consolidation) → {elim, DE, US}
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR"}
                 {:db/id "usd" :kontor.commodity/symbol "USD"}
                 {:db/id "grp"
                  :kontor.entity/code "grp" :kontor.entity/name "Weltweit Group"
                  :kontor.entity/kind :consolidation
                  :kontor.entity/functional-commodity "eur"
                  :kontor.entity/active true}
                 {:db/id "elim"
                  :kontor.entity/code "elim" :kontor.entity/name "Group Eliminations"
                  :kontor.entity/kind :elimination
                  :kontor.entity/functional-commodity "eur"
                  :kontor.entity/parent-entity "grp"
                  :kontor.entity/active true}
                 {:db/id "de"
                  :kontor.entity/code "de" :kontor.entity/name "Weltweit GmbH"
                  :kontor.entity/kind :operating
                  :kontor.entity/functional-commodity "eur"
                  :kontor.entity/parent-entity "grp"
                  :kontor.entity/active true}
                 {:db/id "us"
                  :kontor.entity/code "us" :kontor.entity/name "Weltweit US LLC"
                  :kontor.entity/kind :operating
                  :kontor.entity/functional-commodity "usd"
                  :kontor.entity/parent-entity "grp"
                  :kontor.entity/active true}])
    ;; Shared chart — accounts are entity-agnostic; :kontor.posting/entity scopes.
    (d/transact conn
                [{:db/id "cash" :kontor.account/path "Assets:Cash"
                  :kontor.account/code "1000" :kontor.account/name "Cash"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "ar-ic" :kontor.account/path "Assets:AR-IC"
                  :kontor.account/code "1400" :kontor.account/name "AR — Intercompany"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "ap-ic" :kontor.account/path "Liabilities:AP-IC"
                  :kontor.account/code "2400" :kontor.account/name "AP — Intercompany"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:db/id "stock" :kontor.account/path "Equity:CommonStock"
                  :kontor.account/code "3000" :kontor.account/name "Common Stock"
                  :kontor.account/type :equity :kontor.account/active true}
                 {:db/id "cta" :kontor.account/path "Equity:CTA"
                  :kontor.account/code "3900"
                  :kontor.account/name "Cumulative Translation Adjustment"
                  :kontor.account/type :equity :kontor.account/active true}
                 {:db/id "sales-ic" :kontor.account/path "Income:Sales-IC"
                  :kontor.account/code "4400" :kontor.account/name "Sales — Intercompany"
                  :kontor.account/type :income :kontor.account/active true}
                 {:db/id "purch-ic" :kontor.account/path "Expenses:Purchases-IC"
                  :kontor.account/code "5400" :kontor.account/name "Purchases — Intercompany"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "jnl" :kontor.journal/code "GEN"
                  :kontor.journal/name "General" :kontor.journal/type :misc
                  :kontor.journal/active true}])
    conn))

;; ---------------------------------------------------------------------------
;; Resolution helpers
;; ---------------------------------------------------------------------------

(defn- acct [db path] (:db/id (d/entity db [:kontor.account/path path])))
(defn- ent  [db code] (:db/id (d/entity db [:kontor.entity/code code])))
(defn- comm [db sym]  (:db/id (d/entity db [:kontor.commodity/symbol sym])))
(defn- jnl  [db]      (:db/id (d/entity db [:kontor.journal/code "GEN"])))

(defn- amt=
  "Scale-insensitive numeric equality of two BigDecimals."
  [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (and (some? a) (some? b) (zero? (.compareTo a b))))

;; ===========================================================================
;; 1. convert against a persisted rate — the flagship signature
;;    (fx/convert money provider {:to … :at-date …})
;; ===========================================================================

(deftest convert-eur-to-usd-with-persisted-ecb-rate
  (testing "convert 100,000 EUR → USD at the persisted ECB spot rate 1.1050"
    (let [conn (bootstrap!)
          db   (d/db conn)
          p    (fxp/make-static-table-provider conn)
          ;; 100,000 EUR × 1.1050 = 110,500.00 USD (ECB ref 2023-12-29)
          out-by-sym (fx/convert (money/money 100000M "EUR") p
                                 {:to "USD" :at-date dec-29})
          ;; identical result whether :to is a symbol string or an eid
          out-by-eid (fx/convert (money/money 100000M "EUR") p
                                 {:to (comm db "USD") :at-date dec-29})]
      (is (= "USD" (:commodity out-by-sym)))
      (is (amt= 110500.00M (:amount out-by-sym))
          "100,000 EUR × 1.1050 = 110,500.00 USD")
      (is (amt= (:amount out-by-sym)
                (:amount (fx/convert (money/money 100000M "EUR") p
                                     {:to (comm db "USD") :at-date dec-29})))
          "string :to and eid :to must agree")
      (is (= (comm db "USD") (:commodity out-by-eid)))
      ;; and USD→EUR round-trips via the persisted inverse (1/1.1050)
      (let [back (fx/convert out-by-sym p {:to "EUR" :at-date dec-29})]
        (is (amt= 100000.00M (:amount back))
            "110,500 USD ÷ 1.1050 = 100,000.00 EUR (inverse of the stored rate)")))))

;; ===========================================================================
;; 2. to-functional-currency — US sub records a EUR parent invoice in USD
;; ===========================================================================

(deftest us-sub-records-eur-invoice-in-functional-usd
  (testing "IAS 21 §21: a foreign-currency transaction is recorded at the
            spot rate of the transaction date, in the entity's functional
            currency."
    (let [conn (bootstrap!)
          p    (fxp/make-static-table-provider conn)
          us   {:kontor.entity/functional-commodity "USD"}
          ;; DE parent invoices US sub 100,000 EUR; US books it in USD.
          eur-invoice (money/money 100000M "EUR")
          in-usd (fx/to-functional-currency eur-invoice us p {:at-date dec-29})]
      (is (= "USD" (:commodity in-usd)))
      (is (amt= 110500.00M (:amount in-usd))
          "100,000 EUR × 1.1050 = 110,500.00 USD recorded in functional books")
      ;; a USD-functional entity receiving a USD amount passes through untouched
      (let [same (money/money 500M "USD")]
        (is (identical? same (fx/to-functional-currency same us p {:at-date dec-29}))
            "already in functional commodity → no conversion (input returned as-is)")))))

;; ===========================================================================
;; 3. Intercompany elimination — paired postings negate & balance
;; ===========================================================================

;; DE parent sells to US sub. DE: AR-IC +100,000 EUR / Sales-IC -100,000 EUR.
;; US: Purchases +110,500 USD / AP-IC -110,500 USD (100k EUR × 1.1050).
;; Both tagged :kontor.transaction/intercompany-pair-id "P-IC-2023".
(defn- book-ic-pair! [conn]
  (let [db  (d/db conn)
        eur (comm db "EUR") usd (comm db "USD")
        j   (jnl db) de (ent db "de") us (ent db "us")]
    (posting/post-transaction!
     conn {:transaction {:kontor.transaction/journal j
                         :kontor.transaction/effective-date dec-29
                         :kontor.transaction/intercompany-pair-id "P-IC-2023"
                         :kontor.transaction/narration "DE side — IC sale"}
           :postings [{:kontor.posting/account (acct db "Assets:AR-IC")
                       :kontor.posting/commodity eur :kontor.posting/amount 100000M
                       :kontor.posting/entity de}
                      {:kontor.posting/account (acct db "Income:Sales-IC")
                       :kontor.posting/commodity eur :kontor.posting/amount -100000M
                       :kontor.posting/entity de}]})
    (posting/post-transaction!
     conn {:transaction {:kontor.transaction/journal j
                         :kontor.transaction/effective-date dec-29
                         :kontor.transaction/intercompany-pair-id "P-IC-2023"
                         :kontor.transaction/narration "US side — IC purchase"}
           :postings [{:kontor.posting/account (acct db "Expenses:Purchases-IC")
                       :kontor.posting/commodity usd :kontor.posting/amount 110500M
                       :kontor.posting/entity us}
                      {:kontor.posting/account (acct db "Liabilities:AP-IC")
                       :kontor.posting/commodity usd :kontor.posting/amount -110500M
                       :kontor.posting/entity us}]})))

(deftest eliminate-intercompany-pair-negates-and-balances
  (testing "The elimination tx posts the exact negation of every paired
            posting, stamped with the elimination entity, and sums to zero
            per (entity, commodity)."
    (let [conn (bootstrap!)
          _    (book-ic-pair! conn)
          db   (d/db conn)
          elim (ent db "elim")
          eur  (comm db "EUR") usd (comm db "USD")
          txd  (cons/eliminate-intercompany-pair-tx-data
                {:db db :pair-id "P-IC-2023" :elimination-entity elim
                 :journal (jnl db) :date dec-31})
          postings (filter :kontor.posting/amount txd)
          by-comm  (group-by :kontor.posting/commodity postings)
          sum-c    (fn [ps] (reduce (fn [a p]
                                      (.add ^java.math.BigDecimal a
                                            ^java.math.BigDecimal (:kontor.posting/amount p)))
                                    0M ps))]
      (is (= 4 (count postings)) "4 elimination postings — 2 per source tx")
      ;; N5 FIXED (note 196): the builder now emits :kontor.posting/commodity
      ;; as a bare eid (like :account and translate-trial-balance), not the
      ;; {:db/id N} pull-map it used to copy through. Pin that shape here so
      ;; the grouping above is not silently absorbing an inconsistency.
      (is (every? number? (map :kontor.posting/commodity postings))
          "commodity emitted as a bare eid, one consistent shape")
      (is (every? #(= elim (:kontor.posting/entity %)) postings)
          "every elimination posting is stamped with the elimination entity")
      (is (amt= 0M (sum-c (by-comm eur))) "EUR side of the elimination nets to zero")
      (is (amt= 0M (sum-c (by-comm usd))) "USD side of the elimination nets to zero")
      ;; the negations carry the ORIGINAL commodities (no FX on elimination);
      ;; compare scale-insensitively via BigInteger (amounts are whole)
      (let [as-int (fn [ps] (set (map #(bigint (:kontor.posting/amount %)) ps)))]
        (is (= #{-100000N 100000N} (as-int (by-comm eur))))
        (is (= #{-110500N 110500N} (as-int (by-comm usd))))))))

;; ===========================================================================
;; 4. IAS 21 translation with a real, hand-computed CTA
;; ===========================================================================

(deftest translate-us-sub-to-eur-with-exact-cta
  (testing "US sub standalone balance sheet translated to EUR per IAS 21:
            monetary asset @ closing, equity @ historical, income @ average.
            The residual is the cumulative translation adjustment (CTA)."
    (let [conn (bootstrap!)
          db0  (d/db conn)
          us   (ent db0 "us") grp (ent db0 "grp")
          usd  (comm db0 "USD")
          ;; US sub: Cash +500,000 / Common Stock -400,000 / Sales -100,000 (USD).
          _ (posting/post-transaction!
             conn {:transaction {:kontor.transaction/journal (jnl db0)
                                 :kontor.transaction/effective-date dec-29
                                 :kontor.transaction/narration "US sub opening + first sale"}
                   :postings [{:kontor.posting/account (acct db0 "Assets:Cash")
                               :kontor.posting/commodity usd :kontor.posting/amount 500000M
                               :kontor.posting/entity us}
                              {:kontor.posting/account (acct db0 "Equity:CommonStock")
                               :kontor.posting/commodity usd :kontor.posting/amount -400000M
                               :kontor.posting/entity us}
                              {:kontor.posting/account (acct db0 "Income:Sales-IC")
                               :kontor.posting/commodity usd :kontor.posting/amount -100000M
                               :kontor.posting/entity us}]})
          db   (d/db conn)
          p    (fxp/make-static-table-provider conn)
          us-tb (trial/trial-balance conn {:entity us})
          txd  (cons/translate-trial-balance-tx-data
                {:db db :source-entity us :consolidation-entity grp
                 :presentation-commodity "EUR" :fx-provider p
                 :at-date dec-31 :journal (jnl db)
                 :cta-account (acct db "Equity:CTA") :trial-balance us-tb})
          postings (filter :kontor.posting/amount txd)
          by-acct  (into {} (map (juxt :kontor.posting/account :kontor.posting/amount) postings))
          cash  (acct db "Assets:Cash")
          stock (acct db "Equity:CommonStock")
          sales (acct db "Income:Sales-IC")
          cta   (acct db "Equity:CTA")]
      ;; Cash    500,000 × 0.9050 (closing)    =  452,500.00 EUR
      ;; Stock  -400,000 × 0.9200 (historical) = -368,000.00 EUR
      ;; Sales  -100,000 × 0.9100 (average)    =  -91,000.00 EUR
      ;; translated sum = 452,500 - 368,000 - 91,000 = -6,500.00 EUR
      ;; CTA plug = -(sum) = +6,500.00 EUR (a translation loss, debit to equity)
      (is (amt= 452500.00M (by-acct cash))  "Cash @ closing 0.9050")
      (is (amt= -368000.00M (by-acct stock)) "Common Stock @ historical 0.9200")
      (is (amt= -91000.00M (by-acct sales))  "Sales @ average 0.9100")
      (is (amt= 6500.00M (by-acct cta))
          "CTA = -(452,500 - 368,000 - 91,000) = +6,500.00 EUR")
      ;; the whole translation entry balances in the presentation commodity
      (is (amt= 0M (reduce (fn [a p] (.add ^java.math.BigDecimal a
                                           ^java.math.BigDecimal (:kontor.posting/amount p)))
                           0M postings))
          "translation entry sums to zero in EUR (the CTA is the balancing plug)")
      (is (every? #(= grp (:kontor.posting/entity %)) postings)
          "translated postings are stamped with the consolidation entity"))))

;; ===========================================================================
;; 5. consolidate! — the full atomic cross-border roundtrip
;; ===========================================================================

(deftest consolidate-full-group-balances-with-exact-cta
  (testing "consolidate! translates each operating entity to EUR and
            eliminates the intercompany pair. The consolidated group must
            balance per commodity, carry the exact CTA for the USD sub, and
            the intercompany accounts must net to zero across group + elim."
    (let [conn (bootstrap!)
          _    (book-ic-pair! conn)
          db0  (d/db conn)
          grp  (ent db0 "grp") elim (ent db0 "elim")
          p    (fxp/make-static-table-provider conn)
          _ (cons/consolidate!
             {:conn conn :group-root grp
              :consolidation-entity grp :elimination-entity elim
              :presentation-commodity "EUR" :fx-provider p
              :at-date dec-31 :journal (jnl db0)
              :cta-account (acct db0 "Equity:CTA")})
          states {:include-states #{:draft :posted}}
          group-tb (trial/trial-balance conn (merge {:entity grp} states))
          elim-tb  (trial/trial-balance conn (merge {:entity elim} states))
          db   (d/db conn)
          eur  (comm db "EUR") usd (comm db "USD")
          ar   (acct db "Assets:AR-IC")   sales (acct db "Income:Sales-IC")
          cta  (acct db "Equity:CTA")]
      ;; --- The group's own EUR postings balance (each translation entry
      ;;     sums to zero because the CTA absorbs the residual). ---
      (is (trial/balanced? group-tb)
          "consolidated group trial balance sums to zero per commodity")
      (is (trial/balanced? elim-tb)
          "elimination entity trial balance sums to zero per commodity")

      ;; --- Exact CTA for the USD sub. Only the US side has a residual;
      ;;     DE (EUR→EUR identity) contributes none.
      ;;     Purchases  110,500 × 0.9100 (average)  =  100,555.00 EUR
      ;;     AP-IC     -110,500 × 0.9050 (closing)  = -100,002.50 EUR
      ;;     translated sum = 100,555.00 - 100,002.50 = 552.50
      ;;     CTA plug = -(552.50) = -552.50 EUR
      (is (amt= -552.50M (:amount (get-in group-tb [cta eur])))
          "group CTA = -552.50 EUR (USD sub translation residual)")

      ;; --- Intercompany AR-IC / Sales-IC net to zero across group + elim.
      ;;     DE translated (identity): AR-IC +100,000, Sales-IC -100,000 EUR
      ;;     Elim negations:           AR-IC -100,000, Sales-IC +100,000 EUR
      (let [rolled (fn [a c]
                     (reduce (fn [acc m] (money/add acc m))
                             (money/zero c)
                             (keep #(get-in % [a c]) [group-tb elim-tb])))]
        (is (money/zero? (rolled ar eur))
            "AR-IC at group level (translation + elimination) = 0 EUR")
        (is (money/zero? (rolled sales eur))
            "Sales-IC at group level = 0 EUR"))

      ;; --- The elimination carries the ORIGINAL USD amounts on the US side.
      (is (amt= 110500M (:amount (get-in elim-tb [(acct db "Liabilities:AP-IC") usd])))
          "elim AP-IC = +110,500 USD (negation of US sub's -110,500)"))))

;; ===========================================================================
;; 6. consolidate! is idempotent on re-run (no duplicate CTA / drafts)
;; ===========================================================================

(deftest consolidate-is-idempotent-across-reruns
  (testing "Re-running consolidate! for the same (source, at-date) must not
            spawn duplicate translation drafts nor double the CTA."
    (let [conn (bootstrap!)
          _    (book-ic-pair! conn)
          db0  (d/db conn)
          grp  (ent db0 "grp") elim (ent db0 "elim")
          p    (fxp/make-static-table-provider conn)
          input {:conn conn :group-root grp
                 :consolidation-entity grp :elimination-entity elim
                 :presentation-commodity "EUR" :fx-provider p
                 :at-date dec-31 :journal (jnl db0)
                 :cta-account (acct db0 "Equity:CTA")}
          count-translations #(d/q '[:find (count ?t) .
                                     :where [?t :kontor.transaction/consolidation-kind :translation]]
                                   (d/db conn))
          _ (cons/consolidate! input)
          after-1 (count-translations)
          _ (cons/consolidate! input)
          after-2 (count-translations)
          states {:include-states #{:draft :posted}}
          cta-eur (:amount (get-in (trial/trial-balance conn (merge {:entity grp} states))
                                   [(acct (d/db conn) "Equity:CTA")
                                    (comm (d/db conn) "EUR")]))]
      ;; 2 operating entities (DE + US) → 2 translation txs, stable across runs
      (is (= after-1 after-2)
          "translation tx count is stable across re-runs (idempotent)")
      (is (amt= -552.50M cta-eur)
          "CTA is NOT doubled by the second run — still -552.50 EUR"))))
