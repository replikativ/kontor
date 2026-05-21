(ns kontor.commitment.schema
  "kontor-commitment companion schema — ADR-098, research note 99
   Stage 4.

   A `:commitment` is a recorded *obligation* — value promised but not
   yet moved: a receivable a customer owes, a payable you owe, an
   encumbrance you have set aside. The GL records what *did* move
   (postings, `Ker σ`); a commitment records what is *supposed to*.
   Recognising and liquidating obligations is, per the note-99 DCR
   sharpening, the half of accounting the ledger alone cannot see.

   Entities:
     :commitment              — the obligation (status-machine entity)
     :commitment-fulfillment  — an edge: which `:transaction` settled
                                how much of which commitment

   State machine (ADR-034), facet `:commitment/state`:
     :open → :partially-fulfilled → :fulfilled
     :open / :partially-fulfilled → :cancelled

   The kernel is untouched: the fulfillment edge points AT a kernel
   `:transaction` but the kernel `:transaction` gains no attribute.
   `:commitment/origin` is an opt-in soft link to an `:order` /
   `:schedule` / lease-liability entity — those modules are not
   changed; unification is a deliberately deferred later pass."
  (:require [datahike.api :as d]))

;; ============================================================================
;; :commitment
;; ============================================================================

(def ^:private commitment-attrs
  [{:db/ident       :commitment/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Caller-supplied stable id. Identity."}

   {:db/ident       :commitment/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         ":receivable (owed to you) | :payable (you owe) |
                     :encumbrance (you have earmarked / reserved)."}

   {:db/ident       :commitment/counterparty
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The partner the obligation is with."}

   {:db/ident       :commitment/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ADR-031 legal-entity scope."}

   {:db/ident       :commitment/committed-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The obligation's total amount, unsigned."}

   {:db/ident       :commitment/fulfilled-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Running total fulfilled — a denormalization kept
                     in step with the `:commitment-fulfillment` edges,
                     updated in the SAME tx as each fulfillment (no
                     drift window). `outstanding = committed −
                     fulfilled`."}

   {:db/ident       :commitment/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :commitment/due-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the obligation falls due — drives `aging`."}

   {:db/ident       :commitment/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "ADR-034 status-machine facet.
                     #{:open :partially-fulfilled :fulfilled :cancelled}"}

   {:db/ident       :commitment/recorded-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Actor who recorded the commitment (audit)."}

   {:db/ident       :commitment/recorded-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :commitment/origin
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "OPT-IN soft link to the entity this commitment
                     arose from — an :order line, a :schedule, a lease
                     liability. The kernel does not interpret it; it
                     is a join handle for consumers. Unifying these
                     obligation sources is a deferred pass (ADR-098)."}

   {:db/ident       :commitment/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :commitment-fulfillment — the edge to the settling transaction
;; ============================================================================

(def ^:private commitment-fulfillment-attrs
  [{:db/ident       :commitment-fulfillment/commitment
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The commitment being (partly) settled."}

   {:db/ident       :commitment-fulfillment/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The kernel `:transaction` that settled it. The
                     edge lives here, in the companion — the kernel
                     `:transaction` gains nothing."}

   {:db/ident       :commitment-fulfillment/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "How much of the commitment this fulfillment
                     applied, unsigned."}

   {:db/ident       :commitment-fulfillment/fulfilled-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :commitment-fulfillment/recorded-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :commitment-fulfillment/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private all
  (vec (concat commitment-attrs commitment-fulfillment-attrs)))

;; ============================================================================
;; State-machine seeds — :commitment/state (ADR-034)
;; ============================================================================

(def ^:private status-transition-seeds
  [{:status-transition/entity-type :commitment
    :status-transition/facet :commitment/state
    :status-transition/from :nil :status-transition/to :open
    :status-transition/active true
    :status-transition/name "Record Commitment"}
   {:status-transition/entity-type :commitment
    :status-transition/facet :commitment/state
    :status-transition/from :open :status-transition/to :partially-fulfilled
    :status-transition/active true
    :status-transition/name "Partially Fulfill Commitment"}
   {:status-transition/entity-type :commitment
    :status-transition/facet :commitment/state
    :status-transition/from :open :status-transition/to :fulfilled
    :status-transition/active true
    :status-transition/name "Fulfill Commitment"}
   {:status-transition/entity-type :commitment
    :status-transition/facet :commitment/state
    :status-transition/from :partially-fulfilled :status-transition/to :fulfilled
    :status-transition/active true
    :status-transition/name "Complete Partially-Fulfilled Commitment"}
   {:status-transition/entity-type :commitment
    :status-transition/facet :commitment/state
    :status-transition/from :open :status-transition/to :cancelled
    :status-transition/active true
    :status-transition/name "Cancel Commitment"}
   {:status-transition/entity-type :commitment
    :status-transition/facet :commitment/state
    :status-transition/from :partially-fulfilled :status-transition/to :cancelled
    :status-transition/active true
    :status-transition/name "Cancel Partially-Fulfilled Commitment"}])

;; ============================================================================
;; Installer
;; ============================================================================

(defn install!
  "Install the kontor-commitment schema + `:commitment/state`
   status-machine seeds. Run after `kontor.schema/install!` (the
   kernel attrs the companion references — `:transaction`, `:partner`,
   `:commodity`, `:status-transition` — must already exist).

   The schema attrs are idempotent (re-issuing a `:db/ident` is a
   no-op); the status-transition seeds carry the kernel-wide
   composite-tuple non-idempotency caveat — one install per DB."
  [conn]
  (d/transact conn all)
  (d/transact conn status-transition-seeds))
