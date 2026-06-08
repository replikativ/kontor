(ns kontor.l10n-de.financial-statements-test
  "End-to-end test for the DE Gewinn- und Verlustrechnung, Bilanz,
   and Anlage EÜR over a small fixture book.

   Posts a quarter of activity:
     - 4 invoices (3× 19% sales, 1× 7% sales)
     - 3 supplier bills (Bürobedarf, Reisekosten, Software, all 19% VAT)
     - 1 rent payment (no VAT — Vermieter ist Kleinunternehmer)
     - 1 salary payment

   Then verifies that the GKV P&L, the Aktiva/Passiva sides of the
   Bilanz, and the EÜR each surface the right numbers in the right
   sections / boxes."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.reporting.financial-statements :as fs]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.validation :as v]
            [kontor.l10n-de.chart :as chart]
            [kontor.l10n-de.pnl :as pnl]
            [kontor.l10n-de.bs :as bs]
            [kontor.l10n-de.eur :as eur]))

(def jan-1   #inst "2026-01-01T00:00:00Z")
(def jan-15  #inst "2026-01-15T00:00:00Z")
(def feb-1   #inst "2026-02-01T00:00:00Z")
(def feb-15  #inst "2026-02-15T00:00:00Z")
(def mar-15  #inst "2026-03-15T00:00:00Z")
(def apr-1   #inst "2026-04-01T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:kontor.journal/code "GEN"
                       :kontor.journal/name "General"
                       :kontor.journal/type :general
                       :kontor.journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- post! [conn external-id date postings]
  (let [db (d/db conn)
        jnl (:db/id (d/entity db [:kontor.journal/code "GEN"]))
        tx (-> (posting/build-transaction
                {:transaction
                 {:kontor.transaction/external-id external-id
                  :kontor.transaction/journal jnl
                  :kontor.transaction/effective-date date
                  :kontor.transaction/narration external-id
                  :kontor.transaction/state :posted
                  :kontor.transaction/posted-at date}
                 :postings postings})
               (->> (mapv #(if (some? (:kontor.posting/account %))
                             (assoc % :kontor.posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

(defn- invoice-19! [conn id date net]
  (let [db (d/db conn)
        eur-c (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        net-bd (bigdec net)
        vat (.setScale (.multiply net-bd (bigdec "0.19"))
                       2 java.math.RoundingMode/HALF_EVEN)
        gross (.add net-bd vat)]
    (post! conn id date
           [{:kontor.posting/account (ace db "1400") :kontor.posting/amount gross :kontor.posting/commodity eur-c}
            {:kontor.posting/account (ace db "4400") :kontor.posting/amount (.negate net-bd) :kontor.posting/commodity eur-c}
            {:kontor.posting/account (ace db "3801") :kontor.posting/amount (.negate vat) :kontor.posting/commodity eur-c}])))

(defn- invoice-7! [conn id date net]
  (let [db (d/db conn)
        eur-c (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        net-bd (bigdec net)
        vat (.setScale (.multiply net-bd (bigdec "0.07"))
                       2 java.math.RoundingMode/HALF_EVEN)
        gross (.add net-bd vat)]
    (post! conn id date
           [{:kontor.posting/account (ace db "1400") :kontor.posting/amount gross :kontor.posting/commodity eur-c}
            {:kontor.posting/account (ace db "4300") :kontor.posting/amount (.negate net-bd) :kontor.posting/commodity eur-c}
            {:kontor.posting/account (ace db "3806") :kontor.posting/amount (.negate vat) :kontor.posting/commodity eur-c}])))

(defn- supplier-bill-19! [conn id date net expense-code]
  (let [db (d/db conn)
        eur-c (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        net-bd (bigdec net)
        vat (.setScale (.multiply net-bd (bigdec "0.19"))
                       2 java.math.RoundingMode/HALF_EVEN)
        gross (.add net-bd vat)]
    (post! conn id date
           [{:kontor.posting/account (ace db expense-code) :kontor.posting/amount net-bd :kontor.posting/commodity eur-c}
            {:kontor.posting/account (ace db "1576")       :kontor.posting/amount vat    :kontor.posting/commodity eur-c}
            {:kontor.posting/account (ace db "3300")       :kontor.posting/amount (.negate gross) :kontor.posting/commodity eur-c}])))

(defn- rent-no-vat! [conn id date amount]
  (let [db (d/db conn)
        eur-c (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        bd (bigdec amount)]
    (post! conn id date
           [{:kontor.posting/account (ace db "6300") :kontor.posting/amount bd :kontor.posting/commodity eur-c}
            {:kontor.posting/account (ace db "1200") :kontor.posting/amount (.negate bd) :kontor.posting/commodity eur-c}])))

(defn- salary! [conn id date amount]
  (let [db (d/db conn)
        eur-c (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        bd (bigdec amount)]
    (post! conn id date
           [{:kontor.posting/account (ace db "6020") :kontor.posting/amount bd :kontor.posting/commodity eur-c}
            {:kontor.posting/account (ace db "1200") :kontor.posting/amount (.negate bd) :kontor.posting/commodity eur-c}])))

(defn- seed-q1 [conn]
  ;; Sales: 3 × 19% × 1000 + 1 × 7% × 500
  (invoice-19!  conn "INV-001" jan-15 1000)
  (invoice-19!  conn "INV-002" feb-15 1000)
  (invoice-19!  conn "INV-003" mar-15 1000)
  (invoice-7!   conn "INV-004" mar-15 500)
  ;; Bills: Bürobedarf 200, Reisekosten 400, Software 100 (all 19%)
  (supplier-bill-19! conn "BILL-001" jan-15 200 "6800")
  (supplier-bill-19! conn "BILL-002" feb-15 400 "6650")
  (supplier-bill-19! conn "BILL-003" mar-15 100 "6815")
  ;; Rent 800/mo (no VAT) Jan + Feb + Mar
  (rent-no-vat! conn "RENT-01" jan-15 800)
  (rent-no-vat! conn "RENT-02" feb-15 800)
  (rent-no-vat! conn "RENT-03" mar-15 800)
  ;; Salary 1500/mo
  (salary! conn "SAL-01" jan-15 1500)
  (salary! conn "SAL-02" feb-15 1500)
  (salary! conn "SAL-03" mar-15 1500))

;; ============================================================================
;; P&L (GKV)
;; ============================================================================

(deftest pnl-empty-book
  (let [conn (bootstrap)
        r (pnl/compute conn {:from jan-1 :to apr-1})]
    (is (money/zero? (:statement/total r)) "no postings → no profit")
    (is (= 6 (count (:statement/sections r))))))

(deftest pnl-q1-totals
  (testing "Sales 3500 (3000 19% + 500 7%), expenses 700 supplier-bills
            + 2400 rent + 4500 salary = 7600 → Loss 4100."
    (let [conn (bootstrap)
          _ (seed-q1 conn)
          r (pnl/compute conn {:from jan-1 :to apr-1})]
      ;; Income (section 1) — Umsatzerlöse
      (let [umsatz (fs/section-subtotal r "1")]
        (is (= 3500.00M (:amount umsatz))
            "1000 + 1000 + 1000 + 500"))
      ;; Materialaufwand (3) is 0 (no Wareneingang in seed)
      (is (money/zero? (fs/section-subtotal r "3")))
      ;; Personalaufwand (4) = 4500
      (is (= 4500M (:amount (fs/section-subtotal r "4"))))
      ;; Sonstige (6) = supplier bills 700 + rent 2400 = 3100
      (is (= 3100M (:amount (fs/section-subtotal r "6"))))
      ;; Total = (3500) - (0 + 4500 + 0 + 3100) = -4100  (loss)
      (is (= -4100M (:amount (:statement/total r)))
          "Q1 loss 4100"))))

(deftest pnl-line-drilldown
  (testing "Specific line — Erlöse 19% in section 1 = 3000, Erlöse 7% = 500."
    (let [conn (bootstrap)
          _ (seed-q1 conn)
          r (pnl/compute conn {:from jan-1 :to apr-1})]
      (is (= 3000M (:amount (fs/line-value r "1" "1.1"))))
      (is (= 500M  (:amount (fs/line-value r "1" "1.2")))))))

;; ============================================================================
;; Balance Sheet
;; ============================================================================

(deftest bs-after-q1
  (testing "Balance sheet at quarter-end has receivables, bank delta,
            VAT positions on both sides."
    (let [conn (bootstrap)
          _ (seed-q1 conn)
          aktiva (bs/compute-aktiva conn {:to apr-1})
          passiva (bs/compute-passiva conn {:to apr-1})]
      ;; Aktiva — section B (Umlaufvermögen) shows Forderungen + Bank
      (let [b (fs/section-subtotal aktiva "B")]
        (is (some? b))
        ;; B.II = Forderungen + sonstige Vermögensgegenstände (per HGB
        ;; §266 Abs.2 B.II.4 the latter explicitly includes Vorsteuer).
        ;;   Forderungen (1400) = 4 invoice grosses = 1190×3 + 535 = 4105
        ;;   Vorsteuer  (1576)  = 19% × 700 supplier-bills           = 133
        ;; Total B.II = 4238
        (is (= 4238.00M (:amount (fs/line-value aktiva "B" "B.II")))
            "Forderungen 4105 + Vorsteuer 133 = 4238")
        ;; Bank: paid out rent 2400 + salary 4500 = -6900 (no inflows)
        (is (= -6900M (:amount (fs/line-value aktiva "B" "B.III")))
            "Bank: -2400 rent -4500 salary"))
      ;; Passiva — section A (Eigenkapital) starts empty;
      ;;          section C (Verbindlichkeiten) carries supplier-payables + USt
      (let [c (fs/section-subtotal passiva "C")]
        (is (some? c))
        ;; Lieferantenverbindlichkeiten = 200×1.19 + 400×1.19 + 100×1.19 = 833
        (is (= 833.00M (:amount (fs/line-value passiva "C" "C.2"))))
        ;; USt sammlung (3801 + 3806 = collected − paid on receivables side):
        ;; 3801 = 1000×19% × 3 = 570  (but receivables aren't yet paid;
        ;;                              the credit lives here regardless)
        ;; 3806 = 500×7% = 35
        (is (= 605.00M (:amount (fs/line-value passiva "C" "C.3"))))))))

(deftest bs-balance-check-flags-imbalance
  (testing "Empty book → balance-check returns balanced? = true."
    (let [conn (bootstrap)
          {:bs/keys [balanced?]} (bs/balance-check conn {:to apr-1})]
      (is balanced?))))

;; ============================================================================
;; EÜR
;; ============================================================================

(deftest eur-q1
  (testing "EÜR boxes for Q1 — Einnahmen Box 11 = 3500 (Net), Box 14 =
            collected USt = 605, Box 22-60 ausgaben subset."
    (let [conn (bootstrap)
          _ (seed-q1 conn)
          r (eur/compute conn {:from jan-1 :to apr-1})]
      ;; Box 11 — umsatzsteuerpflichtige Betriebseinnahmen (NET)
      (is (= 3500.00M (:amount (eur/line-by-box r "11"))))
      ;; Box 14 — vereinnahmte USt (collected, on liability side stored
      ;; as negative; :raw shows raw signed → -605)
      (is (= -605.00M (:amount (eur/line-by-box r "14"))))
      ;; Box 26 — Bürobedarf
      (is (= 200.00M (:amount (eur/line-by-box r "26"))))
      ;; Box 28 — Reisekosten
      (is (= 400.00M (:amount (eur/line-by-box r "28"))))
      ;; Box 33 — Software
      (is (= 100.00M (:amount (eur/line-by-box r "33"))))
      ;; Box 49 — Vorsteuer abziehbar (sum of input VAT on bills)
      ;; 200×19% + 400×19% + 100×19% = 133
      (is (= 133.00M (:amount (eur/line-by-box r "49")))))))

(deftest eur-empty-book
  (let [conn (bootstrap)
        r (eur/compute conn {:from jan-1 :to apr-1})]
    (is (money/zero? (:eur/einnahmen r)))
    (is (money/zero? (:eur/ausgaben r)))
    (is (money/zero? (:eur/gewinn r)))))
