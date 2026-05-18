(ns kontor.l10n-at.tax
  "Austrian USt (Umsatzsteuer / VAT) compute — callable function
   family for the invoicing side of AT VAT.

   Where the existing `kontor.l10n-at.uva` ns computes the
   *filing-side* (U30 / monthly UVA field values aggregated from
   posted tax accounts), this ns computes the *invoicing-side*:
   given a line amount and its VAT classification, return the
   tax breakdown the invoice posting builder needs.

   The two namespaces are complementary and share no code: the
   filing report reads tagged ledger postings; the compute below is
   pure arithmetic against published BMF rate tables.

   ## Rate table (Stand 2026)

   Per BMF UStG (Umsatzsteuergesetz) and the BMF UVA-Formular:

       Code            Rate    Use case
       --------------- ------  ----------------------------------------
       :standard       20%     Normalsteuersatz — default for B2B
                               goods + services
       :reduced-13     13%     Ermäßigter Steuersatz — kulturelle
                               Veranstaltungen, Inlandsflüge, lebende
                               Tiere, Wein ab-Hof, etc.
       :reduced-10     10%     Ermäßigter Steuersatz — Lebensmittel,
                               Bücher, Pharma, Wohnungsmiete (teilw.),
                               Restaurants (Speisen), Personenbeförderung
       :zero           0%      Steuerfrei mit Vorsteuerabzug — exports
                               outside EU, intra-EU B2B supply
                               §6 Abs.1 Z.6 (UVA Kz 011)
       :exempt         0%      Steuerfrei OHNE Vorsteuerabzug — Finanz-
                               dienstleistungen, Heilbehandlungen,
                               Bildung, Wohnungsmiete §6 Abs.1 Z.16
       :reverse-charge 0% out  Recipient owes (§19/1a Bauleistungen,
                       (sup)   intra-EU acquisition). Supplier emits no
                               output VAT; the recipient self-assesses.

   ## Zero vs exempt vs reverse-charge

   The arithmetic for all three is identical (output VAT = 0), but
   the audit + filing semantics differ:

   - `:zero` (steuerfrei MIT Vorsteuerabzug) — Vorsteuer is
     deductible. Used for: exports outside EU (§7 UStG),
     innergemeinschaftliche Lieferungen §6 Abs.1 Z.6 (UVA Kz 011).

   - `:exempt` (echt steuerfrei OHNE Vorsteuerabzug) — Vorsteuer is
     NOT deductible. Used for: financial services (§6 Abs.1 Z.8),
     medical (Z.19), education (Z.11), residential rent (Z.16).
     The invoice must disclose `gemäß §6 UStG steuerbefreit` per
     §11 Abs.1 Z.7.

   - `:reverse-charge` — recipient self-assesses. Supplier's
     invoice MUST disclose `Steuerschuldnerschaft des
     Leistungsempfängers gemäß §19 Abs.1a UStG`. The recipient
     books both an output AND an input (Vorsteuer) at the same
     rate, netting to zero in the periodic UVA — but both legs
     must appear separately on UVA Kz 057 / Kz 066.

   Caller signals the VAT class via `:vat-class` on the line
   (default `:standard`).

   ## What this module deliberately does NOT do

   - **No live BMF rate refresh.** Rates here are baked from the
     BMF UStG as of 2026. When a rate changes (the 2020 13% rate
     was a temporary COVID measure that became permanent in 2026),
     a l10n-at artifact bump updates this table.
   - **No fiscal-position / recipient-VAT-ID lookups.** Those are
     the consumer's responsibility (apply :reverse-charge when an
     intra-EU B2B buyer presents a valid UID, etc.).
   - **No Kleinunternehmerregelung** (§6 Abs.1 Z.27 — annual
     turnover < €35,000 exempt). That's a consumer-policy
     decision; the caller sets `:vat-class :exempt` when it applies.

   Algorithm sources (public, non-copyrightable rate tables):
     - BMF UStG (Umsatzsteuergesetz 1994):
       https://www.bmf.gv.at/themen/steuern/umsatzsteuer.html
     - BMF UVA-Formular Stand 2026 (Kz 022/006/029/057/066/011/021)
     - UStG §10 (Steuersätze) — published normative source

   ## API

     compute-tax {:line :vat-class}
       → {:net :ust :total-gross :vat-class :rate :recipient-owes?}
     compute-invoice-tax {:lines}
       → aggregated with :per-line breakdown"
  (:require [kontor.money :as money]))

;; ============================================================================
;; Rate table
;; ============================================================================

(def standard-rate
  "USt Normalsteuersatz — 20%. Applies to most B2B goods + services."
  0.20M)

(def reduced-13-rate
  "Ermäßigter Steuersatz — 13%. Kulturelle Veranstaltungen,
   Inlandsflüge, lebende Tiere, Wein ab-Hof."
  0.13M)

(def reduced-10-rate
  "Ermäßigter Steuersatz — 10%. Lebensmittel, Bücher, Pharma,
   Wohnungsmiete (teilweise), Restaurants (Speisen),
   Personenbeförderung."
  0.10M)

(def vat-class->rate
  "VAT class → BigDecimal rate. `:zero`, `:exempt`, and
   `:reverse-charge` all map to 0M but with different audit
   semantics (see ns docstring)."
  {:standard       standard-rate
   :reduced-13     reduced-13-rate
   :reduced-10     reduced-10-rate
   :zero           0M
   :exempt         0M
   :reverse-charge 0M})

(def vat-classes
  "The set of valid `:vat-class` values."
  (set (keys vat-class->rate)))

(def reverse-charge-classes
  "VAT classes where the recipient self-assesses output VAT — the
   supplier emits no output VAT but the invoice must disclose
   `Steuerschuldnerschaft des Leistungsempfängers`."
  #{:reverse-charge})

(def zero-rated-classes
  "VAT classes whose arithmetic produces 0% output VAT (all of
   them: zero, exempt, reverse-charge plus any class with rate 0)."
  #{:zero :exempt :reverse-charge})

;; ============================================================================
;; Compute helpers
;; ============================================================================

(defn- bd
  "Extract the BigDecimal from a Money record, or pass through a
   BigDecimal / numeric input."
  ^java.math.BigDecimal [m]
  (cond
    (instance? java.math.BigDecimal m) m
    (number? m) (bigdec m)
    (and (map? m) (contains? m :amount)) (:amount m)
    :else (throw (ex-info "Cannot coerce to BigDecimal" {:value m}))))

(defn- m-zero [] (money/zero :EUR))

(defn- m-cents
  "Round a BigDecimal to 2dp HALF-EVEN and wrap in a Money :EUR.
   HALF-EVEN matches the kernel default; the BMF does not mandate
   a specific rounding mode for invoice-line VAT, but UStG §18
   requires the cumulative invoice VAT to match the sum of line
   VATs (€0.01 tolerance)."
  [^java.math.BigDecimal amt]
  (money/money
   (.setScale amt 2 java.math.RoundingMode/HALF_EVEN)
   :EUR))

(defn- m-mul
  "Multiply a BigDecimal net amount by a rate, returning a Money :EUR
   rounded to 2dp HALF-EVEN."
  [^java.math.BigDecimal net ^java.math.BigDecimal rate]
  (m-cents (.multiply net rate)))

;; ============================================================================
;; Validation
;; ============================================================================

(defn- assert-vat-class! [vat-class]
  (when-not (contains? vat-classes vat-class)
    (throw (ex-info "Invalid :vat-class"
                    {:value vat-class
                     :valid vat-classes})))
  vat-class)

;; ============================================================================
;; Compute
;; ============================================================================

(defn rate-for
  "Return the BigDecimal output-VAT rate for a `:vat-class`. Public
   helper for callers that want to know the rate without computing
   tax (e.g. printing rate on an invoice line)."
  [vat-class]
  (assert-vat-class! vat-class)
  (get vat-class->rate vat-class))

(defn compute-tax
  "Compute the per-line VAT breakdown for one Austrian sales line.

   Required input:
     :line       BigDecimal | Money | number — net taxable amount

   Optional input:
     :vat-class  keyword in `vat-classes` (default :standard)

   Returns:
     {:net             Money :EUR  ; echoed for caller convenience
      :ust             Money :EUR  ; output VAT (0 for zero/exempt/RC)
      :total-gross     Money :EUR  ; net + ust
      :vat-class       keyword     ; echoed
      :rate            BigDecimal  ; the rate applied (informational)
      :recipient-owes? boolean     ; true for :reverse-charge
      :supplier-deducts-vorsteuer? boolean
                                   ; true unless :exempt — informs the
                                   ; consumer whether matching vendor
                                   ; bills should claim Vorsteuer.}

   For `:zero`, `:exempt`, and `:reverse-charge`, the `:ust` field is
   Money 0 EUR; the total-gross equals the net.

   Examples:
     (compute-tax {:line 1000M})
       → {:net 1000.00 :ust 200.00 :total-gross 1200.00 :rate 0.20 …}

     (compute-tax {:line 1000M :vat-class :reduced-13})
       → {:net 1000.00 :ust 130.00 :total-gross 1130.00 :rate 0.13 …}

     (compute-tax {:line 1000M :vat-class :reduced-10})
       → {:net 1000.00 :ust 100.00 :total-gross 1100.00 :rate 0.10 …}

     (compute-tax {:line 1000M :vat-class :zero})
       → {:net 1000.00 :ust 0 :total-gross 1000.00 :rate 0 …}

     (compute-tax {:line 1000M :vat-class :reverse-charge})
       → {:net 1000.00 :ust 0 :total-gross 1000.00
          :recipient-owes? true :rate 0 …}"
  [{:keys [line vat-class]
    :or {vat-class :standard}}]
  (assert-vat-class! vat-class)
  (let [net-bd (bd line)
        net-m  (m-cents net-bd)
        rate   (get vat-class->rate vat-class)
        ust    (if (zero? (.signum ^java.math.BigDecimal rate))
                 (m-zero)
                 (m-mul net-bd rate))
        gross  (money/add net-m ust)]
    {:net             net-m
     :ust             ust
     :total-gross     gross
     :vat-class       vat-class
     :rate            rate
     :recipient-owes? (contains? reverse-charge-classes vat-class)
     :supplier-deducts-vorsteuer? (not= vat-class :exempt)}))

(defn compute-invoice-tax
  "Aggregate VAT over a sequence of invoice lines on one Austrian
   invoice. Each line is `{:line <amount> :vat-class <kw>?}`; the
   per-class breakdown is returned alongside the totals.

   Returns:
     {:net          Money :EUR  ; sum of line nets
      :ust          Money :EUR  ; sum of line VATs
      :total-gross  Money :EUR  ; sum of line gross
      :by-class     {vat-class {:net Money :ust Money}}
      :per-line     [<per-line compute-tax result> …]}

   The :by-class bucket is what the invoice posting builder needs
   to route revenue + USt postings to per-rate accounts (4000/4010/
   4020 + 3500/3510/3520)."
  [{:keys [lines]}]
  (when (empty? lines)
    (throw (ex-info "compute-invoice-tax requires at least one line"
                    {:lines lines})))
  (let [per-line (mapv compute-tax lines)
        zero     (m-zero)
        sums     (reduce
                  (fn [acc {:keys [net ust total-gross]}]
                    (-> acc
                        (update :net money/add net)
                        (update :ust money/add ust)
                        (update :total-gross money/add total-gross)))
                  {:net zero :ust zero :total-gross zero}
                  per-line)
        by-class (reduce
                  (fn [acc {:keys [vat-class net ust]}]
                    (-> acc
                        (update-in [vat-class :net]
                                   (fnil money/add zero) net)
                        (update-in [vat-class :ust]
                                   (fnil money/add zero) ust)))
                  {}
                  per-line)]
    (assoc sums
           :by-class by-class
           :per-line per-line)))
