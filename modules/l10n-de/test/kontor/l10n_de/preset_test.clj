(ns kontor.l10n-de.preset-test
  "Acceptance: one `install-all!` call yields a working DE tax stack
   that posts via `kontor.book`, produces GuV+Bilanz, and computes
   Abgeltungsteuer correctly. Note 160 §I-8."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.l10n-de.bs :as de-bs]
            [kontor.l10n-de.investment-income-provider :as de-inv]
            [kontor.l10n-de.pnl :as de-pnl]
            [kontor.l10n-de.preset :as preset]
            [kontor.period-tax-provider :as ptp]))

(def ^:private eur [:kontor.commodity/symbol "EUR"])

(deftest one-call-install-yields-working-stack
  (testing "(preset/create-de-db) returns a fully wired DE conn"
    (let [conn (preset/create-de-db)
          db   (d/db conn)]
      (testing "SKR04 chart is present (incl. tax + dividend accounts per I-18)"
        (let [paths (set (d/q '[:find [?path ...] :where [_ :account/path ?path]] db))]
          (is (>= (count paths) 46) "expected ~50 SKR04 accounts")
          ;; F18 / I-18 regression: the corp-tax + dividend accounts must ship
          (is (contains? paths "Aufwendungen:Steuern:KSt"))
          (is (contains? paths "Aufwendungen:Steuern:GewSt"))
          (is (contains? paths "Verbindlichkeiten:Steuern:KSt-Rückstellung"))
          (is (contains? paths "Verbindlichkeiten:Dividenden-Zahlbar"))
          (is (contains? paths "Verbindlichkeiten:KESt-Zahlbar"))))
      (testing "default 5 journals are present"
        (let [n (count (d/q '[:find [?c ...] :where [_ :journal/code ?c]] db))]
          (is (= 5 n))))
      (testing "EUR commodity is present"
        (is (some? (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "EUR"]] db))))
      (testing "DE tax statutes are installed"
        (let [provisions (set (d/q '[:find [?code ...]
                                     :where [?p :provision/jurisdiction :de]
                                            [?p :provision/code ?code]] db))]
          ;; Soli + KiSt on §20-income (F8 regression — both shipped by IC statute)
          (is (contains? provisions "DE-SolZG-§4-on-§20-income"))
          (is (contains? provisions "DE-KiStG-on-§20"))
          ;; CIT + CGT shipped
          (is (contains? provisions "DE-KStG-§10"))
          (is (contains? provisions "DE-SolZG-§4-on-§20")))))))

(deftest end-to-end-post-and-report-via-preset
  (testing "Hans-Tech UG year on a preset DB — post via kontor.book,
            produce GuV + Bilanz, no chart-setup ceremony"
    (let [conn (preset/create-de-db)
          gj   {:journal [:journal/code "GJ"]}
          cr   {:journal [:journal/code "CR"]}
          cd   {:journal [:journal/code "CD"]}
          e    (fn [opts] (book/entry! conn (assoc opts :commodity eur)))]
      ;; Opening capital
      (e (merge gj {:effective-date #inst "2026-01-02"
                    :narration "Opening Bank"
                    :postings [{:account [:account/path "Umlaufvermögen:Bank"] :amount 50000M}
                               {:account [:account/path "Eigenkapital:Privateinlagen"] :amount -50000M}]}))
      ;; Service revenue €30k + €5.7k USt
      (e (merge cr {:effective-date #inst "2026-03-31"
                    :narration "Beratung Acme Q1"
                    :postings [{:account [:account/path "Umlaufvermögen:Bank"] :amount 35700M}
                               {:account [:account/path "Erträge:Erlöse:19%"]  :amount -30000M}
                               {:account [:account/path "Verbindlichkeiten:Umsatzsteuer:19%"] :amount -5700M}]}))
      ;; Rent expense €18k + €3.42k Vorsteuer
      (e (merge cd {:effective-date #inst "2026-12-15"
                    :narration "Miete 2026"
                    :postings [{:account [:account/path "Aufwendungen:Raum:Miete"]      :amount 18000M}
                               {:account [:account/path "Umlaufvermögen:Vorsteuer:19%"]  :amount 3420M}
                               {:account [:account/path "Umlaufvermögen:Bank"]           :amount -21420M}]}))

      (testing "GuV (HGB §275 Abs. 2)"
        (let [guv (de-pnl/compute conn {:from #inst "2026-01-01"
                                        :through #inst "2026-12-31"})  ; ← :through, inclusive
              sections (into {} (map (juxt :section/code identity) (:statement/sections guv)))]
          (is (== 30000M (some-> (get sections "1") :section/subtotal :amount))
              "Umsatzerlöse")
          (is (== 18000M (some-> (get sections "6") :section/subtotal :amount))
              "Sonstige betriebliche Aufwendungen")
          (is (== 12000M (some-> guv :statement/total :amount))
              "Gewinn vor Steuern = 30k − 18k = 12k")))

      (testing "Bilanz (HGB)"
        (let [aktiva  (de-bs/compute-aktiva  conn {:through #inst "2026-12-31"})
              passiva (de-bs/compute-passiva conn {:through #inst "2026-12-31"})]
          ;; Aktiva = Bank (50k + 35.7k − 21.42k = 64.28k) + Vorsteuer 3.42k
          ;;        = 67.7k
          (is (== 67700M (some-> aktiva :statement/total :amount))
              "Aktiva total")
          ;; Passiva = Eigenkapital 50k + USt 5.7k = 55.7k
          ;;          (difference = 12k = unbooked profit — correct mid-period)
          (is (== 55700M (some-> passiva :statement/total :amount))
              "Passiva total — Aktiva − Passiva = 12k = pre-closing profit"))))))

(deftest investment-income-works-via-preset
  (testing "IC provider produces correct Abgeltungsteuer + Soli with NO extra
            install steps — F8 regression"
    (let [conn (preset/create-de-db)
          provider (de-inv/de-investment-income-provider {})
          facts (ptp/period-tax-facts
                 provider
                 {:db (d/db conn) :entity nil
                  :period {:from #inst "2026-01-01" :to #inst "2027-01-01"}
                  :tax-unit {:filing-status :single :church-tax-rate 0M}
                  :inputs {:investment-income-bases
                           {:dividends 5000M :interest 0M
                            :fund-distributions 0M :royalties 0M
                            :elected-dividends 0M}}})
          §20   (->> (:components facts)
                     (filter #(= :de-§20-income (get-in % [:jurisdiction-specific-codes :lane])))
                     first)]
      (is (some? §20))
      (is (== 4000M (-> §20 :base :amount))
          "5000 − 1000 SP = 4000 base")
      (is (== 1000M (-> §20 :gross-liability :amount))
          "25 % × 4000 = 1000 Abgst")
      (is (== 1055M (-> §20 :liability :amount))
          "+ 5.5 % Soli (55) = 1055 — Soli MUST fire via preset"))))
