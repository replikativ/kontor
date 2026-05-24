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
     {:hebesatz <long>}   GewSt municipality multiplier expressed as
                          integer percent (380 = 380% = factor 3.80)
     {:cgt-provider-active? <bool>}
                          optional, default false. When true the
                          DE-KStG-§8b-Abs-5 provision is suppressed —
                          consumer is wiring the DE CGT provider as the
                          §8b add-back source-of-truth (note 136 P0-3).

   `:inputs` (period facts):
     {:book-profit             <BigDecimal>  required
      :kst-non-deductibles     <BigDecimal>  optional, default 0
      :participation-gain      <BigDecimal>  optional, default 0 —
                                              IGNORED when
                                              `:cgt-provider-active?` true
      :gewst-§8 {:interest        <BigDecimal>  optional, default 0
                 :annuity         <BigDecimal>  optional, default 0
                 :silent-partner  <BigDecimal>  optional, default 0
                 :rent-movable    <BigDecimal>  optional, default 0
                 :rent-immovable  <BigDecimal>  optional, default 0
                 :royalties       <BigDecimal>  optional, default 0}
            — RAW expense amounts; the substrate applies the §8 a-f
              per-bucket weights, then the €200k Freibetrag once on
              the weighted sum, then × ¼ (note 120 P0-1/P0-2 fix)
      :gewst-real-estate-value <BigDecimal>  pre-2025 only — already
                                              multiplied (Einheitswert × 1.4)
      :grundsteuer-paid        <BigDecimal>  from 2025-01-01 — actual
                                              Grundsteuer paid as a
                                              business expense
                                              (Grundsteuerreform; note 120 P0-3)}

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

(defn- de-gewst-§8-hinzurechnung
  "§8 Nr. 1 GewStG — the consolidated six-bucket Hinzurechnung
   (note 120 §1.6 Option B). Statute: ¼ × max(0, Σ(bucket × weight) −
   Freibetrag) over the six §8 Nr. 1 categories (a interest, b
   annuity, c silent-partner, d rent on movable property, e rent on
   immovable property, f royalties).

   Reads each weight + the Freibetrag + the universal ¼ from
   `:parameter` data so a future weight or Freibetrag change is a
   one-row migration. Consumer supplies the raw amounts under
   `:inputs :gewst-§8 {:interest :annuity :silent-partner
   :rent-movable :rent-immovable :royalties}` — any absent key is 0M."
  ^java.math.BigDecimal [ctx]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        gewst-§8    (or (get-in ctx [:inputs :gewst-§8]) {})
        get-bucket  #(or (get gewst-§8 %) 0M)
        weight      #(statute/parameter-value-at db % as-of)
        weighted    (+ (* (get-bucket :interest)        (weight "DE.GewSt.§8.a-interest-weight"))
                       (* (get-bucket :annuity)         (weight "DE.GewSt.§8.b-annuity-weight"))
                       (* (get-bucket :silent-partner)  (weight "DE.GewSt.§8.c-silent-partner-weight"))
                       (* (get-bucket :rent-movable)    (weight "DE.GewSt.§8.d-rent-movable-weight"))
                       (* (get-bucket :rent-immovable)  (weight "DE.GewSt.§8.e-rent-immovable-weight"))
                       (* (get-bucket :royalties)       (weight "DE.GewSt.§8.f-royalty-weight")))
        freibetrag  (statute/parameter-value-at db "DE.GewSt.§8.freibetrag" as-of)
        fraction    (statute/parameter-value-at db "DE.GewSt.§8.hinzurechnung-fraction" as-of)]
    (* (max 0M (- weighted freibetrag)) fraction)))

(defn- de-gewst-§9-real-estate
  "§9 Nr. 1 GewStG (pre-2025) — 1.2% × the real-estate Einheitswert × 1.4
   (consumer supplies the already-multiplied value). Sunset by the
   Jahressteuergesetz 2024 Grundsteuerreform; the post-2024 successor
   is `:de-gewst-§9-grundsteuer` below."
  ^java.math.BigDecimal [ctx]
  (* (inputs-fact ctx :gewst-real-estate-value 0M)
     (statute/parameter-value-at (:db ctx) "DE.GewSt.§9.real-estate-rate" (as-of-from-ctx ctx))))

(defn register!
  "Register the four DE CIT compute-fns with `kontor.statute`. Called
   automatically at namespace load; idempotent. (Post note-120 P0
   fixes: the §8 a-f categories are now ONE compute-fn covering the
   weighted sum minus Freibetrag × ¼; the post-2025 §9 reads
   `:grundsteuer-paid` directly via `:tax-context-fact`, so no
   compute-fn needed there.)"
  []
  (statute/register-compute-fn! :de-soli-on-kst          de-soli-on-kst)
  (statute/register-compute-fn! :de-§8b-addback          de-§8b-addback)
  (statute/register-compute-fn! :de-gewst-§8-hinzurechnung de-gewst-§8-hinzurechnung)
  (statute/register-compute-fn! :de-gewst-§9-real-estate  de-gewst-§9-real-estate))

(register!)

;; ============================================================================
;; Provider — pulls items from kontor.statute, folds, builds components
;; ============================================================================

(defn- component-items
  "For one component (:kst or :gewst), query the statute for all
   applicable base-side + tax-side provisions and resolve them to
   fold-ready items. Returns `{:base-items :tax-items :provisions}`.

   apply-provisions (post-polish) returns items pre-grouped by `:op`
   category, so the provider concatenates the per-concept queries by
   key rather than splitting them itself."
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
    {:base-items (vec (concat (:base-items adds) (:base-items deducts)))
     :tax-items  (:tax-items surtaxes)
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
