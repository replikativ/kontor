(ns kontor.dev.pg-experiment
  "Spin up a pg-datahike server fronting an accounting DB pre-seeded
   with the SKR04 chart and a small Q1 fixture book. Used to explore
   what's reachable from a SQL client (psql / pgjdbc) over the kernel.

   Run:
     clj -M:pg-server
       → starts on 127.0.0.1:54320 (avoiding default 5432)

   Connect:
     psql -h localhost -p 54320 -U datahike accounting
     PGPASSWORD=anything

   Then try:
     \\dt                                    -- list tables
     \\d account                             -- describe account
     SELECT code, name, type FROM account ORDER BY code;
     SELECT code FROM account_tag;
     SELECT count(*) FROM posting;"
  (:require [datahike.api :as d]
            [datahike.pg :as pg]
            [kontor.core :as core]
            [kontor.posting :as posting]
            [kontor.validation :as v]
            [kontor.l10n-de.chart :as chart]))

(def jan-15 #inst "2026-01-15T00:00:00Z")
(def feb-15 #inst "2026-02-15T00:00:00Z")
(def mar-15 #inst "2026-03-15T00:00:00Z")

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- post! [conn external-id date postings]
  (let [db (d/db conn)
        jnl (:db/id (d/entity db [:journal/code "GEN"]))
        tx (-> (posting/build-transaction
                {:transaction
                 {:transaction/external-id external-id
                  :transaction/journal jnl
                  :transaction/effective-date date
                  :transaction/narration external-id
                  :transaction/state :posted
                  :transaction/posted-at date}
                 :postings postings})
               (->> (mapv #(if (some? (:posting/account %))
                             (assoc % :posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

(defn- invoice-19! [conn id date net]
  (let [db (d/db conn)
        eur-c (:db/id (d/entity db [:commodity/symbol "EUR"]))
        net-bd (bigdec net)
        vat (.setScale (.multiply net-bd (bigdec "0.19"))
                       2 java.math.RoundingMode/HALF_EVEN)
        gross (.add net-bd vat)]
    (post! conn id date
           [{:posting/account (ace db "1400") :posting/amount gross :posting/commodity eur-c}
            {:posting/account (ace db "4400") :posting/amount (.negate net-bd) :posting/commodity eur-c}
            {:posting/account (ace db "3801") :posting/amount (.negate vat) :posting/commodity eur-c}])))

(defn -main [& _]
  (println "[pg-experiment] creating accounting DB…")
  (let [conn (core/create-test-db)]
    (println "[pg-experiment] installing invariants + SKR04 chart…")
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:journal/code "GEN"
                       :journal/name "General"
                       :journal/type :general
                       :journal/active true}])
    (println "[pg-experiment] seeding three sample invoices…")
    (invoice-19! conn "INV-001" jan-15 1000)
    (invoice-19! conn "INV-002" feb-15 1500)
    (invoice-19! conn "INV-003" mar-15 750)

    (println "[pg-experiment] starting pg-datahike on 127.0.0.1:54320…")
    (println "[pg-experiment] :tx-wrap installed — SQL writes route through")
    (println "                 kontor.validation/validate-and-apply")
    (let [srv (pg/start-server
               {"accounting" conn}
               {:port 54320 :host "127.0.0.1"
                :tx-wrap (v/pg-tx-wrap)})]
      (println)
      (println "READY. Connect with:")
      (println "  psql -h localhost -p 54320 -U datahike accounting")
      (println)
      (println "Try:")
      (println "  \\dt")
      (println "  SELECT code, name, type FROM account ORDER BY code LIMIT 20;")
      (println "  SELECT count(*) FROM posting;")
      (println "  SELECT * FROM transaction;")
      (println)
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable (fn [] (pg/stop-server srv))))
      @(promise))))
