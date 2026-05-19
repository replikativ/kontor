(ns kontor.import-edgar.schema
  "Schema for kontor-import-edgar.

   The companion adds a `:reported-fact/*` namespace — externally-
   filed regulator-attested values about an `:entity`. Distinct from
   `:posting`:

   - `:posting` = WE booked this datum into a journal (internal
     accounting; balanced; status-machine-gated).
   - `:reported-fact` = a regulator-filed external fact about an
     entity (no balance constraint; supersession = restatement;
     bitemporal valid-time = SEC `:filed` date).

   Both can co-exist on the same DB. A consumer that wants to bridge
   reported facts → postings does so explicitly (e.g.
   `kontor.l10n-us-gaap.bridge`); the substrate keeps them separate."
  (:require [datahike.api :as d]))

(def schema
  [{:db/ident       :reported-fact/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity:
                     '<source>:<accession>:<concept>:<end>:<unit>'
                     (e.g.
                     'edgar:0001193125-09-214859:us-gaap:AccruedLiabilitiesCurrent:2009-09-26:USD').
                     Idempotent re-ingest of the same SEC filing."}

   {:db/ident       :reported-fact/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :entity — the reporting entity."}

   {:db/ident       :reported-fact/concept-iri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "XBRL concept IRI, e.g.
                     'us-gaap:AccruedLiabilitiesCurrent',
                     'ifrs-full:Revenue', 'dei:EntityRegistrantName'.
                     Indexed for cross-concept queries
                     (kontor.explain/entities-with-concept-iri walks
                     this attr too — ADR-090)."}

   {:db/ident       :reported-fact/value-bigdec
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Monetary / numeric value. BigDecimal per kontor
                     money discipline (ADR-013). String / boolean /
                     date facts use the relevant -text / -instant
                     slots."}

   {:db/ident       :reported-fact/value-string
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "String value for textual concepts (e.g.
                     :EntityRegistrantName, :DocumentType)."}

   {:db/ident       :reported-fact/unit
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Unit of measurement. Open-set:
                     :usd | :eur | :jpy | :shares | :percent | :pure
                     | :usd-per-share | ..."}

   {:db/ident       :reported-fact/period-end
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "The period END date this fact reports on. For
                     instant concepts (BS lines) this is the only
                     date. For duration concepts (P&L lines) pair
                     with :reported-fact/period-start.

                     Distinct from :tx/valid-from (kontor's bitemporal
                     valid-time): :period-end is what the fact is
                     ABOUT; :tx/valid-from is when this fact became
                     authoritatively reported (the SEC :filed date)."}

   {:db/ident       :reported-fact/period-start
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "For duration facts: the period start. nil for
                     instant facts."}

   {:db/ident       :reported-fact/accession-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "SEC accession number — '0000320193-17-000070'.
                     Identifies the filing this fact came from."}

   {:db/ident       :reported-fact/form
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "SEC form: '10-K', '10-Q', '10-K/A', '10-Q/A',
                     '8-K', '20-F', etc. The trailing '/A' indicates
                     an amendment — load-bearing for the bitemporal-
                     restatement story."}

   {:db/ident       :reported-fact/filed
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Date SEC received the filing. The ingest sets
                     `:tx/valid-from` to this date — the fact becomes
                     authoritatively reported as of the SEC filing
                     timestamp. Amendments close-validity on the
                     superseded fact and open a new fact valid-from
                     the amendment's :filed date."}

   {:db/ident       :reported-fact/source-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Provenance opaque identifier — e.g.
                     'edgar://companyfacts/CIK0000320193/2026-05-18'.
                     Audit + re-ingest discrimination."}

   {:db/ident       :reported-fact/superseded-by
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the :reported-fact that supersedes this
                     one (typically a 10-K/A amendment). nil = current
                     authoritative value for the (entity, concept,
                     period). The substrate also closes the
                     superseded fact's `:tx/valid-from` window via
                     `kontor.bitemporal/close-validity-tx-data` so
                     `(d/valid-at db t)` queries return the right
                     fact for any timeline point."}])

(defn install!
  "Idempotently install the kontor-import-edgar schema. Run after
   `kontor.core/install-schema!`."
  [conn]
  (d/transact conn schema))
