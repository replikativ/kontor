(ns kontor.l10n-at.cit-provider
  "AT corporate income tax provider — Körperschaftsteuer (KöSt) — built
   as a `PeriodTaxProvider` (ADR-099) over the statute-as-data substrate
   (ADR-101). Mirrors
   `kontor.l10n-fr.cit-provider` (the closest single-component CIT
   template); the Mindest-KöSt floor rides
   `kontor.tax.statute/compose-greater-of` (the AMT / minimum-tax
   pattern — IN's CIT provider is the in-tree precedent).

   The provider does THREE things and nothing else:

   1. Reads the §22 KStG flat rate from `:parameter` data
      (`AT.KStG.cit-rate`, shipped from `kontor.l10n-at.cgt-statute`)
      and the §24 KStG Mindest-KöSt floor amount (GmbH €500 / AG €3 500
      with bitemporal history; selected by `:tax-unit :entity-kind`).
   2. Builds TWO components for the `:koest` axis: the regular KSt
      (book-profit × rate, after `:base-transform` adjustments + tax-
      side credits/surtaxes) and the Mindest-KöSt floor (a fixed amount
      from the parameter, regardless of base). Composes via
      `compose-greater-of` to get the prevailing arm with audit trail
      (`:composed-of` + `:composition`).
   3. Assembles a 1-component `TaxReturnFacts`.

   Everything else — the §10 KStG dividend exemption / addition lanes,
   the §8 Abs 4 Verlustvortrag surfacing, the §12 KStG non-deductibles
   add-back — lives in `kontor.l10n-at.cit-statute` as `:provision`
   data, NOT in this provider's code.

   ## Inputs the consumer supplies

   `:tax-unit` (company config — all optional with defaults):
     {:entity-kind   #{:gmbh :flexco :ag}   default :gmbh — picks the
                                            Mindest-KöSt floor parameter
                                            (FlexCo treated identically
                                            to GmbH per BGBl I 2023/179)}

   `:inputs` (period facts — `:book-profit` is the only required key):
     {:book-profit                   <BigDecimal>  required —
                                                   Steuerbilanz-Gewinn
                                                   (consumer maintains
                                                   the Handelsbilanz →
                                                   Steuerbilanz delta
                                                   outside the substrate)
      :cgt-cit-base-deductions       <BigDecimal>  optional — the §10
                                                   KStG exempt-dividends
                                                   lane harvested from
                                                   `investment-income-provider`
      :cgt-cit-base-additions        <BigDecimal>  optional — the §10
                                                   KStG tax-effective-
                                                   option lane
      :at-verlustvortrag-applied     <BigDecimal>  optional — consumer-
                                                   pre-computed loss
                                                   carry-forward used
                                                   this period
      :at-§12-non-deductibles        <BigDecimal>  optional — consumer-
                                                   pre-computed §12 KStG
                                                   non-deductible
                                                   expenses to add back}

   ## Mindest-KöSt — substrate composition, not a provision

   §24 Abs 4 KStG sets a minimum floor on the annual liability. The
   substrate handles this via
   `kontor.tax.statute/compose-greater-of` (ADR-101 §3.1 of the recipe):
   build TWO components (the regular KSt and the floor),
   then compose. The prevailing arm carries the liability; the other
   is recorded in `:composed-of` + `:composition` for audit.

   ## Out-of-substrate

   - **§9 KStG Gruppenbesteuerung** — deferred to `kontor-group-
     consolidation` companion ().
   - **§22 Abs 2 KStG Privatstiftungs-Zwischensteuer** — deferred to
     a foundation-specific v2.
   - **§8 Abs 4 KStG Mantelkaufverbot** — qualitative test consumer
     adjudicates outside substrate; the surfaced Verlustvortrag
     provision records the consumer-pre-computed offset.
   - **§7 Abs 2 KStG / §10d EStG 75 %-cap-on-€1M Verlustvortrag** —
     consumer pre-computes the permitted offset (inter-period carry
     frontier).

   ## TODO — audit-doc seam

   The eventual posting builder (`tax-return-posting-builder`) does
   not yet stamp `:transaction/audit-doc` referencing the responsible
   `:kontor.provision`. The citation already lives on the provision
   row; the wire-up is a ~50 LOC kernel sweep tracked separately."
  (:require [kontor.l10n-at.cit-statute :as cit-statute]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; suppress 'unused require' lint — the namespace is required for its
;; install-time side effects (parameter / provision defs read elsewhere
;; and to keep the symmetry with the FR / DE / JP / CA templates).
(comment cit-statute/install!)

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- mindest-koest-parameter-code
  "Pick the right Mindest-KöSt parameter for the entity-kind. FlexCo
   treated identically to GmbH per BGBl I 2023/179;
   AG gets the €3 500/yr row. Anything else throws."
  [entity-kind]
  (case (or entity-kind :gmbh)
    (:gmbh :flexco) "AT.KStG.§24.mindest-koest-gmbh-amount"
    :ag             "AT.KStG.§24.mindest-koest-ag-amount"
    (throw (ex-info (str "AT CIT provider — unknown :tax-unit :entity-kind " (pr-str entity-kind)
                         ". v1 supports :gmbh / :flexco / :ag.")
                    {:entity-kind entity-kind}))))

;; ============================================================================
;; Provider — pulls items from kontor.tax.statute, folds, composes-greater-of
;; ============================================================================

(defn- component-items
  "For the `:koest` component, query the statute for all applicable
   base-side + tax-side provisions and resolve them to fold-ready
   items. Returns `{:base-items :tax-items :provisions}`."
  [db ctx as-of]
  (let [scoped-ctx (assoc ctx :component :koest :db db :as-of as-of)
        query      (fn [concept]
                     (statute/apply-provisions db {:concept      concept
                                                   :jurisdiction :at
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

(defn- regular-koest-component
  "Build the regular KSt component map. Base = book-profit + base-side
   adjustments (§10 KStG add / deduct, §8 Abs 4 Verlustvortrag, §12
   non-deductibles); schedule = flat §22 KStG rate (from
   `:parameter`); tax-side credits / surtaxes via `apply-adjustments`
   (none in v1, but the substrate is wired). Floors gross at 0M when
   base is negative — KSt is not refundable on a loss; the
   `compose-greater-of` against the Mindest-KöSt arm handles the
   minimum-tax case."
  [db ctx as-of book-profit functional-commodity]
  (let [kst-rate (or (statute/parameter-value-at db "AT.KStG.cit-rate" as-of)
                     (throw (ex-info "AT CIT provider: parameter AT.KStG.cit-rate not found — was kontor.l10n-at.cgt-statute installed?"
                                     {:as-of as-of})))
        schedule {:kontor.schedule/type :flat :rate kst-rate}
        {:keys [base-items tax-items provisions]} (component-items db ctx as-of)
        scoped-ctx (assoc ctx :component :koest :db db :as-of as-of)
        {base'         :base
         base-resolved :resolved} (ts/apply-base-adjustments
                                   book-profit base-items scoped-ctx)
        raw-gross  (ts/apply-schedule schedule base')
        ;; KSt is not refundable on a loss — floor gross at 0M. The
        ;; compose-greater-of against the Mindest-KöSt arm covers the
        ;; minimum case.
        gross      (if (neg? raw-gross) 0M raw-gross)
        {liability   :liability
         tax-resolved :resolved} (ts/apply-adjustments
                                  gross tax-items scoped-ctx)]
    {:kind            :corporate-income-tax
     :authority       :at-finanz
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
     :provenance      {:provider-id        :at-cit
                       :statute            "KStG 1988 §22 (rate) + §10 / §8 / §12 (provisions)"
                       :provisions-applied (mapv :kontor.provision/code provisions)
                       :as-of              as-of}}))

(defn- mindest-koest-component
  "Build the Mindest-KöSt floor component map. Schedule is a `:flat`
   over a zero base producing zero gross by default, then a single
   `:base-add`-style synthetic adjustment surfaces the floor amount as
   the gross + liability — kept as a parallel KSt-shape component so
   `compose-greater-of` can compare apples to apples. Provenance
   records the §24 KStG citation + the entity-kind that selected the
   parameter."
  [db _ctx as-of entity-kind functional-commodity]
  (let [param-code (mindest-koest-parameter-code entity-kind)
        amount     (or (statute/parameter-value-at db param-code as-of)
                       (throw (ex-info (str "AT CIT provider: Mindest-KöSt parameter " param-code
                                            " not found at as-of — was kontor.l10n-at.cit-statute installed?")
                                       {:param-code param-code :as-of as-of})))]
    {:kind            :minimum-tax
     :authority       :at-finanz
     :base            {:amount 0M :commodity functional-commodity}
     :schedule        {:kontor.schedule/type :flat :rate 0M}
     :gross-liability {:amount amount :commodity functional-commodity}
     :liability       {:amount amount :commodity functional-commodity}
     :regime          nil
     :jurisdiction-specific-codes {:at/mindest-koest-parameter param-code
                                   :at/entity-kind             (or entity-kind :gmbh)}
     :provenance      {:provider-id        :at-cit
                       :statute            "KStG 1988 §24 Abs 4 Z 1 — Mindestkörperschaftsteuer"
                       :citation           "https://www.jusline.at/gesetz/kstg/paragraf/24"
                       :provisions-applied []
                       :as-of              as-of}}))

(defrecord AtCitProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs tax-unit] :as ctx}]
    (let [as-of        (or (:as-of ctx) (:to period))
          book-profit  (or (:book-profit inputs)
                           (throw (ex-info "AT CIT provider needs :inputs :book-profit"
                                           {:inputs inputs})))
          entity-kind  (:entity-kind tax-unit)
          regular      (regular-koest-component db ctx as-of book-profit commodity)
          mindest      (mindest-koest-component db ctx as-of entity-kind commodity)
          prevailing   (statute/compose-greater-of regular mindest)]
      (ptp/assert-monocommodity-facts!
       (ptp/tax-return-facts
        {:entity               entity
         :period               period
         :jurisdiction         {:country :at :authority :at-finanz}
         :functional-commodity commodity
         :components           [prevailing]})))))

(defn at-cit-provider
  "Build an AT CIT provider. Statute lives in `:provision` / `:parameter`
   data (installed via `kontor.l10n-at.cit-statute/install!` plus the
   §22 rate which ships with `kontor.l10n-at.cgt-statute/install!`);
   the provider just folds the applicable provisions for the KSt
   component and composes against the §24 Mindest-KöSt floor.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :at-cit)
     :commodity — functional commodity (default :EUR)"
  [{:keys [id commodity] :or {id :at-cit commodity :EUR}}]
  (->AtCitProvider id commodity))
