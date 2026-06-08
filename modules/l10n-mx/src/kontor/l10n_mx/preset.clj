(ns kontor.l10n-mx.preset
  "One-call MX preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call.

   Per(one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent)
   - MXN commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - MX chart of accounts (SAT Código Agrupador skeleton)
   - Statutes: CGT first (art. 120 averaging, 700k UDIS casa-habitación,
     art. 22 costo promedio with CUFIN/CUCA, the shared
     `MX.CGT.art-9.pm-rate` parameter the CIT consumes), then
     investment-income (LISR Título IV provisions), then CIT (
     MX slice — depends on `MX.CGT.art-9.pm-rate`),
     then PIT — independent, owns the LISR art. 152
     bracket scale + art. 96-bis subsidio para el empleo + lanes from
     CGT / investment-income providers).

   Order note: the recipe §1.4 general
   guidance is \"CIT statute first, CGT second\" because CIT typically
   defines shared rate parameters. MX inverts this — `MX.CGT.art-9.pm-rate`
   shipped FIRST in `cgt-statute` (the MX CGT module shipped before
   the ADR-101 CIT migration), and we keep that order. The
   `:db.unique/identity` upsert on `:kontor.parameter/code` means
   double-defining a parameter is harmless, but we keep the rate
   parameter solely in `cgt-statute` for now.

   Idempotent — re-running installs nothing new (identity attrs
   handle dedup; PIT bracket dedup via the statute's internal
   helper)."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-mx.cgt-statute :as cgt-statute]
            [kontor.l10n-mx.chart :as chart]
            [kontor.l10n-mx.cit-provider]  ; symmetry with FR/DE/JP/CA/AT
            [kontor.l10n-mx.cit-statute :as cit-statute]
            [kontor.l10n-mx.investment-income-provider]  ; load compute-fns
            [kontor.l10n-mx.investment-income-statute :as inv-statute]
            [kontor.l10n-mx.pit-provider]  ; symmetry
            [kontor.l10n-mx.pit-statute :as pit-statute]))

(def ^:private default-journals
  [{:kontor.journal/code "GJ" :kontor.journal/type :general :kontor.journal/name "Libro Diario (General Journal)"}
   {:kontor.journal/code "CR" :kontor.journal/type :cash    :kontor.journal/name "Ingresos (Cash Receipts)"}
   {:kontor.journal/code "CD" :kontor.journal/type :cash    :kontor.journal/name "Egresos (Cash Disbursements)"}
   {:kontor.journal/code "SJ" :kontor.journal/type :sale    :kontor.journal/name "Ventas (Sales Journal)"}
   {:kontor.journal/code "PJ" :kontor.journal/type :purchase :kontor.journal/name "Compras (Purchase Journal)"}])

(defn install-all!
  "Install everything an MX consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (cit-statute/install! conn)
  (pit-statute/install! conn)
  (chart/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-mx-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))
