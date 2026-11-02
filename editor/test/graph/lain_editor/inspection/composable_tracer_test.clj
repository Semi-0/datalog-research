(ns graph.lain-editor.inspection.composable-tracer-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [graph.lain-editor.server :as server]
            [graph.lain-editor.runtime.session :as runtime]
            [graph.lain-editor.runtime.display :as display]
            [propagators.compiler-2.model.env :as env]
            [propagators.network :as net]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.graph-facts :as facts]))

(defn- with-program [source check]
  (let [editors (atom {})]
    (try
      (let [run (server/command! editors
                  {:op "run" :pane "modifiers" :extensions []
                   :source (str "(load-lain \"propagators/compiler_2/runtime/lain/tracers.lain\")\n" source)})]
        (check editors run))
      (finally (server/command! editors {:op "close" :pane "modifiers"})))))

(defn- inspect-value [editors run cell]
  (server/command! editors
    {:op "inspect" :pane "modifiers" :generation (:generation run)
     :display {:cell cell :mode "value"}}))

(deftest built-in-empty-list-and-selection-normalization
  (with-program
    (str "(define empty-selection (modifier:select (list)))\n"
         "(define a (modifier:select (list \"user\" \"runtime\" \"user\")))\n"
         "(define b (modifier:select (list \"runtime\" \"user\")))")
    (fn [editors run]
      (let [empty-selection (inspect-value editors run "empty-selection")
            a (inspect-value editors run "a") b (inspect-value editors run "b")]
        (is (= "ready" (:status empty-selection)))
        (is (= {:kind :select :selection #{}} (edn/read-string (:value empty-selection))))
        (is (= {:kind :select :selection #{"user" "runtime"}} (edn/read-string (:value a))))
        (is (= (edn/read-string (:value a)) (edn/read-string (:value b))))))))

(deftest open-tail-is-pending-and-resolves-through-current-live-fields
  (with-program
    (str "(define pending)\n"
         "(define selected (modifier:select (cons \"user\" pending)))")
    (fn [editors run]
      (let [before (inspect-value editors run "selected")]
        (is (= "waiting" (:status before)))
        (is (= "the-nothing" (:value before))))
      (let [extended (server/command! editors
                       {:op "extend" :pane "modifiers" :generation (:generation run)
                        :source "(-> (list \"runtime\") pending)"})
            after (inspect-value editors extended "selected")]
        (is (= "ready" (:status after)))
        (is (= {:kind :select :selection #{"user" "runtime"}} (edn/read-string (:value after))))))))

(deftest unresolved-selector-head-does-not-become-an-empty-selection
  (with-program
    "(define pending) (define selected (modifier:select (list pending)))"
    (fn [editors run]
      (is (= "waiting" (:status (inspect-value editors run "selected")))))))

(deftest configured-views-reuse-the-callers-observation
  (let [prepared (runtime/prepare
                   (str "(load-lain \"propagators/compiler_2/runtime/lain/tracers.lain\")\n"
                        "(define left 3) (define result (+ left 1))\n"
                        "(define a ((tracer:roots modifier:high-level modifier:with-values) result))\n"
                        "(define b ((tracer:roots modifier:with-values modifier:high-level) result))") [])
        session (:session prepared)]
    (try
      (let [state @session network (:program/net state)
            read-value (fn [name]
                         (display/payload
                          (net/network-cell-strongest network
                            (env/resolve-binding-id network (:program/env state) name))))
            a (read-value 'a) b (read-value 'b)]
        (is (capture/fragment? a))
        (is (capture/fragment? b))
        (is (some? (capture/observation-id a)))
        (is (= (capture/observation-id a) (capture/observation-id b)))
        (is (seq (:nodes (capture/root-graph a))))
        (is (facts/equivalent? (capture/root-graph a) (capture/root-graph b))))
      (finally (runtime/close-session! session)))))

(deftest expansion-does-not-leak-declarations-between-shared-views
  (with-program
    (str "(define combine (network [x] (+ x 1)))\n"
         "(define input 3) (define result (combine input))\n"
         "(define a ((tracer:roots modifier:high-level modifier:with-values) result))\n"
         "(define b ((tracer:roots modifier:high-level "
         "(modifier:select (list \"input\"))) result))")
    (fn [editors run]
      (let [request {:op "inspect" :pane "modifiers" :generation (:generation run)
                     :display {:cell "a" :mode "graph" :direction "both"}}
            a (:graph (server/command! editors request))
            call (first (filter #(and (= "combine" (:label %))
                                      (= "propagator" (:kind %))) (:nodes a)))
            read-b #(-> (server/command! editors (assoc-in request [:display :cell] "b")) :graph)
            b-facts (fn []
                      (let [state @(:session (get @editors "modifiers"))
                            network (:program/net state)
                            id (env/resolve-binding-id network (:program/env state) 'b)]
                        (capture/root-graph
                         (display/payload (net/network-cell-strongest network id)))))]
        (is (some? call) (pr-str (:nodes a)))
        (is (= #{"input"} (set (map :label (:nodes (read-b))))))
        (is (not (facts/enabled? (b-facts) :with-values)))
        (when call
          (let [child (:graph (server/command! editors (assoc request :parent (:id call))))]
            (is (some #(= "+" (:label %)) (:nodes child)))))
        (is (= #{"input"} (set (map :label (:nodes (read-b))))))
        (is (not (facts/enabled? (b-facts) :with-values)))))))
