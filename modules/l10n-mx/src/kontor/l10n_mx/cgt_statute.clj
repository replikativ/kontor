(ns kontor.l10n-mx.cgt-statute
  "MX capital-gains tax — ISR Título IV Cap IV (personas físicas) +
   Título II arts 9 / 18-22 (personas morales) + Título V arts 160-161
   (no residentes) — encoded as `kontor.statute` data per ADR-101.
   Research note 132.

   Mexico has no separate CGT regime: capital gains fold into the
   regular ISR stack. The MX-specific machinery this statute encodes:

   - **Art. 93-XIX-a casa habitación** — 700,000 UDIS proceeds-side
     cap on the principal-residence exemption. Once-per-three-years.
     The UDI is consumer-supplied (daily ~11 000-entry series, too
     large for a parameter snapshot — see note 132 §6).
   - **Art. 120 averaging** — gain / years-held (capped at 20) is the
     `acumulable` portion taxed at the PIT marginal rate; the residual
     `no-acumulable` portion is taxed at the resulting effective rate.
     The two caps (20 for gains, 10 for losses) are parameters.
   - **Art. 122 loss carry** — 3-year general carry; 10-year bolsa
     lane carry.
   - **Art. 129 BMV/BIVA** — 10 % definitive flat on listed-share gains.
     SAT criterio 37/ISR/N extends to SIC foreign shares.
   - **Art. 9 PM CIT rate** — 30 % flat on personas-morales gains.
   - **Art. 22 costo promedio** — share-basis machinery with CUFIN
     adjustment (additive) + CUCA reductions (subtractive) — the unique
     MX mechanism preventing double-taxation of already-distributed
     post-tax earnings. The CUFIN/CUCA inputs are issuer-supplied per
     disposal (note 132 §4 Gap B); the provider folds them.
   - **Art. 160 / 161 non-resident** — 25 % on gross consideration OR
     35 % on net gain with Mexican-resident representative + dictamen.
     `:elective-regime :mx-art-161-dictamen-on-net` flips the lever.
   - **Art. 126 / 127 notary withholding** — federal provisional + 5 %
     state remittance on real-estate disposals. Both consumer-supplied
     via `:inputs :mx-isr-retencion-federal` / `:mx-isr-retencion-estatal`
     and credited as prepayments.

   Citations point at sat.gob.mx (the authority) and Justia México
   (a stable, free-access mirror of the consolidated LISR text).
   The full PDF lives at diputados.gob.mx; the SAT articles at
   www.sat.gob.mx/articulo/<id>/ are the canonical regulator pages.

   ## Out of scope (v1)

   - **INPC adjustment** — `:disposal/basis-amount` is consumer-supplied
     already-indexed per note 132 §4 Gap A. The monthly INPC series
     would be a 30-year parameter timeline that does not belong in a
     v1 snapshot.
   - **Art. 124 depreciation** on residential construction — folded
     into the consumer-supplied indexed basis (the consumer subtracts
     depreciation upstream).
   - **Art. 120 5-year average rate (Option B)** — the elect lever is
     surfaced as `:mx-art-120-option-b-5yr-average-rate`; v1 implements
     Option A (current-year rate via PIT coupling) and surfaces the
     elected alternative in `:line-items` for audit. Full
     cross-provider coupling is a TODO (note 132 §5 — \"cleanest
     cross-provider seam example\")."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "MX CGT parameter definitions. Values live in `parameter-values`,
   keyed by `:effective-from`."
  [;; --- Casa habitación 700k UDIS cap ---------------------------------------
   {:parameter/code         "MX.CGT.casa-habitacion-cap-udis"
    :parameter/label        "Casa habitación exemption — proceeds cap (UDIS)"
    :parameter/jurisdiction :mx
    :parameter/unit         :amount-udis
    :parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-i/"}

   {:parameter/code         "MX.CGT.casa-habitacion-cooling-off-years"
    :parameter/label        "Casa habitación exemption — once-every-N-years"
    :parameter/jurisdiction :mx
    :parameter/unit         :years
    :parameter/concept-iri  "https://www.sat.gob.mx/articulo/31901/articulo-93"}

   ;; --- Art. 120 averaging caps ---------------------------------------------
   {:parameter/code         "MX.CGT.art-120.gain-years-cap"
    :parameter/label        "Art. 120 averaging — divisor cap for gains (years)"
    :parameter/jurisdiction :mx
    :parameter/unit         :years
    :parameter/concept-iri  "https://www.sat.gob.mx/articulo/31901/articulo-120"}

   {:parameter/code         "MX.CGT.art-122.loss-years-cap"
    :parameter/label        "Art. 122 — loss-divisor cap (years)"
    :parameter/jurisdiction :mx
    :parameter/unit         :years
    :parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-iv/seccion-i/"}

   {:parameter/code         "MX.CGT.art-122.loss-carry-years"
    :parameter/label        "Art. 122 — capital-loss carry-forward years (general)"
    :parameter/jurisdiction :mx
    :parameter/unit         :years
    :parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-iv/seccion-i/"}

   {:parameter/code         "MX.CGT.art-129.bolsa-loss-carry-years"
    :parameter/label        "Art. 129 — bolsa-lane loss carry-forward years"
    :parameter/jurisdiction :mx
    :parameter/unit         :years
    :parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-iv/seccion-ii/"}

   ;; --- Art. 129 BMV/BIVA broker withholding (10 %) -------------------------
   {:parameter/code         "MX.CGT.art-129.bolsa-rate"
    :parameter/label        "Art. 129 — bolsa lane definitive flat rate"
    :parameter/jurisdiction :mx
    :parameter/unit         :rate
    :parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-iv/seccion-ii/"}

   ;; --- Art. 9 personas-morales rate (30 %) ---------------------------------
   {:parameter/code         "MX.CGT.art-9.pm-rate"
    :parameter/label        "Art. 9 LISR — personas morales flat CIT rate"
    :parameter/jurisdiction :mx
    :parameter/unit         :rate
    :parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-ii/capitulo-i/"}

   ;; --- Title V non-resident rates ------------------------------------------
   {:parameter/code         "MX.CGT.art-160.nr-real-estate-gross-rate"
    :parameter/label        "Art. 160 — non-resident real estate, gross-proceeds rate"
    :parameter/jurisdiction :mx
    :parameter/unit         :rate
    :parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-v/"}

   {:parameter/code         "MX.CGT.art-160.nr-real-estate-net-rate"
    :parameter/label        "Art. 160 — non-resident real estate, net-gain rate (dictamen)"
    :parameter/jurisdiction :mx
    :parameter/unit         :rate
    :parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-v/"}

   {:parameter/code         "MX.CGT.art-161.nr-shares-gross-rate"
    :parameter/label        "Art. 161 — non-resident shares, gross-proceeds rate"
    :parameter/jurisdiction :mx
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.sat.gob.mx/articulo/88443/articulo-161"}

   {:parameter/code         "MX.CGT.art-161.nr-shares-net-rate"
    :parameter/label        "Art. 161 — non-resident shares, net-gain rate (dictamen)"
    :parameter/jurisdiction :mx
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.sat.gob.mx/articulo/88443/articulo-161"}

   ;; --- Art. 127 state notary 5 % --------------------------------------------
   {:parameter/code         "MX.CGT.art-127.state-notary-rate"
    :parameter/label        "Art. 127 — state notary withholding rate on real-estate gain"
    :parameter/jurisdiction :mx
    :parameter/unit         :rate
    :parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-iv/seccion-i/"}])

;; ============================================================================
;; Parameter values
;; ============================================================================

(def parameter-values
  "MX CGT parameter values. The 700k UDIS cap, 25 %/35 % NR rates,
   10 % bolsa rate and 30 % PM rate have been stable since the 2014
   ISR reform; the loss-divisor and carry caps come from arts 120 /
   122 / 129 which the 2014 reform left intact."
  [{:parameter-value/parameter      [:parameter/code "MX.CGT.casa-habitacion-cap-udis"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  700000M
    :parameter-value/citation       "LISR art. 93-XIX-a — 700 000 UDIS cap (2014 reform tightened from 1.5M UDIS)"}

   {:parameter-value/parameter      [:parameter/code "MX.CGT.casa-habitacion-cooling-off-years"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  3M
    :parameter-value/citation       "LISR art. 93-XIX-a — once every 3 calendar years"}

   {:parameter-value/parameter      [:parameter/code "MX.CGT.art-120.gain-years-cap"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  20M
    :parameter-value/citation       "LISR art. 120 — averaging divisor capped at 20 years"}

   {:parameter-value/parameter      [:parameter/code "MX.CGT.art-122.loss-years-cap"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  10M
    :parameter-value/citation       "LISR art. 122 — loss divisor capped at 10 years"}

   {:parameter-value/parameter      [:parameter/code "MX.CGT.art-122.loss-carry-years"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  3M
    :parameter-value/citation       "LISR art. 122 — capital-loss carry forward 3 calendar years"}

   {:parameter-value/parameter      [:parameter/code "MX.CGT.art-129.bolsa-loss-carry-years"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  10M
    :parameter-value/citation       "LISR art. 129 — bolsa-lane loss carry 10 years within lane"}

   {:parameter-value/parameter      [:parameter/code "MX.CGT.art-129.bolsa-rate"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "LISR art. 129 — 10 % definitive flat on BMV/BIVA listed-share net gains"}

   {:parameter-value/parameter      [:parameter/code "MX.CGT.art-9.pm-rate"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.30M
    :parameter-value/citation       "LISR art. 9 — personas morales flat 30 % CIT"}

   {:parameter-value/parameter      [:parameter/code "MX.CGT.art-160.nr-real-estate-gross-rate"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.25M
    :parameter-value/citation       "LISR art. 160 — non-resident real estate, 25 % on gross consideration"}

   {:parameter-value/parameter      [:parameter/code "MX.CGT.art-160.nr-real-estate-net-rate"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.35M
    :parameter-value/citation       "LISR art. 160 — non-resident real estate, 35 % on net gain (dictamen + rep)"}

   {:parameter-value/parameter      [:parameter/code "MX.CGT.art-161.nr-shares-gross-rate"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.25M
    :parameter-value/citation       "LISR art. 161 (SAT canonical) — 25 % on gross share proceeds"}

   {:parameter-value/parameter      [:parameter/code "MX.CGT.art-161.nr-shares-net-rate"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.35M
    :parameter-value/citation       "LISR art. 161 (SAT canonical) — 35 % on net gain (dictamen + rep)"}

   {:parameter-value/parameter      [:parameter/code "MX.CGT.art-127.state-notary-rate"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.05M
    :parameter-value/citation       "LISR art. 127 — 5 % state withholding on real-estate gain at notarisation"}])

;; ============================================================================
;; Provisions
;; ============================================================================
;;
;; MX CGT logic is predominantly provider-side (asset-class dispatch +
;; art. 120 averaging + CUFIN folds + notary-credit threading), so the
;; v1 provision set is small. The two provisions here let the
;; statute-as-data substrate carry the casa-habitación cap and the
;; art. 127 state surtax as queryable rules rather than provider-local
;; constants. The bulk of MX CGT will migrate to `:provision`-shape
;; in Phase 3 once art. 120 averaging stabilises (note 132 §6).

(def provisions
  "MX CGT provisions encoded for `kontor.statute`. Two entries in v1;
   the rest of the logic lives in the provider (per note 102 §10's
   record-shape-first migration discipline)."
  [;; --------------------------------------------------------------------
   ;; Casa habitación 700k UDIS cap — surfaces as an audit-traceable
   ;; provision pointing at the parameter. The provider reads this
   ;; provision to lift the cap value (rather than the parameter
   ;; directly), giving statute-as-data provenance to the cap.
   ;; --------------------------------------------------------------------
   {:provision/code            "MX-LISR-art-93-XIX-a-casa-habitacion"
    :provision/jurisdiction    :mx
    :provision/concept         [:tax-concept/code :holding-period-preference]
    :provision/title           "Art. 93 fr. XIX a) — casa habitación exemption (700 000 UDIS)"
    :provision/citation        "https://www.sat.gob.mx/articulo/31901/articulo-93"
    :provision/effective-from  #inst "2014-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:eq [:tax-unit :mx-residence-status] :resident])
    :provision/consequence     (pr-str {:op :base-deduct
                                        :code :mx-casa-habitacion-cap-udis
                                        :label "Casa habitación cap (700 000 UDIS)"
                                        :amount-from :parameter
                                        :parameter "MX.CGT.casa-habitacion-cap-udis"})}

   ;; --------------------------------------------------------------------
   ;; Art. 127 — state 5 % notary surtax on real-estate gain. The
   ;; provider folds this as a parallel computation against the
   ;; real-estate gain (credited against the federal provisional).
   ;; --------------------------------------------------------------------
   {:provision/code            "MX-LISR-art-127-state-5pct"
    :provision/jurisdiction    :mx
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "Art. 127 — 5 % state notary remittance on real-estate gain"
    :provision/citation        "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-iv/seccion-i/"
    :provision/effective-from  #inst "2014-01-01"
    :provision/priority        200
    :provision/condition       (pr-str [:eq :asset-class :mx-inmueble])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :mx-art-127-state-5pct
                                        :label "State notary 5 % (art. 127)"
                                        :amount-from :parameter
                                        :parameter "MX.CGT.art-127.state-notary-rate"})}])

;; ============================================================================
;; Install! — transact parameters + provisions
;; ============================================================================

(defn install!
  "Install MX CGT statute (parameters + provisions) into `conn`.
   Idempotent — `:parameter/code` and `:provision/code` are unique
   identity attrs."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))
