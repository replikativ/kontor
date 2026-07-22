(ns kontor.l10n-in.identifiers
  "Indian taxpayer-identifier validators.

   - **GSTIN** (15 chars): state-code(2) + PAN(10) + entity-number(1)
     + literal 'Z' + check-char(1). Mod-36 alphabetic checksum (Luhn
     variant). Single-char and most transposition errors detected.
   - **PAN** (10 chars): 5 letters + 4 digits + 1 letter. Position 4
     is the entity type (P/C/H/F/A/T/B/L/J/G). No checksum.
   - **TAN** (10 chars): 4 letters + 5 digits + 1 letter. No checksum.

   The formulas themselves are public-domain CBIC procedure; this
   implementation is independently derived from the published spec.

   References:
     - GSTIN structure: CBIC notification 32/2017-Central Tax
     - PAN structure: PAN regulations under Income Tax Act
     - GSTIN check-digit algorithm: documented at
       https://medium.com/@dhananjaygokhale/decoding-gst-number-checksum-digit-1ef2c8c53ad6"
  (:require [clojure.string :as str]))

;; ============================================================================
;; PAN
;; ============================================================================

(def ^:private pan-pattern #"^[A-Z]{5}[0-9]{4}[A-Z]$")

(def pan-entity-types
  "Position-4 character → entity-type semantics, per PAN regulations."
  {\P "Individual"
   \C "Company"
   \H "HUF (Hindu Undivided Family)"
   \F "Firm / LLP"
   \A "AOP (Association of Persons)"
   \T "Trust"
   \B "BOI (Body of Individuals)"
   \L "Local Authority"
   \J "Artificial Juridical Person"
   \G "Government"})

(defn valid-pan?
  "True iff `s` matches the PAN structural pattern AND the entity-type
   character (position 4) is in `pan-entity-types`."
  [s]
  (boolean
   (and (string? s)
        (re-matches pan-pattern s)
        (contains? pan-entity-types (.charAt ^String s 3)))))

(defn pan-entity-type
  "Return the human-readable entity type for a PAN, or nil."
  [s]
  (when (valid-pan? s)
    (get pan-entity-types (.charAt ^String s 3))))

(defn assert-pan!
  "Throws on invalid PAN, returns the input on success."
  [s]
  (when-not (valid-pan? s)
    (throw (ex-info "Invalid PAN"
                    {:value s
                     :expected-format "5 letters + 4 digits + 1 letter, with valid entity-type at position 4"})))
  s)

;; ============================================================================
;; TAN
;; ============================================================================

(def ^:private tan-pattern #"^[A-Z]{4}[0-9]{5}[A-Z]$")

(defn valid-tan?
  "Pattern check only — TAN has no published checksum."
  [s]
  (boolean (and (string? s) (re-matches tan-pattern s))))

(defn assert-tan!
  [s]
  (when-not (valid-tan? s)
    (throw (ex-info "Invalid TAN"
                    {:value s
                     :expected-format "4 letters + 5 digits + 1 letter"})))
  s)

;; ============================================================================
;; GSTIN
;; ============================================================================

(def ^:private gstin-pattern
  "Position 13 is 1-9 then A-Z (entity-number, up to 35 per PAN).
   Position 14 is the literal 'Z'.
   Position 15 is the check character (alphanumeric)."
  #"^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]$")

(defn- char-value
  "Mod-36 char value: '0'..'9' → 0..9, 'A'..'Z' → 10..35."
  [^Character c]
  (let [ic (int c)
        v  (cond
             (and (>= ic 48) (<= ic 57)) (- ic 48)             ; '0'..'9'
             (and (>= ic 65) (<= ic 90)) (+ 10 (- ic 65))      ; 'A'..'Z'
             :else (throw (ex-info "Char outside mod-36 alphabet" {:char c})))]
    v))

(defn- value->char
  "Inverse of char-value: 0..9 → '0'..'9', 10..35 → 'A'..'Z'."
  ^Character [v]
  (cond
    (and (>= v 0) (<= v 9))   (char (+ (int \0) v))
    (and (>= v 10) (<= v 35)) (char (+ (int \A) (- v 10)))
    :else (throw (ex-info "Value outside mod-36 alphabet" {:value v}))))

(defn gstin-check-char
  "Compute the mod-36 check character for a 14-character GSTIN prefix.

   Algorithm: alternate multiplier 1 and 2 across positions 1..14
   (starting multiplier 1). Each char's mod-36 value × multiplier
   yields a product; if product ≥ 36, sum its base-36 digits
   (equivalent to product − 36). Sum across all 14 positions; the
   check value = (36 − (sum mod 36)) mod 36."
  [^String prefix-14]
  (when-not (= 14 (count prefix-14))
    (throw (ex-info "gstin-check-char expects a 14-char prefix" {:prefix prefix-14})))
  (let [sum
        (->> (range 14)
             (map (fn [i]
                    (let [c (.charAt prefix-14 (int i))
                          v (char-value c)
                          mult (if (odd? i) 2 1)         ; positions 1-indexed: 1,3,5… get ×1
                          prod (* v mult)
                          ;; Luhn-mod-36 digit collapse: when product ≥ 36, replace
                          ;; with the sum of its base-36 digits. Since prod ≤ 70,
                          ;; base-36 digits are 1 and (prod - 36), summing to
                          ;; 1 + prod - 36 = prod - 35.
                          collapsed (if (>= prod 36) (- prod 35) prod)]
                      collapsed)))
             (reduce +))]
    (value->char (mod (- 36 (mod sum 36)) 36))))

(defn valid-gstin?
  "True iff `s` is structurally a GSTIN AND the check character matches."
  [s]
  (boolean
   (and (string? s)
        (re-matches gstin-pattern s)
        (= (.charAt ^String s 14)
           (gstin-check-char (subs s 0 14))))))

(defn gstin-state-code
  "The leading 2-digit state code. Maps to :kontor.state-code/code under
   `:kontor.state-code/regulator :in/gst`."
  [^String gstin]
  (when (valid-gstin? gstin)
    (subs gstin 0 2)))

(defn gstin-pan
  "Extract the embedded PAN (chars 3..12). The PAN inside a GSTIN is
   not check-validated separately — its presence is structural."
  [^String gstin]
  (when (valid-gstin? gstin)
    (subs gstin 2 12)))

(defn gstin-entity-number
  "The 13th character — 1..9 then A..Z, supporting up to 35
   registrations per PAN."
  [^String gstin]
  (when (valid-gstin? gstin)
    (str (.charAt gstin 12))))

(defn assert-gstin!
  [s]
  (when-not (valid-gstin? s)
    (throw (ex-info "Invalid GSTIN"
                    {:value s
                     :expected-format "15 chars: 2-digit state + 10-char PAN + entity-number + 'Z' + mod-36 check char"})))
  s)
