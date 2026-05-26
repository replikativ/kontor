(ns kontor.tax-posting-builder
  "`TaxPostingBuilder` — ADR-071. The posting-expansion half of the tax
   abstraction: materialize GL postings from a `TaxFacts`.

   A posting builder is chart-of-accounts-aware (account routing,
   SKR04 vs NCM-CST, GSTN); it never determines rates. Its input,
   `kontor.tax-rate-provider/TaxFacts`, already carries the rate, the
   base, and the computed amount — the builder only routes amounts to
   accounts and applies the sign.

   ## Sign

   `:kontor.posting/amount` is signed; positive = debit (note 97 §2). A tax
   posting must combine with the base/gross postings to net to zero:

     :sale     — gross AR is the debit; net revenue + output tax are
                 credits → tax posting amount is NEGATIVE.
     :purchase — gross AP is the credit; net expense + input tax are
                 debits → tax posting amount is POSITIVE.

   The builder reads `:tax-use` off the `TaxFacts` and signs
   accordingly. (Reverse-charge — `:component/kind :reverse-charge` —
   means different things per `:tax-use`; ADR-071 P1-71-2 puts that
   dispatch in per-country builders. `StaticTablePostingBuilder`
   handles the straightforward VAT / sales-tax case; a per-l10n
   builder overrides for reverse-charge / withholding.)

   ## StaticTablePostingBuilder

   Walks each component's backing `:tax` entity's `:tax-rep`
   repartition lines (ADR-071: \"walk `:tax-rep` entries, materialize
   tax postings\"). Each `:tax-rep` line of `:repartition-type :tax`
   produces one posting: account `:tax-rep/account`, amount
   `component-amount × :tax-rep/factor-percent/100`, carrying the
   line's `:tax-rep/tags` for VAT-box aggregation. `:base` repartition
   lines attach tags only and produce no posting of their own."
  (:require [datahike.api :as d]
            [kontor.tax-rate-provider :as trp]))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol TaxPostingBuilder
  "Materialize the tax-side GL postings for a `TaxFacts`."
  (builder-id [this]
    "A keyword identifying the implementation — :static-table, or a
     per-country id (:de-skr04, :br-ncm-cst, …).")
  (tax-postings [this tax-facts opts]
    "Return a vector of `:kontor.posting/*` maps (the tax legs only — the
     caller merges them into a transaction alongside the base
     postings). `opts` may carry `:document-type` (:invoice |
     :refund, default :invoice). Returns `[]` for non-taxable facts."))

;; ============================================================================
;; StaticTablePostingBuilder
;; ============================================================================

(defn- sign-for
  "The sign an *additive* tax posting amount carries, by `:tax-use` —
   output VAT on a sale is a credit, input VAT on a purchase a debit."
  [tax-use]
  (case tax-use
    :sale     -1M
    :purchase 1M
    0M))

(defn- withholding-sign
  "The sign a *withholding* posting carries — INVERTED from `sign-for`
   (G4, note 101). On a sale the supplier is withheld-from: the
   retención is a prepaid receivable, a debit (+). On a purchase the
   buyer withholds: a payable, a credit (−)."
  [tax-use]
  (case tax-use
    :sale     1M
    :purchase -1M
    0M))

(defn- rep-lines
  "The `:tax-rep` lines of `:repartition-type :tax` for `tax-eid` and
   `document-type`, ordered by `:tax-rep/sequence`."
  [db tax-eid document-type]
  (->> (d/q '[:find [?r ...]
              :in $ ?tax ?doc
              :where
              [?r :tax-rep/tax ?tax]
              [?r :tax-rep/document-type ?doc]
              [?r :tax-rep/repartition-type :tax]]
            db tax-eid document-type)
       (map #(d/pull db '[* {:tax-rep/account [:db/id]}
                          {:tax-rep/tags [:db/id]}] %))
       (sort-by #(:tax-rep/sequence % 0))))

(defn- postings-from-rep-lines
  "Materialize one posting per `:repartition-type :tax` repartition
   line of the component's backing `:tax`, the amount signed by
   `sign`. The shared body of the additive (`standard`) and the
   `:withholding` cases — they differ only in `sign`."
  [db component commodity document-type sign]
  (let [reps (rep-lines db (:tax-eid component) document-type)]
    (for [rep reps
          :let [factor  (or (:tax-rep/factor-percent rep) 100M)
                amount  (* (:amount component) (/ factor 100M) sign)
                account (get-in rep [:tax-rep/account :db/id])]
          :when account]
      (cond-> {:kontor.posting/account      account
               :kontor.posting/amount       amount
               :kontor.posting/commodity    commodity
               :kontor.posting/display-type :tax
               :kontor.posting/tax-rep      (:db/id rep)
               :kontor.posting/tax-base     (:base component)}
        (seq (:tax-rep/tags rep))
        (assoc :kontor.posting/account-tags (mapv :db/id (:tax-rep/tags rep)))))))

(defn- reverse-charge-postings
  "G1 (ADR-071 P1-71-2). Seller-side (`:sale`) reverse charge has NO
   GL tax leg — the VAT-return marker rides the base/revenue posting
   as an account tag, applied by the consumer. Buyer-side (`:purchase`)
   self-assesses both halves: Dr input-VAT receivable, Cr output-VAT
   payable, sourced from the backing `:tax`'s `:tax-group` account
   pair. Equal-and-opposite — for a fully-deductible buyer there is no
   cash effect. A country with partial reverse-charge deductibility
   ships a bespoke `TaxPostingBuilder`."
  [db component commodity tax-use]
  (if (= tax-use :sale)
    []
    (let [grp (:tax/tax-group
               (d/pull db '[{:tax/tax-group
                             [{:tax-group/payable-account [:db/id]}
                              {:tax-group/receivable-account [:db/id]}]}]
                       (:tax-eid component)))
          payable    (get-in grp [:tax-group/payable-account :db/id])
          receivable (get-in grp [:tax-group/receivable-account :db/id])
          amt        (:amount component)
          base       {:kontor.posting/commodity    commodity
                      :kontor.posting/display-type :tax
                      :kontor.posting/tax-base     (:base component)}]
      (when-not (and payable receivable)
        (throw (ex-info "reverse-charge: backing :tax needs a :tax-group with both payable + receivable accounts"
                        {:tax-eid (:tax-eid component)})))
      [(assoc base :kontor.posting/account receivable :kontor.posting/amount amt)      ;; Dr input-VAT
       (assoc base :kontor.posting/account payable    :kontor.posting/amount (- amt))]))) ;; Cr output-VAT

(defn- component-postings
  "Tax postings for one `TaxFacts` component, dispatched on `:kind`.
   `:reverse-charge` and `:withholding` are the special mechanisms
   (G1 / G4); every other kind is *additive* and shares the standard
   rep-line walk."
  [db component commodity tax-use document-type]
  (case (:kind component)
    :reverse-charge
    (reverse-charge-postings db component commodity tax-use)

    :withholding
    (postings-from-rep-lines db component commodity document-type
                             (withholding-sign tax-use))

    ;; :output-vat :input-vat :sales-tax :cess :duty :fee :surcharge
    ;; :pre-collection — additive; the original behaviour, unchanged
    (postings-from-rep-lines db component commodity document-type
                             (sign-for tax-use))))

(defrecord StaticTablePostingBuilder [conn opts]
  TaxPostingBuilder
  (builder-id [_] :static-table)
  (tax-postings [_ tax-facts {:keys [document-type db]
                              :or   {document-type :invoice}}]
    (if-not (trp/taxable? tax-facts)
      []
      (let [db (or db (d/db conn))]
        (vec (mapcat #(component-postings db % (:commodity tax-facts)
                                          (:tax-use tax-facts) document-type)
                     (:components tax-facts)))))))

(defn make-static-table-posting-builder
  "Construct a `StaticTablePostingBuilder` over `conn`."
  ([conn] (make-static-table-posting-builder conn {}))
  ([conn opts] (->StaticTablePostingBuilder conn opts)))

;; ============================================================================
;; Pipeline — rate provider → TaxFacts → posting builder
;;
;; The minimal composition ADR-071 calls `kontor.tax-pipeline`. A full
;; pipeline ns (with the `kontor.document.invoice/send!` adapter) is a
;; deferred follow-up; this one function is enough to wire the trio.
;; ============================================================================

(defn compute-tax-postings
  "Run the full ADR-071 trio for one transaction line: ask
   `rate-provider` for `TaxFacts`, then ask `posting-builder` to
   materialize the tax postings. Returns a vector of `:kontor.posting/*` maps
   (`[]` when no tax applies)."
  ([rate-provider posting-builder context]
   (compute-tax-postings rate-provider posting-builder context {}))
  ([rate-provider posting-builder context opts]
   (let [facts (trp/rate-facts rate-provider context)]
     (if (trp/taxable? facts)
       (tax-postings posting-builder facts opts)
       []))))

;; ============================================================================
;; Multi-line aggregation — the G5 wrapper (research note 100 / 101)
;;
;; `tax-postings` is per-line; an invoice-level builder runs it across
;; every line and then collapses postings that hit the same account
;; into one (every l10n `compute-invoice-tax` buckets across lines).
;; `aggregate-postings` is that collapse — shared so each per-l10n
;; builder need not re-roll it.
;; ============================================================================

(defn aggregate-postings
  "Collapse tax postings that target the same account into one per
   account. Postings are grouped by `[:kontor.posting/account :kontor.posting/commodity
   :kontor.posting/display-type]`; `:kontor.posting/amount` is summed; the first
   posting's remaining keys are kept and `:kontor.posting/account-tags` are
   unioned. Groups whose summed amount is zero are dropped.

   NOTE: a posting's `:kontor.posting/tax-rep` is taken from the first member
   of its group — lossless for the bespoke per-l10n builders (no
   `:tax-rep` refs), lossy for `StaticTablePostingBuilder` output;
   aggregate per-rate there, not across rates."
  [postings]
  (->> postings
       (group-by (juxt :kontor.posting/account :kontor.posting/commodity :kontor.posting/display-type))
       vals
       (keep (fn [group]
               (let [sum (reduce + 0M (map :kontor.posting/amount group))]
                 (when-not (zero? sum)
                   (cond-> (assoc (first group) :kontor.posting/amount sum)
                     (some :kontor.posting/account-tags group)
                     (assoc :kontor.posting/account-tags
                            (vec (distinct (mapcat :kontor.posting/account-tags group)))))))))
       vec))
