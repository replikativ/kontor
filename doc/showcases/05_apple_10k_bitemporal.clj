^{:kindly/hide-code true
  :clay {:title "Showcase 5: Apple 10-K bitemporal restatement"
         :format [:quarto :html]}}
(ns showcases.05-apple-10k-bitemporal
  "Multi-national use case 5: Apple Inc.'s 2009 10-K bitemporal
   restatement, ingested from real SEC EDGAR data.

   The headline story: Apple filed a 10-K for fiscal year 2009 on
   October 27, 2009, reporting (among many other facts):

     - AccruedLiabilitiesCurrent FY2008 end:  $3,719,000,000
     - AccruedLiabilitiesCurrent FY2009 end:  $3,376,000,000
     - AccumulatedOtherComprehensiveIncomeLoss FY2008: + $8,000,000

   Three months later, on January 25, 2010, Apple filed a 10-K/A
   amendment retroactively adopting ASC 605-25 (the revenue
   recognition guidance for arrangements with multiple deliverables).
   The amendment restated 1,668 concept-values across the company's
   reported history, including:

     - AccruedLiabilitiesCurrent FY2008 end:  $4,224,000,000  (+$505M, +13.6%)
     - AccruedLiabilitiesCurrent FY2009 end:  $3,852,000,000  (+$476M, +14.1%)
     - AccumulatedOtherComprehensiveIncomeLoss FY2008: - $9,000,000  (sign flip)

   The substrate question: how do you maintain BOTH views — what
   Apple reported in October 2009 AND what they restated in January
   2010 — so an analyst asking 'what did Apple's books look like on
   2009-12-01?' gets the original numbers, while an analyst asking
   'what does Apple now report for that period?' gets the amended
   numbers?

   This is exactly what kontor's bitemporal substrate is for. The
   `kontor.import-edgar` companion ingests SEC `companyfacts` JSON,
   stamps each fact with `:tx/valid-from = SEC :filed date`, and
   closes the prior fact's valid-time window when an amendment
   supersedes it. `(d/valid-at db t)` returns the right fact for
   any timeline point.

   Data: a curated subset of Apple's real SEC EDGAR `companyfacts`
   JSON, bundled at `resources/kontor/import_edgar/fixtures/`. The
   full dataset is downloadable directly from `data.sec.gov` (public
   domain, 17 U.S.C. § 105) with a User-Agent header per SEC API
   policy.

   Substrate exercised:

     - `kontor.import-edgar.schema`     `:kontor.reported-fact/*` external
                                        regulator-attested facts
     - `kontor.import-edgar.core`       JSON parsing + bitemporal
                                        ingest with supersession
     - `kontor.bitemporal/with-vt`      ADR-008 valid-time stamping
     - `kontor.bitemporal/close-validity!`  supersession
     - `d/valid-at`                     datahike's bitemporal query

   Sources cited:

     - SEC EDGAR `companyfacts` API — https://www.sec.gov/edgar/sec-api-documentation
     - Apple 10-K filed 2009-10-27 (accession 0001193125-09-214859)
     - Apple 10-K/A filed 2010-01-25 (accession 0001193125-10-012091)
     - ASC 605-25 Revenue Recognition: Arrangements with Multiple Deliverables"
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.import-edgar.core :as edgar]
            [kontor.import-edgar.schema :as edgar-schema]
            [scicloj.kindly.v4.kind :as kind]
            [scicloj.kindly.v4.api :as kindly]))

;; # Showcase 5: Apple 10-K bitemporal restatement
;;
;; Real public-data demonstration of kontor's bitemporal substrate —
;; specifically, the "what we believed then vs what we know now"
;; archetype on Apple's 2009 10-K → 10-K/A restatement chain.

;; ## Setup
;;
;; Install kontor + the kontor-import-edgar companion's schema.
;; Create a single `:entity` row for Apple to bind the ingested
;; facts to.

(def conn (core/create-test-db))
(edgar-schema/install! conn)

(d/transact conn
            [{:kontor.entity/code "APPLE"
              :kontor.entity/name "Apple Inc."
              :kontor.entity/active true
              :kontor.entity/lei  "HWUPKR0MPOU8FGXBT394"
              :kontor.entity/source-id "showcase-05-fixture"}])

(def apple-eid
  (d/q '[:find ?e . :where [?e :kontor.entity/code "APPLE"]] (d/db conn)))

apple-eid
;; => an eid (your number will differ; the schema uses sequential IDs)

;; ## Ingest the 2009 10-K (filed 2009-10-27)
;;
;; We start by ingesting ONLY the original 10-K — facts filed before
;; the 10-K/A amendment. Parse the curated JSON fixture; filter to
;; the original-10-K-only facts; pass them through the ingest.

(def fixture-json
  (slurp (io/resource
          "kontor/import_edgar/fixtures/apple-companyfacts-2008-2012.json")))

(def all-facts (vec (edgar/parse-companyfacts fixture-json)))

;; How many facts for the AccruedLiabilities concept does the fixture
;; carry, and across how many filings?

(->> all-facts
     (filter #(= "us-gaap:AccruedLiabilitiesCurrent" (:concept-iri %)))
     (group-by :form)
     (map (fn [[form facts]] [form (count facts)]))
     (into (sorted-map)))
;; => {"10-K" 11, "10-K/A" 5, "10-Q" 27}
;; The 10-K/A is the restatement.

;; Ingest ONLY the original 10-K filings first (skip 10-K/A + 10-Qs)
;; so we can demonstrate the pre-amendment state.

(def original-10k-facts
  (->> all-facts
       (filter #(= "10-K" (:form %)))
       (filter #(< (compare (:filed %) "2010-01-25") 0))))

(def original-ingest
  (edgar/ingest-facts! conn original-10k-facts
                       {:entity-eid apple-eid
                        :source "edgar://companyfacts/CIK0000320193/2009-10-27"}))

(select-keys original-ingest [:ingested :superseded :skipped])
;; => {:ingested N :superseded 0 :skipped 0}

;; ## The pre-amendment view
;;
;; What does kontor report for Apple's FY2008 end-of-period
;; AccruedLiabilities, as of December 2009 (before the amendment)?

(def fy2008-end #inst "2008-09-27")

(def pre-amendment-view
  (edgar/current-fact conn apple-eid
                      "us-gaap:AccruedLiabilitiesCurrent"
                      fy2008-end :usd
                      #inst "2009-12-01"))

(select-keys pre-amendment-view
             [:kontor.reported-fact/value-bigdec
              :kontor.reported-fact/form
              :kontor.reported-fact/filed
              :kontor.reported-fact/accession-number])
;; => {:kontor.reported-fact/value-bigdec 3719000000M
;;     :kontor.reported-fact/form "10-K"
;;     :kontor.reported-fact/filed #inst "2009-10-27"
;;     :kontor.reported-fact/accession-number "0001193125-09-214859"}

;; ## Apple files the 10-K/A on 2010-01-25
;;
;; The amendment restates Apple's revenue-recognition treatment under
;; ASC 605-25 and updates a slew of balance-sheet figures. We ingest
;; the amendment facts.

(def amendment-facts
  (->> all-facts
       (filter #(= "10-K/A" (:form %)))
       (filter #(= "2010-01-25" (:filed %)))))

(def amendment-ingest
  (edgar/ingest-facts! conn amendment-facts
                       {:entity-eid apple-eid
                        :source "edgar://companyfacts/CIK0000320193/2010-01-25"}))

(select-keys amendment-ingest [:ingested :superseded :skipped])
;; => {:ingested N :superseded N :skipped 0}
;; Every amendment fact that matched an existing original-10-K fact
;; for the same (concept, period-end, unit) records a supersession.

;; ## The post-amendment view (after February 2010)

(def post-amendment-view
  (edgar/current-fact conn apple-eid
                      "us-gaap:AccruedLiabilitiesCurrent"
                      fy2008-end :usd
                      #inst "2010-02-01"))

(select-keys post-amendment-view
             [:kontor.reported-fact/value-bigdec
              :kontor.reported-fact/form
              :kontor.reported-fact/filed
              :kontor.reported-fact/accession-number])
;; => {:kontor.reported-fact/value-bigdec 4224000000M
;;     :kontor.reported-fact/form "10-K/A"
;;     :kontor.reported-fact/filed #inst "2010-01-25"
;;     :kontor.reported-fact/accession-number "0001193125-10-012091"}
;; The amended value: +$505M vs. the original 10-K.

;; ## The full supersession history

(def fy2008-history
  (edgar/fact-history conn apple-eid
                      "us-gaap:AccruedLiabilitiesCurrent"
                      fy2008-end :usd))

(map (fn [f]
       {:filed (:kontor.reported-fact/filed f)
        :form  (:kontor.reported-fact/form f)
        :value (:kontor.reported-fact/value-bigdec f)
        :superseded? (some? (:kontor.reported-fact/superseded-by f))})
     fy2008-history)
;; =>
;; ({:filed #inst "2009-10-27" :form "10-K"   :value 3719000000M :superseded? true}
;;  {:filed #inst "2010-01-25" :form "10-K/A" :value 4224000000M :superseded? false})

;; ## The sign-flip case — AccumulatedOtherComprehensiveIncomeLoss FY2008
;;
;; The most dramatic restatement: Apple's AccumulatedOCI for FY2008
;; flipped from +$8M to -$9M (sign reversal). The bitemporal view
;; tracks this correctly.

(def oci-pre
  (edgar/current-fact conn apple-eid
                      "us-gaap:AccumulatedOtherComprehensiveIncomeLossNetOfTax"
                      fy2008-end :usd
                      #inst "2009-12-01"))

(def oci-post
  (edgar/current-fact conn apple-eid
                      "us-gaap:AccumulatedOtherComprehensiveIncomeLossNetOfTax"
                      fy2008-end :usd
                      #inst "2010-02-01"))

[(when oci-pre  {:date "2009-12-01" :form (:kontor.reported-fact/form oci-pre)
                 :value (:kontor.reported-fact/value-bigdec oci-pre)})
 (when oci-post {:date "2010-02-01" :form (:kontor.reported-fact/form oci-post)
                 :value (:kontor.reported-fact/value-bigdec oci-post)})]
;; =>
;; [{:date "2009-12-01" :form "10-K"   :value 8000000M}
;;  {:date "2010-02-01" :form "10-K/A" :value -9000000M}]
;; Sign-flip restatement preserved end-to-end.

;; ## Bitemporal substrate verification via `d/valid-at`
;;
;; The supersession chain is observable via `:kontor.reported-fact/superseded-by`
;; refs, but the deeper substrate guarantee is that `d/valid-at`
;; returns the AUTHORITATIVE fact for the chosen reporting timestamp.
;; The original fact's `:tx/valid-from` window is closed at the
;; amendment's `:filed` date — so at a pre-amendment `valid-at`,
;; only the original is visible; at a post-amendment `valid-at`,
;; only the amendment is visible.

(defn fact-at [^java.util.Date valid-time]
  (let [db (d/valid-at (d/db conn) valid-time)]
    (->> (d/q '[:find [?f ...]
                :in $ ?e ?c ?p ?u
                :where
                [?f :kontor.reported-fact/entity ?e]
                [?f :kontor.reported-fact/concept-iri ?c]
                [?f :kontor.reported-fact/period-end ?p]
                [?f :kontor.reported-fact/unit ?u]]
              db apple-eid "us-gaap:AccruedLiabilitiesCurrent"
              fy2008-end :usd)
         (mapv #(d/pull db '[:kontor.reported-fact/value-bigdec
                             :kontor.reported-fact/form
                             :kontor.reported-fact/filed] %)))))

(fact-at #inst "2009-12-01")
;; => [{:kontor.reported-fact/value-bigdec 3719000000M
;;       :kontor.reported-fact/form "10-K"
;;       :kontor.reported-fact/filed #inst "2009-10-27"}]

(fact-at #inst "2010-02-01")
;; => [{:kontor.reported-fact/value-bigdec 4224000000M
;;       :kontor.reported-fact/form "10-K/A"
;;       :kontor.reported-fact/filed #inst "2010-01-25"}]

;; ## What this demonstrates
;;
;; - **Real public data**, not synthetic. Apple's actual EDGAR JSON
;;   feed; the amendment is real; the values restated are the actual
;;   ASC 605-25 adoption deltas.
;; - **Bitemporal substrate** working end-to-end. The substrate
;;   records WHEN each fact became authoritative (the SEC `:filed`
;;   date) and exposes "what was known at time T" via `d/valid-at`.
;; - **Supersession chain** queryable structurally
;;   (`:kontor.reported-fact/superseded-by`) AND bitemporally
;;   (`d/valid-at`).
;; - **No XBRL parser required.** The SEC JSON `companyfacts` API
;;   ships pre-parsed XBRL facts in JSON form. A future
;;   `kontor-xbrl` companion would add the XML/iXBRL ingest path
;;   for FDTA municipal filings, Companies House,
;;   ESEF, E-Bilanz. The substrate this showcase lands accepts both
;;   ingest paths identically — the JSON ingest in
;;   `kontor.import-edgar.core` is the reference implementation.
;;
;; ## What this does NOT demonstrate (out of scope)
;;
;; - **Full balance sheet / GuV / cash-flow reconstruction.** The
;;   showcase ingests selected line items; reconstructing the full
;;   statement layout via the XBRL calculation linkbase is a future
;;   `kontor-xbrl` companion deliverable.
;; - **Taxonomy mapping to kontor's chart of accounts.** The
;;   `:kontor.account/concept-iri` substrate seam (ADR-090) is the bridge;
;;   a consumer wanting to project EDGAR facts onto kontor accounts
;;   would add the mapping in a thin bridge layer.
;; - **Cross-company consolidation.** ADR-073's `kontor.entity/family`
;;   walks LEI relationships from `kontor-import-gleif`; the EDGAR
;;   ingest pairs with that companion but doesn't replicate the
;;   consolidation primitive.
;;
;; ## License + data posture
;;
;; - SEC EDGAR data is **public domain** under 17 U.S.C. § 105.
;; - Bundled fixture: a 3,300-line curated subset of Apple's
;;   companyfacts JSON covering 2008-2012, sufficient to demonstrate
;;   the 2009 10-K/A restatement. The full ~50MB Apple history is
;;   downloadable directly from `data.sec.gov`.
;; - SEC requires a `User-Agent` header on API requests; consumers
;;   downloading at ingest time set
;;   `SEC_EDGAR_USER_AGENT` per the consumer-side ergonomic pattern.
;; - Per ADR-005: no bundled SEC API keys (none required); no rate-
;;   limited data redistribution.
