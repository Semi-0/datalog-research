(ns examples.lain.amb.native-procedure-pipeline
  "Choose three supported procedures whose composition satisfies one target."
  (:require [clojure.java.io :as io]
            [examples.lain.amb.native-premise-selection :as selection]))

(def source (slurp (io/resource "examples/lain/amb/native_procedure_pipeline.lain")))

(def definition-names
  '[definition-one definition-ten definition-three
    definition-double definition-square definition-triple
    definition-minus-three definition-seven definition-half])

(defn run [] (selection/run source definition-names))

(defn -main [& _]
  (try
    (let [run (run)]
      (prn {:status (get-in run [:result :status])
            :solution (get-in run [:snapshot :items])
            :ready? (get-in run [:snapshot :ready?])})
      (if-let [error (get-in run [:result :error])] (throw error) nil))
    (finally (shutdown-agents))))
