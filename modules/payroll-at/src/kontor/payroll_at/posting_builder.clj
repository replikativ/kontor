(ns kontor.payroll-at.posting-builder
  "Build a balanced kernel transaction from a normalized
   `:payroll-result` (ADR-072).

   ADR-068 — pure `*-tx-data` builder; the `!` wrapper routes through
   `kontor.validation/transact-with-validation`. The builder uses
   `kontor.posting/build-transaction` so the standard sum-to-zero
   invariants per (ledger, commodity) fire automatically.

   Posting shape (one transaction per period):

     Dr  6000  Grundgehalt + Überstunden (sum across employees)
     Dr  6400  Urlaubsremuneration       (June period only)
     Dr  6410  Weihnachtsremuneration    (November period only)
     Dr  6500  SV-Arbeitgeber
     Dr  6510  Dienstgeberbeitrag-FLAG
     Dr  6520  Kommunalsteuer
     Dr  6530  Zuschlag-zum-DB
     Dr  6800  Sachbezugsaufwand
       Cr  3500  LSt-Verbindlichkeit
       Cr  3540  SV-Verbindlichkeit   (employee + employer)
       Cr  3550  DB-Verbindlichkeit   (DB + DZ)
       Cr  3560  KommSt-Verbindlichkeit
       Cr  3590  Sachbezugs-Clearing
       Cr  3700  Verbindlichkeit Lohn  (nettogehalt)

   Each line is conditional — if the period has no Sonderzahlung,
   no 6400/6410 line appears. The Sonderzahlung (13./14.) IS the
   special-rate (6 %) Lohnsteuer base; the engine has already split
   the LSt amounts (regular vs Sonderzahlung) — the adapter
   aggregates them on a single 3500 credit."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.payroll-at.wage-types :as wt]
            [kontor.posting :as posting]
            [kontor.validation :as validation])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- aggregate-by-wage-type
  "Sum a `:payroll-result`'s per-employee line-items into a single
   {:wage-type → bigdec} map at the period level."
  [{:keys [:payroll-result/employees]}]
  (->> employees
       (mapcat :line-items)
       (group-by :wage-type)
       (reduce-kv
        (fn [acc wt items]
          (assoc acc wt
                 (.setScale (reduce (fn [^BigDecimal a it]
                                      (.add a ^BigDecimal (:amount it)))
                                    0M items)
                            2 RoundingMode/HALF_EVEN)))
        {})))

(defn- account-eid
  "Resolve an account code to an eid via [:account/code <code>]. Returns
   nil if not present in db."
  [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- resolve-acct
  [db code]
  (or (account-eid db code)
      (throw (ex-info "Account code not present in db; install the
                       Kontenrahmen before posting payroll"
                      {:code code
                       :hint "(kontor.l10n-at.chart/install! conn)"}))))

;; ============================================================================
;; Wage-type → posting fragment
;; ============================================================================

(def ^:private debit-wage-types
  "Aufwand side — these become Dr postings."
  [:grundgehalt :überstunden
   :urlaubsremuneration :weihnachtsremuneration
   :sachbezüge
   :sv-arbeitgeber :dienstgeberbeitrag-fond
   :zuschlag-zum-db :kommunalsteuer])

(def ^:private credit-wage-types
  "Withholding + payable side — these become Cr postings.

   Note: :sv-arbeitgeber's CREDIT is also a payable (to SV-Verbindl.) —
   we model that as a *separate* credit row referencing the payable-
   code-for map, not as a single offset. The same applies to
   :dienstgeberbeitrag-fond, :zuschlag-zum-db, :kommunalsteuer."
  [:lohnsteuer :sv-arbeitnehmer])

(defn- debit-postings
  "Build the Aufwand-side postings. Each non-zero wage-type produces
   one posting with the account from the override map (or default)."
  [db totals account-map commodity-ref]
  (->> debit-wage-types
       (keep (fn [wt]
               (when-let [^BigDecimal amt (get totals wt)]
                 (when (pos? (.signum amt))
                   {:posting/account   (resolve-acct
                                        db (wt/account-code-for wt account-map))
                    :posting/amount    amt
                    :posting/commodity commodity-ref
                    :posting/narration (str "Payroll Aufwand: " (name wt))}))))
       vec))

(defn- payable-postings
  "Credit-side postings. Withholdings (LSt, SV-AN) and the employer-
   borne contributions (SV-AG → 3540; DB+DZ → 3550; KomSt → 3560)
   each become their own credit line.

   Sachbezüge is non-cash; it credits the clearing account 3590, NOT
   the employee — there is no cash leg for it. The consumer reconciles
   the clearing entry against the actual benefit."
  [db totals account-map payable-map commodity-ref]
  (let [neg (fn [^BigDecimal x] (.negate ^BigDecimal x))
        cred (fn [code amt narration]
               {:posting/account   (resolve-acct db code)
                :posting/amount    (neg amt)
                :posting/commodity commodity-ref
                :posting/narration narration})]
    (cond-> []
      ;; Withholdings — pure Cr legs against the Aufwand-side gross.
      (some-> (:lohnsteuer totals) (.signum) pos?)
      (conj (cred (wt/account-code-for :lohnsteuer account-map)
                  (:lohnsteuer totals)
                  "Lohnsteuer-Verbindlichkeit"))

      (some-> (:sv-arbeitnehmer totals) (.signum) pos?)
      (conj (cred (wt/account-code-for :sv-arbeitnehmer account-map)
                  (:sv-arbeitnehmer totals)
                  "SV-AN-Verbindlichkeit"))

      ;; Employer-borne — each gets its own payable Cr.
      (some-> (:sv-arbeitgeber totals) (.signum) pos?)
      (conj (cred (wt/payable-code-for :sv-arbeitgeber payable-map)
                  (:sv-arbeitgeber totals)
                  "SV-AG-Verbindlichkeit"))

      (some-> (:dienstgeberbeitrag-fond totals) (.signum) pos?)
      (conj (cred (wt/payable-code-for :dienstgeberbeitrag-fond payable-map)
                  (:dienstgeberbeitrag-fond totals)
                  "DB-FLAG-Verbindlichkeit"))

      (some-> (:zuschlag-zum-db totals) (.signum) pos?)
      (conj (cred (wt/payable-code-for :zuschlag-zum-db payable-map)
                  (:zuschlag-zum-db totals)
                  "DZ-Verbindlichkeit"))

      (some-> (:kommunalsteuer totals) (.signum) pos?)
      (conj (cred (wt/payable-code-for :kommunalsteuer payable-map)
                  (:kommunalsteuer totals)
                  "KommSt-Verbindlichkeit"))

      (some-> (:sachbezüge totals) (.signum) pos?)
      (conj (cred (wt/payable-code-for :sachbezüge payable-map)
                  (:sachbezüge totals)
                  "Sachbezug-Clearing"))

      ;; Nettogehalt — the residue paid to employees.
      (some-> (:nettogehalt totals) (.signum) pos?)
      (conj (cred (wt/account-code-for :nettogehalt account-map)
                  (:nettogehalt totals)
                  "Nettogehalt-Verbindlichkeit")))))

;; ============================================================================
;; Public entry
;; ============================================================================

(defn build-tx-data
  "Pure tx-data builder (ADR-068). Given a normalized `:payroll-result`,
   a connected `db`, and required refs, produce a kernel tx-data ready
   for `kontor.posting/build-transaction` flow.

   Required opts:
     :payroll-result   the normalized engine output
     :journal          journal ref (:journal/code lookup-ref works)
     :commodity        :commodity ref ([:kontor.commodity/symbol \"EUR\"])
     :effective-date   the period-end #inst (drives :tx/valid-from)

   Optional opts:
     :account-map     wage-type → account-code override
     :payable-map     employer-borne → payable-code override
     :external-id     transaction/external-id (default: 'payroll-<period>')
     :narration       transaction/narration (default: 'Payroll <period>')
     :posted-at       transaction/posted-at (default: effective-date)
     :state           :draft | :posted (default: :posted)"
  [db {:keys [payroll-result journal commodity effective-date
              account-map payable-map external-id narration
              posted-at state]
       :or {state :posted}}]
  (when-not payroll-result (throw (ex-info ":payroll-result required" {})))
  (when-not journal        (throw (ex-info ":journal required" {})))
  (when-not commodity      (throw (ex-info ":commodity required" {})))
  (when-not effective-date (throw (ex-info ":effective-date required" {})))
  (let [totals  (aggregate-by-wage-type payroll-result)
        period  (:payroll-result/period payroll-result)
        period-str (when-let [from (:from period)]
                     (let [fmt (doto (java.text.SimpleDateFormat. "yyyy-MM")
                                 (.setTimeZone
                                  (java.util.TimeZone/getTimeZone "UTC")))]
                       (.format fmt ^java.util.Date from)))
        ext-id  (or external-id (str "payroll-at-" period-str))
        narr    (or narration (str "AT-Payroll " period-str))
        debits  (debit-postings db totals account-map commodity)
        credits (payable-postings db totals account-map payable-map commodity)
        post-at (or posted-at effective-date)
        postings (vec (concat debits credits))
        postings (if (= state :posted)
                   (mapv #(assoc % :posting/posted-at post-at) postings)
                   postings)
        tx-base (cond-> {:transaction/journal         journal
                         :transaction/effective-date  effective-date
                         :transaction/external-id     ext-id
                         :transaction/narration       narr
                         :transaction/source          (str "payroll-at:" period-str)
                         :transaction/state           state}
                  (= state :posted)
                  (assoc :transaction/posted-at post-at))]
    (posting/build-transaction
     {:transaction tx-base
      :postings    postings})))

(defn post!
  "Post a balanced AT-payroll transaction. Routes through the gate.
   Returns the tx-report."
  [conn opts]
  (validation/transact-with-validation
   conn (build-tx-data (d/db conn) opts)))
