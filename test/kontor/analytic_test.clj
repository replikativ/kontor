(ns kontor.analytic-test
  "ADR-022 / ADR-140 — analytic distributions actually enforced, and actually
   reachable from the read side.

   Three promises were made and none was kept:

     - `:kontor.analytic-distribution/percent`'s `:db/doc` said sum-to-100 was
       \"enforced by the report engine\";
     - `:kontor.account/required-analytic-plans`'s `:db/doc` said \"the posting
       validator enforces a sum-to-100 invariant per named plan\";
     - `kontor.posting/expand-distribution`'s docstring said the check was
       `kontor.posting/validate`'s job.

   The validator contained no occurrence of \"analytic\", nothing read
   `required-analytic-plans`, and `kontor.reporting.report` did not pull the
   distributions at all — so a 60/30 cost-centre split committed cleanly and
   every management report that should have shown it showed nothing.

   Each deftest here pairs the REFUSAL with the shape that must still be
   accepted, so the invariant cannot be satisfied by a validator that rejects
   everything."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.core :as dc]
            [kontor.analytic :as analytic]
            [kontor.core :as core]
            [kontor.gate :as gate]
            [kontor.governance :as gov]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.posting.validate :as pv]
            [kontor.reporting.report :as report]))

(def ^:private eur  [:kontor.commodity/symbol "EUR"])
(def ^:private gen  [:kontor.journal/code "GEN"])
(def ^:private cash [:kontor.account/path "Assets:Cash"])
(def ^:private rent [:kontor.account/path "Expenses:Rent"])
(def ^:private plan [:kontor.analytic-plan/code "cost-center"])
(def ^:private cc-a [:kontor.analytic-account/path "cost-center:CC-A"])
(def ^:private cc-b [:kontor.analytic-account/path "cost-center:CC-B"])
(def ^:private d1 #inst "2026-03-15")

(defn- book
  "Fresh book with a two-account chart and two cost centres under the
   kernel-seeded `cost-center` plan. `require-plan?` marks Expenses:Rent with
   `:kontor.account/required-analytic-plans #{cost-center}`."
  [require-plan?]
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2}
                 {:kontor.journal/code "GEN" :kontor.journal/type :general}
                 {:kontor.account/path "Assets:Cash" :kontor.account/code "1000"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:kontor.analytic-account/path "cost-center:CC-A" :kontor.analytic-account/code "CC-A" :kontor.analytic-account/name "Alpha"
                  :kontor.analytic-account/plan plan :kontor.analytic-account/active true}
                 {:kontor.analytic-account/path "cost-center:CC-B" :kontor.analytic-account/code "CC-B" :kontor.analytic-account/name "Beta"
                  :kontor.analytic-account/plan plan :kontor.analytic-account/active true}])
    (d/transact conn
                [(cond-> {:kontor.account/path "Expenses:Rent" :kontor.account/code "6000"
                          :kontor.account/type :expense :kontor.account/active true}
                   require-plan?
                   (assoc :kontor.account/required-analytic-plans [plan]))])
    conn))

(defn- dist
  "One `:kontor.analytic-distribution` entry under the cost-center plan."
  [analytic-account percent]
  {:kontor.analytic-distribution/plan plan
   :kontor.analytic-distribution/account analytic-account
   :kontor.analytic-distribution/percent percent})

(defn- draft
  "A balanced two-leg draft; `dists` (may be nil) rides on the expense leg."
  [dists]
  {:transaction {:kontor.transaction/journal gen
                 :kontor.transaction/effective-date d1
                 :kontor.transaction/narration "Rent"}
   :postings [(cond-> {:kontor.posting/account rent
                       :kontor.posting/amount 1000M
                       :kontor.posting/commodity eur}
                dists (assoc :kontor.posting/analytic-distributions dists))
              {:kontor.posting/account cash
               :kontor.posting/amount -1000M
               :kontor.posting/commodity eur}]})

(defn- errors-of [dists]
  (into #{} (map :error) (:errors (pv/validate (draft dists)))))

;; ============================================================================
;; 1. The pure layer — no db, so it runs client-side too
;; ============================================================================

(deftest pure-validator-enforces-sum-to-100-per-plan
  (testing "a 60/30 split (90%) is refused, and the error names plan and total"
    (let [{:keys [ok? errors]} (pv/validate (draft [(dist cc-a 60M) (dist cc-b 30M)]))
          e (first (filter #(= :analytic-distribution-not-100 (:error %)) errors))]
      (is (false? ok?))
      (is (some? e))
      (is (= plan (:plan e)) "the operator is told WHICH plan is short")
      (is (zero? (money/compare-amounts (money/->amount 90M) (:total e)))
          "and the actual total, not just that it is wrong")
      (is (re-find #"90" (:message e)))))
  ;; TEETH: the same validator must accept the shape it is meant to allow,
  ;; otherwise "it throws" is satisfied by a validator that always throws.
  (testing "a 60/40 split is accepted"
    (is (:ok? (pv/validate (draft [(dist cc-a 60M) (dist cc-b 40M)])))))
  (testing "a single 100% distribution is accepted"
    (is (:ok? (pv/validate (draft [(dist cc-a 100M)])))))
  (testing "no distribution at all is accepted — distributions are optional"
    (is (:ok? (pv/validate (draft nil)))))
  (testing "a 60/50 OVER-split (110%) is refused too, not only a shortfall"
    (is (contains? (errors-of [(dist cc-a 60M) (dist cc-b 50M)])
                   :analytic-distribution-not-100)))
  (testing "fractional percents that do total 100 are accepted"
    (is (:ok? (pv/validate (draft [(dist cc-a 33.33M) (dist cc-b 66.67M)])))))
  (testing "and fractional percents one cent short are refused"
    (is (contains? (errors-of [(dist cc-a 33.33M) (dist cc-b 66.66M)])
                   :analytic-distribution-not-100))))

(deftest pure-validator-enforces-the-0-100-range
  (testing "a percent above 100 is refused even when the plan totals 100"
    (is (contains? (errors-of [(dist cc-a 120M) (dist cc-b -20M)])
                   :analytic-percent-out-of-range)))
  (testing "a negative percent is refused"
    (is (contains? (errors-of [(dist cc-a 110M) (dist cc-b -10M)])
                   :analytic-percent-out-of-range)))
  (testing "0 and 100 are both inside the range"
    (is (empty? (filter #{:analytic-percent-out-of-range}
                        (errors-of [(dist cc-a 100M) (dist cc-b 0M)]))))))

(deftest two-plans-are-judged-independently
  ;; A posting may distribute under several plans at once; a complete
  ;; cost-centre split must not be excused by an incomplete project split.
  (let [project {:kontor.analytic-plan/code "project"
                 :kontor.analytic-plan/name "Projects"
                 :kontor.analytic-plan/active true}
        pref [:kontor.analytic-plan/code "project"]
        both [(dist cc-a 60M) (dist cc-b 40M)
              {:kontor.analytic-distribution/plan pref
               :kontor.analytic-distribution/account cc-a
               :kontor.analytic-distribution/percent 70M}]]
    (is (some? project))
    (testing "cost-center totals 100, project totals 70 — refused, naming project"
      (let [e (->> (:errors (pv/validate (draft both)))
                   (filter #(= :analytic-distribution-not-100 (:error %)))
                   first)]
        (is (= pref (:plan e)))))
    (testing "both complete — accepted"
      (is (:ok? (pv/validate (draft (conj (vec (take 2 both))
                                          (assoc (nth both 2)
                                                 :kontor.analytic-distribution/percent 100M)))))))))

(deftest build-transaction-refuses-the-partial-split
  ;; `expand-distribution`'s docstring promises the check happens "at posting
  ;; time"; the builder is posting time.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"structural validation"
                        (posting/build-transaction (draft [(dist cc-a 60M) (dist cc-b 30M)]))))
  (is (vector? (posting/build-transaction (draft [(dist cc-a 60M) (dist cc-b 40M)])))))

;; ============================================================================
;; 2. required-analytic-plans — the db half, in the gate
;; ============================================================================

(deftest gate-refuses-a-posting-that-omits-a-required-plan
  (let [conn (book true)]
    (testing "no distribution at all against an account that requires the plan"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)required plan"
           (gate/transact-with-validation conn (posting/build-transaction (draft nil))))))
    (testing "the violation names the plan and reports a nil total (= absent)"
      (let [vs (analytic/find-violations @conn (posting/build-transaction (draft nil)))
            plan-eid (:db/id (d/pull @conn [:db/id] plan))]
        (is (= 1 (count vs)) "only the posting against the requiring account")
        (is (= {plan-eid nil} (:missing-plans (first vs))))))
    ;; TEETH: a complete split against the SAME account must commit, and the
    ;; other leg — against an account with no required plans — must not be
    ;; dragged in.
    (testing "a 60/40 split against the same account commits"
      (is (some? (gate/transact-with-validation
                  conn (posting/build-transaction
                        (draft [(dist cc-a 60M) (dist cc-b 40M)]))))))))

(deftest an-account-without-required-plans-needs-no-distribution
  ;; The check must not become "every posting needs analytics" — that would
  ;; make every existing book unwritable.
  (let [conn (book false)]
    (is (some? (gate/transact-with-validation conn (posting/build-transaction (draft nil)))))
    (is (empty? (analytic/find-violations @conn (posting/build-transaction (draft nil)))))))

(deftest required-plans-resolves-lookup-refs-and-eids-alike
  (let [conn (book true)
        db @conn
        plan-eid (:db/id (d/pull db [:db/id] plan))]
    (is (= #{plan-eid} (analytic/required-plans db rent)))
    (is (= #{plan-eid} (analytic/required-plans db (:db/id (d/pull db [:db/id] rent)))))
    (is (= #{} (analytic/required-plans db cash)))
    (testing "an unresolvable account ref is not an analytic violation"
      (is (= #{} (analytic/required-plans db [:kontor.account/path "Nope"]))))))

;; ============================================================================
;; 3. The governor — the seam a raw d/transact cannot route around
;; ============================================================================

(defn- outcome
  "Resolve `tx-data` against `conn` with `dc/with` (exactly the shape a
   `datahike.tx-preds` tx-pred receives) and run the governor. Deliberately
   NOT through the gate: this is the mandatory writer-side path."
  [conn tx-data]
  (try (gov/validate-report (dc/with @conn tx-data)) :accepted
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- raw-tx-data
  "Hand-built tx-data — no builder, no validator, no gate."
  [conn dists]
  (let [db @conn
        e #(:db/id (d/pull db [:db/id] %))]
    (cond-> [{:db/id -1 :kontor.transaction/journal (e gen)
              :kontor.transaction/effective-date d1
              :kontor.transaction/state :draft}
             {:db/id -2 :kontor.posting/transaction -1 :kontor.posting/account (e rent)
              :kontor.posting/amount 1000M :kontor.posting/commodity (e eur)}
             {:db/id -3 :kontor.posting/transaction -1 :kontor.posting/account (e cash)
              :kontor.posting/amount -1000M :kontor.posting/commodity (e eur)}]
      dists (assoc-in [1 :kontor.posting/analytic-distributions]
                      (mapv (fn [[acct pct]]
                              {:kontor.analytic-distribution/plan (e plan)
                               :kontor.analytic-distribution/account (e acct)
                               :kontor.analytic-distribution/percent pct})
                            dists)))))

(deftest governor-refuses-what-the-gate-would-have-refused
  (let [conn (book true)]
    (testing "raw d/transact shape with no distribution — refused in the writer"
      (is (= :kontor.analytic/required-plan-unsatisfied
             (outcome conn (raw-tx-data conn nil)))))
    (testing "raw shape with a 60/30 split — refused"
      (is (= :kontor.analytic/required-plan-unsatisfied
             (outcome conn (raw-tx-data conn [[cc-a 60M] [cc-b 30M]])))))
    ;; TEETH
    (testing "raw shape with a 60/40 split — accepted"
      (is (= :accepted (outcome conn (raw-tx-data conn [[cc-a 60M] [cc-b 40M]])))))))

(deftest governor-catches-a-bad-total-even-when-no-plan-is-required
  ;; `:kontor.analytic-distribution/percent`'s own doc promises sum-to-100
  ;; unconditionally — not only for plans an account requires.
  (let [conn (book false)]
    (is (= :kontor.analytic/required-plan-unsatisfied
           (outcome conn (raw-tx-data conn [[cc-a 60M] [cc-b 30M]]))))
    (is (= :accepted (outcome conn (raw-tx-data conn [[cc-a 60M] [cc-b 40M]]))))
    (is (= :accepted (outcome conn (raw-tx-data conn nil))))))

;; ============================================================================
;; 4. The read side — a percentage-split cost-centre P&L is reachable
;; ============================================================================

(defn- posted-split
  "Post 1000 rent split 60/40 across CC-A / CC-B and return the conn."
  []
  (let [conn (book false)]
    (posting/post-transaction! conn (draft [(dist cc-a 60M) (dist cc-b 40M)]))
    conn))

(deftest marginalize-over-an-analytic-plan-apportions-by-percent
  (let [conn (posted-split)
        ps (report/report-postings conn {})
        by-cc (report/marginalize ps {:analytic-plan "cost-center" :by :code}
                                  {:commodity :EUR})
        amt #(some-> (get by-cc %) :value :amount)]
    (testing "the distributions are visible to the read side at all"
      (is (seq (filter (comp seq :analytics) ps))
          "pull-posting omitted :kontor.posting/analytic-distributions entirely
           until ADR-140, which made a cost-centre report unreachable"))
    (testing "600 / 400, not 1000 / 1000"
      (is (== 600M (amt "CC-A")))
      (is (== 400M (amt "CC-B"))))
    ;; TEETH: set-valued treatment (the ADR-097 `:posting-dimension` semantic)
    ;; would report the FULL 1000 under each class. Assert the classes sum back
    ;; to the posting, which only a weighted fold can do.
    (testing "the classes sum back to the undistributed total"
      (is (== 1000M (+ (amt "CC-A") (amt "CC-B")))))
    (testing "the untagged cash leg lands under class nil, not silently dropped"
      (is (== -1000M (some-> (get by-cc nil) :value :amount))))))

(deftest the-dimension-engine-reports-one-cost-centre
  (let [conn (posted-split)
        line (fn [cc] (-> (report/compute-report
                           conn {:report/name "CC" :report/lines
                                 [{:line/code "1" :line/label "cc"
                                   :line/expression {:engine :dimension
                                                     :dimension {:analytic-plan "cost-center"
                                                                 :by :code}
                                                     :match cc}}]}
                           {:include-states #{:posted}})
                          :report/lines first :line/value :amount))]
    (is (== 600M (line "CC-A")))
    (is (== 400M (line "CC-B")))
    ;; TEETH: a cost centre with no share must report zero, not the whole line.
    (is (== 0M (line "CC-NOPE")))))

(deftest analytic-axis-resolves-a-plan-by-eid-and-a-class-by-path
  (let [conn (posted-split)
        db @conn
        ps (report/report-postings conn {})
        plan-eid (:db/id (d/pull db [:db/id] plan))]
    (testing "plan named by eid"
      (is (== 600M (-> (report/marginalize ps {:analytic-plan plan-eid :by :code})
                       (get "CC-A") :value :amount))))
    (testing ":by :eid keys by the analytic-account eid"
      (let [cc-a-eid (:db/id (d/pull db [:db/id] cc-a))]
        (is (== 600M (-> (report/marginalize ps {:analytic-plan plan-eid})
                         (get cc-a-eid) :value :amount)))))
    (testing "an unknown :by is refused rather than silently defaulted"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":by"
                            (report/marginalize ps {:analytic-plan plan-eid :by :bogus}))))))

;; ============================================================================
;; 5. Partial schemas — the enforcement must not fire on a db that does no
;;    cost accounting at all
;;
;; kontor's schema is a MENU: ADR-002 has the kernel cohabiting with consumer
;; apps in one connection, `install-schema!` installs attribute groups, and the
;; cljs `node-test` lane deliberately hand-writes a 7-attribute schema. Because
;; `d/pull` of an attribute absent from the schema THROWS `:transact/schema`
;; rather than returning nil, and because the governor is a `tx-pred` that runs
;; on EVERY commit, an unconditional analytic pull meant NO db without the
;; analytic attrs could transact anything at all. CI caught it; these tests pin
;; it on the JVM side too.
;;
;; This is the hazard ADR-140 named one layer further out — "an enforcement
;; that fires on correct usage is worse than the defect it fixes" — and it is
;; the same shape as the `:analytic-distributions`-unpostable-through-book
;; problem, just at the schema level instead of the builder level.
;; ============================================================================

(def ^:private minimal-schema
  "The identity attrs plus what the structural validators query — and NOTHING
   analytic. Mirrors `modules/substrate/test/kontor/validation_cljs_test.cljs`,
   which is the schema the cljs lane actually runs."
  [{:db/ident :kontor.account/path :db/valueType :db.type/string
    :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/type :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/active :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.commodity/symbol :db/valueType :db.type/string
    :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.journal/code :db/valueType :db.type/string
    :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.journal/type :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/state :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/journal :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/transaction :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/account :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/amount :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/commodity :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/display-type :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(defn- minimal-book
  "A db with `minimal-schema` ONLY — no `:kontor.posting/analytic-distributions`,
   no `:kontor.account/required-analytic-plans`, no analytic-plan attrs."
  []
  (let [cfg  {:store {:backend :memory :id (random-uuid)}
              :schema-flexibility :write :keep-history? true}
        _    (d/create-database cfg)
        conn (d/connect cfg)]
    (d/transact conn minimal-schema)
    (d/transact conn [{:kontor.commodity/symbol "EUR"}
                      {:kontor.journal/code "GEN" :kontor.journal/type :general}
                      {:kontor.account/path "Assets:Cash" :kontor.account/type :asset
                       :kontor.account/active true}
                      {:kontor.account/path "Income:Sales" :kontor.account/type :income
                       :kontor.account/active true}])
    conn))

(defn- minimal-entry
  [db]
  (let [e #(:db/id (d/pull db [:db/id] %))]
    [{:db/id -1 :kontor.transaction/journal (e gen)
      :kontor.transaction/state :draft}
     {:db/id -2 :kontor.posting/transaction -1
      :kontor.posting/account (e cash) :kontor.posting/amount 100M
      :kontor.posting/commodity (e eur) :kontor.posting/display-type :product}
     {:db/id -3 :kontor.posting/transaction -1
      :kontor.posting/account (e [:kontor.account/path "Income:Sales"])
      :kontor.posting/amount -100M
      :kontor.posting/commodity (e eur) :kontor.posting/display-type :product}]))

(deftest schema-predicates-report-what-is-installed
  (testing "the full kernel schema has both analytic attrs"
    (let [db @(book false)]
      (is (true? (analytic/required-plans-installed? db)))
      (is (true? (analytic/distributions-installed? db)))))
  ;; TEETH: the predicate must actually discriminate, or the guards below are
  ;; vacuously satisfied and would not have caught the CI failure.
  (testing "the minimal schema has neither"
    (let [db @(minimal-book)]
      (is (false? (analytic/required-plans-installed? db)))
      (is (false? (analytic/distributions-installed? db))))))

(deftest schema-predicates-see-through-an-as-of-wrapper
  ;; The subtle half, and the one that nearly shipped a permanently-disabled
  ;; feature. `d/as-of` returns a `datahike.db.AsOfDB` with NO `:schema` field,
  ;; so the obvious `(contains? (:schema db) attr)` reads nil — on the JVM as
  ;; well as in cljs. `report-postings` ALWAYS reads through an as-of db, so a
  ;; field-read guard would have reported "no analytic schema" for every report
  ;; on every db, silently turning the guard into a kill switch for the very
  ;; feature it exists to scope. Hence `dbi/-schema`, the protocol method.
  (let [db   @(book false)
        aod  (d/as-of db (java.util.Date.))
        hist (d/history db)]
    (is (nil? (:schema aod))
        "the field really is absent on the wrapper — this is why the naive
         guard fails, and if datahike ever adds it this test documents the
         change rather than hiding it")
    (is (true? (analytic/distributions-installed? aod))
        "but the protocol method sees through the as-of wrapper")
    (is (true? (analytic/required-plans-installed? aod)))
    (is (true? (analytic/distributions-installed? hist))
        "and through the history wrapper")
    ;; TEETH: still discriminates through the wrapper — not just "always true".
    (let [mad (d/as-of @(minimal-book) (java.util.Date.))]
      (is (false? (analytic/distributions-installed? mad))))))

(deftest a-plain-entry-commits-on-a-db-with-no-analytic-schema
  ;; This is the case CI caught: nothing in the JVM suite covered it, because
  ;; every JVM fixture installs the full kernel schema.
  (let [conn (minimal-book)]
    (testing "the governor accepts it rather than throwing :transact/schema"
      (is (= :accepted (outcome conn (minimal-entry @conn)))))
    (testing "and each analytic predicate is individually a no-op"
      (let [rep (dc/with @conn (minimal-entry @conn))]
        (is (= [] (gov/analytic-violations rep)))
        (is (nil? (gov/validate-report rep)))))
    (testing "the pre-resolution gate pass is a no-op too"
      (is (= [] (analytic/find-violations @conn (minimal-entry @conn))))
      (is (nil? (analytic/assert-required-analytic-plans!
                 @conn (minimal-entry @conn)))))
    (testing "and the write actually lands"
      (is (some? (gate/transact-with-validation conn (minimal-entry @conn)))))))

(deftest required-plans-is-empty-not-throwing-on-a-partial-schema
  (let [db @(minimal-book)]
    (is (= #{} (analytic/required-plans db cash)))
    (is (= #{} (analytic/required-plans db [:kontor.account/path "Income:Sales"])))))

(deftest the-guard-scopes-the-check-it-does-not-weaken-it
  ;; The whole point: on a db that HAS the analytic schema, every refusal from
  ;; §2/§3 above must still fire. Without this, "make it schema-aware" could be
  ;; satisfied by disabling the check outright.
  (let [conn (book true)]
    (is (= :kontor.analytic/required-plan-unsatisfied
           (outcome conn (raw-tx-data conn nil))))
    (is (= :kontor.analytic/required-plan-unsatisfied
           (outcome conn (raw-tx-data conn [[cc-a 60M] [cc-b 30M]]))))
    (is (= :accepted (outcome conn (raw-tx-data conn [[cc-a 60M] [cc-b 40M]]))))))
