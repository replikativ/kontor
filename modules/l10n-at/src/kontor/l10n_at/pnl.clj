(ns kontor.l10n-at.pnl
  "Austrian income statement — Gewinn- und Verlustrechnung per UGB § 231
   Abs. 2 (Gesamtkostenverfahren / total-cost method).

   § 231 UGB fixes two permissible forms: Abs. 2 (Gesamtkostenverfahren,
   the SMB default and the one that maps onto the Einheitskontenrahmen
   posting side) and Abs. 3 (Umsatzkostenverfahren / cost-of-sales). This
   module ships the Abs. 2 shape; a consumer needing Abs. 3 supplies its
   own `:statement/sections` vector against the kernel.

   The § 231 Abs. 2 skeleton (the load-bearing positions):

     Z 1  Umsatzerlöse
     Z 2  Bestandsveränderungen
     Z 3  aktivierte Eigenleistungen
     Z 4  sonstige betriebliche Erträge
     Z 5  Materialaufwand / bezogene Leistungen
     Z 6  Personalaufwand
     Z 7  Abschreibungen
     Z 8  sonstige betriebliche Aufwendungen
       =  Betriebserfolg (Zwischensumme)
     Z 9–15  Finanzerträge / Finanzaufwendungen
       =  Ergebnis vor Steuern
     Z 16 Steuern vom Einkommen und vom Ertrag
       =  Jahresüberschuss / Jahresfehlbetrag  (Z 18)

   Account codes target the RLG-aligned Einheitskontenrahmen skeleton in
   `kontor.l10n-at.chart` (`resources/kontor/l10n_at/kontenrahmen.edn`),
   whose class boundaries line up cleanly with the § 231 positions:
   class 4 = Erträge, class 5 = Materialaufwand, class 6 = Personal-
   aufwand, class 7 = sonstige betriebliche Aufwendungen. That is why
   the expense sections use prefix patterns (`\"5%\"` = all 5xxx): a
   consumer who adds `5010 Wareneinsatz Handelswaren` is picked up
   without touching this definition.

   Umsatzsteuer is NOT a P&L line. Output VAT (USt, class 35xx) is a
   liability and input VAT (Vorsteuer, class 25xx) an asset — both sit on
   the Bilanz, never in Erträge/Aufwendungen — so revenue here is net of
   VAT by construction: the sale posts the net to a 4xxx Erlöskonto and
   the VAT to a separate 3500 USt-Konto.

   Positions the shipped skeleton has no account for, and which therefore
   render as absent rather than zero-valued lines:
   - Z 2 / Z 3 (Bestandsveränderungen, aktivierte Eigenleistungen) — no
     accounts in the skeleton.
   - Z 7 Abschreibungen — the skeleton carries Anlagevermögen (0400/0670)
     but no depreciation-expense account, so there is no Abschreibungen
     section yet. A consumer that books depreciation to a 7010-style
     account adds the section (and the matching accumulated-depreciation
     asset).
   - Z 9–15 Finanzergebnis and Z 16 Steuern vom Einkommen — no accounts.
     Corporate income tax (KSt) is an entity-level computation
     (`kontor.l10n-at.cit-provider`), so this statement stops at the
     Betriebserfolg, which absent a Finanzergebnis equals the
     Jahresüberschuss."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def definition
  "Gesamtkostenverfahren P&L over the `kontor.l10n-at.chart` codes.

   Note-196 F5: line/section commodity is derived from the postings by
   the report engine (`report/resolve-commodity-symbol`), so no `:line/`
   or `:section/commodity` is stamped — an all-EUR AT book resolves to
   :EUR naturally."
  {:statement/name    "Gewinn- und Verlustrechnung (Gesamtkostenverfahren)"
   :statement/country "AT"
   :statement/sections
   [{:section/code  "1"
     :section/label "Umsatzerlöse"
     :section/lines
     [{:line/code "1.1" :line/label "Erlöse 20% USt" :line/codes ["4000"]}
      {:line/code "1.2" :line/label "Erlöse 13% USt" :line/codes ["4010"]}
      {:line/code "1.3" :line/label "Erlöse 10% USt" :line/codes ["4020"]}
      {:line/code "1.4"
       :line/label "Steuerfreie / innergemeinschaftliche / Reverse-Charge Umsätze"
       :line/codes ["4100" "4200" "4300"]}]}

    {:section/code  "5"
     :section/label "Materialaufwand / bezogene Leistungen"
     :section/lines
     [{:line/code "5.1" :line/label "Wareneinsatz / Materialaufwand"
       :line/codes ["5%"]}]}

    {:section/code  "6"
     :section/label "Personalaufwand"
     :section/lines
     [{:line/code "6.1" :line/label "Löhne und Gehälter" :line/codes ["6%"]}]}

    {:section/code  "8"
     :section/label "Sonstige betriebliche Aufwendungen"
     :section/lines
     [{:line/code "8.1" :line/label "Raumaufwand (Miete, Nebenkosten)"
       :line/codes ["7000"]}
      {:line/code "8.2" :line/label "Versicherungen" :line/codes ["7100"]}
      {:line/code "8.3" :line/label "Werbung" :line/codes ["7200"]}
      {:line/code "8.4" :line/label "Reise- und Fahrtaufwand" :line/codes ["7300"]}
      {:line/code "8.5" :line/label "Bürobedarf" :line/codes ["7400"]}
      {:line/code "8.6" :line/label "Lizenzgebühren / Software" :line/codes ["7500"]}
      {:line/code "8.7" :line/label "Telekommunikation" :line/codes ["7600"]}
      {:line/code "8.8" :line/label "Rechts- und Beratungsaufwand" :line/codes ["7700"]}
      {:line/code "8.9" :line/label "Sonstige Aufwendungen" :line/codes ["7800"]}]}]})

(def ^:private sign-map
  "Erträge add, Aufwendungen subtract. Absent a Finanzergebnis (Z 9–15)
   and Steuern vom Einkommen (Z 16) in the skeleton, `:statement/total`
   is both the Betriebserfolg and the § 231 Abs. 2 Z 18
   Jahresüberschuss/Jahresfehlbetrag."
  {"1" :+ "5" :- "6" :- "8" :-})

(defn compute
  "Compute the GKV P&L over the window `[:from, :to)`.

   Opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:from` `:to` `:through` `:as-of-tx` `:include-states` `:ledger`
   `:entity`); `:from` + `:to`/`:through` are what make it a period
   statement.

   `:to` is EXCLUSIVE. For calendar 2026 pass `:through #inst
   \"2026-12-31\"` (inclusive) or `:to #inst \"2027-01-01\"` — a bare
   `:to #inst \"2026-12-31\"` silently drops everything posted on Dec 31.

   Returns the standard computed-statement map plus derived subtotals
   under `:at.pnl/*`:

     :at.pnl/betriebsleistung  = Umsatzerlöse (Z 1; no Z 2–4 accounts)
     :at.pnl/betriebserfolg    = Betriebsleistung − Betriebsaufwand
     :at.pnl/jahresueberschuss = the statement total (= Betriebserfolg
                                 absent a Finanzergebnis / Steuern)"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         leistung (sub "1")
         aufwand  (reduce money/add (map sub ["5" "6" "8"]))]
     (assoc computed
            :at.pnl/betriebsleistung  leistung
            :at.pnl/betriebserfolg    (money/sub leistung aufwand)
            :at.pnl/jahresueberschuss (:statement/total computed)))))
