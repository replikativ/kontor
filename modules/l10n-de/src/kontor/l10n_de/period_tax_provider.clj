(ns kontor.l10n-de.period-tax-provider
  "German personal income tax — Einkommensteuer — as a kontor
   `PeriodTaxProvider` (ADR-099; research note 103).

   The schedule is §32a EStG — NOT a bracket ladder but a continuous
   piecewise-polynomial formula. It is expressed as a `:formula`
   schedule: the substrate's escape hatch carries the real statutory
   function unchanged, so the abstraction does not flatten DE's tax
   into an approximating bracket table. The Solidaritätszuschlag — a
   surcharge on the tax itself, with its own Freigrenze and
   Milderungszone — is a computed `:surtax-fn`.

   Church tax (membership-dependent, 8–9 %) rides `context :inputs`
   as a surtax; Werbungskosten / Sonderausgaben as the deduction
   `:base-transform`."
  (:require [kontor.personal-income-tax :as pit]))

;; ============================================================================
;; §32a EStG — the income-tax formula
;; ============================================================================

(defn- est-2024
  "§32a EStG, tax year 2024 — the Einkommensteuer formula. `zve` (zu
   versteuerndes Einkommen) is rounded down to a full euro; the
   resulting tax is a full euro. Five zones: the Grundfreibetrag
   (0 %), two progression zones (a quadratic in the bracket fraction),
   and two linear zones. Verify the coefficients against current law."
  ^java.math.BigDecimal [zve _ctx]
  (let [x   (.setScale (bigdec zve) 0 java.math.RoundingMode/FLOOR)
        est (cond
              (<= x 11604M) 0M
              (<= x 17005M) (let [y (/ (- x 11604M) 10000M)]
                              (* (+ (* 922.98M y) 1400M) y))
              (<= x 66760M) (let [z (/ (- x 17005M) 10000M)]
                              (+ (* (+ (* 181.19M z) 2397M) z) 1025.38M))
              (<= x 277825M) (- (* 0.42M x) 10602.13M)
              :else          (- (* 0.45M x) 18936.88M))]
    (.setScale ^java.math.BigDecimal est 0 java.math.RoundingMode/FLOOR)))

;; ============================================================================
;; Solidaritätszuschlag
;; ============================================================================

(def ^:private soli-freigrenze
  "Solidaritätszuschlag Freigrenze 2024 (single filer) — no Soli is
   due on an Einkommensteuer at or below this; verify."
  18130M)

(defn- soli-amount
  "Solidaritätszuschlag on an Einkommensteuer of `est` — 5.5 %, with
   the Freigrenze and the Milderungszone (the 11.9 % sliding cap that
   phases the Soli in above the Freigrenze); zero at or below the
   Freigrenze."
  ^java.math.BigDecimal [^java.math.BigDecimal est]
  (if (> est soli-freigrenze)
    (min (* 0.055M est) (* 0.119M (- est soli-freigrenze)))
    0M))

(def ^:private soli-adjustment
  "The Solidaritätszuschlag as an adjustment-layer item (note 105) —
   a surtax computed from the running income tax."
  {:code   :soli
   :label  "Solidaritätszuschlag"
   :op     :surtax
   :amount (fn [ctx] (soli-amount (:running ctx)))})

(defn de-income-tax-provider
  "DE personal income tax — Einkommensteuer — provider. The §32a
   continuous-formula schedule plus the Solidaritätszuschlag (a
   computed surtax in the adjustment layer)."
  [_]
  (pit/personal-income-tax-provider
   {:id          :de-est
    :schedule    {:schedule/type :formula :fn est-2024}
    :authority   :de-finanzamt
    :commodity   :EUR
    :statute     "§32a EStG"
    :adjustments [soli-adjustment]}))
