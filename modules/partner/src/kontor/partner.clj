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
                                [?m :partner-merge/superseded ?super]
                                [?m :partner-merge/duplicate-of ?c]]
                              db eid)]
        (recur canonical (conj visited eid))
        eid))))

(defn merge-partners!
  "Mark `superseded-partner` as a duplicate of `canonical-partner`.
   Both must resolve to :partner eids. Writes a :partner-merge row
   atomically with archiving the superseded partner's status.

   Required keys in opts: `:reason` (keyword, ADR-038 vocabulary).
   Optional: `:reason-note`, `:supporting-doc`, `:merged-by-uid`,
   `:merged-at` (default now)."
  [conn canonical-partner superseded-partner {:keys [reason reason-note
                                                     supporting-doc
                                                     merged-by-uid
                                                     merged-at]
                                              :as _opts}]
  (let [db (d/db conn)
        canonical-eid (resolve-partner db canonical-partner)
        superseded-eid (resolve-partner db superseded-partner)
        _ (when-not canonical-eid
            (throw (ex-info "Canonical partner not found"
                            {:type :partner-merge/canonical-not-found
                             :spec canonical-partner})))
        _ (when-not superseded-eid
            (throw (ex-info "Superseded partner not found"
                            {:type :partner-merge/superseded-not-found
                             :spec superseded-partner})))
        _ (when (= canonical-eid superseded-eid)
            (throw (ex-info "Cannot merge a partner with itself"
                            {:type :partner-merge/self-merge
                             :partner canonical-eid})))
        _ (when-not reason
            (throw (ex-info "merge-partners! requires :reason"
                            {:type :partner-merge/missing-reason})))
        merge-row (cond-> {:partner-merge/duplicate-of canonical-eid
                           :partner-merge/superseded superseded-eid
                           :partner-merge/merged-at (or merged-at (java.util.Date.))
                           :partner-merge/reason reason}
                    reason-note    (assoc :partner-merge/reason-note reason-note)
                    supporting-doc (assoc :partner-merge/supporting-doc supporting-doc)
                    merged-by-uid  (assoc :partner-merge/merged-by-uid merged-by-uid))
        archive-row {:db/id superseded-eid
                     :partner/status :archived}]
    (d/transact conn [merge-row archive-row])))

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
                     [?j :partner-bank-account/partner ?p]
                     [?j :partner-bank-account/bank-account ?ba]
                     [?j :partner-bank-account/from-date ?from]
                     [?j :partner-bank-account/purpose ?purp]
                     [(get-else $ ?j :partner-bank-account/thru-date :__none) ?thru]]
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
                     [?j :partner-bank-account/partner ?p]
                     [?j :partner-bank-account/bank-account ?ba]
                     [?j :partner-bank-account/from-date ?from]
                     [?j :partner-bank-account/purpose ?purp]
                     [(get-else $ ?j :partner-bank-account/thru-date :__none) ?thru]
                     [(get-else $ ?j :partner-bank-account/preferred? false) ?pref]
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
  "Set of :partner-tag/tag-type keywords active for `partner` at
   `:as-of`."
  ([db partner] (tags-of db partner nil))
  ([db partner opts]
   (let [as-of (now-or (:as-of opts))
         pid (resolve-partner db partner)
         rows (d/q '[:find ?tag ?from ?thru
                     :in $ ?p
                     :where
                     [?t :partner-tag/partner ?p]
                     [?t :partner-tag/tag-type ?tag]
                     [?t :partner-tag/from-date ?from]
                     [(get-else $ ?t :partner-tag/thru-date :__none) ?thru]]
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
                     [?t :partner-tag/tag-type ?tag]
                     [?t :partner-tag/partner ?partner]
                     [?t :partner-tag/from-date ?from]
                     [(get-else $ ?t :partner-tag/thru-date :__none) ?thru]]
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
                     [?t :partner-tax-id/partner ?p]
                     [?t :partner-tax-id/country ?country]
                     [?t :partner-tax-id/from-date ?from]
                     [(get-else $ ?t :partner-tax-id/thru-date :__none) ?thru]]
                   db pid)]
     (->> rows
          (filter (fn [[_ from thru _]] (active-as-of? from thru as-of)))
          (filter (fn [[_ _ _ c]] (or (nil? country-eid) (= c country-eid))))
          (map (fn [[t _ _ _]] (d/pull db '[*] t)))
          vec))))

(defn tax-id-for-country
  "Lookup the active tax-id string for `partner` in `country` (a ref
   or :country/code string). Returns the string or nil.

   When multiple tax-id-types apply in the same country (e.g. NL has
   :kvk-nl + :rsin-nl + :btw-nl), pass `:tax-id-type` opt to
   disambiguate. Otherwise returns the first match."
  ([db partner country] (tax-id-for-country db partner country nil))
  ([db partner country opts]
   (let [as-of (now-or (:as-of opts))
         country-eid (cond
                       (string? country) (d/q '[:find ?c .
                                                :in $ ?code
                                                :where [?c :country/code ?code]]
                                              db country)
                       :else country)
         tax-id-type (:tax-id-type opts)
         hits (tax-ids-of db partner {:as-of as-of :country country-eid})
         filtered (if tax-id-type
                    (filter #(= tax-id-type (:partner-tax-id/tax-id-type %)) hits)
                    hits)]
     (some-> filtered first :partner-tax-id/tax-id))))
