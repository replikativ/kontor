(ns kontor.l10n-at.statements-test
  "The Austrian GuV (UGB § 231) + Bilanz (UGB § 224) against a
   hand-computed book.

   Every expectation below is arithmetic done by hand in the comment
   block, not a value captured from a previous run — a golden-value test
   would pass just as happily if the definitions double-counted.

   Amounts are compared with `==` (scale-insensitive numeric equality on
   the BigDecimal `:amount`); commodity is asserted separately."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.book :as book]
            [kontor.l10n-at.bs :as bs]
            [kontor.l10n-at.pnl :as pnl]
            [kontor.l10n-at.preset :as at]
            [kontor.reporting.financial-statements :as fs]))

(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private gj  [:kontor.journal/code "GJ"])

(defn- acct [path] [:kontor.account/path path])

(defn- two-leg! [conn date debit credit amount]
  (book/entry! conn {:debit-account  (acct debit)
                     :credit-account (acct credit)
                     :amount         amount
                     :commodity      eur
                     :journal        gj
                     :effective-date date}))

(defn- multi! [conn date postings]
  (book/entry! conn {:postings   (mapv (fn [[path amt]]
                                         {:account (acct path) :amount amt})
                                       postings)
                     :commodity  eur
                     :journal    gj
                     :effective-date date}))

;; Account paths (from kontor.l10n-at.chart / kontenrahmen.edn)
(def ^:private bank        "Umlaufvermögen:Bank")               ; 2800 asset
(def ^:private forderungen "Umlaufvermögen:Forderungen")        ; 2000 asset
(def ^:private vorsteuer   "Umlaufvermögen:Vorsteuer:Normal")   ; 2500 asset
(def ^:private lieferanten "Verbindlichkeiten:Lieferanten")     ; 3300 liability
(def ^:private ust         "Verbindlichkeiten:Umsatzsteuer:Normal") ; 3500 liability
(def ^:private erloese     "Erträge:Erlöse:20")                 ; 4000 income
(def ^:private wareneink   "Aufwendungen:Wareneinkauf")         ; 5000 expense
(def ^:private personal    "Aufwendungen:Personal")            ; 6000 expense
(def ^:private miete       "Aufwendungen:Raum")                ; 7000 expense
(def ^:private versicherung "Aufwendungen:Versicherungen")     ; 7100 expense
(def ^:private beratung    "Aufwendungen:Beratung")            ; 7700 expense
(def ^:private maschinen   "Anlagevermögen:Maschinen")         ; 0400 asset
(def ^:private stammkapital "Eigenkapital:Stammkapital")       ; 9000 equity

(defn- seed!
  "A small realistic FY2026 for a GmbH, all amounts NET with 20% USt where
   it applies. Touches every section both statements cover."
  [conn]
  ;; owner pays in Stammkapital
  (two-leg! conn #inst "2026-01-02" bank stammkapital 50000M)
  ;; buy a machine on the bank, 20% Vorsteuer
  (multi!   conn #inst "2026-01-10" [[maschinen 10000M] [vorsteuer 2000M] [bank -12000M]])
  ;; credit sale, net 100000 + 20000 USt
  (multi!   conn #inst "2026-03-01" [[forderungen 120000M] [erloese -100000M] [ust -20000M]])
  ;; buy goods on account, 20% Vorsteuer
  (multi!   conn #inst "2026-04-10" [[wareneink 30000M] [vorsteuer 6000M] [lieferanten -36000M]])
  ;; salaries (no VAT)
  (two-leg! conn #inst "2026-05-01" personal bank 24000M)
  ;; rent (no VAT for simplicity)
  (two-leg! conn #inst "2026-06-01" miete bank 12000M)
  ;; insurance (VAT-exempt in AT)
  (two-leg! conn #inst "2026-06-10" versicherung bank 3000M)
  ;; legal / advisory, 20% Vorsteuer
  (multi!   conn #inst "2026-06-15" [[beratung 5000M] [vorsteuer 1000M] [bank -6000M]])
  ;; customer settles the receivable
  (two-leg! conn #inst "2026-09-01" bank forderungen 120000M)
  ;; NEXT fiscal year — must never appear in an FY2026 statement
  (multi!   conn #inst "2027-02-01" [[forderungen 60000M] [erloese -50000M] [ust -10000M]])
  conn)

(defn- book [] (seed! (at/create-at-db)))

;; Hand-computed FY2026 (all amounts EUR, net of VAT):
;;   Umsatzerlöse (4000)                                       = 100000
;;   Materialaufwand (5000)                                    =  30000
;;   Personalaufwand (6000)                                    =  24000
;;   Sonstige Aufw. (miete 12000 + versich 3000 + berat 5000)  =  20000
;;   Betriebserfolg = Jahresüberschuss  100000 − 74000         =  26000
;;
;;   USt collected 20000 → LIABILITY, never revenue.
;;   Vorsteuer 2000 + 6000 + 1000 = 9000 → ASSET.
;;
;;   Bank   50000 − 12000 − 24000 − 12000 − 3000 − 6000 + 120000 = 113000
;;   Forderungen  120000 − 120000                                =      0
;;   Vorsteuer                                                   =   9000
;;   Maschinen (no depreciation account)                        =  10000
;;   Σ Aktiva                                                    = 132000
;;   Lieferanten 36000 + USt 20000  = Verbindlichkeiten         =  56000
;;   Stammkapital 50000 + Periodenergebnis 26000 = Eigenkapital =  76000
;;   132000 = 56000 + 76000  ✓

(def ^:private fy {:from #inst "2026-01-01" :through #inst "2026-12-31"})

(deftest income-statement-matches-hand-computed-book
  (let [p   (pnl/compute (book) fy)
        sub #(:amount (fs/section-subtotal p %))]
    (testing "sections"
      (is (== 100000M (sub "1")) "Umsatzerlöse, net of 20% USt")
      (is (== 30000M (sub "5")) "Materialaufwand")
      (is (== 24000M (sub "6")) "Personalaufwand")
      (is (== 20000M (sub "8")) "sonstige betriebliche Aufwendungen"))
    (testing "derived subtotals"
      (is (== 100000M (:amount (:at.pnl/betriebsleistung p))))
      (is (== 26000M (:amount (:at.pnl/betriebserfolg p))))
      (is (== 26000M (:amount (:at.pnl/jahresueberschuss p)))))
    (testing "revenue EXCLUDES the collected USt (a liability, not an Ertrag)"
      ;; the sale was 120000 gross; 20000 is USt on 3500, so Umsatzerlöse
      ;; must read the 100000 net — never 120000
      (is (== 100000M (:amount (fs/line-value p "1" "1.1")))))
    (testing "every amount is tagged EUR (F5)"
      (is (= #{:EUR} (into #{} (map (comp :commodity (partial fs/section-subtotal p)))
                           ["1" "5" "6" "8"])))
      (is (= :EUR (:commodity (:at.pnl/jahresueberschuss p)))))))

(deftest balance-sheet-matches-and-balances
  (let [b (bs/compute (book) {:through #inst "2026-12-31"})]
    (is (== 132000M (:amount (:at.bs/summe-aktiva b))))
    (is (== 76000M (:amount (:at.bs/eigenkapital b))))
    (is (== 56000M (:amount (:at.bs/verbindlichkeiten b))))
    (testing "the accounting equation holds"
      (is (== 0M (:amount (:at.bs/difference b))))
      (is (:at.bs/balanced? b)))
    (testing "Vorsteuer is a current-asset receivable, not netted into USt"
      (is (== 9000M (:amount (fs/line-value b "B" "B.II.2")))))
    (testing "USt payable sits under Verbindlichkeiten (revenue excluded VAT)"
      (is (== 20000M (:amount (fs/line-value b "D" "D.2")))))
    (testing "the current-period result is carried in Eigenkapital"
      ;; C.V.a revenue 100000, C.V.b negated expenses -74000 → 26000
      (is (== 100000M (:amount (fs/line-value b "C" "C.V.a"))))
      (is (== -74000M (:amount (fs/line-value b "C" "C.V.b")))))
    (testing "commodity is EUR (F5)"
      (is (= :EUR (:commodity (:at.bs/summe-aktiva b)))))))

(deftest window-bound-excludes-the-next-fiscal-year
  (let [conn (book)]
    (testing ":through includes FY2026 and excludes the FY2027 sale"
      (is (== 26000M (:amount (:at.pnl/jahresueberschuss (pnl/compute conn fy))))))
    (testing "the explicit exclusive form agrees"
      (is (== 26000M (:amount (:at.pnl/jahresueberschuss
                               (pnl/compute conn {:from #inst "2026-01-01"
                                                  :to #inst "2027-01-01"}))))))))

(deftest check-reports-the-accounting-equation
  (let [r (bs/check (book) {:through #inst "2026-12-31"})]
    (is (:at.bs/balanced? r))
    (is (== 0M (:amount (:at.bs/difference r))))
    (is (= #{:at.bs/summe-aktiva :at.bs/eigenkapital :at.bs/verbindlichkeiten
             :at.bs/difference :at.bs/balanced?}
           (set (keys r))))))
