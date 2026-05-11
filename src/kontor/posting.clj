(ns datahike-accounting.posting
  "Build draft transactions out of postings, validating the structural
   double-entry invariants:

     1. A transaction has 2+ postings.
     2. Postings sum to zero per commodity (the double-entry rule —
        multi-currency moves balance per currency independently).
     3. Each posting has the minimum required fields (account,
        amount, commodity).
     4. The transaction has the minimum required header fields
        (journal, effective-date).

   What this module does NOT do:
     - Tax expansion (`datahike-accounting.tax` will plug in there)
     - Sealing / posted-at lifecycle (`datahike-accounting.sealing`)
     - Period-locked rejection (`datahike-accounting.period`)
     - Account-active / commodity-match checks (the invariant library
       will, per ADR-011)

   `build-transaction` produces a tx-data vector ready to hand to
   `datahike.api/transact`, plus a small report. It does not connect
   to a db itself — the validations performed here are purely
   structural, not catalog-aware. Callers compose this with the
   db-aware checks in `validation.clj` (Phase 1)."
  (:require [datahike-accounting.money :as money]))

;; ============================================================================
;; Validation predicates
;; ============================================================================

(def ^:private allowed-display-types
  "Per the schema doc on :posting/display-type:
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
  (or (:posting/display-type posting) default-display-type))

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
           (nil? (:posting/account posting)))
      (conj {:posting posting
             :error :missing-account
             :message "balance-affecting posting requires :posting/account"})

      (and (balance-affecting? posting)
           (nil? (:posting/amount posting)))
      (conj {:posting posting
             :error :missing-amount
             :message "balance-affecting posting requires :posting/amount"})

      (and (balance-affecting? posting)
           (nil? (:posting/commodity posting)))
      (conj {:posting posting
             :error :missing-commodity
             :message "balance-affecting posting requires :posting/commodity"}))))

;; ============================================================================
;; Sum-to-zero
;; ============================================================================

(defn balance-by-commodity
  "Return {commodity => Money} of the balance-affecting postings'
   net amount per commodity. A balanced transaction has every entry
   zero."
  [postings]
  (-> postings
      (->> (filter balance-affecting?)
           (keep money/posting->money))
      money/sum-by-commodity))

(defn unbalanced-commodities
  "Return a map {commodity => Money} of commodities where the balance
   is non-zero. Empty map iff the transaction balances per commodity.

   Multi-currency rule: each commodity sums independently. A USD/EUR
   payment with both sides recorded balances if and only if the USD
   leg sums to zero AND the EUR leg sums to zero."
  [postings]
  (->> (balance-by-commodity postings)
       (remove (fn [[_c m]] (money/zero? m)))
       (into {})))

;; ============================================================================
;; Transaction header validation
;; ============================================================================

(defn- header-validation-errors
  [{:keys [transaction]}]
  (cond-> []
    (nil? (:transaction/journal transaction))
    (conj {:error :missing-journal
           :message ":transaction/journal is required"})

    (nil? (:transaction/effective-date transaction))
    (conj {:error :missing-effective-date
           :message ":transaction/effective-date is required (the
                     bitemporal valid-time of this entry)"})))

;; ============================================================================
;; Public entry
;; ============================================================================

(defn validate
  "Pure structural validation. Returns
     {:ok? boolean
      :postings [...]
      :errors [...]
      :balance {commodity => Money}
      :unbalanced {commodity => Money}}

   No db access. Use this when you want to inspect a draft transaction
   without committing it."
  [{:keys [transaction postings] :as input}]
  (let [posting-errors (mapcat posting-validation-errors postings)
        header-errors (header-validation-errors input)
        all-errors (vec (concat header-errors posting-errors))
        balance (balance-by-commodity postings)
        unbalanced (->> balance
                        (remove (fn [[_c m]] (money/zero? m)))
                        (into {}))
        too-few? (< (count (filter balance-affecting? postings)) 2)
        all-errors (cond-> all-errors
                     too-few?
                     (conj {:error :too-few-postings
                            :message "transaction needs at least 2
                                      balance-affecting postings"})

                     (seq unbalanced)
                     (conj {:error :unbalanced
                            :message "postings do not sum to zero per commodity"
                            :unbalanced unbalanced}))]
    {:ok?        (empty? all-errors)
     :transaction transaction
     :postings   postings
     :errors     all-errors
     :balance    balance
     :unbalanced unbalanced}))

(defn build-transaction
  "Build a tx-data vector ready for `datahike.api/transact`, raising on
   structural problems. Input shape:

     {:transaction { :transaction/journal         <ref or external-id>
                     :transaction/effective-date  <#inst>
                     :transaction/narration       <string>
                     :transaction/external-id     <string>      ; optional
                     :transaction/partner         <ref>         ; optional
                     :transaction/state           <kw>          ; defaults :draft
                     :transaction/source          <string>      ; optional
                     ...other transaction/* attrs }
      :postings    [ { :posting/account          <ref>
                       :posting/amount           <bigdec>
                       :posting/commodity        <ref>
                       :posting/display-type     <kw>           ; defaults :product
                       :posting/valid-from       <#inst>        ; optional, falls
                                                                ;   back to txn's
                                                                ;   effective-date
                       :posting/partner          <ref>          ; optional
                       :posting/narration        <string>       ; optional
                       :posting/taxes-applied    [<refs>]       ; optional
                       :posting/account-tags     [<refs>]       ; optional
                       ...other posting/* attrs }
                     ... ]}

   Returns a tx-data vector that, when transacted, creates one new
   :transaction entity and N new :posting entities, refs threaded.
   Throws ex-info on any structural error.

   This function does NOT do the catalog-aware checks (account
   exists/active, commodity matches account, period not locked,
   sealing, …) — those run at the validation/db boundary.

   Use `validate` for non-throwing inspection."
  [{:keys [transaction postings] :as input}]
  (let [report (validate input)]
    (when-not (:ok? report)
      (throw (ex-info "build-transaction: input failed structural validation"
                      {:report report
                       :input input}))))
  (let [tx-tempid -1
        tx-base   (cond-> (assoc transaction :db/id tx-tempid)
                    (nil? (:transaction/state transaction))
                    (assoc :transaction/state :draft))
        ;; Each posting becomes its own entity referencing the
        ;; transaction. Default display-type :product. Default
        ;; valid-from to the transaction's effective-date.
        posting-entities
        (mapv (fn [i posting]
                (cond-> (assoc posting
                               :db/id (- -100 i)
                               :posting/transaction tx-tempid)
                  (nil? (:posting/display-type posting))
                  (assoc :posting/display-type :product)
                  (and (balance-affecting? posting)
                       (nil? (:posting/valid-from posting)))
                  (assoc :posting/valid-from
                         (:transaction/effective-date transaction))))
              (range)
              postings)]
    (into [tx-base] posting-entities)))
