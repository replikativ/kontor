(ns kontor.regression.r2-payroll-e2e-test
  "R2 area — payroll END-TO-END through the REAL orchestrator
   `kontor.hr.payroll/run-payroll!`, starting from each jurisdiction's
   l10n *preset* (`create-de-db` / `create-us-db` / `create-ca-db`) and
   ending at posted, balanced GL postings.

   This is deliberately the ORCHESTRATOR path, not the direct
   `build-postings` path that `kontor.regression.payroll-test` already
   covers. Driving `run-payroll!` from a preset is the journey a real
   payroll consumer takes, and it is where the N3 gap bites:

     - DE  ships a default SKR04 account map (`kontor.payroll-de-datev
           .wage-types/default-account-map-skr04`). The posting-builder
           can resolve those code→eid *when handed a `db`*. But
           `run-payroll!` never threads `:db` into `build-postings`
           (kontor.hr.payroll lines ~178-185 build the opts map without
           it), so the shipped default map is UNREACHABLE via the
           documented entry point — the consumer must hand-resolve an
           `:accounts` map anyway. Pinned ^:kaocha/pending.
     - US  the l10n-us chart DOES carry payroll accounts (2300/2310/
           2400/2410/6110/6120 …) — contrary to the blanket 'charts
           lack payroll accounts' framing — so a US run works from the
           preset once the consumer hand-builds the wage-type→account
           map (no default map ships). GREEN, friction noted.
     - CA  the l10n-ca chart carries NO payroll accounts at all; the
           payroll accounts live in a SEPARATE starter chart
           (`kontor.payroll-ca.chart/install!`) that the preset does not
           call, and no default account map ships. From the preset
           alone, `run-payroll!` is unreachable. Pinned ^:kaocha/pending;
           the with-chart path is GREEN.

   Every asserted figure is hand-derived from the scenario inputs (see
   per-test comments); balances are compared with BigDecimal .compareTo,
   never doubles."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as payperiod]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.provider.payroll-provider :as ppro]
            ;; DE
            [kontor.l10n-de.preset :as de-preset]
            [kontor.payroll-de-datev.core :as datev]
            [kontor.payroll-de-datev.wage-types :as de-wt]
            ;; US
            [kontor.l10n-us.preset :as us-preset]
            [kontor.payroll-us-adp.core :as adp]
            [kontor.payroll-us-adp.wage-types :as us-wt]
            ;; CA
            [kontor.l10n-ca.preset :as ca-preset]
            [kontor.payroll-ca.chart :as pca-chart]
            [kontor.payroll-ca.posting-builder :as ca-pb])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; helpers
;; ============================================================================

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- code->eid [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.account/code ?c]] db code))

(defn- run-postings
  "Pull the payroll transaction's postings from a run-report."
  [db run-code]
  (let [run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :kontor.payroll-run/code ?c]]
                     db run-code)
        run (d/pull db
                    '[* {:kontor.payroll-run/payroll-transaction
                         [:kontor.transaction/external-id
                          {:kontor.posting/_transaction
                           [:kontor.posting/amount
                            {:kontor.posting/account [:kontor.account/code]}]}]}]
                    run-eid)]
    {:run run
     :postings (-> run :kontor.payroll-run/payroll-transaction
                   :kontor.posting/_transaction)}))

(defn- sum-amounts ^BigDecimal [postings]
  (reduce (fn [^BigDecimal a {:kontor.posting/keys [amount]}]
            (.add a ^BigDecimal amount))
          0M postings))

(defn- balanced? [postings]
  (zero? (.signum (sum-amounts postings))))

(defn- total-on-code ^BigDecimal [postings code]
  (->> postings
       (filter #(= code (get-in % [:kontor.posting/account :kontor.account/code])))
       sum-amounts))

;; ============================================================================
;; DE — GmbH Bruttomethode, from create-de-db
;; ============================================================================
;; Stylized German monthly payroll (payroll-de-datev buchungsbeleg fixture):
;;   Bruttolohn (gross salary)          4000.00   Dr 6020
;;   Lohnsteuer withheld                 700.00   Cr 3730
;;   AN-Anteil Sozialvers. (employee)    800.00   Cr 3740
;;   Nettolohn = 4000 - 700 - 800 =     2500.00   Cr 3720
;;   AG-Anteil Sozialvers. (employer)    800.00   Dr 6110 / Cr 3740
;; Verrechnungskonto 3790 nets to zero.

(def ^:private de-fixture-buchungsbeleg
  (slurp (io/resource "kontor/payroll_de_datev/fixtures/buchungsbeleg-2025-11.csv")
         :encoding "ISO-8859-1"))

(def ^:private de-catalog
  (de-wt/validate-catalog
   {:catalog/version 1
    :catalog/mandant "99999"
    :catalog/berater "1234"
    :catalog/coa     :skr04
    :catalog/wage-types {100 {:kind :base-salary :account-hint :gehalt}}}))

(defn- de-bootstrap
  "create-de-db preset + hr + datev + a GmbH fixture. Seeds the SKR04
   payroll liability/expense accounts the l10n-de chart omits (it ships
   only 6020 of the payroll set). Returns [conn ids-map]."
  []
  (let [conn (de-preset/create-de-db)]
    (hr/install! conn)
    (datev/install! conn)
    (d/transact conn
                [{:kontor.entity/code "DE-GMBH" :kontor.entity/name "Acme DE GmbH"
                  :kontor.entity/kind :operating}
                 {:kontor.period/name "2025-11"
                  :kontor.period/start #inst "2025-11-01"
                  :kontor.period/end   #inst "2025-12-01"}
                 ;; SKR04 payroll accounts NOT in the l10n-de preset chart.
                 {:kontor.account/code "6110" :kontor.account/name "Gesetzliche soziale Aufwendungen"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "3720" :kontor.account/name "Verb. aus Löhnen und Gehältern"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:kontor.account/code "3730" :kontor.account/name "Verb. aus Lohn- und Kirchensteuer"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:kontor.account/code "3740" :kontor.account/name "Verb. soziale Sicherheit"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:kontor.account/code "3790" :kontor.account/name "Lohn- und Gehaltsverrechnungskonto"
                  :kontor.account/type :liability :kontor.account/active true}])
    (person/create-person! conn {:external-id "P-mueller"
                                 :given-name "Franz" :family-name "Müller"})
    (let [db (d/db conn)
          ent (ref-eid db :kontor.entity/code "DE-GMBH")
          person (hr/person-by-external-id db "P-mueller")
          period (ref-eid db :kontor.period/name "2025-11")]
      (employment/hire! conn {:code "EMP-mueller" :person person :entity ent
                              :start-date #inst "2025-01-01" :job-title "Engineer"})
      (let [emp (hr/employment-by-code (d/db conn) "EMP-mueller")]
        (payperiod/create-pay-period! conn {:code "DE-2025-11" :entity ent
                                            :start-date #inst "2025-11-01"
                                            :end-date #inst "2025-11-30"
                                            :frequency :monthly :fiscal-period period})
        (let [db2 (d/db conn)]
          [conn {:ent ent
                 :eur (ref-eid db2 :kontor.commodity/symbol "EUR")
                 :journal (ref-eid db2 :kontor.journal/code "GJ")
                 :pp (hr/pay-period-by-code db2 "DE-2025-11")
                 :emp emp}])))))

(defn- de-run! [conn ids accounts]
  (payroll/run-payroll!
   conn {:pay-period (:pp ids)
         :entity (:ent ids)
         :employments [(:emp ids)]
         :compute-provider (datev/make-compute-provider
                            {:coa :skr04 :employment-pnr->eid {"3011" (:emp ids)}})
         :posting-builder (datev/make-posting-builder {:catalog de-catalog :commodity (:eur ids)})
         :accounts accounts
         :run-code "RUN-DE-2025-11"
         :tx-code "TX-DE-2025-11"
         :journal (:journal ids)
         :commodity (:eur ids)
         :variable-inputs {:buchungsbeleg-content de-fixture-buchungsbeleg}}))

(deftest de-run-payroll-with-explicit-accounts-map
  ;; The working consumer path: hand-resolve every SKR04 hint to an eid
  ;; (the pattern the module e2e uses) and drive run-payroll!.
  (let [[conn ids] (de-bootstrap)
        db (d/db conn)
        accounts {:gehalt                  (code->eid db "6020")
                  :soziale-aufwendungen    (code->eid db "6110")
                  :verb-lohn               (code->eid db "3720")
                  :verb-lohnsteuer         (code->eid db "3730")
                  :verb-sozialversicherung (code->eid db "3740")
                  :verrechnung             (code->eid db "3790")}
        report (de-run! conn ids accounts)
        {:keys [run postings]} (run-postings (:db-after report) "RUN-DE-2025-11")]
    (testing "run row lands :computed with the hand-derived control totals"
      (is (= :datev-lodas (:kontor.payroll-run/provider-id run)))
      (is (= 0 (.compareTo ^BigDecimal (:kontor.payroll-run/control-total-gross run) 4000.00M)))
      (is (= 0 (.compareTo ^BigDecimal (:kontor.payroll-run/control-total-net run) 2500.00M))))
    (testing "the linked transaction balances per (ledger, commodity)"
      (is (balanced? postings)))
    (testing "Bruttomethode amounts route to the SKR04 accounts"
      (is (= 0 (.compareTo (total-on-code postings "6020") 4000.00M)))   ; gross expense Dr
      (is (= 0 (.compareTo (total-on-code postings "6110") 800.00M)))    ; employer SI expense Dr
      (is (= 0 (.compareTo (total-on-code postings "3720") -2500.00M)))  ; net liability Cr
      (is (= 0 (.compareTo (total-on-code postings "3730") -700.00M)))   ; LSt liability Cr
      (is (= 0 (.compareTo (total-on-code postings "3740") -1600.00M)))  ; SV liability (AN 800 + AG 800)
      (is (= 0 (.compareTo (total-on-code postings "3790") 0M))))))      ; Verrechnung nets to zero

;; PENDING(NEW): kontor.hr.payroll/run-payroll! never threads `:db` into
;; PayrollPostingBuilder/build-postings — it assembles the opts map as
;; {:accounts :ledger :fx-provider (+ :ledgers-map / :state-allocations)}
;; and nothing else (kontor.hr.payroll ~L178-185). The DE datev builder's
;; SHIPPED default SKR04 map (default-account-map-skr04) can only resolve a
;; code→eid when handed a `db` (resolve-default-code, because
;; :kontor.account/code is not :db.unique/identity — ADR-119). With no `db`
;; it falls back to a bare [:kontor.account/code "3790"] lookup-ref, which
;; the kernel refuses to transact ({:error :lookup-ref/unique}). Net effect:
;; the whole point of "DE ships a default account map" is defeated at the
;; orchestrator boundary — a DE consumer MUST hand-build an :accounts map
;; even though the module ships one. INTENDED: passing :accounts {} (relying
;; on the shipped default map) through run-payroll! should post a balanced
;; journal. Fix: run-payroll! should pass (d/db conn)/the step's `db` into
;; build-postings. Remove ^:kaocha/pending once fixed.
(deftest ^:kaocha/pending de-run-payroll-with-shipped-default-map
  (let [[conn ids] (de-bootstrap)
        result (try {:report (de-run! conn ids {})}   ; {} → rely on shipped default SKR04 map
                    (catch Exception e {:error e}))]
    (testing "run-payroll! resolves the shipped DE default SKR04 map without a hand-built :accounts"
      (is (nil? (:error result))
          (str "run-payroll! dropped :db so the default map was unreachable: "
               (some-> (:error result) ex-message)))
      (is (some? (:db-after (:report result)))))))

;; ============================================================================
;; US — LLC 3-employee / 3-state, from create-us-db
;; ============================================================================
;; ADP GLI fixture gli-3-employees-3-states.csv. Hand-derived from the rows:
;;   E101 CA gross 8500, ded 1500+680+527+123.25 = 2830.25 -> net 5669.75
;;   E102 NY gross 9200, ded 1700+575+570.40+133.40 = 2978.80 -> net 6221.20
;;   E103 TX gross 7800, ded 1300+483.60+113.10 = 1896.70 -> net 5903.30
;;   Σ gross = 25 500.00 ; Σ net = 17 794.25
;;   Federal withholding Σ = 1500+1700+1300 = 4500 (Cr) -> account 2400
;;   Wages expense Σ = 25 500 (Dr) -> account 6110

(defn- us-bootstrap []
  (let [conn (us-preset/create-us-db)]
    (hr/install! conn)
    (adp/install! conn)
    (d/transact conn
                [{:kontor.entity/code "US-LLC" :kontor.entity/name "Acme US LLC"
                  :kontor.entity/kind :operating}
                 {:kontor.ledger/code "us-gaap" :kontor.ledger/name "US GAAP"
                  :kontor.ledger/framework :us-gaap :kontor.ledger/active true}
                 {:kontor.period/name "2026-04"
                  :kontor.period/start #inst "2026-04-01"
                  :kontor.period/end   #inst "2026-05-01"}])
    (doseq [[ext g f] [["P-E101" "Alice" "Pacific"] ["P-E102" "Bob" "Empire"]
                       ["P-E103" "Carol" "Lonestar"]]]
      (person/create-person! conn {:external-id ext :given-name g :family-name f}))
    (let [db (d/db conn)
          ent (ref-eid db :kontor.entity/code "US-LLC")]
      (doseq [[code ext] [["E101" "P-E101"] ["E102" "P-E102"] ["E103" "P-E103"]]]
        (employment/hire! conn {:code code :person (hr/person-by-external-id db ext)
                                :entity ent :start-date #inst "2025-01-01"
                                :job-title "Engineer"}))
      (let [db2 (d/db conn)]
        (payperiod/create-pay-period! conn {:code "US-2026-04" :entity ent
                                            :start-date #inst "2026-04-01"
                                            :end-date #inst "2026-04-30"
                                            :frequency :monthly
                                            :fiscal-period (ref-eid db2 :kontor.period/name "2026-04")})
        [conn {:ent ent
               :usd (ref-eid db2 :kontor.commodity/symbol "USD")
               :ledger (ref-eid db2 :kontor.ledger/code "us-gaap")
               :journal (ref-eid db2 :kontor.journal/code "GJ")
               :pp (hr/pay-period-by-code (d/db conn) "US-2026-04")
               :e101 (hr/employment-by-code db2 "E101")
               :e102 (hr/employment-by-code db2 "E102")
               :e103 (hr/employment-by-code db2 "E103")}]))))

(deftest us-run-payroll-from-preset-chart
  ;; The l10n-us preset chart HAS payroll accounts; the consumer only has
  ;; to bridge the wage-type-map account-keys to them (no default map
  ;; ships — that is the US friction). This confirms the substrate works
  ;; end-to-end from the preset once the bridge map is supplied.
  (let [[conn ids] (us-bootstrap)
        db (d/db conn)
        ;; wage-type account-key -> l10n-us chart account eid.
        accounts {:wages-expense     (code->eid db "6110")   ; Expenses:Payroll:Wages
                  :er-fica-ss        (code->eid db "6120")   ; Expenses:Payroll:Employer-Tax
                  :er-fica-medicare  (code->eid db "6120")
                  :er-futa           (code->eid db "6120")
                  :er-suta           (code->eid db "6120")
                  :ee-fed-withheld   (code->eid db "2400")   ; Federal-Withholding
                  :ee-state-withheld (code->eid db "2310")   ; State-Withholding
                  :ee-fica-ss        (code->eid db "2410")   ; FICA
                  :ee-fica-medicare  (code->eid db "2410")
                  :net-pay-payable   (code->eid db "2300")   ; Wages-Salaries payable
                  :balance-clearing  (code->eid db "2300")}
        csv (io/resource "kontor/payroll_us_adp/fixtures/gli-3-employees-3-states.csv")
        report (payroll/run-payroll!
                conn {:pay-period (:pp ids)
                      :entity (:ent ids)
                      :employments [(:e101 ids) (:e102 ids) (:e103 ids)]
                      :compute-provider (adp/make-adp-gli-compute-provider)
                      :posting-builder (adp/make-us-payroll-posting-builder {:commodity (:usd ids)})
                      :accounts accounts
                      :run-code "RUN-US-2026-04"
                      :tx-code "TX-US-2026-04"
                      :journal (:journal ids)
                      :commodity (:usd ids)
                      :ledger (:ledger ids)
                      :variable-inputs {:csv-source csv
                                        :wage-type-map (us-wt/load-reference)
                                        :employee->employment {"E101" (:e101 ids)
                                                               "E102" (:e102 ids)
                                                               "E103" (:e103 ids)}}})
        {:keys [run postings]} (run-postings (:db-after report) "RUN-US-2026-04")]
    (testing "run row lands with hand-derived control totals"
      (is (= :adp-gli (:kontor.payroll-run/provider-id run)))
      (is (= 0 (.compareTo ^BigDecimal (:kontor.payroll-run/control-total-gross run) 25500.00M)))
      (is (= 0 (.compareTo ^BigDecimal (:kontor.payroll-run/control-total-net run) 17794.25M))))
    (testing "the linked transaction balances per (ledger, commodity)"
      (is (balanced? postings)))
    (testing "wage + federal-withholding route to the l10n-us payroll accounts"
      (is (= 0 (.compareTo (total-on-code postings "6110") 25500.00M)))  ; wages expense Dr
      (is (= 0 (.compareTo (total-on-code postings "2400") -4500.00M)))))) ; federal withholding Cr

;; ============================================================================
;; CA — Inc monthly payroll, from create-ca-db
;; ============================================================================
;; Single ON employee (payroll-ca posting-builder-test figures):
;;   gross 5000.00  Dr 5400
;;   ITX withheld    850.00  Cr 2510
;;   EE-CPP          260.30  Cr 2520
;;   EE-EI            81.50  Cr 2530
;;   net = 5000 - 850 - 260.30 - 81.50 = 3808.20  Cr 2550
;;   ER-CPP 260.30  Dr 5410 / Cr 2520
;;   ER-EI  114.10  Dr 5411 / Cr 2530  (1.4 x 81.50)
;;   CRA CPP bucket (2520) = -(260.30 + 260.30) = -520.60
;;   CRA EI  bucket (2530) = -(81.50 + 114.10)  = -195.60

(defrecord MockOnCompute [emp]
  ppro/PayrollComputeProvider
  (provider-id [_] :mock-ca-on)
  (compute-payroll [_ _ctx]
    [{:employment emp
      :gross 5000M
      :net 3808.20M
      :components [{:kind :base-wage        :amount 5000M    :employer-side? false}
                   {:kind :income-tax-withheld :amount -850M :employer-side? false}
                   {:kind :employee-cpp     :amount -260.30M :employer-side? false}
                   {:kind :employee-ei      :amount -81.50M  :employer-side? false}
                   {:kind :employer-cpp     :amount 260.30M  :employer-side? true}
                   {:kind :employer-ei      :amount 114.10M  :employer-side? true}]
      :jurisdiction-specific-codes {:engine :mock-ca-on :province-of-employment "ON"}}]))

(defn- ca-accounts [db]
  {:ca-payroll-wages     (code->eid db "5400")
   :ca-payroll-itx       (code->eid db "2510")
   :ca-payroll-cpp       (code->eid db "2520")
   :ca-payroll-ei        (code->eid db "2530")
   :ca-payroll-er-cpp    (code->eid db "5410")
   :ca-payroll-er-ei     (code->eid db "5411")
   :ca-payroll-net-wages (code->eid db "2550")})

(defn- ca-bootstrap
  "create-ca-db preset + hr; caller decides whether to add the payroll
   starter chart. Returns [conn ids]."
  [install-payroll-chart?]
  (let [conn (ca-preset/create-ca-db)]
    (hr/install! conn)
    (when install-payroll-chart?
      (pca-chart/install! conn))
    (d/transact conn
                [{:kontor.entity/code "CA-INC" :kontor.entity/name "Acme Canada Inc."
                  :kontor.entity/kind :operating}
                 {:kontor.period/name "2026-05"
                  :kontor.period/start #inst "2026-05-01"
                  :kontor.period/end   #inst "2026-06-01"}])
    (person/create-person! conn {:external-id "P-jane" :given-name "Jane" :family-name "Kirk"})
    (let [db (d/db conn)
          ent (ref-eid db :kontor.entity/code "CA-INC")]
      (employment/hire! conn {:code "EMP-jane" :person (hr/person-by-external-id db "P-jane")
                              :entity ent :start-date #inst "2026-01-01" :job-title "Engineer"})
      (let [emp (hr/employment-by-code (d/db conn) "EMP-jane")]
        (payperiod/create-pay-period! conn {:code "CA-2026-05" :entity ent
                                            :start-date #inst "2026-05-01"
                                            :end-date #inst "2026-05-31"
                                            :frequency :monthly
                                            :fiscal-period (ref-eid (d/db conn) :kontor.period/name "2026-05")})
        (let [db2 (d/db conn)]
          [conn {:ent ent
                 :cad (ref-eid db2 :kontor.commodity/symbol "CAD")
                 :journal (ref-eid db2 :kontor.journal/code "GJ")
                 :pp (hr/pay-period-by-code db2 "CA-2026-05")
                 :emp emp}])))))

(defn- ca-run! [conn ids accounts]
  (payroll/run-payroll!
   conn {:pay-period (:pp ids)
         :entity (:ent ids)
         :employments [(:emp ids)]
         :compute-provider (->MockOnCompute (:emp ids))
         :posting-builder (ca-pb/->CaPayrollPostingBuilder {:commodity (:cad ids)})
         :accounts accounts
         :run-code "RUN-CA-2026-05"
         :tx-code "TX-CA-2026-05"
         :journal (:journal ids)
         :commodity (:cad ids)
         :vt-from #inst "2026-05-15"}))

;; PENDING(NEW): from the l10n-ca preset alone (`create-ca-db`) there are NO
;; payroll accounts in the chart — the CA payroll accounts live in a SEPARATE
;; starter chart (kontor.payroll-ca.chart/install!, resource coa_starter.edn)
;; that the preset's install-all! never calls — and no default tag→account map
;; ships. So the consumer's accounts map (built by looking up the payroll tags/
;; codes) resolves every entry to nil, and CaPayrollPostingBuilder throws
;; "No account configured for tag :ca-payroll-wages". A CA consumer following
;; the obvious 'create-ca-db then run payroll' path hits a wall. INTENDED: the
;; CA preset should make payroll runnable (ship the payroll accounts and/or a
;; default account map). Remove ^:kaocha/pending once the preset covers payroll.
(deftest ^:kaocha/pending ca-run-payroll-from-preset-alone
  (let [[conn ids] (ca-bootstrap false)   ; preset only — no payroll starter chart
        db (d/db conn)
        result (try {:report (ca-run! conn ids (ca-accounts db))}
                    (catch Exception e {:error e}))]
    (testing "payroll accounts should exist in the l10n-ca preset chart"
      (is (some? (code->eid db "5400"))
          "l10n-ca preset ships no wages-expense payroll account"))
    (testing "run-payroll! should post from the CA preset alone"
      (is (nil? (:error result))
          (str "CA payroll unreachable from preset: "
               (some-> (:error result) ex-message))))))

(deftest ca-run-payroll-with-payroll-chart-installed
  ;; The working CA path: also install the payroll starter chart, then
  ;; hand-build the tag→account map. Confirms the substrate + orchestrator
  ;; are correct; the gap is purely preset/default-map ergonomics.
  (let [[conn ids] (ca-bootstrap true)
        db (d/db conn)
        report (ca-run! conn ids (ca-accounts db))
        {:keys [run postings]} (run-postings (:db-after report) "RUN-CA-2026-05")]
    (testing "run row lands with the hand-derived gross control total"
      (is (= :mock-ca-on (:kontor.payroll-run/provider-id run)))
      (is (= 0 (.compareTo ^BigDecimal (:kontor.payroll-run/control-total-gross run) 5000M))))
    (testing "the linked transaction balances per (ledger, commodity)"
      (is (balanced? postings)))
    (testing "CRA CPP + EI buckets carry both employee and employer halves"
      (is (= 0 (.compareTo (total-on-code postings "5400") 5000M)))      ; wages expense Dr
      (is (= 0 (.compareTo (total-on-code postings "2510") -850M)))      ; ITX payable Cr
      (is (= 0 (.compareTo (total-on-code postings "2520") -520.60M)))   ; CPP (ee 260.30 + er 260.30)
      (is (= 0 (.compareTo (total-on-code postings "2530") -195.60M)))   ; EI  (ee 81.50 + er 114.10)
      (is (= 0 (.compareTo (total-on-code postings "2550") -3808.20M)))))) ; net wages payable Cr
