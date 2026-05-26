(ns kontor.document.invoice
  "Kernel-side **invoice-as-document** — lifecycle (create / send /
   mark-paid / cancel) + the per-doc status machine. The business
   workflow (GL posting, dispatch, recurring) lives in the companion
   `modules/invoice/` under the `kontor.invoice.*` prefix.

   The namespace split (doc/research/69 §6 #1) closed a prior collision:
     - this ns (kernel)    : the document + lifecycle
     - kontor.invoice.posting (companion): GL posting builder
     - kontor.invoice.bridge  (companion): external dispatch
     - kontor.invoice.schema  (companion): workflow-specific attrs

   Pre-2026-05 history: this was `kontor.invoice`; the rename frees the
   `kontor.invoice` prefix for the companion's workflow surface.

   Workflow:
     create!     — :draft state, no accounting impact
     send!       — :draft → :sent, creates the accounting transaction
                   via posting-builder (country-supplied; e.g.
                   l10n-de.invoice/posting-builder for SKR04)
     mark-paid!  — :sent → :paid (reconciliation calls this when a
                   bank-line settles the invoice's transaction)
     cancel!     — :sent → :cancelled, creates a reversal transaction
                   pointing at the original via :transaction/reverses

   Totals: caller may supply :invoice/total-net etc. directly, OR
   leave them blank and call `materialize-totals` to derive from
   line items. We don't auto-derive on create — letting the caller
   bring numbers from elsewhere (beleg's existing invoice schema,
   external billing system) avoids round-trip rounding mismatches."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.payment-term :as pt]
            [kontor.posting :as posting]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation])
  (:import [java.util Date]))

;; ============================================================================
;; Total-line computation (from line items)
;; ============================================================================

(defn- line-net
  ^java.math.BigDecimal [{:invoice-line/keys [quantity unit-price]}]
  (.setScale (.multiply (bigdec quantity) (bigdec unit-price))
             2 java.math.RoundingMode/HALF_EVEN))

(defn- line-vat
  ^java.math.BigDecimal [{:invoice-line/keys [vat-rate] :as line}]
  (.setScale (.multiply (line-net line)
                        (.divide (bigdec vat-rate)
                                 (bigdec 100)
                                 6 java.math.RoundingMode/HALF_EVEN))
             2 java.math.RoundingMode/HALF_EVEN))

(defn materialize-totals
  "Compute :invoice/total-net + :total-vat + :total-gross from the
   invoice's line items, and return a map ready to merge onto the
   invoice. Pass to d/transact alongside the original create."
  [lines]
  (let [net (reduce #(.add ^java.math.BigDecimal %1 (line-net %2)) 0M lines)
        vat (reduce #(.add ^java.math.BigDecimal %1 (line-vat %2)) 0M lines)]
    {:invoice/total-net   net
     :invoice/total-vat   vat
     :invoice/total-gross (.add net vat)}))

;; ============================================================================
;; create!
;; ============================================================================

(declare create-tx-data send-tx-data mark-paid-tx-data cancel-tx-data
         flip-paid-on-settlement-tx-data)

(defn create-tx-data
  "Pure tx-data builder for `create!` (ADR-068). Returns the vector
   of [invoice-row line-row …] ready to transact."
  [db invoice-map]
  (when-not (:invoice/external-id invoice-map)
    (throw (ex-info ":invoice/external-id is required" {:invoice invoice-map})))
  (when-not (:invoice/issue-date invoice-map)
    (throw (ex-info ":invoice/issue-date is required" {:invoice invoice-map})))
  (when-not (seq (:invoice/lines invoice-map))
    (throw (ex-info ":invoice/lines must be non-empty" {:invoice invoice-map})))
  (let [lines (:invoice/lines invoice-map)
        tempid (str "invoice-" (:invoice/external-id invoice-map))
        ;; Materialize totals from lines unless caller supplied them.
        totals (cond-> {}
                 (not (:invoice/total-net   invoice-map)) (merge (materialize-totals lines)))
        ;; Apply payment-term if supplied; produces due-date +
        ;; optionally discount-deadline.
        term-frag (when-let [term-eid (:invoice/payment-term invoice-map)]
                    (let [term (d/pull db [:db/id :payment-term/net-days
                                           :payment-term/discount-pct
                                           :payment-term/discount-days]
                                       term-eid)
                          frag (pt/apply-term (:invoice/issue-date invoice-map) term)
                          ;; pt/apply-term returns :transaction/* keys; rename to :invoice/*
                          rename {:transaction/payment-term :invoice/payment-term
                                  :transaction/due-date     :invoice/due-date
                                  :transaction/discount-deadline :invoice/discount-deadline}]
                      (into {} (map (fn [[k v]] [(rename k k) v])) frag)))
        ;; Build line-item entity maps with sequential tempids and a
        ;; :invoice-line/invoice backref to the parent.
        line-tx (mapv (fn [i line]
                        (-> line
                            (dissoc :invoice/lines)
                            (assoc :db/id (str "line-" (:invoice/external-id invoice-map) "-" i)
                                   :invoice-line/invoice tempid
                                   :invoice-line/sequence (or (:invoice-line/sequence line) (inc i)))))
                      (range)
                      lines)
        ;; The invoice entity itself: original map + totals + term + lines refs.
        invoice-tx (-> invoice-map
                       (dissoc :invoice/lines)
                       (assoc :db/id tempid
                              :invoice/status :draft
                              :invoice/currency (or (:invoice/currency invoice-map) "EUR"))
                       (merge totals)
                       (merge term-frag)
                       (assoc :invoice/lines (mapv :db/id line-tx)))]
    (into [invoice-tx] line-tx)))

(defn create!
  "Create a draft invoice + its line items. `invoice-map` shape:

     {:invoice/external-id   String  ; required
      :invoice/issue-date    Date    ; required
      :invoice/seller        eid     ; partner ref
      :invoice/buyer         eid     ; partner ref
      :invoice/payment-term  eid     ; payment-term ref (optional)
      :invoice/currency      String  ; default \"EUR\"
      :invoice/lines         [<line-map> …]  ; required, ≥1
      :invoice/notes         [String]
      :invoice/buyer-reference String}

   Line maps:
     {:invoice-line/sequence  long
      :invoice-line/name      String
      :invoice-line/description String
      :invoice-line/quantity  bigdec
      :invoice-line/unit-code String
      :invoice-line/unit-price bigdec
      :invoice-line/vat-rate  bigdec
      :invoice-line/vat-category String
      :invoice-line/account   eid (optional override)}

   Auto-applies payment-term to compute :due-date + :discount-deadline
   when :payment-term is set.

   Auto-materializes :total-net / :total-vat / :total-gross from
   line items when not supplied.

   Returns the resulting tx-report; the new invoice's eid is in
   `(:tempids report)` keyed by the issued tempid.

   The pure tx-data builder is `create-tx-data` (ADR-068)."
  [conn invoice-map]
  (validation/transact-with-validation
   conn (create-tx-data (d/db conn) invoice-map)))

;; ============================================================================
;; send!
;; ============================================================================

(defn send-tx-data
  "Pure tx-data builder for `send!` (ADR-068). Composes the posting
   tx-data + the invoice update + the status-history row into ONE
   atomic vector. The transaction tempid is `-1` (build-transaction
   convention); callers extract the resulting eid from
   `(:tempids report)` under that key.

   Required keys in opts:
     :invoice-eid     — entity-id of the :draft invoice
     :posting-builder — fn (db invoice-pulled) → posting-input map

   Optional: :changed-by-uid, :reason, :reason-note, :supporting-doc,
             :changed-at (default now)."
  [db {:keys [invoice-eid posting-builder changed-by-uid reason reason-note
              supporting-doc changed-at]}]
  (let [inv (d/pull db
                    [:db/id :invoice/external-id :invoice/status
                     :invoice/issue-date :invoice/currency
                     :invoice/total-net :invoice/total-vat :invoice/total-gross
                     :invoice/buyer-reference
                     {:invoice/seller [:db/id :kontor.partner/external-id :kontor.partner/name
                                       :kontor.partner/tax-id]}
                     {:invoice/buyer  [:db/id :kontor.partner/external-id :kontor.partner/name
                                       :kontor.partner/tax-id]}
                     {:invoice/lines [:invoice-line/sequence
                                      :invoice-line/name
                                      :invoice-line/description
                                      :invoice-line/quantity
                                      :invoice-line/unit-code
                                      :invoice-line/unit-price
                                      :invoice-line/vat-rate
                                      :invoice-line/vat-category
                                      {:invoice-line/account [:db/id :kontor.account/code]}]}]
                    invoice-eid)]
    (when-not (= :draft (:invoice/status inv))
      (throw (ex-info "Only :draft invoices can be sent"
                      {:invoice-eid invoice-eid
                       :status (:invoice/status inv)})))
    (let [now (or changed-at (Date.))
          ;; Country module supplies the posting tx-data.
          tx-input (posting-builder db inv)
          tx (posting/build-transaction tx-input)
          ;; `build-transaction` uses -1 for the transaction tempid.
          tx-tempid -1
          status-tx (sm/record-status-change-tx-data
                     db
                     (cond-> {:entity invoice-eid
                              :entity-type :invoice
                              :facet :invoice/status
                              :from :draft
                              :to :sent
                              :changed-at now
                              :origin-transaction tx-tempid}
                       changed-by-uid (assoc :changed-by-uid changed-by-uid)
                       reason         (assoc :reason reason)
                       reason-note    (assoc :reason-note reason-note)
                       supporting-doc (assoc :supporting-doc supporting-doc)))]
      (-> (vec tx)
          (conj {:db/id invoice-eid :invoice/transaction tx-tempid})
          (into status-tx)))))

(defn send!
  "Transition a :draft invoice to :sent. Calls `(posting-builder db
   invoice-pulled)` to produce the accounting tx-data (country
   module's job — knows the chart conventions). Transacts the
   resulting transaction + sets :invoice/transaction backref +
   moves status :draft → :sent via the status machine (ADR-034 +
   ADR-038), all in ONE atomic tx through the gate (ADR-068).

   `posting-builder` returns a map with :transaction + :postings,
   the same shape posting/build-transaction accepts.

   `opts`:
     :vt-from / :vt-to  — valid-time bounds; default :vt-from is the
                          posting's `:transaction/effective-date`
                          (matching build-transaction's convention),
                          fallback to `now`.
     :changed-by-uid    — actor recorded on the :status-history row
     :reason            — codified reason (ADR-038)
     :reason-note       — free-text
     :supporting-doc    — ref to :audit-doc

   Returns `{:transaction-eid <eid>}`. The pure tx-data builder is
   `send-tx-data` (ADR-068)."
  ([conn invoice-eid posting-builder]
   (send! conn invoice-eid posting-builder nil))
  ([conn invoice-eid posting-builder {:keys [vt-from vt-to changed-by-uid
                                             reason reason-note supporting-doc]}]
   (let [now (Date.)
         db (d/db conn)
         tx-data (send-tx-data db
                               {:invoice-eid invoice-eid
                                :posting-builder posting-builder
                                :changed-by-uid changed-by-uid
                                :reason reason
                                :reason-note reason-note
                                :supporting-doc supporting-doc
                                :changed-at now})
         ;; Preserve build-transaction's vt-from convention: default
         ;; to the posting's :transaction/effective-date. Find it by
         ;; scanning the composed tx-data for the transaction row
         ;; (the only entity carrying :transaction/effective-date).
         eff-date (some :transaction/effective-date tx-data)
         report (validation/transact-with-validation
                 conn (kbt/with-vt tx-data
                        (or vt-from eff-date now)
                        (or vt-to kbt/forever)))]
     {:transaction-eid (get-in report [:tempids -1])})))

;; ============================================================================
;; mark-paid! / cancel!
;; ============================================================================

(defn mark-paid-tx-data
  "Pure tx-data builder for `mark-paid!` (ADR-068)."
  [db {:keys [invoice-eid changed-by-uid reason reason-note supporting-doc
              changed-at]}]
  (sm/record-status-change-tx-data
   db
   (cond-> {:entity invoice-eid
            :entity-type :invoice
            :facet :invoice/status
            :to :paid
            :changed-at (or changed-at (Date.))}
     changed-by-uid (assoc :changed-by-uid changed-by-uid)
     reason         (assoc :reason reason)
     reason-note    (assoc :reason-note reason-note)
     supporting-doc (assoc :supporting-doc supporting-doc))))

(defn mark-paid!
  "Flip a :sent (or :partially-paid) invoice to :paid via the status
   machine (ADR-034 + ADR-038). Called by the reconciliation hook
   when a bank-line settles the invoice's transaction; can also be
   invoked directly when payment is recorded out-of-band. Routes
   through the gate (ADR-068).

   `opts`:
     :vt-from / :vt-to  — valid-time bounds (default :vt-from = now)
     :changed-by-uid    — actor recorded on :status-history
     :reason / :reason-note / :supporting-doc — ADR-038

   The pure tx-data builder is `mark-paid-tx-data`."
  ([conn invoice-eid]
   (mark-paid! conn invoice-eid nil))
  ([conn invoice-eid {:keys [vt-from vt-to changed-by-uid
                             reason reason-note supporting-doc]}]
   (let [now (Date.)]
     (validation/transact-with-validation
      conn (kbt/with-vt (mark-paid-tx-data
                         (d/db conn)
                         {:invoice-eid invoice-eid
                          :changed-by-uid changed-by-uid
                          :reason reason
                          :reason-note reason-note
                          :supporting-doc supporting-doc
                          :changed-at now})
             (or vt-from now)
             (or vt-to kbt/forever))))))

(defn cancel-tx-data
  "Pure tx-data builder for `cancel!` (ADR-068). For :draft, returns
   just the status-history rows. For :sent, returns the reversal
   transaction + the status-history rows, all composed atomically."
  [db {:keys [invoice-eid changed-by-uid reason reason-note supporting-doc
              changed-at]}]
  (let [inv (d/pull db [:invoice/status :invoice/external-id
                        {:invoice/transaction
                         [:db/id :transaction/external-id
                          {:transaction/journal [:db/id]}]}]
                    invoice-eid)
        now (or changed-at (Date.))
        status-change-opts (cond-> {:entity invoice-eid
                                    :entity-type :invoice
                                    :facet :invoice/status
                                    :to :cancelled
                                    :changed-at now}
                             changed-by-uid (assoc :changed-by-uid changed-by-uid)
                             reason         (assoc :reason reason)
                             reason-note    (assoc :reason-note reason-note)
                             supporting-doc (assoc :supporting-doc supporting-doc))]
    (case (:invoice/status inv)
      :draft
      (vec (sm/record-status-change-tx-data db status-change-opts))
      :sent
      (let [original-tx (:invoice/transaction inv)
            original-tx-eid (:db/id original-tx)
            ;; Pull all postings of the original tx and negate.
            postings (->> (d/q '[:find ?p ?acct ?amt ?cur
                                 :in $ ?tx
                                 :where
                                 [?p :posting/transaction ?tx]
                                 [?p :posting/account ?acct]
                                 [?p :posting/amount ?amt]
                                 [?p :posting/commodity ?cur]]
                               db original-tx-eid)
                          (mapv (fn [[_p acct amt cur]]
                                  {:posting/account acct
                                   :posting/amount (.negate ^java.math.BigDecimal amt)
                                   :posting/commodity cur
                                   :posting/posted-at now})))
            reversal-ext (str (:invoice/external-id inv) "-REV")
            reversal-tx (posting/build-transaction
                         {:transaction
                          {:transaction/external-id reversal-ext
                           :transaction/journal (:db/id (:transaction/journal original-tx))
                           :transaction/effective-date now
                           :transaction/narration (str "Reversal of " (:invoice/external-id inv))
                           :transaction/state :posted
                           :transaction/posted-at now
                           :transaction/reverses original-tx-eid}
                          :postings postings})]
        (vec (concat reversal-tx
                     (sm/record-status-change-tx-data db status-change-opts))))
      (throw (ex-info "Cannot cancel invoice in current status"
                      {:invoice-eid invoice-eid
                       :status (:invoice/status inv)})))))

(defn cancel!
  "Cancel a :sent or :draft invoice via the status machine.
   For :draft, just flips status :draft → :cancelled.
   For :sent, also creates a reversal transaction that negates each
   posting and points at the original via :transaction/reverses
   (kernel ADR-007: never edit posted entries; reverse + re-post).
   Routes through the gate (ADR-068).

   `opts`:
     :vt-from / :vt-to  — valid-time bounds (default :vt-from = now)
     :changed-by-uid    — actor recorded on :status-history
     :reason / :reason-note / :supporting-doc — ADR-038

   The pure tx-data builder is `cancel-tx-data`."
  ([conn invoice-eid]
   (cancel! conn invoice-eid nil))
  ([conn invoice-eid {:keys [vt-from vt-to changed-by-uid
                             reason reason-note supporting-doc]}]
   (let [now (Date.)]
     (validation/transact-with-validation
      conn (kbt/with-vt (cancel-tx-data
                         (d/db conn)
                         {:invoice-eid invoice-eid
                          :changed-by-uid changed-by-uid
                          :reason reason
                          :reason-note reason-note
                          :supporting-doc supporting-doc
                          :changed-at now})
             (or vt-from now)
             (or vt-to kbt/forever))))))

;; ============================================================================
;; Reconciliation hook
;; ============================================================================

(defn flip-paid-on-settlement-tx-data
  "Pure tx-data builder for `flip-paid-on-settlement` (ADR-068).
   Returns the status-change tx-data vector (possibly empty when all
   referenced invoices are already :paid)."
  [db {:keys [settled-tx-eids changed-by-uid reason reason-note supporting-doc
              changed-at]}]
  (let [invoice-eids (d/q '[:find [?inv ...]
                            :in $ [?tx ...]
                            :where [?inv :invoice/transaction ?tx]]
                          db settled-tx-eids)
        now (or changed-at (Date.))
        ;; Compose all status-change tx-data in one tx. Skip invoices
        ;; already :paid (idempotent).
        pending (filter (fn [eid]
                          (not= :paid
                                (:invoice/status (d/pull db [:invoice/status] eid))))
                        invoice-eids)]
    (vec (mapcat
          (fn [eid]
            (sm/record-status-change-tx-data
             db
             (cond-> {:entity eid
                      :entity-type :invoice
                      :facet :invoice/status
                      :to :paid
                      :changed-at now
                      :reason (or reason :reconciled)}
               changed-by-uid (assoc :changed-by-uid changed-by-uid)
               reason-note    (assoc :reason-note reason-note)
               supporting-doc (assoc :supporting-doc supporting-doc))))
          pending))))

(defn flip-paid-on-settlement
  "Call after a reconciliation/commit-match! that settled
   transactions which are linked to invoices. Walks each settled
   transaction's `:invoice/_transaction` backref and moves any
   referenced invoice to :paid via the status machine. Routes through
   the gate (ADR-068).

   Each invoice's :from is auto-detected (:sent or :partially-paid).
   Already-:paid invoices are skipped (no-op).

   `opts`:
     :vt-from / :vt-to  — valid-time bounds (default :vt-from = now;
                          e.g. bank-line's value-date)
     :changed-by-uid    — actor recorded on :status-history
     :reason            — codified reason (default :reconciled)
     :reason-note / :supporting-doc — ADR-038

   The pure tx-data builder is `flip-paid-on-settlement-tx-data`."
  ([conn settled-tx-eids]
   (flip-paid-on-settlement conn settled-tx-eids nil))
  ([conn settled-tx-eids {:keys [vt-from vt-to changed-by-uid
                                 reason reason-note supporting-doc]}]
   (let [now (Date.)
         tx-data (flip-paid-on-settlement-tx-data
                  (d/db conn)
                  {:settled-tx-eids settled-tx-eids
                   :changed-by-uid changed-by-uid
                   :reason reason
                   :reason-note reason-note
                   :supporting-doc supporting-doc
                   :changed-at now})]
     (when (seq tx-data)
       (validation/transact-with-validation
        conn (kbt/with-vt tx-data
               (or vt-from now)
               (or vt-to kbt/forever)))))))
