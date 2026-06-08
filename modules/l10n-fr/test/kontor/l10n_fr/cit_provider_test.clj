(ns kontor.l10n-fr.cit-provider-test
  "FR corporate income tax provider tests — ADR-101 substrate's FR
   consumer (ADR-104 template, applied to France). Validates that the
   statute-as-data path (`:parameter` + `:provision` rows +
   `kontor.tax.statute/apply-provisions` fold) computes real FR IS against
   published worked examples.

   Worked examples cited:

   - **§1 standard 25 %** — non-PME (CA HT > €10 M), bénéfice fiscal
     €4 M, CGE fires above €763 k. Total €1,007,821.
   - **§2 PME 15 %/25 % + CGE** —
     CA HT €8 M, fully paid capital, 80 % held by individuals,
     bénéfice fiscal €4 M; CGE fires because CA HT ≥ €7.63 M.
     Total €1,003,430.75 (some sources round to €1,003,431 — we keep
     the exact computation).
     Source: https://www.legifiscal.fr/reperes-fiscaux/contribution-sociale-impot-societe-is-33-2024.html
   - **§3 PME exempt from CGE** — small SARL: CA HT €5 M, bénéfice
     fiscal €1 M, PME + CGE-exempt (CA HT < €7.63 M). No CGE. Plus a
     CIR of €500 k qualifying expenses (refundable for PME → negative
     liability).
   - **§4 régime mère-fille** — verifies the 5 % quote-part add-back
     on participation dividends.
     Source: https://bofip.impots.gouv.fr/bofip/3719-PGP.html

   Plus substrate-property sanity checks: install! idempotence,
   provenance trail, `:tax-unit :pme?` swaps the schedule, missing
   book-profit raises."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-fr.cit-provider :as fr-cit]
            [kontor.l10n-fr.cit-statute :as cit-statute]
            [kontor.tax.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the FR CIT statute installed."
  []
  (let [conn (core/create-test-db)]
    (cit-statute/install! conn)
    conn))

(defn- compute
  "Run the FR CIT provider over `inputs` + a `tax-unit`, return the
   `TaxReturnFacts`. Convenience wrapper."
  [tax-unit inputs]
  (let [conn (fresh)]
    (ptp/period-tax-facts
     (fr-cit/fr-cit-provider {})
     {:entity   :sas
      :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
      :db       (d/db conn)
      :as-of    #inst "2025-12-31"
      :tax-unit tax-unit
      :inputs   inputs})))

(defn- is-component
  "Pull the IS component out of a `TaxReturnFacts`."
  [facts]
  (->> facts :components (filter #(= :fr-dgfip (:authority %))) first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. Standard 25 % rate — non-PME hitting the CGE
;; ============================================================================

(deftest standard-25pct-with-cge-non-pme
  (testing "non-PME (CA HT > €10 M), bénéfice fiscal €4 M, CGE fires (no exemption)"
    (let [facts (compute {:pme?        false
                          :cge-exempt? false}
                         {:book-profit 4000000M})
          is-c  (is-component facts)]
      (testing "base = book-profit (no adjustments fire)"
        (is (== 4000000M (:amount (:base is-c))))
        (is (nil? (:base-transform is-c))))
      (testing "schedule = flat 25 %; gross = 4 000 000 × 25 % = 1 000 000"
        (is (= :flat (:kontor.schedule/type (:schedule is-c))))
        (is (== 0.25M (:rate (:schedule is-c))))
        (is (== 1000000M (:amount (:gross-liability is-c)))))
      (testing "CGE = (1 000 000 − 763 000) × 3.3 % = 7 821"
        (is (= 1 (count (:surtaxes is-c))))
        (let [cge (first (:surtaxes is-c))]
          (is (= :fr-cge (:code cge)))
          (is (== 7821M (:amount cge)))))
      (testing "no credits (no CIR expenses)"
        (is (empty? (:credits is-c))))
      (testing "liability = 1 000 000 + 7 821 = 1 007 821"
        (is (== 1007821M (:amount (:liability is-c))))
        (is (== 1007821M (total-liability facts))))
      (testing "no regime active (flat 25 %, not PME)"
        (is (nil? (:regime is-c)))))))

;; ============================================================================
;; §2. PME 15 % / 25 % + CGE — the
;; ============================================================================

(deftest pme-15-25-with-cge-note-109-worked-example
  (testing "SAS, CA HT €8 M (PME-eligible, NOT CGE-exempt because CA HT ≥ €7.63 M);
            bénéfice fiscal €4 M; total €1,003,430.75"
    (let [facts (compute {:pme?        true
                          :cge-exempt? false}
                         {:book-profit 4000000M})
          is-c  (is-component facts)]
      (testing "base = book-profit (no mère-fille / no other adjustments)"
        (is (== 4000000M (:amount (:base is-c))))
        (is (nil? (:base-transform is-c))))
      (testing "schedule = PME progressive (15 % to €42 500, 25 % above)"
        (is (= :progressive-bracket (:kontor.schedule/type (:schedule is-c))))
        (is (= [{:rate 0.15M :upper 42500M}
                {:rate 0.25M :upper nil}]
               (:brackets (:schedule is-c)))))
      (testing "IS gross = 42 500 × 15 % + 3 957 500 × 25 % = 6 375 + 989 375 = 995 750"
        (is (== 995750M (:amount (:gross-liability is-c)))))
      (testing "CGE = (995 750 − 763 000) × 3.3 % = 232 750 × 3.3 % = 7 680.75"
        (is (= 1 (count (:surtaxes is-c))))
        (let [cge (first (:surtaxes is-c))]
          (is (= :fr-cge (:code cge)))
          (is (== 7680.75M (:amount cge)))))
      (testing "liability = 995 750 + 7 680.75 = 1 003 430.75"
        (is (== 1003430.75M (:amount (:liability is-c))))
        (is (== 1003430.75M (total-liability facts))))
      (testing "regime = :fr-is-pme (the PME schedule fired)"
        (is (= :fr-is-pme (:regime is-c)))))))

;; ============================================================================
;; §3. PME CGE-exempt + CIR refundable
;; ============================================================================

(deftest pme-cge-exempt-with-refundable-cir
  (testing "small SARL: CA HT €5 M (PME + CGE-exempt), bénéfice fiscal €1 M;
            CIR refundable: €500 k qualifying expenses × 30 % = €150 k credit;
            IS gross 245 750; net liability = 245 750 − 150 000 = 95 750"
    (let [facts (compute {:pme?            true
                          :cge-exempt?     true
                          :cir-refundable? true}
                         {:book-profit             1000000M
                          :cir-qualifying-expenses 500000M})
          is-c  (is-component facts)]
      (testing "schedule = PME progressive"
        (is (= :progressive-bracket (:kontor.schedule/type (:schedule is-c)))))
      (testing "IS gross = 42 500 × 15 % + 957 500 × 25 % = 6 375 + 239 375 = 245 750"
        (is (== 245750M (:amount (:gross-liability is-c)))))
      (testing "CIR = 500 000 × 30 % = 150 000 (refundable)"
        (is (= 1 (count (:credits is-c))))
        (let [cir (first (:credits is-c))]
          (is (= :fr-cir (:code cir)))
          (is (== 150000M (:amount cir)))
          (is (true? (:refundable? cir)))))
      (testing "no CGE (PME-exempt: :tax-unit :cge-exempt? true gates the provision off)"
        (is (empty? (:surtaxes is-c))))
      (testing "liability = 245 750 − 150 000 = 95 750"
        (is (== 95750M (:amount (:liability is-c))))))))

(deftest cir-refundable-credit-can-drive-liability-negative
  (testing "refundable CIR exceeding IS gross drives liability negative (a refund)"
    ;; bénéfice fiscal €50 k @ flat 25 % = 12 500; CIR €100 k × 30 % = 30 000.
    ;; Refundable ⇒ liability = 12 500 − 30 000 = −17 500 (a transfer to taxpayer).
    (let [facts (compute {:pme? false :cge-exempt? true :cir-refundable? true}
                         {:book-profit             50000M
                          :cir-qualifying-expenses 100000M})
          is-c  (is-component facts)]
      (is (== 12500M  (:amount (:gross-liability is-c))))
      (is (== 30000M  (:amount (first (:credits is-c)))))
      (is (== -17500M (:amount (:liability is-c)))
          "refundable credit + refundable? true ⇒ liability may go negative"))))

;; ============================================================================
;; §4. Régime mère-fille — the 5 % quote-part add-back
;; ============================================================================

(deftest mere-fille-5pct-addback-on-participation-dividends
  (testing "qualifying participation dividends add back 5 % quote-part to base"
    ;; bénéfice fiscal €500 k (excludes the dividends per French
    ;; réintégration practice); dividends €200 k ⇒ 5 % × 200k = 10k addback.
    ;; Base = 510 k; flat 25 % = 127 500; CGE 0 (below abattement); total 127 500.
    (let [facts (compute {:pme? false :cge-exempt? true}
                         {:book-profit             500000M
                          :participation-dividends 200000M})
          is-c  (is-component facts)]
      (testing "base = 500 000 + 200 000 × 5 % = 510 000"
        (is (== 510000M (:amount (:base is-c))))
        (let [base-items (:items (:base-transform is-c))]
          (is (= 1 (count base-items)))
          (is (= :fr-mere-fille-quote-part (:code (first base-items))))
          (is (== 10000M (:amount (first base-items))))))
      (testing "IS gross = 510 000 × 25 % = 127 500"
        (is (== 127500M (:amount (:gross-liability is-c)))))
      (testing "no CGE (exempt) and no surtaxes; liability = gross"
        (is (== 127500M (:amount (:liability is-c))))))))

;; ============================================================================
;; §5. PME smaller —(CGE does NOT fire)
;; ============================================================================

(deftest pme-without-cge-when-is-below-abattement
  (testing "SAS PME, CA HT €8 M, bénéfice €3 M;
            IS 745 750 < €763 000 abattement ⇒ CGE = 0"
    (let [facts (compute {:pme? true :cge-exempt? false}
                         {:book-profit 3000000M})
          is-c  (is-component facts)]
      (testing "IS gross = 42 500 × 15 % + 2 957 500 × 25 % = 6 375 + 739 375 = 745 750"
        (is (== 745750M (:amount (:gross-liability is-c)))))
      (testing "CGE provision fires but computes 0 (745 750 < 763 000)"
        ;; The :surtax provision is gated only on NOT CGE-exempt; it
        ;; still fires here. The compute-fn returns 0, which is the
        ;; correct number — the abattement is computed inside the fn.
        (is (= 1 (count (:surtaxes is-c))))
        (is (== 0M (:amount (first (:surtaxes is-c))))))
      (testing "liability = 745 750 (no surtax effect at this level)"
        (is (== 745750M (:amount (:liability is-c))))))))

;; ============================================================================
;; §6. Substrate-property sanity
;; ============================================================================

(deftest book-profit-missing-raises
  (testing "FR CIT requires :inputs :book-profit; absent → ex-info"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"book-profit"
                            (ptp/period-tax-facts
                             (fr-cit/fr-cit-provider {})
                             {:entity   :sas
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {:pme? false}
                              :inputs   {}}))))))

(deftest provisions-applied-recorded-in-provenance
  (testing "the IS component records every provision that fired in :provenance"
    (let [facts (compute {:pme? true :cge-exempt? false :cir-refundable? true}
                         {:book-profit             4000000M
                          :participation-dividends 100000M
                          :cir-qualifying-expenses 200000M})
          is-c  (is-component facts)]
      (is (= #{"FR-CGI-219-I-b-PME"
               "FR-CGI-145-216-mere-fille"
               "FR-CGI-235-ter-ZC-CGE"
               "FR-CGI-244-quater-B-CIR"}
             (set (-> is-c :provenance :provisions-applied)))
          "all four FR provisions fire when all inputs are present + PME true"))))

(deftest provisions-skipped-when-driver-fact-absent
  (testing "absent driver facts ⇒ provisions silently no-op"
    ;; No participation-dividends, no cir-qualifying-expenses, PME true,
    ;; CGE-exempt true. Only the PME schedule-override should fire.
    (let [facts (compute {:pme? true :cge-exempt? true}
                         {:book-profit 100000M})
          is-c  (is-component facts)]
      (is (= #{"FR-CGI-219-I-b-PME"}
             (set (-> is-c :provenance :provisions-applied)))
          "only the PME swap fires; mère-fille / CGE / CIR all silently absent"))))

(deftest installable-is-idempotent
  (testing "install! is idempotent (re-run is a no-op on identity attrs)"
    (let [conn (core/create-test-db)]
      (cit-statute/install! conn)
      (cit-statute/install! conn)
      (let [n-params   (count (d/q '[:find ?p :where [?p :kontor.parameter/code _]]
                                   (d/db conn)))
            n-provs    (count (d/q '[:find ?p :where [?p :kontor.provision/code _]]
                                   (d/db conn)))
            n-brackets (count (d/q '[:find ?b :where [?b :kontor.parameter-bracket/index _]]
                                   (d/db conn)))]
        (is (= (count cit-statute/parameters) n-params))
        (is (= (count cit-statute/provisions) n-provs))
        (is (= (count cit-statute/parameter-brackets) n-brackets))))))

(deftest functional-commodity-is-eur-on-every-money
  (let [facts (compute {:pme? true :cge-exempt? false}
                       {:book-profit 1000000M})]
    (is (every? #(= :EUR (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :EUR (:commodity (:liability %)))
                (:components facts)))))
