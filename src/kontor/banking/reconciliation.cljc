(ns kontor.banking.reconciliation
  "Bank-statement reconciliation: ingest bank-csv parser output as
   `:bank-line` entities, match them against open AR/AP, and (on
   confirmation) post the bank-side transaction.

   Workflow:

     1. `ingest-statement!` — bulk-import a parsed bank statement
        (the vec-of-maps shape produced by `bank-de.parser/parse-
        statement` and friends). Each candidate becomes a `:bank-
        line` entity with status `:unmatched`. Idempotent: re-
        importing the same statement is a no-op (each line is
        keyed by a hash of its raw row).

     2. `suggest-match` — given a `:bank-line` and a db, return a
        seq of match candidates ranked by confidence. Strategies:
          a. reference-id: bank description contains a known
             transaction's `:kontor.transaction/external-id`
          b. exact-amount + partner-name match against open AR/AP
          c. multi-line: subset-sum search for combinations of
             same-counterparty open invoices summing to the bank
             amount (Sammelüberweisung pattern)
          d. categorizer fallback (the importer's auto-category)
        Each candidate is `{:strategy …, :confidence …, :match …}`
        where `:match` is one of:
          {:kind :settle, :transactions [eid …]}   ; pay invoices
          {:kind :categorize, :contra-account eid} ; expense / income

     3. `commit-match!` — apply a match decision atomically:
          - construct the bank-side transaction (bank ↔ AR/AP/contra)
          - link `:kontor.transaction/settles` to the settled invoices
          - update `:kontor.bank-line/status` to `:reconciled`
          - set `:kontor.bank-line/posting`

     4. `unmatched-queue` — return all `:bank-line` entities still
        in `:unmatched` status, for a UI / batch tool to walk.

   Scope cuts (deferred):
   - Partial payments (one bank line settles part of an invoice;
     remainder stays open).
   - Installments (multiple bank lines progressively settle one
     invoice — needs partial-payment support first).
   - FX revaluation when bank commodity ≠ invoice commodity.
   - Date-tolerance matching (bank lines often hit a few days after
     invoice date; v1 doesn't use date as a match heuristic).
   These are real but rare for the SMB workflows the kernel targets;
   v2 layer can address them on top of the v1 schema."
  (:require [clojure.string :as str]
            [kontor.money :as money]
            [datahike.api :as d]
            [kontor.posting.build :as posting]
            [kontor.reporting.balance :as balance]
            [kontor.validation :as validation]))

(defn- ->ms [x] #?(:clj (.getTime ^java.util.Date x) :cljs (if (number? x) x (.getTime x))))
(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

;; ============================================================================
;; Ingestion
;; ============================================================================

(defn- raw-row-text
  "Concatenate a candidate's :raw-row vec into a single string for
   hashing + storage."
  ^String [candidate]
  (str/join "" (or (:raw-row candidate) [])))

(defn- bank-line-external-id
  "Stable id for re-import idempotence. Combines bank, date, signed
   amount, and a digest of the raw row so two distinct lines on the
   same date with the same amount don't collide."
  [{:keys [bank date amount] :as candidate}]
  (let [raw (raw-row-text candidate)
        digest #?(:clj (-> raw .hashCode Integer/toString) :cljs (str (hash raw)))
        date-ms (when date (->ms date))]
    (str (name (or bank :unknown))
         "/" (or date-ms "0")
         "/" amount
         "/" digest)))

(defn- candidate->bank-line-tx
  "Translate one bank-csv candidate map into a :bank-line entity
   tx-data fragment. `source-account-eid` and `commodity-eid` are
   provided by the caller (typically the same per statement-batch).
   `category` defaults to nil."
  [candidate {:keys [source-account-eid commodity-eid]}]
  (let [{:keys [bank date value-date amount counterparty
                counterparty-iban description transaction-type
                category]} candidate]
    (cond-> {:kontor.bank-line/external-id    (bank-line-external-id candidate)
             :kontor.bank-line/source-account source-account-eid
             :kontor.bank-line/commodity      commodity-eid
             :kontor.bank-line/amount         #?(:clj (bigdec amount) :cljs (money/->amount amount))
             :kontor.bank-line/status         :unmatched
             :kontor.bank-line/raw-row        (raw-row-text candidate)}
      bank             (assoc :kontor.bank-line/bank bank)
      date             (assoc :kontor.bank-line/date date)
      value-date       (assoc :kontor.bank-line/value-date value-date)
      counterparty     (assoc :kontor.bank-line/counterparty counterparty)
      counterparty-iban (assoc :kontor.bank-line/counterparty-iban counterparty-iban)
      description      (assoc :kontor.bank-line/description description)
      transaction-type (assoc :kontor.bank-line/transaction-type transaction-type)
      category         (assoc :kontor.bank-line/category category))))

(defn ingest-statement-tx-data
  "Pure tx-data builder for `ingest-statement!` (ADR-068)."
  [_db candidates {:keys [source-account-eid commodity-eid] :as opts}]
  (when-not source-account-eid
    (throw (ex-info "ingest-statement! requires :source-account-eid"
                    {:opts opts})))
  (when-not commodity-eid
    (throw (ex-info "ingest-statement! requires :commodity-eid"
                    {:opts opts})))
  (mapv #(candidate->bank-line-tx % opts) candidates))

(defn ingest-statement!
  "Bulk-import a parsed bank statement (vec of candidate maps from
   `bank-{cc}.parser/parse-statement`). Idempotent. Routes through
   the gate (ADR-068).

   `opts`:
     :source-account-eid — the chart account (e.g. SKR04 1200) that
                           this statement's lines land on. Required.
     :commodity-eid      — the commodity (e.g. EUR) the bank account
                           is denominated in. Required.

   Returns the tx-report. The pure tx-data builder is
   `ingest-statement-tx-data`."
  [conn candidates opts]
  (validation/transact-with-validation
   conn (ingest-statement-tx-data (d/db conn) candidates opts)))

;; ============================================================================
;; Open AR / AP discovery
;; ============================================================================

(defn- single-commodity
  "The one commodity a transaction's open-item postings are denominated
   in. Throws when they span several.

   The open-item queries sum a transaction's receivable (or payable)
   legs into one number; that number only means something if the legs
   share a commodity. Rather than return a blended figure the callers
   would then wrap in an arbitrary currency tag, refuse — the same
   stance `report/sum-postings` takes under `:strict-commodity?`.
   Genuine multi-currency AR needs per-commodity open items, which is
   the deferred FX work noted at the top of this namespace."
  [tx fields]
  (let [cs (:commodities fields)]
    (when (> (count cs) 1)
      (throw (ex-info "open items: transaction spans multiple commodities"
                      {:type :reconciliation/mixed-commodity
                       :transaction-eid tx
                       :commodities cs})))
    (first cs)))

(defn- open-item-sort-key
  "Total order over open items: oldest effective-date first, ties broken by
   the transaction eid.

   `:date` ALONE is not a total order, and the result it orders comes out of
   `d/q` — a SET, which has no order of its own. Every downstream consumer of
   these lists is order-sensitive: `subsets-summing-to` TRUNCATES to the
   first `max-subset-search`, and a caller auto-committing
   `(first (suggest-match …))` takes whatever landed on top. Ties are the
   common case, not an edge — a batch import gives every invoice the same
   effective-date. Same bug class as the DATEV contra-account pick and the
   `open-invoices-for-partner` FIFO tie (note 198 audit M8 / H1). Stable
   within a DB; a re-import that renumbers eids may reorder same-date items."
  [{:keys [date transaction-eid]}]
  [(if date (->ms date) 0) (or transaction-eid 0)])

(defn open-receivables-by-tx
  "For each posted transaction whose journal type is :sale, compute
   the AR amount remaining open: gross-AR − sum-of-settling-payments.

   Returns vec of {:transaction-eid :external-id :open-amount
                   :original-amount :partner-eid :date}, in a TOTAL
   order (see [[open-item-sort-key]]). Skips fully-paid transactions
   (open = 0). Filters out :draft and :cancelled transactions
   automatically.

   `ar-account-codes` is the set of chart codes that count as AR for
   the purposes of this query (e.g. #{\"1400\" \"1410\"} on SKR04)."
  [db ar-account-codes]
  (let [;; All postings on AR accounts, with their transaction's
        ;; external-id, partner, date, and state.
        rows (d/q '[:find ?p ?tx ?ext-id ?amount ?commodity ?partner ?date ?journal-type ?state
                    :in $ [?ar-code ...]
                    :where
                    [?a :kontor.account/code ?ar-code]
                    [?p :kontor.posting/account ?a]
                    [?p :kontor.posting/amount ?amount]
                    [?p :kontor.posting/commodity ?commodity]
                    [?p :kontor.posting/transaction ?tx]
                    [?tx :kontor.transaction/external-id ?ext-id]
                    [?tx :kontor.transaction/state ?state]
                    [(get-else $ ?tx :kontor.transaction/effective-date :__null__) ?date]
                    [(get-else $ ?tx :kontor.transaction/partner :__null__) ?partner]
                    [?tx :kontor.transaction/journal ?j]
                    [(get-else $ ?j :kontor.journal/type :__null__) ?journal-type]]
                  db ar-account-codes)
        ;; Group postings by transaction and sum.
        by-tx (reduce
               ;; `?p` is bound and returned so two legs of the SAME amount on
               ;; one transaction are distinct tuples. :find has SET semantics,
               ;; so without it an invoice with 100 + 100 on the receivable
               ;; collapsed to a single 100 and half the balance vanished from
               ;; every open-item and aging figure downstream.
               (fn [acc [_p tx ext amt commodity partner date jtype state]]
                 (-> acc
                     (assoc-in [tx :external-id] ext)
                     (assoc-in [tx :partner-eid] (when (not= partner :__null__) partner))
                     (assoc-in [tx :date] (when (not= date :__null__) date))
                     (assoc-in [tx :journal-type] jtype)
                     (assoc-in [tx :state] state)
                     (update-in [tx :commodities] (fnil conj #{}) commodity)
                     (update-in [tx :ar-amount] (fnil #(money/add-amount % amt) (money/zero-amount)))))
               {}
               rows)
        ;; For each candidate sales tx, find any other transactions
        ;; that :kontor.transaction/settles → it; subtract their AR-side
        ;; offsets to get the open amount.
        settled (d/q '[:find ?p ?settled ?amount
                       :in $ [?ar-code ...]
                       :where
                       [?settler :kontor.transaction/settles ?settled]
                       [?p :kontor.posting/transaction ?settler]
                       [?p :kontor.posting/account ?a]
                       [?a :kontor.account/code ?ar-code]
                       [?p :kontor.posting/amount ?amount]]
                     db ar-account-codes)
        settled-by (reduce (fn [acc [_p tx amt]]
                             (update acc tx (fnil #(money/add-amount % amt) (money/zero-amount))))
                           {} settled)]
    (->> by-tx
         (keep (fn [[tx fields]]
                 (when (and (= :posted (:state fields))
                            (= :sale (:journal-type fields)))
                   (let [original (:ar-amount fields)
                         offset (or (settled-by tx) (money/zero-amount))
                         open (money/add-amount original offset)]
                     (when (money/amount-positive? open)
                       {:transaction-eid tx
                        :external-id (:external-id fields)
                        :original-amount original
                        :open-amount open
                        :commodity (single-commodity tx fields)
                        :partner-eid (:partner-eid fields)
                        :date (:date fields)})))))
         (sort-by open-item-sort-key)
         vec)))

(defn open-payables-by-tx
  "Mirror of `open-receivables-by-tx` for AP. AP postings are credits
   on the payable account, so the original amount is negative; the
   open amount stays negative (debt) until settled. Same total order
   (see [[open-item-sort-key]])."
  [db ap-account-codes]
  (let [rows (d/q '[:find ?p ?tx ?ext-id ?amount ?commodity ?partner ?date ?journal-type ?state
                    :in $ [?ap-code ...]
                    :where
                    [?a :kontor.account/code ?ap-code]
                    [?p :kontor.posting/account ?a]
                    [?p :kontor.posting/amount ?amount]
                    [?p :kontor.posting/commodity ?commodity]
                    [?p :kontor.posting/transaction ?tx]
                    [?tx :kontor.transaction/external-id ?ext-id]
                    [?tx :kontor.transaction/state ?state]
                    [(get-else $ ?tx :kontor.transaction/effective-date :__null__) ?date]
                    [(get-else $ ?tx :kontor.transaction/partner :__null__) ?partner]
                    [?tx :kontor.transaction/journal ?j]
                    [(get-else $ ?j :kontor.journal/type :__null__) ?journal-type]]
                  db ap-account-codes)
        by-tx (reduce
               ;; `?p` is bound and returned so two legs of the SAME amount on
               ;; one transaction are distinct tuples. :find has SET semantics,
               ;; so without it an invoice with 100 + 100 on the receivable
               ;; collapsed to a single 100 and half the balance vanished from
               ;; every open-item and aging figure downstream.
               (fn [acc [_p tx ext amt commodity partner date jtype state]]
                 (-> acc
                     (assoc-in [tx :external-id] ext)
                     (assoc-in [tx :partner-eid] (when (not= partner :__null__) partner))
                     (assoc-in [tx :date] (when (not= date :__null__) date))
                     (assoc-in [tx :journal-type] jtype)
                     (assoc-in [tx :state] state)
                     (update-in [tx :commodities] (fnil conj #{}) commodity)
                     (update-in [tx :ap-amount] (fnil #(money/add-amount % amt) (money/zero-amount)))))
               {}
               rows)
        settled (d/q '[:find ?p ?settled ?amount
                       :in $ [?ap-code ...]
                       :where
                       [?settler :kontor.transaction/settles ?settled]
                       [?p :kontor.posting/transaction ?settler]
                       [?p :kontor.posting/account ?a]
                       [?a :kontor.account/code ?ap-code]
                       [?p :kontor.posting/amount ?amount]]
                     db ap-account-codes)
        settled-by (reduce (fn [acc [_p tx amt]]
                             (update acc tx (fnil #(money/add-amount % amt) (money/zero-amount))))
                           {} settled)]
    (->> by-tx
         (keep (fn [[tx fields]]
                 (when (and (= :posted (:state fields))
                            (= :purchase (:journal-type fields)))
                   (let [original (:ap-amount fields)
                         offset (or (settled-by tx) (money/zero-amount))
                         open (money/add-amount original offset)]
                     (when (money/amount-negative? open)
                       {:transaction-eid tx
                        :external-id (:external-id fields)
                        :original-amount original
                        :open-amount open
                        :commodity (single-commodity tx fields)
                        :partner-eid (:partner-eid fields)
                        :date (:date fields)})))))
         (sort-by open-item-sort-key)
         vec)))

;; ============================================================================
;; Tie-out — the AR subledger against the GL control account
;; ============================================================================

(defn ar-tie-out
  "Reconcile the AR open-item subledger to the GL receivable control
   account(s) — the detective control for \"my balance-sheet receivable
   number is wrong\". Sibling of `kontor.inventory.report/valuation-tie-out`.

   `subledger` = Σ `:open-amount` over [[open-receivables-by-tx]], i.e. what
   the open-item list says customers still owe.
   `gl`        = Σ `account-balance` over EVERY account carrying one of
   `:ar-codes` — the same account set the subledger query reads, so the two
   sides cannot drift by looking at different accounts.

   The two tie iff every posting that relieves a receivable is LINKED to the
   invoice it relieves via `:kontor.transaction/settles`. A relief that posts
   to the GL without the link (a settlement, a bad-debt write-off, a credit
   note) drives the GL down while the subledger keeps reporting the invoice
   fully open — the exact shape of note 198 audit HIGH-3 and HIGH-4, both of
   which this reports as a non-zero `:difference`. A `:sale`-journal invoice
   posted to a NON-AR account shows up as the opposite-signed drift.

   Required: `:commodity` (eid — the tie-out is per-commodity, since a
   blended figure across currencies means nothing).
   Optional: `:ar-codes` (default `#{\"1400\"}`), `:as-of-valid`,
   `:as-of-tx` (applied to BOTH sides — `:as-of-tx` snapshots the db the
   subledger query runs against, so a current subledger is never compared
   to a historical GL), `:entity`, `:ledger` (passed to `account-balance`).

   Returns `{:ar-codes :accounts :subledger :gl :difference :ok?}`, all
   amounts BigDecimal."
  [conn {:keys [ar-codes commodity as-of-valid as-of-tx entity ledger]
         :or   {ar-codes #{"1400"}}}]
  (when-not commodity (throw (ex-info "ar-tie-out: :commodity required" {})))
  (let [db        (cond-> (d/db conn) as-of-tx (d/as-of as-of-tx))
        opts      (cond-> {}
                    as-of-valid (assoc :as-of-valid as-of-valid)
                    as-of-tx    (assoc :as-of-tx as-of-tx)
                    entity      (assoc :entity entity)
                    ledger      (assoc :ledger ledger))
        accounts  (sort (d/q '[:find [?a ...]
                               :in $ [?code ...]
                               :where [?a :kontor.account/code ?code]]
                             db ar-codes))
        subledger (->> (open-receivables-by-tx db ar-codes)
                       (filter #(= commodity (:commodity %)))
                       (reduce (fn [acc row]
                                 (money/add-amount acc (:open-amount row)))
                               (money/zero-amount)))
        gl        (reduce (fn [acc a]
                            (money/add-amount
                             acc
                             (or (:amount (get (balance/account-balance conn a opts)
                                               commodity))
                                 (money/zero-amount))))
                          (money/zero-amount)
                          accounts)
        diff      (money/add-amount subledger (money/negate-amount gl))]
    {:ar-codes   ar-codes
     :accounts   (vec accounts)
     :subledger  subledger
     :gl         gl
     :difference diff
     :ok?        (money/amount-zero? diff)}))

;; ============================================================================
;; Matchers
;; ============================================================================

(defn- description-references?
  "True when bank-line description plausibly contains a transaction's
   external-id. Lowercases both, strips common separators."
  [bank-line-description tx-external-id]
  (when (and bank-line-description tx-external-id)
    (let [normalize (fn [^String s]
                      (-> s str/lower-case
                          (str/replace #"[\s\-_]+" "")))]
      (str/includes? (normalize bank-line-description)
                     (normalize tx-external-id)))))

(defn- amount-matches?
  "Bank-line amount matches an open AR / AP amount. For AR
   (receivable), invoice open is positive; bank inflow is positive →
   match when bank-amount = open-amount. For AP (payable), invoice
   open is negative; bank outflow is negative → match when
   bank-amount = open-amount."
  [bank-amount open-amount]
  (and bank-amount open-amount
       (zero? (money/compare-amounts bank-amount open-amount))))

(defn- counterparty-matches-partner?
  "True when bank-line counterparty text plausibly refers to the
   given partner (case-insensitive substring). Handles the typical
   bank-statement noise like 'BETA AG SEPA' for partner 'Beta AG'."
  [counterparty partner-name]
  (when (and counterparty partner-name)
    (str/includes? (str/lower-case counterparty)
                   (str/lower-case partner-name))))

(def ^:private ^:const max-subset-search 12)

(defn- subsets-summing-to
  "Return seq of subsets of `opens` (vec of {:open-amount …}) whose
   :open-amount sums to `target`. Bounded by `max-subset-search` to
   avoid combinatorial blowup; if there are more open invoices than
   that, fall back to a partner-only filter via the caller. Limits
   results to the first `max-results` matches.

   Strategy: depth-first search with two pruning rules:
     - skip when the running sum + remaining > target * 2 (no chance
       to reach target without overshooting)
     - prefer smaller subsets (caller sorts results by count)

   `opens` is re-sorted into [[open-item-sort-key]] order BEFORE the
   truncation. Dropping all but 12 candidates is only defensible if the 12
   are a defined 12 — an unordered `d/q` result truncated at 12 makes WHICH
   invoices are even eligible for the match arbitrary, and the caller may
   auto-commit the result (note 198 audit M8)."
  [opens target & {:keys [max-results min-size]
                   :or {max-results 5 min-size 2}}]
  (let [opens (vec (take max-subset-search (sort-by open-item-sort-key opens)))
        n (count opens)
        results (atom [])]
    (letfn [(go [start picked sum]
              (when (< (count @results) max-results)
                (let [cmp (money/compare-amounts sum target)]
                  (cond
                    (zero? cmp)
                    (when (>= (count picked) min-size)
                      (swap! results conj picked))
                    (pos? cmp)
                    nil ; overshot; backtrack
                    :else
                    (doseq [i (range start n)]
                      (let [open (nth opens i)
                            new-sum (money/add-amount sum
                                                      (:open-amount open))]
                        (go (inc i) (conj picked open) new-sum)))))))]
      (go 0 [] 0M)
      @results)))

(defn- suggestion-sort-key
  "Total order over suggestions: confidence descending, then strategy, then
   the settled-transaction eids, then the contra account.

   Confidence alone is NOT an order — it comes from a fixed four-value set
   (0.95 / 0.9 / 0.85 / 0.7 / 0.5), so ties are the rule rather than the
   exception, and the tied entries arrive from an unordered `d/q`. A caller
   that auto-commits `(first (suggest-match …))` was therefore committing an
   ARBITRARY match — against a real, sealed GL entry (note 198 audit M8)."
  [{:keys [confidence strategy match]}]
  [(- (double (or confidence 0)))
   (str strategy)
   (vec (or (:transactions match) []))
   (or (:contra-account match) 0)])

(defn suggest-match
  "Given a `:bank-line` entity and a db, return a seq of match
   candidates in a TOTAL order, best first (see [[suggestion-sort-key]]).
   Each candidate:

     {:strategy   keyword
      :confidence number ∈ [0,1]
      :match      <see below>}

   Match shape:
     {:kind :settle :transactions [eid …]}
       — Pay against the listed invoice transactions. Bank-side leg
         goes against AR (positive amounts) or AP (negative amounts).
     {:kind :categorize :contra-account eid}
       — No invoice match; route to the indicated contra account
         (e.g. 6300 Miete for a rent payment).

   `opts`:
     :ar-codes — set of chart codes treated as AR (default #{\"1400\"})
     :ap-codes — set of chart codes treated as AP (default #{\"3300\"})
     :category-resolver — `(fn [category-keyword] -> account-eid)`
                          maps an importer's :category to a contra
                          account. Optional."
  [db bank-line-eid {:keys [ar-codes ap-codes category-resolver]
                     :or {ar-codes #{"1400"} ap-codes #{"3300"}}}]
  (let [bl (d/pull db [:kontor.bank-line/amount :kontor.bank-line/description
                       :kontor.bank-line/counterparty :kontor.bank-line/category]
                   bank-line-eid)
        amount (:kontor.bank-line/amount bl)
        desc (:kontor.bank-line/description bl)
        cp (:kontor.bank-line/counterparty bl)
        cat (:kontor.bank-line/category bl)
        inflow? (and amount (money/amount-positive? amount))
        opens (if inflow?
                (open-receivables-by-tx db ar-codes)
                (open-payables-by-tx db ap-codes))
        ;; Strategy 1: reference-id in description
        ref-matches (->> opens
                         (filter #(description-references? desc (:external-id %)))
                         (mapv (fn [open]
                                 {:strategy :reference-id
                                  :confidence 0.95
                                  :match {:kind :settle
                                          :transactions [(:transaction-eid open)]}
                                  :open open})))
        ;; Strategy 2: exact amount + partner-name overlap
        amount-matches
        (->> opens
             (filter (fn [o] (amount-matches? amount (:open-amount o))))
             (mapv (fn [open]
                     (let [partner-name
                           (when (:partner-eid open)
                             (:kontor.partner/name (d/pull db [:kontor.partner/name]
                                                           (:partner-eid open))))
                           cp-overlap?
                           (counterparty-matches-partner? cp partner-name)]
                       {:strategy :exact-amount
                        :confidence (if cp-overlap? 0.9 0.7)
                        :match {:kind :settle
                                :transactions [(:transaction-eid open)]}
                        :open open}))))
        ;; Strategy 2b: multi-line (Sammelüberweisung) — combinations
        ;; of open invoices for the SAME counterparty summing to the
        ;; bank-line amount. Only fires when no single-invoice match
        ;; exists (else it'd compete with a stronger 1:1 signal).
        multi-line-matches
        (when (and amount cp (empty? amount-matches))
          (let [partner-opens
                (->> opens
                     (filter (fn [o]
                               (when-let [pid (:partner-eid o)]
                                 (let [pn (:kontor.partner/name (d/pull db [:kontor.partner/name] pid))]
                                   (counterparty-matches-partner? cp pn))))))
                subsets (when (seq partner-opens)
                          (subsets-summing-to partner-opens amount))]
            (->> subsets
                 ;; Prefer fewer invoices first; the eid vector breaks the
                 ;; (very common) count tie into a total order.
                 (sort-by (juxt count #(mapv :transaction-eid %)))
                 (mapv (fn [subset]
                         {:strategy :multi-line
                          ;; 0.85 — strong signal (counterparty + exact
                          ;; sum) but slightly lower than the 0.9
                          ;; partner-overlap-1:1 case because subset
                          ;; coincidence is more plausible than a
                          ;; single-amount coincidence.
                          :confidence 0.85
                          :match {:kind :settle
                                  :transactions (mapv :transaction-eid subset)}
                          :opens subset})))))
        ;; Strategy 3: categorizer fallback
        cat-matches (when (and cat category-resolver)
                      (when-let [contra (category-resolver cat)]
                        [{:strategy :category
                          :confidence 0.5
                          :match {:kind :categorize
                                  :contra-account contra}}]))]
    (->> (concat ref-matches amount-matches multi-line-matches cat-matches)
         (sort-by suggestion-sort-key)
         vec)))

;; ============================================================================
;; Commit
;; ============================================================================

(defn- ar-or-ap-account
  "Resolve the contra account for a bank-side posting in :settle mode.
   For AR settlement (inflow) that is the AR account the settled invoices
   sit on; for AP (outflow), the AP account.

   Returns the account when the settled transactions touch EXACTLY ONE
   reconcilable account, and throws otherwise.

   note 198 audit H2. This used to read `(ffirst (d/q …))` under a docstring
   promising \"the FIRST settled transaction's posting\" — but a `d/q` result
   is a SET and has no first. Two independent multipliers make the set bigger
   than one routinely: `ar-codes`/`ap-codes` are documented as a SET
   (`#{\"1400\" \"1410\"}`), and a `:settle` match may carry SEVERAL
   transactions (the multi-line Sammelüberweisung path passes
   `(mapv :transaction-eid subset)`). The pick lands as
   `:kontor.posting/account` of a POSTED, SEALED payment leg, so an arbitrary
   choice permanently overstates one receivable and understates another with
   no error anywhere. Refusing is the only safe answer; the caller resolves
   the ambiguity by passing `:contra-account` explicitly.

   Same shape as `kontor.book/resolve-journal`: one → use it, none → throw,
   several → throw naming the candidates."
  [db settled-tx-eids ar-codes ap-codes inflow?]
  (let [target-codes (if inflow? ar-codes ap-codes)
        eids (vec settled-tx-eids)
        as (sort (d/q '[:find [?a ...]
                        :in $ [?ar-code ...] [?tx ...]
                        :where
                        [?p :kontor.posting/transaction ?tx]
                        [?p :kontor.posting/account ?a]
                        [?a :kontor.account/code ?ar-code]]
                      db target-codes eids))
        side (if inflow? "AR" "AP")]
    (cond
      (= 1 (count as)) (first as)

      (empty? as)
      (throw (ex-info (str "reconciliation: none of the " (count eids)
                           " settled transaction(s) posts to a " side
                           " account in " (vec (sort target-codes))
                           " — widen :" (if inflow? "ar" "ap")
                           "-codes, or pass :contra-account explicitly")
                      {:type :reconciliation/no-contra-account
                       :side (if inflow? :ar :ap)
                       :codes target-codes
                       :transactions eids}))

      :else
      (throw (ex-info (str "reconciliation: the settled transaction(s) span "
                           (count as) " distinct " side
                           " accounts — the contra account is ambiguous and "
                           "picking one would silently misstate the others. "
                           "Pass :contra-account explicitly.")
                      {:type :reconciliation/ambiguous-contra-account
                       :side (if inflow? :ar :ap)
                       :candidates (vec as)
                       :candidate-codes
                       (mapv #(:kontor.account/code
                               (d/pull db [:kontor.account/code] %))
                             as)
                       :transactions eids})))))

(declare commit-match-tx-data)

(defn commit-match!
  "Apply a match decision:
     - construct a payment-receipt transaction with two postings
       (bank ↔ AR/AP/contra)
     - link via :kontor.transaction/settles when match is :settle
     - update bank-line/status to :reconciled and link
       :kontor.bank-line/posting

   `match` is one entry from `suggest-match`'s result, OR a hand-
   crafted equivalent. `journal-eid` is the journal to file the
   payment under (typically a :bank journal).

   `opts`:
     :ar-codes / :ap-codes  — same as suggest-match
     :contra-account        — account eid, overriding the AR/AP account
                              derived from the settled transactions. The
                              required escape hatch when the settled set
                              spans several receivable/payable accounts,
                              which `ar-or-ap-account` refuses to guess.
     :external-id-prefix    — string used to build the payment tx's
                              external-id; default \"PAY-<bank-line-id>\""
  [conn bank-line-eid match journal-eid opts]
  (let [report (validation/transact-with-validation
                conn (commit-match-tx-data
                      (d/db conn) bank-line-eid match journal-eid opts))
        tempids (:tempids report)]
    {:payment-tx-eid (get tempids "pay-tx")
     :bank-posting-eid (get tempids "pay-tx-p0")}))

(defn commit-match-tx-data
  "Pure tx-data builder for `commit-match!` (ADR-068). Both halves
   — the payment transaction and the bank-line update referencing
   the bank-side posting — compose into one tx-data via tempid
   threading: the payment uses `:tx-tempid \"pay-tx\"`, the bank-
   side posting is `\"pay-tx-p0\"` (first posting in the input
   vec), and the bank-line's `:kontor.bank-line/posting` ref carries the
   string `\"pay-tx-p0\"` so datahike resolves it consistently in
   the one commit."
  [db bank-line-eid match journal-eid
   {:keys [ar-codes ap-codes external-id-prefix contra-account]
    :or {ar-codes #{"1400"} ap-codes #{"3300"}
         external-id-prefix "PAY-"}}]
  (let [bl (d/pull db [:kontor.bank-line/external-id :kontor.bank-line/amount
                       :kontor.bank-line/source-account :kontor.bank-line/commodity
                       :kontor.bank-line/date :kontor.bank-line/counterparty]
                   bank-line-eid)
        amount (:kontor.bank-line/amount bl)
        bank-acct (:db/id (:kontor.bank-line/source-account bl))
        commodity (:db/id (:kontor.bank-line/commodity bl))
        date (:kontor.bank-line/date bl)
        inflow? (money/amount-positive? amount)
        contra (or contra-account
                   (case (:kind match)
                     :settle     (ar-or-ap-account db (:transactions match)
                                                   ar-codes ap-codes inflow?)
                     :categorize (:contra-account match)))
        _ (when-not contra
            (throw (ex-info "Cannot resolve contra account for match"
                            {:match match :inflow? inflow?})))
        pay-ext-id (str external-id-prefix (:kontor.bank-line/external-id bl))
        payment-tx
        (posting/build-transaction
         {:tx-tempid "pay-tx"
          :transaction
          (cond-> {:kontor.transaction/external-id    pay-ext-id
                   :kontor.transaction/journal        journal-eid
                   :kontor.transaction/effective-date date
                   :kontor.transaction/narration      (str "Payment via bank: "
                                                           (:kontor.bank-line/counterparty bl))
                   :kontor.transaction/state          :posted
                   :kontor.transaction/posted-at      date}
            (and (= :settle (:kind match))
                 (seq (:transactions match)))
            (assoc :kontor.transaction/settles (vec (:transactions match))))
          ;; Bank-side leg FIRST → gets tempid "pay-tx-p0".
          :postings
          [{:kontor.posting/account bank-acct
            :kontor.posting/amount amount
            :kontor.posting/commodity commodity
            :kontor.posting/posted-at date}
           {:kontor.posting/account contra
            :kontor.posting/amount (money/negate-amount amount)
            :kontor.posting/commodity commodity
            :kontor.posting/posted-at date}]})]
    (conj (vec payment-tx)
          {:db/id bank-line-eid
           :kontor.bank-line/status :reconciled
           :kontor.bank-line/reconciled-at (now)
           :kontor.bank-line/posting "pay-tx-p0"})))

(defn unmatched-queue
  "Return all `:bank-line` entities still in `:unmatched` status,
   most recent first. Light shape: `[:db/id :external-id :date
   :amount :counterparty :description]`."
  [db]
  (->> (d/q '[:find [?bl ...]
              :where [?bl :kontor.bank-line/status :unmatched]]
            db)
       (mapv (fn [eid]
               (let [bl (d/pull db
                                [:db/id :kontor.bank-line/external-id
                                 :kontor.bank-line/date :kontor.bank-line/amount
                                 :kontor.bank-line/counterparty :kontor.bank-line/description]
                                eid)]
                 bl)))
       (sort-by :kontor.bank-line/date #(compare %2 %1))))
