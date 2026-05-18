(ns kontor.l10n-in.chart-test
  "Tests for kontor.l10n-in.chart — Schedule III + Ind AS aligned
   starter chart of accounts.

   Covers:
     - install! is idempotent (re-running the loader replaces values
       without duplication)
     - INR commodity is created
     - All 5 Schedule III classes (1xxxxx Assets, 2xxxxx Equity,
       3xxxxx Liabilities, 4xxxxx Revenue, 5xxxxx Expenses) have at
       least one account
     - GST-specific accounts (Output CGST/SGST/IGST/UTGST/Cess + the
       matching Input ITC and RCM pairs) are all present
     - TDS pay/receivable accounts are present
     - Tags materialise as :account-tag entities with country-code IN"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-in.chart :as chart]))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (chart/install! conn)
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

;; ============================================================================
;; INR commodity
;; ============================================================================

(deftest inr-commodity-installed
  (testing "Installing the chart creates the INR commodity."
    (let [conn (bootstrap)
          db (d/db conn)
          inr (d/entity db [:commodity/symbol "INR"])]
      (is (some? inr))
      (is (= "Indian Rupee" (:commodity/name inr)))
      (is (= 2 (:commodity/precision inr)))
      (is (= "INR" (:commodity/iso-4217 inr))))))

;; ============================================================================
;; All five Schedule III classes covered
;; ============================================================================

(deftest schedule-iii-classes-covered
  (testing "Every Schedule III class (Assets / Equity / Liabilities /
            Revenue / Expenses) has at least one account."
    (let [conn (bootstrap)
          db (d/db conn)
          types (set (d/q '[:find [?t ...] :where [_ :account/type ?t]] db))]
      (is (contains? types :asset))
      (is (contains? types :equity))
      (is (contains? types :liability))
      (is (contains? types :income))
      (is (contains? types :expense)))))

(deftest account-count-is-reasonable
  (testing "Starter chart loads at least 60 accounts (Schedule III +
            ITC + RCM + TDS coverage)."
    (let [conn (bootstrap)
          db (d/db conn)
          n (count (d/q '[:find [?a ...] :where [?a :account/code _]] db))]
      (is (>= n 60) (str "loaded " n " accounts")))))

;; ============================================================================
;; GST-specific accounts
;; ============================================================================

(deftest output-gst-accounts-present
  (testing "All five output-GST liability accounts are present and
            tagged for output-side report aggregation."
    (let [conn (bootstrap)
          db (d/db conn)]
      (is (ace db chart/output-cgst-code)  "Output CGST")
      (is (ace db chart/output-sgst-code)  "Output SGST")
      (is (ace db chart/output-igst-code)  "Output IGST")
      (is (ace db chart/output-utgst-code) "Output UTGST")
      (is (ace db chart/output-cess-code)  "Output Cess"))))

(deftest input-itc-accounts-present
  (testing "All five Input Tax Credit asset accounts are present."
    (let [conn (bootstrap)
          db (d/db conn)]
      (is (ace db chart/input-cgst-code)  "Input CGST ITC")
      (is (ace db chart/input-sgst-code)  "Input SGST ITC")
      (is (ace db chart/input-igst-code)  "Input IGST ITC")
      (is (ace db chart/input-utgst-code) "Input UTGST ITC")
      (is (ace db chart/input-cess-code)  "Input Cess ITC"))))

(deftest rcm-account-pairs-present
  (testing "Reverse Charge Mechanism (RCM) accounts: payable +
            matching ITC for the buyer-as-payer side."
    (let [conn (bootstrap)
          db (d/db conn)]
      ;; Payable side
      (is (ace db chart/rcm-cgst-payable-code) "RCM CGST payable")
      (is (ace db chart/rcm-sgst-payable-code) "RCM SGST payable")
      (is (ace db chart/rcm-igst-payable-code) "RCM IGST payable")
      ;; ITC side
      (is (ace db chart/rcm-cgst-itc-code)     "RCM CGST ITC")
      (is (ace db chart/rcm-sgst-itc-code)     "RCM SGST ITC")
      (is (ace db chart/rcm-igst-itc-code)     "RCM IGST ITC"))))

(deftest tds-accounts-present
  (testing "TDS (Tax Deducted at Source) account family — sections
            194C / 194J / 194I — on both payable + receivable sides."
    (let [conn (bootstrap)
          db (d/db conn)]
      ;; Payable side (buyer withholds, remits to govt)
      (is (ace db "333100") "TDS Payable 194C")
      (is (ace db "333200") "TDS Payable 194J")
      (is (ace db "333300") "TDS Payable 194I")
      ;; Receivable side (supplier recovers against own income tax)
      (is (ace db "132100") "TDS Receivable 194C")
      (is (ace db "132200") "TDS Receivable 194J")
      (is (ace db "132300") "TDS Receivable 194I"))))

(deftest output-gst-accounts-are-liabilities
  (testing "Output GST accounts must be :liability so the kernel
            report engine surfaces them as 'output tax'."
    (let [conn (bootstrap)
          db (d/db conn)]
      (doseq [code [chart/output-cgst-code chart/output-sgst-code
                    chart/output-igst-code chart/output-utgst-code
                    chart/output-cess-code]]
        (let [a (d/entity db (ace db code))]
          (is (= :liability (:account/type a)) (str code " is :liability")))))))

(deftest input-itc-accounts-are-assets
  (testing "Input ITC accounts must be :asset."
    (let [conn (bootstrap)
          db (d/db conn)]
      (doseq [code [chart/input-cgst-code chart/input-sgst-code
                    chart/input-igst-code chart/input-utgst-code
                    chart/input-cess-code]]
        (let [a (d/entity db (ace db code))]
          (is (= :asset (:account/type a)) (str code " is :asset")))))))

;; ============================================================================
;; Retained earnings (closing target)
;; ============================================================================

(deftest retained-earnings-account-present
  (testing "Reserves and Surplus — Retained Earnings (220900) is the
            target the fiscal-year-close module rolls P&L into."
    (let [conn (bootstrap)
          db (d/db conn)]
      (is (ace db chart/retained-earnings-code))
      (is (= :equity
             (:account/type (d/entity db (ace db chart/retained-earnings-code))))))))

;; ============================================================================
;; Reconcilable flags
;; ============================================================================

(deftest reconcilable-flag-applied
  (testing "AR / AP / Bank accounts are :account/reconcilable true."
    (let [conn (bootstrap)
          db (d/db conn)
          ar (d/entity db (ace db chart/ar-code))
          ap (d/entity db (ace db chart/ap-code))
          bank (d/entity db (ace db chart/bank-code))]
      (is (true? (:account/reconcilable ar)))
      (is (true? (:account/reconcilable ap)))
      (is (true? (:account/reconcilable bank))))))

;; ============================================================================
;; Tags materialise as :account-tag entities under country-code IN
;; ============================================================================

(deftest tags-materialise-with-country-code
  (testing "Every distinct tag in the EDN becomes an :account-tag entity
            tagged with :account-tag/country-code IN."
    (let [conn (bootstrap)
          db (d/db conn)
          in-tags (d/q '[:find [?n ...]
                         :where
                         [?t :account-tag/country-code "IN"]
                         [?t :account-tag/name ?n]]
                       db)]
      (is (seq in-tags))
      ;; The output-CGST tag is one we expect to see.
      (is (some #(= "in-gstr3b-output-cgst" %) in-tags))
      ;; And the export sales tag.
      (is (some #(= "in-gstr1-exports" %) in-tags)))))

;; ============================================================================
;; Idempotency
;; ============================================================================

(deftest install-is-idempotent
  (testing "Calling install! twice yields the same number of accounts."
    (let [conn (core/create-test-db)
          _ (chart/install! conn)
          n1 (count (d/q '[:find [?a ...] :where [?a :account/code _]]
                         (d/db conn)))
          _ (chart/install! conn)
          n2 (count (d/q '[:find [?a ...] :where [?a :account/code _]]
                         (d/db conn)))]
      (is (= n1 n2)
          (str "Re-install must not duplicate accounts; got " n1 " then " n2)))))

;; ============================================================================
;; account-by-code helper
;; ============================================================================

(deftest account-by-code-lookup
  (testing "account-by-code returns the same eid as a direct datalog
            lookup, and nil for unknown codes."
    (let [conn (bootstrap)
          db (d/db conn)]
      (is (= (ace db chart/ar-code)
             (chart/account-by-code db chart/ar-code)))
      (is (nil? (chart/account-by-code db "999999"))))))
