(ns kontor.disposal.source
  "Canonical `DisposalSource` implementation against the companion's
   `:disposal/*` schema. ADR-102.

   Wraps `kontor.disposal/disposals-in-period` (which already excludes
   `:voided` entries) and pulls each disposal into a plain Clojure map
   keyed by the `:disposal/*` attrs the kernel `DisposalSource` protocol
   documents.

   Per-jurisdiction CGT providers consume the protocol, not this impl
   — so they remain swappable for a consumer that imports disposals
   from an external source (1099-B JSON, HMRC CGT XML, etc.)."
  (:require [datahike.api :as d]
            [kontor.disposal :as disposal]
            [kontor.disposal-source :as src]))

;; ============================================================================
;; Pull spec — every attr a CGT provider might read
;; ============================================================================

(def ^:private pull-spec
  "Datalog pull spec covering every attr a CGT provider may read.
   Resolves commodity refs to `{:db/id :commodity/symbol :commodity/name}`
   so providers can join on `:commodity/symbol`. Resolves the realizing
   transaction ref to its db-id only — providers don't need the tx
   body to compute the gain (they need the GL posting only when
   building a remittance, which goes through `TaxReturnPostingBuilder`)."
  [:db/id
   :disposal/external-id
   :disposal/kind
   :disposal/subject-kind
   :disposal/asset-class
   :disposal/subject-form
   :disposal/acquired-on
   :disposal/disposed-on
   :disposal/holding-period
   :disposal/proceeds-amount
   {:disposal/proceeds-commodity [:db/id :commodity/symbol :commodity/name]}
   :disposal/basis-amount
   {:disposal/basis-commodity [:db/id :commodity/symbol :commodity/name]}
   :disposal/depreciation-taken-amount
   {:disposal/depreciation-taken-commodity [:db/id :commodity/symbol :commodity/name]}
   :disposal/ownership-fraction
   :disposal/residence?
   :disposal/elective-regime
   :disposal/exemption-claimed
   :disposal/rollover-amount
   {:disposal/rollover-amount-commodity [:db/id :commodity/symbol :commodity/name]}
   :disposal/rollover-deadline
   :disposal/loss-bucket
   :disposal/state
   {:disposal/subject [:db/id]}
   {:disposal/realizing-tx [:db/id]}
   :disposal/notes])

;; ============================================================================
;; The companion's DisposalSource
;; ============================================================================

(defrecord DatahikeDisposalSource [conn]
  src/DisposalSource
  (disposals-in [_ entity period]
    (let [db        (d/db conn)
          entity-id (if (integer? entity) entity (:db/id (d/entity db entity)))
          eids      (if entity-id
                      ;; Entity-scoped path — exact eq on :disposal/entity ref.
                      (d/q '[:find [?d ...]
                             :in $ ?ent ?from ?to
                             :where
                             [?d :disposal/entity ?ent]
                             [?d :disposal/disposed-on ?on]
                             [(<= ?from ?on)]
                             [(< ?on ?to)]
                             [?d :disposal/state ?st]
                             [(not= ?st :voided)]]
                           db entity-id (:from period) (:to period))
                      ;; No entity → list all (still void-excluded).
                      (d/q '[:find [?d ...]
                             :in $ ?from ?to
                             :where
                             [?d :disposal/disposed-on ?on]
                             [(<= ?from ?on)]
                             [(< ?on ?to)]
                             [?d :disposal/state ?st]
                             [(not= ?st :voided)]]
                           db (:from period) (:to period)))]
      (mapv #(d/pull db pull-spec %) eids))))

(defn datahike-source
  "Build a `DisposalSource` backed by `kontor-disposal`'s `:disposal/*`
   schema on `conn`. The canonical reference impl — most consumers can
   use this directly."
  [conn]
  (->DatahikeDisposalSource conn))
