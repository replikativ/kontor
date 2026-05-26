(ns kontor.payroll-mx.accrual
  "Monthly accrual helpers for MX-mandated worker benefits — ADR-082.

   ## Aguinaldo (Ley Federal del Trabajo Art. 87)

   Legally a worker is entitled to a minimum of **15 days of salary**
   as a Christmas bonus, payable by **December 20** each year. Many
   employers pay 30 days; some pay more. The accrual recognizes
   `1/12` of the annual entitlement each month.

   ## Prima vacacional (LFT Art. 80)

   When a worker takes vacation, they earn an extra **25% on top of
   the vacation pay**. The base vacation entitlement scales with
   years of service (6 days year 1, +2/year up to year 4, then +2
   every 5 years). The 25% surcharge IS the prima vacacional.

   ## PTU — out of v1

   `Participación de Trabajadores en Utilidades` — 10% of taxable
   profit, distributed by May 30 the following year. Depends on the
   corporate ISR base, which the kernel does NOT know about. Left
   for v1.1 with corporate-tax-base substrate (Stage R+).

   ## Substrate shape

   Accruals are recorded as GL entries — they are NOT CFDI Nómina
   rows. The CFDI Nómina records the **payment** of aguinaldo when
   it actually happens (December periodicity \"04\" — Decenal /
   anual extraordinaria). The accrual is recognized monthly as an
   expense + a provision liability."
  (:require [kontor.payroll-mx.core :as core]
            [kontor.posting :as posting])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Aguinaldo
;; ============================================================================

(defn aguinaldo-monthly-accrual
  "Compute the monthly aguinaldo accrual for one worker:

     monthly = (daily-salary × bonus-days) / 12

   `daily-salary` is the worker's contractual daily wage (`salario
   diario integrado` for IMSS but simpler `salario diario base`
   suffices for the LFT calculation). `bonus-days` defaults to 15
   (the LFT minimum)."
  (^BigDecimal [daily-salary]
   (aguinaldo-monthly-accrual daily-salary 15))
  (^BigDecimal [^BigDecimal daily-salary ^long bonus-days]
   (when-not daily-salary (throw (ex-info ":daily-salary required" {})))
   (when-not (pos? bonus-days)
     (throw (ex-info ":bonus-days must be positive" {:bonus-days bonus-days})))
   (-> daily-salary
       (.multiply (BigDecimal/valueOf bonus-days))
       (.divide (BigDecimal/valueOf 12) 2 RoundingMode/HALF_EVEN))))

;; ============================================================================
;; Prima vacacional
;; ============================================================================

(def vacation-days-by-year
  "LFT Art. 76 vacation-day entitlement per years-of-service after
   the 2023 reform (Vacaciones Dignas). Returns the **annual** days.

   Year 1 = 12; year 2 = 14; year 3 = 16; year 4 = 18; year 5 = 20;
   thereafter +2 every five years."
  {1 12  2 14  3 16  4 18  5 20})

(defn vacation-days
  "Annual vacation-day entitlement for an MX worker at `years-of-service`
   (an integer ≥ 1). Implements LFT Art. 76 post-2023 reform."
  ^long [^long years-of-service]
  (cond
    (< years-of-service 1) 0
    (<= years-of-service 5) (get vacation-days-by-year years-of-service)
    :else (long (+ 20 (* 2 (long (Math/ceil (/ (- years-of-service 5) 5.0))))))))

(defn prima-vacacional
  "Prima vacacional on a vacation payout — 25% on top of the vacation
   wages.

     vacation-wages = daily-salary × vacation-days-taken
     prima          = vacation-wages × 0.25

   The function returns the **prima** scalar (the 25% surcharge),
   not the total vacation pay. Returns a BigDecimal."
  (^BigDecimal [^BigDecimal daily-salary ^long vacation-days-taken]
   (prima-vacacional daily-salary vacation-days-taken 0.25M))
  (^BigDecimal [^BigDecimal daily-salary ^long vacation-days-taken
                ^BigDecimal rate]
   (when-not daily-salary
     (throw (ex-info ":daily-salary required" {})))
   (-> daily-salary
       (.multiply (BigDecimal/valueOf vacation-days-taken))
       (.multiply rate)
       (.setScale 2 RoundingMode/HALF_EVEN))))

;; ============================================================================
;; Posting builders — Dr expense / Cr provision
;; ============================================================================

(defn build-aguinaldo-accrual-tx-data
  "Recognize one month's aguinaldo expense as a provision:

     Dr 601.02  Gratificación / Aguinaldo
     Cr 206.07  Provisión Aguinaldo

   Required opts:
     :db, :journal, :commodity, :date, :amount, :narration?

   Note: 206.07 is the conventional `:provisiones-laborales` sub-
   account; consumers commonly remap. If 206.07 isn't installed,
   pass `:provision-code` to override."
  [{:keys [db journal commodity date amount narration provision-code]}]
  (when-not db        (throw (ex-info ":db required" {})))
  (when-not journal   (throw (ex-info ":journal required" {})))
  (when-not commodity (throw (ex-info ":commodity required" {})))
  (when-not date      (throw (ex-info ":date required" {})))
  (when-not amount    (throw (ex-info ":amount required" {})))
  (let [aguinaldo-acct (core/account-by-codigo-agrupador db "601.02")
        prov-code (or provision-code "206.07")
        prov-acct (core/account-by-codigo-agrupador db prov-code)]
    (when-not aguinaldo-acct
      (throw (ex-info "Missing 601.02 account (Gratificación Anual)"
                      {:codigo-agrupador "601.02"})))
    (when-not prov-acct
      (throw (ex-info "Missing provision account"
                      {:codigo-agrupador prov-code
                       :hint "Pass :provision-code to override the default 206.07."})))
    (posting/build-transaction
     {:transaction {:kontor.transaction/journal journal
                    :kontor.transaction/effective-date date
                    :kontor.transaction/state :draft
                    :kontor.transaction/narration (or narration
                                               "Provisión mensual de aguinaldo")}
      :postings [{:kontor.posting/account aguinaldo-acct
                  :kontor.posting/amount amount
                  :kontor.posting/commodity commodity
                  :kontor.posting/narration "Dr 601.02 Aguinaldo (gasto)"}
                 {:kontor.posting/account prov-acct
                  :kontor.posting/amount  (.negate ^BigDecimal amount)
                  :kontor.posting/commodity commodity
                  :kontor.posting/narration "Cr 206.07 Provisión aguinaldo"}]})))
