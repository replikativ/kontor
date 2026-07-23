(ns kontor.regression.indirect-tax-test
  "Regression suite — INDIRECT TAX RETURNS (VAT / GST / HST / IVA).

   Locks in the periodic net-remittance math across three jurisdictions,
   each exercising a different rung of the substrate:

     - DE UStVA — the KERNEL `kontor.tax.vat-return/compute-vat-return`,
       driven with the SKR04 output codes (\"3801\" 19% + \"3806\" 7%)
       and input code (\"1576\" Vorsteuer). net = output − input.
     - CA GST/HST — the TURNKEY `kontor.l10n-ca.gst-hst/compute-return`
       (CRA form GST34-2): lines 101 / 105 / 106 / 109 + net-tax.
     - MX DPI — the TURNKEY `kontor.l10n-mx.returns/generate-dpi-return`
       (monthly Declaración de Pago de IVA, cash-basis cobrado/pagado).

   Expected figures are hand-computed from statutory rates and
   cross-checked against the modules' own authority-sourced tests
   (l10n-de/ustva_test, l10n-ca/returns_test, l10n-mx/returns_test).

   Known-issue coverage: F8 (compute-vat-return ships no per-jurisdiction
   default VAT codes — CA is turnkey, DE is not) is pinned by a
   ^:kaocha/pending deftest."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.l10n-ca.gst-hst :as gst]
            [kontor.l10n-ca.preset :as ca]
            [kontor.l10n-de.preset :as de]
            [kontor.l10n-de.ustva :as ustva]
            [kontor.l10n-mx.invoice :as mxinv]
            [kontor.l10n-mx.preset :as mx]
            [kontor.l10n-mx.returns :as mxret]
            [kontor.money :as money]
            [kontor.tax.vat-return :as vat]))

;; ============================================================================
;; Window: full January 2026, :to exclusive (Feb 1)
;; ============================================================================

(def jan-1  #inst "2026-01-01T00:00:00Z")
(def jan-10 #inst "2026-01-10T00:00:00Z")
(def jan-15 #inst "2026-01-15T00:00:00Z")
(def jan-20 #inst "2026-01-20T00:00:00Z")
(def jan-25 #inst "2026-01-25T00:00:00Z")
(def feb-1  #inst "2026-02-01T00:00:00Z")
(def apr-15 #inst "2026-04-15T00:00:00Z")

(defn- eid
  "Resolve an account eid from its `:kontor.account/code`. `:kontor.account/code`
   is not globally `:db/unique` (charts can cohabit), so a lookup-ref is
   rejected by the invariant seed — resolve to an eid before posting."
  [conn code]
  (or (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]]
           (d/db conn) code)
      (throw (ex-info "account code not found" {:code code}))))

;; ============================================================================
;; DE — SKR04 sales/purchases with VAT, booked via the kontor.book facade
;; ============================================================================

(defn- de-sale-19!
  "SKR04 19% sale of `net` EUR: Dr 1400 gross / Cr 4400 net / Cr 3801 VAT."
  [conn ext net vat-amt date]
  (book/entry!
   conn
   {:journal-type   :sale
    :effective-date date
    :external-id    ext
    :commodity      :EUR
    :narration      ext
    :postings [{:account (eid conn "1400") :amount (+ net vat-amt)}
               {:account (eid conn "4400") :amount (- net)}
               {:account (eid conn "3801") :amount (- vat-amt)}]}))

(defn- de-sale-7!
  "SKR04 7% sale of `net` EUR: Dr 1400 gross / Cr 4300 net / Cr 3806 VAT."
  [conn ext net vat-amt date]
  (book/entry!
   conn
   {:journal-type   :sale
    :effective-date date
    :external-id    ext
    :commodity      :EUR
    :narration      ext
    :postings [{:account (eid conn "1400") :amount (+ net vat-amt)}
               {:account (eid conn "4300") :amount (- net)}
               {:account (eid conn "3806") :amount (- vat-amt)}]}))

(defn- de-purchase-19!
  "SKR04 19% purchase of `net` EUR: Dr 6800 net / Dr 1576 Vorsteuer / Cr 3300 gross."
  [conn ext net vat-amt date]
  (book/entry!
   conn
   {:journal-type   :purchase
    :effective-date date
    :external-id    ext
    :commodity      :EUR
    :narration      ext
    :postings [{:account (eid conn "6800") :amount net}
               {:account (eid conn "1576") :amount vat-amt}
               {:account (eid conn "3300") :amount (- (+ net vat-amt))}]}))

(def de-codes
  "SKR04 output/input VAT account codes a DE consumer must supply to the
   kernel compute-vat-return (there is no DE default — see F8)."
  {:output-vat-codes ["3801" "3806"]
   :input-vat-codes  ["1576"]
   :commodity        :EUR})

(deftest de-ustva-net-payable
  ;; Hand-computed (cross-check: l10n-de/ustva_test mixed-rates test):
  ;;   €1000 net @19% → 190 output ; €500 net @7% → 35 output
  ;;   €200 net @19% purchase → 38 input (Vorsteuer)
  ;;   output = 190 + 35 = 225 ; input = 38 ; net = 187 payable.
  (testing "DE VAT return nets output − input to the Zahllast"
    (let [conn (de/create-de-db)]
      (de-sale-19!     conn "INV-1"  1000M 190M jan-15)
      (de-sale-7!      conn "INV-2"   500M  35M jan-20)
      (de-purchase-19! conn "BILL-1"  200M  38M jan-25)
      (let [r (vat/compute-vat-return conn (merge de-codes {:from jan-1 :to feb-1}))]
        (is (money/equiv? (money/money "225.00" :EUR) (:output-vat r))
            "output VAT = 190 (19%) + 35 (7%)")
        (is (money/equiv? (money/money "38.00" :EUR) (:input-vat r))
            "input VAT (Vorsteuer) = 38")
        (is (money/equiv? (money/money "187.00" :EUR) (:net-vat r))
            "net = 225 − 38 = 187 payable")
        (is (money/positive? (:net-vat r))
            "positive net ⇒ payable to the Finanzamt")))))

(deftest de-ustva-net-refund
  ;; A capex-heavy period: input VAT exceeds output → refund (Vorsteuerüberhang).
  ;;   €500 net @19% sale → 95 output ; €2000 net @19% purchase → 380 input.
  ;;   net = 95 − 380 = −285 (refund from the Finanzamt).
  (testing "DE VAT return goes negative when Vorsteuer exceeds output VAT"
    (let [conn (de/create-de-db)]
      (de-sale-19!     conn "INV-1"   500M  95M jan-15)
      (de-purchase-19! conn "BILL-1" 2000M 380M jan-25)
      (let [r (vat/compute-vat-return conn (merge de-codes {:from jan-1 :to feb-1}))]
        (is (money/equiv? (money/money "95.00"  :EUR) (:output-vat r)))
        (is (money/equiv? (money/money "380.00" :EUR) (:input-vat r)))
        (is (money/equiv? (money/money "-285.00" :EUR) (:net-vat r))
            "net = 95 − 380 = −285 refund")
        (is (money/negative? (:net-vat r))
            "negative net ⇒ refund due")))))

(deftest de-ustva-window-excludes-out-of-period
  ;; The half-open window [jan-1, feb-1) must exclude a Dec sale and a
  ;; Feb-1 boundary sale — only the mid-January invoice contributes.
  (testing "Only postings whose valid-time falls inside the window count"
    (let [conn (de/create-de-db)]
      (de-sale-19! conn "EARLY"  999M 189.81M #inst "2025-12-20T00:00:00Z")
      (de-sale-19! conn "INSIDE" 1000M 190M jan-15)
      (de-sale-19! conn "LATE"   999M 189.81M feb-1) ; on the exclusive boundary
      (let [r (vat/compute-vat-return conn (merge de-codes {:from jan-1 :to feb-1}))]
        (is (money/equiv? (money/money "190.00" :EUR) (:output-vat r))
            "only the INSIDE invoice's 190 output VAT is counted")))))

(deftest de-vat-return-is-turnkey-and-kernel-refuses-codeless-F8
  ;; F8 fix (note 196). Two halves:
  ;;  1. DE has a TURNKEY VAT return — kontor.l10n-de.ustva/compute (UStVA) —
  ;;     tag-driven off the SKR04 :ust-* account tags, mirroring CA's
  ;;     gst-hst/compute-return. A DE consumer nets to 187 WITHOUT knowing any
  ;;     codes.
  ;;  2. The general kernel compute-vat-return no longer silently returns net 0
  ;;     when called with no codes (the dangerous "plausible zero"); it throws
  ;;     :vat-return/no-codes pointing at the l10n wrappers.
  (let [conn (de/create-de-db)]
    (de-sale-19!     conn "INV-1"  1000M 190M jan-15)
    (de-sale-7!      conn "INV-2"   500M  35M jan-20)
    (de-purchase-19! conn "BILL-1"  200M  38M jan-25)
    (testing "DE turnkey UStVA: zahllast = 190 + 35 − 38 = 187, no codes needed"
      (let [r (ustva/compute conn {:from jan-1 :to feb-1})]
        (is (money/equiv? (money/money "187.00" :EUR) (:ustva/zahllast r)))))
    (testing "the general kernel fn refuses a codeless call instead of returning 0"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"supply :output-vat-codes"
           (vat/compute-vat-return conn {:from jan-1 :to feb-1 :commodity :EUR}))))
    (testing "and still works when the DE consumer does pass the SKR04 codes"
      (let [r (vat/compute-vat-return conn {:from jan-1 :to feb-1 :commodity :EUR
                                            :output-vat-codes ["3801" "3806"]
                                            :input-vat-codes ["1576"]})]
        (is (money/equiv? (money/money "187.00" :EUR) (:net-vat r)))))))

;; ============================================================================
;; CA — GST34-2 turnkey return (CRA)
;; ============================================================================

(defn- ca-hst-sale!
  "Ontario 13% HST sale of `net` CAD: Dr 1100 gross / Cr 4000 net / Cr 2310 HST."
  [conn ext net vat-amt date]
  (book/entry!
   conn
   {:journal-type   :sale
    :effective-date date
    :external-id    ext
    :commodity      :CAD
    :narration      ext
    :postings [{:account (eid conn "1100") :amount (+ net vat-amt)}
               {:account (eid conn "4000") :amount (- net)}
               {:account (eid conn "2310") :amount (- vat-amt)}]}))

(defn- ca-bill-with-itc!
  "5% GST purchase of `net` CAD with a recoverable ITC:
   Dr 6000 net / Dr 1310 ITC / Cr 2000 gross."
  [conn ext net itc date]
  (book/entry!
   conn
   {:journal-type   :purchase
    :effective-date date
    :external-id    ext
    :commodity      :CAD
    :narration      ext
    :postings [{:account (eid conn "6000") :amount net}
               {:account (eid conn "1310") :amount itc}
               {:account (eid conn "2000") :amount (- (+ net itc))}]}))

(deftest ca-gst-hst-net-payable
  ;; Cross-check: l10n-ca/returns_test cra-gst-hst-with-itc.
  ;;   ON sale $1000 @13% → 130 collected (line 103, = 105 absent adj)
  ;;   Bill $500 @5%      → 25 ITC (line 106, = 108 absent adj)
  ;;   line 101 (sales) = 1000 ; line 109 net = 130 − 25 = 105 payable.
  (testing "GST34-2 lines 101/105/106/109 + net-tax on a sale + ITC bill"
    (let [conn (ca/create-ca-db)]
      (ca-hst-sale!      conn "INV-1"  1000M 130M jan-15)
      (ca-bill-with-itc! conn "BILL-1"  500M  25M jan-25)
      (let [r     (gst/compute-return conn {:from jan-1 :to feb-1})
            lines (:kontor.return/lines r)]
        (is (= "GST34-2" (:kontor.return/form r)))
        (is (money/equiv? (money/money "1000.00" :CAD) (:101 lines))
            "line 101 — sales and other revenue")
        (is (money/equiv? (money/money "130.00" :CAD) (:105 lines))
            "line 105 — total GST/HST collected (103 + 104(0))")
        (is (money/equiv? (money/money "25.00" :CAD) (:106 lines))
            "line 106 — input tax credits")
        (is (money/equiv? (money/money "105.00" :CAD) (:109 lines))
            "line 109 — net tax (105 − 108)")
        (is (money/equiv? (money/money "105.00" :CAD) (:kontor.return/net-tax r)))
        (is (= :payment (:kontor.return/outcome r)))
        (is (every? #(= :CAD (:commodity %))
                    [(:101 lines) (:105 lines) (:106 lines) (:109 lines)])
            "every GST34-2 line is correctly tagged :CAD (the return engine
             resolves the commodity, unlike the statement engine — cf. F5)")))))

(deftest ca-gst-hst-refund-outcome
  ;; Big capital purchase, no sales → ITCs exceed collected → refund.
  ;;   Bill $10000 @5% → 500 ITC ; line 109 net = −500 ; outcome :refund ;
  ;;   line 114 (refund claimed) = 500.
  (testing "GST34-2 refund when ITCs exceed GST/HST collected"
    (let [conn (ca/create-ca-db)]
      (ca-bill-with-itc! conn "BILL-1" 10000M 500M jan-15)
      (let [r (gst/compute-return conn {:from jan-1 :to feb-1})]
        (is (= :refund (:kontor.return/outcome r)))
        (is (money/equiv? (money/money "-500.00" :CAD) (:kontor.return/net-tax r)))
        (is (money/equiv? (money/money "500.00" :CAD)
                          (:114 (:kontor.return/lines r)))
            "line 114 mirrors the absolute refund")
        (is (money/equiv? (money/money "0.00" :CAD)
                          (:115 (:kontor.return/lines r)))
            "line 115 (payment enclosed) is zero on a refund")))))

(deftest ca-gst-hst-quarterly-window
  ;; :year + :quarter routes to the correct calendar-quarter window.
  ;; A Q1 sale and a Q2 sale must land in separate returns.
  (testing "quarterly period routing keeps Q1 and Q2 separate"
    (let [conn (ca/create-ca-db)]
      (ca-hst-sale! conn "INV-Q1" 1000M 130M jan-15)
      (ca-hst-sale! conn "INV-Q2"  500M  65M apr-15)
      (let [q1 (gst/compute-return conn {:year 2026 :quarter 1})
            q2 (gst/compute-return conn {:year 2026 :quarter 2})]
        (is (money/equiv? (money/money "130.00" :CAD)
                          (:103 (:kontor.return/lines q1)))
            "Q1 sees only the January sale")
        (is (money/equiv? (money/money "65.00" :CAD)
                          (:103 (:kontor.return/lines q2)))
            "Q2 sees only the April sale")))))

;; ============================================================================
;; MX — monthly DPI (Declaración de Pago de IVA), cash-basis
;; ============================================================================

(defn- mx-purchase-16!
  "16% domestic purchase of `net` MXN with a PAID (pagado) input ITC:
   Dr 601.06.001 net / Dr 119.01.001 IVA / Cr 201.01.001 gross.
   Booking to 119.01 (pagado) makes the ITC recoverable in-period — the
   cash-basis leg the DPI reads."
  [conn ext net iva date]
  (book/entry!
   conn
   {:journal-type   :purchase
    :effective-date date
    :external-id    ext
    :commodity      :MXN
    :narration      ext
    :postings [{:account (eid conn "601.06.001") :amount net}
               {:account (eid conn "119.01.001") :amount iva}
               {:account (eid conn "201.01.001") :amount (- (+ net iva))}]}))

(deftest mx-dpi-net-with-itc
  ;; Cross-check: l10n-mx/returns_test (cobrado aggregation).
  ;;   Cash sales: $1000 @16% → 160 ; $500 @8% frontera → 40 ; $200 @0% → 0.
  ;;     ⇒ IVA cobrado total = 200 (the credit sale's IVA stays on 208.02,
  ;;       excluded by cash-basis).
  ;;   Purchase $5000 @16% pagado → 800 IVA acreditable.
  ;;   IVA net = cobrado − acreditable = 200 − 800 = −400 (saldo a favor).
  (testing "DPI nets cobrado output IVA against pagado input ITC"
    (let [conn (mx/create-mx-db)]
      ;; post-mx-invoice! posts to a journal coded "INV" (see l10n-mx invoice.clj);
      ;; the preset ships "SJ" — add the INV sales journal the invoice helper needs.
      (d/transact conn [{:kontor.journal/code "INV"
                         :kontor.journal/name "Ventas"
                         :kontor.journal/type :sale
                         :kontor.journal/active true}])
      ;; three cash sales + one credit sale (mirrors the module's own seed)
      (mxinv/post-mx-invoice!
       conn {:kontor.invoice/external-id "INV-CASH-16"
             :kontor.invoice/issue-date jan-10
             :kontor.invoice/cash-sale? true
             :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                     :kontor.invoice-line/unit-price 1000M}]})
      (mxinv/post-mx-invoice!
       conn {:kontor.invoice/external-id "INV-CASH-8"
             :kontor.invoice/issue-date jan-15
             :kontor.invoice/cash-sale? true
             :kontor.invoice/region :border-norte
             :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                     :kontor.invoice-line/unit-price 500M}]})
      (mxinv/post-mx-invoice!
       conn {:kontor.invoice/external-id "INV-CASH-0"
             :kontor.invoice/issue-date jan-20
             :kontor.invoice/cash-sale? true
             :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                     :kontor.invoice-line/unit-price 200M
                                     :kontor.invoice-line/tax-status :zero-rated}]})
      (mxinv/post-mx-invoice!
       conn {:kontor.invoice/external-id "INV-CREDIT-16"
             :kontor.invoice/issue-date jan-20
             :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                     :kontor.invoice-line/unit-price 3000M}]})
      (mx-purchase-16! conn "GASTO-1" 5000M 800M jan-25)
      (let [r     (mxret/generate-dpi-return conn {:year 2026 :month 1})
            lines (:kontor.return/lines r)]
        (is (= "DPI" (:kontor.return/form r)))
        (is (money/equiv? (money/money "200" :MXN) (:iva-cobrado-total lines))
            "IVA cobrado = 160 + 40 + 0 (credit-sale IVA on 208.02 excluded)")
        (is (money/equiv? (money/money "800" :MXN) (:iva-acreditable-total lines))
            "IVA acreditable pagado = 800")
        (is (money/equiv? (money/money "-600" :MXN) (:kontor.return/iva-net r))
            "IVA net = 200 − 800 = −600 (saldo a favor)")
        (is (money/negative? (:kontor.return/total-iva-payable r))
            "net favor ⇒ credit carried forward, nothing remitted")))))
