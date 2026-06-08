(ns kontor.hr.compensation
  "kontor-hr :compensation transactors — the per-employment comp
   envelope with multi-cardinality :compensation-component rows.

   Compensation is its own entity (lifted off :employment) so that
   Weihnachtsgeld + employer SI + VWL + housing allowance can be
   modeled as DISTINCT components — each with its own
   :kontor.compensation-component/account-hint → CoA account. A single
   scalar :kontor.employment/wage cannot represent N simultaneously-active
   pay components; the bitemporal axis only answers 'envelope as of
   date', not 'which components are active'.

   The current-wage query is `employment-current-wage` — finds the
   :active :compensation row whose [effective-from, effective-to)
   window covers the query date, sums its :base-wage components.

   The PayrollComputeProvider feeds on :compensation-component rows
   to assemble its variable-inputs ctx — every component-kind it
   recognizes maps to a pay-period contribution."
  (:require [datahike.api :as d]
            [kontor.validation :as validation])
  (:import [java.util Date]))

;; ============================================================================
;; set-compensation
;; ============================================================================

(defn set-compensation-tx-data
  "Pure tx-data builder for `set-compensation!`. Creates a
   :compensation row plus N :compensation-component rows under it.

   Required keys:
     :employment      — ref or eid of :employment
     :effective-from  — instant
     :commodity       — ref to :commodity
     :components      — vector of {:kind :amount :period
                         [:account-hint] [:commodity]} maps

   Optional keys:
     :effective-to    — instant; nil = open-ended (the current
                        compensation — recommended; supersede by
                        creating a new row + closing this one's
                        effective-to)
     :state           — :proposed | :active (default :active)
     :tempid          — string for cross-step composition
                        (default \"compensation-1\")"
  [_db {:keys [employment effective-from commodity components
               effective-to state tempid]
        :or {state :active
             tempid "compensation-1"}}]
  (when-not employment     (throw (ex-info ":employment required" {})))
  (when-not effective-from (throw (ex-info ":effective-from required" {})))
  (when-not commodity      (throw (ex-info ":commodity required" {})))
  (when (empty? components) (throw (ex-info ":components must be non-empty" {})))
  (let [comp-row (cond-> {:db/id tempid
                          :kontor.compensation/employment employment
                          :kontor.compensation/effective-from effective-from
                          :kontor.compensation/commodity commodity
                          :kontor.compensation/state state}
                   effective-to (assoc :kontor.compensation/effective-to effective-to))
        component-rows
        (map-indexed
         (fn [i {:keys [kind amount period account-hint commodity]
                 :or   {period :monthly}}]
           (when-not kind   (throw (ex-info "component :kind required"   {:i i})))
           (when-not amount (throw (ex-info "component :amount required" {:i i})))
           (cond-> {:db/id (str "comp-" (inc i))
                    :kontor.compensation-component/compensation tempid
                    :kontor.compensation-component/kind kind
                    :kontor.compensation-component/amount (bigdec amount)
                    :kontor.compensation-component/period period
                    :kontor.compensation-component/account-hint (or account-hint kind)}
             commodity (assoc :kontor.compensation-component/commodity commodity)))
         components)]
    (into [comp-row] component-rows)))

(defn set-compensation!
  "Transact a new compensation envelope + its components."
  [conn opts]
  (let [tx (set-compensation-tx-data (d/db conn) opts)]
    (validation/transact-with-validation conn tx)))

;; ============================================================================
;; supersede — close the prior envelope's effective-to + add the new one
;; ============================================================================

(defn supersede-compensation-tx-data
  "Close the currently-active :compensation for an :employment by
   setting its :effective-to to the new envelope's :effective-from,
   transitioning :state to :superseded, and adding the new envelope
   in one transaction. Symmetric to ADR-048 close-validity but at
   the application layer (keeps the schema explicit without relying
   on the bitemporal axis alone)."
  [db {:keys [employment effective-from commodity components] :as opts}]
  (when-not employment     (throw (ex-info ":employment required" {})))
  (when-not effective-from (throw (ex-info ":effective-from required" {})))
  (when-not commodity      (throw (ex-info ":commodity required" {})))
  (let [emp-eid (if (number? employment)
                  employment
                  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.employment/code ?c]]
                       db employment))
        prior (d/q '[:find ?c .
                     :in $ ?emp
                     :where
                     [?c :kontor.compensation/employment ?emp]
                     [?c :kontor.compensation/state :active]]
                   db emp-eid)]
    (vec (concat
          (when prior
            [{:db/id prior
              :kontor.compensation/effective-to effective-from
              :kontor.compensation/state :superseded}])
          (set-compensation-tx-data db (assoc opts :employment emp-eid))))))

(defn supersede-compensation!
  [conn opts]
  (let [tx (supersede-compensation-tx-data (d/db conn) opts)]
    (validation/transact-with-validation conn tx)))

;; ============================================================================
;; Queries
;; ============================================================================

(defn current-compensation
  "Find the :compensation row for an :employment whose
   [effective-from, effective-to) window covers `at-date` (default:
   now). Excludes :proposed envelopes (which never took effect) but
   includes :superseded ones, since a superseded envelope was the
   truth for its own historical window."
  ([db employment] (current-compensation db employment (Date.)))
  ([db employment ^Date at-date]
   (let [emp-eid (if (number? employment)
                   employment
                   (d/q '[:find ?e . :in $ ?c :where [?e :kontor.employment/code ?c]]
                        db employment))]
     (d/q '[:find ?c .
            :in $ ?emp ?at
            :where
            [?c :kontor.compensation/employment ?emp]
            [?c :kontor.compensation/state ?st]
            [(not= ?st :proposed)]
            [?c :kontor.compensation/effective-from ?f]
            [(<= ?f ?at)]
            [(get-else $ ?c :kontor.compensation/effective-to #inst "9999-12-31") ?t]
            [(< ?at ?t)]]
          db emp-eid at-date))))

(defn components-of
  "Pull the :compensation-component rows under a :compensation eid."
  [db compensation-eid]
  (->> (d/q '[:find [?cc ...]
              :in $ ?c
              :where [?cc :kontor.compensation-component/compensation ?c]]
            db compensation-eid)
       (map #(d/pull db '[*] %))
       vec))

(defn employment-current-wage
  "Convenience: total monthly :base-wage for an :employment at
   `at-date` (default: now). Returns a BigDecimal, or 0M when no
   active :compensation or no :base-wage component is present.

   Hides the comp-as-entity indirection for the common-case
   'what's Jane making?' query."
  (^java.math.BigDecimal [db employment]
   (employment-current-wage db employment (Date.)))
  (^java.math.BigDecimal [db employment ^Date at-date]
   (if-let [comp-eid (current-compensation db employment at-date)]
     (or (d/q '[:find (sum ?amt) .
                :with ?cc
                :in $ ?c
                :where
                [?cc :kontor.compensation-component/compensation ?c]
                [?cc :kontor.compensation-component/kind :base-wage]
                [?cc :kontor.compensation-component/amount ?amt]]
              db comp-eid)
         0M)
     0M)))
