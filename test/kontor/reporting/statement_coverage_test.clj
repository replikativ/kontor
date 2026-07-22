(ns kontor.reporting.statement-coverage-test
  "Every account a jurisdiction ships must appear on exactly one line of
   the statement that is supposed to show it.

   The DE Bilanz omitted six shipped accounts — the tax provisions
   `doc/quickstart.md` instructs the reader to post to, plus the dividend
   and KESt payables. Nothing noticed, because a missing line is not an
   error: the money simply is not shown, and the statement stops
   balancing by exactly that amount. Measured before the fix:
   `:balanced? false` with a delta of 8,243.75 after a year-end close,
   which is precisely the CIT provision.

   Asserted here per jurisdiction rather than per account, so the next
   country to ship a chart is covered the moment it appears in the list.

   `:dangling` — line codes matching no shipped account — is reported but
   NOT failed. German practice binds accounts to statement positions
   per-account rather than by number range (DATEV's SKR04 sheet maps
   3040/3050/3060/3065 to four different targets, and 3810 into the same
   family as 3050), so a definition legitimately enumerates codes for a
   fuller chart than the module ships. Research note in note 194 §3.

   Note 194 §2."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.reporting.financial-statements :as fs]
            [kontor.l10n-de.bs :as de-bs]
            [kontor.l10n-de.pnl :as de-pnl]
            [kontor.l10n-de.preset :as de]
            [kontor.l10n-us.bs :as us-bs]
            [kontor.l10n-us.pnl :as us-pnl]
            [kontor.l10n-us.preset :as us]))

(def ^:private balance-sheet-types #{:asset :liability :equity})
(def ^:private pnl-types #{:income :expense})

(defn- check [db statement types label]
  (let [{:keys [uncovered double-counted]}
        (fs/statement-coverage db statement {:account-types types})]
    (is (= [] uncovered)
        (str label ": shipped accounts on no line — their balances cannot appear, "
             "and a balance sheet stops balancing by exactly that much: "
             (pr-str (mapv (juxt :code :path) uncovered))))
    (is (= [] double-counted)
        (str label ": accounts counted on more than one line: "
             (pr-str (mapv (juxt :code :lines) double-counted))))))

(deftest de-statements-cover-the-shipped-skr04-chart
  (let [db @(de/create-de-db)]
    (testing "Bilanz — Aktiva (§ 266 Abs. 2)"
      (check db de-bs/aktiva-definition #{:asset} "DE Aktiva"))
    (testing "Bilanz — Passiva (§ 266 Abs. 3)"
      (check db de-bs/passiva-definition #{:liability :equity} "DE Passiva"))
    (testing "GuV (§ 275 Abs. 2)"
      (check db de-pnl/gkv-definition pnl-types "DE GuV"))))

(deftest us-statements-cover-the-shipped-chart
  (let [db @(us/create-us-db)]
    (testing "balance sheet"
      (check db us-bs/definition balance-sheet-types "US balance sheet"))
    (testing "income statement"
      (check db us-pnl/definition pnl-types "US income statement"))))

(deftest coverage-detects-what-it-claims-to
  ;; The invariant is only worth having if it fails on the shape it is
  ;; meant to catch. Exercised against a definition deliberately missing
  ;; a line and one double-counting an account.
  (let [db @(us/create-us-db)
        one-line (fn [codes]
                   {:statement/name "T" :statement/country "US"
                    :statement/sections
                    [{:section/code "1" :section/label "s"
                      :section/lines [{:line/code "1.1" :line/label "l"
                                       :line/codes codes}]}]})]
    (testing "an account on no line is reported, with enough to find it"
      (let [{:keys [uncovered]} (fs/statement-coverage db (one-line ["1000"])
                                                       {:account-types #{:asset}})
            codes (into #{} (map :code) uncovered)]
        (is (seq uncovered))
        (is (not (contains? codes "1000")) "the covered one is not reported")
        (is (contains? codes "1200") "an uncovered one is")
        (is (every? :path uncovered) "each carries its path, so it can be placed")))
    (testing "an account matched by two lines is reported"
      (let [{:keys [double-counted]}
            (fs/statement-coverage
             db {:statement/name "T" :statement/country "US"
                 :statement/sections
                 [{:section/code "1" :section/label "s"
                   :section/lines [{:line/code "1.1" :line/label "exact"
                                    :line/codes ["1000"]}
                                   {:line/code "1.2" :line/label "prefix"
                                    :line/codes ["10%"]}]}]}
             {:account-types #{:asset}})]
        (is (= ["1000"] (mapv :code (filter #(= "1000" (:code %)) double-counted))))
        (is (= ["1.1" "1.2"] (:lines (first (filter #(= "1000" (:code %)) double-counted)))))))
    (testing "a code matching no account is reported separately, not as a failure"
      (let [{:keys [dangling]} (fs/statement-coverage db (one-line ["1000" "9999"])
                                                      {:account-types #{:asset}})]
        (is (= ["9999"] dangling))))))
