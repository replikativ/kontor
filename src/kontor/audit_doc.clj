(ns kontor.audit-doc
  "Audit-doc helpers — ADR-038, extended with privilege
   classification (ADR-051).

   An :audit-doc is a kernel entity that points at a supporting
   document the consumer stores elsewhere (uploaded PDF in S3, email
   thread in IMAP, regulator clearance token, etc.). The kernel
   stores the ref + content-hash + URI for integrity verification;
   the bytes live wherever the consumer chooses.

   Used by :status-history/supporting-doc on sensitive transitions
   (cancel posted invoice, GDPR redaction, etc.) — the auditor's
   answer to 'where's the proof?'.

   ## Privilege (ADR-051)

   :audit-doc/privilege classifies a document's legal-privilege
   status. It is a status-machine facet — changes go through
   `reclassify-privilege!`, which records who/why/supporting-doc on
   a :status-history row; a *waiver* (→ :none from a privileged
   value) is ADR-038 approval-gated.

   THE KERNEL TAGS; THE CONSUMER ENFORCES. `visible-to?` /
   `filter-by-privilege` are pure label-comparison helpers — they
   take a viewer's privilege *set*, never a user id. There is no
   kernel ACL; the consumer's auth layer owns enforcement."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.status-machine :as sm]))

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
              ;; The uploader IS the creator — stamp :create/uid too
              ;; so ADR-038 :no-self-approval can fire on privilege
              ;; waivers (ADR-051): the doc creator can't waive its
              ;; privilege alone.
              uploaded-by-uid (assoc :audit-doc/uploaded-by-uid uploaded-by-uid
                                     :create/uid uploaded-by-uid))]
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

;; ============================================================================
;; Privilege classification — ADR-051
;; ============================================================================

(def privilege-vocab
  "Open-set starter vocabulary for :audit-doc/privilege. :none is the
   default (nil is treated as :none). Consumer companions extend by
   transacting additional :status-transition + :approval-policy rows
   for their own values (:hipaa-phi, :ferpa-edu, …)."
  [:none :attorney-client :work-product :joint-defense
   :settlement-communication :trade-secret :pii-sensitive])

(def ^:private privileged-values
  "The non-:none members of the starter vocab — the values whose
   waiver (→ :none) is approval-gated."
  (vec (remove #(= :none %) privilege-vocab)))

(def status-transition-seeds
  "ADR-034 :status-transition rows for the :audit-doc/privilege
   facet. Privilege is a *complete graph* over the starter vocab —
   a classification can change to any other (re-determination,
   waiver, upgrade). The status machine still earns its place: it
   gives every change a :status-history row and lets the approval
   policy fire on the waiver edges."
  (vec
   (for [from privilege-vocab
         to   privilege-vocab
         :when (not= from to)]
     {:status-transition/entity-type :audit-doc
      :status-transition/facet :audit-doc/privilege
      :status-transition/from from
      :status-transition/to to
      :status-transition/active true
      :status-transition/name (str "Reclassify " (name from) " → " (name to))})))

(def approval-policy-seeds
  "ADR-038 :approval-policy rows. Every <privileged> → :none edge —
   a privilege WAIVER — requires :no-self-approval (the person who
   classified a doc can't waive it alone), :requires-supporting-doc
   (the waiver determination), and :requires-non-empty-reason-note.
   Upgrades and privileged↔privileged re-classifications are NOT
   gated — over-classification is the safe direction."
  (vec
   (for [from privileged-values
         rule [:no-self-approval
               :requires-supporting-doc
               :requires-non-empty-reason-note]]
     {:approval-policy/entity-type     :audit-doc
      :approval-policy/facet           :audit-doc/privilege
      :approval-policy/transition-from from
      :approval-policy/transition-to   :none
      :approval-policy/rule            rule
      :approval-policy/active          true})))

(defn install-seeds!
  "Idempotently transact the :audit-doc/privilege status-transition +
   approval-policy seeds. Called from `kontor.core/install-schema!`.
   Guarded with a presence check (the composite-tuple-with-nil-in-
   tuple non-idempotency caveat)."
  [conn]
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where
                         [?e :status-transition/entity-type :audit-doc]
                         [?e :status-transition/facet :audit-doc/privilege]]
                       db))]
    (when-not already?
      (d/transact conn (vec (concat status-transition-seeds
                                    approval-policy-seeds))))))

(defn privilege-of
  "Current :audit-doc/privilege of `doc-spec`, normalized — nil is
   returned as :none."
  [db doc-spec]
  (let [eid (resolve-doc db doc-spec)]
    (or (:audit-doc/privilege (d/pull db [:audit-doc/privilege] eid))
        :none)))

(defn reclassify-privilege!
  "Change an :audit-doc's privilege classification through the
   status machine (ADR-034 + ADR-038). The :from is the doc's
   current privilege (nil normalized to :none). A waiver — `:to`
   :none from a privileged value — is approval-gated:
   :no-self-approval, :requires-supporting-doc,
   :requires-non-empty-reason-note.

   SoD-ANCHOR CAVEAT (research note 32 P1-4): the kernel's
   `:no-self-approval` rule compares `:changed-by-uid` against the
   entity's `:create/uid` — which `create-doc!` stamps to the doc's
   *uploader*. So the waiver SoD enforced here is uploader ≠ waiver-
   actor, NOT classifier ≠ waiver-actor. The consequential act is
   classification (counsel determining a doc privileged), and a
   consumer that needs classifier-vs-waiver SoD must enforce it in
   its own layer — the substrate records every classifier on the
   `:audit-doc/privilege` :status-history rows. A
   `:no-self-approval-vs-last-classifier` rule variant in
   `kontor.status-machine` is a documented follow-up.

   Required opts:
     :doc            :audit-doc code or eid
     :to             new privilege keyword
     :changed-by-uid ref to :create/uid
     :reason         keyword (:privilege-determined |
                     :privilege-waived | :privilege-reclassified)

   Optional:
     :reason-note    free-text (required by ADR-038 on waivers)
     :supporting-doc ref to :audit-doc (required by ADR-038 on
                     waivers — the waiver/classification memo)
     :vt-from / :vt-to  valid-time bounds (default :vt-from = now)"
  [conn {:keys [doc to changed-by-uid reason reason-note supporting-doc
                vt-from vt-to]}]
  (when-not to             (throw (ex-info ":to required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not reason         (throw (ex-info ":reason required" {})))
  (let [db (d/db conn)
        doc-eid (resolve-doc db doc)
        _ (when-not doc-eid
            (throw (ex-info "Audit-doc not found"
                            {:type :audit-doc/not-found :spec doc})))
        from (privilege-of db doc-eid)
        now (java.util.Date.)
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity doc-eid
                            :entity-type :audit-doc
                            :facet :audit-doc/privilege
                            :from from
                            :to to
                            :changed-at now
                            :changed-by-uid changed-by-uid
                            :reason reason}
                     reason-note    (assoc :reason-note reason-note)
                     supporting-doc (assoc :supporting-doc supporting-doc)))]
    (d/transact conn (kbt/with-vt status-tx
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

(defn visible-to?
  "Pure label comparison: would a viewer holding the privilege set
   `viewer-privilege` see `doc-spec`?

   Rule: a doc classified :none (or nil) is visible to everyone; a
   doc with any privileged classification is visible only if
   `viewer-privilege` contains that classification.

   THE KERNEL TAGS; THE CONSUMER ENFORCES. This helper does not know
   about users — `viewer-privilege` is a set the consumer's auth
   layer computes for whoever is asking. The kernel never gates a
   URI; it answers a label question."
  [db doc-spec viewer-privilege]
  (let [p (privilege-of db doc-spec)]
    (or (= :none p)
        (contains? (set viewer-privilege) p))))

(defn filter-by-privilege
  "Keep only the `doc-eids` a viewer holding `viewer-privilege` can
   see (see `visible-to?`). This is the helper ADR-052's DSAR
   bundler calls — privileged docs are NOT auto-included in a
   data-subject's package; they need legal-review opt-in."
  [db doc-eids viewer-privilege]
  (let [allowed (set viewer-privilege)]
    (filterv (fn [eid]
               (let [p (privilege-of db eid)]
                 (or (= :none p) (contains? allowed p))))
             doc-eids)))
