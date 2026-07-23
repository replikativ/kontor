(ns kontor.l10n-de.datev
  "DE DATEV EXTF Buchungsstapel export — a thin re-export of the general
   codec in `kontor.import-datev.buchungsstapel` (ADR-120).

   The EXTF grammar is an interchange format, not DE-tax-law reporting, so
   it moved to the `import-datev` companion (parallel to the `bank-*`
   importers), where it also gained an importer and a corrected header
   (the old exporter here wrote the line-count into header field 5, which
   DATEV reserves for the Formatversion — note 195 G1). This namespace
   stays as the DE entry point for back-compat: same `export-buchungsstapel`
   / `write-to-file!` / `datev-columns`, with the older `:client-number` /
   `:konto-nummer` option names adapted onto the companion's
   `:berater-nr` / `:mandant-nr`."
  (:require [kontor.import-datev.buchungsstapel :as bs]))

(def datev-columns
  "The Buchungsstapel column-label list — see `kontor.import-datev.buchungsstapel/columns`."
  bs/columns)

(def datev-column-count bs/column-count)

(defn- adapt-opts
  "Map the historical DE option names onto the companion's."
  [{:keys [client-number konto-nummer] :as opts}]
  (cond-> opts
    client-number (assoc :berater-nr client-number)
    konto-nummer  (assoc :mandant-nr konto-nummer)
    :always       (dissoc :client-number :konto-nummer)))

(defn export-buchungsstapel
  "Generate a DATEV EXTF Buchungsstapel string from the posted postings in
   `conn`. See `kontor.import-datev.buchungsstapel/export-buchungsstapel`;
   the legacy `:client-number` / `:konto-nummer` opts are accepted and map
   to `:berater-nr` / `:mandant-nr`."
  [conn opts]
  (bs/export-buchungsstapel conn (adapt-opts opts)))

(defn write-to-file!
  "Write an export to `filepath` in ISO-8859-1. Same opts as
   [[export-buchungsstapel]]."
  [conn filepath opts]
  (bs/write-to-file! conn filepath (adapt-opts opts)))
