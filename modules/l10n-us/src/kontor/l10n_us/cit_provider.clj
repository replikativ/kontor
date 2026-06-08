(ns kontor.l10n-us.cit-provider
  "US federal corporate income tax provider — Form 1120 / IRC §11 —
   built as a `PeriodTaxProvider` (ADR-099) over the statute-as-data
   substrate (ADR-101). Mirrors
   `kontor.l10n-fr.cit-provider` (the closest single-component flat-
   rate CIT template) minus the schedule-override / surtax / credit
   provisions. **Federal-only.** State CIT is OUT of substrate per
   ADR-005 / ADR-010 /
   engines (Avalara, Vertex) for sub-federal income tax. The CA
   federal+provincial pattern (ADR-107) is the structural template
   if/when state CIT is opened — N-component fan-out via
   `:tax-unit :state-allocation`.

   The provider does THREE things and nothing else:

   1. Reads the §11 flat rate from `:parameter` data
      (`US.CIT.§11.rate`, post-TCJA 21 %).
   2. For the single `:cit` component sets `:component :cit` in ctx,
      calls `kontor.tax.statute/apply-provisions` for the relevant
      concepts (`:base-transform-add`, `:base-transform-deduct`,
      `:surtax`, `:refundable-credit`, `:non-refundable-credit`),
      folds base-side adjustments + applies the flat schedule + folds
      tax-side adjustments, returns the component map.
   3. Assembles a 1-component `TaxReturnFacts`.

   Everything else — the §11 CGT corp-net fold, the §172 NOL stub, the
   §163(j) / §250 stubs — lives in `kontor.l10n-us.cit-statute` as
   `:provision` data, NOT in this provider's code.

   ## Inputs the consumer supplies

   `:tax-unit` (company config — all optional; v1 federal-only):
     {} (no config keys consulted in v1)

   `:inputs` (period facts):
     {:book-profit                <BigDecimal>  required —
                                                Schedule M-1 / M-3
                                                book-to-tax reconciled
                                                taxable income (the
                                                consumer maintains the
                                                book→tax delta outside
                                                the substrate)
      :cgt-cit-base-additions     <BigDecimal>  optional — net capital
                                                gain + §1245/§1250
                                                recapture (cgt-provider
                                                `:cit-base-additions`
                                                lane sum)
      :nol-applied                <BigDecimal>  optional — consumer-
                                                pre-computed §172 NOL
                                                offset (subject to the
                                                80 % cap, enforced by
                                                consumer)
      :§163j-disallowed-interest  <BigDecimal>  optional — consumer-
                                                pre-computed §163(j)
                                                add-back
      :§250-deduction             <BigDecimal>  optional — consumer-
                                                pre-computed §250 FDII
                                                / GILTI deduction}

   ## Out-of-substrate

   - **CAMT (§55-§59 post-IRA-2022).** Deferred to v1.x. The substrate
     would express via `kontor.tax.statute/compose-greater-of` (same
     shape as AT Mindest-KöSt). v1 ships placeholder parameter rows
     only; no provision; no provider computation.
   - **State CIT.** Q5.3 federal-only in v1.
   - **§59A BEAT, individual AMT, §163(j) computation, §250 computation.**
     Consumer pre-computes outside the substrate.

   ## TODO — audit-doc seam

   The eventual posting builder (`tax-return-posting-builder`) does
   not yet stamp `:transaction/audit-doc` referencing the responsible
   `:kontor.provision`. The citation already lives on the provision
   row; the wire-up is a ~50 LOC kernel sweep tracked as a follow-up."
  (:require [kontor.l10n-us.cit-statute :as cit-statute]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; suppress 'unused require' lint — the namespace is required for its
;; install-time side effects (parameter / provision defs read elsewhere)
;; and to keep the symmetry with the FR / DE / JP / CA / AT templates.
(comment cit-statute/install!)

;; ============================================================================
;; Provider — pulls items from kontor.tax.statute, folds, builds component
;; ============================================================================

(defn- component-items
  "For the `:cit` component, query the statute for all applicable
   base-side + tax-side provisions and resolve them to fold-ready
   items. Returns `{:base-items :tax-items :provisions}`."
  [db ctx as-of]
  (let [scoped-ctx (assoc ctx :component :cit :db db :as-of as-of)
        query      (fn [concept]
                     (statute/apply-provisions db {:concept      concept
                                                   :jurisdiction :us
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

(defn- cit-component
  "Build the §11 CIT component map. Base = book-profit + base-side
   adjustments (§11 CGT corp-net add, optional §172/§163j/§250
   stubs); schedule = flat §11 rate (from `:parameter`); tax-side
   adjustments via `apply-adjustments` (none in v1). Floors gross at
   0M when base is negative — §11 has no negative tax (and no minimum
   tax floor — CAMT is deferred to v1.x)."
  [db ctx as-of book-profit functional-commodity]
  (let [cit-rate (or (statute/parameter-value-at db "US.CIT.§11.rate" as-of)
                     (throw (ex-info "US CIT provider: parameter US.CIT.§11.rate not found at as-of — was kontor.l10n-us.cit-statute installed?"
                                     {:as-of as-of})))
        schedule {:kontor.schedule/type :flat :rate cit-rate}
        {:keys [base-items tax-items provisions]} (component-items db ctx as-of)
        scoped-ctx (assoc ctx :component :cit :db db :as-of as-of)
        {base'         :base
         base-resolved :resolved} (ts/apply-base-adjustments
                                   book-profit base-items scoped-ctx)
        raw-gross  (ts/apply-schedule schedule base')
        ;; §11 has no negative tax — floor gross at 0M.
        gross      (if (neg? raw-gross) 0M raw-gross)
        {liability   :liability
         tax-resolved :resolved} (ts/apply-adjustments
                                  gross tax-items scoped-ctx)]
    {:kind            :corporate-income-tax
     :authority       :us-irs
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
     :provenance      {:provider-id        :us-cit
                       :statute            "IRC §11 (rate) + §11 / §172 / §163(j) / §250 (provisions)"
                       :provisions-applied (mapv :kontor.provision/code provisions)
                       :as-of              as-of}}))

(defrecord UsCitProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of       (or (:as-of ctx) (:to period))
          book-profit (or (:book-profit inputs)
                          (throw (ex-info "US CIT provider needs :inputs :book-profit"
                                          {:inputs inputs})))
          cit-c       (cit-component db ctx as-of book-profit commodity)]
      (ptp/assert-monocommodity-facts!
       (ptp/tax-return-facts
        {:entity               entity
         :period               period
         :jurisdiction         {:country :us :authority :us-irs}
         :functional-commodity commodity
         :components           [cit-c]})))))

(defn us-cit-provider
  "Build a US CIT provider (Form 1120 / IRC §11). Statute lives in
   `:provision` / `:parameter` data (installed via
   `kontor.l10n-us.cit-statute/install!`); the provider just folds the
   applicable provisions for the CIT component.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :us-cit)
     :commodity — functional commodity (default :USD)"
  [{:keys [id commodity] :or {id :us-cit commodity :USD}}]
  (->UsCitProvider id commodity))
