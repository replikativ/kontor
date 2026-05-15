(ns kontor.reconciliation
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
             transaction's `:transaction/external-id`
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
          - link `:transaction/settles` to the settled invoices
          - update `:bank-line/status` to `:reconciled`
          - set `:bank-line/posting`

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
            [datahike.api :as d]
            [kontor.posting :as posting]
            [kontor.validation :as validation])
  (:import [java.util Date]))

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
        digest (-> raw .hashCode Integer/toString)
        date-ms (when date (.getTime ^Date date))]
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
    (cond-> {:bank-line/external-id    (bank-line-external-id candidate)
             :bank-line/source-account source-account-eid
             :bank-line/commodity      commodity-eid
             :bank-line/amount         (bigdec amount)
             :bank-line/status         :unmatched
             :bank-line/raw-row        (raw-row-text candidate)}
      bank             (assoc :bank-line/bank bank)
      date             (assoc :bank-line/date date)
      value-date       (assoc :bank-line/value-date value-date)
      counterparty     (assoc :bank-line/counterparty counterparty)
      counterparty-iban(assoc :bank-line/counterparty-iban counterparty-iban)
      description      (assoc :bank-line/description description)
      transaction-type (assoc :bank-line/transaction-type transaction-type)
      category         (assoc :bank-line/category category))))

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

(defn open-receivables-by-tx
  "For each posted transaction whose journal type is :sale, compute
   the AR amount remaining open: gross-AR − sum-of-settling-payments.

   Returns vec of {:transaction-eid :external-id :open-amount
                   :original-amount :partner-eid :date}. Skips fully-
   paid transactions (open = 0). Filters out :draft and :cancelled
   transactions automatically.

   `ar-account-codes` is the set of chart codes that count as AR for
   the purposes of this query (e.g. #{\"1400\" \"1410\"} on SKR04)."
  [db ar-account-codes]
  (let [;; All postings on AR accounts, with their transaction's
        ;; external-id, partner, date, and state.
        rows (d/q '[:find ?tx ?ext-id ?amount ?partner ?date ?journal-type ?state
                    :in $ [?ar-code ...]
                    :where
                    [?a :account/code ?ar-code]
                    [?p :posting/account ?a]
                    [?p :posting/amount ?amount]
                    [?p :posting/transaction ?tx]
                    [?tx :transaction/external-id ?ext-id]
                    [?tx :transaction/state ?state]
                    [(get-else $ ?tx :transaction/effective-date :__null__) ?date]
                    [(get-else $ ?tx :transaction/partner :__null__) ?partner]
                    [?tx :transaction/journal ?j]
                    [(get-else $ ?j :journal/type :__null__) ?journal-type]]
                  db ar-account-codes)
        ;; Group postings by transaction and sum.
        by-tx (reduce
               (fn [acc [tx ext amt partner date jtype state]]
                 (-> acc
                     (assoc-in [tx :external-id] ext)
                     (assoc-in [tx :partner-eid] (when (not= partner :__null__) partner))
                     (assoc-in [tx :date] (when (not= date :__null__) date))
                     (assoc-in [tx :journal-type] jtype)
                     (assoc-in [tx :state] state)
                     (update-in [tx :ar-amount] (fnil #(.add ^java.math.BigDecimal % amt) 0M))))
               {}
               rows)
        ;; For each candidate sales tx, find any other transactions
        ;; that :transaction/settles → it; subtract their AR-side
        ;; offsets to get the open amount.
        settled (d/q '[:find ?settled ?amount
                       :in $ [?ar-code ...]
                       :where
                       [?settler :transaction/settles ?settled]
                       [?p :posting/transaction ?settler]
                       [?p :posting/account ?a]
                       [?a :account/code ?ar-code]
                       [?p :posting/amount ?amount]]
                     db ar-account-codes)
        settled-by (reduce (fn [acc [tx amt]]
                             (update acc tx (fnil #(.add ^java.math.BigDecimal % amt) 0M)))
                           {} settled)]
    (->> by-tx
         (keep (fn [[tx fields]]
                 (when (and (= :posted (:state fields))
                            (= :sale (:journal-type fields)))
                   (let [original (:ar-amount fields)
                         offset (or (settled-by tx) 0M)
                         open (.add ^java.math.BigDecimal original offset)]
                     (when (pos? (.signum ^java.math.BigDecimal open))
                       {:transaction-eid tx
                        :external-id (:external-id fields)
                        :original-amount original
                        :open-amount open
                        :partner-eid (:partner-eid fields)
                        :date (:date fields)})))))
         (sort-by :date)
         vec)))

(defn open-payables-by-tx
  "Mirror of `open-receivables-by-tx` for AP. AP postings are credits
   on the payable account, so the original amount is negative; the
   open amount stays negative (debt) until settled."
  [db ap-account-codes]
  (let [rows (d/q '[:find ?tx ?ext-id ?amount ?partner ?date ?journal-type ?state
                    :in $ [?ap-code ...]
                    :where
                    [?a :account/code ?ap-code]
                    [?p :posting/account ?a]
                    [?p :posting/amount ?amount]
                    [?p :posting/transaction ?tx]
                    [?tx :transaction/external-id ?ext-id]
                    [?tx :transaction/state ?state]
                    [(get-else $ ?tx :transaction/effective-date :__null__) ?date]
                    [(get-else $ ?tx :transaction/partner :__null__) ?partner]
                    [?tx :transaction/journal ?j]
                    [(get-else $ ?j :journal/type :__null__) ?journal-type]]
                  db ap-account-codes)
        by-tx (reduce
               (fn [acc [tx ext amt partner date jtype state]]
                 (-> acc
                     (assoc-in [tx :external-id] ext)
                     (assoc-in [tx :partner-eid] (when (not= partner :__null__) partner))
                     (assoc-in [tx :date] (when (not= date :__null__) date))
                     (assoc-in [tx :journal-type] jtype)
                     (assoc-in [tx :state] state)
                     (update-in [tx :ap-amount] (fnil #(.add ^java.math.BigDecimal % amt) 0M))))
               {}
               rows)
        settled (d/q '[:find ?settled ?amount
                       :in $ [?ap-code ...]
                       :where
                       [?settler :transaction/settles ?settled]
                       [?p :posting/transaction ?settler]
                       [?p :posting/account ?a]
                       [?a :account/code ?ap-code]
                       [?p :posting/amount ?amount]]
                     db ap-account-codes)
        settled-by (reduce (fn [acc [tx amt]]
                             (update acc tx (fnil #(.add ^java.math.BigDecimal % amt) 0M)))
                           {} settled)]
    (->> by-tx
         (keep (fn [[tx fields]]
                 (when (and (= :posted (:state fields))
                            (= :purchase (:journal-type fields)))
                   (let [original (:ap-amount fields)
                         offset (or (settled-by tx) 0M)
                         open (.add ^java.math.BigDecimal original offset)]
                     (when (neg? (.signum ^java.math.BigDecimal open))
                       {:transaction-eid tx
                        :external-id (:external-id fields)
                        :original-amount original
                        :open-amount open
                        :partner-eid (:partner-eid fields)
                        :date (:date fields)})))))
         (sort-by :date)
         vec)))

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
  [^java.math.BigDecimal bank-amount ^java.math.BigDecimal open-amount]
  (and bank-amount open-amount
       (zero? (.compareTo bank-amount open-amount))))

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
     - prefer smaller subsets (caller sorts results by count)"
  [opens ^java.math.BigDecimal target & {:keys [max-results min-size]
                                         :or {max-results 5 min-size 2}}]
  (let [opens (vec (take max-subset-search opens))
        n (count opens)
        results (atom [])]
    (letfn [(go [start picked sum]
              (when (< (count @results) max-results)
                (let [cmp (.compareTo ^java.math.BigDecimal sum target)]
                  (cond
                    (zero? cmp)
                    (when (>= (count picked) min-size)
                      (swap! results conj picked))
                    (pos? cmp)
                    nil ; overshot; backtrack
                    :else
                    (doseq [i (range start n)]
                      (let [open (nth opens i)
                            new-sum (.add ^java.math.BigDecimal sum
                                          ^java.math.BigDecimal (:open-amount open))]
                        (go (inc i) (conj picked open) new-sum)))))))]
      (go 0 [] 0M)
      @results)))

(defn suggest-match
  "Given a `:bank-line` entity and a db, return a seq of match
   candidates sorted by confidence (high → low). Each candidate:

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
  (let [bl (d/pull db [:bank-line/amount :bank-line/description
                       :bank-line/counterparty :bank-line/category]
                   bank-line-eid)
        amount (:bank-line/amount bl)
        desc (:bank-line/description bl)
        cp (:bank-line/counterparty bl)
        cat (:bank-line/category bl)
        inflow? (and amount (pos? (.signum ^java.math.BigDecimal amount)))
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
                             (:partner/name (d/pull db [:partner/name]
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
                                 (let [pn (:partner/name (d/pull db [:partner/name] pid))]
                                   (counterparty-matches-partner? cp pn))))))
                subsets (when (seq partner-opens)
                          (subsets-summing-to partner-opens amount))]
            (->> subsets
                 ;; Prefer fewer invoices first.
                 (sort-by count)
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
         (sort-by :confidence >)
         vec)))

;; ============================================================================
;; Commit
;; ============================================================================

(defn- ar-or-ap-account
  "Pick the contra account for a bank-side posting in :settle mode.
   For AR settlement (inflow), the contra is the AR account. For AP
   (outflow), the contra is the AP account. Reads from the FIRST
   settled transaction's posting on a reconcilable account."
  [db settled-tx-eids ar-codes ap-codes inflow?]
  (let [target-codes (if inflow? ar-codes ap-codes)
        eids (vec settled-tx-eids)]
    (ffirst (d/q '[:find ?a
                   :in $ [?ar-code ...] [?tx ...]
                   :where
                   [?p :posting/transaction ?tx]
                   [?p :posting/account ?a]
                   [?a :account/code ?ar-code]]
                 db target-codes eids))))

(declare commit-match-tx-data)

(defn commit-match!
  "Apply a match decision:
     - construct a payment-receipt transaction with two postings
       (bank ↔ AR/AP/contra)
     - link via :transaction/settles when match is :settle
     - update bank-line/status to :reconciled and link
       :bank-line/posting

   `match` is one entry from `suggest-match`'s result, OR a hand-
   crafted equivalent. `journal-eid` is the journal to file the
   payment under (typically a :bank journal).

   `opts`:
     :ar-codes / :ap-codes  — same as suggest-match
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
   vec), and the bank-line's `:bank-line/posting` ref carries the
   string `\"pay-tx-p0\"` so datahike resolves it consistently in
   the one commit."
  [db bank-line-eid match journal-eid
   {:keys [ar-codes ap-codes external-id-prefix]
    :or {ar-codes #{"1400"} ap-codes #{"3300"}
         external-id-prefix "PAY-"}}]
  (let [bl (d/pull db [:bank-line/external-id :bank-line/amount
                       :bank-line/source-account :bank-line/commodity
                       :bank-line/date :bank-line/counterparty]
                   bank-line-eid)
        amount (:bank-line/amount bl)
        bank-acct (:db/id (:bank-line/source-account bl))
        commodity (:db/id (:bank-line/commodity bl))
        date (:bank-line/date bl)
        inflow? (pos? (.signum ^java.math.BigDecimal amount))
        contra (case (:kind match)
                 :settle    (ar-or-ap-account db (:transactions match)
                                              ar-codes ap-codes inflow?)
                 :categorize (:contra-account match))
        _ (when-not contra
            (throw (ex-info "Cannot resolve contra account for match"
                            {:match match :inflow? inflow?})))
        pay-ext-id (str external-id-prefix (:bank-line/external-id bl))
        payment-tx
        (posting/build-transaction
         {:tx-tempid "pay-tx"
          :transaction
          (cond-> {:transaction/external-id    pay-ext-id
                   :transaction/journal        journal-eid
                   :transaction/effective-date date
                   :transaction/narration      (str "Payment via bank: "
                                                    (:bank-line/counterparty bl))
                   :transaction/state          :posted
                   :transaction/posted-at      date}
            (and (= :settle (:kind match))
                 (seq (:transactions match)))
            (assoc :transaction/settles (vec (:transactions match))))
          ;; Bank-side leg FIRST → gets tempid "pay-tx-p0".
          :postings
          [{:posting/account bank-acct
            :posting/amount amount
            :posting/commodity commodity
            :posting/posted-at date}
           {:posting/account contra
            :posting/amount (.negate ^java.math.BigDecimal amount)
            :posting/commodity commodity
            :posting/posted-at date}]})]
    (conj (vec payment-tx)
          {:db/id bank-line-eid
           :bank-line/status :reconciled
           :bank-line/reconciled-at (Date.)
           :bank-line/posting "pay-tx-p0"})))

(defn unmatched-queue
  "Return all `:bank-line` entities still in `:unmatched` status,
   most recent first. Light shape: `[:db/id :external-id :date
   :amount :counterparty :description]`."
  [db]
  (->> (d/q '[:find [?bl ...]
              :where [?bl :bank-line/status :unmatched]]
            db)
       (mapv (fn [eid]
               (let [bl (d/pull db
                                [:db/id :bank-line/external-id
                                 :bank-line/date :bank-line/amount
                                 :bank-line/counterparty :bank-line/description]
                                eid)]
                 bl)))
       (sort-by :bank-line/date #(compare %2 %1))))
