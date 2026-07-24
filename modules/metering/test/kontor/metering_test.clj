(ns kontor.metering-test
  "Piece C (research note 190): meter a window of kontor-conformant usage
   subledger rows → balanced, sealed, idempotent double-entry accruals,
   with reconciliation + settlement. Provider-agnostic — synthesizes rows
   directly (the exact shape dvergr's `usage->subledger-rows` emits), so
   this runs inside kontor's suite without a dvergr dependency."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.money :as m]
            [kontor.metering :as meter]
            [kontor.reporting.balance :as balance]
            [kontor.reporting.trial :as trial]))

(defn- μ$ [micros commodity]
  (m/money (.movePointLeft (bigdec micros) 6) commodity))

(defn- row [micros commodity settlement provider resource project]
  {:money      (μ$ micros commodity)
   :settlement settlement
   :dimensions {:project project :provider provider :resource resource}})

(defn- fresh-book []
  (let [conn (core/create-test-db)]
    (d/transact conn [{:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
                       :kontor.commodity/precision 2}
                      {:kontor.journal/code "GEN" :kontor.journal/type :general}])
    conn))

(def config
  {:period-key     "2026-07"
   :effective-date #inst "2026-07-31"
   :journal        [:kontor.journal/code "GEN"]
   ;; dev rooms are R&D (OpEx); everything else is customer-serving COGS.
   :classify       (fn [dims] (if (= :room-dev (:project dims)) :r&d :cogs))})

(def rows
  ;; room-prod / anthropic / prepaid: two resources → ONE cogs group ($0.0105)
  [(row 3000 :USD :prepaid  :anthropic      :token-input  :room-prod)
   (row 7500 :USD :prepaid  :anthropic      :token-output :room-prod)
   ;; room-prod / postpaid vendor: one cogs group ($0.005)
   (row 5000 :USD :postpaid :acme-inference :token-input  :room-prod)
   ;; room-dev / anthropic / prepaid: classified R&D ($0.002)
   (row 2000 :USD :prepaid  :anthropic      :token-input  :room-dev)])

(defn- path-balance
  "Signed GL balance of a chart path in `commodity` (a symbol keyword like
   `:USD`). Credit balance is negative (kontor sign convention: positive =
   debit). `account-balance` keys by the commodity EID, so the key is
   normalised back to a symbol rather than picked by position."
  [conn path commodity]
  (if-let [eid (d/q '[:find ?e . :in $ ?p :where [?e :kontor.account/path ?p]] @conn path)]
    (or (some (fn [[c m]]
                (when (= commodity (balance/resolve-commodity-symbol @conn c))
                  (:amount m)))
              (balance/account-balance conn eid {}))
        0M)
    0M))

(defn- accrued
  "Signed balance of the postpaid accrued-liability account for `provider`."
  [conn provider commodity]
  (path-balance conn (str "Liabilities:Accrued:AI-Provider:"
                          (clojure.string/capitalize (name provider)))
                commodity))

(deftest summarize-produces-balanced-idempotent-accruals
  (let [conn (fresh-book)]
    (meter/ensure-accounts! conn rows config)

    (testing "first run posts one balanced entry per (project,class,settlement,provider,commodity) group"
      (let [{:keys [posted skipped]} (meter/summarize! conn rows config)]
        (is (= 3 (count posted)) "3 groups: prod/anthropic/cogs, prod/acme/cogs, dev/anthropic/r&d")
        (is (empty? skipped))))

    (testing "the whole ledger balances (Ker σ)"
      (is (trial/balanced? (trial/trial-balance conn))))

    (testing "postings carry the project + provider dimensions (ADR-097)"
      (let [dim-vals (d/q '[:find [?v ...]
                            :where
                            [?p :kontor.posting/dimensions ?d]
                            [?d :kontor.posting-dimension/value ?v]]
                          @conn)]
        (is (contains? (set dim-vals) "room-prod"))
        (is (contains? (set dim-vals) "anthropic"))))

    (testing "postpaid accrual reconciles to the real invoice ($0.005)"
      (let [{:keys [expected ok?]} (meter/reconcile conn config :acme-inference (μ$ 5000 :USD))]
        (is ok? "accrued postpaid liability reconciles to the $0.005 invoice")
        (is (= 0 (.compareTo (bigdec "0.005") (:amount expected))))))

    (testing "idempotency: re-running the same window posts nothing"
      (let [before (accrued conn :acme-inference :USD)
            {:keys [posted skipped]} (meter/summarize! conn rows config)]
        (is (empty? posted))
        (is (= 3 (count skipped)))
        ;; `trial/balanced?` is a PROXY: a DUPLICATED entry is two balanced
        ;; legs and leaves the trial balance just as balanced (note 198
        ;; audit). Assert the accrual account itself.
        ;; postpaid acme-inference accrued 5000 µ$ = $0.005 → a credit
        ;; balance of −0.005; a double-post reads −0.010.
        (is (= 0 (.compareTo (bigdec "-0.005") before)))
        (is (= 0 (.compareTo (bigdec "-0.005") (accrued conn :acme-inference :USD)))
            "the accrued liability is unchanged by the re-run")
        (is (trial/balanced? (trial/trial-balance conn)))))

    (testing "reconcile flags a mismatched invoice"
      (let [{:keys [delta ok?]} (meter/reconcile conn config :acme-inference (μ$ 6000 :USD))]
        (is (not ok?))
        (is (= 0 (.compareTo (bigdec "0.001") (:amount delta))))))

    (testing "settle! clears the postpaid accrual against cash and stays balanced"
      (meter/ensure-accounts! conn rows config)
      (d/transact conn [{:kontor.account/path "Assets:Cash" :kontor.account/type :asset}])
      (meter/settle! conn config :acme-inference :postpaid (μ$ 5000 :USD)
                     {:journal [:kontor.journal/code "GEN"]
                      :effective-date #inst "2026-08-05" :ref "inv-001"})
      (let [{:keys [ok?]} (meter/reconcile conn config :acme-inference (m/money "0" :USD))]
        (is ok? "after settlement the accrued liability is back to zero"))
      (is (trial/balanced? (trial/trial-balance conn))))))

;; ============================================================================
;; note 198 audit HIGH-6 — the :external-id must name every grouping dimension
;; ============================================================================

(def ^:private collision-rows
  "Three rows that share (project, provider, class) but differ on SETTLEMENT
   and COMMODITY — the two dimensions `group-key` grouped on and the
   `:external-id` did not name. They are three distinct accruals landing on
   three distinct accounts."
  [(row 10000 :USD :prepaid  :anthropic :token-input :room-prod)   ; $0.010
   (row 20000 :USD :postpaid :anthropic :token-input :room-prod)   ; $0.020
   (row 30000 :EUR :prepaid  :anthropic :token-input :room-prod)]) ; €0.030

(deftest external-id-names-settlement-and-commodity
  (let [conn (fresh-book)
        _ (d/transact conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                             :kontor.commodity/precision 2}])
        cfg (assoc config :classify (constantly :cogs))]
    (meter/ensure-accounts! conn collision-rows cfg)

    (testing "the ids are distinct — a narrower id collapsed all three into one"
      (let [ids (map :external-id (meter/accrual-entries collision-rows cfg))]
        (is (= 3 (count (set ids)))
            "3 groups ⇒ 3 external-ids; before the fix all three read
             kontor-meter|2026-07|room-prod|anthropic|cogs")))

    (testing "all three reach the GL — a collision reads as :skipped, which is
              indistinguishable from a legitimate re-run"
      (let [{:keys [posted skipped]} (meter/summarize! conn collision-rows cfg)]
        (is (= 3 (count posted)))
        (is (empty? skipped))))

    (testing "and each lands on the account its settlement + commodity dictate"
      ;; prepaid → Assets:Prepaid-AI-Credits:Anthropic (credit, negative)
      ;;   USD 0.010 and EUR 0.030 on the SAME account, different commodities
      ;; postpaid → Liabilities:Accrued:AI-Provider:Anthropic, USD 0.020
      ;; expense → Expenses:AI-Compute:COGS, USD 0.010+0.020 = 0.030, EUR 0.030
      (is (= 0 (.compareTo (bigdec "-0.010")
                           (path-balance conn "Assets:Prepaid-AI-Credits:Anthropic" :USD))))
      (is (= 0 (.compareTo (bigdec "-0.030")
                           (path-balance conn "Assets:Prepaid-AI-Credits:Anthropic" :EUR))))
      (is (= 0 (.compareTo (bigdec "-0.020")
                           (accrued conn :anthropic :USD)))
          "the postpaid accrual account exists and carries $0.020")
      (is (= 0 (.compareTo (bigdec "0.030")
                           (path-balance conn "Expenses:AI-Compute:COGS" :USD))))
      (is (= 0 (.compareTo (bigdec "0.030")
                           (path-balance conn "Expenses:AI-Compute:COGS" :EUR))))
      (is (trial/balanced? (trial/trial-balance conn))))

    (testing "re-running is still a no-op"
      (let [{:keys [posted skipped]} (meter/summarize! conn collision-rows cfg)]
        (is (empty? posted))
        (is (= 3 (count skipped)))
        (is (= 0 (.compareTo (bigdec "-0.020") (accrued conn :anthropic :USD)))
            "the accrual is not doubled")))))

(deftest summarize-refuses-a-within-batch-external-id-collision
  (testing "if the id ever stops naming every dimension again, summarize!
            throws rather than silently dropping an accrual as :skipped"
    (let [conn (fresh-book)]
      (with-redefs [meter/external-id (fn [period-key _gk] (str "fixed|" period-key))]
        (meter/ensure-accounts! conn rows config)
        (let [e (try (meter/summarize! conn rows config)
                     nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (some? e))
          (is (= :metering/external-id-collision (:type (ex-data e)))))))))
