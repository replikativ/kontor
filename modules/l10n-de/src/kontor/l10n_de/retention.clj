(ns kontor.l10n-de.retention
  "DE per-(jurisdiction × category) retention-policy seeds (ADR-094 §3 +
.3).

   Kernel ships the `:kontor.retention-policy/*` shape (`src/kontor/schema.clj`
   ADR-050). Per-country statute durations + legal-basis citations live
   in `kontor-l10n-<cc>`. This namespace seeds the DE statutes that
   showcase 06 + the people-record companion exercise.

   ## Seed coverage

   - **`:hr-personnel`** umbrella — HGB §257 + AO §147 financial-records
     posture extended to HR documents that participate in payroll +
     SoR auditing. 6 years. Anonymize.
   - **`:hr-track-record`** — career history, performance reviews,
     training records. 3 years per BGB §195 regular limitation +
     BDSG §26 + DSGVO Art. 5(1)(e). Anonymize.
   - **`:hr-medical`** — occupational-health data subject to GefStoffV
     §10a + ArbMedVV §7. **30 years** for documented hazardous-exposure
     records. Archive to cold storage (do not auto-purge).
   - **`:hr-activity-content`** — screen / keystroke / webcam content.
     0-year default floor with explicit purge action: substrate-level
     statement that the default is \"delete immediately unless the
     consumer documents a purpose + DPIA + Betriebsvereinbarung.\"
   - **`:hr-monitoring-consent`** — the DPIAs / LIAs / consent forms
     themselves. 10 years per DSGVO Art. 5(2) accountability +
     BDSG §38 documentation duties.
   - **`:hr-grievance`** — disciplinary records, BetrVG §82-83 employee
     access. 3 years for active records; archive on termination per
     BAG 2 AZR 38/13.
   - **`:payroll-filing`** — payroll regulator filings (LStBV, SV-
     Meldungen). 6 years per AO §147 + §28f SGB IV.

   Consumers extend by transacting additional `:retention-policy` rows
   with `:kontor.retention-policy/jurisdiction [:kontor.country/code \"DE\"]`. The
   sweeper merges them with these seeds.

   ## Discipline

   Per ADR-005 / ADR-094: the durations cited here are the project's
   reading of the named statutes. They are NOT legal advice. A
   consumer-side retention-counsel review is the auditor-grade check;
   these seeds are a defensible starting point a maintainer can hand
   to legal.

   ## Install

   Run AFTER `kontor.core/install-schema!` + after a `:country` row
   with `:kontor.country/code \"DE\"` exists (the chart installer ensures
   this if you also call `kontor.l10n-de.chart/install!`)."
  (:require [datahike.api :as d]))

(def ^:private retention-seeds
  "Effective from 2018-05-25 (GDPR enforcement) onward. Effective-
   until is left open (`nil`) on each row so the sweeper consults them
   against any post-GDPR anchor date. Per ADR-026 effective-dating, a
   statute amendment can supersede a row by adding a new row with the
   later effective-from + closing this row's effective-until."
  [{:kontor.retention-policy/code             "DE-HGB257-hr-personnel"
    :kontor.retention-policy/applies-to       [:audit-doc]
    :kontor.retention-policy/category         :hr-personnel
    :kontor.retention-policy/jurisdiction     [:kontor.country/code "DE"]
    :kontor.retention-policy/duration-years   6
    :kontor.retention-policy/triggered-by     :kontor.audit-doc/uploaded-at
    :kontor.retention-policy/expiry-action    :anonymize
    :kontor.retention-policy/legal-basis      "HGB §257(4) + AO §147(3) (6-year retention for handelsrechtliche / steuerrechtliche Aufzeichnungen) + BDSG §26"
    :kontor.retention-policy/effective-from   #inst "2018-05-25"
    :kontor.retention-policy/state            :active}

   {:kontor.retention-policy/code             "DE-BDSG-hr-track-record"
    :kontor.retention-policy/applies-to       [:audit-doc]
    :kontor.retention-policy/category         :hr-track-record
    :kontor.retention-policy/jurisdiction     [:kontor.country/code "DE"]
    :kontor.retention-policy/duration-years   3
    :kontor.retention-policy/triggered-by     :kontor.audit-doc/uploaded-at
    :kontor.retention-policy/expiry-action    :anonymize
    :kontor.retention-policy/legal-basis      "BGB §195 (Regelmäßige Verjährung 3 Jahre) + BDSG §26 + DSGVO Art. 5(1)(e) Speicherbegrenzung"
    :kontor.retention-policy/effective-from   #inst "2018-05-25"
    :kontor.retention-policy/state            :active}

   {:kontor.retention-policy/code             "DE-GefStoffV-hr-medical"
    :kontor.retention-policy/applies-to       [:audit-doc]
    :kontor.retention-policy/category         :hr-medical
    :kontor.retention-policy/jurisdiction     [:kontor.country/code "DE"]
    :kontor.retention-policy/duration-years   30
    :kontor.retention-policy/triggered-by     :kontor.audit-doc/uploaded-at
    :kontor.retention-policy/expiry-action    :archive-to-cold-storage
    :kontor.retention-policy/legal-basis      "GefStoffV §10a (Gefahrstoffe — 40 Jahre für ärztliche Untersuchungen) + ArbMedVV §7 (arbeitsmedizinische Vorsorge, mind. 10 Jahre, bis 40 Jahre)"
    :kontor.retention-policy/effective-from   #inst "2018-05-25"
    :kontor.retention-policy/state            :active}

   {:kontor.retention-policy/code             "DE-DSGVO-hr-activity-content-floor"
    :kontor.retention-policy/applies-to       [:audit-doc]
    :kontor.retention-policy/category         :hr-activity-content
    :kontor.retention-policy/jurisdiction     [:kontor.country/code "DE"]
    :kontor.retention-policy/duration-years   0
    :kontor.retention-policy/triggered-by     :kontor.audit-doc/uploaded-at
    :kontor.retention-policy/expiry-action    :purge
    :kontor.retention-policy/legal-basis      "DSGVO Art. 5(1)(c) Datenminimierung + BAG-Rechtsprechung (1 ABR 22/21 + 2 AZR 200/22) — Default: sofortige Löschung wenn nicht aktiv begründet (Zweckbindung + DPIA + Betriebsvereinbarung)"
    :kontor.retention-policy/effective-from   #inst "2018-05-25"
    :kontor.retention-policy/state            :active}

   {:kontor.retention-policy/code             "DE-DSGVO-hr-monitoring-consent"
    :kontor.retention-policy/applies-to       [:audit-doc]
    :kontor.retention-policy/category         :hr-monitoring-consent
    :kontor.retention-policy/jurisdiction     [:kontor.country/code "DE"]
    :kontor.retention-policy/duration-years   10
    :kontor.retention-policy/triggered-by     :kontor.audit-doc/uploaded-at
    :kontor.retention-policy/expiry-action    :archive-to-cold-storage
    :kontor.retention-policy/legal-basis      "DSGVO Art. 5(2) Rechenschaftspflicht + BDSG §38 Dokumentationspflichten — DPIAs / LIAs / Einwilligungen werden nach Wegfall des Grundes archiviert, nicht gelöscht"
    :kontor.retention-policy/effective-from   #inst "2018-05-25"
    :kontor.retention-policy/state            :active}

   {:kontor.retention-policy/code             "DE-BetrVG-hr-grievance"
    :kontor.retention-policy/applies-to       [:audit-doc]
    :kontor.retention-policy/category         :hr-grievance
    :kontor.retention-policy/jurisdiction     [:kontor.country/code "DE"]
    :kontor.retention-policy/duration-years   3
    :kontor.retention-policy/triggered-by     :kontor.audit-doc/uploaded-at
    :kontor.retention-policy/expiry-action    :anonymize
    :kontor.retention-policy/legal-basis      "BetrVG §82-§83 (Einsichtsrecht in Personalakte) + BAG 2 AZR 38/13 (Abmahnungen) + DSGVO Art. 5(1)(e)"
    :kontor.retention-policy/effective-from   #inst "2018-05-25"
    :kontor.retention-policy/state            :active}

   {:kontor.retention-policy/code             "DE-AO147-payroll-filing"
    :kontor.retention-policy/applies-to       [:audit-doc]
    :kontor.retention-policy/category         :payroll-filing
    :kontor.retention-policy/jurisdiction     [:kontor.country/code "DE"]
    :kontor.retention-policy/duration-years   6
    :kontor.retention-policy/triggered-by     :kontor.audit-doc/uploaded-at
    :kontor.retention-policy/expiry-action    :archive-to-cold-storage
    :kontor.retention-policy/legal-basis      "AO §147(3) + §28f SGB IV (Beitragsnachweise + LStBV-Meldungen) — 6 Jahre Aufbewahrungsfrist"
    :kontor.retention-policy/effective-from   #inst "2018-05-25"
    :kontor.retention-policy/state            :active}])

(defn install!
  "Idempotently transact the DE retention-policy seeds.

   Run AFTER `kontor.core/install-schema!` and AFTER a `:country` row
   with `:kontor.country/code \"DE\"` exists. The function ensures that
   `:country` row, since not every consumer also installs the
   l10n-de.chart."
  [conn]
  ;; Ensure :country row exists (idempotent — :db.unique/identity).
  (d/transact conn [{:kontor.country/code "DE" :kontor.country/name "Germany"
                     :kontor.country/active true}])
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :in $ ?code
                         :where [?e :kontor.retention-policy/code ?code]]
                       db "DE-HGB257-hr-personnel"))]
    (when-not already?
      (d/transact conn retention-seeds))))
