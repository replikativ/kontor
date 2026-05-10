(ns datahike-accounting.money
  "Money values: BigDecimal + commodity tag.

   Per ADR-013 in doc/decisions.md:
     - Money is a record carrying (amount, commodity).
     - Arithmetic is commodity-checked: cross-commodity ops throw.
     - Default rounding is HALF_EVEN (banker's rounding); HALF_UP is
       available where regulators mandate it.
     - Precision (number of fractional digits) defaults to 2 for
       conventional fiat; callers can override per-call when scaling
       the result. Stored money in datahike preserves the BigDecimal's
       own scale.

   Money is the type the kernel uses internally. At the datahike
   boundary, Money decomposes into the existing :posting/amount
   (bigdec) + :posting/commodity (ref) attribute pair — see
   `posting->money` and `money->posting-fragment` below.

   Naked BigDecimals are a smell. Every kernel fn that takes a monetary
   amount accepts a Money."
  (:refer-clojure :exclude [zero?])
  (:require [clojure.string :as str])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; The Money record
;; ============================================================================

;; The commodity field is intentionally polymorphic — a keyword
;; (:EUR :USD :CAD) for unattached use, or an entity-id / lookup-ref
;; when the Money is constructed from datahike data. The arithmetic
;; predicates compare commodities with `=`, so both representations
;; work as long as both sides agree.

(defrecord Money [^BigDecimal amount commodity])

(defn money?
  "True if x is a Money record."
  [x]
  (instance? Money x))

;; ============================================================================
;; Construction
;; ============================================================================

(defn- coerce-amount
  "Coerce x to BigDecimal. Strings are parsed; doubles are rejected
   (lossy — use a string instead). Longs/integers / BigInts / existing
   BigDecimals pass through."
  ^BigDecimal [x]
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
    (throw (ex-info "Cannot coerce to BigDecimal"
                    {:value x :type (class x)}))))

(defn money
  "Construct a Money. `amount` may be a BigDecimal, integer, BigInt, or
   a string (\"1234.56\"); doubles are explicitly rejected to avoid
   float-precision corruption.

   Examples:
     (money \"100.00\" :EUR)
     (money 1000M :USD)
     (money \"1.234567\" :BTC)"
  [amount commodity]
  (when (nil? commodity)
    (throw (ex-info "Money requires a commodity" {:amount amount})))
  (->Money (coerce-amount amount) commodity))

(defn zero
  "Money with zero amount in the given commodity."
  [commodity]
  (money 0M commodity))

;; ============================================================================
;; Predicates
;; ============================================================================

(defn zero?
  "True iff this Money has amount 0."
  [^Money m]
  (.equals BigDecimal/ZERO (.stripTrailingZeros ^BigDecimal (:amount m))))

(defn positive?
  "True iff amount > 0."
  [^Money m]
  (pos? (.signum ^BigDecimal (:amount m))))

(defn negative?
  "True iff amount < 0."
  [^Money m]
  (neg? (.signum ^BigDecimal (:amount m))))

(defn same-commodity?
  "True iff both Monies are in the same commodity."
  [^Money a ^Money b]
  (= (:commodity a) (:commodity b)))

(defn- assert-same-commodity
  [^Money a ^Money b op]
  (when-not (same-commodity? a b)
    (throw (ex-info (str "Cross-commodity " op " is forbidden")
                    {:left  {:amount (:amount a) :commodity (:commodity a)}
                     :right {:amount (:amount b) :commodity (:commodity b)}
                     :op    op
                     :hint  "Convert one side first via an FX-rate-aware
                             conversion fn; the kernel never silently
                             coerces between commodities."}))))

;; ============================================================================
;; Arithmetic
;; ============================================================================

(defn add
  "Add two same-commodity Monies. Throws on commodity mismatch."
  ^Money [^Money a ^Money b]
  (assert-same-commodity a b :add)
  (->Money (.add ^BigDecimal (:amount a) ^BigDecimal (:amount b))
           (:commodity a)))

(defn sub
  "Subtract b from a. Same-commodity required."
  ^Money [^Money a ^Money b]
  (assert-same-commodity a b :sub)
  (->Money (.subtract ^BigDecimal (:amount a) ^BigDecimal (:amount b))
           (:commodity a)))

(defn neg
  "Unary negation. Useful for credit-side construction."
  ^Money [^Money m]
  (->Money (.negate ^BigDecimal (:amount m)) (:commodity m)))

(defn mul-scalar
  "Multiply by a unitless scalar (long, BigDecimal, BigInt). Result is
   in the same commodity. The scalar may NOT be a Money (use a tax-rate
   protocol for that)."
  ^Money [^Money m scalar]
  (when (money? scalar)
    (throw (ex-info "mul-scalar expects a unitless scalar, got Money"
                    {:money m :scalar scalar})))
  (->Money (.multiply ^BigDecimal (:amount m) (coerce-amount scalar))
           (:commodity m)))

(defn sum
  "Sum a sequence of same-commodity Monies. Empty sequence requires
   an explicit commodity to construct the zero. Throws on mixed
   commodities."
  ([monies]
   (when (empty? monies)
     (throw (ex-info "sum of empty money sequence requires explicit commodity"
                     {:hint "Pass commodity as second arg: (sum [] :EUR)"})))
   (reduce add monies))
  ([monies commodity]
   (reduce add (zero commodity) monies)))

(defn sum-by-commodity
  "Sum a heterogeneous sequence of Monies, returning a map
   {commodity => Money} with one entry per distinct commodity.
   The double-entry sum-to-zero check uses this — postings of one
   commodity must net to zero independently of postings in other
   commodities."
  [monies]
  (->> monies
       (group-by :commodity)
       (reduce-kv
        (fn [acc commodity ms]
          (assoc acc commodity (sum ms commodity)))
        {})))

;; ============================================================================
;; Rounding
;; ============================================================================

(def ^:const default-rounding-mode RoundingMode/HALF_EVEN)

(def rounding-modes
  "Public alias map for the rounding modes the kernel supports.
   ADR-013 defaults to :half-even (IEEE 754 default; ISO/GAAP financial
   reporting standard). :half-up is available where regulators
   specifically mandate it."
  {:half-even RoundingMode/HALF_EVEN
   :half-up   RoundingMode/HALF_UP
   :half-down RoundingMode/HALF_DOWN
   :ceiling   RoundingMode/CEILING
   :floor     RoundingMode/FLOOR
   :down      RoundingMode/DOWN
   :up        RoundingMode/UP})

(defn round
  "Round a Money to the given fractional precision (default 2 for fiat).
   Mode keyword from `rounding-modes` (default :half-even). Returns a
   new Money with the rounded amount; does not mutate."
  (^Money [^Money m]
   (round m 2 :half-even))
  (^Money [^Money m precision]
   (round m precision :half-even))
  (^Money [^Money m precision mode]
   (let [rm (or (get rounding-modes mode)
                (throw (ex-info "Unknown rounding mode"
                                {:mode mode
                                 :supported (keys rounding-modes)})))]
     (->Money (.setScale ^BigDecimal (:amount m) (int precision) ^RoundingMode rm)
              (:commodity m)))))

;; ============================================================================
;; Display
;; ============================================================================

(defn money->str
  "Human-readable form. Default does not localize — use a separate
   formatter for that. Useful for logs and error messages."
  [^Money m]
  (let [amt (:amount m)
        c   (:commodity m)
        c-str (cond
                (keyword? c) (name c)
                :else        (str c))]
    (str (.toPlainString ^BigDecimal amt) " " c-str)))

;; ============================================================================
;; Datahike interop
;; ============================================================================

(defn money->posting-fragment
  "Decompose a Money into the {:posting/amount :posting/commodity}
   fragment that goes into a posting entity map."
  [^Money m]
  {:posting/amount    (:amount m)
   :posting/commodity (:commodity m)})

(defn- normalize-commodity
  "Datahike returns refs from `d/pull` as `{:db/id N}`; from `d/q` as
   bare eids; and from raw transaction maps as whatever the caller
   put there (keyword, eid, lookup-ref). To make Money equality and
   commodity-matching robust across all three, normalize a
   pulled-shape ref to its bare :db/id; pass everything else through
   unchanged."
  [c]
  (if (and (map? c) (contains? c :db/id) (= 1 (count c)))
    (:db/id c)
    c))

(defn posting->money
  "Inverse: pull a Money out of a posting entity map. Returns nil if
   the posting is missing either :posting/amount or :posting/commodity
   (e.g. a UI-only :note / :section line, or a structurally-broken
   posting that hasn't been validated yet).

   Commodity refs returned by `d/pull` (shape `{:db/id N}`) are
   normalized to bare eids so that summing across pulled and
   constructor-built Monies works without the caller pre-flattening."
  [posting]
  (when (and (:posting/amount posting)
             (:posting/commodity posting))
    (->Money (:posting/amount posting)
             (normalize-commodity (:posting/commodity posting)))))

;; ============================================================================
;; Equality (record-based) — note
;; ============================================================================

;; defrecord gives us value-based equality on (amount commodity).
;; Clojure's `=` delegates to clojure.lang.Util/equiv, which uses
;; Numbers/equiv for BigDecimal — i.e., (= 1.0M 1.00M) is true. So
;; record `=` IS scale-insensitive in the value sense; .equals on
;; the underlying BigDecimal would not be (BigDecimal.equals is
;; scale-sensitive). Callers who need byte-exact storage scale
;; comparison should compare amounts via .equals directly.

(defn equiv?
  "Scale-insensitive value equality, with explicit commodity check.
   Slightly redundant with `=` (which already does value comparison
   via Numbers/equiv) but valuable for two reasons:
     - Surfaces commodity mismatch as the *intended* failure mode in
       call sites that conceptually compare \"amounts in the same
       currency\" rather than \"records by structural equality\".
     - Works for derivations like `(equiv? a (sub b c))` where the
       comparison is the primary intent, not a tested invariant."
  [^Money a ^Money b]
  (and (same-commodity? a b)
       (zero? (sub a b))))

;; ============================================================================
;; Parsing helpers
;; ============================================================================

(defn parse-decimal
  "Parse a numeric string into BigDecimal. Trims, accepts comma OR
   period as decimal separator (German format: \"1.234,56\" → 1234.56;
   English: \"1,234.56\" → 1234.56). Detects which is which by the
   position of the last separator. Throws on garbage."
  ^BigDecimal [s]
  (when (nil? s) (throw (ex-info "Cannot parse nil decimal" {})))
  (let [t (str/trim s)
        last-comma  (.lastIndexOf t ",")
        last-period (.lastIndexOf t ".")
        ;; Whichever appears later is the decimal separator; the other
        ;; is a thousands separator that we strip.
        normalized
        (cond
          (and (neg? last-comma) (neg? last-period)) t
          (> last-comma last-period)
          (-> t (str/replace "." "") (str/replace "," "."))
          :else
          (str/replace t "," ""))]
    (try
      (BigDecimal. ^String normalized)
      (catch NumberFormatException e
        (throw (ex-info "Could not parse decimal" {:input s :normalized normalized}
                        e))))))
