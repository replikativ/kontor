(ns kontor.closing
  "Year-end (or any-period) close — roll P&L accounts to a single
   retained-earnings (equity) account so the new period starts with
   zeroed income/expense balances.

   Mechanism: a single closing transaction is posted on the period's
   last day with one posting per non-zero P&L account whose amount is
   the *negative* of that account's period-end balance, plus a
   counter-posting on the retained-earnings account that sums to the
   net result.

   That keeps the kernel's sum-to-zero invariant intact and means the
   trial balance for the closed period collapses to zero on every P&L
   account going forward.

   Country-agnostic — caller supplies the retained-earnings account
   eid (and optionally the journal). DE wraps this with
   `l10n-de.closing/close-fiscal-year!` which pins the account to
   SKR04 2900 (Gewinnvortrag).

   Opening balance carry-forward is *not* a separate posting in this
   model: balance.clj computes balances cumulatively over `:posting/
   valid-from`, so the new period's opening BS balances are simply
   the previous period's closing BS balances. Only P&L gets explicitly
   reset by the closing entry.

   Workflow (year-end):
     1. (close-fiscal-year! …)         ; posts the closing tx
     2. (period/close! conn period-eid) ; soft-close the period
     3. (period/seal!  conn period-eid) ; hard-seal once corrections
                                          settled

   Calling close-fiscal-year! is idempotent in spirit but not in fact:
   it refuses if the period already has a closing transaction
   (`:transaction/closes-period`)."
  (:require [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.period :as period]
            [kontor.posting :as posting]
            [kontor.validation :as validation])
  (:import [java.util Date]))

;; ============================================================================
;; Find P&L accounts with non-zero closing balance
;; ============================================================================

(defn- pnl-accounts
  "All :income + :expense accounts in the chart."
  [db]
  (d/q '[:find [?a ...]
         :where
         (or [?a :kontor.account/type :income]
             [?a :kontor.account/type :expense])]
       db))

(defn- account-period-end-balance
  "Period-end balance on `account-eid` — uses balance.clj's bitemporal
   reader on the *valid-time* axis at the period's last instant. The
   tx-time axis intentionally defaults to NOW so corrections recorded
   later (but valid-dated within the period) are picked up by the close."
  [conn account-eid as-of-date]
  (balance/account-balance conn account-eid
                           {:as-of-valid as-of-date}))

(defn- non-zero-pnl
  "For each P&L account, pull its period-end balance map and keep
   the (account, commodity, money) triples whose money is non-zero.

   Returns vec of {:account-eid :commodity-eid :amount :money}."
  [conn period-end-date]
  (let [db (d/db conn)
        accs (pnl-accounts db)]
    (vec
     (for [a accs
           [c m] (account-period-end-balance conn a period-end-date)
           :when (not (zero? (compare (:amount m) 0M)))]
       {:account-eid a
        :commodity-eid c
        :amount (:amount m)
        :money m}))))

;; ============================================================================
;; Closing-tx assembly
;; ============================================================================

(defn- closing-postings
  "Build postings: one negation per non-zero P&L line + a single
   retained-earnings counter-posting per commodity that sums to the
   net result. The kernel's posting/build-transaction will then assert
   sum-to-zero."
  [non-zero retained-eid period-end-date]
  (let [;; Per-commodity net of P&L (income credits are negative; expense
        ;; debits are positive). Sum = +loss / −profit.
        per-commodity-net
        (reduce (fn [acc {:keys [commodity-eid amount]}]
                  (update acc commodity-eid
                          (fnil #(.add ^java.math.BigDecimal % amount) 0M)))
                {} non-zero)
        zero-out (mapv (fn [{:keys [account-eid commodity-eid amount]}]
                         {:posting/account account-eid
                          :posting/commodity commodity-eid
                          :posting/amount (.negate ^java.math.BigDecimal amount)
                          :posting/posted-at period-end-date})
                       non-zero)
        retained (mapv (fn [[c net]]
                         {:posting/account retained-eid
                          :posting/commodity c
                          :posting/amount net
                          :posting/posted-at period-end-date})
                       per-commodity-net)]
    (into zero-out retained)))

(defn- closing-tx-already-posted?
  "True iff a `:transaction/closes-period` referencing `period-eid`
   already exists in the DB. Used to make close-period! refuse a
   second close attempt."
  [db period-eid]
  (boolean
   (d/q '[:find ?t .
          :in $ ?p
          :where [?t :transaction/closes-period ?p]]
        db period-eid)))

;; ============================================================================
;; Public: close a period
;; ============================================================================

(defn close-period!
  "Post the closing transaction that zeros out income+expense accounts
   in the named period. Returns:

     {:transaction-eid eid
      :postings-count  n
      :net-by-commodity {commodity-eid Money …}}

   Required:
     :period-eid             — the :period entity to close
     :retained-earnings-eid  — equity account to receive the net result
     :journal-eid            — journal under which to file the tx

   Optional:
     :external-id   — :transaction/external-id (default
                      \"CLOSE-<period-eid>\")
     :narration     — :transaction/narration (default
                      \"Period close\")
     :at            — when to post; default = period's :period/end - 1ms
                      (i.e. on the last instant of the period)

   Idempotent-ish: refuses if a closing transaction already exists for
   `period-eid` (look up via `:transaction/closes-period`)."
  [conn {:keys [period-eid retained-earnings-eid journal-eid
                external-id narration at]
         :or {narration "Period close"}}]
  (when-not period-eid
    (throw (ex-info ":period-eid is required" {})))
  (when-not retained-earnings-eid
    (throw (ex-info ":retained-earnings-eid is required" {})))
  (when-not journal-eid
    (throw (ex-info ":journal-eid is required" {})))
  (let [db (d/db conn)]
    (when (closing-tx-already-posted? db period-eid)
      (throw (ex-info "Period already has a closing transaction"
                      {:type :closing/already-posted
                       :period-eid period-eid})))
    (let [period (d/pull db [:period/start :period/end] period-eid)
          end (:period/end period)
          ;; Last instant inside [start, end). end is exclusive, so we
          ;; subtract 1ms to land on the period's actual close moment.
          posted-at (or at (Date. (- (.getTime ^Date end) 1)))
          non-zero (non-zero-pnl conn posted-at)]
      (if (empty? non-zero)
        ;; Nothing to close — return a sentinel rather than posting an
        ;; empty (and sum-to-zero-violating) transaction.
        {:transaction-eid nil
         :postings-count 0
         :net-by-commodity {}
         :note :no-pnl-activity}
        (let [postings (closing-postings non-zero retained-earnings-eid posted-at)
              ext (or external-id (str "CLOSE-" period-eid))
              tx-input
              {:transaction
               {:transaction/external-id ext
                :transaction/journal journal-eid
                :transaction/effective-date posted-at
                :transaction/narration narration
                :transaction/state :posted
                :transaction/posted-at posted-at
                :transaction/closes-period period-eid}
               :postings postings}
              tx-data (posting/build-transaction tx-input)
              ;; Route through transact-with-validation so the closing
              ;; entry picks up sealing / period / sum-to-zero /
              ;; invariant checks (ADR-067 — the gate). Single transact,
              ;; single business event, single :tx/valid-from already
              ;; embedded by build-transaction.
              {:keys [tempids]} (validation/transact-with-validation
                                 conn tx-data)
              tx-eid (or (some (fn [[k v]]
                                 (when (and (string? k)
                                            (.startsWith ^String k ext))
                                   v))
                               tempids)
                         (d/q '[:find ?t .
                                :in $ ?ext
                                :where [?t :transaction/external-id ?ext]]
                              (d/db conn) ext))
              per-c (reduce (fn [acc {:keys [commodity-eid money]}]
                              (update acc commodity-eid
                                      #(if % {:amount (.add ^java.math.BigDecimal (:amount %) (:amount money))
                                              :commodity (:commodity money)}
                                           money)))
                            {} non-zero)]
          {:transaction-eid tx-eid
           :postings-count (count postings)
           :net-by-commodity per-c})))))

(defn close-fiscal-year!
  "Convenience wrapper: close the year-end period AND soft-close it
   in one shot. After settling any remaining corrections, the caller
   can `period/seal!` to make the close irrevocable.

   Returns:
     {:close-result …  ; from close-period!
      :period-close-tx-report …}  ; from period/close!"
  [conn opts]
  (let [close-result (close-period! conn opts)
        ;; Only soft-close after the closing tx settles, so the
        ;; closing tx itself isn't refused by a too-eager pre-close
        ;; period-lock check.
        period-tx (when (:transaction-eid close-result)
                    (period/close! conn (:period-eid opts)))]
    {:close-result close-result
     :period-close-tx-report period-tx}))
