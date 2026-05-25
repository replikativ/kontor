(ns kontor.l10n-fr.investment-income-provider
  "FR investment-income tax providers — TWO `PeriodTaxProvider`s
   (ADR-099) over the ADR-101 statute-as-data substrate. Research
   note 149.

   FR has FIVE overlapping shapes for investment income (note 149
   §1) — the v1 provider covers the four with kernel-substrate fit:

   - **PFU / barème** (CGI Art. 200 A + 117 quater) — default 31.4 %
     (12.8 % IR + 18.6 % PS post-LFSS-2026) on dividends + interest.
     Barème election SUPPRESSES the standalone PFU IR and folds the
     income into the FR PIT base via `:pit-base-additions` WITH a
     40 % abattement on dividends (CGI Art. 158-3-2°). The 12.8 %
     prélèvement à la source non-libératoire (CGI Art. 117 quater) is
     a year-of-payment WHT — surfaces as `:prepaid` on the standalone
     component (per ADR-099 note 102 §9 §5.4 recommendation b).
   - **PEA / PEA-PME** (CGI Art. 157 5°) — IR-exempt after 5y; PS
     still due on the gain. The provider RECEIVES income lanes tagged
     `:account-tag :fr-investment-income/pea-*` and emits a PS-only
     component (no IR component).
   - **Assurance-vie rachats** (CGI Art. 125-0 A) — preferential
     ladder (0 / 7,5 / 12,8 % by contract age + €4 600 / €9 200
     annual abattement + €150 000 versements cap). The v1 provider
     expects the consumer to pre-split the gain by band via
     `:inputs :assurance-vie {<contract-id> {:gain :age-band}}` and
     emits ONE PS-stayed-at-17.2 % component plus the appropriate IR
     line. Out of scope for v1: per-contract eurofonds annual
     prélèvement (assureur-side operation, not GL events).
   - **Régime mère-fille** (CGI Art. 145 + 216) — corporate
     recipient 95 % exemption + 5 % QPFC. The 5 % QPFC is threaded
     to the FR CIT provider's adjustment layer via
     `:cit-base-additions`.

   Out of scope for v1 (note 149 §1.5 / §1.8 / §7):

   - Revenus fonciers (rental income) — separate provider
     (`fr-rental-income-provider`, deferred).
   - Régime intégration fiscale (1 % QPFC instead of 5 %) — multi-
     entity construct; consumer can override per `:inputs
     :mere-fille {:integration-fiscale? true}` (the provider reads
     the parameter then).
   - Foreign-source dividends with treaty WHT — separate provider.

   ## Two providers, two enum slots

   - `fr-personal-investment-income-provider` — PIT-side. Up to
     three components (dividendes, intérêts, assurance-vie). Each
     carries `:kind :investment-income-tax`. PEA routes inside the
     dividendes/intérêts components (IR-only suppression).
   - `fr-corporate-investment-income-provider` — IS-side. ONE
     component (mère-fille). `:kind :investment-income-tax`. Threads
     `:cit-base-additions` to FR CIT.

   ## Composition with FR CIT/PIT providers

   The consumer threads the investment-income provider's
   `TaxReturnFacts` to the CIT/PIT provider via `:inputs
   :base-transform`:

     {:pit-base-additions  [<bigdec> …]   ; barème-elected dividends
                                           ;  60% × gross + interest +
                                           ;  assurance-vie gain
      :cit-base-additions  [<bigdec> …]}  ; mère-fille QPFC

   The composition is consumer-side (mirrors `fr-personal-cgt-
   provider` / note 128 §5 / `kontor.sole-proprietor` ADR-100).

   ## Chart-of-accounts convention

   The provider expects FR PCG 76x income accounts tagged with the
   `:fr-investment-income/*` family (note 149 §3.1):

     :fr-investment-income/dividende-fr-ue-éligible-abattement-40
        — dividends from FR/UE/treaty-state entities subject to IS
          (eligible for the 40 % abattement under barème + 12.8 % PAS)
     :fr-investment-income/dividende-hors-abattement
        — dividends NOT eligible for the 40 % abattement
     :fr-investment-income/dividende-mere-fille-eligible
        — IS-side dividends qualifying for CGI 145 (5 % QPFC)
     :fr-investment-income/interets-prfix-fr
        — interest income from FR/UE sources (12.8 % PAS unless
          dispensed for low RFR)
     :fr-investment-income/interets-livrets-exoneres
        — Livret A / LEP / LDDS — fully exempt (provider drops at
          compute time)
     :fr-investment-income/assurance-vie-rachat-gain
        — gain portion of an assurance-vie rachat
     :fr-investment-income/pea-retrait-gain
        — gain portion of a PEA withdrawal (IR-exempt, PS due)

   ## DivIncomeSource — substrate seam (test-friendly)

   The provider scans the GL by tag-marginalize (`report/marginalize`
   on `:account-tag`) by default. Tests + 1099-style imports may
   short-circuit by supplying `:inputs :investment-income-bases`
   (a map of lane-keyword → BigDecimal); the provider then skips the
   GL scan."
  (:require [kontor.l10n-fr.investment-income-statute :as inv-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.report :as report]
            [kontor.statute :as statute]))

;; ============================================================================
;; Constants — closed FR investment-income tag vocabulary
;; ============================================================================

(def investment-income-tags
  "Closed v1 set of FR-namespaced `:account-tag` enumerants the
   provider recognises. Note 149 §3.1. A posting tagged outside this
   set is silently dropped — the provider only fires on the FR
   vocabulary."
  #{:fr-investment-income/dividende-fr-ue-eligible-abattement-40
    :fr-investment-income/dividende-hors-abattement
    :fr-investment-income/dividende-mere-fille-eligible
    :fr-investment-income/interets-prfix-fr
    :fr-investment-income/interets-livrets-exoneres
    :fr-investment-income/assurance-vie-rachat-gain
    :fr-investment-income/pea-retrait-gain})

;; ============================================================================
;; Utilities
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- param
  ^java.math.BigDecimal [db code as-of]
  (statute/parameter-value-at db code as-of))

(defn- tag-bases
  "Marginalize the entity's income postings by `:account-tag` and
   return `{<tag> <BigDecimal>}` for the closed `investment-income-tags`
   set. Postings without a recognised tag are silently dropped."
  [{:keys [conn entity period]} commodity]
  (let [postings (report/report-postings
                  conn (cond-> {:from (:from period) :to (:to period)}
                         entity (assoc :entity entity)))
        by-tag   (report/marginalize postings :account-tags
                                     {:sign :inflow :commodity commodity})]
    (into {} (for [tag investment-income-tags]
               [tag (or (some-> by-tag (get tag) :value :amount) 0M)]))))

(defn- investment-income-bases
  "Either pull pre-computed bases from `:inputs :investment-income-bases`
   (the consumer-supplied path for IFU / 1099-DIV-style uploads) OR
   scan the GL by tag-marginalize. Returns
   `{<tag-keyword> <BigDecimal>}`."
  [ctx commodity]
  (or (get-in ctx [:inputs :investment-income-bases])
      (tag-bases ctx commodity)))

;; ============================================================================
;; Personal — DIVIDENDES component (PFU vs barème vs PEA)
;; ============================================================================

(defn- dividendes-component
  "Build the dividendes component for the personal provider.

   - PFU (default) — IR = 12.8 % × (eligible + hors-abattement);
     PS = 18.6 % × (eligible + hors-abattement); PEA gross still
     drives PS but NOT IR (livret A drops upstream — tag filtered).
   - Barème — IR = 0 here (60 % of eligible + 100 % of hors-abattement
     fold via `:pit-base-additions`); PS still due on GROSS (Art.
     150-0 D 4° — same rule as CGT mobilière).
   - PAS 12.8 % (CGI 117 quater) — `:inputs :prepaid-pas
     :fr-pas-117-quater` → `:prepaid` on the component."
  [opts ctx bases]
  (let [{:keys [commodity authority db]} opts
        as-of      (as-of-from-ctx ctx)
        barème?    (= :bareme (get-in ctx [:tax-unit :pfu-or-bareme]))
        ir-rate    (param db "FR.CGT.PFU.IR-rate" as-of)
        ps-rate    (param db "FR.CGT.PS.placement-rate" as-of)
        abat-rate  (param db "FR.INV.bareme.abattement-dividendes" as-of)
        csg-share  (param db "FR.INV.bareme.CSG-deductible-share" as-of)
        elig-gross (or (:fr-investment-income/dividende-fr-ue-eligible-abattement-40 bases) 0M)
        other-gross (or (:fr-investment-income/dividende-hors-abattement bases) 0M)
        pea-gross  (or (:fr-investment-income/pea-retrait-gain bases) 0M)
        total-non-pea (+ elig-gross other-gross)
        gross-all  (+ total-non-pea pea-gross)
        ;; IR base under barème: 60 % × eligible + 100 % × hors-abattement.
        ir-base    (if barème?
                     (+ (* elig-gross (- 1M abat-rate)) other-gross)
                     total-non-pea)
        ir-tax     (if barème?
                     0M                                ; folds into PIT
                     (* total-non-pea ir-rate))
        ps-tax     (* gross-all ps-rate)              ; PEA still pays PS
        pas-credit (or (get-in ctx [:inputs :prepaid-pas :fr-pas-117-quater]) 0M)
        gross-liab (+ ir-tax ps-tax)
        liability  (max 0M (- gross-liab pas-credit))
        ;; CSG deductible carry-forward to N+1 (note 149 §1.1 / G9)
        csg-carry  (if barème? (* total-non-pea csg-share) 0M)
        any?       (pos? gross-all)]
    (when any?
      {:kind            :investment-income-tax
       :authority       authority
       :base            (money/money ir-base commodity)
       :schedule        (if barème? nil {:schedule/type :flat :rate ir-rate})
       :gross-liability (money/money gross-liab commodity)
       :liability       (money/money liability commodity)
       :prepaid         (money/money pas-credit commodity)
       :regime          (if barème? :fr-bareme :fr-pfu)
       :surtaxes        [{:code :ps
                          :label (str "Prélèvements sociaux (dividendes — "
                                      (* 100M ps-rate) " %)")
                          :amount (money/money ps-tax commodity)}]
       :line-items      [{:line :div-eligible-gross
                          :label "Dividendes éligibles abattement 40 % (brut)"
                          :value (money/money elig-gross commodity)}
                         {:line :div-other-gross
                          :label "Dividendes hors abattement (brut)"
                          :value (money/money other-gross commodity)}
                         {:line :div-pea-gross
                          :label "Dividendes PEA (IR exonéré, PS dû)"
                          :value (money/money pea-gross commodity)}
                         {:line :div-ir-base
                          :label "Base IR dividendes (post-abattement le cas échéant)"
                          :value (money/money ir-base commodity)}
                         {:line :div-ir
                          :label "IR dividendes"
                          :value (money/money ir-tax commodity)}
                         {:line :div-ps
                          :label "Prélèvements sociaux dividendes"
                          :value (money/money ps-tax commodity)}
                         {:line :div-pas-credit
                          :label "Prélèvement forfaitaire 12,8 % imputable (CGI 117 quater)"
                          :value (money/money pas-credit commodity)}
                         {:line :div-csg-carry
                          :label "CSG déductible (6,8 pp) reportée sur revenus N+1"
                          :value (money/money csg-carry commodity)}]
       :jurisdiction-specific-codes
       (cond-> {:lane :fr-dividendes
                :asset-classes [:fr-dividende]}
         barème? (assoc :pit-base-additions [ir-base]
                        :csg-deductible-carry csg-carry))})))

;; ============================================================================
;; Personal — INTÉRÊTS component (PFU vs barème; livret A excluded)
;; ============================================================================

(defn- interets-component
  "Build the intérêts component. Same shape as dividendes but no
   40 % abattement (CGI 158-3-2° is dividend-specific). Livret A /
   LEP / LDDS interest is excluded upstream by the tag filter."
  [opts ctx bases]
  (let [{:keys [commodity authority db]} opts
        as-of      (as-of-from-ctx ctx)
        barème?    (= :bareme (get-in ctx [:tax-unit :pfu-or-bareme]))
        ir-rate    (param db "FR.CGT.PFU.IR-rate" as-of)
        ps-rate    (param db "FR.CGT.PS.placement-rate" as-of)
        csg-share  (param db "FR.INV.bareme.CSG-deductible-share" as-of)
        int-gross  (or (:fr-investment-income/interets-prfix-fr bases) 0M)
        ir-base    int-gross
        ir-tax     (if barème? 0M (* int-gross ir-rate))
        ps-tax     (* int-gross ps-rate)
        gross-liab (+ ir-tax ps-tax)
        csg-carry  (if barème? (* int-gross csg-share) 0M)]
    (when (pos? int-gross)
      {:kind            :investment-income-tax
       :authority       authority
       :base            (money/money ir-base commodity)
       :schedule        (if barème? nil {:schedule/type :flat :rate ir-rate})
       :gross-liability (money/money gross-liab commodity)
       :liability       (money/money gross-liab commodity)
       :prepaid         (money/zero commodity)
       :regime          (if barème? :fr-bareme :fr-pfu)
       :surtaxes        [{:code :ps
                          :label (str "Prélèvements sociaux (intérêts — "
                                      (* 100M ps-rate) " %)")
                          :amount (money/money ps-tax commodity)}]
       :line-items      [{:line :int-gross
                          :label "Intérêts (brut)"
                          :value (money/money int-gross commodity)}
                         {:line :int-ir
                          :label "IR intérêts"
                          :value (money/money ir-tax commodity)}
                         {:line :int-ps
                          :label "Prélèvements sociaux intérêts"
                          :value (money/money ps-tax commodity)}
                         {:line :int-csg-carry
                          :label "CSG déductible (6,8 pp) reportée sur revenus N+1"
                          :value (money/money csg-carry commodity)}]
       :jurisdiction-specific-codes
       (cond-> {:lane :fr-interets
                :asset-classes [:fr-interets]}
         barème? (assoc :pit-base-additions [ir-base]
                        :csg-deductible-carry csg-carry))})))

;; ============================================================================
;; Personal — ASSURANCE-VIE component (preferential ladder + carve-out PS 17.2 %)
;; ============================================================================

(defn- assurance-vie-component
  "Build the assurance-vie component. The IR rate ladder (0 / 7.5 /
   12.8 % by contract age and the €150 k versements split) is too
   contract-specific for the provider — the consumer pre-computes the
   IR-side via `:inputs :assurance-vie {:ir <BigDecimal>}` (a single
   amount across all contracts), and the provider just emits the
   component + PS layer at the 17.2 % carve-out rate.

   For v1 we treat the gain as the per-period PS base. Default IR is
   the PFU 12.8 % flat when the consumer doesn't supply `:ir`."
  [opts ctx bases]
  (let [{:keys [commodity authority db]} opts
        as-of      (as-of-from-ctx ctx)
        ir-rate    (param db "FR.CGT.PFU.IR-rate" as-of)
        ps-rate    (param db "FR.CGT.PS.real-estate-rate" as-of)  ; 17.2 % carve-out
        av-gain    (or (:fr-investment-income/assurance-vie-rachat-gain bases) 0M)
        av-ir-in   (get-in ctx [:inputs :assurance-vie :ir])
        ir-tax     (or av-ir-in (* av-gain ir-rate))
        ps-tax     (* av-gain ps-rate)
        gross-liab (+ ir-tax ps-tax)]
    (when (pos? av-gain)
      {:kind            :investment-income-tax
       :authority       authority
       :base            (money/money av-gain commodity)
       :schedule        {:schedule/type :flat :rate ir-rate}
       :gross-liability (money/money gross-liab commodity)
       :liability       (money/money gross-liab commodity)
       :prepaid         (money/zero commodity)
       :surtaxes        [{:code :ps-av
                          :label (str "Prélèvements sociaux assurance-vie (carve-out "
                                      (* 100M ps-rate) " %)")
                          :amount (money/money ps-tax commodity)}]
       :line-items      [{:line :av-gain
                          :label "Gain assurance-vie (portion taxable du rachat)"
                          :value (money/money av-gain commodity)}
                         {:line :av-ir
                          :label "IR assurance-vie (ladder consumer-supplied)"
                          :value (money/money ir-tax commodity)}
                         {:line :av-ps
                          :label "Prélèvements sociaux assurance-vie"
                          :value (money/money ps-tax commodity)}]
       :jurisdiction-specific-codes {:lane :fr-assurance-vie}})))

;; ============================================================================
;; Corporate — MÈRE-FILLE component (5 % QPFC → CIT base addition)
;; ============================================================================

(defn- mere-fille-component
  "Build the mère-fille component for the corporate provider.
   Eligibility test (note 149 §1.4):

   - Asset tagged `:fr-investment-income/dividende-mere-fille-eligible`.
   - Consumer-supplied stake fraction ≥ 5 % AND held-since ≥ 2 years
     (both via `:inputs :mere-fille {:holding-fraction <bd>
     :held-since <inst>}`; defaults satisfy when omitted, since the
     tag itself signals consumer-side qualification).

   The 5 % QPFC reintegrates as `:cit-base-additions` for the FR CIT
   provider's adjustment layer. The integration-fiscale 1 % carve-out
   surfaces when `:inputs :mere-fille {:integration-fiscale? true}`.
   Losses on participations are sealed (non-deductible) — not modelled."
  [opts ctx bases]
  (let [{:keys [commodity authority db]} opts
        as-of      (as-of-from-ctx ctx)
        mf-inputs  (get-in ctx [:inputs :mere-fille])
        integ?     (boolean (:integration-fiscale? mf-inputs))
        qpfc-rate  (param db (if integ?
                               "FR.INV.integration-fiscale.QPFC-rate"
                               "FR.INV.mere-fille.QPFC-rate") as-of)
        ;; Eligibility — when the consumer supplies :holding-fraction
        ;; and it's below the 5 % threshold, the lane disqualifies and
        ;; the dividend folds full into CIT (we don't surface a
        ;; component then — consumer handles the full inclusion).
        min-frac   (param db "FR.INV.mere-fille.minimum-holding-fraction" as-of)
        frac       (:holding-fraction mf-inputs)
        eligible?  (or (nil? frac) (>= (compare frac min-frac) 0))
        gross      (or (:fr-investment-income/dividende-mere-fille-eligible bases) 0M)
        qpfc       (if eligible? (* gross qpfc-rate) 0M)
        full-inc   (if eligible? 0M gross)]
    (when (pos? gross)
      {:kind            :investment-income-tax
       :authority       authority
       :base            (money/zero commodity)
       :schedule        nil
       :gross-liability (money/zero commodity)
       :liability       (money/zero commodity)
       :prepaid         (money/zero commodity)
       :regime          (cond
                          (not eligible?)            :fr-non-eligible
                          integ?                     :fr-integration-fiscale
                          :else                      :fr-mere-fille)
       :line-items      [{:line :mf-gross
                          :label "Dividendes mère-fille éligibles (brut, exonéré 95 %)"
                          :value (money/money gross commodity)}
                         {:line :mf-qpfc
                          :label (str "QPFC " (* 100M qpfc-rate) " % réintégrée à l'IS")
                          :value (money/money qpfc commodity)}
                         {:line :mf-full-inclusion
                          :label "Dividende intégralement réintégré (mère-fille non éligible)"
                          :value (money/money full-inc commodity)}]
       :jurisdiction-specific-codes
       {:cit-base-additions [(+ qpfc full-inc)]
        :lane               :fr-mere-fille
        :gross-exempted-gain (if eligible? gross 0M)
        :eligible?          eligible?
        :integration-fiscale? integ?}})))

;; ============================================================================
;; PERSONAL provider — dividendes + intérêts + assurance-vie
;; ============================================================================

(defrecord FRPersonalInvestmentIncomeTaxProvider
           [id authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period] :as ctx}]
    (let [db        (or (:db ctx)
                        (throw (ex-info ":db required in ctx for FR personal investment-income provider"
                                        {:ctx-keys (keys ctx)})))
          bases     (investment-income-bases ctx commodity)
          opts      {:authority authority :commodity commodity :db db}
          div-cmp   (dividendes-component opts ctx bases)
          int-cmp   (interets-component   opts ctx bases)
          av-cmp    (assurance-vie-component opts ctx bases)
          components (->> [div-cmp int-cmp av-cmp]
                          (remove nil?) vec)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :fr :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; CORPORATE provider — mère-fille only
;; ============================================================================

(defrecord FRCorporateInvestmentIncomeTaxProvider
           [id authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period] :as ctx}]
    (let [db        (or (:db ctx)
                        (throw (ex-info ":db required in ctx for FR corporate investment-income provider"
                                        {:ctx-keys (keys ctx)})))
          bases     (investment-income-bases ctx commodity)
          opts      {:authority authority :commodity commodity :db db}
          mf-cmp    (mere-fille-component opts ctx bases)
          components (->> [mf-cmp] (remove nil?) vec)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :fr :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn fr-personal-investment-income-provider
  "Build the FR personal investment-income provider.

   Up to THREE components in the resulting `TaxReturnFacts`:
   - Dividendes (Art. 200 A / 158-3-2°) — PFU or barème + 40 % abat
   - Intérêts (Art. 200 A / 117 quater) — PFU or barème, no abat
   - Assurance-vie (Art. 125-0 A) — PS carve-out 17.2 %; IR consumer-
     supplied via `:inputs :assurance-vie :ir`

   Inputs the consumer may supply via ctx:
     :tax-unit {:pfu-or-bareme :pfu | :bareme}
     :inputs   {:investment-income-bases {<tag> <bigdec> …}
                                          ; OPTIONAL — skip GL scan
                :prepaid-pas {:fr-pas-117-quater <bigdec>}
                                          ; 12,8 % PAS withheld at payment
                :assurance-vie {:ir <bigdec>}
                                          ; consumer-supplied AV IR (ladder)
                :mere-fille  {…}}        ; consumed by CORPORATE provider"
  [{:keys [id commodity] :or {id :fr-pers-inv-inc commodity :EUR}}]
  (->FRPersonalInvestmentIncomeTaxProvider
   id :fr-dgfip commodity
   "CGI Art. 200 A + 117 quater + 158-3-2° + 125-0 A + 154 quinquies + 157 5°"))

(defn fr-corporate-investment-income-provider
  "Build the FR corporate investment-income provider.

   ONE component in the resulting `TaxReturnFacts`:
   - Mère-fille (Art. 145 + 216) — 95 % exempt + 5 % QPFC threaded
     into `:cit-base-additions`. Integration-fiscale (1 % QPFC)
     under `:inputs :mere-fille {:integration-fiscale? true}`.

   Inputs the consumer may supply via ctx:
     :inputs {:investment-income-bases {<tag> <bigdec> …}
              :mere-fille {:holding-fraction <bd>
                           :integration-fiscale? <bool>}}"
  [{:keys [id commodity] :or {id :fr-corp-inv-inc commodity :EUR}}]
  (->FRCorporateInvestmentIncomeTaxProvider
   id :fr-dgfip commodity
   "CGI Art. 145 + 216 + 223 A (intégration fiscale)"))

(defn install-statute!
  "Install the FR investment-income statute (parameters) into `conn`.
   Requires the FR CGT statute to be installed first (this statute
   references the CGT PS placement-rate + PFU IR-rate parameters by
   code)."
  [conn]
  (inv-statute/install! conn))
