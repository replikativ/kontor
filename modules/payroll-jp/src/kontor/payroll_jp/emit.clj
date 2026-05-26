(ns kontor.payroll-jp.emit
  "JP payroll emit-provider. Two responsibilities:

   1. **`JpPayrollEmitProvider`** — `PayrollEmitProvider` impl.
      Returns an `:audit-doc/category :payroll-filing` row per
      payroll run carrying the language flag (default :ja). kontor
      does NOT emit any clearance payload — Japan has no real-time
      payroll clearance regime (the Gensen filing is annual + paper-
      friendly; the engine handles 給与支払報告書 to municipalities
      directly).

   2. **`build-gensen-audit-doc-tx-data`** — ADR-068 builder that
      produces an `:audit-doc` row recording the annual 源泉徴収票
      (Gensen Choshu Hyo) generation. Carries
      `:audit-doc/category :payroll-filing` +
      `:audit-doc/language :ja`. The structured statement payload
      is stored on `:audit-doc/description` as EDN text — kontor's
      audit chain records WHAT was generated; the rendered PDF
      delivery is consumer / engine business.

   3. **`record-my-number-attestation-tx-data`** — companion helper
      for storing the audit-doc that records a My Number (個人番号 /
      Kojin Bangō) attestation. The PII value itself is NEVER
      stored in kontor (per ADR-084 §1 + CLAUDE.md PII discipline +
      ADR-051 privilege facet); the audit-doc carries the
      attestation METADATA (date attested, who attested, document
      type that verified it) with:

        :audit-doc/category :hr-personnel
        :audit-doc/privilege :pii-sensitive
        :audit-doc/language :ja

      The consumer's privileged store holds the My Number value
      itself; kontor records that the attestation happened, the
      retention rule for it, and that access is privilege-gated.

   Reference: ADR-084 §1 (My Number discipline), §7 (Gensen emit)."
  (:require [clojure.string :as str]
            [kontor.payroll-jp.gensen :as gensen]
            [kontor.payroll-provider :as pp]))

;; ============================================================================
;; JpPayrollEmitProvider — PayrollEmitProvider impl
;; ============================================================================

(defrecord JpPayrollEmitProvider [opts]
  pp/PayrollEmitProvider
  (emit-payroll-events [_ payroll-facts {:keys [pay-period-eid entity-eid]}]
    ;; Per ADR-084 §7: no monthly clearance payload (Japan has no
    ;; real-time payroll clearance). The emit returns a single
    ;; per-pay-period :audit-doc summary for the audit chain.
    (let [language (or (:language opts) :ja)
          per-fact-count (count payroll-facts)]
      [{:audit-doc/code (str "PAYROLL-EVENT-JP-" entity-eid "-" pay-period-eid)
        :audit-doc/type :payroll-run-summary
        :audit-doc/title (format "Payroll run (%d facts) for pay-period %d, entity %d"
                                 per-fact-count pay-period-eid entity-eid)
        :audit-doc/category :payroll-filing
        :audit-doc/language language
        :audit-doc/uploaded-at (java.util.Date.)}])))

;; ============================================================================
;; build-gensen-audit-doc-tx-data — companion to gensen/build-gensen-submission
;; ============================================================================

(defn- format-amount
  "Format a BigDecimal as ¥-prefixed integer (no thousands separator
   for the EDN — render-time formatting is the consumer's call)."
  [v]
  (cond
    (nil? v) ""
    (instance? java.math.BigDecimal v) (str "¥" v)
    :else (str v)))

(defn- statement->desc
  "Pretty-print a single Gensen statement as a one-line description
   suitable for :audit-doc/description (a string slot).

   The full structured payload should be carried on
   :audit-doc/storage-uri (pointing at the consumer's rendered PDF)
   when the consumer materializes the form."
  [{:keys [gensen/tax-year gensen/employee gensen/payment-amount
           gensen/withholding-amount gensen/social-insurance-paid]
    :as _statement}]
  (format
   "Gensen Choshu Hyo (源泉徴収票) — %s %s, 課税年: %d, 支払金額: %s, 源泉徴収税額: %s, 社会保険料等: %s"
   (or (:family-name employee) "")
   (or (:given-name employee) "")
   (or tax-year 0)
   (format-amount payment-amount)
   (format-amount withholding-amount)
   (format-amount social-insurance-paid)))

(defn build-gensen-audit-doc-tx-data
  "Build an `:audit-doc` tx-data fragment recording a 源泉徴収票
   (Gensen Choshu Hyo) generation event. The consumer transacts this
   alongside the rendered PDF storage operation (which happens
   outside kontor — engine / consumer renders to disk / archive).

   The statement payload itself is NOT stored on the audit-doc by
   default (the per-employee Gensen is PII-adjacent — name, address,
   wages, deductions); per ADR-084 §1 the consumer chooses whether
   to inline the structured map via `:storage-uri` (pointing at
   the materialized PDF) or keep the data in a privileged store.

   Required:
     :statement     a Gensen statement map (from
                    gensen/payroll-facts->gensen-statement)

   Optional:
     :report-type   :original (default) | :amended | :replacement
     :storage-uri   where the consumer stored the rendered PDF
     :code          consumer-supplied audit-doc code; defaults
                    deterministic from employer + tax-year + name
     :language      :ja (default) | :en (bilingual export)

   Returns a vector containing one :audit-doc map ready to merge
   into a larger tx-data set."
  [{:keys [statement report-type storage-uri code language]
    :or {report-type :original
         language :ja}}]
  (when-not statement (throw (ex-info ":statement required" {})))
  (let [{:gensen/keys [tax-year employee employer]} statement
        emp-corp (or (:corporate-number employer) "no-corp-number")
        last-name (or (:family-name employee) "unknown")
        doc-code (or code
                     (format "GENSEN-%s-%d-%s-%s"
                             emp-corp
                             (or tax-year 0)
                             last-name
                             (name report-type)))
        title (format "源泉徴収票 — %s %s (tax-year %d, %s)"
                      last-name
                      (or (:given-name employee) "")
                      (or tax-year 0)
                      (case language :en "EN" "JA"))
        desc (statement->desc statement)]
    [(cond->
      {:audit-doc/code doc-code
       :audit-doc/type :regulator-clearance
       :audit-doc/title title
       :audit-doc/description desc
       :audit-doc/uploaded-at (java.util.Date.)
       :audit-doc/category :payroll-filing
       :audit-doc/language language}
       storage-uri (assoc :audit-doc/storage-uri storage-uri))]))

(defn build-gensen-submission-audit-docs-tx-data
  "Convenience wrapper: build audit-docs for a vector of Gensen
   statements. Consumer transacts the result (typically alongside a
   per-statement PDF rendering)."
  [{:keys [statements report-type language]
    :or {report-type :original
         language :ja}}]
  (vec (mapcat (fn [s]
                 (build-gensen-audit-doc-tx-data
                  {:statement s
                   :report-type report-type
                   :language language}))
               statements)))

;; ============================================================================
;; record-my-number-attestation-tx-data — PII discipline (ADR-084 §1)
;; ============================================================================

(def attestation-document-types
  "Open-set mapping kontor keyword → human-readable form of accepted
   My Number attestation documents per 番号利用法 (Number Act). The
   consumer's HR process records WHICH document type was used to
   verify the My Number; kontor itself stores ONLY this metadata
   (not the document image, not the My Number value).

   Reference:
     https://www.cao.go.jp/bangouseido/ (Cabinet Office My Number portal)
     https://www.soumu.go.jp/kojinbango_card/ (Ministry of Internal Affairs)"
  {:my-number-card           "マイナンバーカード (Individual Number Card)"
   :notification-card        "通知カード (Notification Card)"
   :resident-record-extract  "住民票の写し (Resident Record Extract with My Number)"
   :other                    "その他の確認書類"})

(defn record-my-number-attestation-tx-data
  "ADR-068 builder for an audit-doc recording a My Number (個人番号 /
   Kojin Bangō / 12-digit personal ID) attestation.

   IMPORTANT (ADR-084 §1 + CLAUDE.md PII discipline):

     - kontor does NOT store the My Number value. The value lives in
       the consumer's privileged store (encrypted at rest, access-
       gated by the consumer's auth layer).
     - kontor's audit-doc carries ONLY the attestation METADATA:
       date attested, who attested, document type that verified it,
       retention end-date.
     - The audit-doc is classified
         :audit-doc/category :hr-personnel
         :audit-doc/privilege :pii-sensitive
         :audit-doc/language :ja
       so the consumer's auth layer (kontor-authz per ADR-065/066)
       can gate access reliably.
     - The audit-doc's `:audit-doc/code` is deterministic from the
       :person + :tax-year so a repeat call is idempotent and won't
       create duplicate attestation records.

   Required keys:
     :person          eid or external-id of the :person
     :tax-year        integer — the tax year this attestation
                      authorizes (My Number attestation is per-
                      tax-year per Number Act §16-2)
     :document-type   keyword — one of `attestation-document-types`
                      keys, OR a consumer-extended kw
     :attested-at     #inst — when the HR officer verified the
                      My Number against the document
     :attested-by-uid ref to the operator (:kontor.audit/create-uid)

   Optional:
     :storage-uri     URI to the consumer's privileged store record
                      (e.g. an internal vault link). The URI itself
                      is OK to expose; the value behind it is the
                      sensitive part — kontor never resolves it.
     :code            consumer-supplied audit-doc code; defaults
                      deterministic from person + tax-year
     :language        :ja (default)

   Retention: My Number must be destroyed when the legal retention
   period ends — typically 7 years after employment termination per
   所得税法施行令 §322 + Number Act §19. Consumers wire this via
   `kontor.retention/retention-policy` (ADR-050) keyed on
   `:retention-policy/category :hr-personnel` — the sweeper walks
   audit-docs with this category + privilege facet and produces
   purge candidates.

   Returns a vector containing one :audit-doc map."
  [{:keys [person tax-year document-type attested-at attested-by-uid
           storage-uri code language]
    :or {language :ja}}]
  (when-not person          (throw (ex-info ":person required" {})))
  (when-not tax-year        (throw (ex-info ":tax-year required" {})))
  (when-not document-type   (throw (ex-info ":document-type required" {})))
  (when-not attested-at     (throw (ex-info ":attested-at required" {})))
  (when-not attested-by-uid (throw (ex-info ":attested-by-uid required" {})))
  (let [doc-type-label (or (get attestation-document-types document-type)
                           (name document-type))
        person-key (cond
                     (number? person) (str "p-eid-" person)
                     (string? person) person
                     :else (str person))
        doc-code (or code
                     (format "MY-NUMBER-ATTEST-%s-%d"
                             person-key tax-year))
        title (format "My Number attestation — %s — tax-year %d (%s)"
                      person-key tax-year doc-type-label)
        desc (str "My Number (個人番号) attestation recorded for "
                  "tax-year " tax-year ". Document type: " doc-type-label
                  ". Value held in consumer's privileged store; "
                  "kontor records ONLY this attestation metadata "
                  "per ADR-084 §1 + Number Act discipline. "
                  "The :audit-doc/privilege :pii-sensitive facet "
                  "gates downstream access via the consumer's auth "
                  "layer.")]
    [(cond->
      {:audit-doc/code doc-code
       :audit-doc/type :pii-attestation
       :audit-doc/title title
       :audit-doc/description desc
       :audit-doc/uploaded-at attested-at
       :audit-doc/uploaded-by-uid attested-by-uid
       :audit-doc/category :hr-personnel
       :audit-doc/privilege :pii-sensitive
       :audit-doc/language language}
       storage-uri (assoc :audit-doc/storage-uri storage-uri))]))

;; ============================================================================
;; QC-style PII-detection helper — surface My Number presence
;; ============================================================================

(defn pii-employees-in-facts
  "Return the set of employment eids whose pay-period facts carry an
   inline My Number value in `:jurisdiction-specific-codes` — a
   discipline violation per ADR-084 §1. Consumers should NEVER pass
   the My Number through `PayrollFacts`; engines that emit it must
   be configured to strip it upstream.

   Returns the set of offending employments (possibly empty)."
  [facts]
  (->> facts
       (filter (fn [{:keys [jurisdiction-specific-codes components]}]
                 (or (contains? jurisdiction-specific-codes :my-number)
                     (contains? jurisdiction-specific-codes :個人番号)
                     (some (fn [c]
                             (some #{:my-number :個人番号} (keys c)))
                           components))))
       (mapv :employment)
       set))

(defn warn-if-my-number-leaked!
  "If any fact carries a `:my-number` slot, log a *loud* warning + a
   stable identifier of the offending facts. Per ADR-084 §1, this is
   a discipline violation: the consumer's engine configuration is
   leaking PII into kontor's PayrollFacts.

   Returns the set of offending employments (possibly empty)."
  [facts]
  (let [offenders (pii-employees-in-facts facts)]
    (when (seq offenders)
      (binding [*out* *err*]
        (println
         (format
          (str "[kontor.payroll-jp.emit] ERROR: My Number (個人番号) "
               "leaked into PayrollFacts for employments %s. "
               "ADR-084 §1 + Number Act discipline FORBIDS storing "
               "the My Number value in PayrollFacts; the consumer's "
               "engine integration must be reconfigured to strip "
               "the value at the engine boundary. Use "
               "record-my-number-attestation-tx-data to record "
               "ONLY the attestation metadata in kontor.")
          (str/join ", " (sort offenders))))))
    offenders))
