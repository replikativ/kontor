(ns kontor.l10n-de.investment-income-provider
  "DE investment-income tax provider — `PeriodTaxProvider` (ADR-099)
   over the ADR-101 statute-as-data substrate. Research note 147.

   Covers §20 EStG INCOME (Abs. 1) under the Abgeltungsteuer regime
   (§32d EStG):

     - **Dividends** (§20 Abs. 1 Nr. 1) — from shares, GmbH-Anteile,
       Genussrechte.
     - **Interest** (§20 Abs. 1 Nr. 5 + Nr. 7) — bond, savings,
       Hypothekenzinsen.
     - **Fund distributions + Vorabpauschale** (InvStG layered on top).
     - **Royalty-like Kapitalforderungen** — generic §20 Abs. 1 Nr. 7
       catch-all.

   §20 Abs. 2 CAPITAL-GAINS (sale of securities, etc.) are the DE CGT
   provider's territory; this provider handles ONLY the income side.

   ## Rate stack — § 32d Abs. 1 EStG

   - **Abgeltungsteuer 25 % flat** — `DE.EStG.§20.flat-rate` (owned
     by `cgt-statute`).
   - **Solidaritätszuschlag 5.5 %** — surtax on the Abgeltungsteuer.
     Reuses the `:de-soli-on-abgeltungsteuer` compute-fn registered
     by the DE CGT provider, but the provision in `cgt-statute`
     scopes on `:eq :component :de-§20`, so this provider scopes its
     §20-income component on `:de-§20-income` and ships its OWN
     Soli-on-§20-income provision in this namespace.

   Wait — actually we mirror the DE CGT pattern fully: we write our
   own Soli provision keyed on `:de-§20-income`, AND we register our
   own compute-fn under a distinct key to avoid the namespace
   collision with the CGT provider's `:de-soli-on-abgeltungsteuer`
   (both compute the same thing; keeping them separate keeps the
   provider standalone-runnable).

   - **Kirchensteuer 8 % / 9 %** — when church-affiliated, consumer
     supplies `:tax-unit :church-tax-rate` (0M / 0.08M / 0.09M). Fired
     by the `DE-KiStG-on-§20` provision in `investment-income-statute`.

   When `k > 0` the § 32d Abs. 1 formula's Sonderausgaben-effect
   `(e − 4q) / (4 + k)` reduces the effective Abgeltungsteuer rate.
   Provider-internal: when `k > 0` the schedule uses
   `(ts/flat (/ 1M (+ 4M k)))` instead of the bare 25 %. No new
   substrate operator. (Note 147 §3.3 — the parameter
   `DE.EStG.§20.flat-rate` stays 0.25; the formula adjustment is
   driven by the tax-unit slot.)

   ## Sparer-Pauschbetrag — § 20 Abs. 9 EStG

   €1 000 single / €2 000 joint (from 2023; pre-2023 €801 / €1 602).
   The provider applies it on its OWN §20-income base. PER NOTE 147
   §5, the allowance is statutorily SHARED with §20 CGT base — if
   both the DE CGT provider AND this provider run for the same period,
   the consumer is responsible for not double-allocating it (the
   bridge fn would be `combined-§20-facts` per note 147 §5 option c;
   future work — out of scope for v1).

   ## Günstigerprüfung — § 32d Abs. 6 EStG

   `:tax-unit :abgeltungsteuer-elect-marginal? true` SUPPRESSES the
   standalone §20-income component and folds the §20 base (after
   Sparer-Pauschbetrag survives per BFH VIII R 14/13) into the PIT
   base via `:pit-base-additions`. Mirrors the DE CGT provider's §20
   Günstigerprüfung pattern. The election is all-or-nothing across
   §20 income + gains; the consumer must wire BOTH providers
   consistently.

   ## Teileinkünfteverfahren — § 32d Abs. 2 Nr. 3 EStG

   Per-issuer election (≥25 % stake or ≥1 % + active influence) folds
   60 % of the dividend into the marginal PIT base. Consumer flags
   per-issuer via `:tax-unit :teileinkünfte-elected-issuers` (a set
   of partner refs). For v1 the consumer may also pass elected
   dividends pre-aggregated via
   `:inputs :investment-income-bases :elected-dividends`. The provider
   surfaces a separate `:de-§20-teileinkünfte` component carrying
   60 % × dividend in `:pit-base-additions`.

   ## §20-other bucket coordination

   Dividends + interest land in the GENERAL §20 bucket per § 20 Abs. 6
   S. 4 EStG (note 147 §1.6) — the substrate key is `:de-§20-other`.
   The DE CGT provider's `:de-§20-other` carry-in is SHARED with this
   provider; v1 reads `:inputs :capital-loss-carryforward :de-§20-other`
   directly. Net negatives surface as a `:loss-bucket-contribution` on
   the standalone component for downstream carryforward (mirrors the
   CGT provider).

   ## Chart-of-accounts convention

   When the consumer does NOT pre-supply bases via
   `:inputs :investment-income-bases`, the provider scans the GL by
   chart-prefix:

     Income:Dividends        → dividends
     Income:Interest         → interest
     Income:Fund-Distributions → fund distributions (incl. Vorabpauschale)
     Income:Royalties        → §20 Abs. 1 Nr. 7 catch-all

   Note 147 §3.2."
  (:require [datahike.api :as d]
            [kontor.l10n-de.investment-income-statute :as inv-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.report :as report]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Compute-fn registration — Soli + KiSt on §20 Abgeltungsteuer
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- de-soli-on-§20-income
  "Soli (5.5 %) × the gross §20-income Abgeltungsteuer. Reads
   `:component-gross` (the bare gross Abgst the provider injects into
   ctx) directly — per § 32d Abs. 1 EStG Soli applies to the
   Abgeltungsteuer itself, NOT to the running tax (linear so the
   ordering doesn't change the sum). Sibling of the CGT provider's
   `:de-soli-on-abgeltungsteuer`; we register a distinct key so each
   provider remains standalone-runnable."
  [ctx]
  (let [rate  (statute/parameter-value-at (:db ctx) "DE.Soli.rate" (as-of-from-ctx ctx))
        gross (or (:component-gross ctx) 0M)]
    (* gross rate)))

(defn- de-kist-on-abgeltungsteuer
  "Kirchensteuer (8 % BY/BW or 9 % other) × the gross §20-income
   Abgeltungsteuer. Reads `:tax-unit :church-tax-rate` (BigDecimal
   0M / 0.08M / 0.09M) and `:component-gross` (the bare gross Abgst
   the provider injects into ctx) directly. Per § 32d Abs. 1 EStG
   both Soli and KiSt apply to the Abgeltungsteuer base, NOT
   compounded — we read the gross directly rather than back out from
   `:running` (which would suffer from BigDecimal-division re-rounding
   per note 147 §3.3)."
  [ctx]
  (let [k     (or (get-in ctx [:tax-unit :church-tax-rate]) 0M)
        gross (or (:component-gross ctx) 0M)]
    (* gross k)))

(defn register!
  "Register DE investment-income compute-fns with `kontor.statute`.
   Called at namespace load; idempotent."
  []
  (statute/register-compute-fn! :de-soli-on-§20-income      de-soli-on-§20-income)
  (statute/register-compute-fn! :de-kist-on-abgeltungsteuer de-kist-on-abgeltungsteuer))

(register!)

;; ============================================================================
;; In-namespace Soli provision — sibling of cgt-statute's DE-SolZG-§4-on-§20,
;; scoped on :de-§20-income so it coexists cleanly with the gains-side provision.
;; ============================================================================

(def soli-on-§20-income-provision
  "Soli (5.5 %) on §20-income Abgeltungsteuer. Installed alongside the
   investment-income statute (not in `inv-statute/provisions` because
   it lives next to its compute-fn registration in this provider ns —
   the standalone-runnable convention)."
  {:provision/code            "DE-SolZG-§4-on-§20-income"
   :provision/jurisdiction    :de
   :provision/concept         [:tax-concept/code :surtax]
   :provision/title           "§4 SolZG — Solidaritätszuschlag (5.5 %) on §20 Abgeltungsteuer (income side)"
   :provision/citation        "https://www.gesetze-im-internet.de/solzg_1995/__4.html"
   :provision/effective-from  #inst "2009-01-01"
   :provision/priority        100
   :provision/condition       (pr-str [:eq :component :de-§20-income])
   :provision/consequence     (pr-str {:op :surtax
                                       :code :soli-on-§20-income
                                       :label "Solidaritätszuschlag (5.5 %) auf §20 Abgeltungsteuer"
                                       :amount-from :compute-fn
                                       :fn :de-soli-on-§20-income})})

;; ============================================================================
;; Base-selector — chart-of-accounts σ_E over §20 income postings
;; ============================================================================

(defn- sum-prefix
  "Sum `:value :amount` across keys whose stringified form starts
   with `prefix`. Returns BigDecimal."
  ^java.math.BigDecimal [marginalized-map prefix]
  (reduce + 0M
          (for [[k v] marginalized-map
                :when (and (some? k)
                           (.startsWith (str k) prefix))]
            (or (some-> v :value :amount) 0M))))

(defn- investment-income-base-selectors
  "Marginalize the GL income postings and split into the §20 sub-
   categories by chart-code convention (note 147 §3.2). Returns a
   map of `{<lane-key> <bigdec>}`.

   Requires `:conn` in ctx (`report-postings` needs a connection for
   a bitemporal snapshot). Consumers without `:conn` must pre-supply
   `:inputs :investment-income-bases`."
  [{:keys [conn entity period]} commodity]
  (let [postings (report/report-postings
                  conn (cond-> {:from (:from period) :to (:to period)}
                         entity (assoc :entity entity)))
        by-code  (report/marginalize postings :account-code
                                     {:sign :inflow :commodity commodity})]
    {:dividends           (sum-prefix by-code "Income:Dividends")
     :interest            (sum-prefix by-code "Income:Interest")
     :fund-distributions  (sum-prefix by-code "Income:Fund-Distributions")
     :royalties           (sum-prefix by-code "Income:Royalties")
     :elected-dividends   0M}))   ; Teileinkünfte-elected; consumer pre-supplies

(defn- gross-§20-income
  "Sum the four §20-other sub-buckets (dividends + interest + fund-
   distributions + royalties) MINUS the Teileinkünfte-elected slice
   (that slice goes to a separate fold). Returns BigDecimal."
  ^java.math.BigDecimal [bases]
  (+ (- (or (:dividends bases) 0M) (or (:elected-dividends bases) 0M))
     (or (:interest bases) 0M)
     (or (:fund-distributions bases) 0M)
     (or (:royalties bases) 0M)))

;; ============================================================================
;; Sparer-Pauschbetrag lookup
;; ============================================================================

(defn- sparer-pauschbetrag
  "Look up the Sparer-Pauschbetrag for `filing-status` (`:single` or
   `:joint`) effective at `as-of`. Returns BigDecimal."
  ^java.math.BigDecimal [db filing-status ^java.util.Date as-of]
  (let [code (case filing-status
               :joint  "DE.EStG.§20.sparer-pauschbetrag.joint"
               #_else  "DE.EStG.§20.sparer-pauschbetrag.single")]
    (or (statute/parameter-value-at db code as-of) 0M)))

;; ============================================================================
;; Components
;; ============================================================================

(defn- §20-income-component
  "Standalone §20-income Abgeltungsteuer component — 25 % flat (or
   `1/(4+k)` when church-affiliated) on `taxable-base` + Soli + KiSt
   surtaxes. EMITTED UNLESS Günstigerprüfung elected."
  [{:keys [commodity authority]} ctx
   ^java.math.BigDecimal gross-income
   ^java.math.BigDecimal sparer
   ^java.math.BigDecimal carry-in
   ^java.math.BigDecimal taxable-base]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        k         (or (get-in ctx [:tax-unit :church-tax-rate]) 0M)
        ;; § 32d Abs. 1 formula: when k > 0 the effective rate becomes
        ;; 1 / (4 + k); when k = 0 the formula collapses to 0.25. We
        ;; SKIP reading DE.EStG.§20.flat-rate when k > 0 because the
        ;; effective rate is determined by the formula, not the
        ;; parameter (note 147 §3.3).
        flat-rate (if (pos? k)
                    (with-precision 34 (/ 1M (+ 4M k)))
                    (or (statute/parameter-value-at db "DE.EStG.§20.flat-rate" as-of)
                        0.25M))
        schedule  (ts/flat flat-rate)
        gross-tax (ts/apply-schedule schedule taxable-base)
        scoped    (assoc ctx :component       :de-§20-income
                             :component-gross gross-tax
                             :db              db
                             :as-of           as-of)
        {tax-items :tax-items}
        (statute/apply-provisions
         db {:concept :surtax :jurisdiction :de :as-of as-of} scoped)
        {liability :liability resolved :resolved}
        (ts/apply-adjustments gross-tax tax-items scoped)
        prepaid   (or (get-in ctx [:inputs :de-kapest-prepaid]) 0M)
        ;; Loss-bucket-contribution: if gross-income went NEGATIVE
        ;; (e.g. heavy Stückzinsen mid-year), the magnitude carries
        ;; forward into the §20-other bucket. v1 reports this as a
        ;; bucket-contribution attribute for the consumer to thread.
        loss-contrib (if (neg? gross-income) (- gross-income) 0M)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money taxable-base commodity)
     :schedule        schedule
     :gross-liability (money/money gross-tax commodity)
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance]) resolved)
     :liability       (money/money liability commodity)
     :prepaid         (money/money prepaid commodity)
     :regime          :abgeltungsteuer
     :line-items
     (into [{:line :gross-§20-income
             :label "§20 Abs. 1 EStG — Bruttoeinkünfte aus Kapitalvermögen"
             :value (money/money gross-income commodity)}
            {:line :sparer-pauschbetrag
             :label "§20 Abs. 9 EStG — Sparer-Pauschbetrag"
             :value (money/money (- sparer) commodity)}
            {:line :de-§20-carry-in
             :label "§20 Abs. 6 EStG — carry-in from §20-other Verlusttopf"
             :value (money/money (- carry-in) commodity)}
            {:line :de-§20-base
             :label "Steuerpflichtige §20-Einkünfte (taxable §20 base)"
             :value (money/money taxable-base commodity)}
            {:line :de-§20-tax
             :label (str "§32d Abs. 1 EStG — Abgeltungsteuer "
                         (if (pos? k)
                           (str "(Formel 1/(4+" k "))")
                           "(25 % flat)"))
             :value (money/money gross-tax commodity)}]
           (mapv (fn [r]
                   {:line  (:code r)
                    :label (:label r)
                    :value (money/money (:amount r) commodity)})
                 resolved))
     :jurisdiction-specific-codes
     {:lane :de-§20-income
      :loss-bucket-contribution {:de-§20-other loss-contrib}}}))

(defn- §20-pit-fold-component
  "Günstigerprüfung-on component — `:tax-unit
   :abgeltungsteuer-elect-marginal?` true → §20 income (after Sparer-
   Pauschbetrag — BFH VIII R 14/13: survives the election) folds into
   PIT base at marginal rate. No own liability."
  [{:keys [commodity authority]}
   ^java.math.BigDecimal gross-income
   ^java.math.BigDecimal sparer
   ^java.math.BigDecimal taxable-base]
  {:kind            :investment-income-tax
   :authority       authority
   :base            (money/money taxable-base commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :regime          :günstigerprüfung
   :line-items      [{:line :gross-§20-income
                      :label "§20 Abs. 1 EStG — Bruttoeinkünfte aus Kapitalvermögen"
                      :value (money/money gross-income commodity)}
                     {:line :sparer-pauschbetrag
                      :label "§20 Abs. 9 EStG — Sparer-Pauschbetrag (survives §32d Abs. 6 — BFH VIII R 14/13)"
                      :value (money/money (- sparer) commodity)}
                     {:line :§20-günstig
                      :label "§32d Abs. 6 EStG — Günstigerprüfung: §20-Einkünfte in tariflichen ESt-Tarif (60 % oder 100 %)"
                      :value (money/money taxable-base commodity)}]
   :jurisdiction-specific-codes {:pit-base-additions [taxable-base]
                                 :lane :de-§20-günstig}})

(defn- §20-teileinkünfte-fold-component
  "§32d Abs. 2 Nr. 3 + §3 Nr. 40 lit. d EStG — Teileinkünfte-elected
   dividend slice: 60 % of the elected-dividend amount folds into PIT
   base at marginal rate. The standalone §20 Abgeltungsteuer
   component is UNAFFECTED — only the elected slice is removed from
   the Abgeltungsteuer base."
  [{:keys [commodity authority]} ctx
   ^java.math.BigDecimal elected-dividend-amount]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        ;; Reuse the §17 inclusion-rate (cgt-statute) — both §3 Nr. 40
        ;; lit. c (§17 gains) and §3 Nr. 40 lit. d (§32d Abs. 2 Nr. 3
        ;; dividends) track to the same 60 % statutory anchor (note 147
        ;; §1.5).
        incl-rate (or (statute/parameter-value-at
                       db "DE.EStG.§17.inclusion-rate" as-of)
                      0.60M)
        teileinkünfte (* elected-dividend-amount incl-rate)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money teileinkünfte commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      [{:line :elected-dividends
                        :label "§32d Abs. 2 Nr. 3 EStG — Teileinkünfte-gewählte Dividenden (Brutto)"
                        :value (money/money elected-dividend-amount commodity)}
                       {:line :§17-inclusion-rate
                        :label "§3 Nr. 40 lit. d EStG — Teileinkünfteverfahren (60 % Inklusion)"
                        :value (money/money teileinkünfte commodity)}]
     :jurisdiction-specific-codes {:pit-base-additions [teileinkünfte]
                                   :lane :de-§20-teileinkünfte}}))

;; ============================================================================
;; The provider
;; ============================================================================

(defrecord DEInvestmentIncomeTaxProvider
           [id authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [_         (or (:db ctx)
                        (throw (ex-info ":db required in ctx for DE investment-income provider"
                                        {:ctx-keys (keys ctx)})))
          as-of     (as-of-from-ctx ctx)
          db        (:db ctx)
          ;; If consumer pre-marginalized into :inputs :investment-income-bases
          ;; (the 1099-style upload path or test path), use it directly;
          ;; otherwise scan the GL by chart-prefix.
          bases     (or (:investment-income-bases inputs)
                        (investment-income-base-selectors ctx commodity))
          tu        (or (:tax-unit ctx) (:tax-unit inputs) {})
          filing    (or (:filing-status tu) :single)
          ;; Gross §20-income — note: elected-dividends are SUBTRACTED
          ;; from the Abgeltungsteuer base (they go to the Teileinkünfte
          ;; fold instead).
          gross-inc (gross-§20-income bases)
          sparer    (sparer-pauschbetrag db filing as-of)
          carry-in  (or (get-in inputs [:capital-loss-carryforward :de-§20-other]) 0M)
          taxable   (max 0M (- gross-inc sparer carry-in))
          elected   (or (:elected-dividends bases) 0M)
          günstig?  (boolean (:abgeltungsteuer-elect-marginal? tu))
          opts      {:authority authority :commodity commodity}
          ;; Inject tax-unit into ctx for the surtax compute-fns.
          ctx'      (assoc ctx :tax-unit tu :db db)
          standalone-cmp
          (cond
            günstig?
            (§20-pit-fold-component opts gross-inc sparer (max 0M (- gross-inc sparer)))

            ;; Standard: emit the standalone Abgeltungsteuer component
            ;; only when there is something to tax (taxable > 0 OR
            ;; gross < 0 with a loss to report).
            (or (pos? taxable) (neg? gross-inc))
            (§20-income-component opts ctx' gross-inc sparer carry-in taxable)

            :else nil)
          teilein-cmp (when (and (not günstig?) (pos? elected))
                        (§20-teileinkünfte-fold-component opts ctx' elected))
          components  (->> [standalone-cmp teilein-cmp] (remove nil?) vec)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :de :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructor
;; ============================================================================

(defn de-investment-income-provider
  "Build a DE investment-income (§20 EStG Abgeltungsteuer) provider.

   Optional opts:
     :id        — provider id (default :de-investment-income)
     :commodity — functional commodity (default :EUR)"
  [{:keys [id commodity]
    :or   {id :de-investment-income commodity :EUR}}]
  (->DEInvestmentIncomeTaxProvider
   id :de-finanzamt commodity
   "§ 20 + § 32d EStG + § 4 SolZG + KiStG"))

(defn install-statute!
  "Install the DE investment-income statute (parameters + provisions)
   into `conn`, plus the in-provider Soli-on-§20-income provision.
   Requires the DE CIT statute (for `DE.Soli.rate`) AND the DE CGT
   statute (for `DE.EStG.§20.flat-rate` + `DE.EStG.§17.inclusion-rate`)
   to be installed first."
  [conn]
  (inv-statute/install! conn)
  (d/transact conn [soli-on-§20-income-provision]))
