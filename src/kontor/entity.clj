(ns kontor.entity
  "Legal entity / accounting unit helpers — ADR-031.

   The kernel ships *no* default entity. Single-entity tenants opt
   out by never assigning `:posting/entity`; the per-(ledger,
   commodity) sum-to-zero invariant from ADR-021 covers them.
   Multi-entity tenants install their entity tree as data — typically
   alongside chart-of-accounts setup at deployment time.

   Conventions for `:entity/kind`:
     :operating     — a real legal entity that books real transactions
     :elimination   — a synthetic entity holding consolidation
                       eliminations (NetSuite's Elimination Subsidiary)
     :consolidation — a synthetic entity representing the group view

   Queries can scope reports by kind: 'operating only' for statutory,
   'operating + elimination' for consolidated, 'consolidation' for
   the group lens."
  (:refer-clojure :exclude [descendants ancestors parent])
  (:require [clojure.set :as set]
            [datahike.api :as d]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-code
  "Resolve an entity entity-id by its `:entity/code`."
  [db code]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :entity/code ?code]]
       db code))

(defn resolve-entity
  "Coerce `entity-spec` to an entity-id. Accepts:
     - nil       → nil (no entity scope)
     - a string  → looked up by `:entity/code`
     - a long    → returned as-is (assumed eid)
     - a map     → assumed lookup ref or pulled entity"
  [db entity-spec]
  (cond
    (nil? entity-spec)    nil
    (string? entity-spec) (by-code db entity-spec)
    :else                 entity-spec))

;; ============================================================================
;; Hierarchy traversal
;; ============================================================================

(defn parent
  "Direct parent of an entity, or nil for the root."
  [db entity-eid]
  (d/q '[:find ?p .
         :in $ ?e
         :where [?e :entity/parent-entity ?p]]
       db entity-eid))

(defn ancestors
  "Set of ancestor entity-ids for `entity-eid`, walking
   `:entity/parent-entity` to the root. Excludes the entity itself."
  [db entity-eid]
  (loop [acc #{}
         current entity-eid]
    (if-let [p (parent db current)]
      (if (contains? acc p)
        ;; Cycle guard — should not happen in well-formed data but
        ;; defensive against bad inputs.
        acc
        (recur (conj acc p) p))
      acc)))

(defn children
  "Direct children of an entity (entities whose
   `:entity/parent-entity` = `entity-eid`)."
  [db entity-eid]
  (set (d/q '[:find [?c ...]
              :in $ ?p
              :where [?c :entity/parent-entity ?p]]
            db entity-eid)))

(defn descendants
  "Transitive set of descendants of `entity-eid`. Excludes the
   entity itself."
  [db entity-eid]
  (loop [acc      #{}
         frontier (children db entity-eid)]
    (if (empty? frontier)
      acc
      (let [next-frontier (set (mapcat #(children db %) frontier))]
        (recur (into acc frontier)
               (set/difference next-frontier acc))))))

(defn family
  "The entity plus all its descendants — useful for 'all operating
   entities under this consolidation parent' queries."
  [db entity-eid]
  (conj (descendants db entity-eid) entity-eid))

;; ============================================================================
;; Kind filters
;; ============================================================================

(defn by-kind
  "All active entities of the given `:entity/kind`."
  [db kind]
  (d/q '[:find [?e ...]
         :in $ ?kind
         :where
         [?e :entity/kind ?kind]
         [?e :entity/active true]]
       db kind))

(defn operating?
  "True iff the entity is `:operating` kind (the default; real
   transactional entity, not synthetic)."
  [db entity-eid]
  (= :operating
     (or (d/q '[:find ?k . :in $ ?e :where [?e :entity/kind ?k]]
              db entity-eid)
         :operating)))
