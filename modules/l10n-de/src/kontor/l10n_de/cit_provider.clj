(ns kontor.l10n-de.cit-provider
  "DE corporate income tax provider — KSt + Soli + GewSt — built as a
   `PeriodTaxProvider` (ADR-099) over the statute-as-data substrate
   (ADR-101; research note 108). First end-to-end consumer of
   `kontor.statute`; the reference example for the per-jurisdiction
   shape Phase 3 will follow.

   The provider does THREE things and nothing else:

   1. Reads the KSt + GewSt rates from `:parameter`s.
   2. For each component (KSt / GewSt) sets `:component` in ctx, calls
      `kontor.statute/apply-provisions` to gather base-side and tax-side
      items, runs the schedule, returns the component map.
   3. Assembles a 2-component `TaxReturnFacts`.

   Everything else — what counts as a base-add, how Soli is computed,
   the §8 / §9 GewSt menu — lives in `kontor.l10n-de.cit-statute` as
   `:provision` data, NOT in this provider's code.

   ## Inputs the consumer supplies

   `:tax-unit` (company config):
     {:hebesatz <long>}   GewSt municipality multiplier (380 = 3.80×)

   `:inputs` (period facts):
     {:book-profit                       <BigDecimal> required
      :kst-non-deductibles               <BigDecimal> optional, default 0
      :participation-gain                <BigDecimal> optional, default 0
      :gewst-interest-post-freibetrag    <BigDecimal> optional, default 0
                                          (consumer applies §8 Freibetrag)
      :gewst-rental-expense              <BigDecimal> optional, default 0
      :gewst-real-estate-value           <BigDecimal> optional, default 0}

   ## Out-of-substrate

   `:tax-context-fact` keyed by `:inputs` keys works for facts the
   consumer supplies as-is. Computed shares (interest × 25%, rental ×
   12.5%, real-estate × 1.2%) are expressed via the
   `:provision/compute-fn` escape hatch — see `register!` below.
   Soli (5.5% × running KSt tax) is also a compute-fn, registered
   under `:de-soli-on-kst`."
  (:require [kontor.l10n-de.cit-statute :as cit-statute]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Compute-fn registration — the ADR-101 §D2 escape hatch
;; ============================================================================

(defn- as-of-from-ctx
  "Read the as-of instant from ctx (:as-of preferred; :period :to as
   the fallback for callers who only thread the period)."
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- inputs-fact
  "Read a fact from `:inputs` of ctx, with a default if missing."
  [ctx fact default]
  (or (get-in ctx [:inputs fact]) default))

(defn- de-soli-on-kst
  "Soli rate × the running KSt tax (late-bound — apply-adjustments
   threads `:running` into the inner fn at fold time)."
  [ctx]
  (let [rate (statute/parameter-value-at (:db ctx) "DE.Soli.rate" (as-of-from-ctx ctx))]
    (fn [ctx-w-running] (* (:running ctx-w-running) rate))))

(defn- de-§8b-addback
  "§8b Abs. 5 KStG — 5% Pauschalzuschlag on participation gains.
   `:participation-gain` × (1 − exemption-rate)."
  ^java.math.BigDecimal [ctx]
  (let [addback-share (- 1M (statute/parameter-value-at
                             (:db ctx) "DE.KStG.§8b.exemption-rate"
                             (as-of-from-ctx ctx)))]
    (* (inputs-fact ctx :participation-gain 0M) addback-share)))

(defn- de-gewst-§8-interest
  "§8 Nr. 1a GewStG — 25% × interest expense above the €200k Freibetrag.
   Consumer supplies the POST-Freibetrag total via
   `:inputs :gewst-interest-post-freibetrag`."
  ^java.math.BigDecimal [ctx]
  (* (inputs-fact ctx :gewst-interest-post-freibetrag 0M)
     (statute/parameter-value-at (:db ctx) "DE.GewSt.§8.interest-share" (as-of-from-ctx ctx))))

(defn- de-gewst-§8-rental
  "§8 Nr. 1d GewStG — 50% × 25% combined share of rental/lease payments.
   The substrate parameter `:DE.GewSt.§8.rental-share` is the
   pre-combined 12.5% (= ½ × ¼)."
  ^java.math.BigDecimal [ctx]
  (* (inputs-fact ctx :gewst-rental-expense 0M)
     (statute/parameter-value-at (:db ctx) "DE.GewSt.§8.rental-share" (as-of-from-ctx ctx))))

(defn- de-gewst-§9-real-estate
  "§9 Nr. 1 GewStG — 1.2% × the real-estate Einheitswert × 1.4
   (consumer supplies the already-multiplied value)."
  ^java.math.BigDecimal [ctx]
  (* (inputs-fact ctx :gewst-real-estate-value 0M)
     (statute/parameter-value-at (:db ctx) "DE.GewSt.§9.real-estate-rate" (as-of-from-ctx ctx))))

(defn register!
  "Register the four DE CIT compute-fns with `kontor.statute`. Called
   automatically at namespace load; idempotent."
  []
  (statute/register-compute-fn! :de-soli-on-kst         de-soli-on-kst)
  (statute/register-compute-fn! :de-§8b-addback         de-§8b-addback)
  (statute/register-compute-fn! :de-gewst-§8-interest   de-gewst-§8-interest)
  (statute/register-compute-fn! :de-gewst-§8-rental     de-gewst-§8-rental)
  (statute/register-compute-fn! :de-gewst-§9-real-estate de-gewst-§9-real-estate))

(register!)

;; ============================================================================
;; Provider — pulls items from kontor.statute, folds, builds components
;; ============================================================================

(defn- component-items
  "For one component (:kst or :gewst), query the statute for all
   applicable base-side + tax-side provisions and resolve them to
   fold-ready items. Returns `{:base-items :tax-items :provisions}`."
  [db ctx as-of component]
  (let [scoped-ctx (assoc ctx :component component :db db :as-of as-of)
        adds       (statute/apply-provisions db {:concept :base-transform-add
                                                 :jurisdiction :de
                                                 :as-of as-of} scoped-ctx)
        deducts    (statute/apply-provisions db {:concept :base-transform-deduct
                                                 :jurisdiction :de
                                                 :as-of as-of} scoped-ctx)
        surtaxes   (statute/apply-provisions db {:concept :surtax
                                                 :jurisdiction :de
                                                 :as-of as-of} scoped-ctx)]
    {:base-items (concat (:items adds) (:items deducts))
     :tax-items  (:items surtaxes)
     :provisions (concat (:provisions adds) (:provisions deducts) (:provisions surtaxes))}))

(defn- kst-component
  "Build the KSt component map. Base = book-profit + base-adjustments;
   schedule = flat KSt rate (from `:parameter`); surtaxes = Soli."
  [db ctx as-of book-profit functional-commodity]
  (let [kst-rate (statute/parameter-value-at db "DE.KSt.rate" as-of)
        schedule {:schedule/type :flat :rate kst-rate}
        {:keys [base-items tax-items provisions]} (component-items db ctx as-of :kst)
        scoped-ctx     (assoc ctx :component :kst :db db :as-of as-of)
        {base'   :base    base-resolved :resolved} (ts/apply-base-adjustments
                                                    book-profit base-items scoped-ctx)
        gross         (ts/apply-schedule schedule base')
        {liability :liability tax-resolved :resolved} (ts/apply-adjustments
                                                       gross tax-items scoped-ctx)]
    {:kind            :corporate-income-tax
     :authority       :de-bundesfinanzministerium
     :base            {:amount base' :commodity functional-commodity}
     :base-transform  (when (seq base-resolved)
                        {:transform/type :adjustments
                         :items base-resolved})
     :schedule        schedule
     :gross-liability {:amount gross :commodity functional-commodity}
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance]) tax-resolved)
     :liability       {:amount liability :commodity functional-commodity}
     :regime          nil
     :provenance      {:provider-id :de-cit
                       :statute "KStG + SolZG"
                       :provisions-applied (mapv :provision/code provisions)
                       :as-of as-of}}))

(defn- gewst-formula
  "GewSt formula: base × Messzahl × (Hebesatz / 100). The Hebesatz
   comes from `:tax-unit` ctx — varies per municipality."
  ^java.math.BigDecimal [^java.math.BigDecimal base ctx]
  (let [messzahl (statute/parameter-value-at (:db ctx) "DE.GewSt.messzahl" (as-of-from-ctx ctx))
        hebesatz (or (get-in ctx [:tax-unit :hebesatz])
                     (throw (ex-info "DE GewSt requires :tax-unit :hebesatz — the municipality Hebesatz multiplier (e.g. 380 for 380%)"
                                     {:ctx ctx})))]
    (* base messzahl (/ (bigdec hebesatz) 100M))))

(defn- gewst-component
  "Build the GewSt component map. Base = book-profit + §8 add-backs −
   §9 reductions; schedule = formula (Messzahl × Hebesatz / 100)."
  [db ctx as-of book-profit functional-commodity]
  (let [schedule {:schedule/type :formula :fn gewst-formula}
        {:keys [base-items tax-items provisions]} (component-items db ctx as-of :gewst)
        scoped-ctx     (assoc ctx :component :gewst :db db :as-of as-of)
        {base' :base base-resolved :resolved} (ts/apply-base-adjustments
                                               book-profit base-items scoped-ctx)
        gross         (ts/apply-schedule schedule base' scoped-ctx)
        {liability :liability tax-resolved :resolved} (ts/apply-adjustments
                                                       gross tax-items scoped-ctx)]
    {:kind            :corporate-income-tax
     :authority       :de-municipality
     :base            {:amount base' :commodity functional-commodity}
     :base-transform  (when (seq base-resolved)
                        {:transform/type :adjustments
                         :items base-resolved})
     :schedule        schedule
     :gross-liability {:amount gross :commodity functional-commodity}
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance]) tax-resolved)
     :liability       {:amount liability :commodity functional-commodity}
     :jurisdiction-specific-codes {:hebesatz (get-in ctx [:tax-unit :hebesatz])}
     :regime          nil
     :provenance      {:provider-id :de-cit
                       :statute "GewStG"
                       :provisions-applied (mapv :provision/code provisions)
                       :as-of as-of}}))

(defrecord DECITProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of        (or (:as-of ctx) (:to period))
          book-profit  (or (:book-profit inputs)
                           (throw (ex-info "DE CIT provider needs :inputs :book-profit"
                                           {:inputs inputs})))
          kst-c        (kst-component db ctx as-of book-profit commodity)
          gewst-c      (gewst-component db ctx as-of book-profit commodity)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :de}
        :functional-commodity commodity
        :components           [kst-c gewst-c]}))))

(defn de-cit-provider
  "Build a DE CIT provider. Statute lives in :provision / :parameter
   data (installed via `kontor.l10n-de.cit-statute/install!`); the
   provider just folds the applicable provisions per component.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :de-cit)
     :commodity — functional commodity (default :EUR)"
  [{:keys [id commodity] :or {id :de-cit commodity :EUR}}]
  (->DECITProvider id commodity))

(defn install-statute!
  "Convenience wrapper around `kontor.l10n-de.cit-statute/install!`
   for callers that want one-call statute setup before constructing
   the provider."
  [conn]
  (cit-statute/install! conn))
