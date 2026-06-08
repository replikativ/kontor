(ns kontor.l10n-ca.y2024.s9
  "Schedule 9 — Donations and gifts (TY2024).

   Federal credit: 15% on first $200; 29% on excess. The 33% rate
   applies to the extent the filer has taxable income above the top
   bracket threshold ($246,752 in 2024); the credit rate steps up
   only on the portion of donations that would otherwise be taxed at
   33%.

   BC provincial credit (handled in bc428): 5.06% on first $200,
   16.8% on excess.

   Eligibility (out of scope for the math): donations must be to
   registered charities; the donor must hold receipts. We assume
   inputs have already been filtered to eligible amounts."
  (:require [kontor.l10n-ca.y2024.constants :as k]
            [kontor.money :as money]))

(defn- bd ^java.math.BigDecimal [m]
  (:amount m))

(defn- m-mul [m ^java.math.BigDecimal rate]
  (money/money (.multiply ^java.math.BigDecimal (bd m) rate) :CAD))

(defn federal-donation-credit
  "Compute federal donation credit (line 34900 on T1).

   donations: Money :CAD — total eligible donations claimed this year.
   taxable-income: Money :CAD — used to determine 33% bracket exposure.

   Algorithm:
     1. First $200 of donations → 15% credit.
     2. Of the excess (over $200):
        a. The portion that overlaps the 33% bracket → 33% credit.
        b. The rest → 29% credit.

     33%-bracket-portion = min(excess, max(0, taxable-income - 246752))"
  [donations taxable-income]
  (let [d (bd donations)
        ti (bd taxable-income)
        zero (BigDecimal/ZERO)
        low-cap k/federal-donation-low-tier-cap
        low-portion (.min d low-cap)
        excess (.max zero (.subtract d low-cap))
        top-bracket-excess
        (.max zero (.subtract ti k/federal-bpa-phaseout-high))
        excess-at-33 (.min excess top-bracket-excess)
        excess-at-29 (.subtract excess excess-at-33)
        credit (-> (.multiply low-portion k/federal-donation-low-rate)
                   (.add (.multiply excess-at-33 k/federal-donation-top-rate))
                   (.add (.multiply excess-at-29 k/federal-donation-high-rate)))]
    (money/money credit :CAD)))

(defn bc-donation-credit
  "Compute BC donation credit (line 5896 on BC428).

   donations: Money :CAD — total eligible donations.

   Algorithm:
     1. First $200 → 5.06%.
     2. Excess → 16.8%."
  [donations]
  (let [d (bd donations)
        low-cap k/bc-donation-low-tier-cap
        low-portion (.min d low-cap)
        excess (.max BigDecimal/ZERO (.subtract d low-cap))
        credit (.add (.multiply low-portion k/bc-donation-low-rate)
                     (.multiply excess k/bc-donation-high-rate))]
    (money/money credit :CAD)))
