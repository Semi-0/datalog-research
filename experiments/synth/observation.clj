(ns experiments.synth.observation
  "Opt-in activation sampling. History is ordinary supported cell data."
  (:require [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.visualization.observation :as observation]
            [propagators.helpers.task-queue :as tq]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.relationship-observer :as observer]
            [propagators.visualizer :as visualizer]))

(defn body-graph [network parent]
  (dataflow/child-dataflow-graph network parent))

(defn declaration-operator [name make-view]
  (operator/propagator-operator
   {:name name
    :activate (fn [_ [source] [out] _] [(message out (make-view out source))])}))

(def capability
  (extension/extension-bundle
   {:id ::views
    :bindings [['body-snapshot
                (observation/value-operator ::body-snapshot 2
                 (fn [network [reference _tick]]
                   (body-graph network [(:source/path reference) (:source/cell reference)])))]
               ['note-window (declaration-operator ::note-window visualizer/cell-window-declaration)]
               ['activation-timeline
                (declaration-operator ::timeline
                 (fn [out source] (visualizer/cell-history-declaration out source source)))]]
    :effects []}))

(defn- occurrence-labels [cache network]
  (let [bindings (get (net/network-dict-entry network gur/name-bindings-key)
                      [:compiler-2 :applications])]
    (when-not (identical? bindings (:bindings @cache))
      (let [keys (mapv #(vector [:outer] (observation/application-propagator %))
                       (application/application-topologies network))]
        (vreset! cache {:bindings bindings :labels (:nodes (observer/snapshot network keys))})))
    (:labels @cache)))

(defn recorder [records tick started]
  (let [cache (volatile! {})]
   (fn [advance]
    (fn [{:keys [network tasks] :as state} continuations]
      (if (tq/queue-empty? tasks)
        (advance state continuations)
        (let [[id _] (tq/pop-task tasks)
              entry (net/network-env-lookup network id)
              label (get (occurrence-labels cache network) [[:outer] id])
              began (System/nanoTime)
              complete (fn [next-state]
                         (when (and (prop/prop? entry) label)
                           (vswap! records conj
                                   {:sample/epoch 0 :sample/tick tick
                                    :sample/node [[:outer] id]
                                    :sample/label (str label)
                                    :sample/time-ms (/ (- began started) 1e6)
                                    :sample/duration-ms (/ (- (System/nanoTime) began) 1e6)
                                    :sample/content tick :sample/strongest tick}))
                         ((:continue continuations) next-state))]
          (advance state (assoc continuations :continue complete))))))))
