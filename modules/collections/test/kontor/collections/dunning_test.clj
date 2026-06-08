(ns kontor.collections.dunning-test
  "Tests for the dunning planner + emitter — ADR-043 commit 5/5.

   Verifies the three pause gates (dispute, open promise, frequency-
   cap), level progression, and emission writing :dunning-event +
   :audit-doc + :side-effect-intent atomically."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.collections.case :as kcase]
            [kontor.collections.dispute :as kdispute]
            [kontor.collections.dunning :as kdunning]
            [kontor.collections.promise :as kpromise]
            [kontor.collections.schema :as coll-schema]
            [kontor.core :as core]
            [kontor.invoice.schema :as inv-schema]
            [kontor.banking.payment-application :as papp]
            [kontor.workflow.status-machine :as sm]))

(def ^:dynamic *conn* nil)

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (inv-schema/install! *conn*)
    (coll-schema/install! *conn*)
    (d/transact *conn*
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
                 {:kontor.entity/code "ACME-DE" :kontor.entity/name "Acme GmbH"
                  :kontor.entity/kind :operating :kontor.entity/active true}
                 {:kontor.partner/external-id "CUST"
                  :kontor.partner/name "Customer Co"
                  :kontor.partner/kind :customer}
                 {:kontor.partner/external-id "U-alice" :kontor.partner/name "Alice"}
                 {:kontor.partner/external-id "U-bob"   :kontor.partner/name "Bob"}])
    (f)))

(use-fixtures :each bootstrap)

(defn- partner [xid]
  (d/q '[:find ?p . :in $ ?x :where [?p :kontor.partner/external-id ?x]]
       (d/db *conn*) xid))

(defn- entity [c] (d/q '[:find ?e . :in $ ?c :where [?e :kontor.entity/code ?c]]
                       (d/db *conn*) c))
(defn- actor [u] (partner (str "U-" u)))
(defn- commodity [s] (d/q '[:find ?c . :in $ ?s :where [?c :kontor.commodity/symbol ?s]]
                          (d/db *conn*) s))

(defn- make-invoice! [external-id gross]
  (let [inv-tempid "inv-1"
        line-tempid "line-1"]
    (d/transact *conn*
                [{:db/id inv-tempid
                  :kontor.invoice/external-id external-id
                  :kontor.invoice/type :sales
                  :kontor.invoice/status :sent
                  :kontor.invoice/issue-date #inst "2026-04-01"
                  :kontor.invoice/buyer (partner "CUST")
                  :kontor.invoice/entity (entity "ACME-DE")
                  :kontor.invoice/currency "EUR"
                  :kontor.invoice/total-gross gross
                  :kontor.invoice/lines [line-tempid]}
                 {:db/id line-tempid
                  :kontor.invoice-line/invoice inv-tempid
                  :kontor.invoice-line/sequence 1
                  :kontor.invoice-line/amount gross
                  :kontor.invoice-line/quantity 1M
                  :kontor.invoice-line/unit-price gross}])
    (d/q '[:find ?e . :in $ ?xid :where [?e :kontor.invoice/external-id ?xid]]
         (d/db *conn*) external-id)))

(defn- seed-policy! [code & {:keys [pause-on-dispute? pause-on-open-promise?
                                    pause-on-unapplied-cash?
                                    freq-window-days freq-max-events]
                              :or {pause-on-dispute? true
                                   pause-on-open-promise? true
                                   pause-on-unapplied-cash? false
                                   freq-window-days 7
                                   freq-max-events 2}}]
  (d/transact *conn*
              [{:kontor.dunning-policy/code code
                :kontor.dunning-policy/name (str "Policy " code)
                :kontor.dunning-policy/entity (entity "ACME-DE")
                :kontor.dunning-policy/applies-to-segment :default
                :kontor.dunning-policy/levels kdunning/default-policy-levels-edn
                :kontor.dunning-policy/frequency-cap-window-days freq-window-days
                :kontor.dunning-policy/frequency-cap-max-events freq-max-events
                :kontor.dunning-policy/pause-on-dispute? pause-on-dispute?
                :kontor.dunning-policy/pause-on-open-promise? pause-on-open-promise?
                :kontor.dunning-policy/pause-on-unapplied-cash? pause-on-unapplied-cash?
                :kontor.dunning-policy/active true}])
  (d/q '[:find ?p . :in $ ?c :where [?p :kontor.dunning-policy/code ?c]]
       (d/db *conn*) code))

(defn- provider []
  (kdunning/static-template-provider
   {1 {"en-US" "Reminder L1"
       "de-DE" "Erinnerung"}
    2 {"en-US" "Past-due L2"
       "de-DE" "Erste Mahnung"}
    3 {"en-US" "Final Notice"
       "de-DE" "Letzte Mahnung"}}))

;; ============================================================================
;; Policy resolution
;; ============================================================================

(deftest resolve-policy-prefers-entity-segment-specific
  (seed-policy! "DEFAULT")
  (let [eid (d/q '[:find ?p . :in $ ?c
                   :where [?p :kontor.dunning-policy/code ?c]]
                 (d/db *conn*) "DEFAULT")
        resolved (kdunning/resolve-policy (d/db *conn*)
                                          {:entity (entity "ACME-DE")
                                           :segment :default})]
    (is (some? resolved))
    (is (= eid (:db/id resolved)))))

;; ============================================================================
;; Frequency cap
;; ============================================================================

(deftest frequency-cap-counts-within-window
  (let [policy-eid (seed-policy! "FC-TEST" :freq-window-days 7
                                            :freq-max-events 2)
        policy (d/pull (d/db *conn*) '[*] policy-eid)
        _ (kcase/open-case! *conn*
                           {:code "CASE-FC"
                            :partner (partner "CUST")
                            :entity (entity "ACME-DE")
                            :opened-by-uid (actor "alice")})
        case-eid (kcase/by-code (d/db *conn*) "CASE-FC")]
    ;; Seed 2 already-sent events within the window
    (d/transact *conn*
                [{:kontor.dunning-event/case case-eid
                  :kontor.dunning-event/level 1
                  :kontor.dunning-event/scheduled-at (java.util.Date.)
                  :kontor.dunning-event/sent-at (java.util.Date.)
                  :kontor.dunning-event/channel :email
                  :kontor.dunning-event/locale "en-US"}
                 {:kontor.dunning-event/case case-eid
                  :kontor.dunning-event/level 2
                  :kontor.dunning-event/scheduled-at (java.util.Date.)
                  :kontor.dunning-event/sent-at (java.util.Date.)
                  :kontor.dunning-event/channel :email
                  :kontor.dunning-event/locale "en-US"}])
    (testing "2 events in window AT the cap"
      (is (kdunning/frequency-cap-violated?
           (d/db *conn*) case-eid policy)))))

;; ============================================================================
;; plan-dunning-run pauses
;; ============================================================================

(deftest plan-pauses-on-open-dispute
  (let [policy-eid (seed-policy! "P-DISP")
        policy (d/pull (d/db *conn*) '[*] policy-eid)
        inv (make-invoice! "INV-DISP" 1000M)
        _ (kcase/open-case! *conn*
                           {:code "CASE-DISP"
                            :partner (partner "CUST")
                            :entity (entity "ACME-DE")
                            :opened-by-uid (actor "alice")})
        case-eid (kcase/by-code (d/db *conn*) "CASE-DISP")]
    (kdispute/raise-dispute! *conn*
                             {:external-id "DISP-1"
                              :invoice inv
                              :disputed-amount 1000M
                              :reason-code :pricing
                              :opened-by-uid (actor "alice")})
    (let [plan (kdunning/plan-dunning-run
                (d/db *conn*)
                {:as-of (java.util.Date.)
                 :entity (entity "ACME-DE")
                 :policy policy
                 :cases [{:case-eid case-eid :invoice-eid inv :locale "de-DE"}]})]
      (is (= 1 (count plan)))
      (testing "single row, :skipped? = true, :skip-reason :open-dispute"
        (is (true? (:skipped? (first plan))))
        (is (= :open-dispute (:skip-reason (first plan))))))))

(deftest plan-pauses-on-open-promise
  (let [policy-eid (seed-policy! "P-PROM")
        policy (d/pull (d/db *conn*) '[*] policy-eid)
        inv (make-invoice! "INV-PROM" 800M)
        _ (kcase/open-case! *conn*
                           {:code "CASE-PROM"
                            :partner (partner "CUST")
                            :entity (entity "ACME-DE")
                            :opened-by-uid (actor "alice")})
        case-eid (kcase/by-code (d/db *conn*) "CASE-PROM")]
    (kpromise/record-promise! *conn*
                              {:external-id "PTP-1"
                               :case case-eid
                               :invoice inv
                               :amount 800M
                               :commodity (commodity "EUR")
                               :promised-by-date #inst "2026-06-01"
                               :captured-by-uid (actor "alice")})
    (let [plan (kdunning/plan-dunning-run
                (d/db *conn*)
                {:as-of (java.util.Date.)
                 :entity (entity "ACME-DE")
                 :policy policy
                 :cases [{:case-eid case-eid :invoice-eid inv :locale "de-DE"}]})]
      (testing "skipped with :open-promise"
        (is (= :open-promise (:skip-reason (first plan))))))))

(deftest plan-progresses-through-levels
  (let [policy-eid (seed-policy! "P-LEVEL")
        policy (d/pull (d/db *conn*) '[*] policy-eid)
        inv (make-invoice! "INV-LEVEL" 500M)
        _ (kcase/open-case! *conn*
                           {:code "CASE-LEVEL"
                            :partner (partner "CUST")
                            :entity (entity "ACME-DE")
                            :opened-by-uid (actor "alice")})
        case-eid (kcase/by-code (d/db *conn*) "CASE-LEVEL")]
    (let [plan (kdunning/plan-dunning-run
                (d/db *conn*)
                {:as-of (java.util.Date.)
                 :entity (entity "ACME-DE")
                 :policy policy
                 :cases [{:case-eid case-eid :invoice-eid inv :locale "en-US"}]})]
      (testing "first plan picks level 1"
        (is (= 1 (:level (first plan))))
        (is (false? (:skipped? (first plan))))))
    ;; Emit level 1
    (kdunning/emit-dunning-event!
     *conn*
     {:plan-row (-> (kdunning/plan-dunning-run
                     (d/db *conn*)
                     {:as-of (java.util.Date.)
                      :entity (entity "ACME-DE")
                      :policy policy
                      :cases [{:case-eid case-eid :invoice-eid inv :locale "en-US"}]})
                    first)
      :channel :email
      :provider (provider)})
    ;; Re-plan; should now pick level 2
    (let [plan2 (kdunning/plan-dunning-run
                 (d/db *conn*)
                 {:as-of (java.util.Date.)
                  :entity (entity "ACME-DE")
                  :policy policy
                  :cases [{:case-eid case-eid :invoice-eid inv :locale "en-US"}]})]
      (testing "second plan picks level 2"
        (is (= 2 (:level (first plan2))))))))

;; ============================================================================
;; Emission
;; ============================================================================

(deftest emit-writes-event-audit-doc-side-effect-intent
  (let [policy-eid (seed-policy! "P-EMIT")
        policy (d/pull (d/db *conn*) '[*] policy-eid)
        inv (make-invoice! "INV-EMIT" 1200M)
        _ (kcase/open-case! *conn*
                           {:code "CASE-EMIT"
                            :partner (partner "CUST")
                            :entity (entity "ACME-DE")
                            :opened-by-uid (actor "alice")})
        case-eid (kcase/by-code (d/db *conn*) "CASE-EMIT")
        plan-row (-> (kdunning/plan-dunning-run
                      (d/db *conn*)
                      {:as-of #inst "2026-05-15"
                       :entity (entity "ACME-DE")
                       :policy policy
                       :cases [{:case-eid case-eid
                                :invoice-eid inv
                                :locale "de-DE"}]})
                     first)]
    (kdunning/emit-dunning-event!
     *conn*
     {:plan-row plan-row
      :channel :email
      :provider (provider)})
    (let [db (d/db *conn*)]
      (testing "dunning-event row created with :sent-at"
        (let [ev-count (d/q '[:find (count ?e) .
                              :in $ ?case
                              :where
                              [?e :kontor.dunning-event/case ?case]
                              [?e :kontor.dunning-event/sent-at _]]
                            db case-eid)]
          (is (= 1 ev-count))))
      (testing "audit-doc with :dunning-letter type created"
        (is (= 1 (d/q '[:find (count ?d) .
                        :where [?d :kontor.audit-doc/type :dunning-letter]]
                      db))))
      (testing "side-effect-intent created in :pending status"
        (is (some? (d/q '[:find ?i .
                          :where
                          [?i :kontor.side-effect-intent/type :send-email]
                          [?i :kontor.side-effect-intent/status :pending]]
                        db)))))))

(deftest emit-skipped-row-records-skip-reason
  (let [policy-eid (seed-policy! "P-SKIP")
        policy (d/pull (d/db *conn*) '[*] policy-eid)
        inv (make-invoice! "INV-SKIP" 1000M)
        _ (kcase/open-case! *conn*
                           {:code "CASE-SKIP"
                            :partner (partner "CUST")
                            :entity (entity "ACME-DE")
                            :opened-by-uid (actor "alice")})
        case-eid (kcase/by-code (d/db *conn*) "CASE-SKIP")]
    (kdispute/raise-dispute! *conn*
                             {:external-id "DISP-S"
                              :invoice inv
                              :disputed-amount 1000M
                              :reason-code :pricing
                              :opened-by-uid (actor "alice")})
    (let [plan-row (-> (kdunning/plan-dunning-run
                        (d/db *conn*)
                        {:as-of (java.util.Date.)
                         :entity (entity "ACME-DE")
                         :policy policy
                         :cases [{:case-eid case-eid :invoice-eid inv :locale "de-DE"}]})
                       first)]
      (kdunning/emit-dunning-event!
       *conn*
       {:plan-row plan-row
        :channel :email
        :provider (provider)})
      (let [db (d/db *conn*)
            ev (-> (d/q '[:find [?e ...]
                          :in $ ?case
                          :where [?e :kontor.dunning-event/case ?case]]
                        db case-eid)
                   first
                   (->> (d/pull db '[*])))]
        (testing "event row has :skipped? + :skip-reason"
          (is (true? (:kontor.dunning-event/skipped? ev)))
          (is (= :open-dispute (:kontor.dunning-event/skip-reason ev))))
        (testing "no audit-doc nor side-effect-intent for skipped"
          (is (nil? (d/q '[:find (count ?d) .
                           :where [?d :kontor.audit-doc/type :dunning-letter]]
                         db))))))))
