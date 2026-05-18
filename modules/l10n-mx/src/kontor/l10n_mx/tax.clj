(ns kontor.l10n-mx.tax
  "Mexican indirect-tax compute — IVA + IEPS + retenciones, as a
   callable function family.

   Mirrors the shape of `kontor.l10n-ca.tax`: a pure rate-table
   compute that the invoice posting builder (`./invoice.clj`)
   consumes, plus the symmetric filing-side aggregator that lives in
   `./returns.clj`.

   ## Cash-basis IVA (the critical MX-specific)

   **Mexico recognises IVA on a CASH basis** (Ley del IVA Art. 1-B):
   the supplier owes output IVA to SAT only when payment is *received*
   from the customer; until then it sits in a holding liability
   account (`208.02.xxx`, \"no cobrado\"). Symmetrically, input IVA
   credit is acreditable only when payment goes *out* (`119.01.xxx`,
   \"pagado\"); pending payment it sits in `119.02.xxx`.

   `compute-tax` here is **rate-only**: it returns the per-component
   IVA / IEPS / retención amounts a single line should produce, with
   no opinion about which side of the cobrado / no-cobrado split they
   land on. That routing is the **invoice builder's** concern — see
   `./invoice.clj` (issuance side → no-cobrado) and the payment-
   recognition flow (cobrado transfer at settlement, out of scope).

   ## IVA rates (Ley del IVA, current 2026)

     16%   — tasa general (general rate, applies countrywide except
             border-zone)
     8%    — región fronteriza (border-zone decree, 8 northern + 4
             southern border states per the Decreto de estímulos
             fiscales región fronteriza). The decree currently
             expires Dec 31, 2024 — rates here track the published
             text; users override if SAT re-publishes a new decree.
     0%    — alimentos básicos, medicinas, libros, exportaciones,
             aviones+barcos comerciales (Ley del IVA Art. 2-A)
     exento — renta habitacional, servicios médicos, educación
             (Art. 9 — no IVA, no credit upstream either)

   Border-zone resolution: caller passes `:region :border-norte`,
   `:border-sur`, or `:general` (default). Border classification is
   per-establishment per Art. 1 of the decree — caller resolves the
   actual zone before calling.

   ## IEPS (Impuesto Especial sobre Producción y Servicios)

   Federal excise on tobacco, alcohol, fuels, sugary drinks, junk
   food. Rates vary widely: 3% (tobacco add-on), 25% – 53% (alcohol),
   8% – 25% (junk food), specific peso-per-litre on fuels.

   Per the brief, **the IEPS rate-table for >5 product categories is
   out of scope here**: callers pass `:ieps-rate` directly on the
   line. We just plumb the multiplication + rounding.

   ## Retenciones (withholding at point of payment)

   Certain buyer categories (federal/state government, IMMEX
   companies, large taxpayers per the Padrón) withhold IVA and/or
   ISR from their suppliers and remit direct to SAT. The supplier's
   issued CFDI shows the withholding in a separate `<Retenciones>`
   block, and the supplier's own books carry the withheld amount as
   a *receivable* (offset against own ISR / IVA payable).

   Standard fixed rates (Ley del IVA Art. 1-A + Ley del ISR Art. 106):

     IVA retenido (servicios profesionales)       — 10.6667% (2/3 of 16%)
     IVA retenido (transporte terrestre de carga) — 4%
     IVA retenido (servicios prestados a personas físicas por morales) — 10.6667%
     ISR retenido — honorarios profesionales       — 10%
     ISR retenido — arrendamiento (rentals)        — 10%

   Caller signals which retenciones apply via `:retencion-iva-rate`
   and `:retencion-isr-rate` (each defaults to zero). The withheld
   amounts REDUCE the net cash the supplier receives — they do NOT
   reduce the gross invoice IVA the supplier owes SAT.

   ## Algorithm sources (public, non-copyrightable)

     - Ley del IVA (Cámara de Diputados consolidated text)
     - Decreto de estímulos fiscales región fronteriza norte + sur
     - Ley del IEPS
     - Resolución Miscelánea Fiscal — annual nominal-rate consolidation

   ## API

     compute-tax {:line :iva-rate :region? :ieps-rate? :tax-status?
                   :retencion-iva-rate? :retencion-isr-rate?}
       → {:iva-rate :iva-amount :ieps-rate :ieps-amount
          :retencion-iva-rate :retencion-iva
          :retencion-isr-rate :retencion-isr
          :net :total-tax :total-gross :tax-status}

     compute-invoice-tax {:lines :region?}
       → {…sums… :per-line [<per-line> …]}"
  (:require [kontor.money :as money]))

;; ============================================================================
;; Rate tables
;; ============================================================================

(def iva-rate-general
  "Tasa general (general rate) — 16% across Mexico except border zones."
  0.16M)

(def iva-rate-border
  "Border-zone rate — 8%. Applies in the región fronteriza per the
   Decreto de estímulos fiscales (8 northern border + 4 southern
   border establishments, per Art. 1 of the decree)."
  0.08M)

(def iva-rate-zero
  "Tasa 0% — basic foods, medicines, books, exports (Art. 2-A)."
  0M)

(def regions
  "Valid `:region` values. Default `:general`."
  #{:general :border-norte :border-sur})

(defn rate-for-region
  "Resolve the headline IVA rate for the given region. Border-norte
   and border-sur both produce 8%."
  [region]
  (case region
    :general      iva-rate-general
    :border-norte iva-rate-border
    :border-sur   iva-rate-border
    iva-rate-general))

(def tax-statuses
  "Valid `:tax-status` values for compute-tax input. Default
   `:taxable` — caller passes `:iva-rate` (or relies on region default).

     :taxable      — apply :iva-rate
     :zero-rated   — :iva-rate forced to 0 (Art. 2-A: food/medicine/
                     books/exports); ITC IS claimable upstream
     :exempt       — out of the IVA base (Art. 9: residential rent,
                     healthcare); ITC NOT claimable upstream
     :non-resident — sale to a foreign buyer with goods exported;
                     treated as zero-rated under Art. 29"
  #{:taxable :zero-rated :exempt :non-resident})

;; ============================================================================
;; Money helpers
;; ============================================================================

(defn- bd
  "Extract a BigDecimal from a Money record, or pass through a
   BigDecimal / numeric input."
  ^java.math.BigDecimal [m]
  (cond
    (instance? java.math.BigDecimal m) m
    (number? m) (bigdec m)
    (and (map? m) (contains? m :amount)) (:amount m)
    :else (throw (ex-info "Cannot coerce to BigDecimal" {:value m}))))

(defn- m-zero [] (money/zero :MXN))

(defn- m-cents
  "Round a BigDecimal to 2dp HALF-EVEN and wrap in a Money :MXN.
   HALF-EVEN matches the kernel default; SAT does not mandate a
   single rounding mode for invoice-line IVA but does require the
   cumulative invoice total to match the sum of line amounts."
  [^java.math.BigDecimal amt]
  (money/money
   (.setScale amt 2 java.math.RoundingMode/HALF_EVEN)
   :MXN))

(defn- m-mul
  "Multiply a BigDecimal net amount by a rate, returning a Money :MXN
   rounded to 2dp HALF-EVEN."
  [^java.math.BigDecimal net ^java.math.BigDecimal rate]
  (m-cents (.multiply net rate)))

;; ============================================================================
;; Validation
;; ============================================================================

(defn- assert-status! [status]
  (when-not (contains? tax-statuses status)
    (throw (ex-info "Invalid :tax-status"
                    {:value status
                     :valid tax-statuses})))
  status)

(defn- assert-region! [region]
  (when-not (contains? regions region)
    (throw (ex-info "Invalid :region — must be :general | :border-norte | :border-sur"
                    {:value region
                     :valid regions})))
  region)

;; ============================================================================
;; compute-tax — per-line
;; ============================================================================

(defn compute-tax
  "Compute the per-line tax breakdown for one Mexican invoice line.

   Required inputs:
     :line     BigDecimal | Money | number — net taxable amount

   Optional inputs (each defaulting):
     :iva-rate    BigDecimal — when nil, looked up from :region
     :region      keyword in `regions` — default :general
     :tax-status  keyword in `tax-statuses` — default :taxable
     :ieps-rate   BigDecimal — per-product excise rate; default 0M
     :retencion-iva-rate BigDecimal — buyer-withheld IVA; default 0M
     :retencion-isr-rate BigDecimal — buyer-withheld ISR; default 0M

   Returns:
     {:iva-rate           BigDecimal
      :iva-amount         Money :MXN — output IVA owed (gross, not yet
                           cash-recognised — that split is the invoice
                           builder's concern, see ns docstring)
      :ieps-rate          BigDecimal
      :ieps-amount        Money :MXN — output IEPS
      :retencion-iva-rate BigDecimal
      :retencion-iva      Money :MXN — buyer-withheld IVA
                           (REDUCES cash receipt; does NOT reduce SAT-
                           owed IVA)
      :retencion-isr-rate BigDecimal
      :retencion-isr      Money :MXN — buyer-withheld ISR
      :net                Money :MXN — echo of input
      :total-tax          Money :MXN — iva + ieps  (positive tax)
      :total-retencion    Money :MXN — retencion-iva + retencion-isr
      :total-gross        Money :MXN — net + total-tax (what AR carries
                           before retención withholding)
      :total-cash-receipt Money :MXN — net + total-tax − total-retencion
      :tax-status         keyword
      :region             keyword}

   For `:zero-rated`, `:exempt`, and `:non-resident`, IVA = 0 (and
   the iva-rate is forced to 0 in the result), but IEPS still applies
   if rate is supplied — caller decides whether to suppress IEPS too
   (e.g. on exports).

   Examples:
     (compute-tax {:line 1000M})
       → 16% IVA = 160; total-gross 1160

     (compute-tax {:line 1000M :region :border-norte})
       → 8% IVA = 80; total-gross 1080

     (compute-tax {:line 1000M :iva-rate 0M :tax-status :zero-rated})
       → IVA 0; total-gross 1000 (food/medicine/export)

     (compute-tax {:line 1000M :ieps-rate 0.265M})
       → 16% IVA = 160, 26.5% IEPS = 265; total-gross 1425

     (compute-tax {:line 1000M :retencion-iva-rate 0.106667M
                   :retencion-isr-rate 0.10M})
       → IVA 160, retención IVA 106.67, retención ISR 100;
         AR = 1160, cash-receipt = 953.33"
  [{:keys [line iva-rate region tax-status ieps-rate
           retencion-iva-rate retencion-isr-rate]
    :or {region :general
         tax-status :taxable
         ieps-rate 0M
         retencion-iva-rate 0M
         retencion-isr-rate 0M}}]
  (assert-status! tax-status)
  (assert-region! region)
  (let [net-bd (bd line)
        net-m  (m-cents net-bd)
        zero   (m-zero)
        ;; Effective IVA rate: caller's :iva-rate wins if supplied;
        ;; else region default; forced 0 for zero-rated/exempt/non-resident.
        effective-iva-rate
        (cond
          (contains? #{:zero-rated :exempt :non-resident} tax-status) 0M
          (some? iva-rate) (bigdec iva-rate)
          :else (rate-for-region region))
        ieps-rate-bd (bigdec ieps-rate)
        rt-iva-bd (bigdec retencion-iva-rate)
        rt-isr-bd (bigdec retencion-isr-rate)
        iva-amt    (m-mul net-bd effective-iva-rate)
        ieps-amt   (if (zero? (.compareTo ieps-rate-bd 0M))
                     zero
                     (m-mul net-bd ieps-rate-bd))
        rt-iva-amt (if (zero? (.compareTo rt-iva-bd 0M))
                     zero
                     (m-mul net-bd rt-iva-bd))
        rt-isr-amt (if (zero? (.compareTo rt-isr-bd 0M))
                     zero
                     (m-mul net-bd rt-isr-bd))
        total-tax    (money/add iva-amt ieps-amt)
        total-rt     (money/add rt-iva-amt rt-isr-amt)
        total-gross  (money/add net-m total-tax)
        cash-receipt (money/sub total-gross total-rt)]
    {:iva-rate            effective-iva-rate
     :iva-amount          iva-amt
     :ieps-rate           ieps-rate-bd
     :ieps-amount         ieps-amt
     :retencion-iva-rate  rt-iva-bd
     :retencion-iva       rt-iva-amt
     :retencion-isr-rate  rt-isr-bd
     :retencion-isr       rt-isr-amt
     :net                 net-m
     :total-tax           total-tax
     :total-retencion     total-rt
     :total-gross         total-gross
     :total-cash-receipt  cash-receipt
     :tax-status          tax-status
     :region              region}))

;; ============================================================================
;; compute-invoice-tax — aggregate over lines
;; ============================================================================

(defn compute-invoice-tax
  "Aggregate tax over a sequence of invoice lines, all in the same
   region. Each line is the same input shape `compute-tax` accepts.

   Required:
     :lines   sequence of per-line maps
     :region  keyword in `regions` (default :general)

   Returns a map with the same shape as `compute-tax` plus a
   `:per-line` vector of the individual breakdowns. The summary
   fields sum across lines (each rounded to 2dp first, then added —
   matching SAT's invoice-level tax check tolerance)."
  [{:keys [lines region] :or {region :general}}]
  (assert-region! region)
  (let [per-line (mapv (fn [l] (compute-tax (assoc l :region region))) lines)
        zero (m-zero)
        sums (reduce
              (fn [acc {:keys [iva-amount ieps-amount retencion-iva retencion-isr
                               net total-tax total-retencion total-gross
                               total-cash-receipt]}]
                (-> acc
                    (update :iva-amount         money/add iva-amount)
                    (update :ieps-amount        money/add ieps-amount)
                    (update :retencion-iva      money/add retencion-iva)
                    (update :retencion-isr      money/add retencion-isr)
                    (update :net                money/add net)
                    (update :total-tax          money/add total-tax)
                    (update :total-retencion    money/add total-retencion)
                    (update :total-gross        money/add total-gross)
                    (update :total-cash-receipt money/add total-cash-receipt)))
              {:iva-amount zero :ieps-amount zero
               :retencion-iva zero :retencion-isr zero
               :net zero :total-tax zero :total-retencion zero
               :total-gross zero :total-cash-receipt zero}
              per-line)]
    (assoc sums
           :region region
           :per-line per-line)))
