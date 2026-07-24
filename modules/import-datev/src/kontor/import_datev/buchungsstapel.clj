(ns kontor.import-datev.buchungsstapel
  "DATEV **Buchungsstapel** (Datenkategorie 21) — GL journal import + export
   over the shared EXTF codec (`kontor.import-datev.extf`).

   A Buchungsstapel row is DATEV's two-line booking view: one `Umsatz` with
   a `Soll/Haben-Kennzeichen`, a `Konto`, and a `Gegenkonto`. `S` means the
   amount is debited to Konto (and credited to Gegenkonto); `H` is the
   reverse. So each row is a complete balanced pair — which is what makes a
   clean export→import round-trip possible for two-leg entries.

   Export reads `:kontor.posting/*` from a datahike conn and projects each
   posted transaction to rows. Import parses an EXTF file back into neutral
   `booking` maps (and, optionally, into balanced transaction tx-data via
   `booking->tx-data`).

   v1 scope (note 195, items 1-2): the round-trip keystone for two-leg
   entries + the spec-correct header. Deferred to follow-ups: BU-Schlüssel
   tax keys (G6), lossless >2-leg contra split (G9), cat-16/20 master data
   (G4/G5). A transaction with more than two legs still EXPORTS (one row per
   non-contra leg against the largest-magnitude contra, as before) but does
   not round-trip leg-for-leg — `export` logs nothing yet; callers needing
   fidelity should keep entries two-legged until G9 lands."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.import-datev.extf :as extf])
  (:import [java.util Date]
           [java.math BigDecimal]))

;; ============================================================================
;; The 122-column Buchungsstapel column-label line (schema 510/700)
;; ============================================================================
;; Every data row must present the same column count as this line. We ship
;; 120 of the 122 official columns — the two omitted are the group-account
;; "Stammkonto-Nr."/"Verkaufsrolle" pair outside SMB scope; real importers
;; tolerate the shorter list. The first 14 are load-bearing.

(def columns
  ["Umsatz (ohne Soll/Haben-Kz)" "Soll/Haben-Kennzeichen" "WKZ Umsatz"
   "Kurs" "Basis-Umsatz" "WKZ Basis-Umsatz" "Konto"
   "Gegenkonto (ohne BU-Schlüssel)" "BU-Schlüssel" "Belegdatum"
   "Belegfeld 1" "Belegfeld 2" "Skonto" "Buchungstext" "Postensperre"
   "Diverse Adressnummer" "Geschäftspartnerbank" "Sachverhalt"
   "Zinssperre" "Beleglink" "Beleginfo - Art 1" "Beleginfo - Inhalt 1"
   "Beleginfo - Art 2" "Beleginfo - Inhalt 2" "Beleginfo - Art 3"
   "Beleginfo - Inhalt 3" "Beleginfo - Art 4" "Beleginfo - Inhalt 4"
   "Beleginfo - Art 5" "Beleginfo - Inhalt 5" "Beleginfo - Art 6"
   "Beleginfo - Inhalt 6" "Beleginfo - Art 7" "Beleginfo - Inhalt 7"
   "Beleginfo - Art 8" "Beleginfo - Inhalt 8" "KOST1 - Kostenstelle"
   "KOST2 - Kostenstelle" "KOST - Menge" "EU-Land u. UStID"
   "EU-Steuersatz" "Abw. Versteuerungsart" "Sachverhalt L+L"
   "Funktionsergänzung L+L" "BU 49 Hauptfunktionstyp"
   "BU 49 Hauptfunktionsnummer" "BU 49 Funktionsergänzung"
   "Zusatzinformation - Art 1" "Zusatzinformation - Inhalt 1"
   "Zusatzinformation - Art 2" "Zusatzinformation - Inhalt 2"
   "Zusatzinformation - Art 3" "Zusatzinformation - Inhalt 3"
   "Zusatzinformation - Art 4" "Zusatzinformation - Inhalt 4"
   "Zusatzinformation - Art 5" "Zusatzinformation - Inhalt 5"
   "Zusatzinformation - Art 6" "Zusatzinformation - Inhalt 6"
   "Zusatzinformation - Art 7" "Zusatzinformation - Inhalt 7"
   "Zusatzinformation - Art 8" "Zusatzinformation - Inhalt 8"
   "Zusatzinformation - Art 9" "Zusatzinformation - Inhalt 9"
   "Zusatzinformation - Art 10" "Zusatzinformation - Inhalt 10"
   "Zusatzinformation - Art 11" "Zusatzinformation - Inhalt 11"
   "Zusatzinformation - Art 12" "Zusatzinformation - Inhalt 12"
   "Zusatzinformation - Art 13" "Zusatzinformation - Inhalt 13"
   "Zusatzinformation - Art 14" "Zusatzinformation - Inhalt 14"
   "Zusatzinformation - Art 15" "Zusatzinformation - Inhalt 15"
   "Zusatzinformation - Art 16" "Zusatzinformation - Inhalt 16"
   "Zusatzinformation - Art 17" "Zusatzinformation - Inhalt 17"
   "Zusatzinformation - Art 18" "Zusatzinformation - Inhalt 18"
   "Zusatzinformation - Art 19" "Zusatzinformation - Inhalt 19"
   "Zusatzinformation - Art 20" "Zusatzinformation - Inhalt 20"
   "Stück" "Gewicht" "Zahlweise" "Forderungsart" "Veranlagungsjahr"
   "Zugeordnete Fälligkeit" "Skontotyp" "Auftragsnummer"
   "Buchungstyp" "USt-Schlüssel (Anzahlungen)"
   "EU-Land (Anzahlungen)" "Sachverhalt L+L (Anzahlungen)"
   "EU-Steuersatz (Anzahlungen)" "Erlöskonto (Anzahlungen)"
   "Herkunft-Kz" "Buchungs GUID" "KOST-Datum" "SEPA-Mandatsreferenz"
   "Skontosperre" "Gesellschaftername" "Beteiligtennummer"
   "Identifikationsnummer" "Zeichnernummer" "Postensperre bis"
   "Bezeichnung SoBil-Sachverhalt" "Kennzeichen SoBil-Buchung"
   "Festschreibung" "Leistungsdatum" "Datum Zuord. Steuerperiode"
   "Fälligkeit" "Generalumkehr (GU)" "Steuersatz" "Land"])

(def ^:const column-count (count columns))

;; column indices (0-based) of the fields import reads back
(def ^:private col-idx
  {:umsatz 0 :soll-haben 1 :wkz 2 :konto 6 :gegenkonto 7 :bu-schluessel 8
   :belegdatum 9 :belegfeld-1 10 :belegfeld-2 11 :buchungstext 13})

;; ============================================================================
;; Export: postings → EXTF rows
;; ============================================================================

(defn- posting->cells
  "One booking → a `column-count`-wide EXTF cell vector."
  [{:keys [amount account-code contra-code text date belegfeld-1]}]
  (let [base {0 (extf/format-amount amount)
              1 (if (pos? (.signum ^BigDecimal amount)) "S" "H")
              2 "EUR"
              6 account-code
              7 contra-code
              9 (extf/format-belegdatum date)
              10 (or belegfeld-1 "")
              13 (or text "")}]
    (mapv (fn [i] (get base i "")) (range column-count))))

(defn- fetch-export-rows
  "Pull posted postings whose valid-from is in [from, to), grouped by
   transaction, each non-contra leg paired against the largest-magnitude
   contra leg (DATEV two-line style). Returns booking maps."
  [db {:keys [from to]}]
  (let [posting-ids (d/q '[:find [?p ...]
                           :where [?p :kontor.posting/account _]
                           [?p :kontor.posting/transaction ?t]
                           [?t :kontor.transaction/state :posted]]
                         db)
        pulled (mapv
                (fn [p]
                  (let [pe (d/pull db
                                   [:kontor.posting/amount
                                    {:kontor.posting/account [:kontor.account/code]}
                                    {:kontor.posting/transaction
                                     [:db/id :kontor.transaction/narration]}]
                                   p)
                        tx (:kontor.posting/transaction pe)
                        vf (d/q '[:find ?vf .
                                  :in $ ?p
                                  :where
                                  [?p :kontor.posting/transaction _ ?tx]
                                  [?tx :db/txInstant ?ti]
                                  [(get-else $ ?tx :db.valid/from ?ti) ?vf]]
                                db p)]
                    {:amount (:kontor.posting/amount pe)
                     :valid-from vf
                     :account-code (-> pe :kontor.posting/account :kontor.account/code)
                     :tx-eid (:db/id tx)
                     :tx-text (:kontor.transaction/narration tx)}))
                posting-ids)
        in-window (filter (fn [{:keys [valid-from]}]
                            (and valid-from
                                 (or (nil? from) (>= (.compareTo ^Date valid-from ^Date from) 0))
                                 (or (nil? to) (< (.compareTo ^Date valid-from ^Date to) 0))))
                          pulled)
        rows (mapcat
              (fn [[_ ps]]
                ;; The Gegenkonto is the largest-magnitude leg. On a balanced
                ;; TWO-leg entry both legs tie on magnitude, and `posting-ids`
                ;; comes from an unordered `d/q` set — so picking `first` after
                ;; a magnitude-only sort chose the Konto/Gegenkonto pair
                ;; ARBITRARILY (it flipped whenever entity ids shifted, e.g. on
                ;; a schema addition). Tie-break deterministically by amount
                ;; ascending (most-negative first) so the CREDIT leg becomes the
                ;; Gegenkonto and the DEBIT leg the Konto with `S` — the DATEV
                ;; convention — then by account-code for full determinism.
                ;; note 198.
                (let [contra (->> ps
                                  (sort-by (juxt #(.negate ^BigDecimal
                                                   (.abs ^BigDecimal (:amount %)))
                                                 :amount
                                                 :account-code))
                                  first)
                      contra-code (:account-code contra)]
                  (->> ps
                       (remove #(= contra %))
                       (sort-by :account-code)
                       (map (fn [p]
                              {:amount (:amount p)
                               :account-code (:account-code p)
                               :contra-code contra-code
                               :date (:valid-from p)
                               :text (:tx-text p)})))))
              (group-by :tx-eid in-window))]
    (sort-by #(.getTime ^Date (:date %)) rows)))

(defn export-buchungsstapel
  "Generate an EXTF Buchungsstapel string from the posted postings in
   `conn` whose valid-from is in [from, to).

   Required opts: `:from` `:to` (Date, `:to` exclusive), `:year` (fiscal
   year), `:company-name`, `:berater-nr` (DATEV Berater/consultant no.).
   Optional: `:mandant-nr` (default \"1\"), `:sachkontenlaenge` (default 4),
   `:versionsnummer` (default 510), `:as-of-tx`, `:timestamp`
   (LocalDateTime, for deterministic test output).

   The header is spec-correct — field 5 is the Formatversion (derived from
   the Versionsnummer), not the line count."
  [conn {:keys [from to year company-name berater-nr mandant-nr sachkontenlaenge
                versionsnummer as-of-tx timestamp]
         :or {mandant-nr "1" sachkontenlaenge 4 versionsnummer 510}}]
  (let [db     (-> conn d/db (cond-> as-of-tx (d/as-of as-of-tx)))
        rows   (fetch-export-rows db {:from from :to to})
        header (extf/render-header
                {:versionsnummer versionsnummer
                 :datenkategorie (extf/datenkategorie :buchungsstapel)
                 :formatname "Buchungsstapel"
                 :erzeugt-am timestamp
                 :herkunft "HC"
                 :exportiert-von company-name
                 :berater berater-nr
                 :mandant mandant-nr
                 :wj-beginn (extf/format-period-bound from)
                 :sachkontenlaenge sachkontenlaenge
                 :datum-von (extf/format-period-bound from)
                 :datum-bis (extf/format-period-bound (Date. (dec (.getTime ^Date to))))
                 :bezeichnung "Buchungen"
                 :buchungstyp 1
                 :wkz "EUR"})
        column-line (extf/render-row columns)
        data-lines (mapv (fn [r] (extf/render-row (posting->cells r))) rows)]
    (str/join "\r\n" (concat [header column-line] data-lines [""]))))

(defn write-to-file!
  "Write an export string to `filepath` in ISO-8859-1 (DATEV's charset)."
  [conn ^String filepath opts]
  (spit filepath (export-buchungsstapel conn opts) :encoding "ISO-8859-1"))

;; ============================================================================
;; Import: EXTF → bookings
;; ============================================================================

(defn- cell [cells k] (get cells (col-idx k) ""))

(defn- row->booking
  "Turn one data-row cell vector into a neutral booking map. `:amount` is
   the SIGNED amount on the Konto side (positive for `S`/debit, negative
   for `H`/credit) — the sign convention `:kontor.posting/amount` uses."
  [cells ^long year]
  (let [umsatz (extf/parse-decimal (cell cells :umsatz))
        sh     (str/upper-case (str/trim (cell cells :soll-haben)))
        signed (if (= sh "H") (.negate ^BigDecimal umsatz) umsatz)]
    {:konto        (cell cells :konto)
     :gegenkonto   (cell cells :gegenkonto)
     :soll-haben   sh
     :amount       signed
     :bu-schluessel (not-empty (cell cells :bu-schluessel))
     :date         (extf/parse-belegdatum (cell cells :belegdatum) year)
     :belegfeld-1  (not-empty (cell cells :belegfeld-1))
     :belegfeld-2  (not-empty (cell cells :belegfeld-2))
     :text         (not-empty (cell cells :buchungstext))}))

(defn parse-buchungsstapel
  "Parse an EXTF Buchungsstapel string into `{:header <parsed-header>
   :bookings [<booking> …]}`. Skips the header + column lines and any
   trailing blank line. Each booking's `:amount` is signed on the Konto
   side; `:date` resolved with the header's fiscal year."
  [^String content]
  (let [lines  (->> (str/split-lines content) (remove str/blank?))
        header (extf/parse-header (first lines))
        year   (:fiscal-year header)
        data   (drop 2 lines)]                 ; header + column line
    {:header header
     :bookings (mapv (fn [line] (row->booking (extf/split-row line) year)) data)}))

(defn booking->tx-data
  "Materialise one booking into a balanced two-leg transaction tx-data.
   `account-fn` resolves a DATEV Konto string to an account ref (eid or
   lookup-ref) — codes are not globally unique, so resolution is the
   caller's (per ADR-119). Opts: `:journal` (ref, required), `:commodity`
   (ref, required), `:state` (default :draft), `:tx-tempid` (default -1)."
  [{:keys [konto gegenkonto amount date text]}
   account-fn
   {:keys [journal commodity state tx-tempid]
    :or {state :draft tx-tempid -1}}]
  (let [amt ^BigDecimal amount]
    [(cond-> {:db/id tx-tempid
              :kontor.transaction/journal journal
              :kontor.transaction/state state}
       date (assoc :kontor.transaction/effective-date date)
       text (assoc :kontor.transaction/narration text))
     {:db/id (dec tx-tempid) :kontor.posting/transaction tx-tempid
      :kontor.posting/account (account-fn konto)
      :kontor.posting/amount amt
      :kontor.posting/commodity commodity}
     {:db/id (- tx-tempid 2) :kontor.posting/transaction tx-tempid
      :kontor.posting/account (account-fn gegenkonto)
      :kontor.posting/amount (.negate amt)
      :kontor.posting/commodity commodity}]))
