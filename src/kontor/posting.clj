(ns kontor.posting
  "Build draft transactions out of postings, validating the structural
   double-entry invariants:

     1. A transaction has 2+ postings.
     2. Postings sum to zero **per ledger per commodity** (ADR-021 +
        the double-entry rule — multi-currency moves balance per
        currency independently, and parallel ledgers are each their
        own self-balancing book).
     3. Each posting has the minimum required fields (account,
        amount, commodity).
     4. The transaction has the minimum required header fields
        (journal, effective-date).

   What this module does NOT do:
     - Tax expansion (`kontor.tax` will plug in there)
     - Sealing / posted-at lifecycle (`kontor.sealing`)
     - Period-locked rejection (`kontor.period`)
     - Account-active / commodity-match checks (the invariant library
       will, per ADR-011)

   `build-transaction` produces a tx-data vector ready to hand to
   `datahike.api/transact`, plus a small report. It does not connect
   to a db itself — the validations performed here are purely
   structural, not catalog-aware. Callers compose this with the
   db-aware checks in `validation.clj` (Phase 1)."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.costing-provider :as costing]
            [kontor.money :as money]
            [kontor.valuation :as valuation]))

;; `kontor.posting` is required by some kernel namespaces that
;; `kontor.validation` also imports indirectly; we resolve the gate
;; lazily to avoid any chance of a load-order cycle.
(defn- transact-with-validation*
  [conn tx-data]
  ((requiring-resolve 'kontor.validation/transact-with-validation)
   conn tx-data))

;; ============================================================================
;; Validation predicates
;; ============================================================================

(def ^:private allowed-display-types
  "Per the schema doc on :kontor.posting/display-type:
     :product       — real posting against a real account
     :tax           — auto-generated tax line
     :payment-term  — placeholder for the receivable/payable from terms
     :rounding      — cash-rounding adjustment
     :section       — UI section header (no posting effect)
     :note          — UI annotation (no posting effect)"
  #{:product :tax :payment-term :rounding :section :note})

(def ^:const default-display-type
  "Display-type that callers can omit. Both `validate` and
   `build-transaction` apply this default consistently. Documented as
   public so consumers can build draft postings ergonomically."
  :product)

(defn- effective-display-type
  "Resolve a posting's display-type, applying the kernel's default
   when the caller omits it. Used by both validate and build-transaction
   so the two stay consistent."
  [posting]
  (or (:kontor.posting/display-type posting) default-display-type))

(defn- balance-affecting?
  "True iff this posting affects the double-entry balance. UI-only
   :section and :note lines are ignored when summing."
  [posting]
  (not (contains? #{:section :note} (effective-display-type posting))))

(defn- posting-validation-errors
  "Return a vector of per-posting structural problems, or empty if OK.
   Each problem is {:posting <input-map> :error <keyword> :message <str>}."
  [posting]
  (let [display-type (effective-display-type posting)]
    (cond-> []
      (not (contains? allowed-display-types display-type))
      (conj {:posting posting
             :error :invalid-display-type
             :message (str "display-type " (pr-str display-type)
                           " not in " allowed-display-types)})

      (and (balance-affecting? posting)
           (nil? (:kontor.posting/account posting)))
      (conj {:posting posting
             :error :missing-account
             :message "balance-affecting posting requires :kontor.posting/account"})

      (and (balance-affecting? posting)
           (nil? (:kontor.posting/amount posting)))
      (conj {:posting posting
             :error :missing-amount
             :message "balance-affecting posting requires :kontor.posting/amount"})

      (and (balance-affecting? posting)
           (nil? (:kontor.posting/commodity posting)))
      (conj {:posting posting
             :error :missing-commodity
             :message "balance-affecting posting requires :kontor.posting/commodity"}))))

;; ============================================================================
;; Sum-to-zero (per ledger, per commodity) — ADR-021
;; ============================================================================

(defn- ledger-key
  "Grouping key for a posting's ledger membership. Returns the raw
   :kontor.posting/ledger value (eid, lookup-ref like [:kontor.ledger/code \"ifrs\"],
   or ident) so postings written with the same reference form group
   together. Returns nil when :kontor.posting/ledger is absent —
   build-transaction defaults missing ledgers to the primary-ledger
   lookup-ref before validation, so well-formed inputs never group
   under nil."
  [posting]
  (:kontor.posting/ledger posting))

(defn balance-by-ledger-and-commodity
  "Return {ledger-key => {commodity => Money}} of the balance-affecting
   postings' net amount, grouped first by :kontor.posting/ledger then summed
   per commodity within each ledger. A balanced transaction has every
   inner Money zero.

   ledger-key is the raw :kontor.posting/ledger value as supplied; postings
   missing :kontor.posting/ledger group under nil (see `ledger-key`)."
  [postings]
  (->> postings
       (filter balance-affecting?)
       (group-by ledger-key)
       (reduce-kv
        (fn [acc l ps]
          (assoc acc l (->> ps
                            (keep money/posting->money)
                            money/sum-by-commodity)))
        {})))

(defn unbalanced-ledger-commodities
  "Return {ledger-key => {commodity => Money}} retaining only entries
   with non-zero balance. Empty outer map iff the transaction balances
   per ledger per commodity.

   Multi-currency rule (unchanged from single-ledger): each commodity
   sums independently. Parallel-ledger rule (ADR-021): each ledger
   sums independently — a 5 EUR debit on the IFRS ledger does NOT
   net against a 5 EUR credit on the HGB ledger."
  [postings]
  (->> (balance-by-ledger-and-commodity postings)
       (reduce-kv
        (fn [acc l m]
          (let [nz (into {} (remove (fn [[_ v]] (money/zero? v)) m))]
            (if (seq nz) (assoc acc l nz) acc)))
        {})))

;; ============================================================================
;; Multi-entity sum-to-zero — ADR-031
;; ============================================================================

(defn- entity-key
  "Grouping key for a posting's entity scope. Returns the raw
   :kontor.posting/entity value (eid, lookup-ref like
   [:kontor.entity/code \"acme-de\"], etc.). nil when the attribute is absent."
  [posting]
  (:kontor.posting/entity posting))

(defn multi-entity-mode?
  "True iff any balance-affecting posting in the collection carries
   :kontor.posting/entity. Drives the choice between per-(ledger, commodity)
   and per-(entity, ledger, commodity) sum-to-zero invariants."
  [postings]
  (boolean (some (every-pred balance-affecting? entity-key) postings)))

(defn mixed-entity-mode?
  "True iff SOME but not ALL balance-affecting postings carry
   :kontor.posting/entity. Mixed mode is a validation error — the invariant
   it implies is ambiguous (does the un-tagged posting belong to a
   default entity? to no entity? to all of them?). Reject."
  [postings]
  (let [bafs (filter balance-affecting? postings)
        with-entity (filter entity-key bafs)]
    (and (seq with-entity)
         (not= (count with-entity) (count bafs)))))

(defn balance-by-entity-ledger-and-commodity
  "Return {entity-key => {ledger-key => {commodity => Money}}} of the
   balance-affecting postings' net amount. Used only in multi-entity
   mode (every posting carries :kontor.posting/entity). The single-entity
   case is handled by `balance-by-ledger-and-commodity`."
  [postings]
  (->> postings
       (filter balance-affecting?)
       (group-by entity-key)
       (reduce-kv
        (fn [acc e ps]
          (assoc acc e (balance-by-ledger-and-commodity ps)))
        {})))

(defn unbalanced-entity-ledger-commodities
  "Return {entity-key => {ledger-key => {commodity => Money}}}
   retaining only the (entity, ledger, commodity) triples with
   non-zero balance. Empty outer map iff the transaction balances
   per (entity, ledger, commodity). Multi-entity mode only."
  [postings]
  (->> (balance-by-entity-ledger-and-commodity postings)
       (reduce-kv
        (fn [acc e ledgers]
          (let [non-zero-ledgers
                (reduce-kv
                 (fn [acc2 l m]
                   (let [nz (into {} (remove (fn [[_ v]] (money/zero? v)) m))]
                     (if (seq nz) (assoc acc2 l nz) acc2)))
                 {}
                 ledgers)]
            (if (seq non-zero-ledgers) (assoc acc e non-zero-ledgers) acc)))
        {})))

;; ============================================================================
;; Transaction header validation
;; ============================================================================

(defn- header-validation-errors
  [{:keys [transaction]}]
  (cond-> []
    (nil? (:kontor.transaction/journal transaction))
    (conj {:error :missing-journal
           :message ":kontor.transaction/journal is required"})

    (nil? (:kontor.transaction/effective-date transaction))
    (conj {:error :missing-effective-date
           :message ":kontor.transaction/effective-date is required (the
                     bitemporal valid-time of this entry)"})))

;; ============================================================================
;; Public entry
;; ============================================================================

(defn validate
  "Pure structural validation. Returns
     {:ok?         boolean
      :mode        :single-entity | :multi-entity
      :postings    [...]
      :errors      [...]
      :balance     <ledger-keyed (single-entity) OR entity-keyed (multi)>
      :unbalanced  <same shape as :balance; only non-zero entries>}

   No db access. Use this when you want to inspect a draft transaction
   without committing it.

   Per ADR-021 the sum-to-zero invariant is enforced per (ledger,
   commodity) pair. Per ADR-031 this extends to per
   (entity, ledger, commodity) when any posting carries
   :kontor.posting/entity. Mixed-mode (some tagged, some not) is rejected.

   Postings without :kontor.posting/ledger group under nil — this nil-group
   conceptually IS the primary book, so readers should treat it the
   same as a posting explicitly tagged with the primary-ledger ref.
   The kernel does NOT auto-inject a lookup-ref at build time
   because the invariant library's speculative-apply uses an empty
   schema-only DB that cannot resolve unique-identity refs to data
   entities."
  [{:keys [transaction postings] :as input}]
  (let [posting-errors (mapcat posting-validation-errors postings)
        header-errors (header-validation-errors input)
        all-errors (vec (concat header-errors posting-errors))
        mixed?  (mixed-entity-mode? postings)
        multi?  (and (not mixed?) (multi-entity-mode? postings))
        mode    (if multi? :multi-entity :single-entity)
        balance (if multi?
                  (balance-by-entity-ledger-and-commodity postings)
                  (balance-by-ledger-and-commodity postings))
        unbalanced (if multi?
                     (unbalanced-entity-ledger-commodities postings)
                     (unbalanced-ledger-commodities postings))
        too-few? (< (count (filter balance-affecting? postings)) 2)
        all-errors (cond-> all-errors
                     mixed?
                     (conj {:error :mixed-entity-mode
                            :message "transaction has SOME postings with :kontor.posting/entity and SOME without; multi-entity mode requires all balance-affecting postings to carry an entity ref"})

                     too-few?
                     (conj {:error :too-few-postings
                            :message "transaction needs at least 2
                                      balance-affecting postings"})

                     (and (not mixed?) (seq unbalanced))
                     (conj {:error :unbalanced
                            :message (if multi?
                                       "postings do not sum to zero per (entity, ledger, commodity)"
                                       "postings do not sum to zero per (ledger, commodity)")
                            :unbalanced unbalanced}))]
    {:ok?        (empty? all-errors)
     :mode       mode
     :transaction transaction
     :postings   postings
     :errors     all-errors
     :balance    balance
     :unbalanced unbalanced}))

(defn build-transaction
  "Build a tx-data vector ready for `datahike.api/transact`, raising on
   structural problems. Input shape:

     {:transaction { :kontor.transaction/journal         <ref or external-id>
                     :kontor.transaction/effective-date  <#inst>
                     :kontor.transaction/narration       <string>
                     :kontor.transaction/external-id     <string>      ; optional
                     :kontor.transaction/partner         <ref>         ; optional
                     :kontor.transaction/state           <kw>          ; defaults :draft
                     :kontor.transaction/source          <string>      ; optional
                     ...other transaction/* attrs }
      :postings    [ { :kontor.posting/account          <ref>
                       :kontor.posting/amount           <bigdec>
                       :kontor.posting/commodity        <ref>
                       :kontor.posting/display-type     <kw>           ; defaults :product
                       :kontor.posting/partner          <ref>          ; optional
                       :kontor.posting/narration        <string>       ; optional
                       :kontor.posting/taxes-applied    [<refs>]       ; optional
                       :kontor.posting/account-tags     [<refs>]       ; optional
                       ...other posting/* attrs }
                     ... ]}

   Returns a tx-data vector that, when transacted, creates one new
   :transaction entity and N new :posting entities, refs threaded.
   Throws ex-info on any structural error.

   This function does NOT do the catalog-aware checks (account
   exists/active, commodity matches account, period not locked,
   sealing, …) — those run at the validation/db boundary.

   Use `validate` for non-throwing inspection.

   Per ADR-021 (revised), `:kontor.posting/ledger` is fully optional. A
   posting without the attribute is conceptually in the *primary*
   book; readers and validators treat the nil-keyed group as the
   primary ledger. Multi-ledger users explicitly tag their postings
   with a ledger ref or lookup-ref; everyone else pays nothing.

   Optional `:tx-tempid` (top-level key, ADR-067) — the tempid for
   the transaction entity, default `-1`. Pass a **string** when
   composing several `build-transaction` outputs into one tx-data
   (a `kontor.process` step that posts N entries): each call needs a
   distinct tempid or the transactions collide into one. With a
   string `s`, postings get tempids `\"s-p0\"`, `\"s-p1\"`, …; the
   default `-1` keeps the original `-100-i` posting tempids."
  [{:keys [transaction postings tx-tempid] :as input}]
  (let [report (validate input)]
    (when-not (:ok? report)
      (throw (ex-info "build-transaction: input failed structural validation"
                      {:report report
                       :input input}))))
  (let [tx-tempid (or tx-tempid -1)
        posting-tempid (if (string? tx-tempid)
                         (fn [i] (str tx-tempid "-p" i))
                         (fn [i] (- -100 i)))
        tx-base   (cond-> (assoc transaction :db/id tx-tempid)
                    (nil? (:kontor.transaction/state transaction))
                    (assoc :kontor.transaction/state :draft))
        ;; Each posting becomes its own entity referencing the
        ;; transaction. Default display-type :product. Valid-time is
        ;; carried on the tx via :tx/valid-from (kontor.bitemporal),
        ;; defaulting to :kontor.transaction/effective-date.
        posting-entities
        (mapv (fn [i posting]
                (cond-> (assoc posting
                               :db/id (posting-tempid i)
                               :kontor.posting/transaction tx-tempid)
                  (nil? (:kontor.posting/display-type posting))
                  (assoc :kontor.posting/display-type :product)))
              (range)
              postings)]
    (kbt/with-vt (into [tx-base] posting-entities)
      (:kontor.transaction/effective-date transaction)
      kbt/forever)))

(defn post-transaction-tx-data
  "Pure tx-data builder for `post-transaction!` (ADR-068). Stamps
   `:kontor.transaction/state :posted` + `:posted-at` (default now),
   propagates `:posted-at` onto each posting, builds via
   `build-transaction`, and applies `kbt/with-vt` (vt-from defaults
   to `:kontor.transaction/effective-date`)."
  ([input] (post-transaction-tx-data input {}))
  ([input {:keys [posted-at vt-from vt-to]}]
   (let [pa (or posted-at (java.util.Date.))
         input' (-> input
                    (assoc-in [:transaction :kontor.transaction/state] :posted)
                    (assoc-in [:transaction :kontor.transaction/posted-at] pa)
                    (update :postings
                            (fn [ps]
                              (mapv #(if (:kontor.posting/posted-at %)
                                       %
                                       (assoc % :kontor.posting/posted-at pa))
                                    ps))))
         tx-data (build-transaction input')
         vf (or vt-from (-> input :transaction :kontor.transaction/effective-date))]
     (kbt/with-vt tx-data vf (or vt-to kbt/forever)))))

(defn post-transaction!
  "Build + seal a balanced transaction atomically. Routes through
   the gate (ADR-068).

   Input shape mirrors `build-transaction`. Opts:
     :posted-at  — sealing timestamp (default = now)
     :vt-from    — valid-time start (default = :kontor.transaction/effective-date)
     :vt-to      — valid-time end (default = kbt/forever)

   This is the kernel-level kernel-pure way to build + seal in one
   move. Companion modules with richer lifecycles (orders, invoices,
   procurement) compose the status machine on top — see
   `kontor.invoice.posting/post-to-ledger!` for the ADR-038-integrated
   variant.

   The pure tx-data builder is `post-transaction-tx-data`."
  ([conn input] (post-transaction! conn input {}))
  ([conn input opts]
   (transact-with-validation* conn (post-transaction-tx-data input opts))))

;; ============================================================================
;; Analytic-distribution expansion (ADR-022, split-line strategy)
;; ============================================================================
;;
;; Per ADR-022 kontor stores analytic distributions on a single posting
;; by default (Odoo-style — preserves the distribution as a queryable
;; fact). `expand-distribution` is the opt-in helper for consumers who
;; want the SAP / NetSuite "one posting per cost center" shape.
;;
;; Usage:
;;   (-> {:transaction {...} :postings [posting]}
;;       (update :postings #(into [] (mapcat (fn [p]
;;                                              (expand-distribution
;;                                               p [:kontor.analytic-plan/code "COST-CENTER"])))
;;                                 %)))
;;       (build-transaction))

(defn- distribution-matches-plan?
  "True iff a distribution map's :kontor.analytic-distribution/plan value
   equals the supplied plan-spec. Plan-spec may be an eid, a
   lookup-ref like [:kontor.analytic-plan/code \"COST-CENTER\"], or any
   value that the caller used in the distribution entry."
  [plan-spec dist]
  (= plan-spec (:kontor.analytic-distribution/plan dist)))

(defn- precision-for-amount
  "At the build/expansion layer we don't have DB access to query
   :kontor.commodity/precision, so derive precision from the Money's amount
   scale (max 2 for fiat). Per ADR-013."
  [^java.math.BigDecimal amt]
  (max 2 (.scale amt)))

(defn- expand-distribution--per-plan
  "Split a posting's amount across the distribution entries that
   match `plan-spec`. Other plans' distributions ride along on each
   child unchanged. See `expand-distribution`."
  [posting plan-spec]
  (let [all-dists (:kontor.posting/analytic-distributions posting)
        matching  (filterv (partial distribution-matches-plan? plan-spec) all-dists)
        others    (filterv (complement (partial distribution-matches-plan? plan-spec))
                           all-dists)]
    (if (empty? matching)
      [posting]
      (let [parent-money (money/posting->money posting)
            percents     (mapv :kontor.analytic-distribution/percent matching)
            precision    (precision-for-amount (:amount parent-money))
            splits       (money/split-by-percentages parent-money percents precision)]
        (->> (map vector matching splits)
             ;; Drop zero-percent children — no zero-amount postings.
             (remove (fn [[d _]]
                       (zero? (compare (bigdec (:kontor.analytic-distribution/percent d))
                                       0M))))
             (mapv (fn [[dist split-money]]
                     (-> posting
                         (assoc :kontor.posting/amount (:amount split-money))
                         (assoc :kontor.posting/analytic-distributions
                                (into [{:kontor.analytic-distribution/plan plan-spec
                                        :kontor.analytic-distribution/account
                                        (:kontor.analytic-distribution/account dist)
                                        :kontor.analytic-distribution/percent 100M}]
                                      others))))))))))

(defn expand-distribution
  "Expand a single posting that carries analytic distributions under
   `plan-spec` into N postings, one per distribution entry, with the
   amount split per percent (largest-remainder method — see
   `kontor.money/split-by-percentages`).

   `plan-spec` matches a distribution's :kontor.analytic-distribution/plan
   value exactly (eid, lookup-ref, etc. — pass it in whatever form
   the caller used to tag the distribution).

   Each child posting inherits:
     - :kontor.posting/account, :kontor.posting/commodity, :kontor.posting/ledger
     - :kontor.posting/partner, :kontor.posting/narration
     - :kontor.posting/display-type, :kontor.posting/period-tag
     - other plans' distributions (they ride along unchanged on each
       child — the default semantic). Pass :strategy :cartesian to
       split across all plans simultaneously (rare; not yet implemented).

   Child amount = trunc(amount × percent / 100), with residue
   redistributed by largest-remainder so the children sum bit-exact
   to the parent.

   Distributions with :kontor.analytic-distribution/percent = 0 produce no
   child (we don't emit zero-amount postings).

   If the posting carries no distributions for `plan-spec`, returns
   `[posting]` unchanged (caller can fold this into a mapcat).

   The expander does NOT validate the per-plan sum-to-100 invariant
   itself — that's `kontor.posting/validate`'s job at posting time.
   Callers that build by hand should validate before expanding.

   Returns a sequence of posting maps."
  ([posting plan-spec]
   (expand-distribution posting plan-spec {}))
  ([posting plan-spec {:keys [strategy] :or {strategy :per-plan}}]
   (case strategy
     :per-plan  (expand-distribution--per-plan posting plan-spec)
     :cartesian (throw (ex-info "expand-distribution :cartesian not yet implemented"
                                {:posting posting :plan-spec plan-spec}))
     (throw (ex-info "Unknown :strategy" {:strategy strategy})))))

;; ============================================================================
;; Stock-move posting builder — ADR-030
;; ============================================================================

(def stock-move-roles
  "Stable vocabulary of `account-fn` role keywords the stock-move
   builder asks for. Consumers should switch on these and return a
   resolved account ref for each.

   ## Receipt + issue (in use today)

     :inventory         — the stock asset account (Dr on receipt,
                          Cr on issue / scrap)
     :gr-ir-clearing    — Goods-Received / Invoice-Received clearing
                          (Cr on receipt; flips to AP at invoice time)
     :cogs              — Cost-of-Goods-Sold expense (Dr on issue
                          for sale; Continental jurisdictions may
                          redirect to a generic material-expense
                          account via the account-fn)
     :price-variance    — Purchase-Price-Variance / Material-Usage-
                          Variance expense (standard-cost provider
                          emits this leg when actual ≠ standard)

   ## Adjustments (reserved; consumed by a future `plan-adjustment-move`)

     :landed-cost-clearing — accrual account for landed-cost
                             vouchers arriving after receipt
     :revaluation-gain     — Cr leg of an upward revaluation
     :revaluation-loss     — Dr leg of a downward revaluation
     :write-down-expense   — Dr leg of an inventory write-down
     :write-up-revenue     — Cr leg of an inventory write-up

   New roles may be added; existing roles must remain spelled this
   way (forward-compat surface — published once, never renamed)."
  #{:inventory :gr-ir-clearing :cogs :price-variance
    :landed-cost-clearing :revaluation-gain :revaluation-loss
    :write-down-expense :write-up-revenue})

(defn- ^java.math.BigDecimal money-amount [bd]
  (.setScale ^java.math.BigDecimal bd 2 java.math.RoundingMode/HALF_EVEN))

(defn- ledger-assoc
  "Conditionally tag a posting map with :kontor.posting/ledger. Omits the key
   when `ledger` is nil so the kernel defaults apply (ADR-021)."
  [posting ledger]
  (cond-> posting
    ledger (assoc :kontor.posting/ledger ledger)))

(defn- receipt-postings
  "Build the GL postings for a receipt: Dr inventory / Cr GR-IR-clearing
   (+ optional price-variance line for standard cost).

   `account-fn` resolves accounts by role keyword."
  [{:keys [tx-tempid commodity ledger qty unit-cost variance account-fn move]}]
  (let [total      (money-amount (.multiply ^java.math.BigDecimal qty
                                            ^java.math.BigDecimal unit-cost))
        gr-ir-amt  (if variance
                     (money-amount (.add total ^java.math.BigDecimal variance))
                     total)
        base       [(ledger-assoc
                     {:kontor.posting/account     (account-fn move :inventory)
                      :kontor.posting/amount      total
                      :kontor.posting/commodity   commodity
                      :kontor.posting/transaction tx-tempid}
                     ledger)
                    (ledger-assoc
                     {:kontor.posting/account     (account-fn move :gr-ir-clearing)
                      :kontor.posting/amount      (.negate ^java.math.BigDecimal gr-ir-amt)
                      :kontor.posting/commodity   commodity
                      :kontor.posting/transaction tx-tempid}
                     ledger)]]
    (if (and variance (not (zero? (.signum ^java.math.BigDecimal variance))))
      (conj base
            (ledger-assoc
             {:kontor.posting/account     (account-fn move :price-variance)
              :kontor.posting/amount      ^java.math.BigDecimal variance
              :kontor.posting/commodity   commodity
              :kontor.posting/transaction tx-tempid}
             ledger))
      base)))

(defn- assert-balanced!
  "Validate the assembled postings for a stock-move tx and throw if
   they don't sum to zero per (ledger, commodity). Mirrors
   `build-transaction`'s contract so `plan-stock-move` cannot emit
   structurally-broken tx-data."
  [tx-base posting-entities]
  (let [report (validate {:transaction tx-base :postings posting-entities})]
    (when-not (:ok? report)
      (throw (ex-info "plan-stock-move: assembled tx is not balanced"
                      {:report report
                       :postings posting-entities})))))

(defn- issue-postings
  "Build the GL postings for an issue: Dr cogs / Cr inventory at
   total consumption value."
  [{:keys [tx-tempid commodity ledger consumptions account-fn move]}]
  (let [total (reduce (fn [^java.math.BigDecimal acc {:keys [qty unit-cost]}]
                        (.add acc (.multiply ^java.math.BigDecimal qty
                                             ^java.math.BigDecimal unit-cost)))
                      0M
                      consumptions)
        total (money-amount total)]
    [(ledger-assoc
      {:kontor.posting/account     (account-fn move :cogs)
       :kontor.posting/amount      total
       :kontor.posting/commodity   commodity
       :kontor.posting/transaction tx-tempid}
      ledger)
     (ledger-assoc
      {:kontor.posting/account     (account-fn move :inventory)
       :kontor.posting/amount      (.negate total)
       :kontor.posting/commodity   commodity
       :kontor.posting/transaction tx-tempid}
      ledger)]))

(defn plan-stock-move
  "Plan the kernel-level facts for a single stock movement. Pure
   (db-read only; no transact). Returns tx-data ready for
   `datahike.api/transact`.

   Mandatory input keys:
     :direction       :in | :out
     :book            valuation-book eid or :kontor.valuation-book/code string
     :item            ref (caller-defined item entity)
     :qty             bigdec
     :commodity       commodity ref
     :journal         journal ref
     :effective-date  #inst
     :unit-cost       bigdec  — required for :in
     :provider        CostingProvider impl — required
     :account-fn      (fn [move role-kw] → account ref) — required

   Optional keys:
     :ledger     ledger ref (defaults to nil = primary ledger group)
     :lot        lot ref
     :narration  string
     :transaction-state  :draft (default) | :posted
     :note       string

   ADR-030 — pure; no side effects; reads `db` for layer resolution
   on issues. Hooks are external (the CostingProvider and account-fn
   ARE the seams).

   For an :in move:
     - Resolve receipt via `(plan-receipt provider db request)` →
       create one new :valuation-layer.
     - Emit Dr inventory / Cr GR-IR-clearing postings (+ optional
       :price-variance leg from standard-cost provider).

   For an :out move:
     - Resolve consumption via `(plan-consumption provider db request)`
       → create N :layer-consumption entities pointing at drawn layers.
     - Emit Dr COGS / Cr inventory postings at total consumption value.

   Returns tx-data as a flat vector. Caller composes:
     (d/transact conn (plan-stock-move db move-spec))"
  [db {:keys [direction book item qty commodity lot journal effective-date
              unit-cost ledger narration provider account-fn
              transaction-state note]
       :or   {transaction-state :draft}
       :as   move-spec}]
  (when-not provider
    (throw (ex-info "plan-stock-move: :provider is required" {:move move-spec})))
  (when-not account-fn
    (throw (ex-info "plan-stock-move: :account-fn is required" {:move move-spec})))
  (when-not (#{:in :out} direction)
    (throw (ex-info "plan-stock-move: :direction must be :in or :out"
                    {:move move-spec})))
  (let [book-eid (valuation/resolve-book db book)
        _ (when-not book-eid
            (throw (ex-info "plan-stock-move: book not found"
                            {:book book})))
        tx-tempid -1
        tx-base   (cond-> {:db/id                       tx-tempid
                           :kontor.transaction/journal         journal
                           :kontor.transaction/effective-date  effective-date
                           :kontor.transaction/state           transaction-state}
                    narration (assoc :kontor.transaction/narration narration))]
    (case direction

      :in
      (let [receipt-req {:book      book-eid
                         :item      item
                         :qty       qty
                         :unit-cost unit-cost
                         :commodity commodity
                         :lot       lot}
            {:keys [layer-data variance]}
            (costing/plan-receipt provider db receipt-req)
            layer-tempid -200
            layer-entity (cond-> {:db/id                              layer-tempid
                                  :kontor.valuation-layer/book               book-eid
                                  :kontor.valuation-layer/item               item
                                  :kontor.valuation-layer/origin-transaction tx-tempid
                                  :kontor.valuation-layer/qty-original       (:qty layer-data)
                                  :kontor.valuation-layer/unit-cost-original (:unit-cost layer-data)
                                  :kontor.valuation-layer/commodity          commodity
                                  :kontor.valuation-layer/received-at        effective-date}
                           lot  (assoc :kontor.valuation-layer/lot lot)
                           note (assoc :kontor.valuation-layer/note note))
            postings (receipt-postings
                      {:tx-tempid tx-tempid
                       :commodity commodity
                       :ledger    ledger
                       :qty       qty
                       ;; Use the LAYER's unit-cost for the inventory leg.
                       ;; For FIFO/AVG that's the user's unit-cost (no variance).
                       ;; For Standard that's the standard cost; variance carries
                       ;; the delta and lands on the variance leg.
                       :unit-cost (:unit-cost layer-data)
                       :variance  variance
                       :account-fn account-fn
                       :move      move-spec})
            posting-entities
            (mapv (fn [i p]
                    (cond-> (assoc p :db/id (- -300 i))
                      (nil? (:kontor.posting/display-type p))
                      (assoc :kontor.posting/display-type :product)))
                  (range)
                  postings)
            _ (assert-balanced! tx-base posting-entities)]
        (kbt/with-vt (into [tx-base layer-entity] posting-entities)
          effective-date kbt/forever))

      :out
      (let [;; Thread the move's effective-date as the bitemporal
            ;; cursor for layer + consumption visibility. A backdated
            ;; issue sees only layers received at or before its
            ;; effective-date and only consumptions already issued.
            consumption-req {:book book-eid :item item :qty qty :lot lot
                             :as-of-valid effective-date}
            {:keys [consumptions underflow]}
            (costing/plan-consumption provider db consumption-req)]
        (when (and underflow (pos? (.signum ^java.math.BigDecimal underflow)))
          (throw (ex-info "plan-stock-move: insufficient stock to satisfy issue"
                          {:requested qty :underflow underflow :item item :book book-eid})))
        (let [postings (issue-postings
                        {:tx-tempid tx-tempid
                         :commodity commodity
                         :ledger    ledger
                         :consumptions consumptions
                         :account-fn account-fn
                         :move      move-spec})
              consumption-entities
              (mapv (fn [i {:keys [layer qty unit-cost]}]
                      {:db/id                                       (- -400 i)
                       :kontor.layer-consumption/layer                     layer
                       :kontor.layer-consumption/qty                       qty
                       :kontor.layer-consumption/unit-cost-at-consumption  unit-cost
                       :kontor.layer-consumption/issue-transaction         tx-tempid
                       :kontor.layer-consumption/issued-at                 effective-date})
                    (range)
                    consumptions)
              posting-entities
              (mapv (fn [i p]
                      (cond-> (assoc p :db/id (- -300 i))
                        (nil? (:kontor.posting/display-type p))
                        (assoc :kontor.posting/display-type :product)))
                    (range)
                    postings)
              _ (assert-balanced! tx-base posting-entities)]
          (kbt/with-vt (into (into [tx-base] posting-entities) consumption-entities)
            effective-date kbt/forever))))))
