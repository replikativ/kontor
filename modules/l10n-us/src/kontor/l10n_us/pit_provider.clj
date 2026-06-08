(ns kontor.l10n-us.pit-provider
  "US federal personal income tax provider — Form 1040 / IRC §1 —
   built as a `PeriodTaxProvider` (ADR-099) over the statute-as-data
   substrate (ADR-101). Mirrors
   `kontor.l10n-at.pit-provider` structurally (single-component fold)
   with one twist: the §1(j) progressive bracket schedule is selected
   per `:tax-unit :filing-status` from FOUR separate `:bracket-scale`
   parameters (one per status), instead of AT's single bracket-scale
   parameter. **Federal-only.** State PIT is OUT of substrate per
   ADR-005 / ADR-010 /.

   The provider does FOUR things and nothing else:

   1. Reads `:tax-unit :filing-status` (default `:single`; raises on
      unknown — loud-fail discipline). Picks the right
      `US.PIT.§1.brackets-<status>` parameter.
   2. Reads the `:effective-from`-keyed bracket scale for `:as-of`
      from `:parameter-bracket` data.
   3. For the single `:pit` component sets `:component :pit` in ctx,
      calls `kontor.tax.statute/apply-provisions` for each relevant
      concept, folds base-side adjustments (std deduction OR itemized;
      CGT pit-base lane; investment-income pit-base lane) + applies
      the bracket schedule + folds tax-side credits (CTC non-refundable
      + ACTC refundable) via `apply-adjustments`. Injects
      `:tax-before-credits` into ctx before tax-side adjustments so
      the ACTC compute-fn can compute the residual.
   4. Assembles a 1-component `TaxReturnFacts`.

   ## Filing-status fan-out

   v1 ships all 4 statuses: `:single`, `:mfj`, `:mfs`, `:hoh`. The
   substrate cannot partial-ship and remain correct — a `:filing-status
   :hoh` query with no HoH parameter would raise. The legacy
   record-shape spelling `:married-filing-jointly` / `:married-filing-
   separately` / `:head-of-household` is mapped to the new short keys
   for back-compat (the old factory's `:filing-statuses` constant
   remains in `period_tax_provider.clj` for documentation).

   ## §1411 NIIT, LT capital gain, qualified dividends, §1250-unrecaptured

   These stay on their respective providers' components (with their own
   preferential schedules). The PIT provider does NOT consume them via
   `:base-additions` and does NOT re-emit NIIT (one-surtax-one-owner
   discipline).

   ## Inputs the consumer supplies

   `:tax-unit` (filing-unit / household config):
     {:filing-status                   #{:single :mfj :mfs :hoh}
                                       optional, default :single
      :itemized?                       <bool>  optional, default false
      :qualifying-children-under-17    <long>  optional, default 0
                                              (drives §24 CTC + ACTC)}

   `:inputs` (period facts):
     {:gross-income                    <BigDecimal>  required —
                                                     AGI (after consumer-
                                                     side above-the-line
                                                     adjustments)
      :itemized-deductions             <BigDecimal>  optional — required
                                                     when :tax-unit
                                                     :itemized? true
      :earned-income                   <BigDecimal>  optional — drives
                                                     the ACTC 15 %
                                                     earned-income cap
      :cgt-pit-base-additions          <BigDecimal>  optional — ST cap
                                                     gain + §1245/§1250
                                                     individual recapture
                                                     (cgt-provider lane)
      :investment-pit-base-additions   <BigDecimal>  optional — ordinary
                                                     dividends + interest
                                                     + §163(d) deduction
                                                     (investment-income
                                                     lane; may be negative)}

   ## Compute-fns

   - `:us-std-deduction-amount` — reads `:tax-unit :filing-status`,
     looks up the right per-status §63 std-deduction parameter.
   - `:us-ctc-non-refundable` — min(running, $2 000 × children).
     The `:running` ctx key is the tax-before-this-credit; for the
     first credit applied it equals gross-liability.
   - `:us-ctc-refundable` — min(ACTC-residual, $1 700 × children,
     15 % × (earned-income − $2 500)). Reads `:tax-before-credits`
     from ctx (the provider injects it before apply-adjustments).

   ## TCJA 2025-12-31 sunset

   §1(j) brackets, §24 CTC $2 000, §63 std deduction levels sunset
   2025-12-31 unless extended. The substrate ships TY 2020-2025 with
   intentional loud-fail behavior past sunset; consumer or maintainer
   ships the post-sunset parameter rows once Congress acts.

   ## TODO — audit-doc seam

   The eventual posting builder (`tax-return-posting-builder`) does
   not yet stamp `:transaction/audit-doc` referencing the responsible
   `:kontor.provision`. The citation already lives on the provision
   row; the wire-up is a ~50 LOC kernel sweep tracked as a follow-up."
  (:require [kontor.l10n-us.pit-statute :as pit-statute]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; suppress 'unused require' lint — symmetry with FR / DE / JP / CA / AT templates.
(comment pit-statute/install!)

;; ============================================================================
;; Filing-status handling
;; ============================================================================

(def filing-statuses
  "The closed set of Form 1040 filing statuses. A qualifying surviving
   spouse files on the MFJ schedule, so no separate table is needed.
   Note these are the SHORT-NAME spellings — recipe convention §3.4.
   The legacy v0.x record-shape provider used the long-form spellings
   (`:married-filing-jointly`, etc.); the deprecated forwarder in
   `period_tax_provider.clj` preserves the long-form public API and
   maps to these short keys internally."
  #{:single :mfj :mfs :hoh})

(defn- bracket-parameter-code
  "Pick the right §1(j) bracket parameter for a filing status. Throws
   on an unknown status rather than silently mis-taxing."
  [fs]
  (case fs
    :single "US.PIT.§1.brackets-single"
    :mfj    "US.PIT.§1.brackets-mfj"
    :mfs    "US.PIT.§1.brackets-mfs"
    :hoh    "US.PIT.§1.brackets-hoh"
    (throw (ex-info (str "US PIT provider — unknown :tax-unit :filing-status " (pr-str fs)
                         ". v1 supports :single / :mfj / :mfs / :hoh.")
                    {:filing-status fs :expected filing-statuses}))))

(defn- standard-deduction-parameter-code
  "Pick the right §63(c) std-deduction parameter for a filing status."
  [fs]
  (case fs
    :single "US.PIT.§63.standard-deduction-single"
    :mfj    "US.PIT.§63.standard-deduction-mfj"
    :mfs    "US.PIT.§63.standard-deduction-mfs"
    :hoh    "US.PIT.§63.standard-deduction-hoh"
    (throw (ex-info (str "US PIT provider — unknown :tax-unit :filing-status " (pr-str fs))
                    {:filing-status fs :expected filing-statuses}))))

(defn- resolve-filing-status
  "Resolve `:tax-unit :filing-status` (default `:single`). Loud-fails on
   unknown values via `bracket-parameter-code`."
  [ctx]
  (let [fs (get-in ctx [:tax-unit :filing-status] :single)]
    ;; Trigger the closed-set validation eagerly:
    (bracket-parameter-code fs)
    fs))

;; ============================================================================
;; Compute-fns — registered via `register!` at namespace load (recipe §A-5)
;; ============================================================================

(defn- as-of-from-ctx
  "Read the as-of instant from ctx (`:as-of` preferred; `:period :to`
   as fallback for callers who only thread the period)."
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- us-std-deduction-amount
  "Per-filing-status §63(c) standard-deduction lookup. Reads
   `:tax-unit :filing-status` from ctx and the corresponding parameter
   from the substrate."
  ^java.math.BigDecimal [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        fs    (resolve-filing-status ctx)
        code  (standard-deduction-parameter-code fs)]
    (or (statute/parameter-value-at db code as-of)
        (throw (ex-info (str "US PIT provider — no §63(c) std deduction value at as-of "
                             (pr-str as-of) " for " (pr-str fs))
                        {:as-of as-of :filing-status fs :code code})))))

(defn- us-ctc-non-refundable
  "§24(a) Child Tax Credit non-refundable portion =
   min(tax-before-credits, $2 000 × qualifying-children).

   Returns a `(fn [ctx-with-:running])` so `apply-adjustments` late-
   binds the running tax at fold time (same shape as the DE Soli
   compute-fn — `cit_provider.clj:83-88`). With CTC non-refundable
   as the FIRST credit applied, `:running` equals gross-liability
   (i.e. tax-before-credits)."
  [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        n     (or (get-in ctx [:tax-unit :qualifying-children-under-17]) 0)
        per   (or (statute/parameter-value-at db "US.PIT.§24.ctc-per-child" as-of)
                  (throw (ex-info "US PIT: no §24 CTC per-child amount at as-of (TCJA sunset?)"
                                  {:as-of as-of})))
        total (* (bigdec n) per)]
    (fn [ctx-w-running]
      (let [running (or (:running ctx-w-running) 0M)]
        ;; apply-adjustments will floor at zero for non-refundable
        ;; credits, so we don't need to cap here ourselves — but
        ;; capping at running preserves the audit display amount
        ;; (Schedule 8812 line 14 shows the credit that was actually
        ;; applied, not the unused potential).
        (min total (max 0M running))))))

(defn- us-ctc-refundable
  "§24(d) ACTC refundable portion =
   min(actc-residual, $1 700 × qualifying-children, 15 % × (earned-income − $2 500)).

   actc-residual = (CTC-potential = $2 000 × children) − non-refundable-applied,
                 = max(0, $2 000 × children − tax-before-credits)
                 (since non-refundable = min(tax-before-credits,
                  $2 000 × children) and running-after-non-refundable
                  = max(0, tax-before − non-refundable-applied))

   Returns a `(fn [ctx-with-:running])` like `us-ctc-non-refundable`.
   The residual is computed from `:tax-before-credits` (the provider
   injects this into ctx before apply-adjustments — see `pit-component`)
   rather than from `:running` because the CTC non-refundable has
   already consumed the latter."
  [ctx]
  (let [db         (:db ctx)
        as-of      (as-of-from-ctx ctx)
        n          (or (get-in ctx [:tax-unit :qualifying-children-under-17]) 0)
        n-bd       (bigdec n)
        ctc-per    (or (statute/parameter-value-at db "US.PIT.§24.ctc-per-child" as-of)
                       (throw (ex-info "US PIT: no §24 CTC per-child amount at as-of (TCJA sunset?)"
                                       {:as-of as-of})))
        actc-per   (or (statute/parameter-value-at db "US.PIT.§24.actc-per-child" as-of)
                       (throw (ex-info "US PIT: no §24 ACTC per-child amount at as-of (TCJA sunset?)"
                                       {:as-of as-of})))
        actc-floor (or (statute/parameter-value-at db "US.PIT.§24.actc-earned-income-floor" as-of) 0M)
        actc-rate  (or (statute/parameter-value-at db "US.PIT.§24.actc-earned-income-rate"  as-of) 0M)
        earned     (or (get-in ctx [:inputs :earned-income]) 0M)]
    (fn [ctx-w-running]
      (let [tax-before    (or (:tax-before-credits ctx-w-running) 0M)
            total-ctc-pot (* n-bd ctc-per)
            non-ref-applied (min total-ctc-pot tax-before)
            actc-residual   (- total-ctc-pot non-ref-applied)
            cap-per-child   (* n-bd actc-per)
            cap-earned      (max 0M (* actc-rate (- earned actc-floor)))]
        ;; ACTC can't go below 0 (it's a refundable credit, not a tax).
        (max 0M (min actc-residual cap-per-child cap-earned))))))

(defn register!
  "Register the three US PIT compute-fns with `kontor.tax.statute`.
   Called automatically at namespace load; idempotent."
  []
  (statute/register-compute-fn! :us-std-deduction-amount us-std-deduction-amount)
  (statute/register-compute-fn! :us-ctc-non-refundable   us-ctc-non-refundable)
  (statute/register-compute-fn! :us-ctc-refundable       us-ctc-refundable))

(register!)

;; ============================================================================
;; Provider — pulls items from kontor.tax.statute, folds, builds component
;; ============================================================================

(defn- component-items
  "For the `:pit` component, query the statute for all applicable
   base-side + tax-side provisions and resolve them. Returns
   `{:base-items :tax-items :provisions}`.

   Tax-items are returned in priority order (apply-provisions handles
   the priority sort), so the CTC non-refundable (priority 200) fires
   BEFORE the ACTC refundable (priority 210) — `apply-adjustments`
   threads `:running` between them."
  [db ctx as-of]
  (let [scoped-ctx (assoc ctx :component :pit :db db :as-of as-of)
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
     ;; non-refundable BEFORE refundable so CTC fires before ACTC
     :tax-items  (vec (concat (:tax-items non-refundable-credits)
                              (:tax-items refundable-credits)
                              (:tax-items surtaxes)))
     :provisions (concat (:provisions adds)
                         (:provisions deducts)
                         (:provisions non-refundable-credits)
                         (:provisions refundable-credits)
                         (:provisions surtaxes))}))

(defn- pit-component
  "Build the §1 PIT component map. Base = gross-income + base-side
   adjustments (std deduction OR itemized; CGT lane; investment-income
   lane); schedule = §1(j) progressive bracket scale from
   `parameter-brackets-at` for `:as-of` × the resolved filing status;
   tax-side = §24 CTC non-refundable + ACTC refundable via
   `apply-adjustments`. Floors gross at 0M when base is negative —
   §1 has no negative tax (refundable credits can still drive
   liability below zero via ACTC)."
  [db ctx as-of gross-income functional-commodity]
  (let [fs        (resolve-filing-status ctx)
        code      (bracket-parameter-code fs)
        brackets  (or (statute/parameter-brackets-at db code as-of)
                      (throw (ex-info (str "US PIT provider: no §1(j) bracket-set in effect at as-of "
                                           (pr-str as-of) " for filing-status " (pr-str fs)
                                           " — install kontor.l10n-us.pit-statute "
                                           "(or check TCJA sunset 2025-12-31)")
                                      {:as-of as-of :filing-status fs :code code})))
        schedule  {:kontor.schedule/type :progressive-bracket :brackets brackets}
        {:keys [base-items tax-items provisions]} (component-items db ctx as-of)
        scoped-ctx (assoc ctx :component :pit :db db :as-of as-of)
        {base'         :base
         base-resolved :resolved} (ts/apply-base-adjustments
                                   gross-income base-items scoped-ctx)
        raw-gross  (ts/apply-schedule schedule base' scoped-ctx)
        ;; §1 has no negative tax — floor gross at 0M.
        gross      (if (neg? raw-gross) 0M raw-gross)
        ;; Inject :tax-before-credits so the ACTC compute-fn can
        ;; compute the residual (apply-adjustments threads :running
        ;; between credits, but the ACTC residual depends on the
        ;; ORIGINAL gross, not on the post-CTC running).
        ctx-w-tbc  (assoc scoped-ctx :tax-before-credits gross)
        {liability   :liability
         tax-resolved :resolved} (ts/apply-adjustments
                                  gross tax-items ctx-w-tbc)]
    {:kind            :personal-income-tax
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
     :regime          fs
     :provenance      {:provider-id        :us-pit
                       :statute            "IRC §1 (rate) + §63 (std deduction) + §24 (CTC) + §1245/§1250 + §163(d) lanes"
                       :provisions-applied (mapv :kontor.provision/code provisions)
                       :filing-status      fs
                       :as-of              as-of}}))

(defrecord UsPitProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of        (or (:as-of ctx) (:to period))
          gross-income (or (:gross-income inputs)
                           (throw (ex-info "US PIT provider needs :inputs :gross-income"
                                           {:inputs inputs})))
          pit-c        (pit-component db ctx as-of gross-income commodity)]
      (ptp/assert-monocommodity-facts!
       (ptp/tax-return-facts
        {:entity               entity
         :period               period
         :jurisdiction         {:country :us :authority :us-irs}
         :functional-commodity commodity
         :components           [pit-c]})))))

(defn us-pit-provider
  "Build a US PIT provider (Form 1040 / IRC §1). Statute lives in
   `:provision` / `:parameter` / `:parameter-bracket` data (installed
   via `kontor.l10n-us.pit-statute/install!`); the provider just folds
   the applicable provisions for the PIT component.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :us-pit)
     :commodity — functional commodity (default :USD)"
  [{:keys [id commodity] :or {id :us-pit commodity :USD}}]
  (->UsPitProvider id commodity))
