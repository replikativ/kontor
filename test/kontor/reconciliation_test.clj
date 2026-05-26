(ns kontor.reconciliation-test
  "End-to-end bank reconciliation test:
     - install SKR04 chart + invariants
     - post 3 sales invoices with distinct partners and amounts
     - simulate a bank statement with 3 inflows that should match
       (one via reference-id, one via exact-amount + partner, one
       via exact-amount only) plus 1 unrelated outflow
     - run ingest → suggest-match → commit-match! per line
     - verify open-receivables-by-tx returns empty after, the bank
       account balance reflects the inflows, and audit links
       (:transaction/settles) are correctly populated."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.posting :as posting]
            [kontor.reconciliation :as recon]
            [kontor.validation :as v]
            [kontor.l10n-de.chart :as chart]))

(def jan-15 #inst "2026-01-15T00:00:00Z")
(def feb-1  #inst "2026-02-01T00:00:00Z")
(def feb-5  #inst "2026-02-05T00:00:00Z")
(def feb-10 #inst "2026-02-10T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn
                [{:journal/code "INV"
                  :journal/name "Sales invoices"
                  :journal/type :sale
                  :journal/active true}
                 {:journal/code "BANK"
                  :journal/name "Bank movements"
                  :journal/type :bank
                  :journal/active true}
                 ;; Three partners
                 {:kontor.partner/external-id "ACME"  :kontor.partner/name "ACME GmbH"
                  :kontor.partner/kind :customer :kontor.partner/country-code "DE"}
                 {:kontor.partner/external-id "BETA"  :kontor.partner/name "Beta AG"
                  :kontor.partner/kind :customer :kontor.partner/country-code "DE"}
                 {:kontor.partner/external-id "GAMMA" :kontor.partner/name "Gamma KG"
                  :kontor.partner/kind :customer :kontor.partner/country-code "DE"}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- post-invoice!
  "Sales invoice: 1190 EUR (1000 net + 19% USt) to a partner."
  [conn external-id partner-extid net]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        recv (ace db "1400")
        rev (ace db "4400")
        ust (ace db "3801")
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        partner (:db/id (d/entity db [:kontor.partner/external-id partner-extid]))
        net-bd (bigdec net)
        vat (.setScale (.multiply net-bd (bigdec "0.19"))
                       2 java.math.RoundingMode/HALF_EVEN)
        gross (.add net-bd vat)
        tx (-> (posting/build-transaction
                {:transaction
                 {:transaction/external-id external-id
                  :transaction/journal jnl
                  :transaction/effective-date jan-15
                  :transaction/narration external-id
                  :transaction/partner partner
                  :transaction/state :posted
                  :transaction/posted-at jan-15}
                 :postings
                 [{:posting/account recv :posting/amount gross
                   :posting/commodity eur :posting/posted-at jan-15}
                  {:posting/account rev :posting/amount (.negate net-bd)
                   :posting/commodity eur :posting/posted-at jan-15}
                  {:posting/account ust :posting/amount (.negate vat)
                   :posting/commodity eur :posting/posted-at jan-15}]}))]
    (v/transact-with-validation conn tx)
    gross))

(defn- seed-three-invoices [conn]
  (let [a (post-invoice! conn "INV-2026-001" "ACME"  1000) ; 1190.00 gross
        b (post-invoice! conn "INV-2026-002" "BETA"  2000) ; 2380.00 gross
        c (post-invoice! conn "INV-2026-003" "GAMMA" 500)] ; 595.00 gross
    {:acme 1190.00M :beta 2380.00M :gamma 595.00M
     :acme-amount a :beta-amount b :gamma-amount c}))

;; ============================================================================
;; Open AR discovery
;; ============================================================================

(deftest open-receivables-finds-three-invoices
  (testing "After posting 3 invoices, open-receivables-by-tx returns
            all three with the right partner refs and amounts."
    (let [conn (bootstrap)
          _ (seed-three-invoices conn)
          db (d/db conn)
          opens (recon/open-receivables-by-tx db #{"1400"})
          ext-ids (set (map :external-id opens))
          by-extid (into {} (map (juxt :external-id :open-amount)) opens)]
      (is (= #{"INV-2026-001" "INV-2026-002" "INV-2026-003"} ext-ids))
      (is (= 1190.00M (get by-extid "INV-2026-001")))
      (is (= 2380.00M (get by-extid "INV-2026-002")))
      (is (= 595.00M  (get by-extid "INV-2026-003")))
      ;; Each has a partner.
      (is (every? :partner-eid opens)))))

;; ============================================================================
;; Ingestion
;; ============================================================================

(defn- bank-candidates
  "Synthetic 4-row bank statement matching the three invoices + one
   unrelated outflow."
  []
  [{:bank :test :date feb-1 :amount 1190.00M
    :counterparty "ACME GmbH"
    :description "Rechnung INV-2026-001 vielen dank"
    :raw-row ["02/01/2026" "+1190,00" "ACME GmbH" "INV-2026-001"]}
   {:bank :test :date feb-5 :amount 2380.00M
    :counterparty "BETA AG SEPA"
    :description "Sammelueberweisung"
    :raw-row ["05/01/2026" "+2380,00" "BETA AG SEPA" "Sammel"]}
   {:bank :test :date feb-10 :amount 595.00M
    :counterparty "Gamma KG"
    :description "Pmt"
    :raw-row ["10/01/2026" "+595,00" "Gamma KG" "Pmt"]}
   {:bank :test :date feb-10 :amount -45.00M
    :counterparty "Stadtwerke" :category :nebenkosten
    :description "Stromrechnung Februar"
    :raw-row ["10/01/2026" "-45,00" "Stadtwerke" "Strom"]}])

(deftest ingest-statement-is-idempotent
  (testing "Re-ingesting the same statement does not duplicate bank-
            lines (external-id is unique-identity, derived from a
            hash of the raw row)."
    (let [conn (bootstrap)
          _ (seed-three-invoices conn)
          db (d/db conn)
          bank-acct (ace db "1200")
          eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
          opts {:source-account-eid bank-acct :commodity-eid eur}]
      (recon/ingest-statement! conn (bank-candidates) opts)
      (let [n1 (count (d/q '[:find [?bl ...] :where [?bl :bank-line/external-id _]]
                           (d/db conn)))]
        (is (= 4 n1) "first import lands 4 lines"))
      (recon/ingest-statement! conn (bank-candidates) opts)
      (let [n2 (count (d/q '[:find [?bl ...] :where [?bl :bank-line/external-id _]]
                           (d/db conn)))]
        (is (= 4 n2) "re-import is idempotent")))))

;; ============================================================================
;; Match suggestions
;; ============================================================================

(deftest reference-id-match-wins-with-high-confidence
  (testing "Bank line with INV-2026-001 in description matches via
            :reference-id at confidence 0.95."
    (let [conn (bootstrap)
          _ (seed-three-invoices conn)
          db (d/db conn)
          bank-acct (ace db "1200")
          eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
          _ (recon/ingest-statement! conn (bank-candidates)
                                     {:source-account-eid bank-acct
                                      :commodity-eid eur})
          db (d/db conn)
          ;; Find the ACME line
          [acme-bl] (d/q '[:find [?bl]
                           :where [?bl :bank-line/counterparty "ACME GmbH"]]
                         db)
          suggestions (recon/suggest-match db acme-bl {})]
      (is (seq suggestions))
      (is (= :reference-id (:strategy (first suggestions))))
      (is (= 0.95 (:confidence (first suggestions)))))))

(deftest exact-amount-match-with-partner-overlap
  (testing "Beta line: amount 2380.00 matches one open invoice
            (Beta's), and counterparty 'BETA AG SEPA' overlaps with
            partner name 'Beta AG' → confidence 0.9."
    (let [conn (bootstrap)
          _ (seed-three-invoices conn)
          db (d/db conn)
          bank-acct (ace db "1200")
          eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
          _ (recon/ingest-statement! conn (bank-candidates)
                                     {:source-account-eid bank-acct
                                      :commodity-eid eur})
          db (d/db conn)
          [beta-bl] (d/q '[:find [?bl]
                           :where [?bl :bank-line/counterparty "BETA AG SEPA"]]
                         db)
          suggestions (recon/suggest-match db beta-bl {})
          best (first suggestions)]
      (is (= :exact-amount (:strategy best)))
      (is (= 0.9 (:confidence best)) "partner-name overlap boosts confidence"))))

(deftest multi-line-settlement-finds-subset-sum
  (testing "ACME pays €1190 + €595 = €1785 in a single bank
            transfer (Sammelüberweisung). The matcher should find
            the subset of ACME's open invoices summing to 1785 and
            return a :multi-line match settling both invoices."
    (let [conn (bootstrap)
          ;; Two ACME invoices + one BETA invoice
          _ (post-invoice! conn "INV-2026-001" "ACME"  1000) ; 1190.00
          _ (post-invoice! conn "INV-2026-003" "ACME"   500) ; 595.00
          _ (post-invoice! conn "INV-2026-002" "BETA"  2000) ; 2380.00
          db (d/db conn)
          bank-acct (ace db "1200")
          eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
          consolidated [{:bank :test :date feb-5 :amount 1785.00M
                         :counterparty "ACME GmbH"
                         :description "Zahlung mehrere Rechnungen"
                         :raw-row ["02/05/2026" "+1785,00" "ACME GmbH" "multi"]}]
          _ (recon/ingest-statement! conn consolidated
                                     {:source-account-eid bank-acct
                                      :commodity-eid eur})
          db (d/db conn)
          [bl] (d/q '[:find [?bl] :where [?bl :bank-line/amount 1785.00M]] db)
          best (first (recon/suggest-match db bl {}))]
      (is (= :multi-line (:strategy best))
          (str "expected :multi-line; got: " (:strategy best)))
      (is (= 0.85 (:confidence best)))
      ;; Both ACME invoices in the settle vector
      (is (= 2 (count (:transactions (:match best))))
          "expected 2 settled transactions")
      (let [settled-ext-ids
            (set (map (fn [eid]
                        (:transaction/external-id
                         (d/pull db [:transaction/external-id] eid)))
                      (:transactions (:match best))))]
        (is (= #{"INV-2026-001" "INV-2026-003"} settled-ext-ids)
            (str "expected ACME's two invoices; got: " settled-ext-ids))))))

(deftest multi-line-settlement-commit-clears-both-invoices
  (testing "Committing the multi-line match marks both invoices
            paid (open-receivables-by-tx returns only BETA's after)."
    (let [conn (bootstrap)
          _ (post-invoice! conn "INV-2026-001" "ACME"  1000)
          _ (post-invoice! conn "INV-2026-003" "ACME"   500)
          _ (post-invoice! conn "INV-2026-002" "BETA"  2000)
          db0 (d/db conn)
          bank-acct (ace db0 "1200")
          eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
          bank-jnl (:db/id (d/entity db0 [:journal/code "BANK"]))
          _ (recon/ingest-statement! conn
                                     [{:bank :test :date feb-5 :amount 1785.00M
                                       :counterparty "ACME GmbH"
                                       :description "Sammel"
                                       :raw-row ["02/05/2026" "+1785,00" "ACME" "Sammel"]}]
                                     {:source-account-eid bank-acct
                                      :commodity-eid eur})
          db (d/db conn)
          [bl] (d/q '[:find [?bl] :where [?bl :bank-line/amount 1785.00M]] db)
          best (first (recon/suggest-match db bl {}))
          _ (recon/commit-match! conn bl (:match best) bank-jnl {})
          opens (recon/open-receivables-by-tx (d/db conn) #{"1400"})
          ext-ids (set (map :external-id opens))]
      ;; Only BETA remains open
      (is (= #{"INV-2026-002"} ext-ids)
          (str "expected only BETA open; got: " ext-ids)))))

(deftest unmatched-categorizer-fallback
  (testing "Stromrechnung outflow has no matching open invoice
            (it's payment for utilities, not AR/AP), but the
            categorizer set :nebenkosten — the resolver maps that
            to 6400."
    (let [conn (bootstrap)
          _ (seed-three-invoices conn)
          db (d/db conn)
          bank-acct (ace db "1200")
          eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
          _ (recon/ingest-statement! conn (bank-candidates)
                                     {:source-account-eid bank-acct
                                      :commodity-eid eur})
          db (d/db conn)
          [strom-bl] (d/q '[:find [?bl]
                            :where [?bl :bank-line/counterparty "Stadtwerke"]]
                          db)
          suggestions (recon/suggest-match db strom-bl
                                           {:category-resolver
                                            (fn [c] (when (= c :nebenkosten)
                                                      (ace db "6400")))})]
      (is (seq suggestions))
      (is (= :category (:strategy (first suggestions))))
      (is (= :categorize (:kind (:match (first suggestions)))))
      (is (= (ace db "6400") (:contra-account (:match (first suggestions))))))))

;; ============================================================================
;; Commit
;; ============================================================================

(deftest commit-match-creates-payment-transaction-and-clears-ar
  (testing "After committing a :settle match for ACME, open-
            receivables-by-tx no longer includes INV-2026-001, the
            bank account balance increased by 1190, and the
            payment transaction has :transaction/settles → INV-001."
    (let [conn (bootstrap)
          _ (seed-three-invoices conn)
          db0 (d/db conn)
          bank-acct (ace db0 "1200")
          eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
          inv-jnl (:db/id (d/entity db0 [:journal/code "INV"]))
          bank-jnl (:db/id (d/entity db0 [:journal/code "BANK"]))
          _ (recon/ingest-statement! conn (bank-candidates)
                                     {:source-account-eid bank-acct
                                      :commodity-eid eur})
          db (d/db conn)
          [acme-bl] (d/q '[:find [?bl]
                           :where [?bl :bank-line/counterparty "ACME GmbH"]]
                         db)
          best (first (recon/suggest-match db acme-bl {}))
          {:keys [payment-tx-eid]} (recon/commit-match!
                                    conn acme-bl (:match best) bank-jnl {})
          db (d/db conn)
          opens (recon/open-receivables-by-tx db #{"1400"})
          ext-ids (set (map :external-id opens))
          payment-tx (d/pull db [:transaction/external-id
                                 {:transaction/settles
                                  [:transaction/external-id]}]
                             payment-tx-eid)
          bank-balance (reduce
                        (fn [^java.math.BigDecimal acc [_ amt]]
                          (.add acc amt))
                        0M
                        (d/q '[:find ?p ?amt
                               :in $ ?bank-acct
                               :where
                               [?p :posting/account ?bank-acct]
                               [?p :posting/amount ?amt]]
                             db bank-acct))]
      ;; Open AR no longer includes INV-001
      (is (not (contains? ext-ids "INV-2026-001")))
      ;; Other invoices still open
      (is (contains? ext-ids "INV-2026-002"))
      (is (contains? ext-ids "INV-2026-003"))
      ;; Payment links to settled invoice
      (is (= "INV-2026-001"
             (-> payment-tx :transaction/settles first :transaction/external-id)))
      ;; Bank balance = 1190
      (is (= 1190.00M bank-balance)))))

(deftest commit-match-payment-transaction-passes-validators
  (testing "The bank-side payment transaction created by commit-
            match! is balanced (sums to zero) and survives the
            kernel's validation chain."
    (let [conn (bootstrap)
          _ (seed-three-invoices conn)
          db0 (d/db conn)
          bank-acct (ace db0 "1200")
          eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
          bank-jnl (:db/id (d/entity db0 [:journal/code "BANK"]))
          _ (recon/ingest-statement! conn (bank-candidates)
                                     {:source-account-eid bank-acct
                                      :commodity-eid eur})
          db (d/db conn)
          [gamma-bl] (d/q '[:find [?bl]
                            :where [?bl :bank-line/counterparty "Gamma KG"]]
                          db)
          best (first (recon/suggest-match db gamma-bl {}))]
      ;; No throw means sum-to-zero / sealing / period passed.
      (is (some? (recon/commit-match! conn gamma-bl (:match best)
                                      bank-jnl {}))))))

(deftest unmatched-queue-shrinks-as-we-reconcile
  (testing "After committing one match, unmatched-queue returns 3
            entries (was 4 before)."
    (let [conn (bootstrap)
          _ (seed-three-invoices conn)
          db0 (d/db conn)
          bank-acct (ace db0 "1200")
          eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
          bank-jnl (:db/id (d/entity db0 [:journal/code "BANK"]))
          _ (recon/ingest-statement! conn (bank-candidates)
                                     {:source-account-eid bank-acct
                                      :commodity-eid eur})]
      (is (= 4 (count (recon/unmatched-queue (d/db conn)))))
      (let [db (d/db conn)
            [acme-bl] (d/q '[:find [?bl]
                             :where [?bl :bank-line/counterparty "ACME GmbH"]]
                           db)
            best (first (recon/suggest-match db acme-bl {}))]
        (recon/commit-match! conn acme-bl (:match best) bank-jnl {}))
      (is (= 3 (count (recon/unmatched-queue (d/db conn))))))))
