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

   Three gaps this file pinned, ALL CLOSED in note 198 Tier 3:
     (a) a fiscal position that remaps a domestic tax to a
         reverse-charge/zero-rate for an EU/export customer — was a marker
         entity with no map; now `:kontor.fiscal-position-tax/*` +
         `kontor.tax.fiscal-position/map-tax`, applied by the rate provider.
     (b) price-included tax gross→net extraction — now
         `:kontor.tax/price-include` + a per-line context override.
     (c) an instalment payment term '30% now / 70% in 60d' aging + settling
         tranches independently — now `:kontor.payment-term-line/*` +
         `compute-tranches`.
   A fourth defect surfaced while closing (a)-(c): compound taxes were
   resolved from an UNORDERED `d/q` set, so `:include-base-amount` — which is
   only meaningful relative to an order — stacked whichever tax the set
   happened to yield first. Closed by `:kontor.tax/sequence`.

   Every asserted number is hand-derived and sourced."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.banking.payment-term :as pt]
            [kontor.tax.fiscal-position :as fp]
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
   customer. The position entity itself still carries only marker attrs;
   the remap lives in a separate `:kontor.fiscal-position-tax` line that
   points BACK at it (Odoo's account.fiscal.position.tax shape), added by
   `fresh-fp-db+map` below."
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

(defn- tax-eid [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.tax/code ?c]] db code))

(defn- fresh-fp-db+map
  "`fresh-fp-db` plus the mapping line that makes the position DO something:
   under 'Intra-Community (EU B2B)', the domestic 19% VAT is replaced by the
   reverse-charge tax."
  []
  (let [conn (fresh-fp-db)
        db   (d/db conn)]
    (fp/map-tax! conn "Intra-Community (EU B2B)"
                 (tax-eid db "DE-VAT-19-SALE")
                 (tax-eid db "DE-RC-0-SALE"))
    conn))

(defn- component-by-code
  "The TaxFacts component for `code`. The DE fixture carries TWO active sale
   taxes, so indexing by position would be reading whatever the resolver
   ordered first rather than the tax under test."
  [facts code]
  (first (filter #(= code (:tax-code %)) (:components facts))))

;; ============================================================================
;; (a) Fiscal position remap — GAP: the marker exists, the map does not.
;; ============================================================================

(deftest fiscal-position-entity-stores-only-markers
  ;; GREEN — the position entity stays a pure MARKER: name / country-code /
  ;; auto-apply / vat-required. The remap deliberately does NOT hang off it as
  ;; a forward many-ref; mapping lines point back at the position instead, so
  ;; a line can carry its own sequence and express "drop this tax" as an
  ;; absent destination.
  (let [conn (fresh-fp-db)
        fp   (d/pull (d/db conn) '[*]
                     (d/q '[:find ?e . :where
                            [?e :kontor.fiscal-position/name "Intra-Community (EU B2B)"]]
                          (d/db conn)))]
    (is (= "Intra-Community (EU B2B)" (:kontor.fiscal-position/name fp)))
    (is (= "FR" (:kontor.fiscal-position/country-code fp)))
    (is (true? (:kontor.fiscal-position/auto-apply fp)))
    (is (true? (:kontor.fiscal-position/vat-required fp)))
    (testing "the remap is a back-ref, not an attribute of the position"
      (is (empty? (->> (keys fp)
                       (filter #(= "kontor.fiscal-position" (namespace %)))
                       (filter #(re-find #"tax|account|map|mapping" (name %)))))
          "Odoo's account.fiscal.position reaches its mappings through
           AccountFiscalPositionTax.position_id (partner.py:303) rather than
           storing them on the position; kontor mirrors that with
           :kontor.fiscal-position-tax/fiscal-position."))))

;; CLOSED (note 198 R3-FP-01). Was: kontor had no fiscal-position tax-remap
;; entity, so an EU intra-community / export sale could not substitute the
;; domestic 19% tax with the reverse-charge/zero-rate one. Odoo does this in
;; addons/account/models/partner.py:154 `map_tax` — it walks `self.tax_map`
;; (built at partner.py:99-105 from AccountFiscalPositionTax
;; tax_src_id→tax_dest_id lines) and replaces each source tax with its
;; destination tax(es). kontor's StaticTableProvider resolved taxes purely by
;; (country-code, tax-use) with NO fiscal-position input to `rate-facts`, so
;; the domestic tax always won.
;;
;; NOTE the original pin never transacted a mapping line — its own scenario
;; could not have produced the asserted result. A ^:kaocha/pending body never
;; runs, so nothing caught that. The fixture now creates the mapping.
(deftest fiscal-position-remaps-domestic-tax-to-reverse-charge
  (let [conn (fresh-fp-db+map)
        prov (trp/make-static-table-provider conn)
        fp   (d/q '[:find ?e . :where
                    [?e :kontor.fiscal-position/name "Intra-Community (EU B2B)"]]
                  (d/db conn))
        ctx  {:base 1000 :commodity eur
              :country-code "DE" :tax-use :sale :at at}
        facts (trp/rate-facts prov (assoc ctx :fiscal-position fp))
        c     (first (:components facts))]
    (testing "the position substitutes the domestic VAT with the reverse charge"
      (is (= 1 (count (:components facts)))
          "the domestic tax is REPLACED, not supplemented — and the two
           source taxes collapsing onto one destination yields one component,
           not a doubled reverse charge")
      (is (= "DE-RC-0-SALE" (:tax-code c)))
      (is (= :reverse-charge (:kind c)))
      (is (== 0M (:amount c)) "intra-community supply carries no output VAT"))
    (testing "without the position the domestic 19% still applies"
      (let [dom (component-by-code (trp/rate-facts prov ctx) "DE-VAT-19-SALE")]
        (is (== 190.00M (:amount dom)) "1000 × 19%")
        (is (= :output-vat (:kind dom)))))))

;; An absent destination DROPS the tax rather than mapping it to a 0% one —
;; the export case. The distinction matters downstream: a dropped tax leaves
;; NO component, while a 0% reverse-charge tax leaves a zero-amount component
;; that still reaches the VAT return with its reporting tags.
(deftest fiscal-position-with-no-destination-drops-the-tax
  (let [conn (fresh-fp-db)
        db   (d/db conn)
        _    (fp/map-tax! conn "Intra-Community (EU B2B)"
                          (tax-eid db "DE-VAT-19-SALE") nil)
        _    (fp/map-tax! conn "Intra-Community (EU B2B)"
                          (tax-eid db "DE-RC-0-SALE") nil)
        prov (trp/make-static-table-provider conn)
        pos  (fp/by-name (d/db conn) "Intra-Community (EU B2B)")]
    (is (nil? (trp/rate-facts prov {:base 1000 :commodity eur
                                    :country-code "DE" :tax-use :sale :at at
                                    :fiscal-position pos}))
        "every applicable tax dropped → no TaxFacts at all")))

(deftest fiscal-position-remaps-accounts
  (let [conn (fresh-fp-db)
        db   (d/db conn)
        dom  (d/q '[:find ?e . :where [?e :kontor.account/path "Income:Sales"]] db)
        _    (d/transact conn [{:kontor.account/path "Income:Sales-EU"
                                :kontor.account/type :income}])
        eu   (d/q '[:find ?e . :where [?e :kontor.account/path "Income:Sales-EU"]]
                  (d/db conn))
        _    (fp/map-account! conn "Intra-Community (EU B2B)" dom eu)
        db2  (d/db conn)
        pos  (fp/by-name db2 "Intra-Community (EU B2B)")]
    (is (= eu (fp/map-account db2 pos dom)) "mapped account wins")
    (let [ar (d/q '[:find ?e . :where [?e :kontor.account/path "Assets:AR"]] db2)]
      (is (= ar (fp/map-account db2 pos ar)) "an unmapped account passes through"))))

;; ============================================================================
;; (b) Price-included tax gross→net extraction — GAP: no :price-include attr.
;; ============================================================================

(deftest tax-schema-carries-both-compound-and-price-include-markers
  ;; GREEN — the compound marker was always there; the B2C tax-inclusive-price
  ;; marker landed with note 198 R3-FP-02, together with the
  ;; :kontor.tax/sequence the compound marker needs to mean anything.
  (let [conn (fresh-fp-db)
        db   (d/db conn)
        ident? (fn [kw] (seq (d/q '[:find ?e :in $ ?k :where [?e :db/ident ?k]] db kw)))]
    (is (ident? :kontor.tax/include-base-amount) "compound-tax marker")
    (is (ident? :kontor.tax/price-include)
        "Odoo account_tax.py:137 (price_include) — the B2C gross-price marker")
    (is (ident? :kontor.tax/sequence)
        "compound ordering — without it 'subsequent' has no referent")))

;; CLOSED (note 198 R3-FP-02). Was: the StaticTableProvider always treated
;; :base as the pre-tax NET and computed amount = base × rate. For a B2C gross
;; price of 119.00 @ 19% VAT the net is 100.00 and the tax 19.00 — the
;; provider instead computed 119.00 × 19% = 22.61, treating the gross AS the
;; net. Odoo extracts the net first when tax.price_include is set
;; (account_tax.py:137 + _compute_taxes_for_single_line).
;;
;; The extraction is NOT the naive `gross / (1 + rate)` — that is correct only
;; for a single non-compound percent tax. `extract-net` inverts the whole
;; forward pass, so a fixed levy or a second included tax stays exact.
(deftest price-included-tax-extracts-net-from-gross
  (let [conn  (fresh-fp-db)
        prov  (trp/make-static-table-provider conn)
        ;; A tax-inclusive B2C price of 119.00 @ 19% decomposes to
        ;; net 100.00 + VAT 19.00 (119 / 1.19 = 100; 119 − 100 = 19).
        facts (trp/rate-facts prov {:base 119.00M :commodity eur
                                    :country-code "DE" :tax-use :sale :at at
                                    :price-include true})
        c (component-by-code facts "DE-VAT-19-SALE")]
    (is (== 100.00M (:base c))
        "price-included: the taxable base is the extracted NET, not the gross")
    (is (== 19.00M (:amount c))
        "119.00 gross @ 19% → 19.00 VAT (119 / 1.19 = 100 net)")
    (is (== 100.00M (:line-base facts))
        ":line-base is the pre-tax net, so a consumer sizing the revenue leg
         from it books 100.00 and not the gross")
    (testing "the same call without :price-include keeps the old meaning"
      (let [gross-as-net (component-by-code
                          (trp/rate-facts prov {:base 119.00M :commodity eur
                                                :country-code "DE" :tax-use :sale
                                                :at at})
                          "DE-VAT-19-SALE")]
        (is (== 22.61M (:amount gross-as-net))
            "119.00 treated as the net → 22.61; inclusiveness must be stated")))))

(deftest price-include-attr-on-the-tax-is-the-default
  ;; The context flag is a per-LINE override; absent it, the tax's own
  ;; :price-include decides. Same tax, quoted gross to consumers and net to
  ;; businesses, is exactly why the override exists.
  (let [conn (fresh-fp-db)]
    (d/transact conn [{:kontor.tax/code "DE-VAT-19-SALE"
                       :kontor.tax/price-include true}])
    (let [prov  (trp/make-static-table-provider conn)
          ctx   {:base 119.00M :commodity eur :country-code "DE"
                 :tax-use :sale :at at}
          c     (component-by-code (trp/rate-facts prov ctx) "DE-VAT-19-SALE")]
      (is (== 19.00M (:amount c)) "the tax attr alone drives the extraction")
      (is (true? (:price-include c)) "the component reports how it was read")
      (testing "an explicit false in the context overrides the attr"
        (let [c2 (component-by-code
                  (trp/rate-facts prov (assoc ctx :price-include false))
                  "DE-VAT-19-SALE")]
          (is (== 22.61M (:amount c2))))))))

;; CLOSED (note 198 R3-FP-03). Was: :kontor.tax/include-base-amount was in the
;; schema but the StaticTableProvider did NOT honour it — `component-amount`
;; computed every tax as base × rate INDEPENDENTLY. Odoo accumulates a running
;; base (account_tax.py:148, include_base_amount).
;;
;; Closing it exposed a second defect: "subsequent" needs an ORDER, and the
;; provider consumed an unordered `d/q` set. `:kontor.tax/sequence` (ties
;; broken on code) makes the chain deterministic — same bug class as the DATEV
;; contra-account pick, where `first` over a `d/q` result was silently
;; order-dependent.
;;
;; A 10% excise feeding the base of a 19% VAT on a 100 net:
;;   excise = 100 × 10%          = 10.00
;;   VAT    = (100 + 10) × 19%   = 20.90   (NOT 100 × 19% = 19.00)
(deftest compound-tax-stacks-included-base
  (let [conn (fresh-fp-db)]
    ;; both country "CX" so a single rate-facts query returns both
    (d/transact conn
                [{:kontor.account/path "Liabilities:Excise-Payable" :kontor.account/type :liability}
                 {:db/id "t-excise" :kontor.tax/code "CX-EXCISE-10" :kontor.tax/name "Excise 10%"
                  :kontor.tax/country-code "CX" :kontor.tax/type-tax-use :sale
                  :kontor.tax/amount-type :percent :kontor.tax/amount 0.10M
                  :kontor.tax/recoverable? false :kontor.tax/active true
                  :kontor.tax/sequence 10
                  :kontor.tax/include-base-amount true}
                 {:db/id "t-vat" :kontor.tax/code "CX-VAT-19" :kontor.tax/name "VAT 19%"
                  :kontor.tax/country-code "CX" :kontor.tax/type-tax-use :sale
                  :kontor.tax/amount-type :percent :kontor.tax/amount 0.19M
                  :kontor.tax/sequence 20
                  :kontor.tax/recoverable? true :kontor.tax/active true}])
    (let [prov  (trp/make-static-table-provider conn)
          facts (trp/rate-facts prov {:base 100 :commodity eur
                                      :country-code "CX" :tax-use :sale :at at})
          by-code (into {} (map (juxt :tax-code identity) (:components facts)))]
      (is (= ["CX-EXCISE-10" "CX-VAT-19"] (mapv :tax-code (:components facts)))
          "components come back in sequence order, not set order")
      (is (== 10.00M (get-in by-code ["CX-EXCISE-10" :amount]))
          "excise = 100 × 10%")
      (is (== 100M (get-in by-code ["CX-EXCISE-10" :base])))
      (is (== 110.00M (get-in by-code ["CX-VAT-19" :base]))
          "the VAT sees the excise folded into its base")
      (is (== 20.90M (get-in by-code ["CX-VAT-19" :amount]))
          "compound: 110 × 19% = 20.90, not 100 × 19% = 19.00"))))

(deftest compound-order-follows-sequence-not-set-order
  ;; Swap the sequences and the SAME two taxes produce different numbers —
  ;; which is the whole point of making the order explicit. Here the VAT is
  ;; computed first and folds into the excise base:
  ;;   VAT    = 100 × 19%        = 19.00
  ;;   excise = (100 + 19) × 10% = 11.90
  (let [conn (fresh-fp-db)]
    (d/transact conn
                [{:kontor.tax/code "CY-EXCISE-10" :kontor.tax/country-code "CY"
                  :kontor.tax/type-tax-use :sale :kontor.tax/amount-type :percent
                  :kontor.tax/amount 0.10M :kontor.tax/active true
                  :kontor.tax/sequence 20 :kontor.tax/recoverable? false}
                 {:kontor.tax/code "CY-VAT-19" :kontor.tax/country-code "CY"
                  :kontor.tax/type-tax-use :sale :kontor.tax/amount-type :percent
                  :kontor.tax/amount 0.19M :kontor.tax/active true
                  :kontor.tax/sequence 10 :kontor.tax/recoverable? true
                  :kontor.tax/include-base-amount true}])
    (let [facts (trp/rate-facts (trp/make-static-table-provider conn)
                                {:base 100 :commodity eur :country-code "CY"
                                 :tax-use :sale :at at})
          by-code (into {} (map (juxt :tax-code identity) (:components facts)))]
      (is (= ["CY-VAT-19" "CY-EXCISE-10"] (mapv :tax-code (:components facts))))
      (is (== 19.00M (get-in by-code ["CY-VAT-19" :amount])))
      (is (== 11.90M (get-in by-code ["CY-EXCISE-10" :amount]))
          "(100 + 19) × 10%"))))

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
    (testing "the term entity itself stays a net-days scalar"
      (is (= 60 (:kontor.payment-term/net-days term)))
      (is (empty? (->> (keys term)
                       (filter #(and (namespace %)
                                     (re-find #"payment-term" (namespace %))))
                       (filter #(re-find #"line|tranche|percent|value" (name %)))))
          "tranches are :kontor.payment-term-line entities pointing BACK at
           the term, so a scalar term is unchanged by their existence"))))

;; CLOSED (note 198 R3-FP-04). Was: kontor payment-terms were a single scalar
;; :net-days, so a '30% now / 70% in 60 days' instalment plan could not be
;; expressed — no AccountPaymentTermLine analogue. Odoo models each tranche as
;; an account.payment.term.line (account_payment_term.py:281) with
;; value_amount (:291, percent or fixed) + nb_days (:307), and
;; `_compute_terms` (:171) explodes an invoice into one receivable per
;; tranche, each with its own date_maturity — which is what lets tranches age
;; and settle independently.
;;
;; NOTE the original pin transacted a term with NO line data and then asserted
;; a two-tranche explosion; its own scenario could never have produced that.
;; A ^:kaocha/pending body never runs, so nothing caught it. The lines are now
;; real.
(deftest instalment-term-explodes-into-tranches
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:db/id "term-30-70"
                  :kontor.payment-term/code "30/70-NET60"
                  :kontor.payment-term/name "30% sofort, 70% in 60 Tagen"
                  :kontor.payment-term/net-days 60
                  :kontor.payment-term/active true}
                 {:kontor.payment-term-line/term "term-30-70"
                  :kontor.payment-term-line/sequence 10
                  :kontor.payment-term-line/value-type :percent
                  :kontor.payment-term-line/value 30M
                  :kontor.payment-term-line/nb-days 0}
                 ;; the closing tranche is :balance — it takes whatever is
                 ;; left, so the plan can never under- or over-shoot the invoice
                 {:kontor.payment-term-line/term "term-30-70"
                  :kontor.payment-term-line/sequence 20
                  :kontor.payment-term-line/value-type :balance
                  :kontor.payment-term-line/nb-days 60}])
    (let [db   (d/db conn)
          term (pt/by-code db "30/70-NET60")
          inv  #inst "2026-01-01T00:00:00.000-00:00"
          tranches (pt/compute-tranches db term inv 1000.00M)]
      (is (= 2 (count tranches)) "30/70 term explodes into two tranches")
      (is (== 300.00M (:amount (first tranches))))
      (is (= #inst "2026-01-01T00:00:00.000-00:00" (:due-date (first tranches))))
      (is (== 700.00M (:amount (second tranches))))
      (is (= #inst "2026-03-02T00:00:00.000-00:00" (:due-date (second tranches)))
          "second tranche due invoice date + 60 days — ages independently"))))

(deftest scalar-term-still-explodes-into-one-tranche
  ;; Every existing net-days term keeps working: `compute-tranches` is safe to
  ;; call unconditionally, so a consumer never has to branch on whether the
  ;; term happens to carry lines.
  (let [conn (core/create-test-db)
        _    (pt/install-standard-terms! conn)
        db   (d/db conn)
        inv  #inst "2026-01-01T00:00:00.000-00:00"
        ts   (pt/compute-tranches db (pt/by-code db "NET60") inv 1000.00M)]
    (is (= 1 (count ts)))
    (is (== 1000.00M (:amount (first ts))))
    (is (= #inst "2026-03-02T00:00:00.000-00:00" (:due-date (first ts))))))

(deftest tranche-rounding-residue-lands-on-the-last-tranche
  ;; A three-way split of 100.00 is 33.333…; the tranches must still sum to
  ;; 100.00 exactly. An instalment plan that adds up to 99.99 is a silent
  ;; shortfall in AR, not a rounding detail.
  (let [conn (core/create-test-db)]
    (d/transact conn
                (into [{:db/id "t3" :kontor.payment-term/code "THIRDS"
                        :kontor.payment-term/net-days 60
                        :kontor.payment-term/active true}]
                      (for [[seq-n days] [[10 0] [20 30] [30 60]]]
                        {:kontor.payment-term-line/term "t3"
                         :kontor.payment-term-line/sequence seq-n
                         :kontor.payment-term-line/value-type :percent
                         :kontor.payment-term-line/value 33.333333M
                         :kontor.payment-term-line/nb-days days})))
    (let [db (d/db conn)
          ts (pt/compute-tranches db "THIRDS" #inst "2026-01-01" 100.00M)]
      (is (= [33.33M 33.33M 33.34M] (mapv :amount ts)))
      (is (== 100.00M (reduce + 0M (map :amount ts)))
          "tranches sum to the invoice total exactly"))))
