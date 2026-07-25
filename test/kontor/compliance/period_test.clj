(ns kontor.compliance.period-test
  "Period locking semantics:
     - Open periods accept postings inside their range.
     - Closed periods refuse new postings whose valid-from falls in.
     - Journal scope: a period scoped to journal X doesn't lock
       postings in journal Y.
     - Domain helpers: open?, close!, reopen!."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book.build :as book-build]
            [kontor.core :as core]
            [kontor.compliance.period :as period]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(def jan-15  #inst "2026-01-15T00:00:00Z")
(def feb-1   #inst "2026-02-01T00:00:00Z")
(def feb-15  #inst "2026-02-15T00:00:00Z")
(def feb-28  #inst "2026-02-28T23:59:59Z")
(def mar-1   #inst "2026-03-01T00:00:00Z")
(def mar-15  #inst "2026-03-15T00:00:00Z")

(defn- catalog!
  "Two journals (sales + general), two accounts, EUR. Returns a map of
   {:eur :rec :rev :sales-jnl :gen-jnl}."
  [conn]
  (d/transact
   conn
   [{:db/id -1 :kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
     :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
    {:db/id -2 :kontor.account/path "Assets:Receivable" :kontor.account/name "AR"
     :kontor.account/type :asset :kontor.account/active true}
    {:db/id -3 :kontor.account/path "Income:Sales" :kontor.account/name "Sales"
     :kontor.account/type :income :kontor.account/active true}
    {:db/id -4 :kontor.journal/code "INV" :kontor.journal/name "Sales invoices"
     :kontor.journal/type :sale :kontor.journal/active true}
    {:db/id -5 :kontor.journal/code "GEN" :kontor.journal/name "General journal"
     :kontor.journal/type :general :kontor.journal/active true}])
  (let [db (d/db conn)]
    {:eur       (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
     :rec       (:db/id (d/entity db [:kontor.account/path "Assets:Receivable"]))
     :rev       (:db/id (d/entity db [:kontor.account/path "Income:Sales"]))
     :sales-jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))
     :gen-jnl   (:db/id (d/entity db [:kontor.journal/code "GEN"]))}))

(defn- open-period!
  "Create an OPEN period covering [start, end), optionally scoped to
   `journal-eid`. Returns the period eid."
  ([conn start end] (open-period! conn start end nil))
  ([conn start end journal-eid]
   (let [report (d/transact conn [(cond-> {:db/id        -1
                                           :kontor.period/start start
                                           :kontor.period/end   end}
                                    journal-eid (assoc :kontor.period/journal journal-eid))])]
     (-> report :tempids (get -1)))))

(defn- closed-period!
  "Create + close in two steps. Returns the period eid. Tests that
   don't care about pre-close validation pass `:pre-checks
   (constantly [])` to bypass — the real surgery is exercised in
   the dedicated pre-close tests below."
  ([conn start end] (closed-period! conn start end nil))
  ([conn start end journal-eid]
   (let [eid (open-period! conn start end journal-eid)]
     (period/close! conn eid {:pre-checks (constantly [])})
     eid)))

(defn- mk-tx
  [{:keys [eur rec rev sales-jnl]} effective-date]
  (posting/build-transaction
   {:transaction
    {:kontor.transaction/external-id    (str "TX-" (.getTime ^java.util.Date effective-date))
     :kontor.transaction/journal        sales-jnl
     :kontor.transaction/effective-date effective-date
     :kontor.transaction/narration      "test"}
    :postings
    [{:kontor.posting/account rec :kontor.posting/amount  100M :kontor.posting/commodity eur}
     {:kontor.posting/account rev :kontor.posting/amount -100M :kontor.posting/commodity eur}]}))

;; ============================================================================
;; find-violations (pure)
;; ============================================================================

(deftest no-violations-when-no-locked-periods
  (let [conn (core/create-test-db)
        cat  (catalog! conn)]
    (is (= [] (period/find-violations (d/db conn) (mk-tx cat feb-15))))))

(deftest no-violations-when-period-is-open
  (let [conn (core/create-test-db)
        cat  (catalog! conn)
        _    (open-period! conn feb-1 mar-1)]
    (is (= [] (period/find-violations (d/db conn) (mk-tx cat feb-15))))))

(deftest violation-when-posting-falls-in-locked-period
  (let [conn (core/create-test-db)
        cat  (catalog! conn)
        _    (closed-period! conn feb-1 mar-1)
        violations (period/find-violations (d/db conn) (mk-tx cat feb-15))]
    (is (= 2 (count violations))                            ;; both postings in tx
        "Both balance-affecting postings violate the closed Feb period.")
    (is (every? #(= feb-15 (:valid-from %)) violations))))

(deftest no-violation-when-posting-after-period-end
  (let [conn (core/create-test-db)
        cat  (catalog! conn)
        _    (closed-period! conn feb-1 mar-1)]
    (is (= [] (period/find-violations (d/db conn) (mk-tx cat mar-15)))
        "Posting on Mar-15 is OUTSIDE the [Feb-1, Mar-1) period.")))

(deftest no-violation-when-posting-on-period-end-exclusive
  (let [conn (core/create-test-db)
        cat  (catalog! conn)
        _    (closed-period! conn feb-1 mar-1)]
    (is (= [] (period/find-violations (d/db conn) (mk-tx cat mar-1)))
        "Posting at the period-end boundary is excluded ([start, end)).")))

(deftest journal-scoped-period-doesnt-lock-other-journals
  (testing "A period scoped to :sales-jnl must not lock postings in
            :gen-jnl (mirroring how Odoo's per-journal closes work)."
    (let [conn (core/create-test-db)
          {:keys [eur rec rev sales-jnl gen-jnl] :as cat} (catalog! conn)
          _    (closed-period! conn feb-1 mar-1 sales-jnl)
          gen-tx (posting/build-transaction
                  {:transaction
                   {:kontor.transaction/external-id    "GEN-1"
                    :kontor.transaction/journal        gen-jnl
                    :kontor.transaction/effective-date feb-15
                    :kontor.transaction/narration      "general entry in Feb"}
                   :postings
                   [{:kontor.posting/account rec :kontor.posting/amount  100M :kontor.posting/commodity eur}
                    {:kontor.posting/account rev :kontor.posting/amount -100M :kontor.posting/commodity eur}]})
          sales-tx (mk-tx cat feb-15)]
      (is (= [] (period/find-violations (d/db conn) gen-tx))
          "General-journal posting is not locked by sales-only period.")
      (is (seq (period/find-violations (d/db conn) sales-tx))
          "Sales-journal posting IS locked by sales-only period."))))

;; ============================================================================
;; transact-with-validation integration
;; ============================================================================

(deftest validation-rejects-posting-in-locked-period
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        cat (catalog! conn)
        _ (closed-period! conn feb-1 mar-1)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Period violation"
         (v/transact-with-validation conn (mk-tx cat feb-15))))))

(deftest validation-passes-for-posting-outside-locked-period
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        cat (catalog! conn)
        _ (closed-period! conn feb-1 mar-1)]
    (is (some? (v/transact-with-validation conn (mk-tx cat mar-15))))))

;; ============================================================================
;; Domain helpers
;; ============================================================================

(deftest open?-recognizes-open-and-closed
  (let [conn (core/create-test-db)
        _    (catalog! conn)
        open-eid   (open-period! conn feb-1 mar-1)
        closed-eid (closed-period! conn mar-1 #inst "2026-04-01T00:00:00Z")]
    (is (period/open? (d/db conn) open-eid))
    (is (not (period/open? (d/db conn) closed-eid)))))

(deftest close!-sets-locked-at-and-records-tx
  (let [conn (core/create-test-db)
        _    (catalog! conn)
        eid  (open-period! conn feb-1 mar-1)
        _    (period/close! conn eid {:pre-checks (constantly [])})
        e    (d/pull (d/db conn) [:kontor.period/locked-at :kontor.period/lock-tx] eid)]
    (is (some? (:kontor.period/locked-at e)))
    (is (some? (:kontor.period/lock-tx e)) "lock-tx must be backfilled")))

(deftest close!-rejects-already-closed
  (let [conn (core/create-test-db)
        _    (catalog! conn)
        eid  (closed-period! conn feb-1 mar-1)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Period already closed"
         (period/close! conn eid {:pre-checks (constantly [])})))))

(deftest reopen!-clears-lock
  (let [conn (core/create-test-db)
        _    (catalog! conn)
        eid  (closed-period! conn feb-1 mar-1)
        _    (period/reopen! conn eid)]
    (is (period/open? (d/db conn) eid))))

(deftest reopen!-rejects-already-open
  (let [conn (core/create-test-db)
        _    (catalog! conn)
        eid  (open-period! conn feb-1 mar-1)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Period already open"
         (period/reopen! conn eid)))))

;; ============================================================================
;; ADR-014: hard sealing
;; ============================================================================

(deftest seal!-requires-soft-close-first
  (let [conn (core/create-test-db)
        _    (catalog! conn)
        open-eid (open-period! conn feb-1 mar-1)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"must be soft-closed"
         (period/seal! conn open-eid)))))

(deftest seal!-rejects-already-sealed
  (let [conn (core/create-test-db)
        _    (catalog! conn)
        eid  (closed-period! conn feb-1 mar-1)
        _    (period/seal! conn eid)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"already sealed"
         (period/seal! conn eid)))))

(deftest seal!-monotone-rejects-out-of-order
  (testing "Sealing earlier than an already-sealed period is refused
            so the seal sequence stays monotone."
    (let [conn (core/create-test-db)
          _    (catalog! conn)
          jan-eid (open-period! conn #inst "2026-01-01" feb-1)
          mar-eid (open-period! conn mar-1 #inst "2026-04-01")
          _ (period/close! conn jan-eid {:pre-checks (constantly [])})
          _ (period/close! conn mar-eid {:pre-checks (constantly [])})
          _ (period/seal! conn mar-eid)]   ;; seal March first
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"later period is already sealed"
           (period/seal! conn jan-eid))
          "Sealing January after March was sealed must refuse — would
           create non-monotone seal sequence."))))

(deftest reopen!-refuses-sealed
  (let [conn (core/create-test-db)
        _    (catalog! conn)
        eid  (closed-period! conn feb-1 mar-1)
        _    (period/seal! conn eid)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"irrevocable"
         (period/reopen! conn eid)))))

(deftest sealed-period-blocks-new-postings
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        cat (catalog! conn)
        eid (closed-period! conn feb-1 mar-1)
        _ (period/seal! conn eid)
        ;; A posting backdated into the sealed Feb range
        bad-tx (mk-tx cat feb-15)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Period violation"
         (v/transact-with-validation conn bad-tx))
        "Sealed periods reject new postings just like soft-closed ones.")))

(deftest sealed-period-blocks-retract
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        _ (catalog! conn)
        eid (closed-period! conn feb-1 mar-1)
        _ (period/seal! conn eid)
        ;; Try to silently mutate the sealed period entity
        bad-write [{:db/id eid :kontor.period/start jan-15}]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Sealing violation"
         (v/transact-with-validation conn bad-write))
        "Sealed period entities cannot be written/retracted via the
         validated transact.")))

;; ============================================================================
;; ADR-014 / ADR-140: the ADJUSTMENT period actually locks something
;;
;; `:kontor.period/tag` documents SAP-style special periods 13..16, and
;; `:kontor.period/adjustment?`'s `:db/doc` says "Postings opt into the
;; adjustment period via :kontor.posting/period-tag". The lock predicate
;; honoured that tag — but `kontor.book`, the verb facade every business
;; write is supposed to go through, rebuilt each posting from a fixed key
;; list that did not include `:period-tag`. The key was silently dropped, so
;; no posting written through the public API could ever carry one, and a
;; period tagged anything other than `:normal` closed and sealed cleanly
;; while locking nothing writable. The promise was checked and unreachable.
;;
;; Each test below pairs the REFUSAL with the write that must still be
;; accepted, so "the lock has teeth" cannot be satisfied by a lock that
;; refuses everything (nor by one that refuses nothing).
;; ============================================================================

(defn- tagged-period!
  "Create + soft-close a period over [start,end) carrying `:kontor.period/tag`."
  [conn start end tag]
  (let [eid (-> (d/transact conn [{:db/id -1
                                   :kontor.period/start start
                                   :kontor.period/end end
                                   :kontor.period/tag tag
                                   :kontor.period/adjustment? true}])
                :tempids (get -1))]
    (period/close! conn eid {:pre-checks (constantly [])})
    eid))

(defn- book-entry
  "A two-leg entry through the `kontor.book` verb facade — the public write
   path — optionally carrying `:period-tag`."
  [{:keys [eur rec rev sales-jnl]} effective-date tag]
  (book-build/entry-tx-data
   (cond-> {:debit-account rec :credit-account rev :amount 100M
            :commodity eur :journal sales-jnl :effective-date effective-date}
     tag (assoc :period-tag tag))))

(deftest book-can-write-a-period-tag-at-all
  ;; The precondition everything below rests on: the verb facade must not
  ;; silently drop the key.
  (let [conn (core/create-test-db)
        cat  (catalog! conn)
        tags (fn [td] (into #{} (keep :kontor.posting/period-tag) td))]
    (is (= #{:adjustment-13} (tags (book-entry cat feb-15 :adjustment-13)))
        "entry-level :period-tag reaches every posting")
    (is (= #{} (tags (book-entry cat feb-15 nil)))
        "and is not invented when the caller did not ask for one")
    (testing "a per-posting :period-tag overrides the entry-level one"
      (let [td (book-build/entry-tx-data
                {:journal (:sales-jnl cat) :effective-date feb-15
                 :commodity (:eur cat) :period-tag :adjustment-13
                 :postings [{:account (:rec cat) :amount 100M :period-tag :adjustment-14}
                            {:account (:rev cat) :amount -100M}]})]
        (is (= #{:adjustment-14 :adjustment-13} (tags td)))))))

(deftest a-closed-adjustment-period-refuses-the-tagged-write
  (let [conn (core/create-test-db)
        _    (v/install-invariants! conn)
        cat  (catalog! conn)
        _    (tagged-period! conn feb-1 mar-1 :adjustment-13)]
    (testing "a posting routed into the closed :adjustment-13 period is REFUSED"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Period violation"
           (v/transact-with-validation conn (book-entry cat feb-15 :adjustment-13)))))
    ;; TEETH #1: the lock is tag-SCOPED, not date-scoped. Closing period 13
    ;; must not close the ordinary February book — that is the entire point
    ;; of a special period, and a lock that refused this would be worse than
    ;; no lock at all.
    (testing "an UNtagged posting on the same date still commits"
      (is (some? (v/transact-with-validation conn (book-entry cat feb-15 nil)))))
    ;; TEETH #2: a different special period is a different bucket.
    (testing "a posting tagged :adjustment-14 still commits"
      (is (some? (v/transact-with-validation conn (book-entry cat feb-15 :adjustment-14)))))
    ;; TEETH #3: the lock is still date-scoped WITHIN the tag.
    (testing "an :adjustment-13 posting OUTSIDE the range still commits"
      (is (some? (v/transact-with-validation conn (book-entry cat mar-15 :adjustment-13)))))))

(deftest a-closed-normal-period-does-not-lock-the-adjustment-bucket
  ;; The converse routing direction, and the reason the tag exists: HGB
  ;; year-end corrections dated 31 December must post after the ordinary
  ;; December book is closed.
  (let [conn (core/create-test-db)
        _    (v/install-invariants! conn)
        cat  (catalog! conn)
        _    (closed-period! conn feb-1 mar-1)]
    (testing "the untagged February book is closed"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Period violation"
           (v/transact-with-validation conn (book-entry cat feb-15 nil)))))
    (testing "but the :adjustment-13 bucket over the same dates is open"
      (is (some? (v/transact-with-validation conn (book-entry cat feb-15 :adjustment-13)))))))

(deftest a-sealed-adjustment-period-refuses-the-tagged-write
  (let [conn (core/create-test-db)
        _    (v/install-invariants! conn)
        cat  (catalog! conn)
        eid  (tagged-period! conn feb-1 mar-1 :adjustment-13)]
    (period/seal! conn eid)
    (is (period/sealed? (d/db conn) eid))
    (testing "sealing an adjustment period refuses the tagged backdated write"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Period violation"
           (v/transact-with-validation conn (book-entry cat feb-15 :adjustment-13)))))
    (testing "and the untagged book over the same dates is still writable"
      (is (some? (v/transact-with-validation conn (book-entry cat feb-15 nil)))))))

;; ============================================================================
;; ADR-014: pre-close checks
;; ============================================================================

(deftest close!-refuses-when-drafts-in-range
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        ;; Need real catalog with rec/rev account refs
        _ (d/transact conn
                      [{:db/id -10 :kontor.account/path "Assets:Receivable"
                        :kontor.account/name "AR" :kontor.account/type :asset
                        :kontor.account/active true}
                       {:db/id -11 :kontor.account/path "Income:Sales"
                        :kontor.account/name "Sales" :kontor.account/type :income
                        :kontor.account/active true}])
        {:keys [eur sales-jnl]} (catalog! conn)
        rec (:db/id (d/entity (d/db conn) [:kontor.account/path "Assets:Receivable"]))
        rev (:db/id (d/entity (d/db conn) [:kontor.account/path "Income:Sales"]))
        period-eid (open-period! conn feb-1 mar-1)
        tx (posting/build-transaction
            {:transaction
             {:kontor.transaction/external-id    "DRAFT-INV"
              :kontor.transaction/journal        sales-jnl
              :kontor.transaction/effective-date feb-15
              :kontor.transaction/narration      "Pending"
              :kontor.transaction/state          :draft}
             :postings
             [{:kontor.posting/account rec :kontor.posting/amount  100M :kontor.posting/commodity eur}
              {:kontor.posting/account rev :kontor.posting/amount -100M :kontor.posting/commodity eur}]})]
    (v/transact-with-validation conn tx)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"pre-close"
         (period/close! conn period-eid))
        "close! with default :pre-checks must refuse — 1 draft posting in range.")))

(deftest close!-passes-when-no-drafts-and-balanced
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        _ (d/transact conn
                      [{:db/id -10 :kontor.account/path "Assets:Receivable"
                        :kontor.account/name "AR" :kontor.account/type :asset
                        :kontor.account/active true}
                       {:db/id -11 :kontor.account/path "Income:Sales"
                        :kontor.account/name "Sales" :kontor.account/type :income
                        :kontor.account/active true}])
        {:keys [eur sales-jnl]} (catalog! conn)
        rec (:db/id (d/entity (d/db conn) [:kontor.account/path "Assets:Receivable"]))
        rev (:db/id (d/entity (d/db conn) [:kontor.account/path "Income:Sales"]))
        period-eid (open-period! conn feb-1 mar-1)
        tx (-> (posting/build-transaction
                {:transaction
                 {:kontor.transaction/external-id    "FEB-CLEAN"
                  :kontor.transaction/journal        sales-jnl
                  :kontor.transaction/effective-date feb-15
                  :kontor.transaction/narration      "Clean"
                  :kontor.transaction/state          :posted
                  :kontor.transaction/posted-at      feb-15}
                 :postings
                 [{:kontor.posting/account rec :kontor.posting/amount  100M :kontor.posting/commodity eur}
                  {:kontor.posting/account rev :kontor.posting/amount -100M :kontor.posting/commodity eur}]})
               (->> (mapv #(if (some? (:kontor.posting/account %))
                             (assoc % :kontor.posting/posted-at feb-15)
                             %))))
        _ (v/transact-with-validation conn tx)]
    (is (some? (period/close! conn period-eid))
        "Default pre-checks pass: no drafts, trial-balance is zero.")))
