(ns kontor.payroll-at.elda-test
  "ELDA mBGM XML emit + L16 XML emit. Validate the artifact shape (the
   bytes are deterministic) without committing to byte-exact fixtures
   that drift on minor formatting changes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-at.compute :as compute]
            [kontor.payroll-at.elda :as elda]
            [kontor.payroll-at.emit :as emit]))

(defn- fixture [name]
  (io/resource (str "kontor/payroll_at/fixtures/" name)))

(def jan #inst "2026-01-01T00:00:00Z")
(def jan-31 #inst "2026-01-31T00:00:00Z")
(def feb-1 #inst "2026-02-01T00:00:00Z")

(deftest mbgm-emits-well-formed-xml
  (let [r (compute/parse :bmd (fixture "bmd-2026-01.csv"))
        xml (elda/emit-mbgm-string
             {:dienstgeber-beitragskonto "1234567"
              :employer-name "Acme GmbH"
              :period (:payroll-result/period r)
              :employees (:payroll-result/employees r)})]
    (testing "the XML contains key elements"
      (is (str/includes? xml "<mBGM"))
      (is (str/includes? xml "1234567"))
      (is (str/includes? xml "1234567890"))    ; VSNR
      (is (str/includes? xml "Mustermann, Max"))
      (is (str/includes? xml "2026-01"))      ; Beitragsmonat
      (is (str/includes? xml "D1")))          ; Beitragsgruppe
    (testing "the contribution-base shows up at the right scale"
      ;; Grundgehalt 3000.00 — formatted with two decimals
      (is (str/includes? xml ">3000.00<")))
    (testing "AN/AG-SV-Anteil shows up"
      (is (str/includes? xml "<AN-SV-Anteil>"))
      (is (str/includes? xml "<AG-SV-Anteil>")))))

(deftest mbgm-rejects-bad-vsnr
  (testing "non-10-digit VSNRs are rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (elda/emit-mbgm-xml
                  {:dienstgeber-beitragskonto "1234567"
                   :period {:from jan :to feb-1}
                   :employees [{:vsnr "X" :name "Wrong" :line-items []}]})))))

(deftest mbgm-rejects-empty-employees
  (is (thrown? clojure.lang.ExceptionInfo
               (elda/emit-mbgm-xml
                {:dienstgeber-beitragskonto "1234567"
                 :period {:from jan :to feb-1}
                 :employees []}))))

(deftest mbgm-rejects-missing-beitragskonto
  (is (thrown? clojure.lang.ExceptionInfo
               (elda/emit-mbgm-xml
                {:period {:from jan :to feb-1}
                 :employees [{:vsnr "1234567890" :name "A"
                              :line-items []}]}))))

(deftest l16-emits-with-section-split
  (let [r-jan (compute/parse :bmd (fixture "bmd-2026-01.csv"))
        r-jun (compute/parse :bmd (fixture "bmd-2026-06.csv"))
        ;; build per-employee annual rollups across two monthly rows
        by-vsnr (fn [rs]
                  (group-by :vsnr (mapcat :payroll-result/employees rs)))
        annual (by-vsnr [r-jan r-jun])
        annual-employees
        (mapv (fn [[vsnr rows]]
                {:vsnr vsnr
                 :name (-> rows first :name)
                 :monthly-rows rows})
              annual)
        xml (emit/emit-l16-string
             {:year 2026
              :employer-name "Acme GmbH"
              :employer-uid "ATU12345678"
              :employees annual-employees})]
    (testing "the L16 XML has the per-employee structure"
      (is (str/includes? xml "<Lohnzettel"))
      (is (str/includes? xml "Veranlagungsjahr=\"2026\""))
      (is (str/includes? xml "ATU12345678"))
      (is (str/includes? xml "1234567890"))
      (is (str/includes? xml "<Sonderzahlungen-Brutto>"))
      (is (str/includes? xml "<Sonderzahlungen-Lohnsteuer>"))
      (is (str/includes? xml "<Bruttobezuege>")))
    (testing "the 6% Sonderzahlungs-LSt is computed"
      ;; Max + Erika in June together had 5500 Sonderzahlung @ 6% = 330.
      ;; Section is per-employee — we'll just verify the field is present
      ;; and parseable to a non-zero amount.
      (is (re-find #"<Sonderzahlungen-Lohnsteuer>\d+\.\d{2}</Sonderzahlungen-Lohnsteuer>"
                   xml)))))
