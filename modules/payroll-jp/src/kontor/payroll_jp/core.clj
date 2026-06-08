(ns kontor.payroll-jp.core
  "kontor-payroll-jp — JP payroll adapter (Stage R C10, ADR-084).

   This namespace is the consumer-facing entry point. Composes:
     - the existing kontor.l10n-jp base chart + identifiers (法人番号
       + 適格請求書発行事業者登録番号 / QIS T-number) + consumption
       tax (消費税) + invoice + closing,
     - the kontor-hr substrate (`kontor.hr.payroll/run-payroll!`,
       `kontor.provider.payroll-provider/Payroll{Compute,Posting,Emit}Provider`),
     - the C10-shipped pieces: wage-types catalog, payroll-extension
       chart, freee + Money Forward + Yayoi CSV compute providers,
       posting builder, 賞与引当金 + 4-bucket SI accrual helpers,
       源泉徴収票 (Gensen Choshu Hyo) year-end aggregator, JP-payroll
       emit provider, and the My Number attestation audit-doc helper.

   ## Quickstart

   ```
   (require '[kontor.core :as kontor]
            '[kontor.hr.core :as hr]
            '[kontor.l10n-jp.chart :as jp-chart]
            '[kontor.payroll-jp.core :as jp-payroll])

   (def conn (kontor/create-test-db))
   (hr/install! conn)
   (jp-chart/install! conn)            ; JP base chart (JPY commodity)
   (jp-payroll/install! conn)          ; JP payroll chart extension
   ```

   ## License posture (CLAUDE.md + ADR-001 + ADR-005 + ADR-071 + ADR-084)

   - NTA + Nenkin Kiko + freee / MF / Yayoi support docs are public;
     no proprietary code has been lifted. CSV column shapes are
     described from public vendor support articles.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No proprietary pay-element catalog bundled — consumer supplies
     the engine→kontor kind mapping via `:pay-element-codes`.
   - No per-prefecture 健保 (Kenpo) rate tables bundled — the
     engine is authoritative for the math.
   - Algorithmic specs (Corporate Number check digit, Gensen box
     meanings, 賞与引当金 accrual formula) are factual / not
     copyrightable.

   ## My Number (個人番号) discipline (ADR-084 §1)

   kontor NEVER stores the My Number value. The value lives in the
   consumer's privileged store, encrypted at rest and access-gated
   by the consumer's auth layer. kontor records ONLY the attestation
   metadata via
   `kontor.payroll-jp.emit/record-my-number-attestation-tx-data`,
   producing an `:audit-doc` row with:

     :kontor.audit-doc/category   :hr-personnel
     :kontor.audit-doc/privilege  :pii-sensitive
     :kontor.audit-doc/language   :ja

   Downstream consumers gate access in their own auth layer.
   Retention follows kontor.compliance.retention (ADR-050) keyed on
   `:kontor.retention-policy/category :hr-personnel`.

   See also: doc/decisions.md ADR-084, doc/research/79 §5.3 (C10
   plan), doc/research/82-86 (Stage R substrate)."
  (:require [kontor.payroll-jp.chart :as chart]))

(defn install!
  "Idempotent install of the payroll-jp extension. Layers on top of
   `kontor.core/install-schema!` + `kontor.hr.core/install!` +
   `kontor.l10n-jp.chart/install!`.

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
   `(:require [kontor.payroll-jp.core])` get the right transitive
   loads."
  []
  (require 'kontor.payroll-jp.wage-types
           'kontor.payroll-jp.compute
           'kontor.payroll-jp.posting-builder
           'kontor.payroll-jp.accrual
           'kontor.payroll-jp.gensen
           'kontor.payroll-jp.emit))

(-load-symbols)
