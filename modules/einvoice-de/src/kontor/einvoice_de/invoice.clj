(ns kontor.einvoice-de.invoice
  "Plain-Clojure invoice schema, the input shape consumed by the
   Mustang wrapper in `factur-x.clj`. Kept separate so callers can
   build / serialize / store invoices without pulling Mustang's
   60+ transitive deps onto the classpath unless they actually need
   to render Factur-X.

   Schema (keys are namespaced under :kontor.invoice/* / :party/* / :item/*):

       {:kontor.invoice/number       String       — invoice number / Rechnungsnummer
        :kontor.invoice/issue-date   #inst        — Ausstellungsdatum
        :kontor.invoice/due-date     #inst        — Fälligkeitsdatum (optional)
        :kontor.invoice/delivery-date #inst       — Leistungsdatum (optional)
        :kontor.invoice/currency     String       — ISO 4217, e.g. \"EUR\"
        :kontor.invoice/seller       <party-map>  — required (Verkäufer / Lieferant)
        :kontor.invoice/buyer        <party-map>  — required (Käufer / Kunde)
        :kontor.invoice/items        [<item-map>] — at least one
        :kontor.invoice/payment      <pay-map>    — bank details (optional)
        :kontor.invoice/notes        [String]     — free-text remarks (optional)
        :kontor.invoice/payment-terms String      — Zahlungsbedingungen (optional)
        :kontor.invoice/reference    String       — Buyer's reference / Leitweg-ID (B2G)}

       <party-map>
       {:party/name      String   — required
        :party/street    String
        :party/zip       String
        :party/city      String
        :party/country   String — ISO 3166-1 alpha-2 (\"DE\", \"AT\", \"FR\")
        :party/vat-id    String — USt-IdNr / VAT-ID (recommended for B2B)
        :party/tax-id    String — Steuernummer (alt. for DE small biz)
        :party/email     String
        :party/contact-name String}

       <item-map>
       {:item/name        String   — required (line description)
        :item/description String   — extended description (optional)
        :item/quantity    BigDecimal — required
        :item/unit-code   String   — UN/CEFACT unit code (\"HUR\"=hour,
                                       \"EA\"=each, \"KGM\"=kg, \"DAY\"=day)
        :item/unit-price  BigDecimal — net unit price
        :item/vat-rate    BigDecimal — % (e.g. 19.0M for German Regelsatz)
        :item/vat-category String — UNTDID 5305 (S=standard, AA=reduced,
                                       Z=zero, E=exempt, etc.)}

   No invariants beyond the required fields are enforced here — the
   downstream Mustang call surfaces semantic errors (missing seller
   VAT-ID for B2B, missing line items, etc.). `validate` runs a
   shallow shape check before handing off."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Validation (shallow — covers the things Mustang surfaces as cryptic NPEs)
;; ============================================================================

(defn- missing? [v]
  (or (nil? v)
      (and (string? v) (str/blank? v))
      (and (coll? v)   (empty? v))))

(defn- party-errors [where party]
  (cond-> []
    (missing? (:party/name party)) (conj (str where ": :party/name is required"))))

(defn- item-errors [idx item]
  (let [tag (str "item[" idx "]")]
    (cond-> []
      (missing? (:item/name item))      (conj (str tag ": :item/name is required"))
      (missing? (:item/quantity item))  (conj (str tag ": :item/quantity is required"))
      (missing? (:item/unit-price item))(conj (str tag ": :item/unit-price is required"))
      (missing? (:item/vat-rate item))  (conj (str tag ": :item/vat-rate is required")))))

(defn validate
  "Return a vector of validation error strings; empty when invoice is
   minimally well-formed enough to feed to Mustang. Use `validate!`
   if you want a thrown `ex-info` instead."
  [invoice]
  (-> []
      (into (cond-> []
              (missing? (:kontor.invoice/number invoice))
              (conj ":kontor.invoice/number is required")
              (missing? (:kontor.invoice/issue-date invoice))
              (conj ":kontor.invoice/issue-date is required")
              (missing? (:kontor.invoice/currency invoice))
              (conj ":kontor.invoice/currency is required (ISO 4217)")
              (missing? (:kontor.invoice/seller invoice))
              (conj ":kontor.invoice/seller is required")
              (missing? (:kontor.invoice/buyer invoice))
              (conj ":kontor.invoice/buyer is required")
              (missing? (:kontor.invoice/items invoice))
              (conj ":kontor.invoice/items must be a non-empty seq")))
      (into (party-errors "seller" (:kontor.invoice/seller invoice)))
      (into (party-errors "buyer"  (:kontor.invoice/buyer invoice)))
      (into (mapcat (fn [[i it]] (item-errors i it))
                    (map-indexed vector (or (:kontor.invoice/items invoice) []))))))

(defn validate!
  "Throw `ex-info` if `validate` returns errors; return invoice otherwise."
  [invoice]
  (let [errs (validate invoice)]
    (when (seq errs)
      (throw (ex-info (str "Invalid invoice: " (count errs) " error(s)")
                      {:type :einvoice/invalid-invoice
                       :errors errs
                       :invoice invoice})))
    invoice))

;; ============================================================================
;; Net / VAT / gross totals (per-line + per-VAT-rate + invoice)
;; ============================================================================

(defn line-net
  ^java.math.BigDecimal [{:item/keys [quantity unit-price]}]
  (.setScale (.multiply (bigdec quantity) (bigdec unit-price))
             2 java.math.RoundingMode/HALF_EVEN))

(defn line-vat
  ^java.math.BigDecimal [item]
  (.setScale (.multiply (line-net item)
                        (.divide (bigdec (:item/vat-rate item))
                                 (bigdec 100) 6 java.math.RoundingMode/HALF_EVEN))
             2 java.math.RoundingMode/HALF_EVEN))

(defn line-gross ^java.math.BigDecimal [item]
  (.add (line-net item) (line-vat item)))

(defn vat-summary
  "Per-VAT-rate breakdown: returns vec of {:rate %, :base-net €, :tax €}."
  [{:kontor.invoice/keys [items]}]
  (->> items
       (group-by :item/vat-rate)
       (map (fn [[rate group]]
              {:vat/rate     (bigdec rate)
               :vat/category (or (some :item/vat-category group) "S")
               :vat/base     (reduce #(.add ^java.math.BigDecimal %1 (line-net %2))
                                     0M group)
               :vat/tax      (reduce #(.add ^java.math.BigDecimal %1 (line-vat %2))
                                     0M group)}))
       (sort-by :vat/rate)
       vec))

(defn invoice-totals
  "Compute :kontor.invoice/total-net, :kontor.invoice/total-vat, :kontor.invoice/total-gross
   from the items list, returning a map you can merge onto the invoice."
  [invoice]
  (let [breakdown (vat-summary invoice)
        total-net (reduce #(.add ^java.math.BigDecimal %1 (:vat/base %2)) 0M breakdown)
        total-vat (reduce #(.add ^java.math.BigDecimal %1 (:vat/tax %2))  0M breakdown)]
    {:kontor.invoice/total-net   total-net
     :kontor.invoice/total-vat   total-vat
     :kontor.invoice/total-gross (.add total-net total-vat)
     :kontor.invoice/vat-breakdown breakdown}))
