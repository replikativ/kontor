# Research note 170 — Composition and API surface map

Research-before for the v1 publishability arc. Two notes already inventory
what exists at the data / kernel level — **168** (tax coverage across the
substrate) and **169** (substrate coverage, in flight). This note asks the
companion question every potential consumer asks:

> "If I `git clone kontor`, what do I actually CALL, and in what ORDER, to
> get a closed set of books with a computed tax provision?"

The audience is a Clojure developer skimming the README before deciding
whether to wire kontor into beleg / simmis / their own app. The deliverable
is a single tiered map of the public surface, the canonical call sequences
for the workflows kontor will most commonly serve, and an honest list of
where the surface is still rough.

This is a SURVEY note — no code change, no benchmark, no design proposal.
It is the precondition for a credible README + a one-paragraph "kontor in
30 lines" Quickstart.

---

## §1 — The public API surface (what a consumer calls)

Three tiers. Tier 1 is what every consumer touches in their first day with
kontor. Tier 2 is the workflow-specific layer (tax / FX / consolidation /
asset / inventory / disposal — pick the ones your domain needs). Tier 3 is
advanced — saga primitives, retention sweeps, the explain graph, DSAR
collection.

For each: namespace + fn + one-line purpose + canonical call shape + file
citation.

### Tier 1 — every consumer

These nine surfaces are the spine of the first session a developer has with
kontor. The Quickstart sketch in §6 is built from this tier alone.

#### `kontor.core/create-test-db`
**Open an in-memory book with the kernel schema installed.**
`src/kontor/core.clj:109`

```clojure
(def conn (kontor.core/create-test-db))
;; → connection ready for d/transact; ~50 ms via CoW branching off
;;   a JVM-wide template (note 96 / note 106).
```

#### `kontor.l10n-XX.preset/install-all!`
**One-call install of a jurisdiction stack** — chart + journals + commodity
+ tax statutes (CIT + CGT + investment-income, in dependency order). All
12 jurisdictions ship this entry per #349 (T1.W1.2). Idempotent.

`modules/l10n-{at,au,br,ca,cn,de,fr,in,jp,mx,uk,us}/src/kontor/l10n_XX/preset.clj` — see
e.g. `modules/l10n-de/src/kontor/l10n_de/preset.clj:44`

```clojure
(require '[kontor.l10n-de.preset :as de])
(def conn (de/create-de-db))      ; create-test-db + install-all!
;; or, on a connection you opened yourself:
(de/install-all! conn)
```

Each preset also exports a `create-XX-db` 1-call helper that wraps
`(create-test-db)` + `install-all!` for tests / scripts
(`modules/l10n-de/src/kontor/l10n_de/preset.clj:55`).

#### `kontor.book/entry!` + 8 verbs
**The headline consumer API for posting a balanced transaction.**
ADR-095. Verb facade over `kontor.posting/post-transaction!`. Each verb
bakes in a `:journal/type` and carries a teaching docstring. The signature
is uniform: one options map with `:debit-account` / `:credit-account` /
`:amount` / `:commodity` / `:effective-date` (+ `:entity` / `:partner` /
`:narration` / `:external-id` as needed). For multi-leg entries pass
`:postings`.

`src/kontor/book.clj:245` (`entry!`) + verbs at `:274` (`receive!`), `:282`
(`pay!`), `:290` (`sell!`), `:298` (`buy!`), `:305` (`receive-payment!`),
`:314` (`pay-bill!`), `:321` (`transfer!`), `:328` (`adjust!`); plus the
equity-distribution pair at `:343` (`declare-dividend!`) and `:361`
(`distribute-dividend!`).

```clojure
(book/sell! conn
  {:debit-account  [:account/path "Assets:Receivable"]
   :credit-account [:account/path "Income:Sales"]
   :amount         1000 :commodity :EUR
   :effective-date #inst "2026-03-05"})
```

The pure `entry-tx-data` builder behind every verb is at `:234` —
composable into a `kontor.process` step list.

#### `kontor.balance/account-balance`
**Read a single account's per-commodity balance** bitemporally.
`src/kontor/balance.clj:111`

```clojure
(balance/account-balance conn account-eid)
;; bitemporal override:
(balance/account-balance conn account-eid
                         {:as-of-valid #inst "2026-03-31"
                          :as-of-tx    #inst "2026-03-31"
                          :entity      [:entity/code "DE-GMBH"]})
;; → {<commodity-eid> Money, ...}
```

Default `:as-of-valid` = nil ⇒ all valid time (note 160 §I-17 fix —
includes future-dated postings; previous default silently dropped
forward-dated accruals).

#### `kontor.trial/trial-balance`
**The whole book, per-account per-commodity, with `balanced?` predicate.**
`src/kontor/trial.clj:25` + `:55`

```clojure
(def tb (trial/trial-balance conn {:entity [:entity/code "DE-GMBH"]}))
(trial/balanced? tb)    ; → true iff every commodity totals to zero
```

#### `kontor.report/marginalize`
**The quotient-epimorphism σ_E (ADR-096 / note 97 §3): partition postings
by any axis and sum each class.** This IS the generic report engine —
P&L, BS, segment, cost-center reports are all `marginalize` calls over
different axes.

`src/kontor/report.clj:230` (the function); the engine multimethod is at
`:256` (`run-engine`) with built-in `:account-codes` / `:tax-tags` /
`:dimension` engines.

```clojure
(-> (report/report-postings conn)
    (report/marginalize :account-type {:sign :raw})
    (update-vals (comp :amount :value)))
;; → {:asset 400M :expense 600M :income -1000M ...}

;; Marginalize over an ADR-097 :posting/dimensions axis:
(report/marginalize postings :cost-center)
```

#### `datahike.api/transact` (raw schema setup)
**Used directly for chart / commodities / entities / partners / journals.**
Not a kontor abstraction — there's no helper here because there's nothing
to abstract. A consumer transacts plain datahike data for static setup.

```clojure
(d/transact conn [{:commodity/symbol "EUR" :commodity/precision 2}
                  {:entity/code "DE-GMBH" :entity/country "DE"
                   :entity/functional-commodity [:commodity/symbol "EUR"]}
                  ...])
```

#### `kontor.entity/family` + neighbors
**Walk the entity tree (parent / children / siblings / family) for
multi-entity reporting and ADR-031 per-(entity, ledger, commodity)
sum-to-zero filtering.**
`src/kontor/entity.clj:27` (`by-code`), `:51` (`parent`), `:73`
(`children`), `:82` (`descendants`), `:94` (`family`).

#### `kontor.bitemporal/with-vt` + `commit-tx-eid` + `close-validity!`
**The bitemporal write helpers consumers reach for when correcting prior
periods.** `src/kontor/bitemporal.clj:47` (`with-vt`), `:87`
(`commit-tx-eid`), `:151` (`close-validity!`). See showcase 06 for the
worked example of "correct a Y1 expense from Y2 without losing the Y1 view"
— `doc/showcases/06_de_gmbh_multi_year.clj`.

### Tier 2 — workflow-specific

#### Period close — `kontor.period`
`src/kontor/period.clj:350` (`close!`), `:411` (`seal!`), `:440`
(`reopen!`), `:271` (`default-pre-close-checks`).

```clojure
(period/close! conn period-eid)
;; or with a custom pre-check policy:
(period/close! conn period-eid {:pre-checks my-checks :at #inst "..."})
```

`close!` is soft (`:period/locked-at`); `seal!` is irrevocable
(`:period/sealed-at`); `reopen!` cancels a soft close. All three route
through `transact-with-validation` and have `*-tx-data` companions per
ADR-068 (`close-tx-data` at `:318`, `seal-tx-data` at `:375`,
`reopen-tx-data` at `:423`).

#### Tax — `period-tax-facts` + `provision-tx-data` + `payment-tx-data`
The two-stage period-tax assembly (ADR-099).
`src/kontor/period_tax_provider.clj:134` (protocol fn), `src/kontor/tax_return_posting_builder.clj:43` (`provision-tx-data`), `:48` (`payment-tx-data`).

```clojure
(require '[kontor.l10n-de.cit-provider :as de-cit]
         '[kontor.period-tax-provider :as ptp]
         '[kontor.tax-return-posting-builder :as trb])

(def facts
  (ptp/period-tax-facts (de-cit/de-cit-provider {})
    {:db (d/db conn) :entity [:entity/code "DE-GMBH"]
     :period {:from #inst "2026-01-01" :to #inst "2027-01-01"}
     :tax-unit {:hebesatz 490}
     :inputs  {:book-profit 25000M}}))

(def builder (trb/make-static-tax-return-posting-builder
              {:expense-account [:account/path "Aufwendungen:Steuern:KSt"]
               :payable-account [:account/path "Verbindlichkeiten:Steuern:KSt-Rückstellung"]
               :journal         [:journal/code "GJ"]
               :commodity       [:commodity/symbol "EUR"]}))

(d/transact conn (trb/provision-tx-data builder facts
                   {:effective-date #inst "2026-12-31"}))
(d/transact conn (trb/payment-tx-data  builder facts
                   {:amount (:amount (ptp/total-liability facts))
                    :date   #inst "2027-04-15"}
                   {}))
```

For VAT/GST: `src/kontor/vat_return.clj:37` (`compute-vat-return`), `:55`
(`vat-return-tx-data`). For sole-prop folding business net onto a personal
return: `src/kontor/sole_proprietor.clj:25` (`business-net`), `:43`
(`business-income-input`).

For *transaction-incident* taxes (VAT lines on invoices, sales tax on a
US sale) the sibling protocol is `kontor.tax-rate-provider/TaxRateProvider`
+ `kontor.tax-posting-builder/TaxPostingBuilder` (ADR-071); see
`src/kontor/tax_rate_provider.clj`.

#### FX — `kontor.fx`
`src/kontor/fx.clj:50` (`convert`), `:119` (`translate-money-seq`), `:137`
(`translate-amounts-by-commodity`), `:170` (`to-functional-currency`).
The rate side lives in `kontor.fx-rate-provider/FxRateProvider`; the
substrate ships no rates — consumer provides one (ECB / OANDA / static).

```clojure
(fx/convert eur-money provider
  {:to "CAD" :at-date #inst "2026-12-31" :rate-type :closing})
```

#### Consolidation — `kontor.consolidation`
`src/kontor/consolidation.clj:130` (`translate-trial-balance-tx-data`),
`:328` (`eliminate-intercompany-pair-tx-data`), `:394`
(`consolidate-tx-data`), `:554` (`consolidate!`).

```clojure
(consolidation/consolidate!
  {:conn parent-conn :at-date #inst "2026-12-31"
   :elimination-entity [:entity/code "ELIM"]
   :journal            [:journal/code "GJ"]
   :pair-ids           #{[:partner/code "SUB-1" :partner/code "PARENT"] ...}
   :fx-provider        ecb-provider
   :present-commodity  [:commodity/symbol "USD"]})
```

Runs the whole consolidation cycle as one `kontor.process` — any
validation failure rolls back the lot.

#### Asset lifecycle — `kontor.asset.asset` + `kontor.asset.runner`
`modules/asset/src/kontor/asset/asset.clj:136` (`acquire!`), `:177`
(`place-in-service!`), `:249` (`dispose!`), `:318` (`transfer!`), `:375`
(`impair!`), `:421` (`revalue!`), `:467` (`revise-useful-life!`), `:508`
(`record-addition!`). Each has a paired `*-tx-data` builder.

`modules/asset/src/kontor/asset/runner.clj:60` (`run-depreciation!`) +
`:219` (`catch-up!`). Provider plug-point is
`kontor.asset.depreciation-provider/DepreciationProvider` (ADR-055).

#### Inventory — `kontor.inventory.ops`
`modules/inventory/src/kontor/inventory/ops.clj:79` (`receive!`), `:264`
(`issue!`), `:497` (`transfer!`), `:559` (`complete-transfer!`), `:406`
(`true-up-negative-fill!`). Each with `*-tx-data` builder. Costing flows
through `kontor.costing-provider/CostingProvider` (ADR-029); FIFO/FEFO
implementations ship.

#### Disposal — `kontor.disposal`
`modules/disposal/src/kontor/disposal.clj:179` (`record-disposal!`), `:224`
(`recognize!`), `:264` (`void!`), `:276` (`disposals-of`), `:291`
(`disposals-in-period`), `:333` (`realized-gain`), `:347`
(`realized-gain-summary`). The CGT providers depend on
`kontor.disposal-source/DisposalSource` (ADR-103) — companion ships
`kontor.disposal.source/DatahikeDisposalSource`.

#### Commitments — `kontor.commitment`
`modules/commitment/src/kontor/commitment.clj:145` (`record-commitment!`),
`:216` (`fulfill!`), `:250` (`cancel!`), `:275` (`aging`), `:68`
(`open-commitments`). The AR/AP aging primitive (ADR-098).

#### Incorporation — `kontor.incorporation`
`src/kontor/incorporation.clj:340` (`incorporate!`), `:205`
(`incorporate-tx-data`). Single-DB Shape A — founder + corp in one DB,
4-leg balanced + 0+ disposal rows.

#### Financial statements — `kontor.financial-statements`
`src/kontor/financial_statements.clj:122` (`compute-statement`), `:223`
(`compute-cash-flow`), `:272` (`compute-equity-changes`). Generic BS / P&L
generator over the report engine; the per-jurisdiction GuV / Bilanz
modules (`kontor.l10n-de.pnl`, `kontor.l10n-de.bs`) are thin wrappers.

### Tier 3 — advanced

#### `kontor.process/run-process`
**Multi-step transactional process — pure step list → one atomic gated
commit.** `src/kontor/process.clj:110`. The composition primitive every
companion's multi-write orchestrator runs through (ADR-067).

#### `kontor.side-effect.cross/CrossTxRouter` + `drain!`
**Cross-DB saga primitive** (ADR-074) — emit a `:side-effect-intent` on
DB A, drain commits it on DB B with idempotent step-ids.
`src/kontor/side_effect/cross.clj:98` (protocol), `:152`
(`cross-tx-intent-tx-data`), `:216` (`execute-one!`), `:263` (`drain!`).

#### `kontor.audit-doc/create-doc!` + `attach-supporting-doc!`
ADR-038 + ADR-051. Document a transaction with attached source (PDF
invoice, contract, etc.) + privilege classification.
`src/kontor/audit_doc.clj:142` (`create-doc!`), `:171`
(`attach-supporting-doc!`), `:279` (`reclassify-privilege!`), `:344`
(`visible-to?`), `:361` (`filter-by-privilege`).

#### `kontor.legal-hold/place!` + `release!`
ADR-049 — declare a legal hold over a scope query, blocking destructive
writes. `src/kontor/legal_hold.clj:480` (`place!`), `:538` (`release!`),
`:257` (`active-holds`), `:285` (`entities-held?`).

#### `kontor.retention/sweep!` + `apply-expiry!`
ADR-050 — retention policy expiry sweep. `src/kontor/retention.clj:321`
(`sweep!`), `:351` (`apply-expiry!`), `:401` (`sweep-and-apply!`), `:127`
(`policy-for`), `:211` (`retention-deadline`).

#### `kontor.dsar/collect` + `file-request!` + `advance-state!`
ADR-052 — GDPR/CCPA Data Subject Access Request collector.
`src/kontor/dsar.clj:309` (`collect`), `:402` (`file-request!`), `:469`
(`advance-state!`).

#### `kontor.statute/apply-provisions`
ADR-101 — the statute-as-data evaluator. Per-jurisdiction CIT / CGT
providers (ADR-104..107, ADR-103) build their TaxReturnFacts on top of
this. Most consumers use it through a provider, not directly.

#### `kontor.explain/explain-balance` + `explain-posting`
ADR-091 — McComb-aligned "explain this number" graph walks.
`src/kontor/explain.clj`. Pure read-only datalog returning plain Clojure
maps; the "data outlives applications" surface.

#### `kontor.event-bus/register-handler!` + `commit-and-emit`
ADR-092 — in-process pub-sub on commit. `src/kontor/event_bus.clj`. The
adapter seam for downstream queues (Kafka / NATS) without coupling the
kernel.

#### `kontor.agent-tools` (catalogue)
`src/kontor/agent_tools.clj` — the server-agnostic tool catalogue for an
agent/LLM caller (note 94 §3.7).

---

## §2 — Canonical workflows: sequence diagrams in text

Eight workflows, ordered by consumer relevance. The first four are fleshed
out with the actual call shapes a consumer writes; the back four are
stubbed (the README likely covers the first four; the rest are extensions
the per-domain consumer reaches for as their workload demands).

### Workflow A — Single-entity DE GmbH year of operations *(POLISHED)*

The canonical "I want to run my UG's books in kontor" path. Mirrors
`test/kontor/integration/christian_scenario_test.clj` lines 88-138.

```clojure
(require '[kontor.l10n-de.preset :as de-preset]
         '[kontor.book :as book]
         '[kontor.trial :as trial]
         '[kontor.period-tax-provider :as ptp]
         '[kontor.tax-return-posting-builder :as trb]
         '[kontor.l10n-de.cit-provider :as de-cit])

;; 1. Open + install the DE stack (one call)
(def conn (de-preset/create-de-db))

;; 2. Register the entity + any extra accounts the SKR04 doesn't cover
(d/transact conn
  [{:entity/name "Hans-Tech UG (haftungsbeschränkt)"
    :entity/code "HANS-TECH-UG" :entity/country "DE"
    :entity/legal-form "UG (haftungsbeschränkt)"
    :entity/functional-commodity [:commodity/symbol "EUR"]}])

;; 3. Book the year's activity (verb facade, :entity stamped per ADR-031)
(def ug [:entity/code "HANS-TECH-UG"])
(def e  (fn [opts] (book/entry! conn (assoc opts :commodity :EUR :entity ug))))

;; opening capital
(e {:journal [:journal/code "GJ"] :effective-date #inst "2026-01-02"
    :narration "Eröffnungsbilanz"
    :postings [{:account [:account/path "Umlaufvermögen:Bank"]          :amount  25000M}
               {:account [:account/path "Eigenkapital:Privateinlagen"] :amount -25000M}]})
;; revenue + USt
(e {:journal [:journal/code "CR"] :effective-date #inst "2026-06-30"
    :narration "Beratung Kunde X H1"
    :postings [{:account [:account/path "Umlaufvermögen:Bank"]                   :amount  47600M}
               {:account [:account/path "Erträge:Erlöse:19%"]                    :amount -40000M}
               {:account [:account/path "Verbindlichkeiten:Umsatzsteuer:19%"]    :amount  -7600M}]})
;; opex + Vorsteuer (... etc ...)

;; 4. Read the books back (per-entity TB filter)
(def tb (trial/trial-balance conn {:entity ug}))
(assert (trial/balanced? tb))

;; 5. Compute the CIT (KSt + Soli + GewSt) via the DE provider
(def facts
  (ptp/period-tax-facts (de-cit/de-cit-provider {})
    {:db (d/db conn) :entity ug
     :period   {:from #inst "2026-01-01" :to #inst "2027-01-01"}
     :tax-unit {:hebesatz 490}
     :inputs   {:book-profit 25000M}}))

;; 6. Accrue the provision (Dr Steueraufwand / Cr Steuerrückstellung)
(def builder
  (trb/make-static-tax-return-posting-builder
    {:expense-account [:account/path "Aufwendungen:Steuern:KSt"]
     :payable-account [:account/path "Verbindlichkeiten:Steuern:KSt-Rückstellung"]
     :journal         [:journal/code "GJ"]
     :commodity       [:commodity/symbol "EUR"]}))

(d/transact conn (trb/provision-tx-data builder facts
                   {:effective-date #inst "2026-12-31"}))

;; 7. Remit later (Dr Steuerrückstellung / Cr Bank)
(d/transact conn (trb/payment-tx-data builder facts
                   {:amount 3956.25M :date #inst "2027-04-15"} {}))
```

This is the workflow most polished today: every step is one call, every
preset / builder / provider exists for DE, and the integration test
verifies the numbers to the cent. **It is the README quickstart.**

### Workflow B — Cross-border dividend (DE corp → CA personal) *(POLISHED)*

The "two-DB topology" that motivated Phase D (note 161). Mirrors
`test/kontor/integration/christian_scenario_test.clj:200-236` and
`modules/treaty-de-ca/`.

```clojure
(require '[kontor.l10n-de.preset :as de-preset]
         '[kontor.l10n-ca.preset :as ca-preset]
         '[kontor.book :as book]
         '[kontor.treaty.de-ca :as treaty])

;; 1. Two physical DBs
(def ug-conn   (de-preset/create-de-db))
(def hans-conn (ca-preset/create-ca-db))

;; 2. Set up entities + the CA-side foreign-tax accounts the treaty helper writes to
(d/transact hans-conn
  [{:entity/name "Christian (Individual)" :entity/code "CW-PERSONAL"
    :entity/country "CA" :entity/functional-commodity [:commodity/symbol "CAD"]}
   {:account/path "Income:Dividends:Foreign:DE"   :account/type :income
    :account/commodity [:commodity/symbol "CAD"]}
   {:account/path "Assets:Foreign-Tax-Prepaid"    :account/type :asset
    :account/commodity [:commodity/symbol "CAD"]}
   {:account/path "Assets:Foreign-Tax-Refundable" :account/type :asset
    :account/commodity [:commodity/symbol "CAD"]}])

;; 3. DE side — declare + distribute the dividend (per-posting :partner)
(book/declare-dividend! ug-conn ...)   ; per-shareholder Cr Dividenden-Payable
(book/distribute-dividend! ug-conn ...) ; net cash + KESt+Soli withholding

;; 4. CA side — one call, treaty split done for you
(treaty/receive-dividend-from-de! hans-conn
  {:gross-amount    9000M
   :withheld-amount 2373.75M
   :net-cash-amount 6626.25M
   :income-kind     :dividend-portfolio
   :fx-rate         1.50M
   :effective-date  #inst "2027-01-20"
   :payer-partner   [:partner/external-id "HT-UG"]
   :entity          [:entity/code "CW-PERSONAL"]})
;; → 4-leg balanced CAD entry:
;;   Dr Bank:CAD                       (net cash × FX)
;;   Dr Assets:Foreign-Tax-Prepaid     (treaty cap × FX, §126 FTC)
;;   Dr Assets:Foreign-Tax-Refundable  (over-treaty excess × FX, BZSt refund)
;;   Cr Income:Dividends:Foreign:DE    (gross × FX)
```

The two DBs don't need to talk during the dividend event — the receiver's
side is self-contained. If/when they need to (e.g. the CA side wants to
mark a corresponding "received" status against an emitted DE-side intent),
the saga primitive `kontor.side-effect.cross` is the path; for the basic
dividend flow it's not needed.

### Workflow C — Period close + tax provision *(POLISHED)*

```clojure
(require '[kontor.period :as period])

;; 1. Open the period (one-time setup at boot)
(d/transact conn [{:period/tag :calendar-month
                   :period/start #inst "2026-12-01"
                   :period/end   #inst "2026-12-31"
                   :period/journal [:journal/code "GJ"]}])

;; 2. Soft-close (runs pre-close-checks; throws on drafts in period etc.)
(period/close! conn period-eid)

;; 3. Compute taxes for the closed period (Workflow A steps 5-7)
(def facts (ptp/period-tax-facts (de-cit/de-cit-provider {}) ctx))
(d/transact conn (trb/provision-tx-data builder facts
                   {:effective-date #inst "2026-12-31"}))

;; 4. Hard-seal once filed (irrevocable; ADR-014)
(period/seal! conn period-eid)
```

### Workflow D — Asset purchase + depreciation *(POLISHED-ENOUGH)*

```clojure
(require '[kontor.asset.asset :as asset]
         '[kontor.asset.runner :as dep])

;; 1. Acquire the asset (kontor-asset companion)
(asset/acquire! conn
  {:asset/external-id "FA-2026-001"
   :asset/name        "Servers (HP DL380 ×4)"
   :asset/class       :it-equipment
   :asset/useful-life-months 60
   :asset/method      :straight-line
   :amount            20000M :commodity [:commodity/symbol "EUR"]
   :acquired-on       #inst "2026-03-15"
   :debit-account     [:account/path "Anlagevermögen:IT"]
   :credit-account    [:account/path "Verbindlichkeiten:Lieferanten"]})

;; 2. Place in service (separate event so the consumer can defer)
(asset/place-in-service! conn {:asset [:asset/external-id "FA-2026-001"]
                               :in-service-on #inst "2026-04-01"})

;; 3. Run depreciation for a period (provider plug-point)
(dep/run-depreciation! conn
  {:period {:from #inst "2026-04-01" :to #inst "2026-12-31"}
   :provider sl-provider                ; DepreciationProvider impl
   :journal  [:journal/code "GJ"]})
```

### Workflow E — Inventory cost-flow + COGS *(SKELETAL)*

`kontor-inventory` companion. `receive!` (intake at cost) → `issue!` (FIFO
/ FEFO consumption via `CostingProvider` → `Dr COGS / Cr Inventory`) →
`transfer!` between locations. See `modules/inventory/src/kontor/inventory/`.

### Workflow F — Sole-prop with VAT/GST return *(SKELETAL)*

`kontor.vat-return/compute-vat-return` + `vat-return-tx-data` for the
periodic remittance. `kontor.sole-proprietor/business-net` +
`business-income-input` to fold the business net onto a personal
income-tax provider's `:inputs`. The CA sole-prop side of Christian's
scenario uses this pattern.

### Workflow G — Group consolidation *(SKELETAL — note 167 substrate landed)*

`kontor.consolidation/consolidate!` runs translate-currency +
eliminate-intercompany + post in one process. ADR-073. Group-tax
consolidation (Organschaft / intégration fiscale / GILTI / etc.) is the
ADR-167 deferral — substrate landed in this T1 wave (gap #8).

### Workflow H — Incorporation event *(SKELETAL — ADR-102/103)*

`kontor.incorporation/incorporate!` builds the founder + corp opening
entries + 0+ `:disposal` rows for contributions where basis ≠ FMV.
Shape A (single-DB) shipped; Shape B (cross-DB saga) deferred.

---

## §3 — The composition primitives (how things chain)

### `*-tx-data` + `!` builder pattern (ADR-068)

The convention that makes everything compose:

- Every business write `foo!` has a paired pure `foo-tx-data` builder
  returning a vector of tx-data.
- The `!` wrapper routes the builder's output through
  `kontor.validation/transact-with-validation` (the gate — sealing +
  legal-hold + period-lock + state-machine + datalog invariants).
- A consumer wanting to compose several writes atomically calls the
  builders, concats the results, and transacts the lot once:

```clojure
(d/transact conn
  (into [] (concat
            (book/entry-tx-data sell-opts)
            (book/entry-tx-data deferred-rev-opts)
            (trb/provision-tx-data builder facts {:effective-date d}))))
;; one commit, one atomic boundary, one audit row
```

The reference test is
`test/kontor/composition_test.clj` (per #139, Stage P cross-module
composition test).

### `kontor.process/run-process` — multi-step monadic flatten

When the writes depend on each other (step 2 needs to read what step 1
just wrote), the consumer uses `run-process`. A *step* is `(db, ctx) →
result`; the speculative db reflects every prior step's tx-data. The
return shape allows a step to splice in more steps (the monadic flatten);
the engine threads them all into one tx-data vector and commits via the
gate. `src/kontor/process.clj:86` (`run-steps`), `:110` (`run-process`).

Used internally by every companion's multi-write orchestrator
(`consolidation/consolidate!`, `asset.runner/run-depreciation!`,
`lease/commence!`, the inventory flows). Available to consumers writing
their own.

### Status machines (ADR-034)

Every state-machine-tracked entity (invoice / payment / commitment /
disposal / legal-hold / dsar-request / asset / lease) advances via
`kontor.status-machine/record-status-change!`. The transition vocabulary
is data: `:status-transition` rows + `:status-history` rows + an optional
`:approval-policy` gate (ADR-038). A consumer querying history walks the
`:status-history` chain. New facets are seeded at install time per
companion (`legal-hold/install-seeds!`, `retention/install-seeds!`, etc.;
all called by `kontor.core/install-schema!` at `src/kontor/core.clj:79`).

### Bitemporal `:as-of-valid`

Every read on the substrate takes `:as-of-valid` and `:as-of-tx`. Default
post-I-17: nil ⇒ all valid time (includes future-dated postings — what
you usually want for simulations, accruals, dividend declarations against
forward dates). Override per call:

```clojure
(trial/trial-balance conn {:as-of-valid #inst "2026-03-31"
                           :as-of-tx    #inst "2026-04-15"})
;; "Show me the books as of March 31, as known on April 15"
```

For writes, `kontor.bitemporal/with-vt` stamps the valid time on a tx-data
vector. `kontor.process/run-process`'s `:vt-from` / `:vt-to` opts stamp
the whole process atomically.

### Sealing (ADR-007)

A posting transitions from draft to immutable by setting
`:posting/posted-at`. After that, **silent retraction is forbidden** by
`kontor.sealing/assert-no-silent-retracts!` (called from
`transact-with-validation`). Explicit `:db/purge` IS allowed — but is
itself a recorded commit that the audit chain documents.

### Concept IRIs (ADR-019 / ADR-090)

`:concept-iri` is the substrate seam to XBRL / FIBO / gist / private
taxonomies. Available across `:account`, `:partner`, `:commodity`, `:tax`,
`:document-type`, `:account-tag`. Queryable via
`kontor.explain/entities-with-concept-iri`.

---

## §4 — What you can't do today (consumer-facing surface gaps)

Honest assessment, tagged for the v1 ship and Christian's personal-use
scenario.

### P0 — would block a first-time consumer

- **F-G1 — `:as-of-valid` default change isn't documented.** The I-17 fix
  reversed the wall-clock-now default to nil; CLAUDE.md doesn't mention
  it, programming.md and quickstart.md don't either. A consumer who reads
  the old docstrings + a stale tutorial will be surprised. **Fix: doc
  sweep before v1.**
- **F-G2 — Trial-balance per-entity reporting works, but per-entity
  *financial-statement* generation (specifically: `kontor.l10n-de.pnl/compute`
  / `bs/compute-aktiva`) does not always scope by entity in the v1
  presets.** A consumer with multiple `:entity` rows in one DB calling
  `de-pnl/compute` gets a whole-DB GuV. The Christian scenario test
  worked because the UG was the only entity in its DB. **Fix: thread an
  `:entity` filter through the per-jurisdiction PnL/BS modules.**

### P1 — would frustrate a real consumer within their first session

- **F-G3 — Chart bootstrapping is uneven across the 12 jurisdictions.**
  DE (SKR04 ~44 accounts) and CA (~40 accounts) ship a usable starter
  chart in their preset. AT / AU / BR / CN / FR / IN / JP / MX / UK / US
  ship statutes + journals but a bare-bones chart. A consumer in those
  jurisdictions has to either (a) write their own chart, (b) lift one
  from a vendor doc, (c) accept the demo accounts only. **Fix: an
  "examples" chart per jurisdiction (note: NOT a definitive chart; that's
  a customer / accountant decision).**
- **F-G4 — No batch CSV importer for opening balances.** The substrate
  has `kontor-import-gleif` / `kontor-import-edgar` for filings + a
  per-bank CSV importer (`kontor-bank-{de,us,...}/`). It does NOT have a
  generic "here's my Q1 ledger as CSV" importer. A consumer migrating
  from another system writes ad-hoc `(d/transact)` calls. **Fix: a
  `kontor.import.opening-balance` helper that takes a CSV path + an
  account-mapping function.**
- **F-G5 — The `:entity` lifecycle is implicit.** Consumers transact
  entities by hand; there's no `kontor.entity/create!` builder, no
  `register!` doctor. Means consumers can omit
  `:entity/functional-commodity` and silently get FX-broken reports
  later. **Fix: a `kontor.entity/declare!` helper that validates the
  required attrs.**

### P2 — polish

- **F-G6 — No JSON serialisation.** Intentional per ADR-010 (no UI, no
  serialisation); document it explicitly so a consumer doesn't go
  hunting.
- **F-G7 — The two-DB scenario (`kontor.side-effect.cross`) isn't
  documented as the "how do I split corp from personal" path.** It IS
  the path; nothing in the showcases threads it. The treaty-de-ca module
  is a step toward the answer but doesn't use `cross.clj`. **Fix: a
  showcase demonstrating the cross-DB saga for a corp→personal
  status-mirror.**
- **F-G8 — No printable / formatted report.** `compute-statement` returns
  a Clojure map; pretty-printing it to a PDF / Org / HTML deliverable
  is the consumer's job. Acceptable for a kernel; document that beleg
  is the planned consumer of "render this to a customer-facing
  document".

### NOT A GAP — intentional

- No UI (ADR-010)
- No US sales-tax rate table (ADR-005 — protocol only)
- No bundled API keys (ADR-005)
- No second runtime (CLAUDE.md "What NOT to do")
- No silent posting retract (ADR-007)

---

## §5 — API stability classification

### Tier 1 stability

| Surface | Stable | Notes |
|---|---|---|
| `kontor.core/create-test-db` | **Stable** | Locked since H |
| `kontor.l10n-XX.preset/install-all!` | **Alpha** | Shipped #349; signature shape is stable, contents will grow (more statutes / charts) |
| `kontor.book/entry!` + 8 verbs | **Stable** | ADR-095, locked + tested at integration level |
| `kontor.book/declare-dividend!` / `distribute-dividend!` | **Alpha** | Newer (note 107 §2.6) |
| `kontor.balance/account-balance` | **Stable** | The `:as-of-valid` default IS the API breaking change to flag |
| `kontor.trial/trial-balance` | **Stable** | Same |
| `kontor.report/marginalize` | **Stable** | ADR-096, locked |
| `kontor.entity/family` + peers | **Stable** | ADR-031, locked |
| `kontor.bitemporal/with-vt` + `commit-tx-eid` + `close-validity!` | **Stable** | ADR-048; `commit-tx-eid` semantics caveat in programming.md |

### Tier 2 stability

| Surface | Stability | Notes |
|---|---|---|
| `kontor.period/close!` / `seal!` / `reopen!` | **Stable** | ADR-014 |
| `PeriodTaxProvider/period-tax-facts` | **Alpha** | ADR-099; `period-tax-kinds` enum stable, component-map shape may grow per note 168 / new providers |
| `TaxReturnPostingBuilder/provision-tx-data` / `payment-tx-data` | **Alpha** | StaticTax* impl is stable; tax-unit / inputs shape per-provider |
| `kontor.fx/convert` + `translate-money-seq` | **Stable** | ADR-072 |
| `kontor.consolidation/consolidate!` | **Alpha** | ADR-073; pair-id format may evolve |
| `kontor.asset.asset/*` lifecycle verbs | **Stable** | ADR-053..056 |
| `kontor.asset.runner/run-depreciation!` | **Stable** | |
| `kontor.inventory.ops/receive!` / `issue!` | **Stable** | Stage N |
| `kontor.disposal/record-disposal!` / `recognize!` | **Alpha** | ADR-102, the CGT entrypoint — opts may grow per non-DE/CA jurisdictions |
| `kontor.commitment/record-commitment!` / `fulfill!` / `aging` | **Stable** | ADR-098 |
| `kontor.incorporation/incorporate!` | **Alpha** | ADR-102 Shape A; Shape B deferred |
| `kontor.financial-statements/compute-statement` | **Stable** | |
| `kontor.vat-return/compute-vat-return` | **Alpha** | ADR-100 — recent, shape settling |
| `kontor.sole-proprietor/business-net` | **Alpha** | ADR-100 — recent |

### Tier 3 stability

| Surface | Stability | Notes |
|---|---|---|
| `kontor.process/run-process` | **Stable** | ADR-067 |
| `kontor.side-effect.cross/drain!` + `CrossTxRouter` | **Alpha** | ADR-074 — protocol stable, retry / DLQ semantics may extend |
| `kontor.audit-doc/*` | **Stable** | |
| `kontor.legal-hold/*` | **Stable** | |
| `kontor.retention/*` | **Stable** | |
| `kontor.dsar/*` | **Stable** | |
| `kontor.statute/apply-provisions` | **Alpha** | ADR-101 + addenda 1/2 — `:op` set is closed-by-ADR + growing |
| `kontor.explain/*` | **Experimental** | ADR-091 — graph-walk surface, may pivot on real consumer demand |
| `kontor.event-bus/*` | **Experimental** | ADR-092 — minimal in-proc, real consumers may want a more featureful bus |
| `kontor.agent-tools` | **Experimental** | New; gated on real MCP consumer |

---

## §6 — README-shaped quickstart sketch

A draft of what the README's "Quickstart" should look like. Roughly 50
LOC of pasteable Clojure, gets the reader from `git clone` to a computed
DE GmbH year-end tax provision. The actual README copy will iterate; this
is the shape:

````markdown
## Quickstart

Add the dependency (deps.edn):

```clojure
{:deps {io.replikativ/kontor {:mvn/version "0.1.0"}
        io.replikativ/kontor-l10n-de {:mvn/version "0.1.0"}}}
```

Then in the REPL:

```clojure
(require '[kontor.l10n-de.preset :as de]
         '[kontor.book :as book]
         '[kontor.trial :as trial]
         '[kontor.period-tax-provider :as ptp]
         '[kontor.tax-return-posting-builder :as trb]
         '[kontor.l10n-de.cit-provider :as de-cit]
         '[datahike.api :as d])

;; 1. Open a DE-preset book (EUR + SKR04 chart + journals + tax statutes)
(def conn (de/create-de-db))

;; 2. Declare your company
(d/transact conn
  [{:entity/name "Hans-Tech UG" :entity/code "HT-UG"
    :entity/country "DE" :entity/functional-commodity [:commodity/symbol "EUR"]}])

;; 3. Book a year of activity (verb facade)
(def ug [:entity/code "HT-UG"])
(book/entry! conn
  {:journal [:journal/code "CR"] :effective-date #inst "2026-06-30"
   :commodity :EUR :entity ug :narration "H1 consulting"
   :postings [{:account [:account/path "Umlaufvermögen:Bank"]                :amount  47600M}
              {:account [:account/path "Erträge:Erlöse:19%"]                 :amount -40000M}
              {:account [:account/path "Verbindlichkeiten:Umsatzsteuer:19%"] :amount  -7600M}]})

;; 4. Check the books balance
(trial/balanced? (trial/trial-balance conn {:entity ug}))   ; => true

;; 5. Compute the year-end CIT (KSt + Soli + GewSt)
(def facts
  (ptp/period-tax-facts (de-cit/de-cit-provider {})
    {:db (d/db conn) :entity ug
     :period   {:from #inst "2026-01-01" :to #inst "2027-01-01"}
     :tax-unit {:hebesatz 490}
     :inputs   {:book-profit 25000M}}))

(:components facts)   ; → [{... :kst+soli €3,956.25 ...} {... :gewst €4,287.50 ...}]
```

That's it. Eleven other jurisdictions (`kontor-l10n-{at,au,br,ca,cn,fr,
in,jp,mx,uk,us}`) all expose the same `install-all!` + verb-facade +
CIT/CGT/investment-income provider pattern.

For deeper context: [accounting-model.md](doc/accounting-model.md) on the
debit/credit translation, [programming.md](doc/programming.md) on the
transact gate + status machines + bitemporal substrate.
````

The shape works at 50 LOC because:
- One preset call covers schema + chart + journals + statutes.
- The verb facade is one fn call per entry, no manual `:transaction` /
  `:postings` map assembly.
- Trial-balance + `balanced?` is two calls.
- Tax computation is one provider call + reads the components map.

Gaps that the Quickstart exposes:
- The reader has to guess the SKR04 paths (`"Umlaufvermögen:Bank"`).
  Acceptable for a quickstart (a real consumer reads
  `kontor.l10n-de.chart` once); the README should link the chart source.
- The `:tax-unit {:hebesatz 490}` and `:inputs {:book-profit 25000M}` are
  jurisdiction-specific opaque inputs — the README links to
  `kontor.l10n-de.cit-provider`'s docstring for the schema.
- No mention of period close / sealing / FX. Those are the second
  paragraph of the README's "Going further" section.

---

## §7 — Synthesis: what's ready, what's gappy

**Ready for a first OSS-release consumer (Christian's personal use + the
GitHub publish):**
- The Tier 1 surface is locked and integration-tested.
- The DE + CA presets exercise every Tier 2 surface end-to-end.
- The Christian scenario test (cross-border dividend + treaty FTC + per-
  entity GuV) IS the existence proof.

**Gappy (will surface within day-1 of a real consumer):**
- Per-entity P&L / BS not threaded through the per-jurisdiction modules
  (F-G2, P0).
- 10 jurisdictions ship statutes but a bare chart (F-G3, P1).
- No CSV opening-balance import (F-G4, P1).
- `:as-of-valid` default-change underdocumented (F-G1, P0).

**Honestly experimental:**
- `kontor.explain` + `kontor.event-bus` + `kontor.agent-tools` are
  shipped surfaces but I haven't seen real consumer demand drive their
  shape; expect them to pivot.

**Out of scope (and the README should say so):**
- No UI / no JSON wire format / no bundled tax rates / no Avalara API
  keys / no second runtime.

The publishable v1 is one P0 fix + a Quickstart + the `:as-of-valid` doc
sweep away. The Tier 1/2 surface is in a usable shape; the friction is
documentation + the per-entity report scoping, not the kernel.
