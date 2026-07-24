(ns kontor.tax.fiscal-position
  "Fiscal positions — per-customer tax/account remapping. note 198 R3-FP-01.

   A `:kontor.fiscal-position` says: *for a counterparty of this shape, the
   default domestic tax is not the tax that applies.* An EU B2B customer with
   a valid VAT ID turns a domestic 19% output VAT into an intra-community
   reverse charge; an export customer drops it entirely; a customer in a
   different régime routes revenue to a different income account.

   Before this namespace the `:kontor.fiscal-position` entity carried only
   markers (name / country-code / auto-apply / vat-required) — the concept was
   named but not wired to anything, so `rate-facts` always returned the
   domestic tax no matter which position the customer held.

   ## Shape

   Mapping lines are separate entities pointing BACK at the position, matching
   Odoo's account.fiscal.position.tax / .account:

       {:kontor.fiscal-position-tax/fiscal-position fp
        :kontor.fiscal-position-tax/src-tax  domestic-19
        :kontor.fiscal-position-tax/dest-tax reverse-charge-0}

   A line with NO `:dest-tax` DROPS the source tax — the export case, where
   the domestic VAT does not apply and no substitute replaces it. This is a
   deliberate distinction from mapping to a 0% tax: a dropped tax leaves no
   `TaxFacts` component at all, whereas a 0% reverse-charge tax leaves a
   zero-amount component that still carries its `:kind` and reporting tags to
   the VAT return.

   ## Use

   `map-taxes` is applied by the `StaticTableProvider` when a `rate-facts`
   context carries `:fiscal-position`. It only ever SUBSTITUTES taxes the
   resolver already found — a position cannot conjure a tax that the
   jurisdiction rules did not select in the first place (Odoo's `map_tax` has
   the same property).

   Builders follow ADR-068: a pure `*-tx-data` builder plus a `!` wrapper
   that routes through the validation gate."
  (:require [datahike.api :as d]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-name
  "Resolve a fiscal position entity-id by `:kontor.fiscal-position/name`.
   nil when no position carries that name; throws
   `:kontor.fiscal-position/ambiguous-name` when more than one does.

   note 198 audit (M9): `:kontor.fiscal-position/name` is neither unique nor
   scoped to an entity, so a multi-entity book with an \"EU B2B\" position per
   entity had the old `:find ?e .` pick one at random. The position chosen is
   what decides whether an invoice carries 19% domestic VAT or an
   intra-community reverse charge — picking silently is picking the tax
   treatment of a legal document."
  [db name]
  (let [eids (d/q '[:find [?e ...]
                    :in $ ?n
                    :where [?e :kontor.fiscal-position/name ?n]]
                  db name)]
    (cond
      (= 1 (count eids)) (first eids)
      (empty? eids)      nil
      :else
      (throw (ex-info (str "Fiscal-position name " (pr-str name) " matches "
                           (count eids) " positions — :kontor.fiscal-position/name "
                           "is not unique. Pass the entity-id (or a pulled map) "
                           "instead of the name.")
                      {:type :kontor.fiscal-position/ambiguous-name
                       :name name
                       :matches (vec (sort eids))})))))

(defn resolve-fiscal-position
  "Coerce `spec` to an entity-id: nil → nil, string → name lookup, map with
   `:db/id` → that id, anything else → itself (assumed an eid)."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-name db spec)
    (map? spec)    (:db/id spec)
    :else          spec))

;; ============================================================================
;; The maps
;; ============================================================================

(defn tax-mappings
  "All `{:src <eid> :dest <eid-or-nil> :sequence <long>}` lines of `fp`,
   ordered by sequence. `:dest` nil means the source tax is dropped."
  [db fp]
  (when-let [fp-eid (resolve-fiscal-position db fp)]
    (->> (d/q '[:find [?m ...]
                :in $ ?fp
                :where [?m :kontor.fiscal-position-tax/fiscal-position ?fp]]
              db fp-eid)
         (map (fn [m]
                (let [e (d/pull db [:kontor.fiscal-position-tax/src-tax
                                    :kontor.fiscal-position-tax/dest-tax
                                    :kontor.fiscal-position-tax/sequence]
                                m)]
                  {:src      (:db/id (:kontor.fiscal-position-tax/src-tax e))
                   :dest     (:db/id (:kontor.fiscal-position-tax/dest-tax e))
                   :sequence (or (:kontor.fiscal-position-tax/sequence e) 0)})))
         (sort-by (juxt :sequence :src))
         vec)))

(defn map-tax
  "The tax(es) `tax-eid` becomes under fiscal position `fp`.

   Returns a vector: `[tax-eid]` when the position has no line for it (the
   pass-through case), `[dest]` when it is remapped, `[]` when the line drops
   it. A vector rather than a scalar so a future one-to-many split (one
   domestic tax becoming two destination taxes) is an additive change."
  [db fp tax-eid]
  (if-let [line (first (filter #(= tax-eid (:src %)) (tax-mappings db fp)))]
    (if-let [dest (:dest line)] [dest] [])
    [tax-eid]))

(defn map-taxes
  "Apply `map-tax` across `tax-eids`, de-duplicating the result. Two domestic
   taxes remapped onto the same destination collapse to one component rather
   than double-charging it."
  [db fp tax-eids]
  (if (nil? (resolve-fiscal-position db fp))
    (vec tax-eids)
    (into [] (distinct) (mapcat #(map-tax db fp %) tax-eids))))

(defn map-account
  "The account `account-eid` becomes under fiscal position `fp`, or
   `account-eid` itself when the position does not remap it."
  [db fp account-eid]
  (or (when-let [fp-eid (resolve-fiscal-position db fp)]
        (d/q '[:find ?dest .
               :in $ ?fp ?src
               :where
               [?m :kontor.fiscal-position-account/fiscal-position ?fp]
               [?m :kontor.fiscal-position-account/src-account ?src]
               [?m :kontor.fiscal-position-account/dest-account ?dest]]
             db fp-eid account-eid))
      account-eid))

;; ============================================================================
;; Builders (ADR-068)
;; ============================================================================

(defn map-tax-tx-data
  "Pure tx-data for one tax-mapping line. `dest-tax` nil means \"drop the
   source tax under this position\"."
  ([db fp src-tax dest-tax] (map-tax-tx-data db fp src-tax dest-tax 0))
  ([db fp src-tax dest-tax sequence]
   (let [fp-eid (resolve-fiscal-position db fp)]
     (when-not fp-eid
       (throw (ex-info "map-tax!: fiscal position not found" {:fiscal-position fp})))
     [(cond-> {:kontor.fiscal-position-tax/fiscal-position fp-eid
               :kontor.fiscal-position-tax/src-tax         src-tax
               :kontor.fiscal-position-tax/sequence        sequence}
        dest-tax (assoc :kontor.fiscal-position-tax/dest-tax dest-tax))])))

(defn map-tax!
  "Transact one tax-mapping line. Re-transacting the same (position, source
   tax) pair updates the destination — the composite identity collapses it."
  ([conn fp src-tax dest-tax] (map-tax! conn fp src-tax dest-tax 0))
  ([conn fp src-tax dest-tax sequence]
   (validation/transact-with-validation
    conn (map-tax-tx-data (d/db conn) fp src-tax dest-tax sequence))))

(defn map-account-tx-data
  "Pure tx-data for one account-mapping line."
  [db fp src-account dest-account]
  (let [fp-eid (resolve-fiscal-position db fp)]
    (when-not fp-eid
      (throw (ex-info "map-account!: fiscal position not found" {:fiscal-position fp})))
    [{:kontor.fiscal-position-account/fiscal-position fp-eid
      :kontor.fiscal-position-account/src-account     src-account
      :kontor.fiscal-position-account/dest-account    dest-account}]))

(defn map-account!
  "Transact one account-mapping line."
  [conn fp src-account dest-account]
  (validation/transact-with-validation
   conn (map-account-tx-data (d/db conn) fp src-account dest-account)))
