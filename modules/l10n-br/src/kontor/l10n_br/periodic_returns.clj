(ns kontor.l10n-br.periodic-returns
  "Brazilian periodic indirect-tax return generators — substrate-tier.

   Brazil's indirect-tax filing landscape is a *family* of returns
   filed on overlapping cadences across the federal / state /
   municipal layers. This namespace produces the **aggregated
   numbers** per return type that downstream filing tools (XML
   emitters, ELSTER-style transports) consume. It does NOT emit
   filing XML — that's a SPED-side concern (see `sped.clj`) or a
   per-state extension that customers maintain.

   ## Returns scope

     - **PIS** + **COFINS** (federal): monthly. Cumulative or
       non-cumulative regime per taxpayer × per revenue stream.
       Filed via **EFD-Contribuições** (SPED block) and consolidated
       in **DCTFWeb**. Due by the 15th business day of the second
       month after the period (RFB IN 2.005/2021).

     - **ICMS** (state): monthly. Filing form varies by state:
         - **GIA** (Guia de Informação e Apuração) — SP, RJ, RS and
           several others
         - **SINTEGRA** (legacy national register, being phased out;
           still required by some states)
         - **EFD ICMS/IPI** — SPED block aggregating ICMS + IPI;
           filed monthly with the state SEFAZ (Convênio ICMS 143/2006).
       Due dates vary by state — most use the 10th-20th of the
       following month.

     - **IPI** (federal manufacturing tax): monthly via EFD ICMS/IPI.
       Aggregated with ICMS in the SPED block but tax remittance is
       separate (federal). Due 25th of the following month.

     - **ISS** (municipal): monthly per município. Filing forms vary
       widely (NFS-e captures the data; the consolidation form is
       municipal). Per the brief, this module produces per-municipality
       totals; the consumer routes them to the appropriate municipal
       filing.

     - **DCTFWeb** (federal consolidation): a unified monthly
       declaration that consolidates PIS, COFINS, INSS, and several
       other federal taxes for transmission to RFB. Replaces the
       legacy DCTF for periods from 2018 onward (full migration by
       2024 per RFB IN 2.005/2021). This module produces the PIS +
       COFINS components that feed DCTFWeb.

   ## What this module does NOT do

     - **No XML / ELSTER-style transport.** Filing-format emitters
       are downstream (sped.clj handles EFD-ICMS-IPI; an EFD-
       Contribuições emitter would live alongside; DCTFWeb's XML +
       digital-signature flow is a separate concern).
     - **No NCM lookup.** When a return needs per-NCM breakdown the
       consumer pre-resolves the classification before calling.
     - **No state-specific GIA layout.** Per ADR-005 we ship the
       protocol-equivalent (the aggregated numbers); each state's
       GIA layout is the consumer's problem.

   ## API shape

   Each generator function takes `conn` + a period-window opts map
   and returns a return-data map. All commodity values are
   `Money :BRL`. The return-data maps carry `:kontor.return/form`,
   `:kontor.return/period`, and the aggregated line totals + drill-down
   posting ids so a downstream auditor can follow each number back to
   contributing postings.

     (generate-pis-cofins-return conn {:from <Date> :to <Date>})
     (generate-icms-return       conn {:from <Date> :to <Date> :state \"SP\"})
     (generate-ipi-return        conn {:from <Date> :to <Date>})
     (generate-iss-return        conn {:from <Date> :to <Date>})
     (generate-dctf-web          conn {:from <Date> :to <Date>})

   Period helpers:
     (period-bounds {:year 2026 :month 1}) → {:from … :to …}
     (period-bounds {:year 2026 :quarter 1}) → quarterly bounds

   Each return name + form code matches the official RFB / state /
   municipal nomenclature; the form-data keys also match so callers
   can pipe straight into a filing-XML emitter."
  (:require [kontor.money :as money]
            [kontor.reporting.report :as report])
  (:import [java.util Calendar Date]))

;; ============================================================================
;; Period bounds
;; ============================================================================

(defn- date-at
  ^Date [year month day]
  (let [cal (Calendar/getInstance (java.util.TimeZone/getTimeZone "UTC"))]
    (.clear cal)
    (.set cal year (dec month) day 0 0 0)
    (.set cal Calendar/MILLISECOND 0)
    (.getTime cal)))

(defn period-bounds
  "Compute the `[:from :to)` window for a Brazilian filing period.

   Inputs (mutually exclusive):
     :year + :month   → monthly window (most BR returns are monthly)
     :year + :quarter → quarterly window (some annual-or-quarterly
                          consolidations)
     :year            → annual window (ECF, DIRF)

   The window is half-open: `:from` inclusive, `:to` exclusive."
  [{:keys [year month quarter]}]
  (cond
    (and year month)
    (let [from (date-at year month 1)
          next-month (if (= month 12) 1 (inc month))
          next-year  (if (= month 12) (inc year) year)
          to (date-at next-year next-month 1)]
      {:from from :to to :kind :monthly :year year :month month})

    (and year quarter)
    (let [first-month (inc (* 3 (dec quarter)))
          last-month  (+ 2 first-month)
          from (date-at year first-month 1)
          next-month (if (= last-month 12) 1 (inc last-month))
          next-year  (if (= last-month 12) (inc year) year)
          to (date-at next-year next-month 1)]
      {:from from :to to :kind :quarterly :year year :quarter quarter})

    year
    {:from (date-at year 1 1) :to (date-at (inc year) 1 1)
     :kind :annual :year year}

    :else
    (throw (ex-info "period-bounds requires :year (+ optional :month / :quarter)"
                    {:input {:year year :month month :quarter quarter}}))))

(defn- resolve-window
  "Accept either an explicit `:from`/`:to` window or a `:year`/`:month`/
   `:quarter` shorthand. Returns the DESCRIPTIVE window map — `:from` and
   `:to` plus the `:kind` / `:year` / `:month` provenance the returns
   echo back as `:kontor.return/period`."
  [opts]
  (if (:from opts)
    (select-keys opts [:from :to])
    (period-bounds opts)))

(defn- window->report-opts
  "The window as `kontor.reporting.report` options — i.e. the bounds only.

   `resolve-window`'s `:kind` / `:year` / `:month` describe the filing
   period for the return payload; they are not report options, and the
   engine now rejects unknown keys rather than ignoring them (see
   `report/check-options!`). Keeping the two shapes distinct here is the
   point: the descriptive map is for humans, this is for the engine."
  [window]
  (select-keys window [:from :to]))

;; ============================================================================
;; PIS + COFINS (federal — monthly EFD-Contribuições / DCTFWeb)
;; ============================================================================

(def pis-cofins-definition
  "PIS + COFINS aggregation per RFB IN 2.005/2021.

   Each line keys on the BR chart's tax-account tags
   (`:br-pis-output`, `:br-cofins-output`, `:br-pis-input`,
   `:br-cofins-input`). Output tags net to PIS / COFINS payable; the
   input tags carry recoverable (non-cumulative regime) credits.

   Per ADR-019 the chart-of-accounts is responsible for tagging; the
   return engine here is purely declarative.

   Field codes (right column) match the EFD-Contribuições layout
   M205 (PIS) and M605 (COFINS) blocks."
  {:report/name    "PIS + COFINS aggregation (EFD-Contribuições / DCTFWeb)"
   :report/country "BR"
   :report/lines
   [{:line/code "pis-output"
     :line/label "PIS collected on outbound (M205/04)"
     :line/expression {:engine :tax-tags :tags [:br-pis-output]
                       :sign :inflow :commodity :BRL}}
    {:line/code "pis-input"
     :line/label "PIS recoverable on inbound (M205/02)"
     :line/expression {:engine :tax-tags :tags [:br-pis-input]
                       :sign :inflow :commodity :BRL}}
    {:line/code "cofins-output"
     :line/label "COFINS collected on outbound (M605/04)"
     :line/expression {:engine :tax-tags :tags [:br-cofins-output]
                       :sign :inflow :commodity :BRL}}
    {:line/code "cofins-input"
     :line/label "COFINS recoverable on inbound (M605/02)"
     :line/expression {:engine :tax-tags :tags [:br-cofins-input]
                       :sign :inflow :commodity :BRL}}]})

(defn generate-pis-cofins-return
  "Compute the monthly PIS + COFINS aggregation.

   Filed via EFD-Contribuições (SPED block) and consolidated into
   DCTFWeb (RFB IN 2.005/2021). Filing due: 15th business day of the
   second month following the period.

   Required:
     :from / :to            explicit window, OR
     :year + :month         shorthand for the monthly window

   Returns:
     {:kontor.return/form    \"EFD-Contribuições\"
      :kontor.return/period  {:from … :to … :kind :monthly :year … :month …}
      :kontor.return/lines   {:pis-output Money :pis-input Money
                        :cofins-output Money :cofins-input Money}
      :kontor.return/pis-net      Money — payable (positive) or credit (negative)
      :kontor.return/cofins-net   Money — payable / credit
      :kontor.return/total-net    Money — combined PIS + COFINS net to remit
      :report/lines        — drill-down per line (postings included)}"
  [conn opts]
  (let [window (resolve-window opts)
        r (report/compute-report conn pis-cofins-definition (window->report-opts window))
        line (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                   (:report/lines r))
        zero (money/zero :BRL)
        get-z (fn [k] (or (get line k) zero))
        pis-out (get-z "pis-output")
        pis-in  (get-z "pis-input")
        cof-out (get-z "cofins-output")
        cof-in  (get-z "cofins-input")
        pis-net (money/sub pis-out pis-in)
        cof-net (money/sub cof-out cof-in)
        total   (money/add pis-net cof-net)]
    (-> r
        (assoc :kontor.return/form "EFD-Contribuições"
               :kontor.return/period window
               :kontor.return/lines {:pis-output    pis-out
                              :pis-input     pis-in
                              :cofins-output cof-out
                              :cofins-input  cof-in}
               :kontor.return/pis-net    pis-net
               :kontor.return/cofins-net cof-net
               :kontor.return/total-net  total))))

;; ============================================================================
;; ICMS (state — monthly EFD ICMS/IPI + GIA)
;; ============================================================================

(def icms-definition
  "ICMS per-state monthly aggregation. ICMS is **state-level**; this
   sums all ICMS-tagged postings into a single payable/recoverable
   pair. State-level disaggregation (GIA per-state) is the consumer's
   layer — pass `:state` to the generator to filter."
  {:report/name    "EFD ICMS/IPI — ICMS aggregation"
   :report/country "BR"
   :report/lines
   [{:line/code "icms-output"
     :line/label "ICMS collected on outbound (E110/02)"
     :line/expression {:engine :tax-tags :tags [:br-icms-output]
                       :sign :inflow :commodity :BRL}}
    {:line/code "icms-input"
     :line/label "ICMS recoverable on inbound (E110/06)"
     :line/expression {:engine :tax-tags :tags [:br-icms-input]
                       :sign :inflow :commodity :BRL}}]})

(defn generate-icms-return
  "Compute the monthly ICMS aggregation per state.

   Filed monthly via the EFD ICMS/IPI SPED block (Convênio ICMS
   143/2006) AND the state-specific GIA / SINTEGRA / equivalent.
   Due dates vary by state — typically the 10th-20th of the
   following month.

   Required:
     :from / :to OR :year + :month  — period bounds

   Optional:
     :state  — 2-letter BR state code (\"SP\", \"BA\", …) to scope
               the return. **Currently the generator does NOT filter
               by state** because origin/destination tracking is
               not yet a kernel-level posting attribute; the
               `:state` value is echoed in the return-data so the
               consumer can route. Splitting by origin/destination
               state will land when the kernel grows per-state
               sub-account tags (ADR-019-style external-code
               mapping is the natural extension).

   Returns:
     {:kontor.return/form        \"EFD ICMS/IPI\"
      :kontor.return/state       <2-letter code or nil>
      :kontor.return/period      {:from … :to … :kind … }
      :kontor.return/lines       {:icms-output Money :icms-input Money}
      :kontor.return/icms-net    Money — payable (positive) / recoverable
      :report/lines       — drill-down per line}"
  [conn {:keys [state] :as opts}]
  (let [window (resolve-window opts)
        r (report/compute-report conn icms-definition (window->report-opts window))
        line (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                   (:report/lines r))
        zero (money/zero :BRL)
        get-z (fn [k] (or (get line k) zero))
        out (get-z "icms-output")
        in  (get-z "icms-input")
        net (money/sub out in)]
    (-> r
        (assoc :kontor.return/form   "EFD ICMS/IPI"
               :kontor.return/state  state
               :kontor.return/period window
               :kontor.return/lines  {:icms-output out :icms-input in}
               :kontor.return/icms-net net))))

;; ============================================================================
;; IPI (federal — monthly EFD ICMS/IPI)
;; ============================================================================

(def ipi-definition
  "IPI federal manufacturing tax aggregation."
  {:report/name    "EFD ICMS/IPI — IPI aggregation"
   :report/country "BR"
   :report/lines
   [{:line/code "ipi-output"
     :line/label "IPI collected on outbound (E520/04)"
     :line/expression {:engine :tax-tags :tags [:br-ipi-output]
                       :sign :inflow :commodity :BRL}}
    {:line/code "ipi-input"
     :line/label "IPI recoverable on inbound (E520/02)"
     :line/expression {:engine :tax-tags :tags [:br-ipi-input]
                       :sign :inflow :commodity :BRL}}]})

(defn generate-ipi-return
  "Compute the monthly IPI aggregation (federal manufacturing tax).

   Filed monthly via the EFD ICMS/IPI SPED block. The numbers also
   feed the federal DCTF-Web consolidation. Due by the 25th of the
   following month for outbound; the recoverable side reduces the
   payable via the non-cumulative credit mechanism.

   Required:
     :from / :to OR :year + :month

   Returns:
     {:kontor.return/form     \"EFD ICMS/IPI\"
      :kontor.return/period   window
      :kontor.return/lines    {:ipi-output Money :ipi-input Money}
      :kontor.return/ipi-net  Money — payable (positive) / credit (negative)
      :report/lines    — drill-down}"
  [conn opts]
  (let [window (resolve-window opts)
        r (report/compute-report conn ipi-definition (window->report-opts window))
        line (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                   (:report/lines r))
        zero (money/zero :BRL)
        get-z (fn [k] (or (get line k) zero))
        out (get-z "ipi-output")
        in  (get-z "ipi-input")
        net (money/sub out in)]
    (-> r
        (assoc :kontor.return/form    "EFD ICMS/IPI"
               :kontor.return/period  window
               :kontor.return/lines   {:ipi-output out :ipi-input in}
               :kontor.return/ipi-net net))))

;; ============================================================================
;; ISS (municipal — per município)
;; ============================================================================

(def iss-definition
  "ISS municipal service-tax aggregation.

   ISS is collected per município; the kernel-level aggregation
   sums all ISS-tagged postings. Per-municipality split is an
   extension dimension the consumer adds via :kontor.posting/partner →
   municipality routing or a per-municipality sub-account tag."
  {:report/name    "ISS — Imposto sobre Serviços (municipal)"
   :report/country "BR"
   :report/lines
   [{:line/code "iss-output"
     :line/label "ISS collected on services rendered"
     :line/expression {:engine :tax-tags :tags [:br-iss-output]
                       :sign :inflow :commodity :BRL}}]})

(defn- partition-by-municipality
  "Group postings by the partner's municipality, returning
   `{municipality-id Money}`. Returns `{}` when no postings carry a
   municipality refinement. Currently a stub — see the note in
   `generate-iss-return` below — but the return-data carries an
   `:kontor.return/by-municipality` key so consumers can populate it from
   their own partner-municipality mapping at the next stage."
  [_postings]
  {})

(defn generate-iss-return
  "Compute the monthly ISS aggregation.

   ISS is collected per município; the substrate-tier number is the
   total — municipal split is a downstream concern (the consumer
   resolves partner / service-location → municipality before filing).

   The per-municipality breakdown (`:kontor.return/by-municipality`) is
   returned as an empty map at the substrate tier; a future
   extension can populate it via partner-municipality mapping
   (ADR-019-style account-tag refinement, or a per-line
   `:kontor.invoice-line/municipality` attribute that materialises as a
   posting tag).

   Required:
     :from / :to OR :year + :month

   Returns:
     {:kontor.return/form              \"ISS\"
      :kontor.return/period            window
      :kontor.return/lines             {:iss-output Money}
      :kontor.return/iss-total         Money — total ISS payable
      :kontor.return/by-municipality   {municipality-id Money} (substrate: empty)
      :report/lines             — drill-down}"
  [conn opts]
  (let [window (resolve-window opts)
        r (report/compute-report conn iss-definition (window->report-opts window))
        line (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                   (:report/lines r))
        zero (money/zero :BRL)
        total (or (get line "iss-output") zero)
        by-mun (partition-by-municipality
                (-> r :report/lines first :line/postings))]
    (-> r
        (assoc :kontor.return/form              "ISS"
               :kontor.return/period            window
               :kontor.return/lines             {:iss-output total}
               :kontor.return/iss-total         total
               :kontor.return/by-municipality   by-mun))))

;; ============================================================================
;; DCTFWeb — federal consolidation
;; ============================================================================

(defn generate-dctf-web
  "Compute the federal DCTFWeb consolidation for a monthly period.

   Per RFB IN 2.005/2021, DCTFWeb replaces the legacy DCTF as the
   unified monthly declaration of federal tax debits. This module
   produces the **PIS + COFINS + IPI** components (kernel-supported
   federal contributions on the indirect-tax side). INSS, IRPJ /
   CSLL withholdings, and other federal taxes are added via
   downstream extensions per the customer's specific portfolio.

   Required:
     :from / :to OR :year + :month

   Returns:
     {:kontor.return/form     \"DCTFWeb\"
      :kontor.return/period   window
      :kontor.return/components {:pis-cofins {…aggregated…}
                          :ipi        {…aggregated…}}
      :kontor.return/federal-total  Money — sum of PIS + COFINS + IPI net}"
  [conn opts]
  (let [window (resolve-window opts)
        pcv (generate-pis-cofins-return conn (merge opts window))
        ipi (generate-ipi-return        conn (merge opts window))
        federal-total (-> (money/zero :BRL)
                          (money/add (:kontor.return/total-net pcv))
                          (money/add (:kontor.return/ipi-net   ipi)))]
    {:kontor.return/form         "DCTFWeb"
     :kontor.return/period       window
     :kontor.return/components   {:pis-cofins pcv
                           :ipi        ipi}
     :kontor.return/federal-total federal-total}))
