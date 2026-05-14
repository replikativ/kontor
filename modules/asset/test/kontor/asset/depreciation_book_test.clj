(ns kontor.asset.depreciation-book-test
  "ADR-054: depreciation books (a depreciation area IS a :ledger) +
   the GL posting builders.

   Covers:
   - open-book! creates an :asset-depreciation book + its :schedule
     (+ optional :asset-method-params), derives the schedule
     end-date from useful-life-months × frequency.
   - one book per (asset, ledger) — a second open-book! for the same
     pair is rejected.
   - a multi-book asset (hgb + tax-de ledgers) has two independent
     books with two independent schedules.
   - accumulated-depreciation / net-book-value read the schedule's
     occurrence log, not the GL.
   - the kontor.asset.posting builders produce balanced tx-data:
     capitalisation, depreciation-charge, disposal (gain + loss +
     fully-depreciated scrap), impairment, revaluation."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.asset.asset :as asset]
            [kontor.asset.depreciation :as dep]
            [kontor.asset.posting :as ap]
            [kontor.asset.schema :as asset-schema]
            [kontor.core :as core]
            [kontor.posting :as kposting]
            [kontor.schedule :as schedule]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (asset-schema/install! conn)
    (d/transact conn
                [{:db/id "eur" :commodity/symbol "EUR" :commodity/precision 2}
                 {:partner/external-id "U-buyer" :partner/name "Asset Buyer"}
                 ;; GL accounts.
                 {:db/id "acct-machinery"
                  :account/code "0210" :account/name "Machinery"
                  :account/type :asset :account/active true}
                 {:db/id "acct-accum"
                  :account/code "0299" :account/name "Accumulated Depreciation"
                  :account/type :asset :account/active true}
                 {:db/id "acct-dep-expense"
                  :account/code "6220" :account/name "Depreciation Expense"
                  :account/type :expense :account/active true}
                 {:db/id "acct-bank"
                  :account/code "1800" :account/name "Bank"
                  :account/type :asset :account/active true}
                 {:db/id "acct-gain"
                  :account/code "4900" :account/name "Gain on Disposal"
                  :account/type :income :account/active true}
                 {:db/id "acct-loss"
                  :account/code "6900" :account/name "Loss on Disposal"
                  :account/type :expense :account/active true}
                 {:db/id "acct-impair"
                  :account/code "6230" :account/name "Impairment Expense"
                  :account/type :expense :account/active true}
                 {:db/id "acct-reval-surplus"
                  :account/code "2920" :account/name "Revaluation Surplus"
                  :account/type :equity :account/active true}
                 ;; Two ledgers — the two depreciation areas.
                 {:db/id "ledger-hgb"
                  :ledger/code "hgb" :ledger/name "Handelsbilanz"
                  :ledger/type :primary :ledger/framework :HGB
                  :ledger/active true}
                 {:db/id "ledger-tax"
                  :ledger/code "tax-de" :ledger/name "Steuerbilanz"
                  :ledger/type :secondary :ledger/framework :tax-de
                  :ledger/active true}
                 ;; Journal.
                 {:db/id "journal-gen"
                  :journal/code "GEN" :journal/name "General"
                  :journal/type :general}
                 ;; Asset class.
                 {:db/id "class-machinery"
                  :asset-class/code "machinery"
                  :asset-class/name "Machinery & Equipment"
                  :asset-class/default-useful-life-months 120}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- uid       [db] (ref-eid db :partner/external-id "U-buyer"))
(defn- commodity [db] (ref-eid db :commodity/symbol "EUR"))
(defn- acct      [db code] (ref-eid db :account/code code))
(defn- ledger    [db code] (ref-eid db :ledger/code code))
(defn- journal   [db] (ref-eid db :journal/code "GEN"))
(defn- class-eid [db] (ref-eid db :asset-class/code "machinery"))

;; A standard in-service €120,000 machine, salvage €0.
(defn- acquire-machine! [conn code]
  (let [db (d/db conn)]
    (asset/acquire! conn
                    {:code code
                     :name (str "Machine " code)
                     :class (class-eid db)
                     :acquisition-cost 120000.00M
                     :acquisition-commodity (commodity db)
                     :acquisition-date #inst "2026-01-15"
                     :in-service? true
                     :salvage-value 0M
                     :asset-account (acct db "0210")
                     :accumulated-account (acct db "0299")
                     :expense-account (acct db "6220")
                     :changed-by-uid (uid db)})
    (asset/by-code (d/db conn) code)))

;; ============================================================================
;; open-book!
;; ============================================================================

(deftest open-book-creates-book-and-schedule
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-001")
        db (d/db conn)
        _ (dep/open-book! conn
                          {:asset "MACH-001"
                           :ledger (ledger db "hgb")
                           :provider-id :straight-line
                           :useful-life-months 120})
        book (dep/pull-book (d/db conn) [(asset/by-code (d/db conn) "MACH-001")
                                         (ledger (d/db conn) "hgb")])]
    (testing "the book carries its config"
      (is (= :straight-line (:asset-depreciation/provider-id book)))
      (is (= 120 (:asset-depreciation/useful-life-months book)))
      (is (= :full (:asset-depreciation/convention book)))
      (is (= 120000.00M (:asset-depreciation/depreciable-base book))
          "depreciable-base defaults to acquisition-cost − salvage"))
    (testing "the schedule is created, active, monthly, with a derived end-date"
      (let [s (:asset-depreciation/schedule book)]
        (is (= :depreciation (:schedule/kind s)))
        (is (= :active (:schedule/state s)))
        (is (= :monthly (:schedule/frequency s)))
        (is (= #inst "2026-01-15" (:schedule/start-date s)))
        (is (= (schedule/date-of-occurrence #inst "2026-01-15" :monthly 120)
               (:schedule/end-date s))
            "end-date = start + useful-life-months occurrences")))))

(deftest open-book-with-method-params
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-DB")
        db (d/db conn)
        _ (dep/open-book! conn
                          {:asset "MACH-DB"
                           :ledger (ledger db "tax-de")
                           :provider-id :declining-balance
                           :useful-life-months 120
                           :method-params {:asset-method-params/rate-multiple 2.5M
                                           :asset-method-params/ceiling-rate 0.25M
                                           :asset-method-params/switch-to-straight-line true}})
        book (dep/pull-book (d/db conn) [(asset/by-code (d/db conn) "MACH-DB")
                                         (ledger (d/db conn) "tax-de")])]
    (testing "the inline method-params entity is created and linked"
      (let [mp (:asset-depreciation/method-params book)]
        (is (= 2.5M (:asset-method-params/rate-multiple mp)))
        (is (= 0.25M (:asset-method-params/ceiling-rate mp)))
        (is (true? (:asset-method-params/switch-to-straight-line mp)))))))

(deftest one-book-per-asset-ledger-pair
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-DUP")
        db (d/db conn)
        _ (dep/open-book! conn
                          {:asset "MACH-DUP" :ledger (ledger db "hgb")
                           :provider-id :straight-line :useful-life-months 120})]
    (testing "a second book for the same (asset, ledger) is rejected"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"already exists"
           (dep/open-book! conn
                           {:asset "MACH-DUP" :ledger (ledger (d/db conn) "hgb")
                            :provider-id :declining-balance
                            :useful-life-months 96}))))))

(deftest multi-book-asset-has-independent-schedules
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-MB")
        db (d/db conn)
        _ (dep/open-book! conn
                          {:asset "MACH-MB" :ledger (ledger db "hgb")
                           :provider-id :straight-line :useful-life-months 120})
        _ (dep/open-book! conn
                          {:asset "MACH-MB" :ledger (ledger db "tax-de")
                           :provider-id :declining-balance :useful-life-months 84})]
    (testing "two ledgers → two independent books"
      (is (= 2 (count (dep/books-of (d/db conn) "MACH-MB")))))
    (testing "each book has its own schedule with its own life"
      (let [hgb (dep/pull-book (d/db conn) [(asset/by-code (d/db conn) "MACH-MB")
                                            (ledger (d/db conn) "hgb")])
            tax (dep/pull-book (d/db conn) [(asset/by-code (d/db conn) "MACH-MB")
                                            (ledger (d/db conn) "tax-de")])]
        (is (= 120 (:asset-depreciation/useful-life-months hgb)))
        (is (= 84 (:asset-depreciation/useful-life-months tax)))
        (is (not= (:db/id (:asset-depreciation/schedule hgb))
                  (:db/id (:asset-depreciation/schedule tax))))))))

;; ============================================================================
;; Roll-forward queries
;; ============================================================================

(deftest accumulated-and-nbv-read-the-occurrence-log
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-NBV")
        db (d/db conn)
        _ (dep/open-book! conn
                          {:asset "MACH-NBV" :ledger (ledger db "hgb")
                           :provider-id :straight-line :useful-life-months 120})
        book (dep/book-for (d/db conn) "MACH-NBV" (ledger (d/db conn) "hgb"))
        sched (:db/id (:asset-depreciation/schedule
                       (d/pull (d/db conn) [:asset-depreciation/schedule] book)))
        eur (commodity (d/db conn))]
    (testing "with no occurrences, accumulated = 0 and NBV = cost"
      (is (= 0M (dep/accumulated-depreciation (d/db conn) book)))
      (is (= 120000.00M (dep/net-book-value (d/db conn) book))))
    (testing "after two €1,000 occurrences, accumulated = 2,000 and NBV = 118,000"
      ;; Record two occurrences directly (the ADR-055 runner does this
      ;; for real; here we only exercise the roll-forward queries).
      (d/transact conn [{:schedule-occurrence/schedule sched
                         :schedule-occurrence/sequence 1
                         :schedule-occurrence/scheduled-date #inst "2026-02-15"
                         :schedule-occurrence/amount 1000.00M
                         :schedule-occurrence/commodity eur
                         :schedule-occurrence/fired-at #inst "2026-02-15"}
                        {:schedule-occurrence/schedule sched
                         :schedule-occurrence/sequence 2
                         :schedule-occurrence/scheduled-date #inst "2026-03-15"
                         :schedule-occurrence/amount 1000.00M
                         :schedule-occurrence/commodity eur
                         :schedule-occurrence/fired-at #inst "2026-03-15"}])
      (is (= 2000.00M (dep/accumulated-depreciation (d/db conn) book)))
      (is (= 118000.00M (dep/net-book-value (d/db conn) book))))))

;; ============================================================================
;; GL posting builders — kontor.asset.posting
;; ============================================================================

(defn- tx-balanced?
  "A built tx-data vector balances iff posting/validate is :ok?."
  [tx-data]
  (let [tx (first (filter #(:transaction/journal %) tx-data))
        postings (filter :posting/account tx-data)]
    (:ok? (kposting/validate {:transaction tx :postings postings}))))

(defn- posting-rows [tx-data]
  (filter :posting/account tx-data))

(deftest plan-capitalisation-builds-balanced-entry
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-CAP")
        db (d/db conn)
        tx-data (ap/plan-capitalisation
                 db {:asset "MACH-CAP"
                     :credit-account (acct db "1800")
                     :journal (journal db)
                     :date #inst "2026-01-15"
                     :narration "Capitalise MACH-CAP"})]
    (testing "Dr asset-account 120,000 / Cr bank 120,000"
      (is (tx-balanced? tx-data))
      (let [ps (posting-rows tx-data)]
        (is (= 2 (count ps)))
        (is (= 120000.00M (->> ps (map :posting/amount) (filter pos?) first)))
        (is (= -120000.00M (->> ps (map :posting/amount) (filter neg?) first)))))))

(deftest plan-depreciation-charge-builds-balanced-ledger-tagged-entry
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-DEP")
        db (d/db conn)
        _ (dep/open-book! conn
                          {:asset "MACH-DEP" :ledger (ledger db "hgb")
                           :provider-id :straight-line :useful-life-months 120})
        book (dep/book-for (d/db conn) "MACH-DEP" (ledger (d/db conn) "hgb"))
        tx-data (ap/plan-depreciation-charge
                 (d/db conn)
                 {:book book :amount 1000.00M
                  :journal (journal (d/db conn)) :date #inst "2026-02-15"})]
    (testing "Dr expense / Cr accumulated, both tagged with the book's ledger"
      (is (tx-balanced? tx-data))
      (let [ps (posting-rows tx-data)
            hgb (ledger (d/db conn) "hgb")]
        (is (= 2 (count ps)))
        (is (every? #(= hgb (:posting/ledger %)) ps))
        (is (= #{1000.00M -1000.00M} (set (map :posting/amount ps))))))))

(deftest plan-disposal-gain-loss-and-scrap
  (let [conn (bootstrap)
        db0 (d/db conn)
        hgb (ledger db0 "hgb")
        ;; Helper: open a book, record `n` €1,000 occurrences.
        setup-book!
        (fn [code occurrences]
          (acquire-machine! conn code)
          (dep/open-book! conn
                          {:asset code :ledger hgb
                           :provider-id :straight-line :useful-life-months 120})
          (let [book (dep/book-for (d/db conn) code hgb)
                sched (:db/id (:asset-depreciation/schedule
                               (d/pull (d/db conn)
                                       [:asset-depreciation/schedule] book)))
                eur (commodity (d/db conn))]
            (when (pos? occurrences)
              (d/transact conn
                          (mapv (fn [i]
                                  {:schedule-occurrence/schedule sched
                                   :schedule-occurrence/sequence (inc i)
                                   :schedule-occurrence/scheduled-date #inst "2026-02-15"
                                   :schedule-occurrence/amount 1000.00M
                                   :schedule-occurrence/commodity eur
                                   :schedule-occurrence/fired-at #inst "2026-02-15"})
                                (range occurrences))))
            book))]
    (testing "disposal at a GAIN — proceeds above NBV"
      ;; 10 occurrences → accumulated 10,000 → NBV 110,000. Sell for 115,000.
      (let [book (setup-book! "MACH-GAIN" 10)
            tx-data (ap/plan-disposal
                     (d/db conn)
                     {:book book
                      :asset-account-cost 120000.00M
                      :proceeds 115000.00M
                      :proceeds-account (acct (d/db conn) "1800")
                      :gain-account (acct (d/db conn) "4900")
                      :journal (journal (d/db conn))
                      :date #inst "2026-12-01"})]
        (is (tx-balanced? tx-data))
        (let [amts (set (map :posting/amount (posting-rows tx-data)))]
          ;; Dr bank 115,000 + Dr accum 10,000 / Cr asset 120,000 + Cr gain 5,000
          (is (contains? amts 115000.00M))
          (is (contains? amts 10000.00M))
          (is (contains? amts -120000.00M))
          (is (contains? amts -5000.00M)))))
    (testing "disposal at a LOSS — proceeds below NBV"
      ;; 10 occurrences → NBV 110,000. Sell for 90,000 → loss 20,000.
      (let [book (setup-book! "MACH-LOSS" 10)
            tx-data (ap/plan-disposal
                     (d/db conn)
                     {:book book
                      :asset-account-cost 120000.00M
                      :proceeds 90000.00M
                      :proceeds-account (acct (d/db conn) "1800")
                      :loss-account (acct (d/db conn) "6900")
                      :journal (journal (d/db conn))
                      :date #inst "2026-12-01"})]
        (is (tx-balanced? tx-data))
        (let [amts (set (map :posting/amount (posting-rows tx-data)))]
          ;; Dr bank 90,000 + Dr accum 10,000 + Dr loss 20,000 / Cr asset 120,000
          (is (contains? amts 90000.00M))
          (is (contains? amts 10000.00M))
          (is (contains? amts 20000.00M))
          (is (contains? amts -120000.00M)))))
    (testing "scrap of a fully-depreciated asset — no proceeds, no gain/loss"
      ;; 120 occurrences → accumulated 120,000 → NBV 0. Scrap for 0.
      (let [book (setup-book! "MACH-SCRAP" 120)
            tx-data (ap/plan-disposal
                     (d/db conn)
                     {:book book
                      :asset-account-cost 120000.00M
                      :journal (journal (d/db conn))
                      :date #inst "2036-01-15"})]
        (is (tx-balanced? tx-data))
        (let [ps (posting-rows tx-data)]
          ;; Dr accum 120,000 / Cr asset 120,000 — exactly two lines.
          (is (= 2 (count ps)))
          (is (= #{120000.00M -120000.00M} (set (map :posting/amount ps)))))))))

(deftest plan-impairment-builds-balanced-entry
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-IMP")
        db (d/db conn)
        _ (dep/open-book! conn
                          {:asset "MACH-IMP" :ledger (ledger db "hgb")
                           :provider-id :straight-line :useful-life-months 120})
        book (dep/book-for (d/db conn) "MACH-IMP" (ledger (d/db conn) "hgb"))
        tx-data (ap/plan-impairment
                 (d/db conn)
                 {:book book :amount 15000.00M
                  :impairment-expense-account (acct (d/db conn) "6230")
                  :journal (journal (d/db conn)) :date #inst "2026-06-30"})]
    (testing "Dr impairment-expense 15,000 / Cr accumulated 15,000"
      (is (tx-balanced? tx-data))
      (is (= #{15000.00M -15000.00M}
             (set (map :posting/amount (posting-rows tx-data))))))))

(deftest plan-revaluation-upward-and-downward
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-REVAL")
        db (d/db conn)
        _ (dep/open-book! conn
                          {:asset "MACH-REVAL" :ledger (ledger db "hgb")
                           :provider-id :straight-line :useful-life-months 120})
        book (dep/book-for (d/db conn) "MACH-REVAL" (ledger (d/db conn) "hgb"))]
    (testing "upward revaluation: Dr asset-account / Cr revaluation-surplus"
      (let [tx-data (ap/plan-revaluation
                     (d/db conn)
                     {:book book :amount 20000.00M
                      :revaluation-surplus-account (acct (d/db conn) "2920")
                      :journal (journal (d/db conn)) :date #inst "2027-01-01"})]
        (is (tx-balanced? tx-data))
        (is (= #{20000.00M -20000.00M}
               (set (map :posting/amount (posting-rows tx-data)))))))
    (testing "downward revaluation: a negative :amount flips the entry"
      (let [tx-data (ap/plan-revaluation
                     (d/db conn)
                     {:book book :amount -8000.00M
                      :revaluation-surplus-account (acct (d/db conn) "2920")
                      :journal (journal (d/db conn)) :date #inst "2027-06-01"})]
        (is (tx-balanced? tx-data))
        (is (= #{-8000.00M 8000.00M}
               (set (map :posting/amount (posting-rows tx-data)))))))))

;; ============================================================================
;; Review-after fixes
;; ============================================================================

(deftest opening-accumulated-feeds-accumulated-and-nbv
  ;; Market-pain P1-3: mid-life import — an asset already part-way
  ;; through its life on day one.
  (let [conn (bootstrap)
        _ (acquire-machine! conn "IMPORT-1")
        db (d/db conn)
        ;; €120,000 asset, already €36,000 depreciated; €84,000 over
        ;; the remaining 84 months.
        _ (dep/open-book! conn {:asset "IMPORT-1" :ledger (ledger db "hgb")
                                :provider-id :straight-line
                                :useful-life-months 84
                                :depreciable-base 84000.00M
                                :opening-accumulated 36000.00M})
        book (dep/book-for (d/db conn) "IMPORT-1" (ledger (d/db conn) "hgb"))]
    (testing "accumulated-depreciation includes the pre-schedule opening figure"
      (is (= 36000.00M (dep/accumulated-depreciation (d/db conn) book))))
    (testing "net-book-value nets it against acquisition cost"
      (is (= 84000.00M (dep/net-book-value (d/db conn) book))))))

(deftest plan-disposal-defaults-asset-account-cost
  (let [conn (bootstrap)
        _ (acquire-machine! conn "DISP-DEFAULT")
        db (d/db conn)
        _ (dep/open-book! conn {:asset "DISP-DEFAULT" :ledger (ledger db "hgb")
                                :provider-id :straight-line :useful-life-months 120})
        book (dep/book-for (d/db conn) "DISP-DEFAULT" (ledger (d/db conn) "hgb"))
        ;; No :asset-account-cost — defaults to the asset's €120,000.
        tx-data (ap/plan-disposal
                 (d/db conn)
                 {:book book
                  :loss-account (acct (d/db conn) "6900")
                  :journal (journal (d/db conn))
                  :date #inst "2027-01-01"})]
    (testing "the asset-account is credited with the full acquisition cost"
      (is (tx-balanced? tx-data))
      ;; No occurrences, no proceeds → Dr loss 120,000 / Cr asset 120,000.
      (is (contains? (set (map :posting/amount (posting-rows tx-data)))
                     -120000.00M)))))
