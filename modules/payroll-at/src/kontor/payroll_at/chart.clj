(ns kontor.payroll-at.chart
  "Installer for the AT payroll starter accounts. Same shape as
   `kontor.payroll-fr.chart` / `-au` / `-jp` — the convention 6 of the 11
   payroll modules already follow — and layers ON TOP of
   `kontor.l10n-at.chart`: run that first for the Einheitskontenrahmen
   base, then this for the Personalverrechnung extension.

   It exists because payroll-at previously shipped no chart at all while
   needing thirteen accounts the l10n-at base does not carry. The
   documented workaround was to hand-add them, and following it put
   withheld Lohnsteuer on account 3500 — which the base chart ships as
   Umsatzsteuer 20 % tagged `:uva-022-ust`, so a payroll run inflated
   box 022 of the filed UVA. Note 194 §1 P0-4.

   Reference: ADR-086; KFS/BW 6 (Einheitskontenrahmen)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-starter
  "Load the payroll-extension starter chart."
  []
  (-> "kontor/payroll_at/coa_starter.edn" io/resource slurp edn/read-string))

(defn- account-tx
  [{:keys [code path type name reconcilable?]}]
  (cond-> {:kontor.account/path path
           :kontor.account/code code
           :kontor.account/name name
           :kontor.account/type type
           :kontor.account/active true}
    reconcilable? (assoc :kontor.account/reconcilable true)))

(defn install!
  "Transact the payroll starter accounts. Idempotent —
   `:kontor.account/path` is `:db.unique/identity`.

   Run AFTER `kontor.l10n-at.chart/install!`; the two are disjoint by
   construction, and `payroll-at-does-not-touch-the-uva` in
   `kontor.payroll-at.chart-test` holds them that way."
  ([conn] (install! conn (load-starter)))
  ([conn chart] (d/transact conn (mapv account-tx chart))))
