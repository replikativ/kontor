(ns kontor.fx.fx-rate-provider
  "The FxRateProvider protocol — the kernel's seam for foreign-exchange
   rate lookup. ADR-072 (mirrors the [[doc/decisions.md ADR-071]] tax
   abstraction in spirit, with one cleaner contract because rates are
   simpler than tax rules).

   The contract: given (from, to, at-date, rate-type) → a BigDecimal
   multiplier such that amount-in-from × rate = amount-in-to. That is
   the only shape; everything else (period-series for IAS 21 average
   rates, triangulation through a base currency, last-on-or-before
   fallback) is built on top of `resolve-rate`.

   Why a protocol and not a plain function:
     - Different jurisdictions / books pin different sources (ECB for
       eurozone reporting, Fed H.10 for US, central-bank reference
       rates in BR / IN / CN). The substrate must let consumers swap.
     - Paid feeds (XE, OANDA) require API keys that are *customer*
       property, never bundled (same posture as ADR-005's tax-API
       stance and ADR-071's TaxRateProvider).
     - In-DB rates (`:kontor.fx-rate/*`, manually entered or batch-imported)
       and live-API rates compose via [[chain]] just like TaxRateProvider.

   Built-in impls:
     - [[StaticTableProvider]] — reads `:kontor.fx-rate/*` from the connected
       db. Default last-on-or-before policy. Bundle-free.
     - [[EcbReferenceRatesProvider]] — wraps a static-table provider,
       populated by ingesting the ECB euro-reference-rates CSV. The
       CSV publisher's license (\"freely usable provided source is
       acknowledged\") is compatible with redistribution; we ship the
       *adapter*, not the dataset.
     - [[XeProvider]] / [[OandaProvider]] / [[FedH10Provider]] —
       scaffolds. Customer brings their own credential / data file.
     - [[ChainedProvider]] — first non-nil result wins.

   IAS 21 / ASC 830 vocabulary:
     :spot       — point-in-time market rate (default).
     :closing    — period-end spot for monetary BS items.
     :average    — period-average for P&L items.
     :opening    — period-open spot.
     :historical — frozen-at-acquisition for non-monetary items.

   The kernel itself never *uses* an FxRateProvider. Consumers
   (kontor.fx.fx, kontor.posting via :fx-provider opt, future
   kontor.provider.consolidation/translate-currency!) call providers. The
   substrate is just the protocol + the StaticTable backing store."
  (:require [datahike.api :as d]
            [kontor.gate :as gate]
            [kontor.money :as money])
  (:import [java.math BigDecimal]
           [java.util Date]))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol FxRateProvider
  "Resolves an FX rate by (from, to, at-date, rate-type)."
  (provider-id [this]
    "Keyword identifying this provider — used in audit/test contexts.
     Examples: :static-table :ecb :xe :oanda :fed-h10 :chained.")

  (resolve-rate [this query]
    "Given a query map, return a non-zero BigDecimal rate such that
     amount-in-from × rate = amount-in-to, or nil if the provider has
     no opinion.

     Query keys:
       :from-commodity  — commodity (:db/id or :kontor.commodity/symbol string).
       :to-commodity    — commodity, same shape as :from-commodity.
       :at-date         — java.util.Date. The valid-time at which the
                          rate should be evaluated.
       :rate-type       — one of :spot :closing :average :opening
                          :historical. Defaults to :spot if omitted.
       :via             — optional commodity (eid / lookup-ref /
                          symbol). May be ignored by impls. The default
                          [[StaticTableProvider]] uses this as the
                          triangulation pivot when from→to is missing
                          but from→via and via→to both exist.

     Returns:
       BigDecimal (non-zero) or nil.

     Semantics:
       - from = to → 1M (every provider must short-circuit identity).
       - Inverse: if a provider only has from→to, the symmetric
         to→from is `BigDecimal/ONE` divided by the rate. Whether to
         do this is provider-specific; [[StaticTableProvider]] does it
         by default.
       - Triangulation through a base currency (typical: EUR for ECB
         feed) is the provider's call. The default StaticTable impl
         supports an explicit `:via` option.
       - Zero-rate policy: a stored 0M rate is treated as `nil` (\"no
           rate available\") across ALL builtin impls. Per ADR-072
           review P1-72-3 the substrate picks one policy across direct
           and chained lookups: zero is a sentinel for unavailability,
           never \"the rate is literally zero\".")

  (resolve-period-rates [this query]
    "Optional bulk-fetch for period-aware reporting (IAS 21 average
     rate over a P&L period). Default impl is fine for most providers;
     a paid-API impl might batch.

     Query: as resolve-rate, plus :from-date / :to-date / :frequency
     (:daily | :monthly | …).

     Returns a sorted-by-date vector of {:at-date <Date> :rate <bd>}
     maps. May be empty."))

;; ============================================================================
;; Helpers shared by impls
;; ============================================================================

(defn- coerce-commodity-eid
  "Accept either an eid (long), a lookup ref [[:kontor.commodity/symbol \"EUR\"]],
   or a bare string \"EUR\". Returns the eid or nil if not found."
  [db commodity]
  (cond
    (nil? commodity)
    nil
    (number? commodity)
    commodity
    (string? commodity)
    (:db/id (d/entity db [:kontor.commodity/symbol commodity]))
    (vector? commodity)
    (:db/id (d/entity db commodity))
    (and (map? commodity) (:db/id commodity))
    (:db/id commodity)
    :else
    (throw (ex-info "fx-rate-provider: cannot coerce commodity"
                    {:commodity commodity}))))

(defn- rate-type-or-default [rt] (or rt :spot))

;; ============================================================================
;; StaticTableProvider — reads :kontor.fx-rate/* from the connected db
;; ============================================================================

(defn- non-zero
  "Treat 0M as nil (no rate). Per ADR-072 review P1-72-3 zero is the
   substrate-wide sentinel for \"rate unavailable\" — never \"the rate
   is literally zero\" — so direct and chained lookups behave the same."
  [^BigDecimal r]
  (when (and r (not (zero? (.signum r))))
    r))

(defn- query-exact
  "Direct lookup by composite tuple. Returns BigDecimal or nil.

   NOTE: pass the tuple as ONE value via `:in`. The naive form
   `[?e :kontor.fx-rate/by-tuple [?from ?to ?date ?type]]` does NOT do tuple
   equality — datahike treats the position-vector as a fresh binding
   for each slot."
  [db from-eid to-eid ^Date at-date rate-type]
  (non-zero
   (d/q '[:find ?r .
          :in $ ?tuple
          :where
          [?e :kontor.fx-rate/by-tuple ?tuple]
          [?e :kontor.fx-rate/rate ?r]]
        db [from-eid to-eid at-date rate-type])))

(defn- query-last-on-or-before
  "Fallback: most recent sample with date ≤ at-date.
   Returns BigDecimal or nil."
  [db from-eid to-eid ^Date at-date rate-type]
  (let [hits (d/q '[:find ?date ?r
                    :in $ ?from ?to ?cutoff ?type
                    :where
                    [?e :kontor.fx-rate/from-commodity ?from]
                    [?e :kontor.fx-rate/to-commodity   ?to]
                    [?e :kontor.fx-rate/rate-type      ?type]
                    [?e :kontor.fx-rate/at-date        ?date]
                    [?e :kontor.fx-rate/rate           ?r]
                    [(<= ?date ?cutoff)]]
                  db from-eid to-eid at-date rate-type)]
    (when (seq hits)
      (non-zero
       (->> hits
            (sort-by first #(compare %2 %1))   ; descending by date
            first
            second)))))

(defn- query-inverse
  "If from→to is missing, try to→from and invert."
  [db from-eid to-eid ^Date at-date rate-type fallback?]
  (when-let [inv (if fallback?
                   (query-last-on-or-before db to-eid from-eid at-date rate-type)
                   (query-exact db to-eid from-eid at-date rate-type))]
    (when-not (zero? (.signum ^BigDecimal inv))
      (.divide BigDecimal/ONE ^BigDecimal inv 12 java.math.RoundingMode/HALF_EVEN))))

(defn- lookup-leg
  "Resolve ONE (from→to) rate using exact + (optionally) fallback +
   (optionally) inverse. Returns BigDecimal or nil. The triangulation
   step composes two of these."
  [db from-eid to-eid ^Date at-date rate-type fallback? inverse?]
  (or (query-exact db from-eid to-eid at-date rate-type)
      (and fallback?
           (query-last-on-or-before db from-eid to-eid at-date rate-type))
      (and inverse?
           (query-inverse db from-eid to-eid at-date rate-type fallback?))))

(defn- triangulate
  "Compose from→via and via→to. Both legs honor fallback + inverse so
   the via pivot can be used even when one leg is only stored as the
   reverse direction (typical for ECB-style feeds where EUR is the
   only base)."
  [db from-eid to-eid via-eid ^Date at-date rate-type fallback? inverse?]
  (let [f->v (lookup-leg db from-eid via-eid at-date rate-type fallback? inverse?)
        v->t (lookup-leg db via-eid to-eid at-date rate-type fallback? inverse?)]
    (when (and f->v v->t)
      (.multiply ^BigDecimal f->v ^BigDecimal v->t))))

(defrecord StaticTableProvider [conn opts]
  FxRateProvider
  (provider-id [_] :static-table)
  (resolve-rate [_ {:keys [from-commodity to-commodity at-date rate-type via]}]
    (let [db        (d/db conn)
          from-eid  (coerce-commodity-eid db from-commodity)
          to-eid    (coerce-commodity-eid db to-commodity)
          rate-type (rate-type-or-default rate-type)
          fallback? (get opts :fallback-on-or-before? true)
          allow-inv? (get opts :allow-inverse? true)
          via-eid   (cond
                      via                              (coerce-commodity-eid db via)
                      (:default-via opts)              (coerce-commodity-eid
                                                        db (:default-via opts))
                      :else                            nil)]
      (when-not (and from-eid to-eid)
        (throw (ex-info "static-table: unknown commodity"
                        {:from-commodity from-commodity
                         :to-commodity   to-commodity})))
      (cond
        (= from-eid to-eid)
        BigDecimal/ONE

        :else
        (or (lookup-leg db from-eid to-eid at-date rate-type fallback? allow-inv?)
            (when (and via-eid
                       (not= via-eid from-eid)
                       (not= via-eid to-eid))
              (triangulate db from-eid to-eid via-eid at-date rate-type
                           fallback? allow-inv?))))))

  (resolve-period-rates [this {:keys [from-commodity to-commodity
                                      from-date to-date rate-type]}]
    (let [db        (d/db conn)
          from-eid  (coerce-commodity-eid db from-commodity)
          to-eid    (coerce-commodity-eid db to-commodity)
          rate-type (rate-type-or-default rate-type)
          hits (d/q '[:find ?date ?r
                      :in $ ?from ?to ?type ?lo ?hi
                      :where
                      [?e :kontor.fx-rate/from-commodity ?from]
                      [?e :kontor.fx-rate/to-commodity   ?to]
                      [?e :kontor.fx-rate/rate-type      ?type]
                      [?e :kontor.fx-rate/at-date        ?date]
                      [?e :kontor.fx-rate/rate           ?r]
                      [(<= ?lo ?date)]
                      [(<= ?date ?hi)]]
                    db from-eid to-eid rate-type from-date to-date)]
      (->> hits
           (sort-by first)
           (mapv (fn [[d r]] {:at-date d :rate r}))))))

(defn make-static-table-provider
  "Construct a StaticTableProvider against `conn`.

   Options:
     :default-via            — keyword/symbol commodity (e.g. \"EUR\")
                               used as the triangulation base when an
                               explicit `:via` is not passed to
                               `resolve-rate`. Default nil
                               (no triangulation).
     :fallback-on-or-before? — when an exact date sample is missing,
                               return the most recent sample with
                               date ≤ at-date. Default true. Disable
                               for strict bookkeeping where the
                               consumer needs the lookup to be
                               exactly-as-published.
     :allow-inverse?         — when from→to is missing, try to→from
                               and 1/rate. Default true."
  ([conn] (make-static-table-provider conn {}))
  ([conn opts]
   (->StaticTableProvider conn opts)))

;; ============================================================================
;; In-DB write helpers
;; ============================================================================

(defn save-rate!-tx-data
  "Tx-data for ONE :kontor.fx-rate/* sample. Per ADR-068.

   Required: :from :to :at-date :rate
   Optional: :rate-type (default :spot) :source (default :manual)
             :source-doc"
  [{:keys [from to at-date rate rate-type source source-doc]}]
  (when (or (nil? from) (nil? to) (nil? at-date) (nil? rate))
    (throw (ex-info "save-rate!-tx-data: required keys missing"
                    {:got #{(when from :from) (when to :to)
                            (when at-date :at-date) (when rate :rate)}})))
  (cond-> {:kontor.fx-rate/from-commodity (if (string? from) [:kontor.commodity/symbol from] from)
           :kontor.fx-rate/to-commodity   (if (string? to)   [:kontor.commodity/symbol to]   to)
           :kontor.fx-rate/at-date        at-date
           :kontor.fx-rate/rate           (if (instance? BigDecimal rate) rate (bigdec rate))
           :kontor.fx-rate/rate-type      (or rate-type :spot)
           :kontor.fx-rate/source         (or source :manual)}
    source-doc (assoc :kontor.fx-rate/source-doc source-doc)))

(defn save-rates!
  "Transact a batch of rates through the kernel's validation gate.
   Each sample is the same map shape as `save-rate!-tx-data`.
   Idempotent via :kontor.fx-rate/by-tuple — re-transacting the same
   key replaces :rate / :source / :source-doc.

   Routes through `kontor.gate/transact-with-validation` per T-4
   of note 160 — reference-data writes have no postings or sealing
   concern today, but the universal convention (every kernel write
   passes the gate) means future invariants like 'no FX rate for an
   inactive commodity' fire without any further plumbing."
  [conn samples]
  (gate/transact-with-validation conn (mapv save-rate!-tx-data samples)))

;; ============================================================================
;; EcbReferenceRatesProvider — adapter over the ECB euro reference rates
;;
;; The ECB publishes a daily CSV at
;;   https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml
;; (XML) and a historical ZIP at
;;   https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.zip
;; (CSV inside).
;;
;; License: ECB statistical data is "freely usable for any purpose,
;; provided the source is acknowledged" — compatible with
;; redistribution. We ship the *adapter* (the parser + ingest call)
;; and the attribution string.
;; The dataset itself is downloaded by the customer at runtime; we do
;; not bundle a snapshot in this repo. Customer-supplied parser
;; outputs are passed through `save-rates!` then served via the
;; underlying StaticTableProvider.
;;
;; Provider shape: thin wrapper around StaticTableProvider with EUR
;; as the default :via (ECB quotes everything against EUR).
;; ============================================================================

(def ecb-attribution
  "Required attribution string per ECB license. Include in any UI or
   report surface that displays an ECB-sourced rate."
  "Exchange rates: European Central Bank (https://www.ecb.europa.eu/stats/exchange/eurofxref).")

(defrecord EcbReferenceRatesProvider [conn opts inner]
  FxRateProvider
  (provider-id [_] :ecb)
  (resolve-rate [_ q] (resolve-rate inner q))
  (resolve-period-rates [_ q] (resolve-period-rates inner q)))

(defn make-ecb-reference-rates-provider
  "Construct an ECB-flavoured StaticTableProvider — sets EUR as the
   default triangulation pivot. Options are forwarded to the underlying
   StaticTableProvider; pass `:default-via nil` (or any falsy value)
   to disable triangulation explicitly.

   The wrapped StaticTableProvider is built once at construction time
   per ADR-072 review P2-72-4 — avoids allocating a fresh record on
   every lookup."
  ([conn] (make-ecb-reference-rates-provider conn {}))
  ([conn opts]
   (let [merged (merge {:default-via "EUR"} opts)
         inner (make-static-table-provider conn merged)]
     (->EcbReferenceRatesProvider conn merged inner))))

(defn ingest-ecb-csv-rows!
  "Persist a sequence of parsed ECB CSV rows into the :kontor.fx-rate/* table.

   Each row is a map:
     {:at-date <java.util.Date>     ; the publication date
      :rates   {\"USD\" 1.0832M     ; map of ISO-4217 -> rate (vs EUR)
                \"GBP\" 0.8512M
                …}}

   Produces ONE sample per (date, currency) pair — only the forward
   EUR→ccy direction. The reverse direction (ccy→EUR) is derived at
   lookup time by [[StaticTableProvider]]'s `:allow-inverse?` machinery
   (default `true`), so we don't risk inverse-staleness when a customer
   later corrects the forward rate (per ADR-072 review P1-72-1: storing
   both directions caused silent inverse drift when one was overridden).

   Re-ingestion is a no-op via the `:kontor.fx-rate/by-tuple` upsert identity.

   We do NOT bundle a CSV parser; the caller produces rows. ECB's
   eurofxref-daily.xml is one parse call from clojure.data.xml, and
   eurofxref-hist.zip is a ZipInputStream + CSV split. Keep the parse
   on the *consumer* side so kontor's single-dep posture stays."
  [conn rows]
  (let [samples
        (vec (mapcat
              (fn [{:keys [at-date rates]}]
                (mapv
                 (fn [[ccy r]]
                   (let [bd (if (instance? BigDecimal r) r (bigdec r))]
                     {:from "EUR" :to ccy :at-date at-date :rate bd
                      :rate-type :spot :source :ecb
                      :source-doc "eurofxref-daily"}))
                 rates))
              rows))]
    (save-rates! conn samples)))

;; ============================================================================
;; Customer-credentialed scaffolds. None ship implementation. Same
;; license posture as TaxJar/Avalara in tax-rate-provider: customer
;; brings their key; the kernel ships a placeholder so consumer code
;; can be written against the protocol shape.
;; ============================================================================

(defrecord XeProvider [api-key opts]
  FxRateProvider
  (provider-id [_] :xe)
  (resolve-rate [_ _]
    (throw (ex-info "XeProvider not implemented — customer brings api-key + adapter."
                    {:hint "See ADR-072. The protocol shape is stable; an
                            adapter against xe.com's REST API is < 200 LoC."})))
  (resolve-period-rates [_ _]
    (throw (ex-info "XeProvider.resolve-period-rates not implemented." {}))))

(defrecord OandaProvider [api-key opts]
  FxRateProvider
  (provider-id [_] :oanda)
  (resolve-rate [_ _]
    (throw (ex-info "OandaProvider not implemented — customer brings api-key + adapter."
                    {})))
  (resolve-period-rates [_ _]
    (throw (ex-info "OandaProvider.resolve-period-rates not implemented." {}))))

(defrecord FedH10Provider [csv-path opts]
  FxRateProvider
  (provider-id [_] :fed-h10)
  (resolve-rate [_ _]
    (throw (ex-info "FedH10Provider not implemented — ingest via
                     ingest-fed-h10-csv-rows! once written."
                    {:csv-path csv-path})))
  (resolve-period-rates [_ _]
    (throw (ex-info "FedH10Provider.resolve-period-rates not implemented." {}))))

;; ============================================================================
;; ChainedProvider — try in order; first non-nil wins.
;; ============================================================================

(defrecord ChainedProvider [providers]
  FxRateProvider
  (provider-id [_] :chained)
  (resolve-rate [_ q]
    (some (fn [p]
            (when-let [r (resolve-rate p q)]
              (when (and (instance? BigDecimal r)
                         (not (zero? (.signum ^BigDecimal r))))
                r)))
          providers))
  (resolve-period-rates [_ q]
    (or (some (fn [p]
                (let [rs (resolve-period-rates p q)]
                  (when (seq rs) rs)))
              providers)
        [])))

(defn chain
  "Construct a ChainedProvider that tries each in order. Useful when an
   in-DB StaticTable holds customer-overridden rates and an EcbProvider
   serves the rest, or when a paid-API provider sits in front of an
   in-DB cache.

   Returns the first non-nil, non-zero BigDecimal from `resolve-rate`.

   ## Asymmetry with scaffold providers

   The credentialed scaffolds ([[XeProvider]], [[OandaProvider]],
   [[FedH10Provider]]) THROW on `resolve-rate` rather than returning
   nil — they're scaffolds, not stubs, and failing loud is the
   intended posture (ADR-072). Consequently `ChainedProvider` does NOT
   wrap each provider in try/catch: a chain with a not-yet-implemented
   scaffold ahead of a real provider will explode rather than degrade.

   Either implement the scaffold (it's the customer's adapter) or
   omit it from the chain. The exception's :hint guides the
   maintainer to ADR-072 + the credential-source documentation."
  [& providers]
  (->ChainedProvider (vec providers)))
