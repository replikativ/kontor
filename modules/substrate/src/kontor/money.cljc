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
     (defn- bd-neg [a]   (bd/bigdec (bineg (bd/->unscaled a)) (bd/->scale a)))))

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
  "Add two same-commodity Monies. Throws on commodity mismatch."
  [a b]
  (assert-same-commodity a b :add)
  (->Money #?(:clj  (.add ^BigDecimal (:amount a) ^BigDecimal (:amount b))
              :cljs (bd-add (:amount a) (:amount b)))
           (:commodity a)))

(defn sub
  "Subtract b from a. Same-commodity required."
  [a b]
  (assert-same-commodity a b :sub)
  (->Money #?(:clj  (.subtract ^BigDecimal (:amount a) ^BigDecimal (:amount b))
              :cljs (bd-sub (:amount a) (:amount b)))
           (:commodity a)))

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

#?(:clj
   (defn round
     "Round a Money to the given fractional precision (default 2). Mode keyword
      from `rounding-modes` (default :half-even). Returns a new Money."
     (^Money [^Money m]
      (round m 2 :half-even))
     (^Money [^Money m precision]
      (round m precision :half-even))
     (^Money [^Money m precision mode]
      (let [rm (or (get rounding-modes mode)
                   (throw (ex-info "Unknown rounding mode"
                                   {:mode mode :supported (keys rounding-modes)})))]
        (->Money (.setScale ^BigDecimal (:amount m) (int precision) ^RoundingMode rm)
                 (:commodity m))))))

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
