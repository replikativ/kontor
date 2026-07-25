(ns kontor.posting.validate
  "Pure, cross-platform structural validation for a draft transaction —
   the tier-1 \"does it balance / is it well-formed\" check that needs no
   database. Extracted from `kontor.posting` (which stays JVM-only: it
   carries the datahike tx-data builders, `java.util.Date`, and the FX /
   costing wiring) so the SAME balance logic runs client-side in the
   browser (ClojureScript over `kontor.money`'s cljc arithmetic) and
   server-side, with no drift.

   Dependency-light on purpose: this namespace requires only
   `kontor.money` (itself `.cljc`). Everything here is map access,
   `group-by`/`reduce-kv`, and Money sums — no substrate, no I/O.

   `kontor.posting` re-exports `validate` (and the public balance/mode
   helpers) so existing JVM callers — and the sum-to-zero validator —
   keep resolving `kontor.posting/validate` unchanged (research note 190;
   the client runs this half, the gate runs the db half via
   `kontor.gate/validate-candidate`)."
  (:require [kontor.money :as money]))

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

(defn balance-affecting?
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
;; Analytic distributions: sum-to-100 per plan — ADR-022 / ADR-124
;;
;; `:kontor.analytic-distribution/percent`'s schema doc says "0..100
;; inclusive. Sum-to-100 per plan is enforced by the report engine"; the
;; per-account `:kontor.account/required-analytic-plans` doc says "the posting
;; validator enforces a sum-to-100 invariant per named plan". Neither was
;; true — no code read either attribute, so a cost-centre split of 60/30
;; (90%) was accepted and every downstream management report lost 10% of the
;; cost silently. This is the pure half of making it true: a posting that
;; carries distributions under plan P must have P's percents sum to exactly
;; 100 and each entry in [0,100]. The db-dependent half — "the account
;; REQUIRES plan P, so it must be present at all" — needs the account's
;; attrs, so it lives in `kontor.analytic` (gate) and `kontor.governance`
;; (writer, mandatory).
;;
;; Odoo's analogue is `addons/analytic/models/analytic_mixin.py`
;; `_validate_distribution`, driven by `analytic_plan.applicability`.
;; ============================================================================

(def analytic-total
  "What an analytic plan's percents must sum to. Public so the db-side
   half (`kontor.analytic`, `kontor.governance`) compares against the same
   constant the pure half does."
  100M)

(defn distribution-plan
  "The plan a distribution entry names — the raw
   `:kontor.analytic-distribution/plan` value (eid, lookup-ref, ident), used
   as the grouping key exactly as supplied. Postings built by different
   reference forms for the same plan therefore group apart; that is the same
   convention `ledger-key` uses, and post-resolution the governor regroups by
   resolved eid."
  [dist]
  (:kontor.analytic-distribution/plan dist))

(defn analytic-totals
  "`{plan-key <summed percent>}` over one posting's
   `:kontor.posting/analytic-distributions`. Empty map when the posting
   carries none. Sums with `money/add-amount` so the fold is bigdec-exact on
   the JVM and in the browser (never `+` — cljs `Bigdec` does not add under
   core arithmetic)."
  [posting]
  (reduce (fn [acc d]
            (update acc (distribution-plan d)
                    (fnil #(money/add-amount % (money/->amount
                                                (:kontor.analytic-distribution/percent d)))
                          (money/zero-amount))))
          {}
          (:kontor.posting/analytic-distributions posting)))

(defn- analytic-validation-errors
  "Per-posting analytic-distribution problems: any plan whose percents do not
   total exactly 100, and any single entry outside [0,100]."
  [posting]
  (let [dists (:kontor.posting/analytic-distributions posting)]
    (if (empty? dists)
      []
      (into
       ;; per-entry range — the schema's stated "0..100 inclusive"
       (vec (for [d dists
                  :let [p (some-> (:kontor.analytic-distribution/percent d)
                                  money/->amount)]
                  :when (or (nil? p)
                            (neg? (money/compare-amounts p (money/zero-amount)))
                            (pos? (money/compare-amounts p analytic-total)))]
              {:posting posting
               :error :analytic-percent-out-of-range
               :plan (distribution-plan d)
               :percent (:kontor.analytic-distribution/percent d)
               :message (str "analytic distribution percent "
                             (pr-str (:kontor.analytic-distribution/percent d))
                             " is outside 0..100 (plan "
                             (pr-str (distribution-plan d)) ")")}))
       (for [[plan total] (analytic-totals posting)
             :when (not (zero? (money/compare-amounts total analytic-total)))]
         {:posting posting
          :error :analytic-distribution-not-100
          :plan plan
          :total total
          :message (str "analytic distributions under plan " (pr-str plan)
                        " total " (str total) "%, not 100% — a partial "
                        "distribution silently drops "
                        (str (money/add-amount analytic-total
                                               (money/negate-amount total)))
                        "% of this posting from every report that "
                        "marginalizes over the plan")})))))

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

   Per ADR-022 / ADR-124 a posting carrying
   :kontor.posting/analytic-distributions must have its percents total
   exactly 100 per plan, each in [0,100] — errors
   `:analytic-distribution-not-100` / `:analytic-percent-out-of-range`.
   Whether a plan is REQUIRED for the posting's account
   (:kontor.account/required-analytic-plans) needs db access and is checked
   by `kontor.analytic` in the gate and by `kontor.governance` in the
   writer; this pure pass only judges what the posting itself carries.

   Postings without :kontor.posting/ledger group under nil — this nil-group
   conceptually IS the primary book, so readers should treat it the
   same as a posting explicitly tagged with the primary-ledger ref.
   The kernel does NOT auto-inject a lookup-ref at build time
   because the invariant library's speculative-apply uses an empty
   schema-only DB that cannot resolve unique-identity refs to data
   entities."
  [{:keys [transaction postings] :as input}]
  (let [posting-errors (concat (mapcat posting-validation-errors postings)
                               (mapcat analytic-validation-errors postings))
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
