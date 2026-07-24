(ns kontor.provider.disposal-provider
  "`DisposalProvider` — the kernel-side protocol that per-jurisdiction CGT
   providers depend on to read disposal events.

   The kernel does NOT load the `kontor-disposal` companion's schema
   (ADR-102). CGT providers depend on this protocol; the companion
   ships the canonical implementation against its `:kontor.disposal/*` schema;
   a consumer with external storage (e.g. a 1099-B feed, an HMRC CGT
   summary import) writes their own.

   Mirrors the existing `TaxRateProvider` / `FxRateProvider` /
   `PayrollEmitProvider` pattern — protocol in the kernel, ship-with
   default in a companion, swap-in by the consumer.

   ## Renamed from `DisposalSource` (W3, note 175)

   Per note 172 §4 the `DisposalSource` name was the one outlier in
   the otherwise-uniform `XxxProvider` protocol family. W3
 renames to `DisposalProvider` and collapses the
   `(this entity period)` arity to the uniform `(this ctx)` shape.
   The method is now `disposals-facts`, the namespace
   `kontor.provider.disposal-provider`, and the companion ns
   `kontor.disposal.provider`.

   ## Disposal map shape

   A `DisposalProvider` returns disposal events as plain Clojure maps
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
   `DisposalProvider` is registered, the provider returns empty
   components (no CGT liability for the period) rather than throwing.

   ADR-102.")

;; ============================================================================
;; The protocol
;; ============================================================================

(defprotocol DisposalProvider
  "A source of disposal events. Two methods — one identity, one
   query — per the uniform `(this ctx)` shape (note 175 / 172 §4)."

  (provider-id [this]
    "A keyword identifying the implementation — :datahike,
     :ofx-1099b, :hmrc-cgt-import, …. Used in `:provenance` and logs.")

  (disposals-facts
    [this ctx]
    "Given a ctx `{:entity <ref-or-eid> :period {:from #inst :to #inst}}`,
     return a vector of disposal-event maps whose realizing date
     (`:kontor.disposal/disposed-on`) falls within `:period`. See the
     namespace docstring for the map shape.

     Implementations MUST exclude `:state :voided` entries, and MUST
     return the vector in a TOTAL, reproducible order: ascending
     `:kontor.disposal/disposed-on`, ties broken by `:db/id` (or, for a
     non-datahike source, any stable per-record key). This is part of the
     contract, not a nicety — consumers fold the list against stateful
     caps and pools (an annual exemption, a loss pool, a §54EC-style
     lifetime cap) where first-come wins, so an unordered result makes the
     tax owed depend on iteration order. note 198 audit (H8)."))

;; ============================================================================
;; Nil-source convenience — for callers without a configured provider
;; ============================================================================

(def empty-provider
  "A `DisposalProvider` that returns no disposals. CGT providers that
   accept an optional provider can default to this so a missing
   provider silently produces an empty component rather than throwing
   — useful for jurisdictions where most entities have no disposals in
   most periods, but the CGT provider is wired regardless."
  (reify DisposalProvider
    (provider-id [_] :empty)
    (disposals-facts [_ _ctx] [])))
