(ns kontor.l10n-ca.y2024.constants
  "Numeric constants for Canadian tax year 2024.

   Sources:
     - CRA 2024 indexation factors (T4127 + general guide 5000-G)
     - CRA 2024 federal brackets per the T1 General + Schedule 1
     - BC Ministry of Finance 2024 brackets per BC428
     - 2024 CPP/EI rates per Canada.ca

   All BigDecimal — exact values, no float drift. Per ADR-015, this
   namespace is immutable once tax year 2024 is filed. A 2025
   sibling will replicate the structure with updated values."
  (:require [kontor.money :as money]))

(def tax-year 2024)

;; ============================================================================
;; Federal tax brackets
;; ============================================================================

(def federal-brackets
  "Each entry covers (prev-upper, :upper]. The last entry's :upper is nil
   (no ceiling)."
  [{:rate 0.15M  :upper 55867M}
   {:rate 0.205M :upper 111733M}
   {:rate 0.26M  :upper 173205M}
   {:rate 0.29M  :upper 246752M}
   {:rate 0.33M  :upper nil}])

;; ============================================================================
;; BC tax brackets
;; ============================================================================

(def bc-brackets
  "BC 2024 personal income tax brackets. Sources:
   BC Min of Finance archived tax rates page (2024).
   Thresholds verified end-2025 — earlier draft had brackets 3-5 off by
   $9-$42 (mis-typed from memory)."
  [{:rate 0.0506M :upper 47937M}
   {:rate 0.077M  :upper 95875M}
   {:rate 0.105M  :upper 110076M}
   {:rate 0.1229M :upper 133664M}
   {:rate 0.147M  :upper 181232M}
   {:rate 0.168M  :upper 252752M}
   {:rate 0.205M  :upper nil}])

;; ============================================================================
;; Basic Personal Amount (federal — income-phased)
;; ============================================================================

(def federal-bpa-max 15705M)
(def federal-bpa-min 14156M)
(def federal-bpa-phaseout-low 173205M)
(def federal-bpa-phaseout-high 246752M)

(def bc-bpa 12580M)

;; ============================================================================
;; Non-refundable credit rates (lowest-bracket rate, by convention)
;; ============================================================================

(def federal-nrtc-rate 0.15M)
(def bc-nrtc-rate 0.0506M)

;; ============================================================================
;; Donation credit (tiered)
;;
;;  Federal: 15% on first $200; 29% on excess (33% on excess to the
;;  extent the filer has income in the 33% bracket).
;;
;;  BC: 5.06% on first $200; 16.8% on excess.
;; ============================================================================

(def federal-donation-low-tier-cap 200M)
(def federal-donation-low-rate 0.15M)
(def federal-donation-high-rate 0.29M)
(def federal-donation-top-rate 0.33M)

(def bc-donation-low-tier-cap 200M)
(def bc-donation-low-rate 0.0506M)
(def bc-donation-high-rate 0.168M)

;; ============================================================================
;; Canada Employment Amount (line 31260)
;; ============================================================================

(def employment-amount-max 1433M)

;; ============================================================================
;; CPP 2024
;;
;;  YMPE: Year's Maximum Pensionable Earnings ($68,500)
;;  YAMPE: Year's Additional Maximum Pensionable Earnings ($73,200) —
;;         CPP2 second-tier (new in 2024).
;;  Basic exemption: $3,500.
;;
;;  Employee total rate 5.95% = 4.95% base + 1.00% enhanced (since 2019).
;;    - Base portion: NRTC (line 30800).
;;    - Enhanced portion: deduction (line 22215).
;;
;;  Self-employed: 11.9% = 2 × 5.95% (paying both halves) on YMPE.
;;  Self-employed CPP2: 8% (= 2 × 4%) on the YAMPE band.
;; ============================================================================

(def cpp-ympe 68500M)
(def cpp-yampe 73200M)
(def cpp-basic-exemption 3500M)
(def cpp-rate-total 0.0595M)
(def cpp-rate-base 0.0495M)
(def cpp-rate-enhanced 0.01M)
(def cpp-rate-self-employed 0.119M)
(def cpp2-rate-employee 0.04M)
(def cpp2-rate-self-employed 0.08M)

;; ============================================================================
;; EI 2024
;; ============================================================================

(def ei-max-insurable 63200M)
(def ei-rate 0.0166M)
(def ei-max-premium 1049.12M)

;; ============================================================================
;; RRSP
;; ============================================================================

(def rrsp-max-deduction 31560M)

;; ============================================================================
;; Helpers
;; ============================================================================

(defn cad [bd]
  (money/money bd :CAD))
