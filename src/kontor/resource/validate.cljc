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

(def ^:private history-purge-ops
  #{:db/purge :db.purge/entity :db.purge/attribute
    :db.history.purge/before})

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
               (if (get-in db [:config :keep-history?])
                 (d/history db)
                 db)
               eids))
    #{}))

(defn- entity-has-attr?
  [db eid attr]
  (boolean
   (d/q '[:find ?e .
          :in $ ?e ?attr
          :where [?e ?attr _]]
        db eid attr)))

(defn- resource-installed?
  [db]
  (boolean
   (d/q '[:find ?e .
          :where [?e :kontor.resource-account/id]] db)))

(defn- resource-protected-eid?
  "Delta-local membership test for one retracted entity.

   Purges use [[resource-protected-eids]] plus a full history comparison. For
   ordinary reports this predicate keeps cost proportional to the retracted
   delta instead of enumerating all prior transfers and postings."
  [db eid]
  (or (entity-has-attr? db eid :kontor.resource-account/id)
      (entity-has-attr? db eid :kontor.resource-transfer/id)
      (boolean
       (d/q '[:find ?posting .
              :in $ ?posting
              :where
              [?posting :kontor.posting/transaction ?tx]
              [?tx :kontor.resource-transfer/id]] db eid))
      (boolean
       (d/q '[:find ?posting .
              :in $ ?referenced
              :where
              [?posting :kontor.posting/transaction ?tx]
              [?tx :kontor.resource-transfer/id]
              (or [?posting :kontor.posting/commodity ?referenced]
                  [?posting :kontor.posting/ledger ?referenced])]
            db eid))
      (boolean
       (d/q '[:find ?tx .
              :in $ ?journal
              :where
              [?tx :kontor.resource-transfer/id]
              [?tx :kontor.transaction/journal ?journal]] db eid))
      (and (resource-installed? db)
           (or (= resource-ledger-code
                  (d/q '[:find ?code .
                         :in $ ?e
                         :where [?e :kontor.ledger/code ?code]] db eid))
               (= "RESOURCE"
                  (d/q '[:find ?code .
                         :in $ ?e
                         :where [?e :kontor.journal/code ?code]] db eid))))))

(defn immutable-history-violations
  "Established resource facts present before but absent after a write.

   Datahike's resolved report for `:db/purge` does not expose either the
   original operation or the purged datoms. Comparing facts—including their
   originating transaction id—is therefore the authoritative writer-side
   backstop and also catches purge-and-recreate attempts."
  [{:keys [db-before db-after tx-data tx-ops]}]
  (if (or (nil? tx-ops) (some history-purge-ops tx-ops))
    ;; Reports from older Datahike releases have no operation provenance, so
    ;; retain the conservative full audit for compatibility. With :tx-ops this
    ;; path is reserved for operations whose history loss has no datom delta.
    (let [eids (resource-protected-eids db-before)]
      (into []
            (map (fn [[e a v tx]] {:entity e :attribute a :value v :tx tx}))
            (set/difference (resource-facts db-before eids)
                            (resource-facts db-after eids))))
    (into []
          (comp (filter #(false? (:added %)))
                (filter #(resource-protected-eid? db-before (:e %)))
                (map (fn [datom]
                       {:entity (:e datom) :attribute (:a datom)
                        :value (:v datom) :tx (:tx datom)})))
          tx-data)))

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

(defn- cardinality-many?
  [db attr]
  (= :db.cardinality/many (:db/cardinality (get (d/schema db) attr))))

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
               (and (sequential? form)
                    (whole-entity-destructive-ops (first form))
                    (or (protected? (second form))
                        (referenced-by-protected? (second form))))
               [{:tx form :entity (resolve-eid db (second form))
                 :operation (first form)}]

               (and (sequential? form)
                    (attribute-destructive-ops (first form))
                    (protected? (second form)))
               [{:tx form :entity (resolve-eid db (second form))
                 :operation (first form) :attribute (nth form 2 nil)}]

               (and (sequential? form) (= :db/add (first form))
                    (protected? (second form)))
               (let [[_ target attr value] form
                     eid (resolve-eid db target)
                     current (current-values db eid attr)
                     value (comparable-value db attr value)]
                 (when (and (not (cardinality-many? db attr))
                            (seq current)
                            (not (contains? current value)))
                   [{:tx form :entity eid :attribute attr
                     :old current :new value}]))

               (and (sequential? form) (= :db/cas (first form))
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
                             (let [many? (cardinality-many? db attr)
                                   current (current-values db eid attr)
                                   values (if (and many? (sequential? value))
                                            value
                                            [value])]
                               (when (and (or (not many?) (some nil? values))
                                          (seq current)
                                          (some #(not (contains? current
                                                                 (comparable-value db attr %)))
                                                values))
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

(defn- identity-refs
  [identity-attrs entity]
  (into []
        (keep (fn [[attr value]]
                (when (and (identity-attrs attr) (some? value))
                  [attr value])))
        entity))

(defn- identity-ref
  [identity-attrs entity]
  (first (identity-refs identity-attrs entity)))

(defn- normalize-tx-entities
  "Project entity maps and `:db/add` forms into a small assertion graph.

   Keys remain transaction-local identities (tempids or lookup refs) when an
   eid does not exist yet. `:aliases` makes an explicit tempid and a unique
   identity asserted on the same entity interchangeable. This is intentionally
   not a general Datahike transactor: resource validation needs only positive
   assertions, while destructive forms are handled by the immutable gate."
  [db tx-data]
  (let [identities (unique-identity-attrs db)
        entries
        (keep-indexed
         (fn [index form]
           (cond
             (map? form)
             (let [identity (identity-ref identities form)
                   identity-aliases (identity-refs identities form)
                   key (or (resolve-eid db (:db/id form))
                           (some-> identity (resolve-eid db))
                           (:db/id form)
                           identity
                           [::anonymous index])]
               {:key key :entity (dissoc form :db/id)
                :aliases (cond-> (into #{key} identity-aliases)
                           (:db/id form) (conj (:db/id form)))})

             (and (sequential? form) (= :db/add (first form)))
             (let [[_ target attr value] form
                   key (or (resolve-eid db target) target)]
               {:key key :entity {attr value} :aliases (hash-set key target)})

             :else nil))
         tx-data)
        entities (reduce (fn [out {:keys [key entity]}]
                           (update out key merge entity))
                         {} entries)
        aliases (reduce (fn [out {:keys [key aliases]}]
                          (reduce #(assoc %1 %2 key) out aliases))
                        {} entries)
        ;; List-form identity assertions are only visible after the first
        ;; merge, so add their lookup refs as a second-pass alias.
        aliases (reduce-kv
                 (fn [out key entity]
                   (reduce #(assoc %1 %2 key)
                           out (identity-refs identities entity)))
                 aliases entities)]
    {:entities entities :aliases aliases}))

(defn- graph-key
  [db aliases ref]
  (or (resolve-eid db ref) (get aliases ref) ref))

(defn- graph-attr
  [db entities aliases ref attr]
  (let [key (graph-key db aliases ref)]
    (or (get-in entities [key attr])
        (when (number? key)
          (get (d/pull db [attr] key) attr)))))

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
  (let [{:keys [entities aliases]} (normalize-tx-entities db tx-data)
        transfers (into []
                        (keep (fn [[key entity]]
                                (when (contains? entity :kontor.resource-transfer/id)
                                  (assoc entity ::key key))))
                        entities)
        postings (into []
                       (keep (fn [[key entity]]
                               (when (or (contains? entity :kontor.posting/transaction)
                                         (contains? entity :kontor.posting/ledger))
                                 (assoc entity ::key key))))
                       entities)
        transfer-keys (into #{} (map ::key) transfers)
        unreceipted
        (vec
         (for [posting postings
               :let [ledger (:kontor.posting/ledger posting)
                     transaction (:kontor.posting/transaction posting)]
               :when (and (= resource-ledger-code
                             (graph-attr db entities aliases ledger
                                         :kontor.ledger/code))
                          (not (contains? transfer-keys
                                          (graph-key db aliases transaction))))]
           {:posting (::key posting) :transaction transaction}))]
    (when (seq unreceipted)
      (throw (ex-info "Resource-ledger posting has no transfer receipt"
                      {:type :kontor.resource/unreceipted-posting
                       :violations unreceipted})))
    (when (seq transfers)
      (let [resolve-account
            (fn [ref]
              {:id (graph-key db aliases ref)
               :kind (graph-attr db entities aliases ref
                                 :kontor.resource-account/kind)})
            resolve-posting
            (fn [posting]
              (let [account (graph-key db aliases (:kontor.posting/account posting))
                    commodity (graph-key db aliases (:kontor.posting/commodity posting))
                    ledger (:kontor.posting/ledger posting)]
                (assoc posting
                       ::account account
                       ::commodity commodity
                       ::ledger-code
                       (graph-attr db entities aliases ledger
                                   :kontor.ledger/code))))
            resolved
            (mapv
             (fn [{:kontor.resource-transfer/keys [kind source destination]
                   :kontor.transaction/keys [state posted-at journal]
                   :as transfer}]
               (let [id          (::key transfer)
                     source      (resolve-account source)
                     destination (resolve-account destination)
                     postings    (into []
                                       (comp (filter #(= id (graph-key db aliases
                                                                       (:kontor.posting/transaction %))))
                                             (map resolve-posting))
                                       postings)
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
                                       (graph-attr db entities aliases journal
                                                   :kontor.journal/code))
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
                                    :transfer transfer
                                    :source source
                                    :destination destination
                                    :postings postings
                                    :topology-ok? topology-ok?
                                    :entry-ok? entry-ok?})))
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
                   :let [prior (get (if (number? wallet)
                                      (wallet-balances db wallet)
                                      {})
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
