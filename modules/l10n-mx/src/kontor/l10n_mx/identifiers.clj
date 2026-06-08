(ns kontor.l10n-mx.identifiers
  "Mexican identifier validators: RFC, CURP, CLABE.

   - **RFC** (Registro Federal de Contribuyentes): 13 chars for
     personas físicas, 12 chars for personas morales. Format check
     + weighted mod-11 check digit on the last position. Generic
     RFCs (`XAXX010101000`, `XEXX010101000`) are accepted by-name.
   - **CURP** (Clave Única de Registro de Población): 18 chars for
     natural persons. Weighted mod-10 check digit over a 37-symbol
     alphabet on the last position.
   - **CLABE** (Clave Bancaria Estandarizada): 18 digits.
     Alternating-weight mod-10 check digit on the last position.

   Algorithms are mathematical and not copyrightable; this
   implementation is independently derived from public SAT / Banxico
   spec PDFs."
  (:require [clojure.string :as str]))

;; ============================================================================
;; RFC
;; ============================================================================

(def generic-rfcs
  "SAT-issued generic RFCs that always validate:
     XAXX010101000 — domestic anonymous / general public
     XEXX010101000 — foreign receiver"
  #{"XAXX010101000" "XEXX010101000"})

(def ^:private rfc-pm-pattern
  "Persona Moral: 3 letters + YYMMDD + 3-char homoclave (2 alphanumeric
   + 1 numeric check)."
  #"^[A-Z&Ñ]{3}\d{6}[A-Z0-9]{3}$")

(def ^:private rfc-pf-pattern
  "Persona Física: 4 letters + YYMMDD + 3-char homoclave."
  #"^[A-Z&Ñ]{4}\d{6}[A-Z0-9]{3}$")

(def ^:private rfc-alphabet
  "SAT's 38-symbol alphabet for the check-digit computation.
   '0'..'9' map to 0..9; letters and special symbols start at 10.
   Empty / space slot at index 24 ('&' is 24)."
  "0123456789ABCDEFGHIJKLMN&OPQRSTUVWXYZ Ñ")

(defn- rfc-char-value
  "Look up a char's index in the SAT alphabet, throwing if absent."
  [^Character c]
  (let [idx (.indexOf ^String rfc-alphabet (int c))]
    (when (neg? idx)
      (throw (ex-info "RFC char outside SAT alphabet" {:char c})))
    idx))

(defn- rfc-check-digit
  "Compute the expected check-digit (last char) of an RFC.

   Algorithm per SAT's Instructivo de Composición del RFC:
     1. Left-pad to 12 chars with a leading space if PM (input
        length 11 — the 12 chars before the check on a 12-char PM
        RFC) so the algorithm is uniform across PF/PM.
     2. For positions 1..12, multiply each char's alphabet value by
        weight (14 − position) — i.e. weights 13, 12, …, 2.
     3. Sum mod 11. The check digit = (11 − that) mod 11.
        Value 10 maps to 'A'; values 0..9 map to '0'..'9'.

   Returns the expected last character."
  [^String rfc-without-check]
  (let [padded (if (= 11 (count rfc-without-check))
                 (str " " rfc-without-check)   ; PM: pad to 12
                 rfc-without-check)
        sum (->> (range 12)
                 (map (fn [i]
                        (let [c (.charAt padded (int i))
                              v (rfc-char-value c)
                              w (- 13 i)]            ; positions 1..12 → weights 13..2
                          (* v w))))
                 (reduce +))
        rest-mod (mod sum 11)
        cd (mod (- 11 rest-mod) 11)]
    (cond
      (= 10 cd) \A
      :else     (.charAt "0123456789" cd))))

(defn valid-rfc?
  "True iff `s` is a valid RFC. Accepts persona física (13 chars) or
   persona moral (12 chars). Validates structural pattern + mod-11
   check digit. Generic RFCs (`generic-rfcs`) always validate."
  [s]
  (boolean
   (and (string? s)
        (or (contains? generic-rfcs s)
            (let [matches-pattern? (or (re-matches rfc-pf-pattern s)
                                       (re-matches rfc-pm-pattern s))]
              (and matches-pattern?
                   (= (.charAt ^String s (dec (count s)))
                      (rfc-check-digit (subs s 0 (dec (count s)))))))))))

(defn assert-rfc!
  [s]
  (when-not (valid-rfc? s)
    (throw (ex-info "Invalid RFC"
                    {:value s
                     :expected-format "13 chars (persona física) or 12 chars (persona moral) with mod-11 check digit"})))
  s)

;; ============================================================================
;; CURP
;; ============================================================================

(def ^:private curp-pattern
  "18 chars: 4 letters + 6 digits + 1 sex letter (H/M) + 2 state letters
   + 3 internal consonants + 1 alphanumeric homonymy + 1 numeric check."
  #"^[A-Z]{4}\d{6}[HM][A-Z]{2}[A-Z]{3}[A-Z\d]\d$")

(def ^:private curp-alphabet
  "CURP's 37-symbol alphabet for mod-10 check-digit computation.
   '0'..'9' = 0..9; 'A'..'N' = 10..23; 'Ñ' = 24; 'O'..'Z' = 25..36."
  "0123456789ABCDEFGHIJKLMNÑOPQRSTUVWXYZ")

(defn- curp-char-value
  [^Character c]
  (let [idx (.indexOf ^String curp-alphabet (int c))]
    (when (neg? idx)
      (throw (ex-info "CURP char outside alphabet" {:char c})))
    idx))

(defn- curp-check-digit
  "Compute the expected mod-10 check digit (last char) of a CURP.

   Algorithm:
     For positions 1..17, value × (19 − position):
       pos 1 × 18, pos 2 × 17, …, pos 17 × 2
     Sum mod 10. Check digit = (10 − that) mod 10."
  [^String curp-17]
  (let [sum (->> (range 17)
                 (map (fn [i]
                        (let [c (.charAt curp-17 (int i))
                              v (curp-char-value c)
                              w (- 19 (inc i))]
                          (* v w))))
                 (reduce +))
        rest-mod (mod sum 10)
        cd (mod (- 10 rest-mod) 10)]
    (.charAt "0123456789" cd)))

(defn valid-curp?
  [s]
  (boolean
   (and (string? s)
        (re-matches curp-pattern s)
        (= (.charAt ^String s 17)
           (curp-check-digit (subs s 0 17))))))

(defn assert-curp!
  [s]
  (when-not (valid-curp? s)
    (throw (ex-info "Invalid CURP"
                    {:value s
                     :expected-format "18 chars with mod-10 check digit"})))
  s)

;; ============================================================================
;; CLABE
;; ============================================================================

(def ^:private clabe-pattern #"^\d{18}$")

(defn- clabe-check-digit
  "Algorithm: each of first 17 digits × weight from the repeating
   pattern [3 7 1 3 7 1 3 7 1 …], take **only the units digit** of
   each product, sum mod 10, then (10 − that) mod 10."
  [^String clabe-17]
  (let [weights [3 7 1]
        sum (->> (range 17)
                 (map (fn [i]
                        (let [d (- (long (.charAt clabe-17 (int i))) (long \0))
                              w (nth weights (mod i 3))
                              p (* d w)]
                          (mod p 10))))   ; units digit of product
                 (reduce +))
        rest-mod (mod sum 10)]
    (mod (- 10 rest-mod) 10)))

(defn valid-clabe?
  [s]
  (boolean
   (and (string? s)
        (re-matches clabe-pattern s)
        (= (- (long (.charAt ^String s 17)) (long \0))
           (clabe-check-digit (subs s 0 17))))))

(defn clabe-bank-code
  "First 3 digits of a CLABE — the SAT bank code."
  [^String clabe]
  (when (valid-clabe? clabe)
    (subs clabe 0 3)))

(defn assert-clabe!
  [s]
  (when-not (valid-clabe? s)
    (throw (ex-info "Invalid CLABE"
                    {:value s
                     :expected-format "18 digits with alternating-weight mod-10 check"})))
  s)
