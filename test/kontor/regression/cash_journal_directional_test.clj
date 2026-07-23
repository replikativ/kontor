(ns kontor.regression.cash-journal-directional-test
  "note 197 — cash-journal-ambiguous (P1) FIXED.

   Every kontor preset seeds TWO journals of type :cash — Cash Receipts
   (code \"CR\") and Cash Disbursements (code \"CD\") — so a kontor.book cash
   verb that resolved a journal purely by type used to throw \"ambiguous\" on
   every shipped preset. The verbs now pass the DIRECTION they encode as a
   :journal-code-hint (inflow → CR, outflow → CD); entry! narrows the :cash
   type to that one journal. This guards that the five cash verbs each land
   in the correct CR/CD journal instead of throwing."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.l10n-de.preset :as de]
            [kontor.book :as book]))

(def ^:private eur  [:kontor.commodity/symbol "EUR"])
(def ^:private bank [:kontor.account/path "Umlaufvermögen:Bank"])
(def ^:private ar   [:kontor.account/path "Umlaufvermögen:Forderungen"])
(def ^:private ap   [:kontor.account/path "Verbindlichkeiten:Lieferanten"])
(def ^:private eqty [:kontor.account/path "Eigenkapital:Privateinlagen"])

(defn- posted-journal-code
  "Code of the journal the transaction with `narration` posted to."
  [conn narration]
  (d/q '[:find ?code .
         :in $ ?narr
         :where
         [?t :kontor.transaction/narration ?narr]
         [?t :kontor.transaction/journal ?j]
         [?j :kontor.journal/code ?code]]
       (d/db conn) narration))

(deftest cash-verbs-resolve-cr-cd-on-de-preset
  (let [conn (de/create-de-db)]
    (testing "inflow verbs (receive / receive-payment) land in CR (Cash Receipts)"
      (book/receive-payment! conn {:debit-account bank :credit-account ar
                                   :amount 100 :commodity eur :narration "rp"})
      (is (= "CR" (posted-journal-code conn "rp"))
          "receive-payment! resolves the :cash type to CR, not ambiguous")
      (book/receive! conn {:debit-account bank :credit-account eqty
                           :amount 50 :commodity eur :narration "rcv"})
      (is (= "CR" (posted-journal-code conn "rcv"))
          "receive! resolves to CR"))
    (testing "outflow verbs (pay / pay-bill / distribute-dividend) land in CD (Cash Disbursements)"
      (book/pay! conn {:debit-account eqty :credit-account bank
                       :amount 30 :commodity eur :narration "pay"})
      (is (= "CD" (posted-journal-code conn "pay")) "pay! resolves to CD")
      (book/pay-bill! conn {:debit-account ap :credit-account bank
                            :amount 20 :commodity eur :narration "pb"})
      (is (= "CD" (posted-journal-code conn "pb")) "pay-bill! resolves to CD")
      (book/distribute-dividend! conn {:debit-account eqty :credit-account bank
                                       :amount 10 :commodity eur :narration "dd"})
      (is (= "CD" (posted-journal-code conn "dd")) "distribute-dividend! resolves to CD"))))

(deftest explicit-journal-still-honoured
  ;; The direction hint is only a fallback for the ambiguous :cash type — an
  ;; explicit :journal must always win.
  (let [conn (de/create-de-db)]
    (book/receive-payment! conn {:debit-account bank :credit-account ar
                                 :amount 100 :commodity eur :narration "explicit"
                                 :journal [:kontor.journal/code "GJ"]})
    (is (= "GJ" (posted-journal-code conn "explicit"))
        "an explicit :journal overrides the CR/CD direction hint")))
