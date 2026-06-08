(ns kontor.l10n-ca.cit-provider
  "CA corporate income tax provider — federal T2 Part I + per-province
   CIT — built as a `PeriodTaxProvider` (ADR-099) over the statute-
   as-data substrate (ADR-101; ADR-107). Sibling
   of the DE CIT provider (ADR-104) — same shape (read parameters,
   call `kontor.tax.statute/apply-provisions`, fold components), CA-specific
   multi-province content.

   The provider does THREE things:

   1. Reads the federal + per-province rate / limit parameters.
   2. For each component (federal `:ca-fed` plus one `:ca-on` /
      `:ca-bc` / `:ca-ab` per province with allocated income), sets
      `:component` in ctx, calls `kontor.tax.statute/apply-provisions`
      to pull the schedule-override + credits, runs the schedule,
      returns the component map.
   3. Assembles an N-component `TaxReturnFacts` (1 federal + N
      provinces) — the same shape the existing CA T1 wrapper uses
      (`l10n_ca/period_tax_provider.clj` for personal income), just
      with more provinces.

   ## Multi-province allocation — provider-internal, not substrate

   Allocation across provinces is **content the provider computes**,
   not a substrate primitive. The
   consumer supplies `:tax-unit :provincial-allocation` as a map of
   province-code → fraction summing to 1. For each non-zero province,
   the provider:

     - Allocates taxable income: `province-base = taxable-income × share`
     - Allocates the SBD limit (CCPCs only): `province-sbd-pool =
       federal-sbd-business-limit × share`
     - Builds a 2-bracket progressive schedule via a compute-fn:
       [{:rate sbd-rate :upper province-sbd-pool}
        {:rate general-rate :upper nil}]
     - Runs the schedule on the allocated province-base.

   This mirrors the published CRA Schedule 5 mechanic (two-factor
   wages+revenue formula); the consumer pre-computes the factor via
   `marginalize` over the appropriate `:posting-dimension`s.

   ## Inputs the consumer supplies

   `:tax-unit` (company config):
     {:ccpc?                  <boolean>     required
                                            true ⇒ CCPC, eligible for
                                            SBD + refundable SR&ED
      :provincial-allocation  <map>         required for provincial
                                            components. Map of province
                                            code (`:on` `:bc` `:ab`) →
                                            fraction (BigDecimal 0..1).
                                            Must sum to 1 across all
                                            provinces with allocated
                                            income. Federal is computed
                                            on FULL taxable income.}

   `:inputs` (period facts):
     {:taxable-income       <BigDecimal>  required — book profit +
                                          statutory add-backs already
                                          netted (Schedule M-1 style;
                                          consumer is the M-1
                                          authority).
      :sred-expenditure     <BigDecimal>  optional, default 0 —
                                          qualifying SR&ED current
                                          expenditures.}

   Out of scope for v1: RDTOH / Part IV
   refundable dividend mechanism (frontier 2 — the carry), capital-loss
   carryforward as a true Mealy fold (frontier 2 — same). Consumers
   needing those today pass them as `:inputs` quantities and report
   carry-out manually in `:line-items`."
  (:require [kontor.l10n-ca.cit-statute :as cit-statute]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- as-of-from-ctx
  "Read the as-of instant from ctx (:as-of preferred; :period :to as
   the fallback)."
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- inputs-fact
  "Read a fact from `:inputs` of ctx, with a default if missing."
  [ctx fact default]
  (or (get-in ctx [:inputs fact]) default))

(defn- sbd-pool
  "The slice of a per-province small-business limit allocated to one
   province for the CCPC small-business cascade. Multi-province
   corporations get a proportional split via the Sch-5 allocation
   factor; single-province corporations get the full limit.

   Note 126 fix: the SBD pool is per-province, not always
   federal — Ontario's Bill 12 (royal assent Nov 2025) raises ON's
   small-business limit to $600k from 2026-01-01, breaking the
   prior alignment with the federal $500k. Callers now pass the
   per-province `:kontor.parameter/code` (`CA.ON.CIT.sbd-limit` etc.); the
   federal caller passes `CA.Federal.CIT.sbd-business-limit`."
  ^java.math.BigDecimal [db as-of ^java.math.BigDecimal share limit-parameter-code]
  (let [limit (statute/parameter-value-at db limit-parameter-code as-of)]
    (* limit share)))

(defn- two-bracket-progressive
  "Build a 2-bracket `:progressive-bracket` schedule — `sbd-rate` on
   the slice up to `sbd-upper`, `general-rate` above. The shape every
   CCPC province uses; differs only in rates + the allocated SBD
   upper."
  [^java.math.BigDecimal sbd-rate
   ^java.math.BigDecimal sbd-upper
   ^java.math.BigDecimal general-rate]
  (ts/progressive [{:rate sbd-rate     :upper sbd-upper}
                   {:rate general-rate :upper nil}]))

;; ============================================================================
;; Compute-fns — the ADR-101 §D2 escape hatch
;; ============================================================================

(defn- province-share
  "Read the consumer-supplied allocation fraction for `province` (a
   keyword like `:on` / `:bc` / `:ab`) from `:tax-unit
   :provincial-allocation`. Defaults to 1 for single-province (whole)
   federal — but the provincial compute-fns trap nil so an unconfigured
   province is a loud error."
  ^java.math.BigDecimal [ctx province]
  (or (some-> (get-in ctx [:tax-unit :provincial-allocation province]) bigdec)
      (throw (ex-info (str "CA CIT provider — province " province
                           " requires :tax-unit :provincial-allocation entry")
                      {:province province :tax-unit (:tax-unit ctx)}))))

(defn- ca-federal-ccpc-schedule
  "Federal CCPC schedule — 9% on first $500k (the SBD), 15% above. The
   federal SBD is NOT allocated by province — the full $500k pool
   applies to the corporation's total taxable income (multi-province
   allocation only affects provincial components)."
  [_base ctx]
  (let [db       (:db ctx)
        as-of    (as-of-from-ctx ctx)
        sbd-rate (statute/parameter-value-at db "CA.Federal.CIT.sbd-rate"          as-of)
        gen-rate (statute/parameter-value-at db "CA.Federal.CIT.general-rate"      as-of)
        limit    (statute/parameter-value-at db "CA.Federal.CIT.sbd-business-limit" as-of)
        schedule (two-bracket-progressive sbd-rate limit gen-rate)]
    schedule))

(defn- provincial-ccpc-schedule
  "Shared per-province CCPC schedule builder — small-business rate on
   the allocated slice of the province's small-business limit, general
   rate above. Each province has its OWN `:sbd-limit` parameter (used
   to match the federal $500k; ON's Bill 12 raises it to $600k from
   2026-01-01). Parametrised by province + rate +
   limit codes."
  [province sbd-rate-code sbd-limit-code general-rate-code]
  (fn [_base ctx]
    (let [db        (:db ctx)
          as-of     (as-of-from-ctx ctx)
          share     (province-share ctx province)
          sbd-rate  (statute/parameter-value-at db sbd-rate-code     as-of)
          gen-rate  (statute/parameter-value-at db general-rate-code as-of)
          pool      (sbd-pool db as-of share sbd-limit-code)
          schedule  (two-bracket-progressive sbd-rate pool gen-rate)]
      schedule)))

(def ^:private ca-on-ccpc-schedule
  (provincial-ccpc-schedule :on "CA.ON.CIT.sbd-rate" "CA.ON.CIT.sbd-limit" "CA.ON.CIT.general-rate"))

(def ^:private ca-bc-ccpc-schedule
  (provincial-ccpc-schedule :bc "CA.BC.CIT.sbd-rate" "CA.BC.CIT.sbd-limit" "CA.BC.CIT.general-rate"))

(def ^:private ca-ab-ccpc-schedule
  (provincial-ccpc-schedule :ab "CA.AB.CIT.sbd-rate" "CA.AB.CIT.sbd-limit" "CA.AB.CIT.general-rate"))

;; ----------------------------------------------------------------------------
;; The `:fn-from :compute-fn` resolver in kontor.tax.statute expects the
;; resolved value to be a fn `(base ctx) → bigdec`, so a `:formula`
;; schedule can carry it. But our compute-fns RESOLVE to a SCHEDULE
;; (so the substrate's apply-schedule of `:formula` sees a fn that —
;; when called — itself runs a progressive-bracket fold). That's a
;; double layer; simpler is to return the gross-tax bigdec directly.
;; ----------------------------------------------------------------------------

(defn- federal-ccpc-gross
  "Federal CCPC formula resolved to gross tax: progressive 2-bracket
   ladder (9% to $500k, 15% above)."
  ^java.math.BigDecimal [base ctx]
  (ts/apply-schedule (ca-federal-ccpc-schedule base ctx) base))

(defn- province-ccpc-gross
  "Provincial CCPC formula resolved to gross tax — picks the right
   per-province schedule by reading `:component` out of ctx."
  ^java.math.BigDecimal [base ctx]
  (let [schedule-fn (case (:component ctx)
                      :ca-on ca-on-ccpc-schedule
                      :ca-bc ca-bc-ccpc-schedule
                      :ca-ab ca-ab-ccpc-schedule
                      (throw (ex-info "province-ccpc-gross: no schedule for component"
                                      {:component (:component ctx)})))]
    (ts/apply-schedule (schedule-fn base ctx) base)))

(defn- ca-sred-credit
  "SR&ED Investment Tax Credit — 35% refundable for CCPCs on qualifying
   expenditures up to the expenditure limit ($3M / $6M post-2024-12-15),
   15% non-refundable on the excess + on all non-CCPC expenditures.

   Two-tier CCPC computation: `min(spend, limit) × 35% + max(0, spend −
   limit) × 15%`. Non-CCPC: `spend × 15%`."
  ^java.math.BigDecimal [ctx]
  (let [db       (:db ctx)
        as-of    (as-of-from-ctx ctx)
        spend    (inputs-fact ctx :sred-expenditure 0M)
        ccpc?    (boolean (get-in ctx [:tax-unit :ccpc?]))
        std-rate (statute/parameter-value-at db "CA.Federal.SRED.standard-rate" as-of)]
    (if ccpc?
      (let [ref-rate (statute/parameter-value-at db "CA.Federal.SRED.ccpc-refundable-rate" as-of)
            limit    (statute/parameter-value-at db "CA.Federal.SRED.ccpc-expenditure-limit" as-of)
            enhanced (min spend limit)
            excess   (max 0M (- spend limit))]
        (+ (* enhanced ref-rate) (* excess std-rate)))
      (* spend std-rate))))

(defn register!
  "Register the CA CIT compute-fns with `kontor.tax.statute`. Called
   automatically at namespace load; idempotent."
  []
  (statute/register-compute-fn! :ca-federal-ccpc-schedule federal-ccpc-gross)
  (statute/register-compute-fn! :ca-on-ccpc-schedule      province-ccpc-gross)
  (statute/register-compute-fn! :ca-bc-ccpc-schedule      province-ccpc-gross)
  (statute/register-compute-fn! :ca-ab-ccpc-schedule      province-ccpc-gross)
  (statute/register-compute-fn! :ca-sred-credit           ca-sred-credit))

(register!)

;; ============================================================================
;; Provider — pulls items from kontor.tax.statute, folds, builds components
;; ============================================================================

(def ^:private province-meta
  "Per-province metadata: component-kw, authority-kw, jurisdiction-kw,
   general-rate parameter code (used for the non-CCPC flat schedule
   fallback when the statute's `:flat` override is resolved)."
  {:on {:component    :ca-on
        :authority    :ca-on
        :jurisdiction :ca-on
        :label        "Ontario"}
   :bc {:component    :ca-bc
        :authority    :ca-bc
        :jurisdiction :ca-bc
        :label        "British Columbia"}
   :ab {:component    :ca-ab
        :authority    :ca-ab-tra
        :jurisdiction :ca-ab
        :label        "Alberta"}})

(defn- component-items
  "For one component-keyword (`:ca-fed` / `:ca-on` / `:ca-bc` / `:ca-ab`),
   query the statute for the schedule-override + applicable credits.
   Returns `{:schedule-overrides :tax-items :provisions}`."
  [db ctx as-of jurisdiction component]
  (let [scoped-ctx (assoc ctx :component component :db db :as-of as-of)
        overrides  (statute/apply-provisions db {:concept :elective-regime
                                                 :jurisdiction jurisdiction
                                                 :as-of as-of} scoped-ctx)
        refund     (statute/apply-provisions db {:concept :refundable-credit
                                                 :jurisdiction jurisdiction
                                                 :as-of as-of} scoped-ctx)
        nonrefund  (statute/apply-provisions db {:concept :non-refundable-credit
                                                 :jurisdiction jurisdiction
                                                 :as-of as-of} scoped-ctx)]
    {:schedule-overrides (:schedule-overrides overrides)
     :tax-items          (vec (concat (:tax-items refund) (:tax-items nonrefund)))
     :provisions         (concat (:provisions overrides)
                                 (:provisions refund)
                                 (:provisions nonrefund))}))

(defn- pick-schedule
  "Pick the schedule for a component: the first schedule-override (the
   substrate already trapped same-priority ambiguity), or throw if
   none. CA's design has exactly one override fire per (component,
   CCPC?) combination — conditions are mutually exclusive on `:ccpc?`."
  [overrides component]
  (or (some-> overrides first :schedule)
      (throw (ex-info (str "CA CIT provider — no schedule-override fired for component "
                           component
                           ". Check :tax-unit :ccpc? is set; check :as-of falls inside the provision's effective window.")
                      {:component component :overrides overrides}))))

(defn- federal-component
  "Build the federal `:ca-fed` component map. Base = taxable income
   (no provincial allocation — the federal Part I tax is on the
   corporation's total income, not per-province; provincial abatement
   is already baked into the effective 15%). Schedule from the
   schedule-override provision. Credits: SR&ED if `:sred-expenditure
   > 0`."
  [db ctx as-of taxable-income functional-commodity]
  (let [{:keys [schedule-overrides tax-items provisions]}
        (component-items db ctx as-of :ca :ca-fed)
        scoped-ctx (assoc ctx :component :ca-fed :db db :as-of as-of)
        schedule   (pick-schedule schedule-overrides :ca-fed)
        gross      (ts/apply-schedule schedule taxable-income scoped-ctx)
        {liability :liability tax-resolved :resolved}
        (ts/apply-adjustments gross tax-items scoped-ctx)]
    {:kind            :corporate-income-tax
     :authority       :cra
     :base            {:amount taxable-income :commodity functional-commodity}
     :schedule        schedule
     :gross-liability {:amount gross :commodity functional-commodity}
     :credits         (mapv #(select-keys % [:code :label :amount :refundable? :provenance])
                            (filter #(= :credit (:op %)) tax-resolved))
     :liability       {:amount liability :commodity functional-commodity}
     :jurisdiction-specific-codes {:ca/form "T2"
                                   :ca/ccpc? (boolean (get-in ctx [:tax-unit :ccpc?]))}
     :regime          (if (get-in ctx [:tax-unit :ccpc?]) :ccpc :general)
     :provenance      {:provider-id :ca-cit
                       :statute "Income Tax Act (Canada) — Part I"
                       :provisions-applied (mapv :kontor.provision/code provisions)
                       :as-of as-of}}))

(defn- province-component
  "Build one provincial component map. Base = taxable income ×
   provincial allocation share. Schedule from the schedule-override
   provision (a `:formula` for CCPCs whose compute-fn produces the
   allocated-pool progressive bracket, or a `:flat` for non-CCPCs).
   No credits at the provincial level in v1 (no provincial SR&ED
   modelled — deferred scope)."
  [db ctx as-of taxable-income functional-commodity province-key]
  (let [{:keys [component authority jurisdiction label]}
        (or (get province-meta province-key)
            (throw (ex-info (str "CA CIT provider — unknown province " province-key
                                 ". v1 supports :on :bc :ab.")
                            {:province province-key})))
        share         (province-share ctx province-key)
        province-base (* taxable-income share)
        {:keys [schedule-overrides tax-items provisions]}
        (component-items db ctx as-of jurisdiction component)
        scoped-ctx (assoc ctx :component component :db db :as-of as-of
                          :province province-key)
        schedule   (pick-schedule schedule-overrides component)
        gross      (ts/apply-schedule schedule province-base scoped-ctx)
        {liability :liability tax-resolved :resolved}
        (ts/apply-adjustments gross tax-items scoped-ctx)]
    {:kind            :corporate-income-tax
     :authority       authority
     :base            {:amount province-base :commodity functional-commodity}
     :schedule        schedule
     :gross-liability {:amount gross :commodity functional-commodity}
     :credits         (mapv #(select-keys % [:code :label :amount :refundable? :provenance])
                            (filter #(= :credit (:op %)) tax-resolved))
     :liability       {:amount liability :commodity functional-commodity}
     :jurisdiction-specific-codes {:ca/province  province-key
                                   :ca/label     label
                                   :ca/share     share
                                   :ca/form      (case province-key
                                                   :ab "AT1"
                                                   :on "T2"
                                                   :bc "T2")}
     :regime          (if (get-in ctx [:tax-unit :ccpc?]) :ccpc :general)
     :provenance      {:provider-id :ca-cit
                       :statute (str "CA " label " corporate income tax statute")
                       :provisions-applied (mapv :kontor.provision/code provisions)
                       :as-of as-of}}))

(defrecord CACITProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of          (or (:as-of ctx) (:to period))
          taxable-income (or (:taxable-income inputs)
                             (throw (ex-info "CA CIT provider needs :inputs :taxable-income (book profit + statutory add-backs)"
                                             {:inputs inputs})))
          allocation     (get-in ctx [:tax-unit :provincial-allocation] {})
          provinces      (->> allocation
                              (filter (fn [[_ share]] (pos? share)))
                              (map first))
          federal-c      (federal-component db ctx as-of taxable-income commodity)
          provincial-cs  (mapv #(province-component db ctx as-of taxable-income commodity %) provinces)]
      (ptp/assert-monocommodity-facts!
       (ptp/tax-return-facts
        {:entity               entity
         :period               period
         :jurisdiction         {:country "CA"}
         :functional-commodity commodity
         :components           (into [federal-c] provincial-cs)})))))

(defn ca-cit-provider
  "Build a CA CIT provider. Statute lives in `:provision` / `:parameter`
   data (installed via `kontor.l10n-ca.cit-statute/install!`); the
   provider just folds the applicable provisions per component.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :ca-cit)
     :commodity — functional commodity (default :CAD)"
  [{:keys [id commodity] :or {id :ca-cit commodity :CAD}}]
  (->CACITProvider id commodity))

