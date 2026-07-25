(ns kontor.actor-test
  "ADR-150 — actor identity on ledger writes.

   Every assertion here is about an EFFECT: what an auditor can read back off
   the ledger, and which writes are refused. Nothing asserts that an attribute
   exists or that a var resolves — the defect this ADR closes shipped for
   months behind exactly that kind of assertion, because
   `:kontor.audit/write-uid` was installed in the schema and had zero writers
   and zero readers."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.actor :as actor]
            [kontor.book :as book]
            [kontor.gate :as gate]
            [kontor.posting :as posting]
            [kontor.workflow.status-machine :as sm]
            [kontor.l10n-de.preset :as de]))

(def ^:private ar  [:kontor.account/path "Umlaufvermögen:Forderungen"])
(def ^:private rev [:kontor.account/path "Erträge:Erlöse:19%"])
(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private d1  #inst "2026-03-15")

(defn- sell! [conn opts]
  (book/sell! conn (merge {:debit-account ar :credit-account rev
                           :amount 100M :commodity eur :effective-date d1}
                          opts)))

(defn- the-tx [db]
  (d/q '[:find ?t . :where [?t :kontor.transaction/posted-at _]] db))

(defn- actor-of
  "The actor uid recorded on `attr` of transaction `t` — i.e. what an audit
   report prints when it asks 'who did this?'. nil when the ref resolves to
   nothing, which is precisely the pre-ADR-150 phantom case."
  [db t attr]
  (some->> (d/pull db [{attr [:db/id]}] t) attr :db/id
           (d/pull db [:kontor.actor/uid]) :kontor.actor/uid))

;; ============================================================================
;; The facade could not carry an actor at all
;; ============================================================================

(deftest book-verbs-record-who-sealed-the-entry
  ;; `kontor.book` is the facade CLAUDE.md calls "start here for any new
  ;; business write", and its option set is STRICT (ADR-124) — so before
  ;; `:actor` joined it, threading an actor through the documented write path
  ;; was not merely unsupported, it THREW.
  (testing ":actor is accepted by the strict option set and lands on the entry"
    (let [conn (de/create-de-db)]
      (actor/register-actor! conn {:uid "sarah" :name "Sarah Weber" :kind :person})
      (sell! conn {:actor "sarah"})
      (let [db (d/db conn) t (the-tx db)]
        (is (= "sarah" (actor-of db t :kontor.transaction/posted-by))
            "posted-by resolves to a real actor — GoBD's Bearbeiter")
        (is (= "sarah" (actor-of db t :kontor.audit/create-uid))
            "create-uid too — what :no-self-approval compares an approver against")
        (is (= "sarah" (actor-of db t :kontor.audit/write-uid))
            ":kontor.audit/write-uid had zero writers repo-wide before ADR-150")
        (is (= "Sarah Weber"
               (:kontor.actor/name (actor/actor db "sarah")))
            "and the actor pulls to something readable, not an attribute-less phantom"))))

  (testing "an unknown option key is still refused — :actor did not loosen the set"
    (let [conn (de/create-de-db)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown"
                            (sell! conn {:actorr "sarah"}))))))

;; ============================================================================
;; The 296 opaque strings
;; ============================================================================

(deftest opaque-uid-strings-become-one-real-actor
  ;; A string in a `:db.type/ref` slot is a TEMPID, so every one of the 296
  ;; measured `-uid` writes minted a brand-new attribute-less entity. Two
  ;; consequences, and the second is the one that mattered.
  (testing "a bare -uid string resolves to a readable actor instead of a phantom"
    (let [conn (de/create-de-db)]
      (gate/transact-with-validation
       conn [{:kontor.status-history/changed-by-uid "carol"
              :kontor.status-history/facet :test}])
      (is (= "carol" (:kontor.actor/uid (actor/actor (d/db conn) "carol")))
          "the audit trail's actor pointer resolves to something")
      (is (= actor/unregistered-kind (:kontor.actor/kind (actor/actor (d/db conn) "carol")))
          "and is marked :unregistered, so an auditor can tell it was not enrolled")))

  (testing "the SAME string twice is the SAME actor — what made four-eyes inert"
    ;; Before ADR-150 `\"bob\"` in one transaction and `\"bob\"` in the next were
    ;; different entities, so `:no-self-approval` — which compares eids — could
    ;; never fire even when both sides were populated. The control was not
    ;; merely unenforced, it was incapable of firing.
    (let [conn (de/create-de-db)]
      (sell! conn {:actor "bob"})
      (sell! conn {:actor "bob" :effective-date #inst "2026-03-16"})
      (is (= 1 (count (d/q '[:find [?e ...] :where [?e :kontor.actor/uid "bob"]]
                           (d/db conn))))
          "two writes of the same uid converge on one actor entity")))

  (testing "an actor created inline by tempid in the same tx-data is left alone"
    (let [conn (de/create-de-db)]
      (gate/transact-with-validation
       conn [{:db/id "a1" :kontor.actor/uid "dave" :kontor.actor/name "Dave"}
             {:kontor.status-history/changed-by-uid "a1"
              :kontor.status-history/facet :test}])
      (is (= "Dave" (:kontor.actor/name (actor/actor (d/db conn) "dave")))
          "the inline registration wins; the tempid was not mistaken for a uid")
      (is (nil? (actor/actor (d/db conn) "a1"))
          "and no actor was invented for the tempid string itself"))))

(deftest mid-life-import-keeps-the-ORIGINAL-creator
  ;; A mid-life import legitimately has two different actors: the service
  ;; account doing the importing, and whoever created the record in the system
  ;; being imported from. `stamp` must not overwrite a create-uid the caller
  ;; set explicitly — if it did, every imported entry would claim the importer
  ;; created it, and `:no-self-approval` would then compare later approvers
  ;; against a service account instead of against the real author.
  ;;
  ;; This also regression-pins a `:transact/upsert` conflict: giving every uid
  ;; a tempid and relying on `:db.unique/identity` to upsert collides when the
  ;; same tempid is also in a ref slot ("resolves both to 899 and 901"), so an
  ;; already-registered actor must become a plain lookup-ref instead.
  (let [conn (de/create-de-db)]
    (actor/register-actor! conn {:uid "importer" :kind :service})
    (actor/register-actor! conn {:uid "original-author" :kind :person})
    (gate/transact-with-validation
     conn
     (posting/post-transaction-tx-data
      {:transaction {:kontor.transaction/journal [:kontor.journal/code "SJ"]
                     :kontor.transaction/effective-date d1
                     :kontor.audit/create-uid "original-author"}
       :postings [{:kontor.posting/account ar :kontor.posting/amount 100M
                   :kontor.posting/commodity eur}
                  {:kontor.posting/account rev :kontor.posting/amount -100M
                   :kontor.posting/commodity eur}]}
      {:actor "importer"}))
    (let [db (d/db conn) t (the-tx db)]
      (is (= "original-author" (actor-of db t :kontor.audit/create-uid))
          "the caller's creator survives — the importer did not claim authorship")
      (is (= "importer" (actor-of db t :kontor.transaction/posted-by))
          "and the importer is recorded as who sealed it")
      (is (= "importer" (actor-of db t :kontor.audit/write-uid))
          "…and as the last logical writer"))))

;; ============================================================================
;; Requiring an actor
;; ============================================================================

(deftest requires-actor-policy-refuses-an-unattributed-seal
  (testing "off by default — a kernel cannot decide a consumer's control environment"
    (let [conn (de/create-de-db)]
      (is (some? (sell! conn {})) "an entry with no actor posts fine by default")))

  (testing "once installed, sealing without an actor is REFUSED"
    (let [conn (de/create-de-db)]
      (actor/require-actor-on-posted! conn)
      (actor/register-actor! conn {:uid "sarah"})
      (is (thrown? Exception (sell! conn {})))
      (is (zero? (count (d/q '[:find [?t ...] :where
                               [?t :kontor.transaction/posted-at _]] (d/db conn))))
          "and nothing was committed — the refusal is not advisory")
      (is (some? (sell! conn {:actor "sarah"}))
          "the same entry with an actor posts")))

  (testing "under the policy an UNREGISTERED actor is refused, not invented"
    ;; Permissive provisioning is right for a book that has not declared it
    ;; cares who acts. A book that HAS declared it must not let a typo become
    ;; a person: `\"sarha\"` would otherwise silently join the roster.
    (let [conn (de/create-de-db)]
      (actor/register-actor! conn {:uid "sarah"})
      (actor/require-actor-on-posted! conn)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unregistered actor"
                            (sell! conn {:actor "sarha"})))
      (is (nil? (actor/actor (d/db conn) "sarha"))
          "the typo did not become an actor")))

  (testing "a DEACTIVATED actor cannot act, but its old entries still read"
    (let [conn (de/create-de-db)]
      (actor/register-actor! conn {:uid "sarah"})
      (actor/require-actor-on-posted! conn)
      (sell! conn {:actor "sarah"})
      (actor/register-actor! conn {:uid "sarah" :active false})   ; she leaves
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"deactivated actor"
                            (sell! conn {:actor "sarah"
                                         :effective-date #inst "2026-03-20"}))
          "a new action must not be attributed to a retired actor")
      (is (= "sarah" (actor-of (d/db conn) (the-tx (d/db conn))
                               :kontor.transaction/posted-by))
          "…while the entry she DID post still names her — deactivation is not deletion"))))

;; ============================================================================
;; :no-self-approval fails CLOSED
;; ============================================================================

(defn- self-approval-policy [conn]
  (d/transact conn [{:kontor.approval-policy/entity-type     :audit-doc
                     :kontor.approval-policy/facet           :test/facet
                     :kontor.approval-policy/transition-from :draft
                     :kontor.approval-policy/transition-to   :approved
                     :kontor.approval-policy/rule            :no-self-approval
                     :kontor.approval-policy/active          true}]))

(defn- check [conn entity actor-spec]
  (sm/check-policies (d/db conn)
                     {:entity entity :entity-type :audit-doc :facet :test/facet
                      :from :draft :to :approved
                      :changed-by-uid actor-spec}))

(defn- refusal-reason
  "The `:reason` the :no-self-approval rule gave for refusing, or nil when it
   permitted the transition. `check-policies` reports through ex-data rather
   than the message, so that is where the assertion has to look."
  [conn entity actor-spec]
  (try (check conn entity actor-spec) nil
       (catch clojure.lang.ExceptionInfo e
         (-> e ex-data :violations first :reason))))

(deftest no-self-approval-fails-closed
  ;; The rule used to return "no violation" whenever EITHER side was nil, so
  ;; four-eyes failed OPEN exactly when the actor was unrecorded — the one
  ;; circumstance in which separation of duties cannot be verified. An auditor
  ;; tests this first.
  (let [conn (de/create-de-db)]
    (self-approval-policy conn)
    (actor/register-actor! conn {:uid "alice"})
    (actor/register-actor! conn {:uid "bob"})
    (let [alice (:db/id (actor/actor (d/db conn) "alice"))
          bob   (:db/id (actor/actor (d/db conn) "bob"))
          ;; any entity carrying :kontor.audit/create-uid will do — the rule
          ;; reads only that attribute. An :audit-doc is the realistic carrier.
          doc   (do (d/transact conn [{:kontor.audit-doc/code "DOC-1"
                                       :kontor.audit/create-uid alice}])
                    (d/q '[:find ?e . :where
                           [?e :kontor.audit-doc/code "DOC-1"]] (d/db conn)))
          uncreated (do (d/transact conn [{:kontor.audit-doc/code "DOC-2"}])
                        (d/q '[:find ?e . :where
                               [?e :kontor.audit-doc/code "DOC-2"]] (d/db conn)))]

      (testing "the creator approving their own document is refused (unchanged)"
        (is (re-find #"must differ from entity creator" (refusal-reason conn doc alice))))

      (testing "a DIFFERENT actor may approve — the rule still permits the legal case"
        (is (nil? (refusal-reason conn doc bob))))

      (testing "NO recorded approver is refused, not waved through"
        ;; This is the fail-OPEN hole ADR-150 closes: with a nil actor the rule
        ;; used to return "no violation", so four-eyes passed in exactly the one
        ;; circumstance where separation of duties cannot be verified.
        (is (re-find #"cannot be verified" (or (refusal-reason conn doc nil) ""))))

      (testing "an entity with NO recorded creator is refused too"
        ;; there is nothing to compare against, so separation of duties is
        ;; unverifiable in exactly the same way.
        (is (re-find #"nothing to compare" (or (refusal-reason conn uncreated bob) "")))))))
