(ns kontor.fx
  "Currency-translation operations on `Money` values.

   This namespace is the *Money*-side of the FX story.
   [[kontor.fx-rate-provider]] is the *rate*-side: it knows how to look
   up a multiplier for (from, to, date, rate-type). `kontor.fx` knows
   how to apply that multiplier to a `Money` (or a sequence of them)
   while preserving the kernel's BigDecimal-always, no-silent-coercion
   posture from [[kontor.money]].

   ## Precision policy

   `convert` defaults to 2-digit fractional precision (typical fiat).
   That is NOT auto-derived from the target commodity's
   `:kontor.commodity/precision` — this namespace is pure-on-`Money` and has
   no db handle. JPY (precision 0) and BTC (precision 8) consumers
   must pass `:precision` explicitly. Pass `:precision nil` to skip
   rounding entirely and keep the full-precision product.

   Two operations cover today's needs:

     [[convert]]
       (convert m provider {:to \"USD\" :at-date d :rate-type :spot})
       → new Money in the target commodity. Rounds to the target
       commodity's precision via :half-even unless told otherwise.

     [[translate-amounts-by-commodity]]
       Given a `{commodity → BigDecimal}` summary (e.g. what
       `kontor.balance/account-balance` returns), re-base into ONE
       presentation commodity using the provider. Used by reports +
       (future) `kontor.consolidation/translate-currency!`.

   Per IAS 21, balance-sheet items use the period closing rate and P&L
   items use the period average rate; the *caller* picks `:rate-type`.
   The kernel does not classify accounts as monetary vs non-monetary —
   that belongs to a consumer (and is the job of the IAS-21 translator
   in `kontor-consolidation`).

   The substrate ships no rates; an [[kontor.fx-rate-provider/FxRateProvider]]
   is required. The kernel itself stays consumer-of-protocol, never
   coupled to a specific feed."
  (:require [kontor.fx-rate-provider :as fx-rate]
            [kontor.money :as money])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Single-Money conversion
;; ============================================================================

(defn convert
  "Translate a `Money` into the target commodity at the rate the
   provider returns for `:at-date`.

   `m` is a [[kontor.money/Money]]. The provider is anything satisfying
   [[kontor.fx-rate-provider/FxRateProvider]].

   Options:
     :to         — target commodity (eid, lookup-ref, or symbol \"USD\").
                   REQUIRED.
     :at-date    — java.util.Date for the rate lookup. REQUIRED.
     :rate-type  — :spot (default) | :closing | :average | :opening
                   | :historical.
     :precision  — fractional precision of the result. Default: 2.
                   Pass nil to skip rounding (keep the full-precision
                   BigDecimal — useful when the result will participate
                   in further arithmetic before display).
     :rounding   — keyword from [[kontor.money/rounding-modes]];
                   default :half-even.

   Identity short-circuit: if from = to, the input is returned
   unchanged (no provider call, no rounding).

   Throws if the provider returns nil; the caller is expected to know
   whether `(convert)` against a stale-rate provider should default vs
   error. (We pick error: silent FX coercion has bitten too many
   accounting systems.)"
  ([m provider {:keys [to at-date rate-type precision rounding via]
                :or   {rate-type :spot
                       precision 2
                       rounding  :half-even}}]
   (when-not (money/money? m) (throw (ex-info "convert: not a Money" {:got m})))
   (when (nil? to)            (throw (ex-info "convert: :to required" {})))
   (when (nil? at-date)       (throw (ex-info "convert: :at-date required" {})))
   (let [from (:commodity m)
         ;; identity short-circuit. Accept whatever the caller passed
         ;; for :to and the commodity carried on the Money — they may
         ;; not be referentially equal (one is a string, the other an
         ;; eid) — treat them as "same" when their resolvable identity
         ;; matches. We delegate identity to the provider when the
         ;; commodities aren't trivially equal.
         identity? (cond
                     (= from to)                                   true
                     (and (string? to) (string? from))             (= from to)
                     :else                                         false)]
     (if identity?
       m
       (let [rate (fx-rate/resolve-rate
                   provider
                   {:from-commodity from
                    :to-commodity   to
                    :at-date        at-date
                    :rate-type      rate-type
                    :via            via})]
         (when (nil? rate)
           (throw (ex-info "convert: provider returned no rate"
                           {:from from :to to :at-date at-date
                            :rate-type rate-type
                            :provider-id (fx-rate/provider-id provider)})))
         (let [amt (.multiply ^BigDecimal (:amount m) ^BigDecimal rate)
               raw (money/->Money amt to)]
           (if (nil? precision)
             raw
             (money/round raw precision rounding))))))))

;; ============================================================================
;; Sum-of-Money translation (per-commodity summary → one commodity)
;; ============================================================================

(defn translate-money-seq
  "Translate a sequence of (possibly mixed-commodity) Monies into ONE
   target commodity, returning a single Money. Each input is converted
   at `:at-date` / `:rate-type`; the converted Monies are summed.

   Useful as the building block for IAS 21 P&L translation (call with
   `:rate-type :average`) and BS translation (`:rate-type :closing`).

   Options mirror [[convert]]. Empty input → zero in `:to`."
  [monies provider opts]
  (let [to (:to opts)]
    (when (nil? to) (throw (ex-info "translate-money-seq: :to required" {})))
    (if (empty? monies)
      (money/zero to)
      (reduce money/add
              (money/zero to)
              (mapv #(convert % provider opts) monies)))))

(defn translate-amounts-by-commodity
  "Re-base a `{commodity → BigDecimal}` summary (as returned by
   [[kontor.balance/account-balance]] or
   [[kontor.report/compute-report]]'s per-commodity rollup) into ONE
   target commodity. Each (commodity, amount) becomes a Money,
   converted, then summed.

   Returns a single Money in `:to`. Used by reports that want
   one-currency presentation totals; the kernel does NOT mutate the
   per-commodity decomposition — `:translate-to` is a presentation
   layer."
  [amounts provider {:keys [to] :as opts}]
  (when (nil? to) (throw (ex-info "translate-amounts-by-commodity: :to required" {})))
  (->> amounts
       (mapv (fn [[commodity amt]]
               (money/->Money (if (instance? BigDecimal amt) amt (bigdec amt))
                              commodity)))
       (#(translate-money-seq % provider opts))))

;; ============================================================================
;; Posting-amount translation (functional-currency rebase)
;;
;; Most accounting frameworks (IAS 21, ASC 830) distinguish:
;;   - functional currency — the entity's primary economic environment
;;     (an :entity/functional-commodity in the schema)
;;   - presentation currency — what reports are denominated in
;;
;; This helper converts a *source* amount into an entity's functional
;; currency at the time of the transaction, for use by consumers that
;; want to record the transaction in the entity's functional commodity
;; rather than the original. Pure helper; does NOT write a posting.
;; ============================================================================

(defn to-functional-currency
  "Translate a foreign-currency `Money` into the entity's functional
   currency.

   `entity` is an entity map (or anything with `:entity/functional-commodity`
   resolved to a commodity eid or symbol). If the entity has no
   `:entity/functional-commodity`, the input is returned unchanged
   (the entity hasn't opted into functional-currency accounting).

   The transaction-date is required because IAS 21 uses the *spot rate
   at the date of the transaction*."
  [m entity provider {:keys [at-date rate-type] :as opts}]
  (let [fc (or (some-> entity :entity/functional-commodity :kontor.commodity/symbol)
               (some-> entity :entity/functional-commodity)
               (:functional-commodity entity))]
    (cond
      (nil? fc)             m       ; entity hasn't opted in
      (= (:commodity m) fc) m       ; already in functional commodity
      :else (convert m provider (merge opts
                                       {:to        fc
                                        :at-date   at-date
                                        :rate-type (or rate-type :spot)})))))
