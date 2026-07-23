(ns kontor.l10n-fr.bs
  "French balance sheet — Bilan (PCG, art. 821-1 et seq., système de base).

   The Plan Comptable Général fixes the French Bilan by account class:

   ACTIF (assets, débit-natural):
     Actif immobilisé   — classe 20 (incorporelles), 21 (corporelles),
                          26/27 (financières)
     Actif circulant    — classe 3 (stocks), 41x (créances clients),
                          44 déductible + autres créances, classe 5
                          (disponibilités)

   PASSIF (equity + liabilities, crédit-natural):
     Capitaux propres   — classe 10 (capital, réserves), 11 (report à
                          nouveau), 12 (résultat de l'exercice), 13/14
     Provisions         — classe 15
     Dettes             — classe 16/17 (financières), 40x (fournisseurs),
                          44 collectée + fiscales/sociales (42/43), 45x

   Line codes are class-prefix patterns, EXCEPT within classe 44 (État),
   which the PCG splits across BOTH sides — TVA déductible (44562/44566/
   44581) is an actif receivable while TVA collectée (44571-4) and TVA due
   intracommunautaire (4452) are passif liabilities. A bare `\"44%\"` on
   either side would double-count the other, so the State accounts are
   enumerated by their specific sub-prefixes.

   Account codes target `kontor.l10n-fr.chart`. As with the P&L that
   skeleton is a load-bearing subset (no stocks, no provisions, no
   financial debt), so those lines compute to zero on the shipped chart
   and light up as a consumer extends it.

   ## Why capitaux propres carries a résultat line computed from class 6/7

   A balance sheet only balances when the period result is IN equity. The
   PCG closing (`kontor.l10n-fr.closing/close-fr-fiscal-year!`) rolls the
   class-6/7 accounts into 110 Report à nouveau (or 120 Résultat de
   l'exercice under the two-step option) at year-end, so BEFORE that runs —
   any interim Bilan — the produits/charges balances sit OUTSIDE equity and
   actif exceeds passif by exactly the period résultat.

   Section C therefore carries the current-period résultat as two lines:
   the class-7 produits, and the class-6 charges flipped by `:line/negate`.
   Their sum is the résultat de l'exercice. That is the PCG
   avant-affectation presentation, and it makes the Bilan balance both
   before and after the close: once the close has zeroed class 6/7 those
   two lines compute to zero and line C.3 (110, via `\"11%\"`) — or C.4
   (120, via `\"12%\"`) under the two-step option — carries the amount
   instead.

   `check` reports whether ACTIF = PASSIF actually holds — see its
   docstring for what a non-zero difference means.

   Commodity is derived from the postings (note-196 F5); on an EUR book no
   `:line/commodity` stamp is needed, but a `:total-sign-map` is (supplied
   by `compute`)."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def definition
  "Bilan (PCG système de base) over the `kontor.l10n-fr.chart` codes."
  {:statement/name    "Bilan"
   :statement/country "FR"
   :statement/sections
   [{:section/code  "A"
     :section/label "Actif immobilisé"
     :section/lines
     [{:line/code "A.1" :line/label "Immobilisations incorporelles"
       :line/codes ["20%"]}
      {:line/code "A.2" :line/label "Immobilisations corporelles"
       :line/codes ["21%" "23%"]}
      {:line/code "A.3" :line/label "Immobilisations financières"
       :line/codes ["26%" "27%"]}]}

    {:section/code  "B"
     :section/label "Actif circulant"
     :section/lines
     [{:line/code "B.1" :line/label "Stocks et en-cours"
       :line/codes ["3%"]}
      {:line/code "B.2" :line/label "Créances clients et comptes rattachés"
       :line/codes ["411%" "413%" "416%" "418%"]}
      ;; State receivables (TVA déductible) enumerated so the passif
      ;; classe-44 lines cannot double-count them; 409 = avances versées
      ;; aux fournisseurs (a débit-balance classe-40 account, hence here
      ;; and NOT on the fournisseurs dette line).
      {:line/code "B.3" :line/label "Autres créances (dont État, TVA déductible)"
       :line/codes ["44562" "44566" "44567" "44581" "44583" "409%" "425%"]}
      {:line/code "B.4" :line/label "Disponibilités"
       :line/codes ["5%"]}]}

    {:section/code  "C"
     :section/label "Capitaux propres"
     :section/lines
     [{:line/code "C.1" :line/label "Capital social"
       :line/codes ["101%" "108%"]}
      {:line/code "C.2" :line/label "Réserves"
       :line/codes ["106%"]}
      {:line/code "C.3" :line/label "Report à nouveau"
       :line/codes ["11%"]}
      ;; 120/129 Résultat de l'exercice — non-zero only after a two-step
      ;; close to 120 (see namespace docstring).
      {:line/code "C.4" :line/label "Résultat de l'exercice (affecté)"
       :line/codes ["12%"]}
      ;; Interim résultat, held outside the equity accounts until the
      ;; fiscal year is closed — computed from class 6/7.
      {:line/code "C.5" :line/label "Résultat de l'exercice — produits"
       :line/codes ["7%"]}
      {:line/code "C.6" :line/label "Résultat de l'exercice — charges"
       :line/codes ["6%"]
       :line/negate true}
      {:line/code "C.7" :line/label "Subventions d'investissement / provisions réglementées"
       :line/codes ["13%" "14%"]}]}

    {:section/code  "D"
     :section/label "Provisions pour risques et charges"
     :section/lines
     [{:line/code "D.1" :line/label "Provisions"
       :line/codes ["15%"]}]}

    {:section/code  "E"
     :section/label "Dettes"
     :section/lines
     [{:line/code "E.1" :line/label "Dettes financières (emprunts)"
       :line/codes ["16%" "17%"]}
      ;; 401-408 only — 409 (avances versées) is an actif, on B.3.
      {:line/code "E.2" :line/label "Dettes fournisseurs et comptes rattachés"
       :line/codes ["401%" "403%" "404%" "408%"]}
      ;; State + social liabilities. TVA collectée (4457x), TVA à décaisser
      ;; (4455x) and TVA due intracommunautaire (4452) — the déductible
      ;; classe-44 accounts sit on B.3, not here.
      {:line/code "E.3" :line/label "Dettes fiscales et sociales"
       :line/codes ["4457%" "4455%" "4452" "444%" "447%" "42%" "43%"]}
      {:line/code "E.4" :line/label "Autres dettes"
       :line/codes ["455%" "457%" "467%" "487%"]}]}]})

(def ^:private sign-map
  "Actif adds; capitaux propres, provisions and dettes subtract. The
   statement total is ACTIF − PASSIF, which is zero for a balanced book."
  {"A" :+ "B" :+ "C" :- "D" :- "E" :-})

(defn compute
  "Compute the Bilan as of a point in time.

   A balance sheet is cumulative since inception, so pass the as-of date
   and leave `:from` nil. Other opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:as-of-tx` `:include-states` `:ledger` `:entity`).

   `:to` is EXCLUSIVE — for a Dec 31 Bilan pass `:through #inst
   \"2026-12-31\"` (inclusive) or `:to #inst \"2027-01-01\"`.
   `:to #inst \"2026-12-31\"` omits everything posted on Dec 31, and
   because it drops both sides of those entries the Bilan still BALANCES
   while being wrong.

   Returns the standard computed-statement map plus:

     :fr.bs/total-actif           = sections A + B
     :fr.bs/total-capitaux-propres = section C
     :fr.bs/total-dettes          = sections D + E (provisions + dettes)
     :fr.bs/total-passif          = sections C + D + E
     :fr.bs/difference            = actif − passif; zero for a balanced book
     :fr.bs/balanced?             = whether that difference is zero"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         actif    (money/add (sub "A") (sub "B"))
         capitaux (sub "C")
         dettes   (money/add (sub "D") (sub "E"))
         passif   (money/add capitaux dettes)
         diff     (money/sub actif passif)]
     (assoc computed
            :fr.bs/total-actif            actif
            :fr.bs/total-capitaux-propres capitaux
            :fr.bs/total-dettes           dettes
            :fr.bs/total-passif           passif
            :fr.bs/difference             diff
            :fr.bs/balanced?              (money/zero? diff)))))

(defn check
  "Assert the accounting equation (ACTIF = PASSIF) for the book as of
   `:to`. Returns the `:fr.bs/*` summary of [[compute]].

   A non-zero `:fr.bs/difference` means real money is unaccounted for by
   this DEFINITION — most often an account whose code falls outside every
   `:line/codes` pattern above (a consumer-added account in a range the
   definition does not cover). It does NOT mean an unbalanced ledger: the
   kernel's transact gate refuses any entry whose postings do not sum to
   zero, so the ledger itself cannot drift. Compare against
   `kontor.reporting.trial/trial-balance` (or
   `financial-statements/statement-coverage`) to find the stray account."
  [conn opts]
  (-> (compute conn opts)
      (select-keys [:fr.bs/total-actif :fr.bs/total-capitaux-propres
                    :fr.bs/total-dettes :fr.bs/total-passif
                    :fr.bs/difference :fr.bs/balanced?])))
