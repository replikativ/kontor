(ns kontor.resource.validate
  "Validation for conserved resource transfers (ADR-171).

   Resource vectors reuse Kontor transactions and postings. This namespace
   adds only the affine control invariant: a resource transfer has exactly one
   source and destination, is balanced per commodity in the resource ledger,
   follows the account-kind topology, and cannot leave a wallet negative."
  (:require [datahike.api :as d]
            [kontor.money :as money]))

(def resource-ledger-code "resource")

(defn- account-kind [db account]
  (d/q '[:find ?kind . :in $ ?a
         :where [?a :kontor.resource-account/kind ?kind]]
       db account))

(defn- resource-postings
  [db tx]
  (mapv (fn [[p account commodity amount ledger-code]]
          {:posting p :account account :commodity commodity
           :amount amount :ledger-code ledger-code
           :posted-at (:kontor.posting/posted-at
                       (d/pull db [:kontor.posting/posted-at] p))})
        (d/q '[:find ?p ?a ?c ?amount ?ledger-code
               :in $ ?tx
               :where
               [?p :kontor.posting/transaction ?tx]
               [?p :kontor.posting/account ?a]
               [?p :kontor.posting/commodity ?c]
               [?p :kontor.posting/amount ?amount]
               [?p :kontor.posting/ledger ?ledger]
               [?ledger :kontor.ledger/code ?ledger-code]]
             db tx)))

(defn- resource-ledger-posting?
  [db posting]
  (= resource-ledger-code
     (d/q '[:find ?code .
            :in $ ?posting
            :where
            [?posting :kontor.posting/ledger ?ledger]
            [?ledger :kontor.ledger/code ?code]]
          db posting)))

(defn unreceipted-posting-violations
  "Resource-ledger postings added without a resource-transfer receipt.

   Without this closed boundary, a caller could issue authority using an
   ordinary balanced Kontor transaction and bypass the transfer topology."
  [{:keys [db-after tx-data]}]
  (vec
   (for [datom tx-data
         :when (and (:added datom)
                    (= :kontor.posting/transaction (:a datom))
                    (resource-ledger-posting? db-after (:e datom)))
         :let [tx (:v datom)]
         :when (nil? (d/q '[:find ?id .
                            :in $ ?tx
                            :where [?tx :kontor.resource-transfer/id ?id]]
                          db-after tx))]
     {:posting (:e datom) :transaction tx})))

(defn- touched-resource-transfers
  [{:keys [db-after tx-data]}]
  (into #{}
        (keep (fn [datom]
                (cond
                  (and (:added datom)
                       (= :kontor.resource-transfer/id (:a datom)))
                  (:e datom)

                  (and (:added datom)
                       (= :kontor.posting/transaction (:a datom))
                       (d/q '[:find ?id . :in $ ?tx
                              :where [?tx :kontor.resource-transfer/id ?id]]
                            db-after (:v datom)))
                  (:v datom))))
        tx-data))

(defn- transfer-shape-violation
  [db tx]
  (let [{:kontor.resource-transfer/keys [id kind source destination]
         :kontor.transaction/keys [state posted-at journal]}
        (d/pull db [:kontor.resource-transfer/id
                    :kontor.resource-transfer/kind
                    :kontor.resource-transfer/source
                    :kontor.resource-transfer/destination
                    :kontor.transaction/state
                    :kontor.transaction/posted-at
                    {:kontor.transaction/journal [:kontor.journal/code]}]
                tx)
        source      (:db/id source)
        destination (:db/id destination)
        source-kind (account-kind db source)
        dest-kind   (account-kind db destination)
        postings    (resource-postings db tx)
        by-resource (group-by :commodity postings)
        topology-ok? (case kind
                       :mint    (and (= :source source-kind) (= :wallet dest-kind))
                       :grant   (and (= :wallet source-kind) (= :wallet dest-kind))
                       :consume (and (= :wallet source-kind) (= :sink dest-kind))
                       :return  (and (= :wallet source-kind) (= :wallet dest-kind))
                       false)
        resource-ok?
        (and (seq postings)
             (every? #(= resource-ledger-code (:ledger-code %)) postings)
             (every?
              (fn [[_ rows]]
                (let [src (filter #(= source (:account %)) rows)
                      dst (filter #(= destination (:account %)) rows)
                      other (remove #(#{source destination} (:account %)) rows)
                      src-total (reduce money/add-amount (money/zero-amount)
                                        (map :amount src))
                      dst-total (reduce money/add-amount (money/zero-amount)
                                        (map :amount dst))]
                  (and (= 1 (count src)) (= 1 (count dst)) (empty? other)
                       (money/amount-negative? src-total)
                       (money/amount-positive? dst-total)
                       (money/amount-zero?
                        (money/add-amount src-total dst-total)))))
              by-resource))]
    (when-not (and id kind source destination (not= source destination)
                   (= :posted state) posted-at
                   (= "RESOURCE" (:kontor.journal/code journal))
                   (every? :posted-at postings)
                   topology-ok? resource-ok?)
      {:transaction tx :transfer-id id :kind kind
       :state state :posted-at posted-at
       :journal (:kontor.journal/code journal)
       :source source :source-kind source-kind
       :destination destination :destination-kind dest-kind
       :postings postings})))

(defn transfer-shape-violations
  "Malformed resource transfers touched by a resolved transaction report."
  [{:keys [db-after] :as report}]
  (into [] (keep #(transfer-shape-violation db-after %))
        (touched-resource-transfers report)))

(defn- touched-wallets
  [{:keys [db-after tx-data]}]
  (into #{}
        (keep (fn [datom]
                (when (and (:added datom)
                           (= :kontor.posting/account (:a datom))
                           (= :wallet (account-kind db-after (:v datom))))
                  (:v datom))))
        tx-data))

(defn- wallet-balances
  [db wallet]
  (reduce (fn [out [_ commodity amount]]
            (update out commodity (fnil money/add-amount (money/zero-amount)) amount))
          {}
          (d/q '[:find ?p ?commodity ?amount
                 :in $ ?wallet ?ledger-code
                 :where
                 [?p :kontor.posting/account ?wallet]
                 [?p :kontor.posting/commodity ?commodity]
                 [?p :kontor.posting/amount ?amount]
                 [?p :kontor.posting/ledger ?ledger]
                 [?ledger :kontor.ledger/code ?ledger-code]
                 [?p :kontor.posting/transaction ?tx]
                 [?tx :kontor.transaction/state :posted]]
               db wallet resource-ledger-code)))

(defn overdraft-violations
  "Every touched wallet/commodity whose resolved post-state is negative."
  [{:keys [db-after] :as report}]
  (vec
   (for [wallet (touched-wallets report)
         [commodity amount] (wallet-balances db-after wallet)
         :when (money/amount-negative? amount)]
     {:account wallet :commodity commodity :balance amount})))

(defn assert-report!
  "Throw on malformed or overdrawn resource authority in a resolved report."
  [report]
  (when-let [violations (seq (unreceipted-posting-violations report))]
    (throw (ex-info "Resource-ledger posting has no transfer receipt"
                    {:type :kontor.resource/unreceipted-posting
                     :violations (vec violations)})))
  (when-let [violations (seq (transfer-shape-violations report))]
    (throw (ex-info "Invalid conserved resource transfer"
                    {:type :kontor.resource/invalid-transfer
                     :violations (vec violations)})))
  (when-let [violations (seq (overdraft-violations report))]
    (throw (ex-info "Resource account has insufficient balance"
                    {:type :kontor.resource/insufficient
                     :violations (vec violations)})))
  nil)

(defn assert-tx-data!
  "Gate-side mirror of [[assert-report!]], evaluated inside Datahike's
   serialized transactor. We must not recursively call `datahike.core/with`
   here: `db` is the writer's transient transaction view, not a persistent DB.
   Instead, resolve the transfer route against that latest view and add every
   proposed posting delta to the durable wallet balances."
  [db tx-data]
  (let [transfers (filter #(and (map? %)
                                (contains? % :kontor.resource-transfer/id))
                          tx-data)
        transfer-ids (into #{} (map :db/id) transfers)
        unreceipted
        (vec
         (for [posting tx-data
               :when (and (map? posting)
                          (contains? posting :kontor.posting/ledger)
                          (= resource-ledger-code
                             (:kontor.ledger/code
                              (d/pull db [:kontor.ledger/code]
                                      (:kontor.posting/ledger posting))))
                          (not (contains? transfer-ids
                                          (:kontor.posting/transaction posting))))]
           {:posting posting
            :transaction (:kontor.posting/transaction posting)}))]
    (when (seq unreceipted)
      (throw (ex-info "Resource-ledger posting has no transfer receipt"
                      {:type :kontor.resource/unreceipted-posting
                       :violations unreceipted})))
    (when (seq transfers)
      (let [resolve-account
            (fn [ref]
              (let [pulled (d/pull db [:db/id :kontor.resource-account/kind] ref)]
                {:id (:db/id pulled)
                 :kind (:kontor.resource-account/kind pulled)}))
            resolved
            (mapv
             (fn [{:db/keys [id]
                   :kontor.resource-transfer/keys [kind source destination]
                   :kontor.transaction/keys [state posted-at journal]
                   :as transfer}]
               (let [source      (resolve-account source)
                     destination (resolve-account destination)
                     postings    (filter #(and (map? %)
                                               (= id (:kontor.posting/transaction %)))
                                         tx-data)
                     by-resource (group-by :kontor.posting/commodity postings)
                     topology-ok? (case kind
                                    :mint    (and (= :source (:kind source))
                                                  (= :wallet (:kind destination)))
                                    :grant   (and (= :wallet (:kind source))
                                                  (= :wallet (:kind destination)))
                                    :consume (and (= :wallet (:kind source))
                                                  (= :sink (:kind destination)))
                                    :return  (and (= :wallet (:kind source))
                                                  (= :wallet (:kind destination)))
                                    false)
                     entry-ok? (and (= :posted state)
                                    posted-at
                                    (= "RESOURCE"
                                       (:kontor.journal/code
                                        (d/pull db [:kontor.journal/code] journal)))
                                    (every? :kontor.posting/posted-at postings))
                     shape-ok?
                     (and (:id source) (:id destination)
                          (not= (:id source) (:id destination))
                          topology-ok? entry-ok? (seq postings)
                          (every?
                           (fn [[_ rows]]
                             (let [src (filter #(= (:id source)
                                                   (:db/id (d/pull db [:db/id]
                                                                   (:kontor.posting/account %))))
                                               rows)
                                   dst (filter #(= (:id destination)
                                                   (:db/id (d/pull db [:db/id]
                                                                   (:kontor.posting/account %))))
                                               rows)
                                   src-total (reduce money/add-amount (money/zero-amount)
                                                     (map :kontor.posting/amount src))
                                   dst-total (reduce money/add-amount (money/zero-amount)
                                                     (map :kontor.posting/amount dst))]
                               (and (= (count rows) (+ (count src) (count dst)))
                                    (= 1 (count src)) (= 1 (count dst))
                                    (every? #(= resource-ledger-code
                                                (:kontor.ledger/code
                                                 (d/pull db [:kontor.ledger/code]
                                                         (:kontor.posting/ledger %))))
                                            rows)
                                    (money/amount-negative? src-total)
                                    (money/amount-positive? dst-total)
                                    (money/amount-zero?
                                     (money/add-amount src-total dst-total)))))
                           by-resource))]
                 (when-not shape-ok?
                   (throw (ex-info "Invalid conserved resource transfer"
                                   {:type :kontor.resource/invalid-transfer
                                    :transfer transfer})))
                 {:source source :destination destination :postings postings}))
             transfers)
            wallet-deltas
            (reduce
             (fn [out {:keys [source destination postings]}]
               (reduce
                (fn [out posting]
                  (let [account (:db/id
                                 (d/pull db [:db/id] (:kontor.posting/account posting)))
                        wallet? (or (and (= :wallet (:kind source))
                                         (= account (:id source)))
                                    (and (= :wallet (:kind destination))
                                         (= account (:id destination))))]
                    (if wallet?
                      (update-in out [account (:kontor.posting/commodity posting)]
                                 (fnil money/add-amount (money/zero-amount))
                                 (:kontor.posting/amount posting))
                      out)))
                out postings))
             {} resolved)
            violations
            (vec
             (for [[wallet deltas] wallet-deltas
                   [commodity delta] deltas
                   :let [prior (get (wallet-balances db wallet)
                                    (:db/id (d/pull db [:db/id] commodity))
                                    (money/zero-amount))
                         after (money/add-amount prior delta)]
                   :when (money/amount-negative? after)]
               {:account wallet :commodity commodity
                :balance after :prior prior :delta delta}))]
        (when (seq violations)
          (throw (ex-info "Resource account has insufficient balance"
                          {:type :kontor.resource/insufficient
                           :violations violations}))))))
  nil)
