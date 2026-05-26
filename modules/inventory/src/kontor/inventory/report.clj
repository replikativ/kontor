(ns kontor.inventory.report
  "kontor-inventory reconciliation reports — ADR-060.

   Two helpers, both thin queries over facts that already exist —
   the append-only `:inventory-detail` ledger *is* the roll-forward,
   and `:valuation-layer` + the GL share one fact log:

   - `inventory-roll-forward` — opening + Σ movements = closing, the
     movements bucketed by `:kontor.inventory-detail/source-kind`. The
     audit-grade inventory roll-forward (research note 36 §9) — no
     separate roll-forward table to drift, reason codes and
     immutability are structural.
   - `valuation-tie-out` — asserts the inventory subledger
     (`Σ valuation/on-hand-value` per book) equals the GL inventory
     account balance, surfacing any delta (research note 36 §2 — the
     'my balance-sheet inventory number is wrong' detective control)."
  (:require [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.valuation :as valuation]
            [kontor.inventory.core :as inv])
  (:import [java.math BigDecimal]
           [java.util Date]))

;; ============================================================================
;; inventory-roll-forward
;; ============================================================================

(defn- before? [^Date a ^Date b] (neg? (.compareTo a b)))

(defn inventory-roll-forward
  "The inventory roll-forward over `[from, to)` for a `:scope` —
   `opening + Σ movements = closing`, derived from the
   `:inventory-detail` ledger.

   `:scope` is an `:inventory-item` eid or `{:product P :facility F?}`
   (resolved by `kontor.inventory.core/resolve-scope`).

   Returns:
     {:window {:from :to}
      :opening          bigdec   ; Σ :qoh-diff with :effective-date < :from
      :movements        {source-kind → bigdec}  ; Σ :qoh-diff in-window
      :movements-total  bigdec
      :closing          bigdec}  ; opening + movements-total

   Bitemporal: pass `:as-of-tx` to roll forward as the books stood at
   a past tx-time."
  [db {:keys [from to scope as-of-tx]}]
  (when-not (and from to)
    (throw (ex-info "inventory-roll-forward requires :from and :to" {})))
  (let [db*   (if as-of-tx (d/as-of db as-of-tx) db)
        items (inv/resolve-scope db* scope)
        rows  (when (seq items)
                (d/q '[:find ?ed ?sk ?diff
                       :with ?d
                       :in $ [?item ...]
                       :where
                       [?d :kontor.inventory-detail/inventory-item ?item]
                       [?d :kontor.inventory-detail/effective-date ?ed]
                       [?d :kontor.inventory-detail/qoh-diff ?diff]
                       [(get-else $ ?d :kontor.inventory-detail/source-kind :unspecified) ?sk]]
                     db* items))
        opening (reduce (fn [^BigDecimal acc [ed _ diff]]
                          (if (before? ed from) (.add acc diff) acc))
                        0M rows)
        movements (reduce (fn [acc [ed sk diff]]
                            (if (and (not (before? ed from)) (before? ed to))
                              (update acc sk (fnil #(.add ^BigDecimal % diff) 0M))
                              acc))
                          {} rows)
        mtotal (reduce (fn [^BigDecimal acc [_ v]] (.add acc v)) 0M movements)]
    {:window {:from from :to to}
     :opening opening
     :movements movements
     :movements-total mtotal
     :closing (.add ^BigDecimal opening mtotal)}))

;; ============================================================================
;; valuation-tie-out
;; ============================================================================

(defn valuation-tie-out
  "Reconcile the inventory subledger to the GL inventory account.
   `subledger` = `Σ valuation/on-hand-value` over every item with a
   layer in the book; `gl` = the GL inventory account balance for
   `:commodity`. A non-zero `:difference` is the 'my balance-sheet
   inventory number is wrong' finding — surfaced, not hidden.

   Required: :book (valuation-book code/eid), :inventory-account
             (eid), :commodity (eid).
   Optional: :as-of-valid, :as-of-tx (the bitemporal cursor, applied
             to BOTH sides — `:as-of-tx` snapshots the db the
             subledger reduce runs against, so it cannot compare a
             current subledger to a historical GL).
   Returns {:book eid :subledger bigdec :gl bigdec :difference bigdec
            :ok? boolean}."
  [conn {:keys [book inventory-account commodity as-of-valid as-of-tx]}]
  (when-not book               (throw (ex-info ":book required" {})))
  (when-not inventory-account  (throw (ex-info ":inventory-account required" {})))
  (when-not commodity          (throw (ex-info ":commodity required" {})))
  (let [;; :as-of-tx is honoured on the subledger side by snapshotting
        ;; the db — valuation/on-hand-value only reads :as-of-valid
        ;; from opts, so the tx-time axis must be applied to `db`
        ;; itself (account-balance applies it on the GL side).
        db       (cond-> (d/db conn) as-of-tx (d/as-of as-of-tx))
        book-eid (valuation/resolve-book db book)
        opts     (cond-> {}
                   as-of-valid (assoc :as-of-valid as-of-valid)
                   as-of-tx    (assoc :as-of-tx as-of-tx))
        items    (d/q '[:find [?item ...]
                        :in $ ?b
                        :where
                        [?l :kontor.valuation-layer/book ?b]
                        [?l :kontor.valuation-layer/item ?item]]
                      db book-eid)
        subledger (reduce (fn [^BigDecimal acc item]
                            (.add acc (valuation/on-hand-value db book-eid item opts)))
                          0M items)
        gl (or (:amount (get (balance/account-balance conn inventory-account opts)
                             commodity))
               0M)
        diff (.subtract subledger ^BigDecimal gl)]
    {:book       book-eid
     :subledger  subledger
     :gl         gl
     :difference diff
     :ok?        (zero? (.signum diff))}))

;; ============================================================================
;; in-transit-balance
;; ============================================================================

(defn in-transit-balance
  "The quantity currently in transit — `Σ :kontor.inventory-transfer/quantity`
   over `:status :in-transit` transfer rows. This is the period-close
   cutoff exposure (research note 36 §8): stock that has left a source
   bucket but not yet landed at a destination. Optionally scoped to
   transfers OUT OF `:from-facility` and/or INTO `:to-facility`
   (codes or eids). Returns a bigdec."
  ([db] (in-transit-balance db {}))
  ([db {:keys [from-facility to-facility]}]
   (let [ff (when from-facility (inv/resolve-facility db from-facility))
         tf (when to-facility (inv/resolve-facility db to-facility))
         rows (d/q '[:find ?t ?qty
                     :where
                     [?t :kontor.inventory-transfer/status :in-transit]
                     [?t :kontor.inventory-transfer/quantity ?qty]]
                   db)]
     (->> rows
          (filter (fn [[t _]]
                    (let [tr (d/pull db [{:kontor.inventory-transfer/from-facility [:db/id]}
                                         {:kontor.inventory-transfer/to-facility [:db/id]}]
                                     t)]
                      (and (or (nil? ff)
                               (= ff (:db/id (:kontor.inventory-transfer/from-facility tr))))
                           (or (nil? tf)
                               (= tf (:db/id (:kontor.inventory-transfer/to-facility tr))))))))
          (reduce (fn [^BigDecimal acc [_ qty]] (.add acc qty)) 0M)))))
