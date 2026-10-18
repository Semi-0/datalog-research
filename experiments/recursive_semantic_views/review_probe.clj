(ns experiments.recursive-semantic-views.review-probe
  "Executable replacement for the superseded capture-runner proposal."
  (:require [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.experimental.visualization.data :as data]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.runner :as runner]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.merge :as merge]))

(defn -main [& _]
  (let [output (ids/new-node-id)
        root (capture/fragment {:observation :original :scopes {:root {:nodes {:a 'a}}}})
        child (capture/fragment {:observation :original :scopes {:child {:nodes {:b 'b}}}})
        [tasks installed] (declarations/eval-activation-result
                           [(merge/submit output :root (merge/fragment-evidence root #{} #{}))
                            (merge/submit output :child (merge/fragment-evidence child #{} #{}))]
                           net/empty-net)
        settled (runner/completed-network (runner/run-network tasks installed))
        captured (data/payload (net/network-cell-strongest settled output))]
    (assert (= #{:root :child} (set (keys (capture/scopes captured)))))
    (println "Ordinary runner capture composition passed")))
