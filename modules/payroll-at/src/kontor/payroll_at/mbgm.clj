(ns kontor.payroll-at.mbgm
  "Monthly ÖGK mBGM (monatliche Beitragsgrundlagenmeldung) — ADR-072.

   Builds the XML artifact from a normalized `:payroll-result`, hashes
   it, and records an `:audit-doc` so the regulator filing is part of
   the audit chain.

   The audit-doc shape:
     :audit-doc/category :payroll-filing
     :audit-doc/language :de
     :audit-doc/type     :mbgm
     :audit-doc/title    'mBGM <yyyy-MM>'
     :audit-doc/storage-uri  consumer-supplied
     :audit-doc/content-hash SHA-256 of the XML bytes"
  (:require [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.payroll-at.elda :as elda]
            [kontor.validation :as validation])
  (:import [java.text SimpleDateFormat]
           [java.util Date TimeZone]))

(defn- yyyymm ^String [^Date d]
  (let [fmt (doto (SimpleDateFormat. "yyyy-MM")
              (.setTimeZone (TimeZone/getTimeZone "UTC")))]
    (.format fmt d)))

(defn emit-mbgm-tx-data
  "Pure tx-data builder (ADR-068). Generates the mBGM XML, computes
   its SHA-256, and produces an :audit-doc creation tx-data vector.

   Required opts:
     :payroll-result               normalized engine output
     :dienstgeber-beitragskonto    employer's ÖGK number
     :storage-uri                  where the consumer will store the bytes

   Optional opts:
     :employer-name                string
     :code                         audit-doc code; default 'mbgm-<yyyy-MM>'
     :uploaded-by-uid              ref to a user entity

   Returns {:tx-data <tx-data> :bytes <bytes> :hash <hex-string>
            :code <string> :title <string>} — the caller can choose
   to skip the tx-data return and use just the bytes."
  [db {:keys [payroll-result dienstgeber-beitragskonto storage-uri
              employer-name code uploaded-by-uid]}]
  (when-not payroll-result
    (throw (ex-info ":payroll-result required" {})))
  (when-not dienstgeber-beitragskonto
    (throw (ex-info ":dienstgeber-beitragskonto required" {})))
  (when-not storage-uri
    (throw (ex-info ":storage-uri required" {})))
  (let [period (:payroll-result/period payroll-result)
        period-str (yyyymm (:from period))
        bytes (elda/emit-mbgm-xml
               {:dienstgeber-beitragskonto dienstgeber-beitragskonto
                :employer-name employer-name
                :period period
                :employees (:payroll-result/employees payroll-result)})
        sha (audit-doc/sha-256 bytes)
        code (or code (str "mbgm-" period-str))
        title (str "mBGM " period-str)
        tx-data (audit-doc/create-doc-tx-data
                 db
                 (cond-> {:code code
                          :type :mbgm
                          :title title
                          :description (str "ÖGK mBGM submission for " period-str)
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

(defn emit-mbgm!
  "Build + record the mBGM. Returns
     {:tx-report <r> :bytes <bytes> :hash <sha> :code <c> :title <t>}.

   The CONSUMER is responsible for uploading :bytes to :storage-uri —
   the kernel only records the URI + hash. (Same convention as ADR-038
   audit-doc: bytes live wherever, kernel stores the pointer.)"
  [conn opts]
  (let [db (d/db conn)
        {:keys [tx-data] :as result} (emit-mbgm-tx-data db opts)
        tx-report (validation/transact-with-validation conn tx-data)]
    (assoc result :tx-report tx-report)))
