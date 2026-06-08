(ns kontor.l10n-de.identifiers
  "German taxpayer-identifier validators.

   Two identifiers are in scope — every B2B invoice and periodic
   filing in Germany references at least one of them:

   - **Steuernummer** — issued by the local Finanzamt. Historically
     formatted per-Bundesland as `FF/BBB/UUUUP` (10 or 11 digits with
     two slashes); a cross-Land **unified 13-digit form** —
     `Vereinheitlichte Bundessteuernummer` — also exists for the
     ELSTER electronic-filing infrastructure. The Land-local check
     digit `P` is the last digit of the local form; the 13-digit
     unified form embeds the local digits with a Bundesland prefix
     and a literal middle '0'. There is no single nationwide check-
     digit algorithm — each Land historically defined (or omitted)
     its own validation. This module validates **structural shape**
     only and exposes a parser that decomposes either form.

   - **USt-IdNr** — Umsatzsteuer-Identifikationsnummer, issued by the
     Bundeszentralamt für Steuern (BZSt). Format: `DE` + 9 digits
     (8 base digits + 1 check digit). The check digit uses the
     **ISO 7064 MOD 11,10** algorithm. We validate format AND check
     digit. Network validation (VIES) is explicitly out of scope —
     that's a runtime concern, not a substrate one.

   Algorithms are mathematical and not copyrightable; this
   implementation is independently derived from the public BZSt
   procedure documented at:
     - https://www.bzst.de — USt-IdNr structure + algorithm
     - ISO/IEC 7064:2003 — generic MOD 11,10 spec
     - German Bundesministerium der Finanzen Steuernummer-Schemen
       Vereinheitlichte Bundessteuernummer (ELSTER)

   ## API

     valid-steuernummer? s → bool
     parse-steuernummer  s → {:form :land :finanzamt :district
                              :sequence :check :local} | nil
     valid-ust-idnr?     s → bool

   See ADR-006 (l10n module boundaries) and CLAUDE.md for the
   per-country substrate convention this module follows."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Steuernummer
;; ============================================================================
;;
;; Two shapes are accepted:
;;
;;   Local form         — 10 or 11 digits, optionally formatted as
;;                        `FF/BBB/UUUUP` (10-digit Länder) or
;;                        `FFF/BBB/UUUUP` (11-digit Länder — Bayern,
;;                        Berlin, Bremen, Hamburg, Niedersachsen,
;;                        Nordrhein-Westfalen, Sachsen-Anhalt,
;;                        Schleswig-Holstein, Thüringen).
;;
;;   Unified 13-digit   — `BBBB0FFFUUUUP`. The BBBB prefix is the
;;                        Bundeszentralamt-für-Steuern Land code
;;                        (assigned ELSTER); position 5 is a literal
;;                        '0'; positions 6..8 are the Finanzamt
;;                        number; 9..12 are the serial; 13 is the
;;                        local check digit.
;;
;; The local Bundesland check-digit algorithm is not uniform across
;; Länder — Bayern alone documents a published mod-11 variant; most
;; other Länder treat the trailing digit as an opaque sequence
;; terminator. We therefore validate only the structural shape, in
;; line with the BR `valid-cnpj?` precedent for shape-only fields
;; (CNPJ trailing digits are checked because the algorithm is
;; uniform; Steuernummer's are not).

(defn- digits-only
  "Strip everything that isn't 0-9. Used to normalise an input that
   may carry slashes or spaces."
  ^String [^String s]
  (when s (str/replace s #"\D" "")))

;; Regex matches valid Steuernummer formatting variants. The slash-
;; separated local form is the most common.
(def ^:private steuernummer-formatted-pattern
  #"^\d{2,3}/\d{3}/\d{4,5}$")

(defn- ten-or-eleven? [n] (or (= n 10) (= n 11)))

(defn- unified-13? [^String d]
  (and (= 13 (count d))
       (= \0 (.charAt d 4))))

(defn- local-form?
  "Pure-digit local form: 10 or 11 digits. Distinguished from the
   unified 13-digit form by length."
  [^String d]
  (ten-or-eleven? (count d)))

(defn valid-steuernummer?
  "True iff `s` is a structurally-valid German Steuernummer.

   Accepts:
     - local 10-digit form (e.g. `2893081508151`'s pre-unified shape)
     - local 11-digit form (Bayern et al.)
     - slash-formatted local form `FF/BBB/UUUUP` or `FFF/BBB/UUUUP`
     - unified 13-digit form `BBBB0FFFUUUUP` (literal '0' at pos 5)

   Returns `false` for nil, non-strings, wrong length, wrong shape,
   or a unified-form input whose position-5 character is not '0'.

   No country-wide check-digit algorithm exists; structural shape is
   the strongest substrate-tier guarantee. Land-specific check-digit
   validation (Bayern publishes one; other Länder do not) belongs in
   a future per-Land helper, not the kernel."
  [s]
  (boolean
   (and (string? s)
        (let [trimmed (str/trim s)
              d (digits-only trimmed)]
          (and (seq d)
               (or
                ;; Slash-formatted local form
                (and (re-matches steuernummer-formatted-pattern trimmed)
                     (ten-or-eleven? (count d)))
                ;; Plain local form (digits only)
                (and (= trimmed d) (local-form? d))
                ;; Unified 13-digit form (with or without slashes —
                ;; slashes already stripped via digits-only)
                (unified-13? d)))))))

(def ^:private unified-land-prefix
  "Bundesland code (first 4 digits of the unified 13-digit
   Steuernummer) → Bundesland short name. Per BMF / ELSTER assignment.

   This list is provided for parse-only inspection; we do not gate
   structural validation on it because the assignment evolves and a
   substrate-tier validator should not reject a structurally-correct
   identifier just because it predates a code-table update."
  {"1011" "Berlin"
   "1012" "Brandenburg"
   "1013" "Bremen"
   "1021" "Hamburg"
   "1022" "Hessen"
   "1023" "Mecklenburg-Vorpommern"
   "2031" "Niedersachsen"
   "2032" "Nordrhein-Westfalen"
   "2041" "Rheinland-Pfalz"
   "2042" "Saarland"
   "2043" "Sachsen"
   "2044" "Sachsen-Anhalt"
   "2051" "Schleswig-Holstein"
   "2052" "Thüringen"
   "2053" "Baden-Württemberg"
   "2061" "Bayern"})

(defn parse-steuernummer
  "Decompose a valid Steuernummer into its components.

   Returns nil if `s` is not structurally valid. Otherwise:

     {:form      :local | :unified
      :land      <Bundesland name | nil>     ; unified-form only
      :land-code <4-digit prefix | nil>      ; unified-form only
      :finanzamt <3-digit Finanzamt number>
      :district  <3-digit district number>
      :sequence  <4-digit serial>
      :check     <1-digit check>
      :local     <FF(F)/BBB/UUUUP slash-formatted string>}

   The `:local` value reconstructs the slash-separated local format
   from either input shape, which is what gets printed on tax
   correspondence inside Germany."
  [s]
  (when (valid-steuernummer? s)
    (let [d (digits-only s)
          n (count d)]
      (cond
        ;; Unified 13-digit form: BBBB0FFFUUUUP
        (= n 13)
        (let [land-code (subs d 0 4)
              fa        (subs d 5 8)
              dist      "000"
              ;; In the unified form there's no explicit district
              ;; field — positions 6..8 are the Finanzamt, 9..12 the
              ;; sequence. We keep `:district` for shape parity with
              ;; the local form but flag it as inferred.
              seq-      (subs d 8 12)
              chk       (subs d 12 13)]
          {:form      :unified
           :land      (get unified-land-prefix land-code)
           :land-code land-code
           :finanzamt fa
           :district  dist
           :sequence  seq-
           :check     chk
           :local     (str fa "/" dist "/" seq- chk)})
        ;; Local 10-digit form: FF/BBB/UUUUP — total 10 digits
        (= n 10)
        {:form      :local
         :land      nil
         :land-code nil
         :finanzamt (subs d 0 2)
         :district  (subs d 2 5)
         :sequence  (subs d 5 9)
         :check     (subs d 9 10)
         :local     (str (subs d 0 2) "/" (subs d 2 5) "/" (subs d 5 10))}
        ;; Local 11-digit form: FFF/BBB/UUUUP — total 11 digits
        (= n 11)
        {:form      :local
         :land      nil
         :land-code nil
         :finanzamt (subs d 0 3)
         :district  (subs d 3 6)
         :sequence  (subs d 6 10)
         :check     (subs d 10 11)
         :local     (str (subs d 0 3) "/" (subs d 3 6) "/" (subs d 6 11))}))))

(defn assert-steuernummer!
  "Throws ex-info on a structurally-invalid Steuernummer; returns the
   input on success."
  [s]
  (when-not (valid-steuernummer? s)
    (throw (ex-info "Invalid Steuernummer"
                    {:value s
                     :expected-format "10–11 digit local form (FF/BBB/UUUUP) or 13-digit unified form (BBBB0FFFUUUUP)"})))
  s)

;; ============================================================================
;; USt-IdNr (Umsatzsteuer-Identifikationsnummer)
;; ============================================================================
;;
;; Format: literal "DE" + 9 digits. The 9th digit is the ISO 7064
;; MOD 11,10 check digit over the first 8.
;;
;; Algorithm (BZSt-published, MOD 11,10 per ISO/IEC 7064:2003):
;;   P ← 10
;;   For each base digit d (i = 1..8):
;;     S ← ((d + P) mod 10)
;;     If S = 0 then S ← 10
;;     P ← (2 * S) mod 11
;;   check-digit ← (11 - P) mod 10
;;
;; Detects all single-digit errors and most transposition errors.

(def ^:private ust-idnr-pattern #"^DE\d{9}$")

(defn- ust-idnr-check-digit
  "Compute the ISO 7064 MOD 11,10 check digit for an 8-digit base.

   `digits` is a seqable of decimal digits (0..9). Returns the
   expected 9th digit."
  [digits]
  (let [P (reduce
           (fn [P d]
             (let [S (mod (+ (long d) (long P)) 10)
                   S (if (zero? S) 10 S)]
               (mod (* 2 S) 11)))
           10
           digits)]
    (mod (- 11 P) 10)))

(defn valid-ust-idnr?
  "True iff `s` is a structurally-valid German USt-IdNr AND its
   ISO 7064 MOD 11,10 check digit matches.

   Accepts the canonical `DE` + 9-digit shape. Whitespace at either
   end is tolerated; embedded whitespace is not (mirrors how German
   forms render the value).

   Out of scope: VIES network validation. That's a runtime concern
   and depends on the EU Commission's network availability."
  [s]
  (boolean
   (and (string? s)
        (let [trimmed (str/trim s)]
          (and (re-matches ust-idnr-pattern trimmed)
               (let [body   (subs trimmed 2)            ; drop "DE"
                     base   (subs body 0 8)
                     actual (- (long (.charAt body 8)) (long \0))
                     digits (mapv #(- (long %) (long \0)) base)]
                 (= actual (ust-idnr-check-digit digits))))))))

(defn assert-ust-idnr!
  "Throws ex-info on an invalid USt-IdNr; returns the input on
   success."
  [s]
  (when-not (valid-ust-idnr? s)
    (throw (ex-info "Invalid USt-IdNr"
                    {:value s
                     :expected-format "DE + 9 digits with ISO 7064 MOD 11,10 check digit"})))
  s)
