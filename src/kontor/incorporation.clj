(ns kontor.incorporation
  "The Phase 3 keystone — `incorporate-tx-data` / `incorporate!`. Per
   note 107 §2.3 / ADR-103 the individual→corporation continuum: the
   founder contributes property (cash, equipment, the going concern
   of a sole-prop) to a newly-formed corporation in exchange for
   equity. The substrate captures four things at once:

   1. **New entity row** — the corp's `:entity` is materialised in
      the same DB (single-DB Shape A; the cross-DB Shape B is an
      ADR-074 saga and lives in `kontor.side-effect.cross`).

   2. **Corp opening books** — debit the contributed assets (Cash,
      Equipment, Receivables, …); credit Common Stock + any
      Additional Paid-In Capital. Assumed liabilities credit the
      respective liability account.

   3. **Founder's Investment-in-NewCo holding** — debit the
      founder's `Investment:<NewCo>` account at BASIS (note 107
      §2.5); credit the contributed asset accounts on the founder's
      side. THIS IS KEY: the founder's basis in the new shares is
      the basis of the property they contributed, NOT the FMV.

   4. **Optional `:disposal`** — when a contributed asset's FMV
      differs from its basis, a deemed disposal is recorded
      (`:disposal/kind :incorporation-contribution`) on the founder's
      books. Most jurisdictions defer recognition (US §351, CA §85,
      DE §20 UmwStG, FR Apport-cession, JP §50 — these are
      `:elective-regime` keywords the CGT provider routes on). v1
      records the disposal unconditionally; the consumer's CGT
      provider applies the deferral elective.

   ## Two shapes

   - **Shape A (single DB)** — both founder and corp `:entity` rows
     in the same DB. `incorporate-tx-data` builds ONE balanced
     tx-data, validated per-entity by `kontor.posting` (ADR-031). The
     common case.

   - **Shape B (cross-DB)** — founder personal DB and corp DB are
     SEPARATE physical DBs. `incorporate-cross!` (NOT shipped here —
     wraps ADR-074 `kontor.side-effect.cross`) is the saga primitive.
     Deferred to a future stage; cross-DB sagas exist for other
     consumers (note 71 / ADR-074) so the precedent is set.

   ## What this namespace does NOT do

   - **Validate corporate-law eligibility** — that's the consumer's
     job (a US single-member LLC has different `:kontor.entity/legal-form`
     than a DE GmbH).
   - **Compute the FMV / basis differences** — the consumer supplies
     `:basis` per contribution; FMV is inferred as `:amount`.
   - **Issue specific share classes** — v1 treats all contributed
     equity as one class. Multi-class / preferred / convertible
     instruments are a future companion.
   - **Run the CGT provider** — the disposal is recorded; the
     consumer wires the CGT provider per ADR-103.

   Note 107 §2.3 / ADR-095 (kontor.book verb facade)."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.disposal :as disposal]
            [kontor.posting :as posting]
            [kontor.validation :as validation]))

;; ============================================================================
;; The single-DB builder
;; ============================================================================

(defn- ->bigdec ^java.math.BigDecimal [x]
  (cond
    (nil? x)         nil
    (decimal? x)     x
    (number? x)      (bigdec x)
    :else            (throw (ex-info "kontor.incorporation: expected number"
                                     {:got x :class (class x)}))))

(defn- resolve-account
  "Resolve an account spec (eid, `[:kontor.account/path …]`, `[:kontor.account/code …]`)
   to an eid. Throws on miss."
  [db spec]
  (or (cond
        (integer? spec) spec
        (vector? spec)  (:db/id (d/entity db spec))
        :else           nil)
      (throw (ex-info "kontor.incorporation: account not found"
                      {:account spec}))))

(defn- corp-side-postings
  "Build the corp-side leg: Dr each contributed asset / Cr each
   assumed liability / Cr Common Stock + Additional Paid-In Capital."
  [db {:keys [contributions assumed-liabilities common-stock-account
              additional-paid-in-capital-account corp-entity
              shares-issued]}]
  (let [stock-amount (->bigdec (or (:par shares-issued)
                                   (throw (ex-info "shares-issued needs :par" {}))))
        share-count  (->bigdec (or (:count shares-issued)
                                   (throw (ex-info "shares-issued needs :count" {}))))
        par-total    (* stock-amount share-count)
        contrib-sum  (reduce + 0M (map (comp ->bigdec :amount) contributions))
        liab-sum     (reduce + 0M (map (comp ->bigdec :amount) assumed-liabilities))
        net-equity   (- contrib-sum liab-sum)
        apic         (- net-equity par-total)]
    (when (neg? apic)
      (throw (ex-info "incorporate: net contribution < par × shares (would imply discount on stock)"
                      {:contrib-sum contrib-sum :liab-sum liab-sum
                       :par-total par-total :apic apic})))
    (concat
     ;; Dr each contributed asset on the corp's books, at FMV (:amount).
     (mapv (fn [{:keys [account amount commodity]}]
             {:kontor.posting/account   (resolve-account db account)
              :kontor.posting/amount    (->bigdec amount)
              :kontor.posting/commodity commodity
              :kontor.posting/entity    corp-entity})
           contributions)
     ;; Cr each assumed liability on the corp's books.
     (mapv (fn [{:keys [account amount commodity]}]
             {:kontor.posting/account   (resolve-account db account)
              :kontor.posting/amount    (- (->bigdec amount))
              :kontor.posting/commodity commodity
              :kontor.posting/entity    corp-entity})
           assumed-liabilities)
     ;; Cr Common Stock for par × shares.
     [{:kontor.posting/account   (resolve-account db common-stock-account)
       :kontor.posting/amount    (- par-total)
       :kontor.posting/commodity (-> contributions first :commodity)
       :kontor.posting/entity    corp-entity}]
     ;; Cr Additional Paid-In Capital for the residual, when positive.
     (when (pos? apic)
       [{:kontor.posting/account   (resolve-account db additional-paid-in-capital-account)
         :kontor.posting/amount    (- apic)
         :kontor.posting/commodity (-> contributions first :commodity)
         :kontor.posting/entity    corp-entity}]))))

(defn- founder-side-postings
  "Build the founder-side leg: Dr Investment-in-NewCo at BASIS
   (note 107 §2.5); Cr the founder's contributed asset accounts at
   their basis (NBV); Dr the founder's liability accounts (for
   liabilities the corp assumed)."
  [db {:keys [founder-contributions founder-assumed-liabilities
              founder-investment-account founder-entity
              shares-issued]}]
  (let [basis-sum (reduce + 0M (map (comp ->bigdec :basis) founder-contributions))
        liab-sum  (reduce + 0M (map (comp ->bigdec :amount) founder-assumed-liabilities))
        net-basis (- basis-sum liab-sum)]
    (concat
     ;; Dr Investment-in-NewCo at the net basis of contributed property
     ;; (basis − assumed liabilities). This IS the founder's basis in
     ;; the new shares.
     [{:kontor.posting/account   (resolve-account db founder-investment-account)
       :kontor.posting/amount    net-basis
       :kontor.posting/commodity (-> founder-contributions first :commodity)
       :kontor.posting/entity    founder-entity}]
     ;; Cr each contributed asset at its basis.
     (mapv (fn [{:keys [account basis commodity]}]
             {:kontor.posting/account   (resolve-account db account)
              :kontor.posting/amount    (- (->bigdec basis))
              :kontor.posting/commodity commodity
              :kontor.posting/entity    founder-entity})
           founder-contributions)
     ;; Dr each assumed liability (settles the founder's payable).
     (mapv (fn [{:keys [account amount commodity]}]
             {:kontor.posting/account   (resolve-account db account)
              :kontor.posting/amount    (->bigdec amount)
              :kontor.posting/commodity commodity
              :kontor.posting/entity    founder-entity})
           founder-assumed-liabilities))))

;; ============================================================================
;; Disposal emission — note 107 §2.5 + §3.5
;; ============================================================================

(defn- disposal-tx-data-for-contribution
  "When `:basis` ≠ `:amount` for a contribution, emit a
   `:disposal/kind :incorporation-contribution` on the founder's
   books. The CGT provider applies any deferral elective
   (US §351 / CA §85 / DE §20 UmwStG / FR Apport-cession / JP §50)
   based on `:elective-regime`."
  [db {:keys [founder-entity effective-date recorded-by-uid
              external-id-prefix corp-entity]} contribution i]
  (let [{:keys [account amount commodity basis elective-regime exemption-claimed]}
        contribution
        amount' (->bigdec amount)
        basis'  (->bigdec basis)]
    (when (and basis' (not= amount' basis'))
      (disposal/record-disposal-tx-data
       db {:entity          founder-entity
           :external-id     (str external-id-prefix "-disp-" i)
           :kind            :incorporation-contribution
           :subject         (resolve-account db account)
           :subject-kind    :fixed-asset
           :acquired-on     (or (:acquired-on contribution) effective-date)
           :disposed-on     effective-date
           :proceeds        {:amount amount' :commodity commodity}
           :basis           {:amount basis'  :commodity commodity}
           :elective-regime elective-regime
           :exemption-claimed exemption-claimed
           :notes           (str "Incorporation contribution to entity " corp-entity)
           :recorded-by-uid recorded-by-uid
           :tempid          (str external-id-prefix "-disp-tempid-" i)}))))

;; ============================================================================
;; The pure builder (ADR-068)
;; ============================================================================

(defn incorporate-tx-data
  "Pure tx-data builder for `incorporate!` (single-DB Shape A per
   note 107 §2.3). Returns a tx-data vector covering:

     - the new `:entity` row for the corp
     - the corp-side opening posting (Dr contributed assets +
       Cr Common Stock + Cr APIC + Cr assumed liabilities)
     - the founder-side posting (Dr Investment-in-NewCo at BASIS +
       Cr contributed assets at BASIS + Dr assumed liabilities)
     - 0+ `:disposal` rows for contributions where basis ≠ amount

   REQUIRED opts:

     :corp-spec            {:code :name :functional-commodity
                            :legal-form :country
                            :registration-status :parent-entity?}
     :founder-entity       resolvable ref of the founder `:entity`
     :journal              the journal eid / lookup-ref to post under
     :effective-date       the incorporation date
     :contributions        [{:account :amount :commodity :basis?
                            :elective-regime? :exemption-claimed?
                            :acquired-on?} ...]
                           — the corp-side asset receipts (one per
                           contributed asset); `:basis` defaults to
                           `:amount` (no deemed disposal)
     :founder-contributions[{:account :basis :commodity} ...]
                           — the founder-side credits (basis-valued)
                           in the SAME ORDER as `:contributions`
     :assumed-liabilities  [{:account :amount :commodity}]
                           — liabilities the corp assumes from the
                           founder; same order on both sides
     :founder-assumed-liabilities  same shape, on founder's books
                           (debits the founder's settled payables)
     :common-stock-account             ref to Equity:Common-Stock on corp books
     :additional-paid-in-capital-account ref to Equity:APIC on corp books
                           (only consulted when net contribution > par-total)
     :founder-investment-account       ref to Investment-in-<NewCo> on
                                       founder books
     :shares-issued        {:par :count}
     :recorded-by-uid      acting user
     :external-id          stable id for the transaction

   OPTIONAL:
     :narration            free-text annotation
     :owner-partner        founder's `:partner` ref (recorded on the
                           transaction for shareholder traceability)
     :audit-doc            seq of `:audit-doc` refs (incorporation docs)
     :tx-tempid            (default \"incorp-1\")

   Note 107 §2.3 / §2.5 / §3.5."
  [db {:keys [corp-spec founder-entity journal effective-date
              contributions founder-contributions
              assumed-liabilities founder-assumed-liabilities
              common-stock-account additional-paid-in-capital-account
              founder-investment-account shares-issued
              recorded-by-uid external-id narration owner-partner
              audit-doc tx-tempid]
       :or   {tx-tempid "incorp-1"
              assumed-liabilities []
              founder-assumed-liabilities []}}]
  (when-not corp-spec       (throw (ex-info ":corp-spec required" {})))
  (when-not founder-entity  (throw (ex-info ":founder-entity required" {})))
  (when-not journal         (throw (ex-info ":journal required" {})))
  (when-not effective-date  (throw (ex-info ":effective-date required" {})))
  (when-not (seq contributions) (throw (ex-info ":contributions must be non-empty" {})))
  (when-not (seq founder-contributions)
    (throw (ex-info ":founder-contributions must be non-empty" {})))
  (when-not (= (count contributions) (count founder-contributions))
    (throw (ex-info ":contributions and :founder-contributions must align" {})))
  (when-not common-stock-account (throw (ex-info ":common-stock-account required" {})))
  (when-not founder-investment-account
    (throw (ex-info ":founder-investment-account required" {})))
  (when-not shares-issued        (throw (ex-info ":shares-issued required" {})))
  (when-not recorded-by-uid      (throw (ex-info ":recorded-by-uid required" {})))
  (when-not external-id          (throw (ex-info ":external-id required" {})))
  (let [corp-tempid   (str tx-tempid "-corp-entity")
        ;; The corp-spec is mostly free-form — only the required attrs
        ;; (code, name, functional-commodity) are mandatory. Consumers
        ;; needing `:kontor.entity/country` (which is a REF to a `:country`
        ;; entity, not a string) pass an already-resolved ref.
        corp-row      (-> {:db/id                       corp-tempid
                           :kontor.entity/code                 (:code corp-spec)
                           :kontor.entity/name                 (:name corp-spec)
                           :kontor.entity/functional-commodity (:functional-commodity corp-spec)
                           :kontor.entity/kind                 (or (:kind corp-spec) :company)
                           :kontor.entity/active               true}
                          (cond->
                           (:legal-form corp-spec)
                            (assoc :kontor.entity/legal-form (:legal-form corp-spec))
                            (:country corp-spec)
                            (assoc :kontor.entity/country (:country corp-spec))
                            (:registration-status corp-spec)
                            (assoc :kontor.entity/registration-status (:registration-status corp-spec))
                            (:parent-entity corp-spec)
                            (assoc :kontor.entity/parent-entity (:parent-entity corp-spec))))
        opts          {:corp-entity corp-tempid
                       :founder-entity founder-entity
                       :common-stock-account common-stock-account
                       :additional-paid-in-capital-account additional-paid-in-capital-account
                       :founder-investment-account founder-investment-account
                       :contributions contributions
                       :assumed-liabilities assumed-liabilities
                       :founder-contributions founder-contributions
                       :founder-assumed-liabilities founder-assumed-liabilities
                       :shares-issued shares-issued}
        postings      (concat (corp-side-postings db opts)
                              (founder-side-postings db opts))
        tx            (cond-> {:kontor.transaction/journal       journal
                               :kontor.transaction/effective-date effective-date
                               :kontor.transaction/external-id    external-id}
                        narration     (assoc :kontor.transaction/narration narration)
                        owner-partner (assoc :kontor.transaction/partner owner-partner))
        main-tx-data  (posting/build-transaction
                       {:transaction tx
                        :postings    postings
                        :tx-tempid   tx-tempid})
        disposal-txs  (->> (map-indexed
                            (fn [i c]
                              (disposal-tx-data-for-contribution
                               db
                               {:founder-entity founder-entity
                                :effective-date effective-date
                                :recorded-by-uid recorded-by-uid
                                :external-id-prefix external-id
                                :corp-entity corp-tempid}
                               c i))
                            contributions)
                           (remove nil?)
                           (apply concat))]
    (into [corp-row] (concat main-tx-data disposal-txs))))

;; ============================================================================
;; The bang wrapper (ADR-068)
;; ============================================================================

(defn incorporate!
  "Single-DB incorporation — founder + corp `:entity`s both in `conn`.
   Builds via `incorporate-tx-data`, applies `:tx/valid-from`
   defaulting to `:effective-date`, routes through
   `kontor.validation`. See `incorporate-tx-data` for opts.

   Returns the transact result. The new corp's `:entity` eid is
   reachable via `(d/entity (d/db conn) [:kontor.entity/code <corp-code>])`."
  [conn {:keys [vt-from vt-to effective-date] :as opts}]
  (validation/transact-with-validation
   conn (kbt/with-vt (incorporate-tx-data (d/db conn) opts)
                     (or vt-from effective-date)
                     (or vt-to kbt/forever))))
