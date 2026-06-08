(ns kontor.l10n-us.identifiers
  "US taxpayer-identifier validators.

   Two identifiers are in scope at the substrate tier — every US
   business filing references at least one of them:

   - **EIN** (Employer Identification Number) — the 9-digit federal
     tax identifier issued by the IRS to every US business entity
     (corporations, partnerships, LLCs, sole proprietors with payroll,
     trusts, estates, non-profits). Format: `NN-NNNNNNN` (a 2-digit
     prefix, a hyphen, then 7 digits). The IRS does NOT publish a
     check-digit algorithm — there isn't one. Validation is purely
     structural plus a prefix-table check: the first 2 digits identify
     the assigning IRS campus, and only certain prefixes have ever
     been assigned. Some valid-looking 2-digit prefixes (07, 08, 09,
     17, 18, 19, 28, 29, 49, 69, 70, 78, 79, 89, 96, 97) have never
     been used and unambiguously identify a forged EIN.

     Per the IRS page \"How EINs are Assigned and Valid EIN Prefixes\":
     https://www.irs.gov/businesses/small-businesses-self-employed/how-eins-are-assigned-and-valid-ein-prefixes

     EINs and SSNs share the 9-digit shape but are NOT
     interchangeable — they live in separate IRS namespaces, the
     prefix tables don't overlap, and a tool that mixes them up
     will misroute filings.

   - **State Tax IDs** — 50 states + DC + territories each issue their
     own state employer / sales-tax identifier. No uniform format
     exists; most are 7-10 digit numbers, some embed a state-prefix
     letter, some attach a 2-digit location code. Out of scope at the
     substrate tier — consumers needing per-state validation add a
     state-specific validator inside their l10n-us-* artifact.

   ## API

     valid-ein?   s → bool
     parse-ein    s → {:prefix :body :campus :formatted} | nil
     assert-ein!  s → s | throws

   Sources (public, non-copyrightable):
     - https://www.irs.gov/businesses/small-businesses-self-employed/how-eins-are-assigned-and-valid-ein-prefixes
     - IRS Publication 1635 (Employer Identification Number guide)

   See ADR-006 (l10n module boundaries) and CLAUDE.md for the
   per-country substrate convention this module follows."
  (:require [clojure.string :as str]))

;; ============================================================================
;; EIN — Employer Identification Number
;; ============================================================================
;;
;; Format: 2-digit prefix + hyphen + 7-digit body, total 9 digits.
;; The hyphen is conventional (it's what the IRS prints on CP-575
;; assignment letters and W-9 forms), but the underlying value is
;; just 9 digits. Both `12-3456789` and `123456789` are accepted.
;;
;; Validation procedure:
;;   1. Strip whitespace and the canonical `XX-XXXXXXX` hyphen.
;;   2. Require 9 digits, all numeric.
;;   3. The first 2 digits (the "prefix") must appear in the IRS
;;      published valid-prefix table.
;;
;; There is no check-digit. Single-digit transpositions inside the
;; 7-digit body are NOT detectable — that's an IRS design choice and
;; the reason every B2B integration that exchanges EINs should
;; cross-check against W-9 or IRS TIN-matching downstream.

(def valid-ein-prefixes
  "IRS-assigned EIN prefixes (first 2 digits of the EIN). Per the IRS
   page \"How EINs are Assigned and Valid EIN Prefixes\".

   Each entry maps the 2-digit prefix to the assigning IRS campus.
   Prefixes 20, 26, 27, 45, 46, 47, 81, 82, 83, 84, 85, 86, 87, 88,
   93, 94, 95, 98, 99 are assigned by the IRS Internet/EIN Online
   facility (no single physical campus); they are marked
   \"Internet\" below.

   Prefixes NOT in this map (notably 07, 08, 09, 17, 18, 19, 28,
   29, 49, 69, 70, 78, 79, 89, 96, 97) are unassigned and signal a
   forged or malformed EIN at validation time.

   This table is a current snapshot; the IRS occasionally adds
   prefixes when a campus opens. A future module bump updates it
   without breaking the public API."
  {"01" "Andover"
   "02" "Andover"
   "03" "Atlanta"
   "04" "Atlanta"
   "05" "Atlanta"
   "06" "Atlanta"
   "10" "Cincinnati"
   "11" "Cincinnati"
   "12" "Cincinnati"
   "13" "Cincinnati"
   "14" "Cincinnati"
   "15" "Cincinnati"
   "16" "Cincinnati"
   "20" "Internet"
   "21" "Brookhaven"
   "22" "Brookhaven"
   "23" "Brookhaven"
   "24" "Brookhaven"
   "25" "Brookhaven"
   "26" "Internet"
   "27" "Internet"
   "30" "Cincinnati"
   "31" "Cincinnati"
   "32" "Cincinnati"
   "33" "Memphis"
   "34" "Memphis"
   "35" "Memphis"
   "36" "Memphis"
   "37" "Memphis"
   "38" "Memphis"
   "39" "Memphis"
   "40" "Memphis"
   "41" "Memphis"
   "42" "Memphis"
   "43" "Memphis"
   "44" "Memphis"
   "45" "Internet"
   "46" "Internet"
   "47" "Internet"
   "48" "Philadelphia"
   "50" "Cincinnati"
   "51" "Cincinnati"
   "52" "Cincinnati"
   "53" "Cincinnati"
   "54" "Cincinnati"
   "55" "Cincinnati"
   "56" "Cincinnati"
   "57" "Cincinnati"
   "58" "Cincinnati"
   "59" "Cincinnati"
   "60" "Cincinnati"
   "61" "Cincinnati"
   "62" "Cincinnati"
   "63" "Cincinnati"
   "64" "Cincinnati"
   "65" "Cincinnati"
   "66" "Cincinnati"
   "67" "Cincinnati"
   "68" "Cincinnati"
   "71" "Philadelphia"
   "72" "Philadelphia"
   "73" "Philadelphia"
   "74" "Philadelphia"
   "75" "Philadelphia"
   "76" "Philadelphia"
   "77" "Philadelphia"
   "80" "Ogden"
   "81" "Internet"
   "82" "Internet"
   "83" "Internet"
   "84" "Internet"
   "85" "Internet"
   "86" "Internet"
   "87" "Internet"
   "88" "Internet"
   "90" "Ogden"
   "91" "Ogden"
   "92" "Ogden"
   "93" "Internet"
   "94" "Internet"
   "95" "Internet"
   "98" "Internet"
   "99" "Internet"})

;; Accept either the canonical hyphenated form or a bare 9-digit
;; string. The IRS prints the hyphen in CP-575 / W-9 forms; many
;; payroll systems strip it.
(def ^:private ein-formatted-pattern   #"^\d{2}-\d{7}$")
(def ^:private ein-bare-pattern        #"^\d{9}$")

(defn- digits-only
  "Strip everything that isn't 0-9."
  ^String [^String s]
  (when s (str/replace s #"\D" "")))

(defn valid-ein?
  "True iff `s` is a structurally-valid US EIN AND its 2-digit prefix
   appears in the IRS-published valid-prefix table.

   Accepts:
     - canonical hyphenated form `NN-NNNNNNN` (W-9 / CP-575)
     - bare 9-digit form `NNNNNNNNN`
     - either form with surrounding whitespace

   Rejects:
     - nil, non-strings
     - wrong length / non-digit content
     - any 9-digit value whose first 2 digits are in the IRS
       unassigned-prefix set (07, 08, 09, 17, 18, 19, 28, 29, 49,
       69, 70, 78, 79, 89, 96, 97 as of the table snapshot)

   No check-digit guarantee: a single-digit error inside the 7-digit
   body is undetectable. Downstream integrations that exchange EINs
   should still cross-check via IRS TIN-matching or a W-9
   reconciliation step."
  [s]
  (boolean
   (and (string? s)
        (let [trimmed (str/trim s)
              d       (digits-only trimmed)]
          (and (or (re-matches ein-formatted-pattern trimmed)
                   (re-matches ein-bare-pattern trimmed))
               (= 9 (count d))
               (contains? valid-ein-prefixes (subs d 0 2)))))))

(defn parse-ein
  "Decompose a valid EIN into its components.

   Returns nil if `s` is not structurally valid. Otherwise:

     {:prefix    <2-digit prefix>
      :body      <7-digit sequence>
      :campus    <assigning IRS campus or \"Internet\">
      :formatted <canonical NN-NNNNNNN string>}

   The `:campus` field is the IRS service centre that issued the
   number (or \"Internet\" for prefixes assigned by the online EIN
   facility). Useful for auditors checking historical assignment
   records."
  [s]
  (when (valid-ein? s)
    (let [d (digits-only s)
          prefix (subs d 0 2)
          body   (subs d 2 9)]
      {:prefix    prefix
       :body      body
       :campus    (get valid-ein-prefixes prefix)
       :formatted (str prefix "-" body)})))

(defn assert-ein!
  "Throws ex-info on an invalid EIN; returns the input on success."
  [s]
  (when-not (valid-ein? s)
    (throw (ex-info "Invalid EIN"
                    {:value s
                     :expected-format "9 digits formatted NN-NNNNNNN with an IRS-assigned 2-digit prefix"})))
  s)
