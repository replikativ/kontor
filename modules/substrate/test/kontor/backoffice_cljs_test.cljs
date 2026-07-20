(ns kontor.backoffice-cljs-test
  "Phase-E2 (note 192): the back-office read/write namespaces — AR/AP aging,
   year-end closing, bank reconciliation — are ported to .cljc and COMPILE +
   LOAD in cljs (java.time day-math → getTime; BigDecimal → money helpers;
   Date → now/date-from-millis). This is a compile+load smoke; their
   data-bearing behaviour is JVM-verified (they're server-side back-office,
   not the frontend read path), and the requires here force cljs compilation."
  (:require [cljs.test :refer [deftest is]]
            [kontor.reporting.aging :as aging]
            [kontor.reporting.closing :as closing]
            [kontor.banking.reconciliation :as recon]))

(deftest backoffice-nss-compile-and-load-in-cljs
  (is (fn? aging/aging-by-partner)  "reporting.aging loads in cljs")
  (is (fn? closing/close-fiscal-year!) "reporting.closing loads in cljs")
  (is (fn? recon/open-receivables-by-tx) "banking.reconciliation loads in cljs"))
