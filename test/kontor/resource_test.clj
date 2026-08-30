(ns kontor.resource-test
  "Conserved resource vectors over Kontor's existing posting algebra."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.core :as dc]
            [kontor.actor :as actor]
            [kontor.core :as core]
            [kontor.gate :as gate]
            [kontor.governance :as governance]
            [kontor.resource :as resource]
            [kontor.resource.validate :as resource-validate]))

(def unit "microUSD")

(defn- setup-on [conn]
  (let [parent (random-uuid)
        child  (random-uuid)]
    (resource/install-defaults! conn)
    (resource/install-unit! conn {:symbol unit :name "Micro US dollars" :precision 0})
    (resource/open-account! conn {:id parent :name "parent"})
    (resource/open-account! conn {:id child :name "child"})
    {:conn conn :parent parent :child child}))

(defn- setup []
  (setup-on (core/create-test-db)))

(defn- maps->add-forms
  [tx-data]
  (mapcat (fn [form]
            (if (map? form)
              (let [eid (:db/id form)]
                (map (fn [[attr value]]
                       (list :db/add eid attr value))
                     (dissoc form :db/id)))
              [form]))
          tx-data))

(deftest vector-transfer-is-an-ordinary-balanced-kontor-entry
  (let [{:keys [parent child]} (setup)
        tx-data (resource/transfer-tx-data
                 {:id (random-uuid)
                  :kind :grant
                  :source (resource/account-ref parent)
                  :destination (resource/account-ref child)
                  :resources {unit 12M}
                  :effective-date #inst "2026-08-30"})
        postings (filter :kontor.posting/amount tx-data)]
    (is (= [12M -12M] (mapv :kontor.posting/amount postings)))
    (is (= #{resource/resource-ledger}
           (set (map :kontor.posting/ledger postings))))
    (is (= 0M (reduce + (map :kontor.posting/amount postings))))))

(deftest grant-consume-and-return-conserve-the-vector
  (let [{:keys [conn parent child]} (setup)]
    (resource/mint! conn {:id (random-uuid)
                          :destination (resource/account-ref parent)
                          :resources {unit 100M}})
    (resource/allocate! conn {:id (random-uuid)
                              :source (resource/account-ref parent)
                              :destination (resource/account-ref child)
                              :resources {unit 40M}})
    (resource/consume! conn {:id (random-uuid)
                             :source (resource/account-ref child)
                             :resources {unit 15M}})
    (resource/return! conn {:id (random-uuid)
                            :source (resource/account-ref child)
                            :destination (resource/account-ref parent)
                            :resources {unit 25M}})
    (is (= {unit 85M} (resource/balance conn (resource/account-ref parent))))
    (is (= {} (resource/balance conn (resource/account-ref child))))
    (is (= {unit 15M} (resource/balance conn resource/sink-account)))
    (is (= {unit -100M} (resource/balance conn resource/source-account)))))

(deftest a-wallet-cannot-delegate-more-than-it-owns
  (let [{:keys [conn parent child]} (setup)
        grant-id (random-uuid)]
    (resource/mint! conn {:id (random-uuid)
                          :destination (resource/account-ref parent)
                          :resources {unit 10M}})
    (let [error (try
                  (resource/allocate! conn {:id grant-id
                                            :source (resource/account-ref parent)
                                            :destination (resource/account-ref child)
                                            :resources {unit 11M}})
                  nil
                  (catch Throwable error error))]
      (is (some? error))
      (is (re-find #"insufficient" (ex-message error))))
    (is (= {unit 10M} (resource/balance conn (resource/account-ref parent))))
    (is (= {} (resource/balance conn (resource/account-ref child))))
    (is (nil? (resource/receipt conn grant-id)))))

(deftest concurrent-delegation-has-one-winner
  (let [{:keys [conn parent child]} (setup)
        child-2 (random-uuid)
        start (promise)]
    (resource/open-account! conn {:id child-2 :name "child-2"})
    (resource/mint! conn {:id (random-uuid)
                          :destination (resource/account-ref parent)
                          :resources {unit 100M}})
    (let [attempt (fn [destination]
                    (future
                      @start
                      (try
                        (resource/allocate! conn {:id (random-uuid)
                                                  :source (resource/account-ref parent)
                                                  :destination destination
                                                  :resources {unit 70M}})
                        :granted
                        (catch Throwable _ :refused))))
          a (attempt (resource/account-ref child))
          b (attempt (resource/account-ref child-2))]
      (deliver start true)
      (is (= {:granted 1 :refused 1} (frequencies [@a @b])))
      (is (= {unit 30M} (resource/balance conn (resource/account-ref parent)))))))

(deftest transfer-id-replay-is-idempotent-but-collision-is-not
  (let [{:keys [conn parent child]} (setup)
        mint-id (random-uuid)
        grant-id (random-uuid)
        mint {:id mint-id :destination (resource/account-ref parent)
              :resources {unit 20M}}
        grant {:id grant-id :source (resource/account-ref parent)
               :destination (resource/account-ref child)
               :resources {unit 7M}}]
    (resource/mint! conn mint)
    (resource/allocate! conn grant)
    (is (= :duplicate (:status (resource/allocate! conn grant))))
    (is (= {unit 13M} (resource/balance conn (resource/account-ref parent))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"idempotency"
         (resource/allocate! conn (assoc grant :resources {unit 8M}))))))

(deftest replay-compares-explicit-audit-semantics
  (let [{:keys [conn parent]} (setup)
        id (random-uuid)
        day-1 #inst "2026-08-30"
        day-2 #inst "2026-08-31"
        posted-1 #inst "2026-08-30T01:00:00.000-00:00"
        posted-2 #inst "2026-08-30T02:00:00.000-00:00"]
    (actor/register-actors! conn [{:uid "alice"} {:uid "bob"}])
    (resource/mint! conn {:id id
                          :destination (resource/account-ref parent)
                          :resources {unit 10M}
                          :effective-date day-1
                          :posted-at posted-1
                          :actor "alice"})
    (is (= :duplicate
           (:status (resource/mint! conn {:id id
                                          :destination (resource/account-ref parent)
                                          :resources {unit 10M}
                                          :effective-date day-1
                                          :posted-at posted-1
                                          :actor "alice"}))))
    (doseq [changed [{:effective-date day-2 :posted-at posted-1 :actor "alice"}
                     {:effective-date day-1 :posted-at posted-2 :actor "alice"}
                     {:effective-date day-1 :posted-at posted-1 :actor "bob"}
                     {:effective-date day-1 :posted-at posted-1}]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"idempotency"
           (resource/mint! conn (merge {:id id
                                        :destination (resource/account-ref parent)
                                        :resources {unit 10M}}
                                       changed)))))))

(deftest pure-transfer-builders-compose-with-distinct-tempids
  (let [{:keys [conn parent child]} (setup)
        child-2 (random-uuid)]
    (resource/open-account! conn {:id child-2})
    (resource/mint! conn {:id (random-uuid)
                          :destination (resource/account-ref parent)
                          :resources {unit 100M}})
    (let [grant (fn [id tempid destination amount]
                  (resource/transfer-tx-data
                   {:id id :tx-tempid tempid :kind :grant
                    :source (resource/account-ref parent)
                    :destination destination
                    :resources {unit amount}
                    :effective-date #inst "2026-08-30"}))
          tx-data (into (grant (random-uuid) "grant-a"
                               (resource/account-ref child) 40M)
                        (grant (random-uuid) "grant-b"
                               (resource/account-ref child-2) 60M))]
      (gate/transact-with-validation conn tx-data)
      (is (= {} (resource/balance conn (resource/account-ref parent))))
      (is (= {unit 40M} (resource/balance conn (resource/account-ref child))))
      (is (= {unit 60M} (resource/balance conn (resource/account-ref child-2)))))))

(deftest resource-vectors-are-strictly-positive
  (let [{:keys [parent child]} (setup)
        base {:id (random-uuid) :kind :grant
              :source (resource/account-ref parent)
              :destination (resource/account-ref child)
              :effective-date #inst "2026-08-30"}]
    (doseq [bad [{} {unit 0M} {unit -1M}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (resource/transfer-tx-data (assoc base :resources bad)))))))

(deftest a-resource-coordinate-may-appear-only-once
  (let [{:keys [parent child]} (setup)
        spec {:id (random-uuid) :kind :grant
              :source (resource/account-ref parent)
              :destination (resource/account-ref child)
              :effective-date #inst "2026-08-30"
              ;; Keywords and strings are both convenient public spellings,
              ;; but they canonicalize to the same commodity coordinate.
              :resources {:microUSD 1M "microUSD" 2M}}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"duplicate"
         (resource/transfer-tx-data spec)))))

(deftest db-resolved-resource-coordinate-may-appear-only-once
  (let [{:keys [conn parent child]} (setup)
        commodity-eid (d/q '[:find ?e . :in $ ?symbol
                             :where [?e :kontor.commodity/symbol ?symbol]]
                           @conn unit)]
    (resource/mint! conn {:id (random-uuid)
                          :destination (resource/account-ref parent)
                          :resources {unit 100M}})
    (is (thrown? clojure.lang.ExceptionInfo
                 (resource/allocate! conn
                                     {:id (random-uuid)
                                      :source (resource/account-ref parent)
                                      :destination (resource/account-ref child)
                                      :resources {unit 60M commodity-eid 60M}})))
    (is (= {unit 100M} (resource/balance conn (resource/account-ref parent))))
    (is (= {} (resource/balance conn (resource/account-ref child))))))

(deftest governor-rejects-a-raw-resource-overdraft
  (let [{:keys [conn parent child]} (setup)]
    (resource/mint! conn {:id (random-uuid)
                          :destination (resource/account-ref parent)
                          :resources {unit 5M}})
    (let [raw (resource/transfer-tx-data
               {:id (random-uuid) :kind :grant
                :source (resource/account-ref parent)
                :destination (resource/account-ref child)
                :resources {unit 6M}
                :effective-date #inst "2026-08-30"})
          report (dc/with @conn raw)
          error (try (governance/validate-report report) nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (is (= :kontor.resource/insufficient
             (:type (ex-data error)))))))

(defn- strip-resource-receipt [tx-data]
  (mapv (fn [form]
          (if (:kontor.resource-transfer/id form)
            (dissoc form
                    :kontor.resource-transfer/id
                    :kontor.resource-transfer/kind
                    :kontor.resource-transfer/source
                    :kontor.resource-transfer/destination)
            form))
        tx-data))

(defn- deepest-cause [error]
  (loop [error error]
    (if-let [cause (ex-cause error)]
      (recur cause)
      error)))

(deftest resource-ledger-cannot-be-written-without-a-transfer-receipt
  (let [{:keys [conn parent child]} (setup)
        raw (-> (resource/transfer-tx-data
                 {:id (random-uuid) :kind :mint
                  :source resource/source-account
                  :destination (resource/account-ref parent)
                  :resources {unit 5M}
                  :effective-date #inst "2026-08-30"})
                strip-resource-receipt)]
    ;; The mandatory writer-side smart contract sees the resolved report.
    (is (= :kontor.resource/unreceipted-posting
           (:type (ex-data
                   (try
                     (governance/validate-report (dc/with @conn raw))
                     nil
                     (catch clojure.lang.ExceptionInfo error error))))))
    ;; The normal business-write gate rejects the same bypass before commit.
    (is (= :kontor.resource/unreceipted-posting
           (:type (ex-data
                   (deepest-cause
                    (try
                      (gate/transact-with-validation conn raw)
                      nil
                      (catch Throwable error error)))))))
    (is (= {} (resource/balance conn (resource/account-ref parent))))
    (is (= {} (resource/balance conn (resource/account-ref child))))))

(deftest a-resource-receipt-must-be-a-posted-sealed-resource-entry
  (let [{:keys [conn parent]} (setup)
        draft (mapv (fn [form]
                      (cond-> (dissoc form
                                      :kontor.transaction/posted-at
                                      :kontor.posting/posted-at)
                        (:kontor.resource-transfer/id form)
                        (assoc :kontor.transaction/state :draft)))
                    (resource/transfer-tx-data
                     {:id (random-uuid) :kind :mint
                      :source resource/source-account
                      :destination (resource/account-ref parent)
                      :resources {unit 5M}
                      :effective-date #inst "2026-08-30"}))]
    (is (= :kontor.resource/invalid-transfer
           (:type (ex-data
                   (try
                     (governance/validate-report (dc/with @conn draft))
                     nil
                     (catch clojure.lang.ExceptionInfo error error))))))
    (is (= :kontor.resource/invalid-transfer
           (:type (ex-data
                   (deepest-cause
                    (try
                      (gate/transact-with-validation conn draft)
                      nil
                      (catch Throwable error error)))))))
    (is (= {} (resource/balance conn (resource/account-ref parent))))))

(defn- governor-error [conn tx-data]
  (try
    (governance/validate-report (dc/with @conn tx-data))
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest resource-authority-history-is-non-destructible
  ;; Use an explicit temporal database: Datahike's CoW test branches currently
  ;; lose purge capability even though their source config keeps history.
  (let [{:keys [conn parent child]}
        (setup-on (core/create-test-db {:keep-history? true}))
        mint-id (random-uuid)
        grant-id (random-uuid)
        owned-wallet (random-uuid)]
    (actor/register-actor! conn {:uid "wallet-owner"})
    (resource/open-account! conn {:id owned-wallet
                                  :owner [:kontor.actor/uid "wallet-owner"]})
    (resource/mint! conn {:id mint-id
                          :destination (resource/account-ref parent)
                          :resources {unit 100M}})
    (resource/allocate! conn {:id grant-id
                              :source (resource/account-ref parent)
                              :destination (resource/account-ref child)
                              :resources {unit 100M}})
    (let [mint-tx (:transaction (resource/receipt conn mint-id))
          grant-tx (:transaction (resource/receipt conn grant-id))]
      (doseq [tx-data [[[:db/purge mint-tx]]
                       [[:db/retractEntity grant-tx]]
                       [[:db/add grant-tx :kontor.transaction/state :cancelled]]
                       [[:db/add grant-tx :kontor.resource-transfer/kind :mint]]
                       [{:db/id (resource/account-ref resource/source-id)
                         :kontor.resource-account/kind :wallet}]]]
        (is (some? (governor-error conn tx-data))))
      (let [report (dc/with @conn [[:db/purge mint-tx]])]
        (is (contains? (:tx-ops report) :db/purge))
        (is (seq (resource-validate/immutable-history-violations report)))
        (is (= :kontor.resource/immutable-history
               (:type (ex-data
                       (try
                         (resource-validate/assert-report! report)
                         nil
                         (catch clojure.lang.ExceptionInfo error error)))))))
      (is (seq (resource-validate/immutable-tx-data-violations
                @conn [[:db/purge mint-tx]])))
      ;; Whole-entity deletion also retracts inbound refs. An owner therefore
      ;; cannot be purged out from under an established wallet.
      (is (seq (resource-validate/immutable-tx-data-violations
                @conn [[:db/purge [:kontor.actor/uid "wallet-owner"]]])))
      ;; The public gate must refuse the destructive intent. Depending on the
      ;; Datahike preflight seam it may surface the resource error or the
      ;; substrate's temporal-purge refusal; either way no write can commit.
      (is (some? (deepest-cause
                  (try
                    (gate/transact-with-validation conn [[:db/purge mint-tx]])
                    nil
                    (catch Throwable error error)))))
      (is (= :kontor.resource/immutable-history
             (:type (ex-data
                     (deepest-cause
                      (try
                        (gate/transact-with-validation
                         conn [{:db/id resource/source-account
                                :kontor.resource-account/kind :wallet}])
                        nil
                        (catch Throwable error error)))))))
      (is (= {} (resource/balance conn (resource/account-ref parent))))
      (is (= {unit 100M} (resource/balance conn (resource/account-ref child)))))))

(deftest ordinary-writes-do-not-scan-all-resource-history
  (let [{:keys [conn]} (setup)
        report (dc/with @conn [{:kontor.actor/uid "unrelated-room-actor"}])
        resource-facts-var (ns-resolve 'kontor.resource.validate
                                       'resource-facts)
        scans (atom 0)]
    (is (contains? (:tx-ops report) :db/add))
    (with-redefs-fn
      {resource-facts-var (fn [& _]
                            (swap! scans inc)
                            (throw (ex-info "unexpected full scan" {})))}
      #(is (nil? (governance/validate-report report))))
    (is (zero? @scans))))

(deftest purge-expanded-from-a-transaction-function-triggers-history-audit
  (let [{:keys [conn parent]} (setup-on
                               (core/create-test-db {:keep-history? true}))
        mint-id (random-uuid)]
    (resource/mint! conn {:id mint-id
                          :destination (resource/account-ref parent)
                          :resources {unit 1M}})
    (let [tx (:transaction (resource/receipt conn mint-id))
          report (dc/with @conn
                          [[:db.fn/call
                            (fn [_] [[:db/purge tx]])]])]
      (is (contains? (:tx-ops report) :db.fn/call))
      (is (contains? (:tx-ops report) :db/purge))
      (is (seq (resource-validate/immutable-history-violations report))))))

(deftest ordinary-financial-accounts-do-not-acquire-a-no-overdraft-policy
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "USD" :kontor.commodity/precision 2}
                 {:kontor.journal/code "GEN" :kontor.journal/type :general}
                 {:kontor.account/path "Assets:Cash" :kontor.account/type :asset
                  :kontor.account/active true}
                 {:kontor.account/path "Equity:Opening" :kontor.account/type :equity
                  :kontor.account/active true}])
    ;; A balanced accounting entry may legitimately overdraw an ordinary bank
    ;; account. The affine cone belongs only to :kontor.resource-account/*.
    (is (nil?
         (governance/validate-report
          (dc/with @conn
                   [{:db/id -1 :kontor.transaction/journal [:kontor.journal/code "GEN"]
                     :kontor.transaction/effective-date #inst "2026-08-30"
                     :kontor.transaction/state :posted
                     :kontor.transaction/posted-at #inst "2026-08-30"}
                    {:db/id -2 :kontor.posting/transaction -1
                     :kontor.posting/account [:kontor.account/path "Assets:Cash"]
                     :kontor.posting/commodity [:kontor.commodity/symbol "USD"]
                     :kontor.posting/amount -10M :kontor.posting/display-type :product
                     :kontor.posting/posted-at #inst "2026-08-30"}
                    {:db/id -3 :kontor.posting/transaction -1
                     :kontor.posting/account [:kontor.account/path "Equity:Opening"]
                     :kontor.posting/commodity [:kontor.commodity/symbol "USD"]
                     :kontor.posting/amount 10M :kontor.posting/display-type :product
                     :kontor.posting/posted-at #inst "2026-08-30"}]))))))

(deftest aggregate-schema-alone-does-not-protect-reserved-resource-names
  (let [conn (core/create-test-db)]
    (d/transact conn [{:kontor.journal/code "RESOURCE"
                       :kontor.journal/name "Ordinary pre-existing journal"
                       :kontor.journal/type :general
                       :kontor.journal/active true}])
    (is (nil? (governance/validate-report
               (dc/with @conn [{:kontor.journal/code "RESOURCE"
                                :kontor.journal/name "Renamed"}]))))))

(deftest protected-resources-accept-append-only-cardinality-many-metadata
  (let [{:keys [conn]} (setup)]
    (d/transact conn [{:db/ident :consumer.resource/tags
                       :db/valueType :db.type/keyword
                       :db/cardinality :db.cardinality/many}])
    (d/transact conn [[:db/add resource/source-account
                       :consumer.resource/tags :alpha]])
    (let [candidate [[:db/add resource/source-account
                      :consumer.resource/tags :beta]]]
      (is (empty? (resource-validate/immutable-tx-data-violations
                   @conn candidate)))
      (is (nil? (governance/validate-report (dc/with @conn candidate))))
      (gate/transact-with-validation conn candidate)
      (is (= #{:alpha :beta}
             (set (:consumer.resource/tags
                   (d/pull @conn [:consumer.resource/tags]
                           resource/source-account))))))))

(deftest sequential-operation-forms-cannot-bypass-resource-validation
  (let [{:keys [conn parent child]} (setup)
        mint-id (random-uuid)]
    (resource/mint! conn {:id mint-id
                          :destination (resource/account-ref parent)
                          :resources {unit 10M}})
    (let [tx (:transaction (resource/receipt conn mint-id))]
      (is (seq (resource-validate/immutable-tx-data-violations
                @conn [(list :db/add tx
                             :kontor.resource-transfer/kind :grant)])))
      (is (= :kontor.resource/immutable-history
             (:type (ex-data
                     (deepest-cause
                      (try
                        (gate/transact-with-validation
                         conn [(list :db/add tx
                                     :kontor.resource-transfer/kind :grant)])
                        nil
                        (catch Throwable error error))))))))
    (let [unreceipted (-> (resource/transfer-tx-data
                           {:id (random-uuid)
                            :kind :grant
                            :source (resource/account-ref parent)
                            :destination (resource/account-ref child)
                            :resources {unit 1M}
                            :effective-date #inst "2026-08-30"})
                          strip-resource-receipt
                          maps->add-forms)]
      (is (= :kontor.resource/unreceipted-posting
             (:type (ex-data
                     (deepest-cause
                      (try
                        (gate/transact-with-validation conn unreceipted)
                        nil
                        (catch Throwable error error))))))))))

(deftest opening-a-child-wallet-and-granting-it-compose-atomically
  (let [{:keys [conn parent]} (setup)
        child (random-uuid)]
    (resource/mint! conn {:id (random-uuid)
                          :destination (resource/account-ref parent)
                          :resources {unit 10M}})
    (let [tx-data (concat
                   (resource/open-account-tx-data {:id child :name "atomic child"})
                   (resource/transfer-tx-data
                    {:id (random-uuid)
                     :kind :grant
                     :source (resource/account-ref parent)
                     :destination (resource/account-ref child)
                     :resources {unit 4M}
                     :effective-date #inst "2026-08-30"}))]
      (gate/transact-with-validation conn tx-data)
      (is (= {unit 6M}
             (resource/balance conn (resource/account-ref parent))))
      (is (= {unit 4M}
             (resource/balance conn (resource/account-ref child)))))))

(deftest resource-kernel-installs-standalone-in-a-cohabiting-store
  (let [cfg (-> core/default-config
                (assoc-in [:store :id] (random-uuid)))
        parent (random-uuid)
        child (random-uuid)]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try
        (resource/install! conn)
        (resource/install-unit! conn {:symbol unit :precision 0})
        (resource/open-account! conn {:id parent})
        (resource/open-account! conn {:id child})
        (resource/mint! conn {:id (random-uuid)
                              :destination (resource/account-ref parent)
                              :resources {unit 3M}})
        (resource/allocate! conn {:id (random-uuid)
                                  :source (resource/account-ref parent)
                                  :destination (resource/account-ref child)
                                  :resources {unit 2M}})
        (is (= {unit 1M}
               (resource/balance conn (resource/account-ref parent))))
        (is (= {unit 2M}
               (resource/balance conn (resource/account-ref child))))
        (finally
          (governance/ungovern! conn)
          (d/release conn)
          (d/delete-database cfg))))))
