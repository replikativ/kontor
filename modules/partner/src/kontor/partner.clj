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
  (:require [datahike.api :as d]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  "Resolve a partner entity-id by `:partner/external-id`."
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :partner/external-id ?xid]]
       db external-id))

(defn resolve-partner
  "Coerce `spec` to a partner entity-id:
     - nil       → nil
     - string    → looked up by :partner/external-id
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
   or string `:partner/external-id`). Returns nil if the partner has
   no :person subtype (i.e. is an org)."
  [db partner]
  (when-let [pid (resolve-partner db partner)]
    (when-let [person-eid (d/q '[:find ?p .
                                 :in $ ?partner
                                 :where [?p :person/partner ?partner]]
                               db pid)]
      (d/pull db '[*] person-eid))))

(defn org
  "Pull the :org subtype map associated with `partner` (entity-id or
   string `:partner/external-id`). Returns nil if the partner has no
   :org subtype (i.e. is a person)."
  [db partner]
  (when-let [pid (resolve-partner db partner)]
    (when-let [org-eid (d/q '[:find ?o .
                              :in $ ?partner
                              :where [?o :org/partner ?partner]]
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
  "All :partner-role/role-type values currently held by `partner`,
   as a set. Pass `:as-of` to query at a different valid-time
   instant; default is `now`."
  ([db partner] (roles-of db partner nil))
  ([db partner opts]
   (let [as-of (now-or (:as-of opts))
         pid (resolve-partner db partner)
         rows (d/q '[:find ?role ?from ?thru
                     :in $ ?partner
                     :where
                     [?r :partner-role/partner ?partner]
                     [?r :partner-role/role-type ?role]
                     [?r :partner-role/from-date ?from]
                     [(get-else $ ?r :partner-role/thru-date :__none) ?thru]]
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
                     [?r :partner-role/role-type ?role]
                     [?r :partner-role/partner ?partner]
                     [?r :partner-role/from-date ?from]
                     [(get-else $ ?r :partner-role/thru-date :__none) ?thru]]
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
                     [?j :partner-contact-mech/partner ?partner]
                     [?j :partner-contact-mech/contact-mech ?cm]
                     [?j :partner-contact-mech/from-date ?from]
                     [(get-else $ ?j :partner-contact-mech/thru-date :__none) ?thru]]
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
                     [?p :partner-contact-mech-purpose/partner ?partner]
                     [?p :partner-contact-mech-purpose/purpose-type ?purpose]
                     [?p :partner-contact-mech-purpose/contact-mech ?cm]
                     [?p :partner-contact-mech-purpose/from-date ?from]
                     [(get-else $ ?p :partner-contact-mech-purpose/thru-date :__none) ?thru]]
                   db pid purpose-type)]
     (->> rows
          (filter (fn [[_ from thru]] (active-as-of? from thru as-of)))
          (sort-by (fn [[_ from _]] from) #(compare %2 %1))
          ffirst))))

(defn primary-email
  "The :email-address/address string serving :primary-email for
   `partner`, or nil. Walks contact-mech-by-purpose then resolves
   the typed payload."
  ([db partner] (primary-email db partner nil))
  ([db partner opts]
   (when-let [cm-eid (contact-mech-by-purpose db partner :primary-email opts)]
     (d/q '[:find ?addr .
            :in $ ?cm
            :where
            [?e :email-address/contact-mech ?cm]
            [?e :email-address/address ?addr]]
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
                                :where [?a :postal-address/contact-mech ?cm]]
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
                     (or [?r :partner-relationship/partner-from ?partner]
                         [?r :partner-relationship/partner-to ?partner])]
                   db pid)
         rows (map (fn [r] (d/pull db '[*] r)) eids)]
     (->> rows
          (filter (fn [rel]
                    (active-as-of? (:partner-relationship/from-date rel)
                                   (:partner-relationship/thru-date rel)
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
                     :where [?r :partner-relationship/partner-from ?partner]]
                   db pid)
         rows (map (fn [r] (d/pull db '[*] r)) eids)]
     (->> rows
          (filter (fn [rel]
                    (active-as-of? (:partner-relationship/from-date rel)
                                   (:partner-relationship/thru-date rel)
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
                     :where [?r :partner-relationship/partner-to ?partner]]
                   db pid)
         rows (map (fn [r] (d/pull db '[*] r)) eids)]
     (->> rows
          (filter (fn [rel]
                    (active-as-of? (:partner-relationship/from-date rel)
                                   (:partner-relationship/thru-date rel)
                                   as-of)))
          vec))))

(defn relationships-of-type
  "Restrict `relationships-of` to a specific `:partner-relationship/
   relationship-type` keyword (e.g. :employment, :subsidiary)."
  ([db partner relationship-type]
   (relationships-of-type db partner relationship-type nil))
  ([db partner relationship-type opts]
   (->> (relationships-of db partner opts)
        (filter #(= relationship-type
                    (:partner-relationship/relationship-type %)))
        vec)))

(defn current-employer
  "For a :person-typed partner, the org partner where they currently
   hold an :employment relationship as the :employee side. Returns
   nil if no active employment. If a person holds multiple concurrent
   employments, returns the one with the highest
   `:partner-relationship/priority` (default ranking)."
  ([db partner] (current-employer db partner nil))
  ([db partner opts]
   (let [emps (->> (relationships-from db partner opts)
                   (filter #(= :employment
                               (:partner-relationship/relationship-type %)))
                   (sort-by #(or (:partner-relationship/priority %) 0) >))]
     (some-> emps first :partner-relationship/partner-to :db/id))))

(defn current-employees
  "For an :org-typed partner, the set of person partner-ids currently
   employed by this org via an :employment relationship as the
   :internal-organization to-side."
  ([db partner] (current-employees db partner nil))
  ([db partner opts]
   (->> (relationships-to db partner opts)
        (filter #(= :employment
                    (:partner-relationship/relationship-type %)))
        (map #(get-in % [:partner-relationship/partner-from :db/id]))
        (remove nil?)
        set)))
