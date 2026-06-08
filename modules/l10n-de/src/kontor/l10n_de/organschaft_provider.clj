(ns kontor.l10n-de.organschaft-provider
  "DE Organschaft provider — first consumer of the fiscal-unit
   substrate (ADR-113). Composes with the existing DE CIT provider
   (ADR-104) — the Organschaft does NOT introduce new tax math; it
   simply attributes each Organgesellschaft's `:gewinn-aus-
   gewerbebetrieb` to the Organträger and runs the same KSt + Soli
   + GewSt computation against the consolidated base.

   ## How it works

   Under KStG §14, the Organgesellschaft transfers its entire profit
   to the Organträger via the Ergebnisabführungsvertrag (EAV). The
   Organträger files KSt 1 for its own zvE PLUS the attributed zvE of
   each Organgesellschaft. Each Organgesellschaft files KSt 1 showing
   zvE = 0 with an Anlage OG memo of the attributed amount.

   This provider implements the `:single-base` branch of
   `kontor.tax.fiscal-unit/run-group-tax!`:

     1. Receive a ctx with `:fiscal-unit` set (and `:inputs :members`
        = a map of {<entity-eid> {:gewinn-aus-gewerbebetrieb <bd>}}).
     2. Sum the per-member `:gewinn-aus-gewerbebetrieb` to the
        consolidated `:book-profit`.
     3. Delegate to the existing DE CIT provider (ADR-104) with the
        consolidated `:book-profit` in `:inputs`.
     4. Return the resulting TaxReturnFacts with `:fiscal-unit` set
        and `:regime :de-organschaft` on each component.

   ## Worked example

   Müller Holding (€2M) + Müller Industries (€500k) + Müller
   Logistik (−€1M) → consolidated zvE €1.5M; KSt 15% = €225,000 +
   Soli 5.5% × KSt = €12,375; total KSt + Soli = €237,375.

   ## Out of scope (deferred to ADR-113 v1.1)

   - **GewSt multi-municipal Zerlegung**. v1 of the
     pilot returns the GewSt component at the Organträger's Hebesatz
     only; the per-Gemeinde Zerlegung waits for a `Zerlegung-aware`
     iteration that fans out the GewSt component across municipal
     Hebesätze with a payroll-fraction allocation per GewStG §29.
   - **§15 KStG Bruttomethode + §8b cohabitation** (load-bearing
     gotcha). The cohabitation test fixture for the 4-entity
     scenario (Organträger + 2 OGs + 1 external) requires a §8b
     participation-gain on the external company that the substrate's
     `exception-of` mechanism on the §15 provision suppresses.
     Lands in ADR-113 v1.1.
   - **Retroactive-void**. The :voided-retro status
     transition is seeded; the bitemporal restatement is exercised
     by status-machine tests."
  (:require [kontor.l10n-de.cit-provider :as de-cit]
            [kontor.tax.fiscal-unit :as fu]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]))

(defn- attributed-zve
  "Sum the per-member `:gewinn-aus-gewerbebetrieb` from
   `:inputs :members` into the consolidated zvE that the Organträger
   files. Members map is keyed by entity-eid (or any opaque key the
   consumer chooses) and carries
   `{:gewinn-aus-gewerbebetrieb <BigDecimal>}` per member."
  ^java.math.BigDecimal [members]
  (reduce
   (fn [^java.math.BigDecimal acc [_eid {:keys [gewinn-aus-gewerbebetrieb]
                                         :or {gewinn-aus-gewerbebetrieb 0M}}]]
     (.add acc ^java.math.BigDecimal gewinn-aus-gewerbebetrieb))
   0M
   members))

(defrecord DEOrganschaftProvider [id commodity de-cit-provider]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [fiscal-unit inputs] :as ctx}]
    (when-not fiscal-unit
      (throw (ex-info "DE Organschaft provider requires :fiscal-unit in ctx"
                      {:type :kontor.l10n-de.organschaft/missing-fiscal-unit})))
    (when-not (:members inputs)
      (throw (ex-info "DE Organschaft provider requires :inputs :members (map of entity → {:gewinn-aus-gewerbebetrieb …})"
                      {:type :kontor.l10n-de.organschaft/missing-members
                       :inputs inputs})))
    ;; Sum the per-member book-profit to the consolidated zvE.
    (let [zve (attributed-zve (:members inputs))
          ;; Compose the inputs for the underlying DE CIT provider:
          ;; replace :members with the consolidated :book-profit.
          consolidated-inputs (-> inputs
                                  (dissoc :members)
                                  (assoc :book-profit zve))
          downstream-ctx (-> ctx
                             (assoc :inputs consolidated-inputs)
                             ;; The Organträger files; the parent
                             ;; entity becomes `:entity` for the
                             ;; downstream provider call.
                             (assoc :entity (:parent-entity ctx)))
          ;; Delegate to the existing DE CIT provider — it does the
          ;; full KSt + Soli + GewSt math against the consolidated
          ;; base.
          facts (ptp/period-tax-facts de-cit-provider downstream-ctx)]
      ;; Tag each component with the :regime so consumers + auditors
      ;; can route the result correctly.
      (-> facts
          (update :components
                  (fn [cs]
                    (mapv #(assoc % :regime :de-organschaft) cs)))
          (assoc :fiscal-unit fiscal-unit
                 :provenance
                 (assoc (:provenance facts {})
                        :group-attribution
                        {:regime         :de-organschaft
                         :statute        "KStG §14"
                         :attributed-zve {:amount zve :commodity commodity}
                         :members        (vec (keys (:members inputs)))}))))))

(defn de-organschaft-provider
  "Build the DE Organschaft provider. Wraps a DE CIT provider (ADR-
   104). Re-uses all the same KSt + Soli + GewSt statute data — no
   new statute file needed for v1.

   Config:
     :id              — provider id keyword (default :de-organschaft)
     :commodity       — functional commodity (default :EUR)
     :de-cit-provider — the underlying DE CIT provider; if omitted
                        a default one is constructed via
                        `de-cit/de-cit-provider`"
  [& [{:keys [id commodity de-cit-provider]
       :or {id :de-organschaft commodity :EUR}}]]
  (->DEOrganschaftProvider id commodity
                           (or de-cit-provider
                               (de-cit/de-cit-provider
                                {:id :de-cit-for-organschaft
                                 :commodity commodity}))))

;; ============================================================================
;; Compose elected vs separate (uses kontor.tax.statute/compose-aggregate-of)
;; ============================================================================

(defn elected-vs-separate
  "Audit helper. Given the consolidated (Organschaft-elected)
   TaxReturnFacts AND a sequence of per-entity standalone
   TaxReturnFacts (one per Organgesellschaft + the Organträger
   solo), compose the comparison via
   `kontor.tax.statute/compose-aggregate-of` and surface the
   economic delta.

   Matches components by `:authority` (the DE CIT provider emits two
   components — `:de-bundesfinanzministerium` for KSt + Soli, and
   `:de-municipality` for GewSt — both with `:kind :corporate-
   income-tax`; matching by authority is the unambiguous join).

   Required:
     :authority — the :authority keyword on the components to
                  compose (e.g. :de-bundesfinanzministerium for the
                  KSt + Soli line).

   Returns the elected component with `:composition.economic-delta`
   set (positive when the election saves money).

   For the canonical.1 case (authority
   :de-bundesfinanzministerium):
     elected (group) KSt + Soli  = €237,375
     separate sum (per-entity)   = €316,500 + €79,125 = €395,625
     economic delta              = €158,250."
  [elected-facts separate-facts-seq {:keys [authority]
                                     :or {authority :de-bundesfinanzministerium}}]
  (let [match? #(= authority (:authority %))
        sum-component
        (fn []
          (let [comps (->> separate-facts-seq
                           (mapcat :components)
                           (filter match?))
                amt   (reduce (fn [^java.math.BigDecimal a c]
                                (.add a ^java.math.BigDecimal
                                      (or (get-in c [:liability :amount]) 0M)))
                              0M comps)]
            {:kind      (some-> comps first :kind)
             :authority authority
             :liability {:amount amt
                         :commodity (or (some-> comps first :liability :commodity)
                                        :EUR)}}))
        elected-comp (->> elected-facts :components (filter match?) first)
        separate-comp (sum-component)]
    (when-not elected-comp
      (throw (ex-info "no component with :authority found in elected facts"
                      {:authority authority
                       :available-authorities (set (map :authority
                                                        (:components elected-facts)))})))
    (statute/compose-aggregate-of elected-comp separate-comp)))
