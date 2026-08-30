(ns kontor.resource
  "Affine resource vectors over Kontor's existing double-entry algebra.

   A vector `{commodity amount}` moves between resource accounts as one
   balanced, sealed transaction in the dedicated resource ledger. Wallets are
   constrained non-negative by both the validation gate and the writer-side
   governor; allocating to a child therefore consumes the parent's authority
   instead of copying a budget ceiling. ADR-171."
  (:require [datahike.api :as d]
            [kontor.actor :as actor]
            [kontor.gate :as gate]
            [kontor.money :as money]
            [kontor.posting.build :as posting-build]
            [kontor.resource.validate :as validate])
  (:import [java.util Date UUID]))

(def resource-ledger
  "Lookup ref for the isolated resource-control ledger."
  [:kontor.ledger/code validate/resource-ledger-code])

(def resource-journal [:kontor.journal/code "RESOURCE"])

(def source-id (UUID/fromString "00000000-0000-0000-0000-000000000171"))
(def sink-id   (UUID/fromString "00000000-0000-0000-0000-000000000172"))

(defn account-ref [id] [:kontor.resource-account/id id])

(defn- lookup-eid
  "Resolve a lookup ref without Datahike's strict missing-entity pull error."
  [db [attr value]]
  (d/q '[:find ?e .
         :in $ ?attr ?value
         :where [?e ?attr ?value]]
       db attr value))

(def source-account (account-ref source-id))
(def sink-account   (account-ref sink-id))

(def default-seeds
  [{:kontor.ledger/code validate/resource-ledger-code
    :kontor.ledger/name "Conserved resources"
    :kontor.ledger/type :budget
    :kontor.ledger/framework :resource
    :kontor.ledger/active true}
   {:kontor.journal/code "RESOURCE"
    :kontor.journal/name "Resource authority"
    :kontor.journal/type :resource
    :kontor.journal/active true}
   {:kontor.account/path "Resources:Source"
    :kontor.account/name "Resource source"
    :kontor.account/type :equity
    :kontor.account/active true
    :kontor.resource-account/id source-id
    :kontor.resource-account/kind :source}
   {:kontor.account/path "Resources:Consumed"
    :kontor.account/name "Consumed resources"
    :kontor.account/type :expense
    :kontor.account/active true
    :kontor.resource-account/id sink-id
    :kontor.resource-account/kind :sink}])

(defn install-defaults!
  "Install the resource ledger, journal, and balanced source/sink accounts."
  [conn]
  (d/transact conn default-seeds)
  conn)

(defn install-unit!
  "Idempotently install a resource commodity. Amounts remain exact decimals;
   `precision` documents the unit's accepted display precision."
  [conn {:keys [symbol name precision] :or {precision 0}}]
  (when-not (and (string? symbol) (seq symbol))
    (throw (ex-info "Resource unit requires a non-empty string :symbol"
                    {:type ::invalid-unit :symbol symbol})))
  (gate/transact-with-validation
   conn
   [{:kontor.commodity/symbol symbol
     :kontor.commodity/name (or name symbol)
     :kontor.commodity/precision (long precision)}])
  [:kontor.commodity/symbol symbol])

(defn open-account-tx-data
  "Pure tx-data for a new resource wallet. `:owner` may reference any entity
   in the cohabiting consumer schema (for Dvergr, normally a Room or Run)."
  [{:keys [id owner name]}]
  (when-not (instance? UUID id)
    (throw (ex-info "Resource account :id must be a UUID"
                    {:type ::invalid-account :id id})))
  [(cond-> {:kontor.account/path (str "Resources:Wallet:" id)
            :kontor.account/name (or name (str id))
            :kontor.account/type :asset
            :kontor.account/active true
            :kontor.resource-account/id id
            :kontor.resource-account/kind :wallet}
     owner (assoc :kontor.resource-account/owner owner))])

(defn open-account!
  "Open (or idempotently re-open) a zero-balance wallet."
  [conn {:keys [id] :as opts}]
  (if-let [existing-eid (lookup-eid @conn (account-ref id))]
    (let [existing (d/pull @conn [:kontor.resource-account/id
                                  :kontor.resource-account/owner]
                           existing-eid)
          requested-owner (:owner opts)
          existing-owner (some-> existing :kontor.resource-account/owner :db/id)
          requested-owner-eid (when requested-owner
                                (lookup-eid @conn requested-owner))]
      (when (and requested-owner (nil? requested-owner-eid))
        (throw (ex-info "Resource account owner does not exist"
                        {:type ::owner-not-found :id id :owner requested-owner})))
      (when (and requested-owner (not= existing-owner requested-owner-eid))
        (throw (ex-info "Resource account idempotency conflict"
                        {:type ::idempotency-conflict :id id})))
      (account-ref id))
    (try
      (gate/transact-with-validation conn (open-account-tx-data opts))
      (account-ref id)
      (catch Throwable error
        ;; A concurrent opener may win the unique-id race after our read.
        ;; Re-enter the idempotency check only when that account now exists.
        (if (lookup-eid @conn (account-ref id))
          (open-account! conn opts)
          (throw error))))))

(defn- commodity-ref [resource]
  (cond
    (string? resource)  [:kontor.commodity/symbol resource]
    (keyword? resource) [:kontor.commodity/symbol (name resource)]
    :else resource))

(defn- canonical-vector [resources]
  (when-not (and (map? resources) (seq resources))
    (throw (ex-info "Resource vector must be a non-empty map"
                    {:type ::invalid-vector :resources resources})))
  (let [coordinates
        (into []
              (map (fn [[resource amount]]
                     (let [amount (money/->amount amount)]
                       (when-not (money/amount-positive? amount)
                         (throw (ex-info "Resource vector amounts must be strictly positive"
                                         {:type ::invalid-vector
                                          :resource resource :amount amount})))
                       [(commodity-ref resource) amount])))
              resources)]
    (when-not (= (count coordinates) (count (into #{} (map first) coordinates)))
      (throw (ex-info "Resource vector has a duplicate canonical coordinate"
                      {:type ::invalid-vector :resources resources})))
    coordinates))

(defn transfer-tx-data
  "Pure builder for a balanced resource-vector transfer.

   Required: `:id`, `:kind`, `:source`, `:destination`, `:resources`.
   Pass a distinct string `:tx-tempid` when composing multiple builders into
   one atomic tx-data vector.
   `:effective-date` and `:posted-at` default to now in the effectful wrapper;
   callers using this pure builder must provide `:effective-date`."
  [{:keys [id kind source destination resources effective-date posted-at actor
           tx-tempid]}]
  (when-not (instance? UUID id)
    (throw (ex-info "Resource transfer :id must be a UUID"
                    {:type ::invalid-transfer :id id})))
  (when-not (#{:mint :grant :consume :return} kind)
    (throw (ex-info "Unknown resource transfer :kind"
                    {:type ::invalid-transfer :kind kind})))
  (when (or (nil? source) (nil? destination) (= source destination))
    (throw (ex-info "Resource transfer needs distinct source and destination"
                    {:type ::invalid-transfer :source source :destination destination})))
  (when-not effective-date
    (throw (ex-info "Resource transfer requires :effective-date"
                    {:type ::invalid-transfer})))
  (let [resources (canonical-vector resources)
        postings (mapcat
                  (fn [[commodity amount]]
                    [{:kontor.posting/account destination
                      :kontor.posting/amount amount
                      :kontor.posting/commodity commodity
                      :kontor.posting/ledger resource-ledger}
                     {:kontor.posting/account source
                      :kontor.posting/amount (money/negate-amount amount)
                      :kontor.posting/commodity commodity
                      :kontor.posting/ledger resource-ledger}])
                  resources)]
    (posting-build/post-transaction-tx-data
     {:tx-tempid tx-tempid
      :transaction {:kontor.transaction/external-id (str "kontor-resource|" id)
                    :kontor.transaction/journal resource-journal
                    :kontor.transaction/effective-date effective-date
                    :kontor.transaction/narration (str "Resource " (name kind))
                    :kontor.resource-transfer/id id
                    :kontor.resource-transfer/kind kind
                    :kontor.resource-transfer/source source
                    :kontor.resource-transfer/destination destination}
      :postings (vec postings)}
     (cond-> {:posted-at (or posted-at effective-date)} actor (assoc :actor actor)))))

(defn- symbol-of [db commodity]
  (:kontor.commodity/symbol
   (d/pull db [:kontor.commodity/symbol] commodity)))

(defn balance
  "Current conserved vector at `account`, returned as `{symbol BigDecimal}`.
   Zero coordinates are omitted. This is control-state, so it intentionally
   reads all posted resource-ledger history rather than a business valid-time
   slice."
  [conn account]
  (let [db @conn
        account-eid (lookup-eid db account)]
    (when-not account-eid
      (throw (ex-info "Resource account not found"
                      {:type ::account-not-found :account account})))
    (reduce
     (fn [out [_ commodity amount]]
       (let [symbol (symbol-of db commodity)
             total (money/add-amount (get out symbol (money/zero-amount)) amount)]
         (if (money/amount-zero? total) (dissoc out symbol) (assoc out symbol total))))
     {}
     (d/q '[:find ?p ?commodity ?amount
            :in $ ?account ?ledger-code
            :where
            [?p :kontor.posting/account ?account]
            [?p :kontor.posting/commodity ?commodity]
            [?p :kontor.posting/amount ?amount]
            [?p :kontor.posting/ledger ?ledger]
            [?ledger :kontor.ledger/code ?ledger-code]
            [?p :kontor.posting/transaction ?tx]
            [?tx :kontor.transaction/state :posted]]
          db account-eid validate/resource-ledger-code))))

(defn receipt
  "Durable receipt for transfer `id`, or nil."
  [conn id]
  (let [db @conn
        tx (d/q '[:find ?tx . :in $ ?id
                  :where [?tx :kontor.resource-transfer/id ?id]] db id)]
    (when tx
      (let [{:kontor.resource-transfer/keys [kind source destination]
             :kontor.transaction/keys [effective-date posted-at posted-by]}
            (d/pull db [:kontor.resource-transfer/kind
                        :kontor.resource-transfer/source
                        :kontor.resource-transfer/destination
                        :kontor.transaction/effective-date
                        :kontor.transaction/posted-at
                        :kontor.transaction/posted-by]
                    tx)
            destination (:db/id destination)
            resources
            (reduce (fn [out [_ commodity amount]]
                      (assoc out (symbol-of db commodity) amount))
                    {}
                    (d/q '[:find ?p ?commodity ?amount
                           :in $ ?tx ?destination
                           :where
                           [?p :kontor.posting/transaction ?tx]
                           [?p :kontor.posting/account ?destination]
                           [?p :kontor.posting/commodity ?commodity]
                           [?p :kontor.posting/amount ?amount]]
                         db tx destination))]
        {:id id :transaction tx :kind kind
         :source (some-> (d/pull db [:kontor.resource-account/id] (:db/id source))
                         :kontor.resource-account/id)
         :destination (some-> (d/pull db [:kontor.resource-account/id] destination)
                              :kontor.resource-account/id)
         :resources resources
         :effective-date effective-date
         :posted-at posted-at
         :actor (:db/id posted-by)}))))

(defn- requested-receipt
  [db {:keys [id kind source destination resources actor] :as spec}]
  (let [coordinates
        (mapv (fn [[resource amount]]
                [(symbol-of db (commodity-ref resource))
                 (money/->amount amount)])
              resources)]
    (when-not (= (count coordinates) (count (into #{} (map first) coordinates)))
      (throw (ex-info "Resource vector has a duplicate database coordinate"
                      {:type ::invalid-vector :resources resources})))
    (cond-> {:id id :kind kind
             :source (:kontor.resource-account/id
                      (d/pull db [:kontor.resource-account/id] source))
             :destination (:kontor.resource-account/id
                           (d/pull db [:kontor.resource-account/id] destination))
             :resources (into {} coordinates)
             ;; Omission is meaningful: a retry that drops the original actor
             ;; is not the same audited command.
             :actor (actor/resolve-actor db actor)}
      (contains? spec :effective-date)
      (assoc :effective-date (:effective-date spec))
      (contains? spec :posted-at)
      (assoc :posted-at (:posted-at spec)))))

(defn- assert-replay! [conn spec existing]
  (let [requested (requested-receipt @conn spec)]
    (when-not (= requested (select-keys existing (keys requested)))
      (throw (ex-info "Resource transfer idempotency conflict"
                      {:type ::idempotency-conflict
                       :requested requested :existing existing})))
    (assoc existing :status :duplicate)))

(defn transfer!
  "Commit one transfer through the Kontor validation gate. Replaying the same
   id and payload returns `:status :duplicate`; reusing an id for a different
   vector or route is an error."
  [conn {:keys [id] :as requested-spec}]
  (if-let [existing (receipt conn id)]
    (assert-replay! conn requested-spec existing)
    (let [now (Date.)
          spec (cond-> requested-spec
                 (nil? (:effective-date requested-spec)) (assoc :effective-date now)
                 (nil? (:posted-at requested-spec)) (assoc :posted-at now))]
      (try
        (gate/transact-with-validation conn (transfer-tx-data spec))
        (assoc (receipt conn id) :status :committed)
        (catch Throwable error
          ;; A simultaneous replay can lose the unique-id race after the
          ;; optimistic read. It is idempotent only if the durable payload is
          ;; the same requested semantic transfer. Generated timestamps are
          ;; deliberately absent from `requested-spec`, while explicit audit
          ;; fields participate in equality.
          (if-let [existing (receipt conn id)]
            (assert-replay! conn requested-spec existing)
            (throw error)))))))

(defn mint! [conn spec]
  (transfer! conn (assoc spec :kind :mint :source source-account)))

(defn allocate! [conn spec]
  (transfer! conn (assoc spec :kind :grant)))

(defn consume! [conn spec]
  (transfer! conn (assoc spec :kind :consume :destination sink-account)))

(defn return! [conn spec]
  (transfer! conn (assoc spec :kind :return)))
