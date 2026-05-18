(ns kontor.payroll-br.core
  "kontor-payroll-br — BR eSocial payroll adapter (Stage R C7, ADR-081).

   This namespace is the consumer-facing entry point. Composes:
     - the existing kontor.l10n-br base chart + identifiers
       (CNPJ/CPF validators) + NF-e / SPED Contábil emitters,
     - the kontor-hr substrate (`kontor.hr.payroll/run-payroll!`,
       `kontor.payroll-provider/Payroll{Compute,Posting,Emit}Provider`),
     - the C7-shipped pieces: wage-types catalog, payroll-extension
       chart, three engine adapters (RH Sistemas / Senior / Pluxee),
       posting builder, three CPC 33 accruals (férias, 13º, multa
       rescisória 40% FGTS), and eSocial event builders for S-1000,
       S-1005, S-1010, S-1020, S-1200, S-1210, S-1299, S-2200, S-2299,
       S-2300, S-2399.

   ## Quickstart

   ```
   (require '[kontor.core :as kontor]
            '[kontor.hr.core :as hr]
            '[kontor.l10n-br.chart :as br-chart]
            '[kontor.payroll-br.core :as br-payroll])

   (def conn (kontor/create-test-db))
   (hr/install! conn)
   (br-chart/install! conn)              ; BR base chart (BRL commodity)
   (br-payroll/install! conn)            ; BR payroll chart extension
   ```

   ## License posture (CLAUDE.md + ADR-001 + ADR-005 + ADR-075 + ADR-081)

   - gov.br/esocial XSDs + leiaute manuals are public regulator
     publications; we read the schemas and emit independent XML.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No proprietary rubrica catalog bundled — consumer supplies
     the engine→kontor kind mapping via `:rubrica-codes`.
   - No bundled INSS / IRRF / FGTS rate tables (these are regulator
     policy + change frequently; the engine is authoritative).
   - Algorithmic specs (CNPJ/CPF mod-11, eSocial event ID format,
     CPC 33 accrual formulae, 40% multa rescisória rate) are facts
     not subject to copyright.

   See also: doc/research/79 §5.3 + ADR-081."
  (:require [datahike.api :as d]
            [kontor.payroll-br.chart :as chart]))

;; ============================================================================
;; Module-local schema extensions (mirror payroll-de-datev/core.clj)
;; ============================================================================
;;
;; The BR module attaches a few extra attrs to the kernel/audit-doc
;; namespace so the EmitProvider can carry the eSocial XML payload +
;; reconciliation metadata as proper attrs.

(def extra-schema
  [{:db/ident       :audit-doc/inline-payload
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional inline payload — eSocial event XML
                     contents stored next to the audit-doc record.
                     Consumers prefer :audit-doc/storage-uri for large
                     files (> ~10 KB). Shared with the DE-DATEV-LODAS
                     adapter (note 82 §8.2)."}

   {:db/ident       :audit-doc/payroll-period
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :pay-period — the period this emit-payload
                     covers."}

   {:db/ident       :audit-doc/payroll-entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :entity — the employer this emit-payload
                     covers."}])

(defn install!
  "Idempotent install of the payroll-br extension. Layers on top of
   `kontor.core/install-schema!` + `kontor.hr.core/install!` +
   `kontor.l10n-br.chart/install!`.

   Pass `{:tags-only? true}` to skip the starter chart and only
   register the :account-tag entities (useful when the consumer has
   their own chart and only needs the tag vocabulary)."
  ([conn] (install! conn {}))
  ([conn {:keys [tags-only?]}]
   (d/transact conn extra-schema)
   (if tags-only?
     (chart/install-tags! conn)
     (chart/install! conn))))

;; Re-export public surface for convenience. Each individual namespace
;; is also importable directly.

(defn -load-symbols
  "Eagerly resolve API namespaces so :as imports in user code get the
   right transitive loads."
  []
  (require 'kontor.payroll-br.wage-types
           'kontor.payroll-br.compute
           'kontor.payroll-br.posting-builder
           'kontor.payroll-br.accrual
           'kontor.payroll-br.esocial
           'kontor.payroll-br.emit))

(-load-symbols)
