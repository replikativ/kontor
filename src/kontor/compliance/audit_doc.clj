(ns kontor.compliance.audit-doc
  "Audit-doc helpers — ADR-038, extended with privilege
   classification (ADR-051).

   An :audit-doc is a kernel entity that points at a supporting
   document the consumer stores elsewhere (uploaded PDF in S3, email
   thread in IMAP, regulator clearance token, etc.). The kernel
   stores the ref + content-hash + URI for integrity verification;
   the bytes live wherever the consumer chooses.

   Used by :kontor.status-history/supporting-doc on sensitive transitions
   (cancel posted invoice, GDPR redaction, etc.) — the auditor's
   answer to 'where's the proof?'.

   ## Privilege (ADR-051)

   :kontor.audit-doc/privilege classifies a document's legal-privilege
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
            [kontor.workflow.status-machine :as sm]
            [kontor.validation :as validation]))

;; ============================================================================
;; Canonical categories (ADR-075 + ADR-094)
;; ============================================================================
;;
;; Closes note 86 P0-86-2 (category vocabulary canonicalization) + adds
;; the 8 HR values from note 93 §4.1. Open-set — the substrate still
;; accepts any keyword on `:kontor.audit-doc/category`; this def names the
;; values the project endorses + documents. Consumers extending the
;; vocabulary (e.g. :hr-equity-vesting, :hr-secondment-agreement) just
;; transact arbitrary keywords; no migration.
;;
;; Refusal posture (ADR-094): the project deliberately does NOT
;; canonicalize values that facilitate AI-Act-banned use (emotion
;; scores, biometric inference, covert telemetry). See note 93 §6.

(def canonical-categories
  "Endorsed `:kontor.audit-doc/category` values. Open-set; consumers extend
   freely. Grouped here for documentation + IDE autocomplete + DSAR /
   retention-policy targeting; the substrate never restricts."
  [;; Financial + regulator-bound filings
   :financial
   :payroll
   :payroll-filing
   :tax-filing
   :legal-proceeding
   :compliance-attestation
   ;; HR — note 93 §4.1
   :hr-personnel
   :hr-track-record
   :hr-activity-monitoring
   :hr-activity-content
   :hr-communications
   :hr-medical
   :hr-immigration
   :hr-background-check
   :hr-compensation-negotiation
   :hr-grievance
   :hr-monitoring-consent])

(def canonical-category-set
  "Set version of `canonical-categories` for fast membership checks
   (e.g. linters / consumer dashboards distinguishing canonical from
   extension values)."
  (set canonical-categories))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-code
  "Resolve an :audit-doc eid by its :kontor.audit-doc/code."
  [db code]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :kontor.audit-doc/code ?code]]
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

(defn create-doc-tx-data
  "Pure tx-data builder for `create-doc!` (ADR-068). Optional
   `:tempid` (default `\"audit-doc-1\"`) for cross-step references.

   Optional `:category` / `:language` set the corresponding
   `:kontor.audit-doc/category` (ADR-075) + `:kontor.audit-doc/language` (ADR-078)
   facets in one builder call — matches the substrate canonical
   vocabulary so callers don't reach for the raw attr names.

   `:uploaded-by-uid` is REQUIRED (ADR-153; it was optional through
   ADR-051). The uploader IS the creator, and the creator is what
   [[approval-policy-seeds]] — installed in EVERY kontor db via
   `kontor.core/install-schema!` — makes `:no-self-approval` compare a
   later waiver actor against. Since ADR-150 made that rule fail CLOSED on
   a nil creator, a doc created without an uploader can never have its
   privilege waived, including under court order. Optional-with-a-default
   would be worse than either: any sentinel value is a uid two different
   people can both equal, which is the self-approval hole wearing a
   disguise. So it is required, and callers that genuinely have no human
   uploader name the machine that did it — `kontor.actor/register-actor!`
   takes `:kind :system` for exactly this."
  [_db {:keys [code type title description content-hash storage-uri
               uploaded-by-uid uploaded-at tempid category language]
        :or {tempid "audit-doc-1"}}]
  (when-not code     (throw (ex-info ":code required" {})))
  (when-not type     (throw (ex-info ":type required" {})))
  (when-not storage-uri (throw (ex-info ":storage-uri required" {})))
  (when-not uploaded-by-uid
    (throw (ex-info
            (str ":uploaded-by-uid required (ADR-153) — it is stamped as "
                 ":kontor.audit/create-uid, which is what the seeded "
                 ":no-self-approval policy on every privilege-waiver edge "
                 "compares the waiving actor against. Without it this document's "
                 "privilege can never be waived. Pass the uploading actor's uid "
                 "(kontor.actor/register-actor!); for an unattended emit, register "
                 "the emitting system as an actor with :kind :system.")
            {:type :kontor.audit-doc/uploader-required :code code})))
  [(cond-> {:db/id tempid
            :kontor.audit-doc/code code
            :kontor.audit-doc/type type
            :kontor.audit-doc/storage-uri storage-uri
            :kontor.audit-doc/uploaded-at (or uploaded-at (java.util.Date.))
            ;; The uploader IS the creator — stamp :kontor.audit/create-uid
            ;; too so ADR-038 :no-self-approval can fire on privilege
            ;; waivers (ADR-051): the doc creator can't waive its privilege
            ;; alone. UNCONDITIONAL since ADR-153 — see the docstring.
            :kontor.audit-doc/uploaded-by-uid uploaded-by-uid
            :kontor.audit/create-uid uploaded-by-uid}
     title           (assoc :kontor.audit-doc/title title)
     description     (assoc :kontor.audit-doc/description description)
     content-hash    (assoc :kontor.audit-doc/content-hash content-hash)
     category        (assoc :kontor.audit-doc/category category)
     language        (assoc :kontor.audit-doc/language language))])

(defn create-doc!
  "Create an :audit-doc entity in one tx. Routes through the gate
   (ADR-068). Returns the tx-report.

   Required keys in spec:
     :code            — opaque consumer-supplied identifier
     :type            — keyword (e.g. :credit-memo, :customer-email)
     :storage-uri     — where the bytes live
     :uploaded-by-uid — the uploading actor (ADR-153; see
                        `create-doc-tx-data` for why this is not optional)

   Optional:
     :title, :description, :content-hash, :category, :language,
     :uploaded-at (default now).

   The pure tx-data builder is `create-doc-tx-data` (ADR-068)."
  [conn spec]
  (validation/transact-with-validation
   conn (create-doc-tx-data (d/db conn) spec)))

(defn attach-supporting-doc-tx-data
  "Pure tx-data builder for `attach-supporting-doc!` (ADR-068)."
  [db history-eid doc-spec]
  (let [doc-eid (resolve-doc db doc-spec)]
    (when-not doc-eid
      (throw (ex-info "Audit-doc not found"
                      {:type :kontor.audit-doc/not-found
                       :spec doc-spec})))
    [{:db/id history-eid
      :kontor.status-history/supporting-doc doc-eid}]))

(defn attach-supporting-doc!
  "Attach an :audit-doc to a specific :status-history row's
   :supporting-doc ref. Useful when the doc is uploaded AFTER the
   transition was recorded (e.g. customer emails the credit memo
   request post-facto, accountant uploads the email thread as
   supporting doc for the already-recorded :cancelled transition).
   Routes through the gate (ADR-068).

   Returns the tx-report."
  [conn history-eid doc-spec]
  (validation/transact-with-validation
   conn (attach-supporting-doc-tx-data (d/db conn) history-eid doc-spec)))

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
  "Open-set starter vocabulary for :kontor.audit-doc/privilege. :none is the
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
  "ADR-034 :status-transition rows for the :kontor.audit-doc/privilege
   facet. Privilege is a *complete graph* over the starter vocab —
   a classification can change to any other (re-determination,
   waiver, upgrade). The status machine still earns its place: it
   gives every change a :status-history row and lets the approval
   policy fire on the waiver edges."
  (vec
   (for [from privilege-vocab
         to   privilege-vocab
         :when (not= from to)]
     {:kontor.status-transition/entity-type :audit-doc
      :kontor.status-transition/facet :kontor.audit-doc/privilege
      :kontor.status-transition/from from
      :kontor.status-transition/to to
      :kontor.status-transition/active true
      :kontor.status-transition/name (str "Reclassify " (name from) " → " (name to))})))

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
     {:kontor.approval-policy/entity-type     :audit-doc
      :kontor.approval-policy/facet           :kontor.audit-doc/privilege
      :kontor.approval-policy/transition-from from
      :kontor.approval-policy/transition-to   :none
      :kontor.approval-policy/rule            rule
      :kontor.approval-policy/active          true})))

(defn install-seeds!
  "Idempotently transact the :kontor.audit-doc/privilege status-transition +
   approval-policy seeds. Called from `kontor.core/install-schema!`.
   Guarded with a presence check (the composite-tuple-with-nil-in-
   tuple non-idempotency caveat)."
  [conn]
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where
                         [?e :kontor.status-transition/entity-type :audit-doc]
                         [?e :kontor.status-transition/facet :kontor.audit-doc/privilege]]
                       db))]
    (when-not already?
      (d/transact conn (vec (concat status-transition-seeds
                                    approval-policy-seeds))))))

(defn privilege-of
  "Current :kontor.audit-doc/privilege of `doc-spec`, normalized — nil is
   returned as :none."
  [db doc-spec]
  (let [eid (resolve-doc db doc-spec)]
    (or (:kontor.audit-doc/privilege (d/pull db [:kontor.audit-doc/privilege] eid))
        :none)))

(declare reclassify-privilege-tx-data)

(defn reclassify-privilege!
  "Change an :audit-doc's privilege classification through the
   status machine (ADR-034 + ADR-038). The :from is the doc's
   current privilege (nil normalized to :none). A waiver — `:to`
   :none from a privileged value — is approval-gated:
   :no-self-approval, :requires-supporting-doc,
   :requires-non-empty-reason-note.

   SoD-ANCHOR CAVEAT (research note 32 P1-4): the kernel's
   `:no-self-approval` rule compares `:changed-by-uid` against the
   entity's `:kontor.audit/create-uid` — which `create-doc!` stamps to the doc's
   *uploader*. So the waiver SoD enforced here is uploader ≠ waiver-
   actor, NOT classifier ≠ waiver-actor. The consequential act is
   classification (counsel determining a doc privileged), and a
   consumer that needs classifier-vs-waiver SoD must enforce it in
   its own layer — the substrate records every classifier on the
   `:kontor.audit-doc/privilege` :status-history rows. A
   `:no-self-approval-vs-last-classifier` rule variant in
   `kontor.workflow.status-machine` is a documented follow-up.

   Required opts:
     :doc            :audit-doc code or eid
     :to             new privilege keyword
     :changed-by-uid ref to :kontor.audit/create-uid
     :reason         keyword (:privilege-determined |
                     :privilege-waived | :privilege-reclassified)

   Optional:
     :reason-note    free-text (required by ADR-038 on waivers)
     :supporting-doc ref to :audit-doc (required by ADR-038 on
                     waivers — the waiver/classification memo)
     :vt-from / :vt-to  valid-time bounds (default :vt-from = now)"
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (reclassify-privilege-tx-data
                        (d/db conn) (assoc opts :changed-at now))
            (or vt-from now)
            (or vt-to kbt/forever)))))

(defn reclassify-privilege-tx-data
  "Pure tx-data builder for `reclassify-privilege!` (ADR-068)."
  [db {:keys [doc to changed-by-uid reason reason-note supporting-doc
              changed-at]}]
  (when-not to             (throw (ex-info ":to required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not reason         (throw (ex-info ":reason required" {})))
  (let [doc-eid (resolve-doc db doc)
        _ (when-not doc-eid
            (throw (ex-info "Audit-doc not found"
                            {:type :kontor.audit-doc/not-found :spec doc})))
        from (privilege-of db doc-eid)]
    (sm/record-status-change-tx-data
     db
     (cond-> {:entity doc-eid
              :entity-type :audit-doc
              :facet :kontor.audit-doc/privilege
              :from from
              :to to
              :changed-at (or changed-at (java.util.Date.))
              :changed-by-uid changed-by-uid
              :reason reason}
       reason-note    (assoc :reason-note reason-note)
       supporting-doc (assoc :supporting-doc supporting-doc)))))

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
