---
date: 2026-05-24
title: 136 — DE CGT baseline review
status: review-after for ADR-103 DE implementation
---

# 136 — DE CGT baseline review

Audit of the DE capital-gains-tax provider against §8b KStG, §6b EStG,
§17/§20/§23 EStG, §32d EStG and §4 SolZG, plus the design note (113)
that drove it.

Files reviewed:
- `modules/l10n-de/src/kontor/l10n_de/cgt_statute.clj` (227 lines)
- `modules/l10n-de/src/kontor/l10n_de/cgt_provider.clj` (599 lines)
- `modules/l10n-de/test/kontor/l10n_de/cgt_provider_test.clj` (538 lines)

Cross-references: ADR-101 / ADR-103 (in `doc/decisions.md`), `kontor.statute`
(`src/kontor/statute.clj`), `kontor.tax-schedule`, `kontor.disposal-source`
(kernel protocol), `kontor.disposal` companion, `kontor.l10n-de.cit-statute`
+ `cit-provider` (for the §8b add-back integration assumption).

---

## §1. Headline

Substrate-side the implementation is sound: protocol layering is correct
(provider depends on the kernel `DisposalSource`, not the companion;
test wires the canonical `kontor.disposal.source/datahike-source`), the
two-provider split (`:corporation` vs `:individual`) matches note 113
§5, the four DE loss buckets are isolated with the right keys, the §20
stock-vs-other wall is honoured, the Soli surtax on §20 is registered
via the same compute-fn pattern the DE CIT provider uses for KSt, and
the disposal-substrate composition (asset-class classification ←
realised gain ← bucket netting ← schedule + adjustments) is clean.

Statute-side **three real correctness bugs** ship and one cited-but-
unused integration plumb:

- **P0-1** §23 EStG Freigrenze comparison is off by one — the code
  uses strict `>` (€1 000.00 is tax-free) but the statute uses
  `weniger als 1 000` i.e. `<` (€1 000.00 is fully taxable). The test
  only probes €999.99 / €1 000.01, so this is invisible to the suite.
- **P0-2** §17 EStG Freibetrag taper compares against the wrong base.
  The statute (§17 Abs. 3 S. 2) and the practitioner consensus (NWB
  summary on the BFH IX R 15/23 decision) anchor the
  Abschmelzungsgrenze comparison on the **gross Veräußerungsgewinn**,
  not on the 60 % Teileinkünfte. The code does the latter.
- **P0-3** §8b CGT-side / CIT-side composition is half-wired — the
  CGT provider emits `:cit-base-additions [addback-amount]` but the
  CIT provider's `de-§8b-addback` compute-fn reads
  `:inputs :participation-gain` and recomputes the 5 % itself. Either
  the CIT provider must consume `:cit-base-additions` (preferred) or
  the CGT provider must surface `:participation-gain` for the CIT
  provider to read. Without one of those, an end-to-end consumer
  either double-counts or silently zeroes the §8b add-back.

Two P1s on parameter metadata: the WtChancenG citation has the wrong
BGBl reference and the `:parameter/unit` of the §23 holding-period
parameters is `:ratio` rather than the more truthful `:days`.

Verified-correct items (§5): §8b 95 % exemption + 5 % add-back
mechanic; §20 Abgeltungsteuer 25 % flat + 5.5 % Soli on the §20 tax
(no church tax — correctly deferred to consumer); §23 holding-period
cutoffs (10 y real estate / 1 y movable, both tax-free past cutoff —
component suppressed, not zero-base); Günstigerprüfung suppression of
§20 standalone; §6b reserve-elected gain deferred (not in §8b pool);
loss-bucket isolation (carry-in only consumes same-bucket gains); KSt
+ Soli rate parameter references (re-uses the CIT statute's
parameters via the shared `:parameter/code` namespace — idempotent
install, no duplication).

**Bottom line.** Substrate-fit excellent; statute-fit needs the three
P0s closed before consumers can wire kontor-cgt-de + kontor-cit-de
into the same return without surprises. Closing P0-1 and P0-2 is
one-line each; closing P0-3 is a 5-line CIT-provider change (consume
`:cit-base-additions` from the CGT facts) plus a one-line CGT-provider
docstring note pointing at the integration target. All three fixes
deserve a regression test that pins the boundary / authority math.

---

## §2. P0 findings (ship-blocker — wrong rate, wrong calc, wrong statutory shape)

### P0-1. §23 EStG Freigrenze boundary off-by-one

**File:line.** `modules/l10n-de/src/kontor/l10n_de/cgt_provider.clj:441`
(in `§23-component`) and `:551-552` (in the case branch that decides
whether to emit the component at all).

**What's wrong.** The implementation:

```
taxable (if (> §23-net-gain freigrenze) §23-net-gain 0M)
```

and the gate:

```
(when (and (pos? §23-net) (> §23-net §23-freigrenze))
  (§23-component opts ctx §23-net))
```

both use **strict greater-than** against the €1 000 Freigrenze. The
statute reads (§23 Abs. 3 S. 5 EStG):

> "Gewinne bleiben steuerfrei, wenn der aus den privaten
> Veräußerungsgeschäften erzielte Gesamtgewinn im Kalenderjahr
> *weniger als* 1 000 Euro betragen hat."

That is **less-than-strict** in the tax-free direction, equivalently
**greater-than-or-equal** in the taxable direction:

- gain < €1 000 → tax-free
- gain ≥ €1 000 → fully taxable

Code says: gain ≤ €1 000 → tax-free; gain > €1 000 → fully taxable.
At exactly €1 000.00 the code outputs €0 taxable; the statute says
€1 000.00 is fully taxable.

**Authority.**
[§23 Abs. 3 S. 5 EStG (gesetze-im-internet.de)](https://www.gesetze-im-internet.de/estg/__23.html);
[Haufe — Private Veräußerungsgeschäfte / Freigrenze](https://www.haufe.de/id/beitrag/private-veraeusserungsgeschaefte-6-freigrenze-HI8997846.html)
("hard exemption limit, not a tax allowance ... if gains are higher
than €999.99, they must be taxed in full" — equivalently ≥ €1 000).

**Recommended fix.** Change `>` to `>=` in both call sites, and add
the boundary case to the test:

```clojure
(if (>= §23-net-gain freigrenze) §23-net-gain 0M)
;; and
(when (and (pos? §23-net) (>= §23-net §23-freigrenze))
  (§23-component opts ctx §23-net))
```

Test addendum: a deftest at €1 000.00 exact that expects a
`:de-§23` component with base €1 000.00, alongside the existing
€999.99 (tax-free) and €1 000.01 (fully taxable) cases at
`cgt_provider_test.clj:417-446`.

---

### P0-2. §17 Freibetrag taper compares against the wrong base

**File:line.** `modules/l10n-de/src/kontor/l10n_de/cgt_provider.clj:355-388`
(specifically `:365-367` — `(§17-freibetrag-after-taper teileinkünfte fb taper-start)`
and the helper itself at `:161-178`).

**What's wrong.** The provider:

```
teileinkünfte (* §17-net-gain inclusion)            ; 60 % of gross
fb-after-taper (§17-freibetrag-after-taper teileinkünfte fb taper-start)
```

passes the **Teileinkünfte** (the 60 %-included gain) into the taper
helper. The helper then does
`max(0, freibetrag − max(0, teileinkünfte − 36 100))`.

§17 Abs. 3 S. 2 EStG reads:

> "Der Freibetrag *ermäßigt sich um den Betrag, um den der
> Veräußerungsgewinn den Teil von 36 100 Euro übersteigt*, der dem
> veräußerten Anteil an der Kapitalgesellschaft entspricht."

"**Veräußerungsgewinn**" is the gross (100 %) gain, not the
Teileinkünfte. The Teileinkünfteverfahren is a §3 Nr. 40 c / §3c Abs. 2
inclusion mechanic applied at the §2 EStG income-aggregation stage; it
is conceptually downstream of §17's Freibetrag-with-taper, which sits
inside §17 itself.

The practitioner literature is mixed in tone but the NWB summary on
BFH IX R 15/23 lands the issue squarely:

> "Der Freibetrag ermäßigt sich um den Betrag, um den der
> Veräußerungsgewinn [gross] den Teil von 36 100 Euro übersteigt …
> Die Abschmelzung wird also nach Anwendung des
> Teileinkünfteverfahrens berechnet, nicht davor."

The "nach Anwendung des Teileinkünfteverfahrens" phrasing is about
the *order* of stages in the §2 EStG income build (compute Freibetrag-
adjusted gain *then* apply Teileinkünfte = inverse of what the code
does today), but the *threshold itself* is anchored on the gross gain
per the statute text.

Worked check: a gross gain of €70 000 (the code's `§17-mid-taper`
test, `cgt_provider_test.clj:255-269`):

- Code today: Teileinkünfte €42 000; excess over €36 100 = €5 900;
  Freibetrag after taper €9 060 − €5 900 = €3 160; taxable
  €42 000 − €3 160 = **€38 840**.
- Statute-faithful: excess over €36 100 = €33 900 (compared against
  gross €70 000); Freibetrag fully tapered to €0; Teileinkünfte
  €42 000; taxable **€42 000**.

These disagree by €3 160 in the favour of the taxpayer. The other §17
tests at `:202-238` are insensitive (gains either far above the
fully-consumed Freibetrag zone or far below the taper-start).

**Authority.**
[§17 Abs. 3 EStG (gesetze-im-internet.de)](https://www.gesetze-im-internet.de/estg/__17.html);
[BFH IX R 15/23 (Bundesfinanzhof)](https://www.bundesfinanzhof.de/en/entscheidungen/entscheidungen-online/decision-detail/STRE202450055/)
via the [NWB Datenbank summary on §17 EStG](https://datenbank.nwb.de/Dokument/692419/).

**Recommended fix.** Pass the **gross** §17 net (the pre-inclusion
amount) into the taper helper, keep the Teileinkünfte computation for
the post-Freibetrag taxable amount:

```clojure
(let [teileinkünfte (* §17-net-gain inclusion)
      fb-after-taper (§17-freibetrag-after-taper
                       §17-net-gain fb taper-start)  ; ← gross, not teileinkünfte
      taxable (max 0M (- teileinkünfte fb-after-taper))]
  …)
```

Update the taper helper's docstring (`cgt_provider.clj:161-172`) to
reflect that it now takes the gross gain. Update the
`§17-mid-taper` / `§17-freibetrag-partial-taper` test expectations to
the statute-faithful numbers. Add a deftest at exactly €45 160 (=
€9 060 + €36 100) gross — the post-Freibetrag-fully-consumed boundary
— to pin the math.

---

### P0-3. §8b CGT → CIT composition path is wired but not consumed

**File:line.** `modules/l10n-de/src/kontor/l10n_de/cgt_provider.clj:312-313`
(the `:jurisdiction-specific-codes {:cit-base-additions [addback-amount]}`
emission) and `modules/l10n-de/src/kontor/l10n_de/cit_provider.clj:83-90`
(`de-§8b-addback` reading `:inputs :participation-gain`).

**What's wrong.** The CGT provider emits the §8b 5 % add-back as
`:cit-base-additions [200000M]` on its `:de-§8b` component (correct —
the CGT side computes it from the disposal-level gain). The CIT
provider's `DE-KStG-§8b-Abs-5` provision then fires on `:eq :component
:kst` + `:gt [:inputs :participation-gain] 0M` and the compute-fn
`de-§8b-addback` re-derives the add-back from
`:inputs :participation-gain × (1 − exemption-rate)`.

For an end-to-end consumer wiring both providers:

- **Path A** — pass the disposal-level gain as `:participation-gain`
  to the CIT provider; ignore the CGT provider's `:cit-base-additions`.
  CGT-CIT composition is logically zero; the §8b 5 % is computed
  inside the CIT provider only. This is what the test wiring
  implicitly assumes (the CGT tests don't run the CIT provider).
- **Path B** — pass the CGT facts' `:cit-base-additions` straight
  into the CIT provider's `:inputs` as some new key. There is no
  consumer of `:cit-base-additions` today, so the value is dropped.
- **Path C** (probable bug) — pass the §8b add-back amount (€200 000
  for the headline example) as `:participation-gain` to the CIT
  provider. The CIT then computes 5 % × €200 000 = €10 000 add-back,
  i.e. 0.25 % effective instead of the intended 1.5 %.

Note 113 §5.1 is explicit that "the integration hook" is
`:cit-base-additions` — but neither end of the wire has been built
for that. The CGT provider's docstring (`cgt_provider.clj:18-23`)
says:

> "Output: a single component whose `:jurisdiction-specific-codes
> :cit-base-additions` carries the 5 % add-back the CIT provider
> composes downstream — mirrors `kontor.sole-proprietor/business-income-input`."

`business-income-input` (per CLAUDE.md's ADR-100 summary) actually
calls into the PIT provider's `:inputs` directly — it's not a value
the PIT provider opaquely consumes. The CGT-CIT integration needs the
same explicit hand-off.

**Authority.** Note 113 §5.1 (the explicit integration sketch);
[§8b Abs. 3 S. 1 KStG (gesetze-im-internet.de)](https://www.gesetze-im-internet.de/kstg_1977/__8b.html)
on the 5 % Pauschalzuschlag mechanic itself, which both sides agree
on — only the cross-provider plumb is open.

**Recommended fix (preferred).** Add a small `kontor.l10n-de.cgt-cit-bridge`
namespace (or fold into `cgt-provider`) exposing one fn
`(cgt-§8b-addback-input cgt-facts)` returning a number suitable to
add into the CIT provider's `:inputs :participation-gain`. Two
docstring updates: cgt-provider's `:cit-base-additions` blurb cites
the bridge; cit-provider's `:inputs :participation-gain` blurb cites
it. The cit-provider keeps computing the 5 % itself (no behaviour
change for consumers that don't use the CGT provider). Add an
integration test that wires both providers on the §8b headline
example (€4 M gain → €200 000 add-back → €60 000 KSt+Soli).

**Alternative fix.** If "everywhere there's a CGT provider, use it"
is the desired stance, sink the §8b add-back computation into the CGT
provider and have the CIT provider's `DE-KStG-§8b-Abs-5` provision
read `:inputs :de-§8b-addback-from-cgt` (a different fact). The CGT
provider becomes authoritative; the CIT provision becomes a "pass-
through" of a pre-computed add-back. Less elegant — the CIT provider
loses the ability to run §8b without the CGT provider, which is a
common standalone case (legacy hand-keyed `:participation-gain`
amounts).

Either way, the **status quo where both ends quietly compute different
things from different inputs** is the bug.

---

## §3. P1 findings (significant correctness gap)

### P1-1. WtChancenG BGBl citation is wrong

**File:line.** `modules/l10n-de/src/kontor/l10n_de/cgt_statute.clj:168`.

**What's wrong.** The §23 Freigrenze raise from €600 to €1 000 cites
"BGBl. I 2023 Nr. 412" but the Wachstumschancengesetz was published in
**BGBl. 2024 I Nr. 108** on **27 March 2024** (the law cleared the
Vermittlungsausschuss in early 2024 and was promulgated after that).
"BGBl. I 2023 Nr. 412" is a phantom — there's no such Fundstelle in
the 2023 Teil I.

**Authority.** [BGBl. 2024 I Nr. 108 (recht.bund.de)](https://www.recht.bund.de/bgbl/1/2024/108/VO.html)
"Gesetz zur Stärkung von Wachstumschancen, Investitionen und
Innovation sowie Steuervereinfachung und Steuerfairness", published
2024-03-27, BMF lead.

**Recommended fix.** Update the `:parameter-value/citation` string at
`cgt_statute.clj:168`:

```
:parameter-value/citation "§23 Abs. 3 S. 5 EStG (Wachstumschancengesetz, BGBl. 2024 I Nr. 108 v. 27.03.2024 — Freigrenze € 1 000 ab VZ 2024)"
```

Effective-from `#inst "2024-01-01"` is correct (the WtChancenG raised
the Freigrenze for VZ 2024 onward; verkündung-date is March 2024 but
the substantive change applies from the start of the assessment
year — per Art. 35 Abs. 4 WtChancenG).

---

### P1-2. §23 holding-period parameters tagged `:ratio` not `:days`

**File:line.** `modules/l10n-de/src/kontor/l10n_de/cgt_statute.clj:105` and `:111`.

**What's wrong.** Both `DE.EStG.§23.real-estate-cutoff-days` and
`DE.EStG.§23.movable-cutoff-days` declare `:parameter/unit :ratio`
but the stored values are 3650 and 365 (whole days). The
`:parameter/unit` enum in the kernel schema (per ADR-101 and
`cit-statute.clj`'s usage of `:rate` / `:amount-money`) is meant to
constrain downstream consumers (e.g. a unit-aware report engine, a
schema validator); declaring days as a `:ratio` defeats that.

**Authority.** None — internal kernel hygiene.

**Recommended fix.** If `:days` isn't already an admitted
`:parameter/unit` value, this is a 2-line schema extension (add
`:days` to the closed `:parameter/unit` set in `src/kontor/schema.clj`)
plus the two-line correction here. If a `:days` unit isn't worth
introducing for two parameters, use `:amount-money` is wrong (the
value isn't money) — better fallback is to lean on `:integer` or
similar. Either way, `:ratio` for "3650" is misleading documentation.

---

### P1-3. §17 wesentliche-Beteiligung threshold isn't gated by the provider

**File:line.** `modules/l10n-de/src/kontor/l10n_de/cgt_provider.clj:230-241`
(classifier's `:de-§17-wesentlich` branch).

**What's wrong.** The provider trusts the consumer to set
`:asset-class :de-§17-wesentlich` whenever a disposal qualifies as
§17. It never reads `:disposal/ownership-fraction` to check the 1 %
test or to apply the §17 Abs. 1 S. 1 5-year-lookback rule (held >=1 %
at any time in the last 5 years). The `§17-large` test passes
`:ownership-fraction 0.05M` (= 5 %) on its disposal but the field is
silently ignored.

For the substrate this is **a design call**, not a bug — the disposal
schema's `:asset-class` is documented as already-classified, and a
consumer with full historical share-ownership data is better placed
than this provider to run the 5-year lookback. But the gap deserves a
docstring note so consumers don't assume the provider double-checks
their classification.

**Authority.** [§17 Abs. 1 S. 1 EStG (gesetze-im-internet.de)](https://www.gesetze-im-internet.de/estg/__17.html)
("innerhalb der letzten fünf Jahre … zu mindestens 1 Prozent
unmittelbar oder mittelbar beteiligt war").

**Recommended fix.** Extend the `classify-individual` docstring
(`cgt_provider.clj:230-234`) to spell out:

> "The 1 % wesentlich threshold and the 5-year lookback (§17 Abs. 1
> S. 1 EStG) are the **consumer's** responsibility — by the time a
> disposal reaches this classifier with `:asset-class
> :de-§17-wesentlich`, the §17 qualification is taken as given. The
> field `:disposal/ownership-fraction` is informational only; pre-
> classification at `record-disposal!` time is the substrate
> convention."

Optional: emit a `kontor.tax/§17-classification-questionable` warning
(at log-level, not exception) when the provider sees `:de-§17-wesentlich`
with `(< ownership-fraction 0.01M)`. Pure defence; doesn't change tax
behaviour.

---

### P1-4. §6b 4/6-year window + 6 % Strafverzinsung are out of substrate

**File:line.** `modules/l10n-de/src/kontor/l10n_de/cgt_provider.clj:315-331`
(`§6b-deferred-component`).

**What's wrong.** The §6b reserve mechanic is split between
this provider (recognises a deferred component with €0 CIT impact)
and "the GL side" (the actual Reinvestitionsrücklage account, the
4/6-year deadline tracking, the dissolution-with-6 %-per-year
penalty). The provider's docstring acknowledges this at
`cgt_provider.clj:46-49`. **No bug**; just documenting that the
substrate does not yet have the matching helper (`kontor-§6b-reserve`
companion or a kernel `:reserve-lifecycle` primitive).

**Authority.** [§6b Abs. 3 + Abs. 7 EStG (gesetze-im-internet.de)](https://www.gesetze-im-internet.de/estg/__6b.html)
("der Gewinn des Wirtschaftsjahres, in dem die Rücklage aufgelöst
wird, … um 6 Prozent des aufgelösten Rücklagenbetrags zu erhöhen" —
the penalty).

**Recommended fix.** None now; capture as a Phase-3-follow-up. The
deferred-component shape is the right interim. Add a one-line
"future work" note in the cgt-provider docstring pointing at the
companion-module gap so a consumer wiring §6b for real doesn't
expect kontor to track the reserve deadline.

---

## §4. P2 findings (polish — comments, naming, doc)

### P2-1. `§17-freibetrag-after-taper` docstring contradicts the implementation

**File:line.** `cgt_provider.clj:161-172`.

The docstring says "the taper compares against the TEILEINKÜNFTE
GAIN, i.e. the 60 %-included amount, per the canonical reading."
The implementation does indeed compare against teileinkünfte — but
that's P0-2 (statute reads against the gross). Once P0-2 is fixed
the docstring needs to flip too. Currently the docstring
*reinforces* the bug.

---

### P2-2. `cgt-statute.clj:30-32` re-references CIT-owned parameters

**File:line.** `cgt_statute.clj:27-32`.

The CGT statute docstring says:

> "Soli on Abgeltungsteuer — DE.Soli.rate (5.5 %) is already
> installed by the CIT statute; we re-reference rather than re-add
> (parameters are idempotent on `:parameter/code`)."

This is correct in spirit, but a consumer who installs ONLY the
CGT statute (e.g. an individual-only setup with no corporate side)
will trigger a runtime nil from `parameter-value-at "DE.Soli.rate"`
because the CIT statute is the only place that installs that
parameter. The cgt-statute test fixture works because it explicitly
installs CIT first (`cgt_provider_test.clj:30-31`), but production
ergonomics need the CGT install to be self-sufficient.

**Recommended fix.** Either install `DE.Soli.rate` (and a
`DE.Soli.rate` parameter-value) in `cgt-statute.clj` too (the
`:parameter/code` idempotency makes it a safe re-add), or split the
shared parameters into a `kontor.l10n-de.shared-statute` namespace
both install. The first is the smaller diff. The two existing tests
that install both statutes already are unaffected either way.

---

### P2-3. `cgt-provider.clj:78-90` "asset class" sets duplicate documentation

**File:line.** `cgt_provider.clj:78-90`.

`corporate-asset-classes` and `individual-asset-classes` are
`def`s with docstrings each declaring "Closed set of … the X
provider recognises." The lane-classification docstring up at
`:38-53` lists the same enum membership with slightly more detail
(§6b vs §6b-deferred fork). Three sources of truth for one enum.

**Recommended fix.** Single doc-source of truth: keep the table at
`:38-53` and shorten the two `def` docstrings to "see ns docstring §
Lane classification." Trivial.

---

### P2-4. `cgt_provider_test.clj:43-54` `record!` helper sets a self-referential subject

**File:line.** `cgt_provider_test.clj:46-54`.

The `record!` helper uses `:subject eur` — the EUR commodity ref —
"throwaway ref" by the comment. Workable for testing CGT classifier
math, but a reviewer scanning the test file might mistake this for
intentional self-disposal of cash. A `:subject [:entity/code "HOLDCO"]`
or a separately-defined `:subject-asset` would be clearer.

---

### P2-5. `§17-freibetrag-fully-available-on-small-gain` test math diverges from comment

**File:line.** `cgt_provider_test.clj:223-238`.

Test comment claims `:max(0, 6 000 − 9 060) = 0`. With the current
(P0-2-buggy) implementation that's right. Post-fix, with the gross-
gain taper-start comparison, a €10 000 gross gain → €0 excess →
€9 060 Freibetrag → Teileinkünfte €6 000 → taxable `max(0, 6 000 −
9 060) = 0`. Same answer by coincidence, different math. After the
fix, the comment should walk through the gross-gain path even if
the answer is unchanged — these are the kind of tests where the
worked-example narrative is half the value.

---

## §5. Verified-correct items

These items were spot-checked against authority and pass:

| Item | Source of truth | Code site | Verdict |
|---|---|---|---|
| §8b Abs. 2 100 % exemption + Abs. 3 5 % add-back mechanic | [§8b KStG](https://www.gesetze-im-internet.de/kstg_1977/__8b.html) | `cgt_provider.clj:287-313` | ✓ exempt-line carries €3.8M for audit, add-back-line carries €200k, `:cit-base-additions [200000M]` is the integration value |
| `0.95M` exemption-rate + `0.05M` add-back-rate parameter values | [§8b Abs. 3 S. 1 KStG](https://www.gesetze-im-internet.de/kstg_1977/__8b.html) | `cgt_statute.clj:127-134` | ✓ stable since 2004 SEStEG, effective-from correctly anchored |
| §17 inclusion-rate `0.60M` (Teileinkünfte) | [§3 Nr. 40 lit. c + §3c Abs. 2 EStG](https://www.gesetze-im-internet.de/estg/__3.html) | `cgt_statute.clj:137-140` | ✓ effective from 2009 Abgeltungsteuer cutover |
| §17 Freibetrag `9060M` + taper-start `36100M` | [§17 Abs. 3 S. 1 + S. 2 EStG](https://www.gesetze-im-internet.de/estg/__17.html) | `cgt_statute.clj:142-150` | ✓ values exact; effective-from is right (stable since 2009; the 2004 SEStEG had different numbers but the EStG has been at these since 2009) |
| §20 Abgeltungsteuer flat rate `0.25M` | [§32d Abs. 1 EStG](https://www.gesetze-im-internet.de/estg/__32d.html) | `cgt_statute.clj:153-156` | ✓ |
| §23 Freigrenze raise (€600 → €1 000 at 2024-01-01) | [WtChancenG, BGBl. 2024 I Nr. 108](https://www.recht.bund.de/bgbl/1/2024/108/VO.html) | `cgt_statute.clj:159-168` | ✓ values + dates correct; **citation BGBl reference is wrong, see P1-1** |
| §23 holding-period cutoffs 10 y / 1 y | [§23 Abs. 1 Nr. 1 + Nr. 2 EStG](https://www.gesetze-im-internet.de/estg/__23.html) | `cgt_statute.clj:170-178`, `cgt_provider.clj:220-228` | ✓ days math; `>` cutoff comparison correct ("nicht mehr als zehn Jahre" = ≤ 10 y is taxable, > 10 y is tax-free) |
| §23 past-cutoff = tax-FREE (no zero-base component) | §23 Abs. 1 EStG | `cgt_provider.clj:251-259`, test `:378-390` | ✓ component suppressed when cleared; not a zero-base ghost |
| §20 stock-vs-other loss-bucket wall | [§20 Abs. 6 Sätze 4-5 EStG](https://www.gesetze-im-internet.de/estg/__20.html) | `cgt_provider.clj:531-535` (separate `net-bucket` per sub-bucket) | ✓ separate carry-ins per `:de-§20-stock` / `:de-§20-other`; test `:296-323` |
| §20 Abgeltungsteuer + Soli 5.5 % surtax (no church tax) | [§32d Abs. 1 + §4 SolZG](https://www.gesetze-im-internet.de/solzg_1995/__4.html) | `cgt_provider.clj:390-427`, `cgt_statute.clj:199-211` | ✓ Soli registered as a `:provision` scoped to `:eq :component :de-§20`; church tax correctly deferred to consumer (varies per Bundesland + religion) |
| Günstigerprüfung suppression of §20 standalone | [§32d Abs. 6 EStG](https://www.gesetze-im-internet.de/estg/__32d.html) | `cgt_provider.clj:543-546`, `:461-479` | ✓ `:tax-unit :abgeltungsteuer-elect-marginal? true` swaps the §20 component for a `§20-pit-fold-component` carrying `:pit-base-additions`; test `:325-348` |
| §6b reserve-elected gain deferred (not in §8b pool) | [§6b EStG](https://www.gesetze-im-internet.de/estg/__6b.html) | `cgt_provider.clj:123-135`, `:197-202`, test `:155-177` | ✓ §6b-elected disposals routed to `:de-§6b-deferred`, never into the §8b pool |
| §6b-eligible without rollover → CIT base 1:1 | §6b EStG (residual treatment) | `cgt_provider.clj:204-209`, test `:179-196` | ✓ residual lane folds the full gain into `:cit-base-additions` (no exemption — §6b assets aren't participations) |
| Loss-bucket isolation (4 buckets: §17 / §20-stock / §20-other / §23) | [§20 Abs. 6 + §17 + §23 EStG (each bucket-isolated by statute)](https://www.gesetze-im-internet.de/estg/__20.html) | `cgt_provider.clj:92-95`, `:529-537` | ✓ each bucket sums only its own gains and consumes only its own carry-in; matches note 113 §1.6 |
| Soli surtax on §20 via late-bound compute-fn pattern | DE-SolZG-§4 (mirror of CIT's `de-soli-on-kst`) | `cgt_provider.clj:141-146` | ✓ same `[ctx-w-running]` shape as `cit_provider.clj:76-81` — substrate convention honoured |
| Disposal substrate pull-shape (proceeds + basis + acquired/disposed) | `kontor.disposal.source/pull-spec` | `cgt_provider.clj:113-121` | ✓ provider reads only fields the source guarantees |
| Voided disposals excluded | ADR-102 contract on `:state :voided` | `cgt_provider_test.clj:513-524` | ✓ test exercises `disposal/void!` and confirms zero components |
| Unknown asset-class silently dropped (forward-compat) | substrate convention | `cgt_provider.clj:184-218`, test `:526-537` | ✓ |
| Two-provider split along corporate / individual axis | note 113 §5 | `cgt_provider.clj:485-557` + factory fns at `:569-593` | ✓ shared record, dispatch on `:kind`; matches note 113 sketch |

---

## §6. Substrate-level observations

1. **`:cit-base-additions` is unconsumed.** No code reads this key —
   not the CIT provider, not the `apply-base-adjustments` fold. It's a
   convention waiting for a sink (see P0-3). Either the kernel grows a
   `compose-cgt-into-cit` helper (one call site, one assertion) or the
   convention is per-l10n-module and documented as such. The same
   `:pit-base-additions` convention has the same gap on the individual
   side — `§17-component` and `§23-component` emit it, but nothing
   consumes it yet.

2. **`:parameter-bracket` uniqueness gap** (noted in ADR-105 for FR
   CIT) doesn't bite here — DE CGT has zero bracket parameters.

3. **`:disposal/loss-bucket` field exists in the pull-spec
   (`kontor.disposal.source` pull) but the provider doesn't read it.**
   The provider derives the bucket from `:asset-class` instead. The
   companion's `realized-gain-summary` does use `:loss-bucket`, so
   the field has a customer — just not this one. Worth a comment in
   `classify-individual` to note that the provider chooses the
   `:asset-class` axis as authoritative and the `:loss-bucket`
   denormalisation is for `realized-gain-summary` reporting only.

4. **`apply-adjustments` `:resolved` items include `:provenance`
   already**; the `§20-component` (`cgt_provider.clj:414`) projects
   only `[:code :label :amount :provenance]` into `:surtaxes`. That's
   fine; consider keeping `:op` too so a downstream report can group
   credits and surtaxes without re-lookup.

5. **Test fixture coupling on `cit-statute/install!`** is mildly
   surprising — a CGT-only consumer should be able to install
   CGT-only. See P2-2; the underlying issue is that `DE.Soli.rate` is
   "shared" but owned by exactly one installer.

6. **`§17-freibetrag-after-taper` returns a clamped Freibetrag, but
   then the caller does `max(0, teileinkünfte − fb-after-taper)`.**
   The clamp is correct but the double-`max(0, …)` (once in the
   helper, once at the call site) is a small redundancy. Pure
   readability; no behaviour impact.

7. **§17 5-year lookback is provider-trusts-consumer.** Solid design
   call (see P1-3); worth a docstring note as the upstream
   precondition.

8. **Date-math in `days-between` uses milliseconds with no DST/TZ
   handling.** `(* 1000 60 60 24)` per day — fine for `java.util.Date`
   instants stored at `00:00:00 UTC` (which is the kontor convention
   per `kontor.bitemporal`'s `:tx/valid-from` stamping), but if a
   consumer ever passes a `disposed-on` with a non-midnight time
   component the days computation could be off by one on the boundary
   day. Worth a test that pins midnight-UTC inputs as the contract.

---

## §7. Sources used

**DE statutes (gesetze-im-internet.de — license: public domain)**:
- [§ 8b KStG](https://www.gesetze-im-internet.de/kstg_1977/__8b.html) — 95/5 participation exemption (P0-3 substrate verified; mechanic confirmed).
- [§ 6b EStG](https://www.gesetze-im-internet.de/estg/__6b.html) — rollover relief (P1-4 verified — 4/6y window + 6 % Strafverzinsung confirmed).
- [§ 17 EStG](https://www.gesetze-im-internet.de/estg/__17.html) — wesentliche Beteiligung; Abs. 3 S. 2 confirms `Veräußerungsgewinn` is the taper anchor (P0-2 evidence).
- [§ 20 EStG](https://www.gesetze-im-internet.de/estg/__20.html) — Abs. 6 stock-vs-other loss wall confirmed (verified item).
- [§ 23 EStG](https://www.gesetze-im-internet.de/estg/__23.html) — Abs. 3 S. 5 "weniger als 1 000 Euro" confirmed (P0-1 evidence).
- [§ 32d EStG](https://www.gesetze-im-internet.de/estg/__32d.html) — Abs. 1 25 % flat + Abs. 6 Günstigerprüfung "anstelle der Anwendung" confirmed (verified item).
- [§ 4 SolZG](https://www.gesetze-im-internet.de/solzg_1995/__4.html) — Soli 5.5 % surtax (verified item).

**Federal sources**:
- [BGBl. 2024 I Nr. 108 (recht.bund.de)](https://www.recht.bund.de/bgbl/1/2024/108/VO.html) — Wachstumschancengesetz, published 2024-03-27 (P1-1 evidence; the cgt-statute citation has this wrong).
- [BMF — Einzelfragen zur Abgeltungsteuer, 2025-05-14 (PDF)](https://www.bundesfinanzministerium.de/Content/DE/Downloads/BMF_Schreiben/Steuerarten/Abgeltungsteuer/2025-05-14-einzelfragen-zur-abgeltungsteuer.pdf?__blob=publicationFile&v=6) — confirms §20 Abs. 6 alt.F. derivative-cap removal; nothing in the implementation depends on the cap, so this is informational background.
- [BFH IX R 15/23 — Veräußerungsgewinn nach § 17 EStG bei teilentgeltlicher Übertragung](https://www.bundesfinanzhof.de/en/entscheidungen/entscheidungen-online/decision-detail/STRE202450055/) — confirms §17 Freibetrag scaling; relevant to P0-2.

**Practitioner commentary**:
- [Haufe — Freigrenze § 23 EStG (HI8997846)](https://www.haufe.de/id/beitrag/private-veraeusserungsgeschaefte-6-freigrenze-HI8997846.html) — "hard exemption limit … if gains are higher than €999.99, they must be taxed in full" (P0-1 evidence).
- [Haufe — Teileinkünfteverfahren § 17 EStG (HI6446215)](https://www.haufe.de/id/beitrag/teileinkuenfteverfahren-123-veraeusserungen-nach-17-estg-HI6446215.html) — 60 % inclusion mechanics.
- [bibukurse.de — § 17 EStG](https://www.bibukurse.de/einkommensteuer/einkuenfte/einkuenfte-aus-gewerbebetrieb/arten-gewerblicher-einkuenfte/einmalige-einkuenfte/veraeusserung-von-anteilen-an-kapitalgesellschaften-17-estg.html) — gross-gain taper-start example (P0-2 evidence).
- [NWB Datenbank — § 17 EStG (692419)](https://datenbank.nwb.de/Dokument/692419/) — "Die Abschmelzung wird also nach Anwendung des Teileinkünfteverfahrens berechnet" (P0-2 evidence on order of operations).
- [IWW — § 23 EStG Freigrenze 2024 (f159438)](https://www.iww.de/ssp/alle-steuerzahler/wachstumschancengesetz--23-estg-veraeusserungsgewinne-ab-2024-bis-1000-euro-steuerfrei-kassieren-f159438) — WtChancenG raise to €1 000 (P1-1 cross-check).
- [Frotscher/Geurts EStG § 32d — 2.4 Soli auf Kapitalerträge (Haufe HI9245440)](https://www.haufe.de/id/kommentar/frotschergeurts-estg-32d-gesonderter-steuertarif-fuer-24-solidaritaetszuschlag-auf-kapitalertraege-HI9245440.html) — Soli on Kapitalertragsteuer always applies (Freigrenze is wage-tax-only); verified-item evidence.

**Internal cross-references**:
- `doc/research/113-de-cgt-fit.md` (the design note this review audits against).
- `doc/decisions.md` — ADR-101 (statute-as-data substrate), ADR-103 (per-jurisdiction CGT providers stage), ADR-104 (DE CIT — the §8b-Abs-5 provision the CGT provider claims to compose with).
- `modules/l10n-de/src/kontor/l10n_de/cit_statute.clj:240-254` — the DE-KStG-§8b-Abs-5 provision (P0-3 evidence).
- `modules/l10n-de/src/kontor/l10n_de/cit_provider.clj:83-90` — `de-§8b-addback` compute-fn (P0-3 evidence).
- `src/kontor/disposal_source.clj` — kernel protocol the CGT provider correctly depends on.
- `src/kontor/statute.clj:150-174` — `parameter-value-at` (half-open `[from, until)` window semantics — substrate confirmed).
- `src/kontor/tax_schedule.clj:192-245` — `apply-adjustments` (Soli-on-§20 surtax fold).

---

End of note 136.
