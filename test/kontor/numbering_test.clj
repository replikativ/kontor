(ns kontor.numbering-test
  "ADR-151 — gapless per-journal legal document numbering.

   In DE (GoBD / §14 UStG), FR (NF525), IT, ES, PT, BR, IN and MX a gapless,
   per-journal, immutable document number is a LEGAL CONDITION of issuing an
   invoice. The assertions below are about issued numbers and refused writes,
   never about attribute presence."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.numbering :as numbering]
            [kontor.l10n-de.preset :as de]))

(def ^:private ar  [:kontor.account/path "Umlaufvermögen:Forderungen"])
(def ^:private rev [:kontor.account/path "Erträge:Erlöse:19%"])
(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private sj  [:kontor.journal/code "SJ"])
(def ^:private d1  #inst "2026-03-15")

(defn- sell! [conn opts]
  (book/sell! conn (merge {:debit-account ar :credit-account rev
                           :amount 100M :commodity eur :effective-date d1}
                          opts)))

(defn- numbered-db [& [cfg]]
  (let [conn (de/create-de-db)]
    (numbering/configure-journal! conn sj (merge {:prefix "RE/{year}/"} cfg))
    conn))

(defn- xids [db]
  (sort (d/q '[:find [?x ...] :where
               [?t :kontor.transaction/sequence-number _]
               [?t :kontor.transaction/external-id ?x]] db)))

;; ============================================================================
;; The pure half
;; ============================================================================

(deftest bucket-and-render-derive-from-the-DOCUMENT-date
  (testing "the reset bucket comes from the entry's effective date, not the clock"
    ;; Backdating an invoice into last December must draw from LAST December's
    ;; series, or the series is not the one the tax authority audits.
    (is (= "2026"    (numbering/bucket-key :yearly  #inst "2026-12-31T23:00:00Z")))
    (is (= "2026-03" (numbering/bucket-key :monthly #inst "2026-03-01")))
    (is (= ""        (numbering/bucket-key :never   #inst "2026-03-01"))))

  (testing "buckets are computed in UTC so the number cannot depend on the server"
    (is (= (numbering/bucket-key :yearly #inst "2027-01-01T00:30:00Z")
           "2027")))

  (testing "rendering substitutes the template and zero-pads the ordinal"
    (is (= "RE/2026/0007"
           (numbering/render {:prefix "RE/{year}/" :padding 4} 7 d1)))
    (is (= "RE/2026-03/007"
           (numbering/render {:prefix "RE/{year}-{month}/" :padding 3} 7 d1)))
    (is (= "SJ/2026/0001"
           (numbering/render {:code "SJ"} 1 d1))
        "a journal with allocation on but no template still gets a distinguishable series")))

;; ============================================================================
;; Allocation
;; ============================================================================

(deftest allocation-issues-a-consecutive-series
  (testing "sequential numbers, and the ordinal is authoritative"
    (let [conn (numbered-db)]
      (dotimes [_ 3] (sell! conn {}))
      (let [db (d/db conn)]
        (is (= ["RE/2026/0001" "RE/2026/0002" "RE/2026/0003"] (xids db)))
        (is (= {"2026" #{1 2 3}} (numbering/allocated-numbers db sj))
            "the rendered string is a display OF the ordinal, and both are stored")
        (is (numbering/gapless? db sj)))))

  (testing "several entries in ONE tx-data chain their counters"
    ;; the fold must thread the counter, or all of them claim the same old
    ;; value and the :db/cas forms conflict.
    (let [conn (numbered-db)]
      (dotimes [_ 2] (sell! conn {}))
      (is (= 2 (count (xids (d/db conn)))))))

  (testing "a caller-supplied external-id is left alone, but still gets an ordinal"
    ;; the caller's external-id is THEIR foreign key (a beleg invoice UUID);
    ;; the legal ordinal is kontor's and is allocated either way.
    (let [conn (numbered-db)]
      (sell! conn {:external-id "beleg-8f3a"})
      (let [db (d/db conn)
            t  (d/q '[:find ?t . :where [?t :kontor.transaction/external-id "beleg-8f3a"]] db)]
        (is (= 1 (:kontor.transaction/sequence-number
                  (d/pull db [:kontor.transaction/sequence-number] t)))
            "the ordinal is allocated even when the display string is the caller's"))))

  (testing "the preview is advisory and does not reserve"
    (let [conn (numbered-db)]
      (is (= 1 (:sequence-number (numbering/next-number (d/db conn) sj d1))))
      (is (= 1 (:sequence-number (numbering/next-number (d/db conn) sj d1)))
          "asking twice reserves nothing — the authoritative allocation is in-transaction")
      (sell! conn {})
      (is (= 2 (:sequence-number (numbering/next-number (d/db conn) sj d1)))))))

(deftest allocation-refuses-rather-than-issuing-a-duplicate
  (testing "backdating into a bucket the journal has left is refused"
    (let [conn (numbered-db)]
      (sell! conn {:effective-date #inst "2027-02-01"})
      (is (thrown? Exception (sell! conn {:effective-date d1}))
          "restarting last year's series at 1 would re-issue a number it already used")
      (is (= ["RE/2027/0001"] (xids (d/db conn)))
          "the refused entry consumed no number")))

  (testing "a counter that has drifted behind hand-written numbers is refused loudly"
    ;; skipping the collision would create the very gap the feature prevents.
    (let [conn (de/create-de-db)]
      (sell! conn {:external-id "RE/2026/0001"})     ; written by hand, pre-allocation
      (numbering/configure-journal! conn sj {:prefix "RE/{year}/"})
      (is (thrown? Exception (sell! conn {}))))))

;; ============================================================================
;; Concurrency — the reason allocation lives inside the transaction
;; ============================================================================

(deftest allocation-is-gapless-under-concurrent-posting
  ;; A gapless counter is a read-modify-write, the shape that goes wrong under
  ;; concurrency. Allocating EAGERLY (read the counter, then transact) loses
  ;; updates: two threads that read the same n both write n+1, and because
  ;; :kontor.transaction/external-id is :db.unique/identity the loser does not
  ;; even fail — it UPSERTS onto the first, already-sealed entry.
  ;;
  ;; Allocating INSIDE the transaction is what makes it safe. datahike's
  ;; LocalWriter owns one transaction thread with one queue and recurs on the
  ;; previous transaction's :db-after (datahike/src/datahike/writer.cljc, the
  ;; `(recur (:db-after res))` in the processing loop), so transactions against
  ;; a connection are strictly serialized and each :db.fn/call sees the chained
  ;; db. The :db/cas on the counter is defence in depth on top of that.
  (testing "8 threads × 25 posts produce 200 distinct consecutive ordinals"
    (let [conn    (numbered-db)
          results (->> (for [_ (range 8)]
                         (future (dotimes [_ 25]
                                   (sell! conn {:effective-date #inst "2026-05-01"}))))
                       doall (mapv deref))
          db      (d/db conn)
          ords    (get (numbering/allocated-numbers db sj) "2026")]
      (is (= 8 (count results)))
      (is (= 200 (count ords)) "every post got a number")
      (is (= 200 (count (set ords))) "and no two posts got the SAME number")
      (is (= 200 (apply max ords)) "the series runs 1..200 with nothing skipped")
      (is (numbering/gapless? db sj)))))

;; ============================================================================
;; Gap detection — the auditor's side
;; ============================================================================

(deftest gaps-are-reported-per-reset-bucket
  (testing "a purge leaves a real hole, and the detector surfaces it"
    ;; :db/purge (ADR-007) legitimately removes a posted entry and IS the one
    ;; way a hole can appear — allocation and consumption being one atomic
    ;; transaction rules out the failed-post case. A purge is an auditable
    ;; event, so its consequence for the series must be visible, not hidden.
    (let [conn (numbered-db)]
      (dotimes [_ 3] (sell! conn {}))
      (let [db (d/db conn)
            t2 (d/q '[:find ?t . :where
                      [?t :kontor.transaction/external-id "RE/2026/0002"]] db)]
        (d/transact conn [[:db/purge t2 :kontor.transaction/sequence-number 2]])
        (let [gaps (numbering/sequence-gaps (d/db conn) sj)]
          (is (= 1 (count gaps)))
          (is (= [2] (:missing (first gaps))))
          (is (= "2026" (:sequence-key (first gaps))))
          (is (= 3 (:highest (first gaps))))))))

  (testing "a new year opening at 1 is not a gap"
    (let [conn (numbered-db {:reset :yearly})]
      (sell! conn {})
      (sell! conn {:effective-date #inst "2027-01-04"})
      (is (numbering/gapless? (d/db conn) sj)
          "grouping by bucket is what stops a 1-January restart reading as a hole")
      (is (= ["RE/2026/0001" "RE/2027/0001"] (xids (d/db conn))))))

  (testing ":never keeps one continuous series across years"
    (let [conn (numbered-db {:reset :never :prefix "GL-"})]
      (sell! conn {})
      (sell! conn {:effective-date #inst "2027-01-04"})
      (is (= ["GL-0001" "GL-0002"] (xids (d/db conn)))
          "no reset means the ordinal keeps climbing")
      (is (numbering/gapless? (d/db conn) sj)))))
