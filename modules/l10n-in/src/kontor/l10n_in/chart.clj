(ns kontor.l10n-in.chart
  "Indian chart-of-accounts loader.

   India has NO single statutory mandatory-coded chart-of-accounts.
   The de-facto framing is:

     * **Schedule III of the Companies Act 2013** — defines the
       required Balance Sheet + Statement of P&L line items and
       classification (Division I: legacy Indian-GAAP; Division II:
       Ind AS-aligned, per G.S.R. 207(E), 2016). Schedule III pins
       *line items*, not numeric codes.

     * **Ind AS (Indian Accounting Standards)** — IFRS-converged
       standards mandatory for listed entities + entities above the
       Companies (Indian Accounting Standards) Rules 2015 thresholds
       (net worth ≥ ₹250 crore for Phase II, etc.). Optional for
       others.

   This module ships a 6-digit-coded starter chart whose hierarchy
   mirrors Schedule III (Division II form) so SMB and mid-market
   filings can map directly. The exact codes are conventions chosen
   for kontor; they are NOT prescribed by Indian law. Customers
   extend the chart freely.

   ## Tax accounts (GST 2.0 + RCM + TDS)

   Output-side (collected, payable to govt) — liabilities under 33xxxx:
     CGST + SGST   intra-state split (within one state, supplier =
                   POS state)
     IGST          inter-state (supplier ≠ POS state)
     UTGST         Union Territory without legislature
     Cess          Compensation cess on luxury / sin goods

   Input-side (paid to vendors, ITC recoverable) — assets under 13xxxx:
     same four heads, mirroring the output side.

   Reverse Charge (RCM) — when notified goods/services are bought,
   the BUYER pays the GST to govt and (typically) claims it back as
   ITC in the same return. Modeled as parallel pairs:
     liability  3321xx / 3322xx / 3323xx  RCM payable
     asset      1316xx / 1317xx / 1318xx  RCM ITC

   TDS (Tax Deducted at Source under the Income-tax Act 1961):
     payable    3331xx-3339xx   (buyer withholds and remits)
     receivable 1321xx-1329xx   (supplier recovers against own tax)

   Idempotent: account paths are `:db.unique/identity` in the kernel
   schema, so re-installing replaces values without duplication.
   Tags from the EDN are materialized as `:kontor.account-tag/*` entities
   (one per distinct tag keyword) and linked via `:kontor.account/tags`.
   The l10n-in GSTR-1 / GSTR-3B aggregators (`./returns.clj`) read
   these tags.

   Source documents (public, non-copyrightable):
     - Schedule III, Companies Act 2013 (Ministry of Corporate Affairs)
     - G.S.R. 207(E) dated 30-03-2016 (Ind AS-aligned Division II)
     - Ind AS framework (ICAI)
     - GST 2.0 slab structure (56th GST Council, effective 2025-09-22)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

;; ============================================================================
;; Resource loading
;; ============================================================================

(defn load-chart
  "Read the IN chart EDN. Returns a vector of account spec maps."
  []
  (-> "kontor/l10n_in/chart.edn"
      io/resource
      slurp
      edn/read-string))

;; ============================================================================
;; Tag materialization
;; ============================================================================

(defn- distinct-tags
  "Every tag keyword referenced anywhere in the chart, deduplicated."
  [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx-data
  [tags]
  (mapv (fn [tag]
          {:kontor.account-tag/name (name tag)
           :kontor.account-tag/country-code "IN"
           :kontor.account-tag/applicability :account})
        tags))

;; ============================================================================
;; Commodity (INR)
;; ============================================================================

(defn- ensure-inr
  "Idempotent INR commodity. Indian Rupee, precision 2."
  []
  {:kontor.commodity/symbol "INR"
   :kontor.commodity/name "Indian Rupee"
   :kontor.commodity/precision 2
   :kontor.commodity/iso-4217 "INR"})

;; ============================================================================
;; Account materialization
;; ============================================================================

(defn- account-tx-entry
  "Build the kernel-side account entity-map for one chart entry.
   :kontor.account/path is the unique identity; tags become refs via the
   materialized :account-tag entities."
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:kontor.account/path        path
           :kontor.account/code        code
           :kontor.account/name        name
           :kontor.account/type        type
           :kontor.account/active      true
           :kontor.account/commodity   [:kontor.commodity/symbol "INR"]
           :kontor.account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :kontor.account/tags
           (mapv (fn [t] [:kontor.account-tag/name (clojure.core/name t)]) tags))))

;; ============================================================================
;; Public installer
;; ============================================================================

(defn install!
  "Transact the IN chart + the per-tag `:account-tag` entities into
   `conn`. Idempotent. Returns the resulting tx-report from the
   final transact."
  ([conn] (install! conn (load-chart)))
  ([conn chart]
   ;; 1. INR commodity
   (d/transact conn [(ensure-inr)])
   ;; 2. Tags first so account-tx refs resolve.
   (d/transact conn (tag-tx-data (distinct-tags chart)))
   ;; 3. Accounts
   (d/transact conn (mapv account-tx-entry chart))))

;; ============================================================================
;; Convenience: key account-code constants
;;
;; The invoice + closing + returns modules pin a small set of codes
;; by default. Centralising the constants here so the chart is the
;; single source of truth.
;; ============================================================================

(def ^:const ar-code                 "121100")   ; Trade Receivables — Domestic
(def ^:const ar-export-code          "121200")   ; Trade Receivables — Export
(def ^:const ap-code                 "320100")   ; Trade Payables — Domestic
(def ^:const bank-code               "122200")   ; Bank — Current account
(def ^:const cash-code               "122100")   ; Cash in hand

(def ^:const sales-domestic-code     "410000")   ; Sales — domestic taxable
(def ^:const sales-b2c-code          "410100")   ; Sales — B2C
(def ^:const sales-export-code       "410200")   ; Sales — Exports (zero-rated)
(def ^:const sales-exempt-code       "410300")   ; Sales — Exempt / Nil-rated
(def ^:const services-code           "410900")   ; Services revenue

(def ^:const output-cgst-code        "331100")
(def ^:const output-sgst-code        "331200")
(def ^:const output-igst-code        "331300")
(def ^:const output-utgst-code       "331400")
(def ^:const output-cess-code        "331500")

(def ^:const input-cgst-code         "131100")
(def ^:const input-sgst-code         "131200")
(def ^:const input-igst-code         "131300")
(def ^:const input-utgst-code        "131400")
(def ^:const input-cess-code         "131500")

(def ^:const rcm-cgst-payable-code   "332100")
(def ^:const rcm-sgst-payable-code   "332200")
(def ^:const rcm-igst-payable-code   "332300")
(def ^:const rcm-cgst-itc-code       "131600")
(def ^:const rcm-sgst-itc-code       "131700")
(def ^:const rcm-igst-itc-code       "131800")

(def ^:const retained-earnings-code  "220900")   ; Reserves & Surplus — Retained Earnings

;; ============================================================================
;; Lookups
;; ============================================================================

(defn account-by-code
  "Resolve `code` to an account entity-id against `db`, or nil."
  [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))
