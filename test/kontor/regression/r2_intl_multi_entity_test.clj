(ns kontor.regression.r2-intl-multi-entity-test
  "Regression suite — INTERNATIONAL MULTI-ENTITY (R2 audit / consolidation area).

   A US parent (functional USD, presentation USD) with a DE sub
   (functional EUR), sharing ONE datahike connection under a synthetic
   :consolidation group entity + a synthetic :elimination entity.

   Deliberately distinct from `kontor.regression.intercompany-fx-test`
   (that suite uses a DE/EUR parent + US/USD sub and EUR presentation).
   Here the presentation currency is USD and the SUB is the foreign
   entity, so the IAS 21 translation + CTA math is hand-derived from a
   clean, independent set of round rates.

   Confirmed-correct (green):
     A. IAS 21 translation of the DE sub into USD with an exact,
        hand-derived cumulative-translation-adjustment (CTA) plug.
     B. Same-currency intercompany balances (AR-IC / Sales-IC, both on
        the USD parent side) eliminate to zero at the consolidated
        (group + elim) level.
     C. DE Organschaft fiscal-unit election + `run-group-tax!`
        (:single-base) with a fresh, independently hand-derived
        KSt + Soli figure.

   Pinned gaps (^:kaocha/pending):
     D. CROSS-CURRENCY intercompany balances (Purchases-IC / AP-IC on
        the EUR sub side) do NOT eliminate at the presentation-currency
        (USD) level — `consolidate!` never translates the elimination
        entity, so its postings stay in the source commodity (EUR) and
        cannot offset the translated (USD) operating-entity postings.
     E. `run-group-tax!` applies NO status gate — it produces a full
        group tax filing even for a fiscal unit that was never activated
        (still :proposed, :active false).

   Money is BigDecimal — compared scale-insensitively via .compareTo,
   never with `=` on doubles."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.fx.fx-rate-provider :as fxp]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.provider.consolidation :as cons]
            [kontor.reporting.trial :as trial]
            [kontor.tax.fiscal-unit :as fu]
            [kontor.validation :as v]
            [kontor.l10n-de.cit-statute :as de-cit-statute]
            [kontor.l10n-de.organschaft-provider :as organschaft]))

;; ---------------------------------------------------------------------------
;; Dates + rates
;; ---------------------------------------------------------------------------

(def txn-date  #inst "2026-06-30T00:00:00Z") ;; IC booking / transaction spot
(def year-end  #inst "2026-12-31T00:00:00Z") ;; group closing / translation date

;; EUR→USD rate-types (stored direct so the IAS 21 CTA math is exact,
;; free of inverse-rounding noise). Round levels chosen for clean
;; hand-derivation:
;;   spot (txn)  1 EUR = 1.10 USD   (booking the IC invoice)
;;   closing     1 EUR = 1.10 USD   (monetary BS items @ year-end)
;;   historical  1 EUR = 1.20 USD   (equity — frozen at contribution)
;;   average     1 EUR = 1.15 USD   (P&L over the period)
(defn- seed-rates! [conn]
  (fxp/save-rates!
   conn
   [{:from "EUR" :to "USD" :at-date txn-date :rate 1.10M :rate-type :spot :source :test}
    {:from "EUR" :to "USD" :at-date year-end :rate 1.10M :rate-type :closing :source :test}
    {:from "EUR" :to "USD" :at-date year-end :rate 1.20M :rate-type :historical :source :test}
    {:from "EUR" :to "USD" :at-date year-end :rate 1.15M :rate-type :average :source :test}]))

;; ---------------------------------------------------------------------------
;; Bootstrap — commodities, entity tree, shared chart of accounts
;; ---------------------------------------------------------------------------

(defn- bootstrap! []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
                 {:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
                  :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "USD"}])
    (seed-rates! conn)
    ;; group (consolidation, USD) → {elim (USD), US parent (USD), DE sub (EUR)}
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR"}
                 {:db/id "usd" :kontor.commodity/symbol "USD"}
                 {:db/id "grp"
                  :kontor.entity/code "grp" :kontor.entity/name "Acme Global Group"
                  :kontor.entity/kind :consolidation
                  :kontor.entity/functional-commodity "usd"
                  :kontor.entity/active true}
                 {:db/id "elim"
                  :kontor.entity/code "elim" :kontor.entity/name "Group Eliminations"
                  :kontor.entity/kind :elimination
                  :kontor.entity/functional-commodity "usd"
                  :kontor.entity/parent-entity "grp"
                  :kontor.entity/active true}
                 {:db/id "us"
                  :kontor.entity/code "us" :kontor.entity/name "Acme US Inc"
                  :kontor.entity/kind :operating
                  :kontor.entity/functional-commodity "usd"
                  :kontor.entity/parent-entity "grp"
                  :kontor.entity/active true}
                 {:db/id "de"
                  :kontor.entity/code "de" :kontor.entity/name "Acme Deutschland GmbH"
                  :kontor.entity/kind :operating
                  :kontor.entity/functional-commodity "eur"
                  :kontor.entity/parent-entity "grp"
                  :kontor.entity/active true}])
    (d/transact conn
                [{:db/id "cash" :kontor.account/path "Assets:Cash"
                  :kontor.account/code "1000" :kontor.account/name "Cash"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "ar-ic" :kontor.account/path "Assets:AR-IC"
                  :kontor.account/code "1400" :kontor.account/name "AR — Intercompany"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "ap-ic" :kontor.account/path "Liabilities:AP-IC"
                  :kontor.account/code "2400" :kontor.account/name "AP — Intercompany"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:db/id "stock" :kontor.account/path "Equity:CommonStock"
                  :kontor.account/code "3000" :kontor.account/name "Common Stock"
                  :kontor.account/type :equity :kontor.account/active true}
                 {:db/id "cta" :kontor.account/path "Equity:CTA"
                  :kontor.account/code "3900"
                  :kontor.account/name "Cumulative Translation Adjustment"
                  :kontor.account/type :equity :kontor.account/active true}
                 {:db/id "sales-ic" :kontor.account/path "Income:Sales-IC"
                  :kontor.account/code "4400" :kontor.account/name "Sales — Intercompany"
                  :kontor.account/type :income :kontor.account/active true}
                 {:db/id "purch-ic" :kontor.account/path "Expenses:Purchases-IC"
                  :kontor.account/code "5400" :kontor.account/name "Purchases — Intercompany"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "fx-elim" :kontor.account/path "Expenses:FX-Elimination"
                  :kontor.account/code "5900"
                  :kontor.account/name "FX gain/loss on intragroup elimination (IAS 21.45)"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "jnl" :kontor.journal/code "GEN"
                  :kontor.journal/name "General" :kontor.journal/type :misc
                  :kontor.journal/active true}])
    conn))

;; ---------------------------------------------------------------------------
;; Resolution helpers
;; ---------------------------------------------------------------------------

(defn- acct [db path] (:db/id (d/entity db [:kontor.account/path path])))
(defn- ent  [db code] (:db/id (d/entity db [:kontor.entity/code code])))
(defn- comm [db sym]  (:db/id (d/entity db [:kontor.commodity/symbol sym])))
(defn- jnl  [db]      (:db/id (d/entity db [:kontor.journal/code "GEN"])))

(defn- amt= [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (and (some? a) (some? b) (zero? (.compareTo a b))))

(def states {:include-states #{:draft :posted}})

;; US parent sells to DE sub. Invoice USD 110,000; DE books it in EUR at
;; the 1.10 spot → EUR 100,000.
;;   US: AR-IC +110,000 USD / Sales-IC -110,000 USD
;;   DE: Purchases-IC +100,000 EUR / AP-IC -100,000 EUR
;; Both tagged :kontor.transaction/intercompany-pair-id "P-IC-2026".
(defn- book-ic-pair! [conn]
  (let [db  (d/db conn)
        eur (comm db "EUR") usd (comm db "USD")
        j   (jnl db) us (ent db "us") de (ent db "de")]
    (posting/post-transaction!
     conn {:transaction {:kontor.transaction/journal j
                         :kontor.transaction/effective-date txn-date
                         :kontor.transaction/intercompany-pair-id "P-IC-2026"
                         :kontor.transaction/narration "US side — IC sale"}
           :postings [{:kontor.posting/account (acct db "Assets:AR-IC")
                       :kontor.posting/commodity usd :kontor.posting/amount 110000M
                       :kontor.posting/entity us}
                      {:kontor.posting/account (acct db "Income:Sales-IC")
                       :kontor.posting/commodity usd :kontor.posting/amount -110000M
                       :kontor.posting/entity us}]})
    (posting/post-transaction!
     conn {:transaction {:kontor.transaction/journal j
                         :kontor.transaction/effective-date txn-date
                         :kontor.transaction/intercompany-pair-id "P-IC-2026"
                         :kontor.transaction/narration "DE side — IC purchase"}
           :postings [{:kontor.posting/account (acct db "Expenses:Purchases-IC")
                       :kontor.posting/commodity eur :kontor.posting/amount 100000M
                       :kontor.posting/entity de}
                      {:kontor.posting/account (acct db "Liabilities:AP-IC")
                       :kontor.posting/commodity eur :kontor.posting/amount -100000M
                       :kontor.posting/entity de}]})))

;; ===========================================================================
;; A. IAS 21 translation of the DE sub into USD — exact hand-derived CTA
;; ===========================================================================

(deftest translate-de-sub-to-usd-with-exact-cta
  (testing "DE sub standalone BS translated to USD per IAS 21:
            monetary asset @ closing, equity @ historical, income @ average.
            The residual is the cumulative translation adjustment (CTA)."
    (let [conn (bootstrap!)
          db0  (d/db conn)
          de   (ent db0 "de") grp (ent db0 "grp")
          eur  (comm db0 "EUR")
          ;; DE sub: Cash +200,000 / Common Stock -150,000 / Sales -50,000 (EUR)
          _ (posting/post-transaction!
             conn {:transaction {:kontor.transaction/journal (jnl db0)
                                 :kontor.transaction/effective-date txn-date
                                 :kontor.transaction/narration "DE sub opening + first sale"}
                   :postings [{:kontor.posting/account (acct db0 "Assets:Cash")
                               :kontor.posting/commodity eur :kontor.posting/amount 200000M
                               :kontor.posting/entity de}
                              {:kontor.posting/account (acct db0 "Equity:CommonStock")
                               :kontor.posting/commodity eur :kontor.posting/amount -150000M
                               :kontor.posting/entity de}
                              {:kontor.posting/account (acct db0 "Income:Sales-IC")
                               :kontor.posting/commodity eur :kontor.posting/amount -50000M
                               :kontor.posting/entity de}]})
          db    (d/db conn)
          p     (fxp/make-static-table-provider conn)
          de-tb (trial/trial-balance conn {:entity de})
          txd  (cons/translate-trial-balance-tx-data
                {:db db :source-entity de :consolidation-entity grp
                 :presentation-commodity "USD" :fx-provider p
                 :at-date year-end :journal (jnl db)
                 :cta-account (acct db "Equity:CTA") :trial-balance de-tb})
          postings (filter :kontor.posting/amount txd)
          by-acct  (into {} (map (juxt :kontor.posting/account :kontor.posting/amount) postings))
          cash  (acct db "Assets:Cash")   stock (acct db "Equity:CommonStock")
          sales (acct db "Income:Sales-IC") cta   (acct db "Equity:CTA")]
      ;; Cash    200,000 × 1.10 (closing)    =  220,000.00 USD
      ;; Stock  -150,000 × 1.20 (historical) = -180,000.00 USD
      ;; Sales   -50,000 × 1.15 (average)    =  -57,500.00 USD
      ;; translated sum = 220,000 - 180,000 - 57,500 = -17,500.00 USD
      ;; CTA plug = -(sum) = +17,500.00 USD
      (is (amt= 220000.00M (by-acct cash))  "Cash @ closing 1.10")
      (is (amt= -180000.00M (by-acct stock)) "Common Stock @ historical 1.20")
      (is (amt= -57500.00M (by-acct sales))  "Sales @ average 1.15")
      (is (amt= 17500.00M (by-acct cta))
          "CTA = -(220,000 - 180,000 - 57,500) = +17,500.00 USD")
      (is (amt= 0M (reduce (fn [a p] (.add ^java.math.BigDecimal a
                                           ^java.math.BigDecimal (:kontor.posting/amount p)))
                           0M postings))
          "translation entry sums to zero in USD (CTA is the balancing plug)")
      (is (every? #(= grp (:kontor.posting/entity %)) postings)
          "translated postings stamped with the consolidation entity"))))

;; ===========================================================================
;; B. Same-currency intercompany eliminates to zero at group level (green)
;; ===========================================================================

(deftest same-currency-intercompany-nets-to-zero
  (testing "AR-IC / Sales-IC live on the USD parent side; the elimination
            is booked in USD too, so they net to zero across group + elim
            at the USD presentation level."
    (let [conn (bootstrap!)
          _    (book-ic-pair! conn)
          db0  (d/db conn)
          grp  (ent db0 "grp") elim (ent db0 "elim")
          p    (fxp/make-static-table-provider conn)
          _ (cons/consolidate!
             {:conn conn :group-root grp
              :consolidation-entity grp :elimination-entity elim
              :presentation-commodity "USD" :fx-provider p
              :at-date year-end :journal (jnl db0)
              :cta-account (acct db0 "Equity:CTA")
              :fx-gain-loss-account (acct db0 "Expenses:FX-Elimination")})
          group-tb (trial/trial-balance conn (merge {:entity grp} states))
          elim-tb  (trial/trial-balance conn (merge {:entity elim} states))
          db   (d/db conn)
          usd  (comm db "USD")
          ar   (acct db "Assets:AR-IC")  sales (acct db "Income:Sales-IC")
          rolled (fn [a c]
                   (reduce (fn [acc m] (money/add acc m))
                           (money/zero c)
                           (keep #(get-in % [a c]) [group-tb elim-tb])))]
      (is (trial/balanced? group-tb) "group trial balance sums to zero per commodity")
      (is (trial/balanced? elim-tb)  "elim trial balance sums to zero per commodity")
      (is (money/zero? (rolled ar usd))
          "AR-IC (USD parent side) nets to zero across translation + elimination")
      (is (money/zero? (rolled sales usd))
          "Sales-IC (USD parent side) nets to zero across translation + elimination"))))

;; ===========================================================================
;; C. DE Organschaft fiscal-unit election + group tax (green, hand-derived)
;; ===========================================================================

(defn- tax-bootstrap []
  (let [conn (core/create-test-db)]
    (de-cit-statute/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:db/id "e-holding" :kontor.entity/code "Acme-Holding"
                  :kontor.entity/name "Acme Holding GmbH"}
                 {:db/id "e-sub" :kontor.entity/code "Acme-Sub"
                  :kontor.entity/name "Acme Sub GmbH"}
                 {:db/id "doc-eav" :kontor.audit-doc/code "ACME-EAV-2026"
                  :kontor.audit-doc/type :tax-election
                  :kontor.audit-doc/storage-uri "s3://docs/eav-acme"
                  :kontor.audit-doc/uploaded-at #inst "2026-01-01"}])
    conn))

(defn- tax-ent [db code]
  (d/q '[:find ?e . :in $ ?v :where [?e :kontor.entity/code ?v]] db code))

(defn- elect-acme! [conn]
  (let [db (d/db conn)
        r  (fu/elect! conn
                      {:code "Acme-Organschaft-2026"
                       :name "Acme-Gruppe (Organschaft)"
                       :parent-entity (tax-ent db "Acme-Holding")
                       :regime :de-organschaft
                       :computation-style :single-base
                       :elected-from #inst "2026-01-01"
                       :anchor-document (d/q '[:find ?e . :in $ ?v
                                               :where [?e :kontor.audit-doc/code ?v]]
                                             db "ACME-EAV-2026")
                       :members [{:entity (tax-ent db "Acme-Holding") :role :parent}
                                 {:entity (tax-ent db "Acme-Sub") :role :sub
                                  :ownership-fraction 1M}]})]
    (get-in r [:tempids "fiscal-unit"])))

(deftest organschaft-group-tax-kst-plus-soli
  (testing "single-base group tax over a 2-entity Organschaft with a
            fresh hand-derived figure."
    (let [conn   (tax-bootstrap)
          fu-eid (elect-acme! conn)
          _      (fu/activate! conn fu-eid)   ; note 197: election must be in force
          db     (d/db conn)
          ;; Holding +€3,000,000 ; Sub −€500,000 → consolidated zvE €2,500,000.
          ;; KSt  (KStG §23 Abs.1, 15%)          = 0.15 × 2,500,000 = €375,000
          ;; Soli (SolZG §4, 5.5% × KSt)         = 0.055 × 375,000  = €20,625
          ;; KSt + Soli                                             = €395,625
          result (fu/run-group-tax! conn
                                    {:fiscal-unit fu-eid
                                     :period {:from #inst "2026-01-01" :to #inst "2026-12-31"}
                                     :provider (organschaft/de-organschaft-provider)
                                     :tax-unit {:hebesatz 470}
                                     :inputs {:members
                                              {(tax-ent db "Acme-Holding")
                                               {:gewinn-aus-gewerbebetrieb 3000000M}
                                               (tax-ent db "Acme-Sub")
                                               {:gewinn-aus-gewerbebetrieb -500000M}}}})
          facts (first (:filings result))
          kst   (first (filter #(= :de-bundesfinanzministerium (:authority %))
                               (:components facts)))]
      (is (= 1 (count (:filings result))) "single-base → one filing")
      (is (amt= 2500000M (get-in facts [:provenance :group-attribution :attributed-zve :amount]))
          "consolidated zvE = 3,000,000 − 500,000 = 2,500,000")
      (is (amt= 2500000M (get-in kst [:base :amount])) "KSt base = consolidated zvE")
      (is (amt= 375000.00M (get-in kst [:gross-liability :amount]))
          "gross KSt = 15% × 2,500,000 = 375,000")
      (let [soli (first (:surtaxes kst))]
        (is (some? soli) "Soli surtax present")
        (is (amt= 20625.000M (:amount soli)) "Soli = 5.5% × 375,000 = 20,625"))
      (is (amt= 395625.000M (get-in kst [:liability :amount]))
          "KSt + Soli = 395,625 to the cent"))))

;; ===========================================================================
;; D. FIXED — cross-currency intercompany eliminates in presentation currency
;; ===========================================================================

(deftest cross-currency-intercompany-should-eliminate-in-presentation-currency
  ;; FIXED (note 197): eliminate-intercompany-pair-tx-data now TRANSLATES each
  ;; eliminated posting to the presentation commodity at its account-type rate
  ;; (IAS 21.45 / IFRS 10 B86(c)) before reversing, so a cross-currency pair
  ;; nets to zero in USD. The residual from translating the two sides at
  ;; different rates (Purchases-IC @ average 1.15, AP-IC @ closing 1.10) is
  ;; posted to the :fx-gain-loss-account (P&L), not CTA and not force-to-zero.
  ;;
  ;;   DE Purchases-IC 100,000 EUR → grp +115,000 USD (@avg 1.15);
  ;;     elim −115,000 USD (@avg 1.15) → net 0 in USD.
  ;;   DE AP-IC −100,000 EUR → grp −110,000 USD (@closing 1.10);
  ;;     elim +110,000 USD (@closing 1.10) → net 0 in USD.
  ;;   Elim FX residual = −5,000 USD → +5,000 USD FX loss to P&L (balances elim).
  (testing "cross-currency intercompany Purchases-IC / AP-IC net to zero in USD"
    (let [conn (bootstrap!)
          _    (book-ic-pair! conn)
          db0  (d/db conn)
          grp  (ent db0 "grp") elim (ent db0 "elim")
          p    (fxp/make-static-table-provider conn)
          _ (cons/consolidate!
             {:conn conn :group-root grp
              :consolidation-entity grp :elimination-entity elim
              :presentation-commodity "USD" :fx-provider p
              :at-date year-end :journal (jnl db0)
              :cta-account (acct db0 "Equity:CTA")
              :fx-gain-loss-account (acct db0 "Expenses:FX-Elimination")})
          group-tb (trial/trial-balance conn (merge {:entity grp} states))
          elim-tb  (trial/trial-balance conn (merge {:entity elim} states))
          db   (d/db conn)
          usd  (comm db "USD")
          purch (acct db "Expenses:Purchases-IC")
          ap    (acct db "Liabilities:AP-IC")
          fx    (acct db "Expenses:FX-Elimination")
          rolled-usd (fn [a]
                       (reduce (fn [acc m] (money/add acc m))
                               (money/zero usd)
                               (keep #(get-in % [a usd]) [group-tb elim-tb])))]
      (is (money/zero? (rolled-usd purch))
          "consolidated intercompany Purchases-IC nets to 0 in USD")
      (is (amt= 5000M (:amount (rolled-usd fx)))
          "the intragroup FX residual (5,000 USD loss) lands in P&L per IAS 21.45")
      (is (money/zero? (rolled-usd ap))
          "consolidated intercompany AP-IC should net to ~0 in USD"))))

;; ===========================================================================
;; E. PENDING — run-group-tax! has no status gate
;; ===========================================================================

(deftest run-group-tax-should-reject-inactive-fiscal-unit
  ;; FIXED (note 197): run-group-tax! now gates on :kontor.fiscal-unit/status +
  ;; :active — only an in-force (:active) election may file. A merely :proposed
  ;; unit (or one driven to :voided-retro) is rejected with
  ;; :kontor.fiscal-unit/election-not-in-force. Authority: 26 CFR §1.1502-75
  ;; (consent); §14 KStG (valid GAV in force). Use fu/activate! to bring an
  ;; election in force before filing.
  (testing "a group tax run against a non-active (:proposed) fiscal unit is rejected"
    (let [conn   (tax-bootstrap)
          fu-eid (elect-acme! conn)      ;; leaves the unit in :proposed / active=false
          db     (d/db conn)
          unit   (d/pull db [:kontor.fiscal-unit/status :kontor.fiscal-unit/active] fu-eid)]
      ;; sanity: the freshly-elected unit is NOT yet active
      (is (= :proposed (:kontor.fiscal-unit/status unit)))
      (is (false? (:kontor.fiscal-unit/active unit)))
      ;; desired behaviour: running group tax on a non-active unit must throw
      (is (thrown? clojure.lang.ExceptionInfo
                   (fu/run-group-tax! conn
                                      {:fiscal-unit fu-eid
                                       :period {:from #inst "2026-01-01" :to #inst "2026-12-31"}
                                       :provider (organschaft/de-organschaft-provider)
                                       :tax-unit {:hebesatz 470}
                                       :inputs {:members
                                                {(tax-ent db "Acme-Holding")
                                                 {:gewinn-aus-gewerbebetrieb 3000000M}}}}))
          "run-group-tax! should refuse a fiscal unit that is not :active"))))
