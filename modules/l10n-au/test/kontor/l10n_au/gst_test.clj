(ns kontor.l10n-au.gst-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-au.chart :as chart]
            [kontor.l10n-au.gst :as gst]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(defn- aud [s] (money/money (bigdec s) :AUD))

(def jan-1  #inst "2026-01-01T00:00:00Z")
(def jan-15 #inst "2026-01-15T00:00:00Z")
(def apr-1  #inst "2026-04-01T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:journal/code "INV" :journal/name "Sales"
                       :journal/type :sale :journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- post-taxable-sale!
  "GST 10% sale: AR (gross) | Sales (net) | GST payable (10%)"
  [conn ext-id date net]
  (let [db (d/db conn)
        aud-eid (:db/id (d/entity db [:kontor.commodity/symbol "AUD"]))
        ar (ace db "11200")
        rev (ace db "41100")
        gst-acc (ace db "21500")
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        net-bd (bigdec net)
        tax-bd (.setScale (.multiply net-bd 0.10M) 2 java.math.RoundingMode/HALF_EVEN)
        gross (.add net-bd tax-bd)
        tx (-> (posting/build-transaction
                {:transaction {:transaction/external-id ext-id
                               :transaction/journal jnl
                               :transaction/effective-date date
                               :transaction/narration ext-id
                               :transaction/state :posted
                               :transaction/posted-at date}
                 :postings
                 [{:posting/account ar :posting/amount gross :posting/commodity aud-eid}
                  {:posting/account rev :posting/amount (.negate net-bd) :posting/commodity aud-eid}
                  {:posting/account gst-acc :posting/amount (.negate tax-bd) :posting/commodity aud-eid}]})
               (->> (mapv #(if (some? (:posting/account %))
                             (assoc % :posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

(deftest single-taxable-sale
  (testing "$10,000 sale → G1=10,000, 1A=1,000, net-gst=1,000"
    (let [conn (bootstrap)
          _ (post-taxable-sale! conn "INV-1" jan-15 10000)
          r (gst/compute-return conn {:from jan-1 :to apr-1})]
      (is (money/equiv? (aud "10000.00") (:G1 (:return/lines r))))
      (is (money/equiv? (aud "1000.00")  (:1A (:return/lines r))))
      (is (money/equiv? (aud "1000.00")  (:return/net-gst r)))
      (is (= :payment (:return/outcome r))))))

(deftest quarterly-period-bounds
  (testing "Q1 2026 picks Jan-Mar"
    (let [{:keys [from to kind quarter year]} (gst/period-bounds {:year 2026 :quarter 1})]
      (is (= :quarterly kind))
      (is (= 1 quarter))
      (is (= 2026 year))
      (is (= jan-1 from))
      (is (= apr-1 to)))))

(deftest monthly-period-bounds
  (testing "Monthly: Jan 2026"
    (let [{:keys [from to kind month]} (gst/period-bounds {:year 2026 :month 1})]
      (is (= :monthly kind))
      (is (= 1 month))
      (is (= jan-1 from))
      (is (= #inst "2026-02-01T00:00:00Z" to)))))

(deftest nil-return
  (let [conn (bootstrap)
        r (gst/compute-return conn {:from jan-1 :to apr-1})]
    (is (= :nil-return (:return/outcome r)))
    (is (money/equiv? (aud "0.00") (:return/net-gst r)))))
