(ns kontor.payroll-fr.core
  "kontor-payroll-fr — FR-DSN payroll adapter (Stage R C5, ADR-079).

   This namespace is the consumer-facing entry point. Composes:
     - the existing kontor.l10n-fr base chart (PCG) + identifiers
       (SIREN / SIRET / TVA intra) + TVA compute + invoice posting
       builder + CA3 monthly aggregator + fiscal-year close,
     - the kontor-hr substrate (`kontor.hr.payroll/run-payroll!`,
       `kontor.provider.payroll-provider/Payroll{Compute,Posting,Emit}Provider`),
     - the C5-shipped pieces: wage-types catalog, payroll-extension
       chart (PCG 641 / 645 / 421 / 431 / 437 / 4421 / 4282 series),
       Silae + Sage CSV compute providers, Cegid API skeleton, FR
       posting builder, DSN structure helpers + emit provider,
       termination helper.

   ## Quickstart

   ```
   (require '[kontor.core :as kontor]
            '[kontor.hr.core :as hr]
            '[kontor.l10n-fr.chart :as fr-chart]
            '[kontor.payroll-fr.core :as fr-payroll])

   (def conn (kontor/create-test-db))
   (hr/install! conn)
   (fr-chart/install! conn)              ; FR PCG base chart (EUR commodity)
   (fr-payroll/install! conn)            ; FR payroll chart extension
   ```

   ## License posture (CLAUDE.md + ADR-001 + ADR-005 + ADR-071 + ADR-075 + ADR-079)

   - DSN / NEODES format spec is a public interop standard published
     by net-entreprises.fr; algorithm sketches re-derived from the
     Cahier Technique. No vendor source has been lifted.
   - Silae / Sage / Cegid CSV column schemas are described from
     public vendor documentation.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No proprietary pay-element catalog bundled — consumer supplies
     the engine→kontor kind mapping via `:pay-element-codes`.
   - No per-CCN (Convention Collective Nationale) rate tables bundled.
   - PCG account numbers are factual data not subject to copyright.
   - Algorithmic specs (SIREN Luhn check, TVA intra check arithmetic,
     PASS thresholds — engine-side) are facts.

   See also: doc/decisions.md ADR-079; doc/research/79 §5.3."
  (:require [kontor.payroll-fr.chart :as chart]))

(defn install!
  "Idempotent install of the payroll-fr extension. Layers on top of
   `kontor.core/install-schema!` + `kontor.hr.core/install!` +
   `kontor.l10n-fr.chart/install!`.

   Pass `{:tags-only? true}` to skip the starter chart and only
   register the :account-tag entities (useful when the consumer has
   their own chart and only needs the tag vocabulary)."
  ([conn] (install! conn {}))
  ([conn {:keys [tags-only?]}]
   (if tags-only?
     (chart/install-tags! conn)
     (chart/install! conn))))

;; Re-export public surface for convenience. Each individual namespace
;; is also importable directly.

(defn -load-symbols
  "Eagerly resolve API namespaces so :as imports in user code
   `(:require [kontor.payroll-fr.core])` get the right transitive
   loads."
  []
  (require 'kontor.payroll-fr.wage-types
           'kontor.payroll-fr.compute
           'kontor.payroll-fr.posting-builder
           'kontor.payroll-fr.dsn
           'kontor.payroll-fr.emit))

(-load-symbols)
