(ns examples.lain.amb.file-premise-session
  "Bootstrap the extension; file composition itself is declared in Lain."
  (:require [clojure.java.io :as io]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.experimental.file-premise.session :as session]
            [propagators.runner :as runner]))

(def source
  (slurp (io/resource "examples/lain/amb/file_premise_selection/composition.lain")))

(defn declare-composition [text]
  (let [initial (session/install (state/empty-state))
        compiled (compiler/compile-source text (:program/env initial)
                                          {:net (:program/net initial)
                                           :environment-props (:program/props initial)})
        network (runner/completed-network
                 (runner/run-network (:props compiled) (:net compiled)))]
    (assoc initial :program/net network)))

(defn run [] (effects/drain-environment-effects (declare-composition source)))

(defn -main [& _]
  (try
    (let [result (run)]
      (doseq [delivery (vals (:environment/effects result))]
        (prn (:receipt delivery))))
    (finally (shutdown-agents))))
