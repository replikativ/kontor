(ns kontor.l10n-fr.tax
  "French TVA compute — taux normal (20%), taux intermédiaire (10%),
   taux réduit (5,5%), taux particulier (2,1%), and the special-case
   tax statuses (intra-EU B2B reverse-charge, export, exempt) as a
   callable function family.

   Where `kontor.l10n-fr.ca3` computes the *filing-side* (the
   Déclaration de TVA CA3 line values aggregated from posted tax-
   tagged ledger postings), this ns computes the *invoicing-side*:
   given a line amount and category, return the per-rate breakdown
   the invoice posting builder needs.

   The two namespaces are complementary and share no code: the filing
   report reads tagged ledger postings; the compute below is pure
   arithmetic against the published TVA rate table.

   ## Rate table — articles 278 to 278-0 ter CGI

   | Code   | Name            | Rate   | Typical scope                       |
   |--------|-----------------|--------|-------------------------------------|
   | :std   | taux normal     | 20%    | Default rate for goods and services |
   | :inter | taux intermédiaire | 10% | Restaurants, transport, hospitality |
   | :red   | taux réduit     | 5,5%   | Food, books, women's hygiene, etc.  |
   | :spec  | taux particulier | 2,1%  | Médicaments remboursés, certains   |
   |        |                 |        | journaux et publications de presse |
   | :zero  | taux zéro       | 0%     | Exports hors UE                     |
   | :exempt | exonéré        | 0%     | Services bancaires, location nue,  |
   |        |                 |        | enseignement, santé (cf. art.261)  |

   Reuses the CA3 rate-keyword set so the invoice builder, the CA3
   filing report, and this compute share one vocabulary.

   ## Reverse charge / intra-EU B2B

   `:intra-eu-b2b` — supply to a registered EU business outside FR.
   No French TVA charged at invoicing; the buyer self-assesses under
   the destination-country reverse-charge rule (CGI art.283-1). For
   compute purposes the result is structurally identical to `:zero`
   (no TVA on the invoice), but `:intra-eu-b2b` carries the semantic
   for the CA3 builder downstream (line 06 — acquisitions intra-
   communautaires — on the buyer side; line F2 — livraisons intra-
   communautaires exonérées — on the seller side).

   `:export` — supply of goods physically shipped outside the EU
   (art.262 I CGI). Zero-rated; ITCs are still claimable upstream.

   ## Zero-rated vs exempt — the kontor convention

   - `:zero`         — taxable in form, rate 0%, ITCs claimable.
                       Exports outside EU; some printed-press
                       categories pre-2024.
   - `:exempt`       — out of the tax base entirely. ITCs NOT
                       claimable. Services bancaires, locations nues
                       à usage d'habitation, prestations de santé,
                       enseignement, etc.
   - `:intra-eu-b2b` — like `:zero` for compute purposes; downstream
                       reporting treats it as a livraison intra-UE.

   ## What this module deliberately does NOT do

   - **No live DGFiP rate refresh.** Rates here are baked from the
     CGI as of 2026. When the legislator changes a rate (e.g. a
     COVID-era temporary cut, or a tampon-tax move from 20% to 5,5%
     in 2016), an l10n-fr artifact bump updates this table.
   - **No fiscal-position / customer-tax-exempt lookups.** The
     caller picks the rate keyword per line; the substrate does not
     resolve whether a particular customer or product belongs in
     `:red` vs `:std` (that's a consumer concern).
   - **No DEB (Déclaration d'Échanges de Biens) export.** Intra-EU
     reporting is a separate CA3-adjacent filing — out of scope
     here.

   ## Rounding

   Per BOFiP TVA-DECLA-30-10-20: HALF-UP is the historical filing-
   side rounding mode. Per ADR (kernel default), invoice-line
   amounts round HALF-EVEN. The CA3 report rounds to whole euros for
   the declaration. We round line TVA at 2dp HALF-EVEN here; the CA3
   report applies its own whole-euro rounding when needed.

   Algorithm sources (public, non-copyrightable rate tables):
     - BOFiP TVA-LIQ — https://bofip.impots.gouv.fr/bofip/3322-PGP
     - CGI art.278 to 278-0 ter — taux de TVA applicables en France
     - economie.gouv.fr — https://www.economie.gouv.fr/cedef/taux-tva

   ## API

     compute-tax {:line :rate :tax-status?} → {:tva :total-tax
                                                :total-gross :net
                                                :rate :tax-status}
     compute-invoice-tax {:lines} → {:tva :total-tax :total-gross
                                      :net :per-line}"
  (:require [kontor.money :as money]))

;; ============================================================================
;; Rate table
;; ============================================================================

(def tva-rate-by-code
  "TVA rate keyword → BigDecimal rate. The five rates in force in
   metropolitan France (overseas DROM use lower rates; out of scope)."
  {:std   0.20M
   :inter 0.10M
   :red   0.055M
   :spec  0.021M
   :zero  0.0M})

(def tva-rates
  "Set of valid `:rate` keys for compute-tax input. Default `:std`
   (20%)."
  (-> tva-rate-by-code keys set))

(def tax-statuses
  "Valid `:tax-status` values for compute-tax input. Default
   `:taxable` — rate-table TVA applies.

     :taxable      — normal rate per :rate keyword
     :exempt       — out of the tax base (services bancaires,
                     locations nues, santé, enseignement, …).
                     ITCs NOT claimable upstream.
     :intra-eu-b2b — reverse charge: buyer self-assesses TVA in
                     destination country. Zero on this invoice.
     :export       — supply shipped outside EU. Zero-rated; ITCs
                     claimable."
  #{:taxable :exempt :intra-eu-b2b :export})

(def ^:private no-tva-statuses
  "Statuses that produce zero TVA on the seller's invoice."
  #{:exempt :intra-eu-b2b :export})

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
   HALF-EVEN matches the kernel default."
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
;; Validators
;; ============================================================================

(defn- assert-rate! [rate]
  (when-not (contains? tva-rates rate)
    (throw (ex-info "Invalid :rate — must be a TVA rate keyword"
                    {:value rate
                     :valid tva-rates})))
  rate)

(defn- assert-status! [status]
  (when-not (contains? tax-statuses status)
    (throw (ex-info "Invalid :tax-status"
                    {:value status
                     :valid tax-statuses})))
  status)

;; ============================================================================
;; Compute
;; ============================================================================

(defn compute-tax
  "Compute the TVA breakdown for one taxable line.

   Required inputs:
     :line   BigDecimal | Money | number — net taxable amount (HT)

   Optional inputs:
     :rate         keyword in `tva-rates` (default :std).
                   Ignored when `:tax-status` is non-taxable.
     :tax-status   keyword in `tax-statuses` (default :taxable).

   Returns:
     {:tva         Money :EUR  ; TVA on this line
      :total-tax   Money :EUR  ; same as :tva (kept for shape parity
                                with multi-authority countries)
      :total-gross Money :EUR  ; net + tva
      :net         Money :EUR  ; net (echoed for caller convenience)
      :rate        keyword     ; :std :inter :red :spec :zero
      :rate-value  BigDecimal  ; 0.20M / 0.10M / 0.055M / 0.021M / 0M
      :tax-status  keyword}

   For `:exempt`, `:intra-eu-b2b`, `:export`, every TVA field is
   Money 0 :EUR; the total-gross equals the net.

   Examples:
     (compute-tax {:line 1000M})
       → {:tva 200.00 :total-gross 1200.00 :rate :std …}

     (compute-tax {:line 1000M :rate :inter})
       → {:tva 100.00 :total-gross 1100.00 :rate :inter …}

     (compute-tax {:line 1000M :rate :red})
       → {:tva 55.00 :total-gross 1055.00 :rate :red …}

     (compute-tax {:line 1000M :rate :spec})
       → {:tva 21.00 :total-gross 1021.00 :rate :spec …}

     (compute-tax {:line 1000M :tax-status :intra-eu-b2b})
       → {:tva 0 :total-gross 1000.00 :tax-status :intra-eu-b2b …}"
  [{:keys [line rate tax-status]
    :or {rate :std tax-status :taxable}}]
  (assert-rate! rate)
  (assert-status! tax-status)
  (let [net-bd     (bd line)
        net-m      (m-cents net-bd)
        zero       (m-zero)
        rate-value (tva-rate-by-code rate)]
    (if (contains? no-tva-statuses tax-status)
      {:tva zero
       :total-tax zero
       :total-gross net-m
       :net net-m
       :rate rate
       :rate-value rate-value
       :tax-status tax-status}
      (let [tva (m-mul net-bd rate-value)
            gross (money/add net-m tva)]
        {:tva tva
         :total-tax tva
         :total-gross gross
         :net net-m
         :rate rate
         :rate-value rate-value
         :tax-status tax-status}))))

(defn compute-invoice-tax
  "Aggregate TVA over a sequence of invoice lines. Each line is
   `{:line <amount> :rate <kw>? :tax-status <kw>?}`.

   Unlike CA, FR has no ship-to-province dimension — the rate is per
   line. Mixed-rate invoices are common in France (e.g. a restaurant
   bill mixes 10% food with 20% alcoholic drinks).

   Returns a map with the same per-line shape plus a `:per-line`
   vector. The summary fields are the sum across lines (each rounded
   to 2dp first, then added — matching DGFiP's invoice-level TVA
   check tolerance)."
  [{:keys [lines]}]
  (let [per-line (mapv compute-tax lines)
        zero (m-zero)
        sums (reduce
              (fn [acc {:keys [tva total-tax total-gross net]}]
                (-> acc
                    (update :tva money/add tva)
                    (update :total-tax money/add total-tax)
                    (update :total-gross money/add total-gross)
                    (update :net money/add net)))
              {:tva zero :total-tax zero :total-gross zero :net zero}
              per-line)]
    (assoc sums :per-line per-line)))
