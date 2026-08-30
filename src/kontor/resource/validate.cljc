(ns kontor.resource.validate
  "Validation for conserved resource transfers (ADR-171).

   Resource vectors reuse Kontor transactions and postings. This namespace
   adds only the affine control invariant: a resource transfer has exactly one
   source and destination, is balanced per commodity in the resource ledger,
   follows the account-kind topology, and cannot leave a wallet negative."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [kontor.money :as money]))

(def resource-ledger-code "resource")

(def ^:private whole-entity-destructive-ops
  #{:db/purge :db.purge/entity :db/retractEntity :db.fn/retractEntity})

(def ^:private attribute-destructive-ops
  #{:db.purge/attribute :db/retract :db/retractAttribute
    :db.fn/retractAttribute})

(defn- resolve-eid
  [db ref]
  ;; Negative numbers and strings are tx-local tempids, never existing
  ;; entities. Avoid asking Datahike to resolve them (which also emits a noisy
  ;; substrate error before returning nil).
  (when (or (and (number? ref) (pos? ref))
            (vector? ref)
            (keyword? ref))
    (try (:db/id (d/entity db ref))
         (catch #?(:clj Exception :cljs :default) _ nil))))

(defn- resource-protected-eids
  "Entities whose established facts constitute conserved-resource history.

   This includes the resource topology, receipts and their postings, plus the
   journal, ledger and commodity definitions those receipts depend on. New
   facts may be appended, but existing facts cannot disappear or be replaced."
  [db]
  (let [accounts (into #{}
                       (d/q '[:find [?e ...]
                              :where [?e :kontor.resource-account/id]] db))]
    ;; The aggregate schema alone does not opt an ordinary book into resource
    ;; governance. Only an installed resource account activates the reserved
    ;; journal/ledger protection.
    (if (empty? accounts)
      #{}
      (into accounts
            cat
            [(d/q '[:find [?e ...]
                    :where [?e :kontor.resource-transfer/id]] db)
             (d/q '[:find [?p ...]
                    :where
                    [?tx :kontor.resource-transfer/id]
                    [?p :kontor.posting/transaction ?tx]] db)
             (d/q '[:find [?ledger ...]
                    :in $ ?code
                    :where [?ledger :kontor.ledger/code ?code]]
                  db resource-ledger-code)
             (d/q '[:find [?journal ...]
                    :where [?journal :kontor.journal/code "RESOURCE"]] db)
             (d/q '[:find [?commodity ...]
                    :where
                    [?tx :kontor.resource-transfer/id]
                    [?p :kontor.posting/transaction ?tx]
                    [?p :kontor.posting/commodity ?commodity]] db)]))))

(defn- resource-facts
  [db eids]
  (if (seq eids)
    (into #{}
          (d/q '[:find ?e ?a ?v ?tx
                 :in $ [?e ...]
                 :where [?e ?a ?v ?tx]]
               db eids))
    #{}))

(defn immutable-history-violations
  "Established resource facts present before but absent after a write.

   Datahike's resolved report for `:db/purge` does not expose either the
   original operation or the purged datoms. Comparing facts—including their
   originating transaction id—is therefore the authoritative writer-side
   backstop and also catches purge-and-recreate attempts."
  [{:keys [db-before db-after]}]
  (let [eids (resource-protected-eids db-before)]
    (into []
          (map (fn [[e a v tx]] {:entity e :attribute a :value v :tx tx}))
          (set/difference (resource-facts db-before eids)
                          (resource-facts db-after eids)))))

(defn- unique-identity-attrs
  [db]
  (into #{}
        (keep (fn [[attr spec]]
                (when (and (keyword? attr) (map? spec)
                           (= :db.unique/identity (:db/unique spec)))
                  attr)))
        (d/schema db)))

(defn- effective-target-eid
  [db identity-attrs entity-map]
  (or (resolve-eid db (:db/id entity-map))
      (some (fn [[attr value]]
              (when (and (not= :db/id attr) (identity-attrs attr))
                (resolve-eid db [attr value])))
            entity-map)))

(defn- ref-attr?
  [db attr]
  (= :db.type/ref (:db/valueType (get (d/schema db) attr))))

(defn- comparable-value
  [db attr value]
  (if (and (some? value) (ref-attr? db attr))
    (resolve-eid db value)
    value))

(defn- current-values
  [db eid attr]
  (into #{}
        (map first)
        (d/q '[:find ?value
               :in $ ?entity ?attribute
               :where [?entity ?attribute ?value]]
             db eid attr)))

(defn immutable-tx-data-violations
  "Original write forms that would remove or replace established resource
   history. This is the serialized gate-side counterpart to
   [[immutable-history-violations]]."
  [db tx-data]
  (let [protected (resource-protected-eids db)
        identities (unique-identity-attrs db)
        protected? #(contains? protected (resolve-eid db %))
        referenced-by-protected?
        (fn [target]
          (when-let [target (resolve-eid db target)]
            (and (seq protected)
                 (boolean
                  (d/q '[:find ?source .
                         :in $ [?source ...] ?target
                         :where [?source _ ?target]]
                       db protected target)))))]
    (into []
          (mapcat
           (fn [form]
             (cond
               (and (vector? form)
                    (whole-entity-destructive-ops (first form))
                    (or (protected? (second form))
                        (referenced-by-protected? (second form))))
               [{:tx form :entity (resolve-eid db (second form))
                 :operation (first form)}]

               (and (vector? form)
                    (attribute-destructive-ops (first form))
                    (protected? (second form)))
               [{:tx form :entity (resolve-eid db (second form))
                 :operation (first form) :attribute (nth form 2 nil)}]

               (and (vector? form) (= :db/add (first form))
                    (protected? (second form)))
               (let [[_ target attr value] form
                     eid (resolve-eid db target)
                     current (current-values db eid attr)
                     value (comparable-value db attr value)]
                 (when (and (seq current) (not (contains? current value)))
                   [{:tx form :entity eid :attribute attr
                     :old current :new value}]))

               (and (vector? form) (= :db/cas (first form))
                    (protected? (second form)))
               (let [[_ target attr old new] form
                     eid (resolve-eid db target)
                     old (comparable-value db attr old)
                     new (comparable-value db attr new)]
                 (when (not= old new)
                   [{:tx form :entity eid :attribute attr
                     :old old :new new}]))

               (map? form)
               (when-let [eid (effective-target-eid db identities form)]
                 (when (contains? protected eid)
                   (keep (fn [[attr value]]
                           (when (not= :db/id attr)
                             (let [current (current-values db eid attr)
                                   value (comparable-value db attr value)]
                               (when (and (seq current)
                                          (not (contains? current value)))
                                 {:tx form :entity eid :attribute attr
                                  :old current :new value}))))
                         form)))

               :else nil))
           tx-data))))

(defn- assert-immutable-tx-data!
  [db tx-data]
  (when-let [violations (seq (immutable-tx-data-violations db tx-data))]
    (throw (ex-info "Conserved resource history is immutable"
                    {:type :kontor.resource/immutable-history
                     :violations (vec violations)
                     :remediation "Record a compensating resource transfer."}))))

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
  (when-let [violations (seq (immutable-history-violations report))]
    (throw (ex-info "Conserved resource history is immutable"
                    {:type :kontor.resource/immutable-history
                     :violations (vec violations)
                     :remediation "Record a compensating resource transfer."})))
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
  (assert-immutable-tx-data! db tx-data)
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
            resolve-posting
            (fn [posting]
              (let [account (resolve-eid db (:kontor.posting/account posting))
                    commodity (resolve-eid db (:kontor.posting/commodity posting))
                    ledger (resolve-eid db (:kontor.posting/ledger posting))]
                (assoc posting
                       ::account account
                       ::commodity commodity
                       ::ledger-code
                       (:kontor.ledger/code
                        (when ledger
                          (d/pull db [:kontor.ledger/code] ledger))))))
            resolved
            (mapv
             (fn [{:db/keys [id]
                   :kontor.resource-transfer/keys [kind source destination]
                   :kontor.transaction/keys [state posted-at journal]
                   :as transfer}]
               (let [source      (resolve-account source)
                     destination (resolve-account destination)
                     postings    (into []
                                       (comp (filter #(and (map? %)
                                                           (= id (:kontor.posting/transaction %))))
                                             (map resolve-posting))
                                       tx-data)
                     by-resource (group-by ::commodity postings)
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
                          (every? ::commodity postings)
                          (every?
                           (fn [[_ rows]]
                             (let [src (filter #(= (:id source) (::account %)) rows)
                                   dst (filter #(= (:id destination) (::account %)) rows)
                                   src-total (reduce money/add-amount (money/zero-amount)
                                                     (map :kontor.posting/amount src))
                                   dst-total (reduce money/add-amount (money/zero-amount)
                                                     (map :kontor.posting/amount dst))]
                               (and (= (count rows) (+ (count src) (count dst)))
                                    (= 1 (count src)) (= 1 (count dst))
                                    (every? #(= resource-ledger-code (::ledger-code %)) rows)
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
                  (let [account (::account posting)
                        wallet? (or (and (= :wallet (:kind source))
                                         (= account (:id source)))
                                    (and (= :wallet (:kind destination))
                                         (= account (:id destination))))]
                    (if wallet?
                      (update-in out [account (::commodity posting)]
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
                                    commodity
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
