(ns examples.lain.amb.file-premise-selection
  "Load ordinary Lain definitions and select the file fitting a target."
  (:require [clojure.java.io :as io]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.file-premise.files :as files]
            [propagators.network :as net]))

(defn fixture-path [name]
  (.getCanonicalPath
   (io/file (io/resource (str "examples/lain/amb/file_premise_selection/" name ".lain")))))

(def target-source (slurp (fixture-path "target")))

(defn loaded-world []
  (-> (files/initial-world)
      (files/load-file (fixture-path "one") 'one)
      (files/load-file (fixture-path "ten") 'ten)
      (files/load-file (fixture-path "goal") 'goal)))

(defn run [] (files/query (loaded-world) target-source))

(defn -main [& _]
  (try
    (let [world (run)
          answer (net/network-cell-strongest (:net world) (get-in world [:compiled :cell]))]
      (prn {:decision (observation/base answer)
            :file (get-in world [:files 'ten :path])
            :support (datum/support-of answer)}))
    (finally (shutdown-agents))))
