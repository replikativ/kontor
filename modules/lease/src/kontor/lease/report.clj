(ns kontor.lease.report
  "kontor-lease reconciliation reports — note 198 (HIGH-5 / MED-2).

   Sibling of `kontor.inventory.report/valuation-tie-out`, and there
   for the same reason: a subledger that RE-DERIVES its number from
   current source data instead of netting what the GL actually posted
   will drift silently, and every unit test will stay green while it
   does. `kontor.lease.lease-provider/outstanding-liability` now nets
   the ledger (see its docstring); `reconcile-liability` is the
   detective control that proves it, and localises the break when it
   does not.

   ## Two scopes

   - **per-book** — `{:book <eid or [lease ledger]>}`. Subledger =
     that book's `outstanding-liability`; GL = the postings on its
     liability account + ledger that are ATTRIBUTABLE to this book:
     its `:recognition-transaction`, its fired occurrences'
     transactions, and the `:lease-modification` adjustments on its
     lease. This is the scope that answers 'which lease broke'.

   - **control-account** — `{:ledger L :liability-account A
     :commodity C}`. Subledger = Σ `outstanding-liability` over every
     `:lease-liability` book on that (ledger, account); GL = the WHOLE
     account balance on that ledger. This is the real month-end
     control-account tie-out — it also catches manual journals against
     the lease-liability account that no lease knows about, which the
     per-book scope cannot see by construction.

   Per-lease attribution is only possible at all because
   `commence!` persists `:kontor.lease-liability/recognition-transaction`
   (note 198 MED-2): the lease-liability control account is SHARED, so
   before that anchor existed a liability break was undetectable in
   principle, not merely untested."
  (:require [datahike.api :as d]
            [kontor.lease.core :as lease]
            [kontor.lease.lease-provider :as lp]
            [kontor.lease.liability :as liability]
            [kontor.reporting.balance :as balance])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; GL attribution
;; ============================================================================

(defn attributed-transactions
  "The GL `:transaction` eids attributable to ONE `:lease-liability`
   book — its day-one recognition entry (or ADR-069 import bridge),
   every fired payment occurrence of its schedule, and every
   `:lease-modification` adjustment recorded against its lease.

   The modification adjustments are per-LEASE (one event, N books), so
   the caller still filters the postings by ledger — which is what
   separates the IFRS 16 book from the ASC 842 book when both post to
   the same account code."
  [db book-spec]
  (let [inputs (liability/book-plan-inputs db book-spec)
        occ-txs (d/q '[:find [?tx ...]
                       :in $ ?s
                       :where
                       [?o :kontor.schedule-occurrence/schedule ?s]
                       [?o :kontor.schedule-occurrence/transaction ?tx]]
                     db (:schedule inputs))
        mod-txs (d/q '[:find [?tx ...]
                       :in $ ?l
                       :where
                       [?m :kontor.lease-modification/lease ?l]
                       [?m :kontor.lease-modification/transaction ?tx]]
                     db (:lease inputs))]
    (cond-> (into (set occ-txs) mod-txs)
      (:recognition-transaction inputs) (conj (:recognition-transaction inputs)))))

(defn- attributed-account-sum
  "Σ `:kontor.posting/amount` over `account` + `ledger` restricted to
   `tx-set`, counting only transactions in `include-states`."
  ^BigDecimal [db account ledger tx-set include-states]
  (if (empty? tx-set)
    0M
    (reduce
     (fn [^BigDecimal a [_p ^BigDecimal amt]] (.add a amt))
     0M
     (d/q '[:find ?p ?amt
            :in $ [?tx ...] ?acct ?led [?state ...]
            :where
            [?p :kontor.posting/transaction ?tx]
            [?p :kontor.posting/account ?acct]
            [?p :kontor.posting/ledger ?led]
            [?p :kontor.posting/amount ?amt]
            [?tx :kontor.transaction/state ?state]]
          db (vec tx-set) account ledger (vec include-states)))))

;; ============================================================================
;; reconcile-liability
;; ============================================================================

(defn- result [subledger ^BigDecimal gl extra]
  (let [diff (.subtract ^BigDecimal subledger gl)]
    (merge {:subledger  subledger
            :gl         gl
            :difference diff
            :ok?        (zero? (.signum diff))}
           extra)))

(defn reconcile-liability
  "Reconcile the lease-liability subledger to the GL control account.
   Mirrors `kontor.inventory.report/valuation-tie-out`; returns
   `{:subledger :gl :difference :ok? …}`.

   BOTH figures are stated the way the subledger states a liability —
   a POSITIVE carrying amount. The GL side is therefore the negated
   posting sum (a liability sits credit, i.e. negative, in the ledger),
   so `:difference` is `subledger − gl` and a non-zero value is the
   'my balance-sheet lease liability is wrong' finding — surfaced, not
   hidden.

   Per-book scope:
     (reconcile-liability conn {:book [\"LSE-1\" ifrs-eid]})
   Control-account scope:
     (reconcile-liability conn {:ledger ifrs-eid
                                :liability-account a-1750
                                :commodity eur})

   Optional (both scopes): `:as-of-tx` (applied to BOTH sides — the
   subledger reduce runs against the snapshotted db, so a current
   subledger is never compared to a historical GL), `:include-states`
   (default `#{:posted}`).

   Per-book extras: `:book`, `:lease`, `:ledger`, `:liability-account`.
   Control-account extras: `:books` (the eids summed)."
  [conn {:keys [book ledger liability-account commodity as-of-tx include-states]
         :or   {include-states #{:posted}}}]
  (let [db (cond-> (d/db conn) as-of-tx (d/as-of as-of-tx))]
    (cond
      book
      (let [inputs (liability/book-plan-inputs db book)
            gl (.negate (attributed-account-sum
                         db (:liability-account inputs) (:ledger inputs)
                         (attributed-transactions db (:book inputs))
                         include-states))]
        (result (lp/outstanding-liability db (:book inputs)) gl
                {:book              (:book inputs)
                 :lease             (:lease inputs)
                 :ledger            (:ledger inputs)
                 :liability-account (:liability-account inputs)}))

      (and ledger liability-account)
      (let [_ (when-not commodity
                (throw (ex-info "reconcile-liability: :commodity required for the control-account scope"
                                {})))
            books (d/q '[:find [?b ...]
                         :in $ ?led ?acct
                         :where
                         [?b :kontor.lease-liability/ledger ?led]
                         [?b :kontor.lease-liability/liability-account ?acct]]
                       db ledger liability-account)
            subledger (reduce (fn [^BigDecimal a b]
                                (.add a ^BigDecimal (lp/outstanding-liability db b)))
                              0M books)
            gl-money (get (balance/account-balance
                           conn liability-account
                           (cond-> {:ledger ledger :include-states include-states}
                             as-of-tx (assoc :as-of-tx as-of-tx)))
                          commodity)
            gl (.negate ^BigDecimal (or (:amount gl-money) 0M))]
        (result subledger gl {:books (vec books)
                              :ledger ledger
                              :liability-account liability-account}))

      :else
      (throw (ex-info "reconcile-liability: pass either :book, or :ledger + :liability-account + :commodity"
                      {})))))

(defn reconcile-lease
  "`reconcile-liability` for EVERY `:lease-liability` book of one
   lease — a vector of per-book results, one per parallel ledger.
   Convenience for 'is this lease clean on all frameworks?'."
  [conn lease-spec & [opts]]
  (let [db (d/db conn)
        lease-eid (lease/resolve-lease db lease-spec)]
    (when-not lease-eid
      (throw (ex-info "reconcile-lease: lease not found" {:spec lease-spec})))
    (mapv #(reconcile-liability conn (assoc (or opts {}) :book %))
          (sort (liability/books-of db lease-eid)))))
