(ns kontor.l10n-mx.statements-test
  "The MX Estado de Resultados + Balance General against a hand-computed
   book (note-196 F4).

   Every expectation below is arithmetic done by hand in the comment
   block, not a value captured from a previous run — a golden-value test
   would pass just as happily if the definitions double-counted."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.l10n-mx.bs :as bs]
            [kontor.l10n-mx.pnl :as pnl]
            [kontor.l10n-mx.preset :as mx]
            [kontor.reporting.financial-statements :as fs]))

(def ^:private mxn :MXN)
(def ^:private gj  [:kontor.journal/code "GJ"])

(defn- p2
  "Two-leg entry: debit `d`, credit `c`, `amount` MXN, on `date`."
  [conn date d c amount]
  (book/entry! conn {:debit-account  [:kontor.account/path d]
                     :credit-account [:kontor.account/path c]
                     :amount         amount
                     :commodity      mxn
                     :journal        gj
                     :effective-date date}))

(defn- pm
  "Multi-leg entry from `[path signed-amount]` pairs (positive = debit),
   which must sum to zero. Used for the IVA sales, where the collected
   tax is a third (credit) leg to a liability account."
  [conn date pairs]
  (book/entry! conn {:postings (mapv (fn [[path amt]]
                                       {:account [:kontor.account/path path]
                                        :amount  amt})
                                     pairs)
                     :commodity      mxn
                     :journal        gj
                     :effective-date date}))

(defn- seed!
  "A structurally complete year for a small SA de CV: owner capital, a
   loan-financed machine, inventory on account, a 16% IVA credit sale, a
   16% IVA service sale, COGS out of inventory, payroll, rent, insurance,
   year-end depreciation, interest both ways, a bank fee, a customer
   collection — and a NEXT-year sale that must never appear in FY2026."
  [conn]
  ;; --- capital + financing + inventory ---
  (p2 conn #inst "2026-01-02" "Activo:Circulante:Bancos:Nacional"
      "Capital:Capital-Social" 500000M)
  (p2 conn #inst "2026-01-10" "Activo:Fijo:Maquinaria"
      "Pasivo:Largo-Plazo:Prestamos" 200000M)
  (p2 conn #inst "2026-01-15" "Activo:Circulante:Inventarios:Mercancias"
      "Pasivo:Corto-Plazo:Proveedores" 120000M)
  ;; --- 16% IVA credit sale: net 400000 + IVA 64000 → AR 464000.
  ;;     Cash-basis: the collected IVA lands on 208.02 (NO cobrado), a
  ;;     LIABILITY, not revenue. ---
  (pm conn #inst "2026-03-01"
      [["Activo:Circulante:Clientes"           464000M]
       ["Ingresos:Ventas:Nacional:16"         -400000M]
       ["Pasivo:IVA-Trasladado:NoCobrado:16"   -64000M]])
  ;; --- 16% IVA service sale: net 100000 + IVA 16000 → AR 116000 ---
  (pm conn #inst "2026-03-05"
      [["Activo:Circulante:Clientes"          116000M]
       ["Ingresos:Servicios"                 -100000M]
       ["Pasivo:IVA-Trasladado:NoCobrado:16"  -16000M]])
  ;; --- COGS, opex ---
  (p2 conn #inst "2026-04-10" "Costos:Costo-de-Ventas"
      "Activo:Circulante:Inventarios:Mercancias" 90000M)
  (p2 conn #inst "2026-05-01" "Gastos:Generales:Sueldos"
      "Activo:Circulante:Bancos:Nacional" 150000M)
  (p2 conn #inst "2026-05-01" "Gastos:Generales:Renta"
      "Activo:Circulante:Bancos:Nacional" 60000M)
  (p2 conn #inst "2026-06-01" "Gastos:Generales:Seguros"
      "Activo:Circulante:Bancos:Nacional" 12000M)
  ;; year-end adjusting entry, ON Dec 31 — the posting an exclusive :to drops
  (p2 conn #inst "2026-12-31" "Gastos:Generales:Depreciacion"
      "Activo:Fijo:Depreciacion-Acumulada" 20000M)
  ;; --- financial result ---
  (p2 conn #inst "2026-07-01" "Activo:Circulante:Bancos:Nacional"
      "Ingresos:Otros:Intereses" 5000M)
  (p2 conn #inst "2026-07-15" "Gastos:Financieros:Intereses"
      "Activo:Circulante:Bancos:Nacional" 8000M)
  (p2 conn #inst "2026-08-01" "Gastos:Financieros:Comisiones-Bancarias"
      "Activo:Circulante:Bancos:Nacional" 3000M)
  ;; --- collection ---
  (p2 conn #inst "2026-09-01" "Activo:Circulante:Bancos:Nacional"
      "Activo:Circulante:Clientes" 300000M)
  ;; --- NEXT fiscal year — must never appear in an FY2026 statement ---
  (pm conn #inst "2027-02-01"
      [["Activo:Circulante:Clientes"          232000M]
       ["Ingresos:Ventas:Nacional:16"        -200000M]
       ["Pasivo:IVA-Trasladado:NoCobrado:16"  -32000M]])
  conn)

(defn- book [] (seed! (mx/create-mx-db)))

;; Hand-computed FY2026:
;;   Ingresos         400000 ventas + 100000 servicios              = 500000
;;   Costo de ventas                                                =  90000
;;   Utilidad bruta                                                 = 410000
;;   Gastos operación 150000 sueldos + 60000 renta + 12000 seguros
;;                    + 20000 depreciación                          = 242000
;;   Utilidad operación                                             = 168000
;;   Otros ingresos   5000 intereses ganados                        =   5000
;;   Gastos financ.   8000 intereses + 3000 comisiones              =  11000
;;   Utilidad antes de impuestos                                    = 162000
;;
;;   Bancos    500000 + 5000 + 300000 − 150000 − 60000 − 12000
;;             − 8000 − 3000                                        = 572000
;;   Clientes  464000 + 116000 − 300000                             = 280000
;;   Inventario 120000 − 90000                                      =  30000
;;   Activo circulante                                              = 882000
;;   Maquinaria 200000 − 20000 depreciación acumulada              = 180000
;;   Activo total                                                   =1062000
;;   Proveedores 120000 + IVA trasladado 80000                      = 200000
;;   Préstamo largo plazo                                           = 200000
;;   Pasivo total                                                   = 400000
;;   Capital 500000 social + 505000 ingresos − 343000 costos/gastos = 662000
;;   Pasivo + capital                                               =1062000

(def ^:private fy {:from #inst "2026-01-01" :through #inst "2026-12-31"})

(deftest estado-de-resultados-matches-hand-computed-book
  (let [p   (pnl/compute (book) fy)
        sub #(:amount (fs/section-subtotal p %))]
    (testing "secciones"
      (is (= 500000M (sub "1")) "ingresos, netos de IVA")
      (is (= 90000M  (sub "2")) "costo de ventas")
      (is (= 242000M (sub "3")) "gastos de operación, incl. depreciación Dec-31")
      (is (= 5000M   (sub "4")) "otros ingresos (intereses)")
      (is (= 11000M  (sub "5")) "gastos financieros"))
    (testing "subtotales derivados del formato multi-step"
      (is (= 410000M (:amount (:mx.pnl/utilidad-bruta p))))
      (is (= 168000M (:amount (:mx.pnl/utilidad-operacion p))))
      (is (= 162000M (:amount (:mx.pnl/utilidad-antes-impuestos p)))))
    (testing "revenue EXCLUDES collected IVA (a liability, not income)"
      ;; 64000 + 16000 = 80000 of IVA was collected; none of it is in
      ;; ingresos. Gross AR was 580000 but revenue is only the 500000 net.
      (is (= 500000M (sub "1")) "ingresos are net; the 80000 IVA is elsewhere"))))

(deftest balance-general-matches-and-balances
  (let [b (bs/compute (book) {:through #inst "2026-12-31"})]
    (is (= 1062000M (:amount (:mx.bs/total-activo b))))
    (is (= 400000M  (:amount (:mx.bs/total-pasivo b))))
    (is (= 662000M  (:amount (:mx.bs/total-capital b))))
    (testing "la ecuación contable se cumple (:difference 0)"
      (is (= 0M (:amount (:mx.bs/difference b))))
      (is (:mx.bs/balanced? b)))
    (testing "depreciación acumulada netea contra el activo fijo bruto"
      ;; 200000 maquinaria − 20000 acumulada = 180000 neto
      (is (= 180000M (:amount (fs/section-subtotal b "B")))))
    (testing "el IVA trasladado cobrado se presenta como pasivo, no ingreso"
      ;; D.5 IVA trasladado = 64000 + 16000 = 80000, en pasivo a corto plazo
      (is (= 80000M (:amount (fs/line-value b "D" "D.5")))))))

(deftest every-money-is-tagged-mxn-not-eur
  ;; F5: the report engine must carry the jurisdiction's own currency,
  ;; not the engine's historical :EUR default.
  (let [conn (book)
        p    (pnl/compute conn fy)
        b    (bs/compute conn {:through #inst "2026-12-31"})]
    (testing "P&L subtotals + total are MXN"
      (is (= #{:MXN} (into #{} (map (comp :commodity (partial fs/section-subtotal p)))
                           ["1" "2" "3" "4" "5"])))
      (is (= :MXN (:commodity (:mx.pnl/utilidad-antes-impuestos p)))))
    (testing "BS totals are MXN"
      (is (= :MXN (:commodity (:mx.bs/total-activo b))))
      (is (= :MXN (:commodity (:mx.bs/total-capital b)))))
    (testing "F5 is genuinely the engine deriving from postings, not the
              module stamping: a definition with NO :line/commodity still
              yields MXN because the postings are MXN"
      (let [bare {:statement/name "bare" :statement/country "MX"
                  :statement/sections
                  [{:section/code "1" :section/label "ingresos"
                    :section/lines [{:line/code "1.1" :line/codes ["401%"]}]}]}
            r    (fs/compute-statement conn bare (assoc fy :total-sign-map {"1" :+}))]
        (is (= :MXN (:commodity (fs/section-subtotal r "1"))))
        (is (= 400000M (:amount (fs/section-subtotal r "1"))))))))

(deftest window-bound-is-inclusive-via-through
  ;; :to is EXCLUSIVE. :through translates it. Both halves matter: Dec-31
  ;; entries must be INCLUDED, and next-year entries must be EXCLUDED.
  (let [conn (book)]
    (testing ":through includes the Dec-31 depreciation and excludes FY2027"
      (is (= 162000M (:amount (:mx.pnl/utilidad-antes-impuestos (pnl/compute conn fy))))))
    (testing "the explicit exclusive form agrees"
      (is (= 162000M (:amount (:mx.pnl/utilidad-antes-impuestos
                               (pnl/compute conn {:from #inst "2026-01-01"
                                                  :to   #inst "2027-01-01"}))))))
    (testing "an exclusive Dec-31 bound silently drops the Dec-31 entry"
      ;; 20000 of depreciation goes missing, so the result reads 20000 high
      (is (= 182000M (:amount (:mx.pnl/utilidad-antes-impuestos
                               (pnl/compute conn {:from #inst "2026-01-01"
                                                  :to   #inst "2026-12-31"}))))))))

(deftest check-reports-the-accounting-equation
  (let [r (bs/check (book) {:through #inst "2026-12-31"})]
    (is (:mx.bs/balanced? r))
    (is (= 0M (:amount (:mx.bs/difference r))))
    (is (= #{:mx.bs/total-activo :mx.bs/total-pasivo :mx.bs/total-capital
             :mx.bs/difference :mx.bs/balanced?}
           (set (keys r))))))
