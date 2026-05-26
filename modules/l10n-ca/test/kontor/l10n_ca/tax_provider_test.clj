(ns kontor.l10n-ca.tax-provider-test
  "Golden-fixture tests for the Canadian ADR-071 tax provider
   (research notes 100 / 101 — CA is the multi-authority Shape-B
   case). Validates that `CaTaxRateProvider` + `CaTaxPostingBuilder`
   resolve CA tax statuses + ship-to provinces to the right multi-
   component `TaxFacts` and per-authority postings. The behaviour-
   identical regression of the *invoice* path is covered by
   `invoice_test.clj`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-ca.tax-provider :as cap]
            [kontor.tax-posting-builder :as tpb]
            [kontor.tax-rate-provider :as trp]))

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "CAD" :kontor.commodity/name "Canadian Dollar"
                  :kontor.commodity/precision 2}
                 {:kontor.account/code "2310" :kontor.account/path "GST-HST" :kontor.account/type :liability}
                 {:kontor.account/code "2320" :kontor.account/path "BC-PST"  :kontor.account/type :liability}
                 {:kontor.account/code "2321" :kontor.account/path "SK-PST"  :kontor.account/type :liability}
                 {:kontor.account/code "2322" :kontor.account/path "MB-RST"  :kontor.account/type :liability}
                 {:kontor.account/code "2330" :kontor.account/path "QST"     :kontor.account/type :liability}])
    conn))

(def ^:private cad [:kontor.commodity/symbol "CAD"])
(def ^:private d1 #inst "2026-03-01")

;; ============================================================================
;; CaTaxRateProvider — province + tax-status → TaxFacts
;; ============================================================================

(deftest rate-facts-hst-province
  (testing "ON (13% HST) → one :output-vat component to CRA"
    (let [prov  (cap/make-ca-tax-rate-provider)
          facts (trp/rate-facts prov {:base 1000M :ship-to-province :ON
                                      :commodity cad})
          cs    (:components facts)]
      (is (= 1 (count cs)))
      (let [c (first cs)]
        (is (= :output-vat (:kind c)))
        (is (== 130M (:amount c)))
        (is (== 0.13M (:rate c)))
        (is (= :ca-cra (get-in c [:jurisdiction :authority])))
        (is (= :ca/hst (get-in c [:jurisdiction-specific-codes :ca/tax-code])))
        (is (true? (:recoverable? c)))))))

(deftest rate-facts-gst-only-province
  (testing "AB (5% GST only) → one :output-vat component to CRA"
    (let [prov  (cap/make-ca-tax-rate-provider)
          facts (trp/rate-facts prov {:base 1000M :ship-to-province :AB
                                      :commodity cad})
          cs    (:components facts)]
      (is (= 1 (count cs)))
      (is (= :output-vat (:kind (first cs))))
      (is (== 50M (:amount (first cs))))
      (is (= :ca/gst (get-in (first cs) [:jurisdiction-specific-codes :ca/tax-code]))))))

(deftest rate-facts-gst-plus-pst-province
  (testing "BC (5% GST + 7% PST) → TWO components, different authorities"
    (let [prov  (cap/make-ca-tax-rate-provider)
          facts (trp/rate-facts prov {:base 1000M :ship-to-province :BC
                                      :commodity cad})
          cs    (:components facts)
          by-auth (into {} (map (juxt #(get-in % [:jurisdiction :authority])
                                      identity))
                        cs)]
      (is (= 2 (count cs)))
      (let [gst (get by-auth :ca-cra)
            pst (get by-auth :bc-finance)]
        (is (= :output-vat (:kind gst)))
        (is (== 50M (:amount gst)))
        (is (= :sales-tax (:kind pst)) "PST is non-recoverable → :sales-tax")
        (is (== 70M (:amount pst)))
        (is (false? (:recoverable? pst)))
        (is (= :ca/pst (get-in pst [:jurisdiction-specific-codes :ca/tax-code])))))))

(deftest rate-facts-quebec-gst-plus-qst
  (testing "QC (5% GST + 9.975% QST) → TWO :output-vat components,
              CRA + Revenu Québec authorities"
    (let [prov  (cap/make-ca-tax-rate-provider)
          facts (trp/rate-facts prov {:base 1000M :ship-to-province :QC
                                      :commodity cad})
          cs    (:components facts)
          by-auth (into {} (map (juxt #(get-in % [:jurisdiction :authority])
                                      identity))
                        cs)]
      (is (= 2 (count cs)))
      (let [gst (get by-auth :ca-cra)
            qst (get by-auth :ca-rq)]
        (is (= :output-vat (:kind gst)))
        (is (== 50M (:amount gst)))
        (is (= :output-vat (:kind qst)) "QST is VAT-style → :output-vat")
        (is (== 99.75M (:amount qst)))
        (is (true? (:recoverable? qst)))
        (is (= :ca/qst (get-in qst [:jurisdiction-specific-codes :ca/tax-code])))))))

(deftest rate-facts-zero-rated-and-exempt
  (testing "zero-rated / exempt / non-resident → no tax (nil facts)"
    (let [prov (cap/make-ca-tax-rate-provider)]
      (doseq [status [:zero-rated :exempt :non-resident]]
        (is (nil? (trp/rate-facts prov {:base 1000M :ship-to-province :ON
                                        :tax-status status}))
            (str status " → nil facts"))))))

(deftest rate-facts-are-structurally-valid
  (testing "every emitted TaxFacts passes the closed-vocabulary check"
    (let [prov (cap/make-ca-tax-rate-provider)]
      (doseq [prov-code [:ON :AB :BC :SK :MB :QC]]
        (is (trp/valid-tax-facts?
             (trp/rate-facts prov {:base 1000M :ship-to-province prov-code
                                   :commodity cad}))
            (str prov-code " facts are valid"))))))

;; ============================================================================
;; CaTaxPostingBuilder — TaxFacts → per-authority postings
;; ============================================================================

(deftest builder-routes-each-authority-to-its-account
  (let [conn (fresh)
        db   (d/db conn)
        prov (cap/make-ca-tax-rate-provider)
        bld  (cap/make-ca-tax-posting-builder)
        a    (fn [code] (d/q '[:find ?e . :in $ ?c :where [?e :kontor.account/code ?c]]
                             db code))
        post (fn [province]
               (tpb/compute-tax-postings
                prov bld
                {:base 1000M :ship-to-province province :commodity cad}
                {:db db :date d1}))]
    (testing "ON → one credit of 130 to 2310 (GST/HST)"
      (let [p (first (post :ON))]
        (is (= (a "2310") (:posting/account p)))
        (is (== -130M (:posting/amount p)) "output tax is a credit")
        (is (= :tax (:posting/display-type p)))))
    (testing "BC → 50 to 2310, 70 to 2320"
      (let [ps (post :BC)
            by-acct (into {} (map (juxt :posting/account :posting/amount)) ps)]
        (is (= 2 (count ps)))
        (is (== -50M (get by-acct (a "2310"))))
        (is (== -70M (get by-acct (a "2320"))))))
    (testing "QC → 50 to 2310, 99.75 to 2330 (QST → Revenu Québec)"
      (let [ps (post :QC)
            by-acct (into {} (map (juxt :posting/account :posting/amount)) ps)]
        (is (= 2 (count ps)))
        (is (== -50M (get by-acct (a "2310"))))
        (is (== -99.75M (get by-acct (a "2330"))))))
    (testing "SK PST routes to 2321, MB RST to 2322"
      (let [sk (into {} (map (juxt :posting/account :posting/amount)) (post :SK))
            mb (into {} (map (juxt :posting/account :posting/amount)) (post :MB))]
        (is (== -60M (get sk (a "2321"))) "SK PST 6% to 2321")
        (is (== -70M (get mb (a "2322"))) "MB RST 7% to 2322")))
    (testing "zero-rated / exempt → no leg"
      (let [zr (tpb/compute-tax-postings
                prov bld
                {:base 1000M :ship-to-province :ON :tax-status :zero-rated
                 :commodity cad}
                {:db db :date d1})]
        (is (= [] zr))))))

(deftest aggregate-collapses-same-authority-lines
  (let [conn (fresh)
        db   (d/db conn)
        prov (cap/make-ca-tax-rate-provider)
        bld  (cap/make-ca-tax-posting-builder)
        a    (fn [code] (d/q '[:find ?e . :in $ ?c :where [?e :kontor.account/code ?c]]
                             db code))
        raw  (mapcat (fn [base]
                       (tpb/compute-tax-postings
                        prov bld
                        {:base base :ship-to-province :BC :commodity cad}
                        {:db db :date d1}))
                     [100M 200M 50M])
        agg  (tpb/aggregate-postings raw)
        by-acct (into {} (map (juxt :posting/account :posting/amount)) agg)]
    (is (= 6 (count raw)) "three BC lines → six raw postings (GST + PST each)")
    (is (= 2 (count agg)) "collapsed to one per authority")
    ;; net 350: GST 5% = 17.50, PST 7% = 24.50
    (is (== -17.50M (get by-acct (a "2310"))) "GST aggregated")
    (is (== -24.50M (get by-acct (a "2320"))) "BC PST aggregated")))

;; ============================================================================
;; Override-codes plumb-through
;; ============================================================================

(deftest builder-honours-code-overrides
  (testing ":codes override pins a different GST/HST account"
    (let [conn (fresh)
          _    (d/transact conn [{:kontor.account/code "9999"
                                  :kontor.account/path "Custom-GST"
                                  :kontor.account/type :liability}])
          db   (d/db conn)
          prov (cap/make-ca-tax-rate-provider)
          bld  (cap/make-ca-tax-posting-builder {:codes {:gst-hst-code "9999"}})
          a9999 (d/q '[:find ?e . :where [?e :kontor.account/code "9999"]] db)
          p    (first (tpb/compute-tax-postings
                       prov bld
                       {:base 1000M :ship-to-province :ON :commodity cad}
                       {:db db :date d1}))]
      (is (= a9999 (:posting/account p)) "routed to the overridden account")
      (is (== -130M (:posting/amount p))))))
