(ns kontor.book.reversal-contract-test
  "The WRITE side's option contract, asserted STRUCTURALLY — the sibling of
   `kontor.reporting.option-contract-test` (read its docstring first; the
   reasoning is the same one).

   Background: `kontor.book/reverse-tx-data` rebuilt each reversing leg from
   a hand-kept pull spec plus a hand-kept `cond->`. Every attribute neither
   of them named was silently dropped from the reversal. That has now
   happened four times with a different key each time — `:ledger`,
   `:period-tag`, `:analytic-distributions`, `:actor` — so a test per key
   would only ever cover the keys someone already thought about. Two of the
   four were P0s:

     - `:period-tag`: the reversal landed untagged, i.e. in `:normal`. The
       adjustment period never netted to zero, the normal period was
       polluted, and — because
       `kontor.compliance.period/closed-periods-covering` matches a lock on
       `(= tag period-tag)` — a reversal out of a HARD-SEALED
       `:adjustment-13` period was accepted.
     - `:analytic-distributions`: an entry on an account carrying
       `:kontor.account/required-analytic-plans` was IRREVERSIBLE — the
       gate refused the undistributed reversal with
       `:kontor.analytic/required-plan-unsatisfied`. Since ADR-007 makes a
       correction a reversal plus a re-posting and never an in-place edit,
       such an entry could never be corrected at all.

   So the property asserted here is the round trip, over DATOMS rather than
   over a list of keys:

     post an entry with every posting option set, reverse it, and require
     the reversal's postings to carry the SAME kernel attributes as the
     originals, modulo sign and the three attributes a reversal must by
     definition differ in.

   Read off `d/datoms`, that covers options nobody has invented yet: a new
   attribute that reaches a posting through the facade and not through the
   reversal fails [[reversal-preserves-every-posting-attribute]] without
   anyone editing this file. [[every-posting-option-is-exercised]] is the
   link that keeps it honest — the fixture must set every key in
   `posting-option-keys`, or the round trip would be asserted over an
   attribute the original never carried.

   ADR-170."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.book.build :as build]
            [kontor.core :as core]
            [kontor.gate :as gate]))

;; ---------------------------------------------------------------------------
;; Fixture: a book with one of everything a posting option can point at
;; ---------------------------------------------------------------------------

(def ^:private cc-plan [:kontor.analytic-plan/code "CC"])
(def ^:private cc-acct [:kontor.analytic-account/path "CC:Eng"])

(defn- book
  []
  (let [conn (core/create-test-db)]
    (gate/transact-with-validation
     conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
            :kontor.commodity/precision 2}
           {:kontor.journal/code "GJ" :kontor.journal/type :general}
           {:kontor.entity/code "E1" :kontor.entity/name "Entity One"}
           {:kontor.ledger/code "IFRS" :kontor.ledger/name "IFRS book"}
           {:kontor.partner/external-id "P1" :kontor.partner/name "Partner One"}
           {:db/id -10 :kontor.analytic-plan/code "CC"
            :kontor.analytic-plan/name "Cost centre"}
           {:kontor.analytic-account/path "CC:Eng"
            :kontor.analytic-account/code "ENG"
            :kontor.analytic-account/plan -10}
           ;; the account that makes the P0-B path reachable: a posting
           ;; against it MUST be fully distributed under CC (ADR-140)
           {:kontor.account/path "Expenses:Ops" :kontor.account/code "6000"
            :kontor.account/type :expense :kontor.account/active true
            :kontor.account/required-analytic-plans [cc-plan]}
           {:kontor.account/path "Assets:Cash" :kontor.account/code "1000"
            :kontor.account/type :asset :kontor.account/active true}])
    conn))

(def ^:private posting-fixture
  "A value for EVERY key in `build/posting-option-keys`, chosen so a dropped
   key shows up as a missing attribute rather than as a coincidence
   (`:display-type :tax` rather than the `:product` default; a non-`:normal`
   `:period-tag`; a two-axis `:dimensions` map).

   [[every-posting-option-is-exercised]] pins this map to
   `posting-option-keys`, so adding an option without adding a fixture value
   for it fails loudly instead of quietly shrinking the property below."
  {:account                "Expenses:Ops"
   :amount                 50M
   :commodity              :EUR
   :entity                 [:kontor.entity/code "E1"]
   :partner                [:kontor.partner/external-id "P1"]
   :ledger                 [:kontor.ledger/code "IFRS"]
   :dimensions             {:cost-centre "CC-1" :project "PRJ-9"}
   :period-tag             :adjustment-13
   :analytic-distributions [{:plan cc-plan :account cc-acct :percent 100M}]
   :narration              "per-leg narration"
   :display-type           :tax})

(defn- full-entry!
  "Post a two-leg entry in which BOTH legs carry every posting option — the
   counter-leg differs only in account and sign, so the round trip is
   asserted twice and an option that survives on one leg only cannot hide."
  [conn opts]
  (book/entry! conn (merge {:journal        "GJ"
                            :effective-date #inst "2026-12-31"
                            :postings
                            [posting-fixture
                             (assoc posting-fixture
                                    :account "Assets:Cash"
                                    :amount  -50M)]}
                           opts)))

;; ---------------------------------------------------------------------------
;; The structural comparison: an entity's datoms, sub-entities expanded
;; ---------------------------------------------------------------------------

(declare entity-shape)

(defn- unique-identity-attrs
  [db]
  (into #{} (keep (fn [[a m]] (when (= :db.unique/identity (:db/unique m)) a)))
        (d/schema db)))

(defn- catalog-entity?
  "True for an entity carrying a `:db.unique/identity` attribute — an
   account, commodity, ledger, entity, partner, analytic plan/account. The
   original and its reversal must reference the SAME one, so those are
   compared by eid.

   False for the value-like sub-entities a posting owns outright (a
   `:posting-dimension`, an `:analytic-distribution`), which the reversal
   must own FRESH COPIES of — `:kontor.analytic-distribution/posting` is a
   cardinality-one back-ref, so one distribution entity cannot describe two
   postings. Those are therefore compared by CONTENT."
  [db uniq eid]
  (boolean (some uniq (map :a (d/datoms db :eavt eid)))))

(defn- entity-shape
  "`{attr #{value …}}` for `eid`, with non-catalog ref targets replaced by
   their own shape (to `depth` levels). Cardinality-many collapses into the
   value set naturally, so ordering never enters the comparison."
  [db sch uniq depth eid]
  (reduce (fn [m dtm]
            (let [v (:v dtm)
                  ref? (= :db.type/ref (:db/valueType (get sch (:a dtm))))]
              (update m (:a dtm) (fnil conj #{})
                      (if (and ref? (pos? depth) (not (catalog-entity? db uniq v)))
                        (entity-shape db sch uniq (dec depth) v)
                        v))))
          {}
          (d/datoms db :eavt eid)))

(def ^:private may-differ
  "The ONLY attributes a reversal is allowed to differ in. Anything else
   differing is the defect this namespace exists to catch — including an
   attribute that does not exist yet.

     :transaction — it is a different transaction, by construction.
     :posted-at   — the reversal is sealed when it is made, not when the
                    original was.
     :amount      — sign-flipped; asserted separately and exactly."
  #{:kontor.posting/transaction
    :kontor.posting/posted-at
    :kontor.posting/amount})

(defn- posting-shapes
  "`{account-eid shape}` for every posting of transaction `tx`."
  [db tx]
  (let [sch  (d/schema db)
        uniq (unique-identity-attrs db)]
    (into {}
          (for [p (d/q '[:find [?p ...] :in $ ?t
                         :where [?p :kontor.posting/transaction ?t]]
                       db tx)]
            [(:v (first (d/datoms db :eavt p :kontor.posting/account)))
             (entity-shape db sch uniq 2 p)]))))

;; ---------------------------------------------------------------------------
;; 1. The fixture is complete — the link that makes the property general
;; ---------------------------------------------------------------------------

(deftest every-posting-option-is-exercised
  (is (= build/posting-option-keys (set (keys posting-fixture)))
      (str "the round-trip property is only as wide as the entry it reverses. "
           "A new key in posting-option-keys needs a value here — pick one "
           "that differs from the attribute's default so a drop is visible.")))

(deftest reversal-mirror-covers-every-posting-option
  ;; `kontor.book` refuses to LOAD when these diverge — so in practice this
  ;; assertion can only fail by being read here after someone weakened the
  ;; load-time guard. Stated anyway: it is the invariant, and a load-time
  ;; throw is not a test result.
  (is (= build/posting-option-keys (set (keys @#'book/reversible-posting-options)))
      (str "every option the facade admits must be re-emittable by "
           "reverse-tx-data, or a reversal silently drops it (ADR-170)")))

;; ---------------------------------------------------------------------------
;; 2. THE property: a reversal is the original modulo sign
;; ---------------------------------------------------------------------------

(deftest reversal-preserves-every-posting-attribute
  (let [conn (book)
        _    (full-entry! conn {:external-id "RT-1"})
        db0  (d/db conn)
        orig (book/resolve-transaction db0 "RT-1")]
    (book/reverse! conn {:transaction "RT-1" :reversal-date #inst "2026-12-31"})
    (let [db   (d/db conn)
          rev  (book/reversal-of db orig)
          os   (posting-shapes db orig)
          rs   (posting-shapes db rev)]
      (is (= 2 (count os) (count rs)) "leg counts match")
      (is (= (set (keys os)) (set (keys rs)))
          "the reversal hits the same accounts")
      (doseq [acct (keys os)]
        (let [o (get os acct)
              r (get rs acct)]
          (testing (str "account " acct)
            (is (= (set (keys o)) (set (keys r)))
                (str "the reversal carries exactly the attributes the original "
                     "does. A key present on the left and missing on the right "
                     "is an option the facade can write and reverse! drops — "
                     "the ADR-170 defect."))
            (is (= (apply dissoc o may-differ)
                   (apply dissoc r may-differ))
                "every attribute except sign, sealing time and the tx link")
            (is (= (into #{} (map -) (:kontor.posting/amount o))
                   (:kontor.posting/amount r))
                ":kontor.posting/amount is exactly negated")))))))

(deftest reversal-owns-fresh-sub-entities
  ;; The content comparison above passes whether the sub-entities are shared
  ;; or copied, so the "copied" half is asserted here: a distribution's
  ;; :kontor.analytic-distribution/posting back-ref is cardinality-ONE, so a
  ;; shared entity could not describe both postings, and two sealed documents
  ;; must not share mutable state (ADR-007).
  (let [conn (book)
        _    (full-entry! conn {:external-id "RT-2"})
        db0  (d/db conn)
        orig (book/resolve-transaction db0 "RT-2")]
    (book/reverse! conn {:transaction "RT-2" :reversal-date #inst "2026-12-31"})
    (let [db  (d/db conn)
          rev (book/reversal-of db orig)
          subs (fn [tx attr]
                 (set (d/q '[:find [?d ...] :in $ ?t ?a
                             :where [?p :kontor.posting/transaction ?t]
                             [?p ?a ?d]]
                           db tx attr)))]
      (doseq [attr [:kontor.posting/analytic-distributions
                    :kontor.posting/dimensions]]
        (let [o (subs orig attr) r (subs rev attr)]
          (is (seq o) (str attr " is actually present on the original"))
          (is (= (count o) (count r)) (str attr " count mirrors"))
          (is (empty? (set/intersection o r))
              (str attr " must be FRESH entities, never the original's")))))))

;; ---------------------------------------------------------------------------
;; 3. The two P0s, as named regressions
;; ---------------------------------------------------------------------------

(deftest reversal-inherits-the-period-tag
  (let [conn (book)
        _    (full-entry! conn {:external-id "P0A"})
        orig (book/resolve-transaction (d/db conn) "P0A")]
    (book/reverse! conn {:transaction "P0A" :reversal-date #inst "2026-12-31"})
    (let [db  (d/db conn)
          rev (book/reversal-of db orig)]
      (is (= #{:adjustment-13}
             (set (d/q '[:find [?tag ...] :in $ ?t :where
                         [?p :kontor.posting/transaction ?t]
                         [(get-else $ ?p :kontor.posting/period-tag :normal) ?tag]]
                       db rev)))
          "the reversal stays in the adjustment layer it is reversing"))))

(deftest a-sealed-adjustment-period-refuses-the-reversal
  ;; The consequence that makes the dropped tag a P0 rather than a report
  ;; nuisance: closed-periods-covering matches on (= tag period-tag), so an
  ;; untagged reversal is not matched by a seal on :adjustment-13 and the
  ;; hard lock refuses nothing.
  (let [conn (book)
        _    (full-entry! conn {:external-id "P0A-SEAL"})]
    (gate/transact-with-validation
     conn [{:kontor.period/start #inst "2026-01-01"
            :kontor.period/end   #inst "2027-01-01"
            :kontor.period/tag   :adjustment-13
            :kontor.period/sealed-at #inst "2027-01-15"}])
    (is (thrown? clojure.lang.ExceptionInfo
                 (book/reverse! conn {:transaction   "P0A-SEAL"
                                      :reversal-date #inst "2026-12-31"}))
        "a hard seal on the adjustment period refuses the reversal too")))

(deftest an-analytic-required-account-is-reversible
  ;; P0-B. Without the distribution mirror the gate refuses the reversal with
  ;; :kontor.analytic/required-plan-unsatisfied, which under ADR-007 (a
  ;; correction is a reversal plus a re-posting) makes the entry permanently
  ;; uncorrectable.
  (let [conn (book)
        _    (full-entry! conn {:external-id "P0B"})
        orig (book/resolve-transaction (d/db conn) "P0B")]
    (is (some? (book/reverse! conn {:transaction "P0B"
                                    :reversal-date #inst "2026-12-31"})))
    (let [db (d/db conn)]
      (is (= [100M]
             (d/q '[:find [?pct ...] :in $ ?o :where
                    [?r :kontor.transaction/reverses ?o]
                    [?p :kontor.posting/transaction ?r]
                    [?p :kontor.posting/account ?a]
                    [?a :kontor.account/path "Expenses:Ops"]
                    [?p :kontor.posting/analytic-distributions ?d]
                    [?d :kontor.analytic-distribution/percent ?pct]]
                  db orig))
          "percent is NOT negated — the sign lives on the posting amount, and
           kontor.posting.validate requires a percent in [0,100]"))))

;; ---------------------------------------------------------------------------
;; 4. The period-tag override is explicit, never silent
;; ---------------------------------------------------------------------------

(deftest period-tag-override-is-an-explicit-act
  (let [conn (book)
        _    (full-entry! conn {:external-id "OV"})
        orig (book/resolve-transaction (d/db conn) "OV")]
    (book/reverse! conn {:transaction   "OV"
                         :reversal-date #inst "2027-02-01"
                         :period-tag    :normal})
    (let [db (d/db conn)]
      (is (= #{:normal}
             (set (d/q '[:find [?tag ...] :in $ ?o :where
                         [?r :kontor.transaction/reverses ?o]
                         [?p :kontor.posting/transaction ?r]
                         [(get-else $ ?p :kontor.posting/period-tag :normal) ?tag]]
                       db orig)))
          "an operator who says where the correction belongs gets it there"))))

(deftest reverse-refuses-an-unknown-option
  ;; A mistyped :reversal-date used to be swallowed by a select-keys and the
  ;; reversal quietly landed on TODAY — in a different period than the one
  ;; asked for, which is the single thing this builder exists to control.
  (let [conn (book)
        _    (full-entry! conn {:external-id "TYPO"})
        d    (try (book/reverse-tx-data (d/db conn)
                                        {:transaction  "TYPO"
                                         :reverse-date #inst "2027-01-01"})
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :kontor.book/unknown-option (:type d)))
    (is (= [:reverse-date] (:unknown d)))
    (is (contains? (:known d) :reversal-date)
        "a near-miss typo can be diagnosed")))
