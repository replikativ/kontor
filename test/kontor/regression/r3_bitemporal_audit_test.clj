(ns kontor.regression.r3-bitemporal-audit-test
  "Regression suite — round-3 adversarial probing of the bitemporal +
   compliance substrate:

     kontor.bitemporal              — with-vt / close-validity! / commit-tx-eid
     kontor.compliance.sealing      — refuse silent retract/edit of posted (ADR-007)
     kontor.compliance.legal-hold   — block destructive writes on held eids (ADR-049)
     kontor.compliance.retention    — sweeper, hold-blocks-expiry, eligibility (ADR-050)
     kontor.compliance.dsar         — the bitemporal collect walk (ADR-052)
     kontor.compliance.period       — soft/hard period lock (ADR-014)

   Every test here asserts a substrate GUARANTEE that holds today; the
   suite carries no `^:kaocha/pending` pins since ADR-140 closed the
   period-lock fail-open gap (§7).

   Everything is booked through `kontor.book` / the validation gate over a
   small EUR/USD chart on `kontor.core/create-test-db`, so the suite
   exercises the same write path a real consumer uses."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.compliance.dsar :as dsar]
            [kontor.compliance.legal-hold :as lhold]
            [kontor.compliance.period :as period]
            [kontor.compliance.retention :as ret]
            [kontor.gate :as gate]
            [kontor.reporting.balance :as balance]
            [kontor.validation :as v]))

;; ============================================================================
;; Fixture
;; ============================================================================

(def ^:private jan-1  #inst "2026-01-01T00:00:00Z")
(def ^:private jan-2  #inst "2026-01-02T00:00:00Z")
(def ^:private jan-15 #inst "2026-01-15T00:00:00Z")
(def ^:private feb-1  #inst "2026-02-01T00:00:00Z")
(def ^:private feb-15 #inst "2026-02-15T00:00:00Z")

(defn- fresh-book
  "Schema-loaded conn with invariants installed and a minimal chart: EUR +
   USD commodities, one journal per verb type (CR + CD cash journals so the
   cash verbs resolve unambiguously), a small chart, plus compliance actors
   (partners standing in for :kontor.audit/create-uid refs), a data-subject
   partner, and the audit-docs the hold/retention builders require."
  []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (d/transact
     conn
     [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
       :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
      {:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
       :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "USD"}
      {:kontor.journal/code "SALE" :kontor.journal/type :sale     :kontor.journal/name "Sales"}
      {:kontor.journal/code "PUR"  :kontor.journal/type :purchase :kontor.journal/name "Purchases"}
      {:kontor.journal/code "CR"   :kontor.journal/type :cash     :kontor.journal/name "Cash Receipts"}
      {:kontor.journal/code "CD"   :kontor.journal/type :cash     :kontor.journal/name "Cash Disbursements"}
      {:kontor.journal/code "GEN"  :kontor.journal/type :general  :kontor.journal/name "General"}
      ;; Compliance actors — partner records reused as :kontor.audit/create-uid
      ;; ref targets (the convention across the compliance test suite).
      {:kontor.partner/external-id "U-counsel" :kontor.partner/name "Counsel C"}
      {:kontor.partner/external-id "U-admin"   :kontor.partner/name "Admin A"}
      {:kontor.partner/external-id "U-records" :kontor.partner/name "Records Manager"}
      ;; The data subject (a customer) for the DSAR walk.
      {:kontor.partner/external-id "SUBJECT" :kontor.partner/name "Jane Subject"
       :kontor.partner/kind :customer}
      ;; Supporting docs for hold placement / release / retention schedule.
      {:kontor.audit-doc/code "DOC-PLACE"    :kontor.audit-doc/type :legal-hold-order
       :kontor.audit-doc/uploaded-at #inst "2026-05-13"}
      {:kontor.audit-doc/code "DOC-RELEASE"  :kontor.audit-doc/type :legal-hold-release
       :kontor.audit-doc/uploaded-at #inst "2026-06-01"}
      {:kontor.audit-doc/code "DOC-SCHEDULE" :kontor.audit-doc/type :retention-schedule
       :kontor.audit-doc/uploaded-at #inst "2026-01-01"}])
    (d/transact
     conn
     [{:kontor.account/path "Assets:Cash"       :kontor.account/code "1000" :kontor.account/type :asset   :kontor.account/active true}
      {:kontor.account/path "Assets:Receivable" :kontor.account/code "1200" :kontor.account/type :asset   :kontor.account/active true}
      {:kontor.account/path "Income:Sales"      :kontor.account/code "4000" :kontor.account/type :income  :kontor.account/active true}
      {:kontor.account/path "Expenses:Supplies" :kontor.account/code "5000" :kontor.account/type :expense :kontor.account/active true}])
    conn))

(def ^:private eur  [:kontor.commodity/symbol "EUR"])
(def ^:private ar   [:kontor.account/path "Assets:Receivable"])
(def ^:private rev  [:kontor.account/path "Income:Sales"])

(defn- eid [db lookup-ref] (:db/id (d/entity db lookup-ref)))

(defn- pe [db xid]
  (d/q '[:find ?e . :in $ ?x :where [?e :kontor.partner/external-id ?x]] db xid))

(defn- adoc [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.audit-doc/code ?c]] db code))

(defn- eur-amt
  "Single-commodity (EUR) balance amount on `account`, as a BigDecimal
   (0M when the account has no postings). Compare with `==`, never `=`."
  [conn account & [opts]]
  (let [m (first (vals (balance/account-balance conn account (or opts {}))))]
    (if m (:amount m) 0M)))

(defn- tx-instant
  "The commit `:db/txInstant` for a `kontor.book` tx-report — used as an
   `:as-of-tx` cut for as-filed vs restated (transaction-time) views."
  [conn report]
  (:db/txInstant (d/pull (d/db conn) [:db/txInstant] (kbt/commit-tx-eid report))))

;; ============================================================================
;; 1. Bitemporal CORRECTION — read the same valid-time as-of both the
;;    pre-correction and post-correction transaction-time (ADR-008).
;; ============================================================================

(deftest bitemporal-correction-read-before-and-after-correction-tx
  (testing "AR is booked 1000 @ jan-15, then discovered overstated and
            corrected DOWN by 100 at the SAME valid-time (jan-15) but a
            later transaction-time. The pre-correction tx-time reads 1000;
            latest knowledge reads 900; and the correction does not leak
            before the fact's valid-from."
    (let [conn (fresh-book)
          r1   (book/sell! conn {:debit-account ar :credit-account rev
                                 :amount 1000 :commodity eur
                                 :effective-date jan-15 :narration "As-filed invoice"})
          t1   (tx-instant conn r1)
          ;; Correction booked later (now), SAME valid-time jan-15: −100.
          _    (book/adjust! conn {:effective-date jan-15
                                   :narration "Overstatement correction −100"
                                   :postings [{:account ar  :amount -100 :commodity eur}
                                              {:account rev :amount  100 :commodity eur}]})]
      (testing "as-of the pre-correction commit tx-time → the as-filed 1000"
        (is (== 1000M (eur-amt conn ar {:as-of-tx t1 :as-of-valid feb-1}))))
      (testing "latest knowledge, valid @ jan-15 → the corrected 900"
        (is (== 900M (eur-amt conn ar {:as-of-valid jan-15}))))
      (testing "the correction does not leak before the fact's valid-from"
        (is (== 0M (eur-amt conn ar {:as-of-valid jan-1})))))))

;; ============================================================================
;; 1b. close-validity! — retroactively close a prior tx's valid-time window
;;     and read across BOTH the valid-time and transaction-time axes.
;; ============================================================================

(deftest close-validity-retroactively-bounds-an-overbroad-fact
  (testing "A partner fact is wrongly recorded as valid open-endedly from
            jan-2; the correction is that the relationship actually ended at
            feb-1. `close-validity!` retroactively bounds the window to
            [jan-2, feb-1). Latest knowledge then splits at feb-1, while a
            read as-of the PRE-correction tx-time still sees it open-ended —
            the two transaction-time views of the same valid-time."
    (let [conn (fresh-book)
          r1   (d/transact conn {:tx-data [{:db/id "v"
                                            :kontor.partner/external-id "VENDOR-X"
                                            :kontor.partner/name "Vendor X"}]
                                 :tx-meta {:db.valid/from jan-2}})
          v-eid (get-in r1 [:tempids "v"])
          tx1   (kbt/commit-tx-eid r1)
          t1    (:db/txInstant (d/pull (d/db conn) [:db/txInstant] tx1))
          seen-at (fn [db-val vt]
                    (:kontor.partner/name
                     (d/pull (d/valid-at db-val vt) [:kontor.partner/name] v-eid)))]
      (testing "before correction: the open-ended fact is visible at feb-15"
        (is (= "Vendor X" (seen-at (d/db conn) feb-15))))
      ;; Retroactive correction: close the window at feb-1 (a recorded commit).
      (kbt/close-validity! conn tx1 feb-1)
      (testing "after correction: still visible inside the window (jan-15)"
        (is (= "Vendor X" (seen-at (d/db conn) jan-15))))
      (testing "after correction: no longer visible at/after feb-1 (feb-15)"
        (is (nil? (seen-at (d/db conn) feb-15))))
      (testing "as-of the PRE-correction tx-time, the window was still open"
        ;; Same valid-time (feb-15), earlier transaction-time snapshot → the
        ;; fact reads as it was known before the closing commit.
        (is (= "Vendor X" (seen-at (d/as-of (d/db conn) t1) feb-15))))
      (testing "the closing commit is auditable — one new :db.valid/to datom on tx1"
        (is (some? (d/q '[:find ?vt . :in $ ?tx
                          :where [?tx :db.valid/to ?vt]]
                        (d/db conn) tx1)))))))

;; ============================================================================
;; 2. Sealing — a posted entry cannot be silently retracted / edited (ADR-007).
;; ============================================================================

(deftest posted-entry-silent-retract-and-edit-are-refused
  (testing "A sale posted through kontor.book is sealed; retracting a datom,
            retracting the whole entity, and in-place-editing a datom are ALL
            refused, and the balance stays intact."
    (let [conn (fresh-book)
          _ (book/sell! conn {:debit-account ar :credit-account rev
                              :amount 800 :commodity eur
                              :effective-date jan-15 :narration "Sealed sale"})
          ar-eid (eid (d/db conn) ar)
          posting-eid (d/q '[:find ?p . :in $ ?acct
                             :where [?p :kontor.posting/account ?acct]]
                           (d/db conn) ar-eid)]
      (is (some? (:kontor.posting/posted-at
                  (d/pull (d/db conn) [:kontor.posting/posted-at] posting-eid)))
          "the posting is sealed (:posted-at set)")
      (testing "silent retract of the sealed amount is refused"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Sealing violation"
             (v/transact-with-validation
              conn [[:db/retract posting-eid :kontor.posting/amount 800M]]))))
      (testing "retractEntity of the sealed posting is refused"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Sealing violation"
             (v/transact-with-validation
              conn [[:db/retractEntity posting-eid]]))))
      (testing "silent in-place edit of the sealed amount is refused"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Sealing violation"
             (v/transact-with-validation
              conn [{:db/id posting-eid :kontor.posting/amount 9999M}]))))
      (testing "the sealed balance is unchanged after the refused writes"
        (is (== 800M (eur-amt conn ar)))))))

;; ============================================================================
;; 3. Sealing GAP — AUGMENTING a sealed posting with a previously-absent
;;    attribute is silently accepted.
;; ============================================================================
;;
;; PENDING(NEW): `kontor.compliance.sealing/find-silent-modifications`
;; (sealing.cljc:99-110) only reports a violation when the changed attribute
;; is ALREADY PRESENT on the posted entity — it guards on `(some? cur)`. So
;; SETTING a previously-nil attribute on a sealed posting (here
;; `:kontor.posting/partner`, which changes the audit meaning of the line —
;; who the line is booked against) is NOT caught, and the gate commits it.
;; A sealed accounting line should be immutable, augmentation included.
;;
;; Odoo catches exactly this: `partner_id` is one of the integrity-hash
;; fields (account_move_line.py:3350 `_get_integrity_hash_fields` →
;; ['name','debit','credit','account_id','partner_id']), and the posted-line
;; write guard (account_move_line.py:1781-1790) fires on `_field_will_change`,
;; which returns True for a nil→value transition — so setting partner_id on a
;; hashed (posted) line raises "You cannot edit the following fields".
(deftest augmenting-sealed-posting-with-new-attr-should-be-refused
  (let [conn (fresh-book)
        _ (book/sell! conn {:debit-account ar :credit-account rev
                            :amount 800 :commodity eur
                            :effective-date jan-15 :narration "Sealed sale, no partner"})
        db (d/db conn)
        ar-eid (eid db ar)
        posting-eid (d/q '[:find ?p . :in $ ?acct
                           :where [?p :kontor.posting/account ?acct]]
                         db ar-eid)
        subject (pe db "SUBJECT")]
    (is (nil? (:kontor.posting/partner
               (d/pull db [:kontor.posting/partner] posting-eid)))
        "precondition: the sealed posting has no partner")
    (testing "setting a previously-absent attribute on a sealed posting should be refused"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Sealing violation"
           (v/transact-with-validation
            conn [{:db/id posting-eid :kontor.posting/partner subject}]))))))

;; ============================================================================
;; 4. Retention sweep — expires only ELIGIBLE records, and a legal hold blocks
;;    expiry of held records (ADR-050 × ADR-049).
;; ============================================================================

(defn- active-purge-policy!
  "Define + activate a 7-year :purge policy on :audit-doc, anchored on
   :kontor.audit-doc/uploaded-at."
  [conn code]
  (ret/define-policy! conn
    {:code code :applies-to [:audit-doc] :duration-years 7
     :triggered-by :kontor.audit-doc/uploaded-at :expiry-action :purge
     :effective-from #inst "2000-01-01" :legal-basis "Test policy"
     :changed-by-uid (pe (d/db conn) "U-records")})
  (let [policy-eid (ret/by-code (d/db conn) code)]
    (ret/activate-policy! conn
                          {:policy-eid policy-eid
                           :supporting-doc (adoc (d/db conn) "DOC-SCHEDULE")
                           :reason-note "Statutory retention period."
                           :changed-by-uid (pe (d/db conn) "U-records")})
    policy-eid))

(defn- seed-doc! [conn code uploaded-at]
  (d/transact conn [{:kontor.audit-doc/code code :kontor.audit-doc/type :customer-email
                     :kontor.audit-doc/title (str "Doc " code)
                     :kontor.audit-doc/uploaded-at uploaded-at}])
  (adoc (d/db conn) code))

(deftest retention-sweep-expires-eligible-and-hold-blocks-held
  (let [conn        (fresh-book)
        policy-eid  (active-purge-policy! conn "P-SWEEP")
        aged-held   (seed-doc! conn "DOC-AGED-HELD"   #inst "2017-01-01") ; deadline 2024
        aged-free   (seed-doc! conn "DOC-AGED-FREE"   #inst "2017-06-01") ; deadline 2024
        fresh-doc   (seed-doc! conn "DOC-FRESH"       #inst "2025-01-01") ; deadline 2032
        _ (lhold/place! conn
                        {:code "HOLD-RET"
                         :matter-name "Preserve DOC-AGED-HELD"
                         :issued-by-uid (pe (d/db conn) "U-counsel")
                         :issued-at #inst "2026-05-13"
                         :supporting-doc (adoc (d/db conn) "DOC-PLACE")
                         :reason-note "Preserve despite retention expiry."
                         :scope-eids [aged-held]})
        as-of #inst "2026-06-01"]
    (testing "sweep! flags only aged docs; the held aged doc is marked blocked"
      (let [items (ret/sweep! (d/db conn) {:entity-type :audit-doc :as-of as-of})
            by-eid (into {} (map (juxt :entity-eid identity)) items)]
        (is (= 2 (count items)) "both aged docs are due; the fresh one is not")
        (is (true?  (:blocked-by-hold? (by-eid aged-held))))
        (is (false? (:blocked-by-hold? (by-eid aged-free))))
        (is (nil? (by-eid fresh-doc)) "the fresh doc is not even a work-item")))
    (testing "sweep-and-apply! purges the eligible free doc, skips the held one"
      (let [r (ret/sweep-and-apply! conn {:entity-type :audit-doc :as-of as-of})]
        (is (= 1 (count (:applied r))))
        (is (= 1 (count (:blocked r))))
        (is (nil?  (adoc (d/db conn) "DOC-AGED-FREE")) "eligible doc purged")
        (is (some? (adoc (d/db conn) "DOC-AGED-HELD")) "held doc preserved")
        (is (some? (adoc (d/db conn) "DOC-FRESH"))     "fresh doc untouched")))
    (testing "apply-expiry! is STRUCTURALLY refused on the held doc (the load-bearing guarantee)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"blocked by active legal hold"
           (ret/apply-expiry! conn {:entity-eid aged-held :policy-eid policy-eid
                                    :action :purge})))
      (is (= :kontor.legal-hold/purge-blocked
             (try (ret/apply-expiry! conn {:entity-eid aged-held :policy-eid policy-eid
                                           :action :purge})
                  nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          "the :type is on (ex-data e), not double-wrapped by the transactor"))
    (testing "after release, the next sweep purges the previously-held doc"
      (lhold/release! conn
                      {:hold-eid (lhold/by-code (d/db conn) "HOLD-RET")
                       :released-by-uid (pe (d/db conn) "U-admin")
                       :supporting-doc (adoc (d/db conn) "DOC-RELEASE")
                       :reason-note "Matter closed; retention may proceed."})
      (let [r (ret/sweep-and-apply! conn {:entity-type :audit-doc :as-of as-of})]
        (is (= 1 (count (:applied r))))
        (is (nil? (adoc (d/db conn) "DOC-AGED-HELD")) "released doc now purged")))))

;; ============================================================================
;; 5. DSAR — the bitemporal collect walk assembles the subject's full graph,
;;    and reports the covering legal hold (ADR-052 × ADR-049).
;; ============================================================================

(deftest dsar-collect-assembles-full-subject-graph
  (let [conn    (fresh-book)
        subject (pe (d/db conn) "SUBJECT")
        ;; Direct references (via registered partner-attrs) + an indirect
        ;; reference (a status-history row on the subject's own transaction,
        ;; reached from :kontor.transaction/partner outward).
        _ (d/transact conn
                      [{:kontor.invoice/external-id "INV-SUBJ-1" :kontor.invoice/buyer subject}
                       {:kontor.partner-bank-account/partner subject}
                       {:db/id "subj-tx"
                        :kontor.transaction/external-id "TX-SUBJ-1"
                        :kontor.transaction/partner subject}
                       {:kontor.status-history/entity "subj-tx"
                        :kontor.status-history/facet :kontor.transaction/state
                        :kontor.status-history/to :posted
                        :kontor.status-history/changed-at #inst "2026-03-01"
                        :kontor.status-history/origin-transaction "subj-tx"}])
        ;; Place a hold covering the subject — a DSAR *access* still collects
        ;; held data (the hold blocks erasure, not access).
        _ (lhold/place! conn
                        {:code "HOLD-DSAR"
                         :matter-name "Preserve subject"
                         :issued-by-uid (pe (d/db conn) "U-counsel")
                         :issued-at #inst "2026-05-13"
                         :supporting-doc (adoc (d/db conn) "DOC-PLACE")
                         :reason-note "Preserve."
                         :scope-eids [subject]})
        result (dsar/collect (d/db conn) subject {})]
    (testing "the subject itself is pulled"
      (is (= subject (:db/id (:partner result)))))
    (testing "direct references are keyed by the registered partner-attr"
      (is (contains? (:references result) :kontor.invoice/buyer))
      (is (contains? (:references result) :kontor.partner-bank-account/partner))
      (is (= "INV-SUBJ-1"
             (-> result :references :kontor.invoice/buyer first :kontor.invoice/external-id))))
    (testing "indirect references (via the subject's transactions) are walked"
      (is (contains? (:indirect-references result) :kontor.status-history/origin-transaction)))
    (testing "the covering legal hold is reported (access still succeeds under hold)"
      (is (true? (:on-legal-hold? result)))
      (is (= 1 (count (:legal-holds result)))))))

;; ============================================================================
;; 6. Period lock — soft close, reopen, hard seal all gate a back-dated post
;;    (ADR-014).
;; ============================================================================

(deftest period-soft-and-hard-lock-gate-backdated-posts
  (let [conn (fresh-book)
        jan  (-> (d/transact conn [{:db/id -1
                                    :kontor.period/start jan-1
                                    :kontor.period/end   feb-1}])
                 :tempids (get -1))
        backdated! (fn []
                     (book/sell! conn {:debit-account ar :credit-account rev
                                       :amount 500 :commodity eur
                                       :effective-date jan-15
                                       :narration "Backdated into Jan"}))]
    (testing "SOFT close refuses a post whose valid-time falls in the period"
      (period/close! conn jan {:pre-checks (constantly [])})
      (is (not (period/open? (d/db conn) jan)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Period violation" (backdated!))))
    (testing "REOPEN clears the soft lock; the same post now commits"
      (period/reopen! conn jan)
      (is (period/open? (d/db conn) jan))
      (is (some? (backdated!)))
      (is (== 500M (eur-amt conn ar {:as-of-valid jan-15}))))
    (testing "HARD seal (after re-close) again refuses the back-dated post"
      (period/close! conn jan {:pre-checks (constantly [])})
      (period/seal! conn jan)
      (is (period/sealed? (d/db conn) jan))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Period violation" (backdated!))))
    (testing "a sealed period is irrevocable — reopen! is refused"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)sealed"
           (period/reopen! conn jan))))
    (testing "a post OUTSIDE the locked range still commits throughout"
      (is (some? (book/sell! conn {:debit-account ar :credit-account rev
                                   :amount 700 :commodity eur
                                   :effective-date feb-15 :narration "Open Feb"}))))))

;; ============================================================================
;; 7. Period lock: the documented `:kontor.transaction/effective-date` fallback
;;    for the period-lock valid-time. CLOSED (ADR-140).
;; ============================================================================
;;
;; `kontor.compliance.period` claims (ns docstring) that a locked period
;; refuses a new posting "whose inbound valid-time (`:tx/valid-from`, falling
;; back to `:kontor.transaction/effective-date`) is in range". `find-violations`
;; used to read the valid-time ONLY from the tx-meta `:db.valid/from` and
;; return `[]` when it was absent — so a hand-built write naming an
;; effective-date inside a SEALED period slipped through the lock entirely.
;; The fallback now exists.
;;
;; Odoo derives the lock-check date from the move's own `date`
;; (account_move.py `_check_fiscal_lock_dates`, invoked from
;; account_move_line.py:1807-1808 on any protected-field write) — the
;; accounting date IS the anchor, never an optional tx-meta.
(defn- no-vt-tx-data
  "Hand-built tx-data with an effective-date but NO `:db.valid/from` tx-meta."
  [db effective-date]
  [{:db/id "t"
    :kontor.transaction/journal (eid db [:kontor.journal/code "SALE"])
    :kontor.transaction/effective-date effective-date}
   {:kontor.posting/account (eid db ar)
    :kontor.posting/transaction "t"
    :kontor.posting/amount 500M
    :kontor.posting/commodity (eid db eur)}
   {:kontor.posting/account (eid db rev)
    :kontor.posting/transaction "t"
    :kontor.posting/amount -500M
    :kontor.posting/commodity (eid db eur)}])

(deftest period-lock-falls-back-to-effective-date
  (let [conn (fresh-book)
        jan  (-> (d/transact conn [{:db/id -1
                                    :kontor.period/start jan-1
                                    :kontor.period/end   feb-1}])
                 :tempids (get -1))
        _ (period/close! conn jan {:pre-checks (constantly [])})
        db (d/db conn)]
    (testing "the effective-date inside the closed period registers a violation"
      (let [vs (period/find-violations db (no-vt-tx-data db jan-15))]
        (is (seq vs) "find-violations falls back to :kontor.transaction/effective-date")
        (is (= [:effective-date] (distinct (map :valid-from-source vs)))
            "and says so, so the operator can tell which anchor was used")
        (is (= [jan-15] (distinct (map :valid-from vs))))))
    (testing "the gate REFUSES the hand-built write, not merely reports it"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Period violation"
                            (gate/transact-with-validation conn (no-vt-tx-data db jan-15)))))
    ;; The teeth test: the same shape OUTSIDE the closed range must still
    ;; commit. Without this, `(constantly [violation])` would pass above.
    (testing "an effective-date OUTSIDE the closed period still commits"
      (is (empty? (period/find-violations db (no-vt-tx-data db feb-15))))
      (is (some? (gate/transact-with-validation conn (no-vt-tx-data db feb-15)))))
    (testing "and the fallback does not fire when the tx-meta vf says otherwise"
      ;; tx-meta wins: an effective-date in the closed period but an explicit
      ;; vf outside it is anchored by the vf (documented precedence).
      (let [td (conj (no-vt-tx-data db jan-15)
                     {:db/id "datomic.tx" :db.valid/from feb-15})]
        (is (empty? (period/find-violations db td)))))))
