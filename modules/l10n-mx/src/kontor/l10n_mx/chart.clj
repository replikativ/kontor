(ns kontor.l10n-mx.chart
  "Mexican chart-of-accounts loader — SAT Código Agrupador starter.

   The Servicio de Administración Tributaria (SAT) requires every
   Mexican filing entity to map their chart-of-accounts to the
   official **Código Agrupador del SAT** taxonomy (Anexo 24 of the
   Resolución Miscelánea Fiscal) for the monthly **Contabilidad
   Electrónica** filing. The Código Agrupador uses a dot-separated
   3-digit group + 3-digit subaccount + 3-digit sub-subaccount
   convention (e.g. `208.01.001`).

   This module ships a ~80-account starter chart aligned with the
   SAT taxonomy:

     100/110  Activos circulantes + fijos
     119      IVA acreditable (input ITC) — split cobrado / no cobrado
     200      Pasivos a corto plazo
     208      IVA trasladado (output) — split cobrado / no cobrado
     209/216  IEPS (output / input)
     300      Pasivos a largo plazo
     301–306  Capital contable
     401–405  Ingresos
     501      Costos
     601      Gastos

   ## Cash-basis IVA (the critical MX-specific)

   Mexico recognises IVA on a **cash basis** (Ley del IVA Art. 1-B).
   The supplier owes output IVA to SAT only when payment is *received*
   from the customer; until then the IVA sits in a holding account
   (`208.02.xxx`, \"no cobrado\"). Symmetrically, input IVA credit
   becomes acreditable only when payment goes *out* (`119.01.xxx`,
   \"pagado\"); pending payment it sits in `119.02.xxx`.

   The chart provides the four-way structure; the invoice posting
   builder (`./invoice.clj`) routes the issuance side to the
   `no cobrado` / `pendiente` accounts. The payment-recognition
   flow that transfers `208.02 → 208.01` (or `119.02 → 119.01`) on
   settlement is a separate process — out of scope for the
   chart loader.

   ## Tag conventions

   Each IVA / IEPS / retención account carries a `:mx-dpi-*` tag so
   the DPI return aggregator (`./returns.clj`) can pull per-line
   totals without hard-coded code lookups.

   ## Sources (public, non-copyrightable)

     - Anexo 24 RMF (SAT) — Código Agrupador del SAT
     - Ley del IVA, Art. 1-B (cash-basis recognition)
     - Ley del IEPS (federal excise on tobacco/alcohol/fuels/sugar)
     - Ley del ISR, Art. 11 (fiscal-year = calendar year)

   Idempotent: `:kontor.account/path` is `:db.unique/identity` in the kernel
   schema, so re-installing replaces values without duplication. Tags
   materialise as `:kontor.account-tag/*` entities (one per distinct keyword)
   and link via `:kontor.account/tags`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [kontor.account :as kacct]))

;; ============================================================================
;; Resource loading
;; ============================================================================

(defn load-chart
  "Read the MX chart EDN. Returns a vector of account spec maps."
  []
  (-> "kontor/l10n_mx/chart.edn"
      io/resource
      slurp
      edn/read-string))

;; ============================================================================
;; Tag materialization
;; ============================================================================

(defn- distinct-tags
  "Every tag keyword referenced anywhere in the chart, deduplicated."
  [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx-data
  [tags]
  (mapv (fn [tag]
          {:kontor.account-tag/name (name tag)
           :kontor.account-tag/country-code "MX"
           :kontor.account-tag/applicability :account})
        tags))

;; ============================================================================
;; Commodity (MXN)
;; ============================================================================

(defn- ensure-mxn
  "Idempotent MXN commodity. Peso Mexicano, precision 2."
  []
  {:kontor.commodity/symbol "MXN"
   :kontor.commodity/name "Peso Mexicano"
   :kontor.commodity/precision 2
   :kontor.commodity/iso-4217 "MXN"})

;; ============================================================================
;; Account materialization
;; ============================================================================

(defn- account-tx-entry
  "Build the kernel-side account entity-map for one chart entry.
   `:kontor.account/path` is the unique identity; tags become refs via the
   materialized `:account-tag` entities."
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:kontor.account/path        path
           :kontor.account/code        code
           :kontor.account/name        name
           :kontor.account/type        type
           :kontor.account/active      true
           :kontor.account/commodity   [:kontor.commodity/symbol "MXN"]
           :kontor.account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :kontor.account/tags
           (mapv (fn [t] [:kontor.account-tag/name (clojure.core/name t)]) tags))))

;; ============================================================================
;; Public installer
;; ============================================================================

(defn install!
  "Transact the MX chart + the per-tag `:account-tag` entities into
   `conn`. Idempotent. Returns the resulting tx-report from the
   final transact."
  ([conn] (install! conn (load-chart)))
  ([conn chart]
   ;; 1. MXN commodity
   (d/transact conn [(ensure-mxn)])
   ;; 2. Tags first so account-tx refs resolve.
   (d/transact conn (tag-tx-data (distinct-tags chart)))
   ;; 3. Accounts
   (d/transact conn (mapv account-tx-entry chart))))

;; ============================================================================
;; Convenience: key account-code constants
;;
;; The invoice / closing / returns modules pin a small set of codes
;; by default. Centralising here so the chart is the single source of
;; truth.
;; ============================================================================

(def ^:const ar-code                "105.01.001") ; Clientes nacionales
(def ^:const ar-export-code         "105.02.001") ; Clientes extranjeros
(def ^:const ap-code                "201.01.001") ; Proveedores nacionales
(def ^:const cash-code              "101.01.001") ; Caja
(def ^:const bank-code              "102.01.001") ; Bancos nacionales

(def ^:const sales-domestic-16-code "401.01.001") ; Ingresos 16%
(def ^:const sales-domestic-8-code  "401.01.002") ; Ingresos 8% frontera
(def ^:const sales-domestic-0-code  "401.01.003") ; Ingresos 0%
(def ^:const sales-exempt-code      "401.01.004") ; Ingresos exentos
(def ^:const sales-export-code      "401.02.001") ; Exportación

;; Output IVA — cash-basis split.
;; The INVOICE-ISSUANCE side posts to the "no cobrado" leg; the
;; payment-recognition flow (out of scope here) transfers to "cobrado".
(def ^:const iva-trasladado-cobrado-16-code   "208.01.001")
(def ^:const iva-trasladado-cobrado-8-code    "208.01.002")
(def ^:const iva-trasladado-cobrado-0-code    "208.01.003")
(def ^:const iva-trasladado-no-cobrado-16-code "208.02.001")
(def ^:const iva-trasladado-no-cobrado-8-code  "208.02.002")
(def ^:const iva-trasladado-no-cobrado-0-code  "208.02.003")

;; Input IVA — cash-basis split.
(def ^:const iva-acreditable-pagado-16-code     "119.01.001")
(def ^:const iva-acreditable-pagado-8-code      "119.01.002")
(def ^:const iva-acreditable-pagado-0-code      "119.01.003")
(def ^:const iva-acreditable-pendiente-16-code  "119.02.001")
(def ^:const iva-acreditable-pendiente-8-code   "119.02.002")

;; IEPS
(def ^:const ieps-trasladado-cobrado-code    "209.01.001")
(def ^:const ieps-trasladado-no-cobrado-code "209.02.001")
(def ^:const ieps-acreditable-code           "216.01.001")

;; Retenciones (withholdings).
(def ^:const isr-retenido-pagar-honorarios-code   "206.01.001")
(def ^:const isr-retenido-pagar-arrendamiento-code "206.01.002")
(def ^:const iva-retenido-pagar-code              "206.02.001")
(def ^:const isr-retenido-cobrar-code             "120.01.001")
(def ^:const iva-retenido-cobrar-code             "120.02.001")

;; Equity / income-tax.
(def ^:const isr-por-pagar-code                "205.01.001")
(def ^:const utilidades-ejercicio-code         "304.01.001") ; Utilidad del Ejercicio
(def ^:const utilidades-retenidas-code         "305.01.001") ; Utilidades Retenidas

;; ============================================================================
;; Lookups
;; ============================================================================

(defn account-by-code
  "Resolve `code` to an account entity-id against `db`, or nil."
  [db code]
  (kacct/resolve-code db code {:context "MX chart"}))
