(ns kontor.payroll-mx.core
  "kontor-payroll-mx — Mexican CFDI Nómina v1.2 payroll adapter (ADR-082).

   This module composes three substrate layers:

     1. **Compute provider** — protocol `PayrollComputeProvider` —
        ingests a vendor file (CONTPAQi Nóminas XLSX/CSV, Aspel NOI
        CSV, Microsip, NominasOnline) and returns
        a vector of `:payroll-facts` per period: the canonical
        wage-type rows (sueldo, aguinaldo, ISR retenido, IMSS
        trabajador, …) with amounts in MXN. The kernel does NOT
        compute payroll — the consumer's payroll engine does, and
        we record + post + clear.

     2. **Posting builder** — `posting-builder/build-tx-data` —
        translates `:payroll-facts` into a balanced
        `kontor.posting/build-transaction` payload routed through
        the SAT Código Agrupador-keyed account map. This is the
        GL recognition (Dr expense / Cr clearing).

     3. **Emit provider** — `MxCfdiNominaEmitProvider` — assembles
        the CFDI Nómina v1.2 XML envelope from
        `:payroll-facts` + employer/employee identity. The XML is
        UNSIGNED — a PAC (Proveedor Autorizado de Certificación)
        stamps it with TFD (TimbreFiscalDigital) and returns a UUID
        which the consumer records on
        `:audit-doc` (category `:payroll-filing`, language `:es-mx`).

   ## Discipline (per task instructions + ADR-068 + ADR-082)

   - No bundled IMSS / INFONAVIT / ISR rate tables — the consumer's
     payroll engine has them, encoded in vendor-specific files.
   - No PAC credentials — PAC submission is a partner concern.
   - Per `:audit-doc/category :payroll-filing` and
     `:audit-doc/language :es-mx`.
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
;; PayrollComputeProvider protocol
;;
;; A compute provider parses a vendor-payroll export into the
;; canonical :payroll-facts map. The provider is stateless — its
;; only state is the configured vendor codes-to-wage-types mapping.
;; ============================================================================

(defprotocol PayrollComputeProvider
  "Parse a vendor-payroll export into canonical `:payroll-facts`."
  (vendor-id [this]
    "Short id for the underlying vendor, e.g. :contpaqi-nominas, :aspel-noi.")
  (parse-period [this source]
    "Parse one period's export from `source` (a string or a reader).
     Returns the :payroll-facts shape (see `make-payroll-facts`)."))

;; ============================================================================
;; PayrollEmitProvider protocol
;;
;; An emit provider serializes :payroll-facts to a country-specific
;; clearance-shape XML/JSON. For MX this is the CFDI Nómina v1.2 XML.
;; ============================================================================

(defprotocol PayrollEmitProvider
  "Serialize `:payroll-facts` to a clearance-shape string + metadata.
   The emit is UNSIGNED — caller / PAC adds the cryptographic
   stamp."
  (emit-format [this]
    "Returns a keyword identifying the emit format, e.g.
     :mx/cfdi-nomina-1.2.")
  (emit-payroll [this facts opts]
    "Returns `{:xml <string> :audit-doc/category :payroll-filing
                :audit-doc/language :es-mx :audit-doc/type
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
     :period/start       — #inst start of pay period
     :period/end         — #inst end of pay period
     :period/payment-date — #inst when wages are actually paid
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
  (when-not (:period/start m)
    (throw (ex-info ":period/start required on :payroll-facts" {:input m})))
  (when-not (:period/end m)
    (throw (ex-info ":period/end required on :payroll-facts" {:input m})))
  (when-not (vector? (:wage-types m))
    (throw (ex-info ":wage-types must be a vector" {:input m})))
  (assoc m :payroll-facts/version 1))

;; ============================================================================
;; Local resolution helpers
;; ============================================================================

(defn account-by-codigo-agrupador
  "Resolve the kontor :account eid by its SAT Código Agrupador (the
   `:account/code` we store on l10n-mx accounts — '601.01', '601.02',
   etc). Returns nil if not found."
  [db codigo-agrupador]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :account/code ?code]]
       db codigo-agrupador))
