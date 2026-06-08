(ns kontor.l10n-mx.returns
  "Mexican periodic-return aggregators — substrate-tier.

   Mexico's monthly indirect-tax filing landscape:

     - **DPI** (Declaración Provisional de Impuestos / Pago Definitivo
       de IVA — colloquially \"la DPI\") — the monthly self-assessed
       summary submitted to SAT, due by the **17th of the following
       month** (Art. 5-D Ley del IVA). Aggregates IVA cobrado (output
       collected) minus IVA acreditable pagado (input credit), plus
       retenciones receivable / payable, plus IEPS net.

     - **DIOT** (Declaración Informativa de Operaciones con Terceros)
       — informational, lists every transaction with each counter-
       party (vendor + customer aggregations). Due in parallel with
       the DPI. Per the brief, the DIOT XML envelope is out of scope
       at the substrate tier; the consumer projects per-RFC totals
       from posting + partner refs.

   ## Cash-basis filing

   The aggregators read from the **cobrado / pagado** accounts only:

     IVA output:    208.01.xxx  (cobrado)    — owed to SAT this period
     IVA input:     119.01.xxx  (pagado)     — recoverable this period
     IEPS output:   209.01.xxx  (cobrado)
     IEPS input:    216.01.xxx  (pagado)

   The \"no cobrado\" / \"pendiente\" holding accounts (208.02 / 119.02)
   are EXCLUDED from the DPI — those balances represent IVA that
   hasn't been cash-recognised yet. The payment-recognition flow
   (out of scope here) transfers balances from the holding to the
   recognised accounts.

   This is **the** structural difference vs an accrual-basis VAT
   filing (which would aggregate at invoice time regardless of
   payment): cash-basis filing reads from the cobrado / pagado
   leg of the chart's split.

   ## What this module deliberately does NOT do

   - **No SAT portal XML envelope.** The DPI is submitted through the
     SAT \"Mi Portal\" / \"Pago Referenciado\" line-by-line entry; the
     numbers go directly into the form, not as a structured filing
     document. Consumers paste the substrate-tier amounts into the
     portal — same pattern as the IN GSTR aggregators.

   - **No live SAT rate refresh.** When SAT bumps a rate (e.g. the
     border-zone decree expires + a new rate publishes), the chart's
     account-tags + the per-line :iva-rate inputs adjust.

   - **No DIOT XML.** The informational return is a separate filing
     emitter — substrate aggregator only.

   ## API shape

   Each generator function takes `conn` + a period-window opts map and
   returns a return-data map. All commodity values are `Money :MXN`.
   The return-data maps carry `:kontor.return/form`, `:kontor.return/period`, and
   the aggregated line totals + drill-down posting ids so a downstream
   auditor can follow each number back to contributing postings.

     (generate-dpi-return conn {:year 2026 :month 1})

   Period helpers:
     (period-bounds {:year 2026 :month 1}) → {:from ... :to ...}
     (dpi-due-date {:year 2026 :month 1})   → 17 February 2026"
  (:require [kontor.money :as money]
            [kontor.reporting.report :as report])
  (:import [java.util Calendar Date TimeZone]))

;; ============================================================================
;; Period bounds
;; ============================================================================

(defn- date-at
  ^Date [year month day]
  (let [cal (Calendar/getInstance (TimeZone/getTimeZone "UTC"))]
    (.clear cal)
    (.set cal year (dec month) day 0 0 0)
    (.set cal Calendar/MILLISECOND 0)
    (.getTime cal)))

(defn period-bounds
  "Compute the `[:from :to)` window for a Mexican filing period.

   Inputs (mutually exclusive):
     :year + :month   → monthly window (DPI / DIOT cadence)
     :year + :quarter → quarterly window (rare; some small-taxpayer
                         consolidations)
     :year            → annual window (ISR annual reconciliation)

   The window is half-open: `:from` inclusive, `:to` exclusive."
  [{:keys [year month quarter]}]
  (cond
    (and year month)
    (let [from (date-at year month 1)
          next-month (if (= month 12) 1 (inc month))
          next-year  (if (= month 12) (inc year) year)
          to (date-at next-year next-month 1)]
      {:from from :to to :kind :monthly :year year :month month})

    (and year quarter)
    (let [first-month (inc (* 3 (dec quarter)))
          last-month  (+ 2 first-month)
          from (date-at year first-month 1)
          next-month (if (= last-month 12) 1 (inc last-month))
          next-year  (if (= last-month 12) (inc year) year)
          to (date-at next-year next-month 1)]
      {:from from :to to :kind :quarterly :year year :quarter quarter})

    year
    {:from (date-at year 1 1) :to (date-at (inc year) 1 1)
     :kind :annual :year year}

    :else
    (throw (ex-info "period-bounds requires :year (+ optional :month / :quarter)"
                    {:input {:year year :month month :quarter quarter}}))))

(defn- resolve-window
  "Accept either an explicit `:from`/`:to` window or a `:year`/`:month`/
   `:quarter` shorthand. Returns the explicit `{:from :to ...}` map."
  [opts]
  (if (:from opts)
    (select-keys opts [:from :to])
    (period-bounds opts)))

;; ============================================================================
;; Filing-due-date helpers — informational only
;; ============================================================================

(defn dpi-due-date
  "Statutory DPI due date for the given monthly period: the **17th of
   the following month** (Art. 5-D Ley del IVA). Note: when the 17th
   falls on a weekend or federal holiday, SAT publishes the
   automatic-extension calendar each year (Resolución Miscelánea).
   This helper returns the nominal 17th — caller can apply the
   working-day correction if needed."
  [{:keys [year month]}]
  (let [next-month (if (= month 12) 1 (inc month))
        next-year  (if (= month 12) (inc year) year)]
    (date-at next-year next-month 17)))

;; ============================================================================
;; Tag-driven report shapes — DPI aggregation
;; ============================================================================

(def dpi-definition
  "DPI (Declaración Provisional de Impuestos) — monthly IVA + IEPS
   return summary.

   Aggregates the CASH-RECOGNISED side only:
     - IVA cobrado    (208.01.xxx, tagged :mx-dpi-iva-cobrado)
     - IVA acreditable pagado (119.01.xxx, :mx-dpi-iva-acreditable)
     - IEPS cobrado    (209.01.xxx, :mx-dpi-ieps-cobrado)
     - IEPS acreditable (216.01.xxx, :mx-dpi-ieps-acreditable)
     - Retenciones IVA payable / receivable
     - Retenciones ISR payable / receivable
     - Ingresos (revenue, for the gross-receipts disclosure line)

   The \"no cobrado\" holding accounts (208.02 / 119.02) are EXCLUDED
   — they represent IVA not yet cash-recognised. Per ADR-019 the
   chart-of-accounts is responsible for tagging; this report engine
   is purely declarative."
  {:report/name    "DPI — Declaración Provisional de Impuestos (mensual)"
   :report/country "MX"
   :report/lines
   [;; --- Output IVA (cobrado, by rate) ---
    {:line/code "iva-cobrado-16"
     :line/label "IVA trasladado cobrado — 16%"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-iva-cobrado-16]
                       :sign :inflow :commodity :MXN}}
    {:line/code "iva-cobrado-8"
     :line/label "IVA trasladado cobrado — 8% frontera"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-iva-cobrado-8]
                       :sign :inflow :commodity :MXN}}
    {:line/code "iva-cobrado-0"
     :line/label "IVA trasladado cobrado — 0%"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-iva-cobrado-0]
                       :sign :inflow :commodity :MXN}}
    {:line/code "iva-cobrado-total"
     :line/label "IVA trasladado cobrado — TOTAL (output VAT)"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-iva-cobrado]
                       :sign :inflow :commodity :MXN}}

    ;; --- Input IVA (acreditable pagado, by rate) ---
    {:line/code "iva-acreditable-16"
     :line/label "IVA acreditable pagado — 16%"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-iva-acreditable-16]
                       :sign :inflow :commodity :MXN}}
    {:line/code "iva-acreditable-8"
     :line/label "IVA acreditable pagado — 8% frontera"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-iva-acreditable-8]
                       :sign :inflow :commodity :MXN}}
    {:line/code "iva-acreditable-0"
     :line/label "IVA acreditable pagado — 0%"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-iva-acreditable-0]
                       :sign :inflow :commodity :MXN}}
    {:line/code "iva-acreditable-total"
     :line/label "IVA acreditable pagado — TOTAL (input ITC)"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-iva-acreditable]
                       :sign :inflow :commodity :MXN}}

    ;; --- IEPS ---
    {:line/code "ieps-cobrado"
     :line/label "IEPS trasladado cobrado"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-ieps-cobrado]
                       :sign :inflow :commodity :MXN}}
    {:line/code "ieps-acreditable"
     :line/label "IEPS acreditable pagado"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-ieps-acreditable]
                       :sign :inflow :commodity :MXN}}

    ;; --- Retenciones ---
    {:line/code "retencion-iva-pagar"
     :line/label "IVA retenido por pagar (buyer-side withholding)"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-iva-retenido-pagar]
                       :sign :inflow :commodity :MXN}}
    {:line/code "retencion-iva-cobrar"
     :line/label "IVA retenido por cobrar (supplier-side recovery)"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-iva-retenido-cobrar]
                       :sign :inflow :commodity :MXN}}
    {:line/code "retencion-isr-pagar"
     :line/label "ISR retenido por pagar (buyer-side withholding)"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-isr-retenido-pagar]
                       :sign :inflow :commodity :MXN}}
    {:line/code "retencion-isr-cobrar"
     :line/label "ISR retenido por cobrar (supplier-side recovery)"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-isr-retenido-cobrar]
                       :sign :inflow :commodity :MXN}}

    ;; --- Gross-receipts disclosure ---
    {:line/code "ingresos-total"
     :line/label "Ingresos totales del período (gross receipts)"
     :line/expression {:engine :tax-tags :tags [:mx-dpi-ingresos]
                       :sign :inflow :commodity :MXN}}]})

;; ============================================================================
;; Line-extraction helper
;; ============================================================================

(defn- lines->map
  "Re-key the kontor.reporting.report output (`:report/lines` vector) into a
   {line-code → Money} map. Easier for downstream computation."
  [computed]
  (into {}
        (map (fn [l] [(:line/code l) (:line/value l)]))
        (:report/lines computed)))

;; ============================================================================
;; DPI generator
;; ============================================================================

(defn generate-dpi-return
  "Compute the monthly DPI (Declaración Provisional de Impuestos).

   Aggregates cash-recognised IVA + IEPS + retenciones across the
   period. Note that only postings on the **cobrado / pagado**
   accounts contribute — the holding accounts (208.02 / 119.02) are
   excluded by tag selection (the holding accounts carry
   `:mx-dpi-iva-no-cobrado` / `:mx-dpi-iva-acreditable-pendiente`
   tags that this report deliberately ignores).

   Filing: due by the 17th of the following month per
   Art. 5-D Ley del IVA. SAT submits via the \"Mi Portal\" /
   \"Pago Referenciado\" web interface; this module produces the
   amounts the consumer types into the portal.

   Required:
     :from / :to            explicit window, OR
     :year + :month         shorthand for the monthly window

   Optional:
     :as-of-tx              bitemporal tx-snapshot (default now)
     :entity                multi-entity scope (ADR-031)

   Returns:
     {:kontor.return/form        \"DPI\"
      :kontor.return/period      {:from … :to … :kind :monthly …}
      :kontor.return/due-date    <Date>           — statutory filing date
      :kontor.return/lines       {:iva-cobrado-16 Money :iva-cobrado-8 Money
                            :iva-cobrado-0 Money :iva-cobrado-total Money
                            :iva-acreditable-* Money
                            :ieps-cobrado Money :ieps-acreditable Money
                            :retencion-* Money
                            :ingresos-total Money}
      :kontor.return/iva-net     Money — IVA cobrado − IVA acreditable
                                   (positive = payable, negative = saldo a favor)
      :kontor.return/ieps-net    Money — IEPS cobrado − IEPS acreditable
      :kontor.return/retencion-iva-net  Money — pagar − cobrar
      :kontor.return/retencion-isr-net  Money — pagar − cobrar
      :kontor.return/total-iva-payable  Money — iva-net + retencion-iva-net
                                   (what supplier remits to SAT this month)
      :report/lines       — drill-down per line (postings included)}"
  [conn opts]
  (let [window (resolve-window opts)
        report-opts (-> opts
                        (assoc :from (:from window) :to (:to window))
                        (dissoc :year :month :quarter))
        r (report/compute-report conn dpi-definition report-opts)
        line (lines->map r)
        zero (money/zero :MXN)
        get-z (fn [k] (or (get line k) zero))
        iva-out (get-z "iva-cobrado-total")
        iva-in  (get-z "iva-acreditable-total")
        ieps-out (get-z "ieps-cobrado")
        ieps-in  (get-z "ieps-acreditable")
        rt-iva-pagar  (get-z "retencion-iva-pagar")
        rt-iva-cobrar (get-z "retencion-iva-cobrar")
        rt-isr-pagar  (get-z "retencion-isr-pagar")
        rt-isr-cobrar (get-z "retencion-isr-cobrar")
        iva-net  (money/sub iva-out iva-in)
        ieps-net (money/sub ieps-out ieps-in)
        rt-iva-net (money/sub rt-iva-pagar rt-iva-cobrar)
        rt-isr-net (money/sub rt-isr-pagar rt-isr-cobrar)
        total-iva-payable (money/add iva-net rt-iva-net)
        due-date (when (and (= :monthly (:kind window))
                            (:year window) (:month window))
                   (dpi-due-date window))]
    (-> r
        (assoc :kontor.return/form     "DPI"
               :kontor.return/period   window
               :kontor.return/due-date due-date
               :kontor.return/lines    {:iva-cobrado-16        (get-z "iva-cobrado-16")
                                 :iva-cobrado-8         (get-z "iva-cobrado-8")
                                 :iva-cobrado-0         (get-z "iva-cobrado-0")
                                 :iva-cobrado-total     iva-out
                                 :iva-acreditable-16    (get-z "iva-acreditable-16")
                                 :iva-acreditable-8     (get-z "iva-acreditable-8")
                                 :iva-acreditable-0     (get-z "iva-acreditable-0")
                                 :iva-acreditable-total iva-in
                                 :ieps-cobrado          ieps-out
                                 :ieps-acreditable      ieps-in
                                 :retencion-iva-pagar   rt-iva-pagar
                                 :retencion-iva-cobrar  rt-iva-cobrar
                                 :retencion-isr-pagar   rt-isr-pagar
                                 :retencion-isr-cobrar  rt-isr-cobrar
                                 :ingresos-total        (get-z "ingresos-total")}
               :kontor.return/iva-net           iva-net
               :kontor.return/ieps-net          ieps-net
               :kontor.return/retencion-iva-net rt-iva-net
               :kontor.return/retencion-isr-net rt-isr-net
               :kontor.return/total-iva-payable total-iva-payable))))
