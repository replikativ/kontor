(ns kontor.payroll-mx.chart
  "Installer for the MX payroll starter accounts. Same shape as
   `kontor.payroll-at.chart` / `-fr` / `-au` / `-jp` — the chart-ownership
   convention (ADR-119) — and layers ON TOP of `kontor.l10n-mx.chart`:
   run that first for the SAT Código Agrupador base, then this for the
   Nómina posting accounts.

   It exists because payroll-mx previously shipped no chart at all while
   resolving ten accounts (five `601.xx` expenses, five `206.xx`
   payables) by an EXACT match on `:kontor.account/code` — codes the
   l10n-mx base does not carry. The documented workaround (\"install the
   l10n-mx chart first\") did not work: the base ships `601.05.001`
   Telecomunicaciones, never bare `601.05`, so every payroll run threw
   \"Missing GL account.\" Note 194 §2 PR 7.

   Reference: ADR-082 (payroll-mx), ADR-119 (chart ownership); SAT RMF
   Anexo 24."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-starter
  "Load the payroll-extension starter chart."
  []
  (-> "kontor/payroll_mx/coa_starter.edn" io/resource slurp edn/read-string))

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

   Run AFTER `kontor.l10n-mx.chart/install!`; the two are disjoint by
   construction, and `payroll-mx-codes-are-disjoint-from-l10n-mx` in
   `kontor.payroll-mx.chart-test` holds them that way."
  ([conn] (install! conn (load-starter)))
  ([conn chart] (d/transact conn (mapv account-tx chart))))
