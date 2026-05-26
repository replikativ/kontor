(ns kontor.payroll-mx.adapter
  "Bridge layer wiring MX-private engine/emitter protocols into the
   kernel-substrate `kontor.payroll-provider` PayrollProvider trio
   so `kontor.hr.payroll/run-payroll!` can drive MX payroll runs in
   the same shape as DE / US / CA / FR / AU / BR / IN / JP / CN.

   ## Why a bridge (instead of swapping protocols outright)

   The MX module was written before the Stage R substrate landed and
   ships its own engine-CSV ingestion protocol (`MxEngineProvider`)
   plus its own CFDI XML emit protocol (`MxCfdiEmitter`). These
   protocols carry data-shapes (per-employee per-wage-type rows,
   CFDI Nómina envelopes) that are deeply MX-specific and have
   value in their own right — the existing tests are written
   against them, and consumer code that only needs MX (no other
   country) can use them directly.

   The kernel-substrate protocols
   (`kontor.payroll-provider/PayrollComputeProvider` +
   `PayrollPostingBuilder` + `PayrollEmitProvider`) are different
   shapes: they speak in canonical `PayrollFacts` (one map per
   employment with `:gross :net :components`) and `:audit-doc`
   tx-data. The bridge adapts between the two so:

     - existing MX tests + consumers keep working unchanged;
     - new MX consumers can drive MX through `run-payroll!`
       alongside any other Stage R-conformant country.

   ## The trio

     - `MxKontorComputeProvider` — wraps an `MxEngineProvider`
       (e.g. `ContpaqiNominasProvider`, `AspelNoiProvider`).
       Reads `:csv-source` + `:employment-by-rfc-map` from the
       run-payroll! ctx `:variable-inputs`, delegates to the
       MX engine's `parse-period`, and projects MX per-wage-type
       rows → canonical `PayrollFacts` (one per `:employment`).

     - `MxKontorPostingBuilder` — satisfies kernel
       `PayrollPostingBuilder`. Per-component `:kind` is mapped to
       the consumer-supplied `:accounts` map via the same SAT
       Código Agrupador routing the legacy MX `posting-builder.clj`
       uses. The consumer supplies `:accounts` keyed by codigo-
       agrupador strings (\"601.01\", \"206.01\", …); the builder
       emits balanced posting maps.

     - `MxKontorEmitProvider` — wraps an `MxCfdiEmitter`
       (e.g. `MxCfdiNominaEmitProvider`). Delegates the CFDI XML
       generation, then projects the result into the canonical
       `:audit-doc` tx-data shape with
       `:audit-doc/category :payroll-filing` +
       `:audit-doc/language :es-mx` (per note 86 P0-86-2 + ADR-082).

   ## Wiring

   ```clojure
   (require '[kontor.payroll-mx.adapter :as mx-adapter]
            '[kontor.payroll-mx.compute :as mx-compute]
            '[kontor.payroll-mx.emit    :as mx-emit]
            '[kontor.hr.payroll :as payroll])

   (let [providers (mx-adapter/make-mx-kontor-providers
                     {:mx-engine (mx-compute/make-contpaqi-nominas-provider)
                      :mx-emitter (mx-emit/make-cfdi-nomina-provider)
                      :commodity-eid <MXN eid>
                      :employer <employer map for the CFDI emit>})]
     (payroll/run-payroll!
       conn {:pay-period   <pp eid>
             :entity       <entity eid>
             :employments  [<employment eids>]
             :variable-inputs
               {:csv-source <reader/string fixture>
                :employment-by-rfc-map {\"ABCD800101AB1\" <emp eid>
                                        \"EFGH900202EF2\" <emp eid>}
                :employee-cfdi-data
                  {<emp eid> {:rfc \"...\" :curp \"...\" :nss \"...\" ...}}}
             :compute-provider (:compute-provider providers)
             :posting-builder  (:posting-builder providers)
             :emit-provider    (:emit-provider providers)
             :accounts
               {\"601.01\" <wages-expense eid>  ; Sueldos
                \"601.02\" <aguinaldo eid>
                \"601.05\" <imss-patron expense>
                \"601.06\" <infonavit-patron expense>
                \"601.84\" <prestaciones expense>
                \"206.01\" <sueldos por pagar>
                \"206.04\" <ISR payable>
                \"206.05\" <IMSS payable>
                \"206.06\" <INFONAVIT payable>}
             :run-code \"ACME-MX-2026-05-001\"
             :tx-code  \"TX-ACME-MX-2026-05\"
             :journal  <NOM journal eid>
             :commodity <MXN eid>}))
   ```

   ## Posture (per ADR-082 + ADR-075 + note 86 P0-86-2)

   - No bundled rates, no PAC credentials, no XSD bundled — all
     vendor / regulator material is referenced from sat.gob.mx
     specs; the consumer holds engine + PAC credentials.
   - BigDecimal HALF-EVEN throughout (Money discipline).
   - `:audit-doc/category :payroll-filing` is canonical (note 86).
   - `:audit-doc/language :es-mx` is the canonical MX locale tag.
   - License posture clean — the bridge contains no derivative
     code from any vendor source; it just routes MX shapes into
     kernel shapes."
  (:require [kontor.payroll-mx.core :as core]
            [kontor.payroll-mx.wage-types :as wt]
            [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- bd-zero
  ^BigDecimal []
  (.setScale 0M 2 RoundingMode/HALF_EVEN))

(defn- bd-add
  ^BigDecimal [^BigDecimal a ^BigDecimal b]
  (.setScale (.add a b) 2 RoundingMode/HALF_EVEN))

(defn- bd-sum
  ^BigDecimal [bds]
  (reduce bd-add (bd-zero) bds))

(defn- signed-component-amount
  "Translate one MX wage-row → a signed component amount per the
   kernel's `:components` convention:

     - :percepcion       → positive (earnings paid into gross)
     - :deduccion        → negative (withheld from worker)
     - :otro-pago        → positive (e.g. subsidio al empleo,
                           paid to worker)
     - employer-only     → positive amount, `:employer-side? true`
                           (does NOT participate in gross / net per
                            ADR-075 contract)"
  ^BigDecimal [{:keys [kind employer-only? amount]}]
  (cond
    employer-only?    amount                      ; magnitude
    (= kind :percepcion) amount                   ; +
    (= kind :otro-pago)  amount                   ; +
    (= kind :deduccion)  (.negate ^BigDecimal amount) ; -
    :else amount))

(defn- mx-row->component
  "Translate one MX `:wage-types` row into the kernel
   `:components` shape required by PayrollFacts. Carries the SAT
   `:codigo-agrup` in `:jurisdiction-codes` for the posting builder
   to route on (so we don't lose the MX vocabulary)."
  [{:keys [wage-type amount]}]
  (let [entry  (wt/lookup wage-type)
        kind   (:kind entry)
        eo?    (boolean (:employer-only? entry))
        codigo (:codigo-agrup entry)
        signed (signed-component-amount {:kind kind
                                         :employer-only? eo?
                                         :amount amount})]
    {:kind wage-type
     :amount signed
     :employer-side? eo?
     :jurisdiction-codes
     {:mx/kind kind
      :mx/codigo-agrupador codigo
      :mx/wage-type wage-type
      :mx/sat-code (:sat-code entry)}}))

(defn- mx-facts->payroll-fact
  "Convert one MX `:payroll-facts` map (per employee, per period)
   into the canonical `PayrollFacts` shape per ADR-075 §
   PayrollFacts. Resolves :employment eid via the supplied
   `employment-by-rfc-map`.

   - gross = Σ positive employee-side component amounts
   - net   = gross + Σ negative employee-side component amounts
   - components carry MX vocabulary via `:jurisdiction-codes`"
  [{:keys [employment-by-rfc-map pay-period-eid commodity-eid]} mx-facts]
  (let [rfc (:employee/rfc mx-facts)
        emp-eid (get employment-by-rfc-map rfc)
        _ (when-not emp-eid
            (throw (ex-info (str "No :employment eid for RFC " rfc
                                 " in :employment-by-rfc-map")
                            {:rfc rfc
                             :known (set (keys employment-by-rfc-map))})))
        components (mapv mx-row->component (:wage-types mx-facts))
        ;; gross = Σ positive employee-side components
        gross (->> components
                   (remove :employer-side?)
                   (map :amount)
                   (filter #(pos? (compare ^BigDecimal % 0M)))
                   bd-sum)
        ;; net = gross + Σ negative employee-side components
        deducs (->> components
                    (remove :employer-side?)
                    (map :amount)
                    (filter #(neg? (compare ^BigDecimal % 0M)))
                    bd-sum)
        net    (.add ^BigDecimal gross ^BigDecimal deducs)]
    (cond-> {:employment emp-eid
             :gross gross
             :net   net
             :components components
             :jurisdiction-specific-codes
             {:engine :mx
              :rfc rfc
              :curp (:employee/curp mx-facts)
              :employee-code (:employee/code mx-facts)
              :period-start  (:kontor.period/start mx-facts)
              :period-end    (:kontor.period/end mx-facts)
              :period-payment-date (:kontor.period/payment-date mx-facts)
              ;; Round-trip the MX-shape facts so the emit-provider
              ;; bridge can reach them later in run-payroll! without
              ;; re-parsing.
              :mx-facts mx-facts}}
      pay-period-eid (assoc :pay-period pay-period-eid)
      commodity-eid  (assoc :commodity commodity-eid))))

;; ============================================================================
;; MxKontorComputeProvider — wraps an MxEngineProvider
;; ============================================================================

(defrecord MxKontorComputeProvider [mx-engine opts]
  pp/PayrollComputeProvider
  (provider-id [_]
    ;; Carry the wrapped engine vendor-id through so the
    ;; :payroll-run/provider-id slot records the upstream engine
    ;; (e.g. :contpaqi-nominas) — matches the substrate convention.
    (core/vendor-id mx-engine))
  (compute-payroll [_ {:keys [pay-period-eid employment-eids variable-inputs]}]
    (when-not mx-engine
      (throw (ex-info "MxKontorComputeProvider needs an :mx-engine" {})))
    (let [{:keys [csv-source employment-by-rfc-map]} variable-inputs
          commodity-eid (:commodity-eid opts)]
      (when-not csv-source
        (throw (ex-info
                "MxKontorComputeProvider needs :csv-source in :variable-inputs"
                {:variable-inputs (keys variable-inputs)})))
      (when-not employment-by-rfc-map
        (throw
         (ex-info
          "MxKontorComputeProvider needs :employment-by-rfc-map in :variable-inputs"
          {:variable-inputs (keys variable-inputs)})))
      (let [mx-facts-vec (core/parse-period mx-engine csv-source)
            ;; Filter to employments the run requested; engines may
            ;; emit other employees in the same file.
            relevant
            (->> mx-facts-vec
                 (filter
                  (fn [{:keys [employee/rfc]}]
                    (let [eid (get employment-by-rfc-map rfc)]
                      (and eid
                           (or (empty? employment-eids)
                               (contains? (set employment-eids) eid))))))
                 vec)]
        (mapv #(mx-facts->payroll-fact
                {:employment-by-rfc-map employment-by-rfc-map
                 :pay-period-eid pay-period-eid
                 :commodity-eid commodity-eid}
                %)
              relevant)))))

(defn make-mx-kontor-compute-provider
  "Construct an MxKontorComputeProvider wrapping a concrete
   MxEngineProvider (CONTPAQi / Aspel / etc.).

   Required opts:
     :mx-engine     — an MxEngineProvider instance.

   Optional opts:
     :commodity-eid — :commodity ref to stamp on the PayrollFacts."
  [{:keys [mx-engine commodity-eid] :as opts}]
  (when-not mx-engine
    (throw (ex-info "make-mx-kontor-compute-provider needs :mx-engine"
                    {:opts opts})))
  (->MxKontorComputeProvider mx-engine {:commodity-eid commodity-eid}))

;; ============================================================================
;; MxKontorPostingBuilder — satisfies PayrollPostingBuilder
;; ============================================================================
;;
;; Routes per-component :jurisdiction-codes :mx/codigo-agrupador to
;; the consumer's :accounts map. Produces a balanced posting set per
;; PayrollFact.
;;
;; Posting shape (the same Código Agrupador routing the legacy
;; posting_builder.clj uses, but lifted to per-fact rather than
;; per-period aggregation — run-payroll! aggregates per-fact across
;; facts when it composes them into one :transaction):
;;
;;   For each PayrollFact:
;;
;;     Dr 601.0x (per percepcion :codigo-agrupador) — gross worker-side
;;     Dr 601.0x employer-only expense (e.g. IMSS patrón → 601.05)
;;     Cr 206.01 net worker payable (gross - deducs + otros-pagos)
;;     Cr 206.04 ISR liability − subsidio
;;     Cr 206.05 IMSS payable (trabajador + patrón + RCV-patron)
;;     Cr 206.06 INFONAVIT payable (trabajador + patrón)

(defn- account-for-codigo!
  [accounts codigo]
  (or (get accounts codigo)
      (throw (ex-info (str "No GL account configured for SAT Código "
                           "Agrupador " codigo)
                      {:codigo-agrupador codigo
                       :hint (str "Add it to the :accounts map passed "
                                  "to run-payroll!. The consumer's "
                                  "l10n-mx chart-of-accounts module "
                                  "supplies the eids.")
                       :available-codigos (set (keys accounts))}))))

(defn- post-leg
  [{:keys [account amount commodity narration]}]
  (cond-> {:kontor.posting/account account
           :kontor.posting/amount amount
           :kontor.posting/commodity commodity}
    narration (assoc :kontor.posting/narration narration)))

(defn- fact->mx-postings
  "Per-fact balanced postings, routed by SAT Código Agrupador."
  [{:keys [components]}
   {:keys [accounts commodity]}]
  (let [;; Components carry signed amounts (deductions are negative,
        ;; percepciones are positive). The MX :codigo-agrupador lives
        ;; on each component's :jurisdiction-codes (set by
        ;; mx-row->component above).
        codigo-of (fn [c]
                    (get-in c [:jurisdiction-codes :mx/codigo-agrupador]))
        mx-kind-of (fn [c]
                     (get-in c [:jurisdiction-codes :mx/kind]))
        wage-type-of (fn [c]
                       (get-in c [:jurisdiction-codes :mx/wage-type]))

        ;; Group worker-side percepciones (positive amounts) by codigo
        ;; for Dr expense legs.
        debit-percep-by-codigo
        (->> components
             (filter (fn [c]
                       (and (not (:employer-side? c))
                            (= :percepcion (mx-kind-of c))
                            (pos? (compare ^BigDecimal (:amount c) 0M)))))
             (group-by codigo-of)
             (map (fn [[codigo cs]]
                    {:codigo codigo
                     :amount (bd-sum (map :amount cs))}))
             vec)

        ;; Employer-side percepciones — separate Dr expense per codigo
        ;; (601.05, 601.06, etc.). These ARE expenses to the employer
        ;; and the matching payable feeds into 206.05/206.06 below.
        debit-employer-by-codigo
        (->> components
             (filter :employer-side?)
             (group-by codigo-of)
             (map (fn [[codigo cs]]
                    {:codigo codigo
                     :amount (bd-sum (map :amount cs))}))
             vec)

        ;; Per-bucket credit totals:
        ;;   206.04 = Σ ISR − Σ subsidio-al-empleo
        ;;   206.05 = Σ (imss-trabajador + imss-patron + rcv-patron)
        ;;   206.06 = Σ (infonavit-trabajador + infonavit-patron)
        ;;   206.01 = (worker-percep − worker-deduc) + otros-pagos
        ;;            (= what the worker takes home)
        worker-percep
        (->> components
             (filter (fn [c]
                       (and (not (:employer-side? c))
                            (= :percepcion (mx-kind-of c)))))
             (map :amount)
             bd-sum)
        ;; Deductions are stored negative; take absolute value for
        ;; the liability buckets.
        worker-deduc
        (->> components
             (filter (fn [c]
                       (and (not (:employer-side? c))
                            (= :deduccion (mx-kind-of c)))))
             (map :amount)
             bd-sum
             ((fn [^BigDecimal x] (.negate x))))
        worker-otros
        (->> components
             (filter (fn [c]
                       (and (not (:employer-side? c))
                            (= :otro-pago (mx-kind-of c)))))
             (map :amount)
             bd-sum)
        neto-payable (-> worker-percep
                         (.subtract ^BigDecimal worker-deduc)
                         (.add ^BigDecimal worker-otros)
                         (.setScale 2 RoundingMode/HALF_EVEN))

        ;; ISR retenido − subsidio al empleo → 206.04
        isr-liability
        (-> (->> components
                 (filter #(= :isr-retencion (wage-type-of %)))
                 (map :amount)
                 bd-sum
                 ((fn [^BigDecimal x] (.negate x)))) ; deductions are −
            (.subtract
             (->> components
                  (filter #(= :subsidio-al-empleo (wage-type-of %)))
                  (map :amount)
                  bd-sum))
            (.setScale 2 RoundingMode/HALF_EVEN))

        ;; IMSS payable (trabajador deduction + patron expense + rcv-patron expense)
        imss-payable
        (-> (->> components
                 (filter #(= :imss-trabajador (wage-type-of %)))
                 (map :amount)
                 bd-sum
                 ((fn [^BigDecimal x] (.negate x))))
            (.add (->> components
                       (filter #(contains? #{:imss-patron :rcv-patron}
                                           (wage-type-of %)))
                       (map :amount)
                       bd-sum))
            (.setScale 2 RoundingMode/HALF_EVEN))

        ;; INFONAVIT payable
        infonavit-payable
        (-> (->> components
                 (filter #(= :infonavit-trabajador (wage-type-of %)))
                 (map :amount)
                 bd-sum
                 ((fn [^BigDecimal x] (.negate x))))
            (.add (->> components
                       (filter #(= :infonavit-patron (wage-type-of %)))
                       (map :amount)
                       bd-sum))
            (.setScale 2 RoundingMode/HALF_EVEN))

        debit-legs
        (concat
         (for [{:keys [codigo amount]} debit-percep-by-codigo
               :when (pos? (compare ^BigDecimal amount 0M))]
           (post-leg
            {:account (account-for-codigo! accounts codigo)
             :amount amount
             :commodity commodity
             :narration (str "Dr " codigo " worker-side percepción")}))
         (for [{:keys [codigo amount]} debit-employer-by-codigo
               :when (pos? (compare ^BigDecimal amount 0M))]
           (post-leg
            {:account (account-for-codigo! accounts codigo)
             :amount amount
             :commodity commodity
             :narration (str "Dr " codigo " employer-side expense")})))

        credit-legs
        (cond-> []
          (pos? (compare ^BigDecimal neto-payable 0M))
          (conj (post-leg
                 {:account (account-for-codigo! accounts "206.01")
                  :amount (.negate ^BigDecimal neto-payable)
                  :commodity commodity
                  :narration "Cr 206.01 Sueldos por pagar (neto)"}))

          (pos? (compare ^BigDecimal isr-liability 0M))
          (conj (post-leg
                 {:account (account-for-codigo! accounts "206.04")
                  :amount (.negate ^BigDecimal isr-liability)
                  :commodity commodity
                  :narration "Cr 206.04 ISR retenido neto subsidio"}))

          (pos? (compare ^BigDecimal imss-payable 0M))
          (conj (post-leg
                 {:account (account-for-codigo! accounts "206.05")
                  :amount (.negate ^BigDecimal imss-payable)
                  :commodity commodity
                  :narration "Cr 206.05 IMSS por pagar"}))

          (pos? (compare ^BigDecimal infonavit-payable 0M))
          (conj (post-leg
                 {:account (account-for-codigo! accounts "206.06")
                  :amount (.negate ^BigDecimal infonavit-payable)
                  :commodity commodity
                  :narration "Cr 206.06 INFONAVIT por pagar"})))]
    (vec (concat debit-legs credit-legs))))

(defrecord MxKontorPostingBuilder [opts]
  pp/PayrollPostingBuilder
  (build-postings [_ payroll-facts {:keys [accounts ledger]}]
    (let [commodity (or (:commodity opts)
                        (throw (ex-info
                                ":commodity required in MxKontorPostingBuilder opts"
                                {})))]
      (vec
       (mapcat
        (fn [fact]
          (let [legs (fact->mx-postings fact
                                        {:accounts accounts
                                         :commodity commodity})]
            (cond->> legs
              ledger (mapv #(assoc % :kontor.posting/ledger ledger)))))
        payroll-facts)))))

(defn make-mx-kontor-posting-builder
  "Construct an MxKontorPostingBuilder.

   Required opts:
     :commodity — :commodity eid (MXN)."
  [{:keys [commodity] :as opts}]
  (when-not commodity
    (throw (ex-info "make-mx-kontor-posting-builder needs :commodity"
                    {:opts opts})))
  (->MxKontorPostingBuilder opts))

;; ============================================================================
;; MxKontorEmitProvider — wraps an MxCfdiEmitter
;; ============================================================================

(defn- mx-emit-result->audit-doc-tx-data
  "Project the MX CFDI emit result map (see `kontor.payroll-mx.emit`)
   into a kernel `:audit-doc` tx-data map per ADR-068 + ADR-082 +
   note 86 P0-86-2 canonical vocabulary.

   The PAC TFD stamp is NOT applied here — that is a consumer
   workflow concern (partner-side). The :audit-doc records the
   UNSIGNED XML's content-hash so the audit chain ties the
   pre-stamp envelope to the run."
  [emit-result {:keys [code-prefix entity-eid pay-period-eid
                       storage-uri-fn employee-code]}]
  (let [code (str (or code-prefix "CFDI-NOM-")
                  entity-eid "-" pay-period-eid
                  (when employee-code (str "-" employee-code)))
        title (or (:audit-doc/title emit-result)
                  (str "CFDI Nómina " code))
        desc  (or (:audit-doc/description emit-result)
                  (str "Recibo de nómina (sin sellar) — empleado "
                       employee-code))
        cat   (or (:audit-doc/category emit-result) :payroll-filing)
        lang  (or (:audit-doc/language emit-result) :es-mx)
        type  (or (:audit-doc/type emit-result) :payroll-cfdi-xml)
        xml   (:xml emit-result)
        ;; Storage-uri is consumer-supplied; default to an opaque
        ;; placeholder that records the run-scope so the consumer can
        ;; rebuild after a PAC stamp.
        storage-uri (if storage-uri-fn
                      (storage-uri-fn emit-result)
                      (str "kontor-payroll-mx://unsigned/"
                           code ".xml"))
        ;; Hash of the unsigned XML — opaque content hash that ties
        ;; the audit-doc to the bytes we produced here. The PAC's
        ;; stamping changes the bytes; the consumer records the
        ;; stamped UUID + content-hash separately as part of the PAC
        ;; settlement workflow.
        content-hash (when xml
                       (format "%08x"
                               (.hashCode ^String xml)))]
    (cond-> {:audit-doc/code code
             :audit-doc/type type
             :audit-doc/title title
             :audit-doc/description desc
             :audit-doc/category cat
             :audit-doc/language lang
             :audit-doc/storage-uri storage-uri
             :audit-doc/uploaded-at (java.util.Date.)}
      content-hash (assoc :audit-doc/content-hash content-hash))))

(defrecord MxKontorEmitProvider [mx-emitter opts]
  pp/PayrollEmitProvider
  (emit-payroll-events [_ payroll-facts {:keys [pay-period-eid entity-eid]}]
    (when-not mx-emitter
      (throw (ex-info "MxKontorEmitProvider needs an :mx-emitter" {})))
    (let [{:keys [employer employee-cfdi-data tipo
                  serie folio fecha lugar-expedicion
                  no-certificado certificado
                  storage-uri-fn code-prefix]} opts]
      (when-not employer
        (throw
         (ex-info
          "MxKontorEmitProvider opts need :employer (CFDI Emisor data)"
          {:opts (keys opts)})))
      (mapv
       (fn [fact]
         (let [emp-eid (:employment fact)
               mx-facts (get-in fact [:jurisdiction-specific-codes
                                      :mx-facts])
               employee-data (get employee-cfdi-data emp-eid)
               employee-code (:employee-code
                              (:jurisdiction-specific-codes fact))]
           (when-not mx-facts
             (throw
              (ex-info
               (str "MxKontorEmitProvider: PayrollFact for "
                    emp-eid " missing :mx-facts in "
                    ":jurisdiction-specific-codes (expected from "
                    "MxKontorComputeProvider)")
               {:employment emp-eid})))
           (when-not employee-data
             (throw
              (ex-info
               (str "MxKontorEmitProvider: no :employee-cfdi-data "
                    "entry for employment " emp-eid)
               {:employment emp-eid
                :known-employments (set (keys employee-cfdi-data))})))
           (let [emit-result
                 (core/emit-payroll
                  mx-emitter
                  mx-facts
                  (cond-> {:employer employer
                           :employee employee-data
                           :tipo (or tipo :ordinary)}
                    serie            (assoc :serie serie)
                    folio            (assoc :folio folio)
                    fecha            (assoc :fecha fecha)
                    lugar-expedicion (assoc :lugar-expedicion lugar-expedicion)
                    no-certificado   (assoc :no-certificado no-certificado)
                    certificado      (assoc :certificado certificado)))]
             (mx-emit-result->audit-doc-tx-data
              emit-result
              {:code-prefix code-prefix
               :entity-eid entity-eid
               :pay-period-eid pay-period-eid
               :employee-code employee-code
               :storage-uri-fn storage-uri-fn}))))
       payroll-facts))))

(defn make-mx-kontor-emit-provider
  "Construct an MxKontorEmitProvider wrapping a concrete MxCfdiEmitter.

   Required opts:
     :mx-emitter         — an MxCfdiEmitter instance (e.g. from
                           kontor.payroll-mx.emit/make-cfdi-nomina-provider).
     :employer           — CFDI Emisor map (rfc + nombre +
                           registro-patronal + regimen-fiscal).
     :employee-cfdi-data — map employment-eid → CFDI Receptor data
                           (rfc, curp, nss, tipo-contrato, etc.; the
                           same shape the underlying emitter consumes).

   Optional opts:
     :tipo               — :ordinary (default) or :extraordinary.
     :serie / :folio / :fecha / :lugar-expedicion
                         — passed through to the CFDI envelope.
     :no-certificado / :certificado
                         — passed through (consumer-held; not bundled).
     :storage-uri-fn     — (fn [emit-result] -> String) overrides the
                           default `kontor-payroll-mx://unsigned/...`
                           placeholder URI.
     :code-prefix        — overrides the default \"CFDI-NOM-\" audit-doc
                           code prefix."
  [{:keys [mx-emitter employer] :as opts}]
  (when-not mx-emitter
    (throw (ex-info "make-mx-kontor-emit-provider needs :mx-emitter"
                    {:opts opts})))
  (when-not employer
    (throw (ex-info "make-mx-kontor-emit-provider needs :employer"
                    {:opts opts})))
  (->MxKontorEmitProvider mx-emitter (dissoc opts :mx-emitter)))

;; ============================================================================
;; make-mx-kontor-providers — convenience constructor
;; ============================================================================

(defn make-mx-kontor-providers
  "Convenience: assemble the kernel-protocol trio for a single MX
   run. Returns `{:compute-provider <...> :posting-builder <...>
   :emit-provider <...>}` for easy wiring through
   `kontor.hr.payroll/run-payroll!`.

   Required opts:
     :mx-engine    — an MxEngineProvider instance
                      (e.g. `compute/make-contpaqi-nominas-provider`).
     :mx-emitter   — an MxCfdiEmitter instance
                      (e.g. `emit/make-cfdi-nomina-provider`).
     :commodity    — :commodity eid (MXN) for the posting builder.
     :employer     — CFDI Emisor map for the emit provider.

   Optional opts forward to the underlying provider constructors:
     :commodity-eid       — stamped on PayrollFacts (defaults to
                            :commodity)
     :employee-cfdi-data  — per-employment CFDI Receptor data
                            (required for emit if any fact reaches
                            the emit provider; can be supplied at
                            run-time by re-constructing).
     :tipo / :serie / :folio / :fecha / :lugar-expedicion /
     :no-certificado / :certificado / :storage-uri-fn /
     :code-prefix — see `make-mx-kontor-emit-provider`."
  [{:keys [mx-engine mx-emitter commodity employer
           commodity-eid employee-cfdi-data tipo serie folio fecha
           lugar-expedicion no-certificado certificado
           storage-uri-fn code-prefix]
    :as opts}]
  (when-not mx-engine
    (throw (ex-info "make-mx-kontor-providers needs :mx-engine"
                    {:opts (keys opts)})))
  (when-not mx-emitter
    (throw (ex-info "make-mx-kontor-providers needs :mx-emitter"
                    {:opts (keys opts)})))
  (when-not commodity
    (throw (ex-info "make-mx-kontor-providers needs :commodity (MXN eid)"
                    {:opts (keys opts)})))
  (when-not employer
    (throw (ex-info "make-mx-kontor-providers needs :employer (CFDI Emisor)"
                    {:opts (keys opts)})))
  {:compute-provider
   (make-mx-kontor-compute-provider
    {:mx-engine mx-engine
     :commodity-eid (or commodity-eid commodity)})
   :posting-builder
   (make-mx-kontor-posting-builder
    {:commodity commodity})
   :emit-provider
   (make-mx-kontor-emit-provider
    (cond-> {:mx-emitter mx-emitter
             :employer employer}
      employee-cfdi-data (assoc :employee-cfdi-data employee-cfdi-data)
      tipo               (assoc :tipo tipo)
      serie              (assoc :serie serie)
      folio              (assoc :folio folio)
      fecha              (assoc :fecha fecha)
      lugar-expedicion   (assoc :lugar-expedicion lugar-expedicion)
      no-certificado     (assoc :no-certificado no-certificado)
      certificado        (assoc :certificado certificado)
      storage-uri-fn     (assoc :storage-uri-fn storage-uri-fn)
      code-prefix        (assoc :code-prefix code-prefix)))})
