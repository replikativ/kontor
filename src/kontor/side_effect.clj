(ns kontor.side-effect
  "Side-effect intent rows — ADR-041.

   The pattern: a status change wants to trigger a side effect (send
   email, send EDI, charge card, notify). Instead of firing the side
   effect inside the transition (which doesn't compose atomically and
   double-fires on retry), the caller writes a `:side-effect-intent`
   row in the SAME tx as the status change. A worker drains pending
   intents and marks them done.

   This namespace ships the intent state machine + dispatcher queries.
   Side-effect EXECUTORS (email senders, EDI clients, etc.) are
   consumer-side."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-key
  "Resolve an intent eid by :side-effect-intent/key."
  [db k]
  (d/q '[:find ?e .
         :in $ ?k
         :where [?e :side-effect-intent/key ?k]]
       db k))

(defn pull-intent
  [db spec]
  (let [eid (if (string? spec) (by-key db spec) spec)]
    (when eid (d/pull db '[*] eid))))

;; ============================================================================
;; Queries
;; ============================================================================

(defn pending
  "Pulled :side-effect-intent rows in :pending status. Optionally
   filter by `:type` opt."
  ([db] (pending db nil))
  ([db opts]
   (let [type-filter (:type opts)
         rows (if type-filter
                (d/q '[:find [?i ...]
                       :in $ ?t
                       :where
                       [?i :side-effect-intent/status :pending]
                       [?i :side-effect-intent/type ?t]]
                     db type-filter)
                (d/q '[:find [?i ...]
                       :where [?i :side-effect-intent/status :pending]]
                     db))]
     (->> rows
          (map #(d/pull db '[*] %))
          (sort-by :side-effect-intent/created-at)
          vec))))

(defn failed
  "Pulled :side-effect-intent rows in :failed status (worth retrying)."
  [db]
  (->> (d/q '[:find [?i ...]
              :where [?i :side-effect-intent/status :failed]]
            db)
       (map #(d/pull db '[*] %))
       vec))

;; ============================================================================
;; State transitions
;; ============================================================================

(defn claim!
  "Atomic claim: transition :pending → :processing. Returns the
   tx-report. Worker calls this before doing the actual work."
  [conn intent-eid]
  (d/transact conn [{:db/id intent-eid
                     :side-effect-intent/status :processing
                     :side-effect-intent/processing-at (java.util.Date.)}]))

(defn mark-done!
  "Transition :processing → :done. Worker calls this after the side
   effect succeeded."
  [conn intent-eid]
  (d/transact conn [{:db/id intent-eid
                     :side-effect-intent/status :done
                     :side-effect-intent/processed-at (java.util.Date.)}]))

(defn mark-failed!
  "Transition :processing → :failed with error message + retry-count
   bump. Worker calls this after the side effect threw. Caller can
   re-claim later for retry."
  [conn intent-eid error-message]
  (let [db (d/db conn)
        intent (d/pull db [:side-effect-intent/retry-count
                           :side-effect-intent/max-retries]
                       intent-eid)
        retry (inc (or (:side-effect-intent/retry-count intent) 0))
        max-r (or (:side-effect-intent/max-retries intent) 5)
        terminal? (>= retry max-r)]
    (d/transact conn
                [{:db/id intent-eid
                  :side-effect-intent/status (if terminal? :abandoned :failed)
                  :side-effect-intent/retry-count retry
                  :side-effect-intent/last-error (or error-message "(no message)")
                  :side-effect-intent/processed-at (java.util.Date.)}])))

(defn mark-abandoned!
  "Force-abandon an intent (no more retries). Use when an error is
   permanent (e.g. invalid recipient, customer deleted)."
  [conn intent-eid reason-string]
  (d/transact conn [{:db/id intent-eid
                     :side-effect-intent/status :abandoned
                     :side-effect-intent/last-error reason-string
                     :side-effect-intent/processed-at (java.util.Date.)}]))
