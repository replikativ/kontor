(ns kontor.payroll-in.accrual
  "IN-specific accrual primitives — Payment of Bonus Act 1965 minimum
   bonus accrual + Ind AS 19 short-term leave encashment accrual.
   Gratuity actuarial valuation is OUT OF SCOPE for v1 (note 79 §5.3
   deferral — Ind AS 19 defined-benefit requires Heubeck-style
   actuarial inputs the consumer supplies).

   ## Payment of Bonus Act 1965 (note 79 §5.3)

   Mandates a minimum bonus of 8.33% of basic wages (and maximum 20%
   depending on allocable surplus) for employees earning ≤ ₹21,000
   per month basic+DA. Accrued per pay-period; paid annually (usually
   by 30-Nov for the prior accounting year per Sec 19).

   Book entry per period:

       Dr  Bonus accrual expense       (690200 in starter)
       Cr  Bonus payable               (323600 in starter)

   Both employees on / above the wage threshold and employees below
   accrue bonus — kontor doesn't decide eligibility (engine /
   consumer responsibility). We provide the primitive; consumer
   supplies the amount.

   ## Ind AS 19 leave encashment (short-term)

   Compensated absences vesting within 12 months are 'short-term
   employee benefits' under Ind AS 19 para 11; accrued per pay-period
   as services are rendered. Long-term portion (vesting > 12 months)
   uses the projected unit credit method — out of substrate scope.

   Book entry per period:

       Dr  Leave encashment accrual expense   (690300 in starter)
       Cr  Leave encashment liability         (323700 in starter)

   ## Gratuity (Payment of Gratuity Act 1972 + Ind AS 19)

   15 days of last drawn wages per year of completed service, capped
   at ₹20 lakhs (Sec 4), payable on separation after 5 years (or
   immediately on death / disablement).

   v1 SCOPE: OUT (consumer-supplied). Ind AS 19 mandates actuarial
   valuation (Heubeck-style or equivalent — Mortality + Withdrawal +
   Salary Escalation + Discount rate). The substrate exposes the
   shape via the `:employer-gratuity-accrual` wage-type kind (so
   engines that pre-compute it can post via the standard path) AND
   a thin builder below (`gratuity-accrual-tx-data`) for the
   pure-accounting case where the consumer has the number already.

   ## Posture

   Pure tx-data builders (ADR-068). The `!` wrappers route through
   `kontor.validation/transact-with-validation` so the kernel gate
   stack fires.

   Reference: doc/research/79-hr-payroll-stage-r-plan.md §5.3,
   modules/payroll-us-adp/src/kontor/payroll_us_adp/accrual.clj
   (structural template — same pure-tx-data + ! wrapper shape)."
  (:require [kontor.posting :as posting]
            [kontor.validation :as validation])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; HALF-EVEN rounding helper (BigDecimal discipline per CLAUDE.md)
;; ============================================================================

(defn round-half-even
  "Round a BigDecimal to `scale` digits with HALF-EVEN. Default 2
   (Indian Rupee paise)."
  (^BigDecimal [x] (round-half-even x 2))
  (^BigDecimal [^BigDecimal x scale]
   (.setScale x ^long scale RoundingMode/HALF_EVEN)))

;; ============================================================================
;; Bonus Act 1965 — accrual
;; ============================================================================

(defn bonus-accrual-tx-data
  "Pure tx-data builder for a per-pay-period bonus accrual delta under
   the Payment of Bonus Act 1965.

   Required keys:
     :bonus-expense-account  — ref to :account (690200 in starter)
     :bonus-payable-account  — ref to :account (323600 in starter)
     :amount                 — BigDecimal — the period accrual delta.
                               Sign convention: +amount Dr expense, Cr
                               liability (typical case).
     :commodity              — :commodity ref (INR)
     :ledger                 — ref to :ledger
     :journal                — ref to :journal
     :effective-date         — #inst
     :tx-code                — string for :kontor.transaction/external-id

   Optional keys:
     :narration              — default 'Payment of Bonus Act 1965 accrual'
     :tx-tempid              — default 'bonus-accrual-tx'
     :pay-period             — ref to :pay-period (linkage)

   Returns a vector of tx-data ready for transact-with-validation."
  [{:keys [bonus-expense-account bonus-payable-account amount commodity
           ledger journal effective-date tx-code narration tx-tempid
           pay-period]
    :or {narration "Payment of Bonus Act 1965 accrual"
         tx-tempid "bonus-accrual-tx"}}]
  (doseq [[k v] {:bonus-expense-account bonus-expense-account
                 :bonus-payable-account bonus-payable-account
                 :amount amount :commodity commodity :ledger ledger
                 :journal journal :effective-date effective-date
                 :tx-code tx-code}]
    (when (nil? v)
      (throw (ex-info (str (subs (str k) 1) " required for bonus-accrual-tx-data") {}))))
  (let [amt (round-half-even amount)
        postings [{:kontor.posting/account bonus-expense-account
                   :kontor.posting/amount amt
                   :kontor.posting/commodity commodity
                   :kontor.posting/ledger ledger
                   :kontor.posting/narration "Bonus expense accrual (Bonus Act 1965)"}
                  {:kontor.posting/account bonus-payable-account
                   :kontor.posting/amount (.negate ^BigDecimal amt)
                   :kontor.posting/commodity commodity
                   :kontor.posting/ledger ledger
                   :kontor.posting/narration "Bonus liability accrual (Bonus Act 1965)"}]
        tx-input {:tx-tempid tx-tempid
                  :transaction
                  (cond-> {:kontor.transaction/external-id tx-code
                           :kontor.transaction/effective-date effective-date
                           :kontor.transaction/narration narration
                           :kontor.transaction/journal journal
                           :kontor.transaction/state :draft}
                    pay-period
                    (assoc :kontor.transaction/source (str "pay-period:" pay-period)))
                  :postings postings}]
    (posting/build-transaction tx-input)))

(defn bonus-accrual!
  "Side-effecting wrapper. Routes through transact-with-validation
   so the kernel gate stack fires (ADR-068)."
  [conn opts]
  (validation/transact-with-validation
   conn (bonus-accrual-tx-data opts)))

;; ============================================================================
;; Leave encashment accrual (Ind AS 19 short-term)
;; ============================================================================

(defn leave-encashment-accrual-tx-data
  "Pure tx-data builder for a per-pay-period leave-encashment accrual.

   Same shape as bonus-accrual-tx-data but with:
     :leave-expense-account    — 690300 in starter
     :leave-liability-account  — 323700 in starter"
  [{:keys [leave-expense-account leave-liability-account amount commodity
           ledger journal effective-date tx-code narration tx-tempid
           pay-period]
    :or {narration "Ind AS 19 leave-encashment accrual (short-term)"
         tx-tempid "leave-accrual-tx"}}]
  (doseq [[k v] {:leave-expense-account leave-expense-account
                 :leave-liability-account leave-liability-account
                 :amount amount :commodity commodity :ledger ledger
                 :journal journal :effective-date effective-date
                 :tx-code tx-code}]
    (when (nil? v)
      (throw (ex-info (str (subs (str k) 1) " required for leave-encashment-accrual-tx-data") {}))))
  (let [amt (round-half-even amount)
        postings [{:kontor.posting/account leave-expense-account
                   :kontor.posting/amount amt
                   :kontor.posting/commodity commodity
                   :kontor.posting/ledger ledger
                   :kontor.posting/narration "Leave encashment expense"}
                  {:kontor.posting/account leave-liability-account
                   :kontor.posting/amount (.negate ^BigDecimal amt)
                   :kontor.posting/commodity commodity
                   :kontor.posting/ledger ledger
                   :kontor.posting/narration "Leave encashment liability"}]
        tx-input {:tx-tempid tx-tempid
                  :transaction
                  (cond-> {:kontor.transaction/external-id tx-code
                           :kontor.transaction/effective-date effective-date
                           :kontor.transaction/narration narration
                           :kontor.transaction/journal journal
                           :kontor.transaction/state :draft}
                    pay-period
                    (assoc :kontor.transaction/source (str "pay-period:" pay-period)))
                  :postings postings}]
    (posting/build-transaction tx-input)))

(defn leave-encashment-accrual!
  [conn opts]
  (validation/transact-with-validation
   conn (leave-encashment-accrual-tx-data opts)))

;; ============================================================================
;; Gratuity accrual (Ind AS 19 defined-benefit) — CONSUMER-DRIVEN
;; ============================================================================

(defn gratuity-accrual-tx-data
  "Thin pure tx-data builder for a gratuity accrual the consumer has
   pre-computed (typically via an actuarial valuation per Ind AS 19
   defined-benefit — Heubeck-style mortality + withdrawal + salary
   escalation + discount rate). v1 SCOPE: substrate provides the
   plumbing; the actuarial math is OUT OF SCOPE.

   Required keys: same as bonus-accrual-tx-data, with:
     :gratuity-expense-account  — 690500 in starter
     :gratuity-liability-account — 323800 in starter
     :amount                     — BigDecimal (actuarial delta)

   Optional:
     :audit-doc-code             — string identifier for an :audit-doc
                                   carrying the actuary's valuation
                                   report (typically created separately
                                   with :kontor.audit-doc/category
                                   :payroll-filing or :compliance-
                                   attestation per the consumer's
                                   classification). The string is
                                   embedded in :kontor.transaction/source so
                                   the audit chain ties the entry to
                                   the valuation document.

   Sign convention: +amount = Dr expense, Cr liability (typical
   accrual increase). Negative amount reverses the accrual."
  [{:keys [gratuity-expense-account gratuity-liability-account
           amount commodity ledger journal effective-date tx-code
           narration tx-tempid pay-period audit-doc-code]
    :or {narration "Ind AS 19 gratuity accrual (consumer-supplied actuarial)"
         tx-tempid "gratuity-accrual-tx"}}]
  (doseq [[k v] {:gratuity-expense-account gratuity-expense-account
                 :gratuity-liability-account gratuity-liability-account
                 :amount amount :commodity commodity :ledger ledger
                 :journal journal :effective-date effective-date
                 :tx-code tx-code}]
    (when (nil? v)
      (throw (ex-info (str (subs (str k) 1) " required for gratuity-accrual-tx-data") {}))))
  (let [amt (round-half-even amount)
        postings [{:kontor.posting/account gratuity-expense-account
                   :kontor.posting/amount amt
                   :kontor.posting/commodity commodity
                   :kontor.posting/ledger ledger
                   :kontor.posting/narration "Gratuity expense (Ind AS 19; consumer actuarial)"}
                  {:kontor.posting/account gratuity-liability-account
                   :kontor.posting/amount (.negate ^BigDecimal amt)
                   :kontor.posting/commodity commodity
                   :kontor.posting/ledger ledger
                   :kontor.posting/narration "Gratuity liability (Ind AS 19)"}]
        tx-input {:tx-tempid tx-tempid
                  :transaction
                  (cond-> {:kontor.transaction/external-id tx-code
                           :kontor.transaction/effective-date effective-date
                           :kontor.transaction/narration narration
                           :kontor.transaction/journal journal
                           :kontor.transaction/state :draft}
                    (or pay-period audit-doc-code)
                    (assoc :kontor.transaction/source
                           (str (when pay-period
                                  (str "pay-period:" pay-period))
                                (when (and pay-period audit-doc-code) "; ")
                                (when audit-doc-code
                                  (str "audit-doc:" audit-doc-code)))))
                  :postings postings}]
    (posting/build-transaction tx-input)))

(defn gratuity-accrual!
  [conn opts]
  (validation/transact-with-validation
   conn (gratuity-accrual-tx-data opts)))
