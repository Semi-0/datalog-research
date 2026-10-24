(ns graph.lain-editor.inspection.tracer-directions-test
  (:require [clojure.test :refer [deftest is testing]]
            [graph.lain-editor.inspection.core :as inspection]
            [graph.lain-editor.runtime.session :as runtime]))

(deftest retained-directional-tracers-inspect-pending-compound-calls
  (doseq [[tracer root producer?]
          [["trace:upstream" "consumed" true]
           ["trace:downstream" "queue" true]
           ["trace:downstream-retained" "queue" true]
           ["trace:downstream" "consumed" false]
           ["trace:upstream" "queue" false]]]
    (testing (str tracer " " root)
      (let [editors (atom {})
            source (str "(load-lain \"propagators/compiler_2/runtime/lain/tracers.lain\")\n"
                        "(define queue)\n"
                        "(define consumed (consume-task agents queue))\n"
                        "(define consume-task (network [agents queue] (+ queue 1)))\n"
                        "(display (" tracer " " root "))")]
        (try
          (let [{:keys [generation]} (runtime/run! editors {:pane "pending" :source source})
                request {:pane "pending" :generation generation
                         :display {:cell "@display" :mode "graph" :direction "both"}}
                view (inspection/inspect editors request)
                graph (:graph view)
                call (first (filter #(= "consume-task" (:label %)) (:nodes graph)))]
            (is (= "ready" (:status view)))
            (is (some? (:semanticObservation graph)))
            (is (= (if producer? #{"consume-task" "agents" "queue" "consumed"} #{root})
                   (set (map :label (:nodes graph)))))
            (is (= producer? (some? call)))
            (when producer?
              (let [child (:graph (inspection/inspect editors (assoc request :parent (:id call))))]
                (is (some #(= "+" (:label %)) (:nodes child)))
                (is (= "the-nothing"
                       (:value (first (filter #(= "consumed" (:label %)) (:nodes graph)))))))))
          (finally (runtime/close! editors)))))))
