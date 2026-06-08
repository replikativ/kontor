(ns kontor.payroll-fr.wage-types
  "FR-specific `:component-kind` extensions per ADR-079 (Stage R C5).
   Open-set per ADR-071 + ADR-075's `PayrollFacts` opaque-
   component contract.

   Each kind maps to:
     - an `:account-tag` keyword (the consumer's PCG account lookup
       key; see `kontor.payroll-fr.posting-builder`),
     - optionally a `:dsn-rubrique` keyword identifying the DSN
       rubrique the component aggregates into for the monthly DSN
       (see `kontor.payroll-fr.dsn`),
     - optionally `:employer-side?` flag (the engine produces these
       as employer-paid; not deducted from gross),
     - optionally `:posts? false` flag (carry-only metadata that does
       NOT generate a posting leg — base soumise URSSAF, plafond,
       etc.).

   Consumer extension: pass an extra map via `:extras-map` to the
   posting builder + DSN builder to add bespoke component kinds (e.g.
   per-CCN bonus types).

   ## PCG account-tag vocabulary

   The vocabulary mirrors the PCG class-6 / class-4 payroll subset:

     | tag                              | PCG account | Meaning                                |
     |----------------------------------|-------------|----------------------------------------|
     | :fr-payroll-salaires              | 641 / 6411  | Rémunérations du personnel             |
     | :fr-payroll-conges-payes          | 6412        | Congés payés (expense)                 |
     | :fr-payroll-primes                | 6413        | Primes et gratifications               |
     | :fr-payroll-avantages-nature      | 6414        | Indemnités, avantages en nature        |
     | :fr-payroll-er-urssaf             | 6451        | Cotisations URSSAF (employeur)         |
     | :fr-payroll-er-retraite           | 6453        | Cotisations retraite complémentaire    |
     | :fr-payroll-er-assedic            | 6454        | Cotisations Pôle emploi (employeur)    |
     | :fr-payroll-er-prevoyance         | 6455        | Cotisations prévoyance/mutuelle (ER)  |
     | :fr-payroll-conges-accrual        | 6412        | Indemnité congés payés à payer (accr)  |
     | :fr-payroll-personnel-net         | 421         | Personnel — Rémunérations dues (net)   |
     | :fr-payroll-acomptes              | 425         | Avances et acomptes                    |
     | :fr-payroll-oppositions           | 427         | Personnel — Oppositions (saisies)      |
     | :fr-payroll-urssaf                | 431         | URSSAF payable (SS + CSG/CRDS)         |
     | :fr-payroll-retraite              | 4371        | Caisse retraite complémentaire ARRCO/AGIRC |
     | :fr-payroll-pole-emploi           | 4373        | Pôle emploi (ASSEDIC) payable          |
     | :fr-payroll-prevoyance            | 4374        | Prévoyance / mutuelle payable          |
     | :fr-payroll-pas                   | 4421        | État, PAS (prélèvement à la source)    |
     | :fr-payroll-conges-liability      | 4282        | Charges à payer — congés payés         |
     | :fr-payroll-ce                    | 4282        | Comité d'entreprise (CSE) payable      |

   ## DSN rubrique mapping

   DSN block 51 (S21.G00.51 'rémunération') carries the gross + type-
   code. DSN block 78 ('autre élément de revenu') carries non-base
   elements like primes / IK / avantages. The component-kind →
   rubrique map below feeds `kontor.payroll-fr.dsn/build-payload`.

   Reference: doc/decisions.md ADR-079; doc/research/79 §5.3."
  (:require [clojure.set :as set]))

;; ============================================================================
;; Standard component-kind table
;; ============================================================================

(def standard-component-kinds
  "Canonical FR wage-type catalog. Keys are component kinds carried in
   `:payroll-facts/components`. See ADR-079 for the per-row rationale."
  {;; ──────────────────────────────────────────────────────────────
   ;; EARNINGS — debit gross wages expense, credit net wages payable
   ;; ──────────────────────────────────────────────────────────────
   :base-salary            {:account-tag :fr-payroll-salaires
                            :dsn-rubrique :s21-g00-51-001}
   :overtime               {:account-tag :fr-payroll-salaires
                            :dsn-rubrique :s21-g00-51-017}
   :13e-mois               {:account-tag :fr-payroll-primes
                            :dsn-rubrique :s21-g00-51-002}
   :prime-de-fin-d-annee   {:account-tag :fr-payroll-primes
                            :dsn-rubrique :s21-g00-51-002}
   :prime-exceptionnelle   {:account-tag :fr-payroll-primes
                            :dsn-rubrique :s21-g00-51-002}
   :indemnite-conges-payes {:account-tag :fr-payroll-conges-payes
                            :dsn-rubrique :s21-g00-51-003}
   :tickets-restaurant     {:account-tag :fr-payroll-avantages-nature
                            :dsn-rubrique :s21-g00-52-007}
   :avantage-nature-vehicule {:account-tag :fr-payroll-avantages-nature
                              :dsn-rubrique :s21-g00-52-002}
   :avantage-nature-logement {:account-tag :fr-payroll-avantages-nature
                              :dsn-rubrique :s21-g00-52-001}
   :participation          {:account-tag :fr-payroll-primes
                            :dsn-rubrique :s21-g00-51-013}
   :interessement          {:account-tag :fr-payroll-primes
                            :dsn-rubrique :s21-g00-51-014}
   :plan-epargne-entreprise {:account-tag :fr-payroll-primes
                             :dsn-rubrique :s21-g00-51-015}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYEE DEDUCTIONS — credit URSSAF / retraite / Pôle emploi / DGFiP
   ;; ──────────────────────────────────────────────────────────────
   :cotisation-urssaf      {:account-tag :fr-payroll-urssaf
                            :dsn-rubrique :s21-g00-81-cotisation-urssaf}
   :csg-deductible         {:account-tag :fr-payroll-urssaf
                            :dsn-rubrique :s21-g00-81-csg-deductible}
   :csg-non-deductible     {:account-tag :fr-payroll-urssaf
                            :dsn-rubrique :s21-g00-81-csg-non-deductible}
   :crds                   {:account-tag :fr-payroll-urssaf
                            :dsn-rubrique :s21-g00-81-crds}
   :cotisation-arrco-agirc {:account-tag :fr-payroll-retraite
                            :dsn-rubrique :s21-g00-81-arrco-agirc}
   :cotisation-pole-emploi {:account-tag :fr-payroll-pole-emploi
                            :dsn-rubrique :s21-g00-81-pole-emploi}
   :cotisation-prevoyance  {:account-tag :fr-payroll-prevoyance
                            :dsn-rubrique :s21-g00-81-prevoyance}
   :medical-mutuelle       {:account-tag :fr-payroll-prevoyance
                            :dsn-rubrique :s21-g00-81-mutuelle}
   :pas-withholding        {:account-tag :fr-payroll-pas
                            :dsn-rubrique :s21-g00-50-pas}
   :acompte                {:account-tag :fr-payroll-acomptes}
   :opposition             {:account-tag :fr-payroll-oppositions
                            :dsn-rubrique :s21-g00-56-opposition}
   :ticket-restaurant-part-salariale {:account-tag :fr-payroll-avantages-nature}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYER ACCRUALS — debit employer expense, credit payable.
   ;; ──────────────────────────────────────────────────────────────
   :employer-urssaf        {:account-tag :fr-payroll-er-urssaf
                            :employer-side? true
                            :payable-tag :fr-payroll-urssaf
                            :dsn-rubrique :s21-g00-81-er-urssaf}
   :employer-arrco-agirc   {:account-tag :fr-payroll-er-retraite
                            :employer-side? true
                            :payable-tag :fr-payroll-retraite
                            :dsn-rubrique :s21-g00-81-er-arrco-agirc}
   :employer-pole-emploi   {:account-tag :fr-payroll-er-assedic
                            :employer-side? true
                            :payable-tag :fr-payroll-pole-emploi
                            :dsn-rubrique :s21-g00-81-er-pole-emploi}
   :employer-prevoyance    {:account-tag :fr-payroll-er-prevoyance
                            :employer-side? true
                            :payable-tag :fr-payroll-prevoyance
                            :dsn-rubrique :s21-g00-81-er-prevoyance}
   :employer-mutuelle      {:account-tag :fr-payroll-er-prevoyance
                            :employer-side? true
                            :payable-tag :fr-payroll-prevoyance
                            :dsn-rubrique :s21-g00-81-er-mutuelle}
   :employer-formation     {:account-tag :fr-payroll-er-urssaf
                            :employer-side? true
                            :payable-tag :fr-payroll-urssaf
                            :dsn-rubrique :s21-g00-81-er-formation}
   :employer-tickets-restaurant {:account-tag :fr-payroll-avantages-nature
                                 :employer-side? true
                                 :payable-tag :fr-payroll-prevoyance}

   ;; ──────────────────────────────────────────────────────────────
   ;; ACCRUALS — provision congés payés (PCG 6412 → 4282)
   ;; In-band model CA pattern: engine emits the
   ;; accrual component; posting-builder routes automatically.
   ;; ──────────────────────────────────────────────────────────────
   :conges-payes-accrual   {:account-tag :fr-payroll-conges-accrual
                            :employer-side? true
                            :payable-tag :fr-payroll-conges-liability}

   ;; ──────────────────────────────────────────────────────────────
   ;; CARRY-ONLY (NOT posted) — for DSN rubrique reporting only.
   ;; These flow through :jurisdiction-specific-codes per ADR-075 so
   ;; the substrate `check-facts` ignores them.
   ;; ──────────────────────────────────────────────────────────────
   :base-soumise-urssaf      {:posts? false :dsn-rubrique :s21-g00-78-base-urssaf}
   :base-soumise-csg         {:posts? false :dsn-rubrique :s21-g00-78-base-csg}
   :plafond-secu             {:posts? false :dsn-rubrique :s21-g00-78-plafond}
   :tranche-a                {:posts? false :dsn-rubrique :s21-g00-78-tranche-a}
   :tranche-b                {:posts? false :dsn-rubrique :s21-g00-78-tranche-b}
   :tranche-c                {:posts? false :dsn-rubrique :s21-g00-78-tranche-c}
   :smic-mensuel             {:posts? false :dsn-rubrique :s21-g00-78-smic}
   :heures-travaillees       {:posts? false :dsn-rubrique :s21-g00-53-heures}})

(defn merged-catalog
  "Return the catalog merged with consumer-supplied extras-map. The
   extras-map can override or extend the standard catalog. Used by the
   posting builder + DSN builder so consumers can add bespoke kinds
   without code change."
  ([] standard-component-kinds)
  ([extras-map]
   (merge standard-component-kinds (or extras-map {}))))

(defn posts?
  "True iff the kind generates posting legs (i.e. is not carry-only)."
  ([kind] (posts? kind nil))
  ([kind extras-map]
   (let [m (get (merged-catalog extras-map) kind)]
     (not (false? (:posts? m))))))

(defn employer-side?
  "True iff the kind represents an employer-side contribution
   (matches the `:employer-side?` flag in a PayrollFact component)."
  ([kind] (employer-side? kind nil))
  ([kind extras-map]
   (boolean (:employer-side? (get (merged-catalog extras-map) kind)))))

(defn account-tag
  "Return the :account-tag keyword for a kind, or nil. Consumer's
   :accounts map keys on this tag."
  ([kind] (account-tag kind nil))
  ([kind extras-map]
   (:account-tag (get (merged-catalog extras-map) kind))))

(defn payable-tag
  "Return the :payable-tag keyword for an employer-side kind. For
   :employer-urssaf this is :fr-payroll-urssaf (both employee +
   employer halves feed the same URSSAF liability bucket)."
  ([kind] (payable-tag kind nil))
  ([kind extras-map]
   (:payable-tag (get (merged-catalog extras-map) kind))))

(defn dsn-rubrique
  "Return the DSN-rubrique keyword the kind aggregates into for the
   monthly DSN payload. nil = does not aggregate (carry-only metadata
   without a DSN slot, employer-only)."
  ([kind] (dsn-rubrique kind nil))
  ([kind extras-map]
   (:dsn-rubrique (get (merged-catalog extras-map) kind))))

(defn known-kinds
  "Set of all known component kinds (standard + extras)."
  ([] (set (keys standard-component-kinds)))
  ([extras-map] (set (keys (merged-catalog extras-map)))))

(defn unknown-kinds
  "Given a vector of components (from PayrollFacts), return the set of
   kinds NOT present in the catalog. Used by the posting builder to
   fail loud rather than silently drop legs."
  ([components] (unknown-kinds components nil))
  ([components extras-map]
   (set/difference (set (map :kind components))
                   (known-kinds extras-map))))
