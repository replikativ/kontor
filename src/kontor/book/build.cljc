(ns kontor.book.build
  "The pure builder half of the `kontor.book` verb facade, extracted so the
   browser can turn a friendly verb map into balanced posting tx-data
   client-side (rung 1, note 192). `build-input` + `entry-tx-data` + the
   value-coercion helpers depend only on `kontor.posting.build` and
   `kontor.money` — no datahike, no gate, no conn. `kontor.book` re-exports
   `entry-tx-data` and keeps the conn-bound `!` verbs (`entry!`, `sell!`, …)
   and `validate-entry`."
  (:require [kontor.money :as money]
            [kontor.posting.build :as posting-build])
  #?(:clj (:import [java.math BigDecimal])))

(defn- ->bigdec
  [x]
  #?(:clj  (cond
             (instance? BigDecimal x) x
             (nil? x)                 nil
             :else                    (bigdec x))
     ;; cljs: coerce string/int/Bigdec via money; reject floats; nil→nil.
     :cljs (when (some? x) (money/->amount x))))

(defn- ->commodity-ref
  "Coerce a consumer-friendly `:commodity` value to a datahike
   reference. Note 160 §I-2: bare keyword `:EUR` or short string `\"EUR\"`
   are the natural ways to write a commodity in a verb call; both get
   auto-promoted to the canonical `[:kontor.commodity/symbol \"EUR\"]`
   lookup-ref. Pre-existing eid (Long) or explicit lookup-ref (vector)
   passes through unchanged."
  [c]
  (cond
    (nil? c)              nil
    (vector? c)           c                       ; already a lookup-ref
    (number? c)           c                       ; already an eid
    (keyword? c)          [:kontor.commodity/symbol (name c)]
    (string? c)           [:kontor.commodity/symbol c]
    :else                 c))

(defn- ->dimension-value
  "Coerce a friendly dimension value to the schema's string."
  [v]
  (cond
    (string? v)  v
    (keyword? v) (name v)
    :else        (str v)))

(defn- ->dimensions
  "Map a friendly `{axis value-or-coll}` dimensions map into a vector
   of `:posting-dimension` entity maps (ADR-097). A collection value
   expands to one dimension per element."
  [dimensions]
  (vec (for [[axis v] dimensions
             one      (if (coll? v) v [v])]
         {:kontor.posting-dimension/axis  axis
          :kontor.posting-dimension/value (->dimension-value one)})))

(defn- ->analytic-distributions
  "Map a friendly `[{:plan p :account a :percent 60}]` vector into
   `:kontor.analytic-distribution/*` entity maps (ADR-022). Already-shaped
   maps (any key already in the `:kontor.analytic-distribution` namespace)
   pass through untouched, so a caller holding kernel-shaped data is not
   forced to un-shape it."
  [dists]
  (mapv (fn [{:keys [plan account percent] :as d}]
          (if (some #(= "kontor.analytic-distribution" (namespace %)) (keys d))
            d
            (cond-> {}
              plan    (assoc :kontor.analytic-distribution/plan plan)
              account (assoc :kontor.analytic-distribution/account account)
              (some? percent)
              (assoc :kontor.analytic-distribution/percent (->bigdec percent)))))
        dists))

(defn- ->posting
  "Map a friendly `{:account :amount :commodity? :entity? :partner?
   :dimensions? :period-tag? :analytic-distributions?}` posting (used in
   `:postings` for multi-leg entries) into the kernel `:kontor.posting/*`
   shape, defaulting commodity + entity + partner to the entry-level ones.
   Per-posting overrides:
   - `:entity`  — ADR-031 intercompany pattern (per-entity sum-to-zero)
   - `:partner` — per-leg counterparty (e.g. multi-shareholder dividend
                  declaration; note 160 §I-15)
   - `:period-tag` — ADR-014 adjustment-period routing. This builder
     rebuilds each posting from a fixed key list, so a key absent from that
     list is SILENTLY DROPPED — which is what happened to `:ledger` before
     note 160 and to `:period-tag` until ADR-140. The consequence for
     `:period-tag` was that a `:kontor.period/tag :adjustment-13` period
     locked nothing writable: the lock predicate demands a matching
     `:kontor.posting/period-tag`, and no posting written through the verb
     facade could carry one. SAP-style period 13 was documented, checked,
     and unreachable.
   - `:analytic-distributions` — ADR-022 cost-centre splits. Same defect,
     worse consequence once ADR-140 made `:kontor.account/required-analytic-plans`
     actually refuse an undistributed posting: an account naming a required
     plan would have become unpostable through `kontor.book` entirely."
  [default-commodity default-entity default-partner default-ledger
   {:keys [account amount commodity entity partner ledger dimensions
           period-tag analytic-distributions] :as p}]
  (when (nil? account)
    (throw (ex-info "kontor.book: each :postings entry needs :account" {:posting p})))
  (when (nil? amount)
    (throw (ex-info "kontor.book: each :postings entry needs :amount" {:posting p})))
  (let [c  (->commodity-ref (or commodity default-commodity))
        e  (or entity    default-entity)
        pa (or partner   default-partner)
        lg (or ledger    default-ledger)]
    (when (nil? c)
      (throw (ex-info "kontor.book: posting needs :commodity (or an entry-level :commodity)"
                      {:posting p})))
    (cond-> {:kontor.posting/account   account
             :kontor.posting/amount    (->bigdec amount)
             :kontor.posting/commodity c}
      e                (assoc :kontor.posting/entity e)
      pa               (assoc :kontor.posting/partner pa)
      lg               (assoc :kontor.posting/ledger lg)
      (seq dimensions) (assoc :kontor.posting/dimensions (->dimensions dimensions))
      period-tag       (assoc :kontor.posting/period-tag period-tag)
      (seq analytic-distributions)
      (assoc :kontor.posting/analytic-distributions
             (->analytic-distributions analytic-distributions)))))

(defn build-input
  "Translate a verb options map into the `{:transaction … :postings …}`
   shape `kontor.posting.build/post-transaction-tx-data` expects. Pure;
   throws `ex-info` on a missing required field.

   `:entity` (optional, ADR-031) is stamped on every posting via
   `:kontor.posting/entity` — required for per-entity trial-balance / BS / GuV
   filters to scope correctly. Per-posting `:entity` overrides the
   entry-level one (intercompany).

   `:ledger` (optional, ADR-021) is stamped the same way via
   `:kontor.posting/ledger` — the parallel-book axis (HGB alongside IFRS).
   Omitting it means the primary book. It is an entry-level option
   BECAUSE it was previously reachable through neither: this builder
   rebuilt each posting from a fixed key list, so a `:ledger` passed in
   `:postings` was silently discarded and ADR-021 parallel books could
   not be written through `kontor.book` at all.

   `:period-tag` (optional, ADR-014) is stamped on every posting via
   `:kontor.posting/period-tag` — the adjustment-period routing axis. It is
   an entry-level option for the same reason `:ledger` is: it was reachable
   through neither path, which made a `:kontor.period/tag :adjustment-13`
   period a lock with nothing to lock (ADR-140). A per-posting `:period-tag`
   overrides it, though routing one leg of an entry into a different period
   than its counter-leg is almost always a mistake."
  [{:keys [debit-account credit-account amount commodity journal
           effective-date narration partner external-id entity ledger
           period-tag postings]}]
  (when (nil? journal)
    (throw (ex-info "kontor.book: :journal is required" {})))
  (when (nil? effective-date)
    (throw (ex-info "kontor.book: :effective-date is required" {})))
  (let [stamp-tag (fn [p] (cond-> p
                            (and period-tag (nil? (:kontor.posting/period-tag p)))
                            (assoc :kontor.posting/period-tag period-tag)))
        ps (cond
             (seq postings)
             (mapv #(->posting commodity entity partner ledger %) postings)

             :else
             (let [amt (->bigdec amount)
                   c   (->commodity-ref commodity)]
               (when (nil? debit-account)
                 (throw (ex-info "kontor.book: :debit-account is required" {})))
               (when (nil? credit-account)
                 (throw (ex-info "kontor.book: :credit-account is required" {})))
               (when (nil? amt)
                 (throw (ex-info "kontor.book: :amount is required" {})))
               (when (nil? c)
                 (throw (ex-info "kontor.book: :commodity is required" {})))
               [(cond-> {:kontor.posting/account   debit-account
                         :kontor.posting/amount    amt
                         :kontor.posting/commodity c}
                  entity (assoc :kontor.posting/entity entity)
                  ledger (assoc :kontor.posting/ledger ledger))
                (cond-> {:kontor.posting/account   credit-account
                         :kontor.posting/amount    (money/negate-amount amt)
                         :kontor.posting/commodity c}
                  entity (assoc :kontor.posting/entity entity)
                  ledger (assoc :kontor.posting/ledger ledger))]))
        ps (mapv stamp-tag ps)]
    {:transaction (cond-> {:kontor.transaction/journal        journal
                           :kontor.transaction/effective-date effective-date}
                    narration   (assoc :kontor.transaction/narration narration)
                    partner     (assoc :kontor.transaction/partner partner)
                    external-id (assoc :kontor.transaction/external-id external-id))
     :postings    ps}))

(defn post-opts
  "The subset of an options map that is forwarded to
   `post-transaction-tx-data` as builder opts."
  [opts]
  (select-keys opts [:posted-at :vt-from :vt-to]))

(defn entry-tx-data
  "Pure tx-data builder for a balanced, sealed transaction — the single
   ADR-068 builder behind every `kontor.book` verb. Composable into a
   `kontor.workflow.process` step list.

   Requires `:journal` and `:effective-date` explicitly (it is pure).
   Use `kontor.book/entry!` / the named verbs for the ergonomic path
   (journal resolved by type, `:effective-date` defaulted to now)."
  [opts]
  (posting-build/post-transaction-tx-data (build-input opts) (post-opts opts)))
