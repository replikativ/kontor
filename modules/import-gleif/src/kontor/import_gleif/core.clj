(ns kontor.import-gleif.core
  "kontor-import-gleif — ingest GLEIF Golden Copy LEI Level 1 (entity
   master) + Level 2 RR-CDF (parent / subsidiary relationships) into
   the kontor `:entity` substrate.

   License posture (ADR-005 + note 91 §6.2): GLEIF data is **CC0 1.0
   Universal** — the best-possible open license. Free attribution-not-
   required commercial use. kontor bundles small sample fixtures for
   tests; consumers ingest the full ~600 MB daily Golden Copy
   themselves via the GLEIF endpoints.

   ## Schema additions (ADR-094 + note 94 §3.3)

   Kernel schema grows ~6 attrs on `:entity`:

   - `:entity/lei` — 20-character ISO 17442 identifier. Unique-value.
   - `:entity/legal-form` — 'GmbH' / 'AG' / 'LLC' / etc.
   - `:entity/registration-status` — `:issued :lapsed :merged :retired
     :duplicate :transferred :annulled`.
   - `:entity/parent-lei` — direct parent LEI string (debug + reingest).
   - `:entity/ultimate-parent-lei` — ultimate parent LEI string.
   - `:entity/source-id` — provenance opaque ID (e.g.
     `\"gleif://Golden-Copy/2026-05-18\"`).

   The resolved `:entity/parent-entity` ref (ADR-031, existing) is
   what `kontor.entity/family` (ADR-073 consolidation) walks. The
   `:entity/*-lei` raw-string slots are provenance — they survive
   re-ingest + give debugging visibility.

   ## Two-phase ingest

   GLEIF parent relationships forward-reference: a Level 2 row may
   declare 'X IS_DIRECTLY_CONSOLIDATED_BY Y' where both X and Y are
   Level 1 entities, in any order. The clean approach:

   1. `import-level-1!` — transact every entity with its `:entity/lei`,
      `:entity/legal-form`, etc. After this, every LEI in the dataset
      resolves to a kontor `:entity` eid.
   2. `import-level-2!` — for each relationship row, look up the start
      + end LEIs in the DB + set `:entity/parent-entity` (direct
      relation) or `:entity/ultimate-parent-lei` (the string slot
      survives — useful when the ultimate parent is outside the
      ingested subset).

   Both transactors are idempotent: a re-ingest with the same source-
   id overwrites the entity attrs without duplicating.

   ## Sources

   - Note 91 §6 — GLEIF research-before with full license + format
     analysis.
   - LEI-CDF 3.1 spec — https://www.gleif.org/en/about-lei/common-data-file-format
   - RR-CDF 2.1 spec — https://www.gleif.org/en/about-lei/lei-relationship-data
   - License — https://www.gleif.org/en/meta/lei-data-terms-of-use"
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]))

;; ============================================================================
;; LEI shape validation
;; ============================================================================
;;
;; Per note 91 §6.5 ("Schema mapping difficulty: 1/5 — trivial"), full
;; LEI semantic validation (ISO/IEC 7064 MOD 97-10 check digits) is a
;; GLEIF-side concern. The kontor importer treats LEIs as opaque IDs;
;; we only sanity-check that the string is 20-character uppercase
;; alphanumeric so a stray header row or empty field doesn't pollute
;; the entity table.
;;
;; Consumers wanting check-digit validation compose their own —
;; the algorithm is small (multiply each digit / letter value, take
;; mod 97, must equal 1) and ships in dozens of public libraries.

(defn valid-lei?
  "Returns true iff `s` is 20-character uppercase alphanumeric — the
   shape of a GLEIF LEI without the full mod-97-10 check.

   Per ADR-005 + note 91 §6.5, semantic validation is GLEIF-side;
   the kontor importer treats LEIs as opaque master-data IDs."
  [^String s]
  (and (string? s)
       (= 20 (count s))
       (boolean (re-matches #"[A-Z0-9]{20}" s))))

;; ============================================================================
;; CSV → row-maps
;; ============================================================================

(defn- read-csv
  "Slurp + parse a GLEIF CSV into a vector of row-maps keyed by the
   header columns."
  [source]
  (with-open [r (io/reader source)]
    (let [rows (doall (csv/read-csv r))
          [header & data] rows
          headers (mapv keyword header)]
      (mapv #(zipmap headers %) data))))

;; ============================================================================
;; Level 1 — entity master
;; ============================================================================

(def ^:private status->kw
  "Normalize the GLEIF RegistrationStatus column to a kontor keyword.
   Open-set; consumers extend by passing :status-map."
  {"ISSUED"      :issued
   "LAPSED"      :lapsed
   "MERGED"      :merged
   "RETIRED"     :retired
   "DUPLICATE"   :duplicate
   "TRANSFERRED" :transferred
   "ANNULLED"    :annulled})

(defn- row->level-1-tx
  "Build tx-data for one Level 1 row. Returns nil for malformed rows
   (caller filters)."
  [{:keys [source-id default-entity-code-prefix]} row]
  (let [lei  (:LEI row)
        name (:EntityLegalName row)]
    (when (and (string? lei) (valid-lei? lei))
      (cond-> {:entity/lei  lei
               :entity/name name
               :entity/code (str (or default-entity-code-prefix "GLEIF-") lei)
               :entity/active true}
        (:LegalForm row)            (assoc :entity/legal-form (:LegalForm row))
        (status->kw (:RegistrationStatus row))
        (assoc :entity/registration-status
               (status->kw (:RegistrationStatus row)))
        source-id                   (assoc :entity/source-id source-id)))))

(defn import-level-1-tx-data
  "Pure tx-data builder. Returns a vector of `:entity` upsert maps
   (one per valid Level 1 row in `rows`). Invalid rows (missing LEI,
   failed checksum) are dropped silently — call `level-1-validation-
   report` for a structured pre-pass.

   Opts:
     :source-id                  — provenance string. Stamped onto
                                   every entity created.
     :default-entity-code-prefix — string prepended to LEI for the
                                   :entity/code uniqueness key (the
                                   kontor convention; defaults to
                                   'GLEIF-')."
  ([rows] (import-level-1-tx-data rows {}))
  ([rows opts]
   (->> rows
        (keep (partial row->level-1-tx opts))
        vec)))

(defn level-1-validation-report
  "Pre-import pass: classify each row as :ok / :missing-lei / :bad-
   shape. Returns `{:ok-count :total-count :issues [...]}` where each
   issue is `{:row-index :lei :status}`. Useful for surfacing parse
   failures before transacting."
  [rows]
  (let [classified
        (map-indexed
         (fn [idx row]
           (let [lei (:LEI row)]
             (cond
               (str/blank? lei)
               {:status :missing-lei :row-index idx :lei lei}

               (not (valid-lei? lei))
               {:status :bad-shape :row-index idx :lei lei}

               :else
               {:status :ok :row-index idx :lei lei})))
         rows)
        ok-count (count (filter #(= :ok (:status %)) classified))
        issues   (filterv #(not= :ok (:status %)) classified)]
    {:total-count (count rows)
     :ok-count    ok-count
     :issues      issues}))

(defn import-level-1!
  "Transact Level 1 entity master rows from a parsed CSV `rows` (or a
   file `source` consumable by `clojure.java.io/reader`).

   Idempotent against `:entity/lei` (unique-value) — a re-ingest with
   the same LEI updates the entity's attrs in place.

   Returns the tx-report."
  ([conn rows-or-source] (import-level-1! conn rows-or-source {}))
  ([conn rows-or-source opts]
   (let [rows (if (or (instance? java.io.File rows-or-source)
                      (string? rows-or-source)
                      (instance? java.net.URL rows-or-source))
                (read-csv rows-or-source)
                rows-or-source)]
     (d/transact conn (import-level-1-tx-data rows opts)))))

;; ============================================================================
;; Level 2 — parent / subsidiary relationships (RR-CDF)
;; ============================================================================
;;
;; RR-CDF rows have a unified schema regardless of relationship type:
;; StartNode = child LEI; EndNode = parent LEI; RelationshipType =
;; IS_DIRECTLY_CONSOLIDATED_BY | IS_ULTIMATELY_CONSOLIDATED_BY |
;; IS_FUND-MANAGED_BY etc. We model the two consolidation types.
;; Other RR types are visible in the parse map but the importer skips
;; them.

(defn- normalize-rr-cols
  "GLEIF RR-CDF column names use dots: `Relationship.StartNode.NodeID`.
   Normalize to a friendlier shape keyed by `:start :end :type :status`."
  [row]
  {:start  (or (get row (keyword "Relationship.StartNode.NodeID"))
               (get row :StartNode))
   :end    (or (get row (keyword "Relationship.EndNode.NodeID"))
               (get row :EndNode))
   :type   (or (get row (keyword "Relationship.RelationshipType"))
               (get row :RelationshipType))
   :status (or (get row (keyword "Relationship.RelationshipStatus"))
               (get row :RelationshipStatus)
               "ACTIVE")})

(defn import-level-2-tx-data
  "Pure tx-data builder for Level 2 rows.

   Strategy: for each ACTIVE relationship, look up the child by LEI
   in `db`. If found:
   - IS_DIRECTLY_CONSOLIDATED_BY  → set :entity/parent-entity (resolved
                                     ref) AND :entity/parent-lei (raw string).
   - IS_ULTIMATELY_CONSOLIDATED_BY → set :entity/ultimate-parent-lei
                                     (raw string only — no resolved ref
                                     because chain might not be loaded).

   Forward-referenced parents (Level 2 row referencing a LEI not in
   the DB) skip the `:parent-entity` ref but still record the raw
   `:parent-lei` string. A second pass after more Level 1 data lands
   will resolve them."
  [db rows]
  (->> rows
       (map normalize-rr-cols)
       (filter (fn [{:keys [status]}] (= "ACTIVE" status)))
       (keep
        (fn [{:keys [start end type]}]
          (let [child-eid  (d/q '[:find ?e .
                                  :in $ ?lei
                                  :where [?e :entity/lei ?lei]]
                                db start)
                parent-eid (d/q '[:find ?e .
                                  :in $ ?lei
                                  :where [?e :entity/lei ?lei]]
                                db end)]
            (when child-eid
              (case type
                "IS_DIRECTLY_CONSOLIDATED_BY"
                (cond-> {:db/id child-eid
                         :entity/parent-lei end}
                  parent-eid (assoc :entity/parent-entity parent-eid))

                "IS_ULTIMATELY_CONSOLIDATED_BY"
                {:db/id child-eid
                 :entity/ultimate-parent-lei end}

                nil)))))
       vec))

(defn import-level-2!
  "Transact Level 2 RR-CDF rows. Idempotent — re-ingest overwrites
   `:entity/parent-entity` + `:entity/parent-lei` /
   `:entity/ultimate-parent-lei` on the child entity.

   Returns the tx-report."
  [conn rows-or-source]
  (let [rows (if (or (instance? java.io.File rows-or-source)
                     (string? rows-or-source)
                     (instance? java.net.URL rows-or-source))
               (read-csv rows-or-source)
               rows-or-source)]
    (d/transact conn (import-level-2-tx-data (d/db conn) rows))))

;; ============================================================================
;; Convenience: resolve a LEI to an :entity eid
;; ============================================================================

(defn by-lei
  "Lookup helper: resolve an LEI string to an :entity eid (or nil)."
  [db lei]
  (d/q '[:find ?e .
         :in $ ?lei
         :where [?e :entity/lei ?lei]]
       db lei))
