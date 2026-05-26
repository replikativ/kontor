(ns kontor.payroll-br.emit
  "BR payroll emit-provider — wraps eSocial event builders as the
   `PayrollEmitProvider` protocol. Two responsibilities:

   1. `BrESocialEmitProvider` — `PayrollEmitProvider` impl. Returns
      `:audit-doc` rows for the periodic events (S-1200 per employee,
      S-1210 per employee, S-1299 per pay-period). Per ADR-081 §6,
      kontor does NOT sign / transmit; the consumer's engine handles
      ICP-Brasil signing + SOAP submission.

   2. `terminate-employment-tx-data` — ADR-068 builder that produces
      the S-2299 audit-doc for a termination event, carrying the
      eSocial code (Tabela 19) the consumer's engine consumes.

   Reference: ADR-081 §6, gov.br/esocial S-1.3 leiaute manual."
  (:require [clojure.string :as str]
            [kontor.payroll-provider :as pp]
            [kontor.payroll-br.esocial :as esocial])
  (:import [java.text SimpleDateFormat]
           [java.util Date]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn ^:private fmt-period-code
  "Format the pay-period as 'yyyy-MM' for the audit-doc code."
  [^Date d]
  (.format (SimpleDateFormat. "yyyy-MM") d))

(defn ^:private require-opts! [opts ks]
  (doseq [k ks]
    (when (nil? (get opts k))
      (throw (ex-info (str (subs (str k) 1) " required in BrESocialEmitProvider opts")
                      {:missing k :provided (keys opts)})))))

(defn ^:private fact->emit-audit-doc
  "Per-fact emit-doc. Each PayrollFact produces an S-1200 + S-1210
   audit-doc pair. The S-1200 carries the rubricas; the S-1210
   carries the payment date + net amount."
  [{:keys [fact employer-cnpj per-apur dt-pgto cod-lotacao
           employee-cpf->matricula language timestamp]}]
  (let [cpf (-> fact :jurisdiction-specific-codes :employee-external-id)
        matricula (or (get employee-cpf->matricula cpf)
                      ;; Synthesize a default matrícula from CPF
                      ;; (consumer should override via lookup).
                      cpf)
        per-code (fmt-period-code per-apur)
        ide-dm-dev (str "DM-" per-code "-" cpf)
        s1200 (esocial/build-s-1200-event
               {:employer-cnpj employer-cnpj
                :cpf cpf
                :matricula matricula
                :per-apur per-apur
                :fact fact
                :cod-lotacao cod-lotacao
                :timestamp timestamp})
        s1210 (esocial/build-s-1210-event
               {:employer-cnpj employer-cnpj
                :cpf cpf
                :per-apur per-apur
                :dt-pgto (or dt-pgto per-apur)
                :net-amount (:net fact)
                :ide-dm-dev ide-dm-dev
                :timestamp timestamp})]
    [;; S-1200 audit-doc
     {:db/id (str "esocial-s1200-" cpf "-" per-code)
      :kontor.audit-doc/code (str "ESOCIAL-S1200-" employer-cnpj "-" cpf "-" per-code)
      :kontor.audit-doc/type :payroll-filing
      :kontor.audit-doc/title (str "eSocial S-1200 Remuneração — " cpf " — " per-code)
      :kontor.audit-doc/description "eSocial S-1200 — Remuneração de Trabalhador (per-employee monthly)"
      :kontor.audit-doc/category :payroll-filing
      :kontor.audit-doc/language (or language :pt-br)
      :kontor.audit-doc/uploaded-at (Date.)
      :kontor.audit-doc/inline-payload (esocial/emit-xml s1200)}
     ;; S-1210 audit-doc
     {:db/id (str "esocial-s1210-" cpf "-" per-code)
      :kontor.audit-doc/code (str "ESOCIAL-S1210-" employer-cnpj "-" cpf "-" per-code)
      :kontor.audit-doc/type :payroll-filing
      :kontor.audit-doc/title (str "eSocial S-1210 Pagamentos — " cpf " — " per-code)
      :kontor.audit-doc/description "eSocial S-1210 — Pagamentos de Rendimentos do Trabalho"
      :kontor.audit-doc/category :payroll-filing
      :kontor.audit-doc/language (or language :pt-br)
      :kontor.audit-doc/uploaded-at (Date.)
      :kontor.audit-doc/inline-payload (esocial/emit-xml s1210)}]))

(defn ^:private fechamento-audit-doc
  "Per-pay-period S-1299 fechamento (period-close) audit-doc."
  [{:keys [employer-cnpj per-apur language timestamp]}]
  (let [per-code (fmt-period-code per-apur)
        s1299 (esocial/build-s-1299-event
               {:employer-cnpj employer-cnpj
                :per-apur per-apur
                :timestamp timestamp})]
    {:db/id (str "esocial-s1299-" per-code)
     :kontor.audit-doc/code (str "ESOCIAL-S1299-" employer-cnpj "-" per-code)
     :kontor.audit-doc/type :payroll-filing
     :kontor.audit-doc/title (str "eSocial S-1299 Fechamento — " per-code)
     :kontor.audit-doc/description "eSocial S-1299 — Fechamento dos Eventos Periódicos"
     :kontor.audit-doc/category :payroll-filing
     :kontor.audit-doc/language (or language :pt-br)
     :kontor.audit-doc/uploaded-at (Date.)
     :kontor.audit-doc/inline-payload (esocial/emit-xml s1299)}))

;; ============================================================================
;; BrESocialEmitProvider — PayrollEmitProvider impl
;; ============================================================================

(defrecord BrESocialEmitProvider [opts]
  pp/PayrollEmitProvider
  (emit-payroll-events [_ payroll-facts _ctx]
    ;; Per ADR-081 §6: emit S-1200 + S-1210 per employee + a single
    ;; S-1299 fechamento per pay-period. The S-1000/1005/1010/1020
    ;; (table events) are emitted via separate `build-table-events`
    ;; helpers since they're not part of the periodic flow.
    (require-opts! opts [:employer-cnpj :per-apur :cod-lotacao])
    (let [{:keys [employer-cnpj per-apur dt-pgto cod-lotacao
                  employee-cpf->matricula language uri-prefix]} opts
          ;; If no timestamp is supplied we use a single deterministic
          ;; one across the per-pay-period emit so test runs that
          ;; invoke the provider twice get matching audit-doc codes
          ;; (the codes embed the per-apur period, not the timestamp,
          ;; so this is already stable; the timestamp is only used in
          ;; the event-ID synthesis).
          timestamp (or (:timestamp opts) (java.util.Date.))
          per-fact-docs
          (->> payroll-facts
               (mapv (fn [fact]
                       (fact->emit-audit-doc
                        {:fact fact
                         :employer-cnpj employer-cnpj
                         :per-apur per-apur
                         :dt-pgto dt-pgto
                         :cod-lotacao cod-lotacao
                         :employee-cpf->matricula employee-cpf->matricula
                         :language language
                         :timestamp timestamp})))
               (mapcat identity)
               vec)
          fechamento (fechamento-audit-doc
                      {:employer-cnpj employer-cnpj
                       :per-apur per-apur
                       :language language
                       :timestamp timestamp})
          all-docs (conj per-fact-docs fechamento)]
      (cond->> all-docs
        uri-prefix
        (mapv (fn [doc]
                (let [code (:kontor.audit-doc/code doc)]
                  (assoc doc :kontor.audit-doc/storage-uri
                         (str uri-prefix code ".xml"))))))
      ;; The above thread-last is a no-op when uri-prefix is nil;
      ;; return the bare list.
      all-docs)))

(defn make-provider
  "Construct a BrESocialEmitProvider.

   Required opts:
     :employer-cnpj    string — full 14-digit CNPJ
     :per-apur         java.util.Date — pay-period competência
     :cod-lotacao      string — S-1020 codLotacao for this pay-run

   Optional:
     :dt-pgto                  Date (default = per-apur)
     :employee-cpf->matricula  map CPF → matrícula
     :language                 :pt-br (default) | :en (informational)
     :uri-prefix               string — prefix for :kontor.audit-doc/storage-uri
     :timestamp                Date for event-ID synthesis (default now)"
  [opts]
  (require-opts! opts [:employer-cnpj :per-apur :cod-lotacao])
  (->BrESocialEmitProvider opts))

;; ============================================================================
;; Table-event builders — kept separate since they're not part of the
;; per-pay-period flow (they're emitted as initial setup + on change).
;; ============================================================================

(defn build-table-event-audit-docs
  "Build audit-doc rows for the four BR eSocial table events
   (S-1000 / S-1005 / S-1010 / S-1020). These run once at company
   setup + on change; they are NOT emitted by `BrESocialEmitProvider`.

   Pass `:event-specs` as a vector of maps; each is a single event
   to build:

     {:event-type :s-1000 | :s-1005 | :s-1010 | :s-1020
      :opts <event-specific opts map>}

   Returns a vector of `:audit-doc` tx-data maps ready for transact."
  [{:keys [event-specs language]
    :or {language :pt-br}}]
  (when-not (seq event-specs)
    (throw (ex-info ":event-specs must be non-empty" {})))
  (mapv
   (fn [{:keys [event-type opts]}]
     (let [{:keys [employer-cnpj]} opts
           xml (case event-type
                 :s-1000 (esocial/build-s-1000-event opts)
                 :s-1005 (esocial/build-s-1005-event opts)
                 :s-1010 (esocial/build-s-1010-event opts)
                 :s-1020 (esocial/build-s-1020-event opts)
                 (throw (ex-info "Unknown event-type" {:type event-type
                                                       :known #{:s-1000 :s-1005 :s-1010 :s-1020}})))
           type-name (-> event-type name str/upper-case)]
       {:kontor.audit-doc/code (str "ESOCIAL-" type-name "-" employer-cnpj "-"
                             (.getTime ^Date (Date.)))
        :kontor.audit-doc/type :payroll-filing
        :kontor.audit-doc/title (str "eSocial " type-name " — table event")
        :kontor.audit-doc/description (str "eSocial " type-name " table event (one-time / on-change)")
        :kontor.audit-doc/category :payroll-filing
        :kontor.audit-doc/language language
        :kontor.audit-doc/uploaded-at (Date.)
        :kontor.audit-doc/inline-payload (esocial/emit-xml xml)}))
   event-specs))

;; ============================================================================
;; terminate-employment-tx-data — S-2299 emit per ADR-081 §6.3
;; ============================================================================

(defn terminate-employment-tx-data
  "Pure ADR-068 tx-data builder for an employment termination event.
   Per ADR-081 §6.3, kontor:

   - status-machine transitions :kontor.employment/state → :terminated
   - sets :kontor.employment/end-date to last-day-worked
   - sets :kontor.employment/termination-reason (open-set keyword)
   - emits an S-2299 :audit-doc carrying the eSocial XML the consumer's
     engine needs to file via eSocial WS
   - does NOT transmit the event itself

   Required opts:
     :employment-eid       eid of the :employment to terminate
     :employer-cnpj        string — employer CNPJ
     :cpf                  string — employee CPF
     :matricula            string — employee matrícula
     :last-day-worked      java.util.Date
     :termination-reason   keyword — one of `esocial/termination-cause-codes`
                                     keys

   Optional:
     :dt-projfimapi        Date — projected aviso prévio end date
     :pensao-aliment       integer (0..1)
     :perc-aliment         BigDecimal
     :code                 audit-doc code override
     :language             :pt-br (default) | :en"
  [_db {:keys [employment-eid employer-cnpj cpf matricula
               last-day-worked termination-reason
               dt-projfimapi pensao-aliment perc-aliment
               code language]
        :or {language :pt-br}}]
  (doseq [[k v] {:employment-eid employment-eid
                 :employer-cnpj employer-cnpj :cpf cpf
                 :matricula matricula :last-day-worked last-day-worked
                 :termination-reason termination-reason}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (let [s2299 (esocial/build-s-2299-event
               {:employer-cnpj employer-cnpj
                :cpf cpf
                :matricula matricula
                :dt-deslig last-day-worked
                :mtv-deslig termination-reason
                :dt-projfimapi dt-projfimapi
                :pensao-aliment (or pensao-aliment 0)
                :perc-aliment perc-aliment})
        doc-code (or code
                     (format "ESOCIAL-S2299-%s-%s-%d"
                             employer-cnpj cpf
                             (.getTime ^Date last-day-worked)))
        doc-tempid (str "termination-event-doc-" employment-eid)
        audit-doc {:db/id doc-tempid
                   :kontor.audit-doc/code doc-code
                   :kontor.audit-doc/type :termination-event
                   :kontor.audit-doc/title (str "eSocial S-2299 — " (name termination-reason))
                   :kontor.audit-doc/description "eSocial S-2299 — Desligamento (consumer's engine signs + transmits)"
                   :kontor.audit-doc/uploaded-at (Date.)
                   :kontor.audit-doc/category :payroll-filing
                   :kontor.audit-doc/language language
                   :kontor.audit-doc/inline-payload (esocial/emit-xml s2299)}
        emp-update {:db/id employment-eid
                    :kontor.employment/state :terminated
                    :kontor.employment/end-date last-day-worked
                    :kontor.employment/termination-reason termination-reason}]
    [audit-doc emp-update]))

;; ============================================================================
;; hire-employee-tx-data — S-2200 emit per ADR-081 §6.2
;; ============================================================================

(defn hire-employee-tx-data
  "Pure ADR-068 tx-data builder for an employee-hire event.

   Required opts:
     :employer-cnpj  string
     :cpf            string
     :nis            string
     :nm-trab        string
     :dt-nascto      Date
     :dt-admissao    Date
     :matricula      string
     :cbo-cargo      string
     :nm-cargo       string
     :remuneracao    BigDecimal

   Optional:
     :sexo           'M' | 'F' (default 'M')
     :raca-cor       1..6
     :est-civil      1..5
     :grau-instr     '01'..'12'
     :cod-categ      Tabela 1 (default 101)
     :code           audit-doc code override
     :language       :pt-br (default) | :en"
  [opts]
  (let [{:keys [employer-cnpj cpf dt-admissao code language]
         :or {language :pt-br}} opts
        xml (esocial/build-s-2200-event opts)
        doc-code (or code
                     (format "ESOCIAL-S2200-%s-%s-%d"
                             employer-cnpj cpf
                             (.getTime ^Date dt-admissao)))]
    [{:kontor.audit-doc/code doc-code
      :kontor.audit-doc/type :payroll-filing
      :kontor.audit-doc/title (str "eSocial S-2200 — Admissão " cpf)
      :kontor.audit-doc/description "eSocial S-2200 — Cadastramento Inicial / Admissão (consumer's engine signs + transmits)"
      :kontor.audit-doc/uploaded-at (Date.)
      :kontor.audit-doc/category :payroll-filing
      :kontor.audit-doc/language language
      :kontor.audit-doc/inline-payload (esocial/emit-xml xml)}]))
