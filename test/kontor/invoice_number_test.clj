(ns kontor.invoice-number-test
  "ADR-172 — an invoice's legal number is the gapless number its journal
   allocated when it was sent; the caller's external id stays its identity."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.document.invoice :as invoice]
            [kontor.invoice.schema :as inv-schema]
            [kontor.l10n-de.chart :as chart]
            [kontor.l10n-de.invoice :as inv-de]
            [kontor.banking.payment-term :as pt]
            [kontor.numbering :as numbering]
            [kontor.settlement :as st]))

(def ^:private jan-1 #inst "2025-01-01T00:00:00Z")

(defn- bootstrap [numbered?]
  (let [conn (core/create-test-db)]
    (inv-schema/install! conn)
    (chart/install! conn)
    (pt/install-standard-terms! conn)
    (d/transact conn
                [{:kontor.journal/code "INV" :kontor.journal/name "Sales invoices"
                  :kontor.journal/type :sale :kontor.journal/active true}
                 {:kontor.partner/external-id "OWN" :kontor.partner/name "Self GmbH"
                  :kontor.partner/kind :company :kontor.partner/country-code "DE"
                  :kontor.partner/tax-id "DE111111111"}
                 {:kontor.partner/external-id "ACME" :kontor.partner/name "ACME GmbH"
                  :kontor.partner/kind :customer :kontor.partner/country-code "DE"
                  :kontor.partner/tax-id "DE222222222"}
                 {:kontor.period/start jan-1 :kontor.period/end #inst "2026-01-01T00:00:00Z"
                  :kontor.period/tag :normal :kontor.period/name "FY2025"}])
    (when numbered?
      (numbering/configure-journal! conn [:kontor.journal/code "INV"]
                                    {:prefix "RE/{year}/" :reset :yearly :padding 4}))
    conn))

(defn- create-and-send! [conn ext-id date]
  (let [db (d/db conn)]
    (invoice/create! conn
                     {:kontor.invoice/external-id ext-id
                      :kontor.invoice/issue-date date
                      :kontor.invoice/seller (:db/id (d/entity db [:kontor.partner/external-id "OWN"]))
                      :kontor.invoice/buyer (:db/id (d/entity db [:kontor.partner/external-id "ACME"]))
                      :kontor.invoice/payment-term (:db/id (pt/by-code db "NET30"))
                      :kontor.invoice/currency "EUR"
                      :kontor.invoice/lines
                      [{:kontor.invoice-line/sequence 1 :kontor.invoice-line/name "Beratung"
                        :kontor.invoice-line/quantity 10M :kontor.invoice-line/unit-code "HUR"
                        :kontor.invoice-line/unit-price 200M
                        :kontor.invoice-line/vat-rate 19.0M :kontor.invoice-line/vat-category "S"}]})
    (let [inv (:db/id (d/entity (d/db conn) [:kontor.invoice/external-id ext-id]))]
      (invoice/send! conn inv (partial inv-de/posting-builder {}))
      (d/entity (d/db conn) inv))))

(deftest a-sent-invoice-carries-its-gapless-number
  (let [conn (bootstrap true)
        a (create-and-send! conn "beleg-7f3a" #inst "2025-03-01T00:00:00Z")
        b (create-and-send! conn "beleg-91c2" #inst "2025-03-02T00:00:00Z")]
    (is (= "RE/2025/0001" (:kontor.invoice/number a)))
    (is (= "RE/2025/0002" (:kontor.invoice/number b)))
    (testing "the caller's id stays the identity"
      (is (= "beleg-7f3a" (:kontor.invoice/external-id a))))
    (is (numbering/gapless? (d/db conn) [:kontor.journal/code "INV"]))))

(deftest a-draft-has-no-number
  (let [conn (bootstrap true)]
    (invoice/create! conn
                     {:kontor.invoice/external-id "draft-1"
                      :kontor.invoice/issue-date #inst "2025-03-01T00:00:00Z"
                      :kontor.invoice/currency "EUR"
                      :kontor.invoice/lines [{:kontor.invoice-line/sequence 1
                                              :kontor.invoice-line/name "x"
                                              :kontor.invoice-line/quantity 1M
                                              :kontor.invoice-line/unit-price 1M
                                              :kontor.invoice-line/vat-rate 19.0M}]})
    (is (nil? (:kontor.invoice/number (d/entity (d/db conn) [:kontor.invoice/external-id "draft-1"]))))))

(deftest an-unnumbered-journal-gives-no-number
  (let [conn (bootstrap false)
        a (create-and-send! conn "beleg-1" #inst "2025-03-01T00:00:00Z")]
    (is (nil? (:kontor.invoice/number a)))))

;; ============================================================================
;; Settlement (ADR-172): invoices a world created are replayed in its parent
;; ============================================================================

(defn- world-of [conn]
  (let [cfg (:config (d/db conn))
        b (keyword (str "w-" (random-uuid)))]
    (d/branch! conn (:branch cfg) b)
    (d/connect (assoc cfg :branch b))))

(deftest a-world-s-invoices-are-renumbered-by-the-parent
  (let [conn (bootstrap true)
        base (d/db conn)
        w (world-of conn)]
    (create-and-send! w "w-inv-1" #inst "2025-03-02T00:00:00Z")
    (invoice/create! w {:kontor.invoice/external-id "w-draft"
                        :kontor.invoice/issue-date #inst "2025-03-03T00:00:00Z"
                        :kontor.invoice/currency "EUR"
                        :kontor.invoice/lines [{:kontor.invoice-line/sequence 1
                                                :kontor.invoice-line/name "later"
                                                :kontor.invoice-line/quantity 1M
                                                :kontor.invoice-line/unit-price 50M
                                                :kontor.invoice-line/vat-rate 19.0M}]})
    ;; the parent sends its own invoice meanwhile
    (create-and-send! conn "p-inv-1" #inst "2025-03-01T00:00:00Z")
    (let [intents (st/extract base (d/db w))]
      (is (= {"RE/2025/0001" "RE/2025/0002"} (st/stamp! conn intents)))
      (let [db (d/db conn)
            sent (d/entity db [:kontor.invoice/external-id "w-inv-1"])
            draft (d/entity db [:kontor.invoice/external-id "w-draft"])]
        (is (= "RE/2025/0002" (:kontor.invoice/number sent)))
        (is (= :sent (:kontor.invoice/status sent)))
        (is (some? (:kontor.invoice/transaction sent)) "linked to its replayed posting")
        (is (= :draft (:kontor.invoice/status draft)))
        (is (nil? (:kontor.invoice/number draft)))
        (is (= 1 (count (:kontor.invoice/lines draft)))))
      (is (numbering/gapless? (d/db conn) [:kontor.journal/code "INV"]))
      (testing "a retry books nothing twice"
        (st/stamp! conn intents)
        (is (= 2 (count (d/q '[:find [?n ...] :where [_ :kontor.invoice/number ?n]] (d/db conn)))))))))

(deftest an-invoice-id-both-sides-used-is-claimed
  (let [conn (bootstrap true)
        base (d/db conn)
        w (world-of conn)]
    (create-and-send! w "INV-ACME-42" #inst "2025-03-02T00:00:00Z")
    (create-and-send! conn "INV-ACME-42" #inst "2025-03-02T00:00:00Z")
    (let [i (first (filter #(= :invoice (:kind %)) (st/extract base (d/db w))))]
      (is (contains? (:footprint i) [:invoice "INV-ACME-42"]))
      (is (contains? (st/parent-claims base (d/db conn)) [:invoice "INV-ACME-42"])))))

(deftest an-invoice-status-it-cannot-replay-is-refused
  (let [conn (bootstrap true)
        base (d/db conn)
        w (world-of conn)
        inv (create-and-send! w "w-inv-2" #inst "2025-03-02T00:00:00Z")]
    (invoice/cancel! w (:db/id inv) {})
    (is (= ::st/unsupported
           (try (st/extract base (d/db w)) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))
