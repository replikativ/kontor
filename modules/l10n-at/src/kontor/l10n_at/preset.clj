(ns kontor.l10n-at.preset
  "One-call AT preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call. Documents the right
   order, the prerequisites between statutes, and the default journals
   so a consumer doesn't have to discover them.

   Per(one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent — `create-test-db` already loads it)
   - EUR commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - AT chart of accounts (RLG-1-aligned skeleton)
   - Statutes: CGT first (KESt rates + §10 thresholds + the §22 KStG
     CIT rate `AT.KStG.cit-rate` live here), then investment-income
     (depends on the CGT parameters by code), then CIT (depends on
     `AT.KStG.cit-rate`), then PIT (independent — owns the §33 Abs 1
     bracket scale + §33 Abs 3a-7 Absetzbeträge).

   Order note: the recipe-§1.4 general
   guidance is \"CIT statute first, CGT second\" because CIT typically
   defines shared rate parameters. AT inverts this — `AT.KStG.cit-rate`
   shipped FIRST in `cgt-statute` (the AT CGT module shipped before
   the ADR-101 CIT migration), and we keep that order. The
   `:db.unique/identity` upsert on `:kontor.parameter/code` means
   double-defining a parameter is harmless, but we keep the rate
   parameter solely in `cgt-statute` for now.

   Idempotent — re-running installs nothing new (identity attrs handle dedup)."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-at.cgt-statute :as cgt-statute]
            [kontor.l10n-at.chart :as chart]
            [kontor.l10n-at.cit-provider]  ; no compute-fns yet, but symmetric
            [kontor.l10n-at.cit-statute :as cit-statute]
            [kontor.l10n-at.investment-income-provider]  ; load compute-fns
            [kontor.l10n-at.investment-income-statute :as inv-statute]
            [kontor.l10n-at.pit-provider]  ; load compute-fns (Familienbonus, etc.)
            [kontor.l10n-at.pit-statute :as pit-statute]))

(def ^:private default-journals
  [{:kontor.journal/code "GJ" :kontor.journal/type :general :kontor.journal/name "Hauptbuch (General Journal)"}
   {:kontor.journal/code "CR" :kontor.journal/type :cash    :kontor.journal/name "Einzahlungen (Cash Receipts)"}
   {:kontor.journal/code "CD" :kontor.journal/type :cash    :kontor.journal/name "Auszahlungen (Cash Disbursements)"}
   {:kontor.journal/code "SJ" :kontor.journal/type :sale    :kontor.journal/name "Verkäufe (Sales Journal)"}
   {:kontor.journal/code "PJ" :kontor.journal/type :purchase :kontor.journal/name "Einkäufe (Purchase Journal)"}])

(defn install-all!
  "Install everything an AT consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (cit-statute/install! conn)
  (pit-statute/install! conn)
  (chart/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-at-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))
