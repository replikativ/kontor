(ns kontor.tax-posting-builder
  "`TaxPostingBuilder` — ADR-071. The posting-expansion half of the tax
   abstraction: materialize GL postings from a `TaxFacts`.

   A posting builder is chart-of-accounts-aware (account routing,
   SKR04 vs NCM-CST, GSTN); it never determines rates. Its input,
   `kontor.tax-rate-provider/TaxFacts`, already carries the rate, the
   base, and the computed amount — the builder only routes amounts to
   accounts and applies the sign.

   ## Sign

   `:posting/amount` is signed; positive = debit (note 97 §2). A tax
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
    "Return a vector of `:posting/*` maps (the tax legs only — the
     caller merges them into a transaction alongside the base
     postings). `opts` may carry `:document-type` (:invoice |
     :refund, default :invoice). Returns `[]` for non-taxable facts."))

;; ============================================================================
;; StaticTablePostingBuilder
;; ============================================================================

(defn- sign-for
  "The sign a tax posting amount carries, by `:tax-use`."
  [tax-use]
  (case tax-use
    :sale     -1M
    :purchase 1M
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

(defn- component-postings
  "Tax postings for one `TaxFacts` component."
  [db component commodity tax-use document-type]
  (let [sign (sign-for tax-use)
        reps (rep-lines db (:tax-eid component) document-type)]
    (for [rep reps
          :let [factor  (or (:tax-rep/factor-percent rep) 100M)
                amount  (* (:amount component) (/ factor 100M) sign)
                account (get-in rep [:tax-rep/account :db/id])]
          :when account]
      (cond-> {:posting/account      account
               :posting/amount       amount
               :posting/commodity    commodity
               :posting/display-type :tax
               :posting/tax-rep      (:db/id rep)
               :posting/tax-base     (:base component)}
        (seq (:tax-rep/tags rep))
        (assoc :posting/account-tags (mapv :db/id (:tax-rep/tags rep)))))))

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
   materialize the tax postings. Returns a vector of `:posting/*` maps
   (`[]` when no tax applies)."
  ([rate-provider posting-builder context]
   (compute-tax-postings rate-provider posting-builder context {}))
  ([rate-provider posting-builder context opts]
   (let [facts (trp/rate-facts rate-provider context)]
     (if (trp/taxable? facts)
       (tax-postings posting-builder facts opts)
       []))))
