(ns kontor.payroll-fr.emit
  "FR payroll emit-provider. Two responsibilities:

   1. `FrDsnEmitProvider` — `PayrollEmitProvider` impl. Returns
      `:audit-doc` rows recording the DSN payload kontor produced;
      the consumer's engine submits the authoritative file to
      net-entreprises.fr (kontor does NOT transmit).

   2. `build-dsn-audit-doc-tx-data` — ADR-068 builder companion that
      records what was emitted with the right `:audit-doc/language`
      slot.

   3. `terminate-employment-tx-data` — ADR-068 builder for an
      employment termination event. The engine handles the DSN
      'fin de contrat' (S21.G00.62) emission; kontor records the
      structured payload + the status-machine transition.

   ## Architectural posture

   Per ADR-079: kontor does NOT transmit the DSN to net-entreprises.fr.
   - DSN submission is via the dsn-info.fr portal or by API;
     credentials are consumer-held (mirrors `:sent-by-consumer?` in
     ADR-017 e-invoicing).
   - kontor produces the **GL-relevant subset** of the DSN payload
     (envelope + S21.G00.51 rémunérations + S21.G00.81 cotisations)
     to feed the audit-doc; the engine produces the authoritative
     full file.

   Reference: ADR-079; doc/research/79 §5.3."
  (:require [clojure.string :as str]
            [kontor.payroll-fr.dsn :as dsn]
            [kontor.payroll-provider :as pp]))

;; ============================================================================
;; FrDsnEmitProvider — PayrollEmitProvider impl
;; ============================================================================

(defrecord FrDsnEmitProvider [opts]
  pp/PayrollEmitProvider
  (emit-payroll-events [_ payroll-facts {:keys [pay-period-eid entity-eid]}]
    (let [{:keys [envelope entreprise etablissement persons-by-emp
                  pay-period-start pay-period-end date-versement
                  type-pas language]
           :or {language :fr
                type-pas :perso}} opts
          ;; If the consumer wires the metadata-emitter, we can build
          ;; the full DSN payload — but the substrate uses this
          ;; provider both inside `run-payroll!` (where the consumer
          ;; supplied envelope/etc.) AND from a sweeper that only has
          ;; the facts. When envelope is missing we emit a minimal
          ;; audit-doc; the consumer's own emit step builds the full
          ;; payload separately via dsn/facts->payload.
          per-fact-count (count payroll-facts)
          payload-lines (when (and envelope entreprise etablissement
                                   persons-by-emp pay-period-start
                                   pay-period-end date-versement)
                          (dsn/facts->payload
                           {:facts payroll-facts
                            :envelope envelope
                            :entreprise entreprise
                            :etablissement etablissement
                            :persons-by-emp persons-by-emp
                            :pay-period-start pay-period-start
                            :pay-period-end pay-period-end
                            :date-versement date-versement
                            :type-pas type-pas}))
          ;; ADR-075 + note 86 P0-86-2 — :payroll-filing is the
          ;; canonical category for periodic regulator emissions.
          doc-code (format "DSN-%d-%d" entity-eid pay-period-eid)
          title (format
                 "DSN payload for pay-period %d, entity %d (%d individus)"
                 pay-period-eid entity-eid per-fact-count)
          ;; The audit-doc carries the payload as a serialized NEODES
          ;; document (CRLF-joined). Per ADR-079 the consumer's engine
          ;; uploads the AUTHORITATIVE file; kontor's persisted payload
          ;; is the GL-relevant subset for audit-trail purposes.
          description (if payload-lines
                        (dsn/serialize payload-lines)
                        (str "DSN payload skeleton — envelope/entreprise/"
                             "établissement metadata not supplied to "
                             "FrDsnEmitProvider opts. Consumer's engine "
                             "produces the authoritative file via "
                             "dsn-info.fr / partner API."))]
      [{:audit-doc/code doc-code
        :audit-doc/type :regulator-clearance
        :audit-doc/title title
        :audit-doc/description description
        :audit-doc/uploaded-at (java.util.Date.)
        :audit-doc/category :payroll-filing
        :audit-doc/language language}])))

;; ============================================================================
;; build-dsn-audit-doc-tx-data — companion of dsn/facts->payload
;; ============================================================================

(defn build-dsn-audit-doc-tx-data
  "Build an :audit-doc tx-data fragment recording a DSN submission
   was generated. The consumer transacts this alongside the actual
   DSN upload (which happens outside kontor — consumer's engine or
   ops uploads the NEODES file to net-entreprises.fr).

   Required opts:
     :siret         14-digit SIRET (employer's establishment)
     :pay-period    string YYYY-MM (e.g. \"2026-05\")
     :individus-count  integer (slip count equivalent)
     :language      :fr (default) | :en
     :payload-lines vector of NEODES rubrique strings (optional;
                    when supplied, serialized into description)

   Optional:
     :nature        :reel (default) | :test
     :type-envoi    :normal (default) | :neant
     :submitted-uri where the consumer stored the file
     :code          consumer-supplied audit-doc code"
  [{:keys [siret pay-period individus-count language nature
           type-envoi payload-lines submitted-uri code]
    :or {language :fr
         nature :reel
         type-envoi :normal}}]
  (when-not siret    (throw (ex-info ":siret required" {})))
  (when-not pay-period (throw (ex-info ":pay-period required" {})))
  (let [doc-code (or code (format "DSN-%s-%s" siret pay-period))
        title (format
               "DSN — SIRET %s — période %s — %s — %s — %d individus"
               siret pay-period
               (case nature :reel "réel" :test "test" "réel")
               (case type-envoi :normal "normal" :neant "néant" "normal")
               (or individus-count 0))
        description (cond
                      payload-lines (dsn/serialize payload-lines)
                      submitted-uri (str "Submitted to net-entreprises.fr; "
                                         "payload stored at " submitted-uri)
                      :else (str "DSN payload generated for " title))]
    [(cond->
      {:audit-doc/code doc-code
       :audit-doc/type :regulator-clearance
       :audit-doc/title title
       :audit-doc/description description
       :audit-doc/uploaded-at (java.util.Date.)
       :audit-doc/category :payroll-filing
       :audit-doc/language language}
       submitted-uri (assoc :audit-doc/storage-uri submitted-uri))]))

;; ============================================================================
;; terminate-employment-tx-data — DSN 'fin de contrat' helper
;; ============================================================================

(def motif-rupture-codes
  "Mapping kontor termination-reason keyword → DSN S21.G00.62 motif de
   rupture code (the URSSAF-issued taxonomy)."
  {:demission         "010"
   :licenciement-economique "020"
   :licenciement-faute-grave "025"
   :licenciement-cause-reelle "031"
   :rupture-conventionnelle "043"
   :fin-cdd           "031"
   :retraite          "081"
   :depart-volontaire-retraite "082"
   :mise-a-la-retraite "083"
   :deces             "094"
   :rupture-essai     "012"
   :transfert         "070"
   :inaptitude        "027"
   :autre             "999"})

(defn terminate-employment-tx-data
  "Pure ADR-068 tx-data builder for an employment termination event.
   kontor:

   - status-machine transitions :employment/state → :terminated
   - sets :employment/end-date to last-day-worked
   - sets :employment/termination-reason (open-set keyword)
   - emits a :termination-event :audit-doc carrying the data the DSN
     engine needs (motif de rupture, indemnités de rupture, attestation
     Pôle emploi data)
   - does NOT generate the DSN événementielle (S21.G00.62) XML; the
     consumer's engine submits the DSN événementielle within 5
     working days per Article R243-13 of the Code de la sécurité
     sociale.

   Required opts:
     :employment-eid       eid of the :employment to terminate
     :last-day-worked      java.util.Date — actual last day worked
     :termination-reason   keyword — one of `motif-rupture-codes` keys
                           (open-set extension allowed)

   Optional:
     :final-pay-period-end-date java.util.Date — for the soldé de tout
                           compte
     :indemnites           {:rupture-conventionnelle Money
                            :licenciement Money :preavis Money
                            :conges-payes-non-pris Money}
     :code                 consumer-supplied audit-doc code
     :language             :fr (default) | :en"
  [_db {:keys [employment-eid last-day-worked termination-reason
               final-pay-period-end-date indemnites
               code language]
        :or {language :fr}}]
  (when-not employment-eid     (throw (ex-info ":employment-eid required" {})))
  (when-not last-day-worked    (throw (ex-info ":last-day-worked required" {})))
  (when-not termination-reason (throw (ex-info ":termination-reason required" {})))
  (let [motif (get motif-rupture-codes termination-reason "999")
        doc-code (or code
                     (format "TERMINATION-%s-%d"
                             (str employment-eid)
                             (.getTime ^java.util.Date last-day-worked)))
        desc (format
              (str "Rupture du contrat de travail %s au %s; motif %s "
                   "(DSN code S21.G00.62 : %s). Indemnités : %s. "
                   "DSN événementielle NON émise ici — l'engin "
                   "comptable du consommateur dépose la DSN sous 5 "
                   "jours ouvrables (Code SS art. R243-13).")
              (str employment-eid)
              (str last-day-worked)
              (name termination-reason)
              motif
              (or (some-> indemnites keys vec str) "[]"))
        doc-tempid (str "termination-event-doc-" employment-eid)
        audit-doc {:db/id doc-tempid
                   :audit-doc/code doc-code
                   :audit-doc/type :termination-event
                   :audit-doc/title (str "Rupture — " (name termination-reason))
                   :audit-doc/description desc
                   :audit-doc/uploaded-at (java.util.Date.)
                   :audit-doc/category :hr-personnel
                   :audit-doc/language language}
        emp-update (cond->
                    {:db/id employment-eid
                     :employment/state :terminated
                     :employment/end-date last-day-worked
                     :employment/termination-reason termination-reason}
                     final-pay-period-end-date
                     (assoc :employment/final-pay-period-end-date
                            final-pay-period-end-date))]
    [audit-doc emp-update]))

;; ============================================================================
;; Misc helpers
;; ============================================================================

(defn dsn-month-from-period
  "Convert a pay-period start date → DSN month code (YYYY-MM). Each
   monthly DSN covers the previous month's pay (declared by the 5th
   or 15th of the current month per the company's effectif)."
  [^java.util.Date period-start]
  (let [ld (-> period-start .toInstant
               (.atZone java.time.ZoneOffset/UTC) .toLocalDate)]
    (format "%04d-%02d" (.getYear ld) (.getMonthValue ld))))

(defn validate-period-code
  "Sanity-check a pay-period code in the standard form 'YYYY-MM'."
  [code]
  (boolean (and (string? code)
                (re-matches #"\d{4}-\d{2}" code))))

;; Re-export string utilities so consumers don't have to require dsn
(def serialize-payload dsn/serialize)
(def payload-lines? (fn [x] (and (sequential? x) (every? string? x))))
;; Use str/blank? when destructuring optional strings
(comment str/blank?)
