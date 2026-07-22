(ns kontor.payroll-at.wage-types
  "Austrian Personalverrechnung wage-type vocabulary + the default
   wage-type → RLG-1 account-code map (ADR-072).

   This is data; consumers can replace the map at posting time. The
   keyword vocabulary itself is the stable seam — engine adapters
   (BMD, RZL) normalize their per-engine wage-type codes into these
   keywords, and the posting builder + accrual builders dispatch on
   them.

   References:
     - ADR-072 (kontor-payroll-at)
     - doc/research/80-payroll-at-research.md
     - RLG-1 (Einheitskontenrahmen) standard chart-of-accounts numbering")

;; ============================================================================
;; The vocabulary
;; ============================================================================

(def wage-types
  "The complete keyword vocabulary of Austrian payroll wage types
   the v1 adapter understands. Open-set — consumers can extend, but
   new keys without a map entry will throw at posting time unless the
   consumer supplies a custom account map.

   Earnings (expense side):
     :grundgehalt            — base salary (monthly)
     :überstunden            — overtime
     :urlaubsremuneration    — 13. Gehalt; June; Sonderzahlung @ 6 %
     :weihnachtsremuneration — 14. Gehalt; November; Sonderzahlung @ 6 %
     :sachbezüge             — non-cash benefits (Sachbezugswertvo)
     :sv-arbeitgeber         — employer SV expense (~21.23 %)
     :dienstgeberbeitrag-fond — DB FLAG (4.1 %)
     :zuschlag-zum-db        — DZ (~0.32–0.40 % per Bundesland)
     :kommunalsteuer         — KomSt (3 %)

   Withholdings (liability side):
     :lohnsteuer             — LSt withholding
     :sv-arbeitnehmer        — employee SV withholding (~18.12 %)

   Net pay (clearing → cash):
     :nettogehalt            — the final settle leg to 3700

   The kernel does not require consumers to use this exact vocabulary;
   it documents what BmdGlProvider + RzlGlProvider emit."
  #{:grundgehalt :überstunden
    :urlaubsremuneration :weihnachtsremuneration
    :sachbezüge
    :sv-arbeitgeber :dienstgeberbeitrag-fond :zuschlag-zum-db
    :kommunalsteuer
    :lohnsteuer :sv-arbeitnehmer
    :nettogehalt})

;; ============================================================================
;; Default RLG-1 account-code map
;; ============================================================================

(def default-rlg-1-map
  "Default {:wage-type → :rlg-1 account-code string} map. Mirrors the
   pattern of the German :skr-04 mapping in kontor-payroll-de-datev.
   Pure data — consumers can fork it and pass an override map to
   `kontor.payroll-at.posting-builder/build-tx-data`.

   The codes follow the Einheitskontenrahmen (KFS/BW 6, Kammer der
   Wirtschaftstreuhänder). The Kontengruppen that matter here:
     6xxx      — Personalaufwand (Aufwand)
     350 – 359 — Verbindlichkeiten aus Steuern (LSt, USt, KomSt, DB/DZ)
     360 – 369 — Verbindlichkeiten im Rahmen der sozialen Sicherheit
                 (SV, Dienstnehmer- UND Dienstgeberanteil)
     370 – 389 — Übrige sonstige Verbindlichkeiten (Nettolohn, Clearing)

   6000 Gehälter is deliberately SHARED with the l10n-at Kontenrahmen —
   payroll expense belongs on the book's own salary account. Liability
   codes must NOT be shared: see the :lohnsteuer note below.

   :nettogehalt is special — it's the clearing leg to the employee's
   payable; it has NO expense side (the expense is split across the
   gross-pay + employer-borne lines). The map entry exists so the
   builder can resolve a single account ref."
  {;; Earnings — Aufwand
   :grundgehalt              "6000"   ; Gehälter
   :überstunden              "6000"   ; same account; Lohnart distinguishes
   :urlaubsremuneration      "6400"   ; Urlaubsremuneration / 13.
   :weihnachtsremuneration   "6410"   ; Weihnachtsremuneration / 14.
   :sachbezüge               "6800"   ; Sachbezugsaufwand

   ;; Employer-borne contributions — Aufwand
   :sv-arbeitgeber           "6500"   ; Sozialaufwand-Arbeitgeber
   :dienstgeberbeitrag-fond  "6510"   ; DB-FLAG
   :zuschlag-zum-db          "6530"   ; DZ
   :kommunalsteuer           "6520"   ; KomSt

   ;; Withholdings — Verbindlichkeit (credit side)
   ;; 350–359 is "Verbindlichkeiten aus Steuern", which KFS/BW 6 lists as
   ;; covering "Lohnsteuer, Umsatzsteuer, Kommunalsteuer" together — so LSt
   ;; and USt share a Kontengruppe and MUST NOT share an account. This was
   ;; "3500", which the l10n-at Kontenrahmen ships as Umsatzsteuer 20%
   ;; tagged :uva-022-ust; a payroll run credited withheld Lohnsteuer into
   ;; the output-VAT account and inflated box 022 of the filed UVA by the
   ;; withholding. Note 194 §1 P0-4.
   :lohnsteuer               "3540"   ; LSt-Verbindlichkeit (350–359 Steuern)
   :sv-arbeitnehmer          "3600"   ; SV-Verbindlichkeit (360–369 soziale Sicherheit)

   ;; Net pay — Verbindlichkeit
   :nettogehalt              "3700"   ; Verbindlichkeit Lohn
   })

(def default-payable-codes
  "The 'where does this credit go' for the employer-borne side. The
   posting-builder uses this to route the credit leg of each Aufwand
   line — DB goes to a different payable than KomSt, etc."
  {:sv-arbeitgeber           "3600"   ; same SV-Verbindlichkeit (360–369)
   :dienstgeberbeitrag-fond  "3550"   ; DB-Verbindlichkeit (Finanzamt → 350–359)
   :zuschlag-zum-db          "3550"   ; DZ → same DB-Verbindlichkeit
   ;; KomSt is a Steuer owed to the Gemeinde, which KFS/BW 6 places in
   ;; 350–359 ("Verbindlichkeiten gegenüber Gemeinde/Stadtkasse"), not in
   ;; the 360–369 soziale-Sicherheit group where it used to sit.
   :kommunalsteuer           "3545"   ; KomSt-Verbindlichkeit (350–359)
   ;; a non-cash clearing account is neither a Steuer nor soziale
   ;; Sicherheit → 370–389 übrige sonstige Verbindlichkeiten
   :sachbezüge               "3790"   ; clearing — sachbezug is non-cash;
                                       ; the contra-leg is recorded as
                                       ; a clearing entry that consumer
                                       ; reconciles to the actual benefit
   })

(defn account-code-for
  "Resolve the RLG-1 account code for `wage-type`. With an override
   map, the override wins; missing keys fall through to
   default-rlg-1-map. Throws if neither has the key."
  ([wage-type] (account-code-for wage-type {}))
  ([wage-type override-map]
   (or (get override-map wage-type)
       (get default-rlg-1-map wage-type)
       (throw (ex-info "Unknown wage-type"
                       {:wage-type wage-type
                        :known     (vec wage-types)})))))

(defn payable-code-for
  "The credit-side payable for an employer-borne contribution."
  ([wage-type] (payable-code-for wage-type {}))
  ([wage-type override-map]
   (or (get override-map wage-type)
       (get default-payable-codes wage-type)
       (throw (ex-info "No payable code for wage-type"
                       {:wage-type wage-type
                        :known     (vec (keys default-payable-codes))})))))

;; ============================================================================
;; Engine wage-type code → kontor keyword
;; ============================================================================

(def bmd-wage-code-map
  "Default wage-code mappings — consumer-supplied :column-mapping
   overrides at adapter boundary."
  {"0001" :grundgehalt
   "0002" :überstunden
   "0050" :urlaubsremuneration
   "0060" :weihnachtsremuneration
   "0100" :sachbezüge
   "0200" :lohnsteuer
   "0210" :sv-arbeitnehmer
   "0300" :sv-arbeitgeber
   "0310" :dienstgeberbeitrag-fond
   "0320" :zuschlag-zum-db
   "0330" :kommunalsteuer
   "9000" :nettogehalt})

(def rzl-wage-code-map
  "Default wage-code mappings — consumer-supplied :column-mapping
   overrides at adapter boundary."
  {"GRU" :grundgehalt
   "UST" :überstunden
   "URL" :urlaubsremuneration
   "WEI" :weihnachtsremuneration
   "SAB" :sachbezüge
   "LST" :lohnsteuer
   "SVN" :sv-arbeitnehmer
   "SVG" :sv-arbeitgeber
   "DBF" :dienstgeberbeitrag-fond
   "ZDB" :zuschlag-zum-db
   "KST" :kommunalsteuer
   "NET" :nettogehalt})
