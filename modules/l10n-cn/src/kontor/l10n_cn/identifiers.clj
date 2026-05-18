(ns kontor.l10n-cn.identifiers
  "Chinese taxpayer-identifier validators.

   One identifier is in scope — every invoice, periodic VAT return,
   and most B2B contracts in mainland China reference it:

   - **USCC** — Unified Social Credit Code / 统一社会信用代码.
     The 18-character identifier issued by the State Administration
     for Market Regulation (SAMR / 国家市场监督管理总局, formerly
     AIC / 国家工商行政管理总局) at business registration. Issued
     under the **'three-certificates-in-one'** reform (三证合一,
     2015-10-01) and the subsequent **'five-in-one'** (五证合一,
     2016-10-01) and **'one license, one code'** (一照一码) rollouts,
     which folded the previously-separate
       - 工商营业执照注册号 (business license registration number)
       - 组织机构代码 (organization code)
       - 税务登记证号 (Tax Registration Number / 税务登记号)
       - 社会保险登记证号 (social insurance number)
       - 统计登记证号 (statistical registration number)
     into a single USCC. Since 2016 the USCC is the canonical tax ID
     for both invoicing (printed on every fapiao) and STA filings.

     **Legacy 税务登记号** — pre-2015 systems used a separate
     15-character Tax Registration Number (the 6-digit administrative
     division code + 9-digit organization code). It survives only in
     historical records; modern systems read/write USCC. This module
     does not validate the legacy form — store it as an opaque
     string on archival data.

   ## USCC structure (per GB 32100-2015)

   Total 18 characters, positions 1..18:

       Pos  Length  Component                       Notes
       ---  ------  ------------------------------  -----------------
       1     1      Registration authority          '9' = SAMR (most);
                                                    '1' = 编办 (PSU);
                                                    '5' = 民政 (NGO); etc.
       2     1      Organization category           '1' = enterprise;
                                                    '2' = individual business;
                                                    '3' = ag. cooperative; etc.
       3-8   6      Administrative division (GB/T 2260)
                                                    GB/T 2260 6-digit
                                                    province-city-county.
       9-17  9      Organization code (本体代码)    Per GB 11714 (the
                                                    legacy 组织机构代码
                                                    base; SAMR may
                                                    assign 'MA' or other
                                                    prefixes for
                                                    post-reform entities).
       18    1      Check digit                     Base-31 weighted-sum
                                                    per GB 32100-2015 §6.

   ## Check-digit algorithm (GB 32100-2015 §6)

   Alphabet (31 characters, excluding I/O/S/V/Z to reduce optical-
   character-recognition ambiguity):

       0 1 2 3 4 5 6 7 8 9 A B C D E F G H J K L M N P Q R T U W X Y

   Weights for positions 1..17:

       [1 3 9 27 19 26 16 17 20 29 25 13 8 24 10 30 28]

   Procedure:
     1. Let `S = Σ(char_value_i × w_i) mod 31`, for i = 1..17.
     2. Check digit value `C = (31 - S) mod 31`.
     3. Render `C` back through the alphabet to obtain the 18th
        character.

   The algorithm detects all single-character errors and most
   adjacent-character transpositions (the standard MOD-31 guarantee).

   ## What this module deliberately does NOT do

   - **No SAMR network lookup.** Validating that a USCC is structurally
     correct AND issued to a real entity is a runtime concern handled
     by an SAMR API integration (out of scope; commercial APIs exist
     but require Chinese-domiciled accounts).
   - **No legacy 15-character 税务登记号 validation.** That form was
     deprecated by the 2015–2016 reforms; modern systems use USCC.

   Algorithms are mathematical and not copyrightable; this
   implementation is independently derived from the public Chinese
   national standard:

     - GB 32100-2015 法人和其他组织统一社会信用代码编码规则
       (Rules for the structure of the Unified Social Credit Code
       for legal entities and other organizations).
     - GB/T 2260 中华人民共和国行政区划代码 (administrative-division
       code book referenced by positions 3..8).
     - SAMR — https://www.samr.gov.cn — issuing authority documentation.

   ## API

     valid-uscc?    s → bool
     parse-uscc     s → {:registration-authority :organization-category
                         :administrative-division :organization-code
                         :check} | nil
     assert-uscc!   s → s | throws

   See ADR-006 (l10n module boundaries) and CLAUDE.md for the
   per-country substrate convention this module follows."
  (:require [clojure.string :as str]))

;; ============================================================================
;; USCC — Unified Social Credit Code
;; ============================================================================

(def ^:private uscc-alphabet
  "The 31-character USCC alphabet per GB 32100-2015 — the decimal
   digits plus the Latin uppercase letters with I / O / S / V / Z
   excluded (the excluded letters look like digits in printed and
   OCR-scanned form)."
  "0123456789ABCDEFGHJKLMNPQRTUWXY")

(def ^:private uscc-alphabet-values
  "Char → integer value 0..30, the inverse of `uscc-alphabet`."
  (zipmap uscc-alphabet (range)))

(def ^:private uscc-weights
  "Per-position weights (positions 1..17) used in the GB 32100-2015
   check-digit computation. The 17 values produce a maximum weighted
   sum well-distributed mod 31."
  [1 3 9 27 19 26 16 17 20 29 25 13 8 24 10 30 28])

(def ^:private uscc-pattern
  "Structural shape: exactly 18 characters drawn from the USCC
   alphabet (digits + uppercase letters minus I/O/S/V/Z). Letter
   ranges enumerated explicitly because the excluded characters
   are not contiguous."
  #"^[0-9A-HJ-NP-RTUWXY]{18}$")

(defn- uscc-char-values
  "Map a USCC string to a vector of 18 integer values per the
   alphabet. Returns nil if any character is outside the alphabet."
  [^String s]
  (let [vs (mapv uscc-alphabet-values s)]
    (when (every? some? vs) vs)))

(defn- uscc-check-digit
  "Compute the expected 18th character value for the 17-character
   base. `base17` is a seq of 17 integer values 0..30."
  [base17]
  (let [s (reduce + (map * uscc-weights base17))]
    (mod (- 31 (mod s 31)) 31)))

(defn valid-uscc?
  "True iff `s` is a structurally-valid Unified Social Credit Code:
   exactly 18 alphabet characters AND the 18th character is the
   GB 32100-2015 check character of the first 17.

   Accepts:
     - bare 18-character form (e.g. `91110000710935732K`)
     - leading / trailing whitespace
     - mixed-case input — lowercase letters are upper-cased before
       checking (Chinese systems typically print USCC uppercase, but
       hand-entered forms vary).

   Returns `false` for nil, non-strings, wrong length, characters
   outside the USCC alphabet (I / O / S / V / Z appear in neither
   the alphabet nor a valid input), or wrong check digit."
  [s]
  (boolean
   (and (string? s)
        (let [up (str/upper-case (str/trim s))]
          (and (re-matches uscc-pattern up)
               (when-let [vs (uscc-char-values up)]
                 (= (nth vs 17)
                    (uscc-check-digit (subvec vs 0 17)))))))))

(def ^:private registration-authority-labels
  "Map of registration-authority digit (position 1) → human-readable
   label. Per the SAMR-published GB 32100-2015 commentary."
  {\1 "Civil Affairs / 编办"
   \5 "Civil Affairs (NGO) / 民政"
   \9 "Market Regulation (SAMR) / 工商"
   \Y "Other / 其他"})

(def ^:private organization-category-labels
  "Map of organization-category digit (position 2) → human-readable
   label. The category combined with the authority describes the
   entity type (e.g. authority 9 + category 1 = SAMR-registered
   enterprise; authority 5 + category 1 = MoCA-registered social
   group)."
  {\1 "Enterprise / 企业"
   \2 "Individual business / 个体工商户"
   \3 "Farmers' cooperative / 农民专业合作社"
   \9 "Other / 其他"})

(defn parse-uscc
  "Decompose a valid USCC into its components.

   Returns nil if `s` is not structurally valid. Otherwise:

     {:registration-authority       <1-char>
      :registration-authority-name  <string | nil>
      :organization-category        <1-char>
      :organization-category-name   <string | nil>
      :administrative-division      <6-char GB/T 2260 code>
      :organization-code            <9-char organization code>
      :check                        <1-char check digit>
      :normalized                   <uppercased 18-char form>}

   The GB/T 2260 administrative-division lookup itself is out of
   scope — the parsed substring is returned as a string so callers
   that need a province / city / county name can do their own
   resolution against a current GB/T 2260 table."
  [s]
  (when (valid-uscc? s)
    (let [up (str/upper-case (str/trim s))]
      {:registration-authority      (.charAt up 0)
       :registration-authority-name (get registration-authority-labels
                                         (.charAt up 0))
       :organization-category       (.charAt up 1)
       :organization-category-name  (get organization-category-labels
                                         (.charAt up 1))
       :administrative-division     (subs up 2 8)
       :organization-code           (subs up 8 17)
       :check                       (.charAt up 17)
       :normalized                  up})))

(defn assert-uscc!
  "Throws ex-info on an invalid USCC; returns the input on success."
  [s]
  (when-not (valid-uscc? s)
    (throw (ex-info "Invalid Unified Social Credit Code"
                    {:value s
                     :expected-format
                     "18 chars from the GB 32100-2015 alphabet (0-9 + A-Z minus I/O/S/V/Z); 18th is the base-31 weighted-sum check digit"})))
  s)
