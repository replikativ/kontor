(ns kontor.payroll-jp.accrual
  "JP-specific payroll accrual primitives (ADR-084 §6).

   Two accrual families ship in v1:

   1. **賞与引当金 (Shoyo Hikiatekin / bonus accrual)** —
      JP bonuses are large (1-3 months' salary), paid twice yearly
      (夏季 summer bonus around June-July; 冬季 winter bonus
      around December). J-GAAP matching requires accruing the
      expense over the periods that earned it, not booking it as
      a single hit in the payout month. The substrate ships a
      monthly delta primitive: per pay-period, DR 賞与引当金
      繰入額 expense, CR 賞与引当金 liability for the accrual
      delta the consumer's policy determined (typically annual
      target / 6 months for a semi-annual payout cycle).

   2. **4-bucket statutory-insurance employer-side accrual** —
      JP SI cash payment lags the wage-earning period: the
      employer's SI premium for month M is typically paid to
      日本年金機構 (Nihon Nenkin Kiko) at the end of month M+1.
      The substrate ships per-bucket accrual primitives so consumers
      can recognize the employer's SI expense in the period the
      wages were earned (J-GAAP matching). Each bucket gets its
      own primitive so cash-payment reconciliation is per-bucket.

   ## Out-of-scope (deferred to consumer / future companion)

   - **退職給付引当金 (Taishoku Kyufu Hikiatekin / retirement-
     benefit provision)** — ASBJ's Statement No. 26 (Accounting
     Standard for Retirement Benefits) requires actuarial
     valuation: 退職給付債務 (PBO equivalent) ÷ 期待運用収益
     × discount rate. Out of substrate scope; a future
     `kontor-pension-actuary-jp` companion integrating
     退職給付に係る会計基準 actuarial tables would land it.

   ## ADR-068 builder posture

   Each primitive is a pure `*-tx-data` builder that returns a
   tx-data vector ready for `transact-with-validation` (the
   kernel's gate stack — legal-hold + period-lock + status-machine
   + datalog invariants — fires inside).

   The `!` wrappers route through `kontor.validation/transact-with-
   validation` per ADR-068.

   Reference: ADR-084 §6."
  (:require [kontor.posting :as posting]
            [kontor.validation :as validation])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- to-whole-yen
  "Round to whole yen (HALF-EVEN). JPY has precision 0 per ADR-013."
  ^BigDecimal [^BigDecimal x]
  (.setScale x 0 RoundingMode/HALF_EVEN))

(defn- require-keys! [opts required]
  (doseq [k required]
    (when (nil? (get opts k))
      (throw (ex-info (str (subs (str k) 1) " required")
                      {:missing k})))))

;; ============================================================================
;; 賞与引当金 — bonus accrual
;; ============================================================================

(defn bonus-accrual-amount
  "Compute the per-pay-period bonus-accrual delta.

   Inputs:
     :annual-bonus-target   BigDecimal — total bonus expected for
                            the accrual cycle (typically a multiple
                            of monthly salary).
     :periods-in-cycle      integer — typically 6 months for a
                            semi-annual payout (二期).
     :periods-elapsed       integer (default 0) — how many periods
                            have already been accrued. Allows
                            consumers to reset / true-up mid-cycle.
     :true-up-delta         BigDecimal (default 0M) — additional
                            adjustment (e.g. when a salary raise
                            mid-cycle requires bumping the target).

   Returns a BigDecimal (rounded to whole yen)."
  [{:keys [annual-bonus-target periods-in-cycle periods-elapsed
           true-up-delta]
    :or {periods-elapsed 0 true-up-delta 0M}}]
  (require-keys! {:annual-bonus-target annual-bonus-target
                  :periods-in-cycle periods-in-cycle}
                 [:annual-bonus-target :periods-in-cycle])
  (when (zero? periods-in-cycle)
    (throw (ex-info ":periods-in-cycle cannot be zero" {})))
  (let [per-period (.divide ^BigDecimal annual-bonus-target
                            (BigDecimal/valueOf (long periods-in-cycle))
                            ^RoundingMode RoundingMode/HALF_EVEN)
        delta (.add ^BigDecimal per-period ^BigDecimal true-up-delta)]
    (to-whole-yen delta)))

(defn bonus-accrual-tx-data
  "Pure tx-data builder for a per-pay-period 賞与引当金 accrual delta.

   The Japanese matching-principle convention is to accrue bonus
   expense over the periods that earn it (typically the 6 months
   leading up to a semi-annual payout). At payout time the booked
   bonus expense reverses against this liability — see
   `bonus-payout-reversal-tx-data`.

   Required keys:
     :bonus-accrual-expense-account — :account ref (e.g. 614000
                                       賞与引当金繰入額)
     :bonus-accrual-liability-account — :account ref (e.g. 217000
                                         賞与引当金)
     :amount                        — BigDecimal — the accrual delta
                                       for this pay-period (signed:
                                       + = increase liability;
                                       - = decrease / reversal)
     :commodity                     — :commodity ref (JPY)
     :journal                       — :journal ref
     :effective-date                — #inst
     :tx-code                       — :kontor.transaction/external-id

   Optional:
     :ledger                        — :ledger ref (book ledger)
     :narration                     — string; default '賞与引当金繰入'
     :tx-tempid                     — default 'bonus-accrual-tx'
     :pay-period                    — :pay-period ref (linkage)

   Sign convention: +amount = Dr expense, Cr liability (the typical
   monthly accrual). -amount reverses (e.g. true-up undershoot)."
  [{:keys [bonus-accrual-expense-account bonus-accrual-liability-account
           amount commodity journal effective-date tx-code
           ledger narration tx-tempid pay-period]
    :or {narration "賞与引当金繰入"
         tx-tempid "bonus-accrual-tx"}}]
  (require-keys! {:bonus-accrual-expense-account bonus-accrual-expense-account
                  :bonus-accrual-liability-account bonus-accrual-liability-account
                  :amount amount :commodity commodity
                  :journal journal :effective-date effective-date
                  :tx-code tx-code}
                 [:bonus-accrual-expense-account
                  :bonus-accrual-liability-account
                  :amount :commodity :journal :effective-date :tx-code])
  (let [amt (to-whole-yen amount)
        postings [(cond-> {:kontor.posting/account bonus-accrual-expense-account
                           :kontor.posting/amount amt
                           :kontor.posting/commodity commodity
                           :kontor.posting/narration "賞与引当金繰入額 (expense)"}
                    ledger (assoc :kontor.posting/ledger ledger))
                  (cond-> {:kontor.posting/account bonus-accrual-liability-account
                           :kontor.posting/amount (.negate ^BigDecimal amt)
                           :kontor.posting/commodity commodity
                           :kontor.posting/narration "賞与引当金 (liability)"}
                    ledger (assoc :kontor.posting/ledger ledger))]
        tx-input {:tx-tempid tx-tempid
                  :transaction (cond-> {:kontor.transaction/external-id tx-code
                                        :kontor.transaction/effective-date effective-date
                                        :kontor.transaction/narration narration
                                        :kontor.transaction/journal journal
                                        :kontor.transaction/state :draft}
                                 pay-period
                                 (assoc :kontor.transaction/source
                                        (str "pay-period:" pay-period)))
                  :postings postings}]
    (posting/build-transaction tx-input)))

(defn bonus-accrual!
  "Side-effecting wrapper. Routes through transact-with-validation
   (ADR-068 — the kernel gate stack fires)."
  [conn opts]
  (validation/transact-with-validation
   conn (bonus-accrual-tx-data opts)))

;; ============================================================================
;; 4-bucket SI employer-side accrual (per-bucket primitives)
;; ============================================================================
;;
;; Each SI bucket — 健保 / 厚生年金 / 雇用保険 / 介護保険 — has its
;; own employer-paid premium with its own (and different) cash-
;; payment schedule. The substrate ships ONE primitive per bucket
;; so consumers can call exactly the buckets that apply (介護保険
;; is age-40-gated; 雇用保険 is industry-rate-variant).
;;
;; Each primitive emits the same posting shape: DR 法定福利費 expense,
;; CR per-bucket 預り金 liability. The expense account is configurable
;; (consumer may want one consolidated 法定福利費 account, or four
;; sub-accounts).

(defn- si-accrual-tx-data
  "Internal shape used by all four SI-accrual primitives.

   Required keys: same as bonus-accrual-tx-data but with named
   expense + liability accounts.

   The narration carries the bucket name + 'employer-side' so audit
   trail readability holds."
  [bucket-kanji {:keys [si-expense-account si-liability-account
                        amount commodity journal effective-date tx-code
                        ledger narration tx-tempid pay-period]
                 :or {tx-tempid "si-accrual-tx"}}]
  (require-keys! {:si-expense-account si-expense-account
                  :si-liability-account si-liability-account
                  :amount amount :commodity commodity
                  :journal journal :effective-date effective-date
                  :tx-code tx-code}
                 [:si-expense-account :si-liability-account
                  :amount :commodity :journal :effective-date :tx-code])
  (let [amt (to-whole-yen amount)
        narr (or narration (str bucket-kanji " — 法定福利費 (事業主負担)"))
        postings [(cond-> {:kontor.posting/account si-expense-account
                           :kontor.posting/amount amt
                           :kontor.posting/commodity commodity
                           :kontor.posting/narration (str "法定福利費: " bucket-kanji)}
                    ledger (assoc :kontor.posting/ledger ledger))
                  (cond-> {:kontor.posting/account si-liability-account
                           :kontor.posting/amount (.negate ^BigDecimal amt)
                           :kontor.posting/commodity commodity
                           :kontor.posting/narration (str "預り金: " bucket-kanji)}
                    ledger (assoc :kontor.posting/ledger ledger))]
        tx-input {:tx-tempid tx-tempid
                  :transaction (cond-> {:kontor.transaction/external-id tx-code
                                        :kontor.transaction/effective-date effective-date
                                        :kontor.transaction/narration narr
                                        :kontor.transaction/journal journal
                                        :kontor.transaction/state :draft}
                                 pay-period
                                 (assoc :kontor.transaction/source
                                        (str "pay-period:" pay-period)))
                  :postings postings}]
    (posting/build-transaction tx-input)))

(defn health-insurance-accrual-tx-data
  "Employer-side 健康保険 (Kenko Hoken) accrual for a pay-period.

   Required keys (in addition to commodity / journal / effective-date /
   tx-code):
     :si-expense-account     — 法定福利費 expense (typically 612000)
     :si-liability-account   — 預り金 — 健康保険料 (typically 216100)
     :amount                 — BigDecimal — the employer's bucket share"
  [opts]
  (si-accrual-tx-data "健康保険料" (assoc opts :tx-tempid
                                     (or (:tx-tempid opts)
                                         "kenko-hoken-accrual-tx"))))

(defn health-insurance-accrual!
  [conn opts]
  (validation/transact-with-validation
   conn (health-insurance-accrual-tx-data opts)))

(defn pension-accrual-tx-data
  "Employer-side 厚生年金 (Kosei Nenkin) accrual for a pay-period."
  [opts]
  (si-accrual-tx-data "厚生年金保険料" (assoc opts :tx-tempid
                                       (or (:tx-tempid opts)
                                           "kosei-nenkin-accrual-tx"))))

(defn pension-accrual!
  [conn opts]
  (validation/transact-with-validation
   conn (pension-accrual-tx-data opts)))

(defn employment-insurance-accrual-tx-data
  "Employer-side 雇用保険 (Koyo Hoken) accrual for a pay-period.

   雇用保険 rates vary by industry (一般 / 農林水産 / 建設); the
   substrate is rate-agnostic — the consumer's engine computed the
   employer's portion, we just post it."
  [opts]
  (si-accrual-tx-data "雇用保険料" (assoc opts :tx-tempid
                                     (or (:tx-tempid opts)
                                         "koyo-hoken-accrual-tx"))))

(defn employment-insurance-accrual!
  [conn opts]
  (validation/transact-with-validation
   conn (employment-insurance-accrual-tx-data opts)))

(defn long-term-care-accrual-tx-data
  "Employer-side 介護保険 (Kaigo Hoken) accrual for a pay-period.

   Only applies to employees ≥40 years old. The substrate does NOT
   enforce the age check — that's the engine's responsibility; if
   the consumer calls this primitive with :amount > 0 for an
   under-40 employment, the resulting posting is valid datalog but
   may be flagged by an audit-doc review."
  [opts]
  (si-accrual-tx-data "介護保険料" (assoc opts :tx-tempid
                                     (or (:tx-tempid opts)
                                         "kaigo-hoken-accrual-tx"))))

(defn long-term-care-accrual!
  [conn opts]
  (validation/transact-with-validation
   conn (long-term-care-accrual-tx-data opts)))
