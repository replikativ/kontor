(ns kontor.disposal.provider
  "Canonical `DisposalProvider` implementation against the companion's
   `:kontor.disposal/*` schema. ADR-102. Implements
   `kontor.provider.disposal-provider/DisposalProvider`.

   Wraps `kontor.disposal/disposals-in-period` (which already excludes
   `:voided` entries) and pulls each disposal into a plain Clojure map
   keyed by the `:kontor.disposal/*` attrs the kernel `DisposalProvider`
   protocol documents.

   Per-jurisdiction CGT providers consume the protocol, not this impl
   — so they remain swappable for a consumer that imports disposals
   from an external source (1099-B JSON, HMRC CGT XML, etc.)."
  (:require [datahike.api :as d]
            [kontor.provider.disposal-provider :as dp]))

;; ============================================================================
;; Pull spec — every attr a CGT provider might read
;; ============================================================================

(def ^:private pull-spec
  "Datalog pull spec covering every attr a CGT provider may read.
   Resolves commodity refs to `{:db/id :kontor.commodity/symbol :kontor.commodity/name}`
   so providers can join on `:kontor.commodity/symbol`. Resolves the realizing
   transaction ref to its db-id only — providers don't need the tx
   body to compute the gain (they need the GL posting only when
   building a remittance, which goes through `TaxReturnPostingBuilder`)."
  [:db/id
   :kontor.disposal/external-id
   :kontor.disposal/kind
   :kontor.disposal/subject-kind
   :kontor.disposal/asset-class
   :kontor.disposal/subject-form
   :kontor.disposal/acquired-on
   :kontor.disposal/disposed-on
   :kontor.disposal/holding-period
   :kontor.disposal/proceeds-amount
   {:kontor.disposal/proceeds-commodity [:db/id :kontor.commodity/symbol :kontor.commodity/name]}
   :kontor.disposal/basis-amount
   {:kontor.disposal/basis-commodity [:db/id :kontor.commodity/symbol :kontor.commodity/name]}
   :kontor.disposal/depreciation-taken-amount
   {:kontor.disposal/depreciation-taken-commodity [:db/id :kontor.commodity/symbol :kontor.commodity/name]}
   :kontor.disposal/ownership-fraction
   :kontor.disposal/residence?
   :kontor.disposal/elective-regime
   :kontor.disposal/exemption-claimed
   :kontor.disposal/rollover-amount
   {:kontor.disposal/rollover-amount-commodity [:db/id :kontor.commodity/symbol :kontor.commodity/name]}
   :kontor.disposal/rollover-deadline
   :kontor.disposal/loss-bucket
   :kontor.disposal/state
   {:kontor.disposal/subject [:db/id]}
   {:kontor.disposal/realizing-tx [:db/id]}
   :kontor.disposal/notes])

;; ============================================================================
;; The companion's DisposalProvider
;; ============================================================================

(defrecord DatahikeDisposalProvider [conn]
  dp/DisposalProvider
  (provider-id [_] :datahike)
  (disposals-facts [_ {:keys [entity period]}]
    (let [db        (d/db conn)
          entity-id (if (integer? entity) entity (:db/id (d/entity db entity)))
          eids      (if entity-id
                      ;; Entity-scoped path — exact eq on :kontor.disposal/entity ref.
                      (d/q '[:find [?d ...]
                             :in $ ?ent ?from ?to
                             :where
                             [?d :kontor.disposal/entity ?ent]
                             [?d :kontor.disposal/disposed-on ?on]
                             [(<= ?from ?on)]
                             [(< ?on ?to)]
                             [?d :kontor.disposal/state ?st]
                             [(not= ?st :voided)]]
                           db entity-id (:from period) (:to period))
                      ;; No entity → list all (still void-excluded).
                      (d/q '[:find [?d ...]
                             :in $ ?from ?to
                             :where
                             [?d :kontor.disposal/disposed-on ?on]
                             [(<= ?from ?on)]
                             [(< ?on ?to)]
                             [?d :kontor.disposal/state ?st]
                             [(not= ?st :voided)]]
                           db (:from period) (:to period)))]
      ;; note 198 audit (H8): this returned the raw `d/q` SET order. CGT
      ;; providers fold the result ORDER-DEPENDENTLY over stateful caps and
      ;; pools — AU absorbs the loss pool and elects Subdiv 152 concessions
      ;; per disposal in sequence, IN consumes the ₹50L §54EC cap first-come
      ;; across lanes taxed at different rates. So set iteration order chose
      ;; which disposal got the relief, and the tax owed on an unchanged
      ;; ledger was not reproducible. Chronological, then eid for same-day
      ;; disposals (dates are day-granular).
      (->> eids
           (mapv #(d/pull db pull-spec %))
           (sort-by (juxt :kontor.disposal/disposed-on :db/id))
           vec))))

(defn datahike-provider
  "Build a `DisposalProvider` backed by `kontor-disposal`'s
   `:kontor.disposal/*` schema on `conn`. The canonical reference impl
   — most consumers can use this directly."
  [conn]
  (->DatahikeDisposalProvider conn))
