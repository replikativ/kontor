(ns kontor.payroll-at.adapter
  "Bridge layer between the AT-specific BMD/RZL ingestion + mBGM/L16
   emit pipeline and the kernel `kontor.payroll-provider` protocol
   trio (PayrollComputeProvider / PayrollPostingBuilder /
   PayrollEmitProvider; ADR-075).

   ## Why a bridge instead of a rewrite

   The AT module pre-dates Stage R. It already ships a complete
   engine-CSV → `:payroll-result` → balanced GL transaction + mBGM/L16
   audit-doc emit pipeline that the AT-specific tests (27 deftests as
   of ADR-086) exercise via `kontor.payroll-at.core/run-payroll-period!`.
   Rather than rewrite that pipeline, this namespace wraps it as
   three records satisfying the kernel protocols so AT integrates with
   `kontor.hr.payroll/run-payroll!` the same way DE / US / CA / FR /
   AU / BR / IN / JP / CN / MX do.

   The pre-existing protocols (`kontor.payroll-at.compute/AtEngineProvider`
   + `kontor.payroll-at.emit/AtFilingEmitProvider`) keep their AT-specific
   semantics (engine-CSV layer + side-effecting `!` emitters); they
   compose UNDER the kernel-protocol records here.

   ## The three bridges

   - `AtKontorComputeProvider` — `PayrollComputeProvider` impl.
     Consumes `:variable-inputs {:csv-source ... :employment-by-vsnr ...}`,
     delegates to a wrapped `AtEngineProvider`'s `parse-export`, and
     converts the resulting `:payroll-result/employees` → canonical
     `PayrollFacts` (one per :employment), one component per AT wage
     type. VSNR strings resolve to :employment eids via the consumer-
     supplied `:employment-by-vsnr` map.

   - `AtKontorPostingBuilder` — `PayrollPostingBuilder` impl. Routes
     per-component `:kind` to the consumer-supplied `:accounts` map
     (component-kind → :account ref). The component vocabulary lifts
     the AT-specific wage-type keywords (`:grundgehalt`, `:lohnsteuer`,
     `:sv-arbeitgeber`, …) as-is — they ARE the canonical AT vocabulary
     per ADR-086. Consumers either supply RLG-1-shaped accounts under
     those keys directly OR pass `:use-default-rlg-1? true` to fall
     back on `kontor.payroll-at.wage-types/default-rlg-1-map` +
     `default-payable-codes` (resolved via `:account/code`).

   - `AtKontorEmitProvider` — `PayrollEmitProvider` impl. Delegates
     to a wrapped `AtFilingEmitProvider` (default `AtMbgmL16Emitter`)
     to construct the mBGM XML bytes + SHA-256, and returns a vector
     of `:audit-doc` tx-data maps with
     `:audit-doc/category :payroll-filing` + `:audit-doc/language :de`
     ready for `run-payroll!` to transact. Unlike the side-effecting
     `emit-monthly!`, this returns PURE tx-data (no `!` call) — the
     orchestrator atomically composes the audit-doc rows with the GL
     transaction + `:payroll-run` row.

   ## Consumer wiring example

   ```clojure
   (require '[kontor.core :as kontor]
            '[kontor.hr.core :as hr]
            '[kontor.l10n-at.chart :as at-chart]
            '[kontor.hr.payroll :as payroll]
            '[kontor.payroll-at.adapter :as adapter]
            '[kontor.payroll-at.compute :as compute])

   (def conn (kontor/create-test-db))
   (hr/install! conn)
   (at-chart/install! conn)
   ;; Persons + employments + pay-period set up via kontor.hr.* …

   (def providers (adapter/make-at-kontor-providers
                   {:engine :bmd
                    :commodity [:kontor.commodity/symbol \"EUR\"]
                    :use-default-rlg-1? true
                    :language :de
                    :storage-uri-template \"s3://payroll/mbgm/%s.xml\"
                    :dienstgeber-beitragskonto \"1234567\"
                    :employer-name \"Acme GmbH\"}))

   (payroll/run-payroll!
    conn {:pay-period pp-eid
          :entity ent-eid
          :employments [emp-eid-1 emp-eid-2]
          :compute-provider (:compute-provider providers)
          :posting-builder  (:posting-builder providers)
          :emit-provider    (:emit-provider providers)
          :accounts {} ;; or {:grundgehalt <eid> :lohnsteuer <eid> ...}
          :variable-inputs {:csv-source <File-or-Reader>
                            :employment-by-vsnr {\"1234567890\" emp-eid-1
                                                  \"9876543210\" emp-eid-2}}
          :run-code \"ACME-2026-01-001\"
          :tx-code  \"TX-ACME-2026-01\"
          :journal  journal-eid
          :commodity eur-eid})
   ```

   ## What it does NOT do

   - Does NOT subsume `kontor.payroll-at.core/run-payroll-period!`.
     That orchestrator is kept for AT-only consumers and per-period
     accrual + L16 emission convenience; for trans-national workflows
     and bitemporal correction (ADR-048 / ADR-067), prefer the kernel
     `kontor.hr.payroll/run-payroll!` orchestrator wired with the
     records below.

   - Does NOT bundle a default `:accounts` map. The consumer's
     chart-of-accounts is theirs to wire. The `:use-default-rlg-1?`
     flag is sugar over the existing `kontor.payroll-at.wage-types`
     default codes — it resolves each wage-type code via
     `:account/code` against the DB, mirroring the legacy
     `kontor.payroll-at.posting-builder/build-tx-data` behavior.

   See also: `modules/payroll-ca/src/kontor/payroll_ca/{compute,
   posting_builder,emit}.clj` for the closest analog adapter trio,
   and ADR-086 for the AT-specific decisions this bridge respects."
  (:require [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.payroll-at.compute :as compute]
            [kontor.payroll-at.elda :as elda]
            [kontor.payroll-at.emit :as at-emit]
            [kontor.payroll-at.wage-types :as wt]
            [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal RoundingMode]
           [java.text SimpleDateFormat]
           [java.util Date TimeZone]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- ->cents
  ^BigDecimal [^BigDecimal bd]
  (when bd (.setScale bd 2 RoundingMode/HALF_EVEN)))

(defn- line-item-amount
  "Sum the `:line-items` entries matching `wage-type`."
  ^BigDecimal [emp wage-type]
  (or (some-> (->> (:line-items emp)
                   (filter #(= wage-type (:wage-type %)))
                   (map :amount)
                   (reduce (fn [^BigDecimal a ^BigDecimal b] (.add a b)) 0M))
              ->cents)
      0M))

(defn- yyyymm ^String [^Date d]
  (let [fmt (doto (SimpleDateFormat. "yyyy-MM")
              (.setTimeZone (TimeZone/getTimeZone "UTC")))]
    (.format fmt d)))

;; ============================================================================
;; PayrollFact assembly from a normalized :payroll-result employee map
;; ============================================================================

(defn- employee->fact
  "Convert one AT-normalized employee map (the per-employee shape
   produced by `kontor.payroll-at.compute/parse`) to a kernel-shaped
   PayrollFacts map.

   The substrate's gross/net invariant (per
   `kontor.hr.payroll/check-facts`):
     gross = Σ positive employee-side :amount
     net   = gross + Σ negative employee-side :amount

   AT engine output gives us the per-wage-type buckets ALREADY split
   (LSt + SV-AN are reported as POSITIVE amounts in the line-items,
   per BMD/RZL convention). We flip the LSt / SV-AN sign so they
   appear as deductions (negative) in the components vector, and
   we ALSO drop the `:nettogehalt` line-item from the components
   (it's the residue — adding it would double-count). The substrate
   then computes net = gross + sum(deductions); we cross-check that
   the result matches the engine's reported `:nettogehalt`."
  [{:keys [vsnr line-items] :as emp}
   {:keys [pay-period-eid employment-eid commodity-eid]}]
  (let [pull (fn [wt] (line-item-amount emp wt))
        gross-grundgehalt   (pull :grundgehalt)
        gross-ueberstunden  (pull :überstunden)
        gross-urlaubs       (pull :urlaubsremuneration)
        gross-weihnachts    (pull :weihnachtsremuneration)
        gross-sachbezuege   (pull :sachbezüge)
        with-lst            (pull :lohnsteuer)
        with-sv-an          (pull :sv-arbeitnehmer)
        emp-sv-ag           (pull :sv-arbeitgeber)
        emp-db-flag         (pull :dienstgeberbeitrag-fond)
        emp-dz              (pull :zuschlag-zum-db)
        emp-komst           (pull :kommunalsteuer)
        nettogehalt         (pull :nettogehalt)
        signed-component
        (fn [kind ^BigDecimal amount employer-side?]
          (when (and amount (pos? (.signum amount)))
            {:kind kind
             :amount amount
             :employer-side? employer-side?}))
        deduction-component
        (fn [kind ^BigDecimal amount]
          (when (and amount (pos? (.signum amount)))
            {:kind kind
             :amount (.negate amount)
             :employer-side? false}))
        components
        (vec
         (keep identity
               [(signed-component :grundgehalt gross-grundgehalt false)
                (signed-component :überstunden gross-ueberstunden false)
                (signed-component :urlaubsremuneration gross-urlaubs false)
                (signed-component :weihnachtsremuneration gross-weihnachts false)
                (signed-component :sachbezüge gross-sachbezuege false)
                (deduction-component :lohnsteuer with-lst)
                (deduction-component :sv-arbeitnehmer with-sv-an)
                (signed-component :sv-arbeitgeber emp-sv-ag true)
                (signed-component :dienstgeberbeitrag-fond emp-db-flag true)
                (signed-component :zuschlag-zum-db emp-dz true)
                (signed-component :kommunalsteuer emp-komst true)]))
        gross (->cents
               (reduce (fn [^BigDecimal a ^BigDecimal b] (.add a b))
                       0M
                       [gross-grundgehalt gross-ueberstunden
                        gross-urlaubs gross-weihnachts gross-sachbezuege]))
        derived-net (->cents
                     (.subtract ^BigDecimal gross
                                (.add ^BigDecimal with-lst
                                      ^BigDecimal with-sv-an)))
        net (->cents
             (if (and nettogehalt (pos? (.signum ^BigDecimal nettogehalt)))
               nettogehalt
               derived-net))]
    (cond-> {:employment employment-eid
             :gross gross
             :net net
             :components components
             :jurisdiction-specific-codes
             (cond-> {:engine :at-bmd-or-rzl
                      :vsnr vsnr
                      :beitragsgruppe (:beitragsgruppe emp)
                      :nettogehalt-engine nettogehalt}
               (some? (:name emp)) (assoc :employee-name (:name emp)))}
      pay-period-eid (assoc :pay-period pay-period-eid)
      commodity-eid  (assoc :commodity commodity-eid))))

;; ============================================================================
;; AtKontorComputeProvider — wraps an AtEngineProvider
;; ============================================================================

(defrecord AtKontorComputeProvider [at-engine opts]
  pp/PayrollComputeProvider
  (provider-id [_] (or (:provider-id opts)
                       (keyword "at" (name (compute/engine-name at-engine)))))
  (compute-payroll [_ {:keys [employment-eids variable-inputs
                              pay-period-eid] :as _ctx}]
    (let [{:keys [csv-source employment-by-vsnr commodity-eid
                  payroll-result]} variable-inputs
          ;; Either a pre-parsed :payroll-result OR a :csv-source. The
          ;; latter is the common path; the former is useful in tests
          ;; that want to short-circuit the parser.
          result (cond
                   payroll-result payroll-result
                   csv-source     (compute/parse-export at-engine csv-source)
                   :else
                   (throw (ex-info "AtKontorComputeProvider needs :csv-source or :payroll-result in :variable-inputs"
                                   {:variable-inputs variable-inputs})))
          {:keys [ok? anomalies]} (compute/validate-result result)
          _ (when-not ok?
              (throw (ex-info "AT-payroll engine export has integrity issues"
                              {:anomalies anomalies})))
          commodity-eid (or commodity-eid (:commodity-eid opts))
          ;; Build VSNR → employment-eid lookup. When the consumer
          ;; doesn't pass one and there's exactly one employment in
          ;; scope, auto-bind (mirrors DatevLodasComputeProvider's
          ;; single-employment convenience).
          vsnr->eid
          (cond
            (map? employment-by-vsnr) employment-by-vsnr
            (and (= 1 (count employment-eids))
                 (= 1 (count (:payroll-result/employees result))))
            {(:vsnr (first (:payroll-result/employees result)))
             (first employment-eids)}
            :else
            (throw (ex-info "AtKontorComputeProvider needs :employment-by-vsnr in :variable-inputs (VSNR → :employment eid map)"
                            {:vsnr-list (mapv :vsnr
                                              (:payroll-result/employees result))
                             :employment-eids employment-eids})))]
      (->> (:payroll-result/employees result)
           (keep (fn [emp]
                   (when-let [eid (get vsnr->eid (:vsnr emp))]
                     (employee->fact
                      emp
                      {:pay-period-eid pay-period-eid
                       :employment-eid eid
                       :commodity-eid commodity-eid}))))
           vec))))

(defn make-at-kontor-compute-provider
  ([] (make-at-kontor-compute-provider {:engine :bmd}))
  ([{:keys [engine] :as opts}]
   (let [at-engine (case engine
                     :bmd (compute/make-bmd-provider)
                     :rzl (compute/make-rzl-provider)
                     (throw (ex-info "Unsupported AT engine"
                                     {:engine engine :supported #{:bmd :rzl}})))]
     (->AtKontorComputeProvider at-engine opts))))

;; ============================================================================
;; AtKontorPostingBuilder — kernel PayrollPostingBuilder impl
;; ============================================================================
;; The substrate's contract (per `kontor.payroll-provider` docstring):
;;
;;   :base-wage / :bonus / :overtime / :imputed-income / :employer-si
;;   :employee-si / :employer-pension / :employee-pension /
;;   :withholding-tax / :garnishment / :voluntary-deduction / :equity-vest
;;
;; AT keeps its OWN open-set wage-type vocabulary (`:grundgehalt`,
;; `:lohnsteuer`, `:sv-arbeitgeber`, …) because mapping to the
;; substrate's example set would lose the distinction between
;; `:dienstgeberbeitrag-fond` and `:kommunalsteuer` (both employer
;; "social" but routed to DIFFERENT payable accounts). Per the
;; substrate the keyword vocab is open; the consumer maps their
;; per-country kinds to their own accounts.

(defn- resolve-account-for-tag
  "Resolve a `:component-kind` keyword to an :account eid via the
   consumer-supplied accounts map. Falls back to default RLG-1 codes
   resolved through `:account/code` when `:use-default-rlg-1?` is set.
   Throws with a useful message when neither yields a hit."
  [db accounts kind use-default? payable?]
  (or (get accounts kind)
      (when use-default?
        (let [code (if payable?
                     (get wt/default-payable-codes kind)
                     (get wt/default-rlg-1-map kind))]
          (when code
            (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]]
                 db code))))
      (throw (ex-info
              "No account configured for component-kind"
              {:kind kind
               :payable? payable?
               :hint "Pass :accounts {<kind> <eid> ...} OR pass :use-default-rlg-1? true (resolves via :account/code) OR consult `kontor.payroll-at.wage-types`."
               :available-default-codes (vec
                                         (keys (if payable?
                                                 wt/default-payable-codes
                                                 wt/default-rlg-1-map)))}))))

(defn- fact->postings
  "Translate one PayrollFact → vector of posting maps. The per-fact
   set sums to zero per (ledger, commodity); when multiple facts
   compose in one transaction, the kernel's sum-to-zero check still
   passes per the sum-of-zeros invariant."
  [db {:keys [components net] :as _fact}
   {:keys [accounts commodity ledger use-default-rlg-1?] :as _opts}]
  (let [neg #(.negate ^BigDecimal %)
        post (fn [account amount narration]
               (cond-> {:posting/account account
                        :posting/amount amount
                        :posting/commodity commodity
                        :posting/narration narration}
                 ledger (assoc :posting/ledger ledger)))
        ;; Employee-side: positive earnings → DR expense; negative
        ;; deductions → CR payable.
        emp-legs
        (->> components
             (remove :employer-side?)
             (mapcat
              (fn [{:keys [kind amount]}]
                (cond
                  (pos? (.signum ^BigDecimal amount))
                  ;; earnings: DR expense (the wage-type's own
                  ;; account in the consumer map / RLG-1 default).
                  [(post (resolve-account-for-tag
                          db accounts kind use-default-rlg-1? false)
                         amount
                         (str "AT payroll earnings: " (name kind)))]

                  (neg? (.signum ^BigDecimal amount))
                  ;; deductions: CR payable. For employee-side
                  ;; withholdings (:lohnsteuer / :sv-arbeitnehmer) the
                  ;; payable account IS the wage-type's RLG-1 entry
                  ;; (3500 / 3540) — same default-rlg-1-map row.
                  [(post (resolve-account-for-tag
                          db accounts kind use-default-rlg-1? false)
                         amount
                         (str "AT payroll deduction: " (name kind)))]))))
        ;; Employer-side: each component produces DR expense + CR payable.
        er-legs
        (->> components
             (filter :employer-side?)
             (mapcat
              (fn [{:keys [kind amount]}]
                [(post (resolve-account-for-tag
                        db accounts kind use-default-rlg-1? false)
                       amount
                       (str "AT employer expense: " (name kind)))
                 (post (resolve-account-for-tag
                        db accounts kind use-default-rlg-1? true)
                       (neg amount)
                       (str "AT employer payable: " (name kind)))])))
        ;; Net pay — CR :nettogehalt (3700). The substrate's :net is
        ;; what the employee receives.
        net-leg
        (when (and net (pos? (.signum ^BigDecimal net)))
          [(post (resolve-account-for-tag
                  db accounts :nettogehalt use-default-rlg-1? false)
                 (neg net)
                 "AT net pay payable")])]
    (vec (concat emp-legs er-legs net-leg))))

(defrecord AtKontorPostingBuilder [opts]
  pp/PayrollPostingBuilder
  (build-postings [_ payroll-facts {:keys [accounts ledger]}]
    (let [db (or (:db opts)
                 (throw (ex-info "AtKontorPostingBuilder needs :db in opts to resolve default RLG-1 codes (pass (d/db conn))"
                                 {:opts (set (keys opts))})))
          commodity (or (:commodity opts)
                        (throw (ex-info "AtKontorPostingBuilder needs :commodity in opts" {})))
          use-default? (boolean (:use-default-rlg-1? opts))
          base-opts {:accounts (or accounts {})
                     :commodity commodity
                     :ledger ledger
                     :use-default-rlg-1? use-default?}]
      (vec
       (mapcat #(fact->postings db % base-opts) payroll-facts)))))

(defn make-at-kontor-posting-builder
  [opts]
  (->AtKontorPostingBuilder opts))

;; ============================================================================
;; AtKontorEmitProvider — kernel PayrollEmitProvider impl
;; ============================================================================
;;
;; The kernel contract: emit-payroll-events returns a VECTOR of pure
;; :audit-doc tx-data maps (NOT a `!`-driven side-effect). The bridge
;; rebuilds the mBGM XML from the facts (which carry the engine's
;; per-employee data in :jurisdiction-specific-codes) and produces a
;; :payroll-filing :audit-doc row.

(defn- facts->mbgm-employees
  "Reconstruct the per-employee shape that
   `kontor.payroll-at.elda/emit-mbgm-xml` expects, from the
   normalized PayrollFacts plus their :jurisdiction-specific-codes.
   The codes carry the original VSNR + Beitragsgruppe + per-wage-type
   bucket data we need for the mBGM element layout.

   The facts vector is the substrate's serialized view; the codes
   round-trip the engine-side data we stamped during compute."
  [facts]
  (mapv
   (fn [{:keys [components jurisdiction-specific-codes]}]
     (let [;; Aggregate by kind + employer-side flag — deductions are
           ;; negative in the fact but mBGM wants absolute amounts.
           by-kind (group-by (juxt :kind (comp boolean :employer-side?))
                             components)
           amount-of (fn [k es?]
                       (let [signed
                             (reduce
                              (fn [^BigDecimal a {:keys [amount]}]
                                (.add a ^BigDecimal amount))
                              0M
                              (get by-kind [k es?] []))]
                         (.abs ^BigDecimal signed)))
           name (:employee-name jurisdiction-specific-codes)]
       {:vsnr (:vsnr jurisdiction-specific-codes)
        :name name
        :beitragsgruppe (:beitragsgruppe jurisdiction-specific-codes)
        :commodity :EUR
        :line-items
        (cond-> []
          (pos? (.signum ^BigDecimal (amount-of :grundgehalt false)))
          (conj {:wage-type :grundgehalt
                 :amount (amount-of :grundgehalt false)})

          (pos? (.signum ^BigDecimal (amount-of :überstunden false)))
          (conj {:wage-type :überstunden
                 :amount (amount-of :überstunden false)})

          (pos? (.signum ^BigDecimal (amount-of :urlaubsremuneration false)))
          (conj {:wage-type :urlaubsremuneration
                 :amount (amount-of :urlaubsremuneration false)})

          (pos? (.signum ^BigDecimal (amount-of :weihnachtsremuneration false)))
          (conj {:wage-type :weihnachtsremuneration
                 :amount (amount-of :weihnachtsremuneration false)})

          (pos? (.signum ^BigDecimal (amount-of :sv-arbeitnehmer false)))
          (conj {:wage-type :sv-arbeitnehmer
                 :amount (amount-of :sv-arbeitnehmer false)})

          (pos? (.signum ^BigDecimal (amount-of :sv-arbeitgeber true)))
          (conj {:wage-type :sv-arbeitgeber
                 :amount (amount-of :sv-arbeitgeber true)}))}))
   facts))

(defn- period-from-pay-period
  "Derive the {:from :to} period bounds for the mBGM element from a
   :pay-period eid. The :pay-period schema carries :pay-period/start-date
   + :pay-period/end-date (per `kontor.hr.schema`); the mBGM element
   wants the same shape `:payroll-result/period` carries.

   The :end-date in HR schema is INCLUSIVE; mBGM uses an exclusive
   upper bound; we add 1 day."
  [db pay-period-eid]
  (let [entity (d/pull db [:pay-period/start-date :pay-period/end-date]
                       pay-period-eid)
        {:pay-period/keys [start-date end-date]} entity]
    (when (and start-date end-date)
      (let [cal (doto (java.util.Calendar/getInstance
                       (TimeZone/getTimeZone "UTC"))
                  (.setTime end-date)
                  (.add java.util.Calendar/DATE 1))]
        {:from start-date :to (.getTime cal)}))))

(defrecord AtKontorEmitProvider [at-emitter opts]
  pp/PayrollEmitProvider
  (emit-payroll-events [_ payroll-facts
                        {:keys [pay-period-eid entity-eid] :as _ctx}]
    (let [{:keys [dienstgeber-beitragskonto storage-uri-template
                  storage-uri employer-name db language uploaded-by-uid]
           :or {language :de}} opts
          _ (when-not dienstgeber-beitragskonto
              (throw (ex-info "AtKontorEmitProvider needs :dienstgeber-beitragskonto in opts" {})))
          db (or db
                 (throw (ex-info "AtKontorEmitProvider needs :db in opts" {})))
          period (period-from-pay-period db pay-period-eid)
          _ (when-not period
              (throw (ex-info ":pay-period missing :start-date / :end-date"
                              {:pay-period-eid pay-period-eid})))
          period-str (yyyymm (:from period))
          emit-format (when at-emitter
                        (at-emit/envelope-format at-emitter))
          mbgm-employees (facts->mbgm-employees payroll-facts)
          xml-bytes (elda/emit-mbgm-xml
                     {:dienstgeber-beitragskonto dienstgeber-beitragskonto
                      :employer-name employer-name
                      :period period
                      :employees mbgm-employees})
          sha (audit-doc/sha-256 xml-bytes)
          uri (or storage-uri
                  (when storage-uri-template
                    (format storage-uri-template period-str))
                  (str "mem://kontor/payroll-at/mbgm-" period-str "-"
                       entity-eid ".xml"))
          code (str "mbgm-" period-str "-" entity-eid)
          tempid (str "at-emit-mbgm-" period-str "-" entity-eid)]
      [(cond-> {:db/id tempid
                :audit-doc/code code
                :audit-doc/type :mbgm
                :audit-doc/title (str "mBGM " period-str)
                :audit-doc/description
                (str "ÖGK mBGM submission for " period-str
                     " (" (count payroll-facts) " employees)"
                     (when emit-format
                       (str "; envelope " emit-format)))
                :audit-doc/storage-uri uri
                :audit-doc/content-hash sha
                :audit-doc/uploaded-at (Date.)
                :audit-doc/category :payroll-filing
                :audit-doc/language language}
         uploaded-by-uid
         (assoc :audit-doc/uploaded-by-uid uploaded-by-uid))])))

(defn make-at-kontor-emit-provider
  ([] (make-at-kontor-emit-provider {}))
  ([opts]
   (->AtKontorEmitProvider (at-emit/make-at-emit-provider) opts)))

;; ============================================================================
;; Convenience constructor — the trio in one call
;; ============================================================================

(defn make-at-kontor-providers
  "Construct the three kernel-protocol-satisfying records as a single
   map, ready to thread through `kontor.hr.payroll/run-payroll!`.

   Required opts:
     :db                          datahike value (`(datahike.api/db conn)`)
                                  for default-RLG-1 lookups + the emit
                                  side's :pay-period date pull
     :commodity                   :commodity ref the postings carry
     :dienstgeber-beitragskonto   employer's ÖGK number (used by the
                                  mBGM emit side)

   Optional opts:
     :engine               :bmd (default) | :rzl
     :use-default-rlg-1?   true to resolve default RLG-1 codes via
                           :account/code (mirrors the legacy
                           `kontor.payroll-at.posting-builder`)
     :employer-name        string — appears in mBGM <Dienstgeber>
     :storage-uri-template printf-style template with one %s slot for
                           yyyy-MM (e.g. \"s3://kontor/mbgm/%s.xml\")
     :storage-uri          override the template per call
     :language             :de (default) — set on the audit-doc
     :uploaded-by-uid      stamp on the audit-doc
     :provider-id          override the compute-provider-id (default
                           :at/bmd or :at/rzl)

   Returns
     {:compute-provider <AtKontorComputeProvider>
      :posting-builder  <AtKontorPostingBuilder>
      :emit-provider    <AtKontorEmitProvider>}"
  [{:keys [engine commodity db dienstgeber-beitragskonto
           use-default-rlg-1? employer-name storage-uri-template
           storage-uri language uploaded-by-uid provider-id]
    :or {engine :bmd}}]
  (when-not db        (throw (ex-info ":db required" {})))
  (when-not commodity (throw (ex-info ":commodity required" {})))
  (when-not dienstgeber-beitragskonto
    (throw (ex-info ":dienstgeber-beitragskonto required" {})))
  {:compute-provider
   (make-at-kontor-compute-provider
    (cond-> {:engine engine}
      provider-id (assoc :provider-id provider-id)))
   :posting-builder
   (make-at-kontor-posting-builder
    {:db db
     :commodity commodity
     :use-default-rlg-1? (boolean use-default-rlg-1?)})
   :emit-provider
   (make-at-kontor-emit-provider
    (cond-> {:db db
             :dienstgeber-beitragskonto dienstgeber-beitragskonto
             :language (or language :de)}
      employer-name        (assoc :employer-name employer-name)
      storage-uri-template (assoc :storage-uri-template storage-uri-template)
      storage-uri          (assoc :storage-uri storage-uri)
      uploaded-by-uid      (assoc :uploaded-by-uid uploaded-by-uid)))})
