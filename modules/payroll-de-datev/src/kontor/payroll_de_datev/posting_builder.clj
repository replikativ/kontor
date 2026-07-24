(ns kontor.payroll-de-datev.posting-builder
  "DATEV LODAS payroll → SKR04 / SKR03 GL posting builder
   (`DatevLodasPostingBuilder`).

   Maps `:kontor.compensation-component/kind` → SKR04 / SKR03 wage accounts
   per the consumer-supplied catalog's `:account-hint` map. Default
   account-map in
   `kontor.payroll-de-datev.wage-types/default-account-map-skr04` /
   `default-account-map-skr03`.

   Per CLAUDE.md money discipline: BigDecimal + commodity ref, never
   doubles. Default rounding HALF-EVEN; no DE jurisdiction mandates
   a different mode for payroll postings (USt is the HALF-UP regulator
   case per ADR-013 — does not apply here).

   ## Output shape

   For each PayrollFact, this builder emits the Bruttomethode posting
   sequence:

     1. Dr Gehalt           Cr Verrechnung      — full gross
     2. Dr Verrechnung      Cr Verb. Lohn       — net pay liability
     3. Dr Verrechnung      Cr Verb. LSt        — withholding tax
     4. Dr Verrechnung      Cr Verb. SV         — employee SI deduction
     5. Dr Soziale Aufw.    Cr Verb. SV         — employer SI (additional)
     6. (Sachbezüge etc. as extra rows when present)

   The Verrechnungskonto nets to zero per fact — the same invariant
   the EXTF Buchungsbeleg parser checks.

   The builder produces flat `:kontor.posting/*` maps ready for
   `kontor.posting/build-transaction` (per ADR-068 *-tx-data builder
   convention). The orchestrator (`kontor.hr.payroll/run-payroll!`)
   wraps them into one balanced `:transaction`.

   ## HGB §249 PTO accrual

   `urlaubsrueckstellung-tx-data` ships the simplified Mittelstand-
   suitable PTO accrual sketched in.1. Larger employers
   override the algorithm; pensions stay
   actuarial-out-of-scope (consumer supplies the valuation amount +
   audit-doc per ADR-038).

   License posture (ADR-001 / ADR-005 / ADR-071 / ADR-075): SKR04 /
   SKR03 numbering is a public DATEV cooperative standard. The
   account map ships as code (not bundled rate table); HGB §249 is
   facts of law not subject to copyright; the algorithm sketch is
   clean-room from the cited public sources (Haufe / hrworks)."
  (:require [kontor.account :as kacct]
            [kontor.payroll-de-datev.wage-types :as wage-types]
            [kontor.provider.payroll-provider :as pp])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Account ref resolution
;; ============================================================================

(defn- resolve-default-code
  "Resolve a default SKR04/03 code to a TRANSACTABLE account ref. Because
   `:kontor.account/code` is NOT `:db/unique` (ADR-119), a bare
   `[:kontor.account/code c]` lookup-ref cannot be transacted (note-196 N1)
   — so when a `db` is available, resolve the code to an eid within THIS
   book (code-as-convenience-within-a-book, the sanctioned ADR-119 use).
   Exactly one match → its eid; none → nil (caller throws \"no account\");
   more than one → a clear ambiguity error naming the fix. With no `db`,
   fall back to the code lookup-ref (works only if the caller later resolves
   it or the store happens to make code unique).

   The strict semantics this fn pioneered now live in `kontor.account/resolve-code`
   — the kernel's single home for code→eid resolution (note 198 audit)."
  [db code]
  (if db
    (kacct/resolve-code db code {:context "DE payroll (SKR04/SKR03)"})
    [:kontor.account/code code]))

(defn resolve-account-ref
  "Resolve an account-hint to a kontor account ref. Strategy:
     1. Consumer-supplied `:accounts` map keyed by account-hint
        (e.g. {:gehalt 12345} or {:gehalt [:kontor.account/path \"Aufwand:Gehälter\"]}).
     2. Catalog's `account-overrides`.
     3. Default-account-maps[coa], resolved code→eid against `:db`
        (ADR-119: code is not unique, so it is resolved within the book,
        not emitted as a bare code lookup-ref — note-196 N1).
   Returns nil when no mapping available — caller decides how to
   surface (throw, route to manual review)."
  [{:keys [accounts catalog db]} account-hint]
  (or (get accounts account-hint)
      (when-let [code (wage-types/resolve-account-code catalog account-hint)]
        (resolve-default-code db code))))

(defn- account-ref-or-throw
  [ctx account-hint role]
  (or (resolve-account-ref ctx account-hint)
      (throw (ex-info (str "DE-DATEV posting builder: no account mapped for "
                           role " (hint " account-hint ")")
                      {:account-hint account-hint :role role}))))

(defn- ^BigDecimal money-amount
  "Coerce to 2-decimal HALF-EVEN — kontor money discipline."
  [^BigDecimal bd]
  (.setScale bd 2 RoundingMode/HALF_EVEN))

(defn- ^BigDecimal neg [^BigDecimal bd] (.negate bd))

;; ============================================================================
;; Single-fact posting expansion (Bruttomethode)
;; ============================================================================

(defn- gross-leg
  [{:keys [commodity ledger] :as _ctx} acct ^BigDecimal amt narration]
  (cond-> {:kontor.posting/account acct
           :kontor.posting/amount  (money-amount amt)
           :kontor.posting/commodity commodity
           :kontor.posting/narration narration}
    ledger (assoc :kontor.posting/ledger ledger)))

(defn- gross-postings
  "Per PayrollFact emit the gross-side Dr legs:
     - For each non-employer-side, non-deduction component (positive
       amount) → Dr <expense account> Cr Verrechnung.
     - For each employer-side component → Dr <expense account> Cr
       <verb-sozialversicherung>.
     - Deductions (withholding, employee SI) are emitted from the
       Verrechnung side as paired Dr Verrechnung Cr Verb-X."
  [{:keys [pnr period] :as fact} ctx]
  (let [verrechnung (account-ref-or-throw ctx :verrechnung :verrechnungskonto)
        verb-lohn   (account-ref-or-throw ctx :verb-lohn :verb-lohn)
        verb-lst    (account-ref-or-throw ctx :verb-lohnsteuer :verb-lohnsteuer)
        verb-sv     (account-ref-or-throw ctx :verb-sozialversicherung :verb-sozialversicherung)
        narration   (fn [comp]
                      (str (name (:kind comp)) " "
                           (or pnr (:employment-pnr fact)) " "
                           (or period (:pay-period fact))))
        kind->hint  (fn [comp]
                      (or (:account-hint comp)
                          (case (:kind comp)
                            :base-wage     :lohn
                            :base-salary   :gehalt
                            :overtime      :gehalt
                            :weihnachtsgeld :freiwillig-st-pflichtig
                            :urlaubsgeld   :freiwillig-st-pflichtig
                            :bonus         :freiwillig-st-pflichtig
                            :bonus-target  :freiwillig-st-pflichtig
                            :vwl           :freiwillig-st-pflichtig
                            :imputed-income-tax-exempt :sachbezug-frei
                            :imputed-income-taxable    :freiwillig-st-pflichtig
                            :bav-direktversicherung    :freiwillig-st-pflichtig
                            :employer-si   :soziale-aufwendungen
                            :employer-pension :soziale-aufwendungen
                            nil)))
        gross-comp? (fn [{:keys [kind amount employer-side?]}]
                      (and (pos? (.compareTo ^BigDecimal amount 0M))
                           (not employer-side?)
                           (#{:base-wage :base-salary :overtime
                              :weihnachtsgeld :urlaubsgeld :bonus :bonus-target
                              :vwl :imputed-income-tax-exempt :imputed-income-taxable
                              :bav-direktversicherung} kind)))
        employer-comp? (fn [{:keys [employer-side? amount]}]
                         (and employer-side?
                              (pos? (.compareTo ^BigDecimal amount 0M))))]
    (concat
     ;; Gross expense rows: Dr expense / Cr Verrechnung
     (for [comp (filter gross-comp? (:components fact))
           :let [hint (kind->hint comp)
                 acct (account-ref-or-throw ctx hint (str "expense:" (name (:kind comp))))]
           leg   [(gross-leg ctx acct (:amount comp) (narration comp))
                  (gross-leg ctx verrechnung (neg (:amount comp))
                             (str "Verrechnung " (narration comp)))]]
       leg)
     ;; Withholding tax: Dr Verrechnung / Cr Verb-LSt
     (when-some [wht (:withholding-tax fact)]
       (when (pos? (.signum ^BigDecimal wht))
         [(gross-leg ctx verrechnung wht (str "Verrechnung Lohnsteuer " (or pnr "")))
          (gross-leg ctx verb-lst (neg wht) (str "Verb. LSt " (or pnr "")))]))
     ;; Employee SI: Dr Verrechnung / Cr Verb-SV
     (when-some [esi (:employee-si fact)]
       (when (pos? (.signum ^BigDecimal esi))
         [(gross-leg ctx verrechnung esi (str "Verrechnung AN-SV " (or pnr "")))
          (gross-leg ctx verb-sv (neg esi) (str "Verb. SV AN " (or pnr "")))]))
     ;; Net: Dr Verrechnung / Cr Verb-Lohn
     (let [net (:net fact)]
       (when (and net (pos? (.signum ^BigDecimal net)))
         [(gross-leg ctx verrechnung net (str "Verrechnung Net " (or pnr "")))
          (gross-leg ctx verb-lohn (neg net) (str "Verb. Lohn " (or pnr "")))]))
     ;; Employer SI: Dr Soz.Aufw. / Cr Verb-SV (own row, additional expense)
     (for [comp (filter employer-comp? (:components fact))
           :let [hint (kind->hint comp)
                 acct (account-ref-or-throw ctx hint (str "employer:" (name (:kind comp))))]
           leg   [(gross-leg ctx acct (:amount comp) (str "AG " (narration comp)))
                  (gross-leg ctx verb-sv (neg (:amount comp))
                             (str "Verb. SV AG " (narration comp)))]]
       leg))))

;; ============================================================================
;; PayrollPostingBuilder impl
;; ============================================================================

(defrecord DatevLodasPostingBuilder [opts]
  pp/PayrollPostingBuilder
  (build-postings [_ facts {:keys [accounts ledger fx-provider db]}]
    (let [{:keys [catalog commodity]} opts
          _ (when-not commodity
              (throw (ex-info ":commodity required (typically EUR ref)" {})))
          ctx {:catalog catalog
               :accounts accounts
               :commodity commodity
               :ledger ledger
               :fx-provider fx-provider
               ;; :db lets the default SKR04 routing resolve codes → eids
               ;; within this book (ADR-119; note-196 N1). Omit it and pass
               ;; an explicit :accounts map for db-free builds.
               :db db}]
      (vec (mapcat #(gross-postings % ctx) facts)))))

(defn make-builder
  "Construct a `DatevLodasPostingBuilder`. Required opts:

     :catalog    — validated wage-type catalog (per
                    `kontor.payroll-de-datev.wage-types/validate-catalog`)
     :commodity  — :commodity ref (typically EUR — `[:kontor.commodity/symbol \"EUR\"]`)

   The build-postings call receives a per-call :accounts override
   map (account-hint → account ref) that takes precedence over the
   catalog's defaults."
  [opts]
  (when-not (:catalog opts)
    (throw (ex-info ":catalog required" {})))
  (when-not (:commodity opts)
    (throw (ex-info ":commodity required" {})))
  (->DatevLodasPostingBuilder opts))

;; ============================================================================
;; HGB §249 — Urlaubsrückstellung (PTO accrual) —.1
;; ============================================================================

(def ^:const default-arbeitstage-handelsbilanz
  "Default 'tatsächliche Arbeitstage' for HGB-Handelsbilanz PTO formula.
   220 = 250 (5-day-week max) − ~25 vacation − ~5 sick / public-holiday
   adjustment. Mittelstand default; consumers override."
  220)

(def ^:const default-arbeitstage-steuerbilanz
  "Default 'regelmäßige Arbeitstage' per BFH simplified rule. Note 82 §5.1."
  250)

(def ^:const default-ag-sv-rate
  "Rough Mittelstand AG-SV factor (KV+RV+AV+PV+UV+Umlagen employer
   shares). Note 82 §5.1; consumers override per actual Beitragssätze."
  0.21M)

(defn urlaubsrueckstellung-amount
  "Compute one employee's Urlaubsrückstellung per the simplified
   formula in.1.

   Inputs:
     :annual-gross         — BigDecimal, employee's annualized base
     :accrued-vacation-days — BigDecimal, untaken vacation at as-of
     :framework            — :hgb-handelsbilanz (default) | :de-steuerbilanz
     :include-ag-sv?       — bool, default true under HGB; false under
                              Steuerbilanz per §6a EStG
     :include-urlaubsgeld? — bool, default false; consumer flips when
                              CBA / contract mandates an Urlaubsgeld
                              proportional addition
     :ag-sv-rate           — BigDecimal, default 0.21
     :urlaubsgeld-rate     — BigDecimal, default 0.05
     :arbeitstage          — long, override default per framework"
  [{:keys [annual-gross accrued-vacation-days framework
           include-ag-sv? include-urlaubsgeld?
           ag-sv-rate urlaubsgeld-rate arbeitstage]
    :or {framework :hgb-handelsbilanz
         ag-sv-rate default-ag-sv-rate
         urlaubsgeld-rate 0.05M}}]
  (let [include-ag-sv? (if (nil? include-ag-sv?)
                         (= framework :hgb-handelsbilanz)
                         include-ag-sv?)
        arbeitstage (or arbeitstage
                        (case framework
                          :de-steuerbilanz default-arbeitstage-steuerbilanz
                          default-arbeitstage-handelsbilanz))
        ag-sv-add (if include-ag-sv?
                    (.multiply ^BigDecimal annual-gross ^BigDecimal ag-sv-rate)
                    0M)
        ug-add    (if include-urlaubsgeld?
                    (.multiply ^BigDecimal annual-gross ^BigDecimal urlaubsgeld-rate)
                    0M)
        total-cost (.add (.add ^BigDecimal annual-gross ^BigDecimal ag-sv-add)
                         ^BigDecimal ug-add)
        tagessatz (.divide ^BigDecimal total-cost
                           (BigDecimal/valueOf (long arbeitstage))
                           10 RoundingMode/HALF_EVEN)
        amount (.multiply ^BigDecimal tagessatz
                          ^BigDecimal accrued-vacation-days)]
    (money-amount amount)))

(defn urlaubsrueckstellung-tx-data
  "Pure tx-data builder for an Urlaubsrückstellung accrual posting
. Returns a vector of `:kontor.posting/*` maps ready for
   `kontor.posting/build-transaction-tx-data`.

   Inputs:
     :amount    — BigDecimal, the Rückstellungs-Betrag (consumer can
                   pass directly or compute via
                   `urlaubsrueckstellung-amount`)
     :commodity — :commodity ref
     :accounts  — override map (default uses catalog defaults for
                   :urlaubsrueckstellung-aufw + :urlaubsrueckstellung)
     :catalog   — wage-type catalog (for default account-map lookup)
     :ledger    — :ledger ref (typically :de-handelsrecht for HGB
                   accrual or :de-steuerrecht for BFH); see ADR-021
     :narration — string"
  [{:keys [amount commodity accounts catalog ledger narration]
    :or {narration "Urlaubsrückstellung"}}]
  (when-not amount    (throw (ex-info ":amount required" {})))
  (when-not commodity (throw (ex-info ":commodity required" {})))
  (let [ctx {:catalog catalog :accounts accounts}
        aufw-acct (account-ref-or-throw ctx :urlaubsrueckstellung-aufw
                                        :urlaubsrueckstellung-aufwand)
        rs-acct   (account-ref-or-throw ctx :urlaubsrueckstellung
                                        :urlaubsrueckstellung)]
    [(cond-> {:kontor.posting/account aufw-acct
              :kontor.posting/amount  (money-amount amount)
              :kontor.posting/commodity commodity
              :kontor.posting/narration narration}
       ledger (assoc :kontor.posting/ledger ledger))
     (cond-> {:kontor.posting/account rs-acct
              :kontor.posting/amount  (neg (money-amount amount))
              :kontor.posting/commodity commodity
              :kontor.posting/narration narration}
       ledger (assoc :kontor.posting/ledger ledger))]))
