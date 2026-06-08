<!--
Thanks for the PR. A short, well-scoped change is much easier to
review than a big one. See CONTRIBUTING.md for the full workflow.
-->

## Summary

<!--
1-3 sentences: what changed and **why**. Reference the ADR or
research note that motivates the change if relevant.
-->

## Test plan

- [ ] New / updated tests cover the change (test-first per CLAUDE.md)
- [ ] Pre-push trio green locally: `clojure -M:format && clojure -M:lint && clojure -M:test`
- [ ] No bypass of the validation gate (ADR-068 — `*-tx-data` +
      `!` wrapper)
- [ ] Schema additions follow `:kontor.<area>/*` naming (see
      CONTRIBUTING.md "Schema-namespace discipline")

## ADR / authority link

<!--
- If this PR makes a non-trivial design choice: which ADR number
  applies? If the decision is load-bearing for new readers, does
  `doc/decisions.md` need a section update too?
- For per-jurisdiction tax / payroll / e-invoice changes: cite the
  authority URL (statute, bulletin, XSD version).
-->

## Anything else reviewers should know

<!--
Out-of-scope items deliberately left for a follow-up PR;
known-broken edge cases; questions you'd like reviewers to weigh in
on.
-->
