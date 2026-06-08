(ns kontor.l10n-br.cit-provider
  "BR corporate income tax provider — IRPJ (15 % + 10 % adicional) +
   CSLL (9 % standard, 15 % / 20 % banks) — built as a
   `PeriodTaxProvider` (ADR-099) over the statute-as-data substrate
   (ADR-101). Mirrors
   `kontor.l10n-de.cit-provider` (ADR-104) — the reference template
   for per-jurisdiction CIT authoring.

   The provider dispatches on the elected regime:

     :br-lucro-real       (default) → LALUR/LACS path: book-profit ±
                          provision-driven adjustments per component
                          (`:irpj` / `:csll`), trava-30 % loss offset
                          late-bound on the post-other-adj base.
     :br-lucro-presumido  → presumption-ratio path: receita-bruta ×
                          per-activity ratio (8 % comércio / 32 %
                          serviços / 1.6 % combustíveis / 16 %
                          transporte-passageiros / 8 % cargas+hospitalar)
                          + ganhos-capital + receitas-financeiras +
                          outras-receitas; NO LALUR/LACS adjustments.
     :br-lucro-arbitrado  → v1 short-circuits to nil (parameter-only,
                          no compute path; consumer should not see
                          this as a missed computation — provenance
                          note returned via the calling convention if
                          needed).
     :br-simples-nacional → returns nil; Simples is a DAS-replacement
                          regime that subsumes IRPJ + CSLL + PIS +
                          COFINS + IPI + CPP + ICMS + ISS, NOT a CIT
                          regime in the ADR-099 sense. A future
                          `SimplesNacionalTaxRateProvider` (ADR-071)
                          covers it.

   In all live cases the IRPJ adicional 10 % surtax fires (provision
   `BR-IRPJ-adicional-10pct`, regime-agnostic3),
   reading `:base-amount` from ctx that the provider sets to the
   post-base-adjustments IRPJ base before invoking `apply-adjustments`.
   This is BR's analogue of DE Soli's `:running` thread, but the
   adicional is tax-ON-BASE (not tax-on-tax), so the base is what's
   threaded —3.

   ## Inputs the consumer supplies

   `:tax-profile` (company config):
     {:regime          <kw>  one of the 4 above, default :br-lucro-real
      :financial?      <bool> CSLL bank/insurer (uses rate-financial)
      :atividade-codigo <kw> Presumido activity routing:
                          :comercio :servicos :combustivel
                          :transporte-passageiros :transporte-cargas}

   `:inputs` (period facts):
     ;; Lucro Real:
     {:book-profit                     <BigDecimal>  required for Real
      :multas-indedutiveis             <BigDecimal>  optional, default 0
      :doacoes-acima-limite            <BigDecimal>  optional, default 0
      :brindes                         <BigDecimal>  optional, default 0
      :csll-provisao-periodo           <BigDecimal>  optional, default 0
      :dividendos-recebidos            <BigDecimal>  optional, default 0
      :pat-deducao                     <BigDecimal>  optional, default 0
      :rouanet-deducao                 <BigDecimal>  optional, default 0
      :jcp-pago                        <BigDecimal>  optional, default 0
      :jcp-tjlp-x-pl-cap               <BigDecimal>  cap consumer supplies
                                                      (TJLP × PL — out-of-books)
      :lucro-periodo                   <BigDecimal>  default :book-profit
      :lucros-acumulados               <BigDecimal>  default 0
      :prejuizo-fiscal-acumulado       <BigDecimal>  default 0
      :base-negativa-csll-acumulada    <BigDecimal>  default 0
      ;; Lucro Presumido:
      :receita-bruta                   <BigDecimal>  required for Presumido
      :ganho-capital                   <BigDecimal>  optional, default 0
      :receita-financeira              <BigDecimal>  optional, default 0
      :outras-receitas                 <BigDecimal>  optional, default 0
      ;; Both:
      :irrf-retido-irpj                <BigDecimal>  optional :prepaid on IRPJ
      :irrf-retido-csll                <BigDecimal>  optional :prepaid on CSLL}

   ## Authority keys

   `:br-rfb-irpj` + `:br-rfb-csll` — two distinct DARF line items
   even though Receita Federal collects both. Symmetric
   with DE's `:de-bundesfinanzministerium` + `:de-municipality`.

   ## Months-in-period helper

   IRPJ adicional threshold = R$ 20k × months. Computed from
   `(:from period)` and `(:to period)`. Anual = 12; trimestral = 3;
   mensal estimativa = 1. v1 keeps this provider-private; if FR / JP /
   CA also need it, promote to `kontor.tax.statute` ( in)."
  (:require [kontor.l10n-br.cit-statute :as cit-statute]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; ============================================================================
;; Private helpers
;; ============================================================================

(defn- as-of-from-ctx
  "Read the as-of instant from ctx (`:as-of` preferred; `:period :to`
   as fallback)."
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- inputs-fact
  "Read a fact from `:inputs` of ctx, with a default if missing."
  [ctx fact default]
  (or (get-in ctx [:inputs fact]) default))

(defn- months-in-period
  "Approximate the number of months in `:period`. 1 / 3 / 12 cover the
   three Receita Federal apuração cadences (mensal estimativa /
   trimestral / anual). Defaults to 12 if `:period` is absent (the
   common test case).

   Implementation: ms / avg-month → round to 1 / 3 / 12. Avoids
   pulling java.time for the substrate v1; if a corner case demands
   bisextile-aware month arithmetic, promote to `kontor.tax.statute`."
  [period]
  (if-let [{:keys [from to]} period]
    (let [ms-per-month 2629746000  ; 365.2425 / 12 days × 24h × 3600s × 1000ms
          n-months (Math/round (double (/ (- (.getTime ^java.util.Date to)
                                             (.getTime ^java.util.Date from))
                                          ms-per-month)))]
      (cond
        (<= n-months 1) 1
        (<= n-months 3) 3
        :else           12))
    12))

;; ============================================================================
;; Compute-fns — the ADR-101 §D2 escape hatch
;; ============================================================================

(defn- jcp-cap
  "Shared JCP-cap implementation — `min(jcp-claimed, tjlp-x-pl-cap,
   50 % × max(profit-period, profit-accumulated))`. The 50 % cap rate
   comes from `:parameter` so a future amendment is a one-row migration.
   Used by both `:br-jcp-cap-irpj` and `:br-jcp-cap-csll` — the
   computation is identical (the same JCP amount is the same cap on
   both sides per Lei 9.249 art. 9 §10 confirming CSLL dedutibilidade).
   Returns a BigDecimal (not a late-bound fn — JCP cap is independent
   of the running base)."
  ^java.math.BigDecimal [ctx]
  (let [db            (:db ctx)
        as-of         (as-of-from-ctx ctx)
        jcp-claimed   (bigdec (inputs-fact ctx :jcp-pago 0M))
        tjlp-x-pl-cap (bigdec (inputs-fact ctx :jcp-tjlp-x-pl-cap 0M))
        book-profit   (bigdec (inputs-fact ctx :book-profit 0M))
        profit-period (bigdec (inputs-fact ctx :lucro-periodo book-profit))
        profit-accum  (bigdec (inputs-fact ctx :lucros-acumulados 0M))
        cap-rate      (statute/parameter-value-at db "BR.JCP.deducao-cap-50pct" as-of)
        cap-50        (* cap-rate (max profit-period profit-accum))]
    (min jcp-claimed tjlp-x-pl-cap cap-50)))

(defn- br-jcp-cap-irpj
  "IRPJ-side JCP cap — same compute as CSLL-side."
  ^java.math.BigDecimal [ctx]
  (jcp-cap ctx))

(defn- br-jcp-cap-csll
  "CSLL-side JCP cap — same compute as IRPJ-side."
  ^java.math.BigDecimal [ctx]
  (jcp-cap ctx))

(defn- trava-30pct
  "Compensação prejuízo / base negativa — `min(carryforward, 30 % ×
   base-pré-compensação)`. Late-bound on `:running` which receives the
   running base AFTER all priority<900 base adjustments have folded.
   Returns a 1-arg fn per apply-base-adjustments' fn-amount convention."
  [ctx carry-fact]
  (fn [ctx-w-running]
    (let [carry    (bigdec (inputs-fact ctx carry-fact 0M))
          cap-rate (statute/parameter-value-at (:db ctx)
                                               "BR.Real.compensacao-prejuizo-cap"
                                               (as-of-from-ctx ctx))
          cap      (* (bigdec (:running ctx-w-running)) cap-rate)]
      (min carry cap))))

(defn- br-trava-30pct-irpj
  "IRPJ-side trava-30 %: caps :prejuizo-fiscal-acumulado."
  [ctx]
  (trava-30pct ctx :prejuizo-fiscal-acumulado))

(defn- br-trava-30pct-csll
  "CSLL-side trava-30 %: caps :base-negativa-csll-acumulada."
  [ctx]
  (trava-30pct ctx :base-negativa-csll-acumulada))

(defn- br-irpj-adicional
  "IRPJ adicional 10 % × max(0, base − R$ 20k × months-in-period).
   Late-bound — returns a fn-of-ctx-w-running per the
   `apply-adjustments` convention, so it reads `:base-amount` from the
   ctx the provider passes to `apply-adjustments` (NOT from the ctx
   `component-items` used to resolve surtax provisions, which runs
   BEFORE the provider knows the post-base-adjustments base — note
   162 §5.3). The fn parameter is named `ctx-w-running` for
   symmetry with DE Soli; we ignore `:running` because adicional is
   tax-ON-BASE, not tax-on-tax. Reads parameters at outer-call time
   so the late-bound fn does not need the db."
  [ctx]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        monthly   (statute/parameter-value-at db "BR.IRPJ.adicional-threshold-mensal" as-of)
        months    (months-in-period (:period ctx))
        threshold (* monthly (bigdec months))
        rate      (statute/parameter-value-at db "BR.IRPJ.adicional-rate" as-of)]
    (fn [ctx-w-running]
      (let [base (bigdec (or (:base-amount ctx-w-running) 0M))]
        (* (max 0M (- base threshold)) rate)))))

(defn register!
  "Register the BR CIT compute-fns with `kontor.tax.statute`. Called
   automatically at namespace load; idempotent."
  []
  (statute/register-compute-fn! :br-jcp-cap-irpj      br-jcp-cap-irpj)
  (statute/register-compute-fn! :br-jcp-cap-csll      br-jcp-cap-csll)
  (statute/register-compute-fn! :br-trava-30pct-irpj  br-trava-30pct-irpj)
  (statute/register-compute-fn! :br-trava-30pct-csll  br-trava-30pct-csll)
  (statute/register-compute-fn! :br-irpj-adicional    br-irpj-adicional))

(register!)

;; ============================================================================
;; Provider — pulls items from kontor.tax.statute, folds, builds components
;; ============================================================================

(defn- component-items
  "For one component (`:irpj` or `:csll`) under regime `regime`, query
   the statute for all applicable base-side + tax-side provisions and
   resolve them to fold-ready items."
  [db ctx as-of component regime]
  (let [scoped-ctx (assoc ctx :component component :db db :as-of as-of)
        adds       (statute/apply-provisions db {:concept      :base-transform-add
                                                 :jurisdiction :br
                                                 :as-of        as-of
                                                 :regime       regime}
                                             scoped-ctx)
        deducts    (statute/apply-provisions db {:concept      :base-transform-deduct
                                                 :jurisdiction :br
                                                 :as-of        as-of
                                                 :regime       regime}
                                             scoped-ctx)
        surtaxes   (statute/apply-provisions db {:concept      :surtax
                                                 :jurisdiction :br
                                                 :as-of        as-of
                                                 :regime       regime}
                                             scoped-ctx)]
    {:base-items (vec (concat (:base-items adds) (:base-items deducts)))
     :tax-items  (:tax-items surtaxes)
     :provisions (concat (:provisions adds) (:provisions deducts) (:provisions surtaxes))}))

(defn- presumido-ratio-code
  "Map an `:atividade-codigo` keyword to the IRPJ presunção
   `:kontor.parameter/code`. Per.2 — Presumido routing is
   provider-side ( in)."
  [atividade kind]
  (case [kind atividade]
    [:irpj :comercio]                "BR.IRPJ.presumido.ratio-comercio"
    [:irpj :servicos]                "BR.IRPJ.presumido.ratio-servicos"
    [:irpj :combustivel]             "BR.IRPJ.presumido.ratio-combustivel"
    [:irpj :transporte-passageiros]  "BR.IRPJ.presumido.ratio-transporte-passageiros"
    [:irpj :transporte-cargas]       "BR.IRPJ.presumido.ratio-transporte-cargas"
    [:csll :comercio]                "BR.CSLL.presumido.ratio-comercio"
    [:csll :servicos]                "BR.CSLL.presumido.ratio-servicos"
    [:csll :combustivel]             "BR.CSLL.presumido.ratio-comercio" ; combustíveis CSLL → 12 %
    [:csll :transporte-passageiros]  "BR.CSLL.presumido.ratio-servicos" ; CSLL transporte-pass → 32 %
    [:csll :transporte-cargas]       "BR.CSLL.presumido.ratio-comercio" ; CSLL cargas → 12 %
    (throw (ex-info "BR CIT: unknown :atividade-codigo for Lucro Presumido"
                    {:atividade atividade :kind kind
                     :supported [:comercio :servicos :combustivel
                                 :transporte-passageiros :transporte-cargas]}))))

(defn- presumido-base
  "Lucro Presumido base for one component: ratio × receita-bruta +
   ganho-capital + receita-financeira + outras-receitas. The ratio
   comes from `:parameter` (atividade-dispatched); the additions are
   consumer-supplied facts."
  ^java.math.BigDecimal [db ctx as-of kind atividade]
  (let [ratio-code (presumido-ratio-code atividade kind)
        ratio      (statute/parameter-value-at db ratio-code as-of)
        receita    (bigdec (inputs-fact ctx :receita-bruta 0M))
        ganho-cap  (bigdec (inputs-fact ctx :ganho-capital 0M))
        rec-fin    (bigdec (inputs-fact ctx :receita-financeira 0M))
        outras     (bigdec (inputs-fact ctx :outras-receitas 0M))]
    (+ (* receita ratio) ganho-cap rec-fin outras)))

(defn- csll-rate
  "Resolve the CSLL flat rate at `as-of`. Banks/insurers use
   `BR.CSLL.rate-financial` (15 % / 20 % / 15 % windows); everyone
   else uses `BR.CSLL.rate` (9 %)."
  ^java.math.BigDecimal [db as-of financial?]
  (statute/parameter-value-at db
                              (if financial?
                                "BR.CSLL.rate-financial"
                                "BR.CSLL.rate")
                              as-of))

(defn- prepaid-or-nil
  "Build a `:prepaid` Money map from an `:inputs` IRRF fact, or nil if
   absent / zero. Per.4."
  [ctx fact commodity]
  (let [amt (bigdec (inputs-fact ctx fact 0M))]
    (when (pos? amt) {:amount amt :commodity commodity})))

(defn- irpj-component
  "Build the IRPJ component map. Dispatches on regime:
     :br-lucro-real      → book-profit ± LALUR adjustments
     :br-lucro-presumido → atividade-dispatched presumption × receita-bruta
                           + ganhos + rec-fin + outras
   In BOTH the IRPJ adicional 10 % surtax fires (regime-agnostic
   provision), reading :base-amount from scoped ctx."
  [db ctx as-of book-profit commodity regime tax-profile]
  (let [{:keys [base-items tax-items provisions]} (component-items db ctx as-of :irpj regime)
        scoped-ctx-base (assoc ctx :component :irpj :db db :as-of as-of)
        raw-base (case regime
                   :br-lucro-real      book-profit
                   :br-lucro-presumido (presumido-base db ctx as-of :irpj
                                                       (:atividade-codigo tax-profile)))
        {base'         :base
         base-resolved :resolved}
        (ts/apply-base-adjustments raw-base base-items scoped-ctx-base)
        irpj-rate (statute/parameter-value-at db "BR.IRPJ.rate" as-of)
        schedule  {:kontor.schedule/type :flat :rate irpj-rate}
        gross     (ts/apply-schedule schedule base')
        ;; Thread the resolved base into ctx so the adicional surtax compute-fn
        ;; can read it via :base-amount.
        scoped-ctx-tax (assoc scoped-ctx-base :base-amount base')
        {liability    :liability
         tax-resolved :resolved}
        (ts/apply-adjustments gross tax-items scoped-ctx-tax)
        prepaid (prepaid-or-nil ctx :irrf-retido-irpj commodity)]
    (cond-> {:kind            :corporate-income-tax
             :authority       :br-rfb-irpj
             :base            {:amount base' :commodity commodity}
             :base-transform  (when (seq base-resolved)
                                {:transform/type :adjustments
                                 :items          base-resolved})
             :schedule        schedule
             :gross-liability {:amount gross :commodity commodity}
             :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance])
                                    tax-resolved)
             :liability       {:amount liability :commodity commodity}
             :regime          regime
             :provenance      {:provider-id        :br-cit
                               :statute            "IRPJ — Lei 9.249/95 + Lei 9.430/96"
                               :provisions-applied (mapv :kontor.provision/code provisions)
                               :as-of              as-of}}
      prepaid (assoc :prepaid prepaid))))

(defn- csll-component
  "Build the CSLL component map. Dispatches on regime (mirror of
   IRPJ); flat rate is 9 % (or 15 %/20 % via :financial?). No surtaxes
   — CSLL has no adicional and no Soli-equivalent."
  [db ctx as-of book-profit commodity regime tax-profile]
  (let [{:keys [base-items tax-items provisions]} (component-items db ctx as-of :csll regime)
        scoped-ctx (assoc ctx :component :csll :db db :as-of as-of)
        raw-base (case regime
                   :br-lucro-real      book-profit
                   :br-lucro-presumido (presumido-base db ctx as-of :csll
                                                       (:atividade-codigo tax-profile)))
        {base'         :base
         base-resolved :resolved}
        (ts/apply-base-adjustments raw-base base-items scoped-ctx)
        rate     (csll-rate db as-of (boolean (:financial? tax-profile)))
        schedule {:kontor.schedule/type :flat :rate rate}
        gross    (ts/apply-schedule schedule base')
        {liability    :liability
         tax-resolved :resolved}
        (ts/apply-adjustments gross tax-items scoped-ctx)
        prepaid (prepaid-or-nil ctx :irrf-retido-csll commodity)]
    (cond-> {:kind            :corporate-income-tax
             :authority       :br-rfb-csll
             :base            {:amount base' :commodity commodity}
             :base-transform  (when (seq base-resolved)
                                {:transform/type :adjustments
                                 :items          base-resolved})
             :schedule        schedule
             :gross-liability {:amount gross :commodity commodity}
             :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance])
                                    tax-resolved)
             :liability       {:amount liability :commodity commodity}
             :regime          regime
             :provenance      {:provider-id        :br-cit
                               :statute            "CSLL — Lei 7.689/88 + Lei 9.249/95"
                               :provisions-applied (mapv :kontor.provision/code provisions)
                               :as-of              as-of}}
      prepaid (assoc :prepaid prepaid))))

(defrecord BRCITProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs tax-profile] :as ctx}]
    (let [regime (or (:regime tax-profile) :br-lucro-real)]
      (case regime
        ;; Out-of-CIT-scope regimes short-circuit
        :br-simples-nacional nil
        :br-lucro-arbitrado  nil

        ;; Real / Presumido — compute both components
        (let [as-of (or (:as-of ctx) (:to period))
              ;; Real requires :book-profit; Presumido requires :receita-bruta.
              book-profit
              (case regime
                :br-lucro-real
                (or (:book-profit inputs)
                    (throw (ex-info "BR CIT (Lucro Real) needs :inputs :book-profit"
                                    {:inputs inputs})))

                :br-lucro-presumido
                (do (when-not (:receita-bruta inputs)
                      (throw (ex-info "BR CIT (Lucro Presumido) needs :inputs :receita-bruta"
                                      {:inputs inputs})))
                    (when-not (:atividade-codigo tax-profile)
                      (throw (ex-info "BR CIT (Lucro Presumido) needs :tax-profile :atividade-codigo"
                                      {:tax-profile tax-profile})))
                    ;; book-profit is the Real-path raw base; under Presumido it's unused
                    nil))
              irpj-c (irpj-component db ctx as-of book-profit commodity regime tax-profile)
              csll-c (csll-component db ctx as-of book-profit commodity regime tax-profile)]
          (ptp/tax-return-facts
           {:entity               entity
            :period               period
            :jurisdiction         {:country :br :authority :br-rfb}
            :functional-commodity commodity
            :components           [irpj-c csll-c]}))))))

(defn br-cit-provider
  "Build a BR CIT provider. Statute lives in `:provision` / `:parameter`
   data (installed via `kontor.l10n-br.cit-statute/install!`); the
   provider routes the regime dispatch + folds the applicable
   provisions per component.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :br-cit)
     :commodity — functional commodity (default :BRL)"
  [{:keys [id commodity] :or {id :br-cit commodity :BRL}}]
  (->BRCITProvider id commodity))

