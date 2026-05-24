(ns kontor.l10n-cn.lat-provider
  "Land Appreciation Tax (土地增值税, LAT) — `PeriodTaxProvider`
   (ADR-099) over the ADR-102 disposal substrate. Research note 133
   §1.6 + §5.3.

   ## Why a separate provider

   LAT is **structurally separate** from CGT — not an income tax. It
   is a stand-alone levy on the value uplift of state-owned land use
   rights and the buildings thereon when transferred for consideration.
   It has its own taxpayer class (real-estate developers + commercial
   transferors), its own four-tier progressive schedule, and its own
   filing form. Conceptually it is a property-incident transfer tax,
   not periodic on ownership, so the cleanest fit in the closed
   `period-tax-kinds` enum is `:capital-gains-tax` (LAT IS a tax on
   capital appreciation — just routed differently). Note 133 §5.3.

   ## The four-tier progressive schedule

   Per 《土地增值税暂行条例》 (国务院令 No. 138, 1993-12-13) Article
   7, LAT is computed on the **value-add ratio** (value-add ÷
   deductibles):

   | Value-add as % of deductibles | Marginal rate |
   |---|---|
   | ≤ 50 %  | 30 % |
   | 50–100 %  | 40 % |
   | 100–200 %  | 50 % |
   | > 200 %  | 60 % |

   Because the rate-determining quantity (the ratio) and the
   rate-applied quantity (value-add itself) differ, we apply the
   bracket directly on the VALUE-ADD amount but slice the bracket
   boundaries in proportion to the deductibles (each boundary is
   `boundary-ratio × deductibles`). This matches the substrate's
   `progressive` constructor — a single schedule per disposal.

   ## Carve-outs

   - **Ordinary residential housing built by developers — exempt when
     value-add ≤ 20 % of deductibles** (Provisional Regs §8 §1).
     Provider verifies via ctx `:tax-unit :ordinary-residential?` AND
     ratio ≤ 0.20.

   ## Scope — developers / commercial transferors only

   LAT is structurally a tax on **developers + commercial transferors**.
   Individual residential disposals are **out of scope by construction**
   — they are fully exempt from LAT per Caishui [2008] 137 (clarified +
   preserved by the 2024 财政部 税务总局 住建部 房地产市场平稳健康
   发展公告). The way to express this in the substrate is to record
   such disposals with asset-class `:cn-residential` (NOT
   `:cn-developer-real-estate`); they will be picked up by the IIT
   provider (which applies the 满五唯一 exemption + 20 % flat / 1-3 %
   deemed-gross election as appropriate) and never enter LAT.

   The provider therefore filters strictly on
   `:asset-class :cn-developer-real-estate`; the
   `:cn-lat-personal-residence` exemption keyword is intentionally NOT
   part of the public API (it would be a contradiction in terms — an
   individual is not a developer). Note 145 §1 P0-1 fix.

   ## Inputs

   The substrate disposal carries `:proceeds-amount` (gross transfer
   income) + `:basis-amount` (the deductibles — acquisition cost +
   development + taxes paid, with the 20 % extra deduction-cap
   multiplier for developers folded into `:basis-amount` by the
   companion consumer). The provider asks ctx `:tax-unit` for the
   developer / ordinary-residential flags.

   ## DisposalSource

   Provider depends on `kontor.disposal-source/DisposalSource` —
   filters for `:disposal/asset-class :cn-developer-real-estate`
   (LAT-eligible)."
  (:require [kontor.disposal-source :as ds]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; The bracket constants — Provisional Regs §7
;; ============================================================================

(def lat-bracket-boundaries
  "The bracket boundaries as ratios of value-add to deductibles.
   The four brackets are: ≤ 50 %, 50-100 %, 100-200 %, > 200 %."
  [0.50M 1.00M 2.00M])

(def lat-bracket-rates
  "The four marginal rates aligned with `lat-bracket-boundaries`."
  [0.30M 0.40M 0.50M 0.60M])

;; ============================================================================
;; The schedule — produced PER DISPOSAL because boundaries scale with
;; deductibles.
;; ============================================================================

(defn lat-schedule
  "Build a `:progressive-bracket` schedule for ONE disposal whose
   value-add ÷ deductibles ratio lands the bracket. The bracket
   boundaries are scaled by `deductibles` so the schedule consumes
   value-add (a Money quantity) directly — `(apply-schedule
   (lat-schedule d) value-add)` gives the LAT liability.

   For zero deductibles the schedule short-circuits to the top
   bracket (the >200 % rate)."
  [^java.math.BigDecimal deductibles]
  (let [scaled-uppers (mapv #(* % deductibles) lat-bracket-boundaries)
        brackets
        (-> (mapv (fn [u r] {:upper u :rate r})
                  scaled-uppers
                  (butlast lat-bracket-rates))
            (conj {:upper nil :rate (last lat-bracket-rates)}))]
    (ts/progressive brackets)))

;; ============================================================================
;; Disposal classification
;; ============================================================================

(defn- lat-eligible?
  "True iff this disposal is LAT-eligible (developer real-estate).
   Individual residential sales — out of scope by construction (the
   Caishui [2008] 137 exemption is enforced by NOT recording them
   under `:cn-developer-real-estate`; consumers should use
   `:cn-residential` and let the IIT provider handle them). See the
   namespace docstring + note 145 §1 P0-1."
  [disposal]
  (= :cn-developer-real-estate (:disposal/asset-class disposal)))

(defn- value-add
  "Value-add = proceeds − basis (= the deductibles). Negative ⇒ no
   appreciation, no LAT."
  ^java.math.BigDecimal [disposal]
  (- (or (:disposal/proceeds-amount disposal) 0M)
     (or (:disposal/basis-amount disposal) 0M)))

(defn- ordinary-residential-developer-exempt?
  "True iff developer + ordinary residential AND value-add ratio ≤
   20 % (Provisional Regs §8 §1)."
  [disposal ctx]
  (let [deductibles (or (:disposal/basis-amount disposal) 0M)
        va          (value-add disposal)
        ratio       (if (pos? deductibles) (/ va deductibles) 0M)]
    (and (true? (get-in ctx [:tax-unit :ordinary-residential?]))
         (true? (get-in ctx [:tax-unit :developer?]))
         (>= ratio 0M)
         (<= ratio 0.20M))))

;; ============================================================================
;; Component assembly
;; ============================================================================

(defn- lat-component-one
  "Build ONE LAT component for ONE LAT-eligible disposal."
  [{:keys [commodity authority]} disposal]
  (let [deductibles (or (:disposal/basis-amount disposal) 0M)
        va          (value-add disposal)
        schedule    (lat-schedule deductibles)
        liability   (if (pos? va) (ts/apply-schedule schedule va) 0M)
        ratio       (if (pos? deductibles) (/ va deductibles) 0M)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money (max 0M va) commodity)
     :schedule        schedule
     :gross-liability (money/money liability commodity)
     :liability       (money/money liability commodity)
     :prepaid         (money/zero commodity)
     :line-items      [{:line :cn-lat-value-add
                        :label "土地增值额 (value-add)"
                        :value (money/money va commodity)}
                       {:line :cn-lat-deductibles
                        :label "扣除项目金额 (deductibles)"
                        :value (money/money deductibles commodity)}
                       {:line :cn-lat-ratio
                        :label "value-add ratio"
                        :value ratio}
                       {:line :cn-lat-liability
                        :label "土地增值税 liability"
                        :value (money/money liability commodity)}]
     :jurisdiction-specific-codes
     {:lane             :cn-lat
      :cn-lat/external-id (:disposal/external-id disposal)
      :cn-lat/ratio       ratio}}))

;; ============================================================================
;; The provider
;; ============================================================================

(defrecord CnLatProvider [id source authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period] :as ctx}]
    (when-not (:db ctx)
      (throw (ex-info ":db required in ctx for CN LAT provider"
                      {:ctx-keys (keys ctx)})))
    (let [disposals (ds/disposals-in source entity period)
          ;; Two filters: LAT-eligible (developer real-estate) + not
          ;; ordinary-residential-developer-exempt. Individual
          ;; personal-residence disposals are out of scope by
          ;; construction — see namespace docstring + note 145 §1 P0-1.
          eligible  (->> disposals
                         (filter lat-eligible?)
                         (remove #(ordinary-residential-developer-exempt? % ctx)))
          components (mapv #(lat-component-one
                             {:authority authority :commodity commodity}
                             %)
                           eligible)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :cn :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructor
;; ============================================================================

(defn cn-lat-provider
  "Build the CN Land Appreciation Tax provider. Required `:source` —
   a `DisposalSource`."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->CnLatProvider (or id :cn-lat) source :cn-sat :CNY
                   "中华人民共和国土地增值税暂行条例 §7 (国务院令 No. 138, 1993-12-13)"))
