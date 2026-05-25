# Research note 161 — Phase D: Christian's two-DB scenario walk

Real-world stress-test of the substrate post-Phase-A+B: spans two
jurisdictions (DE + CA), two entity kinds (UG corp + individual / sole-
prop), and a cross-border dividend with treaty FTC. Validates the
"individual → corporation continuum on one substrate" framing from
[[kontor-vision]].

**Outcome**: end-to-end story runs ~30 lines of REPL code; all numbers
balance; the substrate is **functionally usable as-is**. Six new
inconsistencies surfaced (I-14 .. I-19 in note 160), one P0 (I-17 —
`trial-balance` defaults to wall-clock now, silently drops future-dated
postings).

## §1 — Setup (the on-ramp)

Two DBs via the new Phase B presets:

```clojure
(def ug-conn   (de-preset/create-de-db))   ; DE UG, SKR04, all DE statutes
(def hans-conn (ca-preset/create-ca-db))   ; CA personal, CA chart, CA statutes
```

vs ~30 lines of plumbing pre-Phase-B. Big win.

## §2 — DE side (Hans-Tech UG, FY 2026)

A modest dormant-ish year: €40k consulting revenue, €15k opex, €15k
dividend declared 60/40 to Christian + Partner B.

| Item                              | Amount       |
|-----------------------------------|--------------|
| Revenue (Erlöse 19%)              | €40,000      |
| USt collected (19%)               |   €7,600     |
| Opex (rent + tax-adv + other)     | €15,000      |
| Vorsteuer (19% on opex)           |   €2,850     |
| **Gewinn vor Steuern**            | **€25,000**  |
| KSt 15% + Soli 5.5%               |   €3,956.25  |
| GewSt @ Hebesatz 490%             |   €4,287.50  |
| **Profit after corporate taxes**  | **€16,756.25** |
| Dividend declared                 |  €15,000     |
| Retained (Gewinnvortrag)          |   €1,756.25  |

DE CIT provider (`kontor.l10n-de.cit-provider`) computed KSt+Soli +
GewSt to the cent given `:book-profit 25000M, :hebesatz 490`.

Dividend distribution: each shareholder receives gross − (KESt 25% +
Soli 5.5%×KESt) = 26.375% withhold. CW (60%): gross €9000 → net
€6,626.25, withhold €2,373.75. PB (40%): gross €6000 → net €4,417.50,
withhold €1,582.50.

UG trial balance (as-of-valid #inst "2030-01-01" — see I-17):

```
Aufwendungen:Raum:Miete                          5,000
Aufwendungen:Sonstige                            6,000
Aufwendungen:Steuerberater                       4,000
Aufwendungen:Steuern:GewSt                       4,287.50
Aufwendungen:Steuern:KSt                         3,956.25
Eigenkapital:Gewinnvortrag                      15,000
Eigenkapital:Privateinlagen                    -25,000
Erträge:Erlöse:19%                            -40,000
Umlaufvermögen:Bank                            43,706.25
Umlaufvermögen:Vorsteuer:19%                    2,850
Verbindlichkeiten:KESt-Zahlbar                  -3,956.25
Verbindlichkeiten:Steuern:GewSt-Rückstellung    -4,287.50
Verbindlichkeiten:Steuern:KSt-Rückstellung      -3,956.25
Verbindlichkeiten:Umsatzsteuer:19%              -7,600
```

Sums to 0. Balanced.

## §3 — CA side (Christian personal, FY 2026/2027)

- Sole-prop consulting in BC: CAD 60k net + 5% GST = CAD 63k received
- DE dividend received (Christian's CW share): €9,000 gross @ FX 1.50 =
  CAD 13,500 gross; CAD 9,939.38 net cash; CAD 2,025 treaty-creditable
  DE WHT (15% × 13,500); CAD 1,535.62 BZSt-refundable (excess 11.375%)

Hans-personal trial balance:

```
Assets:Bank:CAD                72,939.38
Assets:Foreign-Tax-Prepaid      2,025.00   ; §126 FTC-creditable
Assets:Foreign-Tax-Refundable   1,535.62   ; BZSt refund-claimable
Income:Dividends:Foreign:DE   -13,500.00
Income:Self-Employment        -60,000.00
Liabilities:GST-HST-Collected  -3,000.00
```

Sums to 0. Balanced.

## §4 — What worked end-to-end

- One-call presets (`(de-preset/create-de-db)`) — Phase B is a big
  ergonomics win
- `kontor.book/entry!` for the multi-leg postings — readable, balanced,
  sealed
- `:through #inst "2026-12-31"` (Phase A4) makes FY windows natural
- DE CIT provider computed KSt+Soli + GewSt correctly given a `:book-
  profit` input
- Both DBs balanced; the cross-DB dividend "transfer" was just a
  consumer-side helper (manual FX + manual treaty split)
- `:posting/entity` (Phase A3) flowed through for new postings on
  Christian's side

## §5 — What still surfaces friction (I-14 .. I-19 in note 160)

| # | Severity | What |
|---|---|---|
| I-14 | P2 | `:entity/legal-form` is free-form string; no queryable enum |
| I-15 | P1 | Per-posting `:partner` silently dropped (shareholder dividend allocation lost) |
| I-16 | P2 | F10 doesn't retro-apply (existing postings have no `:posting/entity`) |
| I-17 | **P0** | `trial-balance` defaults `:as-of-valid` to wall-clock now → simulations / forward-looking accounting silently zero |
| I-18 | P2 | SKR04 ships no tax accounts (KSt / GewSt / Dividenden-Zahlbar / KESt-Zahlbar) |
| I-19 | P1 | Cross-DB FX + treaty FTC mapping is all consumer plumbing (no `kontor.treaty.de-ca` helper) |

## §6 — Architectural verdict

The substrate **works for this scenario** with minimal ceremony. The
two-sided / multi-jurisdiction framing from [[kontor-vision]] composes
cleanly. The semantic-tag layer I was about to write in Phase C would
not have made the scenario shorter or clearer — see note 160 §I-19's
proposal instead: **per-treaty-pair helpers** in
`modules/treaty-{src}-{dst}/`. That's a smaller and more useful
abstraction than a generic tag registry.

## §7 — Recommended next moves (in order)

1. **I-17** fix — change `trial-balance` / `account-balance` default
   `:as-of-valid` from `now` to `nil` (= all valid time). One-line
   patch + regression test. P0 for any simulation / forward-looking
   use.
2. **I-15** fix — extend `kontor.book/->posting` to accept `:partner`.
   Few lines + regression test. Needed for multi-shareholder.
3. **I-18** — add ~6 tax accounts to SKR04 EDN. Trivial.
4. **I-19** — ship `modules/treaty-de-ca/` as the first per-treaty-pair
   helper. Pattern for the other ~30 important treaty pairs (DE-US,
   DE-UK, CA-US, …).
5. Update Phase D into a real integration test under
   `test/kontor/integration/christian_scenario_test.clj` for regression.
