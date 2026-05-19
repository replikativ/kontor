^{:kindly/hide-code true
  :clay {:title "Showcase 6: Multi-year DE GmbH — bitemporal correction + DSAR + retention"
         :format [:quarto :html]}}
(ns showcases.06-de-gmbh-multi-year
  "Multi-national use case 6: Acme Manufacturing GmbH (München) —
   a synthetic 3-year company history exercising kontor's bitemporal
   substrate end-to-end on jurisdictional-depth real-world workflows.

   The headline narrative:

     - Year 1 (2026): hire 2 employees, run monthly DATEV LODAS
       payroll, Q1 expense posting that turns out to be misclassified.
     - Year 2 (2027): one employee promoted with a compensation
       supersession (kontor-hr `:compensation` lifecycle); Q4 discover
       the Y1 misclassified expense + record a backdated correction
       through the bitemporal substrate so the original posting
       remains visible at `(d/valid-at db 2026-12-31)`.
     - Year 3 (2028): the promoted employee terminates; DSAR request
       walks their full record via `kontor.dsar/collect` +
       `kontor-people-record`; ADR-094 + l10n-de retention seeds drive
       the retention-eligibility check.

   Synthetic data; all company/address/Steuernummer information is
   fictional. The accounting flow is grounded in:

     - DATEV LODAS Schnittstellenhandbuch (public spec)
     - SKR04 chart of accounts (DATEV cooperative standard)
     - HGB §238-263 (German Commercial Code accounting basics)
     - BDSG §26 (employment-relationship data processing basis)
     - DSGVO Art. 5(1)(e) (Speicherbegrenzung / storage limitation)
     - BetrVG §82-83 (employee personnel-file access)
     - ADR-094 (kontor substrate posture — note 93)

   Substrate exercised:

     - kontor-hr                — :person + :employment + :compensation
     - kontor-payroll-de-datev  — real DATEV-LODAS adapter (showcase
                                  reuses the same provider trio the
                                  module e2e test exercises)
     - kontor.hr.consent        — ADR-094 :consent/* + bitemporal
                                  legal-basis tracking
     - kontor.audit-doc         — canonical category vocabulary
                                  (ADR-094)
     - kontor.l10n-de.retention — DE per-(jurisdiction × category)
                                  retention seeds (ADR-094)
     - kontor.dsar              — partner-side DSAR walker (ADR-052)
     - kontor.bitemporal        — `with-vt` + `close-validity!` for
                                  the Y2 backdated-correction story

   Note this showcase deliberately SKIPS several pieces called out
   in note 90 §4 Scenario A — asset depreciation, lease IFRS 16,
   parallel-ledger Steuerbilanz reconciliation, UStVA amendment —
   to keep the notebook focused on the load-bearing substrate
   demonstration. Those are exercised in their own per-module e2e
   tests (kontor-asset / kontor-lease / kontor.l10n-de.ustva)."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.bitemporal :as kbt]
            [kontor.core :as core]
            [kontor.dsar :as dsar]
            [kontor.hr.compensation :as comp]
            [kontor.hr.consent :as consent]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.l10n-de.retention :as de-retention]
            [kontor.payroll-de-datev.core :as datev]
            [kontor.payroll-de-datev.wage-types :as datev-wt]
            [kontor.people-record.core :as pr]
            [kontor.people-record.schema :as pr-schema]
            [kontor.posting :as posting]
            [kontor.retention :as retention]
            [kontor.validation :as validation])
  (:import [java.util Date]))

;; # Showcase 6: Multi-year DE GmbH
;;
;; *Acme Manufacturing GmbH* (München, HRB 123456) — three-year
;; company history exercising kontor's bitemporal substrate +
;; jurisdictional depth + DSGVO compliance.

;; ## Setup
;;
;; Install kontor + kontor-hr + kontor-payroll-de-datev + kontor-
;; people-record + the DE retention seeds. The chart of accounts
;; uses a minimal SKR04 subset sufficient for the payroll +
;; correction story.

(def conn (core/create-test-db))
(hr/install! conn)
(datev/install! conn)
(pr/install! conn)
(de-retention/install! conn)

;; Commodities, entity, journals, fiscal periods, and the SKR04
;; payroll account subset.

(d/transact
 conn
 [{:db/id "eur" :commodity/symbol "EUR" :commodity/precision 2}
  {:db/id "ent" :entity/code "ACME-DE" :entity/name "Acme Manufacturing GmbH"
   :entity/active true}
  {:db/id "j-pay" :journal/code "PAY-DE" :journal/name "Payroll DE"
   :journal/type :general}
  {:db/id "j-gen" :journal/code "GEN-DE" :journal/name "General DE"
   :journal/type :general}

  ;; Annual fiscal periods 2026/2027/2028
  {:db/id "p-2026" :period/name "2026"
   :period/start #inst "2026-01-01"
   :period/end   #inst "2027-01-01"}
  {:db/id "p-2027" :period/name "2027"
   :period/start #inst "2027-01-01"
   :period/end   #inst "2028-01-01"}
  {:db/id "p-2028" :period/name "2028"
   :period/start #inst "2028-01-01"
   :period/end   #inst "2029-01-01"}

  ;; SKR04 payroll accounts (the 10 load-bearing ones)
  {:db/id "a-6020" :account/code "6020" :account/name "Gehälter"
   :account/type :expense :account/active true}
  {:db/id "a-6035" :account/code "6035"
   :account/name "Aufwendungen Urlaubsrückstellung"
   :account/type :expense :account/active true}
  {:db/id "a-6060" :account/code "6060"
   :account/name "Freiwillige soziale Aufwendungen, lohnsteuerpflichtig"
   :account/type :expense :account/active true}
  {:db/id "a-6110" :account/code "6110"
   :account/name "Gesetzliche soziale Aufwendungen"
   :account/type :expense :account/active true}
  {:db/id "a-3066" :account/code "3066"
   :account/name "Urlaubsrückstellung"
   :account/type :liability :account/active true}
  {:db/id "a-3720" :account/code "3720"
   :account/name "Verbindlichkeiten LuG"
   :account/type :liability :account/active true}
  {:db/id "a-3730" :account/code "3730"
   :account/name "Verbindlichkeiten LSt+KiSt+Soli"
   :account/type :liability :account/active true}
  {:db/id "a-3740" :account/code "3740"
   :account/name "Verbindlichkeiten SV"
   :account/type :liability :account/active true}
  {:db/id "a-3790" :account/code "3790"
   :account/name "Lohn- und Gehaltsverrechnungskonto"
   :account/type :liability :account/active true}
  {:db/id "a-6010" :account/code "6010" :account/name "Löhne"
   :account/type :expense :account/active true}

  ;; Operating accounts used for the Y1 misclassification story
  {:db/id "a-4660" :account/code "4660"
   :account/name "Reisekosten Arbeitnehmer"
   :account/type :expense :account/active true}
  {:db/id "a-4650" :account/code "4650"
   :account/name "Bewirtungskosten 70% abziehbar"
   :account/type :expense :account/active true}
  {:db/id "a-1000" :account/code "1000"
   :account/name "Kasse"
   :account/type :asset :account/active true}])

(def eur (d/q '[:find ?e . :where [?e :commodity/symbol "EUR"]] (d/db conn)))
(def ent (d/q '[:find ?e . :where [?e :entity/code "ACME-DE"]] (d/db conn)))
(def j-pay (d/q '[:find ?e . :where [?e :journal/code "PAY-DE"]] (d/db conn)))
(def j-gen (d/q '[:find ?e . :where [?e :journal/code "GEN-DE"]] (d/db conn)))
(def p-2026 (d/q '[:find ?e . :where [?e :period/name "2026"]] (d/db conn)))

;; ## Year 1 — hire 2 employees + grant consents
;;
;; Acme hires Franz Müller (Geschäftsführer) and Anna Schmidt
;; (Vertriebsmitarbeiterin) effective 2026-01-15. The substrate
;; requires (per ADR-094) that processing of `:hr-track-record`
;; data has a recorded legal basis — we grant consent via
;; `kontor.hr.consent/grant!` with BDSG §26(1) employment basis.

(person/create-person! conn {:external-id "P-mueller"
                             :given-name "Franz" :family-name "Müller"
                             :birth-date #inst "1968-05-04"
                             :citizenship ["DE"]})
(person/create-person! conn {:external-id "P-schmidt"
                             :given-name "Anna" :family-name "Schmidt"
                             :birth-date #inst "1985-09-22"
                             :citizenship ["DE"]})

(def mueller (hr/person-by-external-id (d/db conn) "P-mueller"))
(def schmidt (hr/person-by-external-id (d/db conn) "P-schmidt"))

;; Consent — supporting DPIA + works-agreement (synthetic refs).
(audit-doc/create-doc!
 conn {:code "DPIA-acme-employees-2026"
       :type :dpia
       :storage-uri "s3://acme/dpia-employees-2026.pdf"
       :title "DPIA — Mitarbeiter-Personalakte (BDSG §26)"
       :category :hr-monitoring-consent})

(def dpia (d/q '[:find ?e . :in $ ?c
                 :where [?e :audit-doc/code ?c]]
               (d/db conn) "DPIA-acme-employees-2026"))

(doseq [[code subject] [["CONS-mueller-2026" mueller]
                        ["CONS-schmidt-2026" schmidt]]]
  (consent/grant!
   conn {:code code
         :subject subject
         :scope :hr-track-record
         :legal-basis :bdsg-26-1-employment
         :granted-at #inst "2026-01-15"
         :supporting-doc dpia}))

;; Hire both employees.
(employment/hire! conn {:code "EMP-mueller"
                        :person mueller :entity ent
                        :start-date #inst "2026-01-15"
                        :job-title "Geschäftsführer"})
(employment/hire! conn {:code "EMP-schmidt"
                        :person schmidt :entity ent
                        :start-date #inst "2026-01-15"
                        :job-title "Vertriebsmitarbeiterin"})

(def emp-mueller (hr/employment-by-code (d/db conn) "EMP-mueller"))
(def emp-schmidt (hr/employment-by-code (d/db conn) "EMP-schmidt"))

;; Set initial compensations.
(comp/set-compensation!
 conn {:employment emp-mueller :effective-from #inst "2026-01-15"
       :commodity eur
       :components [{:kind :base-wage :amount 8500M :period :monthly}]})
(comp/set-compensation!
 conn {:employment emp-schmidt :effective-from #inst "2026-01-15"
       :commodity eur
       :components [{:kind :base-wage :amount 4200M :period :monthly}]})

;; Record the canonical Y1 positions in the people-record substrate.
(pr/record-position!
 conn {:code "POS-mueller-gf-1" :person mueller :employment emp-mueller
       :title "Geschäftsführer" :level :c-suite
       :start-date #inst "2026-01-15"
       :at #inst "2026-01-15"})
(pr/record-position!
 conn {:code "POS-schmidt-vs-1" :person schmidt :employment emp-schmidt
       :title "Vertriebsmitarbeiterin" :level :ic-2
       :start-date #inst "2026-01-15"
       :at #inst "2026-01-15"})

;; ## A representative Y1 month of payroll
;;
;; Run a single representative month (2026-11) through the real
;; DATEV LODAS adapter — the per-module e2e test already validates
;; the 10-leg Bruttomethode shape; we wire it here to show the
;; substrate composition. A full 12-month loop is a one-off detail
;; the showcase elides.

(pp/create-pay-period! conn {:code "DE-2026-11"
                             :entity ent
                             :start-date #inst "2026-11-01"
                             :end-date #inst "2026-11-30"
                             :frequency :monthly
                             :fiscal-period p-2026})

(def pp-2026-11 (hr/pay-period-by-code (d/db conn) "DE-2026-11"))

(def catalog
  (datev-wt/validate-catalog
   {:catalog/version 1
    :catalog/mandant "99999"
    :catalog/berater "1234"
    :catalog/coa     :skr04
    :catalog/wage-types {100 {:kind :base-salary :account-hint :gehalt}}}))

(def accounts-map
  {:lohn                        (d/q '[:find ?e . :where [?e :account/code "6010"]] (d/db conn))
   :gehalt                      (d/q '[:find ?e . :where [?e :account/code "6020"]] (d/db conn))
   :freiwillig-st-pflichtig     (d/q '[:find ?e . :where [?e :account/code "6060"]] (d/db conn))
   :soziale-aufwendungen        (d/q '[:find ?e . :where [?e :account/code "6110"]] (d/db conn))
   :urlaubsrueckstellung-aufw   (d/q '[:find ?e . :where [?e :account/code "6035"]] (d/db conn))
   :urlaubsrueckstellung        (d/q '[:find ?e . :where [?e :account/code "3066"]] (d/db conn))
   :verb-lohn                   (d/q '[:find ?e . :where [?e :account/code "3720"]] (d/db conn))
   :verb-lohnsteuer             (d/q '[:find ?e . :where [?e :account/code "3730"]] (d/db conn))
   :verb-sozialversicherung     (d/q '[:find ?e . :where [?e :account/code "3740"]] (d/db conn))
   :verrechnung                 (d/q '[:find ?e . :where [?e :account/code "3790"]] (d/db conn))})

(def fixture-buchungsbeleg
  (slurp (io/resource "kontor/payroll_de_datev/fixtures/buchungsbeleg-2025-11.csv")
         :encoding "ISO-8859-1"))

;; The fixture's PNR is "3011" — map it to Müller for this showcase.
(def compute (datev/make-compute-provider
              {:coa :skr04 :employment-pnr->eid {"3011" emp-mueller}}))

(def builder (datev/make-posting-builder
              {:catalog catalog :commodity eur}))

(def emitter (datev/make-emit-provider
              {:catalog catalog
               :allgemein {:berater-nr "1234" :mandant-nr "99999"
                           :stammdaten-gueltig-ab #inst "2026-11-01"}
               :pay-period-date #inst "2026-11-01"
               :pay-period-code "DE-2026-11"}))

(def november-payroll
  (payroll/run-payroll!
   conn {:pay-period pp-2026-11
         :entity ent
         :employments [emp-mueller]
         :compute-provider compute
         :posting-builder builder
         :emit-provider emitter
         :accounts accounts-map
         :variable-inputs {:buchungsbeleg-content fixture-buchungsbeleg}
         :run-code "RUN-DE-2026-11"
         :tx-code "TX-PAYROLL-DE-2026-11"
         :journal j-pay
         :commodity eur}))

(:tx-data november-payroll)
;; ⇒ a tx-report — the 10-leg Bruttomethode + payroll-run row +
;;   LODAS audit-doc all landed in one validation-gated transaction.

;; ## The misclassified Y1 expense
;;
;; In November 2026 a 1200 EUR business-dinner cost lands in
;; Reisekosten Arbeitnehmer (#4660) — fully deductible. By DE tax
;; law (EStG §4(5) Nr. 2) it should be Bewirtungskosten (#4650) at
;; 70% deductibility. Acme doesn't catch this until Q4 of Y2 when
;; the Steuerberater reviews the trial balance.

(def misclassified-tx-report
  (validation/transact-with-validation
   conn
   (kbt/with-vt
     (posting/post-transaction-tx-data
      {:transaction {:transaction/external-id "TX-MUELLER-DINNER-2026-11"
                     :transaction/journal j-gen
                     :transaction/effective-date #inst "2026-11-22"
                     :transaction/narration "Geschäftsessen Kundenakquise (misclassified)"}
       :postings
       [{:posting/account (d/q '[:find ?e . :where [?e :account/code "4660"]] (d/db conn))
         :posting/amount 1200.00M
         :posting/commodity eur
         :posting/narration "Wrong account — should have been Bewirtungskosten"}
        {:posting/account (d/q '[:find ?e . :where [?e :account/code "1000"]] (d/db conn))
         :posting/amount -1200.00M
         :posting/commodity eur
         :posting/narration "Kasse"}]})
     #inst "2026-11-22")))

;; The datahike commit-tx eid (NOT the kontor :transaction entity).
;; close-validity! operates on the datahike tx — the carrier of the
;; `:db.valid/from` / `:db.valid/to` window. `kbt/commit-tx-eid` is
;; the substrate helper that makes the extraction one-line.
(def misclassified-tx-eid
  (kbt/commit-tx-eid misclassified-tx-report))

misclassified-tx-eid
;; ⇒ the datahike commit-tx eid

;; ## Year 2 — promotion + the backdated correction
;;
;; Anna Schmidt promotes to Vertriebsleiterin on 2027-03-01.

(audit-doc/create-doc!
 conn {:code "PROMO-LETTER-schmidt-2027"
       :type :promotion-letter
       :storage-uri "s3://acme/promo-schmidt.pdf"
       :category :hr-track-record})
(def promo-doc (d/q '[:find ?e . :in $ ?c :where [?e :audit-doc/code ?c]]
                    (d/db conn) "PROMO-LETTER-schmidt-2027"))

;; New compensation envelope (kontor-hr's supersession story).
(comp/set-compensation!
 conn {:employment emp-schmidt :effective-from #inst "2027-03-01"
       :commodity eur
       :components [{:kind :base-wage :amount 5400M :period :monthly}]})

(def comp-eid
  (d/q '[:find ?c .
         :in $ ?emp ?ef
         :where
         [?c :compensation/employment ?emp]
         [?c :compensation/effective-from ?ef]]
       (d/db conn) emp-schmidt #inst "2027-03-01"))

(pr/record-position!
 conn {:code "POS-schmidt-leiter-1" :person schmidt :employment emp-schmidt
       :title "Vertriebsleiterin" :level :manager
       :start-date #inst "2027-03-01"
       :at #inst "2027-03-01"})

(def pos-schmidt-old (d/q '[:find ?p . :in $ ?ext
                            :where [?p :position-held/external-id ?ext]]
                          (d/db conn) "POS-schmidt-vs-1"))
(def pos-schmidt-new (d/q '[:find ?p . :in $ ?ext
                            :where [?p :position-held/external-id ?ext]]
                          (d/db conn) "POS-schmidt-leiter-1"))

(pr/record-promotion!
 conn {:code "PROMO-schmidt-2027-03"
       :person schmidt
       :from-position pos-schmidt-old
       :to-position pos-schmidt-new
       :effective-date #inst "2027-03-01"
       :comp-change comp-eid
       :supporting-doc promo-doc
       :at #inst "2027-03-01"})

;; ## The backdated correction
;;
;; Steuerberater discovers the misclassification on 2027-10-15 while
;; reviewing the Y1 trial balance. The substrate-honest correction:
;;
;; 1. Close the original Y1 posting's valid-time window at the
;;    correction date — the original fact ceases to be authoritative
;;    on 2027-10-15.
;; 2. Record the corrected postings (split: 70% to #4650 deductible,
;;    30% non-deductible) effective from the original transaction
;;    date (so books-as-restated reflect the right Y1 numbers) but
;;    with `:tx/valid-from = 2027-10-15` so the restated reality
;;    only appears on the AS-OF-TX axis from the correction date
;;    forward.
;;
;; The result: `(d/valid-at db #inst "2026-12-31")` still returns the
;; ORIGINAL posting (the books-as-known-then), while
;; `(d/valid-at db #inst "2027-11-01")` returns the CORRECTED postings.

;; Close-validity on the original tx
(kbt/close-validity! conn misclassified-tx-eid #inst "2027-10-15")

;; Record the corrected posting set (Bewirtungskosten 70% + nondeductible 30%
;; against the same cash account, balanced)
(validation/transact-with-validation
 conn
 (kbt/with-vt
   (posting/post-transaction-tx-data
    {:transaction {:transaction/external-id "TX-MUELLER-DINNER-2026-11-CORR"
                   :transaction/journal j-gen
                   :transaction/effective-date #inst "2026-11-22"
                   :transaction/narration "Bewirtungskosten Geschäftsessen (correction Oct 2027)"}
     :postings
     [{:posting/account (d/q '[:find ?e . :where [?e :account/code "4650"]] (d/db conn))
       :posting/amount 1200.00M
       :posting/commodity eur
       :posting/narration "Bewirtungsaufwand 100% (70% abziehbar per EStG §4(5) Nr. 2)"}
      {:posting/account (d/q '[:find ?e . :where [?e :account/code "1000"]] (d/db conn))
       :posting/amount -1200.00M
       :posting/commodity eur
       :posting/narration "Kasse (no cash impact — reclassification only)"}]})
   #inst "2027-10-15"))

;; Verify the bitemporal-correction story end-to-end
(defn account-postings-at [account-code as-of-vt]
  (let [acct (d/q '[:find ?e . :in $ ?c :where [?e :account/code ?c]] (d/db conn) account-code)
        db (d/valid-at (d/db conn) as-of-vt)]
    (d/q '[:find ?p ?amt ?narr
           :in $ ?a
           :where
           [?p :posting/account ?a]
           [?p :posting/amount ?amt]
           [?p :posting/narration ?narr]]
         db acct)))

;; Before the correction (within Y1) — books show the ORIGINAL misclassification
(account-postings-at "4660" #inst "2026-12-31")
;; ⇒ #{[<eid> 1200.00M "Wrong account — should have been Bewirtungskosten"]}
(account-postings-at "4650" #inst "2026-12-31")
;; ⇒ #{}  (the corrected posting wasn't recorded yet)

;; After the correction (Y2 Q4) — books-as-known-now show the RESTATED view
(account-postings-at "4660" #inst "2027-11-01")
;; ⇒ #{}  (the original tx's vt-window has closed)
(account-postings-at "4650" #inst "2027-11-01")
;; ⇒ #{[<eid> 1200.00M "Bewirtungsaufwand 100% (70% abziehbar per EStG §4(5) Nr. 2)"]}

;; ## Year 3 — termination + DSAR + retention sweep
;;
;; Anna Schmidt terminates 2028-06-30. A DSAR request lands 2028-09-15
;; — kontor.dsar walks her records via the substrate plus the
;; kontor-people-record companion's bundle.

;; The DSAR walk needs a :partner row for Schmidt. The kontor-hr
;; substrate links :partner → :person; we add the partner row first.
(d/transact conn
            [{:partner/external-id "PARTNER-schmidt"
              :partner/name "Anna Schmidt"
              :partner/kind :employee
              :partner/person schmidt}])

(def schmidt-partner (d/q '[:find ?p . :in $ ?x
                            :where [?p :partner/external-id ?x]]
                          (d/db conn) "PARTNER-schmidt"))

;; Kernel-level DSAR walk (per ADR-052 + the note 86 P1-86-5 fix —
;; HR extension collector populates `:extensions :hr`):
(def kernel-dsar-bundle
  (dsar/collect (d/db conn) schmidt-partner {}))

(select-keys kernel-dsar-bundle [:partner :extensions])
;; ⇒ {:partner {...partner pull...}
;;     :extensions {:hr {:employments [...] :compensations [...]}}}

;; Per-companion: people-record bundle
(def pr-bundle (pr/dsar-bundle (d/db conn) schmidt))

{:positions  (count (:positions pr-bundle))
 :reviews    (count (:reviews pr-bundle))
 :promotions (count (:promotions pr-bundle))}
;; ⇒ {:positions 2 :reviews 0 :promotions 1}

;; ## Retention sweep — DE seeds drive the eligibility check
;;
;; The DPIA audit-doc was uploaded 2026-01-15 with category
;; `:hr-monitoring-consent`. The l10n-de seed says
;; `:hr-monitoring-consent` retention = 10 years + archive-to-cold-
;; storage. So at 2028-09-15 (Y3), the DPIA is NOT yet eligible for
;; expiry. The retention sweeper proves this.

(def dpia-policy
  (d/q '[:find ?p .
         :in $ ?code
         :where [?p :retention-policy/code ?code]]
       (d/db conn) "DE-DSGVO-hr-monitoring-consent"))

(def dpia-deadline (retention/retention-deadline (d/db conn) dpia dpia-policy))
dpia-deadline
;; ⇒ 2036-01-15 (10 years from upload)

(retention/eligible? (d/db conn) dpia dpia-policy {:as-of #inst "2028-09-15"})
;; ⇒ false — still within retention floor

(retention/eligible? (d/db conn) dpia dpia-policy {:as-of #inst "2037-01-01"})
;; ⇒ true — past retention floor + no legal hold

;; ## What this showcase demonstrates
;;
;; - **Multi-year company history** (2026-2028) running on real
;;   substrate primitives: kontor-hr + kontor-payroll-de-datev +
;;   kontor-people-record + kontor.l10n-de.retention.
;; - **Bitemporal correction story** — the canonical "what we
;;   believed then vs what we know now" demo on synthetic DE data.
;;   The original misclassified posting remains queryable via
;;   `(d/valid-at db 2026-12-31)`; the corrected restatement appears
;;   only at `(d/valid-at db 2027-11-01)`.
;; - **Compensation supersession + promotion** through the people-
;;   record companion, consent-gated per ADR-094.
;; - **DSAR walk** combining the kernel partner walker
;;   (kontor.dsar/collect) with the kontor-people-record bundle.
;; - **Retention sweep** using DE-seeded `:retention-policy` rows
;;   from the l10n-de companion — demonstrates how l10n-specific
;;   statute durations attach without kernel changes.
;;
;; ## What this showcase deliberately does NOT cover
;;
;; - **Asset depreciation** (kontor-asset) — exercised in its own
;;   per-module e2e test; bringing 7-year linear AfA into this
;;   showcase doubles the LoC without adding bitemporal substrate
;;   demonstration.
;; - **Lease IFRS 16** (kontor-lease) — same reasoning. ADR-064's
;;   modification + remeasurement story is in
;;   `modules/lease/test/`.
;; - **Parallel-ledger HGB-vs-Steuerbilanz reconciliation**
;;   (ADR-021 / kontor-l10n-de) — the dual-ledger structure exists
;;   in the substrate; the showcase elides the reconciliation
;;   step.
;; - **UStVA amendment workflow** — the misclassification SHOULD
;;   trigger a UStVA correction filing under §164 AO; the
;;   kontor.l10n-de.ustva module provides the form generator, but
;;   the showcase stops at the GL correction.
;; - **Full 12-month payroll loop** — one representative month
;;   exercises the DATEV adapter; looping 12 times is mechanical.
;;
;; Each elided piece is its own per-module e2e or unit test;
;; bringing all of them into one showcase trades narrative clarity
;; for completeness. The showcase optimizes for clarity.
;;
;; ## License + data posture
;;
;; Synthetic data throughout. Company names, Steuernummern, employee
;; names, and amounts are fictional. The accounting workflows cite
;; real German statutes (BGB / HGB / BDSG / DSGVO / EStG / AO) but
;; the implementation is grounded in publicly documented standards
;; (DATEV SKR04, LODAS Schnittstellenhandbuch, BetrVG, BAG case law)
;; — see ADR-094 + research notes 93 + 94 for the regulatory
;; sources kontor's substrate posture cites.
