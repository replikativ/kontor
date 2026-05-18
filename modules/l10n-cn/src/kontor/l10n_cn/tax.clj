(ns kontor.l10n-cn.tax
  "Chinese VAT (增值税) — invoicing-side compute layer.

   Where `kontor.l10n-cn.vat` computes the *filing-side* (monthly /
   quarterly 增值税纳税申报表 aggregated from posted MOF accounts +
   tag-routed revenue), this namespace computes the *invoicing-side*:
   given a line amount and a few CN-specific knobs (taxpayer status,
   product/service VAT rate, export flag), return the per-rate tax
   breakdown that the fapiao posting builder needs.

   The two namespaces are complementary and share rate constants but
   no flow: the filing report reads tagged ledger postings; the
   compute below is pure arithmetic against published rate tables.

   ## Rate table (general taxpayer / 一般纳税人)

   General taxpayers (annual taxable sales > CNY 5M, or voluntary
   election) charge output VAT at the *product/service* rate and
   credit *input* VAT against purchases. The 2019 deepening reform
   (Cai Shui 2019 No. 39 / 财政部 税务总局 海关总署 公告 2019 年第 39 号)
   set the modern three-tier rate ladder:

       Rate   Scope (selection)
       ----   -----------------------------------------------
       13%    Manufacturing; goods sale / lease; processing,
              repair, installation services; imports.
       9%     Transportation; postal; basic telecommunications;
              construction; real-estate sale / lease; agricultural
              products; basic utilities (water/gas/heat).
       6%     Modern services (R&D, IT, design, leasing of intangibles,
              cultural/creative); financial services VATable; value-
              added telecommunications; lifestyle services.
       0%     Exports of goods (with refund eligibility per
              出口退(免)税 regime); cross-border services per
              positive list.

   Exempt categories (different from zero-rated — no output VAT AND
   no input VAT credit upstream): nursery / kindergarten services,
   elder care, marriage, funeral, certain medical services, books /
   journals / newspapers sold to certain end-users, agricultural
   producers selling self-produced primary agricultural products,
   second-hand goods sold by consumers, etc. (Cai Shui 2016 No. 36
   Annex 3 — the positive list).

   ## Rate table (small-scale taxpayer / 小规模纳税人)

   Small-scale taxpayers (annual taxable sales ≤ CNY 5M, no election
   to general) apply a **simplified collection** on **gross receipts**:

       Statutory rate        3%   (per VAT Law Article 12)
       Preferential rate     1%   (Cai Shui [2023] No. 19, valid
                                   through 2027-12-31)
       Real-estate sale      5%   (carve-out, retained)
       Real-estate lease     5%   (carve-out, retained)
       Zero-rated            0%   (exports)

   Small-scale taxpayers **cannot claim input VAT credits** — input
   VAT on purchases hits expense / cost, not the 2221.01.02 deductible-
   input account. The kernel models this by NOT emitting an input-VAT
   posting on small-scale-taxpayer purchases (the consumer signals
   taxpayer status; that's out of scope for this module — but the
   `:taxpayer-status` argument here is the dial that selects the rate
   ladder).

   ## Place-of-supply / export

   Domestic supplies between mainland-China-resident parties are
   uniformly subject to the resident rate ladder. Exports apply 0%
   under the export 出口退(免)税 regime (Cai Shui 2012 No. 39 + Cai
   Shui 2013 No. 47 — codifying the original 营改增 export framework).

   Cross-border supplies of services with an offshore counterparty
   follow the **positive list** in Cai Shui 2016 No. 36 Annex 4:
   listed services are zero-rated (consulting, R&D, IT, design,
   technology transfer, etc.); unlisted cross-border services bear
   the resident rate.

   Caller signals the category via `:tax-status` (default `:taxable`):
     :taxable     — apply the resident rate
     :zero-rated  — 0% under the export / cross-border positive list
     :exempt      — 0% AND no input credit upstream (positive list)

   ## What this module deliberately does NOT do

   - **No 出口退税 (export refund) computation.** The 0% export rate
     plus the supplier's input-VAT refund is a separate STA process
     (form 增值税退税申报表 + customs declarations); compute-tax
     returns the zero-rate output and lets the consumer drive the
     refund flow.
   - **No 进项税加计抵减 (additional input-VAT deduction) preferential.**
     Sector-specific super-deductions (Cai Shui 2019 No. 87 + renewals)
     belong in the filing aggregator, not invoicing.
   - **No 差额征税 / margin-method computation.** Travel services,
     labour dispatch, certain financial services compute VAT on a
     margin base; an explicit `:line` here is the post-margin amount.
   - **No fapiao-type routing.** The compute output applies equally to
     special VAT fapiao (增值税专用发票 — buyer can claim input VAT)
     and general fapiao (增值税普通发票 — no buyer credit); the
     fapiao-type distinction matters only for the BUYER's accounts.

   Algorithm sources (public, non-copyrightable rate tables):
     - PRC VAT Law (effective 2026-01-01) — codified rate ladder.
     - Cai Shui 2019 No. 39 — three-tier reform (16%→13%, 10%→9%).
     - Cai Shui [2023] No. 19 — small-scale 3%→1% preferential
       through 2027-12-31.
     - Cai Shui 2016 No. 36 Annexes 3 & 4 — exempt list + cross-
       border zero-rated positive list.

   ## API

     compute-tax        {:line :rate :taxpayer-status :tax-status}
                          → {:output-vat :total-tax :total-gross
                             :net :rate :taxpayer-status :tax-status}
     compute-invoice-tax {:lines :taxpayer-status} → same shape +
                          :per-line vector"
  (:require [kontor.l10n-cn.vat :as vat]
            [kontor.money :as money]))

;; ============================================================================
;; Rate ladder — re-export from vat.clj so callers have a single import.
;; ============================================================================

(def general-taxpayer-rates
  "Permitted output-VAT rates for a general taxpayer (一般纳税人)."
  #{0M 0.06M 0.09M 0.13M})

(def small-scale-rates
  "Permitted output-VAT rates for a small-scale taxpayer (小规模纳税人).
   The 1% preferential rate (Cai Shui [2023] No. 19) is included; the
   5% real-estate carve-out and the statutory 3% are retained."
  #{0M 0.01M 0.03M 0.05M})

(def real-estate-small-scale-rate
  "Small-scale taxpayer real-estate sale / lease rate carve-out — 5%.
   The 2023 No. 19 preferential reduction does NOT apply to this rate."
  0.05M)

(def taxpayer-statuses
  "Valid `:taxpayer-status` values:
     :general      — 一般纳税人; general-taxpayer rate ladder applies.
     :small-scale  — 小规模纳税人; simplified-collection rates apply."
  #{:general :small-scale})

(def tax-statuses
  "Valid `:tax-status` values:
     :taxable      — resident rate applies.
     :zero-rated   — 0% under export / positive-list cross-border
                     (output is 0; input-VAT credit upstream remains).
     :exempt       — 0% AND no input-VAT credit upstream
                     (Cai Shui 2016 No. 36 Annex 3)."
  #{:taxable :zero-rated :exempt})

;; ============================================================================
;; Money helpers
;; ============================================================================

(defn- bd
  "Extract a BigDecimal from a Money / BigDecimal / number input."
  ^java.math.BigDecimal [m]
  (cond
    (instance? java.math.BigDecimal m) m
    (number? m) (bigdec m)
    (and (map? m) (contains? m :amount)) (:amount m)
    :else (throw (ex-info "Cannot coerce to BigDecimal" {:value m}))))

(defn- m-zero [] (money/zero :CNY))

(defn- m-cents
  "Round a BigDecimal to 2dp HALF-EVEN and wrap in a Money :CNY.
   HALF-EVEN matches the kernel default; STA filings tolerate a
   cumulative invoice-tax rounding difference of CNY 0.01 between
   line-sum and invoice-total (per Cai Shui 2016 No. 36 implementation
   guidelines)."
  [^java.math.BigDecimal amt]
  (money/money
   (.setScale amt 2 java.math.RoundingMode/HALF_EVEN)
   :CNY))

(defn- m-mul
  "Multiply a BigDecimal net amount by a rate; return Money :CNY 2dp."
  [^java.math.BigDecimal net ^java.math.BigDecimal rate]
  (m-cents (.multiply net rate)))

;; ============================================================================
;; Validation
;; ============================================================================

(defn- assert-taxpayer-status! [status]
  (when-not (contains? taxpayer-statuses status)
    (throw (ex-info "Invalid :taxpayer-status"
                    {:value status :valid taxpayer-statuses})))
  status)

(defn- assert-tax-status! [status]
  (when-not (contains? tax-statuses status)
    (throw (ex-info "Invalid :tax-status"
                    {:value status :valid tax-statuses})))
  status)

(defn- assert-rate-permitted! [^java.math.BigDecimal rate taxpayer-status]
  (let [permitted (case taxpayer-status
                    :general general-taxpayer-rates
                    :small-scale small-scale-rates)]
    (when-not (contains? permitted rate)
      (throw (ex-info "Rate not permitted for taxpayer status"
                      {:rate rate
                       :taxpayer-status taxpayer-status
                       :permitted permitted}))))
  rate)

;; ============================================================================
;; Default rate selection
;; ============================================================================

(defn default-rate
  "Return a *suggested* default VAT rate for a taxpayer status when
   no explicit `:rate` is supplied. Callers SHOULD pin the rate
   per-line (the product/service determines it); this helper exists
   for tests / minimal scaffolding.

     :general     → 13% (standard goods rate)
     :small-scale → 1%  (current preferential through 2027-12-31)"
  [taxpayer-status]
  (case taxpayer-status
    :general vat/standard-rate
    :small-scale vat/small-scale-preferential-rate))

;; ============================================================================
;; Compute
;; ============================================================================

(defn compute-tax
  "Compute the output-VAT breakdown for one taxable line.

   Required:
     :line              BigDecimal | Money | number — net taxable amount
                         (post-margin if 差额征税 applies)

   Optional:
     :rate              BigDecimal in the permitted set for the taxpayer
                         status (default per `default-rate`)
     :taxpayer-status   :general | :small-scale (default :general)
     :tax-status        :taxable | :zero-rated | :exempt
                         (default :taxable)

   Returns:
     {:output-vat       Money :CNY  ; 销项税额 / 应纳税额
      :total-tax        Money :CNY  ; alias of :output-vat
      :total-gross      Money :CNY  ; net + total-tax
      :net              Money :CNY  ; echoed for caller convenience
      :rate             BigDecimal  ; effective rate (0 for zero-rated/exempt)
      :taxpayer-status  keyword
      :tax-status       keyword}

   For `:zero-rated` and `:exempt` lines, output-vat is Money 0 :CNY
   and total-gross equals net.

   Examples:

     ;; General taxpayer, manufacturing — 13% standard.
     (compute-tax {:line 1000M})
       ; or  {:line 1000M :rate 0.13M :taxpayer-status :general}
       → {:output-vat 130.00 :total-gross 1130.00 :rate 0.13 …}

     ;; General taxpayer, IT services — 6%.
     (compute-tax {:line 1000M :rate 0.06M})
       → {:output-vat 60.00 :total-gross 1060.00 :rate 0.06 …}

     ;; Small-scale taxpayer, preferential 1%.
     (compute-tax {:line 1000M :taxpayer-status :small-scale})
       → {:output-vat 10.00 :total-gross 1010.00 :rate 0.01 …}

     ;; General taxpayer, export — zero-rated.
     (compute-tax {:line 1000M :tax-status :zero-rated})
       → {:output-vat 0 :total-gross 1000.00 :rate 0 …}"
  [{:keys [line rate taxpayer-status tax-status]
    :or {taxpayer-status :general
         tax-status :taxable}}]
  (assert-taxpayer-status! taxpayer-status)
  (assert-tax-status! tax-status)
  (let [rate (or rate (default-rate taxpayer-status))
        _ (assert-rate-permitted! rate taxpayer-status)
        net-bd (bd line)
        net-m (m-cents net-bd)
        zero (m-zero)]
    (if (contains? #{:zero-rated :exempt} tax-status)
      {:output-vat zero
       :total-tax zero
       :total-gross net-m
       :net net-m
       :rate 0M
       :taxpayer-status taxpayer-status
       :tax-status tax-status}
      (let [output (m-mul net-bd rate)
            gross  (money/add net-m output)]
        {:output-vat output
         :total-tax output
         :total-gross gross
         :net net-m
         :rate rate
         :taxpayer-status taxpayer-status
         :tax-status tax-status}))))

(defn compute-invoice-tax
  "Aggregate output VAT over a sequence of invoice lines for a single
   taxpayer-status. Each line is `{:line <amount> :rate <bigdec>?
   :tax-status <kw>?}`; the `:taxpayer-status` is supplied once at the
   top level (a single CN invoice is issued by ONE entity with ONE
   taxpayer status).

   Returns a map with the same shape as `compute-tax` plus:
     :per-line        vector of per-line breakdowns
     :output-by-rate  map BigDecimal-rate → Money summed within rate

   The per-rate breakdown is what the small-scale-vs-general filing
   forms require — general-taxpayer filings split output by rate
   (13 / 9 / 6) on Schedule 1 (附列资料一); small-scale filings split
   by 3% / 1% / 5% on the small-scale main form."
  [{:keys [lines taxpayer-status]
    :or {taxpayer-status :general}}]
  (assert-taxpayer-status! taxpayer-status)
  (let [per-line (mapv (fn [l]
                         (compute-tax (assoc l :taxpayer-status taxpayer-status)))
                       lines)
        zero (m-zero)
        sums (reduce
              (fn [acc {:keys [output-vat total-tax total-gross net]}]
                (-> acc
                    (update :output-vat money/add output-vat)
                    (update :total-tax money/add total-tax)
                    (update :total-gross money/add total-gross)
                    (update :net money/add net)))
              {:output-vat zero :total-tax zero
               :total-gross zero :net zero}
              per-line)
        by-rate (reduce
                 (fn [acc {:keys [rate output-vat]}]
                   (update acc rate (fnil money/add zero) output-vat))
                 {}
                 per-line)]
    (assoc sums
           :taxpayer-status taxpayer-status
           :per-line per-line
           :output-by-rate by-rate)))
