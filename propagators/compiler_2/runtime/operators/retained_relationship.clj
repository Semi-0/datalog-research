(ns propagators.compiler-2.runtime.operators.retained-relationship
  "Public relationship adapters; projections run in retained private topology."
  (:require [propagators.cells.error-layer :as errors]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.operators.network-observation :as snapshots]
            [propagators.compiler-2.runtime.operators.retained-observation :as retained]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.propagator :as prop]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.capture :as capture]
            [propagators.experimental.visualization.ttms :as ttms]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.network :as net]
            [propagators.message :refer [message]]))

(defn- call-parent [snapshot root]
  (let [candidate (snapshots/snapshot-value snapshot root)]
    (if (data/reference? candidate)
      [(:source/path candidate) (:source/cell candidate)]
      (let [graph (snapshots/snapshot-semantic-graph snapshot root)
            root-key [[:outer] root]
            seeds (if (contains? (:nodes graph) root-key)
                    #{root-key}
                    (get (:node-aliases graph) root-key))
            incoming (group-by second (:edges graph))]
        (loop [pending (set seeds) seen #{}]
          (let [calls (filter #(and (= :propagator (get (:node-kinds graph) %))
                                   (not (contains? #{"->" "<->" "copy"}
                                                   (str (get (:nodes graph) %))))) pending)]
            (cond
              (= 1 (count calls)) (first calls)
              (seq calls) (throw (ex-info "Child trace requires one explicit call occurrence" {:calls (set calls)}))
              (empty? pending) (throw (ex-info "No call produces this cell; use an occurrence reference" {:root root}))
              :else (recur (into #{} (comp (mapcat #(get incoming %)) (map first) (remove seen)) pending)
                           (into seen pending)))))))))

(defn- project [mode roots snapshot root]
  (try
    (let [graph (case mode
                  :roots (snapshots/snapshot-roots snapshot roots)
                  :dataflow (let [observed (snapshots/snapshot-value snapshot root)]
                              (if (value/unusable? observed)
                                observed
                                (snapshots/snapshot-dataflow snapshot observed)))
                  :semantic (apply trace/graph-union (map #(snapshots/snapshot-semantic-graph snapshot %) roots))
                  :children (snapshots/snapshot-children snapshot (call-parent snapshot root))
                  (throw (ex-info "Unknown relationship projection" {:mode mode})))]
      (if (value/unusable? graph)
        graph
        (capture/capture-fragment snapshot {:graph graph :scope :root :refinement :initial})))
    (catch Exception error
      (errors/attach value/contradiction
                     #{{:operation :relationship/trace :mode mode
                        :exception/class (.getName (class error))
                        :exception/message (.getMessage error)
                        :exception/data (ex-data error)}}))))

(defn callback [mode roots]
  (operator/operator-closure
   {:name [::sample mode roots]
    :application-installer
    (fn [network arguments output _context]
      (let [id (gur/stable-node-id [::sample mode roots arguments output])
            activation
            (fn [_ _ current]
              (let [contents (mapv #(net/network-cell-content current %) arguments)
                    values (mapv #(data/payload (net/network-cell-strongest current %)) arguments)]
                (cond
                  (some value/nothing? values) (data/state-messages output contents)
                  (some value/contradiction? values)
                  [(message output (data/supported id value/contradiction (set arguments) contents))]
                  :else
                  (let [projected (apply project mode roots values)]
                    [(message output
                              (if (and (value/contradiction? projected)
                                       (seq (errors/errors-of projected))
                                       (some ttms/supported? contents))
                                ;; An error is a supported result, so a later input
                                ;; revision can supersede it just like a graph.
                                (collection/merge-content
                                 (ttms/state-update contents)
                                 (collection/content
                                  {:base projected
                                   :support (apply support/combine
                                                   (map (comp datum/support-of ttms/projection)
                                                        contents))}))
                                (data/supported id projected (set arguments) contents)))]))))
            [pid installed]
            ((prop/construct-propagator id ::sample activation arguments [output]) network)]
        [(declarations/register-props installed [pid]) [pid] output]))}))

(defn- ports [name arguments fallback]
  (when (and (contains? #{'relationship:dataflow 'relationship:children} name)
             (> (count arguments) 2))
    (throw (ex-info (str name " expects one input and optional output") {})))
  (case (count arguments)
    0 (throw (ex-info (str name " expects a seed and optional output cell") {}))
    1 [(vec arguments) fallback]
    [(vec (butlast arguments)) (last arguments)]))

(defn install [network mode roots output context]
  (let [callback-id (gur/stable-node-id [::callback mode roots output])
        callable (callback mode roots)
        prepared (declarations/install-cell network callback-id callable callable)]
    (retained/install prepared [(first roots) callback-id] output context roots)))

(defn operator [name mode]
  (operator/operator-closure
   {:name name
    :input-selector (fn [arguments fallback _] (first (ports name arguments fallback)))
    :output-selector (fn [arguments fallback] [(second (ports name arguments fallback))])
    :application-installer
    (fn [network arguments fallback context]
      (let [[roots output] (ports name arguments fallback)]
        (install network mode roots output context)))}))

(defn roots-operator [] (operator 'relationship:roots :roots))
(defn semantic-operator [] (operator 'relationship:semantic :semantic))
(defn roots-semantic-operator [] (operator 'relationship:roots-semantic :semantic))
(defn children-operator [] (operator 'relationship:children :children))

(defn dataflow-operator [] (operator 'relationship:dataflow :dataflow))
