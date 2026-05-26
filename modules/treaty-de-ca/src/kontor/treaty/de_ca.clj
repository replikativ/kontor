(ns kontor.treaty.de-ca
  "Per-treaty-pair helpers for the Germany ↔ Canada double-taxation
   treaty (BGBl. 1982 II S. 802, signed 17 Jul 1981; protocols 2001,
   2017). Cross-border dividend / interest / royalty flow between a
   DE-resident payer and a CA-resident payee (or vice-versa) splits
   the WHT into:

   1. The treaty-creditable slice (the destination jurisdiction's §126
      / §20-AStG / equivalent FTC consumes this).
   2. The over-treaty-excess slice (refundable from the source-state
      via Erstattungsantrag — DE BZSt, CA CRA, etc.).

   Without a helper, every consumer hand-computes (a) the FX rate at
   the value-date, (b) the split, (c) which kontor accounts receive
   each slice. This module makes the move a one-call composite event.

   Note 161 §7 + note 160 §I-19.

   ## Treaty rates (post-2017 protocol)
   - Portfolio dividends (< 10 % stake):           15 %
   - Direct-investment dividends (≥ 10 % stake):    5 %
   - Interest:                                      10 %
   - Royalties:                                      0 % (since 2017)
   - Pensions:                                       0 %
   - Government service:                             0 %

   ## Scope of v1
   - Inbound to CA: DE-source dividend received on a CA-personal kontor
     DB. v1 implements this — the common case for the founder-with-
     European-LLC scenario.
   - Outbound from CA (CA Inc paying a DE-resident shareholder):
     deferred to v2; emit the data shape but not the helper.

   Account paths assume the canonical CA chart (kontor-l10n-ca). The
   consumer's CA-personal DB must have:
   - `Assets:Bank:CAD`                  (cash receipt)
   - `Assets:Foreign-Tax-Prepaid`       (treaty-creditable portion)
   - `Assets:Foreign-Tax-Refundable`    (over-treaty BZSt refund-claim)
   - `Income:Dividends:Foreign:DE`      (the gross dividend in CAD)
   These four ship in the CA preset (Phase B) plus the additions Phase D
   exercised — see kontor.l10n-ca.chart."
  (:require [datahike.api :as d]
            [kontor.book :as book]))

;; ============================================================================
;; Treaty rates — Art. 10 (dividends), Art. 11 (interest), Art. 12 (royalties)
;; ============================================================================

(def ^:private treaty-rates
  "Post-2017-protocol treaty rates. Source-state withholding cap."
  {:dividend-portfolio          0.15M   ; Art. 10(2)(b) — < 10 % stake
   :dividend-direct-investment  0.05M   ; Art. 10(2)(a) — ≥ 10 % stake
   :interest                    0.10M   ; Art. 11(2)
   :royalty                     0.00M   ; Art. 12 (post-2017 protocol)
   :pension                     0.00M
   :government-service          0.00M})

(defn treaty-rate
  "Look up the DE-CA treaty rate for a given income kind. Throws on
   unknown kind so consumers don't silently fall through to a wrong
   number."
  [kind]
  (or (get treaty-rates kind)
      (throw (ex-info "kontor.treaty.de-ca: unknown :income-kind"
                      {:income-kind kind :supported (set (keys treaty-rates))}))))

;; ============================================================================
;; Split — how a DE-side WHT decomposes for CA-side accounting
;; ============================================================================

(defn split-de-wht
  "Decompose a DE-side WHT `withheld-amount` (in source currency, e.g.
   EUR) on a `gross-amount` of `:income-kind` into:
   - `:treaty-creditable` — amount up to the treaty cap, claimable via
     CA §126 FTC
   - `:over-treaty-refundable` — excess over the treaty cap, claimable
     from DE BZSt via Erstattungsantrag (form available on bzst.de)

   Both in source currency. Caller multiplies each by the FX rate to
   land them in CA functional currency.

   Returns `{:treaty-creditable <bd> :over-treaty-refundable <bd>
            :treaty-rate <bd>}`."
  [{:keys [gross-amount withheld-amount income-kind]}]
  (let [rate     (treaty-rate income-kind)
        treaty   (* gross-amount rate)
        creditable (min withheld-amount treaty)
        excess    (max 0M (- withheld-amount treaty))]
    {:treaty-creditable      creditable
     :over-treaty-refundable excess
     :treaty-rate            rate}))

;; ============================================================================
;; The dividend-receive event — DE → CA inbound
;; ============================================================================

(defn receive-dividend-from-de!
  "Composite event: a CA-resident receives a dividend from a DE-resident
   payer. Books the 4-leg entry on the CA-personal DB with the right
   split per the DE-CA treaty.

   Required opts:
   - `:gross-amount`     BigDecimal in EUR (the declared dividend)
   - `:withheld-amount`  BigDecimal in EUR (KESt + Soli withheld)
   - `:income-kind`      `:dividend-portfolio` or `:dividend-direct-investment`
   - `:fx-rate`          BigDecimal — CAD per EUR at the value-date
   - `:net-cash-amount`  BigDecimal in EUR (sanity-check: should equal
                         `gross - withheld`)
   - `:effective-date`   #inst
   - `:payer-partner`    partner ref of the DE corp
   - `:entity`           CA entity ref (the recipient — e.g. you personally)

   Optional opts:
   - `:journal`          journal ref/lookup-ref (default: :cash type)
   - `:net-cash-account`        (default `[:kontor.account/path \"Assets:Bank:CAD\"]`)
   - `:creditable-account`      (default `[:kontor.account/path \"Assets:Foreign-Tax-Prepaid\"]`)
   - `:refundable-account`      (default `[:kontor.account/path \"Assets:Foreign-Tax-Refundable\"]`)
   - `:income-account`          (default `[:kontor.account/path \"Income:Dividends:Foreign:DE\"]`)
   - `:narration`               (default constructed from the inputs)

   Posts a 4-leg balanced entry in CAD and returns the tx-data report."
  [conn {:keys [gross-amount withheld-amount income-kind fx-rate
                net-cash-amount effective-date payer-partner entity
                journal narration
                net-cash-account creditable-account refundable-account
                income-account]
         :or {net-cash-account   [:kontor.account/path "Assets:Bank:CAD"]
              creditable-account [:kontor.account/path "Assets:Foreign-Tax-Prepaid"]
              refundable-account [:kontor.account/path "Assets:Foreign-Tax-Refundable"]
              income-account     [:kontor.account/path "Income:Dividends:Foreign:DE"]}}]
  ;; Validate inputs
  (when-not (and gross-amount withheld-amount income-kind fx-rate
                 net-cash-amount effective-date payer-partner)
    (throw (ex-info "kontor.treaty.de-ca/receive-dividend-from-de!: missing required opts"
                    {:supplied (keys *1)})))
  (when-not (or (== (+ withheld-amount net-cash-amount) gross-amount)
                (< (.abs (- (+ withheld-amount net-cash-amount) gross-amount)) 0.01M))
    (throw (ex-info "withheld + net-cash must equal gross (±€0.01)"
                    {:gross gross-amount :withheld withheld-amount
                     :net   net-cash-amount})))
  (let [{:keys [treaty-creditable over-treaty-refundable]}
        (split-de-wht {:gross-amount    gross-amount
                       :withheld-amount withheld-amount
                       :income-kind     income-kind})
        ;; Convert each slice to CAD using HALF_EVEN to match accounting
        ;; rounding convention.
        round2 (fn [^java.math.BigDecimal x]
                 (.setScale x 2 java.math.RoundingMode/HALF_EVEN))
        cad-net-cash   (round2 (* net-cash-amount fx-rate))
        cad-creditable (round2 (* treaty-creditable fx-rate))
        cad-refundable (round2 (* over-treaty-refundable fx-rate))
        cad-gross      (+ cad-net-cash cad-creditable cad-refundable)]
    (book/entry! conn
      {:journal        (or journal [:kontor.journal/code "CR"])  ; default to Cash Receipts
       :commodity      [:kontor.commodity/symbol "CAD"]
       :effective-date effective-date
       :entity         entity
       :partner        payer-partner
       :narration      (or narration
                           (format
                            "DE dividend received: €%s gross @ %s CAD/EUR = CAD %s; DE WHT €%s split into treaty-%s%% creditable + excess refundable"
                            gross-amount fx-rate cad-gross withheld-amount
                            (.multiply ^java.math.BigDecimal (treaty-rate income-kind) 100M)))
       :postings       [{:account net-cash-account   :amount cad-net-cash}
                        {:account creditable-account :amount cad-creditable}
                        {:account refundable-account :amount cad-refundable}
                        {:account income-account     :amount (- cad-gross)}]})))
