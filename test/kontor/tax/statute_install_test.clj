(ns kontor.tax.statute-install-test
  "Every l10n preset claims `Idempotent.` — asserted here for all twelve
   at once rather than trusted twelve times.

   It was false. `:kontor.parameter/code`, `:kontor.regime/code` and
   `:kontor.provision/code` are unique identity attrs, so those rows
   upsert; `:parameter-value` and `:parameter-bracket` had no identity
   and every statute installer re-transacted them unconditionally.
   Running `l10n-in`'s installer twice took parameter-values from 69 to
   138 and the CIT surcharge ladder from 3 bands to 6.

   The duplicated ladder is what made this more than untidy. A ladder is
   consumed POSITIONALLY — a provider reads the band below a band to find
   the threshold it starts at — so on a doubled ladder the predecessor of
   the top open band is the other copy of itself. The IN marginal-relief
   threshold read nil instead of ₹100,000,000 and the relief was silently
   skipped: a wrong tax figure, from installing a preset twice.

   Closed at the schema (`:kontor.parameter-value/identity`,
   `:kontor.parameter-bracket/identity`), so it holds for jurisdiction
   thirteen without anyone remembering. Note 194 §2."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.tax.statute :as statute]))

(def ^:private countries
  ["at" "au" "br" "ca" "cn" "de" "fr" "in" "jp" "mx" "uk" "us"])

(defn- install-fn [cc]
  (requiring-resolve (symbol (str "kontor.l10n-" cc ".preset") "install-all!")))

(defn- row-counts [conn]
  (let [n (fn [attr] (count (d/q [:find '?e :where ['?e attr '_]] @conn)))]
    {:parameters       (n :kontor.parameter/code)
     :parameter-values (n :kontor.parameter-value/decimal-value)
     :brackets         (n :kontor.parameter-bracket/upper)
     :provisions       (n :kontor.provision/code)
     :regimes          (n :kontor.regime/code)
     :accounts         (n :kontor.account/path)}))

(deftest every-preset-install-is-idempotent
  (doseq [cc countries]
    (testing (str "l10n-" cc)
      (let [conn (core/create-test-db)
            install! (install-fn cc)
            _ (install! conn)
            once (row-counts conn)
            _ (install! conn)
            twice (row-counts conn)]
        (is (pos? (:parameters once)) "the preset installed something at all")
        (is (= once twice)
            (str "l10n-" cc " install-all! is not idempotent; deltas: "
                 (pr-str (into {} (remove (fn [[k v]] (= v (get twice k)))) once))))))))

(deftest a-reinstalled-bracket-ladder-keeps-its-shape
  ;; The specific case that changed a tax number.
  (let [conn (core/create-test-db)
        install! (install-fn "in")
        ladder #(statute/parameter-brackets-at @conn "IN.CIT.standard.surcharge-brackets"
                                               #inst "2026-03-31")]
    (install! conn)
    (let [after-one (ladder)]
      (is (= 3 (count after-one)) "0 / 7 / 12 % bands")
      (is (= [0M 0.07M 0.12M] (mapv :rate after-one)))
      (install! conn)
      (install! conn)
      (is (= after-one (ladder))
          "three installs, same ladder — not six bands"))))

(deftest bracket-resolution-collapses-identical-bands
  ;; Defence in depth. The schema stops duplicates being installed, but a
  ;; ladder handed to a provider must never contain a band that is its own
  ;; predecessor, whatever its provenance.
  (let [conn (core/create-test-db)
        p    {:kontor.parameter/code "TEST.ladder"
              :kontor.parameter/unit :bracket-scale
              :kontor.parameter/jurisdiction :xx}
        band (fn [idx rate upper]
               (cond-> {:kontor.parameter-bracket/parameter [:kontor.parameter/code "TEST.ladder"]
                        :kontor.parameter-bracket/index idx
                        :kontor.parameter-bracket/rate rate
                        :kontor.parameter-bracket/effective-from #inst "2020-01-01"}
                 upper (assoc :kontor.parameter-bracket/upper upper)))]
    (d/transact conn [p])
    (d/transact conn [(band 0 0M 100M) (band 1 0.07M 1000M) (band 2 0.12M nil)])
    (testing "the composite identity refuses a second row at the same position"
      ;; re-transacting index 1 with a DIFFERENT rate must update, not append
      (d/transact conn [(band 1 0.09M 1000M)])
      (let [l (statute/parameter-brackets-at @conn "TEST.ladder" #inst "2026-01-01")]
        (is (= 3 (count l)))
        (is (= [0M 0.09M 0.12M] (mapv :rate l)) "updated in place")))
    (testing "and identical bands at distinct positions collapse on read"
      ;; index 3 duplicating index 2 is a data error, not a real band
      (d/transact conn [(band 3 0.12M nil)])
      (is (= 3 (count (statute/parameter-brackets-at @conn "TEST.ladder"
                                                     #inst "2026-01-01")))))))
