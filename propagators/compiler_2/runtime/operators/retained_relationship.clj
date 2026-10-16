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
            [propagators.stdlib.supported-procedure :as supported]))

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
    (case mode
      :roots (snapshots/snapshot-roots snapshot roots)
      :dataflow (snapshots/snapshot-dataflow snapshot (snapshots/snapshot-value snapshot root))
      :semantic (apply trace/graph-union (map #(snapshots/snapshot-semantic-graph snapshot %) roots))
      :children (snapshots/snapshot-children snapshot (call-parent snapshot root))
      (throw (ex-info "Unknown relationship projection" {:mode mode})))
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
      (let [base (supported/from-primitive
                  (prop/primitive-propagator ::project (partial project mode roots)))
            [installed props selected] (supported/install base network arguments output)]
        [(declarations/register-props installed props) props selected]))}))

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
