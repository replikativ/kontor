(ns kontor.l10n-jp.identifiers
  "Japanese taxpayer-identifier validators.

   Two business identifiers are in scope — every tax filing and every
   qualified invoice issued in Japan references one or both of them:

   - **法人番号 (Hōjin Bangō, Corporate Number)** — the 13-digit
     identifier the National Tax Agency (国税庁) issues to every
     Japanese corporation, government body, and registered
     organisation since October 2015. It replaced the previous
     ad-hoc Taxpayer Identification Number system. For registered
     corporations the 12-digit base is the Ministry of Justice
     `会社法人等番号` (Company Registration Number); the NTA prepends
     a single check digit. Used for: corporate tax filings (法人税),
     consumption tax filings (消費税), social-insurance reporting,
     and the qualified-invoice system. The number is publicly
     disclosed (unlike the individual マイナンバー — see below).

   - **適格請求書発行事業者登録番号 (Qualified Invoice Issuer
     Registration Number, colloquially the \"T-number\")** — `T`
     prefix + 13 digits, mandatory for any business that wishes its
     invoices to grant the buyer a consumption-tax input credit.
     In force since 2023-10-01 under the 適格請求書等保存方式
     (Qualified Invoice System / QIS, often called the Japanese
     'invoice system'). For a corporate registrant the 13-digit
     body is the same Corporate Number described above; for a sole
     proprietor it's an NTA-issued 13-digit identifier with the same
     check-digit algorithm.

   ## Out of scope: マイナンバー (Individual / My Number)

   The 12-digit My Number assigned to individuals is **deliberately
   not validated here**. It's personally-identifiable information
   under strict access-control rules (the Number Act / 番号法), and
   the kernel does not store it. Withholding-on-payment workflows
   that need to attest a payee's My Number do so through ID-document
   capture, not validator helpers. Mixing PII validators with the
   business-identifier module would invite accidental persistence
   in places where the Number Act forbids it.

   ## Algorithm — Corporate Number check digit

   Algorithms are mathematical and not copyrightable; this
   implementation is independently derived from the NTA's published
   procedure:

     check-digit = 9 - ((Σ Pn · Qn for n ∈ 1..12) mod 9)

   where Pn is the n-th digit of the 12-digit base **counted from
   the right** (so P1 is the rightmost), and Qn = 1 when n is odd,
   2 when n is even. The result is in [1..9] — the check digit can
   never be 0 (so the leading digit of a Corporate Number is never 0,
   which gives the format its 'starts with 1-9' visual signature).

   Worked example from the NTA's published spec
   (https://www.houjin-bangou.nta.go.jp/documents/checkdigit.pdf):

     base = 700110005901
     even-position-from-right sum = 7+0+1+0+5+0+1 = 13
     odd-position-from-right  sum = 0+1+0+0+9+1   = 11
     weighted sum             = 13·2 + 11·1 = 37
     check                    = 9 - (37 mod 9) = 9 - 1 = 8
     Corporate Number         = 8 700110005901 → 8700110005901

   Cited sources:
     - https://www.houjin-bangou.nta.go.jp/ — NTA Corporate Number
       Publication Site (法人番号公表サイト)
     - https://www.houjin-bangou.nta.go.jp/documents/checkdigit.pdf
       — official check-digit calculation
     - https://www.nta.go.jp/taxes/shiraberu/zeimokubetsu/shohi/keigenzeiritsu/invoice.htm
       — Qualified Invoice System (QIS / 適格請求書等保存方式)

   ## API

     valid-corporate-number?              s → bool
     parse-corporate-number               s → {:check :base} | nil
     assert-corporate-number!             s → s | throws

     valid-qualified-invoice-issuer-number?    s → bool
     parse-qualified-invoice-issuer-number     s → {:check :base
                                                    :corporate-number} | nil
     assert-qualified-invoice-issuer-number!   s → s | throws

   See ADR-006 (l10n module boundaries) and CLAUDE.md for the
   per-country substrate convention this module follows."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- strip-formatting
  "Strip whitespace and hyphens — the only formatting characters the
   NTA prints around Corporate Numbers (e.g. `8700-1100-0590-1`
   appears occasionally on PR material; the canonical form has no
   internal separators)."
  ^String [^String s]
  (when s (-> s (str/replace #"[\s\-]" ""))))

(defn- all-digits? [^String s]
  (and (some? s)
       (pos? (count s))
       (every? #(<= (int \0) (int %) (int \9)) s)))

(defn- digit-vec
  "Convert a digit string to a vector of integer values 0..9."
  [^String s]
  (mapv #(- (int %) (int \0)) s))

;; ============================================================================
;; 法人番号 (Corporate Number) — check-digit algorithm
;; ============================================================================
;;
;; The 13-digit Corporate Number has its check digit at the LEFTMOST
;; position (position 1 of the printed form, position 13 from the
;; right of the 12-digit base + check digit). The base spans the
;; remaining 12 digits.
;;
;; Algorithm (NTA-published, MOD 9 weighted):
;;
;;   For n in 1..12 (positions of the BASE counted from the right):
;;     Qn = 2 when n is even
;;        = 1 when n is odd
;;   sum = Σ (base-digit at position n) × Qn
;;   check-digit = 9 - (sum mod 9)
;;
;; Notes:
;; - The literal subtraction `9 - x` for x ∈ [0..8] yields [1..9]; we
;;   do NOT take a final `mod 9` (that would map 9 → 0, but the NTA
;;   spec explicitly states the check digit is 1..9, never 0).
;; - This gives the Corporate Number its 'leading digit is never 0'
;;   property — a quick visual sanity check on any 13-digit candidate.

(defn- corporate-number-check-digit
  "Compute the NTA Corporate Number check digit over a 12-element
   base digit vector (`base[0]` is the leftmost of the base, i.e.
   position 12 from the right)."
  [base]
  (let [n 12
        sum (reduce
             (fn [acc [i d]]
               (let [pos-from-right (- n i)
                     w (if (even? pos-from-right) 2 1)]
                 (+ acc (* d w))))
             0
             (map-indexed vector base))]
    (- 9 (mod sum 9))))

(defn valid-corporate-number?
  "True iff `s` is a structurally-valid 13-digit Japanese Corporate
   Number AND its NTA MOD-9 check digit matches.

   Accepts:
     - bare 13-digit string (`8700110005901`)
     - hyphen-separated variant (`8700-1100-0590-1`)
     - whitespace-padded inputs

   Returns `false` for nil, non-strings, wrong length, non-digit
   content (after stripping hyphen / whitespace formatting), wrong
   check digit, or a leading zero (which is structurally impossible
   under the algorithm)."
  [s]
  (boolean
   (and (string? s)
        (let [d (strip-formatting (str/trim s))]
          (and (= 13 (count d))
               (all-digits? d)
               (not= \0 (.charAt ^String d 0))
               (let [ds (digit-vec d)
                     check (first ds)
                     base (vec (rest ds))]
                 (= check (corporate-number-check-digit base))))))))

(defn parse-corporate-number
  "Decompose a valid Corporate Number into its components.

   Returns nil if `s` is not structurally valid. Otherwise:

     {:check  <1-digit string>           ; the leading check digit
      :base   <12-digit string>          ; the body (= 会社法人等番号
                                           for registered corporations)
      :canonical <13-digit string>}      ; the un-formatted form

   The `:base` value matches the 12-digit `会社法人等番号` (Company
   Registration Number) issued by the Ministry of Justice for
   registered corporations — useful when reconciling against MoJ
   commercial-register data."
  [s]
  (when (valid-corporate-number? s)
    (let [d (strip-formatting (str/trim s))]
      {:check     (subs d 0 1)
       :base      (subs d 1 13)
       :canonical d})))

(defn assert-corporate-number!
  "Throws ex-info on an invalid Corporate Number; returns the input
   on success."
  [s]
  (when-not (valid-corporate-number? s)
    (throw (ex-info "Invalid Japanese Corporate Number (法人番号)"
                    {:value s
                     :expected-format "13 digits with NTA MOD-9 check digit; leading digit 1..9"})))
  s)

;; ============================================================================
;; 適格請求書発行事業者登録番号 (Qualified Invoice Issuer Number)
;; ============================================================================
;;
;; Format: literal `T` + 13 digits, where the 13-digit body uses the
;; same check-digit algorithm as the Corporate Number. For corporate
;; registrants the body IS the Corporate Number; for sole-proprietor
;; registrants the NTA issues a separate 13-digit identifier with
;; identical structural rules.
;;
;; The `T` prefix is mandatory — it distinguishes the QIS number
;; from a bare Corporate Number on an invoice. Without it, the buyer
;; cannot claim a consumption-tax (JCT) input credit on the purchase.

(def ^:private qii-pattern #"^T\d{13}$")

(defn valid-qualified-invoice-issuer-number?
  "True iff `s` is a structurally-valid Qualified Invoice Issuer
   Registration Number (T + 13 digits with valid NTA check digit).

   Accepts:
     - canonical form `T<13 digits>` (e.g. `T8700110005901`)
     - hyphen-separated variant `T8700-1100-0590-1`
     - whitespace-padded inputs

   The `T` prefix is REQUIRED and must be uppercase Latin. A bare
   13-digit Corporate Number is NOT a valid QIS number even if its
   body is identical — the prefix is what signals 'qualified
   invoice issuer' to the consumption-tax credit machinery.

   Returns `false` for nil, non-strings, missing/lowercase prefix,
   wrong body length, non-digit body, or wrong check digit."
  [s]
  (boolean
   (and (string? s)
        (let [trimmed (str/trim s)
              ;; Strip hyphens/whitespace from the BODY, not the prefix.
              normalised (if (and (pos? (count trimmed))
                                  (= \T (.charAt ^String trimmed 0)))
                           (str "T" (strip-formatting (subs trimmed 1)))
                           trimmed)]
          (and (re-matches qii-pattern normalised)
               (valid-corporate-number? (subs normalised 1)))))))

(defn parse-qualified-invoice-issuer-number
  "Decompose a valid Qualified Invoice Issuer Number.

   Returns nil if `s` is not structurally valid. Otherwise:

     {:check            <1-digit string>       ; check digit of the body
      :base             <12-digit string>      ; body's 12-digit base
      :corporate-number <13-digit string>      ; the embedded Corporate
                                                 Number (= body)
      :canonical        <\"T\" + 13 digits>}   ; the un-formatted form

   For a registered-corporation registrant the `:corporate-number`
   matches the issuer's NTA Corporate Number. For a sole-proprietor
   registrant it's the NTA-issued 13-digit identifier, which uses
   the same structural rules but is not separately published on the
   Corporate Number search site."
  [s]
  (when (valid-qualified-invoice-issuer-number? s)
    (let [trimmed (str/trim s)
          normalised (str "T" (strip-formatting (subs trimmed 1)))
          body (subs normalised 1)]
      {:check            (subs body 0 1)
       :base             (subs body 1 13)
       :corporate-number body
       :canonical        normalised})))

(defn assert-qualified-invoice-issuer-number!
  "Throws ex-info on an invalid Qualified Invoice Issuer Number;
   returns the input on success."
  [s]
  (when-not (valid-qualified-invoice-issuer-number? s)
    (throw (ex-info "Invalid Qualified Invoice Issuer Registration Number (適格請求書発行事業者登録番号)"
                    {:value s
                     :expected-format "T + 13 digits with NTA MOD-9 check digit"})))
  s)
