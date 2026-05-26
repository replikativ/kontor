(ns kontor.disposal-source
  "`DisposalSource` — the kernel-side protocol that per-jurisdiction CGT
   providers depend on to read disposal events.

   The kernel does NOT load the `kontor-disposal` companion's schema
   (ADR-102). CGT providers depend on this protocol; the companion
   ships the canonical implementation against its `:kontor.disposal/*` schema;
   a consumer with external storage (e.g. a 1099-B feed, an HMRC CGT
   summary import) writes their own.

   Mirrors the existing `TaxRateProvider` / `FxRateProvider` /
   `PayrollEmitProvider` pattern — protocol in the kernel, ship-with
   default in a companion, swap-in by the consumer.

   ## Disposal map shape

   A `DisposalSource` returns disposal events as plain Clojure maps
   keyed by the `:kontor.disposal/*` attrs from the companion's schema (see
   `modules/disposal/src/kontor/disposal/schema.clj`). The canonical
   keys CGT providers may read:

     :kontor.disposal/external-id            string
     :kontor.disposal/kind                   keyword (closed enum)
     :kontor.disposal/subject-kind           keyword
     :kontor.disposal/asset-class            keyword — provider routes on this
     :kontor.disposal/subject-form           keyword
     :kontor.disposal/acquired-on            instant
     :kontor.disposal/disposed-on            instant
     :kontor.disposal/holding-period         keyword (denormalized)
     :kontor.disposal/proceeds-amount        BigDecimal
     :kontor.disposal/proceeds-commodity     ref (or pulled commodity map)
     :kontor.disposal/basis-amount           BigDecimal
     :kontor.disposal/basis-commodity        ref
     :kontor.disposal/depreciation-taken-amount      BigDecimal (optional)
     :kontor.disposal/depreciation-taken-commodity   ref (optional)
     :kontor.disposal/ownership-fraction     BigDecimal (optional)
     :kontor.disposal/residence?             boolean (optional)
     :kontor.disposal/elective-regime        #{keyword} (cardinality-many)
     :kontor.disposal/exemption-claimed      #{keyword} (cardinality-many)
     :kontor.disposal/rollover-amount        BigDecimal (optional)
     :kontor.disposal/loss-bucket            keyword (optional)
     :kontor.disposal/state                  keyword — providers should filter
                                       OUT `:voided` entries.

   The companion's reference impl always returns RECORDED + RECOGNIZED
   disposals (`:voided` excluded). A custom source MUST honor the same
   contract or document its filtering.

   ## Why a protocol, not a query helper

   Loose coupling. A consumer that doesn't load `kontor-disposal`
   doesn't get `:kontor.disposal/*` schema, doesn't get void-aware queries,
   and doesn't need to. The CGT provider asks the protocol; if no
   `DisposalSource` is registered, the provider returns empty
   components (no CGT liability for the period) rather than throwing.

   ADR-102.")

;; ============================================================================
;; The protocol
;; ============================================================================

(defprotocol DisposalSource
  "A source of disposal events. ONE method: list disposals for an
   entity that REALIZED in a given period window."

  (disposals-in
    [this entity period]
    "All disposal events for `entity` whose realizing date
     (`:kontor.disposal/disposed-on`) falls within `period`'s
     `{:from #inst :to #inst}` window. Returns a sequence of
     plain Clojure maps as documented in the namespace docstring.
     Implementations MUST exclude `:state :voided` entries."))

;; ============================================================================
;; Nil-source convenience — for callers without a configured source
;; ============================================================================

(def empty-source
  "A `DisposalSource` that returns no disposals. CGT providers that
   accept an optional source can default to this so a missing source
   silently produces an empty component rather than throwing — useful
   for jurisdictions where most entities have no disposals in most
   periods, but the CGT provider is wired regardless."
  (reify DisposalSource
    (disposals-in [_ _ _] [])))
