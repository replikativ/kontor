(ns kontor.banking.bank-account
  "Bank-account helpers — ADR-039.

   `:bank-account` is a kernel entity: holds an IBAN/BIC/routing-
   number, currency, country, and verification flags. Bank accounts
   are master-data; partner relationships are temporal via
   `:partner-bank-account` (see `kontor.partner` namespace)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-code
  "Resolve a :bank-account eid by :kontor.bank-account/code."
  [db code]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :kontor.bank-account/code ?code]]
       db code))

(defn resolve-bank-account
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-code db spec)
    :else          spec))

(defn pull-bank-account
  [db spec]
  (when-let [eid (resolve-bank-account db spec)]
    (d/pull db '[*] eid)))

;; ============================================================================
;; IBAN validation (mod-97 checksum)
;; ============================================================================

(defn valid-iban-checksum?
  "Check the IBAN mod-97 checksum (catches single-digit typos).
   Does NOT validate country-specific length or character classes —
   consumers that need full validation plug in iban4j or similar.

   Returns true iff `iban` (whitespace-stripped, uppercased) passes
   the mod-97 = 1 invariant."
  [iban]
  (when (and (string? iban) (>= (count iban) 4))
    (let [cleaned (-> iban
                      (.replaceAll "\\s+" "")
                      .toUpperCase)
          ;; Move first four chars to the end (country code + check digits)
          rearranged (str (.substring cleaned 4) (.substring cleaned 0 4))
          ;; Convert letters A-Z to 10-35
          numeric (apply str
                         (map (fn [^Character c]
                                (let [v (int c)]
                                  (cond
                                    (and (>= v (int \0)) (<= v (int \9))) (str c)
                                    (and (>= v (int \A)) (<= v (int \Z))) (str (- v (int \A) -10))
                                    :else nil)))
                              rearranged))]
      (when (every? #(Character/isDigit ^Character %) numeric)
        (let [big (java.math.BigInteger. ^String numeric)]
          (= 1 (.intValue (.mod big (java.math.BigInteger/valueOf 97)))))))))
