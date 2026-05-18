(ns kontor.payroll-ca.core
  "kontor-payroll-ca — CA-CRA payroll adapter (Stage R C4, ADR-078).

   This namespace is the consumer-facing entry point. Composes:
     - the existing kontor.l10n-ca base chart + identifiers + T4 / T619
       XML emitters,
     - the kontor-hr substrate (`kontor.hr.payroll/run-payroll!`,
       `kontor.payroll-provider/Payroll{Compute,Posting,Emit}Provider`),
     - the C4-shipped pieces: wage-types catalog, payroll-extension
       chart, Ceridian + ADP CSV compute providers, posting builder,
       PD7A remittance helper, T4 year-end aggregator, ROE termination
       audit-doc helper, bilingual support.

   ## Quickstart

   ```
   (require '[kontor.core :as kontor]
            '[kontor.hr.core :as hr]
            '[kontor.l10n-ca.chart :as ca-chart]
            '[kontor.payroll-ca.core :as ca-payroll])

   (def conn (kontor/create-test-db))
   (hr/install! conn)
   (ca-chart/install! conn)              ; CA base chart (CAD commodity)
   (ca-payroll/install! conn)            ; CA payroll chart extension
   ```

   ## License posture (CLAUDE.md + ADR-001 + ADR-005 + ADR-071 + ADR-078)

   - CRA T619 + T4 XSDs are public + ship in the repo under
     `modules/l10n-ca/test/resources/cra/`. No vendor source has
     been lifted.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No proprietary pay-element catalog bundled — consumer supplies
     the engine→kontor kind mapping via `:pay-element-codes`.
   - Algorithmic specs (Luhn check digit, T4 box meanings, PD7A
     totals, remitter-type schedules) are facts — not copyrightable.

   See also: doc/research/84-ca-cra-payroll-research-before.md."
  (:require [kontor.payroll-ca.chart :as chart]))

(defn install!
  "Idempotent install of the payroll-ca extension. Layers on top of
   `kontor.core/install-schema!` + `kontor.hr.core/install!` +
   `kontor.l10n-ca.chart/install!`.

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
   `(:require [kontor.payroll-ca.core])` get the right transitive
   loads."
  []
  (require 'kontor.payroll-ca.wage-types
           'kontor.payroll-ca.compute
           'kontor.payroll-ca.posting-builder
           'kontor.payroll-ca.t4-builder
           'kontor.payroll-ca.pd7a
           'kontor.payroll-ca.emit))

(-load-symbols)
