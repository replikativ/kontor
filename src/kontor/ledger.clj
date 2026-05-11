(ns kontor.ledger
  "Ledger view: ordered postings against an account, bitemporal-aware.

   Same axes as balance.clj (ADR-008). Each result row carries the
   posting eid, transaction eid, valid-from, posted-at, narration,
   amount, commodity, and the partner (if any). Default ordering is
   by `:posting/valid-from` ascending; pass `:order :desc` for
   reverse-chronological."
  (:require [datahike.api :as d]
            [kontor.money :as money])
  (:import [java.util Date]))

(defn- now ^Date [] (Date.))

(defn- before-or-eq? [^Date a ^Date b] (<= (.compareTo a b) 0))

(def ^:private default-included-states #{:posted})

(defn postings-against
  "Return ordered postings against `account-eid` matching the given
   bitemporal window. Each entry is

     {:posting     <eid>
      :transaction <eid>
      :valid-from  Date
      :amount      Money
      :commodity   <eid>
      :narration   string-or-nil
      :partner     <eid-or-nil>
      :tx-state    keyword}

   Options (all optional):
     :as-of-valid    Date — default now
     :as-of-tx       Date — default now
     :include-states set  — default #{:posted}
     :order          :asc | :desc — default :asc on :valid-from"
  ([conn account-eid] (postings-against conn account-eid {}))
  ([conn account-eid {:keys [as-of-valid as-of-tx include-states order]
                      :or   {include-states default-included-states
                             order :asc}}]
   (let [as-of-valid (or as-of-valid (now))
         as-of-tx    (or as-of-tx (now))
         db          (d/db conn)
         tx-snap     (if as-of-tx (d/as-of db as-of-tx) db)
         rows
         (->> (d/q '[:find [?p ...]
                     :in $ ?account
                     :where [?p :posting/account ?account]]
                   tx-snap account-eid)
              (mapv (fn [p]
                      (let [pulled (d/pull tx-snap
                                           [:db/id
                                            :posting/amount
                                            :posting/commodity
                                            :posting/valid-from
                                            :posting/transaction
                                            :posting/narration
                                            :posting/partner]
                                           p)
                            tx-id (-> pulled :posting/transaction :db/id)
                            tx-state (:transaction/state
                                      (d/pull tx-snap [:transaction/state] tx-id))
                            tx-narr  (:transaction/narration
                                      (d/pull tx-snap [:transaction/narration] tx-id))]
                        {:posting     (:db/id pulled)
                         :transaction tx-id
                         :valid-from  (:posting/valid-from pulled)
                         :amount      (money/posting->money pulled)
                         :commodity   (let [c (:posting/commodity pulled)]
                                        (if (map? c) (:db/id c) c))
                         :narration   (or (:posting/narration pulled) tx-narr)
                         :partner     (some-> (:posting/partner pulled) :db/id)
                         :tx-state    tx-state}))))
         filtered
         (filter (fn [{:keys [valid-from tx-state]}]
                   (and (some? valid-from)
                        (before-or-eq? valid-from as-of-valid)
                        (contains? include-states tx-state)))
                 rows)
         comparator (case order
                      :asc  #(compare (:valid-from %1) (:valid-from %2))
                      :desc #(compare (:valid-from %2) (:valid-from %1)))]
     (vec (sort comparator filtered)))))

(defn running-balance
  "Like `postings-against` but each row also carries `:running` —
   the cumulative balance after this posting in its commodity. When
   the account is multi-currency the running balance is per-commodity
   independently. Returns the same shape as postings-against with the
   extra `:running` key (a Money)."
  [conn account-eid & [opts]]
  (let [opts-with-asc (assoc (or opts {}) :order :asc)
        rows (postings-against conn account-eid opts-with-asc)
        ;; Per-commodity running totals
        running (atom {})]
    (mapv (fn [{:keys [amount commodity] :as row}]
            (let [prev (get @running commodity (money/zero commodity))
                  next (money/add prev amount)]
              (swap! running assoc commodity next)
              (assoc row :running next)))
          rows)))
