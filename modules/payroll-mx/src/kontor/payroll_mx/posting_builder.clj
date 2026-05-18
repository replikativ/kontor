(ns kontor.payroll-mx.posting-builder
  "Translate `:payroll-facts` → balanced
   `kontor.posting/build-transaction` payload routed through SAT
   Código Agrupador-keyed account map. ADR-082.

   ## GL routing

   For one (employee, period) record we produce one balanced journal:

     Dr 601.01  Sueldos y Salarios          (sum of :sueldo /
                                              horas-extra)
     Dr 601.02  Aguinaldo + Prima Vac        (sum of :aguinaldo +
                                              :prima-vacacional)
     Dr 601.05  Cuotas IMSS patronales       (employer-paid IMSS
                                              + RCV-patron)
     Dr 601.06  Aportaciones INFONAVIT       (employer-only
                                              INFONAVIT)
     Dr 601.84  Otras prestaciones           (non-taxable: vales,
                                              fondo ahorro)
                                              (employer side)

     Cr 206.01  Sueldos por pagar            (net = ΣPercepciones
                                              − ΣDeducciones
                                              + ΣOtrosPagos
                                              [worker-side only])
     Cr 206.04  Impuestos por pagar (ISR)    (− ΣSubsidio al empleo)
     Cr 206.05  IMSS por pagar               (trabajador + patrón
                                              + RCV-patron)
     Cr 206.06  INFONAVIT por pagar          (trabajador + patrón)

   The kernel `build-transaction` checks sum-to-zero. The account
   eids are resolved by `:account/code` via
   `core/account-by-codigo-agrupador`. If a required account is
   missing, posting raises with a clear message.

   ## Aggregating across employees

   `build-period-tx-data` accepts a vector of :payroll-facts and
   produces ONE journal that aggregates across all (employee,
   wage-type) cells. Per-employee detail lives on the CFDI Nómina
   XML side (and in :payroll-facts itself, persisted as audit-doc
   metadata)."
  (:require [kontor.payroll-mx.core :as core]
            [kontor.payroll-mx.wage-types :as wt]
            [kontor.posting :as posting])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Aggregation
;; ============================================================================

(defn- zero
  ^BigDecimal []
  (.setScale 0M 2 RoundingMode/HALF_EVEN))

(defn- add-amount
  ^BigDecimal [^BigDecimal a ^BigDecimal b]
  (.setScale (.add a b) 2 RoundingMode/HALF_EVEN))

(defn aggregate-by-codigo
  "Group wage-rows across all employees by `:codigo-agrup` + `:kind`,
   summing amounts.  Returns a sorted map of
     {[codigo kind employer-only?] BigDecimal}."
  [facts-vec]
  (reduce
   (fn [acc facts]
     (reduce
      (fn [acc2 row]
        (let [{:keys [wage-type amount]} row
              entry  (wt/lookup wage-type)
              codigo (:codigo-agrup entry)
              kind   (:kind entry)
              eo?    (boolean (:employer-only? entry))]
          (update acc2 [codigo kind eo? wage-type]
                  (fnil add-amount (zero)) amount)))
      acc
      (:wage-types facts)))
   (sorted-map)
   facts-vec))

;; ============================================================================
;; Period totals
;; ============================================================================

(defn period-totals
  "Compute scalar totals across `facts-vec`:
     :total-percepciones (worker-side only — excludes employer-only)
     :total-deducciones
     :total-otros-pagos
     :total-employer-cost  (IMSS patrón + INFONAVIT patrón + RCV
                            patrón)
     :neto-a-pagar         (percepciones − deducciones + otros-pagos)"
  [facts-vec]
  (let [aggs (aggregate-by-codigo facts-vec)
        sum (fn [pred]
              (->> aggs
                   (filter (fn [[[_ kind eo? _] _]] (pred kind eo?)))
                   (map second)
                   (reduce add-amount (zero))))
        percep   (sum (fn [k eo?] (and (= k :percepcion) (not eo?))))
        deduc    (sum (fn [k _]   (= k :deduccion)))
        otros    (sum (fn [k _]   (= k :otro-pago)))
        emp-cost (sum (fn [k eo?] (and (= k :percepcion) eo?)))]
    {:total-percepciones percep
     :total-deducciones  deduc
     :total-otros-pagos  otros
     :total-employer-cost emp-cost
     :neto-a-pagar (-> percep (.subtract deduc) (.add otros)
                       (.setScale 2 RoundingMode/HALF_EVEN))}))

;; ============================================================================
;; Posting build
;; ============================================================================

(defn- resolve-account!
  "Resolve a :account eid by SAT Código Agrupador, raising on miss."
  [db codigo]
  (or (core/account-by-codigo-agrupador db codigo)
      (throw (ex-info "Missing GL account for SAT Código Agrupador"
                      {:codigo-agrupador codigo
                       :hint "Install kontor.l10n-mx chart-of-accounts first."}))))

(defn build-period-tx-data
  "Build the `kontor.posting/build-transaction` payload for one
   payroll period (one journal that aggregates every employee in
   `facts-vec`). Returns datahike tx-data ready for
   `validation/transact-with-validation`.

   Required opts:
     :db          — datahike value used to resolve accounts
     :journal     — :journal eid OR [:journal/code 'NOM']
     :commodity   — :commodity eid OR [:commodity/symbol 'MXN']
     :period      — {:start <inst> :end <inst> :payment-date <inst>}
     :facts       — vector of :payroll-facts maps

   Optional:
     :narration   — defaults to 'Nómina periodo YYYY-MM-DD..YYYY-MM-DD'
     :external-id — defaults to NOM-YYYY-MM-DD-payment-date

   Cr/Dr routing (the kernel's sum-to-zero check enforces balance):
     Dr 6xx codigo for each percepcion (employer + employee-side)
     Cr 206.01 net-payable
     Cr 206.04 ISR retenido − subsidio al empleo
     Cr 206.05 IMSS trabajador + IMSS patrón + RCV patrón
     Cr 206.06 INFONAVIT trabajador + INFONAVIT patrón"
  [{:keys [db journal commodity period facts narration external-id]}]
  (when-not db       (throw (ex-info ":db required" {})))
  (when-not journal  (throw (ex-info ":journal required" {})))
  (when-not commodity (throw (ex-info ":commodity required" {})))
  (when-not period   (throw (ex-info ":period required" {})))
  (when-not (vector? facts) (throw (ex-info ":facts must be a vector" {})))
  (let [aggs (aggregate-by-codigo facts)
        ;; Build per-wage-type Dr lines for every percepcion, separately
        ;; for worker-side and employer-side (they post to different
        ;; expense accounts but the same Dr direction).
        debit-rows
        (for [[[codigo kind _eo? wage-type] amount] aggs
              :when (and (= kind :percepcion) (pos? (.signum ^BigDecimal amount)))]
          {:posting/account (resolve-account! db codigo)
           :posting/amount  amount
           :posting/commodity commodity
           :posting/narration (str "Dr " codigo " "
                                   (wt/description wage-type))})

        ;; Worker-side credit lines (the bank-payable, the ISR liability,
        ;; the social-security payable for the worker portion only).
        sum-where (fn [pred]
                    (->> aggs
                         (filter (fn [[k _]] (pred k)))
                         (map second)
                         (reduce add-amount (zero))))
        worker-percep (sum-where (fn [[_ kind eo? _]]
                                   (and (= kind :percepcion) (not eo?))))
        worker-deduc  (sum-where (fn [[_ kind _ _]] (= kind :deduccion)))
        otros-pagos   (sum-where (fn [[_ kind _ _]] (= kind :otro-pago)))
        ;; Net payable to worker (Cr 206.01)
        neto-payable (-> worker-percep
                         (.subtract worker-deduc)
                         (.add otros-pagos)
                         (.setScale 2 RoundingMode/HALF_EVEN))

        ;; ISR retenido − subsidio al empleo → Cr 206.04
        isr-liability
        (-> (->> aggs
                 (filter (fn [[[_ k _ wt-code] _]]
                           (and (= k :deduccion) (= wt-code :isr-retencion))))
                 (map second)
                 (reduce add-amount (zero)))
            (.subtract
             (->> aggs
                  (filter (fn [[[_ k _ wt-code] _]]
                            (and (= k :otro-pago) (= wt-code :subsidio-al-empleo))))
                  (map second)
                  (reduce add-amount (zero))))
            (.setScale 2 RoundingMode/HALF_EVEN))

        ;; IMSS payable: trabajador + patrón + RCV-patron → Cr 206.05
        imss-payable
        (->> aggs
             (filter (fn [[[_ _ _ wt-code] _]]
                       (contains? #{:imss-trabajador :imss-patron :rcv-patron} wt-code)))
             (map second)
             (reduce add-amount (zero)))

        ;; INFONAVIT payable: trabajador + patrón → Cr 206.06
        infonavit-payable
        (->> aggs
             (filter (fn [[[_ _ _ wt-code] _]]
                       (contains? #{:infonavit-trabajador :infonavit-patron} wt-code)))
             (map second)
             (reduce add-amount (zero)))

        credit-rows
        (cond-> []
          (pos? (.signum neto-payable))
          (conj {:posting/account (resolve-account! db "206.01")
                 :posting/amount  (.negate neto-payable)
                 :posting/commodity commodity
                 :posting/narration "Cr 206.01 Sueldos por pagar (neto)"})

          (pos? (.signum isr-liability))
          (conj {:posting/account (resolve-account! db "206.04")
                 :posting/amount  (.negate isr-liability)
                 :posting/commodity commodity
                 :posting/narration "Cr 206.04 ISR retenido neto subsidio"})

          (pos? (.signum imss-payable))
          (conj {:posting/account (resolve-account! db "206.05")
                 :posting/amount  (.negate imss-payable)
                 :posting/commodity commodity
                 :posting/narration "Cr 206.05 IMSS por pagar"})

          (pos? (.signum infonavit-payable))
          (conj {:posting/account (resolve-account! db "206.06")
                 :posting/amount  (.negate infonavit-payable)
                 :posting/commodity commodity
                 :posting/narration "Cr 206.06 INFONAVIT por pagar"}))

        tx-narration (or narration
                         (str "Nómina periodo "
                              (:start period) ".." (:end period)))
        tx-ext-id (or external-id
                      (str "NOM-" (:payment-date period)))]
    (posting/build-transaction
     {:transaction (cond-> {:transaction/journal journal
                            :transaction/effective-date (:payment-date period)
                            :transaction/narration tx-narration
                            :transaction/state :draft}
                     tx-ext-id (assoc :transaction/external-id tx-ext-id))
      :postings (vec (concat debit-rows credit-rows))})))
