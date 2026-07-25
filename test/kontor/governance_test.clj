(ns kontor.governance-test
  "The report-based governor (`kontor.governance/validate-report`) — the
   post-resolution realization of the gate for governed stores (ADR-118 /
   research note 193). Reports are built with `datahike.core/with` (exactly the
   resolved shape a `datahike.tx-preds` tx-pred receives) and run through the
   governor: the full red-team battery must be REJECTED, legitimate writes
   ACCEPTED."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.core :as dc]
            [kontor.compliance.legal-hold :as lhold]
            [kontor.compliance.period :as period]
            [kontor.core :as core]
            [kontor.gate :as gate]
            [kontor.book.build :as build]
            [kontor.governance :as gov]))

(def ^:private eur  [:kontor.commodity/symbol "EUR"])
(def ^:private gen  [:kontor.journal/code "GEN"])
(def ^:private cash [:kontor.account/path "Assets:Cash"])
(def ^:private rev  [:kontor.account/path "Income:Sales"])
(def ^:private arch [:kontor.account/path "Archived"])
(def ^:private d1 #inst "2026-03-15")

(defn- setup
  "Fresh governed-style book with one POSTED balanced tx; returns {:conn :pd :pc}
   (the posted debit/credit posting eids)."
  []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:kontor.journal/code "GEN" :kontor.journal/type :general}
                 {:kontor.account/path "Assets:Cash"  :kontor.account/type :asset  :kontor.account/active true}
                 {:kontor.account/path "Income:Sales" :kontor.account/type :income :kontor.account/active true}
                 {:kontor.account/path "Archived"     :kontor.account/type :asset  :kontor.account/active false}
                 ;; actors + supporting doc for the legal-hold family (two
                 ;; distinct actors — ADR-038 :no-self-approval blocks release
                 ;; by the issuer)
                 {:kontor.partner/external-id "U-counsel" :kontor.partner/name "Counsel C"}
                 {:kontor.partner/external-id "U-admin" :kontor.partner/name "Admin A"}
                 {:kontor.audit-doc/code "DOC-HOLD" :kontor.audit-doc/type :legal-hold-order
                  :kontor.audit-doc/uploaded-at #inst "2026-03-01"}])
    (gate/transact-with-validation conn
                                   (build/entry-tx-data {:debit-account cash :credit-account rev :amount 1000
                                                         :commodity eur :journal gen :effective-date d1}))
    {:conn conn
     :pd (d/q '[:find ?p . :where [?p :kontor.posting/account ?a]
                [?a :kontor.account/path "Assets:Cash"]] @conn)
     :pc (d/q '[:find ?p . :where [?p :kontor.posting/account ?a]
                [?a :kontor.account/path "Income:Sales"]] @conn)}))

(defn- outcome
  "Build the resolved report for `txf`'s tx-data and run the governor; returns
   :accepted or the rejection `:type`."
  [txf]
  (let [{:keys [conn] :as s} (setup)]
    (try (gov/validate-report (dc/with @conn (txf s))) :accepted
         (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))

(deftest rejects-retract-of-posted-leg
  (is (= :sealing/silent-retract-of-posted
         (outcome (fn [{:keys [pd]}] [[:db/retract pd :kontor.posting/amount 1000M]])))))

(deftest rejects-retract-entity-of-posted
  ;; the A2 vector — retractEntity of a posted posting (report-level, via db-before)
  (is (= :sealing/silent-retract-of-posted
         (outcome (fn [{:keys [pd]}] [[:db/retractEntity pd]])))))

(deftest rejects-in-place-edit-of-posted
  ;; the A4 vector — an upsert that rewrites a posted amount
  (is (contains? #{:sealing/silent-retract-of-posted :validation/sum-to-zero}
                 (outcome (fn [{:keys [pd]}] [{:db/id pd :kontor.posting/amount 9999M}])))))

(deftest rejects-retract-both-legs
  ;; A7 — balance stays 0, only the sealing scan catches the audit destruction
  (is (= :sealing/silent-retract-of-posted
         (outcome (fn [{:keys [pd pc]}] [[:db/retractEntity pd] [:db/retractEntity pc]])))))

(deftest rejects-unbalanced-new-tx
  (is (= :validation/sum-to-zero
         (outcome (fn [_]
                    [{:db/id -1 :kontor.transaction/journal gen
                      :kontor.transaction/effective-date d1 :kontor.transaction/state :draft}
                     {:db/id -2 :kontor.posting/transaction -1 :kontor.posting/account cash
                      :kontor.posting/amount 5M :kontor.posting/commodity eur :kontor.posting/display-type :product}
                     {:db/id -3 :kontor.posting/transaction -1 :kontor.posting/account rev
                      :kontor.posting/amount -4M :kontor.posting/commodity eur :kontor.posting/display-type :product}])))))

(deftest rejects-posting-to-inactive-account
  ;; the datalog invariant tier (account-active) fires on the resolved report
  (is (= :invariant/invariant-mismatch
         (outcome (fn [_] (build/entry-tx-data {:debit-account arch :credit-account rev :amount 50
                                                :commodity eur :journal gen :effective-date d1}))))))

(deftest accepts-legitimate-writes
  (testing "a new balanced tx"
    (is (= :accepted
           (outcome (fn [_] (build/entry-tx-data {:debit-account cash :credit-account rev :amount 50
                                                  :commodity eur :journal gen :effective-date d1}))))))
  (testing "re-asserting the SAME value on a posted row is a no-op (no false positive)"
    (is (= :accepted
           (outcome (fn [{:keys [pd]}] [{:db/id pd :kontor.posting/amount 1000M}]))))))

(deftest short-circuits-source-construction-when-nothing-is-keyed
  ;; `invariant-violations` resolves WHICH invariants apply before building the
  ;; sources they need. That ordering is load-bearing: `report-empty+txs` builds
  ;; an empty db over the store's ENTIRE schema, so it scales with the schema
  ;; rather than the delta, and `validate-report` runs in the writer on every
  ;; committed transaction — including writes from a co-tenant (chat, wiki) that
  ;; keys no invariant at all. Asserted structurally rather than by timing, which
  ;; would be flaky, and here rather than nowhere, because reverting the ordering
  ;; keeps every behavioural test in this namespace green.
  (let [{:keys [conn]} (setup)
        ;; opening an account touches no attribute any invariant is keyed on
        ;; (the kernel keys :kontor.posting/account + :kontor.posting/commodity)
        unkeyed (dc/with @conn [{:kontor.account/path "Assets:Bank"
                                 :kontor.account/type :asset
                                 :kontor.account/active true}])
        keyed   (dc/with @conn (build/entry-tx-data {:debit-account cash :credit-account rev :amount 12
                                                     :commodity eur :journal gen :effective-date d1}))
        calls   (atom 0)
        real    @#'gov/report-empty+txs]
    (with-redefs [gov/report-empty+txs (fn [r] (swap! calls inc) (real r))]
      (testing "no keyed attribute — the sources are never built"
        (is (= [] (gov/invariant-violations unkeyed)))
        (is (zero? @calls)))
      (testing "a keyed attribute — the sources ARE built, exactly once"
        (is (= [] (gov/invariant-violations keyed)))
        (is (= 1 @calls))))))

(deftest violation-fns-are-pure-over-the-report
  (let [{:keys [conn]} (setup)
        good (dc/with @conn (build/entry-tx-data {:debit-account cash :credit-account rev :amount 7
                                                  :commodity eur :journal gen :effective-date d1}))]
    (is (= [] (gov/balance-violations good)))
    (is (= [] (gov/sealing-violations good)))
    (is (= [] (gov/invariant-violations good)))
    (is (= [] (gov/period-violations good)))
    (is (= [] (gov/sealed-period-violations good)))
    (is (= [] (gov/hold-violations good)))
    (is (= [] (gov/state-machine-violations good)))
    (is (= [] (gov/analytic-violations good)))
    (is (nil? (gov/validate-report good)))))

;; ============================================================================
;; balance grouped per (entity, ledger, commodity) — ADR-140
;;
;; The governor used to group by COMMODITY ALONE while `schema.cljc` documents
;; the invariant as "enforced PER LEDGER" and the build-time validator enforces
;; per (entity, ledger, commodity). The MANDATORY guard was therefore weaker
;; than the bypassable one it exists to backstop: a raw `d/transact` splitting
;; a transaction across two parallel books netted to zero and committed,
;; leaving each book off by the full amount.
;; ============================================================================

(defn- setup+
  "`setup` plus a second (IFRS) ledger and a second entity, so the parallel-book
   and multi-entity groupings can actually be exercised."
  []
  (let [{:keys [conn] :as s} (setup)]
    (d/transact conn
                [{:kontor.ledger/code "ifrs" :kontor.ledger/name "IFRS"
                  :kontor.ledger/type :secondary :kontor.ledger/active true}
                 {:kontor.entity/code "acme-de" :kontor.entity/name "Acme DE"}
                 {:kontor.entity/code "acme-fr" :kontor.entity/name "Acme FR"}])
    (assoc s :db @conn)))

(defn- e [db ref] (:db/id (d/pull db [:db/id] ref)))

(defn- two-leg
  "Raw (no builder, no gate) balanced-by-amount two-leg tx-data; `extra-d` /
   `extra-c` are merged onto the debit / credit posting so a test can put the
   legs in different ledgers or entities."
  [db extra-d extra-c]
  [{:db/id -1 :kontor.transaction/journal (e db gen)
    :kontor.transaction/effective-date d1 :kontor.transaction/state :draft}
   (merge {:db/id -2 :kontor.posting/transaction -1 :kontor.posting/account (e db cash)
           :kontor.posting/amount 100M :kontor.posting/commodity (e db eur)
           :kontor.posting/display-type :product}
          extra-d)
   (merge {:db/id -3 :kontor.posting/transaction -1 :kontor.posting/account (e db rev)
           :kontor.posting/amount -100M :kontor.posting/commodity (e db eur)
           :kontor.posting/display-type :product}
          extra-c)])

(deftest balance-is-grouped-per-ledger
  (let [{:keys [conn db]} (setup+)
        ifrs (e db [:kontor.ledger/code "ifrs"])
        prim (e db [:kontor.ledger/code "primary"])
        out  (fn [td] (try (gov/validate-report (dc/with @conn td)) :accepted
                           (catch clojure.lang.ExceptionInfo ex (:type (ex-data ex)))))]
    (testing "+100 on primary against −100 on IFRS is REFUSED"
      (is (= :validation/sum-to-zero
             (out (two-leg db {:kontor.posting/ledger prim} {:kontor.posting/ledger ifrs})))))
    (testing "the violation names the ledger that is out, not just the commodity"
      (let [vs (gov/balance-violations
                (dc/with @conn (two-leg db {:kontor.posting/ledger prim}
                                        {:kontor.posting/ledger ifrs})))]
        (is (= 2 (count vs)) "both books are out, so both are reported")
        (is (= #{prim ifrs} (into #{} (map :ledger) vs)))))
    ;; TEETH: the refinement must not reject what genuinely balances.
    (testing "both legs on IFRS is ACCEPTED"
      (is (= :accepted
             (out (two-leg db {:kontor.posting/ledger ifrs} {:kontor.posting/ledger ifrs})))))
    (testing "both legs untagged (the primary book, nil group) is ACCEPTED"
      (is (= :accepted (out (two-leg db nil nil)))))
    (testing "a four-leg tx balancing WITHIN each of two ledgers is ACCEPTED"
      (is (= :accepted
             (out (conj (two-leg db {:kontor.posting/ledger prim} {:kontor.posting/ledger prim})
                        {:db/id -4 :kontor.posting/transaction -1
                         :kontor.posting/account (e db cash) :kontor.posting/amount 40M
                         :kontor.posting/commodity (e db eur) :kontor.posting/ledger ifrs
                         :kontor.posting/display-type :product}
                        {:db/id -5 :kontor.posting/transaction -1
                         :kontor.posting/account (e db rev) :kontor.posting/amount -40M
                         :kontor.posting/commodity (e db eur) :kontor.posting/ledger ifrs
                         :kontor.posting/display-type :product})))))))

(deftest balance-is-grouped-per-entity
  (let [{:keys [conn db]} (setup+)
        de (e db [:kontor.entity/code "acme-de"])
        fr (e db [:kontor.entity/code "acme-fr"])
        out (fn [td] (try (gov/validate-report (dc/with @conn td)) :accepted
                          (catch clojure.lang.ExceptionInfo ex (:type (ex-data ex)))))]
    (testing "+100 booked to DE against −100 booked to FR is REFUSED (ADR-031)"
      (is (= :validation/sum-to-zero
             (out (two-leg db {:kontor.posting/entity de} {:kontor.posting/entity fr})))))
    (testing "both legs in DE is ACCEPTED"
      (is (= :accepted
             (out (two-leg db {:kontor.posting/entity de} {:kontor.posting/entity de})))))))

(deftest balance-ignores-section-and-note-display-types
  ;; The build validator excludes `:section` / `:note` from the sum
  ;; (`posting.validate/balance-affecting?`). If the governor did not, a
  ;; well-formed entry carrying a UI header line with a stray amount would pass
  ;; the gate and then be rejected by the writer — the two seams disagreeing.
  (let [{:keys [conn db]} (setup+)
        out (fn [td] (try (gov/validate-report (dc/with @conn td)) :accepted
                          (catch clojure.lang.ExceptionInfo ex (:type (ex-data ex)))))]
    (testing "a :note line with a non-zero amount does not unbalance the tx"
      (is (= :accepted
             (out (conj (two-leg db nil nil)
                        {:db/id -9 :kontor.posting/transaction -1
                         :kontor.posting/account (e db cash) :kontor.posting/amount 55M
                         :kontor.posting/commodity (e db eur)
                         :kontor.posting/display-type :note})))))
    ;; TEETH: the exclusion is display-type-specific, not "ignore extra legs".
    (testing "the same stray line as a :product DOES unbalance it"
      (is (= :validation/sum-to-zero
             (out (conj (two-leg db nil nil)
                        {:db/id -9 :kontor.posting/transaction -1
                         :kontor.posting/account (e db cash) :kontor.posting/amount 55M
                         :kontor.posting/commodity (e db eur)
                         :kontor.posting/display-type :product})))))))

;; ============================================================================
;; period locks / legal hold / state machine — families that were gate-only
;; ============================================================================

(deftest governor-refuses-a-posting-into-a-closed-period
  (let [{:keys [conn]} (setup)
        jan (-> (d/transact conn [{:db/id -1
                                   :kontor.period/start #inst "2026-03-01"
                                   :kontor.period/end   #inst "2026-04-01"}])
                :tempids (get -1))
        _ (period/close! conn jan {:pre-checks (constantly [])})
        db @conn
        out (fn [td] (try (gov/validate-report (dc/with @conn td)) :accepted
                          (catch clojure.lang.ExceptionInfo ex (:type (ex-data ex)))))]
    (testing "d1 (2026-03-15) is inside the closed March period — REFUSED"
      (is (= :kontor.period/locked-period-violation (out (two-leg db nil nil)))))
    ;; TEETH: outside the range must still commit, and reopening must un-refuse.
    (testing "an April date is outside — ACCEPTED"
      (is (= :accepted
             (out (assoc-in (two-leg db nil nil)
                            [0 :kontor.transaction/effective-date] #inst "2026-04-15")))))
    (testing "after reopen! the March write is ACCEPTED again"
      (period/reopen! conn jan)
      (is (= :accepted (out (two-leg @conn nil nil)))))))

(deftest governor-refuses-a-write-against-a-sealed-period
  (let [{:keys [conn]} (setup)
        jan (-> (d/transact conn [{:db/id -1
                                   :kontor.period/start #inst "2026-01-01"
                                   :kontor.period/end   #inst "2026-02-01"}])
                :tempids (get -1))]
    (period/close! conn jan {:pre-checks (constantly [])})
    (period/seal! conn jan)
    (testing "touching the sealed period entity is REFUSED"
      (is (= :kontor.period/sealed-write-attempt
             (try (gov/validate-report
                   (dc/with @conn [{:db/id jan :kontor.period/name "renamed"}]))
                  :accepted
                  (catch clojure.lang.ExceptionInfo ex (:type (ex-data ex)))))))
    ;; TEETH: an unsealed period is freely editable.
    (testing "an OPEN period entity can be edited"
      (let [feb (-> (d/transact conn [{:db/id -1
                                       :kontor.period/start #inst "2026-02-01"
                                       :kontor.period/end   #inst "2026-03-01"}])
                    :tempids (get -1))]
        (is (nil? (gov/validate-report
                   (dc/with @conn [{:db/id feb :kontor.period/name "February"}]))))))))

(deftest governor-refuses-a-destructive-write-under-a-legal-hold
  (let [{:keys [conn pd]} (setup)
        db @conn
        counsel (e db [:kontor.partner/external-id "U-counsel"])
        admin (e db [:kontor.partner/external-id "U-admin"])
        doc (e db [:kontor.audit-doc/code "DOC-HOLD"])
        out (fn [td] (try (gov/validate-report (dc/with @conn td)) :accepted
                          (catch clojure.lang.ExceptionInfo ex (:type (ex-data ex)))))]
    (lhold/place! conn {:code "H-1" :matter-name "Acme v. Roe"
                        :issued-by-uid counsel :issued-at #inst "2026-03-01"
                        :supporting-doc doc :reason-note "Preserve the cash leg."
                        :scope-eids [pd]})
    (testing "retracting a held posting reports the HOLD, not merely the seal"
      (is (= :kontor.legal-hold/purge-blocked
             (out [[:db/retract pd :kontor.posting/amount 1000M]]))))
    (testing "and names which hold"
      (let [vs (gov/hold-violations (dc/with @conn [[:db/retractEntity pd]]))]
        (is (= [pd] (mapv :eid vs)))
        (is (seq (:holds (first vs))))))
    ;; TEETH: a hold blocks DESTRUCTIVE writes, not every write, and only for
    ;; entities in its scope.
    (testing "an append-only write to an unheld entity is ACCEPTED"
      (is (= :accepted
             (out (build/entry-tx-data {:debit-account cash :credit-account rev :amount 5
                                        :commodity eur :journal gen
                                        :effective-date d1})))))
    (testing "after release! the retract falls through to the SEALING error"
      (lhold/release! conn {:hold-eid (lhold/by-code @conn "H-1")
                            :released-by-uid admin :supporting-doc doc
                            :reason-note "Matter closed."})
      (is (= :sealing/silent-retract-of-posted
             (out [[:db/retract pd :kontor.posting/amount 1000M]]))))))

(deftest governor-refuses-an-illegal-state-transition
  (let [{:keys [conn]} (setup)
        db @conn
        tx (d/q '[:find ?t . :where [?t :kontor.transaction/state _]] db)
        out (fn [td] (try (gov/validate-report (dc/with @conn td)) :accepted
                          (catch clojure.lang.ExceptionInfo ex (:type (ex-data ex)))))]
    (is (= :posted (:kontor.transaction/state (d/pull db [:kontor.transaction/state] tx))))
    (testing ":posted → :draft is a regression — REFUSED"
      (is (= :state-machine/violation
             (out [{:db/id tx :kontor.transaction/state :draft}]))))
    (testing "the violation reports both ends of the illegal edge"
      (let [v (first (gov/state-machine-violations
                      (dc/with @conn [{:db/id tx :kontor.transaction/state :draft}])))]
        (is (= [:posted :draft :state-machine/illegal-transition]
               [(:from v) (:to v) (:reason v)]))))
    ;; TEETH: the one legal edge out of :posted must still be allowed.
    (testing ":posted → :cancelled is legal — ACCEPTED"
      (is (= :accepted (out [{:db/id tx :kontor.transaction/state :cancelled}]))))
    (testing "a NEW transaction going straight to :posted needs :posted-at"
      (is (= :state-machine/violation
             (out [{:db/id -1 :kontor.transaction/journal gen
                    :kontor.transaction/effective-date d1
                    :kontor.transaction/state :posted}]))))
    (testing "and is ACCEPTED once :posted-at is present"
      (is (= :accepted
             (out [{:db/id -1 :kontor.transaction/journal gen
                    :kontor.transaction/effective-date d1
                    :kontor.transaction/state :posted
                    :kontor.transaction/posted-at #inst "2026-03-16"}]))))))
