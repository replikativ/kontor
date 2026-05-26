(ns kontor.l10n-in.states
  "Indian states + union territories with GSTN state codes (`:in/gst`).

   The 37 entries cover the 28 states + 8 union territories with
   legislatures + Ladakh (UT without legislature; allotted code 38)
   + 2 pseudo-codes used by NIC for IRN/EWB routing:
     97 — Other Territory
     96 — Foreign Country (used for export buyers)

   Per ADR-023 these load as `:state` entities under
   `:kontor.country/code \"IN\"`, with the GSTN code attached as
   `:kontor.state-code/regulator :in/gst`."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [datahike.api :as d]))

(def state-table
  "37 codes (1-37) + 38 (Ladakh) + 96/97 pseudo. Source: CBIC
   GST State Codes notification."
  [;; 28 states
   {:code "AP" :gst "01" :name "Jammu and Kashmir"} ; allotted 01; J&K reorganized 2019 — kept here for back-compat
   {:code "HP" :gst "02" :name "Himachal Pradesh"}
   {:code "PB" :gst "03" :name "Punjab"}
   {:code "CH" :gst "04" :name "Chandigarh"        :ut? true}
   {:code "UT" :gst "05" :name "Uttarakhand"}
   {:code "HR" :gst "06" :name "Haryana"}
   {:code "DL" :gst "07" :name "Delhi"             :ut? true}
   {:code "RJ" :gst "08" :name "Rajasthan"}
   {:code "UP" :gst "09" :name "Uttar Pradesh"}
   {:code "BR" :gst "10" :name "Bihar"}
   {:code "SK" :gst "11" :name "Sikkim"}
   {:code "AR" :gst "12" :name "Arunachal Pradesh"}
   {:code "NL" :gst "13" :name "Nagaland"}
   {:code "MN" :gst "14" :name "Manipur"}
   {:code "MZ" :gst "15" :name "Mizoram"}
   {:code "TR" :gst "16" :name "Tripura"}
   {:code "ML" :gst "17" :name "Meghalaya"}
   {:code "AS" :gst "18" :name "Assam"}
   {:code "WB" :gst "19" :name "West Bengal"}
   {:code "JH" :gst "20" :name "Jharkhand"}
   {:code "OR" :gst "21" :name "Odisha"}
   {:code "CG" :gst "22" :name "Chhattisgarh"}
   {:code "MP" :gst "23" :name "Madhya Pradesh"}
   {:code "GJ" :gst "24" :name "Gujarat"}
   {:code "DD" :gst "25" :name "Dadra and Nagar Haveli and Daman and Diu" :ut? true}
   ;; 26 was DD before merge with Daman & Diu in 2020
   {:code "DH" :gst "26" :name "(merged) Dadra and Nagar Haveli + Daman & Diu (legacy)" :ut? true :legacy? true}
   {:code "MH" :gst "27" :name "Maharashtra"}
   {:code "AD" :gst "28" :name "Andhra Pradesh (Old)" :legacy? true}
   {:code "KA" :gst "29" :name "Karnataka"}
   {:code "GA" :gst "30" :name "Goa"}
   {:code "LD" :gst "31" :name "Lakshadweep"       :ut? true}
   {:code "KL" :gst "32" :name "Kerala"}
   {:code "TN" :gst "33" :name "Tamil Nadu"}
   {:code "PY" :gst "34" :name "Puducherry"        :ut? true}
   {:code "AN" :gst "35" :name "Andaman and Nicobar Islands" :ut? true}
   {:code "TG" :gst "36" :name "Telangana"}
   {:code "AP-new" :gst "37" :name "Andhra Pradesh"}
   {:code "LA" :gst "38" :name "Ladakh"            :ut? true}
   ;; Pseudo-codes (per NIC IRP / EWB routing tables)
   {:code "OT" :gst "97" :name "Other Territory"   :pseudo? true}
   {:code "FC" :gst "96" :name "Foreign Country"   :pseudo? true}])

(defn install!
  "Idempotently install India + its 37 states under ADR-023's :state
   entity model. Each state carries `:kontor.state-code/regulator :in/gst`
   with the 2-digit GSTN state code as the canonical sub-jurisdiction
   identifier.

   Idempotency is by **first-install guard** on `:kontor.country/code \"IN\"`:
   composite-tuple `:db.unique/identity` doesn't auto-deduplicate at
   write time (unlike single-attribute identity), so we skip the
   second install entirely rather than reasserting the same data."
  [conn]
  (when (d/entity (d/db conn) [:kontor.country/code "IN"])
    ;; Already installed — nothing to do.
    #_:already-installed)
  (when-not (d/entity (d/db conn) [:kontor.country/code "IN"])
    (d/transact conn
                [{:db/id     -1
                  :kontor.country/code "IN"
                  :kontor.country/code-iso3 "IND"
                  :kontor.country/name "India"
                  :kontor.country/active true}])
    (let [tx-data
        (->> state-table
             (filter #(not (:legacy? %)))            ; skip legacy AD / DH
             (mapcat (fn [{:keys [code gst name ut? pseudo?]}]
                       (let [state-tempid (- -1000 (parse-long gst))
                             code-tempid  (- -2000 (parse-long gst))
                             note (cond
                                    pseudo? "NIC pseudo-code"
                                    ut?     "Union Territory"
                                    :else   nil)]
                         [{:db/id          state-tempid
                           :kontor.state/country  [:kontor.country/code "IN"]
                           :kontor.state/code     code
                           :kontor.state/name     name
                           :kontor.state/active   (not pseudo?)}
                          (cond-> {:db/id                  code-tempid
                                   :kontor.state-code/state       state-tempid
                                   :kontor.state-code/regulator   :in/gst
                                   :kontor.state-code/code        gst}
                            note (assoc :kontor.state-code/note note))])))
                   vec)]
      (d/transact conn tx-data)))
  conn)

(defn by-gst-code
  "Resolve a `:state` entity-id by its 2-digit GSTN code, or nil."
  [db gst-code]
  (d/q '[:find ?s .
         :in $ ?gst
         :where
         [?sc :kontor.state-code/regulator :in/gst]
         [?sc :kontor.state-code/code ?gst]
         [?sc :kontor.state-code/state ?s]]
       db gst-code))

(defn gst-code-of
  "Reverse: given a state entity-id, return its GSTN 2-digit code."
  [db state-eid]
  (d/q '[:find ?gst .
         :in $ ?s
         :where
         [?sc :kontor.state-code/state ?s]
         [?sc :kontor.state-code/regulator :in/gst]
         [?sc :kontor.state-code/code ?gst]]
       db state-eid))

(defn union-territory?
  "True iff the state is a Union Territory (drives UTGST vs SGST
   dispatch). Determined from the note field on the GST external-code."
  [db state-eid]
  (let [note (d/q '[:find ?note .
                    :in $ ?s
                    :where
                    [?sc :kontor.state-code/state ?s]
                    [?sc :kontor.state-code/regulator :in/gst]
                    [?sc :kontor.state-code/note ?note]]
                  db state-eid)]
    (= note "Union Territory")))
