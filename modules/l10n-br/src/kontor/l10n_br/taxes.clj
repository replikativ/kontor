(ns kontor.l10n-br.taxes
  "Brazilian tax stack — the architectural stress test for kontor.

   Five+ federal/state/municipal taxes simultaneously apply on a single
   invoice line under the legacy regime; the new IBS/CBS dual-VAT
   replaces them progressively 2026-33. This namespace ships:

     - Rate constants for the legacy stack (ICMS state matrix +
       IPI + PIS + COFINS + ISS + IRPJ + CSLL).
     - Rate constants for the new IBS/CBS dual-VAT.
     - Interstate routing helpers (Odoo's `account_fiscal_position`
       pattern, ported as DATA per the Odoo-survey 'don't copy this'
       guidance: state lists are constants, not embedded frozensets
       inside business logic).
     - Compound-tax base computation for the per-line breakdown
       (per ADR-016 :tax-application chain).

   This namespace does NOT compute a tax return. SPED block emitters
   live in `sped.clj`; NF-e XML emitter lives in `nfe.clj`."
  (:require [kontor.l10n-br.chart]                ; for tag identity refs
            [kontor.money :as money]))

;; ============================================================================
;; State sets — used for interstate routing
;;
;; The Brazilian federal/state ICMS regime distinguishes intra-state
;; transactions (rates per state) from inter-state ones (national rates
;; that vary by source region → destination region).
;;
;; Per the agent guidance (don't embed state sets in code; ship as
;; data), these are module-level vars consumers can introspect /
;; override. The two macro-regions are:
;; ============================================================================

(def south-southeast-states
  "BR South + Southeast region states (4% interstate rate to North/NE/MW;
   12% to other South/SE states)."
  #{"PR" "RS" "SC" "ES" "MG" "RJ" "SP"})

(def north-northeast-midwest-states
  "The 19 N/NE/CO states + DF (20 federal units total).
   Receives 7% from S/SE for domestic-origin goods (Res. SF 22/1989);
   sends 12% to S/SE; 12% within own region.

   NOTE — ES exception: Espírito Santo is geographically Southeast but
   CONFAZ tradition treats ES as a 7%-receiving destination from
   other S/SE origins (i.e. ES gets the same 'gets 7%' treatment as
   N/NE/MW for federal-redistribution purposes). The kernel-side
   `south-southeast-states` retains ES because intra-region interstate
   rates from ES *as origin* are still 12%; consumers handling the
   ES-as-destination 7% special case can route via a per-NCM override."
  #{"AC" "AL" "AM" "AP" "BA" "CE" "DF" "GO" "MA" "MT"
    "MS" "PA" "PB" "PE" "PI" "RN" "RO" "RR" "SE" "TO"})

(def all-states
  (clojure.set/union south-southeast-states north-northeast-midwest-states))

;; ============================================================================
;; ICMS — state matrix (intra-state rates; commonly seen values)
;; ============================================================================

(def icms-intrastate-rates
  "Intra-state ICMS modal rate per state as of 2025-05 (includes
   FECP/FECOEP surcharge where the surcharge is permanently part of
   the headline rate). Verified against the state-by-state 2025
   round-up (focusnfe / simtax compilations; cross-referenced with
   the originating state legislation).

   Notes:
     - RJ 22% = 20% base + 2% FECP (mandatory state-add). Lei 10.253/2023.
     - MA 23% effective 23/02/2025 (Lei 12.426/2024 — verified 2026-05-11).
     - PI 22.5% effective 01/04/2025 (Lei 8.558/2024 — verified 2026-05-11).
     - RN 20% effective 20/03/2025, plus +2% FECOP on luxury goods.
     - BA 20.5% (Lei 14.629/2023).
     - AL 20% = 19% base + 1% FECOEP.
     - SE 20% = 19% base + 1% FECOEP.

   Real rates vary by NCM product code + state-specific reduced-rate
   matrices; this map ships the modal rate. Consumers must override
   per NCM where their product class has a reduced or raised rate.

   For 2026+ deployment refresh this annually (every January) to
   pick up state-level rate changes — per the BR verification
   2026-05-11 the table belongs in data, not source."
  {"AC" 0.19M "AL" 0.20M "AM" 0.20M "AP" 0.18M "BA" 0.205M
   "CE" 0.20M "DF" 0.20M "ES" 0.17M "GO" 0.19M "MA" 0.23M
   "MG" 0.18M "MS" 0.17M "MT" 0.17M "PA" 0.19M "PB" 0.20M
   "PE" 0.205M "PI" 0.225M "PR" 0.195M "RJ" 0.22M "RN" 0.20M
   "RO" 0.195M "RR" 0.20M "RS" 0.17M "SC" 0.17M "SE" 0.20M
   "SP" 0.18M "TO" 0.20M})

(defn icms-interstate-rate
  "Return the interstate ICMS rate.

   Per CONFAZ Resoluções:
     - Res. SF 22/1989: 7% S/SE → N/NE/MW (domestic origin)
                       12% N/NE/MW → S/SE; 12% intra-region
     - Res. SF 13/2012: **MANDATORY 4%** for ANY interstate operation
       with imported goods (CST origem 1, 2, 3, 6, 7, or 8) or with
       imported content > 40%. This is not optional — the seller MUST
       apply 4% when the import-content threshold is met, regardless
       of the macro-region pairing.

   Args:
     from-state, to-state — 2-letter Brazilian state codes
     opts (optional):
       :import-content?   true if goods are imported / >40% import content
       :ncm-import-content-pct — if set, computes import? for >40%

   Returns the applicable rate as a BigDecimal."
  ([from-state to-state] (icms-interstate-rate from-state to-state {}))
  ([from-state to-state {:keys [import-content? ncm-import-content-pct]}]
   (let [imported? (or import-content?
                       (and ncm-import-content-pct
                            (> ncm-import-content-pct 40)))]
     (cond
       imported?                                   0.04M
       (= from-state to-state)                     (get icms-intrastate-rates
                                                        from-state 0.18M)
       (and (south-southeast-states from-state)
            (north-northeast-midwest-states to-state))
       0.07M
       :else                                       0.12M))))

;; ============================================================================
;; IPI — federal excise (NCM-tied; sample rates)
;; ============================================================================

(def ipi-sample-rates
  "IPI rates depend on the NCM (8-digit product code). These are
   sample rate buckets. Real implementations use the full TIPI table
   (~10,000 NCM entries) from Receita Federal."
  {:zero        0.00M
   :reduced-5   0.05M
   :standard-10 0.10M
   :raised-15   0.15M
   :raised-20   0.20M
   :tobacco-330 3.30M})    ;; punitive rate on tobacco

;; ============================================================================
;; PIS / COFINS — federal social contributions
;; ============================================================================

(def pis-non-cumulative-rate 0.0165M)   ; 1.65%
(def pis-cumulative-rate     0.0065M)   ; 0.65%

(def cofins-non-cumulative-rate 0.076M) ; 7.6%
(def cofins-cumulative-rate     0.03M)  ; 3.0%

;; ============================================================================
;; ISS — municipal service tax (2-5%, per municipality)
;; ============================================================================

(def iss-min-rate 0.02M)
(def iss-max-rate 0.05M)

;; ============================================================================
;; IRPJ + CSLL — corporate income + social contribution
;; ============================================================================

(def irpj-base-rate    0.15M)             ; 15% on first BRL 240k/year
(def irpj-surtax-rate  0.10M)             ; +10% above
(def irpj-surtax-threshold 240000M)       ; annual BRL
(def csll-rate         0.09M)             ; 9% (15% financial)

;; ============================================================================
;; CBS + IBS — the post-2026 dual-VAT (Tax Reform LC 214/2025)
;;
;; CBS (Contribuição sobre Bens e Serviços, federal): ~8.8%
;; IBS (Imposto sobre Bens e Serviços, state+municipal): ~17.7%
;; IS  (Imposto Seletivo): on goods/services harmful to health/environment
;;
;; Statutory cap (LC 214/2025): CBS + IBS ≤ 26.5% combined. If exceeded
;; in 2031 the Executive must propose corrective measures.
;;
;; Transition schedule (per LC 214/2025 + EC 132/2023 art. 124-ADCT):
;;   2026  : 0.9% CBS + 0.1% IBS COMPUTED + BILLED on invoices and
;;           **compensable against PIS/COFINS debits in the same
;;           period**. Net cash effect is zero only for taxpayers
;;           who have enough PIS/COFINS debit to absorb the credit;
;;           credit-only filers see a refund or credit carryforward.
;;           NOT 'not collected' (corrected 2026-05-11 per BR
;;           verification — invoices must carry actual computed
;;           values, not zero placeholders).
;;   2027  : Full CBS collection begins; PIS + COFINS extinguished.
;;           IPI rate set to zero (except Zona Franca de Manaus).
;;           Imposto Seletivo (IS) begins.
;;   2029  : ICMS + ISS effective rates reduced by 10% cumulatively;
;;           IBS increased correspondingly.
;;   2030  : ICMS/ISS −20%.
;;   2031  : ICMS/ISS −30%; rate-cap-review trigger if CBS+IBS > 26.5%.
;;   2032  : ICMS/ISS −40%.
;;   2033  : Final state — IBS fully replaces ICMS + ISS; IPI residue
;;           also extinguished outside ZFM.
;; ============================================================================

(def cbs-rate-2026-pilot 0.009M)
(def ibs-rate-2026-pilot 0.001M)
(def cbs-rate-final      0.088M)
(def ibs-rate-final      0.177M)

;; ============================================================================
;; Compound-tax base computation
;; ============================================================================

(defn compute-icms-by-inside-base
  "Brazil's 'cálculo por dentro' — ICMS is computed on a base that
   *includes* IPI in some scenarios. Given a net amount and an IPI
   amount, return the ICMS base.

   This is the canonical example of compound taxation that ADR-016's
   :tax-application/compound-on is designed to capture."
  [net-amount ipi-amount]
  (money/add net-amount ipi-amount))

;; ============================================================================
;; DIFAL (Diferencial de Alíquota) — EC 87/2015 + LC 190/2022
;;
;; For B2C interstate sales (sales to a non-contributor consumer in
;; a different state), the seller owes the difference between the
;; destination state's intra-state rate and the interstate rate to
;; the destination state. Optional FCP (Fundo de Combate à Pobreza)
;; rides on top per NCM.
;;
;; Since 2019 (EC 87/2015 transition completed; LC 190/2022 codified),
;; 100% of the DIFAL is due to the destination state. The previous
;; split (60/40, 80/20, etc.) is no longer in force.
;; ============================================================================

(defn difal-applies?
  "True iff DIFAL is owed on this interstate operation.

   Per LC 190/2022 + EC 87/2015 (verified 2026-05-11), DIFAL applies in
   THREE distinct scenarios:

     1. **B2C non-contributor** (final consumer in destination state):
        seller (origin) collects and remits to destination.

     2. **B2B contributor buying for USE / CONSUMPTION**: buyer in
        destination state, goods not for resale. Buyer is responsible
        for collecting and remitting DIFAL to the destination state.

     3. **B2B contributor buying as FIXED ASSET**: same as #2 — buyer
        responsible.

   DIFAL does NOT apply when the buyer is a contributor purchasing for
   RESALE or INDUSTRIALIZATION (the buyer charges full state ICMS on
   the subsequent sale, so there's no differential to remit).

   `opts` map keys:
     :buyer-type     #{:non-contributor :contributor}  (default :non-contributor)
     :purpose        #{:consumption :fixed-asset :resale :industrialization}
                     (default :consumption when buyer is contributor)"
  [{:keys [buyer-type purpose]
    :or {buyer-type :non-contributor purpose :consumption}}]
  (cond
    (= buyer-type :non-contributor) true
    (and (= buyer-type :contributor)
         (#{:consumption :fixed-asset} purpose)) true
    :else false))

(defn difal-due
  "Compute DIFAL (Diferencial de Alíquota) for an interstate sale.

   Args:
     base       Money :BRL  — invoice base
     from-state — origin (seller's) state code, e.g. \"SP\"
     to-state   — destination (buyer's) state code, e.g. \"BA\"
     opts:
       :buyer-type    :non-contributor (default) | :contributor
       :purpose       :consumption | :fixed-asset | :resale | :industrialization
                       (relevant when :buyer-type is :contributor)
       :imported?     true if goods are imported (interstate origin = 4%)
       :fcp-rate      optional BigDecimal — FCP rate on top of DIFAL
                       (varies 1-4% per NCM × state)
       :b2c?          DEPRECATED — legacy alias for :buyer-type :non-contributor.
                       Kept for backward compatibility.

   Returns nil when DIFAL does not apply (resale / industrialization).
   Otherwise:
     {:difal-base   Money
      :difal        Money  ; base × (rate_dest − rate_origin)
      :fcp-base     Money  ; same as difal-base when fcp-rate given
      :fcp          Money  ; base × fcp-rate, or zero if not given
      :total        Money}"
  [base from-state to-state {:keys [imported? fcp-rate b2c?] :as opts}]
  (let [;; Honor legacy :b2c? if provided
        opts* (cond-> opts
                (contains? opts :b2c?)
                (assoc :buyer-type (if b2c? :non-contributor :contributor)
                       :purpose (if b2c? :consumption :resale)))]
    (when (difal-applies? opts*)
      (let [rate-dest (get icms-intrastate-rates to-state 0.18M)
            rate-orig (icms-interstate-rate from-state to-state
                                            {:import-content? imported?})
            base-bd ^java.math.BigDecimal (:amount base)
            difal-amt (-> base-bd
                          (.multiply (.subtract rate-dest rate-orig))
                          (.setScale 2 java.math.RoundingMode/HALF_EVEN))
            fcp-amt (when fcp-rate
                      (-> base-bd
                          (.multiply fcp-rate)
                          (.setScale 2 java.math.RoundingMode/HALF_EVEN)))
            zero (money/zero :BRL)]
        {:difal-base base
         :difal      (money/money difal-amt :BRL)
         :fcp-base   (if fcp-rate base zero)
         :fcp        (if fcp-amt (money/money fcp-amt :BRL) zero)
         :total      (money/add (money/money difal-amt :BRL)
                                (if fcp-amt (money/money fcp-amt :BRL) zero))}))))

;; ============================================================================
;; ICMS-ST (Substituição Tributária)
;;
;; Pre-collection model: one party in the supply chain (typically the
;; manufacturer or distributor) collects, in advance, the ICMS that
;; downstream resellers would otherwise owe on the presumed final
;; sale price. The "presumed" margin is the MVA (Margem de Valor
;; Agregado), an NCM × state-pair specific percentage.
;;
;; Base_ST = (base + IPI + freight + insurance + outras) × (1 + MVA)
;; ICMS_ST_due = Base_ST × rate_internal_dest − ICMS_normal
;;
;; Where ICMS_normal is the ICMS already due on the seller's outbound
;; operation (the "regular" ICMS the seller would owe absent ST).
;; ============================================================================

(defprotocol MvaProvider
  "Lookup interface for ICMS-ST Margem de Valor Agregado.

   Implementations:
     - StaticMvaProvider: in-memory table for testing + small SMB
       hand-curated cases.
     - Customer-supplied production providers fed by Sovos, Avalara,
       or other LATAM tax engines (per ADR-005 we ship the protocol,
       not the data)."
  (mva-for [this ncm to-state]
    "Return the MVA percentage (as BigDecimal, e.g. 0.40M for 40%)
     applicable to the given NCM product code in the given destination
     state. nil if no MVA published (no ST applies)."))

(defrecord StaticMvaProvider [table]
  MvaProvider
  (mva-for [_ ncm to-state]
    (get-in table [(str ncm) (str to-state)])))

(defn static-mva-provider
  "Construct a StaticMvaProvider from a nested map:
     {ncm-code {state-code mva-bigdec}}"
  [table]
  (->StaticMvaProvider table))

(defn icms-st
  "Compute ICMS-ST for a single line.

   Args:
     base          Money :BRL — invoice base (line value)
     ipi-amount    Money :BRL — IPI on this line (added to ST base)
     additional    Money :BRL — freight + insurance + outras (defaults zero)
     mva-pct       BigDecimal — Margem de Valor Agregado (e.g. 0.40M)
     rate-dest     BigDecimal — destination state's intra-state rate
     icms-normal   Money :BRL — ICMS already due on the outbound

   Returns:
     {:icms-st-base Money   ; the grossed-up base
      :icms-st-due  Money   ; the additional ST amount this seller owes}"
  [{:keys [base ipi-amount additional mva-pct rate-dest icms-normal]
    :or {additional (money/zero :BRL)}}]
  (let [combined (money/add (money/add base ipi-amount) additional)
        gross-mult (.add 1M ^java.math.BigDecimal mva-pct)
        base-st-amt (-> ^java.math.BigDecimal (:amount combined)
                        (.multiply gross-mult)
                        (.setScale 2 java.math.RoundingMode/HALF_EVEN))
        base-st (money/money base-st-amt :BRL)
        st-tax-amt (-> base-st-amt
                       (.multiply rate-dest)
                       (.setScale 2 java.math.RoundingMode/HALF_EVEN))
        st-tax (money/money st-tax-amt :BRL)
        st-due (money/sub st-tax icms-normal)]
    {:icms-st-base base-st
     :icms-st-due  st-due}))

;; ============================================================================
;; FCP (Fundo de Combate à Pobreza)
;;
;; Per-state 1-4% surcharge on certain NCMs. Two contexts:
;;   1. As an add-on to intra-state ICMS (vFCP in the ICMS group)
;;   2. As an add-on to DIFAL (vFCPUFDest in ICMSUFDest)
;;   3. As an add-on to ICMS-ST (vFCPST in ICMSST)
;; ============================================================================

(defn fcp-amount
  "Compute the FCP surcharge.
     base      Money :BRL — the FCP-base (typically same as ICMS base)
     fcp-rate  BigDecimal — 0.01M to 0.04M per state × NCM"
  [base fcp-rate]
  (let [amt (-> ^java.math.BigDecimal (:amount base)
                (.multiply fcp-rate)
                (.setScale 2 java.math.RoundingMode/HALF_EVEN))]
    (money/money amt :BRL)))

(defn compute-pis-cofins-base
  "PIS and COFINS base computation.

   Per STF RE 574.706 / Tema 69 (the 'Tese do Século', 15-03-2017
   onwards): ICMS destacado is **excluded** from the PIS/COFINS
   base. This was reaffirmed by RFB Instrução Normativa 1911/2019
   art. 27.

   Base = net + IPI - ICMS-destacado

   Args:
     net-amount  : Money :BRL  — the invoice net (line value)
     ipi-amount  : Money :BRL  — the IPI on this line (added to base)
     icms-amount : Money :BRL  — the ICMS destacado on this line
                                   (subtracted; STF Tema 69)

   For pre-15/03/2017 transactions historians can pass
   `(money/zero :BRL)` for the icms-amount to preserve the legacy
   base."
  ([net-amount ipi-amount]
   ;; 2-arg form is retained for callers that have no ICMS context
   ;; (rare; typically only services without ICMS). Logs a warning
   ;; on stderr to encourage migration to the 3-arg form.
   (binding [*out* *err*]
     (println "WARNING: compute-pis-cofins-base/2 omits ICMS exclusion."
              "Pass icms-amount explicitly per STF Tema 69."))
   (money/add net-amount ipi-amount))
  ([net-amount ipi-amount icms-amount]
   (-> net-amount
       (money/add ipi-amount)
       (money/sub icms-amount))))
