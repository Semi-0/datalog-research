(ns propagators.experimental.functional-web-migration-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.visualization.trace :as trace]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.semantic-trace :as semantic]))

(def options
  {:extensions
   [(extension/extension-bundle
     {:id ::tracer
      :bindings [['relationship:roots (trace/roots-operator)]
                 ['relationship:dataflow (trace/dataflow-operator)]
                 ['relationship:semantic (trace/semantic-operator)]
                 ['relationship:roots-semantic (trace/roots-semantic-operator)]]
      :effects []})]})

(defn bounded [f]
  (let [steps (atom 0)
        started (System/nanoTime)]
    (binding [runner/*advance-transform*
              (fn [advance]
                (fn [state continuations]
                  (if (or (> (swap! steps inc) 10000)
                          (> (- (System/nanoTime) started) 5000000000))
                    (throw (ex-info "Functional web composition did not quiesce"
                                    {:steps @steps}))
                    (advance state continuations))))]
      (f))))

(defn binding-value [state name]
  (data/payload
   (net/network-cell-strongest
    (:program/net state)
    (env/resolve-binding-id (:program/net state) (:program/env state) name))))

(deftest functional-top-level-tracing-quiesces-and-reruns-idempotently
  (let [session (bounded #(loader/load-session-from-source
                          "(define input 2) (define copied)
                           (<-> input copied) (define roots) (define projected)
                           (relationship:roots input roots)
                           (relationship:dataflow roots projected)" options))
        state @session
        network (:program/net state)
        observers (keep (fn [[id p]]
                          (if (and (prop/prop? p)
                                   (= :relationship/observer (prop/prop-name p)))
                            id
                            nil))
                        (net/net-env network))
        rerun (bounded #(nb/run-propagators network observers))]
    (is (= 2 (binding-value state 'copied)))
    (is (semantic/semantic-trace-graph? (binding-value state 'projected)))
    (is (seq (:nodes (binding-value state 'projected))))
    (is (= (net/net-env network) (net/net-env rerun)))
    (is (empty? (:runtime/errors state)))))

(deftest functional-body-records-decorated-observer-declarations
  (let [session (bounded #(loader/load-session-from-source
                          "(define sample
                             (network (input)
                               (let-cell [copied roots projected]
                                 (<-> input copied)
                                 (relationship:roots input roots)
                                 (relationship:dataflow roots projected)
                                 projected)))
                           (define result (sample 2))" options))
        state @session]
    (is (semantic/semantic-trace-graph? (binding-value state 'result)))
    ;; A local input resolves to its caller. Root tracing shows the outer
    ;; call boundary, and keeps its child-owned topology opaque.
    (is (contains? (set (vals (:nodes (binding-value state 'result)))) 'sample))
    (is (seq (datum/support-of
              (net/network-cell-strongest
               (:program/net state)
               (env/resolve-binding-id (:program/net state) (:program/env state) 'result)))))
    (is (empty? (:runtime/errors state)))))

(def reload-prefix
  "(define increment (network (x) (+ x 1)))
   (define input 2) (define output) (define roots) (define projected)")

(def reload-trace
  "(relationship:roots input output roots)
   (relationship:dataflow roots projected)")

(deftest external-edit-replaces-the-complete-functional-traced-chain
  (let [file (java.io.File/createTempFile "functional-trace-" ".lain")
        replacement (java.io.File/createTempFile "functional-replacement-" ".lain")]
    (try
      (spit file (str reload-prefix "(-> (increment input) output)" reload-trace))
      (spit replacement
            (str reload-prefix "(define intermediate)
                 (-> (increment input) intermediate)
                 (-> (increment intermediate) output)" reload-trace))
      (let [session (loader/load-session-from-file file options)
            before @session
            loaded (promise)
            watcher (loader/watch-file! session file
                       (assoc options :interval-ms 25
                              :on-reload #(deliver loaded %)
                              :on-error #(deliver loaded %)))]
        (try
          (let [process (.start (ProcessBuilder. ^java.util.List
                                  ["cp" (.getPath replacement) (.getPath file)]))]
            (is (= 0 (.waitFor process))))
          (is (map? (deref loaded 20000 :timeout)))
          (let [after @session
                old (binding-value before 'projected)
                fresh (binding-value after 'projected)]
            (is (= 3 (binding-value before 'output)))
            (is (= 4 (binding-value after 'output)))
            (is (= 1 (count (filter #{:propagator} (vals (:node-kinds old))))))
            (is (= 2 (count (filter #{:propagator} (vals (:node-kinds fresh))))))
            (is (not= (set (keys (:nodes old))) (set (keys (:nodes fresh)))))
            (is (empty? (:runtime/errors after))))
          (finally ((:close watcher)))))
      (finally (.delete file) (.delete replacement)))))

(deftest direct-semantic-retains-ttms-publication-and-refreshes-appended-calls
  (let [session (bounded #(loader/load-session-from-source
                          "(define input 2) (define output (+ input 1))
                           (define roots (relationship:roots input))
                           (define projected (relationship:roots-semantic input))" options))
        state @session id (env/resolve-binding-id (:program/net state) (:program/env state) 'projected)
        strongest (net/network-cell-strongest (:program/net state) id)]
    (is (seq (datum/support-of strongest)))
    (is (contains? (set (vals (:nodes (binding-value state 'projected)))) '+))
    (is (nil? (get-in (bounded #(loader/load-source! session "(define doubled (* output 2))"))
                     [:load-result :error])))
    (is (contains? (set (vals (:nodes (binding-value @session 'projected)))) '*))
    (is (seq (datum/support-of (net/network-cell-strongest (:program/net @session) id))))))
