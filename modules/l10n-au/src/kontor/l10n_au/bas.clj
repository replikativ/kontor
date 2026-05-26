(ns kontor.l10n-au.bas
  "Business Activity Statement (BAS) aggregator — the periodic ATO
   return consumers feed into the Business Portal or Standard Business
   Reporting (SBR).

   Where `kontor.l10n-au.gst/compute-return` already computes the raw
   per-label numbers, this ns adds:

     - Cadence-aware period helpers (quarterly + monthly + annual)
       that honour the **Australian financial year (1 July – 30 June)**
       rather than the calendar year (gst.clj defaults to calendar
       quarters for simpler / cross-jurisdiction comparison; here we
       expose AU-FY quarters as the canonical aggregation cadence).

     - A BAS-shaped result map with the labels the ATO publishes on
       the BAS form, plus the net-amount summary every BAS submission
       needs (payable to / refundable from the ATO).

     - Scope notes documenting what's deliberately out of scope.

   ## BAS cadence

   Quarterly is the default for most small / mid businesses.
   Monthly is mandatory at AUD 20M+ turnover and optional below.
   Annual GST returns are available for very small businesses (and
   are paid via instalments). The cadence is a registration-time
   election with the ATO; this aggregator just respects whichever
   one the caller specifies.

   ## AU financial-year quarters

   The ATO numbers BAS quarters by AU financial year:

     Q1 = Jul – Sep
     Q2 = Oct – Dec
     Q3 = Jan – Mar
     Q4 = Apr – Jun

   So `{:fy 2026 :quarter 1}` here = `{:from 2025-07-01 :to 2025-10-01}`
   (FY26 starts 1 July 2025). Callers who want CALENDAR quarters use
   `kontor.l10n-au.gst/compute-return` directly.

   ## BAS labels in scope (substrate-tier)

     1A  GST on sales              — output GST collected
     1B  GST on purchases          — input tax credits (ITCs)
     Net = 1A − 1B                 — payable / refundable

     G1  Total sales (incl. GST)
     G2  Export sales (GST-free)
     G3  Other GST-free sales
     G4  Input-taxed sales
     G10 Capital purchases
     G11 Non-capital purchases

     W1  Total wages (payroll)
     W2  PAYG withheld

   ## Out of scope (per CLAUDE.md substrate-tier scope)

   - **SBR XML envelope generation.** The ATO accepts BAS via SBR
     ebMS3 / AS4; we do NOT produce the XML payload. Consumers pipe
     this aggregator's result into the SBR adapter of their choice.
   - **PAYG instalment lines (T1/T2).** PAYG instalment is its own
     ATO program; not part of the BAS line-set this kernel computes.
   - **WET / LCT / FBT.** Wine equalisation tax, luxury car tax, and
     fringe benefits tax each have their own filing flows — out of
     substrate-tier scope until a consumer surfaces a need.

   ## API

     compute-bas         conn opts → BAS result map
     fy-period-bounds    opts      → {:from :to :kind :fy :quarter? :month?}"
  (:require [kontor.l10n-au.gst :as gst]
            [kontor.money :as money]))

;; ============================================================================
;; AU-FY period bounds
;; ============================================================================

(defn- ld->date ^java.util.Date [^java.time.LocalDate ld]
  (java.util.Date/from (.toInstant (.atStartOfDay ld java.time.ZoneOffset/UTC))))

(defn fy-period-bounds
  "Resolve AU financial-year period bounds for BAS cadences.

   Opts (one combo required):
     :fy <year> :quarter <1..4>      — AU-FY quarter
     :fy <year> :month   <1..12>     — AU-FY-month (1 = July, 12 = June)
     :fy <year>                       — full annual FY
     :from <Date> :to <Date>          — explicit bounds (pass-through)

   FY numbering: `:fy 2026` = the year ending 30 June 2026 (i.e. it
   starts 1 July 2025). This matches the ATO convention printed on
   BAS forms.

   Returns a map:
     {:from java.util.Date         ; inclusive
      :to   java.util.Date         ; exclusive
      :kind :quarterly | :monthly | :annual | :explicit
      :fy   <year>                 ; nil for :explicit
      :quarter <1..4>?             ; only for :quarterly
      :month   <1..12>?            ; only for :monthly}"
  [{:keys [fy quarter month from to]}]
  (cond
    (and from to)
    {:from from :to to :kind :explicit}

    (and fy quarter)
    ;; AU-FY Q1 starts 1 July of (fy-1). Each quarter is 3 months.
    (let [fy-start (java.time.LocalDate/of (int (dec fy)) 7 1)
          q-start  (.plusMonths fy-start (* (dec quarter) 3))
          q-end    (.plusMonths q-start 3)]
      {:from (ld->date q-start) :to (ld->date q-end)
       :kind :quarterly :fy fy :quarter quarter})

    (and fy month)
    ;; AU-FY month 1 = July of (fy-1); month 12 = June of fy.
    (let [fy-start (java.time.LocalDate/of (int (dec fy)) 7 1)
          m-start  (.plusMonths fy-start (dec month))
          m-end    (.plusMonths m-start 1)]
      {:from (ld->date m-start) :to (ld->date m-end)
       :kind :monthly :fy fy :month month})

    fy
    {:from (ld->date (java.time.LocalDate/of (int (dec fy)) 7 1))
     :to   (ld->date (java.time.LocalDate/of (int fy) 7 1))
     :kind :annual :fy fy}

    :else
    (throw (ex-info "fy-period-bounds needs :fy (with optional :quarter/:month) or :from/:to" {}))))

;; ============================================================================
;; BAS labels — substrate-tier set
;; ============================================================================

(def bas-labels
  "Canonical BAS label codes this aggregator publishes. Keep in sync
   with kontor.l10n-au.gst/bas-report-definition."
  [:G1 :G2 :G3 :G4 :G10 :G11 :1A :1B :W1 :W2])

(def simpler-bas-labels
  "Labels lodged under Simpler BAS (turnover < AUD 10M). The ATO
   only requires G1, 1A, 1B, 1H — but 1H (GST instalment) is out of
   substrate-tier scope, so we publish three of the four."
  [:G1 :1A :1B])

;; ============================================================================
;; Aggregator
;; ============================================================================

(defn- zero-money [] (money/zero :AUD))

(defn- safe-get
  "Pull a label value out of a BAS-line map, defaulting to Money 0 AUD."
  [m k]
  (or (get m k) (zero-money)))

(defn- compute-net
  "Net amount = 1A (output GST) − 1B (input tax credits).

   Sign convention:
     positive → payable to ATO
     negative → refundable from ATO
     zero     → nil-return"
  [lines]
  (money/sub (safe-get lines :1A) (safe-get lines :1B)))

(defn- outcome-for
  "Categorise the net amount as :payment / :refund / :nil-return."
  [^kontor.money.Money net]
  (let [sign (.signum ^java.math.BigDecimal (:amount net))]
    (cond
      (pos? sign) :payment
      (neg? sign) :refund
      :else       :nil-return)))

(defn compute-bas
  "Compute a BAS return for a period.

   Required opts (one combo):
     :fy <year>   :quarter <1..4>     — AU-FY quarterly (most common)
     :fy <year>   :month   <1..12>    — AU-FY monthly  (AUD 20M+)
     :fy <year>                        — annual GST return
     :from <Date> :to <Date>           — explicit bounds (escape hatch)

   Optional opts:
     :bas/mode    :simpler (default for turnover < AUD 10M; G1/1A/1B
                            only) or :full (≥ AUD 10M; all labels).
                  Defaults to :full so the caller sees every computed
                  number; explicitly request :simpler when lodging.

   Returns:
     {:bas/form     \"BAS\"
      :bas/mode     :simpler | :full
      :bas/cadence  :quarterly | :monthly | :annual | :explicit
      :bas/period   {…}
      :bas/labels   {:G1 Money :G2 Money … :1A Money :1B Money …}
      :bas/net      Money         ; 1A − 1B; >0 payable, <0 refundable
      :bas/outcome  :payment | :refund | :nil-return}

   The aggregator does NOT generate the SBR ebMS3 / AS4 envelope —
   that's a separate adapter concern. The labels + net amount here
   are exactly what a consumer needs to populate either the Business
   Portal form or an SBR-message payload."
  [conn opts]
  (let [period (fy-period-bounds opts)
        ;; Delegate raw line computation to gst/compute-return, which
        ;; already does the tax-tag aggregation against the chart.
        ;; Use explicit :from/:to so we pass through the FY bounds we
        ;; just resolved (gst's :year/:quarter uses calendar quarters).
        raw (gst/compute-return conn (merge (select-keys opts [:bas/mode])
                                            {:from (:from period)
                                             :to   (:to period)}))
        lines (:kontor.return/lines raw)
        ;; Re-key into the canonical :G1/:1A/... keyword shape and
        ;; ensure every documented label has a value (default zero).
        labels (reduce (fn [acc k] (assoc acc k (safe-get lines k)))
                       {} bas-labels)
        mode (or (:bas/mode opts) :full)
        published (if (= :simpler mode)
                    (select-keys labels simpler-bas-labels)
                    labels)
        net (compute-net labels)]
    {:bas/form    "BAS"
     :bas/mode    mode
     :bas/cadence (:kind period)
     :bas/period  period
     :bas/labels  published
     :bas/net     net
     :bas/outcome (outcome-for net)}))
