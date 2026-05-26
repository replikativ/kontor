(ns kontor.payroll-fr.dsn
  "DSN (Déclaration Sociale Nominative) payload structure.

   ## Format posture

   DSN is the monthly French social-data payload that replaced ~20
   separate filings (DUCS-URSSAF, DUCS-Pôle emploi, DADS-U, DAT, etc.)
   in 2017. The format is **NEODES** — a tabular flat-file with a
   `BLOC,RUBRIQUE,'VALEUR'` per line structure, NOT XML. The
   official spec ('Cahier Technique de la Norme') is published by
   net-entreprises.fr; submission is via the same portal.

   Accessed-on URLs (ADR-079 — license posture):
     - https://www.net-entreprises.fr/declaration/dsn/
     - https://www.net-entreprises.fr/media/documentation/dsn-norme-2026.pdf
     - https://www.dsn-info.fr/
     - https://documentation-dsn.code.gouv.fr/

   ## Why we ship structure helpers, not a full emitter

   The DSN spec is ~3,000 pages across the Cahier Technique;
   producing a per-customer-correct DSN requires:
     - the URSSAF code organisme + DDID (déclaration d'identification)
     - the per-CCN (Convention Collective Nationale) rubrique
       extensions
     - the per-organisme-de-prévoyance / per-mutuelle CTP codes
     - the SIRET-level Stammdaten (workplace risk codes, INSEE NAF)
     - the engine's per-rubrique CCN routing decisions

   None of these are kontor's authority. The engine (Silae / Sage /
   Cegid / ADP) holds the per-customer rubrique config; kontor
   captures the **GL-relevant subset** for the audit-doc + lets the
   consumer's engine produce the full file. The audit-doc round-trips
   the DSN-relevant numbers so the audit chain has a row tying the
   pay-period to what was filed.

   ## Block subset kontor models

   - **S10.G00.00** — envelope (sender + receiver coordinates)
   - **S10.G00.01** — emetteur
   - **S20.G00.05** — individu (per-employee identifying block)
   - **S21.G00.06** — entreprise (SIREN + APE)
   - **S21.G00.11** — établissement (SIRET + workplace info)
   - **S21.G00.30** — individu (per-employment block)
   - **S21.G00.31** — versement individu (per-pay-period payment to
                     an individu — gross + net + commodity)
   - **S21.G00.40** — contrat (employment contract details)
   - **S21.G00.50** — versement (per-cotisation versement, aggregated
                     per organisme — URSSAF / ARRCO-AGIRC / Pôle emploi)
   - **S21.G00.51** — rémunération (per-pay-element gross)
   - **S21.G00.78** — autre élément de revenu (carry-only bases)
   - **S21.G00.81** — cotisation individuelle (per-employee per-CTP)

   kontor produces a **subset** sufficient for the audit-doc; the
   engine's NEODES file is the authoritative submission."
  (:require [clojure.string :as str])
  (:import [java.math BigDecimal]
           [java.time.format DateTimeFormatter]))

;; ============================================================================
;; Format helpers
;; ============================================================================

(defn- format-amount
  "DSN amounts are decimal numbers with a dot separator + 2 fractional
   digits. Negatives are allowed (used for reversal rubriques)."
  [^BigDecimal amount]
  (when amount
    (-> amount (.setScale 2 java.math.RoundingMode/HALF_EVEN) .toPlainString)))

(defn- format-date
  "DSN dates are DDMMYYYY (legacy format from DADS-U)."
  [^java.util.Date d]
  (when d
    (let [ld (-> d .toInstant
                 (.atZone java.time.ZoneOffset/UTC) .toLocalDate)]
      (.format ld (DateTimeFormatter/ofPattern "ddMMyyyy")))))

(defn- escape-value
  "DSN rubrique values are surrounded by single quotes. Embedded single
   quotes in payload data are doubled."
  [v]
  (when (some? v)
    (let [s (if (string? v) v (str v))]
      (-> s (str/replace "'" "''")))))

(defn rubrique-line
  "Format one DSN line: `S<bloc>,<rubrique>,'<valeur>'`.

   `bloc` is the dotted-path block code (`\"S10.G00.00\"`); `rubrique`
   the 3-digit rubrique identifier (`\"001\"`); `valeur` the raw value
   (string / number / Date — formatted appropriately)."
  [bloc rubrique valeur]
  (let [v (cond
            (instance? BigDecimal valeur) (format-amount valeur)
            (instance? java.util.Date valeur) (format-date valeur)
            :else (escape-value valeur))]
    (str bloc "," rubrique ",'" v "'")))

;; ============================================================================
;; Per-block builders
;; ============================================================================

(defn envelope-lines
  "DSN S10.G00.00 + S10.G00.01 envelope lines.

   Required opts:
     :siren           9-digit SIREN of the emetteur
     :nom-emetteur    legal name
     :adresse-emetteur address line
     :telephone-emetteur
     :email-emetteur
     :code-organisme  the URSSAF code-organisme (per the engine's
                      organisme-destinataire config)
     :date-creation   java.util.Date — when the file was generated
     :nature          :reel | :test (defaults :reel)
     :type-envoi      :normal | :neant (declaration néant)
     :numero-fraction usually \"01\" for the canonical monthly run

   Reference: net-entreprises.fr Cahier Technique §S10.G00.00."
  [{:keys [siren nom-emetteur adresse-emetteur telephone-emetteur
           email-emetteur code-organisme date-creation
           nature type-envoi numero-fraction
           numero-ordre]
    :or {nature :reel
         type-envoi :normal
         numero-fraction "01"
         numero-ordre "001"}}]
  [(rubrique-line "S10.G00.00" "001" siren)
   (rubrique-line "S10.G00.00" "002" nom-emetteur)
   (rubrique-line "S10.G00.00" "003" adresse-emetteur)
   (rubrique-line "S10.G00.00" "004" telephone-emetteur)
   (rubrique-line "S10.G00.00" "005" email-emetteur)
   (rubrique-line "S10.G00.00" "006" (case nature :reel "01" :test "02" "01"))
   (rubrique-line "S10.G00.00" "007" code-organisme)
   (rubrique-line "S10.G00.00" "008" date-creation)
   (rubrique-line "S10.G00.00" "009" numero-fraction)
   (rubrique-line "S10.G00.00" "010" numero-ordre)
   (rubrique-line "S10.G00.01" "001"
                  (case type-envoi :normal "01" :neant "02" "01"))])

(defn entreprise-lines
  "DSN S21.G00.06 entreprise block.

   Required opts:
     :siren           9-digit SIREN
     :ape             code APE / NAF (usually 4 digits + 1 letter)"
  [{:keys [siren ape]}]
  [(rubrique-line "S21.G00.06" "001" siren)
   (rubrique-line "S21.G00.06" "003" ape)])

(defn etablissement-lines
  "DSN S21.G00.11 établissement block.

   Required opts:
     :siret           14-digit SIRET
     :code-ape        NAF/APE code of the workplace
     :adresse         workplace address
     :code-postal
     :ville
     :pays           defaults \"FR\""
  [{:keys [siret code-ape adresse code-postal ville pays]
    :or {pays "FR"}}]
  (let [nic (when (and siret (>= (count siret) 14))
              (subs siret 9 14))]
    [(rubrique-line "S21.G00.11" "001" nic)
     (rubrique-line "S21.G00.11" "002" code-ape)
     (rubrique-line "S21.G00.11" "004" adresse)
     (rubrique-line "S21.G00.11" "005" code-postal)
     (rubrique-line "S21.G00.11" "006" ville)
     (rubrique-line "S21.G00.11" "007" pays)]))

(defn individu-lines
  "DSN S20.G00.05 (technical individu — submission-level) or S21.G00.30
   (per-employment individu) block.

   Required opts:
     :nir             NIR (numéro de sécurité sociale) — 15-digit
     :nom-de-famille
     :prenom
     :date-de-naissance java.util.Date
     :sexe            :h | :f
     :lieu-de-naissance INSEE code (5-digit) or string
     :adresse         line 1 of address
     :code-postal
     :ville
     :pays            defaults \"FR\""
  [{:keys [nir nom-de-famille prenom date-de-naissance sexe
           lieu-de-naissance adresse code-postal ville pays]
    :or {pays "FR"}}]
  [(rubrique-line "S21.G00.30" "001" nir)
   (rubrique-line "S21.G00.30" "002" nom-de-famille)
   (rubrique-line "S21.G00.30" "004" prenom)
   (rubrique-line "S21.G00.30" "005" date-de-naissance)
   (rubrique-line "S21.G00.30" "006" (case sexe :h "01" :f "02" "99"))
   (rubrique-line "S21.G00.30" "007" lieu-de-naissance)
   (rubrique-line "S21.G00.30" "008" adresse)
   (rubrique-line "S21.G00.30" "009" code-postal)
   (rubrique-line "S21.G00.30" "010" ville)
   (rubrique-line "S21.G00.30" "011" pays)])

(defn versement-individu-lines
  "DSN S21.G00.50 versement-individu block (the per-pay-period payment
   to an employee — gross, net, PAS-withholding).

   Required opts:
     :date-versement  java.util.Date
     :montant-net     BigDecimal — net wages paid
     :montant-pas     BigDecimal — PAS withheld (0M if none)
     :taux-pas        BigDecimal — applied PAS rate (e.g. 0.045M for
                                   4.5%); the engine resolves this
                                   from DGFiP CR-M response
     :type-pas        :bareme (standard rate) | :perso (personalized
                              rate) | :neutre (default rate)"
  [{:keys [date-versement montant-net montant-pas taux-pas
           type-pas]
    :or {type-pas :perso}}]
  [(rubrique-line "S21.G00.50" "001" date-versement)
   (rubrique-line "S21.G00.50" "002" montant-net)
   (rubrique-line "S21.G00.50" "004" montant-pas)
   (rubrique-line "S21.G00.50" "006" taux-pas)
   (rubrique-line "S21.G00.50" "008"
                  (case type-pas
                    :bareme "01" :perso "02" :neutre "03" "02"))])

(defn remuneration-lines
  "DSN S21.G00.51 rémunération block — one entry per pay-element kind.
   This is where SAL_BASE / 13e-mois / primes / overtime flow.

   Required opts:
     :type-rubrique   DSN code (e.g. \"001\" for base, \"002\" for
                       primes, \"017\" for overtime)
     :date-debut      java.util.Date (period start)
     :date-fin        java.util.Date (period end)
     :montant         BigDecimal"
  [{:keys [type-rubrique date-debut date-fin montant]}]
  [(rubrique-line "S21.G00.51" "001" date-debut)
   (rubrique-line "S21.G00.51" "002" date-fin)
   (rubrique-line "S21.G00.51" "010" type-rubrique)
   (rubrique-line "S21.G00.51" "013" montant)])

(defn cotisation-individuelle-lines
  "DSN S21.G00.81 cotisation individuelle — per-employee per-CTP
   (Code Type Personnel) entry. CTP is the URSSAF / ARRCO-AGIRC /
   Pôle emploi taxonomy.

   Required opts:
     :code-cotisation 3-digit CTP code (e.g. \"100\" for vieillesse
                       déplafonnée, \"260\" for CSG-CRDS imposable)
     :base            BigDecimal — assiette de cotisation
     :montant         BigDecimal — montant retenu"
  [{:keys [code-cotisation base montant]}]
  [(rubrique-line "S21.G00.81" "001" code-cotisation)
   (rubrique-line "S21.G00.81" "003" base)
   (rubrique-line "S21.G00.81" "004" montant)])

;; ============================================================================
;; Full DSN payload assembly
;; ============================================================================

(defn build-payload
  "Build the canonical kontor DSN payload — a vector of NEODES rubrique
   lines. This is the **structural subset** of a full DSN; the engine
   produces the authoritative submission.

   The shape kontor emits is sufficient for:
     - audit-doc round-trip (NEODES lines persisted in
       `:kontor.audit-doc/description` as a single string),
     - audit-trail reconstruction (which (employee × pay-element ×
       montant) flowed into which DSN month),
     - regulator-spec compliance for the structural blocks we DO
       emit (S10 envelope + S21.G00.51 rémunérations + S21.G00.81
       cotisations).

   Required opts:
     :envelope            map for `envelope-lines`
     :entreprise          map for `entreprise-lines`
     :etablissement       map for `etablissement-lines`
     :individus           seq of {:individu :versement-individu
                                  :remunerations :cotisations}
                          where each value is a map (for the *-lines fn)
                          or a seq-of-maps (for many-cardinality).

   Returns a vector of formatted NEODES lines (strings, no
   newlines)."
  [{:keys [envelope entreprise etablissement individus]}]
  (vec
   (concat
    (envelope-lines envelope)
    (entreprise-lines entreprise)
    (etablissement-lines etablissement)
    (mapcat (fn [{:keys [individu versement-individu remunerations cotisations]}]
              (concat
               (individu-lines individu)
               (when versement-individu
                 (versement-individu-lines versement-individu))
               (mapcat remuneration-lines (or remunerations []))
               (mapcat cotisation-individuelle-lines (or cotisations []))))
            individus))))

(defn serialize
  "Serialize a payload (vector of NEODES lines) to a String. Uses
   CRLF line endings per the net-entreprises.fr spec."
  [lines]
  (str (str/join "\r\n" lines) "\r\n"))

;; ============================================================================
;; Convenience: facts → payload (the load-bearing emit-side fn)
;; ============================================================================

(def ^:private kind->type-rubrique
  "Map kontor :component-kind → DSN S21.G00.51 type rubrique (the
   3-digit code that classifies the gross element).

   Per net-entreprises.fr Cahier Technique annex 'Types de
   rémunération': 001 = rémunération brute non-plafonnée; 002 = primes;
   003 = indemnités congés payés; 017 = heures supplémentaires;
   013 = participation; 014 = intéressement; 015 = PEE."
  {:base-salary             "001"
   :overtime                "017"
   :13e-mois                "002"
   :prime-de-fin-d-annee    "002"
   :prime-exceptionnelle    "002"
   :indemnite-conges-payes  "003"
   :participation           "013"
   :interessement           "014"
   :plan-epargne-entreprise "015"
   :tickets-restaurant      "010"
   :avantage-nature-vehicule "010"
   :avantage-nature-logement "010"})

(def ^:private kind->ctp
  "Map kontor :component-kind → DSN S21.G00.81 CTP (Code Type
   Personnel). CTP codes are URSSAF-issued; the subset below is
   illustrative — the full list runs to several hundred entries by
   organisme (URSSAF / ARRCO / AGIRC / Pôle emploi)."
  {:cotisation-urssaf       "100"  ; vieillesse déplafonnée
   :csg-deductible          "260"
   :csg-non-deductible      "262"
   :crds                    "263"
   :cotisation-arrco-agirc  "071"
   :cotisation-pole-emploi  "772"
   :cotisation-prevoyance   "330"
   :medical-mutuelle        "335"})

(defn facts->payload
  "Convert a vector of `PayrollFacts` + envelope/entreprise/établissement
   metadata into a full DSN payload (vector of NEODES lines).

   Required opts:
     :facts            PayrollFacts vector
     :envelope         map for `envelope-lines`
     :entreprise       map for `entreprise-lines`
     :etablissement    map for `etablissement-lines`
     :persons-by-emp   fn (employment-eid → person-map). Each person
                       carries :nir :nom-de-famille :prenom
                       :date-de-naissance :sexe :lieu-de-naissance
                       :adresse :code-postal :ville
     :pay-period-start java.util.Date
     :pay-period-end   java.util.Date
     :date-versement   java.util.Date — when net is actually paid

   Optional:
     :type-pas         :bareme | :perso | :neutre — defaults :perso
                       (engine-supplied via :jurisdiction-specific-codes
                       per fact)"
  [{:keys [facts envelope entreprise etablissement persons-by-emp
           pay-period-start pay-period-end date-versement type-pas]
    :or {type-pas :perso}}]
  (let [individus
        (mapv
         (fn [{:keys [employment net components jurisdiction-specific-codes]}]
           (let [person (persons-by-emp employment)
                 _ (when-not person
                     (throw (ex-info "facts->payload: missing person for employment"
                                     {:employment employment})))
                 pas-comp (some #(when (= :pas-withholding (:kind %)) %) components)
                 montant-pas (some-> pas-comp :amount .abs)
                 taux-pas (or (:taux-pas jurisdiction-specific-codes) 0M)
                 remunerations
                 (->> components
                      (remove :employer-side?)
                      (keep (fn [{:keys [kind amount]}]
                              (when-let [tr (kind->type-rubrique kind)]
                                {:type-rubrique tr
                                 :date-debut pay-period-start
                                 :date-fin pay-period-end
                                 :montant amount}))))
                 cotisations
                 (->> components
                      (remove :employer-side?)
                      (keep (fn [{:keys [kind amount]}]
                              (when-let [ctp (kind->ctp kind)]
                                {:code-cotisation ctp
                                 :base (or (:base-soumise-urssaf
                                            jurisdiction-specific-codes)
                                           0M)
                                 :montant (.abs ^BigDecimal amount)}))))]
             {:individu person
              :versement-individu
              {:date-versement date-versement
               :montant-net net
               :montant-pas (or montant-pas 0M)
               :taux-pas taux-pas
               :type-pas type-pas}
              :remunerations remunerations
              :cotisations cotisations}))
         facts)]
    (build-payload {:envelope envelope
                    :entreprise entreprise
                    :etablissement etablissement
                    :individus individus})))
