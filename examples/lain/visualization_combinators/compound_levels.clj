(ns examples.lain.visualization-combinators.compound-levels
  "Example-only observation adapters. File reload rebuilds the published views."
  (:require [graph.xr-server :as server]
            [propagators.cells.cell :as cell]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.session.extension :as session]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.experimental.visualization.extension :as visualization]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.visualization.observation :as observation]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.relationship-observer :as observer]))

(def cell-reference?
  (observation/value-operator
   ::cell-reference? 1
   (fn [network [reference]]
     (when-not (data/reference? reference)
       (throw (ex-info "cell-ref? expects a source reference" {:value reference})))
     (let [entry (observer/node-entry network
                                     [(:source/path reference) (:source/cell reference)])]
       (when-not entry
         (throw (ex-info "cell-ref? source does not exist" {:reference reference})))
       (cell/cell? entry)))))

(def snapshot-extension
  (session/extension-bundle
   {:id ::snapshots
    :bindings
    [['cell-ref? cell-reference?]
     ['body-snapshot
      (observation/value-operator
       ::body-snapshot 2
       (fn [network [reference _completed-result]]
         (dataflow/child-dataflow-graph
          network [(:source/path reference) (:source/cell reference)])))]]
    :effects []}))

(def options {:extensions [visualization/ttms-extension snapshot-extension]})
(def example "examples/lain/visualization_combinators/compound_levels.lain")

(defn -main [& [port host]]
  (let [session (loader/load-session-from-file example options)
        http (server/start-server (Long/parseLong (or port "45670"))
                                  session (or host server/default-host))
        watcher (loader/watch-file! session example options)
        close (fn []
                ((:close watcher))
                ((:close http))
                (runtime/stop-clocks! session))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable close))
    (println "Compound levels:" (str "http://" (get-in http [:host]) ":"
                                     (get-in http [:port]) "/relationships"))
    @(promise)))
