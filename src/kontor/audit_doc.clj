(ns kontor.audit-doc
  "Audit-doc helpers — ADR-038.

   An :audit-doc is a kernel entity that points at a supporting
   document the consumer stores elsewhere (uploaded PDF in S3, email
   thread in IMAP, regulator clearance token, etc.). The kernel
   stores the ref + content-hash + URI for integrity verification;
   the bytes live wherever the consumer chooses.

   Used by :status-history/supporting-doc on sensitive transitions
   (cancel posted invoice, GDPR redaction, etc.) — the auditor's
   answer to 'where's the proof?'."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-code
  "Resolve an :audit-doc eid by its :audit-doc/code."
  [db code]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :audit-doc/code ?code]]
       db code))

(defn resolve-doc
  "Coerce `spec` to an :audit-doc eid."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-code db spec)
    :else          spec))

;; ============================================================================
;; Pulls
;; ============================================================================

(defn pull-doc
  "Pull the :audit-doc by code or eid."
  [db spec]
  (when-let [eid (resolve-doc db spec)]
    (d/pull db '[*] eid)))

;; ============================================================================
;; Transactors
;; ============================================================================

(defn create-doc!
  "Create an :audit-doc entity in one tx. Returns the tx-report.

   Required keys in spec:
     :code           — opaque consumer-supplied identifier
     :type           — keyword (e.g. :credit-memo, :customer-email)
     :storage-uri    — where the bytes live

   Optional:
     :title, :description, :content-hash, :uploaded-by-uid,
     :uploaded-at (default now)."
  [conn {:keys [code type title description content-hash storage-uri
                uploaded-by-uid uploaded-at]}]
  (when-not code     (throw (ex-info ":code required" {})))
  (when-not type     (throw (ex-info ":type required" {})))
  (when-not storage-uri (throw (ex-info ":storage-uri required" {})))
  (let [doc (cond-> {:audit-doc/code code
                     :audit-doc/type type
                     :audit-doc/storage-uri storage-uri
                     :audit-doc/uploaded-at (or uploaded-at (java.util.Date.))}
              title           (assoc :audit-doc/title title)
              description     (assoc :audit-doc/description description)
              content-hash    (assoc :audit-doc/content-hash content-hash)
              uploaded-by-uid (assoc :audit-doc/uploaded-by-uid uploaded-by-uid))]
    (d/transact conn [doc])))

(defn attach-supporting-doc!
  "Attach an :audit-doc to a specific :status-history row's
   :supporting-doc ref. Useful when the doc is uploaded AFTER the
   transition was recorded (e.g. customer emails the credit memo
   request post-facto, accountant uploads the email thread as
   supporting doc for the already-recorded :cancelled transition).

   Returns the tx-report."
  [conn history-eid doc-spec]
  (let [db (d/db conn)
        doc-eid (resolve-doc db doc-spec)]
    (when-not doc-eid
      (throw (ex-info "Audit-doc not found"
                      {:type :audit-doc/not-found
                       :spec doc-spec})))
    (d/transact conn [{:db/id history-eid
                       :status-history/supporting-doc doc-eid}])))

;; ============================================================================
;; Hashing helper
;; ============================================================================

(defn sha-256
  "Compute the SHA-256 hex digest of a byte-array. Helper for
   consumers building :audit-doc entries — they call this on the
   bytes before uploading, store the result as :content-hash, then
   on later verification re-download + re-hash + compare."
  ^String [^bytes bytes]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        digest (.digest md bytes)]
    (apply str (map #(format "%02x" %) digest))))
