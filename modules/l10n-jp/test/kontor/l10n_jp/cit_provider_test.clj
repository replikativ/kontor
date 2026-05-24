(ns kontor.l10n-jp.cit-provider-test
  "JP corporate income tax provider tests — ADR-101 / ADR-106
   substrate's second end-to-end consumer after DE. Validates that
   the statute-as-data path (`:parameter` + `:provision` rows +
   `kontor.statute/apply-provisions` fold) computes real JP CIT
   against published worked examples.

   Cases:
     §1 JETRO Section 3.3 SME worked example — Tokyo SME @ ¥10M
        income, capital ≤¥10M, ≤50 employees. Expected total
        ¥2,625,912 (without 均等割) / ¥2,695,912 (with). Note 110 §2.
     §2 Large-corporation flat schedule — capital >¥100M @ ¥1B
        income → 23.2 % flat national CIT, defense surtax fires
        (post-2026-04-01). Exercises ADR-101-Addendum-1
        `:op :schedule-override`.
     §3 Defense surtax temporal gate — as-of 2025-06-30 ⇒ no
        defense surtax; as-of 2026-06-30 ⇒ defense surtax fires.
     §4 Per-capita levy tier coverage — three tiers from the 10-cell
        table.
     §5 Substrate-property sanity — `:tax-unit` keys required,
        idempotent install, `:provenance` records the provisions
        that fired, `:JPY` on every Money, schedule-override audit
        trail records the swap."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-jp.cit-provider :as jp-cit]
            [kontor.l10n-jp.cit-statute :as cit-statute]
            [kontor.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the JP CIT statute installed."
  []
  (let [conn (core/create-test-db)]
    (cit-statute/install! conn)
    conn))

(defn- compute
  "Run the JP CIT provider over `tax-unit` + `inputs` + an as-of
   instant; return the `TaxReturnFacts`. The period defaults to
   FY 2025-04-01 .. 2026-04-01."
  ([tax-unit inputs] (compute tax-unit inputs #inst "2025-06-30"))
  ([tax-unit inputs as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (jp-cit/jp-cit-provider {})
      {:entity   :kk
       :period   {:from #inst "2025-04-01" :to #inst "2026-04-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- component
  "Pull the first component matching `authority` out of a
   `TaxReturnFacts`."
  [facts authority]
  (->> facts :components (filter #(= authority (:authority %))) first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

(defn- surtax-amount
  "Amount of the surtax with `code` on `component-map` (nil if absent)."
  [component-map code]
  (some (fn [s] (when (= code (:code s)) (:amount s)))
        (:surtaxes component-map)))

;; ============================================================================
;; §1. JETRO worked example — Tokyo SME @ ¥10M income, ≤50 emp
;; ============================================================================
;;
;; Per note 110 §2:
;;   National CIT     = 0.15 × 8M + 0.232 × 2M       = ¥1,664,000
;;   Local CIT        = 0.103 × 1,664,000            = ¥171,392
;;   Inhabitant levy  = 0.07 × 1,664,000             = ¥116,480
;;   Per-capita       = tier(≤¥10M cap, ≤50 emp)     = ¥70,000
;;   Enterprise       = 0.035×4M + 0.053×4M + 0.07×2M = ¥492,000
;;   Special corp     = 0.37 × 492,000               = ¥182,040
;;   Total no per-cap                                = ¥2,625,912
;;   Total with per-cap                              = ¥2,695,912

(deftest jetro-sme-worked-example
  (testing "JETRO §3.3 Tokyo SME @ ¥10M income (note 110 §2)"
    (let [facts (compute {:is-sme?         true
                          :capital-class   :capital-up-to-10m
                          :headcount-class :small
                          :prefecture      :tokyo}
                         {:book-profit 10000000M})
          nat   (component facts :jp-nta)
          ent   (component facts :jp-prefecture)
          inh   (component facts :jp-municipality)]
      (testing "National CIT: progressive 15 % / 23.2 % around ¥8M kink"
        (is (== 10000000M (:amount (:base nat))))
        (is (== 1664000M  (:amount (:gross-liability nat))))
        (testing "local CIT (10.3 %) is the only surtax — defense surtax not yet effective"
          (is (== 171392M (surtax-amount nat :local-corporate-tax)))
          (is (nil? (surtax-amount nat :defense-surtax))))
        (is (== 1835392M (:amount (:liability nat)))
            "national-component liability = 法人税 + 地方法人税"))
      (testing "Enterprise tax: SME 3-bracket progressive 3.5 / 5.3 / 7.0 %"
        (is (== 10000000M (:amount (:base ent))))
        (is (== 492000M   (:amount (:gross-liability ent))))
        (testing "special corp enterprise tax 37 % surtax"
          (is (== 182040M (surtax-amount ent :special-corp-enterprise-tax))))
        (is (== 674040M (:amount (:liability ent)))))
      (testing "Inhabitants' tax: 7 % income-levy on national CIT + ¥70k per-capita"
        (is (== 0M     (:amount (:base inh))))
        (is (== 0M     (:amount (:gross-liability inh))))
        (is (== 116480M (surtax-amount inh :inhabitant-income-levy))
            "0.07 × 1,664,000 = 116,480")
        (is (== 70000M (surtax-amount inh :inhabitant-per-capita-levy))
            "tier (≤¥10M capital, ≤50 employees) = ¥70k")
        (is (== 186480M (:amount (:liability inh)))))
      (testing "Total: ¥2,695,912 (with per-capita) / ¥2,625,912 (without)"
        (is (== 2695912M (total-liability facts)))
        (is (== 2625912M (- (total-liability facts)
                            (surtax-amount inh :inhabitant-per-capita-levy))))))))

;; ============================================================================
;; §2. Large-corporation flat schedule (capital ≥ ¥100M)
;; ============================================================================

(deftest large-corp-flat-23-2-percent
  (testing "Large corporation (capital >¥100M, :is-sme? false) — flat 23.2 %
            schedule via :op :schedule-override; defense surtax fires post-2026"
    (let [facts (compute {:is-sme?         false
                          :capital-class   :capital-up-to-1b
                          :headcount-class :small
                          :prefecture      :tokyo}
                         {:book-profit 1000000000M}     ;; ¥1B book profit
                         #inst "2026-06-30")            ;; post defense-surtax effective
          nat   (component facts :jp-nta)
          ent   (component facts :jp-prefecture)]
      (testing "National CIT: flat 23.2 % schedule override active"
        (is (= :flat (:schedule/type (:schedule nat)))
            "schedule swapped from SME progressive to large-corp flat")
        (is (== 0.232M (:rate (:schedule nat)))
            "flat rate sourced from JP.CIT.flat-rate parameter")
        (is (== 232000000M (:amount (:gross-liability nat)))
            "0.232 × ¥1B = ¥232M"))
      (testing "Local CIT: 10.3 % × ¥232M = ¥23,896,000"
        (is (== 23896000M (surtax-amount nat :local-corporate-tax))))
      (testing "Defense surtax: 4 % × max(0, 232,000,000 − 5,000,000) = 9,080,000"
        (is (== 9080000M (surtax-amount nat :defense-surtax))))
      (testing "Enterprise tax: large-corp flat 1.18 % override fires (income base only;
                value-added / capital bases deferred per note 110 §1)"
        (is (= :flat (:schedule/type (:schedule ent))))
        (is (== 0.0118M (:rate (:schedule ent))))
        (is (== 11800000M (:amount (:gross-liability ent)))
            "0.0118 × ¥1B = ¥11.8M"))
      (testing "Special corp enterprise tax for large corps: 260 % surtax"
        (is (== 30680000M (surtax-amount ent :special-corp-enterprise-tax))
            "2.60 × 11,800,000 = 30,680,000"))
      (testing "the schedule-override is recorded in :provisions-applied"
        (is (contains? (set (-> nat :provenance :provisions-applied))
                       "JP-CIT-§66-large"))
        (is (contains? (set (-> ent :provenance :provisions-applied))
                       "JP-Enterprise-§72-large"))))))

;; ============================================================================
;; §3. Defense surtax temporal gate
;; ============================================================================

(deftest defense-surtax-temporal-gate
  (testing "Defense surtax (4 %) is FY ≥ 2026-04-01 only"
    (let [tax-unit {:is-sme?         false
                    :capital-class   :capital-up-to-1b
                    :headcount-class :small}
          inputs   {:book-profit 1000000000M}
          ;; Pre-2026 — defense surtax NOT effective
          pre  (compute tax-unit inputs #inst "2025-06-30")
          ;; Post-2026 — defense surtax IS effective
          post (compute tax-unit inputs #inst "2026-06-30")
          nat-pre  (component pre  :jp-nta)
          nat-post (component post :jp-nta)]
      (is (nil? (surtax-amount nat-pre :defense-surtax))
          "as-of 2025-06-30 — no defense surtax in :surtaxes")
      (is (== 9080000M (surtax-amount nat-post :defense-surtax))
          "as-of 2026-06-30 — defense surtax fires")
      (testing "local CIT fires on both sides (unchanged by the gate)"
        (is (some? (surtax-amount nat-pre  :local-corporate-tax)))
        (is (some? (surtax-amount nat-post :local-corporate-tax)))))))

;; ============================================================================
;; §4. Per-capita levy tier coverage
;; ============================================================================

(deftest per-capita-levy-tiers
  (testing "per-capita inhabitants' levy 均等割 looks up the 10-cell tier table"
    (let [as-of #inst "2025-06-30"
          inputs {:book-profit 5000000M}
          run    (fn [cap hc]
                   (compute {:is-sme?         true
                             :capital-class   cap
                             :headcount-class hc}
                            inputs
                            as-of))
          lvy    #(surtax-amount (component % :jp-municipality)
                                 :inhabitant-per-capita-levy)]
      (is (== 70000M  (lvy (run :capital-up-to-10m  :small))))
      (is (== 140000M (lvy (run :capital-up-to-10m  :large))))
      (is (== 180000M (lvy (run :capital-up-to-100m :small))))
      (is (== 2290000M (lvy (run :capital-up-to-5b  :large))))
      (is (== 3800000M (lvy (run :capital-above-5b  :large)))))))

;; ============================================================================
;; §5. Substrate-property sanity
;; ============================================================================

(deftest book-profit-missing-raises
  (testing "JP CIT requires :inputs :book-profit"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"book-profit"
                            (ptp/period-tax-facts
                             (jp-cit/jp-cit-provider {})
                             {:entity   :kk
                              :period   {:from #inst "2025-04-01" :to #inst "2026-04-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {:is-sme?         true
                                         :capital-class   :capital-up-to-10m
                                         :headcount-class :small}
                              :inputs   {}}))))))

(deftest is-sme-missing-raises
  (testing "JP CIT requires :tax-unit :is-sme? (true|false) for schedule fan-out"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":is-sme\?"
                            (ptp/period-tax-facts
                             (jp-cit/jp-cit-provider {})
                             {:entity   :kk
                              :period   {:from #inst "2025-04-01" :to #inst "2026-04-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {:capital-class :capital-up-to-10m}
                              :inputs   {:book-profit 1000000M}}))))))

(deftest capital-class-missing-raises
  (testing "JP CIT 均等割 requires :tax-unit :capital-class for the per-capita lookup"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"capital-class"
                            (ptp/period-tax-facts
                             (jp-cit/jp-cit-provider {})
                             {:entity   :kk
                              :period   {:from #inst "2025-04-01" :to #inst "2026-04-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {:is-sme? true}
                              :inputs   {:book-profit 1000000M}}))))))

(deftest installable-is-idempotent
  (testing "install! is idempotent (re-run is a no-op on identity attrs)"
    (let [conn (core/create-test-db)]
      (cit-statute/install! conn)
      (cit-statute/install! conn)
      (let [params (d/q '[:find [?code ...]
                          :where [_ :parameter/code ?code]]
                        (d/db conn))
            provs  (d/q '[:find [?code ...]
                          :where [_ :provision/code ?code]]
                        (d/db conn))
            ;; restrict to JP-prefixed only (other modules may seed their own)
            jp-params (filter #(.startsWith ^String % "JP.") params)
            jp-provs  (filter #(.startsWith ^String % "JP-") provs)]
        (is (= (count cit-statute/parameters) (count jp-params)))
        (is (= (count cit-statute/provisions) (count jp-provs)))))))

(deftest provenance-records-applied-provisions
  (testing "every component records the provisions that fired in :provenance"
    (let [facts (compute {:is-sme?         true
                          :capital-class   :capital-up-to-10m
                          :headcount-class :small}
                         {:book-profit 10000000M}
                         #inst "2025-06-30")
          nat   (component facts :jp-nta)
          ent   (component facts :jp-prefecture)
          inh   (component facts :jp-municipality)]
      (testing "SME path: no large-corp schedule overrides; surtaxes fire"
        (is (= #{"JP-LocalCIT-§9"}
               (set (-> nat :provenance :provisions-applied)))
            "national: local CIT only (defense surtax not yet effective; no override for SME)"))
      (is (= #{"JP-SpecialCorpEnterprise-§7"}
             (set (-> ent :provenance :provisions-applied)))
          "enterprise: special corp enterprise surtax only")
      (is (= #{"JP-Inhabitant-income-levy" "JP-Inhabitant-per-capita"}
             (set (-> inh :provenance :provisions-applied)))
          "inhabitant: both income-levy and per-capita"))))

(deftest inhabitant-records-cross-component-dependency
  (testing "the inhabitants' component records :composed-of [:corporate-income-tax]
            and :depends-on {:component :national :national-cit-amount ...}
            (note 110 §4 stress D audit trail)"
    (let [facts (compute {:is-sme?         true
                          :capital-class   :capital-up-to-10m
                          :headcount-class :small}
                         {:book-profit 10000000M})
          inh   (component facts :jp-municipality)]
      (is (= [:corporate-income-tax] (:composed-of inh)))
      (is (= :national (-> inh :provenance :depends-on :component)))
      (is (== 1664000M (-> inh :provenance :depends-on :national-cit-amount))))))

(deftest functional-commodity-is-jpy-on-every-money
  (let [facts (compute {:is-sme?         true
                        :capital-class   :capital-up-to-10m
                        :headcount-class :small}
                       {:book-profit 10000000M})]
    (is (every? #(= :JPY (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :JPY (:commodity (:liability %)))
                (:components facts)))
    (is (every? #(= :JPY (:commodity (:gross-liability %)))
                (:components facts)))))
