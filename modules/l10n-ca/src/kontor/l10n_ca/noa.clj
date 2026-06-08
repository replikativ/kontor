(ns kontor.l10n-ca.noa
  "CRA Notice of Assessment (NoA) — ingestion.

   CRA issues a Notice of Assessment after assessing a filed return.
   The PDF NoA contains a 'Carryforward amounts' section summarizing
   balances the filer can claim against future returns. This namespace
   parses (or accepts hand-typed) NoA data and surfaces it as kernel
   carryforward facts for the *next* tax year.

   Carryforward fact schema (per ADR-015 — namespaced under
   `:carryforward/*`):

     :carryforward/tax-year             Long       ; year being summarized
     :carryforward/assessment-date      java.time.LocalDate
     :carryforward/rrsp-deduction-room  Money :CAD
     :carryforward/tfsa-room            Money :CAD
     :carryforward/fhsa-room            Money :CAD
     :carryforward/hbp-balance          Money :CAD
     :carryforward/llp-balance          Money :CAD
     :carryforward/capital-loss-balance Money :CAD
     :carryforward/non-capital-loss     Money :CAD
     :carryforward/tuition-unused-fed   Money :CAD
     :carryforward/tuition-unused-prov  Money :CAD
     :carryforward/donation-carryforward Money :CAD ; 5-year window
     :carryforward/itc-balance          Money :CAD ; investment tax credit
     :carryforward/cca-balances         {<class-num Long> Money :CAD}

   Three ingestion paths, in order of practical priority:

     1. **Manual map** — user hand-types numbers from their PDF NoA into
        a map matching the schema above. Today's default path; the
        kontor side never sees the PDF.
     2. **PDF text-extraction** — PDFBox or similar extracts the
        carryforward table from the NoA PDF; we pattern-match the
        labels. Requires real NoA samples to calibrate; the
        `parse-pdf` stub here is the integration point.
     3. **CRA AFR / Represent a Client API** — programmatic pull.
        Cert-gated; out of scope for the non-cert path (ADR-015).

   For TY2024 filing, the user will most commonly use path 1 (manual)."
  (:require [kontor.money :as money]))

(def carryforward-keys
  "Documented set of carryforward fact keys this module understands.
   Used to validate input maps; unknown keys are surfaced as warnings."
  #{:carryforward/tax-year
    :carryforward/assessment-date
    :carryforward/rrsp-deduction-room
    :carryforward/tfsa-room
    :carryforward/fhsa-room
    :carryforward/hbp-balance
    :carryforward/llp-balance
    :carryforward/capital-loss-balance
    :carryforward/non-capital-loss
    :carryforward/tuition-unused-fed
    :carryforward/tuition-unused-prov
    :carryforward/donation-carryforward
    :carryforward/itc-balance
    :carryforward/cca-balances})

(defn from-manual-map
  "Accept a hand-typed map and normalize it into a carryforward-facts
   structure with defaults for missing keys. Returns:

     {:carryforward/facts <validated-map>
      :carryforward/source :manual
      :carryforward/unknown-keys <set of unrecognized keys>}"
  [m]
  (let [unknown (->> (keys m)
                     (filter #(and (qualified-keyword? %)
                                   (= "carryforward" (namespace %))
                                   (not (carryforward-keys %))))
                     set)]
    {:carryforward/facts        m
     :carryforward/source       :manual
     :carryforward/unknown-keys unknown}))

(defprotocol NoaParser
  "Pluggable parser for NoA documents. The default (`text-parser-stub`)
   is unimplemented and exists so calling code can be written before
   real CRA samples are available."
  (parse-pdf [this path]
    "Parse a CRA NoA PDF at `path` into a carryforward-facts structure
     (same shape as `from-manual-map` returns). Implementations should
     return the structure or throw `ex-info` with `:type :parse-failed`."))

(defrecord TextParserStub []
  NoaParser
  (parse-pdf [_ path]
    (throw (ex-info
            "NoA PDF parsing is not yet implemented for production NoAs.
             Use `from-manual-map` to hand-type the carryforward numbers,
             or supply a real-NoA-trained parser implementation."
            {:type :parse-failed
             :path (str path)
             :hint  "Sample NoAs needed to calibrate the parser."}))))

(defn ->t1-inputs
  "Project carryforward facts onto T1 inputs for the *following* tax
   year. Returns a partial T1 input map that the caller merges with
   the rest of their inputs.

     :rrsp-deduction-limit     ← :carryforward/rrsp-deduction-room
       (advisory ceiling on the :rrsp-deduction input)

     :prior-capital-loss        ← :carryforward/capital-loss-balance
       (can offset taxable capital gains)

   The other facts (TFSA/FHSA/HBP/LLP, tuition unused, CCA balances)
   feed downstream forms not yet integrated; we expose them on the
   returned map for callers to use directly."
  [{:carryforward/keys [facts]}]
  (merge
   (when-let [v (:carryforward/rrsp-deduction-room facts)]
     {:rrsp-deduction-limit v})
   (when-let [v (:carryforward/capital-loss-balance facts)]
     {:prior-capital-loss v})
   {:carryforward/facts facts}))
