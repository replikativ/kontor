(ns kontor.asset.tie-out-test
  "The asset subledger must NET WHAT THE GL ACTUALLY POSTED.

   Every assertion here is a GL BALANCE, not a subledger row count or
   a log sum — the whole bug class these tests pin is 'the subledger
   agrees with itself while the control account says something else'.

   Covers:
   - `plan-disposal` relieves the accumulated depreciation the GL
     carries, impairments included (an impairment credits 0299 and
     writes no `:schedule-occurrence`, so a schedule-only sum
     under-relieved and FLIPPED THE SIGN of the gain/loss).
   - a PARTIAL disposal relieves the same fraction of accumulated as
     of cost (relieving 100% of accumulated against 30% of the cost
     inflated the gain and drove 0299 to zero while 0210 still carried
     the retained 70%).
   - `plan-disposal` relieves the REVALUED gross cost, so a
     revaluation is not stranded on the balance sheet.
   - `asset-roll-forward` does not collapse duplicate
     `(scheduled-date, amount)` occurrences (the missing `:with ?o`).
   - `asset-tie-out` — the detective control: it reports the delta
     when the runner posted drafts, and when an `:asset-event` was
     recorded but its GL entry never was."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.asset.asset :as asset]
            [kontor.asset.depreciation :as dep]
            [kontor.asset.posting :as ap]
            [kontor.asset.report :as areport]
            [kontor.asset.runner :as runner]
            [kontor.asset.schema :as asset-schema]
            [kontor.core :as core]
            [kontor.reporting.balance :as balance]
            [kontor.validation :as validation]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (asset-schema/install! conn)
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:kontor.partner/external-id "U-buyer"   :kontor.partner/name "Asset Buyer"}
                 {:kontor.partner/external-id "U-manager" :kontor.partner/name "Asset Manager"}
                 {:kontor.account/code "0210" :kontor.account/name "Machinery"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:kontor.account/code "0299" :kontor.account/name "Accumulated Depreciation"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:kontor.account/code "1800" :kontor.account/name "Bank"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:kontor.account/code "2920" :kontor.account/name "Revaluation Surplus"
                  :kontor.account/type :equity :kontor.account/active true}
                 {:kontor.account/code "4900" :kontor.account/name "Gain on Disposal"
                  :kontor.account/type :income :kontor.account/active true}
                 {:kontor.account/code "6220" :kontor.account/name "Depreciation Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "6230" :kontor.account/name "Impairment Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "6900" :kontor.account/name "Loss on Disposal"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.ledger/code "hgb" :kontor.ledger/name "Handelsbilanz"
                  :kontor.ledger/type :primary :kontor.ledger/framework :HGB
                  :kontor.ledger/active true}
                 {:kontor.journal/code "GEN" :kontor.journal/name "General"
                  :kontor.journal/type :general}
                 {:kontor.asset-class/code "machinery"
                  :kontor.asset-class/name "Machinery & Equipment"
                  :kontor.asset-class/default-useful-life-months 120}
                 {:kontor.audit-doc/code "ASSET-DISPOSAL-001"
                  :kontor.audit-doc/type :disposal-authorisation
                  :kontor.audit-doc/storage-uri "s3://docs/asset-disposal-001"
                  :kontor.audit-doc/uploaded-at #inst "2026-11-01"}
                 {:kontor.audit-doc/code "ASSET-IMPAIR-001"
                  :kontor.audit-doc/type :impairment-test-memo
                  :kontor.audit-doc/storage-uri "s3://docs/asset-impair-001"
                  :kontor.audit-doc/uploaded-at #inst "2026-11-30"}
                 {:kontor.audit-doc/code "ASSET-REVAL-001"
                  :kontor.audit-doc/type :valuation-report
                  :kontor.audit-doc/storage-uri "s3://docs/asset-reval-001"
                  :kontor.audit-doc/uploaded-at #inst "2026-11-30"}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- uid       [db who] (ref-eid db :kontor.partner/external-id (str "U-" who)))
(defn- commodity [db] (ref-eid db :kontor.commodity/symbol "EUR"))
(defn- acct      [db code] (ref-eid db :kontor.account/code code))
(defn- adoc      [db code] (ref-eid db :kontor.audit-doc/code code))
(defn- hgb       [db] (ref-eid db :kontor.ledger/code "hgb"))
(defn- journal   [db] (ref-eid db :kontor.journal/code "GEN"))
(defn- class-eid [db] (ref-eid db :kontor.asset-class/code "machinery"))

(defn- bd=
  "BigDecimal equality by VALUE, so a 0M vs 0.00M scale difference
   never masks or fakes a match."
  [a b]
  (zero? (.compareTo ^java.math.BigDecimal (bigdec a) ^java.math.BigDecimal (bigdec b))))

(defn- gl
  "The GL balance on `code` in EUR. A debit reads positive, a credit
   negative. Only :posted transactions count (the kernel default)."
  ([conn code] (gl conn code {}))
  ([conn code opts]
   (let [db (d/db conn)]
     (or (:amount (get (balance/account-balance conn (acct db code) opts)
                       (commodity db)))
         0M))))

(defn- tie-out
  ([conn] (tie-out conn {}))
  ([conn opts]
   (let [db (d/db conn)]
     (areport/asset-tie-out
      conn (merge {:ledger (hgb db)
                   :asset-account (acct db "0210")
                   :accumulated-account (acct db "0299")
                   :commodity (commodity db)}
                  opts)))))

(defn- post!
  "Transact builder output through the ADR-068 gate."
  [conn tx-data]
  (validation/transact-with-validation conn tx-data))

(defn- acquire-machine!
  "An in-service machine at `cost`, in service 2026-01-15, and its
   capitalisation entry POSTED (Dr 0210 / Cr 1800) — without that the
   GL asset account is empty and there is nothing to tie out against."
  [conn code cost]
  (let [db (d/db conn)]
    (asset/acquire! conn
                    {:code code
                     :name (str "Machine " code)
                     :class (class-eid db)
                     :acquisition-cost cost
                     :acquisition-commodity (commodity db)
                     :acquisition-date #inst "2026-01-15"
                     :in-service? true
                     :salvage-value 0M
                     :asset-account (acct db "0210")
                     :accumulated-account (acct db "0299")
                     :expense-account (acct db "6220")
                     :changed-by-uid (uid db "buyer")})
    (post! conn (ap/plan-capitalisation
                 (d/db conn)
                 {:asset code
                  :credit-account (acct (d/db conn) "1800")
                  :journal (journal (d/db conn))
                  :date #inst "2026-01-15"
                  :posted-at #inst "2026-01-15"
                  :narration (str "Capitalise " code)}))
    (asset/by-code (d/db conn) code)))

(defn- open-book! [conn code]
  (dep/open-book! conn {:asset code :ledger (hgb (d/db conn))
                        :provider-id :straight-line :useful-life-months 120})
  (dep/book-for (d/db conn) code (hgb (d/db conn))))

(defn- run-to! [conn book as-of & [opts]]
  (runner/run-depreciation! conn book
                            (merge {:journal (journal (d/db conn)) :as-of as-of}
                                   opts)))

(defn- amounts [tx-data]
  (into #{} (comp (filter :kontor.posting/account)
                  (map :kontor.posting/amount))
        tx-data))

;; ============================================================================
;; HIGH-1 — the impairment the GL carries must come off on disposal
;; ============================================================================

(deftest disposal-relieves-the-accumulated-the-gl-actually-carries
  ;; €120,000 machine, 120-month straight line. 11 monthly charges
  ;; (2026-01-15 … 2026-11-15) = €11,000, then a €15,000 impairment.
  ;;
  ;;   GL 0299 = −11,000 − 15,000 = −26,000
  ;;
  ;; plan-impairment credits 0299 and writes NO :schedule-occurrence,
  ;; so a schedule-only accumulated figure says 11,000. Disposing at
  ;; €100,000 on that figure posts a €9,000 LOSS; the truth is a
  ;; €6,000 GAIN — €15,000 wrong WITH THE SIGN FLIPPED, and it
  ;; balances, so the transact gate waves it through.
  (let [conn (bootstrap)
        _    (acquire-machine! conn "TIE-IMP" 120000.00M)
        book (open-book! conn "TIE-IMP")
        run  (run-to! conn book #inst "2026-12-01")]
    (testing "11 charges land in the ledger"
      (is (= 11 (:count run)))
      (is (bd= 11000.00M (gl conn "6220")))
      (is (bd= -11000.00M (gl conn "0299"))))

    (testing "the impairment credits the SAME control account, off-schedule"
      (post! conn (ap/plan-impairment
                   (d/db conn)
                   {:book book :amount 15000.00M
                    :impairment-expense-account (acct (d/db conn) "6230")
                    :journal (journal (d/db conn))
                    :date #inst "2026-11-30"
                    :posted-at #inst "2026-11-30"}))
      (asset/impair! conn {:asset "TIE-IMP"
                           :date #inst "2026-11-30"
                           :amount 15000.00M
                           :commodity (commodity (d/db conn))
                           :justification (adoc (d/db conn) "ASSET-IMPAIR-001")
                           :reason-note "recoverable amount below carrying amount"})
      (is (bd= 15000.00M (gl conn "6230")))
      (is (bd= -26000.00M (gl conn "0299"))))

    (testing "the subledger figures name what they are"
      (is (bd= 11000.00M (dep/scheduled-depreciation (d/db conn) book))
          "the schedule log — planned charges only")
      (is (bd= 26000.00M (dep/accumulated-depreciation (d/db conn) book))
          "the control-account figure — impairment included")
      (is (bd= 94000.00M (dep/net-book-value (d/db conn) book))))

    (testing "the subledger ties to the GL before the disposal"
      (let [t (tie-out conn)]
        (is (bd= 120000.00M (:cost (:subledger t))))
        (is (bd= 26000.00M (:accumulated (:subledger t))))
        (is (bd= 120000.00M (:cost (:gl t))))
        (is (bd= 26000.00M (:accumulated (:gl t))))
        (is (true? (:ok? t)))))

    (testing "disposal at €100,000 is a €6,000 GAIN, not a €9,000 loss"
      (let [tx-data (ap/plan-disposal
                     (d/db conn)
                     {:book book
                      :proceeds 100000.00M
                      :proceeds-account (acct (d/db conn) "1800")
                      :gain-account (acct (d/db conn) "4900")
                      :loss-account (acct (d/db conn) "6900")
                      :journal (journal (d/db conn))
                      :date #inst "2026-12-01"
                      :posted-at #inst "2026-12-01"})]
        ;; Dr 1800 100,000 + Dr 0299 26,000 / Cr 0210 120,000 + Cr 4900 6,000
        (is (contains? (amounts tx-data) 26000.00M) "the FULL contra balance comes off")
        (is (contains? (amounts tx-data) -6000.00M) "a 6,000 credit — a gain")
        (is (not (contains? (amounts tx-data) 9000.00M)) "not a 9,000 debit to loss")
        (post! conn tx-data)
        (asset/dispose! conn {:asset "TIE-IMP"
                              :date #inst "2026-12-01"
                              :proceeds 100000.00M
                              :commodity (commodity (d/db conn))
                              :changed-by-uid (uid (d/db conn) "manager")
                              :justification (adoc (d/db conn) "ASSET-DISPOSAL-001")})))

    (testing "both control accounts are fully relieved"
      (is (bd= 0M (gl conn "0210")))
      (is (bd= 0M (gl conn "0299")) "no €15,000 stranded in the contra account")
      (is (bd= -6000.00M (gl conn "4900")))
      (is (bd= 0M (gl conn "6900")))
      (is (bd= -20000.00M (gl conn "1800")) "paid 120,000, received 100,000"))

    (testing "and the tie-out closes at zero"
      (let [t (tie-out conn)]
        (is (= 0 (:asset-count t)) "a :disposed asset leaves the subledger")
        (is (bd= 0M (:cost (:difference t))))
        (is (bd= 0M (:accumulated (:difference t))))
        (is (true? (:ok? t)))))))

;; ============================================================================
;; HIGH-2 — a partial disposal relieves a PROPORTIONAL share
;; ============================================================================

(deftest partial-disposal-relieves-a-proportional-share-of-accumulated
  ;; €120,000 machine, 11 charges = €11,000 accumulated. Dispose 30%
  ;; of the cost (€36,000) for €33,000.
  ;;
  ;;   accumulated relieved = 11,000 × 36,000 / 120,000 =  3,300.00
  ;;   NBV disposed         = 36,000 −  3,300           = 32,700.00
  ;;   gain                 = 33,000 − 32,700           =    300.00
  ;;
  ;; Relieving the WHOLE 11,000 against 30% of the cost gave a €8,000
  ;; gain — €7,700 overstated — and drove 0299 to zero while 0210
  ;; still carried €84,000 of retained cost.
  (let [conn (bootstrap)
        _    (acquire-machine! conn "TIE-PART" 120000.00M)
        book (open-book! conn "TIE-PART")
        _    (run-to! conn book #inst "2026-12-01")
        tx-data (ap/plan-disposal
                 (d/db conn)
                 {:book book
                  :asset-account-cost 36000.00M
                  :proceeds 33000.00M
                  :proceeds-account (acct (d/db conn) "1800")
                  :gain-account (acct (d/db conn) "4900")
                  :loss-account (acct (d/db conn) "6900")
                  :journal (journal (d/db conn))
                  :date #inst "2026-12-01"
                  :posted-at #inst "2026-12-01"})]
    (testing "the entry relieves 3,300 of accumulated and yields a 300 gain"
      (is (contains? (amounts tx-data) 3300.00M))
      (is (contains? (amounts tx-data) -36000.00M))
      (is (contains? (amounts tx-data) -300.00M))
      (is (not (contains? (amounts tx-data) 11000.00M))
          "the whole contra balance must NOT come off a partial cost")
      (is (not (contains? (amounts tx-data) -8000.00M))))

    (testing "the GL keeps cost and accumulated in the same ratio"
      (post! conn tx-data)
      (is (bd= 84000.00M (gl conn "0210")) "70% of the cost is retained")
      (is (bd= -7700.00M (gl conn "0299")) "and 70% of the accumulated with it")
      (is (bd= -300.00M (gl conn "4900"))))

    (testing "an explicit :accumulated-relieved overrides the pro-rata default"
      (let [tx2 (ap/plan-disposal
                 (d/db conn)
                 {:book book
                  :asset-account-cost 12000.00M
                  :accumulated-relieved 5000.00M
                  :loss-account (acct (d/db conn) "6900")
                  :journal (journal (d/db conn))
                  :date #inst "2026-12-02"})]
        (is (contains? (amounts tx2) 5000.00M))
        (is (contains? (amounts tx2) -12000.00M))
        (is (contains? (amounts tx2) 7000.00M) "NBV 7,000 written off as a loss")))))

;; ============================================================================
;; Revaluation — the gross side of the same defect
;; ============================================================================

(deftest disposal-relieves-the-revalued-gross-cost
  ;; plan-revaluation debits 0210 and never restates
  ;; :kontor.asset/acquisition-cost, so defaulting the disposal's cost
  ;; leg to the acquisition cost strands the revaluation on the
  ;; balance sheet forever.
  (let [conn (bootstrap)
        _    (acquire-machine! conn "TIE-REV" 120000.00M)
        book (open-book! conn "TIE-REV")]
    (post! conn (ap/plan-revaluation
                 (d/db conn)
                 {:book book :amount 20000.00M
                  :revaluation-surplus-account (acct (d/db conn) "2920")
                  :journal (journal (d/db conn))
                  :date #inst "2026-06-01"
                  :posted-at #inst "2026-06-01"}))
    (asset/revalue! conn {:asset "TIE-REV"
                          :date #inst "2026-06-01"
                          :amount 20000.00M
                          :commodity (commodity (d/db conn))
                          :justification (adoc (d/db conn) "ASSET-REVAL-001")
                          :reason-note "IAS 16 revaluation model — appraisal"})

    (testing "the GL asset account carries 140,000 and so does the subledger"
      (is (bd= 140000.00M (gl conn "0210")))
      (is (bd= 140000.00M (dep/gross-carrying-amount (d/db conn) book)))
      (is (true? (:ok? (tie-out conn)))))

    (testing "a scrap with no proceeds credits the full 140,000"
      (post! conn (ap/plan-disposal
                   (d/db conn)
                   {:book book
                    :loss-account (acct (d/db conn) "6900")
                    :journal (journal (d/db conn))
                    :date #inst "2026-12-01"
                    :posted-at #inst "2026-12-01"}))
      (is (bd= 0M (gl conn "0210")) "no €20,000 revaluation stranded")
      (is (bd= 140000.00M (gl conn "6900"))))))

;; ============================================================================
;; H9 — duplicate (scheduled-date, amount) occurrences must not collapse
;; ============================================================================

(deftest roll-forward-counts-every-occurrence-not-every-distinct-amount
  ;; ADR-055 re-planning produces occurrences that share a
  ;; (scheduled-date, amount). A `:find` without `:with ?o` collapses
  ;; them into ONE tuple, so the Anlagengitter UNDERSTATES accumulated
  ;; depreciation while the GL carries both charges.
  (let [conn  (bootstrap)
        _     (acquire-machine! conn "TIE-DUP" 120000.00M)
        book  (open-book! conn "TIE-DUP")
        sched (:db/id (:kontor.asset-depreciation/schedule
                       (d/pull (d/db conn)
                               [:kontor.asset-depreciation/schedule] book)))
        eur   (commodity (d/db conn))]
    ;; Two €1,000 charges on the SAME date — posted to the GL, and
    ;; logged as two occurrences with distinct sequences.
    (doseq [seq-no [1 2]]
      (post! conn (ap/plan-depreciation-charge
                   (d/db conn)
                   {:book book :amount 1000.00M
                    :journal (journal (d/db conn))
                    :date #inst "2026-02-15"
                    :posted-at #inst "2026-02-15"}))
      (d/transact conn [{:kontor.schedule-occurrence/schedule sched
                         :kontor.schedule-occurrence/sequence seq-no
                         :kontor.schedule-occurrence/scheduled-date #inst "2026-02-15"
                         :kontor.schedule-occurrence/amount 1000.00M
                         :kontor.schedule-occurrence/commodity eur
                         :kontor.schedule-occurrence/fired-at #inst "2026-02-15"}]))
    (testing "the GL carries both charges"
      (is (bd= -2000.00M (gl conn "0299"))))
    (testing "the Anlagengitter reports both, not one"
      (let [g (first (:groups (areport/asset-roll-forward
                               (d/db conn)
                               {:from #inst "2026-01-01" :to #inst "2027-01-01"
                                :ledger (hgb (d/db conn))})))]
        (is (bd= 2000.00M (:accum-period g)))
        (is (bd= 2000.00M (:accum-closing g)))
        (is (bd= 118000.00M (:nbv-closing g)))))
    (testing "and the roll-forward ties to the control accounts"
      (let [t (tie-out conn)]
        (is (bd= 2000.00M (:accumulated (:gl t))))
        (is (bd= 2000.00M (:accumulated (:subledger t))))
        (is (true? (:ok? t)))))))

(deftest roll-forward-counts-every-event-not-every-distinct-amount
  ;; Same collapse hazard on the `:asset-event` side of the report:
  ;; two impairments booked on one date for one amount are one tuple
  ;; without `:with ?e`.
  (let [conn (bootstrap)
        _    (acquire-machine! conn "TIE-DUPEV" 120000.00M)
        book (open-book! conn "TIE-DUPEV")]
    (dotimes [_ 2]
      (post! conn (ap/plan-impairment
                   (d/db conn)
                   {:book book :amount 4000.00M
                    :impairment-expense-account (acct (d/db conn) "6230")
                    :journal (journal (d/db conn))
                    :date #inst "2026-06-30"
                    :posted-at #inst "2026-06-30"}))
      (asset/impair! conn {:asset "TIE-DUPEV"
                           :date #inst "2026-06-30"
                           :amount 4000.00M
                           :commodity (commodity (d/db conn))
                           :justification (adoc (d/db conn) "ASSET-IMPAIR-001")
                           :reason-note "two components written down"}))
    (is (bd= -8000.00M (gl conn "0299")))
    (is (bd= 8000.00M (dep/accumulated-depreciation (d/db conn) book)))
    (let [g (first (:groups (areport/asset-roll-forward
                             (d/db conn)
                             {:from #inst "2026-01-01" :to #inst "2027-01-01"
                              :ledger (hgb (d/db conn))})))]
      (is (bd= 8000.00M (:impairments g)))
      (is (bd= 8000.00M (:accum-period g))))
    (is (true? (:ok? (tie-out conn))))))

;; ============================================================================
;; asset-tie-out as a DETECTOR
;; ============================================================================

(deftest tie-out-reports-a-draft-only-depreciation-run
  ;; runner.clj's `:posted? false` leaves the entries :draft. The
  ;; subledger's occurrence log advances anyway, so the register says
  ;; 11,000 of accumulated depreciation while the posted GL says 0.
  ;; That is a real finding, and the tie-out is what states it.
  (let [conn (bootstrap)
        _    (acquire-machine! conn "TIE-DRAFT" 120000.00M)
        book (open-book! conn "TIE-DRAFT")
        run  (run-to! conn book #inst "2026-12-01" {:posted? false})]
    (is (= 11 (:count run)))
    (is (bd= 11000.00M (dep/accumulated-depreciation (d/db conn) book)))
    (is (bd= 0M (gl conn "0299")) "nothing is POSTED")
    (let [t (tie-out conn)]
      (is (false? (:ok? t)))
      (is (bd= 0M (:cost (:difference t))))
      (is (bd= 11000.00M (:accumulated (:difference t)))))
    (testing "including drafts closes it — the entries exist, unsealed"
      (let [t (tie-out conn {:include-states #{:posted :draft}})]
        (is (true? (:ok? t)))
        (is (bd= 11000.00M (:accumulated (:gl t))))))))

(deftest tie-out-reports-an-event-that-was-recorded-but-never-posted
  ;; The roll-forward counts every value-moving :asset-event, because
  ;; the same figures drive plan-disposal and a disposal that ignored
  ;; them would under-relieve. The cost of that choice is that a
  ;; recorded-but-unposted event makes the Anlagengitter disagree with
  ;; the balance sheet — which is exactly what asset-tie-out is for.
  (let [conn (bootstrap)
        _    (acquire-machine! conn "TIE-GHOST" 100000.00M)
        _    (open-book! conn "TIE-GHOST")]
    ;; Events only — no plan-impairment / plan-revaluation entry.
    (asset/impair! conn {:asset "TIE-GHOST" :date #inst "2026-06-01"
                         :amount 12000.00M :commodity (commodity (d/db conn))
                         :justification (adoc (d/db conn) "ASSET-IMPAIR-001")
                         :reason-note "impairment test"})
    (asset/revalue! conn {:asset "TIE-GHOST" :date #inst "2026-09-01"
                          :amount 5000.00M :commodity (commodity (d/db conn))
                          :justification (adoc (d/db conn) "ASSET-REVAL-001")
                          :reason-note "appraisal"})
    (let [g (first (:groups (areport/asset-roll-forward
                             (d/db conn)
                             {:from #inst "2026-01-01" :to #inst "2027-01-01"
                              :ledger (hgb (d/db conn))})))
          t (tie-out conn)]
      (testing "the register reports the events"
        (is (bd= 105000.00M (:cost-additions g)))
        (is (bd= 12000.00M (:impairments g))))
      (testing "the GL does not — and the tie-out says so, to the cent"
        (is (bd= 100000.00M (:cost (:gl t))))
        (is (bd= 0M (:accumulated (:gl t))))
        (is (bd= 5000.00M (:cost (:difference t))))
        (is (bd= 12000.00M (:accumulated (:difference t))))
        (is (false? (:ok? t)))))))

(deftest tie-out-requires-its-scope
  (let [conn (bootstrap)
        db (d/db conn)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":ledger required"
                          (areport/asset-tie-out conn {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":asset-account required"
                          (areport/asset-tie-out conn {:ledger (hgb db)})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":accumulated-account required"
                          (areport/asset-tie-out conn {:ledger (hgb db)
                                                       :asset-account (acct db "0210")})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":commodity required"
                          (areport/asset-tie-out
                           conn {:ledger (hgb db)
                                 :asset-account (acct db "0210")
                                 :accumulated-account (acct db "0299")})))))
