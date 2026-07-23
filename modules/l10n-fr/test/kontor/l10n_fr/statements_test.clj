(ns kontor.l10n-fr.statements-test
  "The French Compte de résultat + Bilan against a hand-computed book.

   Every expectation below is arithmetic done by hand in the comment
   block, not a value captured from a previous run — a golden-value test
   would pass just as happily if the definitions double-counted a class-44
   TVA account across the actif/passif split.

   Booked against the shipped `kontor.l10n-fr.preset/create-fr-db` (PCG
   skeleton + GJ/CR/CD/SJ/PJ journals). TVA legs are added explicitly on
   the multi-leg entries — this test exercises the STATEMENTS, so it books
   the TVA by hand rather than going through `l10n-fr.invoice`, keeping the
   arithmetic legible."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.book :as book]
            [kontor.l10n-fr.bs :as bs]
            [kontor.l10n-fr.pnl :as pnl]
            [kontor.l10n-fr.preset :as fr]
            [kontor.reporting.financial-statements :as fs]))

(def ^:private eur :EUR)
;; GJ (general journal) — used explicitly for every entry because the
;; preset ships two :cash journals (CR + CD), so the verb conveniences
;; that resolve a journal by type would be ambiguous.
(def ^:private gj [:kontor.journal/code "GJ"])

(defn- acct [path] [:kontor.account/path path])

(defn- two-leg! [conn date debit credit amount]
  (book/entry! conn {:debit-account  (acct debit)
                     :credit-account (acct credit)
                     :amount         amount
                     :commodity      eur
                     :journal        gj
                     :effective-date date}))

(defn- multi! [conn date postings]
  (book/entry! conn {:postings       (mapv (fn [[path amt]]
                                             {:account (acct path) :amount amt})
                                           postings)
                     :commodity      eur
                     :journal        gj
                     :effective-date date}))

(defn- seed!
  "A small, structurally complete FR year: owner capital, office
   equipment bought on supplier credit, a services invoice + a merchandise
   invoice both at 20% TVA collectée, supplies bought with deductible TVA,
   rent, insurance, payroll, a customer payment, and a supplier payment.
   Touches every account class the two statements cover."
  [conn]
  ;; 1. Apport en capital: Dr Banque 50000 / Cr Capital social 50000.
  (two-leg! conn #inst "2026-01-02" "Banque:Compte-courant"
            "Capitaux:Capital-social" 50000M)
  ;; 2. Achat de matériel de bureau à crédit: Dr Immo 10000 / Cr Fourn 10000.
  (two-leg! conn #inst "2026-01-10" "Immobilisations:Matériel-bureau"
            "Tiers:Fournisseurs" 10000M)
  ;; 3. Facture de prestations 40000 HT @ 20% TVA → 48000 TTC.
  (multi! conn #inst "2026-03-01"
          [["Tiers:Clients:France"        48000M]
           ["Produits:Prestations-services" -40000M]
           ["État:TVA-collectée:20"        -8000M]])
  ;; 4. Facture de marchandises 20000 HT @ 20% TVA → 24000 TTC.
  (multi! conn #inst "2026-06-01"
          [["Tiers:Clients:France"       24000M]
           ["Produits:Ventes-marchandises" -20000M]
           ["État:TVA-collectée:20"       -4000M]])
  ;; 5. Achat de fournitures 5000 HT @ 20% TVA déductible → 6000 TTC à crédit.
  (multi! conn #inst "2026-04-10"
          [["Charges:Fournitures-bureau"          5000M]
           ["État:TVA-déductible:Biens-services"  1000M]
           ["Tiers:Fournisseurs"                 -6000M]])
  ;; 6. Loyer réglé par banque.
  (two-leg! conn #inst "2026-05-01" "Charges:Locations"
            "Banque:Compte-courant" 12000M)
  ;; 7. Prime d'assurance réglée par banque.
  (two-leg! conn #inst "2026-06-15" "Charges:Assurances"
            "Banque:Compte-courant" 3000M)
  ;; 8. Rémunérations du personnel réglées par banque.
  (two-leg! conn #inst "2026-07-01" "Charges:Personnel"
            "Banque:Compte-courant" 15000M)
  ;; 9. Encaissement client (règle la 1re facture).
  (two-leg! conn #inst "2026-09-01" "Banque:Compte-courant"
            "Tiers:Clients:France" 48000M)
  ;; 10. Règlement fournisseur (matériel 10000 + fournitures 6000).
  (two-leg! conn #inst "2026-10-01" "Tiers:Fournisseurs"
            "Banque:Compte-courant" 16000M)
  ;; NEXT fiscal year — must never appear in an FY2026 statement.
  (multi! conn #inst "2027-02-01"
          [["Tiers:Clients:France"        60000M]
           ["Produits:Prestations-services" -50000M]
           ["État:TVA-collectée:20"       -10000M]])
  conn)

(defn- book [] (seed! (fr/create-fr-db)))

;; Hand-computed FY2026:
;;   Produits d'exploitation  706 40000 + 707 20000              =  60000
;;   Charges d'exploitation
;;     achats (606)                                              =   5000
;;     autres charges externes (613 12000 + 616 3000)           =  15000
;;     charges de personnel (641)                               =  15000
;;                                                     total     =  35000
;;   Résultat d'exploitation                                     =  25000
;;   Résultat financier / exceptionnel / impôt                  =      0
;;   Résultat net                                                =  25000
;;
;;   TVA collectée (44571) 8000 + 4000 = 12000  →  a State liability,
;;                                                 NOT revenue.
;;
;;   Bilan @ 2026-12-31
;;     Immobilisations corporelles (2183)                       =  10000
;;     Clients (4111) 48000 + 24000 − 48000                     =  24000
;;     TVA déductible (44566)                                   =   1000
;;     Banque (5121) 50000 + 48000 − 12000 − 3000 − 15000
;;                   − 16000                                     =  52000
;;     ACTIF                                                     =  87000
;;     Fournisseurs (401) 10000 + 6000 − 16000                  =      0
;;     TVA collectée (44571)                                    =  12000
;;     DETTES                                                    =  12000
;;     Capital (101) 50000 + résultat 25000                     =  75000
;;     CAPITAUX PROPRES                                          =  75000
;;     PASSIF = 12000 + 75000                                   =  87000

(def ^:private fy {:from #inst "2026-01-01" :through #inst "2026-12-31"})

(deftest compte-de-resultat-matches-hand-computed-book
  (let [p (pnl/compute (book) fy)
        sub #(:amount (fs/section-subtotal p %))]
    (testing "sections"
      (is (= 60000M (sub "1")) "produits d'exploitation, net of TVA")
      (is (= 35000M (sub "2")) "charges d'exploitation")
      (is (= 0M (sub "3")) "produits financiers")
      (is (= 0M (sub "4")) "charges financières")
      (is (= 0M (sub "5")) "produits exceptionnels")
      (is (= 0M (sub "6")) "charges exceptionnelles")
      (is (= 0M (sub "7")) "participation + impôt sur les bénéfices"))
    (testing "revenue lines split by nature"
      (is (= 20000M (:amount (fs/line-value p "1" "1.1"))) "ventes de marchandises (707)")
      (is (= 40000M (:amount (fs/line-value p "1" "1.3"))) "prestations de services (706)"))
    (testing "expense lines split by nature"
      (is (= 5000M (:amount (fs/line-value p "2" "2.1"))) "achats (606)")
      (is (= 15000M (:amount (fs/line-value p "2" "2.2"))) "autres charges externes (613 + 616)")
      (is (= 15000M (:amount (fs/line-value p "2" "2.4"))) "charges de personnel (641)"))
    (testing "soldes intermédiaires de gestion"
      (is (= 25000M (:amount (:fr.pnl/resultat-exploitation p))))
      (is (= 0M (:amount (:fr.pnl/resultat-financier p))))
      (is (= 25000M (:amount (:fr.pnl/resultat-courant-avant-impots p))))
      (is (= 0M (:amount (:fr.pnl/resultat-exceptionnel p))))
      (is (= 25000M (:amount (:fr.pnl/resultat-net p)))))))

(deftest revenue-excludes-collected-tva
  ;; The 12000 TVA collectée must NOT inflate revenue — it is a class-44
  ;; State liability, unreachable from any produits line.
  (let [p (pnl/compute (book) fy)]
    (is (= 60000M (:amount (fs/section-subtotal p "1")))
        "produits d'exploitation is 60000 HT, not 72000 TTC")
    (is (= 25000M (:amount (:fr.pnl/resultat-net p))))))

(deftest bilan-matches-and-balances
  (let [b (bs/compute (book) {:through #inst "2026-12-31"})]
    (is (= 87000M (:amount (:fr.bs/total-actif b))))
    (is (= 75000M (:amount (:fr.bs/total-capitaux-propres b))))
    (is (= 12000M (:amount (:fr.bs/total-dettes b))))
    (is (= 87000M (:amount (:fr.bs/total-passif b))))
    (testing "ACTIF = PASSIF"
      (is (= 0M (:amount (:fr.bs/difference b))))
      (is (:fr.bs/balanced? b)))
    (testing "actif circulant lines"
      (is (= 24000M (:amount (fs/line-value b "B" "B.2"))) "créances clients (4111)")
      (is (= 1000M (:amount (fs/line-value b "B" "B.3"))) "TVA déductible (44566)")
      (is (= 52000M (:amount (fs/line-value b "B" "B.4"))) "disponibilités (5121)"))
    (testing "capitaux propres carries the interim résultat"
      (is (= 60000M (:amount (fs/line-value b "C" "C.5"))) "résultat — produits (class 7)")
      (is (= -35000M (:amount (fs/line-value b "C" "C.6"))) "résultat — charges (class 6, negated)"))
    (testing "TVA collectée is a dette, not netted into actif"
      (is (= 12000M (:amount (fs/line-value b "E" "E.3"))) "dettes fiscales (44571)"))))

(deftest every-money-is-tagged-eur
  ;; F5: statements carry the jurisdiction currency, derived from the
  ;; postings — not a stray :EUR-that-happens-to-be-wrong. FR's currency
  ;; IS EUR, so this pins that the engine tags EUR (never nil / another
  ;; symbol) across every subtotal, including the empty financier /
  ;; exceptionnel blocks.
  (let [p (pnl/compute (book) fy)
        b (bs/compute (book) {:through #inst "2026-12-31"})]
    (is (= #{:EUR}
           (into #{} (map (comp :commodity (partial fs/section-subtotal p)))
                 ["1" "2" "3" "4" "5" "6" "7"]))
        "every P&L section subtotal is EUR")
    (is (= #{:EUR}
           (into #{} (map (comp :commodity (partial fs/section-subtotal b)))
                 ["A" "B" "C" "D" "E"]))
        "every Bilan section subtotal is EUR")
    (is (= :EUR (:commodity (:fr.pnl/resultat-net p))))
    (is (= :EUR (:commodity (:fr.bs/total-actif b))))
    (is (= :EUR (:commodity (:fr.bs/difference b))))))

(deftest window-bound-is-inclusive-via-through
  ;; :to is EXCLUSIVE; :through is the inclusive form. Both halves matter:
  ;; the FY2027 sale (booked in seed!) must be EXCLUDED from FY2026.
  (let [conn (book)]
    (testing ":through / explicit exclusive :to agree, and exclude FY2027"
      (is (= 25000M (:amount (:fr.pnl/resultat-net (pnl/compute conn fy)))))
      (is (= 25000M (:amount (:fr.pnl/resultat-net
                              (pnl/compute conn {:from #inst "2026-01-01"
                                                 :to #inst "2027-01-01"}))))))
    (testing ":to and :through together is an error, not a silent precedence"
      (is (thrown? clojure.lang.ExceptionInfo
                   (pnl/compute conn {:to #inst "2027-01-01"
                                      :through #inst "2026-12-31"}))))))

(deftest check-reports-the-accounting-equation
  (let [r (bs/check (book) {:through #inst "2026-12-31"})]
    (is (:fr.bs/balanced? r))
    (is (= 0M (:amount (:fr.bs/difference r))))
    (is (= #{:fr.bs/total-actif :fr.bs/total-capitaux-propres
             :fr.bs/total-dettes :fr.bs/total-passif
             :fr.bs/difference :fr.bs/balanced?}
           (set (keys r))))))
