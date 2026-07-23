(ns kontor.regression.r3-fiscal-position-tax-test
  "Round-3 regression pins for the fiscal-position / compound + price-included
   tax / instalment-payment-term area.

   Substrate under test:
     - kontor.schema           :kontor.fiscal-position/* (marker attrs only)
     - kontor.tax.tax-rate-provider  StaticTableProvider (TaxRateProvider)
     - kontor.tax.tax-posting-builder StaticTablePostingBuilder
     - kontor.banking.payment-term   compute-due-date (single net-days)

   Odoo reference: /home/christian-weilbach/Development/odoo/addons/account
     - models/partner.py            AccountFiscalPosition.map_tax / map_account
     - models/account_tax.py        price_include / include_base_amount
     - models/account_payment_term.py  AccountPaymentTermLine multi-tranche

   Three gaps this file pins (all ^:kaocha/pending):
     (a) a fiscal position that remaps a domestic tax to a
         reverse-charge/zero-rate for an EU/export customer — no map entity
     (b) price-included tax gross→net extraction — no :price-include attr
     (c) an instalment payment term '30% now / 70% in 60d' aging + settling
         tranches independently — single-due-date only

   Everything that already works stays green; every asserted number is
   hand-derived and sourced."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.banking.payment-term :as pt]
            [kontor.tax.tax-rate-provider :as trp]))

;; ============================================================================
;; Shared fixture — a DE VAT chart plus a would-be EU / export customer.
;; ============================================================================

(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private at #inst "2026-03-15")

(defn- fresh-fp-db
  "A DE VAT chart:
     - DE-VAT-19-SALE  domestic 19% output VAT (percent, recoverable)
     - DE-RC-0-SALE    intra-EU reverse-charge / zero-rate substitute
   plus a :kontor.fiscal-position entity for an EU (intra-community)
   customer. NOTE the fiscal-position entity carries only marker attrs —
   name / country-code / auto-apply / vat-required — no tax/account map."
  []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:kontor.account/path "Income:Sales"            :kontor.account/type :income}
                 {:kontor.account/path "Liabilities:VAT-Payable" :kontor.account/type :liability}
                 {:kontor.account/path "Assets:AR"               :kontor.account/type :asset}
                 {:db/id "grp" :kontor.vat-group/name "DE VAT" :kontor.vat-group/country-code "DE"}
                 ;; domestic 19% sale VAT
                 {:db/id "t-dom" :kontor.tax/code "DE-VAT-19-SALE" :kontor.tax/name "DE VAT 19% (sale)"
                  :kontor.tax/country-code "DE" :kontor.tax/type-tax-use :sale
                  :kontor.tax/amount-type :percent :kontor.tax/amount 0.19M
                  :kontor.tax/recoverable? true :kontor.tax/active true :kontor.tax/tax-group "grp"}
                 {:kontor.tax-rep/tax "t-dom" :kontor.tax-rep/document-type :invoice
                  :kontor.tax-rep/repartition-type :tax :kontor.tax-rep/factor-percent 100M
                  :kontor.tax-rep/account [:kontor.account/path "Liabilities:VAT-Payable"]
                  :kontor.tax-rep/sequence 0}
                 ;; intra-EU reverse-charge / zero-rate SUBSTITUTE for the domestic tax
                 {:db/id "t-rc" :kontor.tax/code "DE-RC-0-SALE" :kontor.tax/name "Intra-EU reverse charge 0%"
                  :kontor.tax/country-code "DE" :kontor.tax/type-tax-use :sale
                  :kontor.tax/amount-type :percent :kontor.tax/amount 0.00M
                  :kontor.tax/recoverable? true :kontor.tax/active true
                  :kontor.tax/mechanism :reverse-charge :kontor.tax/tax-group "grp"}
                 ;; the fiscal position an EU B2B customer would carry
                 {:db/id "fp-eu" :kontor.fiscal-position/name "Intra-Community (EU B2B)"
                  :kontor.fiscal-position/country-code "FR"
                  :kontor.fiscal-position/auto-apply true
                  :kontor.fiscal-position/vat-required true}])
    conn))

;; ============================================================================
;; (a) Fiscal position remap — GAP: the marker exists, the map does not.
;; ============================================================================

(deftest fiscal-position-entity-stores-only-markers
  ;; GREEN — pin the CURRENT shape: name / country-code / auto-apply /
  ;; vat-required, and demonstrably NOTHING that maps a source tax to a
  ;; destination tax.
  (let [conn (fresh-fp-db)
        fp   (d/pull (d/db conn) '[*]
                     (d/q '[:find ?e . :where
                            [?e :kontor.fiscal-position/name "Intra-Community (EU B2B)"]]
                          (d/db conn)))]
    (is (= "Intra-Community (EU B2B)" (:kontor.fiscal-position/name fp)))
    (is (= "FR" (:kontor.fiscal-position/country-code fp)))
    (is (true? (:kontor.fiscal-position/auto-apply fp)))
    (is (true? (:kontor.fiscal-position/vat-required fp)))
    (testing "no schema attr on the fiscal position references a tax or account map"
      (is (empty? (->> (keys fp)
                       (filter #(= "kontor.fiscal-position" (namespace %)))
                       (filter #(re-find #"tax|account|map|mapping" (name %)))))
          "Odoo's account.fiscal.position has tax_ids (AccountFiscalPositionTax:
           tax_src_id→tax_dest_id, partner.py:303) + account_ids
           (AccountFiscalPositionAccount: account_src_id→account_dest_id) —
           kontor's fiscal-position carries none of these."))))

;; PENDING(NEW): kontor has no fiscal-position tax-remap entity, so an EU
;; intra-community / export sale cannot substitute the domestic 19% tax with
;; the reverse-charge/zero-rate one. Odoo does this in
;; addons/account/models/partner.py:154 `map_tax` — it walks `self.tax_map`
;; (built at partner.py:99-105 from AccountFiscalPositionTax
;; tax_src_id→tax_dest_id lines) and replaces each source tax with its
;; destination tax(es). kontor's StaticTableProvider resolves taxes purely by
;; (country-code, tax-use) with NO fiscal-position input to `rate-facts`
;; (tax_rate_provider.clj:236 context keys), so the domestic tax always wins.
(deftest ^:kaocha/pending fiscal-position-remaps-domestic-tax-to-reverse-charge
  (let [conn (fresh-fp-db)
        prov (trp/make-static-table-provider conn)
        fp   (d/q '[:find ?e . :where
                    [?e :kontor.fiscal-position/name "Intra-Community (EU B2B)"]]
                  (d/db conn))
        ;; What a fiscal-position-aware provider WOULD accept: the fp eid,
        ;; steering rate resolution to the remapped tax. `rate-facts` has no
        ;; such parameter — the fp is simply ignored, so the DOMESTIC 19%
        ;; comes back unchanged.
        facts (trp/rate-facts prov {:base 1000 :commodity eur
                                    :country-code "DE" :tax-use :sale :at at
                                    :fiscal-position fp})
        c (first (:components facts))]
    ;; The domestic tax leaks through — this is the bug we are pinning.
    ;; When the substrate grows a fiscal-position remap, this should become
    ;; the reverse-charge / zero-rate substitute (0 tax, :reverse-charge).
    (is (= "DE-RC-0-SALE" (:tax-code c))
        "EU B2B customer's fiscal position should substitute the domestic
         19% VAT with the intra-community reverse-charge tax")
    (is (= :reverse-charge (:kind c)))
    (is (== 0M (:amount c)) "intra-community supply carries no output VAT")))

;; ============================================================================
;; (b) Price-included tax gross→net extraction — GAP: no :price-include attr.
;; ============================================================================

(deftest tax-schema-has-no-price-include-attr
  ;; GREEN — pin the CURRENT schema: :kontor.tax/include-base-amount exists
  ;; (compound support) but there is NO :kontor.tax/price-include, so a
  ;; provider cannot know a line price is tax-inclusive.
  (let [conn (fresh-fp-db)
        db   (d/db conn)
        ident? (fn [kw] (seq (d/q '[:find ?e :in $ ?k :where [?e :db/ident ?k]] db kw)))]
    (is (ident? :kontor.tax/include-base-amount)
        "compound-tax marker IS in the schema (schema.cljc:1955)")
    (is (not (ident? :kontor.tax/price-include))
        "but the B2C tax-inclusive-price marker is ABSENT — Odoo has it at
         account_tax.py:137 (price_include)")))

;; PENDING(NEW): the StaticTableProvider always treats :base as the pre-tax
;; NET and computes amount = base × rate (tax_rate_provider.clj:227-231
;; `component-amount`). For a B2C gross price of 119.00 @ 19% VAT the net is
;; 100.00 and the tax 19.00 — the provider instead computes 119.00 × 19% =
;; 22.61 (treating the gross AS the net), which is wrong. Odoo extracts the
;; net first when tax.price_include is set: net = gross / (1 + rate); see
;; account_tax.py:137 `price_include` + the compute in _compute_taxes_for_single_line.
(deftest ^:kaocha/pending price-included-tax-extracts-net-from-gross
  (let [conn  (fresh-fp-db)
        prov  (trp/make-static-table-provider conn)
        ;; A tax-inclusive B2C price of 119.00 @ 19% must decompose to
        ;; net 100.00 + VAT 19.00 (119 / 1.19 = 100; 119 − 100 = 19).
        ;; There is no way to tell `rate-facts` the base is gross, so it
        ;; treats 119 as the net and returns 119 × 0.19 = 22.61.
        facts (trp/rate-facts prov {:base 119.00M :commodity eur
                                    :country-code "DE" :tax-use :sale :at at
                                    :price-include true})
        c (first (:components facts))]
    (is (== 100.00M (:base c))
        "price-included: the taxable base is the extracted NET, not the gross")
    (is (== 19.00M (:amount c))
        "119.00 gross @ 19% → 19.00 VAT (119 / 1.19 = 100 net)")))

;; PENDING(NEW): compound tax — :kontor.tax/include-base-amount is in the
;; schema (schema.cljc:1955) but the StaticTableProvider does NOT honour it.
;; `component-amount` (tax_rate_provider.clj:221-231) computes every tax as
;; base × rate INDEPENDENTLY; a tax flagged include-base-amount should add its
;; amount to the base seen by later (higher-sequence) taxes. Odoo does this at
;; account_tax.py:148 (include_base_amount) — the running base accumulates.
;; Modelled here: a 10% excise that affects the base of a 19% VAT on a 100 net.
;;   excise = 100 × 10%          = 10.00
;;   VAT    = (100 + 10) × 19%   = 20.90   (NOT 100 × 19% = 19.00)
(deftest ^:kaocha/pending compound-tax-stacks-included-base
  (let [conn (fresh-fp-db)]
    ;; add an excise tax (include-base-amount true) + reuse DE-VAT-19-SALE,
    ;; both country "CX" so a single rate-facts query returns both.
    (d/transact conn
                [{:kontor.account/path "Liabilities:Excise-Payable" :kontor.account/type :liability}
                 {:db/id "t-excise" :kontor.tax/code "CX-EXCISE-10" :kontor.tax/name "Excise 10%"
                  :kontor.tax/country-code "CX" :kontor.tax/type-tax-use :sale
                  :kontor.tax/amount-type :percent :kontor.tax/amount 0.10M
                  :kontor.tax/recoverable? false :kontor.tax/active true
                  :kontor.tax/include-base-amount true}
                 {:db/id "t-vat" :kontor.tax/code "CX-VAT-19" :kontor.tax/name "VAT 19%"
                  :kontor.tax/country-code "CX" :kontor.tax/type-tax-use :sale
                  :kontor.tax/amount-type :percent :kontor.tax/amount 0.19M
                  :kontor.tax/recoverable? true :kontor.tax/active true}])
    (let [prov  (trp/make-static-table-provider conn)
          facts (trp/rate-facts prov {:base 100 :commodity eur
                                      :country-code "CX" :tax-use :sale :at at})
          by-code (into {} (map (juxt :tax-code identity) (:components facts)))]
      (is (== 10.00M (get-in by-code ["CX-EXCISE-10" :amount]))
          "excise = 100 × 10%")
      (is (== 20.90M (get-in by-code ["CX-VAT-19" :amount]))
          "compound: VAT base is 100 + 10 excise = 110 → 110 × 19% = 20.90,
           but the provider computes 100 × 19% = 19.00 (include-base-amount ignored)"))))

;; ============================================================================
;; (c) Instalment payment terms — GAP: single net-days only.
;; ============================================================================

(deftest payment-term-computes-a-single-due-date
  ;; GREEN — pin the CURRENT behaviour: NET60 yields ONE due date, invoice
  ;; date + 60 calendar days. There is no tranche concept.
  (let [conn (core/create-test-db)
        _    (pt/install-standard-terms! conn)
        term (pt/by-code (d/db conn) "NET60")
        inv  #inst "2026-01-01T00:00:00.000-00:00"
        due  (pt/compute-due-date inv term)]
    ;; 2026-01-01 + 60 days = 2026-03-02 (Jan 31 + Feb 28 = 59, +1 = Mar 2)
    (is (= #inst "2026-03-02T00:00:00.000-00:00" due)
        "NET60 → invoice date + 60 calendar days, one date")
    (testing "the term entity has net-days scalar only — no line/tranche attrs"
      (is (= 60 (:kontor.payment-term/net-days term)))
      (is (empty? (->> (keys term)
                       (filter #(and (namespace %)
                                     (re-find #"payment-term" (namespace %))))
                       (filter #(re-find #"line|tranche|percent|value" (name %)))))
          "no AccountPaymentTermLine analogue exists"))))

;; PENDING(NEW): kontor payment-terms are a single scalar :net-days
;; (payment_term.clj:35 `compute-due-date` → one date; schema.cljc:2644
;; payment-term-attrs = code/name/net-days/discount-pct/discount-days/active).
;; A '30% now / 70% in 60 days' instalment plan cannot be expressed — there
;; is no AccountPaymentTermLine analogue. Odoo models each tranche as an
;; account.payment.term.line (account_payment_term.py:281) with value_amount
;; (:291, percent or fixed) + nb_days (:307), and `_compute_terms`
;; (:171) explodes an invoice into one receivable per tranche, each with its
;; own date_maturity. That is what lets tranches age + settle independently.
(deftest ^:kaocha/pending instalment-term-explodes-into-tranches
  (let [conn (core/create-test-db)]
    ;; What a tranche-aware substrate WOULD store: a payment term with two
    ;; lines. These attrs do not exist, so the transaction is a no-op on the
    ;; line data — we then assert the (absent) explosion behaviour.
    (d/transact conn
                [{:db/id "term-30-70"
                  :kontor.payment-term/code "30/70-NET60"
                  :kontor.payment-term/name "30% sofort, 70% in 60 Tagen"
                  :kontor.payment-term/net-days 60
                  :kontor.payment-term/active true}])
    (let [term (pt/by-code (d/db conn) "30/70-NET60")
          inv  #inst "2026-01-01T00:00:00.000-00:00"
          ;; A tranche-aware compute would return a VECTOR of
          ;; {:amount-fraction :due-date} maps for a 1000.00 EUR invoice:
          ;;   tranche 1: 30% = 300.00 due 2026-01-01 (0 days)
          ;;   tranche 2: 70% = 700.00 due 2026-03-02 (60 days)
          ;; `compute-tranches` does not exist — resolved at runtime so the
          ;; ns still compiles; the (skipped) pending body would NPE on it.
          compute-tranches (resolve 'kontor.banking.payment-term/compute-tranches)
          tranches (compute-tranches term inv 1000.00M)]
      (is (= 2 (count tranches)) "30/70 term explodes into two tranches")
      (is (== 300.00M (:amount (first tranches))))
      (is (= #inst "2026-01-01T00:00:00.000-00:00" (:due-date (first tranches))))
      (is (== 700.00M (:amount (second tranches))))
      (is (= #inst "2026-03-02T00:00:00.000-00:00" (:due-date (second tranches)))
          "second tranche due invoice date + 60 days — ages independently"))))
