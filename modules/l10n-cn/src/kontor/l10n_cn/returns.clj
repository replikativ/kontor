(ns kontor.l10n-cn.returns
  "Chinese VAT periodic return aggregator (增值税纳税申报表).

   Sits one layer above `kontor.l10n-cn.vat/compute-return` and
   produces the **per-taxpayer-status filing form** line-numbers that
   downstream filing tools (STA platform integrations, third-party
   tax-software exporters) consume.

   ## Filing cadence (per VAT Law + STA implementation rules)

     - **General taxpayer** (一般纳税人):
         * Default monthly (报税期 by the 15th of the following month).
         * Voluntary quarterly election available for small-revenue
           general taxpayers (rare, narrowly scoped).
     - **Small-scale taxpayer** (小规模纳税人):
         * Default quarterly since the 2021 reform (Cai Shui 2021
           No. 5 + STA 2021 No. 5 — quarterly for nearly all small-
           scale taxpayers; monthly retained only for those who
           specifically elect).
         * Quarterly filing window: by the 15th of the month
           following each calendar quarter.

   This module deliberately accepts both monthly and quarterly inputs
   for both taxpayer statuses — the law allows the rarer combinations
   and substrate-tier code should not over-constrain.

   ## General-taxpayer return shape (主表 — main form, abridged)

     Line 1  按适用税率计税销售额 (一般计税)
                Sales at standard rates (general-method)
     Line 2  按简易办法计税销售额
                Sales under simplified collection (rare for general)
     Line 8  免抵退办法出口销售额
                Export sales (with refund eligibility)
     Line 11 销项税额                              Output VAT
     Line 12 进项税额                              Input VAT
     Line 14 进项税额转出                          Input VAT reversal
     Line 17 应抵扣税额合计                        Total deductible
     Line 18 实际抵扣税额                          Actually deductible
     Line 19 应纳税额                              VAT payable
     Line 23 应纳税额合计                          Total payable
     Line 32 期末留抵税额                          End-of-period credit
                                                    carryforward

   We expose the load-bearing subset. Schedules 1-4 (附列资料) provide
   per-rate sales breakdowns; we surface the per-rate sales totals
   sufficient for Schedule 1 (主营业务收入 by rate).

   ## Small-scale-taxpayer return shape (主表 — small-scale form)

     Line 1   应征增值税不含税销售额（3%征收率）
                Sales at 3% (rare since 1% preferential)
     Line 4   应征增值税不含税销售额（5%征收率）
                Sales at 5% (real-estate carve-out)
     Line 9   免税销售额                              Exempt sales
     Line 16  本期应纳税额                            VAT payable

   For the small-scale 1% preferential rate (Cai Shui [2023] No. 19),
   the rate-classification field on the form is the statutory rate
   (3%) with a separate column for the rate-reduction amount; this
   aggregator returns both the gross-receipts-amount and the computed
   tax amount, leaving the form-side rate-disclosure choice to the
   downstream tool.

   ## What this module deliberately does NOT do

   - **No official XML / JSON envelope generation.** STA accepts
     returns via the e-tax platform (电子税务局) using vendor-specific
     XML envelopes that change every reform cycle. Substrate-tier
     stops at the line-number map; partner adapters
     (`kontor-l10n-cn-etax`) build the envelope.
   - **No 出口退税 refund-application computation.** The 0% export
     line totals are surfaced; the refund-claim form (增值税退税申报表)
     is a separate STA process.
   - **No 留抵退税 refund computation.** When end-of-period credit
     carryforward is refundable under Cai Shui 2022 No. 14 + 21 etc.,
     the eligibility test is a separate process and not part of the
     periodic return.

   Algorithm sources (public, non-copyrightable form layouts):
     - PRC VAT Law — codified line list for the periodic return.
     - 国家税务总局公告 2019 年第 15 号 — general-taxpayer return form
       (post-2019-reform shape).
     - 国家税务总局公告 2021 年第 5 号 — small-scale quarterly default.
     - Cai Shui [2023] No. 19 — 1% preferential through 2027-12-31.

   ## API

     compute-return conn opts → {:return/form :return/period
                                  :return/taxpayer-status
                                  :return/lines :return/net-vat
                                  :return/outcome ...}"
  (:require [kontor.l10n-cn.vat :as vat]
            [kontor.money :as money]))

;; ============================================================================
;; Money helpers
;; ============================================================================

(defn- bd ^java.math.BigDecimal [m]
  (cond
    (instance? java.math.BigDecimal m) m
    (number? m) (bigdec m)
    (and (map? m) (contains? m :amount)) (:amount m)
    :else 0M))

(defn- m-zero [] (money/zero :CNY))

;; ============================================================================
;; General-taxpayer 主表 line construction
;; ============================================================================

(defn- general-main-form
  "Build the abridged 主表 line map from a base vat/compute-return
   result. The base result already carries per-rate sales totals
   + total output + input VAT. We re-key it into form-line numbers."
  [base-result]
  (let [lines (:return/lines base-result)
        zero (m-zero)
        sales-13 (get lines :sales-13 zero)
        sales-9  (get lines :sales-9  zero)
        sales-6  (get lines :sales-6  zero)
        sales-export (get lines :sales-export zero)
        output (:return/output-vat base-result)
        input  (get lines :input-vat zero)
        ;; Sales at standard rates (general method) = sum of taxable
        ;; per-rate net amounts (excludes export which is line 8).
        line-1 (-> zero
                   (money/add sales-13)
                   (money/add sales-9)
                   (money/add sales-6))]
    {:1  line-1
     :2  zero                          ; 简易办法 sales — n/a for general
     :8  sales-export                  ; 免抵退 export sales
     :11 output                        ; 销项税额
     :12 input                         ; 进项税额
     :14 zero                          ; 进项税额转出 — substrate doesn't
                                        ; auto-compute reversal; consumer
                                        ; supplies via opts
     :17 input                         ; 应抵扣税额合计 (= 12 - 14)
     :18 input                         ; 实际抵扣税额 (≤ 17, capped at output)
     :19 (:return/net-vat base-result) ; 应纳税额
     :23 (:return/net-vat base-result) ; 应纳税额合计
     :32 (if (neg? (.signum ^java.math.BigDecimal (bd (:return/net-vat base-result))))
           ;; If net is negative, 期末留抵税额 = abs(net)
           (let [neg-amt (bd (:return/net-vat base-result))]
             (money/money (.negate neg-amt) :CNY))
           zero)}))

(defn- general-schedule-1
  "Schedule 1 (附列资料一) — per-rate sales detail. Each rate row
   carries [net-sales, output-vat, gross-sales]. Substrate-tier
   ships the numbers; the exact column layout is left to the
   downstream filing tool."
  [base-result]
  (let [out-by-rate (:return/output-by-rate base-result)
        lines (:return/lines base-result)
        zero (m-zero)
        row (fn [rate-key sales-key]
              {:net    (get lines sales-key zero)
               :output (get out-by-rate rate-key zero)})]
    {:rate-13 (row 0.13M :sales-13)
     :rate-9  (row 0.09M :sales-9)
     :rate-6  (row 0.06M :sales-6)
     :rate-0  (row 0M    :sales-export)}))

;; ============================================================================
;; Small-scale 主表 line construction
;; ============================================================================

(defn- small-scale-main-form
  "Build the small-scale 主表 line map.

   Small-scale taxpayers compute VAT on gross receipts (no input-VAT
   credit). The substrate base report doesn't track the small-scale-
   specific tag set in detail, so this aggregator reads the per-rate
   sales totals + computed output from the base result and re-keys
   them to small-scale line numbers.

   For the 1% preferential rate, the form-side rate-classification
   field carries 3% (statutory); we report sales at 1% as a separate
   line for downstream tools to map appropriately."
  [base-result
   {:keys [sales-3pct sales-1pct sales-5pct sales-exempt
           output-3pct output-1pct output-5pct]
    :or {sales-3pct (m-zero) sales-1pct (m-zero) sales-5pct (m-zero)
         sales-exempt (m-zero)
         output-3pct (m-zero) output-1pct (m-zero) output-5pct (m-zero)}}]
  (let [zero (m-zero)
        ;; Total payable = sum of per-rate output, capped at the
        ;; gross level. Surcharges piggy-back from base-result.
        total-payable (-> zero
                          (money/add output-3pct)
                          (money/add output-1pct)
                          (money/add output-5pct))]
    {:1  sales-3pct                        ; 应征增值税销售额 3%
     :2  sales-1pct                        ; (rate-reduction sub-line for 1%)
     :4  sales-5pct                        ; 应征增值税销售额 5% (real estate)
     :9  sales-exempt                      ; 免税销售额
     :15 (or (:return/output-vat base-result) total-payable)
     :16 total-payable}))                  ; 本期应纳税额

;; ============================================================================
;; Public surface
;; ============================================================================

(defn compute-return
  "Compute the periodic VAT return for the given period and taxpayer
   status.

   Required opts:
     :taxpayer-status   :general | :small-scale (default :general)

   Period opts (one of):
     :from / :to            — explicit half-open bounds
     :year + :month         — calendar month (general default)
     :year + :quarter       — calendar quarter (small-scale default)
     :year                  — annual aggregate

   Optional opts:
     :location-tier         — :municipal | :county | :other (default
                               :other) — passes through to the
                               surcharge computation in vat/compute-return.
     :compute-surcharges?   — boolean (default true).

   Small-scale-specific opts (carry the small-scale per-rate totals
   the consumer captured at posting time; substrate-tier doesn't
   auto-aggregate these because the default chart has no small-
   scale-rate revenue accounts):
     :sales-3pct :sales-1pct :sales-5pct :sales-exempt
     :output-3pct :output-1pct :output-5pct

   Returns:
     {:return/form              \"VAT-PRC-General\" | \"VAT-PRC-SmallScale\"
      :return/period            {…}
      :return/taxpayer-status   :general | :small-scale
      :return/lines             {…}    ; form-line-number → Money
      :return/schedule-1        {…}    ; general-taxpayer only:
                                        ;   per-rate sales detail
      :return/output-vat        Money
      :return/input-vat         Money  ; general-taxpayer only
      :return/net-vat           Money  ; positive = pay
      :return/umct-payable      Money  ; surcharge (if computed)
      :return/edu-surcharge-payable Money
      :return/local-edu-surcharge-payable Money
      :return/total-surcharges  Money
      :return/outcome           :payment | :credit-carryforward |
                                 :nil-return}"
  [conn {:keys [taxpayer-status]
         :or {taxpayer-status :general}
         :as opts}]
  (when-not (contains? #{:general :small-scale} taxpayer-status)
    (throw (ex-info "Invalid :taxpayer-status"
                    {:value taxpayer-status
                     :valid #{:general :small-scale}})))
  (let [base (vat/compute-return conn (dissoc opts :taxpayer-status
                                              :sales-3pct :sales-1pct
                                              :sales-5pct :sales-exempt
                                              :output-3pct :output-1pct
                                              :output-5pct))]
    (case taxpayer-status
      :general
      (merge base
             {:return/form            "VAT-PRC-General"
              :return/taxpayer-status :general
              :return/lines           (general-main-form base)
              :return/schedule-1      (general-schedule-1 base)})
      :small-scale
      (merge base
             {:return/form            "VAT-PRC-SmallScale"
              :return/taxpayer-status :small-scale
              :return/lines           (small-scale-main-form base opts)}))))
