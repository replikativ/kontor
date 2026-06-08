(ns kontor.payroll-us-adp.wage-types
  "Consumer-extensible wage-type catalog shape (ADR-077).

   The wage-type map ships as EDN — NOT baked-in defaults — for the
   same reason `kontor.tax.tax-rate-provider` ships abstract: every
   customer's CoA, account numbering, and ADP description vocabulary
   is different. We provide a *reference* fixture (under
   `resources/kontor/payroll_us_adp/wage_type_map_reference.edn`) that
   covers the common ADP wage-type vocabulary; consumers copy + edit.

   ## The shape

     {:vendor             :adp        ; future: :gusto / :paychex / :onpay
      :csv-format         {...}       ; column layout for the parser
      :reference-mappings [...]       ; reference-N column → analytic axis
      :description-rules  [{:match    <regex-string-or-Pattern>
                            :role     <keyword>
                            :account-key       <keyword — CoA lookup>
                            :ledgers           #{:us-gaap :us-tax}
                            :state-from-group  <regex group index>
                            :w2-box            <\"1\" | \"3\" | \"12\" | …>
                            :w2-code           <\"D\" | \"W\" | …>
                            :reduces-box-1?    <bool>
                            :reduces-box-3?    <bool>
                            :reduces-box-5?    <bool>
                            :section-125?      <bool>
                            :irc-404a6?        <bool>
                            :reverses-accrual  <keyword>
                            :flag-for-review?  <bool>}]}

   `:account-key` is the consumer's wage-type → CoA key passed into
   the `:accounts` map at `run-payroll!` time. The reference fixture
   uses descriptive keys (`:wages-expense`, `:ee-fed-withheld`, …);
   consumers map them to their actual `:kontor.account/code` lookup-refs.

   See doc/research/83-us-adp-gli-research-before.md §5 + §9.2 for
   the full reference fixture and the rationale for each flag.

   ## License posture (ADR-077)

   This namespace ships NO embedded customer data: no CoA, no account
   numbers, no ADP rate tables, no vendor credentials. The reference
   EDN is illustrative — it carries no licensed material and uses
   public ADP wage-type labels from public ADP GLI format
   documentation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kontor.payroll-us-adp.compute :as compute]))

(defn load-reference
  "Load the bundled reference wage-type-map (`adp` vendor). Useful for
   tests + as a starting point for consumers."
  []
  (compute/load-reference-map))

(defn load-from-resource
  "Load + compile a consumer-supplied wage-type map from a classpath
   resource path."
  [resource-path]
  (-> resource-path io/resource slurp edn/read-string
      compute/load-wage-type-map))

(defn load-from-file
  "Load + compile a consumer-supplied wage-type map from a filesystem
   path."
  [path]
  (-> path slurp edn/read-string compute/load-wage-type-map))

(defn validate
  "Lightweight structural validation. Returns a vector of error maps
   or `nil` if the map looks good. NOT a full schema — just a sanity
   check for the common mistakes.

   This is the inspection-friendly form. For the canonical
   throw-on-failure convention (matches DE's
   `kontor.payroll-de-datev.wage-types/validate-catalog`), use
   `assert-valid!`."
  [wtm]
  (let [errs (cond-> []
               (nil? (:vendor wtm))
               (conj {:error :missing-vendor})

               (nil? (get-in wtm [:csv-format :columns]))
               (conj {:error :missing-csv-format-columns})

               (empty? (:description-rules wtm))
               (conj {:error :no-description-rules})

               (not-any? (fn [r] (= ".*" (str (:match r))))
                         (:description-rules wtm))
               (conj {:error :no-catch-all-rule
                      :hint "Last rule should match #\".*\" to a :unmapped suspense account so the parser never drops a row silently."}))]
    (when (seq errs) errs)))

(defn assert-valid!
  "Throws ex-info with `:errors` set to the validate output if the
   map fails validation; returns the map unchanged on success.
   Canonical entry-point matching DE's
   `validate-catalog` convention ( — across-adapter
   consistency). Use this at install time so a misconfigured
   wage-type map fails loud rather than silently dropping rows."
  [wtm]
  (when-let [errs (validate wtm)]
    (throw (ex-info "kontor.payroll-us-adp wage-type map invalid"
                    {:type :wage-type-map/invalid
                     :errors errs})))
  wtm)
