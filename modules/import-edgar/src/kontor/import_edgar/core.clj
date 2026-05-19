(ns kontor.import-edgar.core
  "kontor-import-edgar — ingest SEC EDGAR `companyfacts` JSON into
   the kontor `:reported-fact/*` substrate (note 94 §3.4 + note 78).

   ## What this ingests

   SEC's `data.sec.gov/api/xbrl/companyfacts/CIK<10>.json` endpoint
   exposes every XBRL-tagged numeric fact from every filing for a
   company, already parsed into JSON form. We treat this as the
   authoritative shape and DO NOT parse XBRL XML / iXBRL HTML — per
   note 78 §1, the JSON API is XBRL-derived and sidesteps the parser
   problem entirely. A future kontor-xbrl companion would add the
   XML/iXBRL path for FDTA municipal filings + Companies House +
   ESEF + E-Bilanz; the substrate this module ships is the target.

   ## Bitemporal-restatement model

   Each `(entity, concept, period-end, unit)` quadruple lives in ITS
   OWN datahike transaction. The transaction carries `:db.valid/from`
   = SEC `:filed` date — the bitemporal valid-time axis aligns with
   the SEC reporting axis.

   When a later filing (10-K/A amendment, subsequent 10-K's comparative
   period, etc.) re-reports the same `(entity, concept, period-end,
   unit)`:

   1. Close-validity on the prior fact's transaction at the new
      `:filed` date (`kontor.bitemporal/close-validity-tx-data`).
   2. Record a new `:reported-fact` row with `:tx/valid-from` = the
      new `:filed` date, AND set the prior fact's
      `:reported-fact/superseded-by` to the new fact.

   Result:
   - `(d/valid-at db t)` returns the fact authoritative AS OF
     reporting time `t` — the original 10-K before the amendment,
     the amendment after.
   - The supersession chain is also navigable structurally via the
     `:reported-fact/superseded-by` ref — useful for 'show me the
     history of this restatement' walks.

   ## Per ADR-005 + ADR-001

   - No bundled SEC API keys (none required; SEC mandates a
     User-Agent header instead — pass via opts or env var
     `SEC_EDGAR_USER_AGENT`).
   - No bundled rate-limited data (the JSON API is fetched at
     ingest-time by the consumer).
   - JVM-only — no XBRL parser dependency (the JSON API path).

   ## Sources

   - Note 91 §2 — EDGAR research-before (datasets, license, format).
   - Note 78 — XBRL primer + JVM-XBRL-libraries gap.
   - SEC EDGAR docs — https://www.sec.gov/edgar/sec-api-documentation"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt])
  (:import [java.math BigDecimal]
           [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util Date]))

;; ============================================================================
;; Date parsing — SEC dates are ISO-local-date ("2009-10-27")
;; ============================================================================

(defn- parse-date
  "Parse a 'YYYY-MM-DD' string to a `java.util.Date` at UTC midnight."
  ^Date [^String s]
  (when s
    (let [ld (LocalDate/parse s DateTimeFormatter/ISO_LOCAL_DATE)]
      (Date/from (.toInstant (.atStartOfDay ld java.time.ZoneOffset/UTC))))))

;; ============================================================================
;; JSON → fact-rows
;; ============================================================================

(defn- ^String concept-iri
  "Combine taxonomy + concept name → IRI string. SEC keys taxonomies
   by 'us-gaap' / 'dei' / 'srt' / 'ifrs-full' / 'invest'; we adopt the
   colon-prefixed form for `:reported-fact/concept-iri`."
  [taxonomy concept]
  (str (name taxonomy) ":" (name concept)))

(defn- external-id
  "Build the composite identity key for a fact."
  [{:keys [source accession concept-iri period-end unit]}]
  (str source ":" accession ":" concept-iri ":" period-end ":" (name unit)))

(defn parse-companyfacts
  "Parse a `companyfacts` JSON string (or already-parsed map) into a
   flat seq of fact-row maps.

   Each fact-row carries:

     :cik           long
     :entity-name   string
     :taxonomy      keyword (`:us-gaap` etc.)
     :concept       keyword (`:AccruedLiabilitiesCurrent` etc.)
     :concept-iri   string  ('us-gaap:AccruedLiabilitiesCurrent')
     :unit          keyword (`:usd`, `:shares`, ...)
     :end           string  ('2009-09-26')
     :start         string  (only present for duration concepts)
     :val           number  (raw — coerced to BigDecimal at ingest)
     :accession     string  ('0001193125-09-214859')
     :form          string  ('10-K', '10-Q', '10-K/A')
     :filed         string  ('2009-10-27')
     :fy / :fp      fiscal year / fiscal period (informational)"
  [json-or-map]
  (let [data (if (string? json-or-map)
               (json/read-str json-or-map :key-fn keyword)
               json-or-map)
        cik (:cik data)
        ename (:entityName data)
        facts (:facts data)]
    (for [[taxonomy concepts] facts
          [concept body]      concepts
          [unit unit-facts]   (:units body)
          fact                unit-facts]
      (-> fact
          (assoc :cik cik
                 :entity-name ename
                 :taxonomy taxonomy
                 :concept concept
                 :concept-iri (concept-iri taxonomy concept)
                 :unit (-> unit name str/lower-case keyword))))))

;; ============================================================================
;; Ingest — one tx per (entity, concept, period-end, unit) fact
;; ============================================================================

(defn- normalize-value
  "Coerce raw JSON value → BigDecimal (or nil for non-numeric)."
  [v]
  (cond
    (number? v)        (BigDecimal/valueOf (double v))
    (string? v)        (try (BigDecimal. ^String v) (catch Exception _ nil))
    (instance? BigDecimal v) v
    :else              nil))

(defn- find-prior-fact
  "Look up an existing fact for the same `(entity, concept, end, unit)`
   quadruple. Returns the latest pull-result (by `:filed`) or nil."
  [db {:keys [entity-eid concept-iri period-end unit]}]
  (let [eids (d/q '[:find [?f ...]
                    :in $ ?e ?c ?p ?u
                    :where
                    [?f :reported-fact/entity ?e]
                    [?f :reported-fact/concept-iri ?c]
                    [?f :reported-fact/period-end ?p]
                    [?f :reported-fact/unit ?u]]
                  db entity-eid concept-iri period-end unit)
        rows (mapv #(d/pull db
                            [:db/id
                             :reported-fact/filed
                             :reported-fact/value-bigdec
                             :reported-fact/accession-number
                             :reported-fact/form
                             :reported-fact/superseded-by] %)
                   eids)
        ;; Of all facts for this quad, the authoritative one is the
        ;; latest-:filed without a :superseded-by reference. (A row
        ;; might have an older :filed but still be the head if the
        ;; supersession chain points elsewhere.)
        head (->> rows
                  (remove :reported-fact/superseded-by)
                  (sort-by :reported-fact/filed)
                  last)]
    head))

(defn- fact-row-tx-data
  "Build the per-fact tx-data fragment for one EDGAR JSON fact-row.
   Returns nil when the fact is non-ingestable (no value, no end-date)
   or already-ingested (existing fact with same accession is a no-op).

   The fragment is a vector of maps ready to be merged into an outer
   `with-vt` call; the outer call stamps `:db.valid/from` = filed-date.

   When a prior fact exists with an earlier `:filed`:
   - The prior fact gets `:superseded-by` pointing at the new tempid.
   - The new fact carries the SEC metadata.
   The caller (`ingest-facts!`) is responsible for closing the prior
   fact's tx valid-time via `kbt/close-validity-tx-data` — that
   requires resolving the prior fact's tx-entity-id, which only
   datahike can do post-tx; we therefore split the work."
  [db {:keys [entity-eid source]} fact]
  (let [val      (normalize-value (:val fact))
        end-str  (:end fact)
        filed    (parse-date (:filed fact))
        period-end (parse-date end-str)
        period-start (when (:start fact) (parse-date (:start fact)))
        unit     (:unit fact)
        accession (:accn fact)
        concept-iri (:concept-iri fact)
        ext-id   (external-id {:source source
                               :accession accession
                               :concept-iri concept-iri
                               :period-end end-str
                               :unit unit})]
    (when (and val period-end filed accession)
      (let [prior (find-prior-fact db {:entity-eid entity-eid
                                       :concept-iri concept-iri
                                       :period-end period-end
                                       :unit unit})
            already? (and prior
                          (= (:reported-fact/accession-number prior)
                             accession))]
        (when-not already?
          (let [tempid (str "fact:" ext-id)
                new-fact
                (cond-> {:db/id tempid
                         :reported-fact/external-id     ext-id
                         :reported-fact/entity          entity-eid
                         :reported-fact/concept-iri     concept-iri
                         :reported-fact/value-bigdec    val
                         :reported-fact/unit            unit
                         :reported-fact/period-end      period-end
                         :reported-fact/accession-number accession
                         :reported-fact/form            (:form fact)
                         :reported-fact/filed           filed
                         :reported-fact/source-id       source}
                  period-start (assoc :reported-fact/period-start period-start))]
            (if (and prior
                     (.before ^Date (:reported-fact/filed prior) filed))
              ;; Restatement — wire up :superseded-by + return both
              ;; the supersession marker and the new fact.
              {:tx-data [{:db/id (:db/id prior)
                          :reported-fact/superseded-by tempid}
                         new-fact]
               :prior prior
               :vt-from filed}
              {:tx-data [new-fact]
               :prior nil
               :vt-from filed})))))))

(defn ingest-facts!
  "Ingest a sequence of parsed fact-rows into `conn`.

   Required opts:
     :entity-eid — ref to the kontor :entity these facts are about.
     :source     — provenance string for :reported-fact/source-id.

   Each fact becomes its own transaction with `:db.valid/from` set to
   the SEC `:filed` date. Re-ingesting the same fact (same accession,
   concept, period-end, unit) is a no-op. Re-ingesting an updated
   fact (different accession but same quad) records the supersession
   chain via `:reported-fact/superseded-by` AND closes the prior
   tx's valid-time window via `kontor.bitemporal/close-validity!`.

   Returns a summary `{:ingested int :superseded int :skipped int
   :tx-reports [...]}`."
  [conn parsed-facts opts]
  (let [{:keys [entity-eid source]} opts
        _ (when-not entity-eid
            (throw (ex-info ":entity-eid required" {:opts opts})))
        _ (when-not source
            (throw (ex-info ":source required" {:opts opts})))]
    (reduce
     (fn [acc fact]
       (let [db (d/db conn)
             frag (fact-row-tx-data db {:entity-eid entity-eid :source source}
                                    fact)]
         (cond
           (nil? frag)
           (update acc :skipped inc)

           :else
           (let [{:keys [tx-data prior vt-from]} frag
                 ;; First: close the prior fact's tx-window at the new
                 ;; vt-from (only if there's a prior; only datahike
                 ;; can do this since it requires the prior tx-eid).
                 ;; The prior tx-eid is recoverable via :db/txInstant
                 ;; on the prior fact entity — kontor's
                 ;; close-validity-tx-data takes the tx-eid directly.
                 _ (when prior
                     (let [prior-tx-eid
                           (d/q '[:find ?tx .
                                  :in $ ?e
                                  :where
                                  [?e :reported-fact/external-id _ ?tx _]]
                                db (:db/id prior))]
                       (when prior-tx-eid
                         (try
                           (d/transact
                            conn
                            (kbt/close-validity-tx-data prior-tx-eid vt-from))
                           (catch Exception _
                             ;; If close fails (e.g. vt window would
                             ;; collapse), continue — the supersession
                             ;; ref is still set; bitemporal queries
                             ;; can fall back to filed-date ordering.
                             nil)))))
                 ;; Then the supersession + new fact, both at vt-from
                 report (d/transact conn (kbt/with-vt tx-data vt-from))]
             (-> acc
                 (update :ingested inc)
                 (update :superseded (if prior inc identity))
                 (update :tx-reports conj report))))))
     {:ingested 0 :superseded 0 :skipped 0 :tx-reports []}
     parsed-facts)))

;; ============================================================================
;; Convenience queries
;; ============================================================================

(defn current-fact
  "Return the current authoritative fact for `(entity, concept-iri,
   period-end, unit)` as of valid-time `at` (defaults to now). nil if
   no such fact exists. Uses kontor's bitemporal `:tx/valid-from`
   axis — a restated value AFTER the amendment's filed date returns
   the amendment; a query date BEFORE the amendment returns the
   original."
  ([conn entity-eid concept-iri period-end unit]
   (current-fact conn entity-eid concept-iri period-end unit (Date.)))
  ([conn entity-eid concept-iri period-end unit ^Date at]
   (let [db (d/valid-at (d/db conn) at)
         eids (d/q '[:find [?f ...]
                     :in $ ?e ?c ?p ?u
                     :where
                     [?f :reported-fact/entity ?e]
                     [?f :reported-fact/concept-iri ?c]
                     [?f :reported-fact/period-end ?p]
                     [?f :reported-fact/unit ?u]]
                   db entity-eid concept-iri period-end unit)
         ;; The head of the chain (latest non-superseded fact); the
         ;; bitemporal window pruning already removes facts whose
         ;; tx-vt window has closed.
         rows (mapv #(d/pull db '[*] %) eids)]
     (->> rows
          (remove :reported-fact/superseded-by)
          (sort-by :reported-fact/filed)
          last))))

(defn fact-history
  "Return the supersession history for `(entity, concept-iri,
   period-end, unit)` — every fact ever recorded, oldest first.
   Useful for 'show me how this number changed over time' walks."
  [conn entity-eid concept-iri period-end unit]
  (let [db   (d/db conn)
        eids (d/q '[:find [?f ...]
                    :in $ ?e ?c ?p ?u
                    :where
                    [?f :reported-fact/entity ?e]
                    [?f :reported-fact/concept-iri ?c]
                    [?f :reported-fact/period-end ?p]
                    [?f :reported-fact/unit ?u]]
                  db entity-eid concept-iri period-end unit)]
    (->> eids
         (mapv #(d/pull db '[*] %))
         (sort-by :reported-fact/filed))))
