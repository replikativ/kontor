(ns kontor.l10n-jp.cit-provider
  "JP corporate income tax provider — 法人税 + 地方法人税 + 防衛特別法人税 +
   事業税 + 特別法人事業税 + 法人住民税 (法人税割 + 均等割) — built as a
   `PeriodTaxProvider` (ADR-099) over the statute-as-data substrate
   (ADR-101 / ADR-101 Addendum 1; research note 110). Mirrors the DE
   CIT provider (ADR-104) structurally — same three-step shape per
   component (set `:component`, call `apply-provisions`, fold) — fanned
   out across THREE components to match the JP statutory stack.

   The provider does three things and nothing else:

   1. Reads JP rate parameters from `:parameter` data via
      `kontor.statute/parameter-value-at`.
   2. For each component (`:national` / `:enterprise` / `:inhabitant`)
      sets `:component` in ctx, queries `:concept :elective-regime` for
      a `:schedule-override`, queries `:concept :surtax` for tax-side
      items, runs the schedule, returns the component map.
   3. Threads `:national-cit-amount` into the inhabitants' ctx so the
      `:jp-inhabitant-income-levy` compute-fn can see the prior
      component's gross (note 110 §4 stress D — cross-component surtax
      wired manually in the provider, audit trail in `:provenance`).

   ## Inputs the consumer supplies

   `:tax-unit` (company config):
     {:is-sme?           <bool>       required — capital ≤ ¥100M AND
                                       not a wholly-owned subsidiary of
                                       a ≥ ¥500M parent. Consumer-
                                       supplied; provider trusts it.
      :capital-class     <keyword>    required for inhabitants'
                                       per-capita levy; one of
                                       :capital-up-to-10m
                                       :capital-up-to-100m
                                       :capital-up-to-1b
                                       :capital-up-to-5b
                                       :capital-above-5b
      :headcount-class   <keyword>    optional — :small (≤50) | :large
                                       (>50); default :small
      :prefecture        <keyword>    optional — defaults :tokyo
                                       (informational; v1 uses national-
                                       standard rates, prefecture-
                                       specific rate overrides are a
                                       future :parameter swap)}

   `:inputs` (period facts):
     {:book-profit       <BigDecimal>  required}

   ## Out-of-substrate

   Compute-fns for the five non-data consequences:
     :jp-local-cit-on-national      — surtax on `:running` (national CIT)
     :jp-defense-surtax             — 4 % × max(0, `:running` − ¥5M)
     :jp-special-corp-enterprise    — surtax on `:running` (enterprise)
     :jp-inhabitant-income-levy     — surtax on ctx `:national-cit-amount`
     :jp-inhabitant-per-capita      — 10-cell capital × headcount lookup

   The `:jp-inhabitant-income-levy` is the cross-component surtax — it
   reads `:national-cit-amount` from ctx (NOT `:running` — the
   inhabitants' component's own running starts at 0). Note 110 §4
   stress D: the provider wires this manually; the audit trail records
   the dependency in `:provenance :composed-of`."
  (:require [kontor.l10n-jp.cit-statute :as cit-statute]
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

(defn- jp-local-cit-on-national
  "地方法人税 — rate × national CIT (法人税). The base is the national
   CIT GROSS — NOT the running adjustment-fold total — so the inner
   late-bound fn reads `:national-cit-gross` from the fold ctx (the
   provider injects it after the schedule fires, before
   apply-adjustments threads the ctx through). `:running` would
   include any prior surtaxes — defense surtax etc. — and inflate
   the local CIT base, which the statute does not contemplate."
  [ctx]
  (let [rate (statute/parameter-value-at (:db ctx) "JP.LocalCIT.rate"
                                         (as-of-from-ctx ctx))]
    (fn [ctx-w-running]
      (* (or (:national-cit-gross ctx-w-running) 0M) rate))))

(defn- jp-defense-surtax
  "防衛特別法人税 — 4 % × max(0, national CIT − ¥5 000 000). Reads
   `:national-cit-gross` from the fold ctx (NOT `:running`) for the
   same reason as `jp-local-cit-on-national` — the statutory base is
   the 法人税 amount itself. Note 110 §4 stress C: substrate covers
   the deduction-on-prior-liability via a fn `:amount`, no shorthand
   needed."
  [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        rate  (statute/parameter-value-at db "JP.DefenseSurtax.rate" as-of)
        ded   (statute/parameter-value-at db "JP.DefenseSurtax.deduction" as-of)]
    (fn [ctx-w-running]
      (let [national (or (:national-cit-gross ctx-w-running) 0M)]
        (* rate (max 0M (- national ded)))))))

(defn- jp-special-corp-enterprise
  "特別法人事業税 — surtax on the enterprise-tax amount. Rate is
   37 % SME / 260 % large; both live as `:parameter`s. The
   late-bound fn reads `:enterprise-tax-gross` (provider-injected
   into the fold ctx) — the statutory base is the just-computed
   enterprise tax INCOME-base amount, not the running adjustment-
   fold total."
  [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        sme?  (get-in ctx [:tax-unit :is-sme?])
        rate  (statute/parameter-value-at
               db (if sme?
                    "JP.SpecialCorpEnterprise.sme-rate"
                    "JP.SpecialCorpEnterprise.large-rate")
               as-of)]
    (fn [ctx-w-running]
      (* (or (:enterprise-tax-gross ctx-w-running) 0M) rate))))

(defn- jp-inhabitant-income-levy
  "法人住民税 法人税割 — 7 % standard combined (1 % prefectural + 6 %
   municipal) on the NATIONAL CIT amount. Reads
   `:national-cit-amount` from ctx (provider-injected after the
   national component is computed) — NOT `:running`, because the
   inhabitants' component's own running starts at 0. Note 110 §4
   stress D."
  ^java.math.BigDecimal [ctx]
  (let [rate     (statute/parameter-value-at (:db ctx)
                                             "JP.Inhabitant.income-levy-rate"
                                             (as-of-from-ctx ctx))
        national (or (:national-cit-amount ctx) 0M)]
    (* national rate)))

(defn- jp-inhabitant-per-capita
  "法人住民税 均等割 — fixed per-capita levy from the 10-cell
   capital-class × headcount-class table (note 110 §1 / stress A —
   substrate carries the levy as a surtax-on-zero adjustment item
   rather than introducing a `:fixed-amount` schedule kind)."
  ^java.math.BigDecimal [ctx]
  (let [cap (or (get-in ctx [:tax-unit :capital-class])
                (throw (ex-info "JP CIT 均等割 requires :tax-unit :capital-class — one of :capital-up-to-10m / :capital-up-to-100m / :capital-up-to-1b / :capital-up-to-5b / :capital-above-5b"
                                {:tax-unit (:tax-unit ctx)})))
        hc  (get-in ctx [:tax-unit :headcount-class] :small)]
    (or (get cit-statute/per-capita-levy-table [cap hc])
        (throw (ex-info "JP CIT 均等割 — unknown :capital-class / :headcount-class combination"
                        {:capital-class cap :headcount-class hc
                         :known (keys cit-statute/per-capita-levy-table)})))))

(defn- jp-cit-sme-large-income-schedule
  "Note 125 P0-1 — the SME-with-income-over-¥1B 2-bracket schedule
   (17% on first ¥8M, 23.2% above). Same kink as the standard SME
   schedule; the first-bracket rate is the only difference.

   Registered as a `:formula` schedule compute-fn — invoked by
   `kontor.tax-schedule/apply-schedule` with `[base ctx]` after
   `kontor.statute/resolve-schedule-template` resolves the
   `:fn-from :compute-fn` reference."
  ^java.math.BigDecimal [^java.math.BigDecimal base ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)]
    (ts/apply-schedule
     (ts/progressive
      [{:rate  (statute/parameter-value-at db "JP.CIT.sme-reduced-rate-large-income" as-of)
        :upper (statute/parameter-value-at db "JP.CIT.sme-kink" as-of)}
       {:rate  (statute/parameter-value-at db "JP.CIT.flat-rate" as-of)
        :upper nil}])
     base)))

(defn register!
  "Register JP CIT compute-fns with `kontor.statute`. Called
   automatically at namespace load; idempotent."
  []
  (statute/register-compute-fn! :jp-local-cit-on-national         jp-local-cit-on-national)
  (statute/register-compute-fn! :jp-defense-surtax                jp-defense-surtax)
  (statute/register-compute-fn! :jp-special-corp-enterprise       jp-special-corp-enterprise)
  (statute/register-compute-fn! :jp-inhabitant-income-levy        jp-inhabitant-income-levy)
  (statute/register-compute-fn! :jp-inhabitant-per-capita         jp-inhabitant-per-capita)
  (statute/register-compute-fn! :jp-cit-sme-large-income-schedule jp-cit-sme-large-income-schedule))

(register!)

;; ============================================================================
;; Provider — pulls items from kontor.statute, folds, builds components
;; ============================================================================

(defn- national-default-schedule
  "Default schedule for the national CIT component — the SME 2-bracket
   progressive ladder (15 % on the first ¥8M, 23.2 % above). Reads
   rates + kink from `:parameter`s so a future amendment is a one-row
   migration. The large-corporation override (flat 23.2 %) is shipped
   as a `:provision` with `:op :schedule-override`."
  [db as-of]
  (ts/progressive
   [{:rate  (statute/parameter-value-at db "JP.CIT.sme-reduced-rate" as-of)
     :upper (statute/parameter-value-at db "JP.CIT.sme-kink"         as-of)}
    {:rate  (statute/parameter-value-at db "JP.CIT.flat-rate"        as-of)
     :upper nil}]))

(defn- enterprise-default-schedule
  "Default schedule for the enterprise tax component — the SME
   3-bracket progressive ladder (3.5 / 5.3 / 7.0 % on ≤¥4M / ≤¥8M /
   >¥8M). Reads rates + kinks from `:parameter`s. The large-corporation
   override (flat 1.18 % income-base only; value-added/capital bases
   deferred per note 110 §1) is shipped as a `:provision`."
  [db as-of]
  (ts/progressive
   [{:rate  (statute/parameter-value-at db "JP.Enterprise.sme-rate-1" as-of)
     :upper (statute/parameter-value-at db "JP.Enterprise.sme-kink-1" as-of)}
    {:rate  (statute/parameter-value-at db "JP.Enterprise.sme-rate-2" as-of)
     :upper (statute/parameter-value-at db "JP.Enterprise.sme-kink-2" as-of)}
    {:rate  (statute/parameter-value-at db "JP.Enterprise.sme-rate-3" as-of)
     :upper nil}]))

(defn- inhabitant-default-schedule
  "Inhabitants' component default schedule — flat 0 %. The component
   carries no first-class base of its own (法人税割 is a surtax on
   national CIT, 均等割 is fixed); both pieces are layered as
   adjustment-side surtax items in the fold (note 110 §4 stress A —
   'surtax on zero' rather than introducing a `:fixed-amount`
   schedule kind)."
  []
  {:schedule/type :flat :rate 0M})

(defn- component-items
  "For one component (`:national` / `:enterprise` / `:inhabitant`),
   query the statute for applicable schedule-override + surtax
   provisions and resolve them. Returns
   `{:tax-items :schedule-overrides :provisions}`.

   No base-side queries for JP CIT in v1 — the consumer supplies
   `:book-profit` already net of book→tax add-backs (note 110 §1's
   add-back menu is open-ended and a Phase 4 deliverable; see ADR-106
   future-work note). Symmetry with DE: DE wires base-add /
   base-deduct because §8/§9 GewSt menus are statutorily closed; JP
   defers them."
  [db ctx as-of component]
  (let [scoped-ctx (assoc ctx :component component :db db :as-of as-of)
        overrides  (statute/apply-provisions db {:concept :elective-regime
                                                 :jurisdiction :jp
                                                 :as-of as-of} scoped-ctx)
        surtaxes   (statute/apply-provisions db {:concept :surtax
                                                 :jurisdiction :jp
                                                 :as-of as-of} scoped-ctx)]
    {:tax-items          (:tax-items surtaxes)
     :schedule-overrides (:schedule-overrides overrides)
     :provisions         (concat (:provisions overrides) (:provisions surtaxes))}))

(defn- pick-schedule
  "Pick the effective schedule for a component — the first
   (priority-ordered, ambiguity-trapped) `:schedule-override` if any,
   otherwise the provided default. The override path is the
   ADR-101-Addendum-1 substrate seam used here for SME→large
   schedule swap; the same seam CN HNTE / IN §115BAA / FR PME use."
  [default-schedule overrides]
  (if-let [override (first overrides)]
    (:schedule override)
    default-schedule))

(defn- national-component
  "Build the national CIT component map. Schedule = SME progressive
   (default) or large-corp flat (via `:schedule-override`); surtaxes
   = local CIT (always) + defense surtax (FY ≥ 2026-04-01).

   Both surtaxes are computed on the national CIT GROSS (the 法人税
   amount itself), NOT on the running adjustment-fold total — the
   statute references 法人税 explicitly. So the provider injects
   `:national-cit-gross` into ctx after the schedule fires and the
   compute-fns read that fact rather than `:running`."
  [db ctx as-of book-profit functional-commodity]
  (let [{:keys [tax-items schedule-overrides provisions]} (component-items db ctx as-of :national)
        scoped-ctx (assoc ctx :component :national :db db :as-of as-of)
        schedule   (pick-schedule (national-default-schedule db as-of) schedule-overrides)
        gross      (ts/apply-schedule schedule book-profit scoped-ctx)
        adj-ctx    (assoc scoped-ctx :national-cit-gross gross)
        {liability :liability tax-resolved :resolved}
        (ts/apply-adjustments gross tax-items adj-ctx)]
    {:kind            :corporate-income-tax
     :authority       :jp-nta
     :base            {:amount book-profit :commodity functional-commodity}
     :schedule        schedule
     :gross-liability {:amount gross :commodity functional-commodity}
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance]) tax-resolved)
     :liability       {:amount liability :commodity functional-commodity}
     :regime          nil
     :provenance      {:provider-id :jp-cit
                       :statute     "法人税法 §66 + 地方法人税法 + 防衛特別法人税"
                       :provisions-applied (mapv :provision/code provisions)
                       :as-of       as-of}}))

(defn- enterprise-component
  "Build the enterprise tax component map. Schedule = SME 3-bracket
   progressive (default) or large-corp flat 1.18 % (via
   `:schedule-override`); surtaxes = special corporate enterprise tax
   (37 % SME / 260 % large).

   Mirrors `national-component`'s injection pattern: the special-corp
   surtax base is the enterprise-tax GROSS, NOT the adjustment-fold
   running total, so the provider threads `:enterprise-tax-gross`
   into ctx."
  [db ctx as-of book-profit functional-commodity]
  (let [{:keys [tax-items schedule-overrides provisions]} (component-items db ctx as-of :enterprise)
        scoped-ctx (assoc ctx :component :enterprise :db db :as-of as-of)
        schedule   (pick-schedule (enterprise-default-schedule db as-of) schedule-overrides)
        gross      (ts/apply-schedule schedule book-profit scoped-ctx)
        adj-ctx    (assoc scoped-ctx :enterprise-tax-gross gross)
        {liability :liability tax-resolved :resolved}
        (ts/apply-adjustments gross tax-items adj-ctx)]
    {:kind            :corporate-income-tax
     :authority       :jp-prefecture
     :base            {:amount book-profit :commodity functional-commodity}
     :schedule        schedule
     :gross-liability {:amount gross :commodity functional-commodity}
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance]) tax-resolved)
     :liability       {:amount liability :commodity functional-commodity}
     :regime          nil
     :provenance      {:provider-id :jp-cit
                       :statute     "地方税法 §72 + 特別法人事業税及び特別法人事業譲与税に関する法律"
                       :provisions-applied (mapv :provision/code provisions)
                       :as-of       as-of}}))

(defn- inhabitant-component
  "Build the inhabitants' tax component map. Schedule = flat 0 %
   (carries no base of its own); surtaxes = 法人税割 (7 % × national
   CIT — reads `:national-cit-amount` from ctx) + 均等割 (10-cell
   capital × headcount lookup).

   `:composed-of [:corporate-income-tax]` records the structural
   dependency on the national CIT component (note 110 §4 stress D
   audit trail)."
  [db ctx as-of national-cit-amount functional-commodity]
  (let [;; inject :national-cit-amount so the income-levy compute-fn
        ;; can read it; this is the cross-component-surtax wire-up.
        ctx'       (assoc ctx :national-cit-amount national-cit-amount)
        {:keys [tax-items schedule-overrides provisions]} (component-items db ctx' as-of :inhabitant)
        scoped-ctx (assoc ctx' :component :inhabitant :db db :as-of as-of)
        schedule   (pick-schedule (inhabitant-default-schedule) schedule-overrides)
        gross      (ts/apply-schedule schedule 0M scoped-ctx)
        {liability :liability tax-resolved :resolved}
        (ts/apply-adjustments gross tax-items scoped-ctx)]
    {:kind            :corporate-income-tax
     :authority       :jp-municipality
     :base            {:amount 0M :commodity functional-commodity}
     :schedule        schedule
     :gross-liability {:amount gross :commodity functional-commodity}
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance]) tax-resolved)
     :liability       {:amount liability :commodity functional-commodity}
     :composed-of     [:corporate-income-tax]
     :regime          nil
     :provenance      {:provider-id :jp-cit
                       :statute     "地方税法 §51 + §52 + §312 + §314-4 (法人住民税 法人税割 + 均等割)"
                       :provisions-applied (mapv :provision/code provisions)
                       :depends-on  {:component :national
                                     :national-cit-amount national-cit-amount}
                       :as-of       as-of}}))

(defrecord JPCITProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of       (or (:as-of ctx) (:to period))
          book-profit (or (:book-profit inputs)
                          (throw (ex-info "JP CIT provider needs :inputs :book-profit"
                                          {:inputs inputs})))
          _           (when (nil? (get-in ctx [:tax-unit :is-sme?]))
                        (throw (ex-info "JP CIT provider needs :tax-unit :is-sme? (true|false) — schedule fan-out depends on it"
                                        {:tax-unit (:tax-unit ctx)})))
          national    (national-component db ctx as-of book-profit commodity)
          enterprise  (enterprise-component db ctx as-of book-profit commodity)
          ;; Inhabitants' 法人税割 references the national CIT GROSS
          ;; (法人税 amount BEFORE local CIT / defense surtax surtaxes —
          ;; per JETRO Section 3.3 the 法人税割 base is the 法人税
          ;; figure proper, not the inflated total).
          national-cit-amount (get-in national [:gross-liability :amount])
          inhabitant  (inhabitant-component db ctx as-of national-cit-amount commodity)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :jp}
        :functional-commodity commodity
        :components           [national enterprise inhabitant]}))))

(defn jp-cit-provider
  "Build a JP CIT provider. Statute lives in `:provision` / `:parameter`
   data (installed via `kontor.l10n-jp.cit-statute/install!`); the
   provider just folds the applicable provisions per component.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :jp-cit)
     :commodity — functional commodity (default :JPY)"
  [{:keys [id commodity] :or {id :jp-cit commodity :JPY}}]
  (->JPCITProvider id commodity))

(defn install-statute!
  "Convenience wrapper around `kontor.l10n-jp.cit-statute/install!`
   for callers that want one-call statute setup before constructing
   the provider."
  [conn]
  (cit-statute/install! conn))
