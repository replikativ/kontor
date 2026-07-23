(ns kontor.money
  "Money values: a decimal amount + a commodity tag. Cross-platform (ADR-013):

     - JVM:  amount is a java.math.BigDecimal.
     - cljs: amount is a fress.impl.bigdec/Bigdec (unscaled js/BigInt + scale) —
             the exact type a `:kontor.posting/amount` arrives as after konserve
             sync. The cljs balance arithmetic is exact js/BigInt math, so a
             client-side sum-to-zero check is bit-for-bit identical to the JVM
             gate (no rounding drift between the live form check and the commit).

   Money is a record carrying (amount, commodity). Arithmetic is
   commodity-checked: cross-commodity ops throw. Default rounding is HALF_EVEN.

   The client needs only the balance subset (construction, add/sub/neg/zero?,
   sum, sum-by-commodity, display). The heavy ops (round, split-by-percentages,
   mul-scalar, parse-decimal) are JVM-only — the browser never needs them.

   At the datahike boundary Money decomposes into :kontor.posting/amount +
   :kontor.posting/commodity — see `posting->money` / `money->posting-fragment`."
  (:refer-clojure :exclude [zero?])
  (:require [clojure.string :as str]
            #?(:cljs [fress.impl.bigdec :as bd]))
  #?(:clj (:import [java.math BigDecimal RoundingMode])))

;; ============================================================================
;; The Money record
;; ============================================================================

;; The commodity field is polymorphic — a keyword (:EUR) for unattached use, or
;; an eid / lookup-ref when constructed from datahike data. Arithmetic compares
;; commodities with `=`, so both representations work as long as both sides agree.
(defrecord Money [amount commodity])

(defn money?
  "True if x is a Money record."
  [x]
  (instance? Money x))

;; ============================================================================
;; cljs decimal helpers — exact js/BigInt arithmetic over fress Bigdec
;; ============================================================================

#?(:cljs
   (do
     (defn- big [n] (js/BigInt n))
     (defn- pow10 [n] (js* "(10n ** BigInt(~{}))" n))
     (defn- bi+ [a b] (js* "(~{} + ~{})" a b))
     (defn- bi- [a b] (js* "(~{} - ~{})" a b))
     (defn- bineg [a] (js* "(-~{})" a))
     (defn- bi-zero? [a] (js* "(~{} === 0n)" a))
     (defn- bi-sign [a] (cond (js* "(~{} > 0n)" a) 1 (js* "(~{} < 0n)" a) -1 :else 0))
     (defn- str->bigdec [s]
       (let [s    (str/trim s)
             neg? (str/starts-with? s "-")
             s    (if (or neg? (str/starts-with? s "+")) (subs s 1) s)
             dot  (str/index-of s ".")
             ip   (if dot (subs s 0 dot) s)
             fp   (if dot (subs s (inc dot)) "")
             scale (count fp)
             u    (big (str (if (empty? ip) "0" ip) fp))]
         (bd/bigdec (if neg? (bineg u) u) scale)))
     (defn- bd-align [a b]
       (let [sa (bd/->scale a) sb (bd/->scale b) s (max sa sb)]
         [(js* "(~{} * ~{})" (bd/->unscaled a) (pow10 (- s sa)))
          (js* "(~{} * ~{})" (bd/->unscaled b) (pow10 (- s sb)))
          s]))
     (defn- bd-add [a b] (let [[ua ub s] (bd-align a b)] (bd/bigdec (bi+ ua ub) s)))
     (defn- bd-sub [a b] (let [[ua ub s] (bd-align a b)] (bd/bigdec (bi- ua ub) s)))
     (defn- bd-neg [a]   (bd/bigdec (bineg (bd/->unscaled a)) (bd/->scale a)))
     (defn- bi-abs  [a]   (js* "(~{} < 0n ? -~{} : ~{})" a a a))
     (defn- bi*     [a b] (js* "(~{} * ~{})" a b))
     (defn- biquot  [a b] (js* "(~{} / ~{})" a b)) ; BigInt division truncates toward zero
     (defn- bi-odd? [a]   (js* "((~{} % 2n) !== 0n)" a))
     (defn- bi<     [a b] (js* "(~{} < ~{})" a b))
     (defn- bi>     [a b] (js* "(~{} > ~{})" a b))
     (defn- bi>=    [a b] (js* "(~{} >= ~{})" a b))
     ;; Exact multiply: product scale = sum of operand scales (no rounding).
     (defn- bd-mul [a b]
       (bd/bigdec (bi* (bd/->unscaled a) (bd/->unscaled b))
                  (+ (bd/->scale a) (bd/->scale b))))
     ;; Round a Bigdec to `n` fractional places under `mode`. Pads (exact) when
     ;; scaling up; otherwise BigInt-divides the magnitude by 10^(s-n) and
     ;; rounds the quotient per mode (tie compares 2·remainder to the divisor).
     (defn- bd-round [m n mode]
       (let [u (bd/->unscaled m) s (bd/->scale m)]
         (if (<= s n)
           (bd/bigdec (bi* u (pow10 (- n s))) n)
           (let [sign  (bi-sign u)
                 au    (bi-abs u)
                 d     (pow10 (- s n))
                 q     (biquot au d)
                 r     (bi- au (bi* q d))
                 two-r (bi* (big 2) r)
                 up?   (case mode
                         :down      false
                         :up        (bi> r (big 0))
                         :floor     (and (= sign -1) (bi> r (big 0)))
                         :ceiling   (and (= sign 1)  (bi> r (big 0)))
                         :half-up   (bi>= two-r d)
                         :half-down (bi> two-r d)
                         :half-even (cond (bi< two-r d) false
                                          (bi> two-r d) true
                                          :else (bi-odd? q)))
                 q2    (if up? (bi+ q (big 1)) q)]
             (bd/bigdec (if (= sign -1) (bineg q2) q2) n)))))
     ;; a / b to `n` fractional places under `mode` (BigInt long division of the
     ;; scale-aligned magnitudes, then round the quotient).
     (defn- bd-divide [a b n mode]
       (let [ua (bd/->unscaled a) sa (bd/->scale a)
             ub (bd/->unscaled b) sb (bd/->scale b)
             shift (+ n (- sb sa))
             num   (if (>= shift 0) (bi* ua (pow10 shift)) (biquot ua (pow10 (- shift))))
             rsign (* (bi-sign num) (bi-sign ub))
             an    (bi-abs num) ad (bi-abs ub)
             q     (biquot an ad)
             r     (bi- an (bi* q ad))
             two-r (bi* (big 2) r)
             up?   (case mode
                     :down      false
                     :up        (bi> r (big 0))
                     :floor     (and (neg? rsign) (bi> r (big 0)))
                     :ceiling   (and (pos? rsign) (bi> r (big 0)))
                     :half-up   (bi>= two-r ad)
                     :half-down (bi> two-r ad)
                     :half-even (cond (bi< two-r ad) false
                                      (bi> two-r ad) true
                                      :else (bi-odd? q)))
             q2    (if up? (bi+ q (big 1)) q)]
         (bd/bigdec (if (neg? rsign) (bineg q2) q2) n)))))

;; ============================================================================
;; Construction
;; ============================================================================

(defn- coerce-amount
  "Coerce x to the platform decimal (BigDecimal / Bigdec). Strings are parsed;
   doubles / JS numbers are rejected (lossy — use a string)."
  [x]
  #?(:clj
     (cond
       (instance? BigDecimal x) x
       (integer? x)             (BigDecimal/valueOf (long x))
       (string? x)              (BigDecimal. ^String x)
       (instance? Double x)
       (throw (ex-info
               "Refusing to construct Money from a double — use a string or
                BigDecimal to avoid float-precision corruption."
               {:value x :type (class x)}))
       :else
       (throw (ex-info "Cannot coerce to BigDecimal" {:value x :type (class x)})))
     :cljs
     (cond
       (bd/bigdec? x) x
       (string? x)    (str->bigdec x)
       (integer? x)   (bd/bigdec (big x) 0)
       (number? x)
       (throw (ex-info
               "Refusing to construct Money from a JS number — use a string to
                avoid float-precision corruption."
               {:value x}))
       :else
       (throw (ex-info "Cannot coerce to Bigdec" {:value x})))))

(defn money
  "Construct a Money. `amount` may be a platform decimal, integer, or a string
   (\"1234.56\"); doubles/JS-numbers are explicitly rejected.

   Examples:
     (money \"100.00\" :EUR)
     (money 1000 :USD)"
  [amount commodity]
  (when (nil? commodity)
    (throw (ex-info "Money requires a commodity" {:amount amount})))
  (->Money (coerce-amount amount) commodity))

(defn amount?
  "True iff `x` is a platform decimal (BigDecimal on the JVM, fress Bigdec in
   cljs) — the raw type inside a Money's `:amount`."
  [x]
  #?(:clj  (instance? BigDecimal x)
     :cljs (bd/bigdec? x)))

(defn ->amount
  "Coerce `x` (string \"1234.56\", integer, or a platform decimal) to the
   platform decimal — BigDecimal on the JVM, fress `Bigdec` in cljs. Rejects
   floats/JS-numbers (lossy). Public so cross-platform builders
   (`kontor.book.build`) can coerce posting amounts without reaching into the
   private constructor path."
  [x]
  (coerce-amount x))

(defn negate-amount
  "Negate a raw platform decimal (BigDecimal / Bigdec) — for builders that
   construct a credit leg from a debit amount without wrapping in `Money`.
   cljc: `(- amt)` doesn't work on a cljs Bigdec."
  [x]
  #?(:clj  (.negate ^BigDecimal x)
     :cljs (bd-neg x)))

(defn add-amount
  "Add two raw platform decimals (BigDecimal / Bigdec). cljc — for validators
   that accumulate posting amounts without wrapping each in `Money`."
  [a b]
  #?(:clj  (.add ^BigDecimal a ^BigDecimal b)
     :cljs (bd-add a b)))

(defn zero-amount
  "The platform-decimal zero (BigDecimal `0M` / Bigdec 0) — a `fnil` seed for
   an amount accumulator."
  []
  #?(:clj  0M
     :cljs (str->bigdec "0")))

(defn amount-zero?
  "True iff a raw platform decimal is zero (scale-insensitive). NB this ns
   excludes core `zero?` (it defines a Money-level `zero?`), so compare the
   sign directly rather than calling `zero?`."
  [x]
  #?(:clj  (== 0 (.signum ^BigDecimal x))
     :cljs (bi-zero? (bd/->unscaled x))))

(defn amount-positive?
  "True iff a raw platform decimal is > 0. cljc."
  [x]
  #?(:clj  (pos? (.signum ^BigDecimal x))
     :cljs (pos? (bi-sign (bd/->unscaled x)))))

(defn amount-sign
  "Sign of a raw platform decimal: -1, 0, or 1. cljc."
  [x]
  #?(:clj  (.signum ^BigDecimal x)
     :cljs (bi-sign (bd/->unscaled x))))

(defn amount-negative?
  "True iff a raw platform decimal is < 0. cljc."
  [x]
  #?(:clj  (neg? (.signum ^BigDecimal x))
     :cljs (neg? (bi-sign (bd/->unscaled x)))))

(defn compare-amounts
  "Compare two raw platform decimals, returning a negative/zero/positive int
   like `compareTo` (scale-insensitive). cljc."
  [a b]
  #?(:clj  (.compareTo ^BigDecimal a ^BigDecimal b)
     :cljs (bi-sign (bd/->unscaled (bd-sub a b)))))

(defn amount->double
  "Approximate double value of a raw platform decimal — for sort keys / display
   only, NOT arithmetic (lossy). cljc."
  [x]
  #?(:clj  (.doubleValue ^BigDecimal x)
     :cljs (js/parseFloat (bd/->str x))))

(defn zero
  "Money with zero amount in the given commodity."
  [commodity]
  (money 0 commodity))

;; ============================================================================
;; Predicates
;; ============================================================================

(defn zero?
  "True iff this Money has amount 0."
  [m]
  #?(:clj  (.equals BigDecimal/ZERO (.stripTrailingZeros ^BigDecimal (:amount m)))
     :cljs (bi-zero? (bd/->unscaled (:amount m)))))

(defn positive?
  "True iff amount > 0."
  [m]
  #?(:clj  (pos? (.signum ^BigDecimal (:amount m)))
     :cljs (pos? (bi-sign (bd/->unscaled (:amount m))))))

(defn negative?
  "True iff amount < 0."
  [m]
  #?(:clj  (neg? (.signum ^BigDecimal (:amount m)))
     :cljs (neg? (bi-sign (bd/->unscaled (:amount m))))))

(defn same-commodity?
  "True iff both Monies are in the same commodity."
  [a b]
  (= (:commodity a) (:commodity b)))

(defn- assert-same-commodity
  [a b op]
  (when-not (same-commodity? a b)
    (throw (ex-info (str "Cross-commodity " op " is forbidden")
                    {:left  {:amount (:amount a) :commodity (:commodity a)}
                     :right {:amount (:amount b) :commodity (:commodity b)}
                     :op    op
                     :hint  "Convert one side first via an FX-rate-aware
                             conversion fn; the kernel never silently
                             coerces between commodities."}))))

;; ============================================================================
;; Arithmetic (balance subset — cross-platform)
;; ============================================================================

(defn add
  "Add two Monies. Same-commodity required for two non-zero operands.

   A zero operand is the additive identity in ANY commodity:
   `(add (zero :EUR) x)` => `x`, `(add x (zero :CAD))` => `x`. This is
   what makes empty aggregation seeds and absent-data statement lines
   compose cleanly — an empty statement line is a zero, and a zero of the
   'wrong' commodity contributes nothing numerically, so it must not raise
   a spurious cross-commodity error (note 196 F5b). Two NON-zero operands
   of different commodities still throw; the sum-to-zero balance check runs
   per-commodity via `sum-by-commodity`, so this does not weaken it."
  [a b]
  (cond
    (zero? a) b
    (zero? b) a
    :else
    (do (assert-same-commodity a b :add)
        (->Money #?(:clj  (.add ^BigDecimal (:amount a) ^BigDecimal (:amount b))
                    :cljs (bd-add (:amount a) (:amount b)))
                 (:commodity a)))))

(defn sub
  "Subtract b from a. Same-commodity required for two non-zero operands; a
   zero operand acts as the additive identity in any commodity (see `add`):
   `(sub x (zero :CAD))` => `x`, `(sub (zero :EUR) x)` => `(neg x)`."
  [a b]
  (cond
    (zero? b) a
    (zero? a) (->Money #?(:clj  (.negate ^BigDecimal (:amount b))
                          :cljs (bd-neg (:amount b)))
                       (:commodity b))
    :else
    (do (assert-same-commodity a b :sub)
        (->Money #?(:clj  (.subtract ^BigDecimal (:amount a) ^BigDecimal (:amount b))
                    :cljs (bd-sub (:amount a) (:amount b)))
                 (:commodity a)))))

(defn neg
  "Unary negation. Useful for credit-side construction."
  [m]
  (->Money #?(:clj  (.negate ^BigDecimal (:amount m))
              :cljs (bd-neg (:amount m)))
           (:commodity m)))

(defn sum
  "Sum a sequence of same-commodity Monies. Empty sequence requires an explicit
   commodity to construct the zero. Throws on mixed commodities."
  ([monies]
   (when (empty? monies)
     (throw (ex-info "sum of empty money sequence requires explicit commodity"
                     {:hint "Pass commodity as second arg: (sum [] :EUR)"})))
   (reduce add monies))
  ([monies commodity]
   (reduce add (zero commodity) monies)))

(defn sum-by-commodity
  "Sum a heterogeneous sequence of Monies, returning {commodity => Money} with
   one entry per distinct commodity. The double-entry sum-to-zero check uses
   this — postings of one commodity must net to zero independently of others."
  [monies]
  (->> monies
       (group-by :commodity)
       (reduce-kv
        (fn [acc commodity ms]
          (assoc acc commodity (sum ms commodity)))
        {})))

;; ============================================================================
;; JVM-only: scalar multiply, rounding, apportionment, locale parsing
;; (the browser's dry-run never needs these)
;; ============================================================================

#?(:clj
   (defn mul-scalar
     "Multiply by a unitless scalar (long, BigDecimal, BigInt). Same commodity.
      The scalar may NOT be a Money (use a tax-rate protocol for that)."
     ^Money [^Money m scalar]
     (when (money? scalar)
       (throw (ex-info "mul-scalar expects a unitless scalar, got Money"
                       {:money m :scalar scalar})))
     (->Money (.multiply ^BigDecimal (:amount m) (coerce-amount scalar))
              (:commodity m))))

#?(:clj (def ^:const default-rounding-mode RoundingMode/HALF_EVEN))

#?(:clj
   (def rounding-modes
     "Public alias map for supported rounding modes. ADR-013 defaults to
      :half-even; :half-up available where regulators mandate it."
     {:half-even RoundingMode/HALF_EVEN
      :half-up   RoundingMode/HALF_UP
      :half-down RoundingMode/HALF_DOWN
      :ceiling   RoundingMode/CEILING
      :floor     RoundingMode/FLOOR
      :down      RoundingMode/DOWN
      :up        RoundingMode/UP}))

(def valid-rounding-modes
  "The rounding-mode keywords `round` accepts (ADR-013). cljc — the JVM maps
   these to java.math.RoundingMode; cljs implements them over js/BigInt."
  #{:half-even :half-up :half-down :ceiling :floor :down :up})

(defn multiply-amounts
  "Multiply two raw platform decimals (BigDecimal / Bigdec) EXACTLY — the
   product's scale is the sum of the operands' scales, no rounding. cljc; for
   FX conversion (amount × rate) before an explicit `round`."
  [a b]
  #?(:clj  (.multiply ^BigDecimal a ^BigDecimal b)
     :cljs (bd-mul a b)))

(defn divide-amounts
  "Divide raw platform decimal `a` by `b` to `scale` fractional places using
   `mode` (default :half-even). cljc; for FX reciprocal rates (1 / rate)."
  ([a b scale] (divide-amounts a b scale :half-even))
  ([a b scale mode]
   (when-not (contains? valid-rounding-modes mode)
     (throw (ex-info "Unknown rounding mode" {:mode mode :supported valid-rounding-modes})))
   #?(:clj  (.divide ^BigDecimal a ^BigDecimal b (int scale) ^RoundingMode (get rounding-modes mode))
      :cljs (bd-divide a b scale mode))))

(defn round
  "Round a Money to `precision` fractional places (default 2) using `mode` (a
   keyword from `valid-rounding-modes`, default :half-even). Returns a new
   Money. cljc — bit-identical HALF_EVEN etc. on JVM and in the browser."
  ([m] (round m 2 :half-even))
  ([m precision] (round m precision :half-even))
  ([m precision mode]
   (when-not (contains? valid-rounding-modes mode)
     (throw (ex-info "Unknown rounding mode"
                     {:mode mode :supported valid-rounding-modes})))
   (->Money #?(:clj  (.setScale ^BigDecimal (:amount m) (int precision)
                                ^RoundingMode (get rounding-modes mode))
               :cljs (bd-round (:amount m) precision mode))
            (:commodity m))))

#?(:clj
   (defn split-by-percentages
     "Split a Money into N children whose amounts sum exactly to the input
      (Hare / largest-remainder apportionment). See ADR-022. Returns a vector of
      Monies in `percents` order; the sum is bit-exact to the input."
     ([^Money m percents]
      (split-by-percentages m percents 2 :half-even))
     ([^Money m percents precision]
      (split-by-percentages m percents precision :half-even))
     ([^Money m percents precision mode]
      (when (empty? percents)
        (throw (ex-info "split-by-percentages: empty percent sequence" {:money m})))
      (let [bd-percents (mapv coerce-amount percents)
            _ (doseq [p bd-percents]
                (when (neg? (.signum ^BigDecimal p))
                  (throw (ex-info "split-by-percentages: negative percent"
                                  {:money m :percent p}))))
            rm (or (get rounding-modes mode)
                   (throw (ex-info "Unknown rounding mode" {:mode mode})))
            amt ^BigDecimal (:amount m)
            hundred (BigDecimal/valueOf 100)
            floors (mapv (fn [^BigDecimal p]
                           (-> amt (.multiply p) (.divide hundred (int precision) RoundingMode/DOWN)))
                         bd-percents)
            exact (mapv (fn [^BigDecimal p]
                          (-> amt (.multiply p) (.divide hundred (int (+ precision 4)) rm)))
                        bd-percents)
            remainders (mapv (fn [^BigDecimal e ^BigDecimal f] (.subtract e f)) exact floors)
            scaled-amt (.setScale amt (int precision) rm)
            zero-at-p  (.setScale BigDecimal/ZERO (int precision))
            floor-sum (reduce (fn [^BigDecimal acc ^BigDecimal x] (.add acc x)) zero-at-p floors)
            residue   (.subtract scaled-amt floor-sum)
            unit      (.movePointLeft BigDecimal/ONE (int precision))
            unit-count (-> residue (.divide unit 0 RoundingMode/HALF_EVEN) .toBigInteger .longValue)
            indexes (->> (range (count floors))
                         (sort-by (fn [i] [(.negate (.abs ^BigDecimal (nth remainders i))) i])))
            to-bump (set (take (Math/abs unit-count) indexes))
            signed-unit (if (neg? unit-count) (.negate unit) unit)
            adjusted (vec
                      (map-indexed
                       (fn [i ^BigDecimal f]
                         (if (contains? to-bump i) (.add f signed-unit) f))
                       floors))]
        (mapv #(->Money % (:commodity m)) adjusted)))))

;; ============================================================================
;; Display
;; ============================================================================

(defn money->str
  "Human-readable form (unlocalized). Useful for logs and error messages."
  [m]
  (let [c     (:commodity m)
        c-str (cond (keyword? c) (name c) :else (str c))
        amt-str #?(:clj  (.toPlainString ^BigDecimal (:amount m))
                   :cljs (bd/->str (:amount m)))]
    (str amt-str " " c-str)))

;; ============================================================================
;; Datahike interop
;; ============================================================================

(defn money->posting-fragment
  "Decompose a Money into the {:kontor.posting/amount :kontor.posting/commodity}
   fragment that goes into a posting entity map."
  [m]
  {:kontor.posting/amount    (:amount m)
   :kontor.posting/commodity (:commodity m)})

(defn- normalize-commodity
  "Datahike returns refs from `d/pull` as `{:db/id N}`; from `d/q` as bare eids;
   from raw tx maps as whatever the caller put there. Normalize a pulled-shape
   ref to its bare :db/id; pass everything else through unchanged."
  [c]
  (if (and (map? c) (contains? c :db/id) (= 1 (count c)))
    (:db/id c)
    c))

(defn posting->money
  "Inverse: pull a Money out of a posting entity map. Returns nil if the posting
   is missing :kontor.posting/amount or :kontor.posting/commodity."
  [posting]
  (when (and (:kontor.posting/amount posting)
             (:kontor.posting/commodity posting))
    (->Money (:kontor.posting/amount posting)
             (normalize-commodity (:kontor.posting/commodity posting)))))

;; ============================================================================
;; Equality
;; ============================================================================

(defn equiv?
  "Scale-insensitive value equality, with an explicit commodity check."
  [a b]
  (and (same-commodity? a b)
       (zero? (sub a b))))

;; ============================================================================
;; JVM-only: locale-aware parsing
;; ============================================================================

#?(:clj
   (defn parse-decimal
     "Parse a numeric string into BigDecimal. Accepts comma OR period as decimal
      separator (German \"1.234,56\" → 1234.56; English \"1,234.56\" → 1234.56),
      detecting which by the position of the last separator. Throws on garbage."
     ^BigDecimal [s]
     (when (nil? s) (throw (ex-info "Cannot parse nil decimal" {})))
     (let [t (str/trim s)
           last-comma  (.lastIndexOf t ",")
           last-period (.lastIndexOf t ".")
           normalized
           (cond
             (and (neg? last-comma) (neg? last-period)) t
             (> last-comma last-period) (-> t (str/replace "." "") (str/replace "," "."))
             :else (str/replace t "," ""))]
       (try
         (BigDecimal. ^String normalized)
         (catch NumberFormatException e
           (throw (ex-info "Could not parse decimal" {:input s :normalized normalized} e)))))))
