(ns kontor.l10n-in.cit-provider-test
  "IN CIT provider tests — ADR-101 / ADR-101 Addendum 1
   `compose-greater-of` substrate's first end-to-end consumer on a real
   statute. Validates that the statute-as-data path (`:parameter` +
   `:provision` rows + `kontor.tax.statute/apply-provisions` fold +
   `compose-greater-of`) computes IN CIT against published authority
   numbers.

   Cases:
     §1  Worked Example A — standard regime, ₹50L taxable / ₹60L book,
         no surcharge, regular prevails. Cross-checked against authority-published worked example.
     §2  Worked Example B — §115BAA election @ ₹5cr → 25.168 % effective.
         Single regular component (MAT non-applicable per §115JB(5A) /
         condition gating).
     §3  Worked Example C — MAT-binding case ₹50L taxable / ₹2cr book
         → MAT prevails ₹33,38,400; ₹20,38,400 recorded in
         :mat-credit-carry-forward (§115JAA, 15-yr carry).
     §4  Worked Example D — bitemporal foreign-co 40 % → 35 % swap
         across the FY 2024-25 boundary (Finance (No. 2) Act 2024 §2).
     §5  Substrate regressions — :tax-unit traps, MAT non-applicability
         under §115BAA, install-idempotence, MAT 15 % → 14 % bitemporal
         swap across the FY 2026-27 boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-in.cit-provider :as in-cit]
            [kontor.l10n-in.cit-statute :as cit-statute]
            [kontor.tax.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the IN CIT statute installed."
  []
  (let [conn (core/create-test-db)]
    (cit-statute/install! conn)
    conn))

(defn- compute
  "Run the IN CIT provider over `tax-unit` + `inputs` + an as-of
   instant; return the `TaxReturnFacts`. The 2-arg form defaults the
   as-of to AY 2026-27 (FY 2025-26 mid-year), where MAT is 15 % and
   foreign-co is 35 %. Higher arities let tests override the as-of for
   the bitemporal swap checks."
  ([tax-unit inputs]
   (compute tax-unit inputs #inst "2025-09-30"))
  ([tax-unit inputs as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (in-cit/in-cit-provider {})
      {:entity   :pvt-ltd
       :period   {:from #inst "2025-04-01" :to #inst "2026-04-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- only-component
  "The IN CIT provider always returns a single component (the MAT vs
   regular choice is made internally via compose-greater-of). Extract
   it; assert exactly one."
  [facts]
  (let [cs (:components facts)]
    (is (= 1 (count cs))
        "IN CIT returns exactly one component (compose-greater-of picks the prevailing arm)")
    (first cs)))

(defn- surtax-amount [comp code]
  (some (fn [s] (when (= code (:code s)) (:amount s)))
        (:surtaxes comp)))

;; ============================================================================
;; §1. Worked Example A — standard regime, ₹50L taxable / ₹60L book
;; ============================================================================
;;
;; Pvt. Ltd. with PY 2023-24 turnover ₹150 cr (small), AY 2026-27
;; taxable income ₹50,00,000, book profit ₹60,00,000.
;;
;; Regular CIT:    50_00_000 × 25%    = 12_50_000
;;                 + 0 surcharge (≤ ₹1cr)
;;                 + 4% cess           =    50_000
;;                                     = 13_00_000
;; MAT (§115JB):   60_00_000 × 15%    =  9_00_000
;;                 + 0 surcharge
;;                 + 4% cess           =    36_000
;;                                     =  9_36_000
;; Composition:    max(13_00_000, 9_36_000) = 13_00_000  ⇒ regular prevails

(deftest worked-example-a-standard-regular-prevails
  (testing "Standard regime, ₹50L taxable / ₹60L book, no surcharge — regular prevails"
    (let [facts (compute {:regime        :in-cit-standard
                          :turnover-band :small}
                         {:taxable-income     5000000M
                          :book-profit-115jb  6000000M})
          c     (only-component facts)]
      (testing "prevailing component shape"
        (is (= :corporate-income-tax (:kind c)))
        (is (= :in-cbdt              (:authority c)))
        (is (= :INR                  (:commodity (:base c))))
        (is (= :INR                  (:commodity (:liability c)))))
      (testing "schedule and base"
        (is (= :flat (:kontor.schedule/type (:schedule c))))
        (is (== 0.25M (:rate (:schedule c))))
        (is (== 5000000M (:amount (:base c)))))
      (testing "gross + cess only (no surcharge below ₹1 cr) ⇒ ₹13,00,000"
        (is (== 1250000M (:amount (:gross-liability c))))
        (is (== 0M  (surtax-amount c :in-surcharge-standard)))
        (is (== 50000M (surtax-amount c :in-hec-cess)))
        (is (== 1300000M (:amount (:liability c)))))
      (testing "composition records the losing MAT arm for audit"
        (is (= :greater-of (-> c :composition :method)))
        (is (= :a (-> c :composition :prevailed))
            "regular is :a (first arg to compose-greater-of)")
        (is (== 1300000M (-> c :composition :a :liability)))
        (is (== 936000M (-> c :composition :b :liability))
            "MAT liability preserved in :composition for audit")
        (is (= [:corporate-income-tax :minimum-tax] (:composed-of c)))
        (is (nil? (-> c :provenance :mat-credit-carry-forward))
            "no MAT credit when regular prevails")))))

;; ============================================================================
;; §2. Worked Example B — §115BAA election @ ₹5cr → 25.168 % effective
;; ============================================================================
;;
;; Domestic Pvt. Ltd., §115BAA elected, AY 2026-27 taxable income
;; ₹5,00,00,000.
;;
;; Single regular component (MAT condition does NOT match):
;;   Base rate 22%             = ₹1,10,00,000
;;   + Flat 10% surcharge      = ₹  11,00,000
;;   + 4% cess on (tax+surch)  = ₹   4,84,000
;;                             = ₹1,25,84,000
;;   Effective rate            = 25.168 %

(deftest worked-example-b-115BAA-25-168-effective
  (testing "§115BAA @ ₹5cr income → 25.168 % effective; single regular component (no MAT)"
    (let [facts (compute {:regime :in-cit-115BAA}
                         {:taxable-income    50000000M
                          ;; supplying :book-profit-115jb should not
                          ;; materially affect anything — MAT condition
                          ;; gates it OUT for §115BAA per §115JB(5A).
                          :book-profit-115jb 70000000M})
          c     (only-component facts)]
      (testing "exactly one component, no MAT composition occurred"
        (is (= :corporate-income-tax (:kind c)))
        (is (= :in-cit-115BAA (:regime c)))
        (is (nil? (:composed-of c))
            "no compose-greater-of when MAT non-applicable")
        (is (nil? (:composition c))))
      (testing "rate path: §115BAA flat 22%"
        (is (= :flat (:kontor.schedule/type (:schedule c))))
        (is (== 0.22M (:rate (:schedule c)))))
      (testing "gross, surcharge, cess"
        (is (== 11000000M (:amount (:gross-liability c))))
        (is (== 1100000M  (surtax-amount c :in-surcharge-concessional))
            "flat 10% surcharge on §115BAA"))
      (testing "cess fires AFTER surcharge (priority 500 > 100) ⇒ 4% × 12,100,000"
        (is (== 484000M (surtax-amount c :in-hec-cess))))
      (testing "total liability = ₹1,25,84,000 ⇒ effective 25.168 %"
        (is (== 12584000M (:amount (:liability c))))
        (is (== 0.25168M
                (.setScale (bigdec (/ (:amount (:liability c))
                                      (:amount (:base c))))
                           5 java.math.RoundingMode/HALF_EVEN))
            "effective rate 25.168 % per § 115BAA"))
      (testing "MAT was condition-gated OFF — no MAT provision in :provisions-applied"
        (is (not (contains? (set (-> c :provenance :provisions-applied))
                            "IN-MAT-115JB")))
        (is (not (contains? (set (-> c :provenance :provisions-applied))
                            "IN-MAT-Surcharge")))))))

;; ============================================================================
;; §3. Worked Example C — MAT-binding case (load-bearing for compose-greater-of)
;; ============================================================================
;;
;; Standard regime, PY turnover ₹150 cr (small), AY 2026-27 taxable
;; income ₹50,00,000 (after Chapter VI-A deductions), book profit
;; ₹2,00,00,000 (pre-deductions).
;;
;; Regular CIT:    50_00_000 × 25%    = 12_50_000
;;                 + 0 surcharge
;;                 + 4% cess           =    50_000
;;                                     = 13_00_000
;; MAT:            2_00_00_000 × 15%  = 30_00_000
;;                 + 7% surcharge      =  2_10_000   (book > ₹1cr triggers band)
;;                                     = 32_10_000
;;                 + 4% cess           =  1_28_400
;;                                     = 33_38_400
;; Composition:    max(13_00_000, 33_38_400) = 33_38_400 ⇒ MAT prevails
;; MAT credit:     33_38_400 - 13_00_000 = 20_38_400 (15 AYs / §115JAA)

(deftest worked-example-c-mat-binding-composition
  (testing "MAT-binding: ₹50L taxable / ₹2cr book ⇒ MAT prevails ₹33,38,400"
    (let [facts (compute {:regime        :in-cit-standard
                          :turnover-band :small}
                         {:taxable-income     5000000M
                          :book-profit-115jb 20000000M})
          c     (only-component facts)]
      (testing "prevailing arm is MAT (the GREATER liability)"
        (is (= :minimum-tax (:kind c))
            "compose-greater-of returns the prevailing component's :kind")
        (is (== 20000000M (:amount (:base c)))
            "base on prevailing component is book profit (MAT base)"))
      (testing "MAT computation per blueprint §4.3"
        (is (= :flat (:kontor.schedule/type (:schedule c))))
        (is (== 0.15M (:rate (:schedule c))))
        (is (== 3000000M (:amount (:gross-liability c))))
        (is (== 210000M  (surtax-amount c :in-mat-surcharge))
            "7% surcharge fires (₹2cr base > ₹1cr threshold)")
        (is (== 128400M  (surtax-amount c :in-hec-cess))
            "4% cess on (3M + 210k) = 128_400")
        (is (== 3338400M (:amount (:liability c)))
            "MAT total = ₹33,38,400 per § 115JB"))
      (testing "compose-greater-of audit trail — both arms preserved"
        (is (= :greater-of (-> c :composition :method)))
        (is (= :b (-> c :composition :prevailed))
            "MAT is :b (second arg ⇒ winner here)")
        (is (== 1300000M (-> c :composition :a :liability))
            "regular liability preserved in :composition.a")
        (is (= :corporate-income-tax (-> c :composition :a :kind)))
        (is (== 3338400M (-> c :composition :b :liability))
            "MAT liability preserved in :composition.b (matches prevailing)")
        (is (= :minimum-tax (-> c :composition :b :kind)))
        (is (= [:corporate-income-tax :minimum-tax] (:composed-of c))))
      (testing "§115JAA MAT credit carry-forward recorded on :provenance"
        (let [carry (-> c :provenance :mat-credit-carry-forward)]
          (is (some? carry) "MAT credit fact present when MAT prevails")
          (is (== 2038400M (:amount carry))
              "excess MAT paid = 33,38,400 - 13,00,000 = 20,38,400")
          (is (= :INR (:commodity carry)))
          (is (= 15 (:max-years carry)))
          (is (= "§115JAA" (:statute carry)))
          (is (= :recorded-deferred-utilisation (:status carry))
              "utilisation deferred to the kontor carry primitive — v1 only records"))))))

;; ============================================================================
;; §4. Worked Example D — bitemporal foreign-co 40% → 35% swap
;; ============================================================================
;;
;; Foreign-co branch in India, ₹2,00,00,000 income.
;;
;; Pre-FA-2024 (`:as-of 2024-03-31`):
;;   40% × 2cr     = 80_00_000
;;   + 2% surch    =  1_60_000  (foreign-co banded: > ₹1cr → 2%)
;;                 = 81_60_000
;;   + 4% cess     =  3_26_400
;;                 = 84_86_400
;;
;; Post-FA-2024 (`:as-of 2025-06-30`):
;;   35% × 2cr     = 70_00_000
;;   + 2% surch    =  1_40_000
;;                 = 71_40_000
;;   + 4% cess     =  2_85_600
;;                 = 74_25_600

(deftest worked-example-d-foreign-co-bitemporal-swap
  (testing "Bitemporal swap — foreign-co rate 40% → 35% across FY 2024-25 boundary"
    (let [tax-unit {:regime :in-cit-standard  ; regime irrelevant for foreign-co path
                    :foreign-co? true}
          inputs   {:taxable-income 20000000M}
          ;; Pre-FA-2024
          pre-facts (compute tax-unit inputs #inst "2024-03-31")
          pre       (only-component pre-facts)
          ;; Post-FA-2024
          post-facts (compute tax-unit inputs #inst "2025-06-30")
          post       (only-component post-facts)]
      (testing "pre-FA-2024 fires 40 % (parameter-value-at honours :effective-until)"
        (is (== 0.40M    (:rate (:schedule pre))))
        (is (== 8000000M (:amount (:gross-liability pre))))
        (is (== 160000M  (surtax-amount pre :in-surcharge-foreign))
            "foreign-co banded 2% > ₹1cr")
        (is (== 326400M  (surtax-amount pre :in-hec-cess))
            "4% × (80L + 1.6L) = 3,26,400")
        (is (== 8486400M (:amount (:liability pre)))))
      (testing "post-FA-2024 fires 35 % (parameter-value-at picks the newer row)"
        (is (== 0.35M    (:rate (:schedule post))))
        (is (== 7000000M (:amount (:gross-liability post))))
        (is (== 140000M  (surtax-amount post :in-surcharge-foreign)))
        (is (== 285600M  (surtax-amount post :in-hec-cess)))
        (is (== 7425600M (:amount (:liability post)))))
      (testing "MAT condition is foreign-co-gated OFF on both sides ⇒ single component"
        (is (nil? (:composed-of pre)))
        (is (nil? (:composed-of post)))))))

;; ============================================================================
;; §5. Substrate regressions
;; ============================================================================

(deftest regime-missing-raises
  (testing "IN CIT requires :tax-unit :regime"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":regime"
                            (ptp/period-tax-facts
                             (in-cit/in-cit-provider {})
                             {:entity   :pvt-ltd
                              :period   {:from #inst "2025-04-01" :to #inst "2026-04-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-09-30"
                              :tax-unit {}
                              :inputs   {:taxable-income 5000000M}}))))))

(deftest taxable-income-missing-raises
  (testing "IN CIT requires :inputs :taxable-income"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":taxable-income"
                            (ptp/period-tax-facts
                             (in-cit/in-cit-provider {})
                             {:entity   :pvt-ltd
                              :period   {:from #inst "2025-04-01" :to #inst "2026-04-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-09-30"
                              :tax-unit {:regime :in-cit-standard
                                         :turnover-band :small}
                              :inputs   {}}))))))

(deftest turnover-band-missing-raises-when-standard
  (testing "Standard regime requires :tax-unit :turnover-band — no schedule fires otherwise"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no schedule-override fired"
                          (compute {:regime :in-cit-standard}
                                   {:taxable-income 5000000M})))))

(deftest section-115JB-5A-mat-skipped-under-115BAA
  (testing "§115JB(5A) — §115BAA-electing co. gets ONLY the regular component"
    (let [facts (compute {:regime :in-cit-115BAA}
                         {:taxable-income     5000000M
                          :book-profit-115jb 100000000M})    ; huge book profit
          c     (only-component facts)]
      (is (= :corporate-income-tax (:kind c))
          "kind is regular CIT, not :minimum-tax")
      (is (nil? (:composed-of c))
          "no MAT composition — single component path")
      (is (nil? (:composition c)))
      (is (nil? (-> c :provenance :mat-credit-carry-forward))
          "no MAT credit fact when MAT was never computed")
      (testing "MAT provisions are not in :provisions-applied"
        (let [applied (set (-> c :provenance :provisions-applied))]
          (is (not (contains? applied "IN-MAT-115JB")))
          (is (not (contains? applied "IN-MAT-Surcharge"))))))))

(deftest section-115JB-5A-mat-skipped-foreign-co
  (testing "Foreign-co does NOT compute MAT (§115JB(5A) gating + provider mirror)"
    (let [facts (compute {:regime :in-cit-standard :foreign-co? true}
                         {:taxable-income     5000000M
                          :book-profit-115jb 50000000M})
          c     (only-component facts)]
      (is (= :corporate-income-tax (:kind c)))
      (is (nil? (:composed-of c))
          "foreign-co single-component path"))))

(deftest mat-rate-bitemporal-swap-15-to-14
  (testing "MAT 15 % (FY 2025-26) → 14 % (FY 2026-27) bitemporal swap"
    (let [;; Same MAT-binding facts; only :as-of differs.
          tax-unit {:regime :in-cit-standard :turnover-band :small}
          inputs   {:taxable-income     5000000M
                    :book-profit-115jb 20000000M}
          pre  (only-component (compute tax-unit inputs #inst "2026-03-31"))
          post (only-component (compute tax-unit inputs #inst "2026-04-01"))]
      (testing "pre-FY-2026-27 — MAT @ 15 %"
        (is (= :minimum-tax (:kind pre)))
        (is (== 0.15M    (:rate (:schedule pre))))
        (is (== 3000000M (:amount (:gross-liability pre)))))
      (testing "post-FY-2026-27 — MAT @ 14 % (Union Budget 2025)"
        (is (= :minimum-tax (:kind post)))
        (is (== 0.14M    (:rate (:schedule post))))
        (is (== 2800000M (:amount (:gross-liability post))))))))

(deftest provisions-applied-recorded-in-provenance
  (testing "every component records the provisions that fired in :provenance"
    (let [;; Standard small, regular prevails — provisions for regular path only
          a     (only-component
                 (compute {:regime :in-cit-standard :turnover-band :small}
                          {:taxable-income 5000000M :book-profit-115jb 6000000M}))
          a-set (set (-> a :provenance :provisions-applied))]
      (testing "standard-regime regular path"
        (is (contains? a-set "IN-CIT-Standard-25"))
        (is (contains? a-set "IN-FinAct-Cess"))
        (is (contains? a-set "IN-CIT-Surcharge-Standard"))
        ;; surcharge provision is APPLIED even when its computed amount
        ;; is 0 (income under ₹1cr) — the substrate records the
        ;; provision firing in :provisions-applied
        ))
    (testing "§115BAA path"
      (let [b     (only-component
                   (compute {:regime :in-cit-115BAA}
                            {:taxable-income 50000000M}))
            b-set (set (-> b :provenance :provisions-applied))]
        (is (contains? b-set "IN-CIT-115BAA-22"))
        (is (contains? b-set "IN-CIT-Surcharge-Concessional"))
        (is (contains? b-set "IN-FinAct-Cess"))
        (is (not (contains? b-set "IN-CIT-Standard-25"))
            "standard-regime provisions did NOT fire")))))

(deftest installable-is-idempotent
  (testing "install! is idempotent on identity attrs (:kontor.parameter/code, :kontor.regime/code, :kontor.provision/code)"
    (let [conn (core/create-test-db)]
      (cit-statute/install! conn)
      (cit-statute/install! conn)
      (let [in-params (->> (d/q '[:find [?code ...]
                                  :where [_ :kontor.parameter/code ?code]]
                                (d/db conn))
                           (filter #(.startsWith ^String % "IN.")))
            in-provs  (->> (d/q '[:find [?code ...]
                                  :where [_ :kontor.provision/code ?code]]
                                (d/db conn))
                           (filter #(or (.startsWith ^String % "IN-")
                                        (.startsWith ^String % "IN-CIT"))))
            in-regs   (->> (d/q '[:find [?code ...]
                                  :where [_ :kontor.regime/code ?code]]
                                (d/db conn))
                           (filter #(#{:in-cit-standard :in-cit-115BAA :in-cit-115BAB} %)))]
        (is (= (count cit-statute/parameters) (count in-params)))
        (is (= (count cit-statute/provisions) (count in-provs)))
        (is (= (count cit-statute/regimes) (count in-regs)))))))

(deftest functional-commodity-is-inr-on-every-money
  (let [facts (compute {:regime :in-cit-standard :turnover-band :small}
                       {:taxable-income     5000000M
                        :book-profit-115jb 20000000M})
        c     (only-component facts)]
    (is (= :INR (:functional-commodity facts)))
    (is (= :INR (:commodity (:base c))))
    (is (= :INR (:commodity (:gross-liability c))))
    (is (= :INR (:commodity (:liability c))))
    ;; the losing arm in :composition is reported as scalar amounts only
    ;; — verify the prevailing-component money carries :INR everywhere.
    ))
