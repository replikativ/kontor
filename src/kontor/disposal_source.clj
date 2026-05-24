(ns kontor.disposal-source
  "`DisposalSource` — the kernel-side protocol that per-jurisdiction CGT
   providers depend on to read disposal events.

   The kernel does NOT load the `kontor-disposal` companion's schema
   (ADR-102). CGT providers depend on this protocol; the companion
   ships the canonical implementation against its `:disposal/*` schema;
   a consumer with external storage (e.g. a 1099-B feed, an HMRC CGT
   summary import) writes their own.

   Mirrors the existing `TaxRateProvider` / `FxRateProvider` /
   `PayrollEmitProvider` pattern — protocol in the kernel, ship-with
   default in a companion, swap-in by the consumer.

   ## Disposal map shape

   A `DisposalSource` returns disposal events as plain Clojure maps
   keyed by the `:disposal/*` attrs from the companion's schema (see
   `modules/disposal/src/kontor/disposal/schema.clj`). The canonical
   keys CGT providers may read:

     :disposal/external-id            string
     :disposal/kind                   keyword (closed enum)
     :disposal/subject-kind           keyword
     :disposal/asset-class            keyword — provider routes on this
     :disposal/subject-form           keyword
     :disposal/acquired-on            instant
     :disposal/disposed-on            instant
     :disposal/holding-period         keyword (denormalized)
     :disposal/proceeds-amount        BigDecimal
     :disposal/proceeds-commodity     ref (or pulled commodity map)
     :disposal/basis-amount           BigDecimal
     :disposal/basis-commodity        ref
     :disposal/depreciation-taken-amount      BigDecimal (optional)
     :disposal/depreciation-taken-commodity   ref (optional)
     :disposal/ownership-fraction     BigDecimal (optional)
     :disposal/residence?             boolean (optional)
     :disposal/elective-regime        #{keyword} (cardinality-many)
     :disposal/exemption-claimed      #{keyword} (cardinality-many)
     :disposal/rollover-amount        BigDecimal (optional)
     :disposal/loss-bucket            keyword (optional)
     :disposal/state                  keyword — providers should filter
                                       OUT `:voided` entries.

   The companion's reference impl always returns RECORDED + RECOGNIZED
   disposals (`:voided` excluded). A custom source MUST honor the same
   contract or document its filtering.

   ## Why a protocol, not a query helper

   Loose coupling. A consumer that doesn't load `kontor-disposal`
   doesn't get `:disposal/*` schema, doesn't get void-aware queries,
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
     (`:disposal/disposed-on`) falls within `period`'s
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
