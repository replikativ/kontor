(ns kontor.payroll-br.accrual
  "Brazilian payroll accrual helpers — the three load-bearing CPC 33 /
   IAS 19 employer obligations (ADR-081 §8). All three are
   *legally mandatory* employer liabilities that must sit on the
   balance sheet for any active workforce in Brazil:

   1. **Férias + 1/3 adicional** — 1/12 of monthly salary per month
      worked + 1/3 constitutional bonus. CLT art. 129+ + Constituição
      art. 7º XVII.
   2. **13º salário** — 1/12 of monthly salary per month worked.
      Lei 4.090/62 + Decreto 57.155/65.
   3. **Multa rescisória de 40% sobre FGTS** — 40% of the FGTS balance
      paid by the employer on involuntary termination. CF/88 art. 10
      ADCT + Lei 8.036/90.

   ## Substrate posture (ADR-081 §8)

   These are pure ADR-068 tx-data builders. The consumer transacts via
   `kontor.validation/transact-with-validation` (kernel gate stack
   fires). Engine-driven accruals (when the payroll engine emits a
   per-month accrual component as part of S-1200) route through the
   posting-builder's `:employer-side?` component path. This namespace
   is for the *consumer-computed* case (when no engine reports an
   accrual; the consumer's process schedules a periodic accrual tx).

   ## CPC 33 vs Receita Federal book-vs-tax timing

   CPC 33 (the BR IFRS-equivalent) requires accrual on the
   *Demonstração do Resultado do Exercício* (DRE) book ledger as
   services are rendered. The Receita Federal *Lucro Real*
   determination (IRPJ + CSLL) follows different timing rules for
   some employer benefits: férias are *deductible when paid* under
   RIR Art. 337, not when accrued, BUT the parallel-ledger split is
   policy-driven (companies on the Lucro Presumido regime use a fixed
   8% / 12% presumption, not the GL accrual). The substrate ships the
   book-side accrual; consumer decides via `:ledger` opt which ledger
   the accrual lands on (typical: `:br-ifrs` book; the *Lucro Real*
   tax-side adjustment is a separate consumer-driven entry).

   ## Formulae

   - **Férias accrual per pay-period**: `(monthly-salary / 12) * (1 + 1/3)`
     The 1/3 multiplier is the constitutional adicional (Constituição
     art. 7º XVII). Some employers also accrue the INSS-empregador
     pro-rata on the accrued férias — pass `:include-employer-charges?
     true` and the formula becomes `(monthly-salary / 12) * (1 + 1/3)
     * (1 + employer-charge-rate)`. Default charge-rate `0.28M`
     (20% CPP + 8% FGTS, a typical Mittelstand rate; consumer overrides).

   - **13º salário accrual per pay-period**: `monthly-salary / 12`.
     Some employers accrue INSS-empregador + FGTS pro-rata; same
     `:include-employer-charges?` toggle applies.

   - **Multa rescisória 40% FGTS accrual**: `fgts-balance * 0.40`. Only
     accrued for employees with non-trivial probability of involuntary
     termination — policy-driven. The substrate provides the formula;
     the consumer's HR process drives the trigger.

   Reference: ADR-081 §8."
  (:require [kontor.posting :as posting]
            [kontor.validation :as validation])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; HALF-EVEN rounding helper (BR R$ uses HALF-EVEN per kernel default)
;; ============================================================================

(defn round-half-even
  "Round a BigDecimal to `scale` digits with HALF-EVEN (banker's
   rounding). Default scale 2 (BRL centavos)."
  (^BigDecimal [x] (round-half-even x 2))
  (^BigDecimal [^BigDecimal x scale]
   (.setScale x ^long scale RoundingMode/HALF_EVEN)))

(def ^BigDecimal one-twelfth
  "BR accruals divide by 12 months — pre-computed BigDecimal."
  (.divide 1M 12M 10 RoundingMode/HALF_EVEN))

(def ^BigDecimal one-third
  "The 1/3 constitutional adicional on férias."
  (.divide 1M 3M 10 RoundingMode/HALF_EVEN))

(def ^BigDecimal severance-rate
  "Multa rescisória de 40% sobre FGTS — Constituição art. 10 ADCT."
  0.40M)

;; ============================================================================
;; Pure formula helpers (no transact)
;; ============================================================================

(defn ferias-accrual-amount
  "Compute the férias + 1/3 monthly accrual amount.

   Required:
     :monthly-salary       BigDecimal — the employee's monthly base salary

   Optional:
     :include-employer-charges?  bool (default false)
     :employer-charge-rate       BigDecimal (default 0.28M = 20% CPP + 8% FGTS)
     :scale                      integer (default 2)

   Returns BigDecimal (rounded HALF-EVEN to :scale digits)."
  ^BigDecimal [{:keys [monthly-salary include-employer-charges?
                       employer-charge-rate scale]
                :or {include-employer-charges? false
                     employer-charge-rate 0.28M
                     scale 2}}]
  (when-not monthly-salary
    (throw (ex-info ":monthly-salary required" {})))
  (let [base (.multiply ^BigDecimal monthly-salary one-twelfth)
        with-bonus (.multiply ^BigDecimal base
                              (.add 1M one-third))
        final (if include-employer-charges?
                (.multiply ^BigDecimal with-bonus
                           (.add 1M ^BigDecimal employer-charge-rate))
                with-bonus)]
    (round-half-even final scale)))

(defn thirteenth-salary-accrual-amount
  "Compute the 13º salário monthly accrual amount.

   Required:
     :monthly-salary  BigDecimal

   Optional:
     :include-employer-charges?  bool (default false)
     :employer-charge-rate       BigDecimal (default 0.28M)
     :scale                      integer (default 2)"
  ^BigDecimal [{:keys [monthly-salary include-employer-charges?
                       employer-charge-rate scale]
                :or {include-employer-charges? false
                     employer-charge-rate 0.28M
                     scale 2}}]
  (when-not monthly-salary
    (throw (ex-info ":monthly-salary required" {})))
  (let [base (.multiply ^BigDecimal monthly-salary one-twelfth)
        final (if include-employer-charges?
                (.multiply ^BigDecimal base
                           (.add 1M ^BigDecimal employer-charge-rate))
                base)]
    (round-half-even final scale)))

(defn severance-fgts-accrual-amount
  "Compute the multa rescisória de 40% sobre FGTS accrual amount.

   The trigger is *involuntary termination* (demissão sem justa causa);
   for the going-concern accrual, BR practice varies — some employers
   accrue a fraction of the 40% based on historical turnover rate, some
   don't accrue until termination is probable. The substrate ships the
   raw formula; consumer policy decides the trigger + the fraction.

   Required:
     :fgts-balance      BigDecimal — the employee's current FGTS balance

   Optional:
     :turnover-fraction BigDecimal (default 1.0M — full 40% accrual)
                        — pass e.g. 0.10M for a 10% historical
                        turnover assumption.
     :scale             integer (default 2)"
  ^BigDecimal [{:keys [fgts-balance turnover-fraction scale]
                :or {turnover-fraction 1.0M
                     scale 2}}]
  (when-not fgts-balance
    (throw (ex-info ":fgts-balance required" {})))
  (round-half-even
   (.multiply (.multiply ^BigDecimal fgts-balance severance-rate)
              ^BigDecimal turnover-fraction)
   scale))

;; ============================================================================
;; Férias + 1/3 accrual — out-of-band tx-data builder
;; ============================================================================

(defn ferias-accrual-tx-data
  "Pure tx-data builder for a per-pay-period férias + 1/3 accrual
   delta (CPC 33 / CLT art. 129+).

   Required keys:
     :ferias-expense-account   — ref to :account (Provisão férias expense)
     :ferias-liability-account — ref to :account (Provisão férias passivo)
     :amount                   — BigDecimal — the period-end accrual delta
                                 (signed: + = increase liability;
                                 - = decrease liability — e.g. reversal
                                 when employee takes vacation)
     :commodity                — :commodity ref (BRL)
     :ledger                   — ref to the BOOK ledger (typically the
                                 IFRS / DRE book ledger; Lucro Real
                                 tax-side adjustment is a separate
                                 consumer-driven entry)
     :journal                  — ref to :journal
     :effective-date           — #inst
     :tx-code                  — string for :transaction/external-id
     :pay-period               — ref to :pay-period (optional linkage)

   Optional keys:
     :narration                — string; default 'Provisão de férias + 1/3 (CPC 33)'
     :tx-tempid                — for cross-step composition (default
                                 'ferias-accrual-tx')

   Sign convention: +amount = Dr expense, Cr liability (the typical
   case — more férias earned than taken). -amount reverses."
  [{:keys [ferias-expense-account ferias-liability-account amount
           commodity ledger journal effective-date tx-code narration
           tx-tempid pay-period]
    :or {narration "Provisão de férias + 1/3 (CPC 33)"
         tx-tempid "ferias-accrual-tx"}}]
  (doseq [[k v] {:ferias-expense-account ferias-expense-account
                 :ferias-liability-account ferias-liability-account
                 :amount amount :commodity commodity :ledger ledger
                 :journal journal :effective-date effective-date
                 :tx-code tx-code}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (let [amt (round-half-even amount)
        postings [{:posting/account ferias-expense-account
                   :posting/amount amt
                   :posting/commodity commodity
                   :posting/ledger ledger
                   :posting/narration "Provisão de férias — despesa"}
                  {:posting/account ferias-liability-account
                   :posting/amount (.negate ^BigDecimal amt)
                   :posting/commodity commodity
                   :posting/ledger ledger
                   :posting/narration "Provisão de férias — passivo"}]
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

(defn ferias-accrual!
  "Side-effecting wrapper. Routes through `transact-with-validation`
   (ADR-068 — the kernel gate stack fires)."
  [conn opts]
  (validation/transact-with-validation
   conn (ferias-accrual-tx-data opts)))

;; ============================================================================
;; 13º salário accrual — out-of-band tx-data builder
;; ============================================================================

(defn thirteenth-salary-accrual-tx-data
  "Pure tx-data builder for a per-pay-period 13º salário accrual
   delta (CPC 33 / Lei 4.090/62).

   Required keys: same shape as `ferias-accrual-tx-data` but with
     :thirteenth-expense-account
     :thirteenth-liability-account

   Plus the standard journal / period / commodity / ledger / effective-
   date / tx-code envelope."
  [{:keys [thirteenth-expense-account thirteenth-liability-account
           amount commodity ledger journal effective-date tx-code
           narration tx-tempid pay-period]
    :or {narration "Provisão de 13º salário (CPC 33)"
         tx-tempid "thirteenth-accrual-tx"}}]
  (doseq [[k v] {:thirteenth-expense-account thirteenth-expense-account
                 :thirteenth-liability-account thirteenth-liability-account
                 :amount amount :commodity commodity :ledger ledger
                 :journal journal :effective-date effective-date
                 :tx-code tx-code}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (let [amt (round-half-even amount)
        postings [{:posting/account thirteenth-expense-account
                   :posting/amount amt
                   :posting/commodity commodity
                   :posting/ledger ledger
                   :posting/narration "Provisão 13º salário — despesa"}
                  {:posting/account thirteenth-liability-account
                   :posting/amount (.negate ^BigDecimal amt)
                   :posting/commodity commodity
                   :posting/ledger ledger
                   :posting/narration "Provisão 13º salário — passivo"}]
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

(defn thirteenth-salary-accrual!
  [conn opts]
  (validation/transact-with-validation
   conn (thirteenth-salary-accrual-tx-data opts)))

;; ============================================================================
;; Multa rescisória 40% sobre FGTS — out-of-band tx-data builder
;; ============================================================================

(defn severance-fgts-accrual-tx-data
  "Pure tx-data builder for a multa rescisória de 40% sobre FGTS
   accrual delta (Constituição art. 10 ADCT + Lei 8.036/90).

   Required keys: same shape with
     :severance-expense-account
     :severance-liability-account"
  [{:keys [severance-expense-account severance-liability-account
           amount commodity ledger journal effective-date tx-code
           narration tx-tempid pay-period]
    :or {narration "Provisão multa rescisória 40% FGTS"
         tx-tempid "severance-accrual-tx"}}]
  (doseq [[k v] {:severance-expense-account severance-expense-account
                 :severance-liability-account severance-liability-account
                 :amount amount :commodity commodity :ledger ledger
                 :journal journal :effective-date effective-date
                 :tx-code tx-code}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (let [amt (round-half-even amount)
        postings [{:posting/account severance-expense-account
                   :posting/amount amt
                   :posting/commodity commodity
                   :posting/ledger ledger
                   :posting/narration "Multa rescisória 40% FGTS — despesa"}
                  {:posting/account severance-liability-account
                   :posting/amount (.negate ^BigDecimal amt)
                   :posting/commodity commodity
                   :posting/ledger ledger
                   :posting/narration "Multa rescisória 40% FGTS — passivo"}]
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

(defn severance-fgts-accrual!
  [conn opts]
  (validation/transact-with-validation
   conn (severance-fgts-accrual-tx-data opts)))
