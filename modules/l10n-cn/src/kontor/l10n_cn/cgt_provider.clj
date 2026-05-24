(ns kontor.l10n-cn.cgt-provider
  "CN capital-gains tax providers — `PeriodTaxProvider` (ADR-099) over
   the ADR-102 disposal substrate + ADR-101 statute-as-data. Research
   note 133.

   The CN regime is THREE sibling providers (note 133 §5):

   - `cn-iit-cgt-provider` — IIT category 9 财产转让所得 — 20 % flat
     for individuals on unlisted equity / real estate / movables /
     partnership interests. Listed A-share gains for resident
     individuals are TEMPORARILY EXEMPT (Caishui [1998] 61) — the
     provider returns NO COMPONENT. Stock Connect H-share exempt
     through 2027-12-31 (Caishui [2014] 81). 滿五唯一 residential
     exemption requires BOTH 5y self-occupation AND family-sole-
     residence.

   - `cn-eit-cgt-provider` — EIT corporate side. Gains are ORDINARY
     INCOME at 25 % (no separate CGT) — the provider folds the net
     gain via `:cit-base-additions`. Exceptions:
       * `:cn-special-restructuring` — equity-paid slice DEFERRED
         (Caishui [2009] 59 + [2014] 109's lower 50 % threshold).
       * `:cn-intra-group-100pct` — 100 %-controlled relaxed track.
       * `:cn-five-year-spread` — Caishui [2014] 116 evenly-spread.

   - `cn-lat-provider` — Land Appreciation Tax (土地增值税) lives in
     `kontor.l10n-cn.lat-provider` — STRUCTURALLY SEPARATE tax with
     its own progressive schedule.

   ## Composition

   The IIT provider returns a `TaxReturnFacts` whose component carries
   the standalone 20 % liability. The EIT provider returns components
   tagged `:cit-base-additions` / `:cit-base-deductions` for the CIT
   provider to consume — same composition pattern the US CGT provider
   uses (note 112).

   ## No-carryforward discipline

   Per note 133 §1.8 / §6.2 — individual category-9 losses do NOT
   carry forward under the IIT Law. The provider zero-pads negative
   period totals; the `:inputs :capital-loss-carryforward :cn-iit`
   slot is NOT consumed (a negative period total simply rounds to
   zero tax). Enterprise capital losses flow into the EIT NOL bucket
   handled by the CN CIT provider.

   ## DisposalSource

   Provider depends on `kontor.disposal-source/DisposalSource` — the
   companion's reference impl excludes `:voided` disposals."
  (:require [kontor.disposal-source :as ds]
            [kontor.l10n-cn.cgt-statute :as cgt-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Closed enums — note 133 §3 vocabulary
;; ============================================================================

(def iit-asset-classes
  "Closed CN asset-class set the IIT CGT provider classifies on.
   Note 133 §1 surfaces these from the IIT Law's category-9 enumeration
   + Caishui circulars."
  #{:cn-listed-a-share              ; Shanghai/Shenzhen A-shares; Beijing SE
    :cn-listed-b-share              ; foreign-currency B-shares
    :cn-listed-h-share-via-connect  ; Stock Connect H-shares
    :cn-unlisted-equity             ; LLC interests, pre-IPO founder shares
    :cn-residential                 ; personal residence
    :cn-non-residential             ; non-residential real-estate by individuals
    :cn-movable                     ; gold, art, jewellery, collectibles
    :cn-partnership-interest        ; 合伙企业份额
    :cn-crypto                      ; 2024 SAT enforcement push — category 9
    :cn-developer-real-estate})     ; LAT-eligible (handled by `cn-lat-provider`)

(def tax-residencies
  "Closed `:tax-residency` set — note 133 §1 routes exemptions on this."
  #{:resident-individual
    :non-resident-individual
    :resident-corporation
    :non-resident-corporation})

(def elective-regimes
  "Closed `:elective-regime` set for the CN CGT providers."
  #{:cn-real-estate-deemed-rate   ; 1-3 % gross — Guoshuifa [2006] 108
    :cn-special-restructuring     ; Caishui [2009] 59 + [2014] 109
    :cn-intra-group-100pct        ; Caishui [2014] 109 §3
    :cn-five-year-spread})        ; Caishui [2014] 116

;; ============================================================================
;; Helpers — provider-internal
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- days-between
  ^long [^java.util.Date a ^java.util.Date b]
  (long (/ (- (.getTime b) (.getTime a))
           (* 1000 60 60 24))))

(defn- holding-years
  "Whole years between two instants (BigDecimal, rounded down). Returns
   0M when either is nil."
  ^java.math.BigDecimal [^java.util.Date a ^java.util.Date b]
  (if (and a b)
    (bigdec (long (/ (days-between a b) 365)))
    0M))

(defn- realized-gain
  "Gain (positive) or loss (negative) on one disposal:
   `proceeds − basis − rollover-amount`."
  ^java.math.BigDecimal [disposal]
  (let [p (or (:disposal/proceeds-amount disposal) 0M)
        b (or (:disposal/basis-amount disposal) 0M)
        r (or (:disposal/rollover-amount disposal) 0M)]
    (- p b r)))

(defn- elective-regime?
  "True iff `disposal` has the given regime keyword in its (set-valued)
   `:disposal/elective-regime`."
  [disposal regime]
  (boolean (and disposal regime
                (contains? (set (:disposal/elective-regime disposal)) regime))))

(defn- exemption?
  "True iff `disposal` claims the given exemption keyword."
  [disposal exemption]
  (boolean (and disposal exemption
                (contains? (set (:disposal/exemption-claimed disposal)) exemption))))

;; ============================================================================
;; IIT — exemption routing + base computation
;; ============================================================================

(defn- listed-share-exempt?
  "True iff this is a listed-A/B-share or Stock-Connect H-share gain
   by a resident individual — Caishui [1998] 61 / [2014] 81. Returns
   NO TAX (the provider drops the disposal entirely)."
  [disposal residency]
  (and (= residency :resident-individual)
       (contains? #{:cn-listed-a-share :cn-listed-b-share
                    :cn-listed-h-share-via-connect}
                  (:disposal/asset-class disposal))))

(defn- manwuweiyi-exempt?
  "True iff the residential disposal satisfies BOTH 滿五唯一 prongs:
   (1) ≥ 5 years self-occupation (acquired-on to disposed-on) AND
   (2) the holder's family owns this as their sole residence in the
   province. The sole-residence prong rides ctx `:tax-unit
   :family-sole-residence?` (companion-attested per note 133 §6.3)."
  [disposal residency ctx]
  (and (= residency :resident-individual)
       (= :cn-residential (:disposal/asset-class disposal))
       (true? (:disposal/residence? disposal))
       (true? (get-in ctx [:tax-unit :family-sole-residence?]))
       (>= (compare (holding-years (:disposal/acquired-on disposal)
                                   (:disposal/disposed-on disposal))
                    5M)
           0)))

(defn- iit-real-estate-base
  "Determine the IIT base for a real-estate disposal. Two paths:
     :net-gain      → proceeds − basis (standard 20 % path)
     :deemed-gross  → proceeds × rate (1-3 % election per
                      Guoshuifa [2006] 108).
   The election rides `:elective-regime :cn-real-estate-deemed-rate`;
   the chosen provincial rate rides ctx `:tax-unit :deemed-rate`
   (defaults to the 1 % floor)."
  ^java.math.BigDecimal [disposal ctx]
  (if (elective-regime? disposal :cn-real-estate-deemed-rate)
    (let [proceeds (or (:disposal/proceeds-amount disposal) 0M)
          rate    (or (get-in ctx [:tax-unit :deemed-rate]) 0.01M)]
      ;; Deemed-gross returns the pre-multiplied liability — the
      ;; caller does NOT apply the 20 % schedule to it.
      (* proceeds rate))
    ;; Standard net-gain path — return the gain (caller applies 20 %).
    (realized-gain disposal)))

(defn- iit-classify-one
  "Classify one disposal for IIT purposes. Returns one of:
     {:lane :exempt :reason <kw>}                 — drop, no tax
     {:lane :deemed-gross  :liability <bigdec>}   — pre-multiplied tax
     {:lane :net-gain      :gain <bigdec>}        — for 20 % schedule
   Voided disposals never reach here (source excludes them)."
  [disposal ctx residency]
  (cond
    (listed-share-exempt? disposal residency)
    {:lane :exempt :reason :cn-caishui-1998-61 :disposal disposal}

    (manwuweiyi-exempt? disposal residency ctx)
    {:lane :exempt :reason :cn-manwuweiyi :disposal disposal}

    (and (= residency :resident-individual)
         (contains? #{:cn-residential :cn-non-residential}
                    (:disposal/asset-class disposal))
         (elective-regime? disposal :cn-real-estate-deemed-rate))
    {:lane :deemed-gross
     :liability (iit-real-estate-base disposal ctx)
     :disposal disposal}

    :else
    {:lane :net-gain
     :gain (realized-gain disposal)
     :disposal disposal}))

;; ============================================================================
;; IIT — component assembly
;; ============================================================================

(def iit-flat-rate
  "Convenience constant — 20 % IIT category-9 rate. Sourced statutorily
   via `parameter-value-at` `CN.IIT.CGT.flat-rate`."
  0.20M)

(defn- iit-component
  "Build the single multi-line IIT component. `net-gain-sum` is the
   sum of `:net-gain` lane gains (zero-padded — negative ⇒ 0, since
   individual category-9 has no carryforward). `deemed-gross-tax` is
   the sum of pre-multiplied deemed-rate liabilities."
  [{:keys [commodity authority]} ctx
   ^java.math.BigDecimal net-gain-sum
   ^java.math.BigDecimal deemed-gross-tax
   line-items
   provenance]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        rate      (or (statute/parameter-value-at db "CN.IIT.CGT.flat-rate" as-of)
                      iit-flat-rate)
        net-clamped (max 0M net-gain-sum)
        net-tax   (* net-clamped rate)
        gross     (+ net-tax deemed-gross-tax)
        prepaid   (bigdec (or (get-in ctx [:inputs :cn-iit-prepaid]) 0M))
        liability (- gross prepaid)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net-clamped commodity)
     :schedule        (ts/flat rate)
     :gross-liability (money/money gross commodity)
     :liability       (money/money liability commodity)
     :prepaid         (money/money prepaid commodity)
     :line-items      (vec (concat line-items
                                   [{:line :cn-category-9-net-gain
                                     :label "Net category-9 gain (20 % flat)"
                                     :value (money/money net-clamped commodity)}
                                    {:line :cn-category-9-net-tax
                                     :label "IIT @ 20 % on net gain"
                                     :value (money/money net-tax commodity)}
                                    {:line :cn-category-9-deemed-gross-tax
                                     :label "IIT @ 1-3 % deemed-gross election"
                                     :value (money/money deemed-gross-tax commodity)}
                                    {:line :cn-iit-prepaid
                                     :label "Transferee-withheld prepayment"
                                     :value (money/money prepaid commodity)}]))
     :composed-of     [:net-gain :deemed-gross]
     :provenance      provenance
     :jurisdiction-specific-codes {:lane :cn-iit-cgt
                                   :cn-iit-rate rate}}))

;; ============================================================================
;; EIT — exception handling (special restructuring etc.)
;; ============================================================================

(defn- eit-classify-one
  "Classify one disposal for EIT purposes. Most corporate CGT flows
   into ordinary EIT income via `:cit-base-additions`. The exceptions:

     :special-restructuring — equity-paid slice deferred
     :intra-group-100pct    — fully deferred (carry-over basis)
     :five-year-spread      — 1/5 recognised each year (year-1 only here)
     :normal                — fold full net gain into CIT base"
  [disposal]
  (cond
    (elective-regime? disposal :cn-special-restructuring)
    {:exception :special-restructuring :disposal disposal :gain (realized-gain disposal)}

    (elective-regime? disposal :cn-intra-group-100pct)
    {:exception :intra-group-100pct :disposal disposal :gain (realized-gain disposal)}

    (elective-regime? disposal :cn-five-year-spread)
    {:exception :five-year-spread :disposal disposal :gain (realized-gain disposal)}

    :else
    {:exception :normal :disposal disposal :gain (realized-gain disposal)}))

(defn- equity-paid-share
  "The share of the consideration paid in EQUITY (vs cash / other).
   Rides ctx `:tax-unit :equity-payment-share` per disposal external-id;
   defaults to 0.85 (the statutory minimum that triggers the
   five-criteria gate). Returns BigDecimal in [0, 1]."
  ^java.math.BigDecimal [disposal ctx]
  (let [eid (:disposal/external-id disposal)
        m   (get-in ctx [:tax-unit :equity-payment-share])]
    (cond
      (and (map? m) (contains? m eid)) (bigdec (get m eid))
      (number? m)                      (bigdec m)
      :else                            0.85M)))

(defn- eit-process-classified
  "Reduce the classified EIT disposals into `:cit-base-additions` +
   `:cit-base-deductions` slices.

   - :normal             → full gain to :cit-base-additions
   - :special-restructuring → equity-paid slice DEFERRED (deductions);
                              cash slice currently taxable (additions)
   - :intra-group-100pct → fully deferred (deductions = full gain;
                            additions = 0)
   - :five-year-spread   → 1/5 of gain to additions (current period);
                            4/5 to deductions (deferred to future yrs)"
  [classified ctx]
  (reduce
   (fn [{:keys [additions deductions deferral-lines current-lines]} c]
     (let [{:keys [exception disposal gain]} c
           eid (:disposal/external-id disposal)]
       (case exception
         :normal
         {:additions     (+ additions gain)
          :deductions    deductions
          :current-lines (conj current-lines
                               {:line :cn-eit-fold
                                :label (str "Ordinary EIT fold: " eid)
                                :value gain})
          :deferral-lines deferral-lines}

         :special-restructuring
         (let [share (equity-paid-share disposal ctx)
               deferred (* gain share)
               taxable  (- gain deferred)]
           {:additions     (+ additions taxable)
            :deductions    (+ deductions deferred)
            :current-lines (conj current-lines
                                 {:line :cn-special-restructuring-cash
                                  :label (str "SR cash slice: " eid)
                                  :value taxable})
            :deferral-lines (conj deferral-lines
                                  {:line :cn-special-restructuring-deferred
                                   :label (str "SR equity-paid deferral: " eid)
                                   :value deferred})})

         :intra-group-100pct
         {:additions     additions
          :deductions    (+ deductions gain)
          :current-lines current-lines
          :deferral-lines (conj deferral-lines
                                {:line :cn-intra-group-100pct
                                 :label (str "Caishui [2014] 109 §3 deferral: " eid)
                                 :value gain})}

         :five-year-spread
         (let [one-fifth (/ gain 5M)
               deferred  (* gain (/ 4M 5M))]
           {:additions     (+ additions one-fifth)
            :deductions    (+ deductions deferred)
            :current-lines (conj current-lines
                                 {:line :cn-five-year-spread-current
                                  :label (str "5-yr spread Y1: " eid)
                                  :value one-fifth})
            :deferral-lines (conj deferral-lines
                                  {:line :cn-five-year-spread-deferred
                                   :label (str "5-yr spread Y2-5: " eid)
                                   :value deferred})}))))
   {:additions     0M
    :deductions    0M
    :current-lines []
    :deferral-lines []}
   classified))

(defn- eit-component
  "Build the corporate EIT-fold component. `additions` = sum to be
   added to CIT base; `deductions` = sum to be deducted (deferrals).

   Note: per the corporate carry-forward rule (note 133 §1.8) capital
   losses on equity become ordinary EIT losses, eligible for the
   5-year NOL bucket on the CN CIT side. We pass them through to
   :cit-base-additions even when negative; the CIT provider handles
   the NOL bookkeeping."
  [{:keys [commodity authority]}
   ^java.math.BigDecimal additions
   ^java.math.BigDecimal deductions
   current-lines deferral-lines]
  {:kind            :capital-gains-tax
   :authority       authority
   :base            (money/money additions commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :line-items      (vec (concat current-lines deferral-lines))
   :jurisdiction-specific-codes {:lane :cn-eit-cgt
                                 :cit-base-additions   [additions]
                                 :cit-base-deductions  [deductions]}})

;; ============================================================================
;; The IIT provider
;; ============================================================================

(defrecord CnIitCgtProvider [id source authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period] :as ctx}]
    (when-not (:db ctx)
      (throw (ex-info ":db required in ctx for CN IIT CGT provider"
                      {:ctx-keys (keys ctx)})))
    (let [residency  (or (get-in ctx [:tax-unit :tax-residency])
                         :resident-individual)
          disposals  (ds/disposals-in source entity period)
          classified (mapv #(iit-classify-one % ctx residency) disposals)
          ;; Drop LAT-eligible developer disposals (handled by lat-provider).
          classified (remove #(= :cn-developer-real-estate
                                 (:disposal/asset-class (:disposal %)))
                             classified)
          ;; Exempt lines logged for audit but contribute no tax.
          exempt-lines (->> classified
                            (filter #(= :exempt (:lane %)))
                            (mapv (fn [{:keys [reason disposal]}]
                                    {:line  reason
                                     :label (str "Exempt: " (name reason))
                                     :value (money/money (realized-gain disposal) commodity)})))
          net-gain-sum    (reduce + 0M (->> classified
                                            (filter #(= :net-gain (:lane %)))
                                            (map :gain)))
          deemed-gross-tax (reduce + 0M (->> classified
                                             (filter #(= :deemed-gross (:lane %)))
                                             (map :liability)))
          provenance {:provider-id id :statute statute}
          components (if (and (zero? net-gain-sum)
                              (zero? deemed-gross-tax)
                              (empty? exempt-lines))
                       []
                       [(iit-component {:authority authority :commodity commodity}
                                       ctx
                                       net-gain-sum
                                       deemed-gross-tax
                                       exempt-lines
                                       provenance)])]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :cn :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; The EIT provider
;; ============================================================================

(defrecord CnEitCgtProvider [id source authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period] :as ctx}]
    (when-not (:db ctx)
      (throw (ex-info ":db required in ctx for CN EIT CGT provider"
                      {:ctx-keys (keys ctx)})))
    (let [disposals  (ds/disposals-in source entity period)
          ;; Drop LAT-eligible developer disposals (handled by lat-provider).
          disposals  (remove #(= :cn-developer-real-estate
                                 (:disposal/asset-class %))
                             disposals)
          classified (mapv eit-classify-one disposals)
          {:keys [additions deductions current-lines deferral-lines]}
          (eit-process-classified classified ctx)
          components (if (and (zero? additions) (zero? deductions))
                       []
                       [(eit-component {:authority authority :commodity commodity}
                                       additions deductions
                                       current-lines deferral-lines)])]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :cn :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn cn-iit-cgt-provider
  "Build the CN IIT CGT provider (category 9 财产转让所得). Required
   `:source` — a `DisposalSource`."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->CnIitCgtProvider (or id :cn-iit-cgt) source :cn-sat :CNY
                      "中华人民共和国个人所得税法 §3 §6 + Caishui [1998] 61 / Guoshuifa [2006] 108 / Caishui [2014] 81"))

(defn cn-eit-cgt-provider
  "Build the CN EIT CGT provider (property-transfer income exceptions
   only — base case folds into ordinary 25 % EIT via CIT provider).
   Required `:source` — a `DisposalSource`."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->CnEitCgtProvider (or id :cn-eit-cgt) source :cn-sat :CNY
                      "中华人民共和国企业所得税法 §4 §6 + Caishui [2009] 59 / Caishui [2014] 109 / Caishui [2014] 116"))

(defn install-statute!
  "Install the CN CGT statute (parameters + provisions) into `conn`."
  [conn]
  (cgt-statute/install! conn))
