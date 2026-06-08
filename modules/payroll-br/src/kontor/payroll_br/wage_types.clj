(ns kontor.payroll-br.wage-types
  "BR-specific `:component-kind` extensions per ADR-081 §3. Open-set
   per ADR-071 + ADR-075's `PayrollFacts` opaque-component
   contract.

   Each kind maps to:
     - an `:account-tag` keyword (the consumer's chart-of-accounts
       lookup key; see `kontor.payroll-br.posting-builder`),
     - optionally a `:rubrica-hint` keyword identifying the eSocial
       S-1010 Tabela de Rubricas semantic family (informational; the
       consumer's catalog binds Rubrica codes per company),
     - optionally a `:requires-cpc-33-accrual?` flag (the engine MAY
       emit per-month accrual components for vacation / 13th salary;
       absent → consumer computes out-of-band via
       `kontor.payroll-br.accrual`).

   Consumer extension: pass an extra map via `:br/extras-map` to the
   posting builder + esocial event builders to add bespoke component
   kinds (e.g. per-company adicionais or PPR profit-sharing variants).

   ## eSocial Rubrica vocabulary (S-1010 Tabela de Rubricas)

   The eSocial S-1010 event lets each employer publish a Tabela de
   Rubricas — the per-company catalog of wage-type codes that surface
   in S-1200 / S-1210 events. kontor does NOT bundle a universal
   Tabela de Rubricas (each company's Tabela is unique to its payroll
   engine + Acordo Coletivo / Convenção Coletiva); the substrate
   ships rubrica-hint keywords that the consumer's catalog binds to
   per-company codes.

   ## Three load-bearing BR accruals (CPC 33 / IAS 19)

   1. **Férias + 1/3 adicional** — mandatory accrual: 1/12 of monthly
      salary per month worked + 1/3 of that as constitutional bonus.
      The `:ferias-accrual` kind is the engine-emitted form; the
      out-of-band builder `kontor.payroll-br.accrual/ferias-accrual-
      tx-data` is for the consumer-computed form (when the engine
      doesn't emit it as a payroll component).
   2. **13º salário** — mandatory accrual: 1/12 of monthly salary per
      month worked. `:thirteenth-salary-accrual` for the engine-emit
      form; `kontor.payroll-br.accrual/thirteenth-salary-accrual-tx-
      data` for out-of-band.
   3. **Multa rescisória de 40% sobre FGTS** — accrual for the
      involuntary-termination 40% severance on FGTS balance.
      `:severance-fgts-accrual` engine-emit form;
      `kontor.payroll-br.accrual/severance-fgts-accrual-tx-data` out-
      of-band.

   Reference: ADR-081 §3, §6 (eSocial rubrica), §7 (BR CoA tags),
   §8 (CPC 33 accruals)."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ============================================================================
;; Standard component-kind table
;; ============================================================================

(def standard-component-kinds
  "Canonical BR wage-type catalog. Keys are component kinds carried in
   `:payroll-facts/components`. See ADR-081 §3 for the full rationale
   per row."
  {;; ──────────────────────────────────────────────────────────────
   ;; EARNINGS — debit gross wages expense, credit net wages payable
   ;; ──────────────────────────────────────────────────────────────
   :base-wage              {:account-tag :br-payroll-wages
                            :rubrica-hint :salario-base}
   :overtime-50            {:account-tag :br-payroll-wages
                            :rubrica-hint :hora-extra-50}
   :overtime-100           {:account-tag :br-payroll-wages
                            :rubrica-hint :hora-extra-100}
   :overtime               {:account-tag :br-payroll-wages
                            :rubrica-hint :hora-extra-50}
   :night-shift-addition   {:account-tag :br-payroll-wages
                            :rubrica-hint :adicional-noturno}
   :hazard-addition        {:account-tag :br-payroll-wages
                            :rubrica-hint :adicional-periculosidade}
   :unhealthy-addition     {:account-tag :br-payroll-wages
                            :rubrica-hint :adicional-insalubridade}
   :commission             {:account-tag :br-payroll-wages
                            :rubrica-hint :comissoes}
   :bonus                  {:account-tag :br-payroll-wages
                            :rubrica-hint :premiacao}
   :vacation-pay-paid-out  {:account-tag :br-payroll-wages
                            :rubrica-hint :ferias-pagas}
   :vacation-bonus-paid-out {:account-tag :br-payroll-wages
                             :rubrica-hint :adicional-1-3-ferias-pago}
   :thirteenth-salary      {:account-tag :br-payroll-wages
                            :rubrica-hint :gratificacao-natalina}
   :ppr-profit-sharing     {:account-tag :br-payroll-wages
                            :rubrica-hint :ppr}
   ;; Severance payouts (verbas rescisórias)
   :termination-pay        {:account-tag :br-payroll-wages
                            :rubrica-hint :verba-rescisoria}

   ;; ──────────────────────────────────────────────────────────────
   ;; BENEFITS — VR / VT (employer share + employee co-pay deduction)
   ;; ──────────────────────────────────────────────────────────────
   :meal-voucher           {:account-tag :br-payroll-vr-vt
                            :rubrica-hint :vale-refeicao}
   :transport-voucher      {:account-tag :br-payroll-vr-vt
                            :rubrica-hint :vale-transporte}
   :taxable-benefit        {:account-tag :br-payroll-benefits
                            :rubrica-hint :beneficio-tributavel}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYEE DEDUCTIONS — credit statutory payable accounts.
   ;; ──────────────────────────────────────────────────────────────
   :inss-employee          {:account-tag :br-payroll-inss-employee
                            :rubrica-hint :inss-empregado}
   :irrf-employee          {:account-tag :br-payroll-irrf
                            :rubrica-hint :irrf}
   :union-dues             {:account-tag :br-payroll-union-dues
                            :rubrica-hint :contribuicao-sindical}
   :transport-voucher-copay {:account-tag :br-payroll-vr-vt
                             :rubrica-hint :vale-transporte-desconto}
   :meal-voucher-copay     {:account-tag :br-payroll-vr-vt
                            :rubrica-hint :vale-refeicao-desconto}
   :garnishment            {:account-tag :br-payroll-garnishment
                            :rubrica-hint :pensao-alimenticia}
   :voluntary-deduction    {:account-tag :br-payroll-other-deduction
                            :rubrica-hint :desconto-diverso}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYER CHARGES — debit expense, credit payable.
   ;; Note: BR distinguishes the employer INSS (CPP — contribuição
   ;; previdenciária patronal, typically ~20% + RAT + outras
   ;; entidades) from the FGTS (8% to the Caixa worker fund).
   ;; ──────────────────────────────────────────────────────────────
   :inss-employer          {:account-tag :br-payroll-er-inss
                            :rubrica-hint :inss-patronal-cpp
                            :employer-side? true
                            :payable-tag :br-payroll-inss-employer}
   :fgts-employer          {:account-tag :br-payroll-er-fgts
                            :rubrica-hint :fgts-patronal
                            :employer-side? true
                            :payable-tag :br-payroll-fgts}
   :sat-rat                {:account-tag :br-payroll-er-inss
                            :rubrica-hint :sat-rat
                            :employer-side? true
                            :payable-tag :br-payroll-inss-employer}
   :outras-entidades       {:account-tag :br-payroll-er-inss
                            :rubrica-hint :outras-entidades-terceiros
                            :employer-side? true
                            :payable-tag :br-payroll-inss-employer}

   ;; ──────────────────────────────────────────────────────────────
   ;; ACCRUALS (CPC 33) — debit accrual expense, credit accrual
   ;; liability. The engine MAY emit these per pay-period; if it
   ;; doesn't, consumer computes via kontor.payroll-br.accrual.
   ;; ──────────────────────────────────────────────────────────────
   :ferias-accrual         {:account-tag :br-payroll-ferias-accrual
                            :employer-side? true
                            :payable-tag :br-payroll-ferias-liability
                            :requires-cpc-33-accrual? true}
   :thirteenth-salary-accrual {:account-tag :br-payroll-13th-accrual
                               :employer-side? true
                               :payable-tag :br-payroll-13th-liability
                               :requires-cpc-33-accrual? true}
   :severance-fgts-accrual {:account-tag :br-payroll-severance-accrual
                            :employer-side? true
                            :payable-tag :br-payroll-severance-liability
                            :requires-cpc-33-accrual? true}

   ;; ──────────────────────────────────────────────────────────────
   ;; CARRY-ONLY (NOT posted) — surface to eSocial events without
   ;; producing a posting leg. Insurable base for INSS / IRRF / FGTS
   ;; goes via this slot.
   ;; ──────────────────────────────────────────────────────────────
   :inss-base              {:rubrica-hint :base-inss   :posts? false}
   :irrf-base              {:rubrica-hint :base-irrf   :posts? false}
   :fgts-base              {:rubrica-hint :base-fgts   :posts? false}
   :hours-worked           {:rubrica-hint :horas       :posts? false}})

(defn merged-catalog
  "Return the catalog merged with consumer-supplied extras-map. The
   extras-map can override or extend the standard catalog. Used by the
   posting builder + eSocial event builders so consumers can add
   bespoke kinds without code change."
  ([] standard-component-kinds)
  ([extras-map]
   (merge standard-component-kinds (or extras-map {}))))

(defn posts?
  "True iff the kind generates posting legs (i.e. is not carry-only)."
  ([kind] (posts? kind nil))
  ([kind extras-map]
   (let [m (get (merged-catalog extras-map) kind)]
     (not (false? (:posts? m))))))

(defn employer-side?
  "True iff the kind represents an employer-side contribution
   (matches the `:employer-side?` flag in a PayrollFact component)."
  ([kind] (employer-side? kind nil))
  ([kind extras-map]
   (boolean (:employer-side? (get (merged-catalog extras-map) kind)))))

(defn account-tag
  "Return the :account-tag keyword for a kind, or nil. Consumer's
   :accounts map keys on this tag. Per ADR-081 §7."
  ([kind] (account-tag kind nil))
  ([kind extras-map]
   (:account-tag (get (merged-catalog extras-map) kind))))

(defn payable-tag
  "Return the :payable-tag keyword for an employer-side kind. For
   :inss-employer this is :br-payroll-inss-employer (the employer
   half of the INSS bucket — distinct from the employee's
   :br-payroll-inss-employee per ADR-081 §3.2 — both feed GFIP /
   eSocial S-1210 lines but settle to distinct DARFs)."
  ([kind] (payable-tag kind nil))
  ([kind extras-map]
   (:payable-tag (get (merged-catalog extras-map) kind))))

(defn rubrica-hint
  "Return the eSocial-rubrica-hint keyword for a kind, or nil. The
   consumer's Tabela de Rubricas (S-1010) binds these hints to actual
   company codes."
  ([kind] (rubrica-hint kind nil))
  ([kind extras-map]
   (:rubrica-hint (get (merged-catalog extras-map) kind))))

(defn requires-cpc-33-accrual?
  "True iff the kind is one of the three load-bearing CPC 33 accruals
   (férias, 13º, multa rescisória de 40% FGTS)."
  ([kind] (requires-cpc-33-accrual? kind nil))
  ([kind extras-map]
   (boolean (:requires-cpc-33-accrual?
             (get (merged-catalog extras-map) kind)))))

(defn known-kinds
  "Set of all known component kinds (standard + extras)."
  ([] (set (keys standard-component-kinds)))
  ([extras-map] (set (keys (merged-catalog extras-map)))))

(defn unknown-kinds
  "Given a vector of components (from PayrollFacts), return the set of
   kinds NOT present in the catalog. Used by the posting builder to
   fail loud rather than silently drop legs."
  ([components] (unknown-kinds components nil))
  ([components extras-map]
   (set/difference (set (map :kind components))
                   (known-kinds extras-map))))

(defn validate-catalog
  "Validate a consumer-supplied Tabela de Rubricas binding map. The
   binding maps eSocial Rubrica codes (per-company string codes
   declared in S-1010) → kontor :kind keywords. Throws ex-info on
   failure (matching the DE / kernel convention5).

   Required shape:
     {:catalog/version 1
      :catalog/cnpj    \"12.345.678/0001-90\"  ;; employer CNPJ
      :catalog/rubricas
        {\"1001\" {:kind :base-wage :natureza \"1000\"}
         \"2001\" {:kind :inss-employee :natureza \"9201\"}
         ...}}

   Each rubrica entry must carry:
     :kind — a known component-kind keyword (incl. extras)
     :natureza — eSocial codNatRubr string (e.g. '1000' for salário,
                 '9201' for INSS empregado). The consumer carries
                 these from the eSocial Tabela 03 catalog.

   Optional:
     :allow-extra-kinds — additional :kind keywords beyond the
                          standard catalog (for per-company custom
                          rubricas)."
  [{:keys [catalog/version catalog/cnpj catalog/rubricas]
    :as catalog}
   & {:keys [allow-extra-kinds]}]
  (when-not (= 1 version)
    (throw (ex-info "Unsupported catalog version"
                    {:expected 1 :found version})))
  (when (or (nil? cnpj) (str/blank? cnpj))
    (throw (ex-info ":catalog/cnpj required (employer CNPJ)" {})))
  (when (or (nil? rubricas) (empty? rubricas))
    (throw (ex-info ":catalog/rubricas must be non-empty" {})))
  (let [valid-kinds (set/union (known-kinds)
                               (set (or allow-extra-kinds #{})))]
    (doseq [[code {:keys [kind natureza]}] rubricas]
      (when-not (string? code)
        (throw (ex-info "Rubrica code must be string"
                        {:code code :type (class code)})))
      (when-not (contains? valid-kinds kind)
        (throw (ex-info (str "Unknown :kind for rubrica " code)
                        {:code code :kind kind
                         :known valid-kinds})))
      (when (or (nil? natureza) (str/blank? natureza))
        (throw (ex-info (str "Rubrica " code " missing :natureza (codNatRubr)")
                        {:code code})))))
  catalog)
