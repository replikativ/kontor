(ns kontor.collections.p0-fixes-test
  "Tests for ADR-043 P0 review fixes:
 — :kontor.dunning-event/identity drops :invoice (no nil-tuple trap)
 — credit-utilization numeric query
 — :dunning-pause helpers + plan-dunning-run gate
     P1   — credit-hold :expires-at auto-release
     P1   — frequency-cap uses :as-of (deterministic)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.collections.case :as kcase]
            [kontor.collections.credit-hold :as chold]
            [kontor.collections.dunning :as kdunning]
            [kontor.collections.pause :as kpause]
            [kontor.collections.schema :as coll-schema]
            [kontor.core :as core]
            [kontor.invoice.bridge :as inv]
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
                  :kontor.partner/name "Customer"
                  :kontor.partner/kind :customer
                  :kontor.partner/credit-status :open
                  :kontor.partner/credit-limit 10000M}
                 {:kontor.partner/external-id "U-alice" :kontor.partner/name "Alice"}
                 {:kontor.partner/external-id "U-bob"   :kontor.partner/name "Bob"}])
    (f)))

(use-fixtures :each bootstrap)

(defn- partner [xid]
  (d/q '[:find ?p . :in $ ?x :where [?p :kontor.partner/external-id ?x]]
       (d/db *conn*) xid))

(defn- entity [c]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.entity/code ?c]] (d/db *conn*) c))

(defn- actor [u] (partner (str "U-" u)))

(defn- make-invoice! [external-id gross]
  (let [tempid "inv-1"
        line "line-1"]
    (d/transact *conn*
                [{:db/id tempid
                  :kontor.invoice/external-id external-id
                  :kontor.invoice/type :sales
                  :kontor.invoice/status :sent
                  :kontor.invoice/issue-date #inst "2026-04-01"
                  :kontor.invoice/buyer (partner "CUST")
                  :kontor.invoice/entity (entity "ACME-DE")
                  :kontor.invoice/currency "EUR"
                  :kontor.invoice/total-gross gross
                  :kontor.invoice/lines [line]}
                 {:db/id line
                  :kontor.invoice-line/invoice tempid
                  :kontor.invoice-line/sequence 1
                  :kontor.invoice-line/amount gross
                  :kontor.invoice-line/quantity 1M
                  :kontor.invoice-line/unit-price gross}])
    (inv/by-external-id (d/db *conn*) external-id)))

;; ============================================================================
;;: :kontor.dunning-event/identity (case, level, scheduled-at) — no
;;       nil-in-tuple trap
;; ============================================================================

(deftest dunning-event-identity-no-invoice-tuple-trap
  (testing "Two case-level events (no :invoice) at same (case, level,
            scheduled-at) are correctly rejected as duplicates by
            the composite identity"
    (kcase/open-case! *conn*
                      {:code "CASE-DI"
                       :partner (partner "CUST")
                       :entity (entity "ACME-DE")
                       :opened-by-uid (actor "alice")})
    (let [case-eid (kcase/by-code (d/db *conn*) "CASE-DI")
          scheduled #inst "2026-05-15"]
      ;; First event — should succeed
      (d/transact *conn*
                  [{:kontor.dunning-event/case case-eid
                    :kontor.dunning-event/level 1
                    :kontor.dunning-event/scheduled-at scheduled
                    :kontor.dunning-event/channel :email
                    :kontor.dunning-event/locale "en-US"}])
      ;; Re-transacting the same identity should upsert (one row)
      (d/transact *conn*
                  [{:kontor.dunning-event/case case-eid
                    :kontor.dunning-event/level 1
                    :kontor.dunning-event/scheduled-at scheduled
                    :kontor.dunning-event/channel :email
                    :kontor.dunning-event/locale "en-US"}])
      (testing "Only one row exists (upsert worked)"
        (is (= 1 (d/q '[:find (count ?e) .
                        :in $ ?case
                        :where [?e :kontor.dunning-event/case ?case]]
                      (d/db *conn*) case-eid)))))))

;; ============================================================================
;;: credit-utilization numeric query
;; ============================================================================

(deftest credit-utilization-sums-open-amounts
  (make-invoice! "INV-CU-1" 3000M)
  (make-invoice! "INV-CU-2" 5000M)
  (testing "Two open invoices sum to 8000"
    (is (= 0 (.compareTo
              8000M
              (chold/credit-utilization
               (d/db *conn*)
               {:partner (partner "CUST")
                :entity (entity "ACME-DE")})))))
  ;; Partial-pay the first
  (let [inv1 (inv/by-external-id (d/db *conn*) "INV-CU-1")
        eur (d/q '[:find ?c . :where [?c :kontor.commodity/symbol "EUR"]] (d/db *conn*))
        _ (d/transact *conn*
                      [{:kontor.transaction/external-id "PAY-CU-1"
                        :kontor.transaction/state :posted
                        :kontor.transaction/effective-date #inst "2026-04-15"
                        :kontor.transaction/partner (partner "CUST")}])
        pay (d/q '[:find ?t . :where [?t :kontor.transaction/external-id "PAY-CU-1"]]
                 (d/db *conn*))]
    (papp/apply-payment! *conn*
                         {:payment pay :invoice inv1 :amount 1000M
                          :commodity eur :applied-by-uid (actor "alice")}))
  (testing "After 1000 partial, utilization drops to 7000"
    (is (= 0 (.compareTo
              7000M
              (chold/credit-utilization
               (d/db *conn*)
               {:partner (partner "CUST")
                :entity (entity "ACME-DE")}))))))

;; ============================================================================
;;: :dunning-pause gates plan-dunning-run
;; ============================================================================

(deftest explicit-pause-suppresses-dunning
  (kcase/open-case! *conn*
                    {:code "CASE-PA"
                     :partner (partner "CUST")
                     :entity (entity "ACME-DE")
                     :opened-by-uid (actor "alice")})
  (let [case-eid (kcase/by-code (d/db *conn*) "CASE-PA")
        inv (make-invoice! "INV-PA" 500M)]
    (kpause/place-pause! *conn*
                         {:case case-eid
                          :reason-code :holiday-freeze
                          :placed-by-uid (actor "alice")
                          :notes "December holiday freeze"})
    (testing "any-active-pause? true after place-pause!"
      (is (kpause/any-active-pause? (d/db *conn*) case-eid)))
    ;; Plan run should skip with :explicit-pause
    (d/transact *conn*
                [{:kontor.dunning-policy/code "P-PAUSE"
                  :kontor.dunning-policy/name "Test"
                  :kontor.dunning-policy/entity (entity "ACME-DE")
                  :kontor.dunning-policy/applies-to-segment :default
                  :kontor.dunning-policy/levels kdunning/default-policy-levels-edn
                  :kontor.dunning-policy/frequency-cap-window-days 7
                  :kontor.dunning-policy/frequency-cap-max-events 5
                  :kontor.dunning-policy/pause-on-dispute? true
                  :kontor.dunning-policy/pause-on-open-promise? true
                  :kontor.dunning-policy/active true}])
    (let [policy (d/pull (d/db *conn*) '[*]
                         (d/q '[:find ?p . :where [?p :kontor.dunning-policy/code "P-PAUSE"]]
                              (d/db *conn*)))
          plan (kdunning/plan-dunning-run
                (d/db *conn*)
                {:as-of (java.util.Date.)
                 :entity (entity "ACME-DE")
                 :policy policy
                 :cases [{:case-eid case-eid :invoice-eid inv :locale "en-US"}]})]
      (testing "plan row :skipped? true with :explicit-pause"
        (is (true? (:skipped? (first plan))))
        (is (= :explicit-pause (:skip-reason (first plan))))))))

(deftest expired-pause-no-longer-suppresses
  (kcase/open-case! *conn*
                    {:code "CASE-PE"
                     :partner (partner "CUST")
                     :entity (entity "ACME-DE")
                     :opened-by-uid (actor "alice")})
  (let [case-eid (kcase/by-code (d/db *conn*) "CASE-PE")]
    ;; Backdated placement via kbt/with-vt — :tx/valid-from carries the
    ;; placed-at-equivalent date (ADR-048).
    (kpause/place-pause! *conn*
                         {:case case-eid
                          :reason-code :holiday-freeze
                          :placed-by-uid (actor "alice")
                          :expires-at #inst "2026-01-15"
                          :vt-from #inst "2026-01-01"})
    (testing "with :as-of-valid between placement and :expires-at, pause active"
      (is (kpause/any-active-pause? (d/db *conn*) case-eid
                                    {:as-of-valid #inst "2026-01-10"})))
    (testing "with :as-of-valid after :expires-at, pause not active"
      (is (not (kpause/any-active-pause? (d/db *conn*) case-eid
                                         {:as-of-valid #inst "2026-02-01"}))))))

;; ============================================================================
;; P1: credit-hold :expires-at auto-release
;; ============================================================================

(deftest credit-hold-expires-at-auto-releases
  ;; :placed-at + :vt-from pin the placement to a fixed past date so
  ;; the :as-of-valid assertions below are deterministic. Without them
  ;; both default to wall-clock now, and "hold active before
  ;; :expires-at" (:as-of-valid 2026-05-20) fails once the clock
  ;; reaches that date — a time-bomb on a hardcoded near-future #inst.
  (chold/place-hold! *conn*
                     {:partner (partner "CUST")
                      :entity (entity "ACME-DE")
                      :reason-code :manual
                      :placed-by-uid (actor "alice")
                      :placed-at #inst "2026-05-01"
                      :vt-from #inst "2026-05-01"
                      :expires-at #inst "2026-05-30"})
  (testing "hold active before :expires-at"
    (is (= :hold (chold/credit-status-for
                  (d/db *conn*)
                  {:partner (partner "CUST")
                   :entity (entity "ACME-DE")
                   :as-of-valid #inst "2026-05-20"}))))
  (testing "hold inactive after :expires-at — scalar wins"
    (is (= :open (chold/credit-status-for
                  (d/db *conn*)
                  {:partner (partner "CUST")
                   :entity (entity "ACME-DE")
                   :as-of-valid #inst "2026-06-15"})))))

;; ============================================================================
;; P1: frequency-cap is deterministic with :as-of
;; ============================================================================

(deftest frequency-cap-uses-as-of-not-system-clock
  (kcase/open-case! *conn*
                    {:code "CASE-FC2"
                     :partner (partner "CUST")
                     :entity (entity "ACME-DE")
                     :opened-by-uid (actor "alice")})
  (let [case-eid (kcase/by-code (d/db *conn*) "CASE-FC2")]
    ;; Seed an event from 2026-01-01
    (d/transact *conn*
                [{:kontor.dunning-event/case case-eid
                  :kontor.dunning-event/level 1
                  :kontor.dunning-event/scheduled-at #inst "2026-01-01"
                  :kontor.dunning-event/sent-at #inst "2026-01-01"
                  :kontor.dunning-event/channel :email
                  :kontor.dunning-event/locale "en-US"}])
    (let [policy {:kontor.dunning-policy/frequency-cap-window-days 7
                  :kontor.dunning-policy/frequency-cap-max-events 1}]
      (testing "Within window (2026-01-05): cap violated"
        (is (kdunning/frequency-cap-violated?
             (d/db *conn*) case-eid policy #inst "2026-01-05")))
      (testing "Outside window (2026-02-01): not violated"
        (is (not (kdunning/frequency-cap-violated?
                  (d/db *conn*) case-eid policy #inst "2026-02-01"))))
      (testing "Default (no :as-of) uses now — also outside since
                2026-01-01 is past"
        (is (not (kdunning/frequency-cap-violated?
                  (d/db *conn*) case-eid policy)))))))
