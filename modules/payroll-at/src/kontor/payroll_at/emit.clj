(ns kontor.payroll-at.emit
  "Annual L16 Lohnzettel (BMF FinanzOnline XML) + the
   AtPayrollEmitProvider composing record (ADR-072).

   The L16 is the annual wage statement an employer pushes to
   FinanzOnline for each employee. The official BMF XML format
   (Lohnzettel-Verordnung; root element `<Lohnzettel>`) captures the
   annual aggregates split into Section I (regular income) and
   Section II (Sonderzahlungen at the 6 % begünstigte Steuersatz).

   The kernel module produces the XML bytes + records an
   `:audit-doc/category :payroll-filing` row. The transmission to
   FinanzOnline is a consumer concern (the kernel ships no API keys
   per ADR-072)."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]
            [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.payroll-at.mbgm :as mbgm]
            [kontor.validation :as validation])
  (:import [java.io ByteArrayOutputStream]
           [java.math BigDecimal RoundingMode]
           [java.text SimpleDateFormat]
           [java.util Date TimeZone]))

;; ============================================================================
;; Date helpers
;; ============================================================================

(defn- ymd ^String [^Date d]
  (let [fmt (doto (SimpleDateFormat. "yyyy-MM-dd")
              (.setTimeZone (TimeZone/getTimeZone "UTC")))]
    (.format fmt d)))

(defn- year-of ^long [^Date d]
  (let [cal (doto (java.util.Calendar/getInstance
                   (TimeZone/getTimeZone "UTC"))
              (.setTime d))]
    (.get cal java.util.Calendar/YEAR)))

;; ============================================================================
;; Amount rendering
;; ============================================================================

(defn- ^String fmt-amt
  [x]
  (let [^BigDecimal bd (if (instance? BigDecimal x)
                         x
                         (BigDecimal/valueOf (long (or x 0))))]
    (.toPlainString (.setScale bd 2 RoundingMode/HALF_EVEN))))

(defn- sum-bd
  ^BigDecimal [xs]
  (reduce (fn [^BigDecimal a ^BigDecimal b] (.add a b)) 0M (map bigdec xs)))

;; ============================================================================
;; L16 element construction
;; ============================================================================

(defn- annual-aggregate
  "Aggregate one employee's annual numbers from a sequence of monthly
   :payroll-result employee maps. Returns a flat aggregate map of
   bigdecs."
  [emp-rows]
  (letfn [(s [wt]
            (sum-bd
             (mapcat (fn [emp]
                       (->> (:line-items emp)
                            (filter #(= wt (:wage-type %)))
                            (map :amount)))
                     emp-rows)))]
    (let [;; Regular section (I): grundgehalt + überstunden + the
          ;; Lohnsteuer + SV-AN attributed to regular income. The
          ;; engine has already split the LSt between regular and
          ;; Sonderzahlung; the line-item :wage-type :lohnsteuer is
          ;; the union and we ALSO split here by mathematical
          ;; convention: regular-LSt = total-LSt − (sonder * 0.06).
          regular-gross (.add ^BigDecimal (s :grundgehalt)
                              ^BigDecimal (s :überstunden))
          sonder-gross (.add ^BigDecimal (s :urlaubsremuneration)
                             ^BigDecimal (s :weihnachtsremuneration))
          sv-an (s :sv-arbeitnehmer)
          lst-total (s :lohnsteuer)
          ;; Begünstigte 6 % Steuersatz auf den Jahressechstel:
          sonder-lst (.setScale (.multiply ^BigDecimal sonder-gross 0.06M)
                                2 RoundingMode/HALF_EVEN)
          regular-lst (.subtract ^BigDecimal lst-total ^BigDecimal sonder-lst)
          regular-lst (if (neg? (.signum ^BigDecimal regular-lst))
                        0M regular-lst)]
      {:regular-gross  (.setScale ^BigDecimal regular-gross 2 RoundingMode/HALF_EVEN)
       :sonder-gross   (.setScale ^BigDecimal sonder-gross  2 RoundingMode/HALF_EVEN)
       :sv-an          (.setScale ^BigDecimal sv-an         2 RoundingMode/HALF_EVEN)
       :lst-regular    (.setScale ^BigDecimal regular-lst   2 RoundingMode/HALF_EVEN)
       :lst-sonder     (.setScale ^BigDecimal sonder-lst    2 RoundingMode/HALF_EVEN)})))

(defn- l16-employee-element
  "Build a single <L16-Datensatz> element for one employee."
  [vsnr name agg]
  (xml/element
   :L16-Datensatz {:VSNR vsnr}
   (xml/element :Name {} (str name))
   ;; Section I — regular income
   (xml/element :Bruttobezuege {} (fmt-amt (:regular-gross agg)))
   (xml/element :Lohnsteuer-Regulaer {} (fmt-amt (:lst-regular agg)))
   ;; Section II — Sonderzahlungen at 6 %
   (xml/element :Sonderzahlungen-Brutto {} (fmt-amt (:sonder-gross agg)))
   (xml/element :Sonderzahlungen-Lohnsteuer {} (fmt-amt (:lst-sonder agg)))
   ;; SV
   (xml/element :SV-Beitraege-AN {} (fmt-amt (:sv-an agg)))))

(defn build-l16-element
  "Build the root XML for an annual L16 submission. Does NOT serialize.

   Inputs:
     :year                  4-digit calendar year (long)
     :employer-name         string
     :employer-uid          UID-Nummer (string) of the employer
     :employer-fbnr         Firmenbuchnummer (optional)
     :employees             [{:vsnr :name :monthly-rows [<emp-record>...]} ...]
       monthly-rows are the per-month employee maps emitted by
       kontor.payroll-at.compute — one per month."
  [{:keys [year employer-name employer-uid employer-fbnr employees]}]
  (when-not year         (throw (ex-info ":year required" {})))
  (when-not employer-name (throw (ex-info ":employer-name required" {})))
  (when-not employer-uid  (throw (ex-info ":employer-uid required" {})))
  (when (empty? employees)
    (throw (ex-info ":employees required (non-empty)" {})))
  (apply xml/element :Lohnzettel
         {:xmlns "urn:bmf:lohnzettel:v1" :Veranlagungsjahr (str year)}
         (concat
          [(xml/element :Header {}
                        (xml/element :Erstellt {} (ymd (Date.))))
           (xml/element :Dienstgeber {}
                        (xml/element :Name {} employer-name)
                        (xml/element :UID {} employer-uid)
                        (when employer-fbnr
                          (xml/element :Firmenbuchnummer {} employer-fbnr)))]
          (mapv (fn [{:keys [vsnr name monthly-rows]}]
                  (l16-employee-element vsnr name
                                        (annual-aggregate monthly-rows)))
                employees))))

(defn emit-l16-xml
  ^bytes [opts]
  (let [el (build-l16-element opts)
        baos (ByteArrayOutputStream.)]
    (with-open [w (java.io.OutputStreamWriter. baos "UTF-8")]
      (xml/emit el w))
    (.toByteArray baos)))

(defn emit-l16-string
  ^String [opts]
  (String. (emit-l16-xml opts) "UTF-8"))

;; ============================================================================
;; audit-doc emit + the provider record
;; ============================================================================

(defn emit-l16-tx-data
  "Pure tx-data: build the annual L16 XML, hash it, produce
   :audit-doc creation tx-data. Returns
     {:tx-data <tx-data> :bytes <bytes> :hash <sha> :code <c> :title <t>}.

   Required opts:
     :year, :employer-name, :employer-uid, :employees, :storage-uri.
   Optional:
     :code (default 'l16-<year>'), :uploaded-by-uid, :employer-fbnr."
  [db {:keys [year employer-name employer-uid employer-fbnr employees
              storage-uri code uploaded-by-uid]}]
  (when-not storage-uri  (throw (ex-info ":storage-uri required" {})))
  (let [bytes (emit-l16-xml
               {:year year
                :employer-name employer-name
                :employer-uid employer-uid
                :employer-fbnr employer-fbnr
                :employees employees})
        sha (audit-doc/sha-256 bytes)
        code (or code (str "l16-" year))
        title (str "L16 Lohnzettel " year)
        tx-data (audit-doc/create-doc-tx-data
                 db
                 (cond-> {:code code
                          :type :l16-lohnzettel
                          :title title
                          :description (str "BMF L16 annual submission for " year)
                          :content-hash sha
                          :storage-uri storage-uri
                          :category :payroll-filing
                          :language :de}
                   uploaded-by-uid (assoc :uploaded-by-uid uploaded-by-uid)))]
    {:tx-data tx-data
     :bytes bytes
     :hash sha
     :code code
     :title title}))

(defn emit-l16!
  [conn opts]
  (let [db (d/db conn)
        {:keys [tx-data] :as result} (emit-l16-tx-data db opts)
        tx-report (validation/transact-with-validation conn tx-data)]
    (assoc result :tx-report tx-report)))

;; ============================================================================
;; AtPayrollEmitProvider — composes mBGM (monthly) + L16 (annual)
;; ============================================================================

(defprotocol PayrollEmitProvider
  "Per-jurisdiction payroll-filing emitter (ADR-072).

   Mirrors the shape of `kontor.einvoice-provider/EInvoiceProvider`.
   Implementations produce wire-format bytes and records :audit-doc
   for the audit chain."

  (envelope-format [this]
    "Identifying keyword (e.g. :at/mbgm, :at/l16, :de/lohnsteuer-anm).")

  (emit-monthly! [this conn opts]
    "Emit + record the monthly filing. Returns
     {:tx-report :bytes :hash :code :title}.")

  (emit-annual! [this conn opts]
    "Emit + record the annual filing. Returns
     {:tx-report :bytes :hash :code :title}.

     Some jurisdictions have no annual artifact — those impls return
     {:emit-annual!/skipped? true}."))

(defrecord AtPayrollEmitProvider []
  PayrollEmitProvider
  (envelope-format [_] :at/payroll-filing)
  (emit-monthly! [_ conn opts] (mbgm/emit-mbgm! conn opts))
  (emit-annual! [_ conn opts] (emit-l16! conn opts)))

(defn make-at-emit-provider
  "Construct the default AT emit-provider record. Pure — no state."
  []
  (->AtPayrollEmitProvider))
