(ns kontor.payroll-mx.core
  "kontor-payroll-mx — Mexican CFDI Nómina v1.2 payroll adapter (ADR-082).

   This module composes three substrate layers:

     1. **Engine provider** — protocol `MxEngineProvider` —
        ingests a vendor file (CONTPAQi Nóminas XLSX/CSV, Aspel NOI
        CSV, Microsip, NominasOnline) and returns
        a vector of `:payroll-facts` per period: the canonical
        wage-type rows (sueldo, aguinaldo, ISR retenido, IMSS
        trabajador, …) with amounts in MXN. The kernel does NOT
        compute payroll — the consumer's payroll engine does, and
        we record + post + clear.

        This is the **MX-private engine-ingest seam** (file → MX
        rows). Distinct from the kernel's
        `kontor.payroll-provider/PayrollComputeProvider`, which is
        the cross-jurisdictional substrate protocol satisfied by the
        bridge layer in `kontor.payroll-mx.adapter`.

     2. **Posting builder** — `posting-builder/build-tx-data` —
        translates `:payroll-facts` into a balanced
        `kontor.posting/build-transaction` payload routed through
        the SAT Código Agrupador-keyed account map. This is the
        GL recognition (Dr expense / Cr clearing).

     3. **CFDI emitter** — `MxCfdiEmitter` (impl
        `MxCfdiNominaEmitProvider`) — assembles the CFDI Nómina v1.2
        XML envelope from `:payroll-facts` + employer/employee
        identity. The XML is UNSIGNED — a PAC (Proveedor Autorizado
        de Certificación) stamps it with TFD (TimbreFiscalDigital)
        and returns a UUID which the consumer records on
        `:audit-doc` (category `:payroll-filing`, language `:es-mx`).

        The cross-jurisdictional kernel emit seam is
        `kontor.payroll-provider/PayrollEmitProvider`, satisfied by
        the bridge layer in `kontor.payroll-mx.adapter` which
        delegates to a wrapped `MxCfdiEmitter`.

   ## Discipline (per task instructions + ADR-068 + ADR-082)

   - No bundled IMSS / INFONAVIT / ISR rate tables — the consumer's
     payroll engine has them, encoded in vendor-specific files.
   - No PAC credentials — PAC submission is a partner concern.
   - Per `:kontor.audit-doc/category :payroll-filing` and
     `:kontor.audit-doc/language :es-mx`.
   - BigDecimal HALF-EVEN throughout (Money discipline).
   - `kontor.process` ADR-067 composition: `import-period!` is one
     pure tx (the GL post + the audit-doc + the status row).

   ## Scope for v1.0 (this module)

   - CFDI Nómina v1.2 XML emit (the load-bearing clearance shape).
   - GL posting from a `:payroll-facts` vector.
   - Aguinaldo + prima-vacacional accrual primitives.
   - CONTPAQi Nóminas + Aspel NOI compute providers (CSV; the
     vendor-XLSX format is documented in resources/fixtures).

   ## Out of scope (documented for v1.1)

   - SUA (Sistema Único de Autodeterminación) — IMSS / INFONAVIT /
     RCV monthly remittance file. Format is fixed-width binary;
     research note pending for v1.1 plan.
   - PTU (Participación de Trabajadores en Utilidades) — 10% of
     taxable profit. Year-end accrual; depends on corporate ISR
     base. Documented as a roadmap item.
   - DIOT (Declaración Informativa de Operaciones con Terceros) —
     vendor reporting; lives in `kontor.l10n-mx`, not here."
  (:require [datahike.api :as d]))

;; ============================================================================
;; MxEngineProvider protocol — MX-private engine-CSV ingestion seam
;;
;; Distinct from the kernel's
;; kontor.payroll-provider/PayrollComputeProvider. An MxEngineProvider
;; parses a vendor-payroll export into the MX-shaped :payroll-facts
;; map (per-employee per-wage-type rows). The bridge layer in
;; kontor.payroll-mx.adapter wraps an MxEngineProvider behind a
;; kernel-protocol PayrollComputeProvider so run-payroll! can drive
;; it like the DE / US / CA / FR / AU / BR / IN / JP / CN adapters.
;;
;; The provider is stateless — its only state is the configured
;; vendor codes-to-wage-types mapping.
;; ============================================================================

(defprotocol MxEngineProvider
  "Parse a vendor-payroll export into MX-shaped `:payroll-facts`.

   This is the MX-private engine-ingest seam. The kernel-substrate
   PayrollComputeProvider counterpart is the bridge defrecord in
   `kontor.payroll-mx.adapter` which wraps any MxEngineProvider."
  (vendor-id [this]
    "Short id for the underlying vendor, e.g. :contpaqi-nominas, :aspel-noi.")
  (parse-period [this source]
    "Parse one period's export from `source` (a string or a reader).
     Returns the :payroll-facts shape (see `make-payroll-facts`)."))

;; ============================================================================
;; MxCfdiEmitter protocol — MX-private CFDI Nómina XML emit seam
;;
;; An MxCfdiEmitter serializes :payroll-facts to the MX-specific
;; CFDI Nómina v1.2 XML envelope (per Anexo 20). The kernel's
;; cross-jurisdictional PayrollEmitProvider counterpart is the bridge
;; defrecord in `kontor.payroll-mx.adapter` which wraps any
;; MxCfdiEmitter + projects its result to the canonical
;; :audit-doc tx-data shape that run-payroll! expects.
;; ============================================================================

(defprotocol MxCfdiEmitter
  "Serialize MX `:payroll-facts` to the CFDI Nómina 1.2 XML envelope.
   The emit is UNSIGNED — caller / PAC adds the cryptographic
   stamp.

   This is the MX-private CFDI-XML seam. The kernel-substrate
   PayrollEmitProvider counterpart is the bridge defrecord in
   `kontor.payroll-mx.adapter` which wraps any MxCfdiEmitter."
  (emit-format [this]
    "Returns a keyword identifying the emit format, e.g.
     :mx/cfdi-nomina-1.2.")
  (emit-payroll [this facts opts]
    "Returns `{:xml <string> :kontor.audit-doc/category :payroll-filing
                :kontor.audit-doc/language :es-mx :kontor.audit-doc/type
                :payroll-cfdi-xml :emit-format <keyword>}`.
     `opts` carries the employer + period scope; `facts` is one
     period's :payroll-facts vector."))

;; ============================================================================
;; :payroll-facts shape — the canonical period-level wage record
;; ============================================================================

(defn make-payroll-facts
  "Construct the canonical `:payroll-facts` map for one (employee,
   period) record. All amounts are MXN BigDecimals.

   Required:
     :employee/rfc       — 13-char RFC
     :employee/curp      — 18-char CURP
     :employee/code      — employer-internal employee id
     :kontor.period/start       — #inst start of pay period
     :kontor.period/end         — #inst end of pay period
     :kontor.period/payment-date — #inst when wages are actually paid
     :wage-types         — vector of {:wage-type :sueldo
                                       :amount 12345.67M
                                       :commodity \"MXN\"}

   Optional:
     :employee/name        — human-readable name (CFDI Nómina req)
     :employee/nss         — Número de Seguridad Social (IMSS 11-digit)
     :employee/contract-type — :base | :temporal | :sindicalizado
     :employer/rfc         — defaults from emit opts
     :employer/registro-patronal — IMSS employer registration
     :hours-worked         — total period hours (informational)

   `:wage-type` is one of the canonical MX wage-types in
   `kontor.payroll-mx.wage-types/known-codes`. The provider may
   carry vendor-specific extras under `:vendor-extras` for
   round-trip preservation."
  [m]
  (when-not (:employee/rfc m)
    (throw (ex-info ":employee/rfc required on :payroll-facts" {:input m})))
  (when-not (:kontor.period/start m)
    (throw (ex-info ":kontor.period/start required on :payroll-facts" {:input m})))
  (when-not (:kontor.period/end m)
    (throw (ex-info ":kontor.period/end required on :payroll-facts" {:input m})))
  (when-not (vector? (:wage-types m))
    (throw (ex-info ":wage-types must be a vector" {:input m})))
  (assoc m :payroll-facts/version 1))

;; ============================================================================
;; Local resolution helpers
;; ============================================================================

(defn account-by-codigo-agrupador
  "Resolve the kontor :account eid by its SAT Código Agrupador (the
   `:kontor.account/code` we store on l10n-mx accounts — '601.01', '601.02',
   etc). Returns nil if not found."
  [db codigo-agrupador]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :kontor.account/code ?code]]
       db codigo-agrupador))
