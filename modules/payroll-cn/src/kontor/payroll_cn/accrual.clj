(ns kontor.payroll-cn.accrual
  "Annual-bonus (年终奖) accrual primitive — Stage R C11 / ADR-085.

   Per note 87 §2.4: until 2027-12-31 employees may elect between the
   单独计税 (separate) or 并入综合所得 (combined) IIT treatment for
   the year-end bonus. The election affects WHEN the IIT is recognized
   (per-month under combined, year-end under separate) but NOT WHEN
   the accounting cost is recognized — the bonus is earned with the
   underlying wages and accrues monthly per CAS 9 / 财会〔2014〕8号.

   ## Book-side journal entry (per pay-period delta)

       DR  6602/5603/6601 Wage expense       (department-specific)
           CR  2211.01 应付职工薪酬-工资-年终奖累计

   The credit account is a sub-bucket of 2211.01 应付职工薪酬 — the
   `:cn-payroll-bonus-payable` tag in `kontor.payroll-cn.wage-types`.

   ## Tax-side recognition

   Unlike the US 401(k) match (IRC §404(a)(6)) or DE HGB §249 PTO
   accrual, the CN tax treatment of the bonus accrual depends on the
   IIT election:

   - **Separate (单独计税)** — the bonus is treated as a distinct
     payment for IIT purposes. Book + tax timing align: both recognize
     the bonus when paid (year-end). The MONTHLY accrual is book-only;
     the tax ledger sees nothing until the bonus is paid.

   - **Combined (并入综合所得)** — the bonus is folded into 综合所得
     monthly. Book + tax timing also align IF the engine includes the
     accrual in the monthly cumulative-method computation. Most
     engines do NOT do this (the accrual is consumer-recorded in
     kontor, not in the engine), so the tax ledger continues to see
     monthly base wages and the bonus shows up at year-end pay-out.

   For v1 we ship the book-side primitive. Tax-ledger recognition
   follows the same pattern as US ASC 710 PTO: the tax ledger only
   sees the actual pay-out posting (when the engine emits the
   `:annual-bonus` component), so the accrual lands on `:cn-cas-book`
   only. Consumers wanting a Steuerbilanz-style split per ADR-021
   thread `:ledger <cn-cas-book>` through (the default kontor pattern;
   only one CN book ledger in v1).

   ## Posture

   The accrual primitive is a pure tx-data builder (ADR-068). The
   consumer transacts via `kontor.validation/transact-with-validation`
   (kernel's gate). The amount + the basis (monthly 1/12 of expected,
   or whatever formula the consumer chooses) is consumer-computed —
   the substrate exposes the SHAPE, the consumer provides the math.

   See doc/research/87-cn-payroll-research-before.md §2.4."
  (:require [kontor.posting :as posting]
            [kontor.validation :as validation])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; HALF-EVEN rounding helper (CAS uses HALF-EVEN per kernel default)
;; ============================================================================

(defn round-half-even
  "Round a BigDecimal to `scale` digits with HALF-EVEN (banker's
   rounding). Default scale 2 (CNY 分)."
  (^BigDecimal [x] (round-half-even x 2))
  (^BigDecimal [^BigDecimal x scale]
   (.setScale x ^long scale RoundingMode/HALF_EVEN)))

;; ============================================================================
;; Annual-bonus accrual
;; ============================================================================

(defn annual-bonus-accrual-tx-data
  "Pure tx-data builder for a per-pay-period 年终奖 accrual delta.

   Required keys:
     :wage-expense-account     — ref to :account (e.g. 5603 管理费用-工资)
     :bonus-payable-account    — ref to :account (e.g. 2211.01.99
                                  应付职工薪酬-工资-年终奖累计 sub-bucket)
     :amount                   — BigDecimal — the period-end accrual delta
                                  (signed: + = increase liability;
                                  - = decrease liability — e.g. if the
                                  expected year-end payout is revised
                                  downward, the consumer can post a
                                  negative delta)
     :commodity                — :commodity ref (CNY)
     :ledger                   — ref to the BOOK ledger (:cn-cas-book
                                  typically)
     :journal                  — ref to :journal
     :effective-date           — #inst
     :tx-code                  — string for :transaction/external-id

   Optional keys:
     :narration                — string; default '年终奖累计 (Annual bonus accrual)'
     :tx-tempid                — for cross-step composition
     :pay-period               — ref to :pay-period (linkage)

   Sign convention: +amount = Dr expense, Cr liability (the typical
   case — monthly 1/12 of expected bonus accrues). -amount reverses
   (Dr liability, Cr expense, e.g. estimate over-shoot)."
  [{:keys [wage-expense-account bonus-payable-account amount commodity
           ledger journal effective-date tx-code narration tx-tempid
           pay-period]
    :or {narration "年终奖累计 (Annual bonus accrual)"
         tx-tempid "annual-bonus-accrual-tx"}}]
  (doseq [[k v] {:wage-expense-account wage-expense-account
                 :bonus-payable-account bonus-payable-account
                 :amount amount :commodity commodity :ledger ledger
                 :journal journal :effective-date effective-date
                 :tx-code tx-code}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (let [amt (round-half-even amount)
        postings [{:posting/account wage-expense-account
                   :posting/amount amt
                   :posting/commodity commodity
                   :posting/ledger ledger
                   :posting/narration "年终奖累计费用 (Bonus accrual expense)"}
                  {:posting/account bonus-payable-account
                   :posting/amount (.negate ^BigDecimal amt)
                   :posting/commodity commodity
                   :posting/ledger ledger
                   :posting/narration "应付年终奖 (Bonus accrual payable)"}]
        tx-input {:tx-tempid tx-tempid
                  :transaction (cond-> {:transaction/external-id tx-code
                                        :transaction/effective-date effective-date
                                        :transaction/narration narration
                                        :transaction/journal journal
                                        :transaction/state :draft}
                                 pay-period
                                 (assoc :transaction/source
                                        (str "pay-period:" pay-period)))
                  :postings postings}]
    (posting/build-transaction tx-input)))

(defn annual-bonus-accrual!
  "Side-effecting wrapper. Routes through `transact-with-validation`
   (ADR-068 — the kernel gate stack fires)."
  [conn opts]
  (validation/transact-with-validation
   conn (annual-bonus-accrual-tx-data opts)))

;; ============================================================================
;; Convenience: 1/12 monthly accrual amount
;; ============================================================================

(defn one-twelfth
  "Compute the monthly 1/12 accrual amount for a given expected
   year-end bonus. Returns a BigDecimal rounded to CNY 分 (2 decimals)
   with HALF-EVEN. Convenience for the typical case where the consumer
   accrues 1/12 of the expected bonus each month."
  ^BigDecimal [^BigDecimal expected-bonus]
  (round-half-even (.divide ^BigDecimal expected-bonus
                            12M
                            4
                            RoundingMode/HALF_EVEN)
                   2))
