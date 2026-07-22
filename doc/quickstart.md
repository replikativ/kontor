# Quickstart — a DE GmbH year in 5 minutes

This page is a REPL transcript you can paste from the top. It books
a full year for a small German UG (haftungsbeschränkt), runs the DE
CIT provider on the result, and posts the year-end tax provision —
all through the kernel discipline that every business write goes
through `kontor.book/entry!`, which routes through the validation
gate (ADR-068).

The same path that ships [Acme UG's year here is regression-tested
end-to-end](../test/kontor/integration/cross_border_scenario_test.clj)
in `kontor.integration.cross-border-scenario-test`. If you change one
of the snippets below and it breaks, the integration test will tell
you which assertion regressed.

For *why* the kernel makes the choices it does, read
[`decisions.md`](decisions.md) next. For the developer-facing
programming model (the bitemporal + status-machine + transact-gate
substrate), read [`programming.md`](programming.md).

## 1. Boot a DE preset

`kontor.l10n-de.preset/create-de-db` is the one call that gets you
from nothing to a working DE accounting DB: in-memory datahike +
kernel schema + EUR commodity + DE journals (GJ / CR / CD / SJ / PJ)
+ SKR04 chart of accounts + ADR-101 statute parameters for KSt +
Soli + GewSt + CGT + investment income.

```clojure
(require '[datahike.api :as d]
         '[kontor.book :as book]
         '[kontor.l10n-de.preset :as de]
         '[kontor.l10n-de.cit-provider :as de-cit]
         '[kontor.l10n-de.pnl :as de-pnl]
         '[kontor.l10n-de.bs :as de-bs]
         '[kontor.period-tax-provider :as ptp]
         '[kontor.trial :as trial])

(def conn (de/create-de-db))
```

## 2. Declare the entity

Every posting in kontor is stamped with the legal entity it belongs
to — sum-to-zero is enforced per `(entity × ledger × commodity)`
triple (ADR-031), which is what lets one datahike connection hold
multiple legal entities (parent + subsidiaries) without losing
audit discipline.

```clojure
(d/transact conn
  [{:kontor.entity/name "Acme UG (haftungsbeschränkt)"
    :kontor.entity/code "ACME-UG"
    :kontor.entity/country "DE"
    :kontor.entity/legal-form "UG (haftungsbeschränkt)"
    :kontor.entity/functional-commodity [:kontor.commodity/symbol "EUR"]}])
```

## 3. Book a year

`kontor.book/entry!` builds a balanced, sealed transaction and
routes it through `transact-with-validation`. Positive `:amount`s
are debits, negatives are credits, and the legs must sum to zero
per the entity/ledger/commodity triple — the gate fails fast if not.

Below: an opening capital injection, a single Beratung invoice at
€40k + 19 % USt, and a one-shot summarized Jahresopex of €15k +
Vorsteuer. The numbers are the same ones the integration test
asserts on, so you can copy-paste and verify.

```clojure
(defn e [opts]
  (book/entry! conn (assoc opts
                           :commodity :EUR
                           :entity [:kontor.entity/code "ACME-UG"])))

;; Opening Bank ← Privateinlage
(e {:journal [:kontor.journal/code "GJ"] :effective-date #inst "2026-01-02"
    :narration "Eröffnungsbilanz Bank"
    :postings [{:account [:kontor.account/path "Umlaufvermögen:Bank"]           :amount  25000M}
               {:account [:kontor.account/path "Eigenkapital:Privateinlagen"]   :amount -25000M}]})

;; Service revenue €40k + USt 7,600 → Bank 47,600
(e {:journal [:kontor.journal/code "CR"] :effective-date #inst "2026-06-30"
    :narration "Beratung Kunde X H1 2026"
    :postings [{:account [:kontor.account/path "Umlaufvermögen:Bank"]                :amount  47600M}
               {:account [:kontor.account/path "Erträge:Erlöse:19%"]                 :amount -40000M}
               {:account [:kontor.account/path "Verbindlichkeiten:Umsatzsteuer:19%"] :amount  -7600M}]})

;; Opex 15k (Miete 5k + Stb 4k + Sonstige 6k) + Vorsteuer 2,850 → Bank −17,850
(e {:journal [:kontor.journal/code "CD"] :effective-date #inst "2026-12-15"
    :narration "Jahresopex 2026 (zusammengefasst)"
    :postings [{:account [:kontor.account/path "Aufwendungen:Raum:Miete"]           :amount   5000M}
               {:account [:kontor.account/path "Aufwendungen:Steuerberater"]        :amount   4000M}
               {:account [:kontor.account/path "Aufwendungen:Sonstige"]             :amount   6000M}
               {:account [:kontor.account/path "Umlaufvermögen:Vorsteuer:19%"]      :amount   2850M}
               {:account [:kontor.account/path "Umlaufvermögen:Bank"]               :amount -17850M}]})
```

## 4. Read it back — GuV + Bilanz

```clojure
(def fy-2026 {:from #inst "2026-01-01" :through #inst "2026-12-31"})

;; GuV — no tax booked yet, so the Jahresüberschuss (§ 275 Abs. 2 Nr. 17)
;; is still €40k revenue − €15k expenses = €25k
(-> (de-pnl/compute conn fy-2026) :statement/total :amount)
;; => 25000M

;; A correct book always has a zero trial balance — that *is* the
;; double-entry invariant.
(trial/balanced? (trial/trial-balance conn))
;; => true
```

`:through` is the inclusive-end window sugar. Default `:as-of-valid`
is `nil`, which means "show everything regardless of valid-time" —
future-dated entries are not silently filtered.

## 5. Run the DE CIT provider on the result

`kontor.l10n-de.cit-provider` is a `PeriodTaxProvider` (ADR-099)
that computes KSt + Soli + GewSt from a `:book-profit` input by
folding ADR-101 `:provision`s in priority order. The Hebesatz is
the per-municipality GewSt multiplier — pass it via `:tax-unit`.

```clojure
(def facts
  (ptp/period-tax-facts (de-cit/de-cit-provider {})
    {:db (d/db conn)
     :entity   [:kontor.entity/code "ACME-UG"]
     :period   {:from #inst "2026-01-01" :to #inst "2027-01-01"}
     :tax-unit {:hebesatz 490}
     :inputs   {:book-profit 25000M}}))

(sort (map #(-> % :liability :amount) (:components facts)))
;; => (3956.25000M 4287.5000M)
;;     ^^^^^^^^^^   ^^^^^^^^
;;     KSt+Soli     GewSt (15 % × Hebesatz/100 × book-profit)
```

The exact numbers match the integration test
(`de-ug-year-end-numbers-match-hand-calculation`), which carries the
hand-calculation as a docstring on the deftest.

## 6. Post the year-end provision

Provider output goes back through the same `book/entry!` gate as
every other business write — there is no special "tax write" path.

```clojure
(e {:journal [:kontor.journal/code "GJ"] :effective-date #inst "2026-12-31"
    :narration "Steuerrückstellung 2026"
    :postings [{:account [:kontor.account/path "Aufwendungen:Steuern:KSt"]              :amount  3956.25M}
               {:account [:kontor.account/path "Aufwendungen:Steuern:GewSt"]            :amount  4287.50M}
               {:account [:kontor.account/path "Verbindlichkeiten:Steuern:KSt-Rückstellung"]   :amount -3956.25M}
               {:account [:kontor.account/path "Verbindlichkeiten:Steuern:GewSt-Rückstellung"] :amount -4287.50M}]})
```

The book is now closed. The trial balance is still zero, the Bilanz
balances, and the GuV reports both figures § 275 Abs. 2 distinguishes:

```clojure
(-> (de-pnl/compute conn fy-2026) :de.pnl/ergebnis-vor-steuern :amount)  ; => 25000M
(-> (de-pnl/compute conn fy-2026) :de.pnl/jahresueberschuss :amount)     ; => 16756.25M
```

`:statement/total` is the Jahresüberschuss (§ 275 Abs. 2 Nr. 17) — the
statutory bottom line. `Ergebnis vor Steuern` is a § 265 Abs. 5
voluntary subtotal, not a § 275 position; it is exposed because it is
the meaningful figure for an Einzelunternehmen, whose owner's income tax
is a private matter rather than a company expense.

The €8,243.75 of provisions sits on the Passivseite under
§ 266 Abs. 3 B.2 Steuerrückstellungen, ready for payment in 2027.

## Where next

- [`doc/decisions.md`](decisions.md) — distilled architecture
  decisions (~30 entries). Locked design choices behind the snippets
  above.
- [`doc/programming.md`](programming.md) — the transact gate,
  status machines, and the bitemporal substrate as a unified model.
- [`test/kontor/integration/cross_border_scenario_test.clj`](../test/kontor/integration/cross_border_scenario_test.clj)
  — the regression test for this walkthrough, plus the cross-border
  dividend (DE UG → CA personal) via `kontor.treaty.de-ca`. Read it
  as worked-example code.
- [`doc/showcases/`](showcases/) — six longer Clay notebooks:
  DE B2B Factur-X (01), US multi-state SaaS (02), IN B2B IRN+TDS
  (03), multi-entity intercompany (04), Apple 10-K bitemporal
  restatement (05), and the full multi-year DE GmbH with backdated
  correction (06).
- Tax, FX, commitments, payroll, per-country localization, e-invoice
  emission, bank-statement import: companion modules under
  `modules/`. See [`doc/architecture.md`](architecture.md).
