(ns kontor.actor.ref
  "The pure half of `kontor.actor` (ADR-150) — actor-spec coercion and the
   stamp, with ZERO dependencies.

   Extracted from `kontor.actor` for the same reason `kontor.posting.build`
   and `kontor.book.build` exist (note 192): the pure `*-tx-data` builders
   must stay free of `datahike.api`, and they need `->ref` to turn a
   friendly `:actor` option into a reference. `kontor.actor` re-exports
   everything here, so consumers require only `kontor.actor`."
  (:refer-clojure :exclude [name])
  (:require [clojure.string :as str]))

(def canonical-kinds
  "Project-endorsed `:kontor.actor/kind` values. Open set per ADR-051's
   convention — a consumer may add its own — but these three cover the
   distinction an auditor actually asks about (human / integration / the
   system itself)."
  #{:person :service :system})

(defn ->ref
  "Coerce a friendly actor spec to a datahike reference. Pure — no db, so
   it runs inside the `*-tx-data` builders and in the browser.

     nil                              → nil
     42                               → 42                   (already an eid)
     [:kontor.actor/uid \"sarah\"]      → unchanged             (lookup-ref)
     \"sarah\"                          → [:kontor.actor/uid \"sarah\"]
     :sarah                           → [:kontor.actor/uid \"sarah\"]
     #uuid \"…\"                        → [:kontor.actor/uid \"…\"]
     {:db/id 42 …}                    → 42                    (a pulled actor)

   The string case is the load-bearing one: it preserves the ergonomics of
   the convention the shipped suite already uses (`:actor \"sarah\"`) while
   changing its MEANING from \"mint a phantom entity\" to \"resolve the
   registered actor, or refuse\". `:kontor.actor/uid` is
   `:db.unique/identity`, so datahike raises on an unknown lookup-ref —
   which is the fail-closed behaviour an audit control needs."
  [spec]
  (cond
    (nil? spec)                        nil
    (and (map? spec) (:db/id spec))    (:db/id spec)
    (number? spec)                     spec
    (vector? spec)                     spec                    ; lookup-ref
    (string? spec)                     [:kontor.actor/uid spec]
    (keyword? spec)                    [:kontor.actor/uid (clojure.core/name spec)]
    :else                              [:kontor.actor/uid (str spec)]))

(def actor-stamp-attrs
  "The attributes an `:actor` stamps, in the order a reader should think
   about them: who sealed it, who created it, who last wrote it. The
   create/write pair follows the Odoo convention the kernel's
   `:kontor.audit/*` attributes were modelled on — both are set at
   creation, and `write-uid` moves on later updates."
  [:kontor.transaction/posted-by
   :kontor.audit/create-uid
   :kontor.audit/write-uid])

(defn stamp
  "Stamp `actor-spec` onto a transaction entity map as all three of
   [[actor-stamp-attrs]]. Pure. Returns `tx-map` unchanged when
   `actor-spec` is nil, and never overwrites an attribute the caller set
   explicitly — a mid-life import legitimately carries a different original
   creator than the actor doing the importing."
  [tx-map actor-spec]
  (if-let [r (->ref actor-spec)]
    (reduce (fn [m a] (if (contains? m a) m (assoc m a r))) tx-map actor-stamp-attrs)
    tx-map))

;; ============================================================================
;; The `…-uid` family — normalising the convention already in the wild
;; ============================================================================
;;
;; There are 19 `…-uid` attributes in the kernel schema and ALL 19 are
;; `:db.type/ref`. The de-facto convention across the shipped suite is to
;; pass an opaque string (`"test"`, `"sarah"`) — 296 such writes were
;; measured by ADR-124's gate check, which deliberately exempted the
;; `…-uid` suffix rather than break every one of them.
;;
;; A string in a ref slot is a TEMPID (see `kontor.gate`), so each of those
;; 296 writes minted a BRAND NEW entity with no attributes. Two
;; consequences, both silent:
;;
;;   1. The audit trail records a pointer resolving to nothing. Asking
;;      "who approved this?" pulls `#:db{:id 1443}` and stops.
;;   2. `"bob"` in one transaction and `"bob"` in the next are DIFFERENT
;;      entities. So `:no-self-approval`, which compares the approver's eid
;;      against the creator's eid, could never fire even when both sides
;;      were populated — the control was inert, not merely unenforced.
;;
;; [[uid-attr?]] + [[collect-uid-strings]] + [[rewrite-uid-strings]] are the
;; pure half of the fix: recognise those strings and re-point them at one
;; canonical `:kontor.actor` entity per uid. `kontor.actor/resolve-uid-refs`
;; adds the db-aware half (provision-or-refuse). ADR-150.

(defn uid-attr?
  "True for the `…-uid` family — `:kontor.audit/create-uid`,
   `:kontor.status-history/changed-by-uid`,
   `:kontor.payment-application/applied-by-uid`,
   `:kontor.receipt/inspector-uid`, … — every one of which is a
   `:db.type/ref` pointing at an actor.

   Suffix-matched rather than enumerated on purpose: the 19 attributes are
   spread across the kernel and six companion modules, and a hard-coded set
   would silently stop covering the twentieth. `kontor.gate/actor-uid-attr?`
   is the same predicate on the other side of the same boundary.

   `:kontor.actor/uid` itself is excluded, and the exclusion is
   load-bearing: it is the actor's own `:db.type/string` IDENTITY, not a
   ref at an actor. Matching it would rewrite the very datom the normaliser
   points everything else at — the actor entity would be created carrying
   its own tempid as its uid, and every lookup-ref would then miss."
  [a]
  (and (keyword? a)
       (not= :kontor.actor/uid a)
       (str/ends-with? (clojure.core/name a) "-uid")))

(defn actor-lookup-ref?
  "True for `[:kontor.actor/uid \"sarah\"]` — the shape [[->ref]] produces.

   Recognising the VALUE rather than the attribute is what lets the
   normaliser cover `:kontor.transaction/posted-by`, which points at an
   actor but does not carry the `-uid` suffix, without maintaining a list of
   every actor-pointing attribute in the kernel and its companions. Any
   value of this shape denotes an actor no matter which slot it sits in."
  [v]
  (and (vector? v) (= 2 (count v))
       (= :kontor.actor/uid (first v)) (string? (second v))))

(defn- uid-of
  "The actor uid a value denotes in slot `a` — a bare string under the
   `…-uid` convention, or the second element of an actor lookup-ref
   anywhere. nil for everything else (eids, other lookup-refs, maps)."
  [a v]
  (cond
    (and (string? v) (uid-attr? a)) v
    (actor-lookup-ref? v)           (second v)
    :else                           nil))

(defn- other-lookup-ref?
  "A 2-vector lookup-ref that is NOT an actor one — must not be descended
   into as if it were a cardinality-many collection, since its second slot
   is a plain value."
  [v]
  (and (vector? v) (= 2 (count v)) (keyword? (first v)) (not (actor-lookup-ref? v))))

(defn collect-uid-strings
  "Every distinct actor uid `tx-data` mentions — bare strings in `…-uid`
   slots and `[:kontor.actor/uid …]` lookup-refs anywhere — EXCLUDING bare
   strings the same tx-data declares as a tempid.

   That exclusion keeps the legitimate inline case working: a tx-data that
   creates an actor and points a `-uid` at it by tempid
   (`[{:db/id \"a1\" :kontor.actor/uid \"sarah\"} {… :changed-by-uid \"a1\"}]`)
   is already correct and must be left exactly as written. Lookup-refs are
   never tempids, so they are always collected.

   Mirrors `kontor.gate/collect-refs` on what counts as a tempid
   declaration — deliberately, since the two judge the same forms."
  [tx-data]
  (let [found    (volatile! #{})
        declared (volatile! #{})
        see!     (fn [a v] (when-let [u (uid-of a v)]
                             (vswap! found conj [u (actor-lookup-ref? v)])))]
    (letfn [(walk-map [m]
              (when (string? (:db/id m)) (vswap! declared conj (:db/id m)))
              (doseq [[a v] (dissoc m :db/id)]
                (cond
                  ;; an actor lookup-ref is a VALUE, not a collection — it must
                  ;; be judged whole, BEFORE the cardinality-many descent below
                  ;; walks into its two slots and mistakes the uid inside it for
                  ;; a bare string sitting in a `…-uid` attribute.
                  (actor-lookup-ref? v) (see! a v)
                  (map? v)              (walk-map v)
                  (other-lookup-ref? v) nil
                  (sequential? v)       (doseq [x v]
                                          (if (map? x) (walk-map x) (see! a x)))
                  :else                 (see! a v))))
            (walk-form [form]
              (cond
                (map? form) (walk-map form)
                (sequential? form)
                (let [[op e a & vs] form]
                  (when (and (keyword? op) (string? e)) (vswap! declared conj e))
                  (when (#{:db/add :db/retract :db/cas} op)
                    (doseq [v vs] (see! a v))))))]
      (doseq [form tx-data] (walk-form form)))
    ;; a uid reached ONLY as a bare string can be shadowed by a tempid
    ;; declaration; one reached as a lookup-ref never can.
    (into #{} (keep (fn [[u lookup?]]
                      (when (or lookup? (not (@declared u))) u)))
          @found)))

(defn rewrite-uid-strings
  "Replace every actor mention in `tx-data` — bare `…-uid` string or
   `[:kontor.actor/uid …]` lookup-ref — with `(rename uid)`, leaving
   everything else (eids, other lookup-refs, declared tempids) untouched.
   `rename` returns nil to leave a mention alone.

   Pure and total over the tx-data shapes datahike accepts: entity maps,
   nested maps, cardinality-many vectors and list forms."
  [tx-data rename]
  (letfn [(rw-val [a v]
            (if-let [u (uid-of a v)] (or (rename u) v) v))
          (rw-map [m]
            (reduce-kv
             (fn [acc a v]
               (assoc acc a
                      (cond
                        (= :db/id a)          v
                        ;; whole-value first — see the matching note in
                        ;; `collect-uid-strings`.
                        (actor-lookup-ref? v) (rw-val a v)
                        (map? v)              (rw-map v)
                        (other-lookup-ref? v) v
                        (sequential? v)       (mapv #(if (map? %) (rw-map %) (rw-val a %)) v)
                        :else                 (rw-val a v))))
             {} m))
          (rw-form [form]
            (cond
              (map? form) (rw-map form)
              (and (sequential? form) (#{:db/add :db/retract :db/cas} (first form)))
              (let [a (nth form 2 nil)]
                (into (vec (take 3 form)) (map #(rw-val a %)) (drop 3 form)))
              :else form))]
    (mapv rw-form tx-data)))
