(ns kontor.resource-test
  "Conserved resource vectors over Kontor's existing posting algebra."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.core :as dc]
            [kontor.core :as core]
            [kontor.gate :as gate]
            [kontor.governance :as governance]
            [kontor.resource :as resource]))

(def unit "microUSD")

(defn- setup []
  (let [conn   (core/create-test-db)
        parent (random-uuid)
        child  (random-uuid)]
    (resource/install-defaults! conn)
    (resource/install-unit! conn {:symbol unit :name "Micro US dollars" :precision 0})
    (resource/open-account! conn {:id parent :name "parent"})
    (resource/open-account! conn {:id child :name "child"})
    {:conn conn :parent parent :child child}))

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
