(ns kontor.l10n-at.identifiers
  "Austrian taxpayer-identifier validators.

   Three identifiers are in scope — every B2B invoice, BMF filing,
   and Firmenbuch lookup in Austria references at least one:

   - **UID** — Umsatzsteuer-Identifikationsnummer, issued by the
     Finanzamt / Bundesministerium für Finanzen (BMF). Format:
     literal `ATU` + 8 digits (7 base digits + 1 check digit). The
     check digit follows a BMF-published modulo-10 algorithm
     described below. We validate format AND check digit. Network
     validation (VIES) is explicitly out of scope — that's a runtime
     concern, not a substrate one.

   - **Steuernummer** — Austrian tax number, 9 digits. Conventionally
     printed as `FA/NNNNNNN` where `FA` is the 2-digit Finanzamt
     prefix (00–99) and `NNNNNNN` is the 7-digit local serial. There
     is no nationwide check-digit algorithm; the structural shape
     (2+7 digits, optional slash separator) is the strongest
     substrate-tier guarantee. Mirrors the DE Steuernummer policy.

   - **Firmenbuchnummer (FN)** — commercial-register number issued by
     a Landesgericht (commercial court) and held in the Justiz-
     Bundes-Firmenbuch. Format: 1–6 digits + 1 lowercase check letter,
     e.g. `FN 188776h`. The check-letter algorithm is not openly
     published — we validate the structural shape only (1..6 digits
     followed by exactly one lowercase letter). The `FN` prefix is
     conventional and may appear with a space; both are tolerated.

   Algorithms are mathematical and not copyrightable; this
   implementation is independently derived from the public BMF /
   Firmenbuch documentation:

     - https://www.bmf.gv.at — UID structure and check-digit procedure
     - https://www.bmf.gv.at — Steuernummer-Struktur (FA-Nr + Serial)
     - https://www.justiz.gv.at/firmenbuch — Firmenbuch shape +
       Landesgericht assignment
     - https://www.firmenbuch.at — FN-Struktur

   ## API

     valid-uid?              s → bool
     parse-uid               s → {:uid :base :check} | nil
     assert-uid!             s → s | throws
     valid-steuernummer?     s → bool
     parse-steuernummer      s → {:finanzamt :sequence :local} | nil
     assert-steuernummer!    s → s | throws
     valid-firmenbuchnummer? s → bool
     parse-firmenbuchnummer  s → {:digits :check :canonical} | nil
     assert-firmenbuchnummer! s → s | throws

   See ADR-006 (l10n module boundaries) and CLAUDE.md for the
   per-country substrate convention this module follows."
  (:require [clojure.string :as str]))

;; ============================================================================
;; UID — Umsatzsteuer-Identifikationsnummer
;; ============================================================================
;;
;; Format: literal `ATU` + 7 base digits + 1 check digit.
;;
;; BMF-published check-digit algorithm (modulo-10 with weighted
;; cross-sum on the even positions):
;;
;;   For i ∈ {1,3,5,7} (1-indexed):  Si = base[i]
;;   For i ∈ {2,4,6}:                Si = cross-sum(2 * base[i])
;;                                       (a + b where 2*d = 10a+b)
;;   R     = S1 + S2 + S3 + S4 + S5 + S6 + S7 + 4
;;   Check = (10 - (R mod 10)) mod 10
;;
;; The +4 constant is the BMF's idiosyncratic addend — it does NOT
;; come from the generic Luhn family. It's documented in the BMF
;; Verfahrenshandbuch UID and reproduced in every independent open-
;; source VAT-ID validator (e.g. Python `stdnum`, JS `vat`, Ruby
;; `valvat`).
;;
;; The algorithm catches all single-digit errors and most adjacent
;; transpositions (the standard mod-10 design guarantee).

(def ^:private uid-pattern #"^ATU\d{8}$")

(defn- cross-sum
  "Sum the decimal digits of `n`. For 0..18 (the only range we hit
   when doubling a 0..9 digit), the equivalent shortcut is
   `n - 9 * (n div 10)` — but the explicit sum reads clearly."
  ^long [^long n]
  (loop [n n acc 0]
    (if (zero? n)
      acc
      (recur (quot n 10) (+ acc (rem n 10))))))

(defn- uid-check-digit
  "Compute the BMF mod-10 check digit for a 7-digit UID base.

   `digits` is a seqable of decimal digits (0..9) of length 7,
   ordered base[1]..base[7] (1-indexed, leftmost first)."
  ^long [digits]
  (let [v (vec digits)
        weighted (reduce-kv
                  (fn [acc i d]
                    (let [pos (inc i)] ; 1-indexed
                      (if (odd? pos)
                        (+ acc d)
                        (+ acc (cross-sum (* 2 d))))))
                  0
                  v)
        r (+ weighted 4)]
    (mod (- 10 (mod r 10)) 10)))

(defn valid-uid?
  "True iff `s` is a structurally-valid Austrian UID AND its BMF
   mod-10 check digit matches.

   Accepts the canonical `ATU` + 8-digit shape (case-sensitive `ATU`
   prefix per BMF spec — lowercase is rejected to mirror the DE
   `DE` + 9-digit convention). Whitespace at either end is tolerated.

   Out of scope: VIES network validation. That's a runtime concern
   and depends on the EU Commission's network availability."
  [s]
  (boolean
   (and (string? s)
        (let [trimmed (str/trim s)]
          (and (re-matches uid-pattern trimmed)
               (let [body   (subs trimmed 3)              ; drop "ATU"
                     base   (subs body 0 7)
                     actual (- (int (.charAt body 7)) (int \0))
                     digits (mapv #(- (int %) (int \0)) base)]
                 (= actual (uid-check-digit digits))))))))

(defn parse-uid
  "Decompose a valid UID into its components.

   Returns nil if `s` is not structurally valid. Otherwise:

     {:uid   <canonical ATU + 8-digit string>
      :base  <7-digit base, leftmost first>
      :check <single-digit check>}"
  [s]
  (when (valid-uid? s)
    (let [trimmed (str/trim s)
          body    (subs trimmed 3)]
      {:uid   trimmed
       :base  (subs body 0 7)
       :check (subs body 7 8)})))

(defn assert-uid!
  "Throws ex-info on an invalid UID; returns the input on success."
  [s]
  (when-not (valid-uid? s)
    (throw (ex-info "Invalid Austrian UID"
                    {:value s
                     :expected-format "ATU + 7 base digits + 1 BMF mod-10 check digit"})))
  s)

;; ============================================================================
;; Steuernummer — Austrian local tax number
;; ============================================================================
;;
;; Format: 9 digits, conventionally written `FA/NNNNNNN` (2 digit
;; Finanzamt prefix + 7 digit local serial). Spaces and slashes are
;; tolerated and stripped before length / shape checks.
;;
;; Unlike DE, AT has a single nationwide form — there is no Land-
;; specific 11-digit variant and no unified ELSTER form to
;; cohabit. Like DE, however, no published nationwide check-digit
;; algorithm exists, so we validate structural shape only.

(def ^:private steuernummer-formatted-pattern
  #"^\d{2}/\d{7}$")

(defn- digits-only ^String [^String s]
  (when s (str/replace s #"\D" "")))

(defn valid-steuernummer?
  "True iff `s` is a structurally-valid Austrian Steuernummer.

   Accepts:
     - bare 9-digit form (e.g. `123456789`)
     - slash-separated form `FF/NNNNNNN` (e.g. `12/3456789`)
     - leading/trailing whitespace

   Returns `false` for nil, non-strings, wrong length, or wrong
   shape. No country-wide check-digit algorithm exists; structural
   shape is the strongest substrate-tier guarantee.

   Per BMF documentation, the 2-digit prefix is the Finanzamt code
   (one of ~40 published Austrian Finanzämter — not enumerated here
   because the list evolves with administrative reform)."
  [s]
  (boolean
   (and (string? s)
        (let [trimmed (str/trim s)
              d       (digits-only trimmed)]
          (and (= 9 (count d))
               (or
                ;; Bare 9-digit form (digits only)
                (= trimmed d)
                ;; Slash-formatted form FF/NNNNNNN
                (re-matches steuernummer-formatted-pattern trimmed)))))))

(defn parse-steuernummer
  "Decompose a valid Steuernummer into its components.

   Returns nil if `s` is not structurally valid. Otherwise:

     {:finanzamt <2-digit Finanzamt prefix>
      :sequence  <7-digit local serial>
      :local     <FF/NNNNNNN slash-formatted string>}"
  [s]
  (when (valid-steuernummer? s)
    (let [d (digits-only s)]
      {:finanzamt (subs d 0 2)
       :sequence  (subs d 2 9)
       :local     (str (subs d 0 2) "/" (subs d 2 9))})))

(defn assert-steuernummer!
  "Throws ex-info on a structurally-invalid Steuernummer; returns the
   input on success."
  [s]
  (when-not (valid-steuernummer? s)
    (throw (ex-info "Invalid Austrian Steuernummer"
                    {:value s
                     :expected-format "9 digits, conventionally FF/NNNNNNN"})))
  s)

;; ============================================================================
;; Firmenbuchnummer (FN) — commercial-register number
;; ============================================================================
;;
;; Format: 1..6 digits + 1 lowercase letter (the Prüfbuchstabe), e.g.
;; `188776h`. Conventionally prefixed with `FN ` in correspondence;
;; the prefix is optional.
;;
;; The Prüfbuchstabe algorithm is administered by the Justiz
;; Firmenbuch and is not openly published — we validate the
;; structural shape only. The letter is exactly one lowercase
;; character a-z; uppercase is not a documented variant and is
;; rejected to keep parsing unambiguous.
;;
;; The Landesgericht (commercial court) that issued the number is
;; encoded elsewhere in the Firmenbuch entry, not in the FN itself,
;; so we do not parse a court-code field.

(def ^:private fn-pattern #"^\d{1,6}[a-z]$")
(def ^:private fn-prefix-pattern #"^FN\s+(.+)$")

(defn- strip-fn-prefix
  "If `s` starts with `FN ` (case-sensitive, per Firmenbuch style),
   return the body; otherwise return `s` unchanged. Internal
   whitespace beyond the prefix is not normalised."
  [^String s]
  (when s
    (let [trimmed (str/trim s)]
      (if-let [[_ body] (re-matches fn-prefix-pattern trimmed)]
        body
        trimmed))))

(defn valid-firmenbuchnummer?
  "True iff `s` is a structurally-valid Firmenbuchnummer.

   Accepts:
     - bare form (`188776h`)
     - `FN`-prefixed form (`FN 188776h`)
     - leading/trailing whitespace

   Returns `false` for nil, non-strings, wrong length, missing /
   uppercase / multi-character check letter, or any non-digit
   content in the digit portion.

   The Prüfbuchstabe algorithm is not openly documented; this
   validator is shape-only (mirrors the DE Steuernummer / AT
   Steuernummer policy)."
  [s]
  (boolean
   (and (string? s)
        (let [body (strip-fn-prefix s)]
          (and (string? body)
               (re-matches fn-pattern body))))))

(defn parse-firmenbuchnummer
  "Decompose a valid Firmenbuchnummer into its components.

   Returns nil if `s` is not structurally valid. Otherwise:

     {:digits    <leading digit portion, 1..6 chars>
      :check     <single lowercase Prüfbuchstabe>
      :canonical <FN-prefixed canonical render, e.g. \"FN 188776h\">}"
  [s]
  (when (valid-firmenbuchnummer? s)
    (let [body (strip-fn-prefix s)
          n    (count body)
          digits (subs body 0 (dec n))
          chk    (subs body (dec n) n)]
      {:digits    digits
       :check     chk
       :canonical (str "FN " body)})))

(defn assert-firmenbuchnummer!
  "Throws ex-info on a structurally-invalid Firmenbuchnummer; returns
   the input on success."
  [s]
  (when-not (valid-firmenbuchnummer? s)
    (throw (ex-info "Invalid Austrian Firmenbuchnummer"
                    {:value s
                     :expected-format "1..6 digits + 1 lowercase check letter (e.g. 188776h), optionally prefixed by 'FN '"})))
  s)
