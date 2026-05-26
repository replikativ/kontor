(ns kontor.partner
  "Public surface of the `kontor-partner` companion — ADR-033.

   Resolution, subtype pulls, role / contact-mech queries, and
   relationship traversal.

   Effective-date semantics: junction rows carry `:from-date` and
   optional `:thru-date`. A junction is *active-as-of* an instant `d`
   iff `from-date <= d` AND (`thru-date` is nil OR `d < thru-date`).
   `:thru-date` is exclusive — a row with thru-date = 2026-01-01 is
   not active on that day. This matches OFBiz / Tryton convention.

   Bitemporal semantics (ADR-008): callers pass the datahike `db` at
   the desired transaction-time snapshot. Junction-time validity is
   layered on top via the `:as-of` opts parameter where applicable;
   default is `now`."
  (:require [datahike.api :as d]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  "Resolve a partner entity-id by `:kontor.partner/external-id`."
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :kontor.partner/external-id ?xid]]
       db external-id))

(defn resolve-partner
  "Coerce `spec` to a partner entity-id:
     - nil       → nil
     - string    → looked up by :kontor.partner/external-id
     - any other → returned as-is (assumed eid)."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-external-id db spec)
    :else          spec))

;; ============================================================================
;; Subtype access
;; ============================================================================

(defn person
  "Pull the :person subtype map associated with `partner` (entity-id
   or string `:kontor.partner/external-id`). Returns nil if the partner has
   no :person subtype (i.e. is an org)."
  [db partner]
  (when-let [pid (resolve-partner db partner)]
    (when-let [person-eid (d/q '[:find ?p .
                                 :in $ ?partner
                                 :where [?p :kontor.person/partner ?partner]]
                               db pid)]
      (d/pull db '[*] person-eid))))

(defn org
  "Pull the :org subtype map associated with `partner` (entity-id or
   string `:kontor.partner/external-id`). Returns nil if the partner has no
   :org subtype (i.e. is a person)."
  [db partner]
  (when-let [pid (resolve-partner db partner)]
    (when-let [org-eid (d/q '[:find ?o .
                              :in $ ?partner
                              :where [?o :kontor.org/partner ?partner]]
                            db pid)]
      (d/pull db '[*] org-eid))))

;; ============================================================================
;; Effective-date predicate
;; ============================================================================

(defn active-as-of?
  "True iff a junction row is active on the instant `d`.
   `from` is required (instant); `thru` may be nil (open-ended).

   Convention: from-date inclusive, thru-date exclusive."
  [^java.util.Date from thru ^java.util.Date d]
  (let [thru (when (instance? java.util.Date thru) thru)]
    (and from
         (>= (.compareTo d from) 0)
         (or (nil? thru) (< (.compareTo d thru) 0)))))

(defn- now-or
  ^java.util.Date [^java.util.Date d]
  (or d (java.util.Date.)))

;; ============================================================================
;; Roles
;; ============================================================================

(defn roles-of
  "All :kontor.partner-role/role-type values currently held by `partner`,
   as a set. Pass `:as-of` to query at a different valid-time
   instant; default is `now`."
  ([db partner] (roles-of db partner nil))
  ([db partner opts]
   (let [as-of (now-or (:as-of opts))
         pid (resolve-partner db partner)
         rows (d/q '[:find ?role ?from ?thru
                     :in $ ?partner
                     :where
                     [?r :kontor.partner-role/partner ?partner]
                     [?r :kontor.partner-role/role-type ?role]
                     [?r :kontor.partner-role/from-date ?from]
                     [(get-else $ ?r :kontor.partner-role/thru-date :__none) ?thru]]
                   db pid)]
     (->> rows
          (filter (fn [[_ from thru]] (active-as-of? from thru as-of)))
          (map first)
          set))))

(defn has-role?
  "True iff `partner` holds `role-type` at the given instant.
   Defaults to `now`."
  ([db partner role-type] (has-role? db partner role-type nil))
  ([db partner role-type opts]
   (contains? (roles-of db partner opts) role-type)))

(defn partners-with-role
  "All partner entity-ids currently holding `role-type` (active as of
   `:as-of`, default `now`)."
  ([db role-type] (partners-with-role db role-type nil))
  ([db role-type opts]
   (let [as-of (now-or (:as-of opts))
         rows (d/q '[:find ?partner ?from ?thru
                     :in $ ?role
                     :where
                     [?r :kontor.partner-role/role-type ?role]
                     [?r :kontor.partner-role/partner ?partner]
                     [?r :kontor.partner-role/from-date ?from]
                     [(get-else $ ?r :kontor.partner-role/thru-date :__none) ?thru]]
                   db role-type)]
     (->> rows
          (filter (fn [[_ from thru]] (active-as-of? from thru as-of)))
          (map first)
          set))))

;; ============================================================================
;; Contact mechanisms
;; ============================================================================

(defn contact-mechs-of
  "All contact-mech entity-ids associated with `partner` (active as
   of `:as-of`, default `now`). Returns a set."
  ([db partner] (contact-mechs-of db partner nil))
  ([db partner opts]
   (let [as-of (now-or (:as-of opts))
         pid (resolve-partner db partner)
         rows (d/q '[:find ?cm ?from ?thru
                     :in $ ?partner
                     :where
                     [?j :kontor.partner-contact-mech/partner ?partner]
                     [?j :kontor.partner-contact-mech/contact-mech ?cm]
                     [?j :kontor.partner-contact-mech/from-date ?from]
                     [(get-else $ ?j :kontor.partner-contact-mech/thru-date :__none) ?thru]]
                   db pid)]
     (->> rows
          (filter (fn [[_ from thru]] (active-as-of? from thru as-of)))
          (map first)
          set))))

(defn contact-mech-by-purpose
  "The contact-mech entity-id serving `purpose-type` for `partner`
   at `:as-of`. If multiple contact-mechs serve the same purpose
   simultaneously (e.g. two billing addresses during a transition),
   returns the one with the latest `from-date`. Returns nil if none."
  ([db partner purpose-type]
   (contact-mech-by-purpose db partner purpose-type nil))
  ([db partner purpose-type opts]
   (let [as-of (now-or (:as-of opts))
         pid (resolve-partner db partner)
         rows (d/q '[:find ?cm ?from ?thru
                     :in $ ?partner ?purpose
                     :where
                     [?p :kontor.partner-contact-mech-purpose/partner ?partner]
                     [?p :kontor.partner-contact-mech-purpose/purpose-type ?purpose]
                     [?p :kontor.partner-contact-mech-purpose/contact-mech ?cm]
                     [?p :kontor.partner-contact-mech-purpose/from-date ?from]
                     [(get-else $ ?p :kontor.partner-contact-mech-purpose/thru-date :__none) ?thru]]
                   db pid purpose-type)]
     (->> rows
          (filter (fn [[_ from thru]] (active-as-of? from thru as-of)))
          (sort-by (fn [[_ from _]] from) #(compare %2 %1))
          ffirst))))

(defn primary-email
  "The :kontor.email-address/address string serving :primary-email for
   `partner`, or nil. Walks contact-mech-by-purpose then resolves
   the typed payload."
  ([db partner] (primary-email db partner nil))
  ([db partner opts]
   (when-let [cm-eid (contact-mech-by-purpose db partner :primary-email opts)]
     (d/q '[:find ?addr .
            :in $ ?cm
            :where
            [?e :kontor.email-address/contact-mech ?cm]
            [?e :kontor.email-address/address ?addr]]
          db cm-eid))))

(defn primary-postal-address
  "Pull the :postal-address subtype map for whichever contact-mech
   currently serves :primary-location for `partner`. Returns nil if
   none. The pull pattern includes structured :country / :state refs
   via wildcard."
  ([db partner] (primary-postal-address db partner nil))
  ([db partner opts]
   (when-let [cm-eid (contact-mech-by-purpose db partner :primary-location opts)]
     (when-let [addr-eid (d/q '[:find ?a .
                                :in $ ?cm
                                :where [?a :kontor.postal-address/contact-mech ?cm]]
                              db cm-eid)]
       (d/pull db '[*] addr-eid)))))

;; ============================================================================
;; Relationships
;; ============================================================================

(defn relationships-of
  "All relationship entities where `partner` is either the from-side
   OR the to-side. Returns a vector of pulled relationship maps.
   Filters by validity at `:as-of` (default `now`)."
  ([db partner] (relationships-of db partner nil))
  ([db partner opts]
   (let [as-of (now-or (:as-of opts))
         pid (resolve-partner db partner)
         eids (d/q '[:find [?r ...]
                     :in $ ?partner
                     :where
                     (or [?r :kontor.partner-relationship/partner-from ?partner]
                         [?r :kontor.partner-relationship/partner-to ?partner])]
                   db pid)
         rows (map (fn [r] (d/pull db '[*] r)) eids)]
     (->> rows
          (filter (fn [rel]
                    (active-as-of? (:kontor.partner-relationship/from-date rel)
                                   (:kontor.partner-relationship/thru-date rel)
                                   as-of)))
          vec))))

(defn relationships-from
  "All relationship rows originating at `partner` (i.e. partner is the
   from-side). Filters by validity. Returns pulled maps."
  ([db partner] (relationships-from db partner nil))
  ([db partner opts]
   (let [as-of (now-or (:as-of opts))
         pid (resolve-partner db partner)
         eids (d/q '[:find [?r ...]
                     :in $ ?partner
                     :where [?r :kontor.partner-relationship/partner-from ?partner]]
                   db pid)
         rows (map (fn [r] (d/pull db '[*] r)) eids)]
     (->> rows
          (filter (fn [rel]
                    (active-as-of? (:kontor.partner-relationship/from-date rel)
                                   (:kontor.partner-relationship/thru-date rel)
                                   as-of)))
          vec))))

(defn relationships-to
  "All relationship rows arriving at `partner` (i.e. partner is the
   to-side). Filters by validity. Returns pulled maps."
  ([db partner] (relationships-to db partner nil))
  ([db partner opts]
   (let [as-of (now-or (:as-of opts))
         pid (resolve-partner db partner)
         eids (d/q '[:find [?r ...]
                     :in $ ?partner
                     :where [?r :kontor.partner-relationship/partner-to ?partner]]
                   db pid)
         rows (map (fn [r] (d/pull db '[*] r)) eids)]
     (->> rows
          (filter (fn [rel]
                    (active-as-of? (:kontor.partner-relationship/from-date rel)
                                   (:kontor.partner-relationship/thru-date rel)
                                   as-of)))
          vec))))

(defn relationships-of-type
  "Restrict `relationships-of` to a specific `:kontor.partner-relationship/
   relationship-type` keyword (e.g. :employment, :subsidiary)."
  ([db partner relationship-type]
   (relationships-of-type db partner relationship-type nil))
  ([db partner relationship-type opts]
   (->> (relationships-of db partner opts)
        (filter #(= relationship-type
                    (:kontor.partner-relationship/relationship-type %)))
        vec)))

(defn current-employer
  "For a :person-typed partner, the org partner where they currently
   hold an :employment relationship as the :employee side. Returns
   nil if no active employment. If a person holds multiple concurrent
   employments, returns the one with the highest
   `:kontor.partner-relationship/priority` (default ranking)."
  ([db partner] (current-employer db partner nil))
  ([db partner opts]
   (let [emps (->> (relationships-from db partner opts)
                   (filter #(= :employment
                               (:kontor.partner-relationship/relationship-type %)))
                   (sort-by #(or (:kontor.partner-relationship/priority %) 0) >))]
     (some-> emps first :kontor.partner-relationship/partner-to :db/id))))

(defn current-employees
  "For an :org-typed partner, the set of person partner-ids currently
   employed by this org via an :employment relationship as the
   :internal-organization to-side."
  ([db partner] (current-employees db partner nil))
  ([db partner opts]
   (->> (relationships-to db partner opts)
        (filter #(= :employment
                    (:kontor.partner-relationship/relationship-type %)))
        (map #(get-in % [:kontor.partner-relationship/partner-from :db/id]))
        (remove nil?)
        set)))

;; ============================================================================
;; ADR-068 — Party / contact-mech / relationship / role transactors
;;
;; Every transactor follows the kontor `*-tx-data` builder + `!`
;; wrapper convention (ADR-068): the pure builder returns a vector of
;; tx-ops, the `!` form routes through
;; `kontor.validation/transact-with-validation` so the kernel gate
;; stack (legal-hold + period-lock + sealing + status-machine + sum-
;; to-zero) fires uniformly.
;;
;; The schema does NOT install `:status-transition` seeds for
;; `:kontor.partner/status` or `:kontor.partner-relationship/status`, so those are
;; written as plain facet updates. If/when a future ADR adds the
;; seeds, `update-party!` + `end-relationship!` can switch to
;; `kontor.status-machine/record-status-change-tx-data` without a
;; callsite churn (the public arity stays the same).
;;
;; "Removal" of a partner-contact-mech association honours ADR-007
;; (no silent retract of consequential history) by setting
;; `:kontor.partner-contact-mech/thru-date` rather than retracting the
;; junction row. The audit chain documents the withdrawal.
;; ============================================================================

(defn- ^java.util.Date now-instant []
  (java.util.Date.))

(defn create-party-tx-data
  "Pure tx-data builder for `create-party!`. Returns a vector of
   tx-ops: one `:partner` map plus, when `:type` is `:person` or
   `:org`, a 1:1 subtype row joined by `:kontor.person/partner` or
   `:kontor.org/partner`.

   Required:
     :external-id  — string, unique :kontor.partner/external-id
     :type         — :person | :org (drives subtype + sub-attrs)
     :name         — string :kontor.partner/name

   Optional kernel attrs:
     :kind                — :customer | :vendor | :both
     :country-code        — ISO-3166 alpha-2
     :tax-id              — primary tax-id string

   Optional companion attrs:
     :status              — :enabled (default) | :disabled | :archived
     :preferred-commodity — ref/eid
     :description         — string
     :created-at          — instant (default now)

   Optional subtype attrs (passed through when type matches):
     :person — :first-name :middle-name :last-name :salutation
               :suffix :nickname :first-name-local :last-name-local
               :gender :birth-date :deceased-date :marital-status
               :national-id-type :national-id
     :org    — :legal-name :legal-form :trading-name
               :registration-number :duns :lei :ticker-symbol
               :exchange :annual-revenue :revenue-commodity
               :num-employees :incorporation-date :dissolution-date

   Optional `:tempid` (default `\"partner-1\"`) for cross-step
   composition; the subtype row tempid is `<tempid>-subtype`."
  [_db {:keys [external-id type name kind country-code tax-id
               status preferred-commodity description created-at
               person org tempid]
        :or   {tempid "partner-1"}}]
  (when-not external-id (throw (ex-info ":external-id required" {:type :kontor.partner/missing-external-id})))
  (when-not type        (throw (ex-info ":type required (:person or :org)"
                                        {:type :kontor.partner/missing-type})))
  (when-not name        (throw (ex-info ":name required" {:type :kontor.partner/missing-name})))
  (when-not (contains? #{:person :org} type)
    (throw (ex-info ":type must be :person or :org"
                    {:type :kontor.partner/invalid-type :got type})))
  (let [now (or created-at (now-instant))
        partner-row (cond-> {:db/id               tempid
                             :kontor.partner/external-id external-id
                             :kontor.partner/name        name
                             :kontor.partner/type        type
                             :kontor.partner/status      (or status :enabled)
                             :kontor.partner/created-at  now
                             :kontor.partner/modified-at now}
                      kind                (assoc :kontor.partner/kind kind)
                      country-code        (assoc :kontor.partner/country-code country-code)
                      tax-id              (assoc :kontor.partner/tax-id tax-id)
                      preferred-commodity (assoc :kontor.partner/preferred-commodity preferred-commodity)
                      description         (assoc :kontor.partner/description description))
        subtype-tempid (str tempid "-subtype")
        ;; Subtype rows are only emitted when the caller passes the
        ;; corresponding `:person` / `:org` sub-map. A `:partner` of
        ;; `:type :person` without any `:kontor.person/*` attrs is valid —
        ;; consumers may populate the subtype later via
        ;; `update-party!` (TODO) or a direct `add-person-attrs!`
        ;; transactor. This keeps the builder a strict 1-row
        ;; constructor when no subtype payload is supplied.
        person-row (when (and (= type :person) (seq person))
                     (cond-> {:db/id          subtype-tempid
                              :kontor.person/partner tempid}
                       (:first-name person)       (assoc :kontor.person/first-name (:first-name person))
                       (:middle-name person)      (assoc :kontor.person/middle-name (:middle-name person))
                       (:last-name person)        (assoc :kontor.person/last-name (:last-name person))
                       (:salutation person)       (assoc :kontor.person/salutation (:salutation person))
                       (:suffix person)           (assoc :kontor.person/suffix (:suffix person))
                       (:nickname person)         (assoc :kontor.person/nickname (:nickname person))
                       (:first-name-local person) (assoc :kontor.person/first-name-local (:first-name-local person))
                       (:last-name-local person)  (assoc :kontor.person/last-name-local (:last-name-local person))
                       (:gender person)           (assoc :kontor.person/gender (:gender person))
                       (:birth-date person)       (assoc :kontor.person/birth-date (:birth-date person))
                       (:deceased-date person)    (assoc :kontor.person/deceased-date (:deceased-date person))
                       (:marital-status person)   (assoc :kontor.person/marital-status (:marital-status person))
                       (:national-id-type person) (assoc :kontor.person/national-id-type (:national-id-type person))
                       (:national-id person)      (assoc :kontor.person/national-id (:national-id person))))
        org-row (when (and (= type :org) (seq org))
                  (cond-> {:db/id       subtype-tempid
                           :kontor.org/partner tempid}
                    (:legal-name org)          (assoc :kontor.org/legal-name (:legal-name org))
                    (:legal-form org)          (assoc :kontor.org/legal-form (:legal-form org))
                    (:trading-name org)        (assoc :kontor.org/trading-name (:trading-name org))
                    (:registration-number org) (assoc :kontor.org/registration-number (:registration-number org))
                    (:duns org)                (assoc :kontor.org/duns (:duns org))
                    (:lei org)                 (assoc :kontor.org/lei (:lei org))
                    (:ticker-symbol org)       (assoc :kontor.org/ticker-symbol (:ticker-symbol org))
                    (:exchange org)            (assoc :kontor.org/exchange (:exchange org))
                    (:annual-revenue org)      (assoc :kontor.org/annual-revenue (:annual-revenue org))
                    (:revenue-commodity org)   (assoc :kontor.org/revenue-commodity (:revenue-commodity org))
                    (:num-employees org)       (assoc :kontor.org/num-employees (:num-employees org))
                    (:incorporation-date org)  (assoc :kontor.org/incorporation-date (:incorporation-date org))
                    (:dissolution-date org)    (assoc :kontor.org/dissolution-date (:dissolution-date org))))]
    (cond-> [partner-row]
      person-row (conj person-row)
      org-row    (conj org-row))))

(defn create-party!
  "Create a `:partner` (+ matching `:person` or `:org` subtype row) in
   one tx. Routes through `transact-with-validation` (ADR-068).

   See `create-party-tx-data` for the option vocabulary."
  [conn opts]
  (validation/transact-with-validation
   conn (create-party-tx-data (d/db conn) opts)))

(defn update-party-tx-data
  "Pure tx-data builder for `update-party!`. Rewrites the named fields
   on an existing `:partner` row (resolved by eid or
   `:kontor.partner/external-id`). `:kontor.partner/modified-at` is stamped to
   `now` automatically unless `:modified-at` is supplied.

   Optional fields (each present-only-if-passed):
     :name :kind :status :country-code :tax-id
     :preferred-commodity :description :modified-at

   Throws if the partner does not resolve."
  [db spec {:keys [name kind status country-code tax-id
                   preferred-commodity description modified-at]
            :as   opts}]
  (let [eid (resolve-partner db spec)]
    (when-not eid
      (throw (ex-info "Partner not found"
                      {:type :kontor.partner/not-found :spec spec})))
    (when (empty? (dissoc opts :modified-at))
      (throw (ex-info "update-party! requires at least one field to change"
                      {:type :kontor.partner/empty-update :spec spec})))
    [(cond-> {:db/id eid
              :kontor.partner/modified-at (or modified-at (now-instant))}
       name                 (assoc :kontor.partner/name name)
       kind                 (assoc :kontor.partner/kind kind)
       status               (assoc :kontor.partner/status status)
       country-code         (assoc :kontor.partner/country-code country-code)
       tax-id               (assoc :kontor.partner/tax-id tax-id)
       preferred-commodity  (assoc :kontor.partner/preferred-commodity preferred-commodity)
       description          (assoc :kontor.partner/description description))]))

(defn update-party!
  "Mutate fields on an existing partner. Routes through the gate
   (ADR-068). See `update-party-tx-data` for the field vocabulary."
  [conn spec opts]
  (validation/transact-with-validation
   conn (update-party-tx-data (d/db conn) spec opts)))

;; ----------------------------------------------------------------------------
;; Contact mechanisms
;; ----------------------------------------------------------------------------

(def ^:private contact-mech-kinds
  "Discriminator values the schema endorses on `:kontor.contact-mech/type`."
  #{:postal :telecom :email :web :ftp})

(defn- contact-mech-tempid [base] (str base "-cm"))
(defn- contact-mech-payload-tempid [base] (str base "-cm-payload"))
(defn- junction-tempid [base] (str base "-junction"))
(defn- purpose-tempid [base i] (str base "-purpose-" i))

(defn- typed-payload-row
  "Build the typed subtype row that hangs off a `:contact-mech`.
   Returns nil for `:web` / `:ftp` (no schema-side subtype — payload
   goes into `:kontor.contact-mech/info-string` directly)."
  [kind payload-tempid cm-tempid payload]
  (case kind
    :postal (let [{:keys [to-name attn-name address1 address2 house-number
                          house-number-ext directions city postal-code
                          postal-code-ext county region state country
                          latitude longitude]} payload]
              (cond-> {:db/id                       payload-tempid
                       :kontor.postal-address/contact-mech cm-tempid}
                to-name           (assoc :kontor.postal-address/to-name to-name)
                attn-name         (assoc :kontor.postal-address/attn-name attn-name)
                address1          (assoc :kontor.postal-address/address1 address1)
                address2          (assoc :kontor.postal-address/address2 address2)
                house-number      (assoc :kontor.postal-address/house-number house-number)
                house-number-ext  (assoc :kontor.postal-address/house-number-ext house-number-ext)
                directions        (assoc :kontor.postal-address/directions directions)
                city              (assoc :kontor.postal-address/city city)
                postal-code       (assoc :kontor.postal-address/postal-code postal-code)
                postal-code-ext   (assoc :kontor.postal-address/postal-code-ext postal-code-ext)
                county            (assoc :kontor.postal-address/county county)
                region            (assoc :kontor.postal-address/region region)
                state             (assoc :kontor.postal-address/state state)
                country           (assoc :kontor.postal-address/country country)
                latitude          (assoc :kontor.postal-address/latitude latitude)
                longitude         (assoc :kontor.postal-address/longitude longitude)))
    :telecom (let [{:keys [country-code area-code contact-number extension
                           ask-for-name]} payload]
               (cond-> {:db/id                       payload-tempid
                        :kontor.telecom-number/contact-mech cm-tempid}
                 country-code   (assoc :kontor.telecom-number/country-code country-code)
                 area-code      (assoc :kontor.telecom-number/area-code area-code)
                 contact-number (assoc :kontor.telecom-number/contact-number contact-number)
                 extension      (assoc :kontor.telecom-number/extension extension)
                 ask-for-name   (assoc :kontor.telecom-number/ask-for-name ask-for-name)))
    :email (let [{:keys [address verified? bounced?]} payload]
             (cond-> {:db/id                      payload-tempid
                      :kontor.email-address/contact-mech cm-tempid}
               address                (assoc :kontor.email-address/address address)
               (some? verified?)      (assoc :kontor.email-address/verified? (boolean verified?))
               (some? bounced?)       (assoc :kontor.email-address/bounced? (boolean bounced?))))
    (:web :ftp) nil))

(defn add-contact-mech-tx-data
  "Pure tx-data builder for `add-contact-mech!`. Creates a
   `:contact-mech` root row with a typed subtype payload
   (`:postal-address` / `:telecom-number` / `:email-address`; `:web`
   and `:ftp` store the raw URL via `:kontor.contact-mech/info-string`),
   plus a `:partner-contact-mech` junction row linking the mech to
   `partner` from `from-date` (default now), plus optional
   `:partner-contact-mech-purpose` rows (one per `:purposes` entry).

   Required:
     :partner    — partner eid or external-id string
     :code       — string :kontor.contact-mech/code (unique-identity)
     :kind       — :postal | :telecom | :email | :web | :ftp
     :payload    — typed sub-map matching `:kind` (see schema for
                   field vocabulary); for `:web` / `:ftp` `payload`
                   may be `{:info-string \"…\"}`

   Optional:
     :from-date          — instant (default now); junction validity
     :thru-date          — instant; exclusive end of validity
     :role-type          — keyword stamped on the junction row
     :allow-solicitation?— boolean stamped on the junction row
     :verified?          — boolean stamped on the junction row
     :comments           — string stamped on the junction row
     :purposes           — sequence of purpose-types (keywords);
                           ONE `:partner-contact-mech-purpose` row
                           per entry, with the same `:from-date` /
                           `:thru-date`
     :info-string        — string (always written to
                           `:kontor.contact-mech/info-string`; useful for
                           `:web` / `:ftp` payload)
     :tempid             — base tempid prefix (default
                           `\"contact-mech-1\"`); the cm / payload /
                           junction rows get derived tempids."
  [db {:keys [partner code kind payload from-date thru-date role-type
              allow-solicitation? verified? comments purposes info-string
              tempid]
       :or {tempid "contact-mech-1"}}]
  (when-not partner (throw (ex-info ":partner required" {:type :kontor.contact-mech/missing-partner})))
  (when-not code    (throw (ex-info ":code required"    {:type :kontor.contact-mech/missing-code})))
  (when-not kind    (throw (ex-info ":kind required"    {:type :kontor.contact-mech/missing-kind})))
  (when-not (contains? contact-mech-kinds kind)
    (throw (ex-info ":kind must be :postal :telecom :email :web or :ftp"
                    {:type :kontor.contact-mech/invalid-kind :got kind})))
  (let [partner-eid (resolve-partner db partner)
        _ (when-not partner-eid
            (throw (ex-info "Partner not found"
                            {:type :kontor.partner/not-found :spec partner})))
        now           (now-instant)
        from          (or from-date now)
        cm-tempid     (contact-mech-tempid tempid)
        payload-tempid (contact-mech-payload-tempid tempid)
        j-tempid      (junction-tempid tempid)
        effective-info (or info-string
                           (when (contains? #{:web :ftp} kind)
                             (:info-string payload)))
        cm-row        (cond-> {:db/id                 cm-tempid
                               :kontor.contact-mech/code     code
                               :kontor.contact-mech/type     kind
                               :kontor.contact-mech/created-at  now
                               :kontor.contact-mech/modified-at now}
                        effective-info (assoc :kontor.contact-mech/info-string effective-info))
        payload-row   (typed-payload-row kind payload-tempid cm-tempid (or payload {}))
        junction-row  (cond-> {:db/id                             j-tempid
                               :kontor.partner-contact-mech/partner      partner-eid
                               :kontor.partner-contact-mech/contact-mech cm-tempid
                               :kontor.partner-contact-mech/from-date    from}
                        thru-date              (assoc :kontor.partner-contact-mech/thru-date thru-date)
                        role-type              (assoc :kontor.partner-contact-mech/role-type role-type)
                        (some? allow-solicitation?)
                        (assoc :kontor.partner-contact-mech/allow-solicitation?
                               (boolean allow-solicitation?))
                        (some? verified?)
                        (assoc :kontor.partner-contact-mech/verified? (boolean verified?))
                        comments               (assoc :kontor.partner-contact-mech/comments comments))
        purpose-rows  (map-indexed
                       (fn [i purpose]
                         (cond-> {:db/id (purpose-tempid tempid i)
                                  :kontor.partner-contact-mech-purpose/partner      partner-eid
                                  :kontor.partner-contact-mech-purpose/contact-mech cm-tempid
                                  :kontor.partner-contact-mech-purpose/purpose-type purpose
                                  :kontor.partner-contact-mech-purpose/from-date    from}
                           thru-date
                           (assoc :kontor.partner-contact-mech-purpose/thru-date thru-date)))
                       (or purposes []))]
    (cond-> [cm-row]
      payload-row (conj payload-row)
      :always     (conj junction-row)
      (seq purpose-rows) (into purpose-rows))))

(defn add-contact-mech!
  "Attach a new contact mechanism to `partner`. Routes through the
   gate (ADR-068). See `add-contact-mech-tx-data` for options."
  [conn opts]
  (validation/transact-with-validation
   conn (add-contact-mech-tx-data (d/db conn) opts)))

(defn remove-contact-mech-tx-data
  "Pure tx-data builder for `remove-contact-mech!`. Closes the
   currently-active `:partner-contact-mech` junction row(s) between
   `partner` and `contact-mech` by setting
   `:kontor.partner-contact-mech/thru-date` (default now).

   Per ADR-007 the substrate prefers `thru-date` closure over silent
   retraction: the junction history stays in the chain. If multiple
   currently-active junction rows exist (overlapping windows, role-
   type slices), every active row at `:as-of` is closed.

   Required:
     :partner       — eid or external-id
     :contact-mech  — eid or `:kontor.contact-mech/code` string

   Optional:
     :thru-date     — instant (default now); inclusive cutoff
     :as-of         — instant for the active-window filter (default
                      `:thru-date`)"
  [db {:keys [partner contact-mech thru-date as-of]}]
  (when-not partner      (throw (ex-info ":partner required" {:type :kontor.contact-mech/missing-partner})))
  (when-not contact-mech (throw (ex-info ":contact-mech required" {:type :kontor.contact-mech/missing-contact-mech})))
  (let [partner-eid (resolve-partner db partner)
        _ (when-not partner-eid
            (throw (ex-info "Partner not found"
                            {:type :kontor.partner/not-found :spec partner})))
        cm-eid (if (string? contact-mech)
                 (d/q '[:find ?cm .
                        :in $ ?code
                        :where [?cm :kontor.contact-mech/code ?code]]
                      db contact-mech)
                 contact-mech)
        _ (when-not cm-eid
            (throw (ex-info "Contact-mech not found"
                            {:type :kontor.contact-mech/not-found :spec contact-mech})))
        thru (or thru-date (now-instant))
        as-of-d (or as-of thru)
        rows (d/q '[:find ?j ?from ?thru
                    :in $ ?p ?cm
                    :where
                    [?j :kontor.partner-contact-mech/partner ?p]
                    [?j :kontor.partner-contact-mech/contact-mech ?cm]
                    [?j :kontor.partner-contact-mech/from-date ?from]
                    [(get-else $ ?j :kontor.partner-contact-mech/thru-date :__none) ?thru]]
                  db partner-eid cm-eid)
        active (filter (fn [[_ from t]]
                         (active-as-of? from
                                        (when (instance? java.util.Date t) t)
                                        as-of-d))
                       rows)]
    (when (empty? active)
      (throw (ex-info "No active partner-contact-mech junction to close"
                      {:type :kontor.contact-mech/no-active-junction
                       :partner partner-eid
                       :contact-mech cm-eid
                       :as-of as-of-d})))
    (mapv (fn [[j _ _]]
            {:db/id j
             :kontor.partner-contact-mech/thru-date thru})
          active)))

(defn remove-contact-mech!
  "Close the active `:partner-contact-mech` junction row(s) between
   `partner` and `contact-mech` by stamping `:thru-date`. Routes
   through the gate (ADR-068). The contact-mech entity itself is
   preserved — only the *association* is closed. See ADR-007 (no
   silent retract of consequential history)."
  [conn opts]
  (validation/transact-with-validation
   conn (remove-contact-mech-tx-data (d/db conn) opts)))

;; ----------------------------------------------------------------------------
;; Relationships
;; ----------------------------------------------------------------------------

(defn add-relationship-tx-data
  "Pure tx-data builder for `add-relationship!`. Creates a single
   `:partner-relationship` row linking `partner-from` to
   `partner-to`. The relationship's composite identity is
   `[partner-from role-type-from partner-to role-type-to from-date]`,
   so re-running the same call upserts.

   Required:
     :partner-from         — eid or external-id
     :partner-to           — eid or external-id
     :role-type-from       — keyword (role context of from-side)
     :role-type-to         — keyword (role context of to-side)
     :relationship-type    — keyword (:employment :subsidiary …)

   Optional:
     :from-date            — instant (default now)
     :thru-date            — instant
     :status               — :active (default) | :inactive | :pending
     :relationship-name    — string
     :position-title       — string
     :priority             — long (multi-employment ranking tiebreak)
     :comments             — string
     :tempid               — default `\"relationship-1\"`"
  [db {:keys [partner-from partner-to role-type-from role-type-to
              relationship-type from-date thru-date status
              relationship-name position-title priority comments
              tempid]
       :or {tempid "relationship-1"
            status :active}}]
  (when-not partner-from      (throw (ex-info ":partner-from required" {:type :relationship/missing-from})))
  (when-not partner-to        (throw (ex-info ":partner-to required"   {:type :relationship/missing-to})))
  (when-not role-type-from    (throw (ex-info ":role-type-from required" {:type :relationship/missing-role-from})))
  (when-not role-type-to      (throw (ex-info ":role-type-to required"   {:type :relationship/missing-role-to})))
  (when-not relationship-type (throw (ex-info ":relationship-type required" {:type :relationship/missing-rel-type})))
  (let [from-eid (resolve-partner db partner-from)
        to-eid   (resolve-partner db partner-to)
        _ (when-not from-eid
            (throw (ex-info "from-partner not found"
                            {:type :kontor.partner/not-found :spec partner-from})))
        _ (when-not to-eid
            (throw (ex-info "to-partner not found"
                            {:type :kontor.partner/not-found :spec partner-to})))
        from (or from-date (now-instant))]
    [(cond-> {:db/id                                  tempid
              :kontor.partner-relationship/partner-from      from-eid
              :kontor.partner-relationship/partner-to        to-eid
              :kontor.partner-relationship/role-type-from    role-type-from
              :kontor.partner-relationship/role-type-to      role-type-to
              :kontor.partner-relationship/relationship-type relationship-type
              :kontor.partner-relationship/from-date         from
              :kontor.partner-relationship/status            status}
       thru-date         (assoc :kontor.partner-relationship/thru-date thru-date)
       relationship-name (assoc :kontor.partner-relationship/relationship-name relationship-name)
       position-title    (assoc :kontor.partner-relationship/position-title position-title)
       priority          (assoc :kontor.partner-relationship/priority priority)
       comments          (assoc :kontor.partner-relationship/comments comments))]))

(defn add-relationship!
  "Link two partners with a `:partner-relationship` row. Routes
   through the gate (ADR-068). See `add-relationship-tx-data` for
   options."
  [conn opts]
  (validation/transact-with-validation
   conn (add-relationship-tx-data (d/db conn) opts)))

(defn end-relationship-tx-data
  "Pure tx-data builder for `end-relationship!`. Stamps `:thru-date`
   (default now) and optionally `:status` (default `:inactive`) on
   an existing `:partner-relationship` row.

   `relationship` may be either an entity-id (preferred) or a tuple
   lookup ref into `:kontor.partner-relationship/identity`."
  [db {:keys [relationship thru-date status]
       :or   {status :inactive}}]
  (when-not relationship
    (throw (ex-info ":relationship required" {:type :relationship/missing-eid})))
  (let [rel-eid (if (number? relationship)
                  relationship
                  (let [pulled (d/pull db [:db/id] relationship)]
                    (:db/id pulled)))]
    (when-not rel-eid
      (throw (ex-info "Relationship not found"
                      {:type :relationship/not-found :spec relationship})))
    [(cond-> {:db/id rel-eid
              :kontor.partner-relationship/thru-date (or thru-date (now-instant))}
       status (assoc :kontor.partner-relationship/status status))]))

(defn end-relationship!
  "Terminate a `:partner-relationship` by setting its `:thru-date`
   and `:status` (default `:inactive`). Routes through the gate
   (ADR-068)."
  [conn opts]
  (validation/transact-with-validation
   conn (end-relationship-tx-data (d/db conn) opts)))

;; ----------------------------------------------------------------------------
;; Roles
;; ----------------------------------------------------------------------------

(defn add-party-role-tx-data
  "Pure tx-data builder for `add-party-role!`. Creates a
   `:partner-role` row tagging `partner` with `role-type`
   (`:customer | :supplier | :employee | :contractor | :ship-to |
   :bill-to | :internal-organization | …`).

   Required:
     :partner    — eid or external-id
     :role-type  — keyword (per ADR-033 vocabulary; consumers extend)

   Optional:
     :from-date  — instant (default now)
     :thru-date  — instant
     :tempid     — default `\"role-1\"`"
  [db {:keys [partner role-type from-date thru-date tempid]
       :or   {tempid "role-1"}}]
  (when-not partner   (throw (ex-info ":partner required"   {:type :role/missing-partner})))
  (when-not role-type (throw (ex-info ":role-type required" {:type :role/missing-role-type})))
  (let [partner-eid (resolve-partner db partner)
        _ (when-not partner-eid
            (throw (ex-info "Partner not found"
                            {:type :kontor.partner/not-found :spec partner})))
        from (or from-date (now-instant))]
    [(cond-> {:db/id                  tempid
              :kontor.partner-role/partner   partner-eid
              :kontor.partner-role/role-type role-type
              :kontor.partner-role/from-date from}
       thru-date (assoc :kontor.partner-role/thru-date thru-date))]))

(defn add-party-role!
  "Assign `role-type` to `partner` as a `:partner-role` row. Routes
   through the gate (ADR-068). See `add-party-role-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (add-party-role-tx-data (d/db conn) opts)))

;; ============================================================================
;; ADR-039 — Merge (non-destructive)
;; ============================================================================

(defn resolve-canonical-partner
  "Walk :partner-merge chain. If `partner-eid` has been superseded
   by a canonical, return the canonical eid. Otherwise return
   partner-eid unchanged.

   Recursive: A merged into B, then B merged into C → A resolves to C."
  [db partner-eid]
  (loop [eid partner-eid
         visited #{}]
    (if (contains? visited eid)
      eid                                       ; cycle guard
      (if-let [canonical (d/q '[:find ?c .
                                :in $ ?super
                                :where
                                [?m :kontor.partner-merge/superseded ?super]
                                [?m :kontor.partner-merge/duplicate-of ?c]]
                              db eid)]
        (recur canonical (conj visited eid))
        eid))))

(defn merge-partners-tx-data
  "Pure tx-data builder for `merge-partners!` (ADR-068)."
  [db canonical-partner superseded-partner {:keys [reason reason-note
                                                   supporting-doc
                                                   merged-by-uid
                                                   merged-at]
                                            :as _opts}]
  (let [canonical-eid (resolve-partner db canonical-partner)
        superseded-eid (resolve-partner db superseded-partner)
        _ (when-not canonical-eid
            (throw (ex-info "Canonical partner not found"
                            {:type :kontor.partner-merge/canonical-not-found
                             :spec canonical-partner})))
        _ (when-not superseded-eid
            (throw (ex-info "Superseded partner not found"
                            {:type :kontor.partner-merge/superseded-not-found
                             :spec superseded-partner})))
        _ (when (= canonical-eid superseded-eid)
            (throw (ex-info "Cannot merge a partner with itself"
                            {:type :kontor.partner-merge/self-merge
                             :partner canonical-eid})))
        _ (when-not reason
            (throw (ex-info "merge-partners! requires :reason"
                            {:type :kontor.partner-merge/missing-reason})))
        merge-row (cond-> {:kontor.partner-merge/duplicate-of canonical-eid
                           :kontor.partner-merge/superseded superseded-eid
                           :kontor.partner-merge/merged-at (or merged-at (java.util.Date.))
                           :kontor.partner-merge/reason reason}
                    reason-note    (assoc :kontor.partner-merge/reason-note reason-note)
                    supporting-doc (assoc :kontor.partner-merge/supporting-doc supporting-doc)
                    merged-by-uid  (assoc :kontor.partner-merge/merged-by-uid merged-by-uid))
        archive-row {:db/id superseded-eid
                     :kontor.partner/status :archived}]
    [merge-row archive-row]))

(defn merge-partners!
  "Mark `superseded-partner` as a duplicate of `canonical-partner`.
   Both must resolve to :partner eids. Writes a :partner-merge row
   atomically with archiving the superseded partner's status. Routes
   through the gate (ADR-068).

   Required keys in opts: `:reason` (keyword, ADR-038 vocabulary).
   Optional: `:reason-note`, `:supporting-doc`, `:merged-by-uid`,
   `:merged-at` (default now).

   The pure tx-data builder is `merge-partners-tx-data`."
  [conn canonical-partner superseded-partner opts]
  (validation/transact-with-validation
   conn (merge-partners-tx-data (d/db conn) canonical-partner
                                superseded-partner opts)))

;; ============================================================================
;; ADR-039 — Bank accounts
;; ============================================================================

(defn bank-accounts-of
  "Pulled :bank-account rows for `partner`, filtered to active-as-of
   the optional `:as-of` instant (default now). Filter further by
   `:purpose` opt (`:disbursement | :collection | :both`)."
  ([db partner] (bank-accounts-of db partner nil))
  ([db partner opts]
   (let [as-of (now-or (:as-of opts))
         pid (resolve-partner db partner)
         purpose (:purpose opts)
         rows (d/q '[:find ?ba ?from ?thru ?purp
                     :in $ ?p
                     :where
                     [?j :kontor.partner-bank-account/partner ?p]
                     [?j :kontor.partner-bank-account/bank-account ?ba]
                     [?j :kontor.partner-bank-account/from-date ?from]
                     [?j :kontor.partner-bank-account/purpose ?purp]
                     [(get-else $ ?j :kontor.partner-bank-account/thru-date :__none) ?thru]]
                   db pid)]
     (->> rows
          (filter (fn [[_ from thru _]] (active-as-of? from thru as-of)))
          (filter (fn [[_ _ _ purp]] (or (nil? purpose)
                                          (= purp purpose)
                                          (= purp :both))))
          (map (fn [[ba _ _ _]] (d/pull db '[*] ba)))
          vec))))

(defn primary-disbursement-account
  "The :bank-account preferred-for-disbursement for partner at
   `:as-of`. Returns the pulled :bank-account map, or nil."
  ([db partner] (primary-disbursement-account db partner nil))
  ([db partner opts]
   (let [as-of (now-or (:as-of opts))
         pid (resolve-partner db partner)
         rows (d/q '[:find ?ba ?from ?thru ?pref
                     :in $ ?p
                     :where
                     [?j :kontor.partner-bank-account/partner ?p]
                     [?j :kontor.partner-bank-account/bank-account ?ba]
                     [?j :kontor.partner-bank-account/from-date ?from]
                     [?j :kontor.partner-bank-account/purpose ?purp]
                     [(get-else $ ?j :kontor.partner-bank-account/thru-date :__none) ?thru]
                     [(get-else $ ?j :kontor.partner-bank-account/preferred? false) ?pref]
                     [(contains? #{:disbursement :both} ?purp)]]
                   db pid)
         active (filter (fn [[_ from thru _]] (active-as-of? from thru as-of)) rows)
         preferred (or (first (filter #(true? (nth % 3)) active))
                       (first active))]
     (when-let [[ba-eid _ _ _] preferred]
       (d/pull db '[*] ba-eid)))))

;; ============================================================================
;; ADR-039 — Tag segmentation
;; ============================================================================

(defn tags-of
  "Set of :kontor.partner-tag/tag-type keywords active for `partner` at
   `:as-of`."
  ([db partner] (tags-of db partner nil))
  ([db partner opts]
   (let [as-of (now-or (:as-of opts))
         pid (resolve-partner db partner)
         rows (d/q '[:find ?tag ?from ?thru
                     :in $ ?p
                     :where
                     [?t :kontor.partner-tag/partner ?p]
                     [?t :kontor.partner-tag/tag-type ?tag]
                     [?t :kontor.partner-tag/from-date ?from]
                     [(get-else $ ?t :kontor.partner-tag/thru-date :__none) ?thru]]
                   db pid)]
     (->> rows
          (filter (fn [[_ from thru]] (active-as-of? from thru as-of)))
          (map first)
          set))))

(defn partners-with-tag
  "Partner eids holding `tag-type` active at `:as-of`."
  ([db tag-type] (partners-with-tag db tag-type nil))
  ([db tag-type opts]
   (let [as-of (now-or (:as-of opts))
         rows (d/q '[:find ?partner ?from ?thru
                     :in $ ?tag
                     :where
                     [?t :kontor.partner-tag/tag-type ?tag]
                     [?t :kontor.partner-tag/partner ?partner]
                     [?t :kontor.partner-tag/from-date ?from]
                     [(get-else $ ?t :kontor.partner-tag/thru-date :__none) ?thru]]
                   db tag-type)]
     (->> rows
          (filter (fn [[_ from thru]] (active-as-of? from thru as-of)))
          (map first)
          set))))

;; ============================================================================
;; ADR-040 — Multi-tax-id-per-jurisdiction
;; ============================================================================

(defn tax-ids-of
  "Pulled :partner-tax-id rows for `partner`, filtered to active
   at `:as-of` (default now). Optionally filter by `:country` ref."
  ([db partner] (tax-ids-of db partner nil))
  ([db partner opts]
   (let [as-of (now-or (:as-of opts))
         country-eid (:country opts)
         pid (resolve-partner db partner)
         rows (d/q '[:find ?t ?from ?thru ?country
                     :in $ ?p
                     :where
                     [?t :kontor.partner-tax-id/partner ?p]
                     [?t :kontor.partner-tax-id/country ?country]
                     [?t :kontor.partner-tax-id/from-date ?from]
                     [(get-else $ ?t :kontor.partner-tax-id/thru-date :__none) ?thru]]
                   db pid)]
     (->> rows
          (filter (fn [[_ from thru _]] (active-as-of? from thru as-of)))
          (filter (fn [[_ _ _ c]] (or (nil? country-eid) (= c country-eid))))
          (map (fn [[t _ _ _]] (d/pull db '[*] t)))
          vec))))

(defn tax-id-for-country
  "Lookup the active tax-id string for `partner` in `country` (a ref
   or :kontor.country/code string). Returns the string or nil.

   When multiple tax-id-types apply in the same country (e.g. NL has
   :kvk-nl + :rsin-nl + :btw-nl), pass `:tax-id-type` opt to
   disambiguate. Otherwise returns the first match."
  ([db partner country] (tax-id-for-country db partner country nil))
  ([db partner country opts]
   (let [as-of (now-or (:as-of opts))
         country-eid (cond
                       (string? country) (d/q '[:find ?c .
                                                :in $ ?code
                                                :where [?c :kontor.country/code ?code]]
                                              db country)
                       :else country)
         tax-id-type (:tax-id-type opts)
         hits (tax-ids-of db partner {:as-of as-of :country country-eid})
         filtered (if tax-id-type
                    (filter #(= tax-id-type (:kontor.partner-tax-id/tax-id-type %)) hits)
                    hits)]
     (some-> filtered first :kontor.partner-tax-id/tax-id))))
