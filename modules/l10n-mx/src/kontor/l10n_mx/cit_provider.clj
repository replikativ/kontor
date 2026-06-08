(ns kontor.l10n-mx.cit-provider
  "MX corporate income tax provider — ISR personas morales — built as
   a `PeriodTaxProvider` (ADR-099) over the statute-as-data substrate
   (ADR-101). Mirrors
   `kontor.l10n-fr.cit-provider` (the closest single-component CIT
   template); MX has NO minimum-tax, NO surtax — the cleanest
   single-component-flat shape in.

   The provider does THREE things and nothing else:

   1. Reads the LISR art. 9 flat 30 % rate from `:parameter` data
      (`MX.CGT.art-9.pm-rate`, shipped from
      `kontor.l10n-mx.cgt-statute`).
   2. Builds ONE component (`:isr-pm`): book-profit × rate, after
      `:base-transform` adjustments (PTU deduction, CGT corporate
      fold add, optional non-deductibles add) and tax-side adjustments
      (none in v1, but the substrate is wired).
   3. Assembles a 1-component `TaxReturnFacts`.

   Everything else — the §9 fr. I PTU deduction, the cgt-provider
   corporate fold, the §28 / §32 non-deductibles surface — lives in
   `kontor.l10n-mx.cit-statute` as `:provision` data, NOT in this
   provider's code.

   ## Inputs the consumer supplies

   `:tax-unit` (company config — none required in v1; reserved for
   future RIGS / participation-exemption gating).

   `:inputs` (period facts — `:book-profit` is the only required
   key):
     {:book-profit              <BigDecimal>  required — utilidad
                                              fiscal (consumer
                                              maintains
                                              ingresos-acumulables −
                                              deducciones-autorizadas
                                              − pérdida-carry delta
                                              outside the substrate)
      :ptu-deductible           <BigDecimal>  optional — PTU pagada
                                              en el año (10 % of
                                              prior-year utilidad
                                              fiscal; consumer
                                              pre-computes)
      :cgt-cit-base-additions   <BigDecimal>  optional — net capital
                                              gains lane harvested
                                              from
                                              `cgt-provider/corp-net-component`
      :mx-non-deductible-add    <BigDecimal>  optional — §28 / §32
                                              non-deductible
                                              expenses to add back}

   ## Out-of-substrate

   - **RIGS group consolidation** — deferred to.
   - **REPSE outsourcing test** — qualitative; consumer pre-computes
     non-deductible portion and surfaces via the optional provision.
   - **Loss carry-forward (arts. 57-60)** — consumer pre-computes
     the permitted offset (inter-period carry).
   - **CUFIN / CUCA maintenance** — out of substrate; CGT provider
     consumes deltas from `:inputs :mx-share-adjustments`.

   ## TODO — audit-doc seam

   The eventual posting builder (`tax-return-posting-builder`) does
   not yet stamp `:transaction/audit-doc` referencing the responsible
   `:kontor.provision`. The citation already lives on the provision
   row; the wire-up is a small kernel sweep tracked separately."
  (:require [kontor.l10n-mx.cit-statute :as cit-statute]
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
  "For the `:isr-pm` component, query the statute for all applicable
   base-side + tax-side provisions and resolve them. Returns
   `{:base-items :tax-items :provisions}`."
  [db ctx as-of]
  (let [scoped-ctx (assoc ctx :component :isr-pm :db db :as-of as-of)
        query      (fn [concept]
                     (statute/apply-provisions db {:concept      concept
                                                   :jurisdiction :mx
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

(defn- isr-pm-component
  "Build the ISR personas morales component map. Base = book-profit +
   base-side adjustments (PTU deduction, CGT corporate fold add,
   optional non-deductibles add); schedule = flat LISR art. 9 30 %
   rate (from `:parameter`); tax-side credits / surtaxes via
   `apply-adjustments` (none in v1). Floors gross at 0M when base is
   negative — ISR personas morales is not refundable on a loss; the
   consumer carries the loss forward via art. 57 outside the
   substrate."
  [db ctx as-of book-profit functional-commodity]
  (let [rate (or (statute/parameter-value-at db "MX.CGT.art-9.pm-rate" as-of)
                 (throw (ex-info "MX CIT provider: parameter MX.CGT.art-9.pm-rate not found — was kontor.l10n-mx.cgt-statute installed?"
                                 {:as-of as-of})))
        schedule {:kontor.schedule/type :flat :rate rate}
        {:keys [base-items tax-items provisions]} (component-items db ctx as-of)
        scoped-ctx (assoc ctx :component :isr-pm :db db :as-of as-of)
        {base'         :base
         base-resolved :resolved} (ts/apply-base-adjustments
                                   book-profit base-items scoped-ctx)
        raw-gross  (ts/apply-schedule schedule base')
        ;; ISR is not refundable on a loss — floor gross at 0M.
        gross      (if (neg? raw-gross) 0M raw-gross)
        {liability   :liability
         tax-resolved :resolved} (ts/apply-adjustments
                                  gross tax-items scoped-ctx)]
    {:kind            :corporate-income-tax
     :authority       :mx-sat
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
     :provenance      {:provider-id        :mx-cit
                       :statute            "LISR Título II art. 9 (rate) + art. 9 fr. I (PTU) + arts. 22 / 28 / 32 (provisions)"
                       :provisions-applied (mapv :kontor.provision/code provisions)
                       :as-of              as-of}}))

(defrecord MxCitProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of       (or (:as-of ctx) (:to period))
          book-profit (or (:book-profit inputs)
                          (throw (ex-info "MX CIT provider needs :inputs :book-profit"
                                          {:inputs inputs})))
          isr-pm-c    (isr-pm-component db ctx as-of book-profit commodity)]
      (ptp/assert-monocommodity-facts!
       (ptp/tax-return-facts
        {:entity               entity
         :period               period
         :jurisdiction         {:country :mx :authority :mx-sat}
         :functional-commodity commodity
         :components           [isr-pm-c]})))))

(defn mx-cit-provider
  "Build an MX CIT provider. Statute lives in `:provision` /
   `:parameter` data (installed via
   `kontor.l10n-mx.cit-statute/install!` plus the 30 % rate which
   ships with `kontor.l10n-mx.cgt-statute/install!`); the provider
   just folds the applicable provisions for the ISR personas morales
   component.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :mx-cit)
     :commodity — functional commodity (default :MXN)"
  [{:keys [id commodity] :or {id :mx-cit commodity :MXN}}]
  (->MxCitProvider id commodity))
