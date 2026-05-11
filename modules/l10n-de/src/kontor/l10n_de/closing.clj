(ns kontor.l10n-de.closing
  "DE-specific year-end close: defaults the retained-earnings account
   to SKR04 2900 (Gewinnvortrag vor Verwendung) and the closing
   journal to \"CLOSE\".

   Both can be overridden — Kapitalgesellschaften that distinguish
   :gewinnvortrag (2900) from :verlustvortrag (2978) before the
   shareholder resolution may want their own routing."
  (:require [datahike.api :as d]
            [kontor.closing :as closing]))

(def ^:const default-retained-code "2900")
(def ^:const default-journal-code "CLOSE")

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn close-fiscal-year!
  "Close a fiscal-year period using SKR04 conventions.

   Required:
     :period-eid

   Optional:
     :retained-code  — SKR04 code for the retained-earnings account
                       (default \"2900\")
     :journal-code   — journal code for the closing tx
                       (default \"CLOSE\"; auto-created if absent)
     :external-id, :narration, :at — passed through

   Returns the kernel close-fiscal-year! result."
  [conn {:keys [period-eid retained-code journal-code]
         :or {retained-code default-retained-code
              journal-code default-journal-code}
         :as opts}]
  (let [db (d/db conn)
        retained (ace db retained-code)
        _ (when-not retained
            (throw (ex-info (str "SKR04 retained-earnings account "
                                 retained-code " not found — install the chart first")
                            {:code retained-code})))
        ;; Auto-create the CLOSE journal if it doesn't exist.
        jnl (or (:db/id (d/entity db [:journal/code journal-code]))
                (do (d/transact conn
                                [{:journal/code journal-code
                                  :journal/name "Year-end closing entries"
                                  :journal/type :closing
                                  :journal/active true}])
                    (:db/id (d/entity (d/db conn) [:journal/code journal-code]))))]
    (closing/close-fiscal-year!
     conn
     (-> opts
         (dissoc :retained-code :journal-code)
         (assoc :retained-earnings-eid retained
                :journal-eid jnl)))))
