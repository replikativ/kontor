(ns kontor.payroll-fr.dsn-test
  "Tests for the DSN (Déclaration Sociale Nominative) structure helpers."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-fr.dsn :as dsn]))

;; ============================================================================
;; rubrique-line format
;; ============================================================================

(deftest rubrique-line-shape
  (testing "Basic NEODES line shape: S<bloc>,<rubrique>,'<value>'"
    (is (= "S10.G00.00,001,'123456782'"
           (dsn/rubrique-line "S10.G00.00" "001" "123456782"))))
  (testing "Embedded single quotes are doubled per NEODES spec"
    (is (= "S10.G00.00,002,'L''Oréal SA'"
           (dsn/rubrique-line "S10.G00.00" "002" "L'Oréal SA"))))
  (testing "BigDecimal amounts render with 2 decimals + dot separator"
    (is (= "S21.G00.51,013,'3500.00'"
           (dsn/rubrique-line "S21.G00.51" "013" 3500M))))
  (testing "java.util.Date renders as DDMMYYYY"
    (is (= "S10.G00.00,008,'31052026'"
           (dsn/rubrique-line "S10.G00.00" "008" #inst "2026-05-31")))))

(deftest amount-rounding
  (testing "BigDecimal amounts round HALF_EVEN to 2 decimals"
    ;; 0.125 → 0.12 (HALF_EVEN); 0.135 → 0.14
    (is (= "S21.G00.51,013,'0.12'"
           (dsn/rubrique-line "S21.G00.51" "013" 0.125M)))
    (is (= "S21.G00.51,013,'0.14'"
           (dsn/rubrique-line "S21.G00.51" "013" 0.135M)))))

;; ============================================================================
;; envelope
;; ============================================================================

(deftest envelope-lines-shape
  (testing "Envelope produces S10.G00.00 + S10.G00.01 blocks"
    (let [lines (dsn/envelope-lines
                 {:siren "123456782"
                  :nom-emetteur "Acme France SAS"
                  :adresse-emetteur "10 rue de la Paix"
                  :telephone-emetteur "+33145000000"
                  :email-emetteur "paie@acme.fr"
                  :code-organisme "URSSAF-IDF"
                  :date-creation #inst "2026-06-05"
                  :nature :reel
                  :type-envoi :normal})]
      (is (= 11 (count lines)))
      (is (str/starts-with? (first lines) "S10.G00.00,001"))
      (is (str/includes? (first lines) "123456782"))
      ;; Last line is S10.G00.01 type-envoi
      (is (= "S10.G00.01,001,'01'" (last lines)))))
  (testing "Test-mode + déclaration néant flags render as '02'"
    (let [lines (dsn/envelope-lines
                 {:siren "123456782" :nom-emetteur "X" :adresse-emetteur "Y"
                  :telephone-emetteur "Z" :email-emetteur "W"
                  :code-organisme "Q" :date-creation #inst "2026-06-05"
                  :nature :test :type-envoi :neant})]
      (is (str/includes? (nth lines 5) "'02'"))  ; nature = test
      (is (= "S10.G00.01,001,'02'" (last lines)))))) ; type-envoi = néant

;; ============================================================================
;; etablissement — NIC extraction from SIRET
;; ============================================================================

(deftest etablissement-nic-extraction
  (testing "Établissement block extracts NIC (last 5 digits) from SIRET"
    (let [lines (dsn/etablissement-lines
                 {:siret "12345678900012"
                  :code-ape "6201Z"
                  :adresse "10 rue de la Paix"
                  :code-postal "75001"
                  :ville "Paris"})]
      (is (= "S21.G00.11,001,'00012'" (first lines)))
      (is (= "S21.G00.11,002,'6201Z'" (second lines))))))

;; ============================================================================
;; full build-payload assembly
;; ============================================================================

(def envelope-fixture
  {:siren "123456782"
   :nom-emetteur "Acme France SAS"
   :adresse-emetteur "10 rue de la Paix 75001 Paris"
   :telephone-emetteur "+33145000000"
   :email-emetteur "paie@acme.fr"
   :code-organisme "URSSAF-IDF-110"
   :date-creation #inst "2026-06-05"
   :nature :reel
   :type-envoi :normal})

(def entreprise-fixture
  {:siren "123456782" :ape "6201Z"})

(def etablissement-fixture
  {:siret "12345678900012" :code-ape "6201Z"
   :adresse "10 rue de la Paix" :code-postal "75001" :ville "Paris"})

(def dupont-individu
  {:nir "180056789012345"  :nom-de-famille "Dupont" :prenom "Jean"
   :date-de-naissance #inst "1980-05-15" :sexe :h
   :lieu-de-naissance "75056" :adresse "10 rue de Rivoli"
   :code-postal "75004" :ville "Paris"})

(deftest build-payload-shape
  (testing "build-payload composes envelope + entreprise + établissement + individus"
    (let [payload (dsn/build-payload
                   {:envelope envelope-fixture
                    :entreprise entreprise-fixture
                    :etablissement etablissement-fixture
                    :individus
                    [{:individu dupont-individu
                      :versement-individu {:date-versement #inst "2026-05-31"
                                           :montant-net 2566.36M
                                           :montant-pas 284M
                                           :taux-pas 0.075M
                                           :type-pas :perso}
                      :remunerations [{:type-rubrique "001"
                                       :date-debut #inst "2026-05-01"
                                       :date-fin #inst "2026-05-31"
                                       :montant 3500M}
                                      {:type-rubrique "017"
                                       :date-debut #inst "2026-05-01"
                                       :date-fin #inst "2026-05-31"
                                       :montant 200M}]
                      :cotisations [{:code-cotisation "100"
                                     :base 3700M
                                     :montant 284.80M}
                                    {:code-cotisation "260"
                                     :base 3700M
                                     :montant 240.87M}]}]})]
      (is (every? string? payload))
      ;; Must contain envelope (S10.G00.00) + entreprise (S21.G00.06) +
      ;; établissement (S21.G00.11) + individu (S21.G00.30) +
      ;; versement (S21.G00.50) + remunerations (S21.G00.51) +
      ;; cotisations (S21.G00.81).
      (is (some #(str/starts-with? % "S10.G00.00") payload))
      (is (some #(str/starts-with? % "S21.G00.06") payload))
      (is (some #(str/starts-with? % "S21.G00.11") payload))
      (is (some #(str/starts-with? % "S21.G00.30") payload))
      (is (some #(str/starts-with? % "S21.G00.50") payload))
      (is (some #(str/starts-with? % "S21.G00.51") payload))
      (is (some #(str/starts-with? % "S21.G00.81") payload))
      ;; Two remunerations + two cotisations should each appear
      (is (= 8 (count (filter #(str/starts-with? % "S21.G00.51") payload))))
      ;; 2 cotisations × 3 lines each
      (is (= 6 (count (filter #(str/starts-with? % "S21.G00.81") payload)))))))

(deftest serialize-uses-crlf
  (testing "serialize uses CRLF line endings per net-entreprises.fr spec"
    (let [lines ["S10.G00.00,001,'A'" "S10.G00.00,002,'B'"]
          serialized (dsn/serialize lines)]
      (is (str/includes? serialized "\r\n"))
      (is (str/ends-with? serialized "\r\n")))))

;; ============================================================================
;; facts → payload (the load-bearing fn)
;; ============================================================================

(deftest facts-to-payload-round-trip
  (testing "Single fact → minimal-but-complete DSN payload"
    (let [fact {:employment 101
                :gross 3700M
                :net 2566.36M
                :components [{:kind :base-salary :amount 3500M :employer-side? false}
                             {:kind :overtime :amount 200M :employer-side? false}
                             {:kind :cotisation-urssaf :amount -284.80M :employer-side? false}
                             {:kind :csg-deductible :amount -240.87M :employer-side? false}
                             {:kind :pas-withholding :amount -284M :employer-side? false}]
                :jurisdiction-specific-codes
                {:base-soumise-urssaf 3700M
                 :taux-pas 0.075M}}
          payload (dsn/facts->payload
                   {:facts [fact]
                    :envelope envelope-fixture
                    :entreprise entreprise-fixture
                    :etablissement etablissement-fixture
                    :persons-by-emp {101 dupont-individu}
                    :pay-period-start #inst "2026-05-01"
                    :pay-period-end #inst "2026-05-31"
                    :date-versement #inst "2026-05-31"
                    :type-pas :perso})]
      ;; Envelope + entreprise + établissement + individu + versement +
      ;; 2 remunerations + 2 cotisations
      (is (some #(str/includes? % "3500.00") payload))
      (is (some #(str/includes? % "200.00") payload))
      ;; PAS rendered correctly on the versement
      (is (some #(and (str/starts-with? % "S21.G00.50,004") (str/includes? % "284.00")) payload))
      ;; Taux PAS rendered
      (is (some #(and (str/starts-with? % "S21.G00.50,006") (str/includes? % "0.08")) payload)))))

(deftest facts-to-payload-missing-person-throws
  (testing "facts->payload throws clearly when persons-by-emp returns nil"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"missing person"
         (dsn/facts->payload
          {:facts [{:employment 999 :gross 100M :net 100M :components []}]
           :envelope envelope-fixture
           :entreprise entreprise-fixture
           :etablissement etablissement-fixture
           :persons-by-emp {}
           :pay-period-start #inst "2026-05-01"
           :pay-period-end #inst "2026-05-31"
           :date-versement #inst "2026-05-31"})))))
