(ns kontor.authz.core
  "kontor-authz — relationship-based access control (ReBAC) — ADR-065.

   The `IAuthorization` protocol + the records the rest of the module
   speaks in. A faithful reimplementation of EACL's ReBAC model
   (https://github.com/theronic/eacl, EPL-2.0), proven to run on
   datahike in research note 41 — kontor-authz ports the *design*
   into a datahike-native, EPL-1.0, `:authz/*`-namespaced companion
   (the project's lift-the-pattern-write-our-own convention, ADR-001;
   ADR-002 cohabitation).

   The model is SpiceDB-shaped:
   - a **Relation** is a typed edge definition — `(Relation :account
     :owner :user)` reads \"an `:account` can have an `:owner` that is
     a `:user`\".
   - a **Permission** is a derived check — either a direct relation
     (`{:relation :owner}`), an arrow through another relation
     (`{:arrow :account :permission :admin}` = \"`account->admin`\"),
     or a self-permission (`{:permission :other}`).
   - a **Relationship** is an actual edge instance — `(Relationship
     (subject :user u) :owner (subject :account a))`.

   `can?` / `lookup-resources` / `lookup-subjects` walk that graph.
   This namespace is **pure** — no datahike dependency; it is the
   protocol + the value types only."
  (:require [clojure.core]))

(defprotocol IAuthorization
  "The kontor-authz client surface. Arities follow the convention
   `[subject permission resource]` for order-dependent calls."

  ;; --- permission checks ---
  (can?
    [this subject permission resource]
    [this subject permission resource consistency]
    [this demand]
    "True iff `subject` has `permission` on `resource`. `demand` is a
     map `{:keys [subject permission resource consistency]}`.")

  ;; --- schema ---
  (read-schema [this]
    "Return the installed relation + permission definitions.")
  (write-schema! [this schema]
    "Install / extend the relation + permission definitions.")

  ;; --- relationships (the edges) ---
  (read-relationships [this query]
    "Query relationship edges. `query` keys: `:subject/type`,
     `:subject/id`, `:resource/type`, `:resource/id`,
     `:resource/relation`, `:limit`, `:cursor`. At least one of
     `:resource/type`, `:subject/type`, `:resource/relation` is
     required.")
  (write-relationships! [this updates]
    "Apply a seq of `RelationshipUpdate` `{:keys [operation
     relationship]}` — operation ∈ #{:create :touch :delete}.")
  (write-relationship!
    [this operation subject relation resource]
    [this demand])
  (create-relationships! [this relationships]
    "Create a seq of `Relationship`s. Throws on a duplicate.")
  (create-relationship!
    [this subject relation resource]
    [this relationship])
  (delete-relationships! [this relationships]
    "Delete a seq of `Relationship`s (e.g. the result of
     `read-relationships`).")
  (delete-relationship!
    [this subject relation resource]
    [this relationship])

  ;; --- enumeration ---
  (lookup-resources [this query]
    "Every resource `subject` has `permission` on. `query` keys:
     `:subject`, `:permission`, `:resource/type`, `:limit`,
     `:cursor`. Cursor-paginated.")
  (count-resources [this query]
    "Count of `lookup-resources` — enumerates from the cursor.")
  (lookup-subjects [this query]
    "Every subject that has `permission` on `resource`. `query` keys:
     `:resource`, `:permission`, `:subject/type`,
     `:subject/relation`.")
  (expand-permission-tree [this query]
    "Expand the permission graph for `resource` + `permission`."))

;; ============================================================================
;; Value types
;; ============================================================================

(defrecord Relationship [subject relation resource])
(defrecord RelationshipUpdate [operation relationship])

(defrecord ObjectRef [type id relation])
;; `relation` here is the *subject-relation* (a usersets-style
;; \"members of group X\" reference) — distinct from `Relationship`'s
;; `relation`. nil for a plain subject/resource.

(defn object-ref
  "Construct an `ObjectRef` — a typed subject/resource reference.
     (object-ref :user \"alice\")
     (object-ref :group \"admins\" :member)   ; a userset"
  ([type id]          (->ObjectRef type id nil))
  ([type id relation] (->ObjectRef type id relation)))
