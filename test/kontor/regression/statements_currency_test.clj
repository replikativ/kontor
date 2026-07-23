(ns kontor.regression.statements-currency-test
  "Regression suite — financial statements (income statement + balance
   sheet) and CURRENCY correctness across jurisdictions.

   For each of US / DE / CA / JP / AU we book a small but structurally
   complete year via `kontor.book/entry!` against the jurisdiction's own
   preset chart + commodity, then compute the income statement and the
   balance sheet and assert:

     (a) the arithmetic — net income, and assets = liabilities + equity
         (the book balances); and
     (b) the COMMODITY of every returned Money equals the jurisdiction's
         own currency (USD / EUR / CAD / JPY / AUD).

   US and DE ship statement definitions (`l10n-us.pnl` / `.bs`,
   `l10n-de.pnl` / `.bs`) that defend the commodity — US stamps :USD on
   every line, DE runs a EUR book so the engine's :EUR default happens to
   be correct. Those two pass outright.

   CA / JP / AU ship NO statement definitions (F4), so this suite drives
   the generic `kontor.reporting.financial-statements/compute-statement`
   with a hand-built definition per chart. The arithmetic is correct, and
   every returned Money is now tagged the book's real currency — F5 is
   FIXED: the report engine derives the statement commodity from the
   postings it sums (kontor.reporting.report/resolve-commodity-symbol),
   instead of the old :EUR default. The commodity deftests (ca/jp/au)
   assert exactly that and pass.

   This was the flagship F5 reproduction across nations; it now guards the fix.

   Expected figures are hand-computed in the comment block above each
   jurisdiction's seed — a golden-value capture would pass just as
   happily if a definition double-counted, so every number is arithmetic
   done by hand from the postings."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.book :as book]
            [kontor.reporting.financial-statements :as fs]
            ;; jurisdiction presets
            [kontor.l10n-us.preset :as us]
            [kontor.l10n-us.pnl :as us-pnl]
            [kontor.l10n-us.bs :as us-bs]
            [kontor.l10n-de.preset :as de]
            [kontor.l10n-de.pnl :as de-pnl]
            [kontor.l10n-de.bs :as de-bs]
            [kontor.l10n-ca.preset :as ca]
            [kontor.l10n-jp.preset :as jp]
            [kontor.l10n-au.preset :as au]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- post!
  "Two-leg entry via the verb facade, in the given commodity."
  [conn commodity date debit-path credit-path amount]
  (book/entry! conn {:debit-account  [:kontor.account/path debit-path]
                     :credit-account [:kontor.account/path credit-path]
                     :amount         amount
                     :commodity      [:kontor.commodity/symbol commodity]
                     :journal        [:kontor.journal/code "GJ"]
                     :effective-date date}))

(defn- amt [m] (:amount m))
(defn- cur [m] (:commodity m))

;; Full calendar-year 2026 window. :to is EXCLUSIVE, :through inclusive —
;; so a Dec-31 entry survives and a next-year entry is excluded.
(def ^:private fy {:from #inst "2026-01-01" :through #inst "2026-12-31"})
(def ^:private asof {:through #inst "2026-12-31"})

;; ===========================================================================
;; US — ships pnl/bs, stamps :USD. Both arithmetic AND commodity pass.
;; ===========================================================================
;;
;; USD book:
;;   cap   Bank <- Owner                 100000
;;   inv   Inventory <- Payable           20000   (liability)
;;   sale  Receivable <- Sales            60000
;;   cogs  COGS:Materials <- Inventory    15000
;;   rent  Rent <- Bank                   12000
;;   coll  Bank <- Receivable             40000
;;
;;   Revenue                              60000
;;   COGS                                 15000  -> gross 45000
;;   Operating exp (rent)                 12000  -> net income 33000
;;
;;   Cash      100000 - 12000 + 40000   = 128000
;;   AR        60000 - 40000            =  20000
;;   Inventory 20000 - 15000            =   5000
;;   Assets                             = 153000
;;   Liab (payable)                     =  20000
;;   Equity    100000 + 33000 earned    = 133000   (20000 + 133000 = 153000)

(defn- seed-us! [conn]
  (post! conn "USD" #inst "2026-01-02" "Assets:Bank:Checking"   "Equity:Owner-Contributions" 100000M)
  (post! conn "USD" #inst "2026-01-10" "Assets:Inventory"       "Liabilities:Payable"         20000M)
  (post! conn "USD" #inst "2026-03-01" "Assets:Receivable"      "Income:Sales"                60000M)
  (post! conn "USD" #inst "2026-04-10" "Expenses:COGS:Materials" "Assets:Inventory"           15000M)
  (post! conn "USD" #inst "2026-05-01" "Expenses:Rent"          "Assets:Bank:Checking"        12000M)
  (post! conn "USD" #inst "2026-09-01" "Assets:Bank:Checking"   "Assets:Receivable"           40000M)
  conn)

(deftest us-income-statement-and-balance-sheet
  (let [conn (seed-us! (us/create-us-db))
        p    (us-pnl/compute conn fy)
        b    (us-bs/compute conn asof)]
    (testing "income statement arithmetic"
      (is (== 60000M (amt (fs/section-subtotal p "1"))) "revenue")
      (is (== 15000M (amt (fs/section-subtotal p "2"))) "COGS")
      (is (== 12000M (amt (fs/section-subtotal p "3"))) "operating expenses")
      (is (== 45000M (amt (:us.pnl/gross-profit p))))
      (is (== 33000M (amt (:us.pnl/net-income p)))))
    (testing "balance sheet balances"
      (is (== 153000M (amt (:us.bs/total-assets b))))
      (is (== 20000M  (amt (:us.bs/total-liabilities b))))
      (is (== 133000M (amt (:us.bs/total-equity b))))
      (is (:us.bs/balanced? b))
      (is (== 0M (amt (:us.bs/difference b)))))
    (testing "every returned Money is tagged :USD (the shipped defs stamp it)"
      (is (= :USD (cur (:us.pnl/net-income p))))
      (is (= #{:USD}
             (into #{} (map (comp cur (partial fs/section-subtotal p)))
                   ["1" "2" "3" "4" "5"])))
      (is (= :USD (cur (:us.bs/total-assets b))))
      (is (= :USD (cur (:us.bs/total-equity b)))))))

;; ===========================================================================
;; DE — ships GuV/Bilanz. EUR book, so the engine's :EUR default is correct.
;; ===========================================================================
;;
;; EUR book (SKR04):
;;   cap    Bank(1200) <- Privateinlagen(2100)      50000
;;   sale   Forderungen(1400) <- Erlöse-19%(4400)   40000
;;   wareneinkauf 5400 <- Lieferanten(3300)          15000  (liability)
;;   miete  6300 <- Bank(1200)                        9000
;;   löhne  6020 <- Bank(1200)                        12000
;;
;;   Umsatzerlöse (sec 1)                             40000
;;   Materialaufwand (sec 3)                          15000
;;   Personalaufwand (sec 4)                          12000
;;   Sonstige betr. Aufw. / Raumkosten (sec 6)         9000
;;   Jahresüberschuss (pre-tax)                        4000
;;
;;   Aktiva:  Bank 50000 - 9000 - 12000 = 29000; Forderungen 40000 = 69000
;;   Passiva: Privateinlagen 50000; Jahresüberschuss 4000;
;;            Lieferanten 15000                        = 69000  (balanced)

(defn- seed-de! [conn]
  (post! conn "EUR" #inst "2026-02-01" "Umlaufvermögen:Bank"       "Eigenkapital:Privateinlagen" 50000M)
  (post! conn "EUR" #inst "2026-03-01" "Umlaufvermögen:Forderungen" "Erträge:Erlöse:19%"          40000M)
  (post! conn "EUR" #inst "2026-04-01" "Aufwendungen:Wareneinkauf"  "Verbindlichkeiten:Lieferanten" 15000M)
  (post! conn "EUR" #inst "2026-05-01" "Aufwendungen:Raum:Miete"    "Umlaufvermögen:Bank"          9000M)
  (post! conn "EUR" #inst "2026-06-01" "Aufwendungen:Personal:Gehälter" "Umlaufvermögen:Bank"      12000M)
  conn)

(deftest de-guv-and-bilanz
  (let [conn (seed-de! (de/create-de-db))
        p    (de-pnl/compute conn fy)
        chk  (de-bs/balance-check conn {:to #inst "2027-01-01"})]
    (testing "GuV arithmetic (HGB §275 Abs. 2)"
      (is (== 40000M (amt (fs/section-subtotal p "1"))) "Umsatzerlöse")
      (is (== 15000M (amt (fs/section-subtotal p "3"))) "Materialaufwand")
      (is (== 12000M (amt (fs/section-subtotal p "4"))) "Personalaufwand")
      (is (== 9000M  (amt (fs/section-subtotal p "6"))) "sonstige betr. Aufwendungen")
      (is (== 4000M  (amt (:de.pnl/jahresueberschuss p))) "Jahresüberschuss"))
    (testing "Bilanz balances (Aktiva = Passiva)"
      (is (== 69000M (amt (:statement/total (:bs/aktiva chk)))))
      (is (== 69000M (amt (:statement/total (:bs/passiva chk)))))
      (is (:bs/balanced? chk))
      (is (== 0M (amt (:bs/delta chk)))))
    (testing "every returned Money is tagged :EUR (correct for a EUR book)"
      (is (= :EUR (cur (:de.pnl/jahresueberschuss p))))
      (is (= :EUR (cur (:statement/total (:bs/aktiva chk)))))
      (is (= :EUR (cur (:statement/total (:bs/passiva chk))))))))

;; ===========================================================================
;; Generic (hand-built) statement definitions for the jurisdictions that
;; ship none — used by CA / JP / AU below. Prefix patterns ("4%") group a
;; whole leading-digit range; :sign defaults to :inflow so income/expense
;; and asset/liability accounts read as positive presented numbers.
;; ===========================================================================

(defn- generic-pnl-def [country rev-codes exp-codes]
  {:statement/name    "Income Statement"
   :statement/country country
   :statement/sections
   [{:section/code "REV" :section/label "Revenue"
     :section/lines [{:line/code "REV.1" :line/label "Revenue" :line/codes rev-codes}]}
    {:section/code "EXP" :section/label "Expenses"
     :section/lines [{:line/code "EXP.1" :line/label "Expenses" :line/codes exp-codes}]}]})

(def ^:private generic-pnl-signs {"REV" :+ "EXP" :-})

(defn- generic-bs-def [country asset-codes liab-codes equity-codes rev-codes exp-codes]
  {:statement/name    "Balance Sheet"
   :statement/country country
   :statement/sections
   [{:section/code "A" :section/label "Assets"
     :section/lines [{:line/code "A.1" :line/label "Assets" :line/codes asset-codes}]}
    {:section/code "L" :section/label "Liabilities"
     :section/lines [{:line/code "L.1" :line/label "Liabilities" :line/codes liab-codes}]}
    {:section/code "E" :section/label "Equity"
     :section/lines
     [{:line/code "E.1" :line/label "Contributed / retained" :line/codes equity-codes}
      ;; current-period result held outside equity until close (like US bs)
      {:line/code "E.2" :line/label "Current-period revenue" :line/codes rev-codes}
      {:line/code "E.3" :line/label "Current-period expenses"
       :line/codes exp-codes :line/negate true}]}]})

;; total = A - (L + E); zero for a balanced book
(def ^:private generic-bs-signs {"A" :+ "L" :- "E" :-})

(defn- bs-summary
  "Pull assets / liabilities / equity subtotals + balance flag from a
   computed generic balance sheet."
  [b]
  (let [assets (fs/section-subtotal b "A")
        liabs  (fs/section-subtotal b "L")
        equity (fs/section-subtotal b "E")]
    {:assets assets :liabs liabs :equity equity
     :total  (:statement/total b)}))

;; ===========================================================================
;; CA — no shipped pnl/bs. Generic engine. CAD book; commodity comes back
;;      :EUR (F5).
;; ===========================================================================
;;
;; CAD book:
;;   cap   Bank(1000) <- Owner(3000)          40000
;;   sale  Receivable(1100) <- Sales(4000)    30000
;;   cogs  COGS(5000) <- Payable(2000)        10000  (liability)
;;   rent  Rent(6100) <- Bank(1000)            6000
;;   office Office(6000) <- Bank(1000)         2000
;;
;;   Revenue 30000; Expenses 10000+6000+2000 = 18000; net income 12000
;;   Cash 40000 - 6000 - 2000 = 32000; AR 30000; Assets 62000
;;   Liab (payable) 10000; Equity 40000 + 12000 = 52000 (10000+52000=62000)

(defn- seed-ca! [conn]
  (post! conn "CAD" #inst "2026-01-05" "Assets:Bank"      "Equity:Owner"          40000M)
  (post! conn "CAD" #inst "2026-03-01" "Assets:Receivable" "Income:Sales"         30000M)
  (post! conn "CAD" #inst "2026-04-01" "Expenses:COGS"     "Liabilities:Payable"  10000M)
  (post! conn "CAD" #inst "2026-05-01" "Expenses:Rent"     "Assets:Bank"           6000M)
  (post! conn "CAD" #inst "2026-06-01" "Expenses:Office"   "Assets:Bank"           2000M)
  conn)

(def ^:private ca-pnl-def
  (generic-pnl-def "CA" ["4%"] ["5%" "6%"]))
(def ^:private ca-bs-def
  (generic-bs-def "CA" ["1%"] ["2%"] ["3%"] ["4%"] ["5%" "6%"]))

(deftest ca-statements-arithmetic
  (let [conn (seed-ca! (ca/create-ca-db))
        p    (fs/compute-statement conn ca-pnl-def (assoc fy :total-sign-map generic-pnl-signs))
        b    (fs/compute-statement conn ca-bs-def (assoc asof :total-sign-map generic-bs-signs))
        {:keys [assets liabs equity total]} (bs-summary b)]
    (testing "income statement"
      (is (== 30000M (amt (fs/section-subtotal p "REV"))))
      (is (== 18000M (amt (fs/section-subtotal p "EXP"))))
      (is (== 12000M (amt (:statement/total p))) "net income = revenue - expenses"))
    (testing "balance sheet balances"
      (is (== 62000M (amt assets)))
      (is (== 10000M (amt liabs)))
      (is (== 52000M (amt equity)) "owner 40000 + net income 12000")
      (is (== 0M (amt total)) "assets - (liab + equity) = 0"))))

(deftest ca-statement-commodity
  ;; PENDING(F5): the generic report engine defaults statement commodity
  ;; to :EUR. A CAD book returns correct amounts tagged :EUR. Every Money
  ;; below SHOULD be :CAD. Passes once the engine derives the default from
  ;; the book's functional / posting commodity.
  (let [conn (seed-ca! (ca/create-ca-db))
        p    (fs/compute-statement conn ca-pnl-def (assoc fy :total-sign-map generic-pnl-signs))
        b    (fs/compute-statement conn ca-bs-def (assoc asof :total-sign-map generic-bs-signs))]
    (is (= :CAD (cur (:statement/total p))) "net income currency")
    (is (= :CAD (cur (fs/section-subtotal p "REV"))))
    (is (= :CAD (cur (fs/section-subtotal b "A"))) "total assets currency")))

;; ===========================================================================
;; JP — no shipped pnl/bs. Generic engine. JPY book; commodity :EUR (F5).
;; ===========================================================================
;;
;; JPY book:
;;   cap   Cash:Bank(110200) <- Capital(310000)         5000000
;;   sale  Receivable(121000) <- Sales:10pct(411000)    3000000
;;   cogs  Purchases-10(511000) <- Payable(211000)      1000000  (liability)
;;   sal   Salaries(610000) <- Bank(110200)             1200000
;;   rent  Rent(620000) <- Bank(110200)                  600000
;;
;;   Revenue 3000000; Expenses 1000000+1200000+600000 = 2800000; NI 200000
;;   Cash 5000000 - 1200000 - 600000 = 3200000; AR 3000000; Assets 6200000
;;   Liab (payable) 1000000; Equity 5000000 + 200000 = 5200000
;;     (1000000 + 5200000 = 6200000)

(defn- seed-jp! [conn]
  (post! conn "JPY" #inst "2026-01-05" "Assets:Cash:Bank"           "Equity:Capital"        5000000M)
  (post! conn "JPY" #inst "2026-03-01" "Assets:Receivable"          "Income:Sales:10pct"    3000000M)
  (post! conn "JPY" #inst "2026-04-01" "Expense:COGS:Purchases-10"  "Liabilities:Payable"   1000000M)
  (post! conn "JPY" #inst "2026-05-01" "Expense:Salaries"           "Assets:Cash:Bank"      1200000M)
  (post! conn "JPY" #inst "2026-06-01" "Expense:Rent"               "Assets:Cash:Bank"       600000M)
  conn)

(def ^:private jp-pnl-def
  (generic-pnl-def "JP" ["4%"] ["5%" "6%" "720000"]))
(def ^:private jp-bs-def
  (generic-bs-def "JP" ["1%"] ["2%"] ["3%"] ["4%"] ["5%" "6%" "720000"]))

(deftest jp-statements-arithmetic
  (let [conn (seed-jp! (jp/create-jp-db))
        p    (fs/compute-statement conn jp-pnl-def (assoc fy :total-sign-map generic-pnl-signs))
        b    (fs/compute-statement conn jp-bs-def (assoc asof :total-sign-map generic-bs-signs))
        {:keys [assets liabs equity total]} (bs-summary b)]
    (testing "income statement"
      (is (== 3000000M (amt (fs/section-subtotal p "REV"))))
      (is (== 2800000M (amt (fs/section-subtotal p "EXP"))))
      (is (== 200000M  (amt (:statement/total p)))))
    (testing "balance sheet balances"
      (is (== 6200000M (amt assets)))
      (is (== 1000000M (amt liabs)))
      (is (== 5200000M (amt equity)))
      (is (== 0M (amt total))))))

(deftest jp-statement-commodity
  ;; PENDING(F5): JPY book, generic engine returns :EUR. Should be :JPY.
  (let [conn (seed-jp! (jp/create-jp-db))
        p    (fs/compute-statement conn jp-pnl-def (assoc fy :total-sign-map generic-pnl-signs))
        b    (fs/compute-statement conn jp-bs-def (assoc asof :total-sign-map generic-bs-signs))]
    (is (= :JPY (cur (:statement/total p))) "net income currency")
    (is (= :JPY (cur (fs/section-subtotal b "A"))) "total assets currency")))

;; ===========================================================================
;; AU — no shipped pnl/bs. Generic engine. AUD book; commodity :EUR (F5).
;; ===========================================================================
;;
;; AUD book:
;;   cap   Bank:Main(11100) <- OwnerContribution(31100)  80000
;;   sale  Receivable(11200) <- Sales:Taxable(41100)     50000
;;   cogs  COGS:Purchases(51100) <- Payable(21100)       20000  (liability)
;;   wage  Wages(61100) <- Bank:Main(11100)              15000
;;   rent  Rent(61500) <- Bank:Main(11100)                8000
;;
;;   Revenue 50000; Expenses 20000+15000+8000 = 43000; NI 7000
;;   Cash 80000 - 15000 - 8000 = 57000; AR 50000; Assets 107000
;;   Liab (payable) 20000; Equity 80000 + 7000 = 87000 (20000+87000=107000)

(defn- seed-au! [conn]
  (post! conn "AUD" #inst "2026-01-05" "Assets:Bank:Main"      "Equity:OwnerContribution" 80000M)
  (post! conn "AUD" #inst "2026-03-01" "Assets:Receivable"     "Income:Sales:Taxable"     50000M)
  (post! conn "AUD" #inst "2026-04-01" "Expense:COGS:Purchases" "Liabilities:Payable"     20000M)
  (post! conn "AUD" #inst "2026-05-01" "Expense:Wages"         "Assets:Bank:Main"         15000M)
  (post! conn "AUD" #inst "2026-06-01" "Expense:Rent"          "Assets:Bank:Main"          8000M)
  conn)

(def ^:private au-pnl-def
  (generic-pnl-def "AU" ["4%"] ["5%" "6%" "71100"]))
(def ^:private au-bs-def
  (generic-bs-def "AU" ["1%"] ["2%"] ["3%"] ["4%"] ["5%" "6%" "71100"]))

(deftest au-statements-arithmetic
  (let [conn (seed-au! (au/create-au-db))
        p    (fs/compute-statement conn au-pnl-def (assoc fy :total-sign-map generic-pnl-signs))
        b    (fs/compute-statement conn au-bs-def (assoc asof :total-sign-map generic-bs-signs))
        {:keys [assets liabs equity total]} (bs-summary b)]
    (testing "income statement"
      (is (== 50000M (amt (fs/section-subtotal p "REV"))))
      (is (== 43000M (amt (fs/section-subtotal p "EXP"))))
      (is (== 7000M  (amt (:statement/total p)))))
    (testing "balance sheet balances"
      (is (== 107000M (amt assets)))
      (is (== 20000M  (amt liabs)))
      (is (== 87000M  (amt equity)))
      (is (== 0M (amt total))))))

(deftest au-statement-commodity
  ;; PENDING(F5): AUD book, generic engine returns :EUR. Should be :AUD.
  (let [conn (seed-au! (au/create-au-db))
        p    (fs/compute-statement conn au-pnl-def (assoc fy :total-sign-map generic-pnl-signs))
        b    (fs/compute-statement conn au-bs-def (assoc asof :total-sign-map generic-bs-signs))]
    (is (= :AUD (cur (:statement/total p))) "net income currency")
    (is (= :AUD (cur (fs/section-subtotal b "A"))) "total assets currency")))

;; ===========================================================================
;; F4 — statement-definition parity. US + DE ship pnl/bs; CA / JP / AU
;;      ship none (a consumer must hand-build, as above). This documents
;;      the gap: the namespaces genuinely do not resolve.
;; ===========================================================================

(defn- ns-loadable? [sym]
  (try (require sym) true
       (catch java.io.FileNotFoundException _ false)
       (catch Exception _ false)))

(deftest l10n-pnl-bs-parity
  (testing "US and DE ship statement definitions"
    (is (ns-loadable? 'kontor.l10n-us.pnl))
    (is (ns-loadable? 'kontor.l10n-us.bs))
    (is (ns-loadable? 'kontor.l10n-de.pnl))
    (is (ns-loadable? 'kontor.l10n-de.bs)))
  (testing "F4: CA / JP / AU ship NO pnl/bs namespace (parity gap)"
    (is (not (ns-loadable? 'kontor.l10n-ca.pnl)) "CA should ship a pnl def")
    (is (not (ns-loadable? 'kontor.l10n-ca.bs))  "CA should ship a bs def")
    (is (not (ns-loadable? 'kontor.l10n-jp.pnl)) "JP should ship a pnl def")
    (is (not (ns-loadable? 'kontor.l10n-jp.bs))  "JP should ship a bs def")
    (is (not (ns-loadable? 'kontor.l10n-au.pnl)) "AU should ship a pnl def")
    (is (not (ns-loadable? 'kontor.l10n-au.bs))  "AU should ship a bs def")))
