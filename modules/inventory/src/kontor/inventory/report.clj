(ns kontor.inventory.report
  "kontor-inventory reconciliation reports — ADR-060.

   Two helpers, both thin queries over facts that already exist —
   the append-only `:inventory-detail` ledger *is* the roll-forward,
   and `:valuation-layer` + the GL share one fact log:

   - `inventory-roll-forward` — opening + Σ movements = closing, the
     movements bucketed by `:inventory-detail/source-kind`. The
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
                       [?d :inventory-detail/inventory-item ?item]
                       [?d :inventory-detail/effective-date ?ed]
                       [?d :inventory-detail/qoh-diff ?diff]
                       [(get-else $ ?d :inventory-detail/source-kind :unspecified) ?sk]]
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
             to both sides).
   Returns {:book eid :subledger bigdec :gl bigdec :difference bigdec
            :ok? boolean}."
  [conn {:keys [book inventory-account commodity as-of-valid as-of-tx]}]
  (when-not book               (throw (ex-info ":book required" {})))
  (when-not inventory-account  (throw (ex-info ":inventory-account required" {})))
  (when-not commodity          (throw (ex-info ":commodity required" {})))
  (let [db       (d/db conn)
        book-eid (valuation/resolve-book db book)
        opts     (cond-> {}
                   as-of-valid (assoc :as-of-valid as-of-valid)
                   as-of-tx    (assoc :as-of-tx as-of-tx))
        items    (d/q '[:find [?item ...]
                        :in $ ?b
                        :where
                        [?l :valuation-layer/book ?b]
                        [?l :valuation-layer/item ?item]]
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
