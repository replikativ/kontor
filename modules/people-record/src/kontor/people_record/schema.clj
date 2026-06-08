(ns kontor.people-record.schema
  "Schema for kontor-people-record (ADR-094).

   A minimal, forensically-correct *track-record* schema over the
   kontor-hr substrate. Three entity types:

     :position-held       — one entry per (person, employment, role).
                            Career history; one :person can have many.
     :performance-review  — each formal documented review is one row,
                            referring to a backing :audit-doc with
                            :kontor.audit-doc/category :hr-track-record (or
                            an extension like :hr-performance-review).
     :promotion           — one event per advancement; effective-date
                            + comp-change reference (optional ref to
                            :compensation supersession in kontor-hr).

   Out of scope by ADR-094:
     - activity / screen / keystroke / webcam monitoring
     - emotion / engagement / productivity scoring
     - automated promotion / termination recommendations (Art. 22)

   ## Discipline

   Per ADR-094 the substrate is neutral; people-record is the
   CONSUMER layer that enforces consent + retention + DSAR policy.
   Writes through this companion's `-tx-data` builders check
   `kontor.hr.consent/active-at?` for the affected scope; consumers
   that need a hard refusal compose the gate themselves."
  (:require [datahike.api :as d]))

(def schema
  [;; :position-held — one (person, employment, role, start, end?) row
   {:db/ident       :kontor.position-held/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Consumer-supplied opaque identifier."}

   {:db/ident       :kontor.position-held/person
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :person (the human; not the partner)."}

   {:db/ident       :kontor.position-held/employment
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :employment (kontor-hr) — the legal
                     relationship anchor. A position-held can outlive
                     its employment (e.g. internal transfer) or be
                     bounded inside one employment."}

   {:db/ident       :kontor.position-held/title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.position-held/level
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Open-set band. Consumer extends:
                     :ic-1 / :ic-2 / :senior / :staff / :principal /
                     :manager / :director / :vp / :c-suite."}

   {:db/ident       :kontor.position-held/start-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.position-held/end-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "nil = currently held."}

   {:db/ident       :kontor.position-held/manager-employment
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :employment of the person's manager during
                     this position. Matches kontor-hr's
                     :kontor.employment/manager — manager-relationship is per
                     employment, not per person."}

   ;; :performance-review — one row per formal review event
   {:db/ident       :kontor.performance-review/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :kontor.performance-review/person
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.performance-review/reviewer-employment
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :employment of the reviewer."}

   {:db/ident       :kontor.performance-review/period-start
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.performance-review/period-end
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.performance-review/outcome
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Open-set: :exceeds | :meets | :partial-meets |
                     :below | :pip-required (consumer extends).
                     Per ADR-094, the substrate does
                     NOT canonicalize derived productivity scores;
                     consumers extending with their own outcome
                     keywords is the supported path."}

   {:db/ident       :kontor.performance-review/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the review form / write-up.
                     Typical :kontor.audit-doc/category :hr-track-record."}

   {:db/ident       :kontor.performance-review/calibrated-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Date the review was calibrated / finalized."}

   ;; :promotion — one event per advancement
   {:db/ident       :kontor.promotion/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :kontor.promotion/person
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.promotion/from-position
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :position-held (the previous position)."}

   {:db/ident       :kontor.promotion/to-position
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :position-held (the new position)."}

   {:db/ident       :kontor.promotion/effective-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.promotion/comp-change
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :compensation (kontor-hr) — the new
                     compensation envelope effective with the
                     promotion. Optional — pure title changes
                     without comp-change have nil."}

   {:db/ident       :kontor.promotion/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the promotion letter /
                     calibration notes. Typical :kontor.audit-doc/category
                     :hr-track-record."}])

(defn install!
  "Idempotently install the kontor-people-record schema. Run after
   `kontor.hr.core/install!`."
  [conn]
  (d/transact conn schema)
  conn)
