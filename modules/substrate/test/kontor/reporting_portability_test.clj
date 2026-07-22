(ns kontor.reporting-portability-test
  "A rot-guard, not a behaviour test. The whole read side is `.cljc` and
   runs on ClojureScript (that is the point — a frontend computes the
   same trial balance / account statement / financial statements as the
   backend, from the same code). But the cljs lane is a hand-maintained
   list in `kontor.node-runner`: add a new `src/kontor/reporting/*.cljc`
   and forget to exercise it on cljs, and it rots silently — it still
   COMPILES, so nothing complains, while never being proven to RUN.

   This JVM test (which always runs in the kaocha suite) closes that gap:
   every reporting namespace must be required by some `*-cljs-test`, and
   every such test must be wired into the node runner so it actually
   executes. `ledger` was uncovered exactly this way until note-194 PR 9.

   Scope is deliberately `src/kontor/reporting/` — the read surface a
   consumer renders. Widening it to the whole portable substrate is a
   later call (there are write-side `.cljc` too); keeping it narrow keeps
   the guarantee honest about what it actually checks."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private reporting-src-dir "src/kontor/reporting")
(def ^:private cljs-test-dir "modules/substrate/test/kontor")
(def ^:private node-runner "modules/substrate/test/kontor/node_runner.cljs")

(defn- reporting-namespaces
  "Every `kontor.reporting.<name>` shipped as a portable `.cljc`."
  []
  (->> (.listFiles (io/file reporting-src-dir))
       (filter #(str/ends-with? (.getName %) ".cljc"))
       (map #(-> (.getName %)
                 (str/replace #"\.cljc$" "")
                 (str/replace "_" "-")))
       (map #(str "kontor.reporting." %))
       set))

(defn- cljs-test-files []
  (->> (.listFiles (io/file cljs-test-dir))
       (filter #(str/ends-with? (.getName %) "_cljs_test.cljs"))))

(defn- ns-covered-by
  "Map reporting-ns -> set of cljs-test namespaces that require it."
  []
  (reduce
   (fn [acc test-file]
     (let [text (slurp test-file)
           test-ns (second (re-find #"\(ns ([\w.-]+)" text))]
       (reduce (fn [a rep-ns]
                 (cond-> a
                   (str/includes? text rep-ns) (update rep-ns (fnil conj #{}) test-ns)))
               acc
               (reporting-namespaces))))
   {}
   (cljs-test-files)))

(deftest every-reporting-namespace-is-exercised-on-cljs
  (let [reporting (reporting-namespaces)
        coverage (ns-covered-by)
        uncovered (remove #(seq (get coverage %)) reporting)]
    ;; guard against the guard passing vacuously (empty enumeration)
    (is (<= 8 (count reporting))
        (str "expected to enumerate the reporting .cljc namespaces; found " (count reporting)))
    (is (empty? uncovered)
        (str "these reporting namespaces are .cljc but no *_cljs_test.cljs requires them — "
             "add a cljs test that runs against a datahike-cljs db: " (vec uncovered)))))

(deftest every-covering-cljs-test-is-wired-into-the-node-runner
  (let [runner (slurp node-runner)
        covering-tests (into #{} (mapcat val) (ns-covered-by))
        not-wired (remove #(str/includes? runner %) covering-tests)]
    (testing "a cljs test that covers a reporting ns but is absent from the runner never runs"
      (is (empty? not-wired)
          (str "these cljs tests exist but are not in kontor.node-runner (so they do not run in CI): "
               (vec not-wired))))))
