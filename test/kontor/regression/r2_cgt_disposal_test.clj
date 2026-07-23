(ns kontor.regression.r2-cgt-disposal-test
  "R2 regression — capital-gains END-TO-END, exercising kontor-disposal
   (record-disposal! / recognize!) feeding per-jurisdiction CGT
   providers through the `DisposalProvider` protocol (ADR-102 / ADR-103).

   Four jurisdictions: US (§1(h) LT brackets, §1211(b) loss), AU (Div
   115 50 % discount + s102-5 loss ordering), UK (BADR £1M lifetime cap
   + AEA), DE (§8b 95/5 corporate participation).

   Green tests confirm behaviour I hand-derived from authority figures.
   `^:kaocha/pending` tests pin GENUINE gaps found while exercising the
   substrate as a consumer."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.book :as book]
            [kontor.disposal :as disposal]
            [kontor.disposal.provider :as disp-provider]
            [kontor.l10n-us.cgt-provider :as us-cgt]
            [kontor.l10n-us.cgt-statute :as us-cgt-statute]
            [kontor.l10n-us.preset :as us-preset]
            [kontor.l10n-au.cgt-provider :as au-cgt]
            [kontor.l10n-au.cgt-statute :as au-cgt-statute]
            [kontor.l10n-uk.cgt-provider :as uk-cgt]
            [kontor.l10n-uk.cgt-statute :as uk-cgt-statute]
            [kontor.l10n-de.cgt-provider :as de-cgt]
            [kontor.l10n-de.cgt-statute :as de-cgt-statute]
            [kontor.l10n-de.cit-statute :as de-cit-statute]
            [kontor.tax.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixtures — one per jurisdiction: disposal schema + CGT statute +
;; commodity + a single HOLDCO entity.
;; ============================================================================

(defn- base-db
  "Fresh kernel DB + disposal companion + a commodity + one HOLDCO
   entity in `country`."
  [ccy ccy-name country]
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (d/transact conn [{:kontor.commodity/symbol ccy :kontor.commodity/name ccy-name
                       :kontor.commodity/precision 2}
                      {:kontor.entity/code "HOLDCO" :kontor.entity/name "HoldCo"
                       :kontor.entity/kind :company :kontor.entity/country country
                       :kontor.entity/functional-commodity [:kontor.commodity/symbol ccy]}])
    conn))

(defn- holdco-eid [conn]
  (d/q '[:find ?e . :where [?e :kontor.entity/code "HOLDCO"]] (d/db conn)))

(defn- record!
  "Record a disposal owned by HOLDCO, filling sensible defaults."
  [conn ccy opts]
  (let [c [:kontor.commodity/symbol ccy]]
    (disposal/record-disposal!
     conn (merge {:entity          [:kontor.entity/code "HOLDCO"]
                  :kind            :sale
                  :subject         c
                  :subject-kind    :fixed-asset
                  :recorded-by-uid "test"
                  :proceeds        {:amount 0M :commodity c}
                  :basis           {:amount 0M :commodity c}}
                 opts))))

(defn- run
  "Build `provider` and call period-tax-facts with the standard ctx."
  [conn provider period & [extra]]
  (ptp/period-tax-facts
   provider
   (merge {:db (d/db conn) :entity (holdco-eid conn) :period period} extra)))

(defn- by-lane [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

(def ^:private us-2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})
;; AU income year 2025-26 = 1 Jul 2025 → 30 Jun 2026.
(def ^:private au-fy26 {:from #inst "2025-07-01" :to #inst "2026-07-01"})
;; UK: land before 2025-04-06 so `as-of` reads the 10 % BADR rate.
(def ^:private uk-2024-25 {:from #inst "2024-11-01" :to #inst "2025-04-05"})
(def ^:private de-2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

;; ============================================================================
;; §1. US individual — §1(h) long-term brackets (GREEN)
;;
;; Authority: IRC §1(h); TY-2026 single 0 % ceiling $49,450, 15 % band to
;; $545,500 (IRS Rev. Proc. 2025-32, shipped in us-cgt-statute).
;; A $200,000 LT gain, single filer:
;;   first $49,450 @ 0 %   = $0
;;   next  $150,550 @ 15 % = $22,582.50
;; ============================================================================

(deftest us-individual-lt-1h-single-200k
  (testing "record → provider: $200k LT gain, single filer → $22,582.50"
    (let [conn (base-db "USD" "US Dollar" "US")
          _    (us-cgt-statute/install! conn)]
      (record! conn "USD"
               {:external-id "us-lt-200k"
                :acquired-on #inst "2020-01-01"
                :disposed-on #inst "2026-06-15"
                :proceeds {:amount 300000M :commodity [:kontor.commodity/symbol "USD"]}
                :basis    {:amount 100000M :commodity [:kontor.commodity/symbol "USD"]}})
      (let [provider (us-cgt/us-individual-cgt-provider
                      {:source (disp-provider/datahike-provider conn)})
            facts    (run conn provider us-2026 {:tax-unit {:filing-status :single}})
            lt       (by-lane facts :lt)]
        (is (some? lt) "an :lt component is emitted")
        (is (== 200000M (-> lt :base :amount)) "base = proceeds − basis")
        ;; 49,450 @ 0% + 150,550 @ 15% = 22,582.50
        (is (== 22582.5M (-> lt :liability :amount))
            "§1(h): single 0% ceiling $49,450 then 15% → $22,582.50")))))

;; ============================================================================
;; §2. recognize! posting integration (GREEN)
;;
;; Record a disposal, post the realising GL transaction with the
;; kontor.book verb facade, then recognize! (advance :recorded →
;; :recognized, linking the tx). A recognized disposal must STILL reach
;; the CGT provider (only :voided is excluded).
;; ============================================================================

(deftest us-recognize-links-realizing-transaction
  (testing "record → book/entry! → recognize! → provider still sees the disposal"
    (let [conn (us-preset/create-us-db)]      ; chart + journals + USD + statutes
      (disposal/install! conn)
      (d/transact conn [{:kontor.entity/code "HOLDCO" :kontor.entity/name "HoldCo"
                         :kontor.entity/kind :company :kontor.entity/country "US"
                         :kontor.entity/functional-commodity
                         [:kontor.commodity/symbol "USD"]}])
      (record! conn "USD"
               {:external-id "us-recog-1"
                :acquired-on #inst "2020-01-01"
                :disposed-on #inst "2026-06-15"
                :proceeds {:amount 150000M :commodity [:kontor.commodity/symbol "USD"]}
                :basis    {:amount 50000M  :commodity [:kontor.commodity/symbol "USD"]}})
      ;; Post the realising sale through the verb facade (a real,
      ;; balanced, sealed GL transaction).
      (let [report (book/entry!
                    conn {:debit-account  "Assets:Cash"
                          :credit-account "Income:Sales"
                          :amount 150000M
                          :commodity [:kontor.commodity/symbol "USD"]
                          :journal-type :general})
            tx-eid (get-in report [:tempids "datomic.tx"])]
        (is (integer? tx-eid) "book/entry! yields the realising tx eid")
        (disposal/recognize! conn {:disposal "us-recog-1"
                                   :transaction tx-eid
                                   :recorded-by-uid "test"})
        (let [d1 (disposal/pull-disposal (d/db conn) "us-recog-1")]
          (is (= :recognized (:kontor.disposal/state d1))
              "state advanced :recorded → :recognized")
          (is (some? (:kontor.disposal/realizing-tx d1))
              "realizing-tx link set"))
        ;; The recognized disposal STILL flows to the CGT provider.
        (let [provider (us-cgt/us-individual-cgt-provider
                        {:source (disp-provider/datahike-provider conn)})
              facts    (run conn provider us-2026 {:tax-unit {:filing-status :single}})
              lt       (by-lane facts :lt)]
          (is (some? lt) "recognized disposal reaches the provider")
          (is (== 100000M (-> lt :base :amount))))))))

;; ============================================================================
;; §3. AU individual — Div 115 50 % discount, loss netted PRE-discount
;;     (GREEN)
;;
;; Authority: ITAA 1997 s102-5 Method Statement. Losses (Steps 1-2)
;; apply BEFORE the 50 % discount (Step 3). $50k discountable gain and a
;; separate $10k capital loss:
;;   $50,000 − $10,000 = $40,000 pre-discount; × 50 % = $20,000 assessable.
;; ============================================================================

(deftest au-50pct-discount-loss-pre-discount
  (testing "s102-5: $50k LT gain − $10k loss = $40k, × 50 % → $20k assessable"
    (let [conn (base-db "AUD" "Australian Dollar" "AU")
          _    (au-cgt-statute/install! conn)
          aud  [:kontor.commodity/symbol "AUD"]]
      (record! conn "AUD"
               {:external-id "au-gain"
                :asset-class :au-listed-shares
                :acquired-on #inst "2022-06-01"
                :disposed-on #inst "2026-03-15"
                :proceeds {:amount 150000M :commodity aud}
                :basis    {:amount 100000M :commodity aud}})   ; +50k, >12mo
      (record! conn "AUD"
               {:external-id "au-loss"
                :asset-class :au-listed-shares
                :acquired-on #inst "2025-11-01"
                :disposed-on #inst "2026-03-15"
                :proceeds {:amount 0M     :commodity aud}
                :basis    {:amount 10000M :commodity aud}})     ; −10k loss
      (let [provider (au-cgt/au-cgt-provider
                      {:source (disp-provider/datahike-provider conn) :kind :individual})
            facts    (run conn provider au-fy26)
            cmp      (first (:components facts))]
        (is (some? cmp))
        (is (== 20000M (-> cmp :base :amount))
            "loss nets pre-discount → (50k−10k)×50% = 20k")
        (is (= [20000M] (get-in cmp [:jurisdiction-specific-codes :pit-base-additions])))))))

;; ============================================================================
;; §4. UK individual — BADR £1M lifetime cap + AEA overflow (GREEN)
;;
;; Authority: TCGA 1992 / FA 2024-25. TY 2024-25: AEA £3,000; BADR rate
;; 10 % (through 5 Apr 2025) on the first £1,000,000 lifetime; overflow
;; at the standard higher rate 24 %.
;; £1,500,000 all-BADR gain, higher-rate taxpayer:
;;   AEA £3,000 off the BADR lane   → £1,497,000
;;   BADR cap £1,000,000 @ 10 %     = £100,000
;;   overflow  £497,000 @ 24 %      = £119,280
;;   total CGT                      = £219,280
;; ============================================================================

(deftest uk-badr-lifetime-cap-overflow
  (testing "£1.5M BADR gain, higher-rate → £219,280 (cap + AEA + overflow)"
    (let [conn (base-db "GBP" "Pound sterling" "GB")
          _    (uk-cgt-statute/install! conn)
          gbp  [:kontor.commodity/symbol "GBP"]]
      (record! conn "GBP"
               {:external-id "uk-badr"
                :subject-kind :participation
                :asset-class :uk-trading-company-shares
                :exemption-claimed #{:uk-badr}
                :acquired-on #inst "2018-01-01"
                :disposed-on #inst "2025-02-01"
                :proceeds {:amount 1500000M :commodity gbp}
                :basis    {:amount 0M       :commodity gbp}})
      (let [provider (uk-cgt/uk-individual-cgt-provider
                      {:source (disp-provider/datahike-provider conn)})
            facts    (run conn provider uk-2024-25 {:tax-unit {:income-band :higher}})
            cmp      (by-lane facts :uk-individual-cgt)]
        (is (some? cmp))
        ;; £1M BADR + £497k standard = £1,497,000 taxable base
        (is (== 1497000M (-> cmp :base :amount)) "AEA £3k stripped, rest taxable")
        (is (== 219280M (-> cmp :liability :amount))
            "£1M@10% + £497k@24% = £219,280")))))

;; ============================================================================
;; §5. DE corporate — §8b KStG 95 % exempt / 5 % add-back (GREEN)
;;
;; Authority: §8b Abs. 2 + Abs. 3 KStG. A corporate participation
;; disposal gain of €1,000,000 is 95 % exempt; the 5 % pauschale
;; Betriebsausgaben add-back (€50,000) is fed to the CIT base.
;; ============================================================================

(deftest de-8b-corporate-95-5-split
  (testing "§8b: €1,000,000 participation gain → €50,000 5% add-back to CIT"
    (let [conn (base-db "EUR" "Euro" "DE")
          _    (de-cit-statute/install! conn)      ; Soli / KSt refs
          _    (de-cgt-statute/install! conn)
          eur  [:kontor.commodity/symbol "EUR"]]
      (record! conn "EUR"
               {:external-id "de-8b"
                :subject-kind :participation
                :asset-class :de-§8b-participation
                :acquired-on #inst "2018-01-01"
                :disposed-on #inst "2026-06-15"
                :proceeds {:amount 1000000M :commodity eur}
                :basis    {:amount 0M       :commodity eur}})
      (let [provider (de-cgt/de-corporate-cgt-provider
                      {:source (disp-provider/datahike-provider conn)})
            facts    (run conn provider de-2026)
            cmp      (by-lane facts :de-§8b)]
        (is (some? cmp))
        (is (== 1000000M (-> cmp :base :amount)) "gross gain")
        (is (= [50000M] (get-in cmp [:jurisdiction-specific-codes :cit-base-additions]))
            "§8b Abs. 3 KStG — 5% add-back = €50,000")))))

;; ============================================================================
;; §6. GAP — US individual net capital LOSS: §1211(b) silently dropped
;;     (PENDING)
;;
;; PENDING(NEW): A US individual whose only disposal is a $10,000 LT
;; capital loss gets EMPTY :components from us-individual-cgt-provider.
;; IRC §1211(b) lets an individual deduct up to $3,000 of net capital
;; loss against ordinary income each year, carrying the $7,000 excess
;; forward indefinitely (§1212(b)). The statute even ships the
;; parameter `US.CGT.§1211b.ordinary-offset-cap` = 3000M — but the
;; provider never reads it and emits NO :pit-base deduction and NO
;; carryforward. Net losses vanish; the consumer's PIT is overstated
;; and the carryforward is lost.
;; ============================================================================

(deftest us-individual-net-loss-1211b-dropped
  ;; FIXED (note 197): the US individual CGT provider now consumes the shipped
  ;; US.CGT.§1211b.ordinary-offset-cap ($3,000) — a net capital loss surfaces a
  ;; −$3,000 ordinary-income offset (§1211(b)) and a $7,000 long-term carry-
  ;; forward (§1212(b)). Authority: 26 USC §1211(b) / §1212(b) (Cornell LII).
  (testing "$10k LT net loss should surface a §1211(b) $3k ordinary offset + $7k carry"
    (let [conn (base-db "USD" "US Dollar" "US")
          _    (us-cgt-statute/install! conn)
          usd  [:kontor.commodity/symbol "USD"]]
      (record! conn "USD"
               {:external-id "us-lt-loss"
                :acquired-on #inst "2020-01-01"
                :disposed-on #inst "2026-06-15"
                :proceeds {:amount 0M      :commodity usd}
                :basis    {:amount 10000M  :commodity usd}})   ; −$10k LT loss
      (let [provider (us-cgt/us-individual-cgt-provider
                      {:source (disp-provider/datahike-provider conn)})
            facts    (run conn provider us-2026 {:tax-unit {:filing-status :single}})
            ;; A correct impl surfaces §1211(b): a −$3,000 ordinary
            ;; (PIT-base) deduction, and a $7,000 carryforward output.
            offsets  (mapcat #(get-in % [:jurisdiction-specific-codes :pit-base-additions])
                             (:components facts))
            carry    (some #(get-in % [:jurisdiction-specific-codes :capital-loss-carryforward])
                           (:components facts))]
        (is (seq (:components facts))
            "§1211(b) net-loss component is emitted")
        (is (some #(= -3000M %) offsets)
            "a −$3,000 ordinary-income offset reaches the PIT base")
        (is (== 7000M (:long carry))
            "§1212(b): the $7,000 excess carries forward as a long-term loss")
        (is (nil? (:short carry))
            "no short-term carryforward on an all-long-term loss")))))

;; ============================================================================
;; §7. GAP — AU loss ordering: loss eats the DISCOUNTABLE gain first,
;;     over-taxing (PENDING)
;;
;; PENDING(NEW): `apply-losses` walks taxable gains "FIFO in disposal
;; order" and applies capital losses regardless of discount eligibility.
;; ATO guidance (and every AU tax package) applies losses to
;; NON-discountable gains first to preserve the 50 % discount. With a
;; $10k discountable gain recorded before a $10k non-discountable gain
;; and a $10k loss, the loss lands on the discountable gain:
;;   discountable → 0 ; non-discountable $10k, no discount → $10k assessable.
;; The ATO-optimal (and what a consumer expects) result is:
;;   loss → non-discountable (0) ; discountable $10k × 50 % = $5k assessable.
;; The provider produces $10k — double — and the outcome depends on
;; the arbitrary disposal recording order. Verified by permutation: the
;; SAME three disposals yield $5,000 for record-order [disc nondisc loss]
;; but $10,000 for [nondisc disc loss]. This test pins the latter — the
;; provider's loss walk (`apply-losses`, "FIFO in disposal order") eats
;; the discountable gain first, wasting the 50 % discount.
;; ============================================================================

(deftest ^:kaocha/pending au-loss-order-overtaxes-discountable
  (testing "loss should hit the non-discountable gain first → $5k, not $10k"
    (let [conn (base-db "AUD" "Australian Dollar" "AU")
          _    (au-cgt-statute/install! conn)
          aud  [:kontor.commodity/symbol "AUD"]]
      ;; Record order [nondisc disc loss] → the loss pool consumes the
      ;; DISCOUNTABLE gain first → $10,000 assessable (over-taxed).
      (record! conn "AUD"
               {:external-id "au-nondisc-gain"      ; held <12mo → no discount
                :asset-class :au-listed-shares
                :acquired-on #inst "2025-12-01"
                :disposed-on #inst "2026-03-15"
                :proceeds {:amount 10000M :commodity aud}
                :basis    {:amount 0M     :commodity aud}})
      (record! conn "AUD"
               {:external-id "au-disc-gain"        ; held >12mo → discountable
                :asset-class :au-listed-shares
                :acquired-on #inst "2022-01-01"
                :disposed-on #inst "2026-03-15"
                :proceeds {:amount 10000M :commodity aud}
                :basis    {:amount 0M     :commodity aud}})
      (record! conn "AUD"
               {:external-id "au-cap-loss"          ; −$10k capital loss
                :asset-class :au-listed-shares
                :acquired-on #inst "2025-12-01"
                :disposed-on #inst "2026-03-15"
                :proceeds {:amount 0M     :commodity aud}
                :basis    {:amount 10000M :commodity aud}})
      (let [provider (au-cgt/au-cgt-provider
                      {:source (disp-provider/datahike-provider conn) :kind :individual})
            facts    (run conn provider au-fy26)
            cmp      (first (:components facts))]
        ;; ATO-optimal: loss → non-discountable; discountable × 50 % = $5,000.
        (is (== 5000M (-> cmp :base :amount))
            "loss-to-non-discountable-first should yield $5,000 assessable")))))
