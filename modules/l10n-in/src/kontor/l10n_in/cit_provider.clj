(ns kontor.l10n-in.cit-provider
  "IN corporate income tax provider — Regular CIT + MAT (§115JB)
   composed via `kontor.statute/compose-greater-of` per ADR-101
   Addendum 1. First end-to-end consumer of `compose-greater-of` on a
   real statute — the addendum's docstring names IN MAT as the
   canonical reference case.

   The provider does THREE things and nothing else:

   1. Reads IN CIT rates from `:parameter` data via
      `kontor.statute/parameter-value-at` (rates) and
      `kontor.statute/parameter-brackets-at` (surcharge bands).
   2. Builds one `:regular` component (always) and one `:mat` component
      (when the MAT provision's condition matches — standard regime AND
      not foreign-co). Each component sets `:component` in ctx, queries
      `:elective-regime` for the `:schedule-override`, queries `:surtax`
      for surcharge + cess items, runs the schedule, folds adjustments.
   3. If a MAT component was built, calls `compose-greater-of` to pick
      the prevailing arm (the losing arm is preserved in
      `:composition`); otherwise the single regular component goes
      through unchanged. The returned `TaxReturnFacts` always has ONE
      `:components` element — never two candidates and a winner.

   Everything else — rate values, bracket cutoffs, the AY 2026-27
   foreign-co 35 %, the MAT 14 % step, the cess discipline — lives in
   `kontor.l10n-in.cit-statute` as `:provision` / `:parameter` data,
   NOT in this provider's code.

   ## Inputs the consumer supplies

   `:tax-unit` (company config):
     {:regime         <keyword>  required — one of
                                  :in-cit-standard
                                  :in-cit-115BAA
                                  :in-cit-115BAB
      :turnover-band  <keyword>  required when :regime :in-cit-standard
                                  AND :foreign-co? not true — :small
                                  (PY 2023-24 turnover ≤ ₹400 cr) or
                                  :large (> ₹400 cr). Consumer pre-
                                  computes from prior-period data.
      :foreign-co?    <bool>     optional, default false. When true the
                                  domestic regime provisions don't fire;
                                  the foreign-co rate fires instead.
                                  MAT is also skipped (§115JB(5A) +
                                  consumer convention; provider mirrors).}

   `:inputs` (period facts):
     {:taxable-income      <BigDecimal>  required — ITA §4 base for
                                          regular CIT (after Chapter VI-A
                                          deductions the consumer
                                          maintains outside the substrate)
      :book-profit-115jb   <BigDecimal>  optional — book profit per
                                          §115JB Explanation 1 add-backs
                                          (consumer pre-computes; v1 ships
                                          a single :inputs fact, the
                                          12-row Explanation 1 menu is a
                                          v1.1 catalogue expansion per
                                          note 163 §1.4.2). When absent or
                                          when MAT condition doesn't
                                          match, the MAT component is
                                          not built.
      :prepaid             <Money>       optional — TDS suffered, advance
                                          tax remitted (Chapter XVII-B);
                                          stamped on the prevailing
                                          component for the return.}

   ## Out-of-substrate compute-fns (the ADR-101 §D2 escape hatches)

   Four compute-fns implement the rate-on-rate / banded-with-relief
   computations that exceed the closed predicate vocabulary:

     :in-cit-surcharge-standard      — banded 0/7/12 with marginal
                                       relief at ₹1cr and ₹10cr
                                       (re-used by MAT surcharge)
     :in-cit-surcharge-concessional  — flat 10 % (§115BAA / §115BAB)
     :in-cit-surcharge-foreign       — banded 0/2/5 (no marginal relief)
     :in-cit-cess                    — 4 % × :running (cumulative-of-
                                       prior-passes per ADR-101 §D2)

   ## MAT credit (§115JAA) — deferred to v2

   When MAT prevails, the prevailing component's
   `:provenance :mat-credit-carry-forward` records the excess
   `(MAT - regular)` paid this year — recoverable in any of the next 15
   AYs when regular tax exceeds MAT. **Utilisation in later years**
   waits on note 105 frontier 2 (the kontor carry primitive); this v1
   only records the would-be carry on the audit trail. See research
   note 163 §1.4.3 / §3.5.

   ## DDT — abolished (informational)

   Dividend Distribution Tax (§115-O, ~15 % + surcharge + cess on the
   distributing company) was ABOLISHED with effect from 2020-04-01 by
   Finance Act 2020 §40. India returned to the classical system:
   dividends are taxed in the recipient's hands (§194 resident /
   §195 non-resident TDS). v1 does NOT encode DDT — this paragraph
   exists so future code archaeologists don't search for a §115-O
   provision in vain. See research note 163 §1.5."
  (:require [kontor.l10n-in.cit-statute :as cit-statute]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Compute-fn registration — the ADR-101 §D2 escape hatch
;; ============================================================================

(defn- as-of-from-ctx
  "Read the as-of instant from ctx (`:as-of` preferred; `:period :to`
   as the fallback for callers who only thread the period)."
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- band-for-amount
  "Return the active bracket (a `{:rate :upper}` map) for `amount` from
   a vector of brackets sorted ascending by `:upper` (nil upper = open
   top band)."
  [brackets ^java.math.BigDecimal amount]
  (or (some (fn [b]
              (when (and (:upper b) (<= (compare amount (:upper b)) 0))
                b))
            brackets)
      (last brackets)))

(defn- prior-upper
  "The `:upper` of the bracket immediately below `band`'s position in
   `brackets` — i.e. the threshold this band starts at. Returns nil for
   the lowest band (no threshold means surcharge starts at zero income —
   no marginal-relief cliff)."
  [brackets band]
  (let [idx (loop [i 0
                   bs brackets]
              (cond
                (empty? bs)        nil
                (identical? (first bs) band) i
                :else              (recur (inc i) (rest bs))))]
    (when (and idx (pos? idx))
      (:upper (nth brackets (dec idx))))))

(defn- in-cit-surcharge-standard
  "Banded surcharge with statutory marginal relief at each band
   threshold. Used for BOTH the standard-regime regular surcharge and
   the MAT surcharge (same 0/7/12 % bands).

   Statutory marginal-relief rule (per Finance Act 2025 First Schedule
   Part III A proviso): at each cliff, `(tax + surcharge)` on the higher
   income may not exceed `(tax at exactly the threshold)
   + (income beyond the threshold)`. The relief is the breach amount.

   Late-bound — surcharge depends on `:running` (the gross tax) AND
   `:base` (the income that produced the gross), so the inner fn takes
   the fold ctx threaded with `:running`."
  [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        bands (statute/parameter-brackets-at db "IN.CIT.standard.surcharge-brackets" as-of)]
    (fn [{:keys [base running] :as _ctx-w-running}]
      (let [band      (band-for-amount bands base)
            raw-surch (* running (:rate band))
            threshold (prior-upper bands band)]
        (if (and threshold (pos? raw-surch))
          ;; Marginal-relief check: cap = tax-at-threshold + (base − threshold).
          ;; Tax-at-threshold is `(threshold/base) × running` since the
          ;; underlying rate is flat (standard 25 % or 30 %, MAT 15 %)
          ;; — that's true for every IN CIT regular/MAT case here.
          (let [tax-at-thresh   (* (/ threshold base) running)
                cap             (+ tax-at-thresh (- base threshold))
                tax-plus-surch  (+ running raw-surch)]
            (if (> tax-plus-surch cap)
              (max 0M (- cap running))
              raw-surch))
          raw-surch)))))

(defn- in-cit-surcharge-concessional
  "Flat 10 % surcharge — §115BAA / §115BAB. No cliff, no marginal
   relief."
  [ctx]
  (let [rate (statute/parameter-value-at (:db ctx)
                                         "IN.CIT.concessional.surcharge-rate"
                                         (as-of-from-ctx ctx))]
    (fn [{:keys [running]}]
      (* running rate))))

(defn- in-cit-surcharge-foreign
  "Foreign-co banded surcharge — 0/2/5 % at ₹1cr/₹10cr. No marginal
   relief (statute does not extend Part III A's proviso to Part III E)."
  [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        bands (statute/parameter-brackets-at db "IN.CIT.foreign.surcharge-brackets" as-of)]
    (fn [{:keys [base running]}]
      (let [band (band-for-amount bands base)]
        (* running (:rate band))))))

(defn- in-cit-cess
  "Health & Education Cess — 4 % × `:running`. `:running` at cess time
   is `(gross + surcharge)` because cess priority (500) > surcharge
   priority (100). Re-used by BOTH `:regular` and `:mat` components
   via the shared IN-FinAct-Cess provision (`[:or ...]` condition)."
  [ctx]
  (let [rate (statute/parameter-value-at (:db ctx)
                                         "IN.cess.rate"
                                         (as-of-from-ctx ctx))]
    (fn [{:keys [running]}]
      (* running rate))))

(defn register!
  "Register the four IN CIT compute-fns with `kontor.statute`. Called
   automatically at namespace load; idempotent."
  []
  (statute/register-compute-fn! :in-cit-surcharge-standard     in-cit-surcharge-standard)
  (statute/register-compute-fn! :in-cit-surcharge-concessional in-cit-surcharge-concessional)
  (statute/register-compute-fn! :in-cit-surcharge-foreign      in-cit-surcharge-foreign)
  (statute/register-compute-fn! :in-cit-cess                   in-cit-cess))

(register!)

;; ============================================================================
;; Provider — pulls items from kontor.statute, folds, builds components
;; ============================================================================

(defn- component-items
  "For one component (`:regular` or `:mat`), query the statute for
   applicable `:schedule-override` (elective-regime) and `:surtax`
   provisions and resolve them. Returns
   `{:tax-items :schedule-overrides :provisions}`.

   No base-side queries for IN CIT in v1 — the consumer supplies
   `:taxable-income` already net of Chapter VI-A deductions and
   `:book-profit-115jb` already net of §115JB Explanation 1
   add-backs (note 163 §1.4.2 — long-tail menu deferred to v1.1)."
  [db ctx as-of component]
  (let [scoped-ctx (assoc ctx :component component :db db :as-of as-of)
        overrides  (statute/apply-provisions db {:concept      :elective-regime
                                                 :jurisdiction :in
                                                 :as-of        as-of} scoped-ctx)
        surtaxes   (statute/apply-provisions db {:concept      :surtax
                                                 :jurisdiction :in
                                                 :as-of        as-of} scoped-ctx)]
    {:schedule-overrides (:schedule-overrides overrides)
     :tax-items          (:tax-items surtaxes)
     :provisions         (concat (:provisions overrides) (:provisions surtaxes))}))

(defn- pick-schedule-or-throw
  "Pick the (first / highest-priority) `:schedule-override` for a
   component or throw a helpful error explaining what `:tax-unit`
   facts were expected. Same-priority ambiguity has already been
   trapped by `apply-provisions`."
  [overrides component tax-unit]
  (or (some-> overrides first :schedule)
      (throw (ex-info (str "IN CIT " (name component)
                           " component: no schedule-override fired — check :tax-unit :regime"
                           " (and :turnover-band when :in-cit-standard, :foreign-co? when applicable)")
                      {:component component :tax-unit tax-unit}))))

(defn- regular-component
  "Build the `:regular` component map. Schedule comes from the matching
   `:schedule-override` (standard small/large, §115BAA, §115BAB, or
   foreign-co); surtaxes = surcharge + cess. Always built (the consumer
   must always have a regime + a regular-tax base)."
  [db ctx as-of taxable-income functional-commodity]
  (let [{:keys [schedule-overrides tax-items provisions]}
        (component-items db ctx as-of :regular)
        schedule   (pick-schedule-or-throw schedule-overrides :regular (:tax-unit ctx))
        scoped-ctx (assoc ctx :component :regular :db db :as-of as-of
                          :base taxable-income)
        gross      (ts/apply-schedule schedule taxable-income)
        {liability :liability tax-resolved :resolved}
        (ts/apply-adjustments gross tax-items scoped-ctx)]
    {:kind            :corporate-income-tax
     :authority       :in-cbdt
     :base            {:amount taxable-income :commodity functional-commodity}
     :schedule        schedule
     :gross-liability {:amount gross :commodity functional-commodity}
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance]) tax-resolved)
     :liability       {:amount liability :commodity functional-commodity}
     :regime          (get-in ctx [:tax-unit :regime])
     :provenance      {:provider-id        :in-cit
                       :statute            "Income-tax Act 1961 (Regular CIT)"
                       :provisions-applied (mapv :provision/code provisions)
                       :as-of              as-of}}))

(defn- mat-component
  "Build the `:mat` component map. Returns nil when no MAT schedule
   fires (concessional regime / foreign-co / `:tax-unit :regime` other
   than `:in-cit-standard`) OR when the consumer supplied no
   `:book-profit-115jb` — both are valid 'no MAT' signals; the caller
   skips composition in either case."
  [db ctx as-of book-profit functional-commodity]
  (let [{:keys [schedule-overrides tax-items provisions]}
        (component-items db ctx as-of :mat)]
    (when (seq schedule-overrides)
      (let [schedule   (-> schedule-overrides first :schedule)
            scoped-ctx (assoc ctx :component :mat :db db :as-of as-of
                              :base book-profit)
            gross      (ts/apply-schedule schedule book-profit)
            {liability :liability tax-resolved :resolved}
            (ts/apply-adjustments gross tax-items scoped-ctx)]
        {:kind            :minimum-tax
         :authority       :in-cbdt
         :base            {:amount book-profit :commodity functional-commodity}
         :schedule        schedule
         :gross-liability {:amount gross :commodity functional-commodity}
         :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance]) tax-resolved)
         :liability       {:amount liability :commodity functional-commodity}
         :regime          (get-in ctx [:tax-unit :regime])
         :provenance      {:provider-id        :in-cit
                           :statute            "§115JB Minimum Alternate Tax"
                           :provisions-applied (mapv :provision/code provisions)
                           :as-of              as-of}}))))

(defn- annotate-mat-credit
  "When MAT prevailed, stamp `:provenance :mat-credit-carry-forward`
   with the excess MAT paid this year (max 15 AYs per §115JAA). The
   credit's utilisation in later AYs waits on note 105 frontier 2 — v1
   only records it on the audit trail."
  [prevailing regular mat commodity]
  (let [reg-amt (-> regular :liability :amount)
        mat-amt (-> mat :liability :amount)]
    (if (> mat-amt reg-amt)
      (assoc-in prevailing [:provenance :mat-credit-carry-forward]
                {:amount    (- mat-amt reg-amt)
                 :commodity commodity
                 :max-years 15
                 :statute   "§115JAA"
                 :status    :recorded-deferred-utilisation})
      prevailing)))

(defrecord INCITProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of           (or (:as-of ctx) (:to period))
          taxable-income  (or (:taxable-income inputs)
                              (throw (ex-info "IN CIT provider needs :inputs :taxable-income"
                                              {:inputs inputs})))
          _               (when (nil? (get-in ctx [:tax-unit :regime]))
                            (throw (ex-info "IN CIT provider needs :tax-unit :regime — one of :in-cit-standard / :in-cit-115BAA / :in-cit-115BAB"
                                            {:tax-unit (:tax-unit ctx)})))
          book-profit     (:book-profit-115jb inputs)
          regular         (regular-component db ctx as-of taxable-income commodity)
          mat             (when book-profit
                            (mat-component db ctx as-of book-profit commodity))
          prevailing      (if mat
                            (-> (statute/compose-greater-of regular mat)
                                (annotate-mat-credit regular mat commodity))
                            regular)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :in :authority :in-cbdt}
        :functional-commodity commodity
        :components           [prevailing]}))))

(defn in-cit-provider
  "Build an IN CIT provider. Statute lives in `:provision` /
   `:parameter` data (installed via
   `kontor.l10n-in.cit-statute/install!`); the provider just folds the
   applicable provisions per component and composes via
   `kontor.statute/compose-greater-of`.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :in-cit)
     :commodity — functional commodity (default :INR)"
  [{:keys [id commodity] :or {id :in-cit commodity :INR}}]
  (->INCITProvider id commodity))

