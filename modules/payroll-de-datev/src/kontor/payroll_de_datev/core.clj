(ns kontor.payroll-de-datev.core
  "kontor-payroll-de-datev — the DE DATEV-LODAS payroll adapter for
   `kontor-hr` (ADR-076 / research note 82).

   Ships:
     - `DatevLodasComputeProvider` — parses an EXTF Buchungsbeleg
       (Lohn-Buchungsbeleg, LODAS Report 80) into PayrollFacts.
     - `DatevLodasPostingBuilder`  — maps :kontor.compensation-component/kind
       → SKR04 (default) / SKR03 wage accounts; emits Bruttomethode
       GL postings (note 82 §4.2).
     - `DatevLodasEmitProvider`    — writes the LODAS Importdatei
       (4-section, ISO-8859-1, CR-LF, semicolon-delimited) for
       per-period variable-input handoff.
     - HGB §249 simplified Urlaubsrückstellung helper (note 82 §5.1).

   Per ADR-005 / ADR-071 / ADR-075: no bundled vendor API keys, no
   bundled wage-type catalog (consumer-supplied per
   `kontor.payroll-de-datev.wage-types/validate-catalog`), no
   bundled engine output schema. We consume DATEV's public
   format specification (LODAS Schnittstellenhandbuch + EXTF v21).

   Install order:
     1. kontor.core/install-schema!
     2. kontor.hr.core/install!
     3. kontor.l10n-de.chart/install!  (SKR04; SKR03 if you prefer)
     4. kontor.payroll-de-datev.core/install!  (this — registers
                                                 schema extension)

   Two consumer-facing constructors:

     (compute/make-provider {:coa :skr04
                              :employment-pnr->eid {...}})

     (posting-builder/make-builder
        {:catalog validated-catalog
         :commodity [:kontor.commodity/symbol \"EUR\"]})

     (emit/make-provider
        {:catalog validated-catalog
         :allgemein {:berater-nr \"1234\" :mandant-nr \"99999\"
                      :stammdaten-gueltig-ab #inst \"2026-05-01\"}
         :pay-period-date #inst \"2026-05-01\"
         :pay-period-code \"DE-2026-05\"})

   These compose into `kontor.hr.payroll/run-payroll!` per the
   ADR-075 orchestrator contract."
  (:require [datahike.api :as d]
            [kontor.payroll-de-datev.compute :as compute]
            [kontor.payroll-de-datev.emit :as emit]
            [kontor.payroll-de-datev.posting-builder :as posting-builder]))

;; ============================================================================
;; Module-local schema extensions
;; ============================================================================
;;
;; The C2 module attaches a few extra attrs to the kernel/audit-doc
;; namespace so the EmitProvider can carry the LODAS payload + the
;; reconciliation metadata as proper attrs (rather than opaque blobs):
;;
;;   :kontor.audit-doc/inline-payload     — the LODAS Importdatei contents
;;                                    (string); short LODAS files
;;                                    inline (< ~10KB), larger ones
;;                                    use :kontor.audit-doc/storage-uri only.
;;   :kontor.audit-doc/payroll-period     — ref to :pay-period
;;   :kontor.audit-doc/payroll-entity     — ref to :entity
;;   :kontor.audit-doc/unmapped-count     — long, # of unmapped wage-types
;;                                    seen during emit (consumer routes
;;                                    to manual review when > 0).
;;
;; These follow the existing :kontor.audit-doc/* convention; under ADR-002
;; the audit-doc namespace is shared between kernel + companions.

(def extra-schema
  [{:db/ident       :kontor.audit-doc/inline-payload
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional inline payload — short LODAS Importdatei
                     contents stored next to the audit-doc record.
                     Consumers prefer :kontor.audit-doc/storage-uri for large
                     files (> ~10 KB)."}

   {:db/ident       :kontor.audit-doc/payroll-period
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :pay-period — the period this emit-payload
                     covers (note 82 §8.2)."}

   {:db/ident       :kontor.audit-doc/payroll-entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :entity — the employer this emit-payload
                     covers."}

   {:db/ident       :kontor.audit-doc/unmapped-count
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Count of compensation-components dropped during
                     LODAS Bewegungsdaten emit because their (:kind,
                     :account-hint) did not match any wage-type in the
                     catalog. > 0 routes the run to manual review per
                     note 82 §6.3.5."}])

(defn install!
  "Install the module-local audit-doc attrs. Idempotent.

   kontor.hr.core/install! must already have run; kontor.l10n-de.chart/install!
   should also have run if you intend to use the default SKR04
   account-map via :kontor.account/code lookup-refs."
  [conn]
  (d/transact conn extra-schema))

;; ============================================================================
;; Re-exports — single import point for adapters
;; ============================================================================

(def make-compute-provider compute/make-provider)
(def make-posting-builder  posting-builder/make-builder)
(def make-emit-provider    emit/make-provider)
(def urlaubsrueckstellung-tx-data
  posting-builder/urlaubsrueckstellung-tx-data)
(def urlaubsrueckstellung-amount
  posting-builder/urlaubsrueckstellung-amount)
