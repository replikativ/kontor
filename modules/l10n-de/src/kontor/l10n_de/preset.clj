(ns kontor.l10n-de.preset
  "One-call DE preset — turns the 4-step prerequisite-aware install
   dance (note 159 §F9 / note 160 §I-8) into a single
   `(install-all! conn)` call. Documents the right order, the
   prerequisites between statutes, and the default journals + chart so
   a consumer doesn't have to discover them.

   What it installs:
   - DE schema (via `kontor.core/install-schema!` — included in
     `create-test-db`, but re-call is idempotent)
   - EUR commodity
   - Default journals: GJ (general), CR (cash-receipts), CD (cash-
     disbursements), SJ (sales), PJ (purchase)
   - SKR04 chart of accounts (~44 accounts under the standard
     `Anlagevermögen / Umlaufvermögen / Eigenkapital / …` paths)
   - Statutes in the right order: CIT first (owns `DE.Soli.rate`), then
     CGT (owns §20 flat rate + §17 inclusion + the gains-side Soli),
     then investment-income (KiSt + Soli-on-§20-income, both shipped
     by the IC statute as of F8).

   After `install-all!`, the consumer can:
   - Post via `kontor.book/entry!` / `sell!` / `pay!` etc.
   - Produce `kontor.l10n-de.pnl/compute`, `…bs/compute-aktiva` etc.
   - Run period taxes via `kontor.l10n-de.cit-provider` /
     `…cgt-provider` / `…investment-income-provider`.

   Idempotent — re-running installs nothing new (identity attrs
   handle dedup). Note 160 §I-8."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-de.chart :as chart]
            [kontor.l10n-de.cgt-statute :as cgt-statute]
            [kontor.l10n-de.cit-statute :as cit-statute]
            [kontor.l10n-de.investment-income-provider]  ; load compute-fns
            [kontor.l10n-de.investment-income-statute :as inv-statute]))

(def ^:private default-journals
  [{:journal/code "GJ" :journal/type :general :journal/name "Hauptbuch (General Journal)"}
   {:journal/code "CR" :journal/type :cash    :journal/name "Einzahlungen (Cash Receipts)"}
   {:journal/code "CD" :journal/type :cash    :journal/name "Auszahlungen (Cash Disbursements)"}
   {:journal/code "SJ" :journal/type :sale    :journal/name "Verkäufe (Sales Journal)"}
   {:journal/code "PJ" :journal/type :purchase :journal/name "Einkäufe (Purchase Journal)"}])

(defn install-all!
  "Install everything a DE consumer needs to start booking and producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cit-statute/install! conn)
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (chart/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-de-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))
