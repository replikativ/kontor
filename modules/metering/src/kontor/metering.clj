(ns kontor.metering
  "Usage-metering → double-entry GL summarizer (research note 190, Piece C).

   Turns a *kontor-conformant usage subledger* — a seq of single-entry
   measurement rows — into balanced, sealed, idempotent double-entry
   accruals in a kontor general ledger. This is the governed second leg
   that the metering layer (e.g. dvergr's `:ledger/*`, via
   `dvergr.chat.accounting/usage->subledger-rows`) deliberately omits.

   ## The row shape (provider-agnostic)

   Each input row is:

     {:money      <kontor.money/Money>   ; cost, commodity-tagged
      :settlement :prepaid | :postpaid    ; selects the credit leg
      :dimensions {:project <id> :provider <kw> :model <str?> :resource <kw?>}}

   This is exactly what a metering source emits; `kontor.metering` knows
   nothing about dvergr — a consumer (simmis) wires the two together.

   ## What it posts

   One balanced entry per (project, expense-class, settlement, provider,
   commodity):

     Dr Expenses:AI-Compute:{COGS|R&D}          (the class → a real account,
                                                 because it changes the P&L line)
     Cr Assets:Prepaid-AI-Credits:<Provider>    (settlement :prepaid — a drawdown)
        or
     Cr Liabilities:Accrued:AI-Provider:<Provider>   (settlement :postpaid — an accrual)

   Provider / project ride as `:posting/dimensions` (ADR-097) so the cost
   is `marginalize`-sliceable without exploding the chart of accounts
   (note 190 §Finding 3). COGS-vs-R&D is the only account distinction —
   the caller supplies `:classify` (default: everything COGS).

   ## Idempotency (cross-DB safe)

   Each entry carries a deterministic `:external-id`
   (`kontor-meter|<period>|<project>|<provider>|<class>|<settlement>|<commodity>`);
   `summarize!` skips any that already exist, so re-running a period — or
   retrying after a crash between the meter read and the GL write — is a
   no-op. The id must name EVERY grouping dimension: an id narrower than
   the group key makes two distinct entries collide, and a collision
   reads as `:skipped`, indistinguishable from a legitimate re-run
   (note 198 audit HIGH-6).

   ## Settlement, closing the loop

   `settle!` books the real provider invoice against the same counter
   account (Dr accrued / Cr cash for postpaid; Dr prepaid-credits / Cr
   cash when *buying* prepaid credits), and `reconcile` reports the
   accrued-vs-actual delta."
  (:require [datahike.api :as d]
            [clojure.string :as str]
            [kontor.book :as book]
            [kontor.money :as m]
            [kontor.reporting.balance :as balance]))

;; ============================================================================
;; Config + account naming
;; ============================================================================

(def default-accounts
  "Default chart-of-accounts paths. Override per-consumer via config :accounts."
  {:cogs           "Expenses:AI-Compute:COGS"
   :r&d            "Expenses:AI-Compute:R&D"
   :prepaid-prefix "Assets:Prepaid-AI-Credits"
   :accrued-prefix "Liabilities:Accrued:AI-Provider"
   :cash           "Assets:Cash"})

(defn- provider-seg
  "Provider keyword → a chart-of-accounts path segment (:anthropic → \"Anthropic\")."
  [provider]
  (-> (name (or provider :unknown)) str/capitalize))

(defn- expense-account
  [accounts class]
  (get accounts (case class :r&d :r&d :cogs) (:cogs accounts)))

(defn- counter-account
  "The credit-leg account for a (settlement, provider)."
  [accounts settlement provider]
  (str (case settlement
         :postpaid (:accrued-prefix accounts)
         (:prepaid-prefix accounts))
       ":" (provider-seg provider)))

;; ============================================================================
;; Ensure accounts (idempotent)
;; ============================================================================

(defn ensure-accounts!
  "Idempotently create the expense + counter accounts referenced by `rows`
   (by `:kontor.account/path` identity), with the right account type
   (:expense for the class accounts, :asset for prepaid, :liability for
   accrued). Returns conn. Lets a consumer avoid pre-enumerating the CoA."
  [conn rows {:keys [classify accounts] :or {classify (constantly :cogs)}}]
  (let [accounts (merge default-accounts accounts)
        db       @conn
        existing (set (d/q '[:find [?p ...] :where [_ :kontor.account/path ?p]] db))
        needed   (into #{}
                       (mapcat (fn [{:keys [settlement dimensions]}]
                                 (let [cls (classify dimensions)]
                                   [[(expense-account accounts cls) :expense]
                                    [(counter-account accounts settlement (:provider dimensions))
                                     (case settlement :postpaid :liability :asset)]])))
                       rows)
        tx       (for [[path typ] needed :when (not (existing path))]
                   {:kontor.account/path path :kontor.account/type typ})]
    (when (seq tx) (d/transact conn (vec tx)))
    conn))

;; ============================================================================
;; Accrual entries (pure)
;; ============================================================================

(defn- group-key
  [classify {:keys [settlement money dimensions]}]
  {:project    (:project dimensions)
   :provider   (:provider dimensions)
   :class      (classify dimensions)
   :settlement settlement
   :commodity  (:commodity money)})

(defn- seg
  "Render one grouping dimension into the `:external-id`. Keywords lose
   their leading colon so `:USD` and `\"USD\"` read alike; everything
   else stringifies. `nil` becomes a literal so an absent dimension is
   still a distinct id rather than an empty run of separators."
  [v]
  (cond
    (nil? v)     "nil"
    (keyword? v) (name v)
    :else        (str v)))

(defn external-id
  "The deterministic idempotency key for one accrual entry. MUST name every
   dimension of [[group-key]] — see the namespace docstring."
  [period-key {:keys [project provider class settlement commodity]}]
  (str "kontor-meter|" period-key
       "|" (seg project)
       "|" (seg (or provider :unknown))
       "|" (seg class)
       "|" (seg settlement)
       "|" (seg commodity)))

(defn accrual-entries
  "Pure: subledger `rows` + config → a seq of `kontor.book/entry!` opts maps,
   one balanced accrual per (project, class, settlement, provider, commodity).
   Requires `:journal` + `:effective-date` + `:period-key` in config."
  [rows {:keys [period-key effective-date posted-at journal classify accounts]
         :or   {classify (constantly :cogs)}}]
  (let [accounts  (merge default-accounts accounts)
        posted-at (or posted-at effective-date)]        ; accruals are born sealed
    (for [[{:keys [project provider class settlement commodity] :as gk} rs]
          (group-by #(group-key classify %) rows)]
      (let [amt  (reduce m/add (map :money rs))         ; same commodity per group
            exp  (expense-account accounts class)
            ctr  (counter-account accounts settlement provider)
            ext  (external-id period-key gk)
            dims {:project project :provider provider}]
        {:journal        journal
         :effective-date effective-date
         :posted-at      posted-at
         :external-id    ext
         :narration      (str "AI compute accrual " period-key)
         :commodity      commodity
         :postings       [{:account [:kontor.account/path exp]
                           :amount (:amount amt) :dimensions dims}
                          {:account [:kontor.account/path ctr]
                           :amount (:amount (m/neg amt)) :dimensions dims}]}))))

;; ============================================================================
;; Summarize (idempotent post through the gate)
;; ============================================================================

(defn- existing-external-ids
  [db]
  (set (d/q '[:find [?e ...] :where [_ :kontor.transaction/external-id ?e]] db)))

(defn summarize!
  "Idempotently post the accruals for `rows` into the kontor GL, routed
   through the validation gate (balanced + sealed). Skips any entry whose
   `:external-id` already exists. Returns
     {:posted [ext-id …] :skipped [ext-id …]}."
  [conn rows config]
  (let [entries (accrual-entries rows config)
        ;; note 198 audit HIGH-6. A collision INSIDE one batch is silently
        ;; absorbed by the `seen` check below — the second entry reads
        ;; `:skipped`, which is indistinguishable from a legitimate re-run, so
        ;; a whole accrual (and its counter account) never reaches the GL. The
        ;; id now names every grouping dimension, which makes this
        ;; unreachable; the guard keeps it that way, loudly, if either side
        ;; is ever changed alone.
        dupes   (->> entries
                     (map :external-id)
                     frequencies
                     (keep (fn [[k n]] (when (> n 1) k)))
                     sort)
        _ (when (seq dupes)
            (throw (ex-info (str "kontor.metering/summarize!: " (count dupes)
                                 " :external-id collision(s) within one batch — the id "
                                 "does not name every grouping dimension, so an accrual "
                                 "would be silently dropped as :skipped")
                            {:type :metering/external-id-collision
                             :external-ids (vec dupes)})))
        seen    (existing-external-ids @conn)]
    (reduce (fn [acc opts]
              (let [ext (:external-id opts)]
                (if (contains? seen ext)
                  (update acc :skipped conj ext)
                  (do (book/entry! conn opts)
                      (update acc :posted conj ext)))))
            {:posted [] :skipped []}
            entries)))

;; ============================================================================
;; Reconciliation + settlement
;; ============================================================================

(defn- account-eid
  [db path]
  (d/q '[:find ?e . :in $ ?p :where [?e :kontor.account/path ?p]] db path))

(defn- account-money
  "The {commodity-eid Money} balance of an account path, or {} if absent."
  [conn path opts]
  (if-let [eid (account-eid @conn path)]
    (balance/account-balance conn eid opts)
    {}))

(defn accrued-balance
  "Signed Money balances ({commodity-eid Money}) of the postpaid accrued
   liability for `provider`. Credit balance is negative (kontor sign
   convention: positive = debit)."
  [conn config provider & [opts]]
  (account-money conn
                 (counter-account (merge default-accounts (:accounts config)) :postpaid provider)
                 (or opts {})))

(defn reconcile
  "Compare the recorded accrual for `provider` against the real invoice
   `actual` (a Money). Returns {:expected Money :actual Money :delta Money
   :ok? bool}. `:expected` is the magnitude of the accrued liability (the
   credit balance's sign is flipped for readability). Compared on amounts
   and re-tagged with `actual`'s commodity, so it is agnostic to whether
   the stored balance carries a keyword or an eid commodity.

   The balance is selected BY COMMODITY, not by position. `account-balance`
   returns `{commodity Money}`, and a provider accrued in two currencies puts
   two entries in it; `(first (vals …))` picked an arbitrary one, so the
   detector could report `:ok?` against a currency nobody asked about — the
   multi-commodity half of what note 198 audit HIGH-6 let through. An absent
   commodity reconciles against zero."
  [conn config provider actual & [opts]]
  (let [db           @conn
        bals         (accrued-balance conn config provider opts)
        want         (balance/resolve-commodity-symbol db (:commodity actual))
        raw-amt      (or (some (fn [[c m]]
                                 (when (= want (balance/resolve-commodity-symbol db c))
                                   (:amount m)))
                               bals)
                         BigDecimal/ZERO)
        expected-amt (.abs ^BigDecimal raw-amt)
        delta-amt    (.subtract ^BigDecimal (:amount actual) expected-amt)]
    {:expected (m/money expected-amt (:commodity actual))
     :actual   actual
     :delta    (m/money delta-amt (:commodity actual))
     :ok?      (zero? (.compareTo ^BigDecimal delta-amt BigDecimal/ZERO))}))

(defn settle!
  "Book the real provider settlement (research note 190):
   - :postpaid — pay the accrued liability: Dr Accrued:<Provider> / Cr Cash
   - :prepaid  — buy prepaid credits:       Dr Prepaid:<Provider> / Cr Cash
   `amount` is a Money. Idempotent via `:external-id`
   `kontor-settle|<ref>|<provider>`."
  [conn config provider settlement amount
   {:keys [journal effective-date ref] :as _opts}]
  (let [accounts (merge default-accounts (:accounts config))
        ctr      (counter-account accounts settlement provider)
        cash     (:cash accounts)
        ext      (str "kontor-settle|" ref "|" (name provider))]
    (when-not (contains? (existing-external-ids @conn) ext)
      (book/entry! conn
                   {:journal        journal
                    :effective-date effective-date
                    :posted-at      effective-date
                    :external-id    ext
                    :narration      (str "AI provider settlement " (name provider))
                    :commodity      (:commodity amount)
                    ;; :postpaid → Dr liability (clears it) / Cr cash
                    ;; :prepaid  → Dr asset (tops it up) / Cr cash
                    :postings       [{:account [:kontor.account/path ctr]
                                      :amount (:amount amount)}
                                     {:account [:kontor.account/path cash]
                                      :amount (:amount (m/neg amount))}]}))))
