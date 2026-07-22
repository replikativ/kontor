(ns kontor.l10n-ca.identifiers
  "Canadian taxpayer-identifier validators.

   Three identifier shapes are in scope — every CRA filing and most
   B2B invoices in Canada reference at least one of them:

   - **Business Number (BN9)** — the 9-digit identifier the CRA issues
     to every Canadian business at incorporation / first program
     registration. 8 base digits + 1 Luhn check digit (the same
     algorithm used by credit cards, IMEI, and SIN — published by
     ISO/IEC 7812-1). All CRA program accounts hang off a single BN9.

   - **Program account (BN15)** — full 15-character identifier of the
     form `<BN9><PROGRAM><REFERENCE>`. The 2-character program
     identifier marks which CRA program this account is for:

         RT  — GST/HST (Goods and Services Tax / Harmonized ST)
         RP  — Payroll (T4 / source deductions)
         RC  — Corporation income tax (T2)
         RM  — Import / Export (customs)
         RR  — Registered charity (T3010)
         RZ  — Information returns (T5, T5018, etc.)

     The 4-digit reference number distinguishes multiple accounts of
     the same program type (e.g. a business with two divisions might
     have RT0001 and RT0002). New accounts start at 0001.

   - **GST/HST number** — convenience alias for `<BN9>RT<NNNN>`. This
     is the identifier most B2B invoices print and that CRA's GST/HST
     NETFILE accepts. Validating a GST/HST number is the same as
     validating a BN15 whose program identifier is RT.

   Algorithms are mathematical and not copyrightable; this
   implementation is independently derived from the public CRA
   documentation and ISO 7812-1:

     - https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/registering-your-business/business-number.html
     - https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/registering-your-business/bn-structure.html
     - ISO/IEC 7812-1:2017 — Luhn check digit (clause 5)

   ## API

     valid-business-number? s → bool
     valid-program-account? s → bool
     valid-gst-hst-number?  s → bool
     parse-program-account  s → {:bn :program :reference} | nil
     assert-business-number! s → s | throws
     assert-program-account! s → s | throws

   See ADR-006 (l10n module boundaries) and CLAUDE.md for the
   per-country substrate convention this module follows."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- strip-formatting
  "Strip whitespace and hyphens — the only formatting characters the
   CRA prints around BN9 / BN15 values (e.g. `123 456 789 RT 0001`)."
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
;; Business Number (BN9) — Luhn check digit
;; ============================================================================
;;
;; CRA BN9 uses the standard Luhn algorithm (ISO/IEC 7812-1):
;;   1. Append a placeholder 0 to the right of the 8 base digits.
;;   2. From the rightmost (placeholder) position counting left
;;      (positions 1, 2, 3, …), double every digit at an even
;;      position.
;;   3. If a doubled digit is > 9, sum its decimal digits
;;      (equivalent to subtracting 9).
;;   4. Sum all (possibly-doubled) digit values.
;;   5. The check digit is `(10 - (sum mod 10)) mod 10` — i.e. the
;;      value that makes the whole 9-digit number Luhn-valid.
;;
;; Equivalently, given only the 8 base digits indexed 0..7 (i = 0 is
;; leftmost), positions-from-right is `7 - i`, and we double when
;; that position is EVEN (which means doubling positions 1, 3, 5, 7
;; from the right of the full 9-digit number — every other digit
;; starting from the placeholder's left neighbour).

(defn- luhn-check-digit
  "Compute the Luhn check digit for a vector of base digits."
  [digits]
  (let [n (count digits)
        weighted (map-indexed
                  (fn [i d]
                    (let [pos-from-right (- (dec n) i)
                          double? (even? pos-from-right)
                          v (if double? (* 2 d) d)]
                      (if (> v 9) (- v 9) v)))
                  digits)
        s (reduce + weighted)]
    (mod (- 10 (mod s 10)) 10)))

(defn valid-business-number?
  "True iff `s` is a structurally-valid CRA Business Number (BN9):
   exactly 9 digits AND the 9th digit is the Luhn check of the first
   8.

   Accepts:
     - bare 9-digit string (`123456782`)
     - spaced form (`123 456 782`) — CRA prints this on correspondence
     - hyphen-separated form (`123-456-782`)

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
                     expected (luhn-check-digit base)]
                 (= actual expected)))))))

(defn assert-business-number!
  "Throws ex-info on an invalid BN9; returns the input on success."
  [s]
  (when-not (valid-business-number? s)
    (throw (ex-info "Invalid CRA Business Number"
                    {:value s
                     :expected-format "9 digits, 9th is Luhn check of first 8"})))
  s)

;; ============================================================================
;; Program account (BN15)
;; ============================================================================
;;
;; Shape: <BN9><PROGRAM><REFERENCE>
;;   BN9        — 9-digit Business Number (Luhn-valid per above)
;;   PROGRAM    — 2-character program identifier (uppercase letters)
;;   REFERENCE  — 4-digit account index, starting at 0001

(def program-identifiers
  "Map of CRA program-identifier code → human-readable program name.
   Per CRA's published BN-structure documentation."
  {"RT" "GST/HST"
   "RP" "Payroll"
   "RC" "Corporation income tax"
   "RM" "Import / Export (customs)"
   "RR" "Registered charity"
   "RZ" "Information returns"})

(def ^:private program-pattern #"^[A-Z]{2}$")
(def ^:private reference-pattern #"^\d{4}$")

(defn- split-program-account
  "Decompose a stripped 15-char string into [bn program reference].
   Returns nil if the length isn't 15."
  [^String d]
  (when (= 15 (count d))
    [(subs d 0 9) (subs d 9 11) (subs d 11 15)]))

(defn valid-program-account?
  "True iff `s` is a structurally-valid CRA program account (BN15):
   a valid 9-digit BN followed by a 2-letter program identifier and
   a 4-digit reference number.

   Accepts:
     - bare 15-char string (`123456782RT0001`)
     - spaced form (`123456782 RT 0001`)
     - hyphen-separated form (`123456782-RT0001`)

   The program identifier is uppercase-only; lowercase is rejected
   to keep parsing unambiguous. Whitespace at either end and
   between the three components is tolerated and stripped.

   Returns `false` for nil, non-strings, wrong length, unknown
   program letters, non-digit reference, or invalid embedded BN9."
  [s]
  (boolean
   (and (string? s)
        (let [d (strip-formatting (str/trim s))]
          (when-let [[bn program reference] (split-program-account d)]
            (and (valid-business-number? bn)
                 (re-matches program-pattern program)
                 (re-matches reference-pattern reference)))))))

(defn valid-gst-hst-number?
  "True iff `s` is a valid GST/HST program account — a BN15 whose
   program identifier is `RT`. The same identifier is sometimes
   called a 'GST/HST registration number' on B2B invoices."
  [s]
  (boolean
   (and (valid-program-account? s)
        (let [d (strip-formatting (str/trim s))]
          (= "RT" (subs d 9 11))))))

(defn parse-program-account
  "Decompose a valid BN15 into its components:

     {:bn        <9-digit BN>
      :program   <2-letter program identifier>
      :program-name <human label or nil if unknown>
      :reference <4-digit reference>}

   Returns nil if `s` is not structurally valid."
  [s]
  (when (valid-program-account? s)
    (let [d (strip-formatting (str/trim s))
          [bn program reference] (split-program-account d)]
      {:bn bn
       :program program
       :program-name (get program-identifiers program)
       :reference reference})))

(defn assert-program-account!
  "Throws ex-info on an invalid BN15; returns the input on success."
  [s]
  (when-not (valid-program-account? s)
    (throw (ex-info "Invalid CRA program account"
                    {:value s
                     :expected-format "BN9 + 2-letter program + 4-digit reference (e.g. 123456782RT0001)"})))
  s)
