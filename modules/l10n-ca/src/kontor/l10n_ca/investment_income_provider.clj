(ns kontor.l10n-ca.investment-income-provider
  "CA investment-income tax provider — `PeriodTaxProvider` (ADR-099)
   over the ADR-101 statute-as-data substrate. Research note 152.

   ## Two callable shapes — `:kind :individual | :corporation`

   - **Individual** — gross-up dividends, fold federal + per-province
     DTC as `:non-refundable-credit`s, fold interest + foreign-dividend
     gross into the PIT base via `:pit-base-additions`, apply §126
     foreign-tax credit (15% non-business cap). Emits ONE component
     per authority (federal `:cra` + 0 or 1 provincial: `:ca-on` /
     `:ca-bc` / `:ca-ab` / `:ca-qc`), each `:kind
     :investment-income-tax` with `:base 0` and `:liability` equal to
     the NEGATIVE of the credit total — i.e. the provider expresses
     the integration relief, and the downstream PIT provider applies
     the gross-up base addition + threads the credit against its own
     bracket tax. Pure feeder; no own bracket schedule.

   - **Corporation** — Part IV refundable tax on portfolio dividends
     (38⅓ %); §123.3 ART on AII (10⅔ %). The §112 inter-corporate
     deduction + interest fold via `:cit-base-additions` /
     `:cit-base-deductions`. Emits a `:kind :part-iv-tax` component
     for portfolio dividend liability + optionally an `:art` line for
     the additional refundable tax.

   ## Posting classification — consumer-supplied items

   The substrate `:ca-shelter` posting-dimension convention (note 152
   §1.6) is enforced AT THE CONSUMER — postings in TFSA / RRSP / FHSA
   / RPP / DPSP wrappers are filtered out before they reach this
   provider. The provider receives consumer-classified items via
   `:inputs :ca-investment-income`:

     {:eligible-dividends     <Money>     ; actual amount received (pre-gross-up)
      :non-eligible-dividends <Money>     ; actual amount received (pre-gross-up)
      :interest               <Money>     ; ordinary interest (no gross-up)
      :foreign-dividends      <Money>     ; included at gross; NO Canadian DTC
      :portfolio-dividends    <Money>     ; CORP-only: portfolio (non-connected) dividends → Part IV
      :connected-dividends    <Money>     ; CORP-only: from connected corps → §112 only
      :aii                    <Money>}    ; CORP-only: aggregate investment income for §123.3 ART

   ## Province routing

   `:tax-unit :province` selects which provincial component to emit
   (defaults to nil = federal only). v1 supports `:on`, `:bc`, `:ab`,
   `:qc`. Unknown provinces silently skip the provincial component
   (federal still fires) — the consumer can add per-province support
   by extending the statute file."
  (:require [kontor.l10n-ca.investment-income-statute :as inv-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- bigdec0
  ^java.math.BigDecimal [x]
  (or x 0M))

(defn- money-amount
  "Coerce Money or BigDecimal to BigDecimal; nil → 0M."
  ^java.math.BigDecimal [x]
  (cond
    (nil? x)               0M
    (instance? java.math.BigDecimal x) x
    (number? x)            (bigdec x)
    (map? x)               (or (:amount x) 0M)
    :else                  0M))

(def ^:private province->component
  "Map :tax-unit :province → component keyword used by statute
   condition `[:eq :component ...]`."
  {:on :ca-on
   :bc :ca-bc
   :ab :ca-ab
   :qc :ca-qc})

(def ^:private province->jurisdiction
  {:on :ca-on
   :bc :ca-bc
   :ab :ca-ab
   :qc :ca-qc})

(def ^:private province->authority
  {:on :ca-on
   :bc :ca-bc
   :ab :ca-ab-tra
   :qc :ca-qc-revenu})

(def ^:private province->label
  {:on "Ontario"
   :bc "British Columbia"
   :ab "Alberta"
   :qc "Quebec"})

;; ============================================================================
;; Compute-fns — federal + provincial DTC + §126
;; ============================================================================

(defn- ca-federal-dtc-eligible
  "Federal DTC on eligible dividends = grossed-up × rate-parameter.
   Reads `:inputs :ca-grossed-up-eligible` (provider pre-computed)."
  ^java.math.BigDecimal [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        rate  (or (statute/parameter-value-at db "CA.InvIncome.federal-dtc-eligible-rate" as-of)
                  0M)
        gu    (bigdec0 (get-in ctx [:inputs :ca-grossed-up-eligible]))]
    (* gu rate)))

(defn- ca-federal-dtc-non-eligible
  ^java.math.BigDecimal [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        rate  (or (statute/parameter-value-at db "CA.InvIncome.federal-dtc-non-eligible-rate" as-of)
                  0M)
        gu    (bigdec0 (get-in ctx [:inputs :ca-grossed-up-non-eligible]))]
    (* gu rate)))

(defn- ca-provincial-dtc-eligible
  "Provincial DTC on eligible dividends; rate parameter chosen per
   `:component` set by the provider (`:ca-on` / `:ca-bc` / `:ca-ab` /
   `:ca-qc`)."
  ^java.math.BigDecimal [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        comp- (:component ctx)
        code  (case comp-
                :ca-on "CA.ON.InvIncome.dtc-eligible-rate"
                :ca-bc "CA.BC.InvIncome.dtc-eligible-rate"
                :ca-ab "CA.AB.InvIncome.dtc-eligible-rate"
                :ca-qc "CA.QC.InvIncome.dtc-eligible-rate"
                (throw (ex-info "ca-provincial-dtc-eligible: no rate for component"
                                {:component comp-})))
        rate  (or (statute/parameter-value-at db code as-of) 0M)
        gu    (bigdec0 (get-in ctx [:inputs :ca-grossed-up-eligible]))]
    (* gu rate)))

(defn- ca-provincial-dtc-non-eligible
  ^java.math.BigDecimal [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        comp- (:component ctx)
        code  (case comp-
                :ca-on "CA.ON.InvIncome.dtc-non-eligible-rate"
                :ca-bc "CA.BC.InvIncome.dtc-non-eligible-rate"
                :ca-ab "CA.AB.InvIncome.dtc-non-eligible-rate"
                :ca-qc "CA.QC.InvIncome.dtc-non-eligible-rate"
                (throw (ex-info "ca-provincial-dtc-non-eligible: no rate for component"
                                {:component comp-})))
        rate  (or (statute/parameter-value-at db code as-of) 0M)
        gu    (bigdec0 (get-in ctx [:inputs :ca-grossed-up-non-eligible]))]
    (* gu rate)))

(defn- ca-federal-foreign-tax-credit
  "§126(1) federal foreign tax credit on non-business income.
   Claim = min(actual-foreign-tax-paid, 15% × foreign-income).
   Reads `:inputs :ca-foreign-tax-paid-total` and
   `:inputs :ca-foreign-income-total`."
  ^java.math.BigDecimal [ctx]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        cap-rate  (or (statute/parameter-value-at db "CA.InvIncome.foreign-non-bus-tax-cap" as-of)
                      0.15M)
        paid      (bigdec0 (get-in ctx [:inputs :ca-foreign-tax-paid-total]))
        foreign-i (bigdec0 (get-in ctx [:inputs :ca-foreign-income-total]))
        cap       (* foreign-i cap-rate)]
    (ts/lesser-of paid cap)))

(defn register!
  "Register the CA investment-income compute-fns with `kontor.statute`.
   Called automatically at namespace load; idempotent."
  []
  (statute/register-compute-fn! :ca-federal-dtc-eligible        ca-federal-dtc-eligible)
  (statute/register-compute-fn! :ca-federal-dtc-non-eligible    ca-federal-dtc-non-eligible)
  (statute/register-compute-fn! :ca-provincial-dtc-eligible     ca-provincial-dtc-eligible)
  (statute/register-compute-fn! :ca-provincial-dtc-non-eligible ca-provincial-dtc-non-eligible)
  (statute/register-compute-fn! :ca-federal-foreign-tax-credit  ca-federal-foreign-tax-credit))

(register!)

;; ============================================================================
;; Individual — gross-up + DTC + foreign credit
;; ============================================================================

(defn- gross-up
  "Gross up eligible + non-eligible dividends per the statute parameters.
   Returns `{:grossed-up-eligible <bd> :grossed-up-non-eligible <bd>}`."
  [db ^java.util.Date as-of
   ^java.math.BigDecimal elig
   ^java.math.BigDecimal non-elig]
  (let [elig-gu     (or (statute/parameter-value-at db "CA.InvIncome.eligible-gross-up" as-of) 1M)
        non-elig-gu (or (statute/parameter-value-at db "CA.InvIncome.non-eligible-gross-up" as-of) 1M)]
    {:grossed-up-eligible     (* elig elig-gu)
     :grossed-up-non-eligible (* non-elig non-elig-gu)
     :eligible-gross-up-rate  elig-gu
     :non-eligible-gross-up-rate non-elig-gu}))

(defn- individual-component
  "Build a single `:investment-income-tax` component for ONE authority
   (federal `:cra` or one provincial). The component's `:base` is 0
   (the bracket tax is computed by downstream PIT); the `:liability`
   is the NEGATIVE of the credit total (i.e. the integration relief
   this component contributes against the downstream PIT). The
   `:jurisdiction-specific-codes :pit-base-additions` carries the
   gross-up amount + interest + foreign-dividend gross for the
   downstream PIT provider to add to its base.

   Only the federal component carries the base additions + foreign
   tax credit; provincial components carry only the provincial DTC.
   This avoids double-adding the same base to two providers."
  [db ctx as-of jurisdiction component authority commodity
   {:keys [grossed-up-eligible grossed-up-non-eligible interest
           foreign-dividends foreign-tax-paid eligible non-eligible
           eligible-gross-up-rate non-eligible-gross-up-rate]}
   federal?]
  (let [scoped-ctx (-> ctx
                       (assoc :component component :db db :as-of as-of)
                       (assoc-in [:inputs :ca-grossed-up-eligible] grossed-up-eligible)
                       (assoc-in [:inputs :ca-grossed-up-non-eligible] grossed-up-non-eligible)
                       (assoc-in [:inputs :ca-foreign-tax-paid-total]
                                 (money-amount foreign-tax-paid))
                       (assoc-in [:inputs :ca-foreign-income-total]
                                 (money-amount foreign-dividends)))
        {:keys [tax-items provisions]}
        (statute/apply-provisions
         db {:concept :non-refundable-credit
             :jurisdiction jurisdiction
             :as-of as-of}
         scoped-ctx)
        ;; Resolve each credit's :amount via the same machinery
        ;; apply-adjustments would — but DON'T fold; non-refundable
        ;; clamping happens downstream against the PIT bracket tax,
        ;; not here. Surface each credit at face value.
        resolved (mapv
                  (fn [item]
                    (let [raw (:amount item)
                          amt (if (fn? raw)
                                (raw (assoc scoped-ctx :running 0M))
                                raw)]
                      (assoc item :amount (bigdec (or amt 0M)))))
                  tax-items)
        credit-total (reduce + 0M (map :amount resolved))
        ;; PIT base additions: federal carries the full additions
        ;; (gross-up + interest + foreign gross). Provincial components
        ;; carry NOTHING — the consumer threads the federal :pit-base-
        ;; additions to ONE PIT provider; provincial PIT uses the same
        ;; base via its own bracket schedule (note 152 §6.1).
        pit-additions (when federal?
                        (cond-> []
                          (pos? grossed-up-eligible)     (conj grossed-up-eligible)
                          (pos? grossed-up-non-eligible) (conj grossed-up-non-eligible)
                          (pos? interest)                (conj interest)
                          (pos? (money-amount foreign-dividends))
                          (conj (money-amount foreign-dividends))))
        base-line-items
        (cond-> []
          (and federal? (pos? eligible))
          (conj {:line :ca-elig-actual
                 :label "Eligible dividends actual"
                 :value (money/money eligible commodity)})
          (and federal? (pos? eligible))
          (conj {:line :ca-elig-grossed-up
                 :label (str "Eligible dividends grossed-up (× " eligible-gross-up-rate ")")
                 :value (money/money grossed-up-eligible commodity)})
          (and federal? (pos? non-eligible))
          (conj {:line :ca-non-elig-actual
                 :label "Non-eligible dividends actual"
                 :value (money/money non-eligible commodity)})
          (and federal? (pos? non-eligible))
          (conj {:line :ca-non-elig-grossed-up
                 :label (str "Non-eligible dividends grossed-up (× " non-eligible-gross-up-rate ")")
                 :value (money/money grossed-up-non-eligible commodity)})
          (and federal? (pos? interest))
          (conj {:line :ca-interest
                 :label "Interest (taxable line 12100)"
                 :value (money/money interest commodity)})
          (and federal? (pos? (money-amount foreign-dividends)))
          (conj {:line :ca-foreign-div
                 :label "Foreign dividends (line 12000, no gross-up)"
                 :value (money/money (money-amount foreign-dividends) commodity)}))
        credit-line-items
        (mapv (fn [r] {:line  (:code r)
                       :label (:label r)
                       :value (money/money (:amount r) commodity)})
              resolved)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/zero commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :credits         (mapv #(select-keys % [:code :label :amount :refundable? :provenance])
                            resolved)
     :liability       (money/money (- credit-total) commodity)
     :prepaid         (money/zero commodity)
     :line-items      (vec (concat base-line-items credit-line-items))
     :provenance      {:provider-id        :ca-inv-income
                       :statute            "ITA §82 / §121 / §126"
                       :provisions-applied (mapv :provision/code provisions)
                       :as-of              as-of}
     :jurisdiction-specific-codes
     (cond-> {:lane            (if federal? :ca-fed-inv-income :ca-prov-inv-income)
              :kind            :individual
              :ca/dtc-credit-total credit-total}
       federal?                          (assoc :ca/grossed-up-eligible     grossed-up-eligible
                                                :ca/grossed-up-non-eligible grossed-up-non-eligible
                                                :ca/interest                interest
                                                :ca/foreign-dividends       (money-amount foreign-dividends))
       (and federal? (seq pit-additions)) (assoc :pit-base-additions        pit-additions))}))

;; ============================================================================
;; Corporation — Part IV + ART + §112
;; ============================================================================

(defn- part-iv-component
  "Part IV refundable tax on portfolio dividends (§186). 38⅓ % flat
   on portfolio (non-connected) dividends. Refundable via the
   Non-Eligible RDTOH on later outbound dividend. Connected-corp
   dividends do NOT attract Part IV in v1 (the §186(1)(b)
   connected-exclusion fires)."
  [db as-of authority commodity ^java.math.BigDecimal portfolio-div]
  (let [rate     (or (statute/parameter-value-at db "CA.InvIncome.part-iv-rate" as-of)
                     0.383333M)
        schedule (ts/flat rate)
        gross    (ts/apply-schedule schedule portfolio-div)]
    {:kind            :part-iv-tax
     :authority       authority
     :base            (money/money portfolio-div commodity)
     :schedule        schedule
     :gross-liability (money/money gross commodity)
     :liability       (money/money gross commodity)
     :prepaid         (money/zero commodity)
     :line-items      [{:line  :portfolio-dividend-base
                        :label "Portfolio (non-connected) dividend base"
                        :value (money/money portfolio-div commodity)}
                       {:line  :part-iv-tax
                        :label "Part IV refundable tax (§186; 38⅓ %)"
                        :value (money/money gross commodity)}]
     :provenance      {:provider-id :ca-inv-income
                       :statute     "ITA §186 — Part IV"
                       :as-of       as-of}
     :jurisdiction-specific-codes
     {:lane :ca-part-iv
      :kind :corporation
      :ca/part-iv-rate    rate
      :rdtoh-credits-out  {:eligible     gross
                           :non-eligible 0M}}}))

(defn- art-component
  "§123.3 additional refundable tax on aggregate investment income —
   10⅔ %. Reported as a separate `:corporate-income-tax` component
   distinct from regular Part I (substrate doesn't have a dedicated
   ART kind; the rate + label make the role explicit). Refundable
   via the Non-Eligible RDTOH on outbound non-eligible dividends."
  [db as-of authority commodity ^java.math.BigDecimal aii]
  (let [rate     (or (statute/parameter-value-at db "CA.InvIncome.art-rate" as-of)
                     0.106667M)
        schedule (ts/flat rate)
        gross    (ts/apply-schedule schedule aii)]
    {:kind            :corporate-income-tax
     :authority       authority
     :base            (money/money aii commodity)
     :schedule        schedule
     :gross-liability (money/money gross commodity)
     :liability       (money/money gross commodity)
     :prepaid         (money/zero commodity)
     :line-items      [{:line  :aii-base
                        :label "Aggregate investment income (AII)"
                        :value (money/money aii commodity)}
                       {:line  :art
                        :label "§123.3 additional refundable tax (10⅔ % × AII)"
                        :value (money/money gross commodity)}]
     :provenance      {:provider-id :ca-inv-income
                       :statute     "ITA §123.3 — ART"
                       :as-of       as-of}
     :jurisdiction-specific-codes
     {:lane :ca-art
      :kind :corporation
      :ca/art-rate        rate
      :rdtoh-credits-out  {:eligible     0M
                           :non-eligible gross}}}))

(defn- corporate-cit-feeder
  "Build a `:corporate-income-tax` feeder component carrying
   `:cit-base-additions` (interest, foreign div gross) +
   `:cit-base-deductions` (§112 inter-corporate deduction = all
   portfolio + connected dividends). The component's own liability is
   0; downstream CIT folds the additions/deductions into its base."
  [authority commodity
   {:keys [interest foreign-dividends portfolio-dividends connected-dividends
           eligible-dividends non-eligible-dividends]}]
  (let [foreign  (money-amount foreign-dividends)
        ;; §112: all CA-source dividends to a corp are deductible.
        section112 (+ (bigdec0 portfolio-dividends)
                      (bigdec0 connected-dividends)
                      (bigdec0 eligible-dividends)
                      (bigdec0 non-eligible-dividends))
        cit-adds  (cond-> []
                    (pos? interest) (conj interest)
                    (pos? foreign)  (conj foreign))
        cit-deds  (cond-> []
                    (pos? section112) (conj section112))]
    {:kind            :corporate-income-tax
     :authority       authority
     :base            (money/zero commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      (cond-> []
                        (pos? interest)
                        (conj {:line :corp-interest
                               :label "Corporate interest income → CIT base"
                               :value (money/money interest commodity)})
                        (pos? foreign)
                        (conj {:line :corp-foreign-div
                               :label "Foreign portfolio dividend → CIT base"
                               :value (money/money foreign commodity)})
                        (pos? section112)
                        (conj {:line :s112-deduction
                               :label "§112 inter-corporate dividend deduction"
                               :value (money/money section112 commodity)}))
     :jurisdiction-specific-codes
     (cond-> {:lane :ca-corp-inv-feeder
              :kind :corporation
              :ca/section112-deduction section112}
       (seq cit-adds) (assoc :cit-base-additions  cit-adds)
       (seq cit-deds) (assoc :cit-base-deductions cit-deds))}))

;; ============================================================================
;; Provider
;; ============================================================================

(defn- individual-tax-return-facts
  [db ctx as-of entity period commodity inputs]
  (let [items     (or (:ca-investment-income inputs) {})
        eligible  (money-amount (:eligible-dividends items))
        non-elig  (money-amount (:non-eligible-dividends items))
        interest  (money-amount (:interest items))
        foreign-d (money-amount (:foreign-dividends items))
        foreign-t (money-amount (:foreign-tax-paid items))
        gu        (gross-up db as-of eligible non-elig)
        bundle    (assoc gu
                         :interest          interest
                         :foreign-dividends foreign-d
                         :foreign-tax-paid  foreign-t
                         :eligible          eligible
                         :non-eligible      non-elig)
        province  (some-> ctx :tax-unit :province)
        fed-c     (individual-component db ctx as-of :ca :ca-fed :cra commodity bundle true)
        prov-c    (when-let [pc (province->component province)]
                    (individual-component db ctx as-of
                                          (province->jurisdiction province)
                                          pc
                                          (province->authority province)
                                          commodity bundle false))]
    (ptp/tax-return-facts
     {:entity               entity
      :period               period
      :jurisdiction         {:country :ca :subdivision province}
      :functional-commodity commodity
      :components           (vec (cond-> [fed-c] prov-c (conj prov-c)))})))

(defn- corporate-tax-return-facts
  [db _ctx as-of entity period commodity inputs]
  (let [items      (or (:ca-investment-income inputs) {})
        portfolio  (money-amount (:portfolio-dividends items))
        aii        (money-amount (:aii items))
        comp1      (when (pos? portfolio)
                     (part-iv-component db as-of :cra commodity portfolio))
        comp2      (when (pos? aii)
                     (art-component db as-of :cra commodity aii))
        feeder     (corporate-cit-feeder
                    :cra commodity
                    {:interest               (money-amount (:interest items))
                     :foreign-dividends      (money-amount (:foreign-dividends items))
                     :portfolio-dividends    portfolio
                     :connected-dividends    (money-amount (:connected-dividends items))
                     :eligible-dividends     (money-amount (:eligible-dividends items))
                     :non-eligible-dividends (money-amount (:non-eligible-dividends items))})
        components (->> [comp1 comp2 feeder] (remove nil?) vec)]
    (ptp/tax-return-facts
     {:entity               entity
      :period               period
      :jurisdiction         {:country :ca}
      :functional-commodity commodity
      :components           components})))

(defrecord CAInvestmentIncomeTaxProvider [id kind commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (when-not (#{:individual :corporation} kind)
      (throw (ex-info "CA investment-income provider :kind must be :individual or :corporation"
                      {:kind kind})))
    (let [db    (or (:db ctx)
                    (throw (ex-info ":db required in ctx for CA investment-income provider"
                                    {:ctx-keys (keys ctx)})))
          as-of (as-of-from-ctx ctx)]
      (case kind
        :individual  (individual-tax-return-facts db ctx as-of entity period commodity inputs)
        :corporation (corporate-tax-return-facts  db ctx as-of entity period commodity inputs)))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn ca-individual-investment-income-provider
  "Build a CA individual investment-income provider. Pure feeder to
   PIT — emits gross-up additions + DTC + §126 credit; downstream PIT
   provider applies bracket tax + threads the credits.

   Config (all optional):
     :id        — provider id keyword (default :ca-inv-income-individual)
     :commodity — functional commodity (default :CAD)"
  [{:keys [id commodity] :or {id :ca-inv-income-individual commodity :CAD}}]
  (->CAInvestmentIncomeTaxProvider
   id :individual commodity
   "ITA §82, §121, §126, §146.2, §146.6"))

(defn ca-corporate-investment-income-provider
  "Build a CA corporate investment-income provider. Emits Part IV
   refundable tax + §123.3 ART; feeds §112 inter-corporate deduction
   + interest to CIT.

   Config (all optional):
     :id        — provider id keyword (default :ca-inv-income-corporate)
     :commodity — functional commodity (default :CAD)"
  [{:keys [id commodity] :or {id :ca-inv-income-corporate commodity :CAD}}]
  (->CAInvestmentIncomeTaxProvider
   id :corporation commodity
   "ITA §112, §123.3, §186"))

(defn install-statute!
  "Install the CA investment-income statute (parameters + provisions)
   into `conn`."
  [conn]
  (inv-statute/install! conn))
