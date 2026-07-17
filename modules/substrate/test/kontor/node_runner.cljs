(ns kontor.node-runner
  "Entry point for the ClojureScript test lane (Phase 0 of research
   note 191). shadow-cljs compiles the `:node-test` build with this as
   `:main`; running the bundle on Node executes every cross-platform
   (`.cljc`) kontor test namespace and exits non-zero on any failure, so
   portability regressions are caught in CI instead of rotting silently.

   As more of the kernel becomes `.cljc`, add its `-test` namespace to the
   `:require` list AND to `run-tests` below. Named `node-runner` (not
   `-test`) so the JVM kaocha suite never tries to load this cljs-only ns."
  (:require [cljs.test :as t]
            [kontor.money-portable-test]
            [kontor.posting.validate-test]
            [kontor.cljs-smoke-test]
            [kontor.invariant-cljs-test]
            [kontor.bitemporal-entity-cljs-test]))

;; Exit 0 only when every test passes — otherwise Node exits 1 and CI fails.
(defmethod t/report [::t/default :end-run-tests] [m]
  (.exit js/process (if (t/successful? m) 0 1)))

(defn -main []
  (t/run-tests 'kontor.money-portable-test
               'kontor.posting.validate-test
               'kontor.cljs-smoke-test
               'kontor.invariant-cljs-test
               'kontor.bitemporal-entity-cljs-test))
