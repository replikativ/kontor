(ns kontor.l10n-au.identifiers
  "Australian business-identifier validators.

   Two identifiers are in scope — every B2B invoice and ATO filing
   in Australia references at least one of them:

   - **ABN** (Australian Business Number) — 11-digit identifier issued
     by the Australian Business Register (ABR) to every business that
     registers with the ATO. The leading-digit-minus-one is a
     deliberate design choice (so that no real-world numeric like a
     bare TFN can validate as an ABN). GST registration is **via the
     ABN** — there is no separate GST registration number in Australia;
     a GST-registered business is identified by its ABN alone.

   - **ACN** (Australian Company Number) — 9-digit identifier issued
     by the Australian Securities and Investments Commission (ASIC) to
     every registered company at incorporation. Sole traders /
     partnerships / trusts do NOT have an ACN; only companies. When a
     company also registers for GST, ASIC's ACN is embedded as digits
     3..11 of the ABN (i.e. ABN = `<2-digit ABN-prefix><9-digit ACN>`).

   ## ABN check-digit algorithm

   Per the ABR public spec at:
     https://abr.business.gov.au/Help/AbnFormat

   1. Subtract 1 from the FIRST digit of the 11-digit candidate.
   2. Multiply each (modified) digit by its position weight, where
      the weight vector left-to-right is
        [10 1 3 5 7 9 11 13 15 17 19]
   3. Sum the products.
   4. The candidate is valid iff the sum modulo 89 equals 0.

   Detects all single-digit errors and almost all adjacent
   transposition errors.

   ## ACN check-digit algorithm

   Per ASIC's documented algorithm (s.1346 Corporations Act 2001
   refers; the check is published in ASIC's Form 410 instructions
   and replicated on multiple regulator-side cross-checks):

   1. Multiply each of the first 8 digits by its position weight,
      where the left-to-right weight vector is
        [8 7 6 5 4 3 2 1]
   2. Sum the products.
   3. Compute (10 - (sum mod 10)) mod 10 — the expected check digit.
   4. The 9th digit must equal the expected check digit.

   This is a simple weighted-sum-mod-10 — structurally similar to
   Luhn but with explicit positional weights rather than alternating
   doubling.

   Algorithms are mathematical and not copyrightable; this
   implementation is independently derived from public regulator
   documentation. We do not bundle any production ABN / ACN values;
   all test fixtures are synthetic (the trailing check digit is
   computed from the leading base).

   ## API

     valid-abn?         s → bool
     parse-abn          s → {:abn :acn?} | nil
     assert-abn!        s → s | throws
     valid-acn?         s → bool
     parse-acn          s → {:acn} | nil
     assert-acn!        s → s | throws

   See ADR-006 (l10n module boundaries) and CLAUDE.md for the
   per-country substrate convention this module follows."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- strip-formatting
  "Strip whitespace — the only formatting character the ABR / ASIC
   print around ABN / ACN values (e.g. `12 345 678 901` for ABN,
   `123 456 789` for ACN). We also tolerate hyphens for parity with
   the CA / DE modules."
  ^String [^String s]
  (when s (-> s (str/replace #"[\s\-]" ""))))

(defn- digit-vec
  "Convert a digit string to a vector of integer values 0..9."
  [^String s]
  (mapv #(- (int %) (int \0)) s))

(defn- all-digits? [^String s]
  (and (some? s)
       (pos? (count s))
       (every? #(<= (int \0) (int %) (int \9)) s)))

;; ============================================================================
;; ABN — 11-digit, weighted mod-89 check
;; ============================================================================

(def ^:private abn-weights
  "Position weights for the ABN check, left-to-right (positions
   1..11). The first weight is 10; the rest follow odd integers
   starting at 1."
  [10 1 3 5 7 9 11 13 15 17 19])

(defn- abn-checksum
  "Compute the ABR mod-89 checksum for an 11-digit ABN. The first
   digit is decremented by 1 before weighting; the candidate is
   valid iff the sum modulo 89 is 0."
  [digits]
  (let [adjusted (assoc digits 0 (dec (nth digits 0)))
        weighted (map * adjusted abn-weights)
        s (reduce + weighted)]
    (mod s 89)))

(defn valid-abn?
  "True iff `s` is a structurally-valid Australian Business Number:
   exactly 11 digits AND the ABR weighted mod-89 check passes.

   Accepts:
     - bare 11-digit string (`12345678901`)
     - spaced form `XX XXX XXX XXX` (the ABR's canonical print form)
     - hyphenated form (some legacy correspondence)

   Returns `false` for nil, non-strings, wrong length, non-digit
   content, a leading-digit of 0 (which after the -1 step would be
   -1 and is treated as invalid per the ABR spec), or a failing
   check.

   Reference: https://abr.business.gov.au/Help/AbnFormat"
  [s]
  (boolean
   (and (string? s)
        (let [d (strip-formatting (str/trim s))]
          (and (= 11 (count d))
               (all-digits? d)
               (let [ds (digit-vec d)]
                 ;; The ABR spec implies the leading digit must be
                 ;; >= 1 for the (digit-1) step to land in 0..8.
                 ;; Zero leading digit is treated as invalid.
                 (and (pos? (nth ds 0))
                      (zero? (abn-checksum ds)))))))))

(defn parse-abn
  "Decompose a valid ABN into its components:

     {:abn   <11-digit canonical bare form>
      :acn   <9-digit embedded ACN or nil>}

   When the ABN was issued to a company, digits 3..11 of the ABN
   equal the company's ACN. parse-abn returns that embedded value
   ONLY when its trailing 9 digits also pass the ACN check — this is
   a structural hint, not a guarantee that the issuer is a company
   (the embedding rule is documented but not universally enforced).

   Returns nil if `s` is not structurally valid."
  [s]
  (when (valid-abn? s)
    (let [d (strip-formatting (str/trim s))
          tail (subs d 2 11)
          ;; Re-check the embedded 9-digit tail as a potential ACN.
          ds (digit-vec tail)
          weights [8 7 6 5 4 3 2 1]
          sum (reduce + (map * (subvec ds 0 8) weights))
          expected (mod (- 10 (mod sum 10)) 10)
          acn? (= (nth ds 8) expected)]
      {:abn d
       :acn (when acn? tail)})))

(defn assert-abn!
  "Throws ex-info on a structurally-invalid ABN; returns the input on
   success."
  [s]
  (when-not (valid-abn? s)
    (throw (ex-info "Invalid Australian Business Number (ABN)"
                    {:value s
                     :expected-format "11 digits, ABR weighted mod-89 check"})))
  s)

;; ============================================================================
;; ACN — 9-digit, weighted mod-10 check
;; ============================================================================

(def ^:private acn-weights
  "Position weights for the ACN check, left-to-right (positions
   1..8). The 9th (trailing) digit is the check, NOT weighted."
  [8 7 6 5 4 3 2 1])

(defn- acn-check-digit
  "Compute the expected ACN check digit for an 8-digit base."
  [base-digits]
  (let [sum (reduce + (map * base-digits acn-weights))]
    (mod (- 10 (mod sum 10)) 10)))

(defn valid-acn?
  "True iff `s` is a structurally-valid Australian Company Number:
   exactly 9 digits AND the 9th digit equals the ASIC weighted
   mod-10 check of the first 8.

   Accepts:
     - bare 9-digit string (`123456789`)
     - spaced form `XXX XXX XXX` (ASIC's canonical print form)
     - hyphenated form

   Returns `false` for nil, non-strings, wrong length, non-digit
   content, or wrong check digit."
  [s]
  (boolean
   (and (string? s)
        (let [d (strip-formatting (str/trim s))]
          (and (= 9 (count d))
               (all-digits? d)
               (let [ds (digit-vec d)
                     base (subvec ds 0 8)
                     actual (nth ds 8)
                     expected (acn-check-digit base)]
                 (= actual expected)))))))

(defn parse-acn
  "Decompose a valid ACN into its component map:

     {:acn  <9-digit canonical bare form>}

   Returns nil if `s` is not structurally valid."
  [s]
  (when (valid-acn? s)
    {:acn (strip-formatting (str/trim s))}))

(defn assert-acn!
  "Throws ex-info on a structurally-invalid ACN; returns the input on
   success."
  [s]
  (when-not (valid-acn? s)
    (throw (ex-info "Invalid Australian Company Number (ACN)"
                    {:value s
                     :expected-format "9 digits, ASIC weighted mod-10 check"})))
  s)
