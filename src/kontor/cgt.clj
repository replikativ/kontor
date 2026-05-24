(ns kontor.cgt
  "Composition helpers for CGT → PIT/CIT integration — ADR-103
   Addendum 1 (per the CA CGT review-after, note 137 §6).

   The ADR-103 per-jurisdiction CGT providers emit `:capital-gains-
   tax` components whose `:jurisdiction-specific-codes` carry one or
   more of:

     :pit-base-additions  [<bigdec> ...]   add to PIT taxable base
     :pit-base-deductions [<bigdec> ...]   subtract from PIT base
     :cit-base-additions  [<bigdec> ...]   add to CIT taxable base
     :cit-base-deductions [<bigdec> ...]   subtract from CIT base

   The consumer is expected to thread those into the PIT/CIT provider
   via `:inputs :base-transform`. Without a helper, the wire is
   silent-tax-miss-prone — the CA review (note 137 §6) caught this as
   a substrate gap.

   This namespace ships ONE primary helper, `fold-into-base-transform`,
   that gathers the flat-vector additions and deductions across all
   components of a `TaxReturnFacts` and assembles the
   `:base-transform :adjustments` shape the kernel PIT/CIT providers
   consume natively (via `kontor.tax-schedule/apply-base-transform`).

   ## Jurisdiction-specific shapes — NOT handled here

   AT (note 134) uses a tagged-map shape for §30 Abs 7 loss carry:
   `:pit-base-deductions {:§28-vermietung [<bigdec> ...]}` — the loss
   feeds a specific INCOME CATEGORY (Vermietung), not the general PIT
   base. That's a jurisdiction-specific cross-category mechanic;
   consumers writing AT PIT integration consume the tagged-map shape
   directly. The helper here returns `nil` for AT's deductions slot.

   ## Why not put this in `kontor.disposal-source`?

   `DisposalSource` is the data-source protocol (CGT providers depend
   on it for events). `kontor.cgt` is the CONSUMER-side composition
   layer (PIT/CIT providers consume CGT facts via it). Two distinct
   seams — see ADR-103 + ADR-099.")

;; ============================================================================
;; The fold
;; ============================================================================

(defn- vec-additions
  "Pull the `:pit-base-additions` / `:cit-base-additions` slice out of
   one component; returns a seq of BigDecimals. Skips entries whose
   value is a map (AT's tagged shape — handled separately)."
  [component k]
  (let [v (get-in component [:jurisdiction-specific-codes k])]
    (cond (vector? v) v
          (seq? v)    v
          :else       nil)))

(defn fold-into-base-transform
  "Assemble a `:base-transform :adjustments` map from the additions
   and deductions across all components of a CGT `TaxReturnFacts`.

   `pit-or-cit` is `:pit` or `:cit`. Returns:

     {:transform/type :adjustments
      :additions  [<bigdec> ...]
      :deductions [<bigdec> ...]}

   ready to splat into the PIT/CIT provider's
   `:inputs :base-transform`. Returns `nil` when no flat-shape
   additions or deductions are present, so the consumer can
   conditionally omit `:base-transform` (the PIT/CIT default is the
   identity transform — adding an empty `:adjustments` map is wasteful
   but harmless).

   Skips jurisdiction-specific tagged-map shapes (e.g. AT's
   `:pit-base-deductions {:§28-vermietung [...]}`) — those need
   per-jurisdiction routing the consumer wires directly.

   ## Example

     (let [cgt-facts ((:period-tax-facts cgt-provider) ctx)
           pit-bt    (cgt/fold-into-base-transform cgt-facts :pit)
           pit-facts ((:period-tax-facts pit-provider)
                      (cond-> ctx
                        pit-bt (assoc-in [:inputs :base-transform] pit-bt)))]
       ...)"
  [cgt-facts pit-or-cit]
  (when-not (#{:pit :cit} pit-or-cit)
    (throw (ex-info "kontor.cgt/fold-into-base-transform: pit-or-cit must be :pit or :cit"
                    {:given pit-or-cit})))
  (let [k-add (case pit-or-cit :pit :pit-base-additions :cit :cit-base-additions)
        k-ded (case pit-or-cit :pit :pit-base-deductions :cit :cit-base-deductions)
        adds  (vec (mapcat #(vec-additions % k-add) (:components cgt-facts)))
        deds  (vec (mapcat #(vec-additions % k-ded) (:components cgt-facts)))]
    (when (or (seq adds) (seq deds))
      (cond-> {:transform/type :adjustments}
        (seq adds) (assoc :additions adds)
        (seq deds) (assoc :deductions deds)))))

(defn fold-into-inputs
  "Convenience — fold CGT facts into a complete `:inputs` map for the
   PIT/CIT provider. Merges any extra inputs the consumer wants to
   pass (e.g. `:tax-unit`, `:credits`, `:capital-loss-carryforward`).

   The CGT-fold goes under `:base-transform`; everything else passes
   through.

   ## Example

     (ptp/period-tax-facts
       pit-provider
       (assoc base-ctx
              :inputs (cgt/fold-into-inputs cgt-facts :pit
                                            {:tax-unit {:filing-status :single}})))"
  ([cgt-facts pit-or-cit]
   (fold-into-inputs cgt-facts pit-or-cit {}))
  ([cgt-facts pit-or-cit extra-inputs]
   (let [bt (fold-into-base-transform cgt-facts pit-or-cit)]
     (cond-> (or extra-inputs {})
       bt (assoc :base-transform bt)))))

;; ============================================================================
;; Component access helpers — read facets of a TaxReturnFacts cleanly
;; ============================================================================

(defn components-by-lane
  "Return components grouped by their
   `:jurisdiction-specific-codes :lane` keyword (or `nil` for
   components without a lane tag). The lane vocabulary is per-
   provider; the keys you'll see depend on the jurisdiction
   (`:st :lt :§1250-unrecaptured :ordinary-recapture :niit` for US,
   `:de-§8b :de-§17 :de-§20 :de-§23` for DE, etc.)."
  [cgt-facts]
  (group-by #(get-in % [:jurisdiction-specific-codes :lane])
            (:components cgt-facts)))

(defn total-liability
  "Sum `:liability :amount` across all components — the standalone
   CGT tax payable in the period BEFORE any consumer-side
   composition with PIT/CIT."
  [cgt-facts]
  (reduce + 0M
          (keep #(get-in % [:liability :amount])
                (:components cgt-facts))))
