(ns kontor.l10n-ca.y2024.s11
  "Schedule 11 — Federal tuition, education, and textbook amounts (TY2024).

   Federal credit: 15% of eligible tuition (line 32300 → folds into the
   line-33500 NRTC subtotal, then taxed at 15% to produce line 33800).

   We model only the federal current-year tuition credit. Out of scope
   for this slice:
     - Unused tuition carryforward from prior years (post-MVP).
     - Tuition transferred from a child (line 32400).
     - Provincial tuition credits (BC428 has its own).

   Assumes inputs have been filtered to eligible amounts (T2202 box A
   tuition fees from a designated educational institution)."
  (:require [kontor.l10n-ca.y2024.constants :as k]
            [kontor.money :as money]))

(defn eligible-tuition-amount
  "Line 32300 — the federal tuition amount claimed.

   tuition-paid: Money :CAD — eligible tuition for the year.

   For this slice it equals tuition-paid; carryforward semantics will
   refine this when prior-year balances are modeled (post-MVP)."
  [tuition-paid]
  tuition-paid)
