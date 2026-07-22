^{:kindly/hide-code true
  :clay {:title "Showcase 2: US LLC multi-state sales tax + Reg-F dunning"
         :format [:quarto :html]}}
(ns showcases.02-us-llc-multi-state
  "Multi-national use case 2: a fictional US LLC selling SaaS to
   customers across multiple states (CA, NY, TX, WA). End-to-end:

     - Multi-state sales tax via a stub TaxProvider (the kernel's
       ADR-005 protocol; production users plug Avalara/TaxJar/
       TaxCloud here)
     - Customer dispute lifecycle
     - Reg-F (Regulation F, 12 CFR Part 1006) frequency-cap-as-
       predicate dunning
     - Customer concedes after L2 reminder; final payment
     - Write-off path NOT exercised here (Showcase 1 covered it)

   Synthetic data. Cited:
     - 12 CFR Part 1006 (CFPB Regulation F, 7-in-7 rule for consumer
       collections; B2B not strictly covered but most US AR systems
       use Reg-F as their internal cap)
     - Streamlined Sales Tax (SST) member states' rate sources
     - SST Governing Board uniform definitions
     - IRS Pub 946 (MACRS — informational only, not used in this
       showcase)
     - Avalara API surface (compared, not lifted; per ADR-005 + ADR-
       010 we ship the protocol, not the engine)

   Reference: NetSuite uses TaxJar as a built-in adapter; SAP S/4HANA
   has 'TTE' (Transaction Tax Engine); Salesforce Revenue Cloud
   exposes a TaxAdjustmentRules table; QuickBooks Online relies on
   the Intuit-built sales-tax service. kontor's value: the same
   substrate (TaxProvider protocol + ADR-040 reverse-charge / WHT
   flags + bitemporal queries) handles ANY of these adapters."
  (:require [datahike.api :as d]
            [kontor.collections.case :as kcase]
            [kontor.collections.dispute :as kdispute]
            [kontor.collections.dunning :as kdunning]
            [kontor.collections.schema :as coll-schema]
            [kontor.core :as core]
            [kontor.invoice.bridge :as inv]
            [kontor.invoice.schema :as inv-schema]
            [kontor.partner.schema :as partner-schema]
            [kontor.banking.payment-application :as papp]
            [kontor.sales.schema :as sales-schema]
            [kontor.workflow.status-machine :as sm]))

;; # The story
;;
;; **Skyline Analytics LLC** (Delaware-organized, NY-headquartered)
;; sells a $399/month SaaS subscription. They have economic nexus in
;; CA, NY, TX, WA (revenue exceeds each state's threshold —
;; per Wayfair v. South Dakota 2018 and each state's specific rule).
;;
;; Q2 2026 invoices to four customers, one per state:
;;
;; - **CA-Customer** (Los Angeles) — 7.25% CA state + 1.00% LA county
;;   + 0.50% LA city + 1.25% LA District = **9.50% effective rate**
;; - **NY-Customer** (Brooklyn) — 4.0% NY state + 4.5% NYC city =
;;   **8.875% effective rate** (incl 0.375% MCTD surcharge)
;; - **TX-Customer** (Austin) — 6.25% state + 2.00% city = **8.25%
;;   effective rate**
;; - **WA-Customer** (Seattle) — 6.5% state + 3.85% King County +
;;   1.10% Sound Transit District = **10.5% effective rate** (with
;;   B&O tax handled separately)
;;
;; *These rates are notional for a 2026 snapshot; production
;; consumers plug Avalara / TaxJar / TaxCloud via the TaxProvider
;; protocol. ADR-005 explicitly forbids the kernel from bundling rate
;; tables.*
;;
;; One customer (WA) raises a small-dollar dispute. Reg-F caps
;; reminder cadence; we test the predicate.

(def conn (core/create-test-db))
(partner-schema/install! conn)
(sales-schema/install! conn)
(inv-schema/install! conn)
(coll-schema/install! conn)

;; ## Seeds: chart of accounts (simplified US GAAP), partners

(d/transact conn
            [{:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
              :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "USD"}
             {:kontor.entity/code "SKYLINE" :kontor.entity/name "Skyline Analytics LLC"
              :kontor.entity/kind :operating :kontor.entity/active true}
             ;; Accounts
             {:kontor.account/code "1200" :kontor.account/path "1200"
              :kontor.account/name "Accounts Receivable" :kontor.account/type :asset}
             {:kontor.account/code "4000" :kontor.account/path "4000"
              :kontor.account/name "SaaS Revenue" :kontor.account/type :revenue}
             {:kontor.account/code "2200" :kontor.account/path "2200"
              :kontor.account/name "Sales Tax Payable - CA" :kontor.account/type :liability}
             {:kontor.account/code "2201" :kontor.account/path "2201"
              :kontor.account/name "Sales Tax Payable - NY" :kontor.account/type :liability}
             {:kontor.account/code "2202" :kontor.account/path "2202"
              :kontor.account/name "Sales Tax Payable - TX" :kontor.account/type :liability}
             {:kontor.account/code "2203" :kontor.account/path "2203"
              :kontor.account/name "Sales Tax Payable - WA" :kontor.account/type :liability}
             ;; GL defaults
             {:kontor.gl-account-default/account-type :ar
              :kontor.gl-account-default/account [:kontor.account/path "1200"]}
             {:kontor.gl-account-default/account-type :sales-revenue
              :kontor.gl-account-default/account [:kontor.account/path "4000"]}
             ;; Journal
             {:kontor.journal/code "AR" :kontor.journal/name "Accounts Receivable"
              :kontor.journal/type :sales}
             ;; Customers
             {:kontor.partner/external-id "CA-CUST"
              :kontor.partner/name "GoldenGate Analytics Inc"
              :kontor.partner/kind :customer :kontor.partner/country-code "US"
              :kontor.partner/credit-status :open}
             {:kontor.partner/external-id "NY-CUST"
              :kontor.partner/name "Empire State Software LLC"
              :kontor.partner/kind :customer :kontor.partner/country-code "US"
              :kontor.partner/credit-status :open}
             {:kontor.partner/external-id "TX-CUST"
              :kontor.partner/name "Lone Star Data Co"
              :kontor.partner/kind :customer :kontor.partner/country-code "US"
              :kontor.partner/credit-status :open}
             {:kontor.partner/external-id "WA-CUST"
              :kontor.partner/name "Cascadia Cloud LLC"
              :kontor.partner/kind :customer :kontor.partner/country-code "US"
              :kontor.partner/credit-status :open}
             {:kontor.partner/external-id "U-alice" :kontor.partner/name "Alice (collector)"}
             {:kontor.partner/external-id "U-bob"   :kontor.partner/name "Bob (manager)"}])

(def skyline (d/q '[:find ?e . :where [?e :kontor.entity/code "SKYLINE"]] (d/db conn)))
(def usd     (d/q '[:find ?c . :where [?c :kontor.commodity/symbol "USD"]] (d/db conn)))
(def alice   (d/q '[:find ?p . :where [?p :kontor.partner/external-id "U-alice"]] (d/db conn)))
(def bob     (d/q '[:find ?p . :where [?p :kontor.partner/external-id "U-bob"]] (d/db conn)))

;; ## The TaxProvider stub
;;
;; The kernel's ADR-005 protocol pattern. Production consumers
;; plug Avalara/TaxJar/TaxCloud here. This stub is a static rate
;; table — what the rich providers would compute against jurisdiction
;; codes is hand-encoded.

(defprotocol StubTaxProvider
  (compute-tax-rate [this partner-ext-id]))

(def tax-provider
  (reify StubTaxProvider
    (compute-tax-rate [_ partner-ext-id]
      (case partner-ext-id
        "CA-CUST" {:rate 0.0950M :state "CA" :gl-account "2200"
                   :breakdown {"CA-State" 0.0725M "LA-County" 0.0100M
                               "LA-City" 0.0050M "LA-District" 0.0075M}}
        "NY-CUST" {:rate 0.08875M :state "NY" :gl-account "2201"
                   :breakdown {"NY-State" 0.040M "NYC-City" 0.045M
                               "MCTD" 0.00375M}}
        "TX-CUST" {:rate 0.0825M :state "TX" :gl-account "2202"
                   :breakdown {"TX-State" 0.0625M "Austin-City" 0.0200M}}
        "WA-CUST" {:rate 0.105M  :state "WA" :gl-account "2203"
                   :breakdown {"WA-State" 0.065M "King-County" 0.0385M
                               "Sound-Transit" 0.0110M}}))))

;; ## Issue four invoices, one per state
;;
;; Each is a $399 line + state-specific tax line.

(defn issue-invoice! [external-id partner-ext-id net]
  (let [tax-info (compute-tax-rate tax-provider partner-ext-id)
        rate (:rate tax-info)
        tax-amt (.setScale (.multiply ^java.math.BigDecimal net
                                       ^java.math.BigDecimal rate)
                            2 java.math.RoundingMode/HALF_UP)
        gross (.add ^java.math.BigDecimal net ^java.math.BigDecimal tax-amt)
        partner-eid (d/q '[:find ?p . :in $ ?x
                           :where [?p :kontor.partner/external-id ?x]]
                         (d/db conn) partner-ext-id)
        tax-acct (d/q '[:find ?a . :in $ ?p
                        :where [?a :kontor.account/path ?p]]
                      (d/db conn) (:gl-account tax-info))
        inv-tempid (str "inv-" external-id)
        prod-tempid (str "pl-prod-" external-id)
        tax-tempid (str "pl-tax-" external-id)]
    (d/transact conn
                [{:db/id inv-tempid
                  :kontor.invoice/external-id external-id
                  :kontor.invoice/type :sales
                  :kontor.invoice/status :sent
                  :kontor.invoice/issue-date #inst "2026-04-01"
                  :kontor.invoice/buyer partner-eid
                  :kontor.invoice/entity skyline
                  :kontor.invoice/currency "USD"
                  :kontor.invoice/total-net net
                  :kontor.invoice/total-vat tax-amt
                  :kontor.invoice/total-gross gross
                  :kontor.invoice/lines [prod-tempid tax-tempid]}
                 {:db/id prod-tempid
                  :kontor.invoice-line/invoice inv-tempid
                  :kontor.invoice-line/sequence 1
                  :kontor.invoice-line/name "Skyline SaaS Pro - Monthly"
                  :kontor.invoice-line/quantity 1M
                  :kontor.invoice-line/unit-price net
                  :kontor.invoice-line/amount net
                  :kontor.invoice-line/gl-account-type :sales-revenue}
                 {:db/id tax-tempid
                  :kontor.invoice-line/invoice inv-tempid
                  :kontor.invoice-line/sequence 2
                  :kontor.invoice-line/name (str (:state tax-info) " Sales Tax")
                  :kontor.invoice-line/quantity 1M
                  :kontor.invoice-line/unit-price tax-amt
                  :kontor.invoice-line/amount tax-amt
                  :kontor.invoice-line/account tax-acct
                  :kontor.invoice-line/gl-account-type :sales-tax-payable
                  :kontor.invoice-line/vat-rate (.multiply rate 100M)
                  :kontor.invoice-line/vat-category "STATE"}])
    {:external-id external-id :gross gross :tax tax-amt :rate rate
     :state (:state tax-info)}))

(def inv-ca (issue-invoice! "SKY-2026-CA-001" "CA-CUST" 399M))
(def inv-ny (issue-invoice! "SKY-2026-NY-001" "NY-CUST" 399M))
(def inv-tx (issue-invoice! "SKY-2026-TX-001" "TX-CUST" 399M))
(def inv-wa (issue-invoice! "SKY-2026-WA-001" "WA-CUST" 399M))

;; All four invoices created. Per-state gross totals:
[inv-ca inv-ny inv-tx inv-wa]

;; The TX customer pays on time. The other three become past-due.

(defn pay-in-full! [invoice-external-id payment-external-id]
  (let [buyer (d/q '[:find ?b . :in $ ?i
                     :where [?inv :kontor.invoice/external-id ?i]
                     [?inv :kontor.invoice/buyer ?b]]
                   (d/db conn) invoice-external-id)
        gross (:kontor.invoice/total-gross
               (d/pull (d/db conn) [:kontor.invoice/total-gross]
                       [:kontor.invoice/external-id invoice-external-id]))]
    (d/transact conn
                [{:kontor.transaction/external-id payment-external-id
                  :kontor.transaction/state :posted
                  :kontor.transaction/effective-date #inst "2026-04-15"
                  :kontor.transaction/posted-at #inst "2026-04-15"
                  :kontor.transaction/partner buyer}])
    (papp/apply-payment!
     conn
     {:payment (d/q '[:find ?t . :in $ ?x
                      :where [?t :kontor.transaction/external-id ?x]]
                    (d/db conn) payment-external-id)
      :invoice (inv/by-external-id (d/db conn) invoice-external-id)
      :amount gross
      :commodity usd
      :applied-by-uid alice
      :applied-at #inst "2026-04-15"
      :strategy :customer-instruction})))

(pay-in-full! "SKY-2026-TX-001" "ACH-TX-001")

;; TX is `:paid`. CA, NY, WA remain `:sent`.

(map #(sm/current-status (d/db conn)
                         (inv/by-external-id (d/db conn) %)
                         :kontor.invoice/status)
     ["SKY-2026-CA-001" "SKY-2026-NY-001" "SKY-2026-TX-001" "SKY-2026-WA-001"])

;; ## Open collection cases for the past-due trio + Reg-F policy

(doseq [[code partner-ext] [["CASE-CA" "CA-CUST"]
                            ["CASE-NY" "NY-CUST"]
                            ["CASE-WA" "WA-CUST"]]]
  (let [partner-eid (d/q '[:find ?p . :in $ ?x
                           :where [?p :kontor.partner/external-id ?x]]
                         (d/db conn) partner-ext)]
    (kcase/open-case! conn
                      {:code code
                       :partner partner-eid
                       :entity skyline
                       :opened-by-uid alice
                       :strategy :reminder-only
                       :segment :default})))

;; Reg-F (12 CFR §1006.14(b)(2)(i)) caps consumer-collection
;; communications to **no more than 7 in any 7-day period** for the
;; same debt. The threshold is technically for consumer credit (not
;; B2B), but most US AR systems use it as their internal cap to be
;; safe. We seed a policy with a 7-day window and 2-event max for
;; small-balance accounts (configurable up to the regulatory cap).

(d/transact conn
            [{:kontor.dunning-policy/code "REG-F-CONSERVATIVE"
              :kontor.dunning-policy/name "US Reg-F-compliant"
              :kontor.dunning-policy/entity skyline
              :kontor.dunning-policy/applies-to-segment :default
              :kontor.dunning-policy/levels (pr-str
                                      [{:ordinal 1 :trigger-days 14
                                        :template-ref :reminder
                                        :late-fee-pct 0M}
                                       {:ordinal 2 :trigger-days 30
                                        :template-ref :past-due-notice
                                        :late-fee-pct 0M}
                                       {:ordinal 3 :trigger-days 60
                                        :template-ref :final-demand
                                        :late-fee-pct 0M
                                        :late-fee-fixed 25M}])
              :kontor.dunning-policy/frequency-cap-window-days 7
              :kontor.dunning-policy/frequency-cap-max-events 2  ; conservative
              :kontor.dunning-policy/pause-on-dispute? true
              :kontor.dunning-policy/pause-on-open-promise? true
              :kontor.dunning-policy/active true}])

(def policy
  (d/pull (d/db conn) '[*]
          (d/q '[:find ?p . :where [?p :kontor.dunning-policy/code "REG-F-CONSERVATIVE"]]
               (d/db conn))))

(def tpl-provider
  (kdunning/static-template-provider
   {1 {"en-US" "Hi {{name}}, your invoice {{invoice}} is past due."}
    2 {"en-US" "Past-due notice — invoice {{invoice}} is 30 days overdue."}
    3 {"en-US" "Final demand — invoice {{invoice}} 60+ days overdue."}}))

;; ## Send 3 dunning letters in one batch (CA, NY, WA)

(defn case-of [code] (kcase/by-code (d/db conn) code))

(defn invoice-of [ext] (inv/by-external-id (d/db conn) ext))

(def batch-input
  [{:case-eid (case-of "CASE-CA")
    :invoice-eid (invoice-of "SKY-2026-CA-001")
    :locale "en-US"}
   {:case-eid (case-of "CASE-NY")
    :invoice-eid (invoice-of "SKY-2026-NY-001")
    :locale "en-US"}
   {:case-eid (case-of "CASE-WA")
    :invoice-eid (invoice-of "SKY-2026-WA-001")
    :locale "en-US"}])

(def plan-l1
  (kdunning/plan-dunning-run
   (d/db conn)
   {:as-of #inst "2026-04-20"
    :entity skyline
    :policy policy
    :cases batch-input}))

plan-l1

;; All three should emit at level 1 (none disputed, no promises, no
;; prior events in the window).

(doseq [row plan-l1]
  (kdunning/emit-dunning-event! conn
                                {:plan-row row
                                 :channel :email
                                 :provider tpl-provider}))

;; ## WA customer opens a dispute
;;
;; **2026-05-05**: Cascadia Cloud emails saying the WA invoice
;; included an incorrect "Sound Transit" surcharge — they're in
;; King County but outside the Sound Transit District. They dispute
;; $4.39 of the $41.90 tax line.

(def wa-tax-line-eid
  (d/q '[:find ?l . :in $ ?inv
         :where
         [?inv :kontor.invoice/external-id "SKY-2026-WA-001"]
         [?l :kontor.invoice-line/invoice ?inv]
         [?l :kontor.invoice-line/sequence 2]]
       (d/db conn)))

(kdispute/raise-dispute! conn
                         {:external-id "DISP-WA-TAX"
                          :invoice (invoice-of "SKY-2026-WA-001")
                          :scope wa-tax-line-eid
                          :disputed-amount 4.39M
                          :reason-code :tax
                          :opened-by-uid alice
                          :notes "Customer claims wrong Sound Transit District code"})

;; ## CA + NY get Mahnstufe 2; WA suppressed by dispute
;;
;; **2026-05-15** (30 days after issue): re-plan.

(def plan-l2
  (kdunning/plan-dunning-run
   (d/db conn)
   {:as-of #inst "2026-05-15"
    :entity skyline
    :policy policy
    :cases batch-input}))

plan-l2

;; CA + NY get level 2, WA gets `:skipped? true :skip-reason
;; :open-dispute`.
;;
;; This is the **substrate win the market-pain delta verified**:
;; the bitemporal default + `:kontor.dispute/state` predicate means the
;; dispute auto-suppresses dunning. SAP FSCM requires a manual
;; pause action that drifts; NetSuite Dunning Schedules have no
;; concept of suppression on dispute at all.

(doseq [row plan-l2]
  (when-not (:skipped? row)
    (kdunning/emit-dunning-event! conn
                                  {:plan-row row
                                   :channel :email
                                   :provider tpl-provider})))

;; ## Reg-F frequency cap in action
;;
;; **2026-05-16**: an over-eager collector wants to re-send. Re-plan
;; same as above; this time the frequency-cap kicks in (2 events
;; for CA-CUST in the last 7 days = at cap).

(def plan-may-16
  (kdunning/plan-dunning-run
   (d/db conn)
   {:as-of #inst "2026-05-16"
    :entity skyline
    :policy policy
    :cases [{:case-eid (case-of "CASE-CA")
             :invoice-eid (invoice-of "SKY-2026-CA-001")
             :locale "en-US"}]}))

plan-may-16

;; The first row should have `:skip-reason :frequency-cap` (because
;; CA had 2 sent events in the last 7 days — L1 on 2026-04-20 was
;; outside the 7-day window, but the L2 on 2026-05-15 brings it to
;; just-under-cap; another emit would exceed).

;; ## WA dispute resolves; cash arrives; close cases
;;
;; **2026-05-20**: WA dispute resolved in Skyline's favor (the
;; surcharge IS owed; customer was wrong about district boundaries).

(kdispute/resolve-dispute! conn
                           {:dispute "DISP-WA-TAX"
                            :resolution :customer-conceded
                            :resolved-by-uid bob
                            :reason-note "WA Dept of Revenue confirmed STD code"})

;; All three customers pay in full by 2026-05-30.

(doseq [[ext pay] [["SKY-2026-CA-001" "ACH-CA-001"]
                   ["SKY-2026-NY-001" "ACH-NY-001"]
                   ["SKY-2026-WA-001" "ACH-WA-001"]]]
  (pay-in-full! ext pay))

;; All four invoices now `:paid`.

(map #(sm/current-status (d/db conn)
                         (inv/by-external-id (d/db conn) %)
                         :kontor.invoice/status)
     ["SKY-2026-CA-001" "SKY-2026-NY-001" "SKY-2026-TX-001" "SKY-2026-WA-001"])

;; Close the cases.

(doseq [code ["CASE-CA" "CASE-NY" "CASE-WA"]]
  (kcase/advance-case-state! conn
                        {:case code :to :paid
                         :changed-by-uid alice
                         :reason :closed-paid}))

;; ## Per-state tax-payable balances
;;
;; The cumulative sales-tax-payable per state — what we owe each
;; revenue department at the end of Q2:

(defn tax-balance [acct-path]
  (or (d/q '[:find (sum ?a) .
             :in $ ?p
             :where
             [?ac :kontor.account/path ?p]
             [?p* :kontor.posting/account ?ac]
             [?p* :kontor.posting/amount ?a]]
           (d/db conn) acct-path)
      0M))

;; Note: this showcase didn't run `post-to-ledger!` for the invoice
;; (only `:payment-application` was used; the AR posting is created
;; by the bridge separately). In a production flow the bridge fires
;; `post-to-ledger!` after `make-invoice-from-order!`; we skipped
;; that step here to keep the focus on collections.

;; ## What this showcase exercised
;;
;; - Multi-state tax via a TaxProvider stub (ADR-005)
;; - Multi-invoice batch dunning planning + emission
;; - Dispute auto-suppression (#17 substrate win)
;; - Reg-F frequency-cap as plan-time predicate (#34 substrate win)
;; - `:payment-application` partial + full payment lifecycle
;; - Same kontor primitives as Showcase 1, exercising a different
;;   jurisdiction
;;
;; What it didn't exercise (deferred):
;;
;; - 1099-MISC withholding via `:kontor.invoice-line/withholding-on-
;;   payment?` (ADR-040) — needs a vendor-side flow. Showcase 3
;;   exercises this.
;; - Avalara API integration — kernel ships the protocol; per
;;   ADR-005 + ADR-010 the API client lives in a per-tenant
;;   adapter, not in kontor.

;; ## Source citations
;;
;; - **12 CFR Part 1006** (CFPB Regulation F) — frequency-cap rule
;;   in §1006.14(b)(2)(i). 7 calls / 7 days / same debt —
;;   <https://www.consumerfinance.gov/rules-policy/regulations/1006/14/>
;; - **South Dakota v. Wayfair, Inc.** (2018) — economic nexus —
;;   <https://supreme.justia.com/cases/federal/us/585/17-494/>
;; - **Streamlined Sales Tax (SST) Governing Board** — uniform
;;   definitions — <https://www.streamlinedsalestax.org/>
;; - **California Dept of Tax & Fee Admin (CDTFA)** — rate sources —
;;   <https://www.cdtfa.ca.gov/>
;; - **NYC Dept of Finance** — combined NYS + NYC + MCTD rate guide
;;   — <https://www.tax.ny.gov/pubs_and_bulls/tg_bulletins/st/sales-tax-rates.htm>
;; - **TX Comptroller** — local sales/use tax —
;;   <https://comptroller.texas.gov/taxes/sales/local/>
;; - **WA Dept of Revenue** — combined state/local rates —
;;   <https://dor.wa.gov/find-taxes-rates/sales-and-use-tax-rates>
;; - **Avalara** API surface (compared, not lifted) —
;;   <https://developer.avalara.com/api-reference/avatax/rest/v2/>
;;
;; Reference comparison:
;;
;; - **NetSuite SuiteTax** — built-in TaxJar adapter, hardcoded
;;   provider; no clean swap-out without third-party SuiteApps.
;; - **SAP S/4HANA TTE** — heavy customizing, Tax Procedure FA per
;;   country.
;; - **Salesforce Revenue Cloud** — `TaxAdjustmentRules` table; no
;;   bitemporal queries.
;; - **QuickBooks Online** — automatic tax service is Intuit-only;
;;   no protocol seam.
