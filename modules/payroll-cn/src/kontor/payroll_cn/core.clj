(ns kontor.payroll-cn.core
  "kontor-payroll-cn — CN payroll adapter (Stage R C11, ADR-085).

   Companion module on top of `kontor-hr` (Stage R substrate, ADR-075)
   that wraps the dominant CN payroll engines (Yonyou / Kingdee /
   Beisen), classifies wage-type rows against a consumer-supplied
   mapping, and produces ASBE-aligned balanced GL postings on the
   2211 应付职工薪酬 sub-tree plus the IIT monthly filing audit-doc.

   ## Install order

     1. `kontor.core/install-schema!`         — kernel
     2. `kontor.hr.core/install!`             — HR substrate
     3. `kontor.l10n-cn.chart/install!`       — CN base chart (CNY commodity + 2211 / 2221 / 5602 / 5603 etc.)
     4. `kontor.payroll-cn.core/install!`     — this; installs the
                                                `:cn-province` analytic
                                                plan + 31 provinces /
                                                autonomous regions /
                                                directly-administered
                                                municipalities

   ## What this module ships

     - `kontor.payroll-cn.compute`         — YonyouCsvComputeProvider /
                                              KingdeeCsvComputeProvider /
                                              BeisenCsvComputeProvider
                                              (config-driven CSV
                                              parser, per-engine
                                              column-mapping defaults)
     - `kontor.payroll-cn.posting-builder` — CnPayrollPostingBuilder
                                              (per-component routing to
                                              2211 sub-accounts +
                                              2221 IIT payable; per-
                                              province analytic
                                              distributions)
     - `kontor.payroll-cn.accrual`         — 年终奖累计 (annual bonus
                                              accrual) primitive
                                              (book-only per CAS 9)
     - `kontor.payroll-cn.iit`             — IIT computation oracle
                                              (engine-authoritative;
                                              we only aggregate)
     - `kontor.payroll-cn.emit`            — CnIitMonthlyEmitProvider
                                              (zh-cn structured CSV
                                              payload for 自然人电子
                                              税务局)
     - `kontor.payroll-cn.wage-types`      — consumer-extensible
                                              wage-type catalog

   ## License posture (ADR-085 — same posture as ADR-005 / ADR-071 / ADR-075 / ADR-076 / ADR-077 / ADR-078)

   - NEVER lifts engine code (Yonyou / Kingdee / Beisen) — we work
     from public spec only.
   - NEVER bundles engine API credentials.
   - NEVER bundles per-city SI / HF rate tables (200+ cities, change
     ~annually).
   - NEVER bundles IIT bracket schedules (regulator-versioned).
   - NEVER bundles 自然人电子税务局 credentials.

   See doc/decisions.md ADR-085 + doc/research/87-cn-payroll-research-before.md
   for the design rationale."
  (:require [datahike.api :as d]
            [kontor.payroll-cn.accrual :as accrual]
            [kontor.payroll-cn.compute :as compute]
            [kontor.payroll-cn.emit :as emit]
            [kontor.payroll-cn.iit :as iit]
            [kontor.payroll-cn.posting-builder :as pb]
            [kontor.payroll-cn.wage-types :as wage-types]))

;; ============================================================================
;; The :cn-province analytic plan + per-province analytic accounts
;; ============================================================================
;; Per note 87 §2.2 — multi-city allocation uses :analytic-account, NOT
;; :posting/entity. A CN Ltd Co with employees in 5 cities is ONE
;; legal entity (one CIT filing). Per-province lives on ADR-022
;; analytic distributions; we install the plan + 34 administrative
;; divisions (23 provinces + 5 autonomous regions + 4 municipalities
;; + 2 SARs).
;;
;; Per-city allocation is a follow-up — consumers can install a
;; second :analytic-plan/code "cn-city" themselves; v1 ships the
;; province-level granularity.
;;
;; ISO-3166-2:CN codes — see https://www.iso.org/obp/ui/#iso:code:3166:CN

(def cn-provinces
  "ISO-3166-2:CN — 34 administrative divisions (23 provinces, 5
   autonomous regions, 4 directly-administered municipalities, 2
   special administrative regions). The :cn-province analytic plan
   installs one :analytic-account per entry. Names in Chinese (per
   the regulator) + English (for legibility)."
  [;; ── Directly-administered municipalities (直辖市) ──
   ["BJ" "北京 / Beijing"]
   ["TJ" "天津 / Tianjin"]
   ["SH" "上海 / Shanghai"]
   ["CQ" "重庆 / Chongqing"]
   ;; ── Provinces (省) ──
   ["HE" "河北 / Hebei"]
   ["SX" "山西 / Shanxi"]
   ["LN" "辽宁 / Liaoning"]
   ["JL" "吉林 / Jilin"]
   ["HL" "黑龙江 / Heilongjiang"]
   ["JS" "江苏 / Jiangsu"]
   ["ZJ" "浙江 / Zhejiang"]
   ["AH" "安徽 / Anhui"]
   ["FJ" "福建 / Fujian"]
   ["JX" "江西 / Jiangxi"]
   ["SD" "山东 / Shandong"]
   ["HA" "河南 / Henan"]
   ["HB" "湖北 / Hubei"]
   ["HN" "湖南 / Hunan"]
   ["GD" "广东 / Guangdong"]
   ["HI" "海南 / Hainan"]
   ["SC" "四川 / Sichuan"]
   ["GZ" "贵州 / Guizhou"]
   ["YN" "云南 / Yunnan"]
   ["SN" "陕西 / Shaanxi"]
   ["GS" "甘肃 / Gansu"]
   ["QH" "青海 / Qinghai"]
   ["TW" "台湾 / Taiwan"]
   ;; ── Autonomous regions (自治区) ──
   ["NM" "内蒙古 / Inner Mongolia"]
   ["GX" "广西 / Guangxi"]
   ["XZ" "西藏 / Tibet"]
   ["NX" "宁夏 / Ningxia"]
   ["XJ" "新疆 / Xinjiang"]
   ;; ── Special administrative regions (特别行政区) ──
   ["HK" "香港 / Hong Kong"]
   ["MO" "澳门 / Macau"]])

(defn install-cn-province-analytic-plan!
  "Install the :cn-province analytic plan + per-province
   :analytic-account rows. Idempotent: re-running with the same data
   is a no-op (uses :db.unique/identity on :analytic-plan/code +
   :analytic-account/path).

   The :cn-province plan applies to *consumer-marked* wage / SI / HF
   accounts via :kontor.account/required-analytic-plans (per ADR-022). We do
   NOT mark the accounts here — that's the consumer's chart install.
   We DO ship the plan + provinces so consumers don't need to."
  [conn]
  (let [plan-tempid "cn-province-plan"
        plan-tx [{:db/id plan-tempid
                  :analytic-plan/code "cn-province"
                  :analytic-plan/name "CN province of employment"
                  :analytic-plan/applicability :optional
                  :analytic-plan/active true}]
        account-tx (mapv (fn [[code label]]
                           {:analytic-account/path (str "cn-province:" code)
                            :analytic-account/code code
                            :analytic-account/name label
                            :analytic-account/plan plan-tempid
                            :analytic-account/active true})
                         cn-provinces)]
    (d/transact conn (vec (concat plan-tx account-tx)))))

;; ============================================================================
;; The :account-tag vocabulary
;; ============================================================================

(def account-tags
  "The :account-tag entities the CN payroll adapter expects on the
   consumer's chart. Installed idempotently as `:kontor.account-tag/name`
   rows; the consumer then attaches these tags to the relevant chart
   accounts."
  [;; Expense side
   :cn-payroll-wages-expense
   :cn-payroll-er-si-expense
   :cn-payroll-er-hf-expense
   ;; Liability side — net + IIT
   :cn-payroll-net-wages
   :cn-payroll-iit
   ;; Liability side — SI buckets
   :cn-payroll-ee-si
   :cn-payroll-ee-hf
   :cn-payroll-er-si-payable
   :cn-payroll-er-hf-payable
   ;; Annual bonus accrual sub-bucket
   :cn-payroll-bonus-payable])

(defn install-account-tags!
  "Install the :account-tag entities the CN payroll adapter expects
   on the consumer's chart. Idempotent."
  [conn]
  (d/transact conn
              (mapv (fn [t]
                      {:kontor.account-tag/name (name t)
                       :kontor.account-tag/country-code "CN"
                       :kontor.account-tag/applicability :account})
                    account-tags)))

;; ============================================================================
;; Module-local schema extensions
;; ============================================================================
;;
;; The DE-DATEV module shipped these attrs first (ADR-076 / note 82); the CN
;; module ships its installer too so a consumer running ONLY kontor-payroll-cn
;; (without the DE module) still gets the audit-doc emit attrs that the
;; emit provider writes. Idempotent (datahike upserts schema by :db/ident).
;;
;;   :audit-doc/inline-payload   — short CSV/XML payload string
;;   :audit-doc/payroll-period   — ref to :pay-period
;;   :audit-doc/payroll-entity   — ref to :entity
;;   :audit-doc/unmapped-count   — long, # of unmapped components

(def extra-schema
  [{:db/ident       :audit-doc/inline-payload
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional inline payload — short IIT-monthly CSV
                     contents stored next to the audit-doc record.
                     Consumers prefer :audit-doc/storage-uri for large
                     files (> ~10 KB). Shared attr (also installed by
                     payroll-de-datev per ADR-076)."}

   {:db/ident       :audit-doc/payroll-period
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :pay-period — the period this emit-payload
                     covers (note 87 §6). Shared attr."}

   {:db/ident       :audit-doc/payroll-entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :entity — the employer this emit-payload
                     covers. Shared attr."}

   {:db/ident       :audit-doc/unmapped-count
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Count of compensation-components dropped during
                     IIT monthly emit because their kind did not match
                     the catalog. > 0 routes the run to manual review.
                     Shared attr."}])

(defn install!
  "Install the kontor-payroll-cn companion. Currently:
     - Installs the four shared `:audit-doc/*` payroll-emit attrs (no-op
       if already installed by another payroll adapter).
     - Installs the :cn-province analytic plan + 34 provinces / regions.
     - Installs the :account-tag vocabulary so the consumer's chart can
       reference :cn-payroll-* tags by name.

   Run AFTER `kontor.core/install-schema!` + `kontor.hr.core/install!`
   + (optionally) `kontor.l10n-cn.chart/install!`. Idempotent."
  [conn]
  ;; Install the shared audit-doc emit attrs first (idempotent — datahike
  ;; upserts schema by :db/ident).
  (d/transact conn extra-schema)
  (let [db (d/db conn)
        already? (boolean (d/q '[:find ?e .
                                 :where [?e :analytic-plan/code "cn-province"]]
                               db))]
    (when-not already?
      (install-cn-province-analytic-plan! conn))
    (install-account-tags! conn)))

;; ============================================================================
;; Convenience constructors for the provider trio
;; ============================================================================

(defn make-yonyou-compute-provider
  "Construct a `YonyouCsvComputeProvider`. Per ADR-085 the provider has
   no embedded config — credentials / CSV source / employee-id-mapping
   are all passed at `run-payroll!` time via `:variable-inputs`."
  ([] (make-yonyou-compute-provider {}))
  ([opts] (compute/->YonyouCsvComputeProvider opts)))

(defn make-kingdee-compute-provider
  "Construct a `KingdeeCsvComputeProvider`."
  ([] (make-kingdee-compute-provider {}))
  ([opts] (compute/->KingdeeCsvComputeProvider opts)))

(defn make-beisen-compute-provider
  "Construct a `BeisenCsvComputeProvider`."
  ([] (make-beisen-compute-provider {}))
  ([opts] (compute/->BeisenCsvComputeProvider opts)))

(defn make-cn-payroll-posting-builder
  "Construct a `CnPayrollPostingBuilder`. The only required opt is
   :commodity (typically CNY) so `build-postings` can stamp it on
   every leg without the consumer threading it through
   `:variable-inputs`."
  [{:keys [commodity] :as opts}]
  (when-not commodity
    (throw (ex-info "make-cn-payroll-posting-builder needs :commodity (CNY ref)"
                    {:type :cn-payroll/missing-commodity})))
  (pb/->CnPayrollPostingBuilder opts))

(def make-iit-emit-provider emit/make-provider)

;; Re-exports for one-import convenience.

(def annual-bonus-accrual-tx-data accrual/annual-bonus-accrual-tx-data)
(def annual-bonus-accrual!        accrual/annual-bonus-accrual!)
(def one-twelfth                  accrual/one-twelfth)

(def iit-summary-for-period       iit/iit-summary-for-period)
(def iit-summary-per-employee     iit/iit-summary-per-employee)
(def annual-bonus-method          iit/annual-bonus-method)

(def standard-component-kinds     wage-types/standard-component-kinds)
(def known-kinds                  wage-types/known-kinds)
(def assert-known!                wage-types/assert-known!)
