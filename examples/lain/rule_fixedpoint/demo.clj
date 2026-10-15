(ns examples.lain.rule-fixedpoint.demo
  "Load a complete Lain rule catalog; the host supplies facts and solver selection."
  (:require [clojure.java.io :as io]
            [examples.lain.amb.solver-strategy :as strategy]
            [examples.lain.amb.worker-driver :as driver]
            [examples.lain.rule-fixedpoint.catalog :as catalog]
            [examples.lain.rule-fixedpoint.engine :as engine]
            [examples.lain.rule-fixedpoint.fixtures :as fixtures]
            [examples.lain.rule-fixedpoint.solvers :as solvers]
            [propagators.cells.value :as value]))

(defn completed [world]
  (let [result (engine/run world)]
    (if (= :completed (:status result)) result (throw (:error result)))))

(defn fixture [program]
  (case program
    :reach (fixtures/diamond 1)
    :dataflow (fixtures/dataflow)
    (throw (ex-info "Unknown rule demo" {:program program}))))

(defn run-program [mode program]
  (let [fixture (fixture program)
        catalog (catalog/compile-source
                 (slurp (io/resource (str "examples/lain/rule_fixedpoint/" (name program) ".lain"))))
        source (case program :reach :edge-0 :dataflow :flow-0
                     (throw (ex-info "Unknown rule demo" {:program program})))
        tuple (get-in fixture [:sources source :tuple])
        options (solvers/attach (:rules catalog) {:strategy mode :domain (:domain fixture)} catalog)
        pool (driver/pool 4)]
    (try
      (binding [strategy/*executor* pool]
        (let [active (completed (engine/seed (engine/prepare (:rules catalog) options (:sources fixture))))
              withdrawn (completed (engine/inject active source value/nothing {:timestamp 2 :status :retracted}))
              restored (completed (engine/inject withdrawn source tuple {:timestamp 3 :status :active}))]
          (mapv (fn [[stage world]] {:stage stage :status (:status world)
                                    :reach (vec (sort-by pr-str (engine/facts world :reach)))
                                    :facts (into {} (map (fn [[predicate _]]
                                                          [predicate (vec (sort-by pr-str (engine/facts world predicate)))]))
                                                 (:expected fixture))
                                    :metrics (engine/metrics world)})
                [[:active active] [:one-path-withdrawn withdrawn] [:restored restored]])))
      (finally (driver/close! pool)))))

(defn run [mode] (run-program mode :reach))

(defn -main [& [mode program]]
  (try
    (doseq [stage (run-program (if mode (keyword mode) :indexed-delta)
                              (if program (keyword program) :reach))] (prn stage))
    (finally (shutdown-agents))))
