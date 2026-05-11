(ns kontor.l10n-br.identifiers
  "Brazilian taxpayer-identifier checksum validators.

   **CPF** — Cadastro de Pessoas Físicas. 11 digits total: 9 base
   digits + 2 mod-11 check digits.

   **CNPJ** — Cadastro Nacional da Pessoa Jurídica. 14 characters
   total: 12 base + 2 mod-11 check digits.

   Both algorithms are mod-11 with position-weighted sums, published
   by Receita Federal in the Cadastro CPF / CNPJ regulations. The
   formulas themselves are mathematical and not copyrightable; the
   implementation here is independently derived from RFB's public
   procedure (não-código RFB).

   ## CNPJ alfanumérico (Resolução RFB 2.229/2024)

   Effective for new registrations from 2026-07. The 12 base
   characters may include uppercase letters A–Z; the 2 check digits
   remain numeric. Per RFB Nota Técnica COCAD/SUARA 49/2024, each
   character's value in the mod-11 sum is
     `(- (int c) (int \\0))`
   which gives '0'..'9' → 0..9 and 'A'..'Z' → 17..42 (ord('A')=65,
   ord('0')=48). The same position weights and mod-11 formula apply.

   Numeric-only CNPJs issued before 2026-07 continue to validate
   under this implementation because the per-character value
   reduces to the digit's face value for '0'..'9'.

   Known invalid 'mathematically-valid-but-blacklisted' values:
     - CPF: all-same-digit strings (11111111111, …, 99999999999,
       and 00000000000) pass the checksum but are blacklisted.
     - CNPJ: same situation (extended to all-same-character).

   Reference sources (RFB-published, in Portuguese):
     - Instrução Normativa RFB nº 1.183/2011 (CPF)
     - Instrução Normativa RFB nº 1.863/2018 (CNPJ, numeric)
     - Instrução Normativa RFB nº 2.229, de 15/10/2024 (CNPJ
       alfanumérico — CGSN governs Simples Nacional, not CNPJ;
       CNPJ is RFB-only)
     - Nota Técnica COCAD/SUARA/RFB nº 49/2024 (per-char value
       formula ord(c) − ord('0'))

   ## API

     valid-cpf?  s → bool
     valid-cnpj? s → bool
     format-cpf  s → \"XXX.XXX.XXX-XX\"
     format-cnpj s → \"XX.XXX.XXX/XXXX-XX\"
     strip       s → digits-only string (CPF — numeric only)

   Each fn accepts either raw or formatted input."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- digits
  "Strip non-digit characters; return the digits-only string."
  ^String [^String s]
  (when s (str/replace s #"\D" "")))

(defn strip [s] (digits s))

(defn- alphanumerics
  "Strip non-alphanumeric characters; uppercase result. Used by the
   CNPJ flow (which may include letters under Res. 2.229/2024).
   Numeric-only inputs are unaffected."
  ^String [^String s]
  (when s (-> s str/upper-case (str/replace #"[^0-9A-Z]" ""))))

(defn- digit-vec
  "[String] → [Long] of decimal digits."
  [s]
  (mapv #(- (long %) (long \0)) s))

(defn- alnum-vec
  "[String of 0-9A-Z] → [Long] using the RFB-NT 49/2024 per-character
   value formula: (ord c) - (ord \\0)."
  [s]
  (mapv #(- (long %) (long \0)) s))

(defn- digit-char? [^Character c]
  (and (>= (long c) (long \0)) (<= (long c) (long \9))))

(defn- all-same?
  "True iff all chars are the same."
  [^String s]
  (and (pos? (count s))
       (every? #(= (.charAt s 0) %) s)))

(defn- check-digit
  "Mod-11 check digit for a vector of digits + per-position weights.
   Returns 0 when (sum mod 11) < 2; else (11 - (sum mod 11))."
  [digits weights]
  (let [sum (reduce + (map * digits weights))
        rest (mod sum 11)]
    (if (< rest 2) 0 (- 11 rest))))

;; ============================================================================
;; CPF
;; ============================================================================

(def ^:private cpf-weights-1 [10 9 8 7 6 5 4 3 2])
(def ^:private cpf-weights-2 [11 10 9 8 7 6 5 4 3 2])

(defn valid-cpf?
  "True iff `s` is a valid CPF. Accepts either raw 11-digit string
   or formatted 'XXX.XXX.XXX-XX'."
  [s]
  (let [d (digits s)]
    (and (= 11 (count d))
         (not (all-same? d))
         (let [ds (digit-vec d)
               base (subvec ds 0 9)
               dv1-expected (check-digit base cpf-weights-1)
               dv2-expected (check-digit (conj base dv1-expected) cpf-weights-2)
               dv1-actual (nth ds 9)
               dv2-actual (nth ds 10)]
           (and (= dv1-expected dv1-actual)
                (= dv2-expected dv2-actual))))))

(defn format-cpf
  "Format a digits-only CPF as 'XXX.XXX.XXX-XX'."
  [s]
  (let [d (digits s)]
    (when (= 11 (count d))
      (str (subs d 0 3) "."
           (subs d 3 6) "."
           (subs d 6 9) "-"
           (subs d 9 11)))))

;; ============================================================================
;; CNPJ
;; ============================================================================

(def ^:private cnpj-weights-1 [5 4 3 2 9 8 7 6 5 4 3 2])
(def ^:private cnpj-weights-2 [6 5 4 3 2 9 8 7 6 5 4 3 2])

(defn valid-cnpj?
  "True iff `s` is a valid CNPJ. Accepts:
     - raw 14-char string (digits, or digits + uppercase letters per
       Res. 2.229/2024)
     - formatted 'XX.XXX.XXX/XXXX-XX' (digits or alphanumeric)

   Per RFB Res. 2.229/2024 (effective 2026-07): the 12 base
   characters may include uppercase A–Z; the trailing 2 check digits
   remain numeric. Per-character value in the mod-11 sum is
   (ord c) − (ord \\0)."
  [s]
  (let [d (alphanumerics s)]
    (and (= 14 (count d))
         (not (all-same? d))
         ;; The two check digits must remain numeric.
         (digit-char? (.charAt d 12))
         (digit-char? (.charAt d 13))
         (let [ds (alnum-vec d)
               base (subvec ds 0 12)
               dv1-expected (check-digit base cnpj-weights-1)
               dv2-expected (check-digit (conj base dv1-expected) cnpj-weights-2)
               dv1-actual (- (long (.charAt d 12)) (long \0))
               dv2-actual (- (long (.charAt d 13)) (long \0))]
           (and (= dv1-expected dv1-actual)
                (= dv2-expected dv2-actual))))))

(defn format-cnpj
  "Format a 14-char (numeric or alphanumeric) CNPJ as
   'XX.XXX.XXX/XXXX-XX'."
  [s]
  (let [d (alphanumerics s)]
    (when (= 14 (count d))
      (str (subs d 0 2) "."
           (subs d 2 5) "."
           (subs d 5 8) "/"
           (subs d 8 12) "-"
           (subs d 12 14)))))

(defn assert-cpf!
  "Throws ex-info on invalid CPF; returns the input on success."
  [s]
  (when-not (valid-cpf? s)
    (throw (ex-info "Invalid CPF" {:value s})))
  s)

(defn assert-cnpj!
  "Throws ex-info on invalid CNPJ; returns the input on success."
  [s]
  (when-not (valid-cnpj? s)
    (throw (ex-info "Invalid CNPJ" {:value s})))
  s)
