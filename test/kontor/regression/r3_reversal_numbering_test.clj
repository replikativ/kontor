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

;; PENDING(NEW): there is NO generic GL reverse builder in the kernel. Only the
;; document-specific kontor.document.invoice/cancel! reverses (and it hard-codes
;; the reversal effective-date to `now`, so it cannot reverse into a chosen
;; period). A raw kontor.book/entry! GL entry has no reverse path at all — the
;; caller must hand-roll leg negation + the :reverses link + any settlement, as
;; the GREEN test above demonstrates. Odoo exposes account.move._reverse_moves
;; (account_move.py:5430) — one call takes a reversal date, sign-flips (or
;; storno-doubles at :5464), sets reversed_entry_id, and optionally reconciles
;; the reversal against the original (auto-settle). Remove ^:kaocha/pending once
;; kontor ships e.g. kontor.book/reverse! / kontor.posting/reverse-transaction!.
(deftest ^:kaocha/pending generic-gl-reverse-builder-exists
  (testing "the kernel should expose a generic reverse builder for a posted GL tx"
    (let [candidates ['kontor.book/reverse!
                      'kontor.book/reverse-entry!
                      'kontor.posting/reverse-transaction!
                      'kontor.posting/reverse!]
          found (keep resolve candidates)]
      (is (seq found)
          (str "no generic GL reverse builder found among " (vec candidates)
               " — reversal is document-specific (invoice/cancel!) only")))))

;; ============================================================================
;; B. Gapless legal numbering
;; ============================================================================

;; GREEN — document the numbering substrate as it actually is: a bare
;; :kontor.journal/sequence-prefix string and NO counter/allocation attribute.
;; This is a factual schema check, not a bug in itself — it pins the shape the
;; gapless-allocation gap (B2/B3) sits on top of.
(deftest journal-sequence-substrate-is-a-bare-prefix
  (testing ":kontor.journal/sequence-prefix exists but no last/next counter attr does"
    (let [db (d/db (de/create-de-db))]
      (is (schema-ident? db :kontor.journal/sequence-prefix)
          "the prefix attribute is present")
      (is (not (schema-ident? db :kontor.journal/last-sequence))
          "no :kontor.journal/last-sequence counter")
      (is (not (schema-ident? db :kontor.journal/next-sequence))
          "no :kontor.journal/next-sequence counter")
      (is (not (schema-ident? db :kontor.transaction/sequence-number))
          "no per-transaction sequence number attribute"))))

;; PENDING(NEW): nothing ALLOCATES a gapless per-journal legal number. Posting
;; three sales through book/sell! with no :external-id leaves every transaction
;; with a nil external-id — the substrate never assigns SJ/2026/0001..0003.
;; Odoo's sequence.mixin (_get_last_sequence, account_move.py:4155) computes the
;; next name per journal at post time so posted moves are gaplessly numbered.
;; Remove ^:kaocha/pending once kontor auto-allocates a per-journal legal number.
(deftest ^:kaocha/pending posted-entries-get-gapless-per-journal-numbers
  (testing "three sales with no :external-id should receive sequential legal numbers"
    (let [conn (de/create-de-db)]
      (dotimes [_ 3]
        (book/sell! conn {:debit-account ar :credit-account rev
                          :amount 100 :commodity eur :effective-date d1}))
      (let [db   (d/db conn)
            xids (d/q '[:find [?x ...] :where
                        [?t :kontor.transaction/journal ?j]
                        [?j :kontor.journal/type :sale]
                        [?t :kontor.transaction/external-id ?x]] db)]
        ;; DESIRED: three distinct, sequential legal numbers. ACTUAL: the query
        ;; returns nothing because external-id was never set — no allocator.
        (is (= 3 (count xids))
            "each posted sale should carry an auto-allocated legal number")
        (is (apply distinct? xids)
            "allocated legal numbers must be unique per journal")))))

;; PENDING(NEW): there is no gap DETECTION. Odoo stores made_sequence_gap
;; (account_move.py:972 _compute_made_sequence_gap) so a break in a journal's
;; posted sequence is flagged for the auditor. kontor has no equivalent helper
;; and no sequence field to scan. Remove ^:kaocha/pending once a gap-detector
;; (e.g. kontor.reporting/sequence-gaps) ships.
(deftest ^:kaocha/pending journal-sequence-gap-detection-exists
  (testing "the kernel should expose a per-journal sequence gap detector"
    (let [candidates ['kontor.reporting.ledger/sequence-gaps
                      'kontor.reporting/sequence-gaps
                      'kontor.compliance.sealing/sequence-gaps
                      'kontor.book/sequence-gaps]
          found (keep resolve candidates)]
      (is (seq found)
          (str "no gapless-sequence gap detector found among " (vec candidates))))))

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
