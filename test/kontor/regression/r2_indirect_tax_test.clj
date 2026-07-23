(ns kontor.regression.r2-indirect-tax-test
  "R2 regression — periodic INDIRECT-TAX RETURNS beyond the DE/CA/MX
   cohort already locked by kontor.regression.indirect-tax-test.

   Exercised AS A CONSUMER, netting output tax against input tax /
   ITC over a filing period and checking the net against a
   HAND-DERIVED figure taken from the statutory rate:

     - IN  GSTR-3B  (kontor.l10n-in.returns/generate-gstr-3b)
                    per-head net = output − ITC, 18% GST (CGST 9 +
                    SGST 9 intra; IGST 18 inter).
     - AU  BAS      (kontor.l10n-au.bas/compute-bas)
                    net GST = 1A − 1B, single 10% rate.
     - JP  JCT      (kontor.l10n-jp.consumption-tax/compute-return)
                    net = collected − deductible, 10% std + 8% reduced.
     - UK  VAT100   — GAP: l10n-uk ships NO VAT-return machinery and
                    NO chart / code binding (contrast the three above,
                    which are turnkey). Pinned ^:kaocha/pending. A
                    companion green test proves the KERNEL substrate
                    (kontor.tax.vat-return) can still net a UK VAT100
                    when the consumer hand-supplies codes — i.e. the
                    gap is the missing l10n binding, not the kernel.

   Every expected number is hand-derived from the published statutory
   rate; sources cited inline."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.l10n-au.bas :as au-bas]
            [kontor.l10n-au.invoice :as au-inv]
            [kontor.l10n-au.preset :as au]
            [kontor.l10n-in.invoice :as in-inv]
            [kontor.l10n-in.preset :as in]
            [kontor.l10n-in.returns :as in-ret]
            [kontor.l10n-jp.consumption-tax :as jct]
            [kontor.l10n-jp.preset :as jp]
            [kontor.l10n-uk.preset :as uk]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.tax.vat-return :as vat]
            [kontor.validation :as v]))

;; ============================================================================
;; Shared period window: full January 2026 (:to exclusive)
;; ============================================================================

(def jan-1  #inst "2026-01-01T00:00:00Z")
(def jan-15 #inst "2026-01-15T00:00:00Z")
(def jan-20 #inst "2026-01-20T00:00:00Z")
(def feb-1  #inst "2026-02-01T00:00:00Z")

(defn- ace
  "account entity-id by :kontor.account/code."
  [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- post-manual!
  "Post a balanced multi-leg journal entry directly (for the input /
   ITC side, which the sales invoice helpers do not cover). `legs` is
   a vector of [account-eid amount-bigdec]. Commodity resolved by
   symbol. Mirrors the module tests' posting/build-transaction form."
  [conn commodity-symbol journal-code external-id date legs]
  (let [db  (d/db conn)
        cid (:db/id (d/entity db [:kontor.commodity/symbol commodity-symbol]))
        jnl (:db/id (d/entity db [:kontor.journal/code journal-code]))
        tx  (posting/build-transaction
             {:transaction
              {:kontor.transaction/external-id  external-id
               :kontor.transaction/journal      jnl
               :kontor.transaction/effective-date date
               :kontor.transaction/narration    external-id
               :kontor.transaction/state        :posted
               :kontor.transaction/posted-at    date}
              :postings
              (mapv (fn [[acct amt]]
                      {:kontor.posting/account   acct
                       :kontor.posting/amount    amt
                       :kontor.posting/commodity cid
                       :kontor.posting/posted-at date})
                    legs)})]
    (v/transact-with-validation conn tx)))

;; ============================================================================
;; IN — GSTR-3B: net per head = output − ITC (18% GST)
;; ============================================================================
;;
;; Statutory basis (Indian GST, standard 18% slab):
;;   intra-state supply → CGST 9% + SGST 9%   (CGST Act 2017 + respective SGST Act)
;;   inter-state supply → IGST 18%            (IGST Act 2017 §5)
;;
;; Fixture (all January 2026):
;;   Sales:
;;     intra-state B2B ₹10,000 @18% → output CGST 900, output SGST 900
;;     inter-state B2B  ₹5,000 @18% → output IGST 900
;;   Purchases (ITC), posted to the tag-bound input-GST accounts:
;;     intra-state purchase ₹4,000 @18% → ITC CGST 360, ITC SGST 360
;;
;; Hand-derived GSTR-3B net per head (output − ITC; per-head model,
;; no cross-utilisation needed since ITC < output on every head):
;;     CGST : 900 − 360 = 540
;;     SGST : 900 − 360 = 540
;;     IGST : 900 −   0 = 900
;;     ───────────────────────
;;     net total       = 1,980

(defn- inr [s] (money/money (bigdec s) :INR))

(deftest in-gstr3b-net-of-itc
  (testing "GSTR-3B nets output GST against claimed ITC, per head"
    (let [conn (in/create-in-db)]
      (v/install-invariants! conn)
      ;; --- sales via the turnkey IN invoice helper ---
      (in-inv/post-in-invoice!
       conn {:kontor.invoice/external-id  "R2-IN-INTRA"
             :kontor.invoice/issue-date   jan-15
             :kontor.invoice/supplier-state "MH"
             :kontor.invoice/place-of-supply "MH"
             :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                     :kontor.invoice-line/unit-price 10000M
                                     :kontor.invoice-line/tax-rate 0.18M}]})
      (in-inv/post-in-invoice!
       conn {:kontor.invoice/external-id  "R2-IN-INTER"
             :kontor.invoice/issue-date   jan-20
             :kontor.invoice/supplier-state "MH"
             :kontor.invoice/place-of-supply "KA"
             :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                     :kontor.invoice-line/unit-price 5000M
                                     :kontor.invoice-line/tax-rate 0.18M}]})
      ;; --- purchase ITC posted to the tag-bound input-GST accounts ---
      ;; 131100 = Input CGST, 131200 = Input SGST, 122200 = Bank.
      (let [db (d/db conn)]
        (post-manual! conn "INR" "PJ" "R2-IN-PURCH" jan-20
                      [[(ace db "131100") 360M]     ; Dr input CGST
                       [(ace db "131200") 360M]     ; Dr input SGST
                       [(ace db "122200") -720M]])) ; Cr Bank
      (let [r (in-ret/generate-gstr-3b conn {:year 2026 :month 1})
            {:keys [cgst sgst igst utgst cess]} (:kontor.return/net-tax r)]
        (is (money/equiv? (inr "540.00") cgst) "net CGST = 900 output − 360 ITC")
        (is (money/equiv? (inr "540.00") sgst) "net SGST = 900 output − 360 ITC")
        (is (money/equiv? (inr "900.00") igst) "net IGST = 900 output − 0 ITC")
        (is (money/equiv? (money/zero :INR) utgst))
        (is (money/equiv? (money/zero :INR) cess))
        (is (money/equiv? (inr "1980.00") (:kontor.return/net-total r))
            "GSTR-3B net total = 540 + 540 + 900 = 1,980")))))

;; ============================================================================
;; AU — BAS: net GST = 1A − 1B (single 10% rate)
;; ============================================================================
;;
;; Statutory basis: A New Tax System (GST) Act 1999 — single 10% rate.
;;   1A = GST collected on sales
;;   1B = input tax credits on purchases
;;   Net = 1A − 1B  (>0 payable to ATO)
;;
;; Fixture (posted Feb 2026 = AU-FY2026 Q3, Jan–Mar):
;;   Sale A$20,000 net @10% → 1A = 2,000   (via turnkey AU invoice helper)
;;   Purchase ITC A$800     → 1B =   800   (posted to 11700 GSTReceivable,
;;                                           tag :au-bas-1b-itc — a 10%
;;                                           credit on A$8,000 of purchases)
;;
;; Hand-derived net = 2,000 − 800 = 1,200 payable.

(defn- aud [s] (money/money (bigdec s) :AUD))

(deftest au-bas-net-of-itc
  (testing "BAS nets 1A (GST on sales) against 1B (input tax credits)"
    (let [conn (au/create-au-db)]
      (v/install-invariants! conn)
      (au-inv/post-au-invoice!
       conn {:kontor.invoice/external-id "R2-AU-SALE"
             :kontor.invoice/issue-date  #inst "2026-02-15T00:00:00Z"
             :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                     :kontor.invoice-line/unit-price 20000M}]})
      ;; ITC: Dr GSTReceivable (11700, tag :au-bas-1b-itc), Cr Bank (11100).
      (let [db (d/db conn)]
        (post-manual! conn "AUD" "SJ" "R2-AU-ITC" #inst "2026-02-15T00:00:00Z"
                      [[(ace db "11700") 800M]
                       [(ace db "11100") -800M]]))
      (let [r (au-bas/compute-bas conn {:fy 2026 :quarter 3})]
        (is (money/equiv? (aud "2000.00") (get-in r [:bas/labels :1A]))
            "1A = 10% GST on A$20,000 sale")
        (is (money/equiv? (aud "800.00") (get-in r [:bas/labels :1B]))
            "1B = input tax credit")
        (is (money/equiv? (aud "1200.00") (:bas/net r))
            "BAS net = 1A 2,000 − 1B 800 = 1,200")
        (is (= :payment (:bas/outcome r)))))))

;; ============================================================================
;; JP — JCT: net = collected − deductible (10% std + 8% reduced)
;; ============================================================================
;;
;; Statutory basis (JCT, effective since Oct 2019):
;;   standard rate 10%, reduced rate 8% (food / newspapers).
;;   Net JCT = output (collected) − input (deductible).
;;
;; Fixture (January 2026):
;;   Sales:
;;     standard ¥1,000,000 @10% → output 100,000  (acct 411000 / 215100)
;;     reduced    ¥500,000 @8%  → output  40,000  (acct 412000 / 215200)
;;   Purchase:
;;     standard   ¥600,000 @10% → input   60,000  (acct 511000 / 180100)
;;
;; Hand-derived:
;;   collected  = 100,000 + 40,000 = 140,000
;;   deductible =                     60,000
;;   net        =                     80,000 payable.

(defn- jpy [s] (money/money (bigdec s) :JPY))

(deftest jp-jct-net-of-input
  (testing "JCT nets output tax against deductible input tax across both rates"
    (let [conn (jp/create-jp-db)]
      (v/install-invariants! conn)
      (let [db (d/db conn)
            ar   (ace db "121000")   ; AR
            ap   (ace db "211000")   ; AP
            s10  (ace db "411000")   ; Sales 10%
            s8   (ace db "412000")   ; Sales 8%
            out10 (ace db "215100")  ; Output JCT 10%
            out8  (ace db "215200")  ; Output JCT 8%
            pur10 (ace db "511000")  ; Purchases 10%
            in10  (ace db "180100")] ; Input JCT 10%
        ;; standard 10% sale ¥1,000,000 → JCT 100,000
        (post-manual! conn "JPY" "SJ" "R2-JP-S10" jan-15
                      [[ar 1100000M] [s10 -1000000M] [out10 -100000M]])
        ;; reduced 8% sale ¥500,000 → JCT 40,000
        (post-manual! conn "JPY" "SJ" "R2-JP-S8" jan-15
                      [[ar 540000M] [s8 -500000M] [out8 -40000M]])
        ;; standard 10% purchase ¥600,000 → input JCT 60,000
        (post-manual! conn "JPY" "PJ" "R2-JP-P10" jan-20
                      [[pur10 600000M] [in10 60000M] [ap -660000M]]))
      (let [r (jct/compute-return conn {:from jan-1 :to feb-1})]
        (is (money/equiv? (jpy "140000") (:kontor.return/jct-collected r))
            "collected = 100,000 (10%) + 40,000 (8%)")
        (is (money/equiv? (jpy "60000") (:kontor.return/jct-deductible r))
            "deductible = 60,000 input JCT")
        (is (money/equiv? (jpy "80000") (:kontor.return/jct-net r))
            "net JCT = 140,000 − 60,000 = 80,000")
        (is (= :payment (:kontor.return/outcome r)))))))

;; ============================================================================
;; UK — VAT100: GAP + substrate-capability proof
;; ============================================================================
;;
;; UK standard VAT rate = 20% (HMRC; unchanged since 4 Jan 2011).
;;
;; GAP: l10n-uk ships NO VAT-return machinery and NO chart-of-accounts
;; module. Its preset docstring says outright "UK does not yet ship a
;; chart module"; the src tree carries only CGT + investment-income.
;; Contrast IN (generate-gstr-3b), AU (compute-bas) and JP
;; (compute-return), each of which ships a turnkey periodic return over
;; a tag-bound chart. A UK consumer gets ZERO default code binding for
;; the VAT100 boxes 1–9.

(defn- uk-vat-ns-present? []
  (or (some? (find-ns 'kontor.l10n-uk.vat))
      (try (require 'kontor.l10n-uk.vat) true
           (catch Throwable _ false))))

(deftest ^:kaocha/pending uk-vat-return-machinery-missing
  ;; PENDING(NEW): l10n-uk ships no turnkey VAT-return (VAT100) namespace
  ;; and no chart/code binding, whereas IN/AU/JP each ship a turnkey
  ;; periodic indirect-tax return. There is no `kontor.l10n-uk.vat` (or
  ;; equivalent) a consumer can call — the whole l10n VAT surface is absent.
  (is (uk-vat-ns-present?)
      "expected a turnkey kontor.l10n-uk VAT100 return namespace — none ships"))

(deftest uk-vat100-nets-via-kernel-when-codes-supplied
  (testing "The KERNEL substrate (kontor.tax.vat-return) CAN net a UK
            VAT100 when the consumer hand-wires accounts + codes — so
            the gap above is the missing l10n binding, not the kernel."
    (let [conn (uk/create-uk-db)]   ; installs GBP + GJ/CR/CD/SJ/PJ, NO chart
      (v/install-invariants! conn)
      ;; Consumer must hand-create the VAT accounts + codes UK ships none of.
      (d/transact conn
                  [{:kontor.account/path "Assets:Bank"           :kontor.account/code "1200" :kontor.account/name "Bank" :kontor.account/type :asset}
                   {:kontor.account/path "Assets:VAT:Input"      :kontor.account/code "1400" :kontor.account/name "Input VAT (recoverable)" :kontor.account/type :asset}
                   {:kontor.account/path "Liabilities:VAT:Output" :kontor.account/code "2200" :kontor.account/name "Output VAT" :kontor.account/type :liability}
                   {:kontor.account/path "Income:Sales"          :kontor.account/code "4000" :kontor.account/name "Sales" :kontor.account/type :income}
                   {:kontor.account/path "Expenses:Purchases"    :kontor.account/code "5000" :kontor.account/name "Purchases" :kontor.account/type :expense}])
      (let [db (d/db conn)]
        ;; Sale £10,000 net + 20% VAT → Cr output VAT £2,000
        (post-manual! conn "GBP" "SJ" "R2-UK-SALE" jan-15
                      [[(ace db "1200") 12000M]      ; Dr Bank
                       [(ace db "4000") -10000M]     ; Cr Sales
                       [(ace db "2200") -2000M]])    ; Cr Output VAT
        ;; Purchase £4,000 net + 20% VAT → Dr input VAT £800
        (post-manual! conn "GBP" "PJ" "R2-UK-PURCH" jan-20
                      [[(ace db "5000") 4000M]       ; Dr Purchases
                       [(ace db "1400") 800M]        ; Dr Input VAT
                       [(ace db "1200") -4800M]]))   ; Cr Bank
      (let [r (vat/compute-vat-return
               conn {:from jan-1 :to feb-1
                     :output-vat-codes ["2200"]
                     :input-vat-codes  ["1400"]
                     :commodity :GBP})]
        ;; VAT100 box 1 = 2,000; box 4 = 800; box 5 (net) = 1,200 payable.
        (is (money/equiv? (money/money "2000.00" :GBP) (:output-vat r))
            "box 1: output VAT = 20% × £10,000")
        (is (money/equiv? (money/money "800.00" :GBP) (:input-vat r))
            "box 4: input VAT = 20% × £4,000")
        (is (money/equiv? (money/money "1200.00" :GBP) (:net-vat r))
            "box 5: net VAT = 2,000 − 800 = 1,200 payable to HMRC")))))
