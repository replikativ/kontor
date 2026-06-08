(ns kontor.payroll-de-datev.compute-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-de-datev.compute :as compute]
            [kontor.provider.payroll-provider :as pp]))

(defn- fixture
  []
  (slurp (io/resource "kontor/payroll_de_datev/fixtures/buchungsbeleg-2025-11.csv")
         :encoding "ISO-8859-1"))

;; ============================================================================
;; Header parsing
;; ============================================================================

(deftest parse-header-extracts-load-bearing-fields
  (let [header-line (first (str/split-lines (fixture)))
        h (compute/parse-header header-line)]
    (is (= "EXTF" (:format h)))
    (is (= "510"  (:schema h)))
    (is (= "21"   (:version h)))
    (is (= "Buchungsstapel" (:section h)))
    (is (= 2025   (:year h)))
    (is (= "Acme GmbH" (:company-name h)))
    (is (= "12345" (:client-number h)))))

;; ============================================================================
;; End-to-end parse round-trip
;; ============================================================================

(deftest parse-buchungsbeleg-builds-one-fact-per-employee-period
  (let [{:keys [header facts]} (compute/parse-buchungsbeleg (fixture))]
    (is (= 1 (count facts)))
    (let [f (first facts)]
      (testing "headline aggregates4"
        (is (= 4000.00M (:gross f)))
        (is (= 2500.00M (:net f)))
        (is (= 700.00M  (:withholding-tax f)))
        (is (= 800.00M  (:employee-si f)))
        (is (= 800.00M  (:employer-si f))))
      (testing "employee Pnr surfaces from Belegfeld 1"
        (is (= "3011" (:employment-pnr f))))
      (testing "pay-period surfaces from Belegfeld 2"
        (is (= "11/2025" (:pay-period f))))
      (testing "components are classified by SKR04 account code"
        (let [kinds (set (map :kind (:components f)))]
          (is (contains? kinds :base-salary))      ; 6020 gehalt
          (is (contains? kinds :withholding-tax))  ; 3730
          (is (contains? kinds :employee-si))      ; 3740 via Verrechnung
          (is (contains? kinds :employer-si))))    ; 6110
      (testing "raw postings are preserved for audit / re-emit"
        (is (= 5 (count (:raw-postings f))))))))

(deftest parse-buchungsbeleg-rejects-corrupt-verrechnung
  ;; Mutate the fixture so Verrechnung does not balance to zero —
  ;; drop the SV AN-Anteil row by selecting the unique prefix and
  ;; trimming through the next newline.
  (let [content (fixture)
        marker  "800,00;S;EUR;;;;3790;3740"
        idx (.indexOf content marker)
        end (.indexOf content "\n" idx)
        bad (str (subs content 0 idx) (subs content (inc end)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Verrechnungskonto"
                          (compute/parse-buchungsbeleg bad)))))

;; ============================================================================
;; DatevLodasComputeProvider — protocol satisfaction
;; ============================================================================

(deftest compute-provider-derives-facts-from-buchungsbeleg
  (let [provider (compute/make-provider {:coa :skr04})
        facts (pp/compute-payroll
               provider
               {:employment-eids [99]
                :variable-inputs {:buchungsbeleg-content (fixture)}})]
    (is (= 1 (count facts)))
    (is (= 99 (-> facts first :employment)))     ; single-employment auto-binding
    (is (= 4000.00M (-> facts first :gross)))))

(deftest compute-provider-uses-pnr-mapping-when-multi-employment
  (let [provider (compute/make-provider
                  {:coa :skr04
                   :employment-pnr->eid {"3011" 42}})
        facts (pp/compute-payroll
               provider
               {:employment-eids [42 999]
                :variable-inputs {:buchungsbeleg-content (fixture)}})]
    (is (= 1 (count facts)))
    (is (= 42 (-> facts first :employment)))))

(deftest compute-provider-accepts-pre-parsed-facts
  (let [pre [{:employment-pnr "3011" :employment 7
              :gross 4000M :net 2500M
              :components []}]
        provider (compute/make-provider
                  {:coa :skr04
                   :employment-pnr->eid {"3011" 7}})
        facts (pp/compute-payroll
               provider
               {:employment-eids [7]
                :variable-inputs {:facts pre}})]
    (is (= 1 (count facts)))
    (is (= 7 (-> facts first :employment)))))

(deftest provider-id-is-datev-lodas
  (is (= :datev-lodas (pp/provider-id (compute/make-provider)))))
