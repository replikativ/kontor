(ns kontor.regression.r2-cash-journal-test
  "R2 audit — ambiguous `:cash` journal resolution in the `kontor.book`
   verb facade.

   `kontor.book/resolve-journal` (src/kontor/book.clj:100) resolves a
   journal from a `:kontor.journal/type` keyword and REQUIRES exactly one
   journal of that type; on 2+ it throws
   `kontor.book: N journals of type :cash — ambiguous`.

   Every l10n preset, however, seeds TWO journals of type `:cash`:
   `CR` (Cash Receipts) + `CD` (Cash Disbursements). This is the
   conventional double-cash-book split. Consequence: on a standard
   preset db, the four ergonomic cash verbs — `receive!`, `pay!`,
   `receive-payment!`, `pay-bill!` (and `distribute-dividend!`) —
   which resolve `:journal-type :cash` cannot post without the caller
   passing `:journal` explicitly. The `book` namespace docstring and
   each verb's docstring advertise the journal-resolved-by-type path as
   the ergonomic default, so this is a real usability gap.

   The single-type verbs are fine: presets seed exactly one journal of
   each of `:sale` (SJ), `:purchase` (PJ), `:general` (GJ) — so
   `sell!` / `buy!` / `transfer!` / `adjust!` / `declare-dividend!`
   resolve unambiguously. Those stay green here.

   The bug is preset-universal, not FR/BR-specific: all 13 create-*-db
   presets exhibit it."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.reporting.balance :as balance]
            ;; all preset namespaces (each ships create-<cc>-db)
            [kontor.l10n-de.preset :as de]
            [kontor.l10n-at.preset :as at]
            [kontor.l10n-fr.preset :as fr]
            [kontor.l10n-ca.preset :as ca]
            [kontor.l10n-us.preset :as us]
            [kontor.l10n-jp.preset :as jp]
            [kontor.l10n-au.preset :as au]
            [kontor.l10n-cn.preset :as cn]
            [kontor.l10n-br.preset :as br]
            [kontor.l10n-in.preset :as in]
            [kontor.l10n-mx.preset :as mx]
            [kontor.l10n-uk.preset :as uk]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- journal-type->count
  "Map of :kontor.journal/type -> number of journals of that type in `db`."
  [db]
  (->> (d/q '[:find ?t (count ?j)
              :where [?j :kontor.journal/type ?t]]
            db)
       (into {})))

(defn- bal-amount
  "Single-commodity balance BigDecimal on `account` in `conn` (nil if none)."
  [conn account]
  (some-> (balance/account-balance conn account) vals first :amount))

(defn- eq? [^java.math.BigDecimal expected actual]
  (and (some? actual) (zero? (.compareTo expected ^java.math.BigDecimal actual))))

;; DE / SKR04 account lookup-refs (paths are :db.unique/identity)
(def ^:private eur     [:kontor.commodity/symbol "EUR"])
(def ^:private kasse   [:kontor.account/path "Umlaufvermögen:Kasse"])
(def ^:private bank    [:kontor.account/path "Umlaufvermögen:Bank"])
(def ^:private ar      [:kontor.account/path "Umlaufvermögen:Forderungen"])
(def ^:private ap      [:kontor.account/path "Verbindlichkeiten:Lieferanten"])
(def ^:private rev     [:kontor.account/path "Erträge:Erlöse:19%"])
(def ^:private einkauf [:kontor.account/path "Aufwendungen:Wareneinkauf"])
(def ^:private d1      #inst "2026-03-15")

;; ============================================================================
;; 1. Root cause — sweep ALL presets for journal-type multiplicity
;; ============================================================================

(def ^:private all-presets
  {"DE" de/create-de-db "AT" at/create-at-db "FR" fr/create-fr-db
   "CA" ca/create-ca-db "US" us/create-us-db "JP" jp/create-jp-db
   "AU" au/create-au-db "CN" cn/create-cn-db "BR" br/create-br-db
   "IN" in/create-in-db "MX" mx/create-mx-db "UK" uk/create-uk-db})

(deftest every-preset-seeds-two-cash-journals
  (testing "all 12 l10n presets seed exactly TWO :cash journals (CR + CD),
            and exactly one each of :sale / :purchase / :general"
    (doseq [[cc create-db] all-presets]
      (let [counts (journal-type->count (d/db (create-db)))]
        (is (= 2 (:cash counts))     (str cc ": two :cash journals (CR + CD)"))
        (is (= 1 (:sale counts))     (str cc ": one :sale journal"))
        (is (= 1 (:purchase counts)) (str cc ": one :purchase journal"))
        (is (= 1 (:general counts))  (str cc ": one :general journal"))))))

;; ============================================================================
;; 2. Single-type verbs resolve unambiguously — GREEN (correct behaviour)
;; ============================================================================

(deftest single-type-verbs-work-on-a-preset
  (testing "sell! / buy! / transfer! resolve their (unique) journal by type
            and post correctly on the DE preset"
    (let [conn (de/create-de-db)]
      ;; :sale (SJ is the only :sale journal)
      (book/sell!     conn {:debit-account ar :credit-account rev
                            :amount 1000 :commodity eur :effective-date d1})
      ;; :purchase (PJ is the only :purchase journal)
      (book/buy!      conn {:debit-account einkauf :credit-account ap
                            :amount 300 :commodity eur :effective-date d1})
      ;; :general (GJ is the only :general journal)
      (book/transfer! conn {:debit-account bank :credit-account kasse
                            :amount 50 :commodity eur :effective-date d1})
      (is (eq? 1000M  (bal-amount conn ar))   "receivable 1000")
      (is (eq? -1000M (bal-amount conn rev))  "revenue credit-natural")
      (is (eq? 300M   (bal-amount conn einkauf)) "purchase expense 300")
      (is (eq? -300M  (bal-amount conn ap))   "payable credit-natural")
      (is (eq? 50M    (bal-amount conn bank)) "bank +50 (transfer in)")
      (is (eq? -50M   (bal-amount conn kasse)) "cash -50 (transfer out)"))))

;; ============================================================================
;; 3. The ambiguity is reachable — GREEN (confirms current thrown behaviour)
;; ============================================================================

(deftest cash-verbs-throw-ambiguous-on-a-preset
  (testing "receive-payment! / pay-bill! / receive! / pay! all throw a clear
            ambiguity ex-info on the DE preset, because two :cash journals exist"
    (let [conn (de/create-de-db)]
      (doseq [verb [book/receive-payment! book/pay-bill! book/receive! book/pay!]]
        (let [e (try (verb conn {:debit-account kasse :credit-account ar
                                 :amount 100 :commodity eur :effective-date d1})
                     nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (some? e) "a cash verb throws")
          (is (= :cash (:journal-type (ex-data e))) "ex-data names :cash")
          (is (= 2 (count (:found (ex-data e)))) "ex-data reports the 2 candidates"))))))

(deftest explicit-journal-is-the-workaround
  (testing "passing :journal explicitly (CR for receipts) bypasses resolution
            and posts correctly — this is the documented escape hatch"
    (let [conn (de/create-de-db)
          cr   [:kontor.journal/code "CR"]]
      ;; Dr Kasse / Cr Forderungen — a customer payment settling a receivable
      (book/receive-payment! conn {:debit-account kasse :credit-account ar
                                   :amount 100 :commodity eur :effective-date d1
                                   :journal cr})
      (is (eq? 100M  (bal-amount conn kasse)) "cash +100")
      (is (eq? -100M (bal-amount conn ar))    "receivable -100"))))

;; ============================================================================
;; 4. THE GAP — the ergonomic cash-verb path the docstrings promise is broken
;; ============================================================================

;; PENDING(NEW): kontor.book cash verbs (receive!/pay!/receive-payment!/
;; pay-bill!/distribute-dividend!) all bake in :journal-type :cash and rely on
;; resolve-journal (book.clj:100) picking the single journal of that type. But
;; EVERY l10n preset seeds two :cash journals (CR + CD, the standard split), so
;; resolve-journal throws "N journals of type :cash — ambiguous" and no cash
;; verb can post without the caller hand-passing :journal. The verb docstrings
;; advertise "Journal type :cash" as the resolved-by-type ergonomic default, so
;; on any shipped preset that default is unusable. A correct facade would either
;; distinguish receipts (CR) from disbursements (CD) by the verb's cash-flow
;; direction, or the presets would seed a single :cash journal. This test asserts
;; the promised behaviour (receive-payment! posts a customer payment with no
;; explicit :journal) and currently ERRORS on the ambiguity throw.
(deftest ^:kaocha/pending receive-payment-resolves-cash-journal-on-preset
  (testing "receive-payment! posts on the DE preset WITHOUT an explicit :journal"
    (let [conn (de/create-de-db)]
      (book/receive-payment! conn {:debit-account kasse :credit-account ar
                                   :amount 100 :commodity eur :effective-date d1})
      (is (eq? 100M  (bal-amount conn kasse)) "cash +100")
      (is (eq? -100M (bal-amount conn ar))    "receivable -100"))))
