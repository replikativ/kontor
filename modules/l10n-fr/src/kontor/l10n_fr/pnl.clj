(ns kontor.l10n-fr.pnl
  "French income statement — Compte de résultat (PCG, présentation par
   nature / en liste).

   The Plan Comptable Général (art. 821-1 et seq., système de base) fixes
   the French statutory P&L. The by-nature list form runs three activity
   blocks, each a produits − charges pair, plus the tax block:

     Produits d'exploitation  − Charges d'exploitation  = Résultat d'exploitation
     Produits financiers      − Charges financières     = Résultat financier
       (résultat d'exploitation + résultat financier)   = Résultat courant avant impôts
     Produits exceptionnels   − Charges exceptionnelles = Résultat exceptionnel
       − Participation des salariés − Impôt sur les bénéfices
                                                        = Résultat net

   The block structure comes straight from the PCG account classes:

     Classe 60-65, 68  Charges d'exploitation
     Classe 66         Charges financières
     Classe 67         Charges exceptionnelles
     Classe 69         Participation / impôt sur les bénéfices
     Classe 70-75      Produits d'exploitation
     Classe 76         Produits financiers
     Classe 77         Produits exceptionnels

   so the line codes below are class-prefix patterns (`\"60%\"` = every
   6000-series achats account) rather than the DATEV-style per-account
   enumeration the German module needs — the PCG binds a report position
   to a whole class, so a consumer who adds `6068 Autres achats` is picked
   up without touching this definition.

   Account codes target the PCG skeleton in `kontor.l10n-fr.chart`
   (`resources/kontor/l10n_fr/pcg.edn`). That skeleton is a load-bearing
   subset — it ships class-60/61/62/64 charges and class-70 produits, so
   the financier / exceptionnel / impôt blocks compute to zero on the
   shipped chart and light up as a consumer extends it.

   TVA is NOT revenue: collected TVA (44571-4) is a State liability, input
   TVA (44562/44566) a State receivable — neither is a class-6/7 account,
   so neither can land on a produits or charges line here. The revenue
   lines show net-of-tax turnover by construction.

   Contra / débit-balance produits accounts (rare in FR practice — e.g.
   709 Rabais, remises et ristournes accordés) carry their parent's
   `:kontor.account/type :income`, so `:sign :inflow` nets them against
   gross turnover automatically.

   Commodity: the report engine derives each line's currency from the
   postings it sums (`kontor.reporting.report/resolve-commodity-symbol`,
   note-196 F5); on an EUR book that is EUR, and it matches the engine's
   own default, so no line needs a `:line/commodity` stamp. A section
   `:total-sign-map` is still required and supplied by `compute`."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def definition
  "Compte de résultat par nature (PCG système de base, présentation en
   liste) over the `kontor.l10n-fr.chart` codes."
  {:statement/name    "Compte de résultat"
   :statement/country "FR"
   :statement/sections
   [{:section/code  "1"
     :section/label "Produits d'exploitation"
     :section/lines
     [{:line/code "1.1" :line/label "Ventes de marchandises"
       :line/codes ["707%"]}
      {:line/code "1.2" :line/label "Production vendue — biens"
       :line/codes ["701%" "702%" "703%" "704%" "705%"]}
      {:line/code "1.3" :line/label "Production vendue — services"
       :line/codes ["706%"]}
      {:line/code "1.4" :line/label "Autres produits d'exploitation"
       :line/codes ["708%" "71%" "72%" "74%" "75%"]}]}

    {:section/code  "2"
     :section/label "Charges d'exploitation"
     :section/lines
     [{:line/code "2.1" :line/label "Achats et variations de stock"
       :line/codes ["60%"]}
      {:line/code "2.2" :line/label "Autres achats et charges externes"
       :line/codes ["61%" "62%"]}
      {:line/code "2.3" :line/label "Impôts, taxes et versements assimilés"
       :line/codes ["63%"]}
      {:line/code "2.4" :line/label "Charges de personnel"
       :line/codes ["64%"]}
      {:line/code "2.5" :line/label "Dotations aux amortissements et provisions"
       :line/codes ["68%"]}
      {:line/code "2.6" :line/label "Autres charges d'exploitation"
       :line/codes ["65%"]}]}

    {:section/code  "3"
     :section/label "Produits financiers"
     :section/lines
     [{:line/code "3.1" :line/label "Produits financiers"
       :line/codes ["76%"]}]}

    {:section/code  "4"
     :section/label "Charges financières"
     :section/lines
     [{:line/code "4.1" :line/label "Charges financières"
       :line/codes ["66%"]}]}

    {:section/code  "5"
     :section/label "Produits exceptionnels"
     :section/lines
     [{:line/code "5.1" :line/label "Produits exceptionnels"
       :line/codes ["77%"]}]}

    {:section/code  "6"
     :section/label "Charges exceptionnelles"
     :section/lines
     [{:line/code "6.1" :line/label "Charges exceptionnelles"
       :line/codes ["67%"]}]}

    ;; Classe 69 — Participation des salariés + Impôt sur les bénéfices.
    ;; FR corporate income tax (IS) is an entity-level computation
    ;; (`kontor.l10n-fr.cit-provider`); a consumer that books the IS
    ;; provision to 695 gets it here as the last block before résultat net.
    {:section/code  "7"
     :section/label "Participation et impôt sur les bénéfices"
     :section/lines
     [{:line/code "7.1" :line/label "Participation des salariés"
       :line/codes ["691%"]}
      {:line/code "7.2" :line/label "Impôt sur les bénéfices"
       :line/codes ["695%" "698%"]}]}]})

(def ^:private sign-map
  "Produits blocks add, charges + tax blocks subtract. The statement
   total is the résultat net (résultat de l'exercice)."
  {"1" :+ "2" :- "3" :+ "4" :- "5" :+ "6" :- "7" :-})

(defn compute
  "Compute the Compte de résultat over the window `[:from, :to]`.

   Opts are forwarded to `kontor.reporting.financial-statements/compute-statement`
   (`:from` `:to` `:through` `:as-of-tx` `:include-states` `:ledger` `:entity`);
   `:from` and `:to` are what make it a period statement.

   `:to` is EXCLUSIVE. For calendar 2026 pass `:through #inst \"2026-12-31\"`
   (the inclusive form) or `:to #inst \"2027-01-01\"`. `:to #inst \"2026-12-31\"`
   silently omits everything posted on Dec 31 — where year-end
   depreciation (dotations) and accruals live.

   Returns the standard computed-statement map plus the PCG intermediate
   management balances (soldes intermédiaires de gestion) that the
   list-form résultat is defined by, under the `:fr.pnl/*` keys:

     :fr.pnl/resultat-exploitation         = produits (1) − charges (2)
     :fr.pnl/resultat-financier            = produits (3) − charges (4)
     :fr.pnl/resultat-courant-avant-impots = exploitation + financier
     :fr.pnl/resultat-exceptionnel         = produits (5) − charges (6)
     :fr.pnl/resultat-net                  = the statement total"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         exploit  (money/sub (sub "1") (sub "2"))
         financ   (money/sub (sub "3") (sub "4"))
         courant  (money/add exploit financ)
         except   (money/sub (sub "5") (sub "6"))]
     (assoc computed
            :fr.pnl/resultat-exploitation         exploit
            :fr.pnl/resultat-financier            financ
            :fr.pnl/resultat-courant-avant-impots courant
            :fr.pnl/resultat-exceptionnel         except
            :fr.pnl/resultat-net                  (:statement/total computed)))))
