(ns kontor.partner.schema
  "Companion schema for `kontor-partner` — see ADR-033.

   The party-as-root model with discriminator subtypes (`:person`,
   `:org`), polymorphic contact mechanisms (`:contact-mech` with
   typed subtypes), temporal junctions for party↔contact-mech
   association + multi-purpose routing, capability roles, and
   temporal multi-role relationships.

   This namespace extends the kernel's existing `:kontor.partner/*`
   namespace and adds the new namespaces; the kernel's `:kontor.posting/
   partner` ref continues to point at the same `:partner` root
   entity. Kernel-only consumers never call `install!` here and
   are unaffected.

   See doc/research/12-ofbiz-companion-mappings.md for the
   OFBiz / Tryton / Workday / SAP-BP shape survey that informs
   the design."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Root: partner extensions (additive to kernel :kontor.partner/*)
;;
;; The kernel already ships :kontor.partner/external-id (identity),
;; :kontor.partner/name, :kontor.partner/kind, :kontor.partner/country-code,
;; :kontor.partner/tax-id (see kontor.schema). The companion adds
;; discriminator + status + audit + commodity preference.
;; ============================================================================

(def ^:private partner-ext-attrs
  [{:db/ident       :kontor.partner/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Discriminator: :person or :org. Determines which
                     subtype entity (kontor.partner :person | :org)
                     carries the type-specific attributes."}

   {:db/ident       :kontor.partner/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":enabled | :disabled | :archived. Soft-delete and
                     compliance-quiescent partners stay in the graph
                     for audit; queries scope by status."}

   {:db/ident       :kontor.partner/preferred-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Default commodity (currency) for transactions
                     with this partner. Ref to :commodity."}

   {:db/ident       :kontor.partner/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Partner-record creation timestamp (domain-level;
                     distinct from datahike's :db/txInstant)."}

   {:db/ident       :kontor.partner/modified-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Last logical modification of the partner record."}

   {:db/ident       :kontor.partner/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Person subtype
;;
;; 1:1 with :partner via :kontor.person/partner. PII fields are kept
;; separate from :org to allow encryption / redaction policy to
;; be applied to :kontor.person/* alone.
;; ============================================================================

(def ^:private person-attrs
  [{:db/ident       :kontor.person/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value
    :db/doc         "FK to :partner. 1:1 — enforced by :db.unique/value."}

   {:db/ident       :kontor.person/first-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.person/middle-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.person/last-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.person/salutation
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.person/suffix
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.person/nickname
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.person/first-name-local
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Localized first name in a non-Latin script
                     (e.g., 山田 for a Japanese partner whose
                     first-name is romanized to Yamada)."}

   {:db/ident       :kontor.person/last-name-local
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.person/gender
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":male | :female | :nonbinary | :unspecified.
                     Free-form keyword; consumers may extend."}

   ;; :kontor.person/birth-date and :kontor.person/national-id are owned by the kernel
   ;; schema (audit note 95 — was duplicate-defined here + in kontor-hr
   ;; with diverging shapes, causing silent install-order-dependent
   ;; overwrite). Reach them via kernel install + the same idents.

   {:db/ident       :kontor.person/deceased-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.person/marital-status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":single | :married | :divorced | :widowed |
                     :partnered | :unspecified."}

   {:db/ident       :kontor.person/national-id-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":ssn | :passport | :national-id | :tin | …
                     consumers extend per jurisdiction."}])

;; ============================================================================
;; Organization subtype
;;
;; 1:1 with :partner via :org/partner. Carries org-specific
;; registration + financial + market data.
;; ============================================================================

(def ^:private org-attrs
  [{:db/ident       :org/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value
    :db/doc         "FK to :partner. 1:1."}

   {:db/ident       :org/legal-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Formally registered name (often differs from
                     :kontor.partner/name which may be the trading name)."}

   {:db/ident       :org/legal-form
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":gmbh | :ag | :llc | :inc | :sa | :ltd | …
                     keyword vocabulary; consumers extend per
                     jurisdiction."}

   {:db/ident       :org/trading-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "\"Doing business as\" name."}

   {:db/ident       :org/registration-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Jurisdiction-specific company register number
                     (HRB in DE, ABN in AU, EIN in US, etc.)."}

   {:db/ident       :org/duns
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Dun & Bradstreet 9-digit D-U-N-S identifier."}

   {:db/ident       :org/lei
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Legal Entity Identifier (ISO 17442 / GLEIF)."}

   {:db/ident       :org/ticker-symbol
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :org/exchange
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Stock exchange where listed (e.g. NYSE, XETRA)."}

   {:db/ident       :org/annual-revenue
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :org/revenue-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Commodity (currency) of :org/annual-revenue."}

   {:db/ident       :org/num-employees
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :org/incorporation-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :org/dissolution-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Polymorphic contact-mech root
;;
;; A contact mechanism is identified by a consumer-supplied code
;; (vCard UID / Peppol contact-point identifier / app-internal ID)
;; and discriminated by :contact-mech/type. The :info-string is a
;; fallback for raw/untyped data when a subtype entity is overkill.
;; ============================================================================

(def ^:private contact-mech-attrs
  [{:db/ident       :contact-mech/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Consumer-supplied opaque identifier for the
                     contact-mech. vCard UID, Peppol contact-point
                     ID, or app-internal. Identity attribute."}

   {:db/ident       :contact-mech/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":postal | :telecom | :email | :web | :ftp.
                     Discriminator for the subtype entity carrying
                     the typed payload."}

   {:db/ident       :contact-mech/info-string
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Fallback untyped storage when no subtype is
                     installed (e.g. :web / :ftp without a dedicated
                     entity)."}

   {:db/ident       :contact-mech/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :contact-mech/modified-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Postal-address subtype
;;
;; Reuses the kernel's :country / :state entities (ADR-023) for
;; structured geography; keeps :region as a free-text fallback for
;; jurisdictions without state-level breakdown.
;; ============================================================================

(def ^:private postal-address-attrs
  [{:db/ident       :postal-address/contact-mech
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value
    :db/doc         "FK to :contact-mech. 1:1."}

   {:db/ident       :postal-address/to-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Addressee on the envelope, if distinct from the
                     partner name."}

   {:db/ident       :postal-address/attn-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "ATTN: line — typically a department or role
                     name for a corporate recipient."}

   {:db/ident       :postal-address/address1
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :postal-address/address2
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :postal-address/house-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Separated from address1 for jurisdictions
                     (DE, NL) where house number is a distinct
                     parsing token from the street name."}

   {:db/ident       :postal-address/house-number-ext
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :postal-address/directions
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :postal-address/city
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :postal-address/postal-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :postal-address/postal-code-ext
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "ZIP+4 suffix in the US, similar in other
                     jurisdictions."}

   {:db/ident       :postal-address/county
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :postal-address/region
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-text region (use :postal-address/state for
                     jurisdictions with structured state entities)."}

   {:db/ident       :postal-address/state
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :state — structured province/state per
                     ADR-023. Preferred over :region when the country
                     has its state set installed."}

   {:db/ident       :postal-address/country
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :country — structured ISO country per
                     ADR-023."}

   {:db/ident       :postal-address/latitude
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :postal-address/longitude
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Telecom subtype
;; ============================================================================

(def ^:private telecom-number-attrs
  [{:db/ident       :telecom-number/contact-mech
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value
    :db/doc         "FK to :contact-mech. 1:1."}

   {:db/ident       :telecom-number/country-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "E.164-style country code, e.g. \"+49\". Stored
                     as a string to preserve the leading +."}

   {:db/ident       :telecom-number/area-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :telecom-number/contact-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :telecom-number/extension
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :telecom-number/ask-for-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Routing hint for switchboards (\"ask for
                     accounts payable\")."}])

;; ============================================================================
;; Email subtype
;; ============================================================================

(def ^:private email-address-attrs
  [{:db/ident       :email-address/contact-mech
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value
    :db/doc         "FK to :contact-mech. 1:1."}

   {:db/ident       :email-address/address
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The email address itself (e.g. jane@example.com)."}

   {:db/ident       :email-address/verified?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :email-address/bounced?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Marks an address that has hard-bounced. Affects
                     :allow-solicitation? semantics in upstream
                     consumers (e.g. dunning, marketing)."}])

;; ============================================================================
;; Partner-contact-mech junction (temporal)
;;
;; Composite identity [partner, contact-mech, from-date]. Multiple
;; rows per (partner, contact-mech) are valid — they represent the
;; same mechanism's association with a partner across distinct time
;; windows (e.g. a phone reassigned away and then back again).
;; ============================================================================

(def ^:private partner-contact-mech-attrs
  [{:db/ident       :partner-contact-mech/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "FK to :partner."}

   {:db/ident       :partner-contact-mech/contact-mech
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "FK to :contact-mech."}

   {:db/ident       :partner-contact-mech/from-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Inclusive start of the association's validity."}

   {:db/ident       :partner-contact-mech/thru-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Exclusive end of the association's validity. Nil
                     = currently active. (Convention: any predicate
                     `active-as-of? d` is true iff
                     from-date <= d < thru-date, or thru-date is nil.)"}

   {:db/ident       :partner-contact-mech/role-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional role-context for this association — a
                     :customer-role :ship-to address vs the same
                     partner's :employee-role home address."}

   {:db/ident       :partner-contact-mech/allow-solicitation?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "True iff the partner has consented to be
                     contacted via this mechanism for marketing /
                     non-transactional outreach. Default false."}

   {:db/ident       :partner-contact-mech/verified?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "True once the consumer has confirmed the
                     mechanism is valid (email round-trip click,
                     postal mail returned, phone-call verification,
                     etc.)."}

   {:db/ident       :partner-contact-mech/comments
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-contact-mech/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:partner-contact-mech/partner
                     :partner-contact-mech/contact-mech
                     :partner-contact-mech/from-date]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity: one row per (partner,
                     contact-mech, from-date)."}])

;; ============================================================================
;; Partner-contact-mech-purpose junction (multi-purpose routing)
;;
;; One contact-mech can serve multiple purposes (one email handles
;; both :billing-email and :general-correspondence). The composite
;; identity allows the same purpose to recur across distinct time
;; windows.
;; ============================================================================

(def ^:private partner-contact-mech-purpose-attrs
  [{:db/ident       :partner-contact-mech-purpose/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-contact-mech-purpose/contact-mech
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-contact-mech-purpose/purpose-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":billing-location | :shipping-location |
                     :primary-email | :billing-email | :primary-phone |
                     :general-correspondence | … See ADR-033 for the
                     canonical vocabulary; consumers extend."}

   {:db/ident       :partner-contact-mech-purpose/from-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-contact-mech-purpose/thru-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-contact-mech-purpose/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:partner-contact-mech-purpose/partner
                     :partner-contact-mech-purpose/contact-mech
                     :partner-contact-mech-purpose/purpose-type
                     :partner-contact-mech-purpose/from-date]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Partner-role (capability assignment)
;;
;; Composite identity [partner, role-type, from-date] — one partner
;; can hold multiple roles concurrently (customer + employee) and a
;; role can recur across distinct time windows (former employee
;; rehired).
;; ============================================================================

(def ^:private partner-role-attrs
  [{:db/ident       :partner-role/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-role/role-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":customer | :supplier | :employee | :contractor |
                     :carrier | :bill-to | :ship-to | :internal-
                     organization | … See ADR-033 for the canonical
                     vocabulary; consumers extend."}

   {:db/ident       :partner-role/from-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-role/thru-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-role/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:partner-role/partner
                     :partner-role/role-type
                     :partner-role/from-date]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Partner-relationship (temporal, multi-role)
;;
;; (from-partner, from-role) → (to-partner, to-role) plus a
;; relationship-type discriminator. Supports the four-quadrant
;; relationship space (Person-Org employment, Org-Org subsidiary,
;; Person-Person family, Org-Person agent-representation) without
;; type-specific tables.
;; ============================================================================

(def ^:private partner-relationship-attrs
  [{:db/ident       :partner-relationship/partner-from
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "FK to :partner — the 'from' side."}

   {:db/ident       :partner-relationship/partner-to
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "FK to :partner — the 'to' side."}

   {:db/ident       :partner-relationship/role-type-from
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Role context of the from-partner (e.g. :employee
                     in an employment relationship)."}

   {:db/ident       :partner-relationship/role-type-to
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Role context of the to-partner (e.g. :internal-
                     organization in an employment relationship)."}

   {:db/ident       :partner-relationship/from-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-relationship/thru-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-relationship/relationship-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":employment | :subsidiary | :branch | :partnership |
                     :reseller-channel | :family | … See ADR-033 for
                     the canonical vocabulary."}

   {:db/ident       :partner-relationship/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":active | :inactive | :pending."}

   {:db/ident       :partner-relationship/relationship-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-friendly label (\"Wholly-owned
                     subsidiary\", \"Senior Engineer\")."}

   {:db/ident       :partner-relationship/position-title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-relationship/priority
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Tiebreaker when one party has multiple
                     concurrent relationships of the same type
                     (e.g. multi-job employment ranking)."}

   {:db/ident       :partner-relationship/comments
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-relationship/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:partner-relationship/partner-from
                     :partner-relationship/role-type-from
                     :partner-relationship/partner-to
                     :partner-relationship/role-type-to
                     :partner-relationship/from-date]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Aggregate + install
;; ============================================================================

(def all
  "Full companion schema as one transactable vector."
  (vec
   (concat partner-ext-attrs
           person-attrs
           org-attrs
           contact-mech-attrs
           postal-address-attrs
           telecom-number-attrs
           email-address-attrs
           partner-contact-mech-attrs
           partner-contact-mech-purpose-attrs
           partner-role-attrs
           partner-relationship-attrs)))

(defn install!
  "Transact the kontor-partner companion schema into a connection.
   Idempotent: re-running on a connection that already has the schema
   produces empty ops on each unchanged ident.

   The kernel schema must already be installed
   (`kontor.core/install-schema!` or `kontor.schema/install!`); this
   companion references kernel attributes like :commodity, :country,
   :state.

   Returns the resulting tx-report."
  [conn]
  (d/transact conn all))
