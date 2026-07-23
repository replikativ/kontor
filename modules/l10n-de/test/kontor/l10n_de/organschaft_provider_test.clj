(ns kontor.l10n-de.organschaft-provider-test
  "ADR-113 v1 pilot — DE Organschaft. The first end-to-end consumer
   of the fiscal-unit substrate (`kontor.tax.fiscal-unit`) +
   `compose-aggregate-of` (`kontor.tax.statute`). Reuses the DE CIT
   provider (ADR-104).

   Canonical worked example —.1.2:

     Müller Holding GmbH (+€2,000,000)
     Müller Industries GmbH (+€500,000)
     Müller Logistik GmbH (−€1,000,000)
     ────────────────────────────────────
     Consolidated zvE (post-§14 attribution) = +€1,500,000
     KSt (15%, KStG §23 Abs. 1)              = €225,000
     Soli (5.5% × KSt, SolZG §3 Abs. 1 Nr. 1) = €12,375
     KSt + Soli total                          = €237,375

   The economic delta vs filing separately is recorded via
   `compose-aggregate-of`:
     - Holding solo: €2M zvE → €300,000 KSt + €16,500 Soli = €316,500
     - Industries solo: €500k zvE → €75,000 KSt + €4,125 Soli = €79,125
     - Logistik solo (loss carried forward, ignored — €0)
     - Separate sum: €395,625
     - Elected sum (Organschaft): €237,375
     - Economic delta: €158,250 — the value of the election.

   Source: BMF Anwendungsschreiben 28.03.2024 §14 Abs. 1;
   pwc.com/de Worldwide Tax Summary Germany 2026."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-de.cit-statute :as cit-statute]
            [kontor.l10n-de.organschaft-provider :as organschaft]
            [kontor.tax.fiscal-unit :as fu]))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (cit-statute/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 ;; The 3-entity Müller-Gruppe1.1.
                 {:db/id "e-holding"    :kontor.entity/code "Müller-Holding"
                  :kontor.entity/name "Müller Holding GmbH"}
                 {:db/id "e-industries" :kontor.entity/code "Müller-Industries"
                  :kontor.entity/name "Müller Industries GmbH"}
                 {:db/id "e-logistik"   :kontor.entity/code "Müller-Logistik"
                  :kontor.entity/name "Müller Logistik GmbH"}
                 {:db/id "doc-eav" :kontor.audit-doc/code "MUELLER-EAV-2020"
                  :kontor.audit-doc/type :tax-election
                  :kontor.audit-doc/storage-uri "s3://docs/eav-mueller"
                  :kontor.audit-doc/uploaded-at #inst "2020-12-15"}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- ent [db code] (ref-eid db :kontor.entity/code code))

(defn- elect-mueller-organschaft! [conn]
  (let [db (d/db conn)
        result (fu/elect! conn
                 {:code "Mueller-Gruppe-Organschaft-2025"
                  :name "Müller-Gruppe (Organschaft)"
                  :parent-entity (ent db "Müller-Holding")
                  :regime :de-organschaft
                  :computation-style :single-base
                  :elected-from #inst "2021-01-01"
                  :minimum-term-ends #inst "2025-12-31" ; 5-y EAV
                  :anchor-document (ref-eid db :kontor.audit-doc/code "MUELLER-EAV-2020")
                  :members
                  [{:entity (ent db "Müller-Holding") :role :parent}
                   {:entity (ent db "Müller-Industries") :role :sub
                    :ownership-fraction 1M}
                   {:entity (ent db "Müller-Logistik") :role :sub
                    :ownership-fraction 1M}]})
        fu-eid (get-in result [:tempids "fiscal-unit"])]
    ;; note 197: bring the election in force (:proposed → :elected → :active) —
    ;; run-group-tax! now requires an :active election.
    (fu/activate! conn fu-eid)
    fu-eid))

;; ============================================================================
;; The canonical worked example — KSt + Soli to the cent
;; ============================================================================

(deftest organschaft-worked-example-kst-plus-soli
  (let [conn (bootstrap)
        fu-eid (elect-mueller-organschaft! conn)
        db (d/db conn)
        result (fu/run-group-tax! conn
                 {:fiscal-unit fu-eid
                  :period {:from #inst "2025-01-01" :to #inst "2025-12-31"}
                  :provider (organschaft/de-organschaft-provider)
                  :tax-unit {:hebesatz 470} ; Holding's Hebesatz (Hamburg)
                  :inputs   {:members
                             {(ent db "Müller-Holding")
                              {:gewinn-aus-gewerbebetrieb 2000000M}
                              (ent db "Müller-Industries")
                              {:gewinn-aus-gewerbebetrieb 500000M}
                              (ent db "Müller-Logistik")
                              {:gewinn-aus-gewerbebetrieb -1000000M}}}})
        facts (first (:filings result))
        kst-component (first (filter #(= :de-bundesfinanzministerium
                                         (:authority %))
                                     (:components facts)))]
    (testing "the orchestrator returns one filing (single-base)"
      (is (= 1 (count (:filings result))))
      (is (= [] (:settlements result))))
    (testing "the fiscal-unit is recorded on the facts"
      (is (= fu-eid (:fiscal-unit facts))))
    (testing "the consolidated zvE is the algebraic sum of member contributions"
      (let [zve (get-in facts [:provenance :group-attribution :attributed-zve])]
        (is (= 1500000M (:amount zve)))))
    (testing "each component is tagged :regime :de-organschaft"
      (is (every? #(= :de-organschaft (:regime %))
                  (:components facts))))
    (testing "KSt base = €1.5M consolidated zvE"
      (is (= 1500000M (get-in kst-component [:base :amount]))))
    (testing "gross KSt = 15% × €1.5M = €225,000"
      (is (= 225000.00M (get-in kst-component [:gross-liability :amount]))))
    (testing "Soli = 5.5% × KSt = €12,375"
      ;; Soli is a surtax fold; find it in :surtaxes.
      (let [soli (first (:surtaxes kst-component))]
        (is (some? soli) "Soli surtax present")
        (is (= 12375.000M (:amount soli)))))
    (testing "KSt + Soli total = €237,375 to the cent"
      (is (= 237375.000M (get-in kst-component [:liability :amount]))))))

;; ============================================================================
;; compose-aggregate-of — the economic delta vs separate filing
;; ============================================================================

(deftest organschaft-compose-aggregate-records-economic-delta
  (let [conn (bootstrap)
        fu-eid (elect-mueller-organschaft! conn)
        db (d/db conn)
        ;; Elected — the Organschaft result.
        elected-result (fu/run-group-tax! conn
                         {:fiscal-unit fu-eid
                          :period {:from #inst "2025-01-01" :to #inst "2025-12-31"}
                          :provider (organschaft/de-organschaft-provider)
                          :tax-unit {:hebesatz 470}
                          :inputs   {:members
                                     {(ent db "Müller-Holding")
                                      {:gewinn-aus-gewerbebetrieb 2000000M}
                                      (ent db "Müller-Industries")
                                      {:gewinn-aus-gewerbebetrieb 500000M}
                                      (ent db "Müller-Logistik")
                                      {:gewinn-aus-gewerbebetrieb -1000000M}}}})
        elected-facts (first (:filings elected-result))
        ;; Separate — each Organgesellschaft files solo. Build a
        ;; sequence of standalone DE CIT facts.
        ;; Holding solo: €2M zvE → KSt 300k + Soli 16,500 = 316,500
        ;; Industries: €500k → KSt 75k + Soli 4,125 = 79,125
        ;; Logistik: −€1M (loss; no current-year tax in solo path) → 0
        de-cit (organschaft/de-organschaft-provider)
        run-solo (fn [eid book-profit]
                   (-> (fu/run-group-tax! conn
                         {:fiscal-unit fu-eid ; reuse the same unit
                          :period {:from #inst "2025-01-01"
                                   :to #inst "2025-12-31"}
                          :provider de-cit
                          :tax-unit {:hebesatz 470}
                          :inputs   {:members
                                     {eid {:gewinn-aus-gewerbebetrieb book-profit}}}})
                       :filings first))
        holding-solo    (run-solo (ent db "Müller-Holding")    2000000M)
        industries-solo (run-solo (ent db "Müller-Industries")  500000M)
        ;; Note: Logistik solo with a -€1M base would produce a
        ;; negative KSt under our naive substrate (no §10d
        ;; Verlustvortrag); we EXCLUDE it from the separate sum
        ;; per the worked example's assumption (current-year loss
        ;; carryforward not yet usable). The Organschaft, by
        ;; contrast, nets the loss against the profits at the group
        ;; level — that's exactly the value of the election.
        composed (organschaft/elected-vs-separate
                  elected-facts
                  [holding-solo industries-solo]
                  {:authority :de-bundesfinanzministerium})]
    (testing "elected liability is €237,375 (group KSt + Soli)"
      (is (= 237375.000M (get-in composed [:composition :elected-liability]))))
    (testing "separate sum is €316,500 + €79,125 = €395,625"
      (is (= 395625.000M (get-in composed [:composition :separate-liability]))))
    (testing "economic delta = separate − elected = €158,250 (saving from electing)"
      (is (= 158250.000M (get-in composed [:composition :economic-delta]))))
    (testing "composition method is :aggregate-of"
      (is (= :aggregate-of (get-in composed [:composition :method]))))
    (testing "the elected branch prevails in the output"
      (is (= 237375.000M (get-in composed [:liability :amount]))))))

;; ============================================================================
;; Guard rails
;; ============================================================================

(deftest organschaft-rejects-missing-fiscal-unit
  (let [conn (bootstrap)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":fiscal-unit required"
         (fu/run-group-tax! conn
                            {:period {:from #inst "2025-01-01"
                                      :to #inst "2025-12-31"}
                             :provider (organschaft/de-organschaft-provider)})))))

(deftest organschaft-rejects-missing-members-inputs
  (let [conn (bootstrap)
        fu-eid (elect-mueller-organschaft! conn)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":inputs :members"
         (fu/run-group-tax! conn
                            {:fiscal-unit fu-eid
                             :period {:from #inst "2025-01-01"
                                      :to #inst "2025-12-31"}
                             :provider (organschaft/de-organschaft-provider)
                             :tax-unit {:hebesatz 470}
                             :inputs   {:book-profit 1500000M}}))))) ; wrong shape

(deftest organschaft-provider-zero-zve-handles-empty-members-input
  ;; Edge: members-map present but empty → consolidated zvE = 0;
  ;; KSt = 0; Soli = 0.
  (let [conn (bootstrap)
        fu-eid (elect-mueller-organschaft! conn)
        result (fu/run-group-tax! conn
                 {:fiscal-unit fu-eid
                  :period {:from #inst "2025-01-01" :to #inst "2025-12-31"}
                  :provider (organschaft/de-organschaft-provider)
                  :tax-unit {:hebesatz 470}
                  :inputs   {:members {}}})
        facts (first (:filings result))
        kst (first (filter #(= :de-bundesfinanzministerium (:authority %))
                           (:components facts)))]
    (is (= 0M (get-in kst [:base :amount])))
    (is (= 0M (get-in kst [:gross-liability :amount])))
    (is (= 0M (get-in kst [:liability :amount])))))
