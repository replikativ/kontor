(ns kontor.regression.r2-chart-skeleton-test
  "R2 audit — chart-skeleton coverage across all 12 l10n modules.

   Question (per-jurisdiction): does the chart the module's preset installs
   actually contain the accounts that the module's OWN shipped financial-
   statement definitions (pnl.clj / bs.clj) reference by exact account code?

   Method: install `(create-<cc>-db)`, then for the shipped pnl + bs
   `definition`s compute
     - DANGLING-EXACT : exact (non-`%`) `:line/codes` that resolve to NO
                        account in the installed chart → the line renders a
                        permanent zero.
     - UNCOVERED      : accounts in the chart that NO statement line claims →
                        for a balance sheet this is money the statement cannot
                        show, i.e. the sheet silently stops balancing by that
                        account's balance (the real defect per note 194 / the
                        kernel's own `statement-coverage` docstring).

   The kernel ships `kontor.reporting.financial-statements/statement-coverage`
   for exactly this; `report/code-prefix-match?` confirms a code WITHOUT a
   trailing `%` is an EXACT match, so an enumerated exact code with no account
   is a dead line.

   FINDINGS (see the per-deftest docstrings + the structured gaps[]):
   - GREEN for US/CA/JP/AU/BR/IN/MX/AT/CN/DE: every exact code the pnl+bs defs
     reference resolves, and no balance-sheet account is left uncovered. (DE
     enumerates a deliberately-fuller SKR04 — its dangling codes are documented
     as intentional; its `:uncovered` is what must be empty, and it is.)
   - PENDING for UK: the preset installs ZERO accounts, so all 28 pnl + 31 bs
     exact codes dangle — the module ships statement defs with no chart to run
     them against.
   - PENDING for FR: the Bilan asset line B.3 enumerates 44567 + 44583 (VAT-
     credit / refund-requested) but the PCG skeleton ships neither, while
     shipping their classe-44 siblings 44562/44566/44581.
   - PENDING for CN: 4101/4105 (production cost / mfg overhead) are shipped
     as :type :expense yet no P&L line covers them → a period cost booked there
     is invisible on the income statement.

   NOTE on part (b) — payroll/tax posting builders: the payroll posting
   builders (verified: US `payroll-us-adp`, CA `payroll-ca`) are chart-
   AGNOSTIC — the consumer passes an `:accounts` map, so they reference no
   chart code. The CA chart ships zero payroll accounts by design; the US
   chart happens to ship them (2300/2400/2410 + 6110/6120/6130) as a
   convenience. That inconsistency is documented, not a correctness bug."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [datahike.api :as d]
            [kontor.reporting.financial-statements :as fs]
            [kontor.l10n-us.preset :as us] [kontor.l10n-us.pnl :as us-pnl] [kontor.l10n-us.bs :as us-bs]
            [kontor.l10n-de.preset :as de] [kontor.l10n-de.pnl :as de-pnl] [kontor.l10n-de.bs :as de-bs]
            [kontor.l10n-ca.preset :as ca] [kontor.l10n-ca.pnl :as ca-pnl] [kontor.l10n-ca.bs :as ca-bs]
            [kontor.l10n-jp.preset :as jp] [kontor.l10n-jp.pnl :as jp-pnl] [kontor.l10n-jp.bs :as jp-bs]
            [kontor.l10n-au.preset :as au] [kontor.l10n-au.pnl :as au-pnl] [kontor.l10n-au.bs :as au-bs]
            [kontor.l10n-fr.preset :as fr] [kontor.l10n-fr.pnl :as fr-pnl] [kontor.l10n-fr.bs :as fr-bs]
            [kontor.l10n-br.preset :as br] [kontor.l10n-br.pnl :as br-pnl] [kontor.l10n-br.bs :as br-bs]
            [kontor.l10n-in.preset :as in] [kontor.l10n-in.pnl :as in-pnl] [kontor.l10n-in.bs :as in-bs]
            [kontor.l10n-mx.preset :as mx] [kontor.l10n-mx.pnl :as mx-pnl] [kontor.l10n-mx.bs :as mx-bs]
            [kontor.l10n-cn.preset :as cn] [kontor.l10n-cn.pnl :as cn-pnl] [kontor.l10n-cn.bs :as cn-bs]
            [kontor.l10n-at.preset :as at] [kontor.l10n-at.pnl :as at-pnl] [kontor.l10n-at.bs :as at-bs]
            [kontor.l10n-uk.preset :as uk] [kontor.l10n-uk.pnl :as uk-pnl] [kontor.l10n-uk.bs :as uk-bs]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- non-pct [codes] (remove #(str/ends-with? % "%") codes))

(defn- exact-refs
  "Set of exact (non-`%`) account codes a statement definition references."
  [statement]
  (->> (mapcat :section/lines (:statement/sections statement))
       (mapcat :line/codes) non-pct distinct set))

(defn- present-codes [db]
  (set (map first (d/q '[:find ?c :where [?a :kontor.account/code ?c]] db))))

(defn- dangling-exact
  "Exact codes the def references that resolve to NO account in db."
  [db statement]
  (->> (exact-refs statement) (remove (present-codes db)) sort vec))

(defn- uncovered-codes
  [db statement account-types]
  (->> (:uncovered (fs/statement-coverage db statement {:account-types account-types}))
       (mapv :code) sort vec))

(defn- resolves? [db code]
  (some? (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code)))

(def ^:private income+expense #{:income :expense})
(def ^:private asset+liab+eq  #{:asset :liability :equity})

;; ===========================================================================
;; GREEN — charts fully back their own statement defs
;; ===========================================================================

;; US SMB chart (101 accounts). Every exact code in pnl + bs resolves, and no
;; balance-sheet account is left uncovered. Spot-grounded on the payroll
;; accounts the area lead claimed were absent — they are present.
(deftest us-chart-backs-its-statement-defs
  (let [db @(us/create-us-db)]
    (is (= [] (dangling-exact db us-pnl/definition)))
    (is (= [] (dangling-exact db us-bs/definition)))
    (is (= [] (uncovered-codes db us-bs/definition asset+liab+eq)))
    (is (= [] (uncovered-codes db us-pnl/definition income+expense)))
    ;; hand-checked payroll accounts DO ship (lead was outdated):
    ;; 6110 Expenses:Payroll:Wages, 6120 Employer-Tax, 2410 FICA, 2400 Fed-WH
    (is (resolves? db "6110"))
    (is (resolves? db "6120"))
    (is (resolves? db "2410"))
    (is (resolves? db "2400"))))

;; CA SMB chart (24 accounts). pnl+bs exact codes all resolve; bs has no
;; uncovered account. NB: CA chart ships zero payroll accounts by design (the
;; payroll builder is chart-agnostic), and neither statement references any.
(deftest ca-chart-backs-its-statement-defs
  (let [db @(ca/create-ca-db)]
    (is (= [] (dangling-exact db ca-pnl/definition)))
    (is (= [] (dangling-exact db ca-bs/definition)))
    (is (= [] (uncovered-codes db ca-bs/definition asset+liab+eq)))
    (is (= [] (uncovered-codes db ca-pnl/definition income+expense)))
    ;; CA GST/PST/QST liability accounts the Bilan references DO ship
    (is (resolves? db "2310"))   ; GST-HST-Collected
    (is (resolves? db "2330"))))  ; QST-Collected

;; JP chart (38 accounts, 6-digit codes).
(deftest jp-chart-backs-its-statement-defs
  (let [db @(jp/create-jp-db)]
    (is (= [] (dangling-exact db jp-pnl/definition)))
    (is (= [] (dangling-exact db jp-bs/definition)))
    (is (= [] (uncovered-codes db jp-bs/definition asset+liab+eq)))
    (is (= [] (uncovered-codes db jp-pnl/definition income+expense)))))

;; AU chart (43 accounts, 5-digit codes).
(deftest au-chart-backs-its-statement-defs
  (let [db @(au/create-au-db)]
    (is (= [] (dangling-exact db au-pnl/definition)))
    (is (= [] (dangling-exact db au-bs/definition)))
    (is (= [] (uncovered-codes db au-bs/definition asset+liab+eq)))
    (is (= [] (uncovered-codes db au-pnl/definition income+expense)))))

;; BR chart (53 accounts, dotted CPC codes). pnl uses exact leaf codes.
(deftest br-chart-backs-its-statement-defs
  (let [db @(br/create-br-db)]
    (is (= [] (dangling-exact db br-pnl/definition)))
    (is (= [] (dangling-exact db br-bs/definition)))
    (is (= [] (uncovered-codes db br-bs/definition asset+liab+eq)))
    (is (= [] (uncovered-codes db br-pnl/definition income+expense)))))

;; IN chart (96 accounts, 6-digit codes).
(deftest in-chart-backs-its-statement-defs
  (let [db @(in/create-in-db)]
    (is (= [] (dangling-exact db in-pnl/definition)))
    (is (= [] (dangling-exact db in-bs/definition)))
    (is (= [] (uncovered-codes db in-bs/definition asset+liab+eq)))
    (is (= [] (uncovered-codes db in-pnl/definition income+expense)))))

;; MX chart (85 accounts, Código-Agrupador dotted codes).
(deftest mx-chart-backs-its-statement-defs
  (let [db @(mx/create-mx-db)]
    (is (= [] (dangling-exact db mx-pnl/definition)))
    (is (= [] (dangling-exact db mx-bs/definition)))
    (is (= [] (uncovered-codes db mx-bs/definition asset+liab+eq)))
    (is (= [] (uncovered-codes db mx-pnl/definition income+expense)))))

;; AT chart (RLG). pnl uses 5%/6% prefixes for expenses + exact 7xxx.
(deftest at-chart-backs-its-statement-defs
  (let [db @(at/create-at-db)]
    (is (= [] (dangling-exact db at-pnl/definition)))
    (is (= [] (dangling-exact db at-bs/definition)))
    (is (= [] (uncovered-codes db at-bs/definition asset+liab+eq)))
    (is (= [] (uncovered-codes db at-pnl/definition income+expense)))))

;; DE SKR04. The pnl (GKV) + Bilanz (Aktiva ⊎ Passiva) DELIBERATELY enumerate a
;; fuller SKR04 than the 51-account starter edn seeds (documented in
;; l10n-de/pnl.clj: "A code enumerated here that the shipped chart does not
;; carry is deliberate"). So dangling is EXPECTED; the invariant that must hold
;; is that no SHIPPED account is left uncovered — that is the real defect the
;; DE coverage check guards, and it holds.
(deftest de-chart-no-uncovered-accounts
  (let [conn (de/create-de-db)
        db @conn
        bilanz {:statement/name "Bilanz"
                :statement/sections (into (vec (:statement/sections de-bs/aktiva-definition))
                                          (:statement/sections de-bs/passiva-definition))}]
    (is (= [] (uncovered-codes db de-pnl/gkv-definition income+expense)))
    (is (= [] (uncovered-codes db bilanz asset+liab+eq)))
    ;; sanity: the deliberate fuller-chart dangling is real (non-empty), so
    ;; this test is genuinely asserting the uncovered=[] property, not a
    ;; vacuous chart==def match.
    (is (seq (dangling-exact db de-pnl/gkv-definition)))))

;; CN Bilanz side is fully covered (62 exact codes all resolve, none uncovered).
;; The P&L gap is pinned separately below.
(deftest cn-balance-sheet-backs-its-def
  (let [db @(cn/create-cn-db)]
    (is (= [] (dangling-exact db cn-pnl/definition)))
    (is (= [] (dangling-exact db cn-bs/definition)))
    (is (= [] (uncovered-codes db cn-bs/definition asset+liab+eq)))))

;; ===========================================================================
;; PENDING — genuine coverage gaps
;; ===========================================================================

;; FIXED (note 197): l10n-uk now ships a nominal-ledger starter chart
;; (kontor.l10n-uk.chart), installed by create-uk-db, covering every exact code
;; the shipped Companies-Act-2006 Sch-1 Format-1 P&L + Balance Sheet reference —
;; so the UK module's own reports work against its own preset out of the box.
(deftest uk-preset-ships-no-chart
  (let [db @(uk/create-uk-db)]
    (is (= [] (dangling-exact db uk-pnl/definition))
        "UK P&L exact codes resolve to shipped accounts")
    (is (= [] (dangling-exact db uk-bs/definition))
        "UK balance-sheet exact codes resolve to shipped accounts")
    (is (pos? (count (present-codes db)))
        "create-uk-db installs a nominal-ledger chart")))

;; FIXED (note 197): the PCG skeleton now ships 44567 (Crédit de TVA à reporter)
;; and 44583 (Remboursement de TVA demandé) — the two VAT-asset codes the Bilan
;; asset line B.3 references — so an exporter / capex-heavy French book in a
;; VAT-credit or refund-requested position has a shipped account for the credit.
(deftest fr-bilan-vat-credit-accounts-absent
  (let [db @(fr/create-fr-db)]
    ;; the two VAT-asset codes the Bilan references but the chart omits:
    (is (resolves? db "44567") "44567 Crédit de TVA à reporter should ship")
    (is (resolves? db "44583") "44583 Remboursement de TVA demandé should ship")
    ;; grounding: their siblings on the same B.3 line DO ship
    (is (resolves? db "44562"))
    (is (resolves? db "44566"))
    (is (resolves? db "44581"))
    ;; so the FR balance-sheet def has dangling exact codes
    (is (= [] (dangling-exact db fr-bs/definition)))))

;; PENDING(NEW): l10n-cn ships accounts 4101 (生产成本 / production cost) and
;; 4105 (制造费用 / manufacturing overhead) with :kontor.account/type :expense,
;; but NO line of the CN income statement (cn.pnl) covers them. A period cost
;; FIXED (note 197): 4101/4105 are 成本类 WIP cost-gathering accounts that
;; capitalise into inventory and clear into 营业成本 — an ASSET, not a P&L
;; expense (ASBE). Retyped :expense → :asset (so the income statement no longer
;; leaves them uncovered) and carried on the Balance Sheet 存货 (inventory) line.
(deftest cn-pnl-omits-manufacturing-cost-accounts
  (let [db @(cn/create-cn-db)]
    (is (= [] (uncovered-codes db cn-pnl/definition income+expense))
        "no income/expense account is left uncovered by the P&L")
    ;; grounding: they still ship, now typed :asset (WIP inventory)
    (is (resolves? db "4101"))
    (is (resolves? db "4105"))))
