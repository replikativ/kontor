(ns kontor.l10n-au.cgt-provider
  "AU capital-gains tax provider — `PeriodTaxProvider` (ADR-099) over the
   ADR-102 disposal substrate + ADR-101 statute-as-data. Research note
   129.

   ## One provider, four holder kinds

   AU CGT is structurally a holder-class × asset-class function (note
   129 §1.2 / §1.7). The same provider services all four holder kinds
   via `:kind`:

     :individual  — Div 115 50 % discount (sunset 1 Jul 2027); Subdiv 152
                    cascade; main-residence; collectables / personal-use
                    thresholds; foreign-resident TAP gate.
     :trust       — same as :individual.
     :super-fund  — Div 115 1/3 discount (NOT subject to 2027 sunset);
                    no Subdiv 152 (super funds don't qualify under
                    s152-10); main-residence n/a.
     :company     — NO discount (s115-100 silent for companies); gain
                    folds into CIT at the corporate rate.

   ## Single component, single base

   Unlike US (note 112 §5 four components — ST / LT / §1250 / NIIT) or
   JP (note 115 §5 multi-component per asset class), AU has ONE
   component per period: the net assessable capital gain after every
   discount / exemption / cascade application. No standalone schedule —
   the gain folds into the holder's PIT / CIT via
   `:jurisdiction-specific-codes {:pit-base-additions [...]
                                   :cit-base-additions [...]}`.

   This matches the JP `sōgō kazei` posture (note 115 §1.1) and the
   note 129 §5 recommendation.

   ## Subdivision 152 cascade — provider-internal logic

   Per note 129 §5, the four-step Subdiv 152 cascade lives in code, not
   in ADR-101 provisions: the cascade is a taxpayer-elected ORDERED
   sequence of mutually-interacting concessions whose semantics don't
   fit the `:base-add` / `:credit` / `:surtax` vocabulary. Cascade order
   (s152-10 cross-references):

     1. 15-year exemption (Subdiv 152-B, s152-105) — full exclusion;
        MUTUALLY EXCLUSIVE with the rest.
     2. 50 % discount (Div 115, s115-100) — for non-15y disposals.
     3. 50 % active-asset reduction (Subdiv 152-C, s152-205) — stacks
        multiplicatively with the discount → 75 % total reduction.
     4. $500 000 retirement exemption (Subdiv 152-D, s152-305) — cap
        consumes lifetime budget via `:inputs :au-retirement-cap-used`.
     5. Small-business rollover (Subdiv 152-E, s152-410) — deferral via
        kernel `:disposal/rollover-amount` (handled by the substrate).

   ## Other carve-outs handled provider-internally

   - **Main residence** (Subdiv 118-B, s118-110) — `:disposal/residence?
     true` + `:au-main-residence` exemption-claim → full exclusion.
   - **Collectables / personal-use thresholds** (s118-10) — first-element
     under $500 / $10 000 → gain disregarded.
   - **Foreign-resident TAP** (Div 855) — `:au-foreign-resident-non-tap`
     exemption-claim → not taxable.
   - **6-year absence rule** (s118-145) — out of scope; consumer pre-
     flags eligibility via `:disposal/residence?`.
   - **Indexation method** (s110-36) — taxpayer election via
     `:au-indexation-method` regime; v1 raises `:not-yet-implemented`
     (note 129 §6 Q6).

   ## DisposalSource

   Same protocol seam as US (ADR-103) — `kontor.disposal-source`."
  (:require [kontor.disposal-source :as ds]
            [kontor.l10n-au.cgt-statute :as cgt-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]))

;; ============================================================================
;; Constants — closed sets
;; ============================================================================

(def kinds
  "Closed set of holder kinds the AU CGT provider services."
  #{:individual :trust :super-fund :company})

(def cit-kinds
  "Holder kinds that fold into CIT (not PIT)."
  #{:company})

;; ============================================================================
;; Date helpers
;; ============================================================================

(defn- days-between
  "Whole days between two `java.util.Date` instants (Long)."
  ^long [^java.util.Date a ^java.util.Date b]
  (long (/ (- (.getTime b) (.getTime a))
           (* 1000 60 60 24))))

(defn- long-term?
  "True iff held strictly more than the s115-25 holding-period cutoff."
  [disposal cutoff-days]
  (let [acq (:disposal/acquired-on disposal)
        dis (:disposal/disposed-on disposal)]
    (and acq dis (> (days-between acq dis) cutoff-days))))

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- date-ms->date
  "Coerce a stored milliseconds-since-epoch BigDecimal back to a
   java.util.Date — used for the `AU.CGT.discount-sunset-date`
   parameter which is stored as a numeric (the parameter-value carrier
   is `bigdec`)."
  ^java.util.Date [^java.math.BigDecimal ms]
  (java.util.Date. (long ms)))

;; ============================================================================
;; Gain computation — provider-internal
;; ============================================================================

(defn- realized-gain
  "Gain (positive) or loss (negative) on one disposal, in the proceeds
   commodity: `proceeds − basis − rollover-amount`. Rollover slice is
   deferred (Subdiv 152-E or Div 124) — not recognised this period."
  ^java.math.BigDecimal [disposal]
  (let [p (or (:disposal/proceeds-amount disposal) 0M)
        b (or (:disposal/basis-amount disposal) 0M)
        r (or (:disposal/rollover-amount disposal) 0M)]
    (- p b r)))

;; ============================================================================
;; Exemption gates — return [reason :exempt?] or nil
;; ============================================================================

(defn- elective-regime-set
  "Coerce the cardinality-many `:disposal/elective-regime` to a set."
  [disposal]
  (set (:disposal/elective-regime disposal)))

(defn- exemption-claimed-set
  "Coerce the cardinality-many `:disposal/exemption-claimed` to a set."
  [disposal]
  (set (:disposal/exemption-claimed disposal)))

(defn- below-threshold-exempt?
  "True iff this disposal is exempt under s118-10 — collectables under
   $500 or personal-use under $10 000 — measured on the BASIS amount
   (the 'first element' acquisition cost) per note 129 §1.5 + s108-10.
   Both thresholds are statute parameters."
  [disposal db ^java.util.Date as-of]
  (let [basis (or (:disposal/basis-amount disposal) 0M)
        ac    (:disposal/asset-class disposal)
        coll  (statute/parameter-value-at db "AU.CGT.§118-10.collectable-threshold" as-of)
        pu    (statute/parameter-value-at db "AU.CGT.§118-10.personal-use-threshold" as-of)]
    (cond
      (and (= ac :au-collectable) coll (<= basis coll))     :s118-10-collectable
      (and (= ac :au-personal-use) pu   (<= basis pu))      :s118-10-personal-use
      :else nil)))

(defn- main-residence-exempt?
  "True iff the disposal claims the Div 118-B main-residence exemption
   AND `:disposal/residence?` is set (the gate). Returns the reason
   keyword if exempt; nil otherwise. The 6-year absence rule (s118-145)
   is consumer-flagged via `:residence?` per note 129 §1.9."
  [disposal]
  (when (and (:disposal/residence? disposal)
             (contains? (exemption-claimed-set disposal) :au-main-residence))
    :s118-110-main-residence))

(defn- foreign-non-tap-exempt?
  "True iff the disposal claims Div 855 foreign-resident non-TAP
   exemption. Returns the reason keyword if exempt; nil otherwise."
  [disposal]
  (when (contains? (exemption-claimed-set disposal) :au-foreign-resident-non-tap)
    :s855-foreign-resident-non-tap))

(defn- §152-15y-claimed?
  "True iff the disposal elects the Subdivision 152-B 15-year full
   exemption. Provider trusts the consumer's eligibility check (age 55+,
   continuous 15-year hold, retirement connection — note 129 §1.7)."
  [disposal]
  (contains? (exemption-claimed-set disposal) :au-§152-15y))

;; ============================================================================
;; Discount — Div 115 — holder-class × sunset gate
;; ============================================================================

(defn- discount-rate
  "Resolve the Div 115 discount rate for `kind` at `as-of`. For
   individuals + trusts after the 2027-07-01 sunset, returns 0 (the
   discount is repealed; the 30 % min-effective-rate floor is a TODO —
   note 129 §1.4)."
  ^java.math.BigDecimal [db ^java.util.Date as-of kind]
  (let [sunset-ms (statute/parameter-value-at db "AU.CGT.discount-sunset-date" as-of)
        sunset    (when sunset-ms (date-ms->date sunset-ms))
        sunset?   (and sunset (#{:individual :trust} kind)
                       (>= (.getTime as-of) (.getTime sunset)))]
    (cond
      sunset?                       0M
      (= kind :individual)          (statute/parameter-value-at db "AU.CGT.discount-rate.individual" as-of)
      (= kind :trust)               (statute/parameter-value-at db "AU.CGT.discount-rate.individual" as-of)
      (= kind :super-fund)          (statute/parameter-value-at db "AU.CGT.discount-rate.super-fund" as-of)
      (= kind :company)             (statute/parameter-value-at db "AU.CGT.discount-rate.company" as-of)
      :else                         0M)))

(defn- discount-eligible?
  "Discount applies when:
     - holder kind is non-:company (companies have 0 discount anyway);
     - asset held > 12 months;
     - the taxpayer has NOT elected the indexation method (mutually
       exclusive per note 129 §1.3)."
  [disposal kind cutoff-days]
  (and (not= kind :company)
       (long-term? disposal cutoff-days)
       (not (contains? (elective-regime-set disposal) :au-indexation-method))))

;; ============================================================================
;; Subdivision 152 cascade — provider-internal logic
;; ============================================================================

(defn- §152-active-reduction-elected?
  "True iff the disposal elects the Subdiv 152-C 50 % active-asset
   reduction."
  [disposal]
  (contains? (elective-regime-set disposal) :au-§152-50-active-reduction))

(defn- §152-retirement-elected?
  "True iff the disposal elects the Subdiv 152-D retirement exemption."
  [disposal]
  (contains? (elective-regime-set disposal) :au-§152-retirement-exemption))

(defn- apply-cascade
  "Apply the per-disposal post-discount cascade (Subdiv 152-C → 152-D)
   to `gain-after-discount`. Returns a map:

     {:assessable      <bigdec>  the residual gain after the cascade
      :active-reduced  <bigdec>  amount removed by §152-C (or 0)
      :retirement-used <bigdec>  amount consumed from the lifetime cap
                                  (or 0)
      :cap-remaining   <bigdec>  the holder's residual cap AFTER this
                                  disposal — provider returns it so the
                                  consumer can persist it via the
                                  :emits-inputs convention.}

   `cap-remaining-in` is the BigDecimal cap headroom going INTO this
   disposal (i.e. $500 000 − cumulative used so far). The cascade
   never consumes more than `min(elected, cap-remaining)`."
  [^java.math.BigDecimal gain-after-discount
   ^java.math.BigDecimal cap-remaining-in
   disposal]
  (let [active?   (§152-active-reduction-elected? disposal)
        after-152c (if active?
                     (* gain-after-discount 0.5M)
                     gain-after-discount)
        active-reduced (- gain-after-discount after-152c)
        ;; Retirement exemption — elects to exempt up to min(remaining
        ;; gain, cap-remaining-in). The consumer can target a specific
        ;; amount via `:inputs :au-retirement-exemption-target`; if
        ;; absent we exempt as much as possible.
        retire? (§152-retirement-elected? disposal)
        retire-used (if retire?
                      (max 0M (min after-152c cap-remaining-in))
                      0M)
        assessable (- after-152c retire-used)]
    {:assessable      assessable
     :active-reduced  active-reduced
     :retirement-used retire-used
     :cap-remaining   (- cap-remaining-in retire-used)}))

;; ============================================================================
;; Per-disposal compute — exemption gates → indexation guard → discount → cascade
;; ============================================================================

(defn- compute-disposal
  "Compute the assessable capital gain for one disposal. Returns:

     {:disposal       <input map>
      :raw-gain       <bigdec>  proceeds − basis − rollover
      :gain-after-disc <bigdec> after Div 115 discount
      :assessable     <bigdec>  AFTER cascade — feeds the net stream
      :exempt-reason  <kw|nil>  exemption gate that fired (else nil)
      :discount-rate  <bigdec>  rate applied (0 if not eligible)
      :line-items     [<{:line :label :value}>]
      :cap-remaining  <bigdec>  the holder's cap headroom after this
                                disposal — caller threads forward.}

   For losses (`raw-gain` < 0), discount / cascade are bypassed; the
   loss flows into the net-stream untouched (s115-105: no discount on
   capital losses).

   The indexation method (`:au-indexation-method`) is recognised but not
   computed in v1 — provider RAISES (`:not-yet-implemented`) to surface
   the gap explicitly per note 129 §6 Q6."
  [disposal {:keys [db kind cutoff-days as-of cap-remaining]}]
  (let [raw           (realized-gain disposal)
        commodity-sym (or (:commodity/symbol (:disposal/proceeds-commodity disposal))
                          "AUD")
        regimes       (elective-regime-set disposal)
        exempt-line   (fn [reason]
                        [{:line :gain          :label "Raw realised gain"     :value raw}
                         {:line :exempt-reason :label "Exemption fired"       :value reason}
                         {:line :assessable    :label "Assessable gain"       :value 0M}])
        below-thresh  (below-threshold-exempt? disposal db as-of)
        main-res      (main-residence-exempt? disposal)
        non-tap       (foreign-non-tap-exempt? disposal)
        §152-15?       (§152-15y-claimed? disposal)
        indexation?   (contains? regimes :au-indexation-method)]
    (cond
      indexation?
      (throw (ex-info "AU CGT :au-indexation-method not yet implemented (note 129 §6 Q6)"
                      {:disposal-id (:db/id disposal)
                       :external-id (:disposal/external-id disposal)
                       :as-of as-of}))

      ;; --- Exemption gates (in priority order) --------------------------
      below-thresh
      {:disposal disposal :raw-gain raw :gain-after-disc 0M :assessable 0M
       :exempt-reason below-thresh :discount-rate 0M
       :line-items (exempt-line below-thresh)
       :cap-remaining cap-remaining
       :commodity commodity-sym}

      main-res
      {:disposal disposal :raw-gain raw :gain-after-disc 0M :assessable 0M
       :exempt-reason main-res :discount-rate 0M
       :line-items (exempt-line main-res)
       :cap-remaining cap-remaining
       :commodity commodity-sym}

      non-tap
      {:disposal disposal :raw-gain raw :gain-after-disc 0M :assessable 0M
       :exempt-reason non-tap :discount-rate 0M
       :line-items (exempt-line non-tap)
       :cap-remaining cap-remaining
       :commodity commodity-sym}

      §152-15?
      {:disposal disposal :raw-gain raw :gain-after-disc 0M :assessable 0M
       :exempt-reason :s152-105-15y :discount-rate 0M
       :line-items [{:line :gain        :label "Raw realised gain"   :value raw}
                    {:line :§152-15y    :label "Subdiv 152-B 15-yr exemption" :value raw}
                    {:line :assessable  :label "Assessable gain"     :value 0M}]
       :cap-remaining cap-remaining
       :commodity commodity-sym}

      (neg? raw)
      ;; Loss — no discount, no cascade. Flow into net stream.
      {:disposal disposal :raw-gain raw :gain-after-disc raw :assessable raw
       :exempt-reason nil :discount-rate 0M
       :line-items [{:line :loss       :label "Realised capital loss" :value raw}
                    {:line :assessable :label "Loss to net stream"    :value raw}]
       :cap-remaining cap-remaining
       :commodity commodity-sym}

      :else
      (let [rate (if (discount-eligible? disposal kind cutoff-days)
                   (discount-rate db as-of kind)
                   0M)
            after-disc (- raw (* raw rate))
            {:keys [assessable active-reduced retirement-used
                    cap-remaining]}
            (apply-cascade after-disc cap-remaining disposal)]
        {:disposal       disposal
         :raw-gain       raw
         :gain-after-disc after-disc
         :assessable     assessable
         :exempt-reason  nil
         :discount-rate  rate
         :line-items     (cond-> [{:line :gain        :label "Raw realised gain"     :value raw}]
                           (pos? rate)
                           (conj {:line :discount    :label (str "Div 115 discount × " rate)
                                  :value (- after-disc raw)})
                           (pos? active-reduced)
                           (conj {:line :§152-C-50pct
                                  :label "Subdiv 152-C 50 % active-asset reduction"
                                  :value (- active-reduced)})
                           (pos? retirement-used)
                           (conj {:line :§152-D-retirement
                                  :label "Subdiv 152-D retirement exemption (consumed)"
                                  :value (- retirement-used)})
                           true
                           (conj {:line :assessable  :label "Assessable gain (net of cascade)"
                                  :value assessable}))
         :cap-remaining  cap-remaining
         :commodity      commodity-sym}))))

;; ============================================================================
;; Loss netting — single AU bucket
;; ============================================================================

(defn- apply-losses
  "Apply current-year capital losses + carry-in losses to current-year
   gains. AU has a SINGLE capital-loss bucket (note 129 §1.6): losses
   apply to gains BEFORE the discount (s102-5(1)(b) order is handled
   per-disposal — losses sit on their own row with discount=0, so the
   sum-after-cascade is the correct netting base).

   Returns `{:net <bigdec> :loss-carry-forward <bigdec>}`. A `:net`
   below zero is the new carryforward; the `:net` above zero is the
   period's assessable amount."
  [computed carry-in]
  (let [sum (reduce + 0M (map :assessable computed))
        after-carry (- sum (or carry-in 0M))]
    (if (neg? after-carry)
      {:net 0M :loss-carry-forward (- after-carry)}
      {:net after-carry :loss-carry-forward 0M})))

;; ============================================================================
;; Components
;; ============================================================================

(defn- one-component
  "Build the single AU CGT component. `net` is the period-assessable
   capital gain (≥ 0). `kind` drives whether it folds into PIT or CIT
   base-additions."
  [{:keys [commodity authority kind]}
   ^java.math.BigDecimal net
   computed
   ^java.math.BigDecimal cap-remaining]
  (let [fold-code (if (cit-kinds kind) :cit-base-additions :pit-base-additions)
        wrap-value (fn [v]
                     ;; Numeric values become Money; non-numeric values
                     ;; (e.g. exemption-reason keywords) pass through.
                     (if (number? v)
                       (money/money (bigdec v) commodity)
                       v))
        per-disposal-line-items
        (vec (mapcat
              (fn [c]
                (let [id (or (:disposal/external-id (:disposal c))
                             (str "eid-" (:db/id (:disposal c))))]
                  (mapv (fn [li]
                          (assoc li :disposal id
                                 :value (wrap-value (:value li))))
                        (:line-items c))))
              computed))
        roll-summary [{:line :net-assessable
                       :label "Net assessable capital gain (period)"
                       :value (money/money net commodity)}
                      {:line :§152-D-cap-remaining
                       :label "Subdivision 152-D lifetime cap headroom (post-period)"
                       :value (money/money cap-remaining commodity)}]]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :regime          kind
     :line-items      (into per-disposal-line-items roll-summary)
     :jurisdiction-specific-codes {fold-code [net]
                                   :lane :au-net-capital-gain
                                   :holder-kind kind}}))

;; ============================================================================
;; The provider
;; ============================================================================

(defrecord AuCapitalGainsTaxProvider
           [id source authority commodity statute kind]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (when-not (contains? kinds kind)
      (throw (ex-info ":kind must be one of #{:individual :trust :super-fund :company}"
                      {:kind kind})))
    (let [db          (or (:db ctx)
                          (throw (ex-info ":db required in ctx for AU CGT provider"
                                          {:ctx-keys (keys ctx)})))
          as-of       (as-of-from-ctx ctx)
          cutoff-days (long (or (statute/parameter-value-at
                                 db "AU.CGT.holding-period-cutoff-days" as-of)
                                365))
          ret-cap     (or (statute/parameter-value-at
                           db "AU.CGT.§152-D.retirement-cap-lifetime" as-of)
                          500000M)
          ;; Retirement-cap headroom: lifetime cap minus consumer-supplied
          ;; cumulative-used (per CGT-concession stakeholder; provider
          ;; treats `:au-retirement-cap-used` as a scalar for v1 — the
          ;; per-stakeholder map shape (note 129 §4 Gap 6) is a future
          ;; refinement, easily added by reading the right key into
          ;; cap-remaining at compute time).
          used        (or (get-in inputs [:au-retirement-cap-used]) 0M)
          carry-in    (or (:capital-loss-carryforward inputs)
                          (:au-capital-loss-carryforward inputs)
                          {})
          carry-amt   (or (:capital carry-in) 0M)
          disposals   (ds/disposals-in source entity period)
          ;; Thread cap-remaining through the computed-disposal stream so
          ;; the retirement exemption is consumed in the disposal-order
          ;; sequence (it's a lifetime budget — later disposals see what
          ;; earlier ones already consumed).
          init-cap    (max 0M (- ret-cap used))
          {:keys [computed cap-remaining]}
          (reduce
           (fn [{:keys [computed cap-remaining]} d]
             (let [c (compute-disposal d {:db db :kind kind :cutoff-days cutoff-days
                                          :as-of as-of :cap-remaining cap-remaining})]
               {:computed (conj computed c) :cap-remaining (:cap-remaining c)}))
           {:computed [] :cap-remaining init-cap}
           disposals)
          {:keys [net]} (apply-losses computed carry-amt)
          components (if (zero? (count computed))
                       []
                       [(one-component {:authority authority :commodity commodity :kind kind}
                                       net computed cap-remaining)])]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :au :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn au-cgt-provider
  "Build an AU CGT provider. Required:
     :source  — a `DisposalSource` (kernel protocol)
     :kind    — one of #{:individual :trust :super-fund :company}

   Optional:
     :id        — provider id (defaults derived from :kind)
     :authority — taxing authority (default :au-ato)
     :commodity — money commodity (default :AUD)"
  [{:keys [source kind id authority commodity]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (when-not (contains? kinds kind)
    (throw (ex-info (str ":kind must be one of " kinds)
                    {:kind kind})))
  (->AuCapitalGainsTaxProvider
   (or id (keyword (str "au-cgt-" (name kind))))
   source
   (or authority :au-ato)
   (or commodity :AUD)
   "ITAA 1997 Div 115 / Subdiv 152 / Div 118-B / Div 855"
   kind))

(defn install-statute!
  "Install the AU CGT statute (parameters + values) into `conn`."
  [conn]
  (cgt-statute/install! conn))
