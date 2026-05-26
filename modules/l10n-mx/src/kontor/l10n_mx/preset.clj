(ns kontor.l10n-mx.preset
  "One-call MX preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call.

   Per note 168 §S10 (one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent)
   - MXN commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - MX chart of accounts (SAT Código Agrupador skeleton)
   - Statutes: CGT (art. 120 averaging, 700k UDIS casa-habitación,
     art. 22 costo promedio with CUFIN/CUCA), investment-income
     (LISR Título II provisions).

   MX does not yet ship an ADR-101 CIT statute; the period-tax provider
   carries the rate (record-shape, pre-ADR-101).

   Idempotent."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-mx.cgt-statute :as cgt-statute]
            [kontor.l10n-mx.chart :as chart]
            [kontor.l10n-mx.investment-income-provider]  ; load compute-fns
            [kontor.l10n-mx.investment-income-statute :as inv-statute]))

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
