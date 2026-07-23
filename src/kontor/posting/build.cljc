(ns kontor.posting.build
  "The pure tx-data builders, extracted from `kontor.posting` so the browser
   can materialize posting entities client-side (rung 1, note 192). Depends
   only on `kontor.posting.validate` (the balance check) and `kontor.bitemporal`
   (valid-time stamping) — no datahike, no gate, no costing/valuation. The
   JVM-only inventory / GR-IR / costing builders and the gate-routed
   `post-transaction!` stay in `kontor.posting`, which re-exports these two."
  (:require [kontor.bitemporal :as kbt]
            [kontor.posting.validate :as pv]))

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

   Use `kontor.posting.validate/validate` for non-throwing inspection.

   Per ADR-021 (revised), `:kontor.posting/ledger` is fully optional. A
   posting without the attribute is conceptually in the *primary*
   book; readers and validators treat the nil-keyed group as the
   primary ledger. Multi-ledger users explicitly tag their postings
   with a ledger ref or lookup-ref; everyone else pays nothing.

   Optional `:tx-tempid` (top-level key, ADR-067) — the tempid for
   the transaction entity, default `-1`. Pass a **string** when
   composing several `build-transaction` outputs into one tx-data
   (a `kontor.workflow.process` step that posts N entries): each call needs a
   distinct tempid or the transactions collide into one. With a
   string `s`, postings get tempids `\"s-p0\"`, `\"s-p1\"`, …; the
   default `-1` keeps the original `-100-i` posting tempids."
  [{:keys [transaction postings tx-tempid] :as input}]
  (let [report (pv/validate input)]
    (when-not (:ok? report)
      ;; Carry a typed :type so callers dispatch uniformly across BOTH write
      ;; paths: a sum-to-zero imbalance here raises the SAME
      ;; :validation/sum-to-zero the gate's transactor-side check raises
      ;; (note-196 F2); other structural failures get :posting/structural-invalid.
      (let [unbalanced? (some #(= :unbalanced (:error %)) (:errors report))]
        (throw (ex-info (if unbalanced?
                          "Postings do not sum to zero per commodity"
                          "build-transaction: input failed structural validation")
                        {:type   (if unbalanced?
                                   :validation/sum-to-zero
                                   :posting/structural-invalid)
                         :report report
                         :input  input})))))
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
   (let [pa (or posted-at #?(:clj (java.util.Date.) :cljs (js/Date.)))
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
