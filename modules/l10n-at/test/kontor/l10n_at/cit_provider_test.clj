(ns kontor.l10n-at.cit-provider-test
  "AT corporate income tax provider tests — ADR-101 substrate's AT
   consumer (ADR-104 template, applied to Austria). Validates that the
   statute-as-data path (`:parameter` + `:provision` rows +
   `kontor.tax.statute/apply-provisions` fold +
   `compose-greater-of` against the Mindest-KöSt floor) computes
   real AT KöSt against published worked examples.

   Worked examples cited:

   - **§1 Clean GmbH @ €100 k** — flat 23 % × €100 000 = €23 000.
     Mindest-KöSt €500 (does not prevail). Source: §22 KStG 2025
     Worldwide Tax Summary; WKO Aktuelle Werte 2026.
   - **§2 Loss scenario** — book-profit €−5 000 → KSt-flat floored at
     €0; Mindest-KöSt €500 (post-2024) prevails. Source: WKO Aktuelle
     Werte 2026.
   - **§3 Bitemporal swap** — same €100 k assessed as-of 2022-12-31
     (25 % + €1 750 floor) vs 2025-12-31 (23 % + €500 floor). Tests
     both parameter cliffs (rate cliff 2024-01-01 + Mindest-KöSt cliff
     2024-01-01).
   - **§4 AG entity-kind** — `:tax-unit :entity-kind :ag` selects the
     €3 500/yr floor; small book-profit demonstrates the floor
     prevailing.
   - **§5 §10 deduction lane** — `:inputs :cgt-cit-base-deductions
     20 000M` reduces base; KSt = 80 000 × 23 % = €18 400.
   - **§6 §10 addition lane** — `:inputs :cgt-cit-base-additions
     5 000M` increases base; KSt = 105 000 × 23 % = €24 150.
   - **§7 Optional provisions** — both §8 Verlustvortrag + §12 non-
     deductibles fire; provenance records both codes.
   - **§8 Install idempotence** — substrate property.
   - **§9 Provenance** — `:provisions-applied` records the applied
     codes.
   - **§10 Missing book-profit** — ex-info with diagnostic message."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-at.cgt-statute :as cgt-statute]
            [kontor.l10n-at.cit-provider :as at-cit]
            [kontor.l10n-at.cit-statute :as cit-statute]
            [kontor.tax.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the AT CIT + CGT statutes installed (CGT ships
   `AT.KStG.cit-rate`; CIT consumes it). Order matches
   `kontor.l10n-at.preset/install-all!`."
  []
  (let [conn (core/create-test-db)]
    (cgt-statute/install! conn)
    (cit-statute/install! conn)
    conn))

(defn- compute
  "Run the AT CIT provider over `inputs` + `tax-unit`, return the
   `TaxReturnFacts`. Convenience wrapper. Defaults the `:as-of` to
   2025-12-31 (post-ÖkoStRefG-2022 23 % rate + post-Startup-
   Förderungsgesetz €500 Mindest-KöSt regime)."
  ([tax-unit inputs] (compute tax-unit inputs #inst "2025-12-31"))
  ([tax-unit inputs as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (at-cit/at-cit-provider {})
      {:entity   :gmbh
       :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- koest-component
  "Pull the KSt component (the prevailing arm after compose-greater-of)
   out of a `TaxReturnFacts`."
  [facts]
  (->> facts :components first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. Clean GmbH @ €100 k — flat 23 % KSt prevails
;; ============================================================================

(deftest gmbh-clean-100k-book-profit
  (testing "GmbH, book-profit €100 000, as-of 2025-12-31: flat 23 % KSt €23 000 prevails"
    (let [facts (compute {} {:book-profit 100000M})
          c     (koest-component facts)]
      (testing "regular KSt arm prevailed in compose-greater-of"
        (is (= :a (:prevailed (:composition c)))
            "KSt €23 000 > Mindest-KöSt €500"))
      (testing "base = book-profit (no adjustments fire)"
        (is (== 100000M (:amount (:base c)))))
      (testing "schedule = flat 23 %"
        (is (= :flat (:kontor.schedule/type (:schedule c))))
        (is (== 0.23M (:rate (:schedule c)))))
      (testing "gross-liability = €23 000"
        (is (== 23000M (:amount (:gross-liability c)))))
      (testing "liability = €23 000"
        (is (== 23000M (:amount (:liability c))))
        (is (== 23000M (total-liability facts))))
      (testing "compose-greater-of audit trail preserves both arms"
        (is (== 23000M (-> c :composition :a :liability)))
        (is (== 500M   (-> c :composition :b :liability)))))))

;; ============================================================================
;; §2. Loss scenario — Mindest-KöSt floor prevails
;; ============================================================================

(deftest gmbh-loss-mindest-koest-prevails
  (testing "GmbH at €−5 000 loss → KSt-flat €0; Mindest-KöSt €500 prevails"
    (let [facts (compute {} {:book-profit -5000M})
          c     (koest-component facts)]
      (testing "Mindest-KöSt arm prevailed"
        (is (= :b (:prevailed (:composition c)))
            "KSt-flat €0 < Mindest-KöSt €500"))
      (testing "liability = €500 (the floor)"
        (is (== 500M (:amount (:liability c))))
        (is (== 500M (total-liability facts))))
      (testing "composition records both arms for audit"
        (is (== 0M   (-> c :composition :a :liability)))
        (is (== 500M (-> c :composition :b :liability))))
      (testing "Mindest-KöSt provenance carries the §24 citation"
        (is (= "KStG 1988 §24 Abs 4 Z 1 — Mindestkörperschaftsteuer"
               (-> c :provenance :statute)))))))

;; ============================================================================
;; §3. Bitemporal swap — 2022 (25 % + €1 750) vs 2025 (23 % + €500)
;; ============================================================================

(deftest bitemporal-swap-2022-vs-2025-rate-and-floor
  (testing "as-of 2022-12-31 fires the 25 % rate + €1 750 floor;
            as-of 2025-12-31 fires the 23 % rate + €500 floor"
    (let [pre  (compute {} {:book-profit 100000M} #inst "2022-12-31")
          post (compute {} {:book-profit 100000M} #inst "2025-12-31")
          pre-c  (koest-component pre)
          post-c (koest-component post)]
      (testing "pre-2024 rate = 25 %"
        (is (== 0.25M (:rate (:schedule pre-c)))))
      (testing "post-2023 rate = 23 %"
        (is (== 0.23M (:rate (:schedule post-c)))))
      (testing "pre-2024 KSt = €25 000 (regular prevails)"
        (is (== 25000M (:amount (:liability pre-c))))
        (is (= :a (:prevailed (:composition pre-c)))))
      (testing "post-2023 KSt = €23 000 (regular prevails)"
        (is (== 23000M (:amount (:liability post-c))))
        (is (= :a (:prevailed (:composition post-c)))))
      (testing "pre-2024 Mindest-KöSt arm carries €1 750 (the legacy floor)"
        (is (== 1750M (-> pre-c :composition :b :liability))))
      (testing "post-2023 Mindest-KöSt arm carries €500 (the post-Startup-Förderungsgesetz floor)"
        (is (== 500M (-> post-c :composition :b :liability)))))))

;; ============================================================================
;; §4. AG entity-kind selects €3 500 floor
;; ============================================================================

(deftest ag-mindest-koest-3500-default
  (testing "AG with €5 000 book-profit: KSt-flat €1 150; AG Mindest-KöSt €3 500 prevails"
    (let [facts (compute {:entity-kind :ag} {:book-profit 5000M})
          c     (koest-component facts)]
      (testing "Mindest-KöSt arm prevailed"
        (is (= :b (:prevailed (:composition c)))))
      (testing "liability = €3 500"
        (is (== 3500M (:amount (:liability c)))))
      (testing "floor parameter recorded in :jurisdiction-specific-codes"
        (is (= :ag (-> c :jurisdiction-specific-codes :at/entity-kind))
            "the :ag entity-kind was used to pick the parameter")))))

;; ============================================================================
;; §5. §10 KStG deduction lane (consumer-supplied)
;; ============================================================================

(deftest §10-cit-base-deductions-flows-through
  (testing "book-profit €100 k + :cgt-cit-base-deductions €20 k → base €80 k → KSt €18 400"
    (let [facts (compute {} {:book-profit                100000M
                             :cgt-cit-base-deductions  20000M})
          c     (koest-component facts)]
      (testing "base = 100 000 − 20 000 = 80 000"
        (is (== 80000M (:amount (:base c)))))
      (testing "base-transform records the §10 deduction"
        (let [items (:items (:base-transform c))]
          (is (= 1 (count items)))
          (is (= :at-§10-exempt-dividends (:code (first items))))
          (is (== 20000M (:amount (first items))))))
      (testing "liability = 80 000 × 23 % = 18 400"
        (is (== 18400M (:amount (:liability c)))))
      (testing "provenance records the §10 deduction provision code"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "AT-KStG-§10-cit-base-deductions"))))))

;; ============================================================================
;; §6. §10 KStG addition lane (consumer-supplied)
;; ============================================================================

(deftest §10-elective-cit-base-additions-flows-through
  (testing "book-profit €100 k + :cgt-cit-base-additions €5 k → base €105 k → KSt €24 150"
    (let [facts (compute {} {:book-profit              100000M
                             :cgt-cit-base-additions 5000M})
          c     (koest-component facts)]
      (testing "base = 100 000 + 5 000 = 105 000"
        (is (== 105000M (:amount (:base c)))))
      (testing "liability = 105 000 × 23 % = 24 150"
        (is (== 24150M (:amount (:liability c)))))
      (testing "provenance records the §10 addition provision code"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "AT-KStG-§10-cit-base-additions"))))))

;; ============================================================================
;; §7. Optional provisions — Verlustvortrag + §12 non-deductibles trace
;; ============================================================================

(deftest verlustvortrag-and-§12-non-deductibles-trace-in-provenance
  (testing "both optional provisions fire; provisions-applied contains both codes"
    (let [facts (compute {} {:book-profit                100000M
                             :at-verlustvortrag-applied 10000M
                             :at-§12-non-deductibles    3000M})
          c     (koest-component facts)]
      (testing "base = 100 000 + 3 000 − 10 000 = 93 000"
        (is (== 93000M (:amount (:base c)))))
      (testing "liability = 93 000 × 23 % = 21 390"
        (is (== 21390M (:amount (:liability c)))))
      (testing "both provision codes appear in provenance"
        (let [applied (set (-> c :provenance :provisions-applied))]
          (is (contains? applied "AT-KStG-§8-Abs-4-verlustvortrag"))
          (is (contains? applied "AT-KStG-§12-nicht-abzugsfaehig")))))))

;; ============================================================================
;; §8. Substrate property — install idempotence
;; ============================================================================

(deftest installable-is-idempotent
  (testing "install! is idempotent (re-run is a no-op on identity attrs)"
    (let [conn (core/create-test-db)]
      (cgt-statute/install! conn)
      (cit-statute/install! conn)
      (cit-statute/install! conn)
      (let [n-params (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.parameter/jurisdiction ?juris]
                                   [?p :kontor.parameter/code ?code]
                                   [(.startsWith ^String ?code "AT.KStG.§24.")]]
                                 (d/db conn) :at))
            n-provs  (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.provision/jurisdiction ?juris]
                                   [?p :kontor.provision/code ?code]
                                   [(.startsWith ^String ?code "AT-KStG-")]]
                                 (d/db conn) :at))]
        (is (= (count cit-statute/parameters) n-params))
        (is (= (count cit-statute/provisions) n-provs))))))

;; ============================================================================
;; §9. Substrate property — provenance trail
;; ============================================================================

(deftest provenance-records-the-applied-provisions
  (testing "clean GmbH case fires NO provisions (no driver facts present)"
    (let [facts (compute {} {:book-profit 100000M})
          c     (koest-component facts)]
      (is (empty? (-> c :provenance :provisions-applied))
          "no driver facts → no provisions fire — the silent-no-op posture"))))

;; ============================================================================
;; §10. Substrate property — missing book-profit raises
;; ============================================================================

(deftest missing-book-profit-raises
  (testing "absent :inputs :book-profit → ex-info with diagnostic"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"book-profit"
                            (ptp/period-tax-facts
                             (at-cit/at-cit-provider {})
                             {:entity   :gmbh
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {}
                              :inputs   {}}))))))

;; ============================================================================
;; Substrate property — monocommodity facts
;; ============================================================================

(deftest functional-commodity-is-eur-on-every-money
  (let [facts (compute {} {:book-profit 100000M})]
    (is (every? #(= :EUR (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :EUR (:commodity (:liability %)))
                (:components facts)))))
