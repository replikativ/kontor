(ns kontor.report
  "Declarative report engine.

   A report is a (typically named) tree of expressions, each
   computed by an *engine* (see `engines` below). The two kernel
   engines, per the user's Phase-1.5 scope cut:

     :account-codes — sum postings whose `:account/code` matches
                      any of the given prefix patterns. The DE UStVA
                      uses this for boxes that key off konto-number
                      patterns (e.g. \"all 4400/4410/4420 sales 19%\").

     :tax-tags      — sum postings whose `:posting/account-tags`
                      (or whose account's `:account/tags`) include
                      any of the given tag keywords. Used by the
                      DE UStVA for box-keyed aggregations
                      (e.g. :ust-81 = sales 19%, :ust-86 = sales 7%,
                      :ust-66 = total deductible Vorsteuer).

   A report definition is plain data:

     {:report/name \"UStVA 2026 (monatlich)\"
      :report/country \"DE\"
      :report/lines
      [{:line/code   \"81\"
        :line/label  \"Steuerpflichtige Umsätze 19%\"
        :line/expression {:engine :tax-tags
                          :tags   [:ust-81]
                          :sign   :inflow}}
       {:line/code   \"86\"
        :line/label  \"Steuerpflichtige Umsätze 7%\"
        :line/expression {:engine :tax-tags
                          :tags   [:ust-86]
                          :sign   :inflow}}
       …]}

   `:sign` can be `:inflow` (sum the natural-balance side of the
   account class — credits for income/liabilities, debits for
   asset/expense) or `:raw` (sum the raw signed amounts as stored).
   Defaults to `:raw`.

   The engine produces, per expression:
     {:value Money :postings [<eid> ...]}

   so consumers can drill from a report line into the contributing
   postings — important for audit defense.

   `compute-report` is bitemporal-aware (same axes as balance.clj).
   Default = today/today.

   Future engines (`:aggregation`, `:domain`) come when a real
   need surfaces; per ADR-014's spirit we don't build the broader
   Odoo shape until a second country forces it."
  (:require [clojure.set]
            [clojure.string :as str]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.money :as money])
  (:import [java.util Date]))

;; ============================================================================
;; Internal: bitemporal posting fetch
;; ============================================================================

(def ^:private default-included-states #{:posted})

(defn- now ^Date [] (Date.))

(defn- before-or-eq? [^Date a ^Date b] (<= (.compareTo a b) 0))
(defn- on-or-after?  [^Date a ^Date b] (>= (.compareTo a b) 0))

(defn- pull-posting
  "Pull the posting + its account's commodity/code/type/tags + tx state.
   Returns a flat map suitable for predicate filtering. Adds
   `:valid-from` derived from the creating tx's `:tx/valid-from`
   (kontor.bitemporal) and `:ledger-eid` for the optional :ledger
   filter (ADR-021 — a nil :posting/ledger is the primary book)."
  [db p]
  (let [pulled (d/pull db
                       [:db/id
                        :posting/amount
                        :posting/commodity
                        :posting/transaction
                        {:posting/ledger [:db/id]}
                        {:posting/account [:account/code
                                           :account/type
                                           :account/tags]}
                        {:posting/account-tags [:account-tag/name]}]
                       p)
        tx-state (some-> (-> pulled :posting/transaction :db/id)
                         (#(d/pull db [:transaction/state] %))
                         :transaction/state)
        account (:posting/account pulled)
        vf (d/q '[:find ?vf .
                  :in $ ?p
                  :where
                  [?p :posting/transaction _ ?tx]
                  [?tx :db/txInstant ?ti]
                  [(get-else $ ?tx :db.valid/from ?ti) ?vf]]
                db p)
        ;; Account tags from the M2M; flatten to keywords
        acct-tag-names (->> (:account/tags account)
                            (map (fn [t]
                                   (or (:account-tag/name (d/pull db [:account-tag/name] (:db/id t)))
                                       (:account-tag/name t))))
                            (filter some?)
                            (map keyword)
                            set)
        ;; Posting-level tags (materialized at posting time)
        posting-tag-names (->> (:posting/account-tags pulled)
                               (map :account-tag/name)
                               (filter some?)
                               (map keyword)
                               set)]
    (assoc pulled
           :valid-from vf
           :tx-state tx-state
           :ledger-eid (:db/id (:posting/ledger pulled))
           :account-code (:account/code account)
           :account-type (:account/type account)
           :all-tags (clojure.set/union acct-tag-names posting-tag-names))))

(defn- in-window?
  "Check valid-from is in [from, to-exclusive)."
  [posting from to-exclusive]
  (let [vf (:valid-from posting)]
    (and (some? vf)
         (or (nil? from) (on-or-after? vf from))
         (or (nil? to-exclusive) (before-or-eq? vf (Date. (dec (.getTime ^Date to-exclusive)))))
         ;; the (dec) makes this strictly before; cleaner-looking than `< end`
         )))

;; ============================================================================
;; Posting → Money sign per :sign mode
;; ============================================================================

(defn- natural-sign
  "Per accounting convention:
     :asset / :expense  → debit-natural  (positive amounts grow)
     :liability / :equity / :income → credit-natural (negative amounts grow)
   `:inflow` reports the *natural* increase, so for credit-natural
   accounts we negate the stored signed amount."
  [account-type stored-amount]
  (case account-type
    (:liability :equity :income) (.negate ^java.math.BigDecimal stored-amount)
    stored-amount))

;; ============================================================================
;; Engines
;; ============================================================================

(defmulti run-engine
  "Dispatch on (:engine expression). Each engine receives the
   already-fetched seq of pulled postings and the expression spec,
   and returns {:value Money :postings [<eid>...]}."
  (fn [_postings expression _opts] (:engine expression)))

(defn- code-prefix-match?
  [^String code patterns]
  (some (fn [^String p]
          (cond
            (str/ends-with? p "%")
            (str/starts-with? code (subs p 0 (dec (count p))))

            :else
            (= code p)))
        patterns))

(defmethod run-engine :account-codes
  [postings {:keys [codes sign commodity] :or {sign :raw commodity :EUR}} _opts]
  (let [matched (filter (fn [p]
                          (let [code (:account-code p)]
                            (and (some? code) (code-prefix-match? code codes))))
                        postings)
        amounts (mapv (fn [p]
                        (let [stored (:posting/amount p)]
                          (case sign
                            :inflow (natural-sign (:account-type p) stored)
                            :raw    stored)))
                      matched)
        sum (if (seq amounts)
              (reduce (fn [^java.math.BigDecimal a ^java.math.BigDecimal b] (.add a b))
                      java.math.BigDecimal/ZERO
                      amounts)
              java.math.BigDecimal/ZERO)]
    {:value (money/money sum (or commodity :EUR))
     :postings (mapv :db/id matched)}))

(defmethod run-engine :tax-tags
  [postings {:keys [tags sign commodity] :or {sign :raw commodity :EUR}} _opts]
  (let [tag-set (set tags)
        matched (filter (fn [p]
                          (seq (clojure.set/intersection tag-set (:all-tags p))))
                        postings)
        amounts (mapv (fn [p]
                        (let [stored (:posting/amount p)]
                          (case sign
                            :inflow (natural-sign (:account-type p) stored)
                            :raw    stored)))
                      matched)
        sum (if (seq amounts)
              (reduce (fn [^java.math.BigDecimal a ^java.math.BigDecimal b] (.add a b))
                      java.math.BigDecimal/ZERO
                      amounts)
              java.math.BigDecimal/ZERO)]
    {:value (money/money sum (or commodity :EUR))
     :postings (mapv :db/id matched)}))

(defmethod run-engine :default
  [_ expression _opts]
  (throw (ex-info "Unknown report engine"
                  {:type :report/unknown-engine
                   :expression expression
                   :supported (-> (methods run-engine) keys set)})))

;; ============================================================================
;; Top-level: compute-report
;; ============================================================================

(defn- ledger-filter-pred
  "Build a posting predicate for the optional `:ledger` report
   filter. `ledger-spec` is an eid or lookup-ref. Per ADR-021 a
   posting with no `:posting/ledger` is conceptually in the PRIMARY
   book — so when the requested ledger is `:ledger/type :primary`,
   nil-ledger postings pass too. Returns `(constantly true)` when no
   ledger filter is requested."
  [db ledger-spec]
  (if (nil? ledger-spec)
    (constantly true)
    (let [{:keys [db/id ledger/type]} (d/pull db [:db/id :ledger/type] ledger-spec)]
      (when-not id
        (throw (ex-info "compute-report: :ledger not found" {:ledger ledger-spec})))
      (let [primary? (= :primary type)]
        (fn [p]
          (let [le (:ledger-eid p)]
            (or (= le id)
                (and primary? (nil? le)))))))))

(defn compute-report
  "Run a report definition against `conn` and return:

     {:report/name str
      :report/lines [{:line/code str :line/label str
                      :line/value Money :line/postings [eid ...]} ...]
      :report/window {:from Date :to Date}
      :report/computed-at Date}

   Options:
     :from           — inclusive lower bound on the posting's valid-from
                       (resolved via :tx/valid-from on the creating tx).
                       Default: nil = beginning of time.
     :to             — exclusive upper bound (default: nil = today+1d)
     :as-of-tx       — datahike snapshot timestamp (default: now)
     :include-states — set of :transaction/state values to include
                       (default: #{:posted}). Drafts excluded so the
                       report reflects what's actually been posted.
     :ledger         — optional ledger eid / lookup-ref. When set,
                       only postings on that ledger are summed (ADR-021
                       parallel books — the HGB-vs-IFRS Jahresabschluss
                       prerequisite). A nil-ledger posting counts as
                       the primary book."
  ([conn report] (compute-report conn report {}))
  ([conn report {:keys [from to as-of-tx include-states ledger]
                 :or   {include-states default-included-states}}]
   (let [as-of-tx (or as-of-tx (now))
         db (-> conn d/db (d/as-of as-of-tx))
         ledger-pred (ledger-filter-pred db ledger)
         all-pids (d/q '[:find [?p ...] :where [?p :posting/account _]] db)
         pulled (mapv #(pull-posting db %) all-pids)
         filtered (filter (fn [p]
                            (and (in-window? p from to)
                                 (contains? include-states (:tx-state p))
                                 (ledger-pred p)))
                          pulled)
         lines (mapv (fn [{:keys [:line/code :line/label :line/expression]}]
                       (let [{:keys [value postings]} (run-engine filtered expression {})]
                         {:line/code code
                          :line/label label
                          :line/value value
                          :line/postings postings}))
                     (:report/lines report))]
     {:report/name (:report/name report)
      :report/country (:report/country report)
      :report/window {:from from :to to}
      :report/lines lines
      :report/computed-at (now)})))

(defn line-value
  "Convenience: pull the Money value for `code` out of a computed
   report. Returns nil if the line isn't present."
  [computed code]
  (some (fn [l] (when (= code (:line/code l)) (:line/value l)))
        (:report/lines computed)))
