(ns kontor.l10n-ca.returns-test
  "Canadian multi-province end-to-end:
     - BC sale (GST 5% + PST 7%, two output tax postings to two
       different authority accounts)
     - QC sale (GST 5% + QST 9.975% — both VAT-style, both
       recoverable, but to different authorities)
     - ON sale (HST 13% — single combined, simplest)
     Each authority's report computes its own net.

   Numbers verified by hand against CRA / Revenu Québec / BC Min of
   Finance return shapes."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-ca.chart :as chart]
            [kontor.l10n-ca.gst-hst :as gst]
            [kontor.l10n-ca.returns :as ret]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(def jan-1   #inst "2026-01-01T00:00:00Z")
(def jan-15  #inst "2026-01-15T00:00:00Z")
(def jan-20  #inst "2026-01-20T00:00:00Z")
(def jan-25  #inst "2026-01-25T00:00:00Z")
(def feb-1   #inst "2026-02-01T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:journal/code "INV"
                       :journal/name "Sales"
                       :journal/type :sale
                       :journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- post-on-hst-sale!
  "Ontario HST 13% sale (single combined output tax):
     1100 receivable    DEBIT  net + 13%
     4000 sales        CREDIT  net
     2310 GST/HST coll CREDIT  13%"
  [conn external-id date net]
  (let [db (d/db conn)
        cad (:db/id (d/entity db [:commodity/symbol "CAD"]))
        rec (ace db "1100") rev (ace db "4000")
        coll (ace db "2310")
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        net-bd (bigdec net)
        vat-bd (.setScale (.multiply net-bd (bigdec "0.13")) 2 java.math.RoundingMode/HALF_EVEN)
        gross  (.add net-bd vat-bd)
        tx (-> (posting/build-transaction
                {:transaction
                 {:transaction/external-id external-id
                  :transaction/journal jnl
                  :transaction/effective-date date
                  :transaction/narration external-id
                  :transaction/state :posted
                  :transaction/posted-at date}
                 :postings
                 [{:posting/account rec :posting/amount gross :posting/commodity cad}
                  {:posting/account rev :posting/amount (.negate net-bd) :posting/commodity cad}
                  {:posting/account coll :posting/amount (.negate vat-bd) :posting/commodity cad}]})
               (->> (mapv #(if (some? (:posting/account %))
                             (assoc % :posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

(defn- post-bc-sale!
  "BC sale: GST 5% + PST 7% — TWO output tax postings to TWO authorities.
     1100 receivable    DEBIT  net + 12% (GST + PST)
     4000 sales         CREDIT net
     2310 GST collected CREDIT 5% (CRA)
     2320 BC PST coll   CREDIT 7% (BC Finance)"
  [conn external-id date net]
  (let [db (d/db conn)
        cad (:db/id (d/entity db [:commodity/symbol "CAD"]))
        rec (ace db "1100") rev (ace db "4000")
        gst (ace db "2310") pst (ace db "2320")
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        net-bd (bigdec net)
        gst-bd (.setScale (.multiply net-bd (bigdec "0.05")) 2 java.math.RoundingMode/HALF_EVEN)
        pst-bd (.setScale (.multiply net-bd (bigdec "0.07")) 2 java.math.RoundingMode/HALF_EVEN)
        gross  (-> net-bd (.add gst-bd) (.add pst-bd))
        tx (-> (posting/build-transaction
                {:transaction
                 {:transaction/external-id external-id
                  :transaction/journal jnl
                  :transaction/effective-date date
                  :transaction/narration external-id
                  :transaction/state :posted
                  :transaction/posted-at date}
                 :postings
                 [{:posting/account rec :posting/amount gross :posting/commodity cad}
                  {:posting/account rev :posting/amount (.negate net-bd) :posting/commodity cad}
                  {:posting/account gst :posting/amount (.negate gst-bd) :posting/commodity cad}
                  {:posting/account pst :posting/amount (.negate pst-bd) :posting/commodity cad}]})
               (->> (mapv #(if (some? (:posting/account %))
                             (assoc % :posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

(defn- post-qc-sale!
  "QC sale: GST 5% + QST 9.975% — both VAT-style, both recoverable,
   different authorities (CRA + Revenu Québec).
     1100 receivable     DEBIT  net + GST + QST
     4000 sales          CREDIT net
     2310 GST collected  CREDIT 5%
     2330 QST collected  CREDIT 9.975%"
  [conn external-id date net]
  (let [db (d/db conn)
        cad (:db/id (d/entity db [:commodity/symbol "CAD"]))
        rec (ace db "1100") rev (ace db "4000")
        gst (ace db "2310") qst (ace db "2330")
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        net-bd (bigdec net)
        gst-bd (.setScale (.multiply net-bd (bigdec "0.05")) 2 java.math.RoundingMode/HALF_EVEN)
        qst-bd (.setScale (.multiply net-bd (bigdec "0.09975")) 2 java.math.RoundingMode/HALF_EVEN)
        gross  (-> net-bd (.add gst-bd) (.add qst-bd))
        tx (-> (posting/build-transaction
                {:transaction
                 {:transaction/external-id external-id
                  :transaction/journal jnl
                  :transaction/effective-date date
                  :transaction/narration external-id
                  :transaction/state :posted
                  :transaction/posted-at date}
                 :postings
                 [{:posting/account rec :posting/amount gross :posting/commodity cad}
                  {:posting/account rev :posting/amount (.negate net-bd) :posting/commodity cad}
                  {:posting/account gst :posting/amount (.negate gst-bd) :posting/commodity cad}
                  {:posting/account qst :posting/amount (.negate qst-bd) :posting/commodity cad}]})
               (->> (mapv #(if (some? (:posting/account %))
                             (assoc % :posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

(defn- post-bill-with-itc!
  "Vendor bill with GST 5% + ITC. Postings:
     6000 office       DEBIT  net
     1310 GST ITC      DEBIT  5%
     2000 payable     CREDIT  net + 5%"
  [conn external-id date net]
  (let [db (d/db conn)
        cad (:db/id (d/entity db [:commodity/symbol "CAD"]))
        exp (ace db "6000") itc (ace db "1310") pay (ace db "2000")
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        net-bd (bigdec net)
        gst-bd (.setScale (.multiply net-bd (bigdec "0.05")) 2 java.math.RoundingMode/HALF_EVEN)
        gross (.add net-bd gst-bd)
        tx (-> (posting/build-transaction
                {:transaction
                 {:transaction/external-id external-id
                  :transaction/journal jnl
                  :transaction/effective-date date
                  :transaction/narration external-id
                  :transaction/state :posted
                  :transaction/posted-at date}
                 :postings
                 [{:posting/account exp :posting/amount net-bd :posting/commodity cad}
                  {:posting/account itc :posting/amount gst-bd :posting/commodity cad}
                  {:posting/account pay :posting/amount (.negate gross) :posting/commodity cad}]})
               (->> (mapv #(if (some? (:posting/account %))
                             (assoc % :posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

;; ============================================================================
;; Smoke
;; ============================================================================

(deftest chart-installs
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        n (count (d/q '[:find [?a ...] :where [_ :account/code ?a]] db))]
    (is (>= n 18) (str "loaded " n " accounts"))
    (is (ace db "2310") "GST/HST collected")
    (is (ace db "2320") "BC PST collected")
    (is (ace db "2330") "QST collected (Revenu Québec)")
    (is (ace db "1310") "GST/HST ITC")))

(deftest tax-authority-attribute-installed
  (let [conn (core/create-test-db)
        all-idents (set (d/q '[:find [?i ...] :where [_ :db/ident ?i]] (d/db conn)))]
    (is (contains? all-idents :tax/authority)
        ":tax/authority must be present in the kernel schema for CA filing reports.")))

;; ============================================================================
;; CRA GST/HST report — combined federal + HST sales
;; ============================================================================

(deftest cra-gst-hst-on-sale
  (testing "Single ON sale — $1000 net @ 13% HST → line 103 = 130, ITC 0,
            net = 130, payment outcome."
    (let [conn (bootstrap)
          _ (post-on-hst-sale! conn "INV-1" jan-15 1000)
          r (gst/compute-return conn {:from jan-1 :to feb-1})]
      (is (= "GST34-2" (:return/form r)))
      (is (money/equiv? (money/money "130.00" :CAD) (:103 (:return/lines r))))
      (is (money/equiv? (money/money "130.00" :CAD) (:return/net-tax r)))
      (is (money/equiv? (money/money "130.00" :CAD) (:115 (:return/lines r)))
          "line 115 (payment enclosed) mirrors the positive balance")
      (is (= :payment (:return/outcome r))))))

(deftest cra-gst-hst-with-itc
  (testing "ON sale + vendor bill with GST 5% ITC.
              Sale 1000 @ 13% → 130 collected (line 103)
              Bill 500 @ 5%  → 25 ITC      (line 106 — and line 108 absent adj)
              Net = 130 - 25 = 105"
    (let [conn (bootstrap)
          _ (post-on-hst-sale! conn "INV-1" jan-15 1000)
          _ (post-bill-with-itc! conn "BILL-1" jan-25 500)
          r (gst/compute-return conn {:from jan-1 :to feb-1})]
      (is (money/equiv? (money/money "130.00" :CAD) (:103 (:return/lines r))))
      (is (money/equiv? (money/money "25.00"  :CAD) (:106 (:return/lines r))))
      (is (money/equiv? (money/money "25.00"  :CAD) (:108 (:return/lines r)))
          "108 = 106 + 107(0)")
      (is (money/equiv? (money/money "105.00" :CAD) (:return/net-tax r))))))

;; ============================================================================
;; BC stacking — GST + PST as TWO separate authority filings
;; ============================================================================

(deftest bc-stacking-produces-two-authority-filings
  (testing "BC sale: GST 5% (CRA) + PST 7% (BC Finance) on $1000 base.
            CRA report shows GST collected 50; BC PST report shows 70.
            They DO NOT mix — proves authority separation works via
            tag-name conventions."
    (let [conn (bootstrap)
          _ (post-bc-sale! conn "BC-INV-1" jan-15 1000)
          cra (gst/compute-return  conn {:from jan-1 :to feb-1})
          bc  (ret/compute-bc-pst conn {:from jan-1 :to feb-1})]
      (is (money/equiv? (money/money "50.00" :CAD) (:103 (:return/lines cra)))
          "CRA sees the GST 5% portion only")
      (is (money/equiv? (money/money "70.00" :CAD) (:bc-pst/payable bc))
          "BC Finance sees the PST 7% portion only")
      (is (money/equiv? (money/money "50.00" :CAD) (:return/net-tax cra))
          "CRA net = 50 (GST collected, no ITCs)"))))

;; ============================================================================
;; QC dual-VAT — GST + QST as TWO separate VAT-style filings
;; ============================================================================

(deftest qc-dual-vat-produces-two-authority-filings
  (testing "QC sale: GST 5% (CRA) + QST 9.975% (Revenu Québec).
            CRA: 50, QST: 99.75."
    (let [conn (bootstrap)
          _ (post-qc-sale! conn "QC-INV-1" jan-20 1000)
          cra (gst/compute-return conn {:from jan-1 :to feb-1})
          rq  (ret/compute-qst    conn {:from jan-1 :to feb-1})]
      (is (money/equiv? (money/money "50.00"  :CAD) (:103 (:return/lines cra))))
      (is (money/equiv? (money/money "99.75"  :CAD) (:203 (:qst/lines rq))))
      (is (money/equiv? (money/money "99.75"  :CAD) (:qst/net-tax rq))))))

;; ============================================================================
;; GST34-2 filing-completeness: all 15 lines, period detection, adjustments
;; ============================================================================

(deftest period-bounds-quarterly
  (testing "Q1 2026 → Jan 1 - Apr 1 (exclusive)"
    (let [{:keys [from to kind year quarter]}
          (gst/period-bounds {:year 2026 :quarter 1})]
      (is (= :quarterly kind))
      (is (= 2026 year))
      (is (= 1 quarter))
      (is (= jan-1 from))
      (is (= #inst "2026-04-01T00:00:00Z" to)))))

(deftest period-bounds-annual
  (testing "Annual 2026 → Jan 1 2026 - Jan 1 2027 (exclusive)"
    (let [{:keys [from to kind year]} (gst/period-bounds {:year 2026})]
      (is (= :annual kind))
      (is (= 2026 year))
      (is (= jan-1 from))
      (is (= #inst "2027-01-01T00:00:00Z" to)))))

(deftest all-gst34-2-lines-computed
  (testing "Single ON sale + bill — every GST34-2 line key present in :return/lines.
            Includes 113B (other debits) and 113C (final balance), which the
            original draft omitted; 205/405 are caller-supplied opts that
            default to zero but appear in the output for completeness."
    (let [conn (bootstrap)
          _ (post-on-hst-sale!  conn "INV-1"  jan-15 1000)
          _ (post-bill-with-itc! conn "BILL-1" jan-25 500)
          r (gst/compute-return conn {:from jan-1 :to feb-1})
          ks (set (keys (:return/lines r)))]
      (is (= #{:101 :103 :104 :105 :106 :107 :108 :109
               :110 :111 :112 :113A
               :205 :405 :113B :113C
               :114 :115}
             ks)))))

(deftest quarterly-period-routing
  (testing ":year + :quarter selects the correct date window."
    (let [conn (bootstrap)
          _ (post-on-hst-sale! conn "INV-Q1" jan-15 1000)         ;; in Q1
          _ (post-on-hst-sale! conn "INV-Q2" #inst "2026-04-15" 500) ;; in Q2
          q1 (gst/compute-return conn {:year 2026 :quarter 1})
          q2 (gst/compute-return conn {:year 2026 :quarter 2})]
      (is (money/equiv? (money/money "130.00" :CAD) (:103 (:return/lines q1))))
      (is (money/equiv? (money/money  "65.00" :CAD) (:103 (:return/lines q2)))))))

(deftest adjustment-and-instalment-lines
  (testing "Caller-supplied 104/107/110/111 flow through to 105/108/112/113A.
            Sale 1000 @ 13% = 130 collected. +5 adj (104). 25 ITC, +2 adj (107).
            105 = 130+5 = 135; 108 = 25+2 = 27; 109 = 135-27 = 108.
            Instalment 50 (110) + rebate 8 (111). 112 = 58; 113A = 108-58 = 50."
    (let [conn (bootstrap)
          _ (post-on-hst-sale!  conn "INV-1"  jan-15 1000)
          _ (post-bill-with-itc! conn "BILL-1" jan-25 500)
          r (gst/compute-return
             conn
             {:from jan-1 :to feb-1
              :line-104 (money/money  "5.00" :CAD)
              :line-107 (money/money  "2.00" :CAD)
              :line-110 (money/money "50.00" :CAD)
              :line-111 (money/money  "8.00" :CAD)})]
      (is (money/equiv? (money/money "135.00" :CAD) (:105 (:return/lines r))))
      (is (money/equiv? (money/money  "27.00" :CAD) (:108 (:return/lines r))))
      (is (money/equiv? (money/money "108.00" :CAD) (:109 (:return/lines r))))
      (is (money/equiv? (money/money  "58.00" :CAD) (:112 (:return/lines r))))
      (is (money/equiv? (money/money  "50.00" :CAD) (:113A (:return/lines r))))
      (is (money/equiv? (money/money  "50.00" :CAD) (:115 (:return/lines r)))))))

(deftest refund-outcome-when-itcs-exceed-collected
  (testing "Big purchase, no sales → refund. ITCs (line 106) push 113A negative;
            line 114 mirrors the absolute value; outcome :refund."
    (let [conn (bootstrap)
          _ (post-bill-with-itc! conn "BILL-1" jan-15 10000)
          r (gst/compute-return conn {:from jan-1 :to feb-1})]
      (is (= :refund (:return/outcome r)))
      (is (money/equiv? (money/money "-500.00" :CAD) (:return/balance r)))
      (is (money/equiv? (money/money "500.00"  :CAD) (:114 (:return/lines r))))
      (is (money/equiv? (money/money "0.00"    :CAD) (:115 (:return/lines r)))))))

(deftest nil-return-outcome
  (testing "No postings in period → all lines zero, outcome :nil-return."
    (let [conn (bootstrap)
          r (gst/compute-return conn {:from jan-1 :to feb-1})]
      (is (= :nil-return (:return/outcome r)))
      (is (money/equiv? (money/money "0.00" :CAD) (:return/net-tax r))))))

(deftest transcription-sheet-renders
  (testing "Transcription sheet contains form, period, every line, and outcome."
    (let [conn (bootstrap)
          _ (post-on-hst-sale!  conn "INV-1"  jan-15 1000)
          _ (post-bill-with-itc! conn "BILL-1" jan-25 500)
          r  (gst/compute-return conn {:year 2026 :quarter 1})
          sh (gst/transcription-sheet r)]
      (is (string? sh))
      (is (re-find #"GST34-2" sh))
      (is (re-find #"Q1 2026" sh))
      (is (re-find #"\b101\b" sh))
      (is (re-find #"\b113A\b" sh))
      (is (re-find #"payment" sh)))))
