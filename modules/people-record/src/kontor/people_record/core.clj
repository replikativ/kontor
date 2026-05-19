(ns kontor.people-record.core
  "kontor-people-record — minimal consent + retention + audit-doc +
   DSAR loop over kontor-hr's `:person`/`:employment` substrate.

   Per ADR-094 §3.5 + note 93 §8 (scope in / scope out tables).

   ## What this companion is

   A forensically-correct *track-record* layer:

   - `:position-held` — career history
   - `:performance-review` — formal documented review events
   - `:promotion` — advancement events with comp-change refs

   Each write is consent-aware:
   - Writes to `:hr-track-record`-scoped data check
     `kontor.hr.consent/active-at?` for the affected `:person` +
     `:hr-track-record` scope at the write timestamp.
   - If no active consent (and no other recorded lawful basis), the
     write is REFUSED with a structured error.

   ## What this companion is NOT

   - No activity-monitoring (note 93 §3.3-3.4 — separate companion).
   - No emotion / engagement / productivity scoring (project refusal
     posture, ADR-094 §6).
   - No automated promotion / termination recommendations (Art. 22
     refusal posture).
   - No screen / keystroke / webcam capture.

   ## Composition

   - kontor-hr: provides `:person`, `:employment`, `:compensation`.
   - kontor-hr.consent: provides `:consent/*` machinery (ADR-094).
   - kontor.audit-doc: provides the `:audit-doc` backbone +
     canonical categories (ADR-094 §3.1).
   - kontor.dsar: bundles a person's records via the kernel walker."
  (:require [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.bitemporal :as kbt]
            [kontor.dsar :as dsar]
            [kontor.hr.consent :as consent]
            [kontor.people-record.schema :as schema]
            [kontor.validation :as validation])
  (:import [java.util Date]))

(declare dsar-bundle)

(defn install!
  "Idempotent install — kernel + kontor-hr attrs must be present (run
   `kontor.core/install-schema!` + `kontor.hr.core/install!` first).

   Registers a `kontor.dsar` extension collector under `:people-
   record` so the kernel-canonical `kontor.dsar/collect` walk reaches
   the track-record bundle. Same pattern as `kontor.hr.core/install!`
   (note 86 P1-86-5): given a partner eid, we resolve the linked
   `:person` (if any) via `:partner/person` and call `dsar-bundle`.
   The kernel walker merges the result under `:extensions :people-
   record`.

   Without this registration, consumer DSAR pipelines using the
   kernel-canonical walker silently miss track-record data — an
   ADR-094 compliance gap. See note 95 §2 (kontor-people-record) for
   the audit + this followup."
  [conn]
  (schema/install! conn)
  (dsar/register-extension-collector!
   :people-record
   (fn [db partner-eid _opts]
     (when-let [person-eid (d/q '[:find ?p .
                                  :in $ ?pa
                                  :where [?pa :partner/person ?p]]
                                db partner-eid)]
       (dsar-bundle db person-eid)))))

;; ============================================================================
;; Consent gate — every write through this ns checks active-at?
;; ============================================================================

(def ^:const hr-track-record-scope :hr-track-record)

(defn check-consent!
  "Throw `:consent/missing` if no active `:hr-track-record` consent
   for `person` at `at`. The substrate stays neutral; this is the
   consumer-side enforcement of ADR-094's substrate posture.

   Public so a consumer composing a custom track-record write into
   their own `kontor.process` step list can apply the same consent
   gate the built-in `record-position!` / `record-review!` /
   `record-promotion!` wrappers apply. Note this is a pure guard
   (throws on missing consent, returns nil otherwise) — NOT a
   tx-data builder; ADR-068's `*-tx-data` shape doesn't apply
   because there's no transactable side-effect."
  [db person ^Date at]
  (when-not (consent/active-at? db person hr-track-record-scope at)
    (throw (ex-info "No active :hr-track-record consent for person"
                    {:type    :consent/missing
                     :person  person
                     :scope   hr-track-record-scope
                     :at      at}))))

;; ============================================================================
;; :position-held — career history
;; ============================================================================

(defn record-position-tx-data
  "Pure tx-data builder for `record-position!`. Required: `:code
   :person :employment :title :start-date`. Optional: `:level`,
   `:end-date`, `:manager-employment`."
  [_db {:keys [code person employment title level start-date end-date
               manager-employment tempid]
        :or {tempid "position-1"}}]
  (when-not code        (throw (ex-info ":code required" {})))
  (when-not person      (throw (ex-info ":person required" {})))
  (when-not employment  (throw (ex-info ":employment required" {})))
  (when-not title       (throw (ex-info ":title required" {})))
  (when-not start-date  (throw (ex-info ":start-date required" {})))
  [(cond-> {:db/id                   tempid
            :position-held/external-id code
            :position-held/person      person
            :position-held/employment  employment
            :position-held/title       title
            :position-held/start-date  start-date}
     level              (assoc :position-held/level level)
     end-date           (assoc :position-held/end-date end-date)
     manager-employment (assoc :position-held/manager-employment manager-employment))])

(defn record-position!
  "Record a held position. Consent-gated on `:hr-track-record` at the
   write timestamp. Routes through the kernel validation gate.

   Bitemporal: `:tx/valid-from` is set to `:start-date` (the position
   was held FROM that date in the business world). Lets
   `(d/valid-at db t)` answer 'what was Jane's title on date T?'."
  [conn {:keys [person at start-date] :as spec}]
  (let [now (or at (Date.))]
    (check-consent! (d/db conn) person now)
    (validation/transact-with-validation
     conn (kbt/with-vt
            (record-position-tx-data (d/db conn) (assoc spec :at now))
            (or start-date now)))))

;; ============================================================================
;; :performance-review — formal documented review events
;; ============================================================================

(defn record-review-tx-data
  "Pure tx-data builder for `record-review!`. Required: `:code
   :person :reviewer-employment :period-start :period-end :outcome`.
   Optional: `:supporting-doc`, `:calibrated-at`, `:tempid`."
  [_db {:keys [code person reviewer-employment period-start period-end
               outcome supporting-doc calibrated-at tempid]
        :or {tempid "review-1"}}]
  (when-not code                (throw (ex-info ":code required" {})))
  (when-not person              (throw (ex-info ":person required" {})))
  (when-not reviewer-employment (throw (ex-info ":reviewer-employment required" {})))
  (when-not period-start        (throw (ex-info ":period-start required" {})))
  (when-not period-end          (throw (ex-info ":period-end required" {})))
  (when-not outcome             (throw (ex-info ":outcome required" {})))
  [(cond-> {:db/id                                 tempid
            :performance-review/external-id        code
            :performance-review/person             person
            :performance-review/reviewer-employment reviewer-employment
            :performance-review/period-start       period-start
            :performance-review/period-end         period-end
            :performance-review/outcome            outcome}
     supporting-doc (assoc :performance-review/supporting-doc supporting-doc)
     calibrated-at  (assoc :performance-review/calibrated-at calibrated-at))])

(defn record-review!
  "Record a performance-review event. Consent-gated on
   `:hr-track-record` at the write timestamp. Routes through the
   kernel validation gate.

   Bitemporal: `:tx/valid-from` is set to `:calibrated-at` (the date
   the review was finalized + became authoritative). Falls back to
   `at` (or now) when calibrated-at is absent."
  [conn {:keys [person at calibrated-at] :as spec}]
  (let [now (or at (Date.))]
    (check-consent! (d/db conn) person now)
    (validation/transact-with-validation
     conn (kbt/with-vt
            (record-review-tx-data (d/db conn) (assoc spec :at now))
            (or calibrated-at now)))))

;; ============================================================================
;; :promotion — advancement events
;; ============================================================================

(defn record-promotion-tx-data
  "Pure tx-data builder for `record-promotion!`. Required: `:code
   :person :from-position :to-position :effective-date`. Optional:
   `:comp-change`, `:supporting-doc`, `:tempid`."
  [_db {:keys [code person from-position to-position effective-date
               comp-change supporting-doc tempid]
        :or {tempid "promotion-1"}}]
  (when-not code           (throw (ex-info ":code required" {})))
  (when-not person         (throw (ex-info ":person required" {})))
  (when-not from-position  (throw (ex-info ":from-position required" {})))
  (when-not to-position    (throw (ex-info ":to-position required" {})))
  (when-not effective-date (throw (ex-info ":effective-date required" {})))
  [(cond-> {:db/id                   tempid
            :promotion/external-id    code
            :promotion/person         person
            :promotion/from-position  from-position
            :promotion/to-position    to-position
            :promotion/effective-date effective-date}
     comp-change    (assoc :promotion/comp-change comp-change)
     supporting-doc (assoc :promotion/supporting-doc supporting-doc))])

(defn record-promotion!
  "Record a promotion event. Consent-gated. Routes through the gate.

   Bitemporal: `:tx/valid-from` is set to `:effective-date` (the day
   the promotion takes effect in the business world)."
  [conn {:keys [person at effective-date] :as spec}]
  (let [now (or at (Date.))]
    (check-consent! (d/db conn) person now)
    (validation/transact-with-validation
     conn (kbt/with-vt
            (record-promotion-tx-data (d/db conn) (assoc spec :at now))
            (or effective-date now)))))

;; ============================================================================
;; DSAR bundler — collect everything for a person
;; ============================================================================

(defn dsar-bundle
  "Collect every track-record entity touching `person`. Returns a map
   `{:positions [...] :reviews [...] :promotions [...]}`. Each value
   is a vector of pull-results.

   Consumer DSAR pipelines compose this with kontor.dsar/collect for
   the kernel + HR-side bundle (per ADR-052 + note 86 P1-86-5)."
  [db person]
  (let [positions  (mapv #(d/pull db '[*] %)
                         (d/q '[:find [?p ...]
                                :in $ ?subj
                                :where [?p :position-held/person ?subj]]
                              db person))
        reviews    (mapv #(d/pull db '[*] %)
                         (d/q '[:find [?r ...]
                                :in $ ?subj
                                :where [?r :performance-review/person ?subj]]
                              db person))
        promotions (mapv #(d/pull db '[*] %)
                         (d/q '[:find [?p ...]
                                :in $ ?subj
                                :where [?p :promotion/person ?subj]]
                              db person))]
    {:positions  positions
     :reviews    reviews
     :promotions promotions}))
