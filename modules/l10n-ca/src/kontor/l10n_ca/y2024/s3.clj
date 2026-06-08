(ns kontor.l10n-ca.y2024.s3
  "Schedule 3 — Capital gains (or losses) for TY2024.

   Inclusion rate: 50% for the full 2024 tax year. The proposed mid-year
   increase to 66.67% on gains exceeding $250,000 was deferred by the
   federal government in early 2025 and is not in force for TY2024.

   Scope of this slice:
     - Sum total gains and total losses.
     - Net capital gain = max(0, gains - losses).
     - Taxable capital gains (line 12700) = net × 50%.

   Out of scope:
     - Per-category breakdown (real estate / shares / mutual funds /
       personal-use / listed personal property) — useful for the form
       layout but not for the math.
     - Adjusted Cost Base (ACB) tracking — assumed inputs are post-ACB.
     - Lifetime Capital Gains Exemption (T657).
     - Capital loss carryforward — handled at the NoA-ingestion layer."
  (:require [kontor.money :as money]))

(def inclusion-rate-2024 0.5M)

(defn compute
  "Compute taxable capital gains.

   Input:
     {:s3/gains   [Money :CAD]    ; per-disposition gains (positive)
      :s3/losses  [Money :CAD]}   ; per-disposition losses (positive)

   Returns:
     {:s3/total-gains          Money
      :s3/total-losses         Money
      :s3/net-capital-gain     Money    ; max(0, gains - losses)
      :s3/unused-loss          Money    ; positive if losses > gains; carryforward
      :s3/taxable-capital-gains Money}  ; line 12700"
  [{:s3/keys [gains losses] :or {gains [] losses []}}]
  (let [total-g (reduce money/add (money/zero :CAD) gains)
        total-l (reduce money/add (money/zero :CAD) losses)
        net (money/sub total-g total-l)
        net-positive (if (money/negative? net) (money/zero :CAD) net)
        unused-loss (if (money/negative? net) (money/neg net) (money/zero :CAD))
        taxable (money/money
                 (.setScale (.multiply ^java.math.BigDecimal (:amount net-positive)
                                       inclusion-rate-2024)
                            2 java.math.RoundingMode/HALF_EVEN)
                 :CAD)]
    {:s3/total-gains          total-g
     :s3/total-losses         total-l
     :s3/net-capital-gain     net-positive
     :s3/unused-loss          unused-loss
     :s3/taxable-capital-gains taxable}))
