(ns examples.lain.static-call-graph-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [examples.lain.static-call-graph.analysis :as analysis]
            [examples.lain.static-call-graph.extension :as call-graph]
            [examples.lain.static-call-graph.main :as example]
            [graph.xr-runtime :as xr]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.operators.environment :as environment]
            [propagators.datastructures.compound-object :as obj]
            [propagators.semantic-trace :as semantic-trace]))

(def synthetic-analysis
  {:var-definitions
   [{:ns 'sample :name 'root :filename "sample.clj" :row 1 :col 1}
    {:ns 'sample :name 'left :filename "sample.clj" :row 2 :col 1}
    {:ns 'sample :name 'right :filename "sample.clj" :row 3 :col 1}
    {:ns 'sample :name 'unused :filename "sample.clj" :row 4 :col 1}]
   :var-usages
   [{:from 'sample :from-var 'root :to 'sample :name 'left}
    {:from 'sample :from-var 'root :to 'sample :name 'left}
    {:from 'sample :from-var 'left :to 'sample :name 'right}
    {:from 'sample :from-var 'right :to 'sample :name 'right}
    {:from 'sample :from-var 'right :to 'clojure.core :name 'inc}]})

(deftest builds-deterministic-project-internal-transitive-graph
  (let [graph (analysis/build-call-graph synthetic-analysis "sample/root")]
    (is (semantic-trace/semantic-trace-graph? graph))
    (is (= #{'sample/root 'sample/left 'sample/right}
           (set (keys (:nodes graph)))))
    (is (= [['sample/left 'sample/right]
            ['sample/root 'sample/left]]
           (:edges graph)))
    (is (= 2 (count (:edges (xr/graph->json graph)))))))

(deftest rejects-invalid-or-missing-targets
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"qualified var"
                        (analysis/build-call-graph synthetic-analysis "root")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"was not found"
                        (analysis/build-call-graph synthetic-analysis
                                                   "sample/missing"))))

(deftest source-roots-stay-under-project-root
  (is (seq (analysis/resolve-source-roots "." ["propagators"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"escapes"
                        (analysis/resolve-source-roots "." ["../"]))))

(deftest effect-normalization-validates-lain-inputs
  (let [effect (call-graph/call-graph-effect ".")]
    (is (= :ready
           (:status
            (environment/normalize-effect-invocation
             effect {} ["propagators" "propagators.runner/run-network" 0]))))
    (is (= :invalid
           (:status
            (environment/normalize-effect-invocation
             effect {} [["propagators"] "propagators.runner/run-network" 0]))))
    (is (= :invalid
           (:status
            (environment/normalize-effect-invocation
             effect {} ["propagators" "run-network" 0]))))
    (is (= :invalid
           (:status
            (environment/normalize-effect-invocation
             effect {} ["propagators" "propagators.runner/run-network" :new]))))))

(deftest handler-returns-a-failed-receipt-for-a-missing-target
  (let [session {:preserved true}
        result (call-graph/handle-call-graph
                nil
                session
                {:boundary/payload
                 {:project-root "."
                  :source-roots ["examples/lain/static_call_graph"]
                  :target "examples.lain.static-call-graph.analysis/missing"
                  :revision 0}})]
    (is (= session (:state result)))
    (is (= :failed (get-in result [:receipt :status])))
    (is (seq (get-in result [:receipt :diagnostics])))))

(deftest result-projection-selects-successful-call-graph-receipt
  (let [graph (analysis/build-call-graph synthetic-analysis "sample/root")
        receipt (obj/compound-object
                 {"receipt"
                  {:boundary/kind call-graph/effect-kind
                   :boundary/status :analyzed
                   :boundary/id [:analysis 0]
                   :revision 0
                   :graph graph}})]
    (is (= graph (call-graph/call-graph-result receipt)))
    (is (value/nothing?
         (call-graph/call-graph-result
          (obj/compound-object
           {"receipt"
            {:boundary/kind call-graph/effect-kind
             :boundary/status :failed}}))))))

(deftest analyzes-run-network-and-serializes-for-xr
  (let [{:keys [graph summary]}
        (analysis/analyze-call-graph
         {:project-root "."
          :source-roots ["propagators"]
          :target "propagators.runner/run-network"})
        root 'propagators.runner/run-network
        edges (set (:edges graph))
        json (xr/graph->json graph)]
    (is (contains? (:nodes graph) root))
    (is (contains? edges
                   [root 'propagators.runner/network-iterator]))
    (is (contains? edges
                   [root 'propagators.runner/*advance-transform*]))
    (is (contains? edges
                   [root 'propagators.helpers.task-queue/into-queue]))
    (is (not-any? #(= 'clojure.core/trampoline (second %)) edges))
    (is (= (:node-count summary) (count (:nodes json))))
    (is (= (:edge-count summary) (count (:edges json))))))

(deftest example-launcher-publishes-call-graph-page-and-endpoint
  (let [server (example/start-example {:port 0 :xr-port 0})]
    (try
      (let [session (:session server)
            effects (:effects (runtime/read-xr-effects @session))
            launch (last (filter #(= :xr/launch-trace (:boundary/kind %))
                                 effects))
            graph (get-in launch [:boundary/payload :graph])
            xr-port (get-in server [:xr :port])
            page (slurp (str "http://127.0.0.1:" xr-port "/relationships"))
            endpoint (slurp (str "http://127.0.0.1:" xr-port
                                 "/api/relationships"))]
        (is (some? launch))
        (is (semantic-trace/semantic-trace-graph? graph))
        (is (contains? (:nodes graph) 'propagators.runner/run-network))
        (is (seq (:edges graph)))
        (is (or (str/includes? page "<!doctype html>")
                (str/includes? page "<html")))
        (is (str/includes? endpoint "propagators.runner/run-network")))
      (finally
        ((:close server))))))
