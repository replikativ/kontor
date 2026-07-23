(ns kontor.l10n-br.statements-test
  "End-to-end DRE (income statement) + Balanço Patrimonial (balance
   sheet) for the BR l10n module.

   Scenario — a Brazilian retailer (Comércio Ltda), FY2025, booked via
   `kontor.book` against the `create-br-db` preset:

     - Owner contributes R$100,000 cash (capital social).
     - Buys a building for R$50,000 cash.
     - Buys R$100,000 of inventory on account (fornecedores).
     - Sells goods, receita bruta R$200,000, ICMS/PIS/COFINS booked
       'por dentro' (deduction + liability, NOT revenue).
     - Recognises R$80,000 COGS (R$20,000 inventory left).
     - Pays salaries 30,000 / rent 12,000 / utilities 3,000.
     - Books R$5,000 depreciation on the building.
     - Earns R$6,000 finance income, pays R$1,500 finance expense.
     - Receives R$160,000 from the customer, pays R$60,000 to suppliers.
     - Provisions IRPJ R$3,000 + CSLL R$1,800.

   ## Hand-computed DRE (Lei 6.404/76 art. 187)

     Receita bruta                         200,000
     (-) Deduções (ICMS 36,000
         + PIS 3,300 + COFINS 15,200)      (54,500)
     = Receita líquida                     145,500
     (-) CMV                               (80,000)
     = Lucro bruto                          65,500
     (-) Despesas operacionais
         (30,000+12,000+3,000+5,000)       (50,000)
     + Receitas financeiras                  6,000
     (-) Despesas financeiras               (1,500)
     = Resultado operacional / LAIR         20,000
     (-) Provisão IRPJ (3,000) + CSLL
         (1,800)                            (4,800)
     = Lucro líquido do exercício           15,200

   ## Hand-computed Balanço (art. 178), 2025-12-31, pre-close

     ATIVO
       Circulante
         Banco            109,500
         Clientes          40,000
         Estoques          20,000            = 169,500
       Não circulante
         Edifícios         50,000
         (-) Depr. acum.   (5,000)           =  45,000
       Total ativo                            214,500
     PASSIVO + PL
       Circulante
         Fornecedores      40,000
         ICMS a recolher   36,000
         PIS a recolher     3,300
         COFINS a recolher 15,200
         IRPJ a recolher    3,000
         CSLL a recolher    1,800            =  99,300
       Patrimônio líquido
         Capital social   100,000
         Resultado exerc.  15,200            = 115,200
       Total passivo + PL                     214,500  → balances."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.l10n-br.bs :as bs]
            [kontor.l10n-br.pnl :as pnl]
            [kontor.l10n-br.preset :as preset]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def jan-1   #inst "2025-01-05T00:00:00Z")
(def mar-1   #inst "2025-03-01T00:00:00Z")
(def jun-1   #inst "2025-06-01T00:00:00Z")
(def sep-1   #inst "2025-09-01T00:00:00Z")
(def dec-15  #inst "2025-12-15T00:00:00Z")
;; :to is EXCLUSIVE — Jan 1 next year captures the whole of FY2025.
(def eoy     #inst "2026-01-01T00:00:00Z")
(def soy     #inst "2025-01-01T00:00:00Z")

(defn- ace
  "Resolve an account eid by its chart code."
  [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- jnl [code] [:kontor.journal/code code])

(defn- amt=
  "Numeric BigDecimal equality on a Money's :amount."
  [expected money]
  (and money
       (zero? (.compareTo ^java.math.BigDecimal (:amount money)
                          (bigdec expected)))))

(defn- seed-fy2025!
  "Book the full year via kontor.book. Returns the connection."
  [conn]
  (let [db (d/db conn)
        a  (partial ace db)
        ;; balance-sheet accounts
        bank      (a "1.01.01.02.01")
        ar        (a "1.01.03.01.01")
        inv       (a "1.01.04.02.01")
        building  (a "1.02.03.02.01")
        accdep    (a "1.02.03.99.01")
        capital   (a "2.03.01.01.01")
        suppliers (a "2.01.01.01.01")
        icms-pay  (a "2.01.04.01.01")
        pis-pay   (a "2.01.04.01.03")
        cofins-pay (a "2.01.04.01.04")
        irpj-pay  (a "2.01.04.01.06")
        csll-pay  (a "2.01.04.01.07")
        ;; result accounts
        rev       (a "3.01.01.01.01")
        icms-ded  (a "3.02.01.01.01")
        pis-ded   (a "3.02.01.01.02")
        cofins-ded (a "3.02.01.01.03")
        cogs      (a "3.03.01.01.01")
        salaries  (a "3.04.01.01.01")
        rent      (a "3.04.02.01.01")
        utilities (a "3.04.03.01.01")
        depr      (a "3.04.04.01.01")
        fin-inc   (a "3.07.01.01.01")
        fin-exp   (a "3.07.02.01.01")
        irpj-exp  (a "3.10.01.01.01")
        csll-exp  (a "3.10.01.01.02")]
    ;; 1. Owner contributes R$100,000 cash.
    (book/entry! conn {:journal (jnl "CR") :effective-date jan-1
                       :external-id "CAP-1" :commodity :BRL
                       :debit-account bank :credit-account capital :amount 100000M})
    ;; 2. Buys a building for R$50,000 cash.
    (book/entry! conn {:journal (jnl "CD") :effective-date jan-1
                       :external-id "BLD-1" :commodity :BRL
                       :debit-account building :credit-account bank :amount 50000M})
    ;; 3. Buys R$100,000 of inventory on account.
    (book/buy! conn {:effective-date mar-1 :external-id "PUR-1" :commodity :BRL
                     :debit-account inv :credit-account suppliers :amount 100000M})
    ;; 4. Sells goods — receita bruta 200,000, taxes 'por dentro'.
    (book/sell! conn {:effective-date jun-1 :external-id "NFe-1" :commodity :BRL
                      :postings [{:account ar :amount 200000M}
                                 {:account rev :amount -200000M}
                                 {:account icms-ded :amount 36000M}
                                 {:account pis-ded :amount 3300M}
                                 {:account cofins-ded :amount 15200M}
                                 {:account icms-pay :amount -36000M}
                                 {:account pis-pay :amount -3300M}
                                 {:account cofins-pay :amount -15200M}]})
    ;; 5. Recognises COGS 80,000 (inventory 20,000 left).
    (book/adjust! conn {:effective-date jun-1 :external-id "CMV-1" :commodity :BRL
                        :postings [{:account cogs :amount 80000M}
                                   {:account inv :amount -80000M}]})
    ;; 6. Operating expenses paid from bank.
    (book/entry! conn {:journal (jnl "CD") :effective-date jun-1
                       :external-id "SAL-1" :commodity :BRL
                       :debit-account salaries :credit-account bank :amount 30000M})
    (book/entry! conn {:journal (jnl "CD") :effective-date jun-1
                       :external-id "RENT-1" :commodity :BRL
                       :debit-account rent :credit-account bank :amount 12000M})
    (book/entry! conn {:journal (jnl "CD") :effective-date jun-1
                       :external-id "UTIL-1" :commodity :BRL
                       :debit-account utilities :credit-account bank :amount 3000M})
    ;; 7. Depreciation 5,000 on the building (contra-asset).
    (book/entry! conn {:journal (jnl "GJ") :effective-date dec-15
                       :external-id "DEP-1" :commodity :BRL
                       :debit-account depr :credit-account accdep :amount 5000M})
    ;; 8. Finance income / expense.
    (book/entry! conn {:journal (jnl "CR") :effective-date sep-1
                       :external-id "FIN-INC-1" :commodity :BRL
                       :debit-account bank :credit-account fin-inc :amount 6000M})
    (book/entry! conn {:journal (jnl "CD") :effective-date sep-1
                       :external-id "FIN-EXP-1" :commodity :BRL
                       :debit-account fin-exp :credit-account bank :amount 1500M})
    ;; 9. Customer pays 160,000; company pays suppliers 60,000.
    (book/entry! conn {:journal (jnl "CR") :effective-date sep-1
                       :external-id "REC-1" :commodity :BRL
                       :debit-account bank :credit-account ar :amount 160000M})
    (book/entry! conn {:journal (jnl "CD") :effective-date sep-1
                       :external-id "PAY-1" :commodity :BRL
                       :debit-account suppliers :credit-account bank :amount 60000M})
    ;; 10. IRPJ + CSLL provision.
    (book/adjust! conn {:effective-date dec-15 :external-id "TAX-1" :commodity :BRL
                        :postings [{:account irpj-exp :amount 3000M}
                                   {:account csll-exp :amount 1800M}
                                   {:account irpj-pay :amount -3000M}
                                   {:account csll-pay :amount -1800M}]})
    conn))

;; ---------------------------------------------------------------------------
;; DRE (income statement)
;; ---------------------------------------------------------------------------

(deftest dre-arithmetic
  (testing "DRE section subtotals + art. 187 sub-results match hand figures"
    (let [conn (preset/create-br-db)
          _    (seed-fy2025! conn)
          p    (pnl/compute conn {:from soy :to eoy})
          sub  #(kontor.reporting.financial-statements/section-subtotal p %)]
      (is (amt= 200000M (sub "1")) "Receita bruta")
      (is (amt= 54500M  (sub "2")) "Deduções (ICMS 36000 + PIS 3300 + COFINS 15200)")
      (is (amt= 80000M  (sub "3")) "CMV")
      (is (amt= 50000M  (sub "4")) "Despesas operacionais")
      (is (amt= 6000M   (sub "5")) "Receitas financeiras")
      (is (amt= 1500M   (sub "6")) "Despesas financeiras")
      (is (amt= 4800M   (sub "7")) "Provisão IRPJ + CSLL")
      (is (amt= 145500M (:br.pnl/receita-liquida p)) "Receita líquida")
      (is (amt= 65500M  (:br.pnl/lucro-bruto p)) "Lucro bruto")
      (is (amt= 20000M  (:br.pnl/resultado-operacional p)) "Resultado operacional")
      (is (amt= 20000M  (:br.pnl/lucro-antes-tributos p)) "LAIR")
      (is (amt= 15200M  (:br.pnl/lucro-liquido p)) "Lucro líquido do exercício")
      (is (amt= 15200M  (:statement/total p)) "Statement total = lucro líquido"))))

(deftest dre-revenue-excludes-collected-tax
  (testing "receita bruta holds the sale price only; the ICMS/PIS/COFINS
            the seller collected sit in liabilities, not revenue"
    (let [conn (preset/create-br-db)
          _    (seed-fy2025! conn)
          p    (pnl/compute conn {:from soy :to eoy})
          b    (bs/compute conn {:to eoy})
          sub-p #(kontor.reporting.financial-statements/section-subtotal p %)
          line-b #(kontor.reporting.financial-statements/line-value b %1 %2)]
      ;; Revenue is the full price (200,000) — the R$54,500 of collected
      ;; ICMS/PIS/COFINS is NOT added into it.
      (is (amt= 200000M (sub-p "1")) "receita bruta = sale price, tax-free of the payable")
      ;; The collected tax is a liability on the Balanço (C.4 obrigações
      ;; tributárias = 36,000 + 3,300 + 15,200 sales tax + 3,000 + 1,800
      ;; IRPJ/CSLL = 59,300).
      (is (amt= 59300M (line-b "C" "C.4")) "collected + provisioned taxes are liabilities")
      ;; Net revenue is gross minus those sales taxes — i.e. revenue with
      ;; the collected tax removed.
      (is (amt= 145500M (:br.pnl/receita-liquida p))
          "receita líquida = receita bruta − impostos sobre vendas"))))

;; ---------------------------------------------------------------------------
;; Balanço Patrimonial (balance sheet)
;; ---------------------------------------------------------------------------

(deftest balanco-arithmetic-and-balances
  (testing "Balanço subtotals match hand figures AND the equation holds"
    (let [conn (preset/create-br-db)
          _    (seed-fy2025! conn)
          b    (bs/compute conn {:to eoy})]
      (is (amt= 214500M (:br.bs/total-ativo b)) "Total ativo")
      (is (amt= 99300M  (:br.bs/total-passivo b)) "Total passivo")
      (is (amt= 115200M (:br.bs/total-pl b)) "Patrimônio líquido (capital 100k + result 15.2k)")
      (is (amt= 0M (:br.bs/difference b)) "ativo − (passivo + PL) = 0")
      (is (:br.bs/balanced? b) "Balanço balances")
      ;; The un-closed period result IS carried in equity (section E),
      ;; which is what makes an interim Balanço balance.
      (is (amt= 45000M (kontor.reporting.financial-statements/section-subtotal b "B"))
          "Imobilizado net of accumulated depreciation (50k − 5k)"))))

(deftest balanco-check-helper
  (testing "check returns the :br.bs/* summary and reports balanced"
    (let [conn (preset/create-br-db)
          _    (seed-fy2025! conn)
          r    (bs/check conn {:to eoy})]
      (is (true? (:br.bs/balanced? r)))
      (is (amt= 0M (:br.bs/difference r))))))

;; ---------------------------------------------------------------------------
;; Currency (note-196 F5) — every Money is tagged the jurisdiction's own
;; currency (:BRL), never the engine's :EUR default.
;; ---------------------------------------------------------------------------

(deftest every-money-is-brl
  (testing "all DRE + Balanço subtotals and totals are tagged :BRL"
    (let [conn (preset/create-br-db)
          _    (seed-fy2025! conn)
          p    (pnl/compute conn {:from soy :to eoy})
          b    (bs/compute conn {:to eoy})
          monies (concat
                  (map :section/subtotal (:statement/sections p))
                  (map :section/subtotal (:statement/sections b))
                  [(:statement/total p) (:statement/total b)
                   (:br.pnl/receita-liquida p) (:br.pnl/lucro-bruto p)
                   (:br.pnl/resultado-operacional p) (:br.pnl/lucro-liquido p)
                   (:br.bs/total-ativo b) (:br.bs/total-passivo b)
                   (:br.bs/total-pl b) (:br.bs/difference b)])]
      (doseq [m monies]
        (is (= :BRL (:commodity m))
            (str "expected :BRL, got " (:commodity m) " on " m))))))
