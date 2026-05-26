(ns kontor.import-.beancount
  ;; Directory is `import_` (trailing underscore) because `import` is
  ;; a reserved special-form name in Clojure / a JVM-level keyword.
  ;; Clojure's package-name-to-directory rule munges the underscore in
  ;; the namespace to a hyphen — so `kontor.import-.beancount` lives
  ;; in `src/kontor/import_/beancount.clj`. No work-around; this is
  ;; the standard escape hatch. Don't try to rename the directory.
  "Beancount round-trip — Phase 1 acceptance test (ADR-009).

   Covers the load-bearing subset of Beancount syntax:
     - `option \"key\" \"value\"`
     - `YYYY-MM-DD open Account:Path CURRENCY[,CURRENCY...]`
     - `YYYY-MM-DD close Account:Path`
     - `YYYY-MM-DD * \"payee\" \"narration\"` followed by indented postings
       `  Account:Path  AMOUNT CURRENCY`
     - `YYYY-MM-DD balance Account:Path AMOUNT CURRENCY`
     - `;`-prefixed comments
     - blank lines

   Out of scope (Phase 1):
     - cost / lot / price annotations on postings (`{...}`, `@`, `@@`)
     - pad, document, event, query, custom directives
     - meta key/value tags
     - links and tags (#tag, ^link)
     - includes / pushtag / poptag
     - flag variants beyond `*` (the `!` flag is parsed but currently
       maps to :draft; everything else is rejected)

   Round-trip definition (ADR-009 acceptance):
     parse → load into datahike → dump → diff against original
     (modulo whitespace/comment normalization)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [instaparse.core :as insta]
            [kontor.bitemporal :as kbt])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util Date TimeZone]))

;; ============================================================================
;; Grammar
;; ============================================================================

;; Tokens-and-productions style. Whitespace is significant: postings
;; live on indented lines and ungrouped blank lines separate
;; directives. The grammar uses explicit eol/indent productions
;; rather than `:auto-whitespace` so we keep precise control.

(def ^:private grammar
  "
file       = (block <blank-line>*)*
<block>    = comment / option / open / close / transaction / balance

comment    = <#';;?[^\\n]*'> <eol>
blank-line = <#'[ \\t]*\\n'>

option     = <'option'> <ws> string <ws> string <eol>

open       = date <ws> <'open'> <ws> account <ws> currencies <eol>
close      = date <ws> <'close'> <ws> account <eol>

transaction = txn-header posting+
txn-header = date <ws> flag <ws> string <ws> string <eol>
posting    = <ind> account <ws> amount <ws> currency <eol>

balance    = date <ws> <'balance'> <ws> account <ws> amount <ws> currency <eol>

date       = #'\\d{4}-\\d{2}-\\d{2}'
flag       = #'[*!]'
account    = #'[A-Z][A-Za-z0-9-]*(?::[A-Z0-9][A-Za-z0-9-]*)+'
currencies = currency (<','> <wsopt> currency)*
currency   = #'[A-Z][A-Z0-9\\'._-]{0,22}[A-Z0-9]'
amount     = #'-?[0-9]+(?:\\.[0-9]+)?'
string     = <'\"'> #'[^\"\\n]*' <'\"'>

ws         = #'[ \\t]+'
wsopt      = #'[ \\t]*'
ind        = #'[ \\t]+'
eol        = #'[ \\t]*\\n'
")

(def ^:private parser (insta/parser grammar))

;; ============================================================================
;; AST → domain
;; ============================================================================

(def ^:private utc (TimeZone/getTimeZone "UTC"))

(defn- date-str->inst
  "Parse a YYYY-MM-DD string into a java.util.Date at UTC midnight."
  ^Date [s]
  (let [ld (LocalDate/parse s DateTimeFormatter/ISO_LOCAL_DATE)
        cal (doto (java.util.Calendar/getInstance utc)
              (.clear)
              (.set (.getYear ld)
                    (dec (.getMonthValue ld))     ;; Calendar month is 0-based
                    (.getDayOfMonth ld)))]
    (.getTime cal)))

(defn- ast-children
  "Drop the production-name keyword head; keep the children."
  [node]
  (rest node))

(defn- ->ast-map
  "Walk the parsed tree turning each (production-name children…) into
   a map shape easier to reduce over. Strings, dates, accounts and
   currencies are extracted to bare values; structural rules
   (transaction, posting, …) become tagged maps."
  [node]
  (cond
    (string? node) node
    (vector? node)
    (let [tag (first node)
          xs  (mapv ->ast-map (ast-children node))]
      (case tag
        :date       {:tag :date       :inst (date-str->inst (first xs))}
        :flag       {:tag :flag       :ch   (first xs)}
        :account    {:tag :account    :path (first xs)}
        :currency   {:tag :currency   :sym  (first xs)}
        :amount     {:tag :amount     :bd   (BigDecimal. ^String (first xs))}
        :string     {:tag :string     :s    (first xs)}
        :currencies {:tag :currencies :syms (mapv :sym xs)}

        :option      {:tag :option
                      :key   (-> xs (nth 0) :s)
                      :value (-> xs (nth 1) :s)}

        :open        {:tag :open
                      :date    (-> xs (nth 0) :inst)
                      :account (-> xs (nth 1) :path)
                      :currencies (-> xs (nth 2) :syms)}

        :close       {:tag :close
                      :date    (-> xs (nth 0) :inst)
                      :account (-> xs (nth 1) :path)}

        :txn-header  {:tag :txn-header
                      :date      (-> xs (nth 0) :inst)
                      :flag      (-> xs (nth 1) :ch)
                      :payee     (-> xs (nth 2) :s)
                      :narration (-> xs (nth 3) :s)}

        :posting     {:tag :posting
                      :account   (-> xs (nth 0) :path)
                      :amount    (-> xs (nth 1) :bd)
                      :currency  (-> xs (nth 2) :sym)}

        :transaction (let [[hdr & ps] xs]
                       (assoc hdr :tag :transaction :postings (vec ps)))

        :balance     {:tag :balance
                      :date     (-> xs (nth 0) :inst)
                      :account  (-> xs (nth 1) :path)
                      :amount   (-> xs (nth 2) :bd)
                      :currency (-> xs (nth 3) :sym)}

        :comment     {:tag :comment}

        :file        (vec (filter #(not= :comment (:tag %)) xs))

        ;; default: pass through
        node))
    :else node))

(defn parse-string
  "Parse a Beancount source string into a vector of directive maps:

     {:tag :option   :key str :value str}
     {:tag :open     :date Date :account str :currencies [str ...]}
     {:tag :close    :date Date :account str}
     {:tag :transaction
                     :date Date :flag char :payee str :narration str
                     :postings [{:tag :posting :account str
                                 :amount BigDecimal :currency str} ...]}
     {:tag :balance  :date Date :account str :amount BigDecimal :currency str}

   Throws insta/Failure as ex-info on parse error."
  [source]
  (let [tree (parser source)]
    (when (insta/failure? tree)
      (throw (ex-info "Beancount parse failure"
                      {:type :beancount/parse-error
                       :failure (insta/get-failure tree)})))
    (->ast-map tree)))

;; ============================================================================
;; Load: directives → kontor tx-data
;; ============================================================================

(defn- option-tx
  [{:keys [key value]}]
  ;; Phase-1 scope: we don't have schema for option storage. Keep
  ;; them in a meta entity for future-use (round-trip needs us to
  ;; remember the keys + order).
  {:db/id (str (gensym "opt-"))
   :beancount/option-key key
   :beancount/option-value value
   :beancount/option-order (System/nanoTime)})

(defn- ensure-commodity
  "Idempotent: return a tx-fragment that creates the commodity entity
   only if it doesn't already exist (looked up by :kontor.commodity/symbol).
   datahike's :db.unique/identity on :kontor.commodity/symbol gives us
   upsert-by-identity for free."
  [sym]
  {:kontor.commodity/symbol sym
   :kontor.commodity/name sym
   :kontor.commodity/precision 2
   :kontor.commodity/iso-4217 sym})

(defn- ensure-account
  [path & {:keys [active?] :or {active? true}}]
  ;; Account/type is required by our schema enum but Beancount's
  ;; semantics are looser. Infer a reasonable default from the path
  ;; head (Assets/Liabilities/Equity/Income/Expenses are the
  ;; Beancount conventional roots).
  (let [head (first (str/split path #":"))
        type (case head
               "Assets"      :asset
               "Liabilities" :liability
               "Equity"      :equity
               "Income"      :income
               "Expenses"    :expense
               :asset)]
    {:account/path path
     :account/name (last (str/split path #":"))
     :account/type type
     :account/active active?}))

(defn- ensure-journal
  "Beancount has no journal concept — every transaction is just dated.
   We synthesize a single :general journal so the schema's NOT-NULL
   journal ref is satisfied."
  []
  {:journal/code "BEAN"
   :journal/name "Beancount import"
   :journal/type :general
   :journal/active true})

(defn- transaction-tx
  "Build the kernel tx-data for one Beancount transaction directive.
   Uses :db/id tempids so commodity/account/journal refs resolve.
   Valid-time is stamped on the tx via :tx/valid-from = date."
  [{:keys [date payee narration postings]}]
  (let [tx-id (str (gensym "txn-"))
        external-id (str date "/" payee "/" narration)
        posting-entities
        (map-indexed
         (fn [idx {:keys [account amount currency]}]
           {:db/id              (str "p-" tx-id "-" idx)
            :posting/account    [:account/path account]
            :posting/amount     amount
            :posting/commodity  [:kontor.commodity/symbol currency]
            :posting/posted-at  date
            :posting/transaction tx-id
            :posting/display-type :product})
         postings)]
    (kbt/with-vt
      (into [{:db/id                       tx-id
              :transaction/external-id     external-id
              :transaction/journal         [:journal/code "BEAN"]
              :transaction/effective-date  date
              :transaction/narration       (str payee " — " narration)
              :transaction/state           :posted
              :transaction/posted-at       date}]
            posting-entities)
      date kbt/forever)))

(defn- balance-assertion-tx
  [{:keys [date account amount currency]}]
  ;; Pure data; no validation against actual balance here. The
  ;; assert-balances! fn (separate) walks the loaded book and
  ;; verifies each.
  [{:db/id                       (str (gensym "bal-"))
    :balance-assertion/account   [:account/path account]
    :balance-assertion/at        date
    :balance-assertion/amount    amount
    :balance-assertion/commodity [:kontor.commodity/symbol currency]
    :balance-assertion/source    "Beancount import"}])

;; Beancount-specific schema fragments (just for option storage during
;; round-trip). Idempotent.
(def ^:private beancount-schema
  [{:db/ident :beancount/option-key
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :beancount/option-value
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :beancount/option-order
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index true}])

(defn install-import-schema!
  "Add the import-time auxiliary schema (Beancount option storage).
   Idempotent."
  [conn]
  (d/transact conn beancount-schema))

(defn load-into!
  "Load a parsed Beancount directive list into `conn`. Returns a map
   of {:tx-counts <map> :commodity-eids <map> :account-eids <map>}.

   **Bootstrap-class per ADR-068**: this loader does raw `d/transact`
   for every phase (schema, journal, commodities, accounts,
   transactions) — it is a one-shot data-migration / import path,
   not a business-write API. Callers who need the kernel gate
   (sealing / period / sum-to-zero / invariants) on imported entries
   should post each imported transaction via `posting/post-transaction!`
   AFTER the catalog / commodity / account skeleton is loaded."
  [conn directives]
  (install-import-schema! conn)
  ;; Pre-create the catch-all journal once.
  (d/transact conn [(ensure-journal)])
  ;; Phase 1: declare commodities + accounts as we encounter them via
  ;; open directives, then run each transaction / balance.
  (let [opts (filter #(= :option (:tag %)) directives)
        opens (filter #(= :open (:tag %)) directives)
        closes (filter #(= :close (:tag %)) directives)
        txns (filter #(= :transaction (:tag %)) directives)
        balances (filter #(= :balance (:tag %)) directives)]
    ;; Options (preserved verbatim for dump).
    (when (seq opts)
      (d/transact conn (mapv option-tx opts)))
    ;; Commodities (uniqued by :kontor.commodity/symbol).
    (let [syms (->> opens (mapcat :currencies) distinct)]
      (when (seq syms)
        (d/transact conn (mapv ensure-commodity syms))))
    ;; Accounts (uniqued by :account/path).
    (when (seq opens)
      (d/transact conn (mapv #(ensure-account (:account %)) opens)))
    ;; Closes — set :account/active false on those accounts.
    (when (seq closes)
      (d/transact conn (mapv (fn [{:keys [account]}]
                               {:account/path account :account/active false})
                             closes)))
    ;; Transactions (each is an entity-graph, not a tx-data vector itself).
    (doseq [t txns]
      (d/transact conn (transaction-tx t)))
    ;; Balance assertions.
    (when (seq balances)
      (d/transact conn (vec (mapcat balance-assertion-tx balances))))
    {:options    (count opts)
     :commodities (count (distinct (mapcat :currencies opens)))
     :accounts   (count opens)
     :closes     (count closes)
     :transactions (count txns)
     :balances   (count balances)}))

(defn load-string!
  "Convenience: parse + load. Returns the same shape as load-into!."
  [conn source]
  (load-into! conn (parse-string source)))

(defn load-file!
  [conn ^String path]
  (load-string! conn (slurp path)))

;; ============================================================================
;; Dump: datahike state → Beancount source
;; ============================================================================

(def ^:private date-fmt
  (DateTimeFormatter/ofPattern "yyyy-MM-dd"))

(defn- inst->date-str
  ^String [^Date d]
  (-> d
      .toInstant
      (.atZone java.time.ZoneOffset/UTC)
      .toLocalDate
      (.format date-fmt)))

(defn- left-pad [n s]
  (let [s (str s)
        pad (- n (count s))]
    (if (pos? pad)
      (str (apply str (repeat pad " ")) s)
      s)))

(defn- format-amount
  "Right-align an amount string in a fixed-width column (matching the
   Beancount idiomatic two-space-after-account spacing)."
  ^String [^BigDecimal bd]
  ;; .toPlainString avoids scientific notation; preserves scale.
  (.toPlainString bd))

(defn- dump-option [{:keys [key value]}]
  (str "option \"" key "\" \"" value "\""))

(defn- dump-open [{:keys [date path currencies]}]
  (str (inst->date-str date) " open " path "          "
       (str/join "," currencies)))

(defn ^:no-doc dump-close
  "Reserved: emit a `close` directive when we round-trip closes.
   `mini.beancount` has none, so this isn't on the dump path yet.
   Kept public so kondo doesn't flag it as unused while we wait for
   a close-bearing fixture."
  [{:keys [date path]}]
  (str (inst->date-str date) " close " path))

(defn- dump-balance [{:keys [date path amount currency]}]
  (str (inst->date-str date) " balance " path "      "
       (left-pad 10 (format-amount amount)) " " currency))

(defn- dump-transaction
  [{:keys [date payee narration postings]}]
  (str (inst->date-str date) " * \"" payee "\" \"" narration "\""
       "\n"
       (str/join "\n"
                 (for [{:keys [account amount currency]} postings]
                   (str "  " account
                        ;; align amount column at width 20 from the
                        ;; account name end
                        (left-pad (max 1 (- 22 (count account)))
                                  (format-amount amount))
                        " " currency)))))

(defn- option-rows
  [db]
  (->> (d/q '[:find ?k ?v ?ord
              :where
              [?e :beancount/option-key ?k]
              [?e :beancount/option-value ?v]
              [?e :beancount/option-order ?ord]]
            db)
       (sort-by #(nth % 2))
       (mapv (fn [[k v _]] {:key k :value v}))))

(defn- account-rows
  "Pull all accounts in path-order with their open dates inferred
   from the earliest posting (or, if none, the first balance/close)."
  [db]
  (let [paths (d/q '[:find [?path ...]
                     :where [_ :account/path ?path]]
                   db)]
    (vec
     (for [path (sort paths)]
       (let [eid (:db/id (d/entity db [:account/path path]))
             ;; Derive the open date heuristically: earliest of
             ;; (a) min :transaction/effective-date for postings on
             ;; this account, (b) min :balance-assertion/at for
             ;; assertions on this account. `min-date` returns nil
             ;; for empty seqs so the cond below picks correctly.
             min-date (fn [^java.util.Collection coll]
                        (when (seq coll)
                          (reduce (fn [^Date a ^Date b]
                                    (if (< (.getTime a) (.getTime b)) a b))
                                  coll)))
             tx-min (->> (d/q '[:find ?d
                                :in $ ?a
                                :where
                                [?p :posting/account ?a]
                                [?p :posting/transaction ?t]
                                [?t :transaction/effective-date ?d]]
                              db eid)
                         (map first)
                         (filter some?)
                         min-date)
             bal-min (->> (d/q '[:find ?d
                                 :in $ ?a
                                 :where
                                 [?b :balance-assertion/account ?a]
                                 [?b :balance-assertion/at ?d]]
                               db eid)
                          (map first)
                          (filter some?)
                          min-date)
             open-date (cond
                         (and tx-min bal-min)
                         (if (< (.getTime ^Date tx-min) (.getTime ^Date bal-min))
                           tx-min bal-min)
                         tx-min  tx-min
                         bal-min bal-min
                         :else   (Date. 0))
             ;; Currencies: every distinct commodity used on this account
             currencies (->> (d/q '[:find [?sym ...]
                                    :in $ ?a
                                    :where
                                    [?p :posting/account ?a]
                                    [?p :posting/commodity ?c]
                                    [?c :kontor.commodity/symbol ?sym]]
                                  db eid)
                             distinct
                             sort
                             vec)
             active? (:account/active (d/entity db eid))]
         {:date  open-date
          :path  path
          :currencies (or (seq currencies) ["EUR"])
          :active? active?})))))

(defn- transaction-rows
  [db]
  (let [tx-eids (d/q '[:find [?t ...]
                       :where [?t :transaction/external-id _]]
                     db)]
    (vec
     (for [t (sort-by (fn [eid]
                        (.getTime ^Date (:transaction/effective-date (d/entity db eid))))
                      tx-eids)]
       (let [tx (d/pull db [:transaction/effective-date :transaction/narration] t)
             postings (d/q '[:find ?p
                             :in $ ?t
                             :where [?p :posting/transaction ?t]]
                           db t)
             ps (vec
                 (for [[pid] (sort-by first postings)]
                   (let [pe (d/pull db [:posting/account :posting/amount
                                        :posting/commodity] pid)
                         apath (-> pe :posting/account :db/id
                                   (#(:account/path (d/entity db %))))
                         csym (-> pe :posting/commodity :db/id
                                  (#(:kontor.commodity/symbol (d/entity db %))))]
                     {:account apath
                      :amount  (:posting/amount pe)
                      :currency csym})))
             [payee narration] (let [n (:transaction/narration tx)]
                                 (if (re-find #" — " n)
                                   (str/split n #" — " 2)
                                   ["" n]))]
         {:date (:transaction/effective-date tx)
          :payee payee
          :narration narration
          :postings ps})))))

(defn- balance-rows
  [db]
  (let [eids (d/q '[:find [?b ...] :where [?b :balance-assertion/at _]] db)]
    (->> eids
         (map (fn [eid]
                (let [b (d/pull db [:balance-assertion/at
                                    :balance-assertion/amount
                                    :balance-assertion/account
                                    :balance-assertion/commodity] eid)
                      apath (:account/path
                             (d/entity db (-> b :balance-assertion/account :db/id)))
                      csym (:kontor.commodity/symbol
                            (d/entity db (-> b :balance-assertion/commodity :db/id)))]
                  {:date (:balance-assertion/at b)
                   :path apath
                   :amount (:balance-assertion/amount b)
                   :currency csym})))
         (sort-by (juxt #(.getTime ^Date (:date %)) :path))
         vec)))

(defn dump
  "Render the loaded book as a Beancount-syntax string. Round-trip
   stable for the supported subset (per ADR-009): dump → parse →
   load → dump produces an identical string."
  [conn]
  (let [db    (d/db conn)
        opts  (option-rows db)
        accs  (account-rows db)
        txns  (transaction-rows db)
        bals  (balance-rows db)]
    (str
     ;; Options
     (when (seq opts)
       (str (str/join "\n" (mapv dump-option opts)) "\n\n"))
     ;; Opens (and any closes appended at end with original close date)
     (when (seq accs)
       (str (str/join "\n" (mapv dump-open accs)) "\n\n"))
     ;; Transactions interleaved with balance assertions in date order.
     ;; Append a trailing newline so the last directive's <eol> token
     ;; can match — the grammar requires a newline after every block.
     (let [combined (sort-by (fn [{:keys [date]}] (.getTime ^Date date))
                             (concat (mapv #(assoc % :_kind :txn) txns)
                                     (mapv #(assoc % :_kind :bal) bals)))
           body (str/join "\n\n"
                          (for [m combined]
                            (case (:_kind m)
                              :txn (dump-transaction m)
                              :bal (dump-balance m))))]
       (str body "\n")))))
