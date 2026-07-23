(ns kontor.regression.payroll-test
  "Regression suite — payroll journals for the three richest jurisdictions
   (DE / US / CA). Locks in that each module's compute + posting-builder
   produces a BALANCED payroll journal that routes to the expected GL
   accounts, and — for DE — that the journal actually posts through the
   kernel gate to a real ledger and reads back correctly.

   Figures are reused from each payroll module's own tests (authority-
   plausible gross-to-net splits), extended with fresh multi-employee /
   accrual scenarios:

     - DE  Bruttomethode (SKR04): gross 4000 / net 2500 / LSt 700 /
           AN-SV 800 / AG-SV 800  (payroll-de-datev posting-builder-test).
     - US  ADP GLI 3-employee / 3-state fixture: gross 8500 / 9200 / 7800,
           net 5669.75 / 6221.20 / 5903.30  (payroll-us-adp compute-test).
     - CA  ON employee 'Jane': gross 5000 / ITX 850 / EE-CPP 260.30 /
           EE-EI 81.50 / ER-CPP 260.30 / ER-EI 114.10 / net 3808.20
           (payroll-ca posting-builder-test).

   The substrate invariant under test everywhere: per-(entity, ledger,
   commodity) postings sum to zero (ADR-021 / ADR-031)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.posting :as posting]
            [kontor.reporting.balance :as balance]
            [kontor.provider.payroll-provider :as pp]
            ;; DE
            [kontor.payroll-de-datev.posting-builder :as de-pb]
            [kontor.payroll-de-datev.wage-types :as de-wt]
            ;; US
            [kontor.payroll-us-adp.compute :as us-compute]
            [kontor.payroll-us-adp.posting-builder :as us-pb]
            [kontor.payroll-us-adp.wage-types :as us-wt]
            ;; CA
            [kontor.payroll-ca.posting-builder :as ca-pb])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; helpers
;; ============================================================================

(defn- sum-amounts ^BigDecimal [postings]
  (reduce (fn [^BigDecimal a {:kontor.posting/keys [amount]}]
            (.add a ^BigDecimal amount))
          0M postings))

(defn- balanced? [postings]
  (zero? (.signum (sum-amounts postings))))

(defn- amounts-on
  "All posting amounts whose account equals `acct`."
  [postings acct]
  (->> postings
       (filter #(= acct (:kontor.posting/account %)))
       (map :kontor.posting/amount)))

;; ============================================================================
;; DE — DATEV LODAS Bruttomethode (SKR04)
;; ============================================================================

(def ^:private de-eur [:kontor.commodity/symbol "EUR"])

(def ^:private de-catalog
  (de-wt/validate-catalog
   {:catalog/version 1
    :catalog/mandant "99999"
    :catalog/berater "1234"
    :catalog/coa     :skr04
    :catalog/wage-types {100 {:kind :base-salary :account-hint :gehalt}}}))

(defn- de-fact
  "A DE Bruttomethode PayrollFact. The builder reads gross from the
   :base-salary component and net/withholding/SI from the fact top-level."
  [{:keys [pnr gross net wht ee-si er-si]}]
  {:employment (str "emp-" pnr)
   :employment-pnr pnr
   :pay-period "11/2025"
   :gross gross :net net :withholding-tax wht
   :employee-si ee-si :employer-si er-si
   :components [{:kind :base-salary :amount gross :account-hint :gehalt}
                {:kind :withholding-tax :amount (.negate ^BigDecimal wht)}
                {:kind :employee-si :amount (.negate ^BigDecimal ee-si)}
                {:kind :employer-si :amount er-si :employer-side? true
                 :account-hint :soziale-aufwendungen}]})

(defn- de-postings [facts & {:keys [accounts] :or {accounts {}}}]
  (let [builder (de-pb/make-builder {:catalog de-catalog :commodity de-eur})]
    (pp/build-postings builder facts {:accounts accounts :ledger nil :fx-provider nil})))

(deftest de-bruttomethode-routes-to-skr04-defaults
  ;; With accounts {} the DE builder resolves every leg via the SKR04
  ;; catalog DEFAULTS — no consumer chart needed. Reuses the exact figures
  ;; from payroll-de-datev posting-builder-test.
  (let [fact (de-fact {:pnr "3011" :gross 4000M :net 2500M
                       :wht 700M :ee-si 800M :er-si 800M})
        postings (de-postings [fact])]
    (testing "10-leg Bruttomethode, balanced"
      (is (= 10 (count postings)))
      (is (balanced? postings)))
    (testing "routing to SKR04 default accounts"
      (is (= [4000.00M]  (amounts-on postings [:kontor.account/code "6020"])))  ; Gehälter (gross)
      (is (= [800.00M]   (amounts-on postings [:kontor.account/code "6110"])))  ; AG-SV expense
      (is (= [-2500.00M] (amounts-on postings [:kontor.account/code "3720"])))  ; Verb. Lohn (net)
      (is (= [-700.00M]  (amounts-on postings [:kontor.account/code "3730"])))  ; Verb. LSt
      ;; Verb. SV (3740) carries the AN-SV and AG-SV credits.
      (is (= [-800.00M -800.00M]
             (sort (amounts-on postings [:kontor.account/code "3740"])))))
    (testing "Verrechnungskonto 3790 nets to zero per fact"
      (is (zero? (.signum (sum-amounts
                           (filter #(= [:kontor.account/code "3790"]
                                       (:kontor.posting/account %))
                                   postings))))))))

(deftest de-multi-employee-run-balances-per-commodity
  ;; NEW scenario: a two-employee DE payroll run. Each fact's legs net to
  ;; zero, so the aggregate journal is balanced per (ledger, commodity).
  (let [f1 (de-fact {:pnr "3011" :gross 4000M :net 2500M
                     :wht 700M :ee-si 800M :er-si 800M})
        f2 (de-fact {:pnr "3012" :gross 6000M :net 3600M
                     :wht 1200M :ee-si 1200M :er-si 1200M})
        postings (de-postings [f1 f2])]
    (testing "20 legs, aggregate balanced"
      (is (= 20 (count postings)))
      (is (balanced? postings)))
    (testing "gross expense 6020 aggregates both salaries (4000 + 6000)"
      (is (= 10000.00M (sum-amounts
                        (filter #(= [:kontor.account/code "6020"]
                                    (:kontor.posting/account %))
                                postings)))))
    (testing "net liability 3720 aggregates both nets (2500 + 3600)"
      (is (= -6100.00M (sum-amounts
                        (filter #(= [:kontor.account/code "3720"]
                                    (:kontor.posting/account %))
                                postings)))))))

(deftest de-urlaubsrueckstellung-employer-accrual-balances
  ;; NEW figure. HGB §249 simplified PTO accrual (employer-side accrual),
  ;; Handelsbilanz: annual-gross 48000, AG-SV 21%, 220 Arbeitstage, 15 days.
  ;; ((48000 + 10080) / 220) * 15 = (58080/220=264.00) * 15 = 3960.00
  (let [amt (de-pb/urlaubsrueckstellung-amount
             {:annual-gross 48000M
              :accrued-vacation-days 15M
              :framework :hgb-handelsbilanz})
        postings (de-pb/urlaubsrueckstellung-tx-data
                  {:amount amt :commodity de-eur :catalog de-catalog
                   :ledger :de-handelsrecht
                   :narration "Urlaubsrückstellung 2025-12-31"})]
    (testing "accrual amount matches the hand-computed Handelsbilanz figure"
      (is (= 3960.00M amt)))
    (testing "balanced DR expense / CR provision pair on SKR04"
      (is (= 2 (count postings)))
      (is (balanced? postings))
      (is (= [:kontor.account/code "6035"] (-> postings first :kontor.posting/account)))
      (is (= [:kontor.account/code "3066"] (-> postings second :kontor.posting/account)))
      (is (= 3960.00M  (-> postings first :kontor.posting/amount)))
      (is (= -3960.00M (-> postings second :kontor.posting/amount))))))

;; ---------------------------------------------------------------------------
;; DE — end-to-end: build → gate → real ledger → balance read-back
;; ---------------------------------------------------------------------------

(def ^:private skr04-accounts
  [{:kontor.account/path "Aufwand:Gehälter" :kontor.account/code "6020"
    :kontor.account/name "Gehälter" :kontor.account/type :expense :kontor.account/active true}
   {:kontor.account/path "Aufwand:SozialeAufwendungen" :kontor.account/code "6110"
    :kontor.account/name "Gesetzliche soziale Aufwendungen" :kontor.account/type :expense :kontor.account/active true}
   {:kontor.account/path "Verb:Lohn" :kontor.account/code "3720"
    :kontor.account/name "Verb. Löhne/Gehälter" :kontor.account/type :liability :kontor.account/active true}
   {:kontor.account/path "Verb:Lohnsteuer" :kontor.account/code "3730"
    :kontor.account/name "Verb. Lohn-/Kirchensteuer" :kontor.account/type :liability :kontor.account/active true}
   {:kontor.account/path "Verb:Sozialversicherung" :kontor.account/code "3740"
    :kontor.account/name "Verb. soziale Sicherheit" :kontor.account/type :liability :kontor.account/active true}
   {:kontor.account/path "Verb:Verrechnung" :kontor.account/code "3790"
    :kontor.account/name "Lohn-/Gehaltsverrechnungskonto" :kontor.account/type :liability :kontor.account/active true}])

(defn- code->eid [db]
  (into {} (for [a ["6020" "6110" "3720" "3730" "3740" "3790"]]
             [a (d/q '[:find ?e . :in $ ?c :where [?e :kontor.account/code ?c]] db a)])))

(deftest de-monthly-payroll-journal-posts-to-real-ledger
  ;; The full consumer path: seed SKR04 accounts, PRE-RESOLVE account
  ;; codes to eids (see finding below — :kontor.account/code is not a
  ;; unique-identity attr, so the builder's default [:kontor.account/code ...]
  ;; refs cannot be transacted directly), build the journal, post it
  ;; through the gate, and read the ledger back.
  (let [conn (core/create-test-db)]
    (d/transact conn
                (into [{:kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                       {:kontor.entity/code "DE-GMBH" :kontor.entity/name "Acme DE GmbH"
                        :kontor.entity/kind :operating}
                       {:kontor.journal/code "PAY-DE" :kontor.journal/name "Payroll (DE)"
                        :kontor.journal/type :general}]
                      skr04-accounts))
    (let [db (d/db conn)
          eur (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "EUR"]] db)
          journal (d/q '[:find ?e . :where [?e :kontor.journal/code "PAY-DE"]] db)
          acct (code->eid db)
          ;; hint -> eid, pre-resolved (the pattern the DE e2e test uses).
          accounts {:gehalt (acct "6020")
                    :soziale-aufwendungen (acct "6110")
                    :verb-lohn (acct "3720")
                    :verb-lohnsteuer (acct "3730")
                    :verb-sozialversicherung (acct "3740")
                    :verrechnung (acct "3790")}
          builder (de-pb/make-builder {:catalog de-catalog :commodity eur})
          fact (de-fact {:pnr "3011" :gross 4000M :net 2500M
                         :wht 700M :ee-si 800M :er-si 800M})
          postings (pp/build-postings builder [fact]
                                      {:accounts accounts :ledger nil :fx-provider nil})
          report (posting/post-transaction!
                  conn
                  {:transaction {:kontor.transaction/journal journal
                                 :kontor.transaction/effective-date #inst "2025-11-30"
                                 :kontor.transaction/narration "DE payroll 2025-11"
                                 :kontor.transaction/external-id "TX-PAY-DE-2025-11"}
                   :postings postings})]
      (testing "the balanced payroll journal is accepted by the gate"
        (is (some? (:db-after report))))
      (testing "the ledger reads back the Bruttomethode balances (EUR)"
        (let [bal (fn [code]
                    (get-in (balance/account-balance conn (acct code)) [eur :amount]))]
          (is (= 4000.00M  (bal "6020")))   ; gross salary expense
          (is (= 800.00M   (bal "6110")))   ; employer SI expense
          (is (= -2500.00M (bal "3720")))   ; net pay liability
          (is (= -700.00M  (bal "3730")))   ; withholding-tax liability
          (is (= -1600.00M (bal "3740")))   ; SI liability (AN 800 + AG 800)
          (is (= 0M        (bal "3790"))))))))  ; Verrechnung nets to zero

;; PENDING(NEW): the DE posting-builder's DEFAULT account routing emits
;; [:kontor.account/code "6020"] lookup-refs, but :kontor.account/code is
;; only :db/index true (NOT :db.unique/identity — only :kontor.account/path
;; is). Transacting those default refs fails hard with
;; {:error :lookup-ref/unique} "Lookup ref attribute should be marked as
;; :db/unique: [:kontor.account/code \"3790\"]". A consumer who accepts the
;; builder's default SKR04 routing and tries to POST the journal cannot —
;; they must pre-resolve every code to an eid by hand (as this file's
;; de-monthly-payroll-journal-posts-to-real-ledger and the module e2e test
;; both do). INTENDED: a balanced journal built from the builder's own
;; defaults should be transactable. Fix options: builder resolves to eids /
;; emits :kontor.account/path refs, OR the kernel makes :kontor.account/code
;; a unique-identity attr. P2 usability footgun; the error is also untyped
;; (:type nil in ex-data — cf. F3). Remove ^:kaocha/pending once fixed.
(deftest de-default-routed-journal-should-post
  (let [conn (core/create-test-db)]
    (d/transact conn
                (into [{:kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                       {:kontor.journal/code "PAY-DE" :kontor.journal/name "Payroll (DE)"
                        :kontor.journal/type :general}]
                      skr04-accounts))
    (let [db (d/db conn)
          eur (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "EUR"]] db)
          journal (d/q '[:find ?e . :where [?e :kontor.journal/code "PAY-DE"]] db)
          builder (de-pb/make-builder {:catalog de-catalog :commodity eur})
          fact (de-fact {:pnr "3011" :gross 4000M :net 2500M
                         :wht 700M :ee-si 800M :er-si 800M})
          ;; accounts {} + :db → builder resolves its default SKR04 codes to
          ;; eids within this book (ADR-119; N1 fix), producing transactable
          ;; refs instead of the non-unique [:kontor.account/code …].
          postings (pp/build-postings builder [fact]
                                      {:accounts {} :ledger nil :fx-provider nil :db db})
          report (posting/post-transaction!
                  conn
                  {:transaction {:kontor.transaction/journal journal
                                 :kontor.transaction/effective-date #inst "2025-11-30"
                                 :kontor.transaction/narration "DE payroll (default routing)"}
                   :postings postings})]
      (testing "a balanced journal built from the builder's default routing posts"
        (is (some? (:db-after report)))))))

;; ============================================================================
;; US — ADP GLI, 3-employee / 3-state pay period (compute-driven)
;; ============================================================================

(def ^:private us-accounts
  {:wages-expense     -20001
   :er-fica-ss        -20002  :er-fica-medicare -20003
   :er-futa           -20004  :er-suta          -20005
   :er-health         -20006  :er-401k-match    -20007
   :er-workers-comp   -20008
   :ee-fed-withheld   -20009  :ee-state-withheld -20010
   :ee-local-withheld -20011  :ee-fica-ss       -20012
   :ee-fica-medicare  -20013  :ee-401k-deferral -20014
   :ee-roth-deferral  -20015  :ee-section125    -20016
   :ee-hsa            -20017  :ee-fsa           -20018
   :ee-dep-care-fsa   -20019  :garnishment      -20020
   :child-support     -20021  :net-pay-payable  -20022
   :unmapped-suspense -20099})

(def ^:private us-book-ledger -10001)
(def ^:private us-tax-ledger  -10002)
(def ^:private us-usd         -10003)

(defn- us-3-state-facts []
  (let [wtm (us-wt/load-reference)
        {:keys [classified]}
        (us-compute/parse-and-classify
         (io/resource "kontor/payroll_us_adp/fixtures/gli-3-employees-3-states.csv")
         wtm)]
    (us-compute/payroll-facts-from-rows classified)))

(deftest us-adp-multi-employee-3-state-run-balances-per-ledger
  ;; Challenging case: 3 employees across CA / NY / TX, dual-ledger
  ;; (us-gaap + us-tax) parallel-book split. Each ledger must balance
  ;; independently (ADR-021 + ADR-031 + ADR-077).
  (let [facts (us-3-state-facts)
        postings (us-pb/build-payroll-postings
                  {:facts facts :accounts us-accounts
                   :ledgers-map {:us-gaap us-book-ledger :us-tax us-tax-ledger}
                   :commodity us-usd})]
    (testing "compute produced one fact per employee w/ the fixture grosses"
      (is (= 3 (count facts)))
      (is (= #{8500.00M 9200.00M 7800.00M} (set (map :gross facts)))))
    (testing "each ledger balances independently"
      (let [by-ledger (group-by :kontor.posting/ledger postings)]
        (is (zero? (.signum (sum-amounts (get by-ledger us-book-ledger)))))
        (is (zero? (.signum (sum-amounts (get by-ledger us-tax-ledger)))))))
    (testing "aggregate wage expense on the book ledger = sum of grosses (25500)"
      (let [book-wages (->> postings
                            (filter #(and (= us-book-ledger (:kontor.posting/ledger %))
                                          (= (:wages-expense us-accounts)
                                             (:kontor.posting/account %))))
                            sum-amounts)]
        (is (= 25500.00M book-wages))))))

(deftest us-adp-run-routes-wages-and-state-withholding
  ;; Routing regression: wage-expense on each (employee × ledger); state
  ;; withholding only for the two states that levy it (CA + NY), never TX.
  (let [facts (us-3-state-facts)
        postings (us-pb/build-payroll-postings
                  {:facts facts :accounts us-accounts
                   :ledgers-map {:us-gaap us-book-ledger :us-tax us-tax-ledger}
                   :commodity us-usd})]
    (testing "wage expense = 3 employees × 2 ledgers = 6 legs"
      (is (= 6 (count (amounts-on postings (:wages-expense us-accounts))))))
    (testing "state withholding = CA + NY only (TX has none) × 2 ledgers = 4"
      (is (= 4 (count (amounts-on postings (:ee-state-withheld us-accounts))))))
    (testing "every posting carries account + amount + commodity + ledger"
      (doseq [p postings]
        (is (some? (:kontor.posting/account p)))
        (is (instance? BigDecimal (:kontor.posting/amount p)))
        (is (= us-usd (:kontor.posting/commodity p)))
        (is (some? (:kontor.posting/ledger p)))))))

;; ============================================================================
;; CA — CRA payroll (ON employee), employer-side leg pairs
;; ============================================================================

(def ^:private ca-accounts
  {:ca-payroll-wages              :acct/wages
   :ca-payroll-er-cpp             :acct/er-cpp
   :ca-payroll-er-ei              :acct/er-ei
   :ca-payroll-itx                :acct/itx
   :ca-payroll-cpp                :acct/cpp
   :ca-payroll-ei                 :acct/ei
   :ca-payroll-net-wages          :acct/net-wages})

(defn- ca-fact [{:keys [emp gross itx ee-cpp ee-ei er-cpp er-ei net]}]
  {:employment emp
   :gross gross
   :net net
   :components [{:kind :base-wage           :amount gross :employer-side? false}
                {:kind :income-tax-withheld :amount (.negate ^BigDecimal itx) :employer-side? false}
                {:kind :employee-cpp        :amount (.negate ^BigDecimal ee-cpp) :employer-side? false}
                {:kind :employee-ei         :amount (.negate ^BigDecimal ee-ei) :employer-side? false}
                {:kind :employer-cpp        :amount er-cpp :employer-side? true}
                {:kind :employer-ei         :amount er-ei :employer-side? true}]
   :jurisdiction-specific-codes {:engine :test}})

(defn- ca-postings [facts]
  (let [builder (ca-pb/->CaPayrollPostingBuilder {:commodity :kontor.commodity/cad})]
    (pp/build-postings builder facts {:accounts ca-accounts})))

(deftest ca-monthly-payroll-balances-and-routes
  ;; ON employee 'Jane' — figures from payroll-ca posting-builder-test.
  (let [jane (ca-fact {:emp :emp/jane :gross 5000M :itx 850M
                       :ee-cpp 260.30M :ee-ei 81.50M
                       :er-cpp 260.30M :er-ei 114.10M :net 3808.20M})
        postings (ca-postings [jane])
        by-acct (group-by :kontor.posting/account postings)]
    (testing "journal balances"
      (is (balanced? postings)))
    (testing "single wages-expense debit for the gross"
      (is (= [5000M] (amounts-on postings :acct/wages))))
    (testing "single net-wages credit for the net"
      (is (= [-3808.20M] (amounts-on postings :acct/net-wages))))
    (testing "income tax credited to the CRA ITX payable"
      (is (= [-850M] (amounts-on postings :acct/itx))))
    (testing "CPP payable aggregates employee (-260.30) + employer (-260.30) = -520.60"
      (is (= -520.60M (sum-amounts (get by-acct :acct/cpp)))))
    (testing "EI payable aggregates employee (-81.50) + employer (-114.10) = -195.60"
      (is (= -195.60M (sum-amounts (get by-acct :acct/ei)))))
    (testing "employer-side expense legs land on their own expense accounts"
      (is (= [260.30M] (amounts-on postings :acct/er-cpp)))
      (is (= [114.10M] (amounts-on postings :acct/er-ei))))))

(deftest ca-multi-employee-run-balances
  ;; NEW scenario: a two-employee CA run (Jane ON + Bob). The aggregate
  ;; journal balances and the wages/net buckets aggregate correctly.
  (let [jane (ca-fact {:emp :emp/jane :gross 5000M :itx 850M
                       :ee-cpp 260.30M :ee-ei 81.50M
                       :er-cpp 260.30M :er-ei 114.10M :net 3808.20M})
        ;; Bob: 6000 - 1100 - 330 - 100 = 4470 net.
        bob (ca-fact {:emp :emp/bob :gross 6000M :itx 1100M
                      :ee-cpp 330M :ee-ei 100M
                      :er-cpp 330M :er-ei 140M :net 4470M})
        postings (ca-postings [jane bob])]
    (testing "aggregate journal balances"
      (is (balanced? postings)))
    (testing "wages expense aggregates both grosses (5000 + 6000)"
      (is (= 11000M (sum-amounts
                     (filter #(= :acct/wages (:kontor.posting/account %)) postings)))))
    (testing "net-wages liability aggregates both nets (3808.20 + 4470)"
      (is (= -8278.20M (sum-amounts
                        (filter #(= :acct/net-wages (:kontor.posting/account %)) postings)))))))
