(ns kontor.l10n-fr.identifiers
  "French business-identifier validators.

   Three identifier shapes are in scope — every French B2B invoice and
   most filings reference at least one:

   - **SIREN** — 9-digit identifier the INSEE assigns to every French
     legal entity (company, association, EIG, etc.) at incorporation.
     The Système Informatique pour le Répertoire des ENtreprises is
     the official register. 8 base digits + 1 **Luhn (mod-10)** check
     digit — the same algorithm used by credit cards, IMEI, and the
     CRA Business Number (ISO/IEC 7812-1).

   - **SIRET** — 14-digit identifier of an *establishment* (a physical
     location belonging to the legal entity). Shape: `<SIREN><NIC>`
     where the NIC (Numéro Interne de Classement) is a 5-digit serial
     INSEE assigns per establishment. SIRET also uses Luhn — over the
     full 14 digits, NOT recomputed independently from the NIC. A
     valid SIRET implies a valid SIREN, but the two Luhn checks are
     independent.

     Exception: La Poste's SIREN `356000000` is reserved; its SIRETs
     historically used a different check (sum of digits ≡ 0 mod 5).
     We accept both schemes for that SIREN; otherwise standard Luhn.

   - **TVA intracommunautaire** — pan-EU VAT identifier. French shape:
     `FR` + 2-character check key + SIREN. The check key is
     **`((SIREN × 100) + 12) mod 97`** rendered as 2 digits with a
     leading zero when < 10. The key may contain an uppercase letter
     in rare historical cases (mixed alphanumeric form), but the
     all-numeric form is the only one INSEE issues today. We accept
     the numeric form and validate the check arithmetic.

   Algorithms are mathematical and not copyrightable; this
   implementation is independently derived from the public INSEE +
   DGFiP documentation and ISO 7812-1:

     - INSEE — SIREN / SIRET structure:
       https://www.insee.fr/fr/information/2406147
     - economie.gouv.fr — TVA intracommunautaire algorithm:
       https://www.economie.gouv.fr/cedef/numero-de-tva-intracommunautaire
     - ISO/IEC 7812-1:2017 — Luhn check digit (clause 5)

   ## API

     valid-siren?     s → bool
     valid-siret?     s → bool
     valid-tva-fr?    s → bool
     parse-siren      s → {:siren} | nil
     parse-siret      s → {:siren :nic} | nil
     parse-tva-fr     s → {:key :siren} | nil
     assert-siren!    s → s | throws
     assert-siret!    s → s | throws
     assert-tva-fr!   s → s | throws

   See ADR-006 (l10n module boundaries) and CLAUDE.md for the
   per-country substrate convention this module follows."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- strip-formatting
  "Strip whitespace and hyphens — INSEE prints SIREN/SIRET with spaces
   every three digits (`123 456 782` / `123 456 782 00012`)."
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
;; Luhn (ISO/IEC 7812-1) — used by SIREN and SIRET
;; ============================================================================
;;
;; The standard right-to-left scheme: starting from the rightmost
;; digit (the check digit) at position 1, double every digit at an
;; EVEN position (positions 2, 4, 6, …). Sum of (doubled-and-cast-
;; to-single-digit + non-doubled) ≡ 0 mod 10.

(defn- luhn-valid?
  "True iff `digits` (a vector of integer digit values, length ≥ 2)
   satisfies the Luhn checksum."
  [digits]
  (let [n (count digits)
        s (->> digits
               (map-indexed
                (fn [i d]
                  (let [pos-from-right (- n i)
                        double? (even? pos-from-right)
                        v (if double? (* 2 d) d)]
                    (if (> v 9) (- v 9) v))))
               (reduce +))]
    (zero? (mod s 10))))

;; ============================================================================
;; SIREN — 9 digits, Luhn check
;; ============================================================================

(defn valid-siren?
  "True iff `s` is a structurally-valid French SIREN: exactly 9 digits
   and Luhn-valid.

   Accepts:
     - bare 9-digit string (`732829320`)
     - spaced form (`732 829 320`) — INSEE prints this on the K-bis
     - hyphen-separated form (rare; tolerated for paste-friendliness)

   Returns `false` for nil, non-strings, wrong length, non-digit
   content, or wrong check digit."
  [s]
  (boolean
   (and (string? s)
        (let [d (strip-formatting (str/trim s))]
          (and (= 9 (count d))
               (all-digits? d)
               (luhn-valid? (digit-vec d)))))))

(defn parse-siren
  "Decompose a valid SIREN into its canonical pieces.

   Returns:
     {:siren <9-digit canonical string>}
   or nil if `s` is not structurally valid."
  [s]
  (when (valid-siren? s)
    {:siren (strip-formatting (str/trim s))}))

(defn assert-siren!
  "Throws ex-info on an invalid SIREN; returns the input on success."
  [s]
  (when-not (valid-siren? s)
    (throw (ex-info "Invalid SIREN"
                    {:value s
                     :expected-format "9 digits, Luhn-valid (ISO/IEC 7812-1)"})))
  s)

;; ============================================================================
;; SIRET — 14 digits, Luhn check over the full 14
;; ============================================================================
;;
;; Shape: <SIREN (9)><NIC (5)>
;;
;; The check is Luhn over the full 14 digits, NOT a separate check
;; over the NIC. A valid SIREN does not imply a valid SIRET (the NIC
;; affects the global checksum), and conversely a Luhn-valid SIRET
;; implies a Luhn-valid SIREN substring only when the embedded SIREN
;; was itself Luhn-valid at issuance — INSEE guarantees both.
;;
;; La Poste's `356000000` SIRETs historically used a custom check
;; (sum of all 14 digits ≡ 0 mod 5) — see the INSEE doc footnote.
;; We accept that scheme for the La Poste SIREN, and standard Luhn
;; otherwise.

(def ^:private la-poste-siren "356000000")

(defn- la-poste-siret?
  "True iff `digits` are a valid SIRET under La Poste's mod-5 scheme.
   Applies only to SIRETs whose SIREN prefix is `356000000`."
  [digits]
  (and (= 14 (count digits))
       (= la-poste-siren (apply str (subvec digits 0 9)))
       (zero? (mod (reduce + digits) 5))))

(defn valid-siret?
  "True iff `s` is a structurally-valid French SIRET: exactly 14
   digits, and either Luhn-valid (standard) or La-Poste-mod-5 valid
   (special case for SIREN 356000000).

   Accepts:
     - bare 14-digit string (`73282932000074`)
     - INSEE's spaced form (`732 829 320 00074`)
     - hyphen-separated form (rare)

   Returns `false` for nil, non-strings, wrong length, non-digit
   content, or wrong check digit."
  [s]
  (boolean
   (and (string? s)
        (let [d (strip-formatting (str/trim s))]
          (and (= 14 (count d))
               (all-digits? d)
               (let [ds (digit-vec d)]
                 (or (luhn-valid? ds)
                     (la-poste-siret? ds))))))))

(defn parse-siret
  "Decompose a valid SIRET into SIREN + NIC.

   Returns:
     {:siren <9-digit canonical string>
      :nic   <5-digit canonical string>}
   or nil if `s` is not structurally valid."
  [s]
  (when (valid-siret? s)
    (let [d (strip-formatting (str/trim s))]
      {:siren (subs d 0 9)
       :nic   (subs d 9 14)})))

(defn assert-siret!
  "Throws ex-info on an invalid SIRET; returns the input on success."
  [s]
  (when-not (valid-siret? s)
    (throw (ex-info "Invalid SIRET"
                    {:value s
                     :expected-format "14 digits = SIREN(9) + NIC(5), Luhn-valid"})))
  s)

;; ============================================================================
;; TVA intracommunautaire — FR + key(2) + SIREN(9)
;; ============================================================================
;;
;; Numeric check key: ((SIREN × 100) + 12) mod 97, rendered as 2
;; digits with leading zero. Note that mathematically this is
;; equivalent to (12 + 3 × (SIREN mod 97)) mod 97 because 100 ≡ 3
;; (mod 97); we use the form INSEE / DGFiP publish.
;;
;; The historical alphanumeric form (key containing one letter) is
;; out of scope — INSEE has issued numeric-only keys for new VAT
;; registrations since 1993.

(def ^:private tva-fr-pattern #"^FR\d{2}\d{9}$")

(defn- tva-fr-expected-key
  "Compute the expected 2-digit numeric check key for a SIREN.
   `siren-str` is the 9-digit canonical SIREN string."
  ^String [^String siren-str]
  (let [siren-long (Long/parseLong siren-str)
        ;; ((SIREN × 100) + 12) mod 97
        k (mod (+ (* siren-long 100) 12) 97)]
    (format "%02d" k)))

(defn valid-tva-fr?
  "True iff `s` is a structurally-valid French TVA intracommunautaire:
   prefix `FR` + 2-digit numeric check key + 9-digit Luhn-valid SIREN,
   AND the check key equals `((SIREN × 100) + 12) mod 97`.

   Accepts:
     - bare canonical (`FR40732829320`)
     - spaced form (`FR 40 732 829 320`) — common on B2B invoices
     - hyphen-separated form (rare)

   Returns `false` for nil, non-strings, wrong shape, embedded SIREN
   that fails its own Luhn check, or wrong check key."
  [s]
  (boolean
   (and (string? s)
        (let [trimmed (str/trim s)]
          (and (str/starts-with? trimmed "FR")
               (let [compact (str "FR" (strip-formatting (subs trimmed 2)))]
                 (and (re-matches tva-fr-pattern compact)
                      (let [key-str (subs compact 2 4)
                            siren   (subs compact 4 13)]
                        (and (valid-siren? siren)
                             (= key-str (tva-fr-expected-key siren)))))))))))

(defn parse-tva-fr
  "Decompose a valid TVA intracommunautaire into key + SIREN.

   Returns:
     {:key   <2-digit check key>
      :siren <9-digit SIREN>}
   or nil if `s` is not structurally valid."
  [s]
  (when (valid-tva-fr? s)
    (let [trimmed (str/trim s)
          compact (str "FR" (strip-formatting (subs trimmed 2)))]
      {:key   (subs compact 2 4)
       :siren (subs compact 4 13)})))

;; Silence unused-symbol linter — public surface above keeps these.
(comment valid-siren? valid-siret? valid-tva-fr?
         parse-siren parse-siret parse-tva-fr
         assert-siren! assert-siret! assert-tva-fr!)

(defn assert-tva-fr!
  "Throws ex-info on an invalid French TVA number; returns the input
   on success."
  [s]
  (when-not (valid-tva-fr? s)
    (throw (ex-info "Invalid TVA intracommunautaire (FR)"
                    {:value s
                     :expected-format "FR + 2-digit check key + 9-digit SIREN; key = ((SIREN × 100) + 12) mod 97"})))
  s)
