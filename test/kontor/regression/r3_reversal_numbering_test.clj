(ns kontor.regression.r3-reversal-numbering-test
  "R3 audit — generic GL reversal, gapless legal numbering, and per-entry
   hash chaining vs Odoo / ERPNext / Tryton.

   kontor ships the *attribute* substrate for reversals
   (`:kontor.transaction/reverses`, ADR-007) and DB-wide tamper-evidence
   (datahike's commit DAG + `kontor.compliance.sealing`, ADR-007), but three
   capabilities a statutory ledger needs are ABSENT from the kernel:

     (a) a GENERIC GL reverse builder — sign-flip a posted transaction at a
         chosen reversal date, link `:reverses`, and optionally auto-settle
         the original. Only the document-specific `kontor.document.invoice/cancel!`
         reverses today; a raw `kontor.book/entry!` GL entry has no reverse path.
         Odoo: account/models/account_move.py:5430 `_reverse_moves`
         (+ storno at :5464).

     (b) gapless per-journal legal-number ALLOCATION + gap DETECTION.
         `:kontor.journal/sequence-prefix` is a bare string; the legal number
         lands in caller-supplied `:kontor.transaction/external-id`. Nothing
         allocates the next number, and nothing detects a hole.
         Odoo: account_move.py:4155 `_get_last_sequence` (gapless per journal);
         :972 `_compute_made_sequence_gap`.

     (c) a per-entry CHAINED HASH an NF525 / GoBD auditor re-verifies. Sealing
         is DB-wide (a silent retract of a posted entity is rejected), but there
         is no per-document `inalterable_hash` / `secure_sequence_number` an
         auditor can independently recompute.
         Odoo: account_move.py:353-354 (`secure_sequence_number` +
         `inalterable_hash`), :4754 (sha256 previous+current chain),
         :4579 `_hash_moves`.

   GREEN tests confirm what the substrate DOES provide; `^:kaocha/pending`
   tests pin the genuine gaps (each with a PENDING(NEW) comment + an Odoo
   file:line reference) and are expected to fail until the gap is closed.

   Every asserted number is hand-derived from a single `sell!` of 1,000 EUR:
   Dr Forderungen +1000 / Cr Erlöse 19% −1000; its reversal negates both legs."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.numbering :as numbering]
            [kontor.posting :as posting]
            [kontor.compliance.sealing :as sealing]
            [kontor.reporting.balance :as balance]
            [kontor.l10n-de.preset :as de]))

;; ============================================================================
;; Fixtures / helpers
;; ============================================================================

(def ^:private eur   [:kontor.commodity/symbol "EUR"])
(def ^:private ar    [:kontor.account/path "Umlaufvermögen:Forderungen"])
(def ^:private rev   [:kontor.account/path "Erträge:Erlöse:19%"])
(def ^:private d1    #inst "2026-03-15")
(def ^:private d2    #inst "2026-04-01")
(def ^:private sj    [:kontor.journal/code "SJ"])

(defn- resolve-journal [db j] (:db/id (d/entity db j)))

(defn- eq? [^java.math.BigDecimal expected actual]
  (and (some? actual) (zero? (.compareTo expected ^java.math.BigDecimal actual))))

(defn- bal [conn account]
  (some-> (balance/account-balance conn account) vals first :amount))

(defn- tx-by-xid [db xid]
  (d/q '[:find ?t . :in $ ?x :where [?t :kontor.transaction/external-id ?x]] db xid))

(defn- posting-count [db tx]
  (or (d/q '[:find (count ?p) . :in $ ?tx
             :where [?p :kontor.posting/transaction ?tx]] db tx) 0))

(defn- narration [db tx]
  (d/q '[:find ?n . :in $ ?tx :where [?tx :kontor.transaction/narration ?n]] db tx))

(defn- schema-ident? [db ident]
  (some? (d/q '[:find ?e . :in $ ?i :where [?e :db/ident ?i]] db ident)))

(defn- sell-1000! [conn]
  (book/sell! conn {:debit-account ar :credit-account rev
                    :amount 1000 :commodity eur :effective-date d1
                    :external-id "SJ-2026-0001" :narration "original sale"}))

;; ============================================================================
;; A. Reversal
;; ============================================================================

;; GREEN — the :kontor.transaction/reverses attribute round-trips. A reversal
;; can be hand-assembled (negate each leg, link :reverses, date it later) and
;; nets the original to zero. This confirms the substrate ATTR is present and
;; correct — the missing piece is a builder that does this for you (see A2).
(deftest reverses-attribute-roundtrips-when-built-by-hand
  (testing "a hand-built sign-flipped reversal links :reverses and nets to zero"
    (let [conn (de/create-de-db)]
      (sell-1000! conn)
      (let [db   (d/db conn)
            orig (tx-by-xid db "SJ-2026-0001")
            ps   (d/q '[:find ?acct ?amt ?cur :in $ ?tx :where
                        [?p :kontor.posting/transaction ?tx]
                        [?p :kontor.posting/account ?acct]
                        [?p :kontor.posting/amount ?amt]
                        [?p :kontor.posting/commodity ?cur]] db orig)
            jrnl (d/q '[:find ?j . :in $ ?t :where [?t :kontor.transaction/journal ?j]] db orig)
            rev-postings (mapv (fn [[a amt c]]
                                 {:kontor.posting/account   a
                                  :kontor.posting/amount    (.negate ^java.math.BigDecimal amt)
                                  :kontor.posting/commodity c})
                               ps)
            rev-tx (posting/build-transaction
                    {:transaction {:kontor.transaction/external-id    "SJ-2026-0001-REV"
                                   :kontor.transaction/journal        jrnl
                                   :kontor.transaction/effective-date d2
                                   :kontor.transaction/narration      "reversal of SJ-2026-0001"
                                   :kontor.transaction/state          :posted
                                   :kontor.transaction/posted-at      (java.util.Date.)
                                   :kontor.transaction/reverses       orig}
                     :postings    rev-postings})]
        (d/transact conn rev-tx)
        (let [db2     (d/db conn)
              rev-eid (tx-by-xid db2 "SJ-2026-0001-REV")
              linked  (d/q '[:find ?o . :in $ ?r :where
                             [?r :kontor.transaction/reverses ?o]] db2 rev-eid)]
          (is (= orig linked) ":reverses points back at the original transaction")
          (is (not= orig rev-eid) "the reversal is its own transaction, not an edit")
          (is (eq? 0M (bal conn ar))  "receivable nets to zero after reversal")
          (is (eq? 0M (bal conn rev)) "revenue nets to zero after reversal"))))))

;; CLOSED by ADR-152 (`kontor.book/reverse!`). The pin above used to assert that
;; one of four candidate SYMBOLS resolved, which a stub would have satisfied;
;; what follows asserts the EFFECTS the hand-built reversal at :88-122 spells
;; out — same legs negated, same journal, :reverses linked, and the reversal
;; landing in the period the CALLER chose rather than `now`. That last one is
;; the whole reason the builder had to exist: kontor.document.invoice/cancel!
;; hard-codes `now`, so a March-discovered January error could not be reversed
;; into January. Odoo: account.move._reverse_moves (account_move.py:5430).
(deftest reverse!-negates-every-leg-into-a-caller-chosen-period
  (testing "book/reverse! sign-flips into the requested period and links :reverses"
    (let [conn (de/create-de-db)]
      (sell-1000! conn)
      (let [orig (tx-by-xid (d/db conn) "SJ-2026-0001")]
        (book/reverse! conn {:transaction "SJ-2026-0001" :reversal-date d2})
        (let [db  (d/db conn)
              rvs (d/q '[:find ?r . :in $ ?o :where
                         [?r :kontor.transaction/reverses ?o]] db orig)]
          (is (some? rvs) ":reverses links the reversal back at the original")
          (is (not= orig rvs) "the reversal is its own transaction, not an edit")

          ;; the effect that matters: both legs net to zero…
          (is (eq? 0M (bal conn ar))  "receivable nets to zero after reversal")
          (is (eq? 0M (bal conn rev)) "revenue nets to zero after reversal")

          ;; …but ONLY from the reversal date onward. A bitemporal read before
          ;; it still shows the original standing — which is what proves the
          ;; reversal landed in the period asked for and not at `now`.
          (is (eq? 1000M (some-> (balance/account-balance
                                  conn ar {:as-of-valid #inst "2026-03-20"})
                                 vals first :amount))
              "as of before the reversal date the original still stands")

          ;; the reversal carries the negated legs, not a fresh guess at them
          (is (= #{-1000M 1000M}
                 (set (map #(.stripTrailingZeros ^java.math.BigDecimal %)
                           (d/q '[:find [?a ...] :in $ ?t :where
                                  [?p :kontor.posting/transaction ?t]
                                  [?p :kontor.posting/amount ?a]] db rvs))))
              "the reversal's legs are the original's, negated")
          (is (= (d/q '[:find ?j . :in $ ?t :where
                        [?t :kontor.transaction/journal ?j]] db orig)
                 (d/q '[:find ?j . :in $ ?t :where
                        [?t :kontor.transaction/journal ?j]] db rvs))
              "the reversal is filed in the original's journal"))))))

(deftest reverse!-refuses-the-cases-that-would-silently-double-book
  (testing "reversing twice would re-book the original amount — it is refused"
    (let [conn (de/create-de-db)]
      (sell-1000! conn)
      (book/reverse! conn {:transaction "SJ-2026-0001" :reversal-date d2})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already been reversed"
                            (book/reverse! conn {:transaction "SJ-2026-0001"
                                                 :reversal-date d2})))
      (is (eq? 0M (bal conn ar))
          "the refused second reversal left the receivable at zero, not +1000")))
  (testing "an unknown transaction is refused rather than reversing nothing"
    (let [conn (de/create-de-db)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no transaction found"
                            (book/reverse! conn {:transaction "NOPE"}))))))

;; ============================================================================
;; B. Gapless legal numbering
;; ============================================================================

;; REWRITTEN. This test used to assert that :kontor.journal/last-sequence and
;; :kontor.transaction/sequence-number did NOT exist in the schema. That made it
;; true by construction and, worse, it would have gone RED the moment the gap it
;; described was closed — a test that fails when the bug is fixed is an alarm
;; wired backwards. What it was really pinning is that a journal does not
;; allocate until somebody asks it to, so that is what it now asserts, by
;; effect: post into an unconfigured journal and no number appears.
(deftest allocation-is-opt-in-per-journal
  (testing "a journal with no numbering configured allocates nothing"
    (let [conn (de/create-de-db)]
      (book/sell! conn {:debit-account ar :credit-account rev
                        :amount 100 :commodity eur :effective-date d1})
      (let [db (d/db conn)]
        (is (empty? (d/q '[:find [?n ...] :where
                           [?t :kontor.transaction/sequence-number ?n]] db))
            "no ordinal is allocated in an unconfigured journal")
        (is (empty? (d/q '[:find [?x ...] :where
                           [?t :kontor.transaction/external-id ?x]] db))
            "and no legal number is invented for the caller")))
    ;; Opt-in because only some journals carry legally numbered documents: an
    ;; internal accrual journal must NOT mint invoice numbers. Odoo scopes the
    ;; series per journal for the same reason (ir_sequence.py:132).
    (testing "…and an internal journal stays unnumbered while a sales journal does not"
      (let [conn (de/create-de-db)]
        (numbering/configure-journal! conn sj {:prefix "RE/{year}/"})
        (book/sell! conn {:debit-account ar :credit-account rev
                          :amount 100 :commodity eur :effective-date d1})
        (book/adjust! conn {:debit-account ar :credit-account rev
                            :amount 5 :commodity eur :effective-date d1})
        (is (= ["RE/2026/0001"]
               (d/q '[:find [?x ...] :where
                      [?t :kontor.transaction/external-id ?x]] (d/db conn)))
            "only the configured sales journal allocated")))))

;; CLOSED by ADR-151. The pin asserted that three sales with no :external-id
;; each receive a distinct legal number; that assertion is kept verbatim below
;; and strengthened — distinct is not enough for a legal series, the numbers
;; must be CONSECUTIVE FROM 1 with no hole, which is the actual statutory
;; requirement in DE/FR/IT/ES/PT/BR/IN/MX. Odoo: sequence.mixin
;; `_get_last_sequence` (account_move.py:4155).
(deftest posted-entries-get-gapless-per-journal-numbers
  (testing "three sales with no :external-id receive sequential legal numbers"
    (let [conn (de/create-de-db)]
      (numbering/configure-journal! conn sj {:prefix "SJ/{year}/" :reset :yearly})
      (dotimes [_ 3]
        (book/sell! conn {:debit-account ar :credit-account rev
                          :amount 100 :commodity eur :effective-date d1}))
      (let [db   (d/db conn)
            xids (d/q '[:find [?x ...] :where
                        [?t :kontor.transaction/journal ?j]
                        [?j :kontor.journal/type :sale]
                        [?t :kontor.transaction/external-id ?x]] db)]
        (is (= 3 (count xids))
            "each posted sale carries an auto-allocated legal number")
        (is (apply distinct? xids)
            "allocated legal numbers are unique per journal")
        (is (= ["SJ/2026/0001" "SJ/2026/0002" "SJ/2026/0003"] (sort xids))
            "and they run consecutively from 1 — a legal series, not just unique ids")
        (is (numbering/gapless? db sj) "the series has no hole"))))

  (testing "the ordinal restarts at 1 in the next year, and that is not a gap"
    (let [conn (de/create-de-db)]
      (numbering/configure-journal! conn sj {:prefix "SJ/{year}/" :reset :yearly})
      (book/sell! conn {:debit-account ar :credit-account rev
                        :amount 100 :commodity eur :effective-date d1})
      (book/sell! conn {:debit-account ar :credit-account rev
                        :amount 100 :commodity eur :effective-date #inst "2027-01-04"})
      (let [db (d/db conn)]
        (is (= #{"SJ/2026/0001" "SJ/2027/0001"}
               (set (d/q '[:find [?x ...] :where
                           [?t :kontor.transaction/external-id ?x]] db)))
            "each year opens its own series at 1")
        (is (numbering/gapless? db sj)
            "a 1-January restart is not read as a 5,000-entry hole"))))

  (testing "backdating into a bucket the journal has left is REFUSED"
    ;; Allocating into last year after this year's series started would restart
    ;; at 1 and re-issue a number last year already used. A duplicate legal
    ;; number is worse than a missing feature. Odoo refuses the same shape in
    ;; sequence.mixin._constrains_date_sequence (sequence_mixin.py:157).
    (let [conn (de/create-de-db)]
      (numbering/configure-journal! conn sj {:prefix "SJ/{year}/" :reset :yearly})
      (book/sell! conn {:debit-account ar :credit-account rev
                        :amount 100 :commodity eur :effective-date #inst "2027-01-04"})
      (is (thrown? Exception
                   (book/sell! conn {:debit-account ar :credit-account rev
                                     :amount 100 :commodity eur :effective-date d1})))
      (is (= ["SJ/2027/0001"]
             (d/q '[:find [?x ...] :where
                    [?t :kontor.transaction/external-id ?x]] (d/db conn)))
          "the refused entry consumed no number — allocation and commit are one unit"))))

;; CLOSED by ADR-151. The pin asserted a SYMBOL resolved; this asserts the
;; detector actually finds a hole, and — the part a symbol check can never
;; reach — that it does not cry wolf on an intact series. The only way to make
;; a hole is ADR-007's :db/purge, which is exactly the auditable event the
;; detector must surface rather than hide. Odoo:
;; account_move._compute_made_sequence_gap (account_move.py:972).
(deftest journal-sequence-gap-detection-exists
  (testing "a purge of a numbered entry leaves a hole the detector reports"
    (let [conn (de/create-de-db)]
      (numbering/configure-journal! conn sj {:prefix "SJ/{year}/"})
      (dotimes [_ 3]
        (book/sell! conn {:debit-account ar :credit-account rev
                          :amount 100 :commodity eur :effective-date d1}))
      (is (numbering/gapless? (d/db conn) sj) "sanity: 1,2,3 is intact")

      (let [second-tx (tx-by-xid (d/db conn) "SJ/2026/0002")]
        (d/transact conn [[:db/purge second-tx :kontor.transaction/sequence-number 2]])
        (let [db   (d/db conn)
              gaps (numbering/sequence-gaps db sj)]
          (is (not (numbering/gapless? db sj)) "the series is no longer intact")
          (is (= [{:journal (resolve-journal db sj) :sequence-key "2026"
                   :missing [2] :highest 3}]
                 gaps)
              "the detector names the missing ordinal and the bucket it is missing from")))))

  (testing "an entry missing from the START of a bucket is reported too"
    ;; "the first two invoices of the year are gone" is precisely what an
    ;; auditor is looking for, and a naive max-minus-count check misses it.
    (let [conn (de/create-de-db)]
      (numbering/configure-journal! conn sj {:prefix "SJ/{year}/"})
      (dotimes [_ 2]
        (book/sell! conn {:debit-account ar :credit-account rev
                          :amount 100 :commodity eur :effective-date d1}))
      (d/transact conn [[:db/purge (tx-by-xid (d/db conn) "SJ/2026/0001")
                         :kontor.transaction/sequence-number 1]])
      (is (= [1] (:missing (first (numbering/sequence-gaps (d/db conn) sj))))
          "ordinal 1 is reported missing, not silently treated as 'series starts at 2'"))))

;; PENDING(NEW) — a real correctness / sealing-bypass hazard. Because a legal
;; number lives in :kontor.transaction/external-id, which is :db.unique/identity
;; (UPSERT semantics), posting a SECOND entry that reuses an existing number does
;; NOT get rejected — it silently upserts onto the ORIGINAL, sealed transaction.
;; Verified: after a duplicate book/sell!, the original transaction's posting
;; count goes 2 → 4 and its narration is overwritten "original sale" → the new
;; value, with NO error.
;;
;; The sealing guard misses it: find-silent-modifications (sealing.cljc:94-110)
;; resolves the tx-data's :db/id, but book/entry! builds the transaction with a
;; NEGATIVE TEMPID (posting/build.cljc, :tx-tempid default -1). resolvable-eid
;; (sealing.cljc:84-92) returns nil for tempids, so the upsert-via-unique-attr
;; that datahike will fold onto the posted original is never inspected — the
;; A4 in-place-edit vector routed through a unique attribute instead of an eid.
;;
;; Odoo forbids this two ways: per-journal name uniqueness on posted moves, and
;; the inalterable_hash chain (account_move.py:353-354) — a posted move's fields
;; cannot be rewritten. DESIRED: reusing a posted legal number is rejected, or at
;; minimum never mutates the sealed original. Remove ^:kaocha/pending once fixed.
(deftest duplicate-legal-number-does-not-mutate-sealed-original
  (testing "a second post reusing a posted legal number must not merge onto it"
    (let [conn (de/create-de-db)]
      (sell-1000! conn)
      (let [orig (tx-by-xid (d/db conn) "SJ-2026-0001")]
        (is (= 2 (posting-count (d/db conn) orig)) "sanity: original has 2 legs")
        ;; second, DIFFERENT entry that reuses the same legal number
        (try
          (book/sell! conn {:debit-account ar :credit-account rev
                            :amount 500 :commodity eur :effective-date d2
                            :external-id "SJ-2026-0001" :narration "different sale"})
          (catch Exception _ :rejected))
        (let [db (d/db conn)]
          ;; DESIRED: the sealed original is untouched (rejected, or the second
          ;; posting became its own tx). ACTUAL: postings 2 → 4, narration
          ;; overwritten — the posted entry was silently mutated.
          (is (= 2 (posting-count db orig))
              "the sealed original must still have exactly its 2 postings")
          (is (= "original sale" (narration db orig))
              "the sealed original's narration must not be overwritten"))))))

;; ============================================================================
;; C. Per-entry chained hash
;; ============================================================================

;; GREEN — DB-wide tamper-evidence IS present. A silent retract (or in-place
;; edit) of a posted entity is rejected by the sealing guard. This is what
;; kontor offers today in lieu of a per-doc hash chain.
(deftest sealing-rejects-silent-retract-of-posted-entity
  (testing "assert-no-silent-retracts! throws on retracting a posted posting"
    (let [conn (de/create-de-db)]
      (sell-1000! conn)
      (let [db  (d/db conn)
            tx  (tx-by-xid db "SJ-2026-0001")
            p   (d/q '[:find ?p . :in $ ?tx :where
                       [?p :kontor.posting/transaction ?tx]] db tx)
            amt (d/q '[:find ?a . :in $ ?p :where
                       [?p :kontor.posting/amount ?a]] db p)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"[Ss]ealing"
             (sealing/assert-no-silent-retracts!
              db [[:db/retract p :kontor.posting/amount amt]]))
            "a retract targeting a posted posting is rejected")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"[Ss]ealing"
             (sealing/assert-no-silent-retracts!
              db [{:db/id p :kontor.posting/amount 9999M}]))
            "an in-place edit (upsert) of a posted posting amount is rejected")))))

;; PENDING(NEW): there is NO per-entry chained hash. sealing gives DB-wide
;; tamper-evidence via datahike's commit DAG, but no per-document
;; inalterable_hash / secure_sequence_number an NF525 / GoBD auditor can
;; independently recompute and verify. Odoo stores both fields on each move
;; (account_move.py:353-354), chains them with
;;   sha256(previous_hash + current_record)  (account_move.py:4754)
;; and re-hashes via _hash_moves (account_move.py:4579), so an auditor re-runs
;; the chain and detects any altered document. kontor has neither the fields nor
;; a verify fn. Remove ^:kaocha/pending once a per-entry hash chain + verifier
;; (e.g. kontor.compliance.sealing/hash-chain-valid?) ships.
(deftest ^:kaocha/pending per-entry-hash-chain-is-recomputable-by-auditor
  (testing "kernel should carry a per-transaction chained hash + a verifier"
    (let [db (d/db (de/create-de-db))]
      (is (or (schema-ident? db :kontor.transaction/inalterable-hash)
              (schema-ident? db :kontor.transaction/hash))
          "no per-transaction inalterable-hash attribute")
      (is (schema-ident? db :kontor.transaction/secure-sequence-number)
          "no per-transaction secure (gapless) sequence-number attribute")
      (let [verifiers ['kontor.compliance.sealing/hash-chain-valid?
                       'kontor.compliance.sealing/verify-hash-chain
                       'kontor.compliance.sealing/hash-moves
                       'kontor.compliance.sealing/recompute-hash]
            found (keep resolve verifiers)]
        (is (seq found)
            (str "no per-entry hash-chain verifier found among " (vec verifiers)))))))
