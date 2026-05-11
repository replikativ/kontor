(ns kontor.tax-provider
  "The TaxProvider protocol — the only abstraction the kernel uses to
   compute taxes. Per ADR-005 in doc/decisions.md:

     - DE / CA tax surfaces fit in a static EDN table — covered by
       StaticTableProvider.
     - US sales tax requires either an SST CSV feeder (24 states with
       free quarterly data) or a paid-API adapter (Avalara, TaxJar,
       TaxCloud). Adapters are scaffolded; we never bundle API keys
       or ToS-restricted data.
     - Recoverable vs non-recoverable is a tax-level property
       (:tax/recoverable?), not a provider concern. The repartition
       machinery handles both correctly.

   Phase 1 ships only StaticTableProvider — the others are scaffolding
   stubs. The protocol shape itself is the durable design choice.")

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol TaxProvider
  "Resolves applicable taxes for a posting context.

   The kernel calls (resolve-taxes provider context) when expanding a
   draft transaction's :product display-type postings into the full
   set including :tax postings. The provider is responsible for
   returning the additional postings (typically two per applicable
   tax: the base-side tag attachment and the tax-side posting) but
   does NOT mutate or replace the input base posting."
  (provider-id [this]
    "A keyword identifying this provider implementation. Used in audit
     logs and tests. Examples: :static-table :sst-csv :avalara :taxjar
     :taxcloud.")

  (resolve-taxes [this context]
    "Given a context map, return a vector of additional posting maps
     to merge into the transaction.

     Context keys:
       :transaction       — the in-flight transaction map (may not yet
                            have :db/id; will after transact)
       :base-posting      — the :product display-type posting being taxed
       :partner           — the partner ref (with :partner/country-code,
                            :partner/tax-id, etc.)
       :fiscal-position   — the fiscal position ref or nil
       :effective-date    — the bitemporal valid-time of the transaction
       :db                — the current db value (read-only) for catalog
                            lookups (account-tag refs, tax-rep tags, etc.)

     Returns a vector of posting maps. Each posting map is:
       {:posting/account      <ref>
        :posting/amount       <bigdec, signed>
        :posting/commodity    <ref>
        :posting/display-type :tax
        :posting/tax-rep      <ref to tax-repartition-line>
        :posting/tax-base     <bigdec — the base this tax was computed from>
        :posting/account-tags <vec of account-tag refs>}

     Returns [] when no taxes apply (out-of-scope partner, exempt
     account, etc.). Never returns nil."))

;; ============================================================================
;; Default implementation: StaticTableProvider
;;
;; Reads tax definitions and repartition lines from the connected db
;; itself (the per-country localization module transacts them at install
;; time). This is the impl used for DE, CA, and any country whose tax
;; rules fit a finite EDN table.
;;
;; Phase 1 implementation is a stub returning [] — actual rule matching
;; is the next slice. The shape proves the protocol is satisfiable.
;; ============================================================================

(defrecord StaticTableProvider [opts]
  TaxProvider
  (provider-id [_] :static-table)
  (resolve-taxes [_ _context]
    ;; TODO Phase 1: select :tax entities matching country/use, walk
    ;; :tax-rep entries, materialize tax postings. See doc/roadmap.md
    ;; "Phase 1 — tax engine + tax-provider".
    []))

(defn make-static-table-provider
  "Construct a StaticTableProvider. Options:
     :default-country-code — fallback when partner has no country
                             (default \"DE\" for Phase 1; reconsider
                             when CA / US localizations land)."
  [{:keys [default-country-code]
    :or   {default-country-code "DE"}
    :as opts}]
  (->StaticTableProvider (assoc opts :default-country-code default-country-code)))

;; ============================================================================
;; SCAFFOLDS — implementations to be filled in by Phase 5-US.
;;
;; Each is intentionally a stub today; the contract is fixed so a
;; consumer can swap providers without code changes elsewhere.
;; ============================================================================

(defrecord SstCsvProvider [csv-dir opts]
  TaxProvider
  (provider-id [_] :sst-csv)
  (resolve-taxes [_ _context]
    (throw (ex-info "SstCsvProvider not implemented (Phase 5-US)"
                    {:csv-dir csv-dir}))))

(defrecord AvalaraProvider [api-key opts]
  TaxProvider
  (provider-id [_] :avalara)
  (resolve-taxes [_ _context]
    (throw (ex-info "AvalaraProvider not implemented (Phase 5-US)"
                    {:hint "Customers register their own API key; we do
                            not bundle one. See ADR-005."}))))

(defrecord TaxJarProvider [api-key opts]
  TaxProvider
  (provider-id [_] :taxjar)
  (resolve-taxes [_ _context]
    (throw (ex-info "TaxJarProvider not implemented (Phase 5-US)"
                    {:hint "Customers register their own API key; we do
                            not bundle one. See ADR-005."}))))

;; ============================================================================
;; Composite: ChainedProvider
;;
;; Tries providers in order; first non-empty result wins. Useful when
;; some jurisdictions are static-table-covered and others need a paid
;; API — e.g., a US accounting setup where SST states use the CSV
;; provider and non-SST states fall through to Avalara.
;; ============================================================================

(defrecord ChainedProvider [providers]
  TaxProvider
  (provider-id [_] :chained)
  (resolve-taxes [_ context]
    (or (->> providers
             (some (fn [p]
                     (let [r (resolve-taxes p context)]
                       (when (seq r) r)))))
        [])))

(defn chain
  "Construct a ChainedProvider that tries each in order and returns the
   first non-empty result."
  [& providers]
  (->ChainedProvider (vec providers)))
