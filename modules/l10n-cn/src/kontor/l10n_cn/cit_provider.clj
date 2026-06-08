(ns kontor.l10n-cn.cit-provider
  "CN corporate income tax provider — Enterprise Income Tax (企业所得税,
   EIT) — built as a `PeriodTaxProvider` (ADR-099) over the
   statute-as-data substrate (ADR-101). Mirrors
   `kontor.l10n-fr.cit-provider` structurally
   (single-component schedule-override) with CN's distinctive THREE
   regime overrides (standard 25 % / SLPE preferential 5 % / HNTE
   15 %) and the SLPE cliff handled via the substrate's two-pass
   query pattern.

   The provider does THREE things and nothing else:

   1. Reads the §4 ¶1 standard rate from `:parameter` data
      (`CN.EIT.standard-rate`, shipped from `kontor.l10n-cn.cgt-statute`)
      and threads the §28 ¶1 SLPE / §28 ¶2 HNTE / regional 15 %
      preferential rates via `:schedule-override` consequences resolved
      at query time.
   2. For the single `:eit` component, performs the TWO-PASS query
      pattern (`kontor.tax.statute/apply-provisions` §\"Two-pass query
      pattern\"):
        - Pass 1 — compute taxable income from book-profit using the
          base-side fold over the STANDARD 25 % regime (no
          schedule-override applies yet because the cliff condition
          can't resolve without :taxable-income).
        - Pass 2 — inject the computed taxable income into
          `:inputs :taxable-income` and re-query (apply-provisions)
          for the elective-regime concept; the SLPE cliff condition
          (`[:leq [:inputs :taxable-income] 3000000M]`) now resolves
          correctly. Pick the prevailing schedule-override (highest
          priority) and apply the schedule. Fold tax-side credits
          (foreign tax credit).
   3. Assembles a 1-component `TaxReturnFacts`.

   ## Inputs the consumer supplies

   `:tax-unit` (company config — all optional with defaults):
     {:slpe?                     <bool>  optional — Small Low-Profit
                                         Enterprise; consumer adjudicates
                                         the qualitative §2 (headcount
                                         ≤ 300, assets ≤ ¥50M, not in
                                         negative-list industry) outside
                                         substrate; substrate enforces
                                         the quantitative cliff
                                         (taxable-income ≤ ¥3M).
      :hnte?                     <bool>  optional — High and New-
                                         Technology Enterprise; consumer
                                         attests Guo Ke Fa Huo [2016] 32
                                         qualification.
      :region                    <kw>    optional — :hainan-ftp /
                                         :western-region / :lingang
                                         for regional 15 % preferential.
      :hainan-substantive-ops?   <bool>  optional — drives Hainan FTP.
      :encouraged-industry?      <bool>  optional — drives Western Region.
      :lingang-industry?         <bool>  optional — drives Lingang.
      :industry                  <kw>    optional — drives R&D negative-
                                         list exclusion (:tobacco /
                                         :hospitality / :wholesale /
                                         :real-estate / :leasing /
                                         :entertainment).
      :rd-sector                 <kw>    optional — :ic-machine-tools
                                         picks the 220 % multiplier;
                                         anything else (or nil) picks
                                         the general 200 %.}

   `:inputs` (period facts — `:book-profit` is the only required key):
     {:book-profit                  <BigDecimal>  required — book
                                                  profit per PRC GAAP
                                                  (consumer maintains
                                                  the GAAP → taxable
                                                  delta outside the
                                                  substrate).
      :cn-non-deductibles           <BigDecimal>  optional — §10 EIT
                                                  Law non-deductible
                                                  expenses (consumer
                                                  pre-computed).
      :cn-tre-dividend-exemption    <BigDecimal>  optional — §26 ¶2
                                                  TRE-to-TRE dividend
                                                  exemption; lane from
                                                  `cn-eit-investment-
                                                  income-provider`.
      :rd-qualifying-expense        <BigDecimal>  optional — §30 R&D
                                                  qualifying expense
                                                  base (the compute-fn
                                                  produces the EXTRA
                                                  deduction = expense
                                                  × (multiplier − 1)).
      :cn-nol-applied               <BigDecimal>  optional — §18 net
                                                  operating loss
                                                  carry-forward
                                                  (consumer pre-computed
                                                  permitted offset).
      :cn-foreign-tax-credit        <BigDecimal>  optional — §23
                                                  foreign tax credit
                                                  (consumer pre-computed
                                                  per-basket cap).}

   ## Compute-fns

   - `:cn-rd-super-deduction-general` — `(rd-qualifying-expense ×
     (multiplier − 1))` reading `CN.EIT.rd-multiplier-general`
     (175 % pre-2023; 200 % post-2023).
   - `:cn-rd-super-deduction-ic-mt` — same shape reading
     `CN.EIT.rd-multiplier-ic-mt` (220 % post-2023).

   ## Two-pass query discipline

   The SLPE schedule-override condition gates on the computed taxable
   income (a CLIFF, not a phase-out — taxable-income > ¥3M ⇒ regime
   falls away entirely). The substrate's two-pass query pattern
   (`statute.clj:485-526`) handles this: provider computes taxable
   income from base-side adjustments first, then re-queries the
   elective-regime concept with the computed value injected into
   `ctx :inputs :taxable-income`. Forgetting this discipline produces
   silent mis-computation in the cliff-eligible case.

   ## Out-of-substrate

   - **CCSV multi-province fan-out** —.
   - **CFC anti-deferral** (Caishui [2009] 1) — consumer pre-computes;
     no v1 provision (extend if a consumer surfaces a case).
   - **Pillar Two** (Caishui [2024] 12) — v2; `compose-greater-of`.
   - **Withholding tax on non-resident** — `TaxRateProvider`
     (ADR-071), not period-tax.

   ## TODO — audit-doc seam

   The eventual posting builder (`tax-return-posting-builder`) does
   not yet stamp `:transaction/audit-doc` referencing the responsible
   `:kontor.provision`. The citation already lives on the provision
   row; the wire-up is a small kernel sweep tracked separately."
  (:require [kontor.l10n-cn.cit-statute :as cit-statute]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; suppress 'unused require' lint — the namespace is required for its
;; install-time side effects (parameter / provision defs read elsewhere
;; and to keep the symmetry with the FR / DE / JP / CA / AT templates).
(comment cit-statute/install!)

;; ============================================================================
;; Compute-fn registration — the ADR-101 §D2 escape hatch
;; ============================================================================

(defn- as-of-from-ctx
  "Read the as-of instant from ctx (`:as-of` preferred; `:period :to`
   as fallback for callers who only thread the period)."
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- cn-rd-super-deduction-general
  "R&D super-deduction (general sector) — `rd-qualifying-expense ×
   (multiplier − 1)`. Reads `CN.EIT.rd-multiplier-general` from
   `:parameter` data (175 % pre-2023; 200 % post-2023 per Cai Shui
   [2023] 7). The book already deducted the actual expense once; the
   substrate adds the EXTRA portion as a base-deduct so the total
   deduction equals expense × multiplier."
  ^java.math.BigDecimal [ctx]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        qe          (or (get-in ctx [:inputs :rd-qualifying-expense]) 0M)
        multiplier  (or (statute/parameter-value-at db "CN.EIT.rd-multiplier-general" as-of) 1M)]
    (* (bigdec qe) (- multiplier 1M))))

(defn- cn-rd-super-deduction-ic-mt
  "R&D super-deduction (IC + industrial mother-machine sector) — same
   shape, reads `CN.EIT.rd-multiplier-ic-mt` (220 % post-2023 per
   Cai Shui [2023] 44)."
  ^java.math.BigDecimal [ctx]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        qe          (or (get-in ctx [:inputs :rd-qualifying-expense]) 0M)
        multiplier  (or (statute/parameter-value-at db "CN.EIT.rd-multiplier-ic-mt" as-of) 1M)]
    (* (bigdec qe) (- multiplier 1M))))

(defn register!
  "Register the two CN CIT compute-fns with `kontor.tax.statute`.
   Called automatically at namespace load; idempotent."
  []
  (statute/register-compute-fn! :cn-rd-super-deduction-general  cn-rd-super-deduction-general)
  (statute/register-compute-fn! :cn-rd-super-deduction-ic-mt    cn-rd-super-deduction-ic-mt))

(register!)

;; ============================================================================
;; Provider — pulls items from kontor.tax.statute via TWO-PASS query
;; ============================================================================

(defn- base-side-items
  "Query base-side provisions for the `:eit` component. Used in BOTH
   passes (pass 1 sees the same base-side adjustments; pass 2 only
   re-queries elective-regime). Returns
   `{:base-items :tax-items :provisions}`."
  [db ctx as-of]
  (let [scoped-ctx (assoc ctx :component :eit :db db :as-of as-of)
        query      (fn [concept]
                     (statute/apply-provisions db {:concept      concept
                                                   :jurisdiction :cn
                                                   :as-of        as-of}
                                               scoped-ctx))
        adds       (query :base-transform-add)
        deducts    (query :base-transform-deduct)
        surtaxes   (query :surtax)
        refundable-credits     (query :refundable-credit)
        non-refundable-credits (query :non-refundable-credit)]
    {:base-items (vec (concat (:base-items adds) (:base-items deducts)))
     :tax-items  (vec (concat (:tax-items refundable-credits)
                              (:tax-items non-refundable-credits)
                              (:tax-items surtaxes)))
     :provisions (concat (:provisions adds)
                         (:provisions deducts)
                         (:provisions refundable-credits)
                         (:provisions non-refundable-credits)
                         (:provisions surtaxes))}))

(defn- elective-regime-overrides
  "Pass-2 query: re-evaluate `:elective-regime` provisions with
   `:taxable-income` populated in `ctx :inputs` so the SLPE cliff
   condition can resolve. Returns the schedule-overrides + their
   provenance provisions."
  [db ctx as-of]
  (let [scoped-ctx (assoc ctx :component :eit :db db :as-of as-of)
        result     (statute/apply-provisions db {:concept      :elective-regime
                                                 :jurisdiction :cn
                                                 :as-of        as-of}
                                             scoped-ctx)]
    {:schedule-overrides (:schedule-overrides result)
     :provisions         (:provisions result)}))

(defn- pick-schedule
  "Pick the effective schedule for the EIT component — the first
   (priority-ordered, ambiguity-trapped) `:schedule-override` if any,
   otherwise the provided default."
  [default-schedule overrides]
  (if-let [override (first overrides)]
    (:schedule override)
    default-schedule))

(defn- eit-component
  "Build the EIT component map via the two-pass query pattern. Pass 1
   computes the base via base-side adjustments over the standard
   regime; pass 2 re-queries elective-regime with the computed
   taxable income injected so the SLPE cliff condition fires."
  [db ctx as-of book-profit functional-commodity]
  (let [;; Pass 1 — gather all base-side + tax-side items (these don't
        ;; depend on taxable-income).
        pass-1                                    (base-side-items db ctx as-of)
        {:keys [base-items tax-items]}            pass-1
        pass-1-provisions                         (:provisions pass-1)
        scoped-ctx (assoc ctx :component :eit :db db :as-of as-of)
        {base'         :base
         base-resolved :resolved} (ts/apply-base-adjustments
                                   book-profit base-items scoped-ctx)
        ;; Pass 2 — inject the computed taxable income (base') so the
        ;; SLPE cliff condition [:leq [:inputs :taxable-income] 3M] can
        ;; resolve. This is the canonical two-pass discipline
        ;; (statute.clj:505-515).
        ctx-pass-2  (assoc-in ctx [:inputs :taxable-income] base')
        pass-2      (elective-regime-overrides db ctx-pass-2 as-of)
        schedule-overrides (:schedule-overrides pass-2)
        pass-2-provisions  (:provisions pass-2)
        ;; The default schedule = standard 25 % flat.
        standard-rate (or (statute/parameter-value-at db "CN.EIT.standard-rate" as-of)
                          (throw (ex-info "CN CIT provider: parameter CN.EIT.standard-rate not found — was kontor.l10n-cn.cgt-statute installed?"
                                          {:as-of as-of})))
        default-schedule {:kontor.schedule/type :flat :rate standard-rate}
        schedule         (pick-schedule default-schedule schedule-overrides)
        ;; EIT is not refundable on a loss — floor the gross at 0.
        raw-gross        (ts/apply-schedule schedule base' scoped-ctx)
        gross            (if (neg? raw-gross) 0M raw-gross)
        {liability    :liability
         tax-resolved :resolved} (ts/apply-adjustments
                                  gross tax-items scoped-ctx)
        ;; Combine all provisions for provenance — pass 1 collected
        ;; base-side + tax-side; pass 2 collected schedule-overrides.
        all-provisions (concat pass-1-provisions pass-2-provisions)]
    {:kind            :corporate-income-tax
     :authority       :cn-tax
     :base            {:amount base' :commodity functional-commodity}
     :base-transform  (when (seq base-resolved)
                        {:transform/type :adjustments
                         :items          base-resolved})
     :schedule        schedule
     :gross-liability {:amount gross :commodity functional-commodity}
     :credits         (mapv #(select-keys % [:code :label :amount :refundable? :provenance])
                            (filter #(= :credit (:op %)) tax-resolved))
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance])
                            (filter #(= :surtax (:op %)) tax-resolved))
     :liability       {:amount liability :commodity functional-commodity}
     :regime          nil
     :provenance      {:provider-id        :cn-cit
                       :statute            "中华人民共和国企业所得税法 (EIT Law) §4 / §28 / §10 / §26 / §30 / §18 / §23"
                       :provisions-applied (mapv :kontor.provision/code all-provisions)
                       :as-of              as-of}}))

(defrecord CnCitProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of        (or (:as-of ctx) (:to period))
          book-profit  (or (:book-profit inputs)
                           (throw (ex-info "CN CIT provider needs :inputs :book-profit"
                                           {:inputs inputs})))
          eit-c        (eit-component db ctx as-of book-profit commodity)]
      (ptp/assert-monocommodity-facts!
       (ptp/tax-return-facts
        {:entity               entity
         :period               period
         :jurisdiction         {:country :cn :authority :cn-tax}
         :functional-commodity commodity
         :components           [eit-c]})))))

(defn cn-cit-provider
  "Build a CN CIT provider. Statute lives in `:provision` /
   `:parameter` data (installed via `kontor.l10n-cn.cit-statute/install!`
   plus the §4 ¶1 standard rate which ships with
   `kontor.l10n-cn.cgt-statute/install!`); the provider just folds
   the applicable provisions for the EIT component using the two-pass
   query pattern.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :cn-cit)
     :commodity — functional commodity (default :CNY)"
  [{:keys [id commodity] :or {id :cn-cit commodity :CNY}}]
  (->CnCitProvider id commodity))
