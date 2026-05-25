(ns kontor.l10n-uk.investment-income-provider
  "UK investment-income tax provider — `PeriodTaxProvider` (ADR-099)
   over the ADR-101 statute-as-data substrate. Research note 150.

   ## The load-bearing mechanic — ITA 2007 §16 income-tax ordering

   UK income tax stacks income in a STRICT statutory order, with each
   layer consuming band capacity for the next:

     1. Non-savings income first (employment, self-employment,
        pension, rental).
     2. Savings income second (bank/corporate/government interest).
     3. Dividend income third.

   Each layer has its own zero-rate allowance(s):

     - Non-savings: PA £12,570 (consumed first against non-savings).
     - Savings: leftover PA, then SRS £5,000 (tapered £1-for-£1 by
       non-savings income above PA), then PSA (£1,000 basic / £500
       higher / £0 additional).
     - Dividends: dividend allowance £500.

   Savings income above its zero-rate bands is taxed at the SAME
   basic/higher/additional rates as non-savings (20/40/45 %).
   Dividends above the dividend allowance are taxed at the dividend
   rate ladder (pre-2026-04-06: 8.75/33.75/39.35; from 2026-04-06:
   10.75/35.75/39.35).

   The dividend allowance and PSA DO consume band capacity (a £500
   dividend at the higher-rate boundary can push the *next* £500 of
   income into higher rate); the SRS does NOT (it's a pure tax-free
   slice for low non-savings filers).

   ## Substrate fit (note 150 §3-§5)

   The provider:
     - Marginalizes income postings by `:account-tag` (chart-prefix
       convention; see `investment-income-base-selectors`).
     - Filters ISA-wrapped lanes (tag suffix `*-isa-wrapped`).
     - For corporates, routes by `:corp-dividend-exempt` (Part 9A) vs
       `:corp-dividend-taxable` (the consumer asserts via the tag).
     - For individuals, runs `uk-income-tax-allocation` — the §16
       ordering algorithm — purely.

   ## Composition

   The consumer wires:
     1. `uk-investment-income-provider` — emits savings-tax + dividend-
        tax (individual) OR Part-9A exempt + taxable lines (corporate).
     2. `uk-individual-cgt-provider` / `uk-corporate-cgt-provider` —
        independent (no shared base; UK losses don't cross).
     3. The UK PIT provider (when it ships) — consumes any
        `:pit-base-additions` (none today; v1 doesn't fold investment
        income into PIT because the provider emits its own tax).

   Note 150."
  (:require [kontor.l10n-uk.investment-income-statute :as inv-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.report :as report]
            [kontor.statute :as statute]))

;; ============================================================================
;; Constants — closed sets the provider routes on
;; ============================================================================

(def income-bands
  "Closed `:tax-unit :income-band` values. Extended from the CGT
   provider's `:basic | :higher` to include `:additional` (the £125,140+
   bracket) — investment income distinguishes the additional-rate
   slice for PSA (£0 at additional) and the dividend additional rate."
  #{:basic :higher :additional})

;; ============================================================================
;; Helpers — context / parameter access
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- param
  ^java.math.BigDecimal [db code ^java.util.Date as-of]
  (statute/parameter-value-at db code as-of))

;; ============================================================================
;; The §16 ordering algorithm — pure function over BigDecimal
;; ============================================================================

(defn- bd-max [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (if (>= (compare a b) 0) a b))

(defn- bd-min [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (if (<= (compare a b) 0) a b))

(defn- band-tax
  "Walk a single layer's taxable amount across basic / higher /
   additional bands given the cumulative `position` already used
   below the layer (POST-PA — i.e. in the bands themselves). Returns
   `[tax-amount used-in-each-band]`. Pure.

   The thresholds passed in are BAND-RELATIVE (post-PA): basic-cap is
   the basic-band width (e.g. £37,700); additional-floor is the
   start of the additional band measured post-PA (e.g. £112,570 =
   £125,140 − £12,570). The algorithm walks the amount across
   `[position, position+amount)` and splits it into basic / higher /
   additional slices based on where each falls."
  [^java.math.BigDecimal amount ^java.math.BigDecimal position
   {:keys [^java.math.BigDecimal basic-cap
           ^java.math.BigDecimal additional-floor
           ^java.math.BigDecimal basic-rate
           ^java.math.BigDecimal higher-rate
           ^java.math.BigDecimal additional-rate]}]
  (let [end           (+ position amount)
        in-basic-end  (bd-min end basic-cap)
        in-basic      (bd-max 0M (- in-basic-end position))
        higher-start  (bd-max position basic-cap)
        in-higher-end (bd-min end additional-floor)
        in-higher     (bd-max 0M (- in-higher-end higher-start))
        add-start     (bd-max position additional-floor)
        in-add        (bd-max 0M (- end add-start))
        tax           (+ (* in-basic basic-rate)
                         (* in-higher higher-rate)
                         (* in-add additional-rate))]
    [tax {:basic in-basic :higher in-higher :additional in-add}]))

(defn uk-income-tax-allocation
  "Implements ITA 2007 §16 ordering. Pure function over BigDecimal.

   Inputs:
     - `:non-savings` (BD) — non-savings income (employment + self-emp
       + rental) before PA. The PROVIDER subtracts PA against this.
     - `:savings` (BD) — total taxable savings income (post-ISA filter).
     - `:dividends` (BD) — total taxable dividend income (post-ISA filter).
     - `params` — see keys below.

   Returns:
     {:non-savings-tax <BD>      ;; tax on non-savings (PIT provider's
                                 ;;   slice; this provider does NOT own
                                 ;;   it but exposes it for audit)
      :savings-tax    <BD>
      :dividend-tax   <BD>
      :line-items     [<{:line :label :value}>]
      :psa-used       <BD>
      :srs-used       <BD>
      :pa-used-on-non-savings <BD>
      :pa-leftover-to-savings <BD>
      :div-allowance-used     <BD>
      :marginal-rate-band     :basic|:higher|:additional}

   Algorithm (per note 150 §5.4):
     1. PA against non-savings first. Leftover cascades to savings.
     2. SRS = max(0, srs - max(0, non-savings - pa)).
     3. PSA depends on the marginal-rate-band (derived from total income).
     4. (PA-leftover + SRS + PSA) zero-rates the bottom of savings.
        Remaining savings @ basic/higher/additional via `band-tax`.
     5. Dividend allowance zero-rates the bottom of dividends; it
        STILL consumes band capacity. Remaining dividends @ dividend-
        basic/higher/additional via `band-tax` with the dividend
        rates.
     6. The dividend allowance and PSA count toward bands; the SRS
        DOES count toward bands (it's a zero-rate band, not an above-
        the-line exclusion). The PA does not (it's stripped first).

   The function does NOT honour PA tapering above £100k (note 150
   §7.5 open question) — that's a UK PIT provider concern. v1 callers
   pre-adjust `:non-savings` if PA tapering applies."
  [{:keys [^java.math.BigDecimal non-savings
           ^java.math.BigDecimal savings
           ^java.math.BigDecimal dividends]}
   {:keys [^java.math.BigDecimal pa
           ^java.math.BigDecimal srs
           ^java.math.BigDecimal psa-basic
           ^java.math.BigDecimal psa-higher
           ^java.math.BigDecimal div-allowance
           ^java.math.BigDecimal basic-band
           ^java.math.BigDecimal additional-threshold
           ^java.math.BigDecimal basic-rate
           ^java.math.BigDecimal higher-rate
           ^java.math.BigDecimal additional-rate
           ^java.math.BigDecimal div-basic-rate
           ^java.math.BigDecimal div-higher-rate
           ^java.math.BigDecimal div-add-rate]}]
  (let [;; --- Step 1: PA against non-savings -----------------------------
        pa-non-savings  (bd-min non-savings pa)
        pa-leftover     (- pa pa-non-savings)
        non-savings-taxable (- non-savings pa-non-savings)

        ;; --- Step 2: SRS taper -------------------------------------------
        non-savings-above-pa (bd-max 0M (- non-savings pa))
        srs-available   (bd-max 0M (- srs non-savings-above-pa))

        ;; --- Determine marginal-rate-band from TOTAL income --------------
        higher-rate-threshold (+ pa basic-band)        ; e.g. 50 270
        total-income    (+ non-savings savings dividends)
        band            (cond
                          (>= (compare total-income additional-threshold) 0) :additional
                          (>= (compare total-income higher-rate-threshold) 0) :higher
                          :else :basic)
        psa             (case band
                          :basic      psa-basic
                          :higher     psa-higher
                          :additional 0M)

        ;; --- Step 3: zero-rate savings via leftover-PA + SRS + PSA -------
        zero-rate-cap   (+ pa-leftover srs-available psa)
        zero-rate-used  (bd-min savings zero-rate-cap)
        savings-taxable (- savings zero-rate-used)

        ;; Of zero-rate-used, attribute consumption (for audit):
        pa-used-on-savings (bd-min savings pa-leftover)
        rem-after-pa    (- savings pa-used-on-savings)
        srs-used        (bd-min rem-after-pa srs-available)
        rem-after-srs   (- rem-after-pa srs-used)
        psa-used        (bd-min rem-after-srs psa)

        ;; --- Step 4: band-walk taxable savings ---------------------------
        ;; Positions are BAND-RELATIVE (post-PA). Non-savings-taxable
        ;; fills bands 0 → basic-cap (= basic-band width) → additional-
        ;; floor (= additional-threshold − PA). The PA-leftover that
        ;; cascaded to savings sits BELOW the bands (it's still PA),
        ;; so it doesn't eat band capacity. The SRS-consumed and PSA-
        ;; consumed savings DO sit in the bands at 0% and DO eat band
        ;; capacity (note 150 §1.2 + §5.4).
        basic-cap        basic-band                        ; e.g. 37 700
        additional-floor (- additional-threshold pa)       ; e.g. 112 570
        rates            {:basic-cap        basic-cap
                          :additional-floor additional-floor
                          :basic-rate       basic-rate
                          :higher-rate      higher-rate
                          :additional-rate  additional-rate}

        ;; Savings POSITION in bands = non-savings-taxable + (SRS + PSA
        ;; consumed). The PA-leftover used on savings stays below the
        ;; bands. (zero-rate-used − pa-used-on-savings) = SRS + PSA used.
        savings-band-zero-used (- zero-rate-used pa-used-on-savings)
        savings-position (+ non-savings-taxable savings-band-zero-used)
        [savings-tax savings-bands]
        (band-tax savings-taxable savings-position rates)

        ;; --- Step 5: dividend allowance + dividend tax -------------------
        div-allow-used  (bd-min dividends div-allowance)
        dividends-taxable (- dividends div-allow-used)

        ;; Dividends sit ABOVE (non-savings-taxable + savings-in-bands).
        ;; All of savings (zero-rate-used + savings-taxable, except the
        ;; pa-used-on-savings slice which is below bands) sits in bands.
        savings-in-bands (- savings pa-used-on-savings)
        div-position    (+ non-savings-taxable savings-in-bands div-allow-used)
        div-rates       {:basic-cap        basic-cap
                         :additional-floor additional-floor
                         :basic-rate       div-basic-rate
                         :higher-rate      div-higher-rate
                         :additional-rate  div-add-rate}
        [dividend-tax dividend-bands]
        (band-tax dividends-taxable div-position div-rates)

        ;; --- Non-savings own tax (for audit; PIT owns this) --------------
        [non-savings-tax _]
        (band-tax non-savings-taxable 0M rates)

        line-items
        [{:line :pa-against-non-savings
          :label "PA consumed by non-savings income"
          :value pa-non-savings}
         {:line :pa-leftover
          :label "Leftover PA cascading to savings"
          :value pa-leftover}
         {:line :srs-available
          :label "Starting-rate band available for savings"
          :value srs-available}
         {:line :srs-used
          :label "SRS actually consumed"
          :value srs-used}
         {:line :psa
          :label "Personal Savings Allowance for this band"
          :value psa}
         {:line :psa-used
          :label "PSA actually consumed"
          :value psa-used}
         {:line :savings-tax
          :label "Tax on savings income (above zero-rate bands)"
          :value savings-tax}
         {:line :savings-bands
          :label "Savings tax breakdown by band"
          :value savings-bands}
         {:line :div-allowance-used
          :label "Dividend allowance used"
          :value div-allow-used}
         {:line :dividend-tax
          :label "Tax on dividends (above allowance)"
          :value dividend-tax}
         {:line :dividend-bands
          :label "Dividend tax breakdown by band"
          :value dividend-bands}
         {:line :marginal-rate-band
          :label "Marginal-rate band derived from total income"
          :value band}]]
    {:non-savings-tax        non-savings-tax
     :savings-tax            savings-tax
     :dividend-tax           dividend-tax
     :pa-used-on-non-savings pa-non-savings
     :pa-leftover-to-savings pa-leftover
     :srs-used               srs-used
     :psa-used               psa-used
     :div-allowance-used     div-allow-used
     :marginal-rate-band     band
     :savings-bands          savings-bands
     :dividend-bands         dividend-bands
     :line-items             line-items}))

;; ============================================================================
;; Base selectors — marginalize the GL by UK l10n account tag
;; ============================================================================

(def ^:private tag-lanes
  "The closed v1 tag set this provider recognises (note 150 §3.1).
   ISA-wrapped variants are dropped at compute time (filtered)."
  {:uk-investment-income/dividend-uk               :dividend-uk
   :uk-investment-income/dividend-uk-isa-wrapped   :isa-wrapped
   :uk-investment-income/savings-uk-domestic       :savings-uk
   :uk-investment-income/savings-uk-isa-wrapped    :isa-wrapped
   :uk-investment-income/corp-dividend-exempt      :corp-dividend-exempt
   :uk-investment-income/corp-dividend-taxable     :corp-dividend-taxable})

(defn- investment-income-base-selectors
  "Marginalize income postings by `:account-tags` axis and split into
   the per-tag lane totals. Returns a map of `{<lane-key> <bigdec>}`.

   ISA-wrapped income is FILTERED (returns nothing for those tags) —
   per note 150 §1.5 the substrate convention is the consumer keeps
   ISA portfolio inside an `Asset:ISA-Portfolio` and the income never
   posts to the income accounts at all; the wrapped-tag exists for
   consumers who DO post for portfolio-tracking purposes.

   Requires `:conn` in ctx (`report-postings` needs a connection for
   a bitemporal snapshot). Consumers without `:conn` must pre-supply
   `:inputs :investment-income-bases`."
  [{:keys [conn entity period]} commodity]
  (let [postings (report/report-postings
                  conn (cond-> {:from (:from period) :to (:to period)}
                         entity (assoc :entity entity)))
        by-tag   (report/marginalize postings :account-tags
                                     {:sign :inflow :commodity commodity})
        get-amt  (fn [tag]
                   (or (some-> (get by-tag tag) :value :amount) 0M))]
    {:dividend-uk            (get-amt :uk-investment-income/dividend-uk)
     :savings-uk             (get-amt :uk-investment-income/savings-uk-domestic)
     :corp-dividend-exempt   (get-amt :uk-investment-income/corp-dividend-exempt)
     :corp-dividend-taxable  (get-amt :uk-investment-income/corp-dividend-taxable)}))

;; ============================================================================
;; Components — individual
;; ============================================================================

(defn- individual-component
  "Build the individual provider's combined savings + dividend
   component. Returns nil when no taxable investment income."
  [{:keys [commodity authority]} ctx
   ^java.math.BigDecimal savings
   ^java.math.BigDecimal dividends]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        unit        (:tax-unit ctx)
        non-savings (or (:non-savings-income unit) 0M)
        params      {:pa                   (or (param db "UK.IIT.PA" as-of) 12570M)
                     :srs                  (or (param db "UK.IIT.SRS" as-of) 5000M)
                     :psa-basic            (or (param db "UK.IIT.PSA-basic" as-of) 1000M)
                     :psa-higher           (or (param db "UK.IIT.PSA-higher" as-of) 500M)
                     :div-allowance        (or (param db "UK.IIT.dividend-allowance" as-of) 500M)
                     :basic-band           (or (param db "UK.IIT.basic-band" as-of) 37700M)
                     :additional-threshold (or (param db "UK.IIT.additional-threshold" as-of) 125140M)
                     :basic-rate           (or (param db "UK.IIT.basic-rate" as-of) 0.20M)
                     :higher-rate          (or (param db "UK.IIT.higher-rate" as-of) 0.40M)
                     :additional-rate      (or (param db "UK.IIT.additional-rate" as-of) 0.45M)
                     :div-basic-rate       (or (param db "UK.IIT.dividend.basic-rate" as-of) 0.0875M)
                     :div-higher-rate      (or (param db "UK.IIT.dividend.higher-rate" as-of) 0.3375M)
                     :div-add-rate         (or (param db "UK.IIT.dividend.additional-rate" as-of) 0.3935M)}
        alloc       (uk-income-tax-allocation
                     {:non-savings non-savings
                      :savings     savings
                      :dividends   dividends}
                     params)
        liability   (+ (:savings-tax alloc) (:dividend-tax alloc))
        gross-base  (+ savings dividends)]
    (when (or (pos? savings) (pos? dividends))
      {:kind            :investment-income-tax
       :authority       authority
       :base            (money/money gross-base commodity)
       :schedule        nil
       :gross-liability (money/money liability commodity)
       :liability       (money/money liability commodity)
       :prepaid         (money/zero commodity)
       :regime          (:marginal-rate-band alloc)
       :line-items
       (mapv (fn [{:keys [line label value]}]
               {:line  line
                :label label
                :value (if (instance? java.math.BigDecimal value)
                         (money/money value commodity)
                         value)})
             (:line-items alloc))
       :jurisdiction-specific-codes
       {:lane                    :uk-investment-income
        :marginal-rate-band      (:marginal-rate-band alloc)
        :non-savings-income      non-savings
        :savings-income          savings
        :dividend-income         dividends
        :pa-used-on-non-savings  (:pa-used-on-non-savings alloc)
        :pa-leftover-to-savings  (:pa-leftover-to-savings alloc)
        :srs-used                (:srs-used alloc)
        :psa-used                (:psa-used alloc)
        :div-allowance-used      (:div-allowance-used alloc)
        :savings-tax             (:savings-tax alloc)
        :dividend-tax            (:dividend-tax alloc)}})))

;; ============================================================================
;; Components — corporate (CTA 2009 Part 9A)
;; ============================================================================

(defn- corporate-component
  "Build the corporate provider's component. Part 9A exempt amounts
   are recorded for audit but carry zero liability; taxable amounts
   surface as `:cit-base-additions` for the consumer to thread into
   the CT provider. Returns nil when no investment income."
  [{:keys [commodity authority]}
   ^java.math.BigDecimal exempt
   ^java.math.BigDecimal taxable]
  (when (or (pos? exempt) (pos? taxable))
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money taxable commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items
     [{:line :exempt-dividends-part-9A
       :label "Dividends exempt under CTA 2009 Part 9A"
       :value (money/money exempt commodity)}
      {:line :taxable-dividends
       :label "Dividends not within an exempt class (to CT base)"
       :value (money/money taxable commodity)}]
     :jurisdiction-specific-codes
     {:lane                :uk-corp-investment-income
      :cit-base-additions  [taxable]
      :exempt-amount       exempt
      :taxable-amount      taxable}}))

;; ============================================================================
;; The provider record
;; ============================================================================

(defrecord UKInvestmentIncomeTaxProvider
           [id authority commodity statute kind]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [_       (or (:db ctx)
                      (throw (ex-info ":db required in ctx for UK investment-income provider"
                                      {:ctx-keys (keys ctx)})))
          ;; Tests / 1099-style pre-aggregation may pre-marginalize
          ;; via :inputs :investment-income-bases (mirrors the US
          ;; provider's convention).
          bases   (or (:investment-income-bases inputs)
                      (investment-income-base-selectors ctx commodity))
          opts    {:authority authority :commodity commodity}
          components
          (case kind
            :individual
            (let [savings   (or (:savings-uk bases) 0M)
                  dividends (or (:dividend-uk bases) 0M)
                  cmp (individual-component opts ctx savings dividends)]
              (if cmp [cmp] []))

            :corporation
            (let [exempt  (or (:corp-dividend-exempt bases) 0M)
                  taxable (or (:corp-dividend-taxable bases) 0M)
                  cmp (corporate-component opts exempt taxable)]
              (if cmp [cmp] []))

            (throw (ex-info "UK investment-income provider :kind must be :individual or :corporation"
                            {:kind kind})))]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :uk :authority authority}
        :functional-commodity commodity
        :components           (vec components)}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn uk-individual-investment-income-provider
  "Build a UK individual investment-income provider. The provider
   reads `:tax-unit :non-savings-income` (BigDecimal) from ctx — the
   consumer threads the post-PA non-savings income (employment +
   self-employment + rental) from the UK PIT provider's
   `TaxReturnFacts` (note 150 §1.4 / §5.4)."
  [{:keys [id]}]
  (->UKInvestmentIncomeTaxProvider
   (or id :uk-investment-income-individual) :uk-hmrc :GBP
   "ITA 2007 §16 + ITTOIA 2005 Pt 4 Ch 2/3 + FA 2016 §4 + FA 2022 + Autumn Budget 2025"
   :individual))

(defn uk-corporate-investment-income-provider
  "Build a UK corporate investment-income provider. The consumer
   pre-classifies dividend receipts by account tag (`:corp-dividend-
   exempt` for Part 9A-eligible; `:corp-dividend-taxable` for the
   rare non-exempt case)."
  [{:keys [id]}]
  (->UKInvestmentIncomeTaxProvider
   (or id :uk-investment-income-corporate) :uk-hmrc :GBP
   "CTA 2009 Part 9A — distribution exemption for corporate recipients"
   :corporation))

(defn install-statute!
  "Install the UK investment-income statute (parameters + values)
   into `conn`."
  [conn]
  (inv-statute/install! conn))
