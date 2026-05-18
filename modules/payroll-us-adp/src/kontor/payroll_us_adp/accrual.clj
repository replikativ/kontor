(ns kontor.payroll-us-adp.accrual
  "Parallel-ledger accrual helpers — ASC 710 PTO and 401(k) employer
   match (ADR-077; note 83 §6).

   ## ASC 710 PTO accrual

   FASB ASC 710-10-25-1 requires accruing employees' rights to
   compensated future absences when:

     1. attributable to services already rendered,
     2. vests or accumulates,
     3. payment is probable,
     4. amount is reasonably estimable.

   Standard US private-sector vacation policy (vest at separation,
   carry forward partially) meets all four. 'Use it or lose it'
   policies that don't vest at separation typically do NOT meet (2)
   and are not accrued.

   Book-side journal entry (per pay-period delta, book ledger only):

       Dr  PTO Expense       (5040-ish)
       Cr  PTO Accrual       (2290)

   When the employee takes PTO, the wage-expense reverses the
   accrual (the GLI carries the PTO PAID line; we route it to
   :pto-paid → debit PTO Accrual, credit Wages Payable, by analogy
   to the standard wage row but with a different account):

       Dr  PTO Accrual       (2290)
       Cr  Wages Payable     (2100)

   Tax-side (IRC §461(h)): the tax ledger does NOT accrue PTO.
   Vacation liability is deductible only when the absence is taken
   (or via the narrow 'recurring item' exception when paid within
   8.5 months of year-end). The tax ledger sees only the actual
   PTO-paid postings; the accrual delta is a book-tax temporary
   difference the consumer's tax-prep engine handles.

   ## 401(k) employer-match accrual

   Book-side: accrue per pay-period as wages are earned (the match is
   earned with the underlying compensation). Per ASC 715 / ASC 710:

       Dr  401(k) Match Expense  (5310)
       Cr  401(k) Match Payable  (2210)

   Tax-side (IRC §404(a)(6)): the deduction is allowed when the
   contribution is actually made. A grace period of ~8.5 months
   (corporate due-date + extension) treats post-year-end contributions
   as 'deemed made on the last day of the preceding tax year' if:
     - paid by the §404(a)(6) deadline, AND
     - 'on account of' deferrals from compensation earned in the tax
       year, AND
     - the plan document treats it as a prior-year contribution.

   Substrate decision (ADR-077): we ship the book-side accrual at
   pay-period close. The tax-ledger recognition is a *late-cycle
   adjustment* the consumer's process explicitly requests (we provide
   `tax-recognize-401k-match-tx-data` as the primitive). This matches
   the ADR-021 parallel-ledger pattern: substrate ships the dual-post
   primitive, business policy chooses which postings to dual-post.

   ## Posture

   The accrual primitives are pure tx-data builders. The consumer
   transacts via `kontor.validation/transact-with-validation`
   (kernel's gate). PTO-policy machinery (`:employment/pto-policy`,
   `:employment/pto-balance-hours`) is deferred to a future C4 slice
   per note 79 §3; for C3 the accrual delta is consumer-computed
   (timesheet aggregation or manual journal-entry) and passed in as
   a BigDecimal. The substrate exposes the *shape*, the consumer
   provides the math."
  (:require [kontor.posting :as posting]
            [kontor.validation :as validation])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; HALF-EVEN rounding helper (W-2 reconciliation uses HALF-EVEN; note 83 §1)
;; ============================================================================

(defn round-half-even
  "Round a BigDecimal to `scale` digits with HALF-EVEN (banker's
   rounding). Default scale 2 (US cents)."
  (^BigDecimal [x] (round-half-even x 2))
  (^BigDecimal [^BigDecimal x scale]
   (.setScale x ^long scale RoundingMode/HALF_EVEN)))

;; ============================================================================
;; ASC 710 PTO accrual
;; ============================================================================

(defn asc-710-pto-accrual-tx-data
  "Pure tx-data builder for a per-pay-period PTO accrual delta.

   Required keys:
     :pto-expense-account  — ref to :account (e.g. 5040 Wages – PTO)
     :pto-accrual-account  — ref to :account (e.g. 2290 PTO Accrual)
     :amount               — BigDecimal — the period-end accrual delta
                             (signed: + = increase liability;
                             - = decrease liability — e.g. if the
                             period's vesting was less than the
                             estimate, the consumer can post a
                             negative delta)
     :commodity            — :commodity ref (USD)
     :ledger               — ref to the BOOK ledger (:us-gaap typically;
                             we do NOT post this to :us-tax per
                             IRC §461(h))
     :journal              — ref to :journal
     :effective-date       — #inst
     :tx-code              — string for :transaction/external-id
     :pay-period           — ref to :pay-period (linkage)

   Optional keys:
     :narration            — string; default 'ASC 710 PTO accrual'
     :tx-tempid            — for cross-step composition

   Sign convention: +amount = Dr expense, Cr liability (the typical
   case — more PTO earned than taken). -amount reverses (Dr liability,
   Cr expense, e.g. estimate over-shoot)."
  [{:keys [pto-expense-account pto-accrual-account amount commodity
           ledger journal effective-date tx-code narration tx-tempid
           pay-period]
    :or {narration "ASC 710 PTO accrual"
         tx-tempid "pto-accrual-tx"}}]
  (doseq [[k v] {:pto-expense-account pto-expense-account
                 :pto-accrual-account pto-accrual-account
                 :amount amount :commodity commodity :ledger ledger
                 :journal journal :effective-date effective-date
                 :tx-code tx-code}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (let [amt (round-half-even amount)
        postings [{:posting/account pto-expense-account
                   :posting/amount amt
                   :posting/commodity commodity
                   :posting/ledger ledger
                   :posting/narration "PTO expense accrual"}
                  {:posting/account pto-accrual-account
                   :posting/amount (.negate ^BigDecimal amt)
                   :posting/commodity commodity
                   :posting/ledger ledger
                   :posting/narration "PTO liability accrual"}]
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

(defn asc-710-pto-accrual!
  "Side-effecting wrapper. Routes through `transact-with-validation`
   (ADR-068 — the kernel gate stack fires)."
  [conn opts]
  (validation/transact-with-validation
   conn (asc-710-pto-accrual-tx-data opts)))

;; ============================================================================
;; 401(k) employer-match book-ledger accrual (per pay-period)
;; ============================================================================

(defn er-401k-match-accrual-tx-data
  "Book-ledger 401(k) employer-match accrual for a pay-period.

   This is intentionally separate from the GLI-imported wage-period
   posting because the match is *earned* with the underlying wages
   but typically *paid* at a different cadence (some quarterly, some
   annually, some at year-end with §404(a)(6) grace-period timing).
   The GLI's `ER 401K MATCH` line, if present, IS the match payment;
   this fn produces the accrual delta independently.

   Required keys: same shape as `asc-710-pto-accrual-tx-data` but with
     :match-expense-account  — 5310 Employer 401(k) Match
     :match-payable-account  — 2210 401(k) Employer Match Payable
   plus the standard journal / period / commodity / ledger / effective-
   date / tx-code envelope.

   Per ADR-077 + note 83 §6.2 the book-ledger postings ALWAYS land.
   Tax-ledger recognition is a separate, late-cycle, consumer-driven
   adjustment — see `tax-recognize-401k-match-tx-data`."
  [{:keys [match-expense-account match-payable-account amount commodity
           ledger journal effective-date tx-code narration tx-tempid
           pay-period]
    :or {narration "401(k) employer match accrual (book)"
         tx-tempid "match-accrual-tx"}}]
  (doseq [[k v] {:match-expense-account match-expense-account
                 :match-payable-account match-payable-account
                 :amount amount :commodity commodity :ledger ledger
                 :journal journal :effective-date effective-date
                 :tx-code tx-code}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (let [amt (round-half-even amount)
        postings [{:posting/account match-expense-account
                   :posting/amount amt
                   :posting/commodity commodity
                   :posting/ledger ledger
                   :posting/narration "401(k) match expense"}
                  {:posting/account match-payable-account
                   :posting/amount (.negate ^BigDecimal amt)
                   :posting/commodity commodity
                   :posting/ledger ledger
                   :posting/narration "401(k) match payable"}]
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

(defn er-401k-match-accrual!
  [conn opts]
  (validation/transact-with-validation
   conn (er-401k-match-accrual-tx-data opts)))

;; ============================================================================
;; Tax-ledger recognition of 401(k) match — late-cycle, consumer-driven
;; ============================================================================

(defn tax-recognize-401k-match-tx-data
  "Produce the tax-ledger leg of a 401(k) match recognition. Used by
   the consumer's year-end / quarterly process when IRC §404(a)(6)
   conditions are satisfied:

     - paid by the corporate-return due-date (+ extensions) for the
       tax year being recognized,
     - 'on account of' deferrals from wages earned in that tax year,
     - the plan document treats the contribution as a prior-year one.

   The substrate does NOT make this determination — see note 83 §10
   item 3: the call has plan-document-specific inputs kontor can't
   see. The consumer's tax-prep engine answers; we provide the
   primitive to record the answer.

   Sign convention: same as the book accrual. The consumer typically
   passes the same amount as the book accrual (so book = tax after
   recognition); the result is a tax-ledger Dr expense / Cr payable
   that mirrors the book accrual."
  [{:keys [match-expense-account match-payable-account amount commodity
           tax-ledger journal effective-date tx-code narration tx-tempid]
    :or {narration "401(k) match tax-ledger recognition (IRC §404(a)(6))"
         tx-tempid "match-tax-tx"}}]
  (doseq [[k v] {:match-expense-account match-expense-account
                 :match-payable-account match-payable-account
                 :amount amount :commodity commodity :tax-ledger tax-ledger
                 :journal journal :effective-date effective-date
                 :tx-code tx-code}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (let [amt (round-half-even amount)
        postings [{:posting/account match-expense-account
                   :posting/amount amt
                   :posting/commodity commodity
                   :posting/ledger tax-ledger
                   :posting/narration "401(k) match expense (tax)"}
                  {:posting/account match-payable-account
                   :posting/amount (.negate ^BigDecimal amt)
                   :posting/commodity commodity
                   :posting/ledger tax-ledger
                   :posting/narration "401(k) match payable (tax)"}]
        tx-input {:tx-tempid tx-tempid
                  :transaction {:transaction/external-id tx-code
                                :transaction/effective-date effective-date
                                :transaction/narration narration
                                :transaction/journal journal
                                :transaction/state :draft}
                  :postings postings}]
    (posting/build-transaction tx-input)))

(defn tax-recognize-401k-match!
  [conn opts]
  (validation/transact-with-validation
   conn (tax-recognize-401k-match-tx-data opts)))
