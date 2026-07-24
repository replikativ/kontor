(ns kontor.asset.jahresabschluss-test
  "ADR-056: Jahresabschluss extensions.

   Covers:
   - the :ledger filter on kontor.reporting.report / kontor.reporting.financial-statements
     (the HGB-vs-IFRS prerequisite — a nil-ledger posting counts as
     the primary book).
   - compute-cash-flow: window-delta statement + the reconciliation
     against the cash accounts.
   - compute-equity-changes: per-component opening/movements/closing
     roll-forward + the reconciliation check.
   - kontor.asset.report/asset-roll-forward: the Anlagengitter
     arithmetic (opening / additions / disposals / closing for cost
     and accumulated depreciation; NBV).
   - kontor.asset.report/pending-depreciation-issues: the
     :no-pending-depreciation pre-close hook."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.asset.asset :as asset]
            [kontor.asset.depreciation :as dep]
            [kontor.asset.report :as areport]
            [kontor.asset.runner :as runner]
            [kontor.asset.schema :as asset-schema]
            [kontor.core :as core]
            [kontor.reporting.financial-statements :as fs]
            [kontor.posting :as posting]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (asset-schema/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:kontor.partner/external-id "U-buyer" :kontor.partner/name "Asset Buyer"}
                 {:db/id "acct-machinery"
                  :kontor.account/code "0210" :kontor.account/name "Machinery"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "acct-accum"
                  :kontor.account/code "0299" :kontor.account/name "Accumulated Depreciation"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "acct-dep-expense"
                  :kontor.account/code "6220" :kontor.account/name "Depreciation Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "acct-bank"
                  :kontor.account/code "1800" :kontor.account/name "Bank"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "acct-revenue"
                  :kontor.account/code "8000" :kontor.account/name "Revenue"
                  :kontor.account/type :income :kontor.account/active true}
                 {:db/id "acct-equity"
                  :kontor.account/code "2900" :kontor.account/name "Subscribed Capital"
                  :kontor.account/type :equity :kontor.account/active true}
                 {:db/id "ledger-hgb"
                  :kontor.ledger/code "hgb" :kontor.ledger/name "Handelsbilanz"
                  :kontor.ledger/type :primary :kontor.ledger/framework :HGB
                  :kontor.ledger/active true}
                 {:db/id "ledger-ifrs"
                  :kontor.ledger/code "ifrs" :kontor.ledger/name "IFRS"
                  :kontor.ledger/type :secondary :kontor.ledger/framework :IFRS
                  :kontor.ledger/active true}
                 {:db/id "journal-gen"
                  :kontor.journal/code "GEN" :kontor.journal/name "General"
                  :kontor.journal/type :general}
                 {:db/id "class-machinery"
                  :kontor.asset-class/code "machinery"
                  :kontor.asset-class/name "Machinery & Equipment"
                  :kontor.asset-class/default-useful-life-months 120}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- uid       [db] (ref-eid db :kontor.partner/external-id "U-buyer"))
(defn- commodity [db] (ref-eid db :kontor.commodity/symbol "EUR"))
(defn- acct      [db code] (ref-eid db :kontor.account/code code))
(defn- ledger    [db code] (ref-eid db :kontor.ledger/code code))
(defn- journal   [db] (ref-eid db :kontor.journal/code "GEN"))
(defn- class-eid [db] (ref-eid db :kontor.asset-class/code "machinery"))

(defn- post!
  "Post a balanced 2-line tx: Dr `dr-acct` / Cr `cr-acct` for `amount`,
   on `ledger-eid` (nil = primary), at `date`."
  [conn {:keys [dr cr amount date ledger]}]
  (let [db (d/db conn)
        line (fn [a amt]
               (cond-> {:kontor.posting/account a
                        :kontor.posting/amount amt
                        :kontor.posting/commodity (commodity db)}
                 ledger (assoc :kontor.posting/ledger ledger)))]
    (posting/post-transaction!
     conn
     {:transaction {:kontor.transaction/journal (journal db)
                    :kontor.transaction/effective-date date}
      :postings [(line dr amount) (line cr (.negate ^java.math.BigDecimal amount))]})))

;; ============================================================================
;; :ledger filter
;; ============================================================================

(deftest ledger-filter-on-compute-statement
  (let [conn (bootstrap)
        db (d/db conn)
        ;; Tx A → IFRS ledger; Tx B → primary (no :kontor.posting/ledger).
        _ (post! conn {:dr (acct db "1800") :cr (acct db "8000")
                       :amount 1000.00M :date #inst "2026-06-01"
                       :ledger (ledger db "ifrs")})
        _ (post! conn {:dr (acct db "1800") :cr (acct db "8000")
                       :amount 2000.00M :date #inst "2026-06-02"})
        stmt {:statement/name "Revenue" :statement/country "DE"
              :statement/sections
              [{:section/code "R" :section/label "Revenue"
                :section/lines [{:line/code "1" :line/label "Sales"
                                 :line/codes ["8000"] :line/sign :inflow}]}]}
        val (fn [opts] (:amount (fs/line-value (fs/compute-statement conn stmt opts)
                                               "R" "1")))]
    (testing "no :ledger → both books"
      (is (= 3000.00M (val {}))))
    (testing ":ledger ifrs → only the IFRS-tagged posting"
      (is (= 1000.00M (val {:ledger (ledger (d/db conn) "ifrs")}))))
    (testing ":ledger hgb (primary) → the nil-ledger posting counts as primary"
      (is (= 2000.00M (val {:ledger (ledger (d/db conn) "hgb")}))))))

;; ============================================================================
;; compute-cash-flow
;; ============================================================================

(deftest compute-cash-flow-window-and-reconciliation
  (let [conn (bootstrap)
        db (d/db conn)
        _ (post! conn {:dr (acct db "1800") :cr (acct db "8000")
                       :amount 1000.00M :date #inst "2026-06-01"})
        _ (post! conn {:dr (acct db "1800") :cr (acct db "8000")
                       :amount 2000.00M :date #inst "2026-06-02"})
        cf-def {:statement/name "Cash flow" :statement/country "DE"
                :statement/sections
                [{:section/code "OP" :section/label "Operating"
                  :section/lines [{:line/code "ni" :line/label "Net revenue"
                                   :line/codes ["8000"] :line/sign :inflow}]}]}]
    (testing "requires :from and :to"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :from and :to"
                            (fs/compute-cash-flow conn cf-def {}))))
    (testing "window total + :statement/kind tag"
      (let [cf (fs/compute-cash-flow conn cf-def {:from #inst "2026-01-01"
                                                  :to #inst "2027-01-01"})]
        (is (= :cash-flow (:statement/kind cf)))
        (is (= 3000.00M (:amount (:statement/total cf))))))
    (testing ":reconcile-codes checks the statement against the cash accounts"
      (let [cf (fs/compute-cash-flow conn cf-def
                                     {:from #inst "2026-01-01"
                                      :to #inst "2027-01-01"
                                      :reconcile-codes ["1800"]})
            recon (:statement/reconciliation cf)]
        (is (= 3000.00M (:amount (:actual recon))) "bank received 3,000")
        (is (true? (:ok? recon)) "indirect statement reconciles to cash movement")))))

;; ============================================================================
;; compute-equity-changes
;; ============================================================================

(deftest compute-equity-changes-roll-forward
  (let [conn (bootstrap)
        db (d/db conn)
        ;; 5,000 contributed before the window, 2,000 within it.
        _ (post! conn {:dr (acct db "1800") :cr (acct db "2900")
                       :amount 5000.00M :date #inst "2025-06-01"})
        _ (post! conn {:dr (acct db "1800") :cr (acct db "2900")
                       :amount 2000.00M :date #inst "2026-06-01"})
        eq-def {:statement/name "Eigenkapitalspiegel" :statement/country "DE"
                :statement/components
                [{:component/code "capital"
                  :component/label "Subscribed Capital"
                  :component/codes ["2900"]
                  :component/movements
                  [{:movement/code "contrib" :movement/label "Contributions"
                    :movement/codes ["2900"]}]}]}
        result (fs/compute-equity-changes conn eq-def
                                          {:from #inst "2026-01-01"
                                           :to #inst "2027-01-01"})
        comp (first (:statement/components result))]
    (testing "opening / closing point-in-time balances"
      (is (= 5000.00M (:amount (:component/opening comp))))
      (is (= 7000.00M (:amount (:component/closing comp)))))
    (testing "the movement is the window delta"
      (is (= 2000.00M (:amount (:movement/value (first (:component/movements comp)))))))
    (testing "opening + Σmovements = closing → reconciles"
      (is (true? (:component/reconciles? comp))))
    (testing "totals"
      (is (= 5000.00M (:amount (:statement/total-opening result))))
      (is (= 7000.00M (:amount (:statement/total-closing result)))))))

;; ============================================================================
;; asset-roll-forward — the Anlagengitter
;; ============================================================================

(defn- acquire-machine! [conn code cost in-service-date]
  (asset/acquire! conn
                  {:code code
                   :name (str "Machine " code)
                   :class (class-eid (d/db conn))
                   :acquisition-cost cost
                   :acquisition-commodity (commodity (d/db conn))
                   :acquisition-date in-service-date
                   :in-service? true
                   :in-service-date in-service-date
                   :salvage-value 0M
                   :asset-account (acct (d/db conn) "0210")
                   :accumulated-account (acct (d/db conn) "0299")
                   :expense-account (acct (d/db conn) "6220")
                   :changed-by-uid (uid (d/db conn))})
  (asset/by-code (d/db conn) code))

(defn- open-and-run! [conn code as-of]
  (dep/open-book! conn {:asset code :ledger (ledger (d/db conn) "hgb")
                        :provider-id :straight-line :useful-life-months 120})
  (runner/run-depreciation! conn
                            (dep/book-for (d/db conn) code (ledger (d/db conn) "hgb"))
                            {:journal (journal (d/db conn)) :as-of as-of}))

(deftest asset-roll-forward-cost-and-accumulated
  (let [conn (bootstrap)
        ;; RF-OLD: in service 2025-06 (before the 2026 window).
        ;; RF-NEW: in service 2026-03 (an addition within the window).
        _ (acquire-machine! conn "RF-OLD" 100000.00M #inst "2025-06-01")
        _ (acquire-machine! conn "RF-NEW" 60000.00M #inst "2026-03-01")
        _ (open-and-run! conn "RF-OLD" #inst "2026-12-31")
        _ (open-and-run! conn "RF-NEW" #inst "2026-12-31")
        window {:from #inst "2026-01-01" :to #inst "2027-01-01"
                :ledger (ledger (d/db conn) "hgb")}]
    (testing "before any disposal: RF-OLD opening, RF-NEW addition"
      (let [rf (areport/asset-roll-forward (d/db conn) window)
            g (first (:groups rf))]
        (is (= 1 (count (:groups rf))) "one :asset-class group")
        (is (= 2 (:asset-count g)))
        (is (= 100000.00M (:cost-opening g)))
        (is (= 60000.00M (:cost-additions g)))
        (is (= 0M (:cost-disposals g)))
        (is (= 160000.00M (:cost-closing g)))
        (testing "the roll-forward identities hold"
          (is (= (:accum-closing g)
                 (.subtract (.add ^java.math.BigDecimal (:accum-opening g)
                                  ^java.math.BigDecimal (:accum-period g))
                            ^java.math.BigDecimal (:accum-disposals g))))
          (is (= (:nbv-closing g)
                 (.subtract ^java.math.BigDecimal (:cost-closing g)
                            ^java.math.BigDecimal (:accum-closing g)))))))
    (testing "after RF-OLD is disposed within the window"
      ;; Record the disposal :asset-event directly — asset-roll-forward
      ;; reads :asset-event, and this keeps the test off the approval
      ;; machinery (exercised in lifecycle_test).
      (d/transact conn [{:kontor.asset-event/asset (asset/by-code (d/db conn) "RF-OLD")
                         :kontor.asset-event/kind :disposal
                         :kontor.asset-event/date #inst "2026-06-15"}])
      (let [rf (areport/asset-roll-forward (d/db conn) window)
            g (first (:groups rf))]
        (is (= 100000.00M (:cost-disposals g)))
        (is (= 60000.00M (:cost-closing g))
            "RF-OLD leaves the closing balance; RF-NEW remains")
        (is (= 5000.00M (:accum-closing g))
            "RF-OLD's accumulated depreciation comes off; RF-NEW's 10 × €500 remains")
        (is (= 55000.00M (:nbv-closing g)))))
    (testing ":group-by :none collapses to one :all group with :totals"
      (let [rf (areport/asset-roll-forward (d/db conn) (assoc window :group-by :none))]
        (is (= [:all] (mapv :group (:groups rf))))
        (is (= 60000.00M (:cost-closing (:totals rf))))))))

;; ============================================================================
;; pending-depreciation-issues — the pre-close hook
;; ============================================================================

(deftest pending-depreciation-pre-close-hook
  (let [conn (bootstrap)
        _ (acquire-machine! conn "PC-1" 120000.00M #inst "2026-01-15")
        _ (dep/open-book! conn {:asset "PC-1" :ledger (ledger (d/db conn) "hgb")
                                :provider-id :straight-line
                                :useful-life-months 120})
        period {:start #inst "2026-01-01" :end #inst "2026-07-01"}]
    (testing "un-fired occurrences within the period flag the close"
      (let [issues (areport/pending-depreciation-issues (d/db conn) period)]
        (is (= 1 (count issues)))
        (is (= :no-pending-depreciation (:check (first issues))))))
    (testing "after the runner fires them, the check is clean"
      (runner/run-depreciation! conn
                                (dep/book-for (d/db conn) "PC-1"
                                              (ledger (d/db conn) "hgb"))
                                {:journal (journal (d/db conn))
                                 :as-of #inst "2026-07-01"})
      (is (empty? (areport/pending-depreciation-issues (d/db conn) period))))))

;; ============================================================================
;; Review-after: roll-forward folds impairment / revaluation / opening-accumulated
;; ============================================================================

(deftest roll-forward-folds-mid-life-events
  ;; Market-pain: an impairment must show in the accumulated
  ;; roll-forward (HGB §284), a revaluation in the cost roll-forward,
  ;; and a mid-life import's :opening-accumulated must be opening
  ;; accumulated depreciation.
  ;;
  ;; NOTE on the bare :asset-event rows below: they are transacted
  ;; with NO GL entry, and the expectations here (105,000 cost
  ;; additions, 12,000 impairments) are deliberately the SUBLEDGER's
  ;; figures, not the GL's. The roll-forward counts every value-moving
  ;; event because kontor.asset.posting/plan-disposal relieves the
  ;; same figures — a report that silently dropped un-posted events
  ;; would make a disposal under-relieve the control accounts, which
  ;; is the far worse failure. The gap between register and GL is a
  ;; real finding, and it is REPORTED, by
  ;; kontor.asset.report/asset-tie-out — see
  ;; kontor.asset.tie-out-test/tie-out-reports-an-event-that-was-recorded-but-never-posted,
  ;; which runs exactly this scenario and asserts
  ;; :difference {:cost 5000 :accumulated 12000} / :ok? false.
  (let [conn (bootstrap)
        _ (acquire-machine! conn "EV-1" 100000.00M #inst "2026-02-01")
        db (d/db conn)
        ;; Mid-life import: €30,000 already depreciated before the book.
        _ (dep/open-book! conn {:asset "EV-1" :ledger (ledger db "hgb")
                                :provider-id :straight-line
                                :useful-life-months 120
                                :depreciable-base 100000.00M
                                :opening-accumulated 30000.00M})
        ;; An impairment and a revaluation, both within the window.
        _ (d/transact conn [{:kontor.asset-event/asset (asset/by-code (d/db conn) "EV-1")
                             :kontor.asset-event/kind :impairment
                             :kontor.asset-event/date #inst "2026-06-01"
                             :kontor.asset-event/amount 12000.00M}
                            {:kontor.asset-event/asset (asset/by-code (d/db conn) "EV-1")
                             :kontor.asset-event/kind :revaluation
                             :kontor.asset-event/date #inst "2026-09-01"
                             :kontor.asset-event/amount 5000.00M}])
        window {:from #inst "2026-01-01" :to #inst "2027-01-01"
                :ledger (ledger (d/db conn) "hgb")}
        g (first (:groups (areport/asset-roll-forward (d/db conn) window)))]
    (testing ":opening-accumulated is opening accumulated depreciation"
      (is (= 30000.00M (:accum-opening g))))
    (testing "the impairment folds into accum-period + the :impairments memo"
      (is (= 12000.00M (:accum-period g)))
      (is (= 12000.00M (:impairments g))))
    (testing "the revaluation folds into cost-additions + the :revaluations memo"
      ;; EV-1 entered in-window → cost-additions = 100,000 cost + 5,000 reval.
      (is (= 105000.00M (:cost-additions g)))
      (is (= 5000.00M (:revaluations g))))
    (testing "the roll-forward identities still hold"
      (is (= (:accum-closing g)
             (.subtract (.add ^java.math.BigDecimal (:accum-opening g)
                              ^java.math.BigDecimal (:accum-period g))
                        ^java.math.BigDecimal (:accum-disposals g))))
      (is (= (:nbv-closing g)
             (.subtract ^java.math.BigDecimal (:cost-closing g)
                        ^java.math.BigDecimal (:accum-closing g)))))))
