^{:kindly/hide-code true
  :clay {:title "Showcase 1: DE GmbH B2B with Factur-X + Mahnverfahren"
         :format [:quarto :html]}}
(ns showcases.01-de-b2b-factur-x
  "Multi-national use case 1: a fictional German GmbH selling B2B
   software-consulting services to other German customers. End-to-end
   exercise of kontor's DE-localized stack:

     - SKR04 chart of accounts (kontor-l10n-de)
     - Factur-X / ZUGFeRD invoice generation (kontor-einvoice-de)
     - Posting bridge with DE VAT (UStG §3-5)
     - Partial payment via :payment-application kernel primitive
     - German Mahnverfahren (3-level dunning) per BGB §286 / EU Late
       Payment Directive 2011/7/EU
     - Bitemporal dispute lifecycle

   Synthetic data; all company/address/tax-ID information is
   fictional. The accounting flow is grounded in:

     - German Commercial Code (HGB) §§238-263 (accounting basics)
     - German Civil Code (BGB) §286 (commercial default / Verzug)
     - EU Late Payment Directive 2011/7/EU (ECB ref +8% interest)
     - DATEV SKR04 chart of accounts (publicly documented)
     - Factur-X 1.0.07 specification (FNFE-MPE + AFNOR)
     - Mustang Project APIs (org.mustangproject/library, APL-2)

   Reference comparison: this flow in Odoo's `l10n_de` + `account_*`
   modules takes ~20 LOC of Python configuration + the running
   accounting workflow; in SAP S/4HANA it's a Customizing-heavy
   configuration via FI/CO; in DATEV-classic the bookkeeper enters
   each step manually. kontor's value: the schema enforces the
   invariants (sum-to-zero, bitemporal aging) and the helpers
   compose the steps as data, not workflow code."
  (:require [datahike.api :as d]
            [kontor.collections.aging :as kaging]
            [kontor.collections.case :as kcase]
            [kontor.collections.dispute :as kdispute]
            [kontor.collections.dunning :as kdunning]
            [kontor.collections.schema :as coll-schema]
            [kontor.core :as core]
            [kontor.invoice.bridge :as inv]
            [kontor.invoice.schema :as inv-schema]
            [kontor.partner.schema :as partner-schema]
            [kontor.payment-application :as papp]
            [kontor.payment-term :as pt]
            [kontor.sales.schema :as sales-schema]
            [kontor.status-machine :as sm]))

;; # The story
;;
;; **Schnitzel & Code GmbH** (fictional, München, *DE325000000*) is a
;; 12-person software-consulting boutique invoicing other German
;; B2B customers. Standard payment terms: net 30 days. Late payment
;; interest: ECB main refinancing rate + 8% per EU Late Payment
;; Directive 2011/7/EU (transposed into BGB §288).
;;
;; In 2026-Q2 we ship invoice **R-2026-0042** to *Goldener Brezel
;; GmbH* (a Bayreuth restaurant chain consultancy, *DE229000000*) for
;; 16'750.00 EUR net + 19% USt (= 19'932.50 EUR gross). The customer
;; pays 8'000 EUR on time, then disputes 4'750 EUR (claiming
;; deliverable B wasn't accepted), then partially pays another 5'000
;; after the dispute is resolved. Final balance: 2'182.50 EUR drops
;; into the 60-day bucket, triggers the second Mahnung (level 2),
;; and Goldener Brezel finally settles.

;; ## Bootstrap
;;
;; Spin up an in-memory datahike kernel + companions. In a real
;; tenant this is a once-per-database call; here we run it for each
;; notebook render.

(def conn (core/create-test-db))
(partner-schema/install! conn)
(sales-schema/install! conn)
(inv-schema/install! conn)
(coll-schema/install! conn)
(pt/install-standard-terms! conn)

;; ## Seeds: chart, journal, partners
;;
;; SKR04 is the German non-balance-sheet-grouped chart. Skipping
;; `kontor.l10n-de.chart` to avoid coupling this notebook to
;; specific account codes — the relevant five accounts are seeded
;; directly:

(d/transact conn
            [{:commodity/symbol "EUR" :commodity/name "Euro"
              :commodity/precision 2 :commodity/iso-4217 "EUR"}
             {:entity/code "SCHCODE-DE" :entity/name "Schnitzel & Code GmbH"
              :entity/kind :operating :entity/active true}
             ;; SKR04 codes
             {:account/code "1200" :account/path "1200"
              :account/name "Forderungen aL"     ; AR
              :account/type :asset}
             {:account/code "1800" :account/path "1800"
              :account/name "Bank"
              :account/type :asset}
             {:account/code "4400" :account/path "4400"
              :account/name "Erlöse 19% USt"
              :account/type :revenue}
             {:account/code "3806" :account/path "3806"
              :account/name "USt 19% an FA"
              :account/type :liability}
             {:account/code "6920" :account/path "6920"
              :account/name "Forderungsverluste"   ; Bad debt
              :account/type :expense}
             ;; GL defaults
             {:gl-account-default/account-type :ar
              :gl-account-default/account [:account/path "1200"]}
             {:gl-account-default/account-type :sales-revenue
              :gl-account-default/account [:account/path "4400"]}
             {:gl-account-default/account-type :sales-tax-payable
              :gl-account-default/account [:account/path "3806"]}
             {:gl-account-default/account-type :bad-debt-expense
              :gl-account-default/account [:account/path "6920"]}
             ;; Journal
             {:journal/code "AR" :journal/name "Forderungen"
              :journal/type :sales}
             ;; Partners
             {:partner/external-id "BREZEL"
              :partner/name "Goldener Brezel GmbH"
              :partner/kind :customer
              :partner/country-code "DE"
              :partner/tax-id "DE229000000"
              :partner/credit-status :open
              :partner/credit-limit 50000M
              :partner/credit-commodity [:commodity/symbol "EUR"]}
             {:partner/external-id "U-alice" :partner/name "Alice (collector)"}
             {:partner/external-id "U-bob"   :partner/name "Bob (manager)"}])

(def schcode-eid    (d/q '[:find ?e . :where [?e :entity/code "SCHCODE-DE"]] (d/db conn)))
(def brezel-eid     (d/q '[:find ?p . :where [?p :partner/external-id "BREZEL"]] (d/db conn)))
(def alice          (d/q '[:find ?p . :where [?p :partner/external-id "U-alice"]] (d/db conn)))
(def bob            (d/q '[:find ?p . :where [?p :partner/external-id "U-bob"]] (d/db conn)))
(def eur            (d/q '[:find ?c . :where [?c :commodity/symbol "EUR"]] (d/db conn)))

;; ## Issue the invoice
;;
;; In our story it's **2026-04-01**: we deliver the work, issue the
;; invoice, and post it to the GL. The line totals to 16'750 net
;; + 3'182.50 VAT = 19'932.50 gross. Standard DE B2B: full VAT
;; on the invoice, no reverse-charge (both seller and buyer are DE).
;;
;; *Why this step matters for the kontor showcase*: the posting
;; bridge resolves GL accounts via three-tier `:gl-account-default`
;; (override → entity-default → tenant-default) and produces a
;; sum-to-zero `:transaction` with `:posting`s per line. The
;; invariant is enforced at transact time by `kontor.posting/build-
;; transaction` (ADR-021).

(def invoice-tempid "inv-R-2026-0042")

;; Build the invoice + lines as one tx. In a typical kontor consumer
;; this is the `inv/make-invoice-from-order!` path; here we
;; construct directly to keep the notebook short.

(def invoice-result
  (d/transact
   conn
   [{:db/id invoice-tempid
     :invoice/external-id "R-2026-0042"
     :invoice/type :sales
     :invoice/status :sent
     :invoice/issue-date #inst "2026-04-01"
     :invoice/seller schcode-eid
     :invoice/buyer brezel-eid
     :invoice/entity schcode-eid
     :invoice/currency "EUR"
     :invoice/total-net 16750M
     :invoice/total-vat 3182.50M
     :invoice/total-gross 19932.50M
     :invoice/lines ["line-1" "line-2"]}
    {:db/id "line-1"
     :invoice-line/invoice invoice-tempid
     :invoice-line/sequence 1
     :invoice-line/name "Beratung Strategie Q1"
     :invoice-line/quantity 40M
     :invoice-line/unit-price 300M
     :invoice-line/amount 12000M
     :invoice-line/gl-account-type :sales-revenue
     :invoice-line/vat-rate 19.0M
     :invoice-line/vat-category "S"}
    {:db/id "line-2"
     :invoice-line/invoice invoice-tempid
     :invoice-line/sequence 2
     :invoice-line/name "Deliverable B — Codereview"
     :invoice-line/quantity 1M
     :invoice-line/unit-price 4750M
     :invoice-line/amount 4750M
     :invoice-line/gl-account-type :sales-revenue
     :invoice-line/vat-rate 19.0M
     :invoice-line/vat-category "S"}]))

(def invoice-eid (inv/by-external-id (d/db conn) "R-2026-0042"))

;; The kernel state-machine seed for `nil → :sent` is one of the
;; canonical paths (ADR-036). Verify:

(sm/current-status (d/db conn) invoice-eid :invoice/status)

;; ## Partial payment 1: 8'000 EUR on time
;;
;; **2026-04-25**: customer pays 8'000 EUR via SEPA. Bank-ingest
;; would write the cash-receipt `:transaction`; we simulate it.
;;
;; The collection-shaped fact is the `:payment-application` linking
;; the cash receipt to the invoice. Status flips :sent →
;; :partially-paid automatically.

(d/transact conn [{:transaction/external-id "SEPA-2026-04-25-001"
                   :transaction/state :posted
                   :transaction/effective-date #inst "2026-04-25"
                   :transaction/posted-at #inst "2026-04-25"
                   :transaction/partner brezel-eid}])

(def payment-1-eid
  (d/q '[:find ?t . :where [?t :transaction/external-id "SEPA-2026-04-25-001"]]
       (d/db conn)))

(papp/apply-payment! conn
                     {:payment payment-1-eid
                      :invoice invoice-eid
                      :amount 8000M
                      :commodity eur
                      :applied-by-uid alice
                      :applied-at #inst "2026-04-25"
                      :strategy :customer-instruction
                      :reason :remittance-received})

;; Status now `:partially-paid`; open = 11932.50

(sm/current-status (d/db conn) invoice-eid :invoice/status)
(papp/open-amount-of-invoice (d/db conn) invoice-eid)

;; ## Dispute opened on Deliverable B
;;
;; **2026-05-02**: Goldener Brezel emails to dispute Deliverable B
;; (4'750 EUR line item). Per BGB §286 II Nr. 1 the invoice's debtor
;; can suspend payment for legitimate disputes; collections must
;; suppress dunning until the dispute is resolved.
;;
;; In OFBiz there is no `Dispute` entity at all (see research note
;; 15 §1). In SAP FSCM the dispute case data is overwritten on
;; state change — no bitemporal record of what the dispute looked
;; like on 2026-05-02. In kontor it's a normal entity, queryable at
;; any `:as-of-valid`.

(def line-2-eid
  (d/q '[:find ?l . :in $ ?inv :where [?l :invoice-line/invoice ?inv]
                                       [?l :invoice-line/sequence 2]]
       (d/db conn) invoice-eid))

(kdispute/raise-dispute! conn
                         {:external-id "DISP-R0042-L2"
                          :invoice invoice-eid
                          :scope line-2-eid
                          :disputed-amount 4750M
                          :reason-code :short-ship
                          :opened-by-uid alice
                          :notes "Customer says Codereview deliverable not received."})

;; ## Open collection case + first Mahnung (Mahnstufe 1)
;;
;; **2026-05-15** (30 days after net-30 due-date): the invoice is
;; past due. Even with a partial payment + open dispute, the
;; *non-disputed* portion is past due — collections opens a case.

(kcase/open-case! conn
                  {:code "CASE-BREZEL-Q2"
                   :partner brezel-eid
                   :entity schcode-eid
                   :opened-by-uid alice
                   :strategy :reminder-only
                   :segment :default})

;; Seed a German dunning policy: 3 levels (Mahnstufen) at 14 / 30 /
;; 60 days past due, with 8% late-payment interest at L2+ per EU
;; Directive 2011/7/EU.

(d/transact conn
            [{:dunning-policy/code "DE-MAHNUNG"
              :dunning-policy/name "Standard DE Mahnverfahren"
              :dunning-policy/entity schcode-eid
              :dunning-policy/applies-to-segment :default
              :dunning-policy/levels (pr-str
                                      [{:ordinal 1 :trigger-days 14
                                        :template-ref :erinnerung
                                        :late-fee-pct 0M}
                                       {:ordinal 2 :trigger-days 30
                                        :template-ref :mahnung-2
                                        :late-fee-pct 0.08M}
                                       {:ordinal 3 :trigger-days 60
                                        :template-ref :letzte-mahnung
                                        :late-fee-pct 0.08M
                                        :late-fee-fixed 40M}])
              :dunning-policy/frequency-cap-window-days 7
              :dunning-policy/frequency-cap-max-events 1
              :dunning-policy/pause-on-dispute? true
              :dunning-policy/pause-on-open-promise? true
              :dunning-policy/active true}])

(def policy
  (d/pull (d/db conn) '[*] (d/q '[:find ?p . :where [?p :dunning-policy/code "DE-MAHNUNG"]] (d/db conn))))

(def case-eid (kcase/by-code (d/db conn) "CASE-BREZEL-Q2"))

;; Plan a dunning run as of 2026-05-15. **The dispute is open on the
;; whole invoice (scope = line) — the policy gate will suppress.**

(kdunning/plan-dunning-run
 (d/db conn)
 {:as-of #inst "2026-05-15"
  :entity schcode-eid
  :policy policy
  :cases [{:case-eid case-eid :invoice-eid invoice-eid :locale "de-DE"}]})

;; The plan emits `{:skipped? true :skip-reason :open-dispute}` —
;; auto-suppression. No Mahnung is sent. Verified: kontor's
;; bitemporal-default + `:dispute/state` predicate is structurally
;; what SAP/NetSuite require manual workarounds for.

;; ## Resolve the dispute (Deliverable B: customer concedes)
;;
;; **2026-05-22**: After a Slack thread + a re-sent deliverable PDF,
;; Goldener Brezel concedes the dispute. We resolve in kontor:

(kdispute/resolve-dispute! conn
                           {:dispute "DISP-R0042-L2"
                            :resolution :customer-conceded
                            :resolved-by-uid bob
                            :reason-note "Customer acknowledged delivery via email 2026-05-22"})

;; Now plan dunning again, as of 2026-05-25. Dispute is closed →
;; gate clears; case picks Mahnstufe 1.

(def plan-may-25
  (kdunning/plan-dunning-run
   (d/db conn)
   {:as-of #inst "2026-05-25"
    :entity schcode-eid
    :policy policy
    :cases [{:case-eid case-eid :invoice-eid invoice-eid :locale "de-DE"}]}))

plan-may-25

;; Emit the Mahnstufe-1 reminder.

(kdunning/emit-dunning-event! conn
                              {:plan-row (first plan-may-25)
                               :channel :email
                               :provider (kdunning/static-template-provider
                                          {1 {"de-DE" "Sehr geehrte Damen und Herren, …"}})})

;; The kernel atomically writes:
;; 1. A `:dunning-event` row with `:level 1`
;; 2. An `:audit-doc` of `:type :dunning-letter` with content-hash
;; 3. A `:side-effect-intent` of `:type :send-email` in `:pending`
;;    status — the consumer's mailer worker drains the queue.

;; ## Customer pays 5'000 EUR; Mahnstufe 2 timing
;;
;; **2026-06-02**: customer pays another 5'000 EUR. open balance
;; drops to 2'932.50.
;;
;; By 2026-06-15 (60 days past due) Mahnstufe 2 triggers per the
;; policy. Late-payment interest at 8% p.a. = 2932.50 × 0.08 ×
;; (60/365) ≈ 38.59 EUR. In a real production flow the consumer's
;; mailer renders the Factur-X PDF with both the open principal and
;; the accrued interest. Here we just record the event.

(d/transact conn [{:transaction/external-id "SEPA-2026-06-02-001"
                   :transaction/state :posted
                   :transaction/effective-date #inst "2026-06-02"
                   :transaction/posted-at #inst "2026-06-02"
                   :transaction/partner brezel-eid}])

(papp/apply-payment! conn
                     {:payment (d/q '[:find ?t . :where [?t :transaction/external-id "SEPA-2026-06-02-001"]]
                                    (d/db conn))
                      :invoice invoice-eid
                      :amount 5000M
                      :commodity eur
                      :applied-by-uid alice
                      :applied-at #inst "2026-06-02"
                      :strategy :customer-instruction})

(papp/open-amount-of-invoice (d/db conn) invoice-eid)

;; ## Mahnstufe 2 — manager approval + audit-doc

(kcase/advance-state! conn
                      {:case "CASE-BREZEL-Q2"
                       :to :dunning-l1
                       :changed-by-uid alice
                       :reason :dunning-l1-sent})

(kcase/advance-state! conn
                      {:case "CASE-BREZEL-Q2"
                       :to :dunning-l2
                       :changed-by-uid alice
                       :reason :dunning-l2-sent})

;; ## Final settlement
;;
;; **2026-06-22**: customer pays the remaining 2'932.50 EUR + 38.59
;; interest. For the showcase we only record the principal — the
;; interest fee is a separate `:invoice-line` in a real flow.

(d/transact conn [{:transaction/external-id "SEPA-2026-06-22-001"
                   :transaction/state :posted
                   :transaction/effective-date #inst "2026-06-22"
                   :transaction/posted-at #inst "2026-06-22"
                   :transaction/partner brezel-eid}])

(papp/apply-payment! conn
                     {:payment (d/q '[:find ?t . :where [?t :transaction/external-id "SEPA-2026-06-22-001"]]
                                    (d/db conn))
                      :invoice invoice-eid
                      :amount 6932.50M
                      :commodity eur
                      :applied-by-uid alice
                      :applied-at #inst "2026-06-22"
                      :strategy :customer-instruction
                      :reason :remittance-received})

(sm/current-status (d/db conn) invoice-eid :invoice/status)

;; The invoice is now `:paid`. The collection-case state advances
;; one final time:

(kcase/advance-state! conn
                      {:case "CASE-BREZEL-Q2"
                       :to :paid
                       :changed-by-uid alice
                       :reason :closed-paid})

(sm/current-status (d/db conn) case-eid :collection-case/state)

;; ## Bitemporal replay: aging snapshot at 2026-05-20 vs 2026-06-10
;;
;; A useful demonstration of kontor's bitemporal default: the
;; collections-aware aging at a past `:as-of-valid` reads only the
;; payment-applications that had `:applied-at ≤ as-of`. So we can
;; reconstruct what aging looked like before the second payment:

(kaging/aging-rows
 (d/db conn)
 {:entity-eid schcode-eid
  :method :due-date
  :as-of #inst "2026-05-20"
  :as-of-valid #inst "2026-05-20"})

;; vs at 2026-06-10 (after the 5'000 partial):

(kaging/aging-rows
 (d/db conn)
 {:entity-eid schcode-eid
  :method :due-date
  :as-of #inst "2026-06-10"
  :as-of-valid #inst "2026-06-10"})

;; **This is the kontor-over-SAP-FSCM structural win**: the May 20
;; report and June 10 report are both queryable today. SAP FSCM
;; (and most ERPs) don't persist aging snapshots; auditors who want
;; "what did your AR look like on May 20" cannot get it without
;; backups.

;; ## What this showcase exercised
;;
;; - kontor kernel: `:payment-application` partial payment, status-
;;   machine, bitemporal `:as-of-valid` queries
;; - `kontor-invoice`: `:invoice/status` lifecycle through partial-
;;   paid + paid
;; - `kontor-collections`: `:collection-case`, `:dispute` (line-
;;   level), dunning-policy + plan + emission, suppression gates
;; - Audit story: 3 :status-history rows on the case, dispute opened
;;   + resolved with `:reason-note`, audit-doc + side-effect-intent
;;   per dunning emission
;;
;; What it didn't exercise (deferred to other showcases):
;;
;; - Factur-X PDF rendering via Mustang — we noted the integration
;;   shape but didn't render PDFs in this notebook to keep deps
;;   slim. The bridge to `kontor-einvoice-de` is documented in
;;   `modules/einvoice-de/README.md`.
;; - SKR04 full chart install — we used 5 manual accounts; the
;;   `kontor-l10n-de/chart` namespace installs the full SKR04.
;; - Multi-entity intercompany — see showcase 3.

;; ## Source citations
;;
;; - **EU Late Payment Directive 2011/7/EU** —
;;   <https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32011L0007>
;; - **BGB §286 (Verzug)** — German Civil Code, default rules —
;;   <https://www.gesetze-im-internet.de/bgb/__286.html>
;; - **BGB §288 (Verzugszinsen)** — ECB ref rate + 8% for B2B —
;;   <https://www.gesetze-im-internet.de/bgb/__288.html>
;; - **HGB §§238-263** — German Commercial Code accounting basics —
;;   <https://www.gesetze-im-internet.de/hgb/>
;; - **DATEV SKR04** — chart of accounts —
;;   <https://www.datev.de/web/de/aktuelles/news-themen/themen/kontenrahmen-skr-03-skr-04/>
;; - **Factur-X 1.0.07** spec — <https://fnfe-mpe.org/factur-x/>
;; - **Mustang Project** (APL-2) —
;;   <https://www.mustangproject.org/>
;;
;; Reference comparison (design only; no code lifted):
;;
;; - **Odoo** `l10n_de` + `account_*` modules (LGPLv3) — Mahnverfahren
;;   via `account.followup.report` + manual workflow rules.
;; - **Tryton** `account_de_skr04` + `account_dunning` (GPLv3) —
;;   data-driven dunning rules via `account.dunning.procedure`.
;; - **SAP S/4HANA** `FI-CA-DM` (Dispute Management) +
;;   `FI-AR-CR` (Credit Management) — heavy customizing; bitemporal
;;   queries unsupported.
