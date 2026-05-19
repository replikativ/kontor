# Start here

This is the single-page on-ramp to kontor. If you have 10 minutes,
read this; then either run the showcase locally or jump to the
audience-specific docs at the bottom.

The goal of this document: by the time you finish reading, you should
understand **what kontor's bitemporal substrate buys you that a
traditional accounting system can't**, demonstrated against a real
business scenario.

## The setup — Acme Manufacturing GmbH

Acme is a synthetic German GmbH (München, HRB 123456). Two employees,
monthly DATEV payroll, the usual mix of customer invoices + supplier
bills + cash transactions. We're going to walk through three years
of Acme's books on top of kontor and pay attention to ONE specific
event: a misclassified expense that doesn't get caught until 18
months later.

This is the scenario in [showcase 06](showcases/06_de_gmbh_multi_year.clj).

## Year 1 — November 2026: the posting that's quietly wrong

Acme's Geschäftsführer takes a customer to dinner. The bookkeeper
posts the €1,200 expense:

```clojure
;; Posted 2026-11-22, effective-date 2026-11-22
{:posting/account [:account/code "4660"]  ; Reisekosten Arbeitnehmer
 :posting/amount 1200.00M
 :posting/commodity [:commodity/symbol "EUR"]
 :posting/narration "Geschäftsessen Kundenakquise"}
```

The kernel checks the posting balances + nothing's sealed + the period
is open + the journal accepts general entries. Everything passes.
The transaction is sealed.

**Except the posting is wrong.** Under §4(5) Nr. 2 EStG, business
meals are *Bewirtungskosten* (account 4650), not *Reisekosten*
(account 4660), and they're only 70% tax-deductible (not 100%). The
bookkeeper got it wrong. Y1 closes, the books look fine, the company
files its Y1 tax return showing €1,200 fully deductible.

## Year 2 Q4 — October 2027: the Steuerberater spots it

During the regular Jahresabschluss review, the Steuerberater catches
the misclassification. Two questions arise:

1. **What do the corrected Y1 books look like?** (Acme needs to file
   a UStVA correction + a §233a interest computation.)
2. **What did we report on the Y1 tax return?** (Acme needs to
   reconcile the restated books against what the tax authority has on
   file.)

In a traditional system, fixing the Y1 posting either *overwrites*
the original (losing what was reported) or creates a reversing-
plus-restatement pair in Y2 (mangling Y1's books for retroactive
reporting). Neither is great.

**kontor's substrate makes both views simultaneously available.**
The correction is a new write at a past valid-time, plus a closure
of the original posting's tx-window at the correction date:

```clojure
;; Close the original misclassified posting at the correction date.
;; close-validity! sets :db.valid/to = 2027-10-15 on the prior tx.
(kbt/close-validity! conn misclassified-tx-eid #inst "2027-10-15")

;; Record the corrected split (Bewirtungskosten 100% + 70% deductibility
;; metadata lives in the consumer's tax-prep layer), effective-date
;; still the original 2026-11-22, but :tx/valid-from = 2027-10-15.
(validation/transact-with-validation
 conn
 (kbt/with-vt
   (posting/post-transaction-tx-data
    {:transaction {:transaction/external-id "TX-DINNER-CORR"
                   :transaction/effective-date #inst "2026-11-22"
                   :transaction/narration "Bewirtungskosten correction Oct 2027"
                   :transaction/journal j-gen}
     :postings [{:posting/account [:account/code "4650"]  ; CORRECT account
                 :posting/amount 1200.00M
                 :posting/commodity eur}
                {:posting/account [:account/code "1000"]  ; Kasse
                 :posting/amount -1200.00M
                 :posting/commodity eur}]})
   #inst "2027-10-15"))
```

Now the two queries:

```clojure
;; "What did the Y1 books say at year-end 2026?" — original 10-K view
(d/q '[:find ?amt :in $ ?a :where [?p :posting/account ?a] [?p :posting/amount ?amt]]
     (d/valid-at (d/db conn) #inst "2026-12-31")
     (ref-eid (d/db conn) :account/code "4660"))
;; => #{[1200.00M]}   — the original misclassified posting

;; "What do the restated Y1 books say now?" — post-correction view
(d/q '[:find ?amt :in $ ?a :where [?p :posting/account ?a] [?p :posting/amount ?amt]]
     (d/valid-at (d/db conn) #inst "2027-11-01")
     (ref-eid (d/db conn) :account/code "4650"))
;; => #{[1200.00M]}   — the corrected posting; #4660 is empty
```

**Both views are first-class.** The Steuerberater can show the tax
authority what was reported AND what should have been reported, and
the substrate guarantees the audit chain documents the correction
event (when it landed, who recorded it, what the original looked
like).

This is the bitemporal substrate's promise. The same machinery
handles:

- **Apple's actual 2009 10-K → 2010 10-K/A restatement** on real SEC
  EDGAR data ([showcase 05](showcases/05_apple_10k_bitemporal.clj)).
  AccruedLiabilities went from $3.719B to $4.224B (+$505M); the
  substrate records both.
- **Quarterly comparative restatement** when a 10-Q restates a prior
  period's value.
- **Inventory cost-method change** (FIFO → WeightedAverage) that
  retroactively adjusts cost-of-goods-sold.
- **Lease modification** (IFRS 16) that retroactively changes the
  right-of-use asset's measurement.

## Year 3 — June 2028: the employee terminates

Anna Schmidt (Acme's Vertriebsleiterin, promoted in Y2) terminates
2028-06-30. In September she files a DSAR (data-subject access
request) asking for her full employment record.

```clojure
;; Kernel-side DSAR walker — partner-rooted; reaches HR via the
;; extension collector (note 86 P1-86-5).
(dsar/collect (d/db conn) schmidt-partner-eid {})
;; => {:partner {...}
;;     :extensions {:hr {:employments [...] :compensations [...]}}}

;; people-record companion bundle — career history + reviews + promotions
(pr/dsar-bundle (d/db conn) schmidt-person-eid)
;; => {:positions [...] :reviews [...] :promotions [...]}
```

The same query infrastructure that powered the bitemporal correction
in Year 2 powers the GDPR Art. 15 access request in Year 3.

And the retention sweeper checks if Anna's DPIA from Y1 is eligible
for purge under the DE l10n's `DE-DSGVO-hr-monitoring-consent` policy
(10-year floor + archive-to-cold-storage action):

```clojure
(retention/eligible? (d/db conn) dpia-eid policy-eid {:as-of #inst "2028-09-15"})
;; => false   — within the 10-year retention window
```

DSGVO + BDSG §26 + GefStoffV + AO §147 + BetrVG §82-83 retention
periods are seeded by the `kontor-l10n-de` companion; the kernel
ships the shape; the sweeper applies eligibility checks against
each policy's `:triggered-by` anchor date.

## What just happened

You watched three years of a German GmbH's accounting on top of
kontor. Three substrate primitives carried the weight:

1. **Bitemporal `:db.valid/from` + `:db.valid/to`** for the
   correction story. `(d/valid-at db t)` returns the authoritative
   fact as of business-time `t`.
2. **`:audit-doc/category` + `:audit-doc/privilege`** for the
   grievance + DPIA + consent classification (ADR-094 substrate
   posture).
3. **`kontor.dsar/collect`** + per-companion DSAR bundles for the
   GDPR Art. 15 walk; **`kontor.retention/eligible?`** + l10n-seeded
   policies for the retention-floor check.

None of these are bolt-on. They compose because the substrate is
datalog over an immutable database where every fact carries its
own two-axis time identity.

## What kontor is NOT

- **Not an ERP.** No UI; no SaaS; no install wizard. You embed kontor
  in your Clojure app and own the operational layer.
- **Not a tax engine.** kontor provides the `TaxRateProvider` seam
  (ADR-071); customers integrate Avalara / TaxJar / TaxCloud or build
  per-country rate-table providers.
- **Not an XBRL parser.** The SEC EDGAR JSON API gives us pre-parsed
  bitemporal-ready facts; full XBRL XML / iXBRL HTML parsing is
  deferred until a real consumer needs FDTA / Companies House / ESEF
  ingest (note 78 §1).
- **Not a workforce-surveillance scaffold.** Real-time biometric
  emotion recognition, covert monitoring, automated termination
  recommendations — all banned by EU AI Act Art. 5 + Art. 22. The
  project refuses to canonicalize categories that facilitate this
  use (ADR-094 §6).

## Next steps

You're done with the on-ramp. Three directions from here:

**If you're an accounting / finance professional curious about
kontor**: read [doc/value.md](value.md). It's the same scenario
above expanded with the regulatory anchors (BGB / HGB / DSGVO / EU
AI Act articles) and the comparison to what Odoo / SAP / NetSuite
make hard.

**If you're a Clojure developer who wants to use the substrate**:
read [doc/programming.md](programming.md) for the three-axis
programming model (bitemporal substrate + status machines + the
transact gate), then open [showcase 06's source](showcases/06_de_gmbh_multi_year.clj)
and follow the build/run instructions in [README §60-second](../README.md#try-it-in-60-seconds).

**If you're an architect / evaluator**: read
[doc/architecture.md](architecture.md) for the layer cake +
namespace map, then [doc/decisions.md](decisions.md) for the 94 ADRs
(each one ~1-2 pages of context + rationale + non-decisions).

**To see all six showcases**: index lives at
[doc/showcases/](showcases/) with a one-line hook each. Render
locally with `clojure -J-Xss16m -M:notebooks:dev` (the 16MB stack
flag is required because kindly's markdown parser has been observed
to overflow on URL-bullet-followed-by-numeric-bullet patterns —
without `-Xss16m` you'll get a `StackOverflowError`).
