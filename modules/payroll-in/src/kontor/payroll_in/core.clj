(ns kontor.payroll-in.core
  "kontor-payroll-in — IN payroll companion module (Stage R C9, ADR-083).

   Composes:
     - the existing kontor.l10n-in base chart + identifiers (PAN /
       GSTIN validators) + states + GST infrastructure,
     - the kontor-hr substrate (`kontor.hr.payroll/run-payroll!`,
       `kontor.payroll-provider/Payroll{Compute,Posting,Emit}Provider`),
     - the C9-shipped pieces: wage-types catalog, payroll-extension
       chart, Keka + GreytHR + ZenHR CSV compute providers, posting
       builder, TDS / PF / ESI quarterly / monthly aggregators,
       termination audit-doc helper.

   ## Quickstart

   ```
   (require '[kontor.core :as kontor]
            '[kontor.hr.core :as hr]
            '[kontor.l10n-in.chart :as in-chart]
            '[kontor.l10n-in.states :as in-states]
            '[kontor.payroll-in.core :as in-payroll])

   (def conn (kontor/create-test-db))
   (hr/install! conn)
   (in-states/install! conn)             ; IN + 37 GST state codes
   (in-chart/install! conn)              ; Schedule III base chart (INR commodity)
   (in-payroll/install! conn)            ; IN payroll chart extension +
                                         ; :in-state analytic plan
                                         ; (28 states + 8 UTs + Ladakh)
   ```

   ## License posture (CLAUDE.md + ADR-001 + ADR-005 + ADR-071 + ADR-083)

   - NSDL e-TDS FVU spec is public — published on the Protean website.
     No vendor source has been lifted.
   - EPFO ECR + ESIC contribution CSV formats are public — published
     on EPFO Unified Portal + ESIC portal help pages.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No proprietary pay-element catalog (Keka / GreytHR / ZenHR /
     Saral PayPack / SumoPayroll) bundled — consumer supplies the
     engine→kontor kind mapping.
   - No PT rate slabs bundled — per-state, changed annually.
   - No TDS slabs bundled — Finance Act updates them annually.

   See also: doc/research/79-hr-payroll-stage-r-plan.md §5.3,
   modules/payroll-ca/src/kontor/payroll_ca/core.clj (structural
   template — same install! + plan-install + re-export pattern)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [kontor.payroll-in.accrual :as accrual]
            [kontor.payroll-in.compute :as compute]
            [kontor.payroll-in.emit :as emit]
            [kontor.payroll-in.esi :as esi]
            [kontor.payroll-in.pf :as pf]
            [kontor.payroll-in.posting-builder :as pb]
            [kontor.payroll-in.tds :as tds]
            [kontor.payroll-in.wage-types :as wage-types]))

;; ============================================================================
;; IN sub-jurisdiction analytic plan + per-state analytic accounts
;; ============================================================================
;; Per note 79 §5.3 + ADR-077 note 83 §4 — multi-state allocation uses
;; :analytic-account, NOT :posting/entity. An IN company with employees
;; across MH + KA + TN is ONE legal entity (one PAN); per-state lives
;; on ADR-022 analytic distributions.
;;
;; ISO-3166-2:IN codes — 28 states + 8 union territories + Ladakh (UT).
;; Matches `:employment/province-of-employment` per note 86 P1-86-3
;; (substrate-level attr documents ISO-3166-2 codes as canonical).

(def in-states
  "ISO-3166-2:IN — 28 states + 8 UTs + Ladakh (the 2019 carve-out from
   J&K). The :in-state analytic plan installs one :analytic-account
   per entry. PT-levy is read from
   `kontor.payroll-in.wage-types/pt-states`."
  [;; ── States (28) ─────────────────────────────────────────────
   ["AP" "Andhra Pradesh"]
   ["AR" "Arunachal Pradesh"]
   ["AS" "Assam"]
   ["BR" "Bihar"]
   ["CT" "Chhattisgarh"]
   ["GA" "Goa"]
   ["GJ" "Gujarat"]
   ["HR" "Haryana"]
   ["HP" "Himachal Pradesh"]
   ["JH" "Jharkhand"]
   ["KA" "Karnataka"]
   ["KL" "Kerala"]
   ["MP" "Madhya Pradesh"]
   ["MH" "Maharashtra"]
   ["MN" "Manipur"]
   ["ML" "Meghalaya"]
   ["MZ" "Mizoram"]
   ["NL" "Nagaland"]
   ["OR" "Odisha"]
   ["PB" "Punjab"]
   ["RJ" "Rajasthan"]
   ["SK" "Sikkim"]
   ["TN" "Tamil Nadu"]
   ["TG" "Telangana"]
   ["TR" "Tripura"]
   ["UP" "Uttar Pradesh"]
   ["UT" "Uttarakhand"]
   ["WB" "West Bengal"]
   ;; ── Union Territories (8) — A&N, CH, DH (post-2020-merger
   ;;    DNH+DD), DL, JK, LA, LD, PY ──────────────────────────
   ["AN" "Andaman and Nicobar Islands"]
   ["CH" "Chandigarh"]
   ["DH" "Dadra and Nagar Haveli and Daman and Diu"]
   ["DL" "Delhi"]
   ["JK" "Jammu and Kashmir"]
   ["LA" "Ladakh"]
   ["LD" "Lakshadweep"]
   ["PY" "Puducherry"]])

(defn install-in-state-analytic-plan!
  "Install the :in-state analytic plan + per-state :analytic-account
   rows. Idempotent: re-running with the same data is a no-op (uses
   :db.unique/identity on :analytic-plan/code + :analytic-account/path).

   The :in-state plan applies to *consumer-marked* wage / payroll-tax
   / PT-payable accounts via :account/required-analytic-plans (per
   ADR-022). We do NOT mark the accounts here — that's the consumer's
   chart install. We DO ship the plan + states so consumers don't
   need to."
  [conn]
  (let [plan-tempid "in-state-plan"
        plan-tx [{:db/id plan-tempid
                  :analytic-plan/code "in-state"
                  :analytic-plan/name "IN state of employment (ISO-3166-2:IN)"
                  :analytic-plan/applicability :optional
                  :analytic-plan/active true}]
        account-tx (mapv (fn [[code label]]
                           {:analytic-account/path (str "in-state:IN-" code)
                            :analytic-account/code (str "IN-" code)
                            :analytic-account/name label
                            :analytic-account/plan plan-tempid
                            :analytic-account/active true})
                         in-states)]
    (d/transact conn (vec (concat plan-tx account-tx)))))

;; ============================================================================
;; Chart loader (mirrors payroll-ca/chart.clj shape)
;; ============================================================================

(defn load-starter-chart
  "Load the IN payroll-extension starter chart (Schedule III aligned)."
  []
  (-> "kontor/payroll_in/coa_starter.edn"
      io/resource slurp edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx [tags]
  (mapv (fn [t] {:account-tag/name (name t)
                 :account-tag/country-code "IN"
                 :account-tag/applicability :account})
        tags))

(defn- account-tx
  ;; :tax-payable? in the EDN starter is a hint for downstream filters;
  ;; the kernel schema doesn't have a typed slot for it (per
  ;; modules/l10n-in/chart.clj convention — also drops :tax-payable?).
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:account/path path :account/code code :account/name name
           :account/type type :account/active true
           :account/commodity [:kontor.commodity/symbol "INR"]
           :account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :account/tags
           (mapv (fn [t] [:account-tag/name (clojure.core/name t)]) tags))))

(defn install-tags!
  "Idempotent install of just the :account-tag entities (no accounts).
   Useful when the consumer's chart already exists and only needs the
   payroll tag vocabulary registered."
  ([conn] (install-tags! conn (load-starter-chart)))
  ([conn chart]
   (d/transact conn (tag-tx (distinct-tags chart)))))

;; ============================================================================
;; install! — top-level idempotent installer
;; ============================================================================

(defn install!
  "Idempotent install of the IN payroll companion. Currently:
     - Installs the `:in-state` analytic plan + 37 :analytic-account
       rows (28 states + 8 UTs + Ladakh) idempotently.
     - Installs the payroll account-tag entities.
     - Installs the starter chart accounts (unless `:tags-only? true`).

   Run AFTER `kontor.core/install-schema!` + `kontor.hr.core/install!`
   + `kontor.l10n-in.chart/install!` (the latter installs the INR
   commodity + Schedule III base chart this extension layers on top
   of)."
  ([conn] (install! conn {}))
  ([conn {:keys [tags-only?]}]
   (let [db (d/db conn)
         plan-already?
         (boolean (d/q '[:find ?e .
                         :where [?e :analytic-plan/code "in-state"]]
                       db))]
     (when-not plan-already?
       (install-in-state-analytic-plan! conn))
     (if tags-only?
       (install-tags! conn)
       (do
         (install-tags! conn)
         (d/transact conn (mapv account-tx (load-starter-chart))))))))

;; ============================================================================
;; Convenience constructors for the provider trio
;; ============================================================================

(defn make-keka-compute-provider
  "Construct a `KekaProvider`. The provider holds optional defaults
   (`:csv-source`, `:pay-element-codes`, `:external-id->eid`,
   `:column-mapping`, `:commodity-eid`, `:extras-map`) that may also
   be passed at `compute-payroll` time via `:variable-inputs` /
   `ctx`."
  ([] (make-keka-compute-provider {}))
  ([opts] (compute/->KekaProvider opts)))

(defn make-greythr-compute-provider
  "Construct a `GreytHrProvider`."
  ([] (make-greythr-compute-provider {}))
  ([opts] (compute/->GreytHrProvider opts)))

(defn make-zenhr-compute-provider
  "Construct a `ZenHrProvider`. The generic CSV adapter — handles
   ZenHR / ZingHR / SumoPayroll / Saral PayPack / consumer-rolled
   in-house CSV exports. REQUIRES `:column-mapping` (no engine
   default)."
  [opts]
  (compute/->ZenHrProvider opts))

(defn make-in-payroll-posting-builder
  "Construct an `InPayrollPostingBuilder`. Requires `:commodity` (INR
   ref). Optional `:extras-map` for consumer-extended wage-type kinds;
   optional `:default-state` ISO-3166-2 code for facts without a
   province (most engines emit it per-employee, but fallback helps
   single-state companies skip the per-row column)."
  [{:keys [commodity] :as opts}]
  (when-not commodity
    (throw (ex-info "make-in-payroll-posting-builder needs :commodity (INR ref)"
                    {})))
  (pb/->InPayrollPostingBuilder opts))

(defn make-in-payroll-emit-provider
  "Construct an `InPayrollEmitProvider`. Optional `:language` defaults
   to `:en-in` per ADR-083."
  ([] (make-in-payroll-emit-provider {}))
  ([opts] (emit/->InPayrollEmitProvider opts)))

;; ============================================================================
;; Re-exports for one-import convenience
;; ============================================================================

(def load-starter-chart-fixture load-starter-chart)
(def merged-catalog wage-types/merged-catalog)
(def pt-state? wage-types/pt-state?)

(def bonus-accrual! accrual/bonus-accrual!)
(def bonus-accrual-tx-data accrual/bonus-accrual-tx-data)
(def leave-encashment-accrual! accrual/leave-encashment-accrual!)
(def leave-encashment-accrual-tx-data accrual/leave-encashment-accrual-tx-data)
(def gratuity-accrual! accrual/gratuity-accrual!)
(def gratuity-accrual-tx-data accrual/gratuity-accrual-tx-data)

(def quarterly-tds-summary tds/quarterly-tds-summary)
(def form-24q-fvu tds/form-24q-fvu)
(def build-form-24q-submission tds/build-form-24q-submission)
(def tds-audit-doc-tx-data tds/tds-audit-doc-tx-data)

(def monthly-pf-summary pf/monthly-pf-summary)
(def ecr-text pf/ecr-text)
(def build-ecr-submission pf/build-ecr-submission)
(def ecr-audit-doc-tx-data pf/ecr-audit-doc-tx-data)

(def monthly-esi-summary esi/monthly-esi-summary)
(def esic-csv esi/esic-csv)
(def build-esi-submission esi/build-esi-submission)
(def esi-audit-doc-tx-data esi/esi-audit-doc-tx-data)

(def terminate-employment-tx-data emit/terminate-employment-tx-data)
(def warn-if-multi-state-pt! emit/warn-if-multi-state-pt!)
(def build-form-24q-audit-doc-tx-data emit/build-form-24q-audit-doc-tx-data)
