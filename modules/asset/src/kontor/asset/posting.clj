(ns kontor.asset.posting
  "kontor-asset GL posting builders — ADR-054.

   Pure functions: each `plan-*` builder takes a db value + a spec
   and returns tx-data ready for `datahike.api/transact`. None
   transacts. Every builder routes through
   `kontor.posting/build-transaction`, so sum-to-zero per
   (ledger, commodity) is enforced for free and a structurally
   broken entry throws at build time.

   Sign convention (kernel-wide): a debit is a positive
   `:kontor.posting/amount`, a credit is negative.

   ## Composition with ADR-053

   ADR-053's lifecycle transactors (`acquire!`, `dispose!`,
   `impair!`, `revalue!`) take an optional `:transaction` /
   `:origin-transaction` ref. The flow: build the tx-data with a
   builder here, `d/transact` it, then pass the resulting
   transaction eid into the ADR-053 transactor. The builders are
   the durable seam; fusing build+transact+link into one call is a
   consumer-app choice.

   ## Multi-book

   `plan-depreciation-charge` / `plan-disposal` / `plan-impairment`
   / `plan-revaluation` take a *book* and tag every posting with
   that book's `:ledger`. A multi-book asset's disposal is N calls,
   one per book — the parallel-book shape (each ledger balances
   independently)."
  (:require [datahike.api :as d]
            [kontor.asset.asset :as asset]
            [kontor.asset.depreciation :as depreciation]
            [kontor.posting :as posting]))

;; ============================================================================
;; Internals
;; ============================================================================

(defn- asset-accounts
  "Pull an asset's three GL account eids."
  [db asset-eid]
  (let [a (d/pull db
                  '[{:kontor.asset/asset-account [:db/id]}
                    {:kontor.asset/accumulated-account [:db/id]}
                    {:kontor.asset/expense-account [:db/id]}]
                  asset-eid)]
    {:asset-account       (:db/id (:kontor.asset/asset-account a))
     :accumulated-account (:db/id (:kontor.asset/accumulated-account a))
     :expense-account     (:db/id (:kontor.asset/expense-account a))}))

(defn- book-context
  "Resolve a book spec to {:book :asset :ledger :commodity
   :acquisition-cost + the asset's three account eids}. The
   `:expense-account` is the book's per-book override
   (`:kontor.asset-depreciation/expense-account`, ADR-063) when set, else
   the asset's `:kontor.asset/expense-account`."
  [db book-spec]
  (let [eid (depreciation/resolve-book db book-spec)
        _ (when-not eid (throw (ex-info "Depreciation book not found" {:spec book-spec})))
        b (d/pull db [{:kontor.asset-depreciation/asset [:db/id :kontor.asset/acquisition-cost]}
                      {:kontor.asset-depreciation/ledger [:db/id]}
                      {:kontor.asset-depreciation/commodity [:db/id]}
                      {:kontor.asset-depreciation/expense-account [:db/id]}]
                  eid)
        asset-eid (:db/id (:kontor.asset-depreciation/asset b))
        accts (asset-accounts db asset-eid)]
    (merge {:book             eid
            :asset            asset-eid
            :ledger           (:db/id (:kontor.asset-depreciation/ledger b))
            :commodity        (:db/id (:kontor.asset-depreciation/commodity b))
            :acquisition-cost (:kontor.asset/acquisition-cost (:kontor.asset-depreciation/asset b))}
           accts
           ;; Per-book :expense-account override (ADR-063) wins.
           (when-let [ovr (:db/id (:kontor.asset-depreciation/expense-account b))]
             {:expense-account ovr}))))

(defn- posting*
  "Build one posting map, tagging :kontor.posting/ledger only when `ledger`
   is non-nil (ADR-021 — a nil ledger is the primary book)."
  [account amount commodity ledger]
  (cond-> {:kontor.posting/account   account
           :kontor.posting/amount    amount
           :kontor.posting/commodity commodity}
    ledger (assoc :kontor.posting/ledger ledger)))

(defn- build
  "Assemble + build a transaction from a journal/date/narration header
   and a vector of posting maps. Drops zero-amount postings (no
   zero-value journal lines).

   When the header carries `:posted-at`, the entry is built *sealed*:
   `:kontor.transaction/state :posted` + `:kontor.transaction/posted-at`, and every
   posting is stamped `:kontor.posting/posted-at` (the propagation the
   `kontor.sealing` middleware enforces). Omit `:posted-at` for a
   draft entry the caller seals later.

   A header `:tx-tempid` (ADR-067) is threaded to
   `kontor.posting/build-transaction` — pass a distinct string when
   composing several entries into one `kontor.process` tx-data."
  [{:keys [journal date narration external-id posted-at tx-tempid]} postings]
  (when-not journal (throw (ex-info ":journal required" {})))
  (when-not date    (throw (ex-info ":date required" {})))
  (let [nonzero (filterv (fn [p]
                           (not (zero? (.signum ^java.math.BigDecimal
                                        (:kontor.posting/amount p)))))
                         postings)
        nonzero (if posted-at
                  (mapv #(assoc % :kontor.posting/posted-at posted-at) nonzero)
                  nonzero)]
    (posting/build-transaction
     (cond-> {:transaction (cond-> {:kontor.transaction/journal journal
                                    :kontor.transaction/effective-date date}
                             narration   (assoc :kontor.transaction/narration narration)
                             external-id (assoc :kontor.transaction/external-id external-id)
                             posted-at   (assoc :kontor.transaction/state :posted
                                                :kontor.transaction/posted-at posted-at))
              :postings nonzero}
       tx-tempid (assoc :tx-tempid tx-tempid)))))

;; ============================================================================
;; Capitalisation
;; ============================================================================

(defn plan-capitalisation
  "Build the acquisition entry: `Dr :kontor.asset/asset-account /
   Cr <credit-account>`. The credit side — AP, bank, an asset-
   clearing account — is caller-supplied.

   Required: :asset (code or eid), :credit-account (eid),
             :journal, :date
   Optional: :amount (default = the asset's :acquisition-cost),
             :commodity (default = the asset's :acquisition-commodity),
             :ledger (a nil ledger is the primary book — for a
             multi-book asset, call once per ledger that carries the
             gross cost), :narration, :external-id, :posted-at (seal the entry)"
  [db {:keys [asset credit-account amount commodity ledger] :as spec}]
  (when-not credit-account (throw (ex-info ":credit-account required" {})))
  (let [asset-eid (asset/resolve-asset db asset)
        _ (when-not asset-eid (throw (ex-info "Asset not found" {:spec asset})))
        a (d/pull db [:kontor.asset/acquisition-cost
                      {:kontor.asset/acquisition-commodity [:db/id]}
                      {:kontor.asset/asset-account [:db/id]}]
                  asset-eid)
        amt (or amount (:kontor.asset/acquisition-cost a))
        com (or commodity (:db/id (:kontor.asset/acquisition-commodity a)))
        asset-account (:db/id (:kontor.asset/asset-account a))]
    (build spec
           [(posting* asset-account amt com ledger)
            (posting* credit-account (.negate ^java.math.BigDecimal amt) com ledger)])))

;; ============================================================================
;; Depreciation charge
;; ============================================================================

(defn plan-depreciation-charge
  "Build one period's depreciation entry for a book:
   `Dr :kontor.asset/expense-account / Cr :kontor.asset/accumulated-account`,
   both tagged with the book's `:ledger`.

   The ADR-055 runner calls this per pending `:schedule` occurrence.

   Required: :book (eid or [asset ledger]), :amount, :journal, :date
   Optional: :commodity (default = the book's :commodity),
             :narration, :external-id, :posted-at (seal the entry),
             :tx-tempid (ADR-067 — distinct string per charge when
             composing into one process tx-data)"
  [db {:keys [book amount commodity] :as spec}]
  (when-not amount (throw (ex-info ":amount required" {})))
  (let [{:keys [ledger expense-account accumulated-account] book-com :commodity}
        (book-context db book)
        com (or commodity book-com)]
    (build spec
           [(posting* expense-account amount com ledger)
            (posting* accumulated-account (.negate ^java.math.BigDecimal amount)
                      com ledger)])))

;; ============================================================================
;; Disposal
;; ============================================================================

(defn plan-disposal
  "Build the disposal entry for a book:
   `Dr <proceeds-account> + Dr :kontor.asset/accumulated-account /
    Cr :kontor.asset/asset-account` ± gain/loss.

   Gain/loss = `proceeds − net-book-value`, where NBV =
   `acquisition-cost − accumulated-depreciation` for THIS book
   (HGB NBV ≠ tax NBV → disposal is a per-book posting). A gain
   credits `<gain-account>`; a loss debits `<loss-account>`.

   Required: :book, :journal, :date
   Optional: :asset-account-cost (default = the asset's
             :acquisition-cost — see note below),
             :proceeds (default 0M — a scrap/write-off),
             :proceeds-account (required iff :proceeds > 0),
             :gain-account (required iff a gain results),
             :loss-account (required iff a loss results),
             :commodity (default = the book's :commodity),
             :narration, :external-id, :posted-at (seal the entry)

   `:asset-account-cost` defaults to the asset's `:acquisition-cost`
   (the whole-asset disposal — the common case, matching the cost
   side of `net-book-value`). A partial disposal overrides it with
   the disposed portion."
  [db {:keys [book proceeds proceeds-account gain-account loss-account
              commodity asset-account-cost]
       :or {proceeds 0M}
       :as spec}]
  (let [{:keys [ledger asset-account accumulated-account
                acquisition-cost] book-com :commodity}
        (book-context db book)
        asset-account-cost (or asset-account-cost acquisition-cost)
        _ (when-not asset-account-cost
            (throw (ex-info ":asset-account-cost required (the asset has no :acquisition-cost)" {})))
        com (or commodity book-com)
        accumulated (depreciation/accumulated-depreciation db book)
        nbv (.subtract ^java.math.BigDecimal asset-account-cost accumulated)
        gain-loss (.subtract ^java.math.BigDecimal proceeds nbv)
        gain? (pos? (.signum gain-loss))
        loss? (neg? (.signum gain-loss))]
    (when (and (pos? (.signum ^java.math.BigDecimal proceeds))
               (not proceeds-account))
      (throw (ex-info ":proceeds-account required when :proceeds > 0" {})))
    (when (and gain? (not gain-account))
      (throw (ex-info ":gain-account required — disposal results in a gain"
                      {:gain gain-loss})))
    (when (and loss? (not loss-account))
      (throw (ex-info ":loss-account required — disposal results in a loss"
                      {:loss (.negate ^java.math.BigDecimal gain-loss)})))
    (build spec
           (cond-> [(posting* proceeds-account proceeds com ledger)
                    (posting* accumulated-account accumulated com ledger)
                    (posting* asset-account
                              (.negate ^java.math.BigDecimal asset-account-cost)
                              com ledger)]
             gain? (conj (posting* gain-account
                                   (.negate ^java.math.BigDecimal gain-loss)
                                   com ledger))
             loss? (conj (posting* loss-account
                                   (.negate ^java.math.BigDecimal gain-loss)
                                   com ledger))))))

;; ============================================================================
;; Impairment
;; ============================================================================

(defn plan-impairment
  "Build the impairment write-down entry for a book:
   `Dr <impairment-expense-account> / Cr :kontor.asset/accumulated-account`
   (IAS 36 / HGB §253 außerplanmäßige Abschreibung).

   Required: :book, :amount (the impairment loss),
             :impairment-expense-account, :journal, :date
   Optional: :commodity, :narration, :external-id, :posted-at (seal the entry)"
  [db {:keys [book amount impairment-expense-account commodity] :as spec}]
  (when-not amount (throw (ex-info ":amount required" {})))
  (when-not impairment-expense-account
    (throw (ex-info ":impairment-expense-account required" {})))
  (let [{:keys [ledger accumulated-account] book-com :commodity}
        (book-context db book)
        com (or commodity book-com)]
    (build spec
           [(posting* impairment-expense-account amount com ledger)
            (posting* accumulated-account (.negate ^java.math.BigDecimal amount)
                      com ledger)])))

;; ============================================================================
;; Revaluation
;; ============================================================================

(defn plan-revaluation
  "Build the revaluation entry for a book:
   `Dr :kontor.asset/asset-account / Cr <revaluation-surplus-account>` for
   an upward revaluation (IAS 16 revaluation model — the surplus is
   an OCI/equity line ADR-056's equity statement picks up). A
   negative `:amount` produces the symmetric downward entry.

   The IAS 16 nuance that a downward revaluation first reverses any
   prior surplus and only then hits P&L is a consumer/l10n concern;
   this builder does the symmetric entry.

   Required: :book, :amount (signed — positive = upward),
             :revaluation-surplus-account, :journal, :date
   Optional: :commodity, :narration, :external-id, :posted-at (seal the entry)"
  [db {:keys [book amount revaluation-surplus-account commodity] :as spec}]
  (when-not amount (throw (ex-info ":amount required" {})))
  (when-not revaluation-surplus-account
    (throw (ex-info ":revaluation-surplus-account required" {})))
  (let [{:keys [ledger asset-account] book-com :commodity}
        (book-context db book)
        com (or commodity book-com)]
    (build spec
           [(posting* asset-account amount com ledger)
            (posting* revaluation-surplus-account
                      (.negate ^java.math.BigDecimal amount) com ledger)])))
