(ns examples.lain.amb.knowledge
  "Positive graph knowledge inferred by an opt-in Lain feedback network.
  The BFS oracle is verification only. Dataflow facts come from installed ports."
  (:require [clojure.java.io :as io]
            [clojure.set :as sets]
            [examples.lain.relational-datalog.runtime :as relation]
            [propagators.cells.value :as value]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as session]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.stdlib.supported-procedure :as supported]))

(def source (slurp (io/resource "examples/lain/amb/knowledge.lain")))

(defn edges-in [domain edges]
  (let [nodes (set domain) facts (set edges)]
    (if (and (seq domain) (= (count domain) (count nodes))
             (every? #(and (vector? %) (= 2 (count %))
                           (every? nodes %)) facts))
      facts
      (throw (ex-info "Knowledge needs distinct nodes and in-domain binary edges"
                      {:domain domain :edges edges})))))

(defn extensions [reachable edges]
  (into #{} (for [[a b] reachable [middle c] edges :when (= b middle)] [a c])))

(def extend-operator
  (operator/propagator-operator
   {:name ::extend-reach
    :messages
    (fn [network {:keys [inputs outputs]}]
      (if (= 2 (count inputs))
        (let [[reachable edges] (mapv #(net/network-cell-strongest network %) inputs)]
          (if (or (value/unusable? reachable) (value/unusable? edges))
            []
            [(message/message (first outputs)
                              (relation/relation
                               (extensions (relation/facts reachable) (relation/facts edges))))]))
        (throw (ex-info "extend-reach expects reachable and edges" {:inputs inputs}))))}))

(defn session-extension [edge-id]
  (extension/extension-bundle
   {:id ::inference
    :bindings [['edges (env/cell-binding edge-id)] ['extend-reach extend-operator]]
    :effects []}))

(defn infer [domain edges]
  (let [edges (edges-in domain edges) started (System/nanoTime)
        edge-id (ids/new-node-id) initial (relation/relation edges)
        state (extension/install-session-extension
               (update (session/empty-state) :program/net nb/install-cell edge-id initial initial)
               (session-extension edge-id) {})
        compiled (compiler/compile-source source (:program/env state)
                                         {:net (:program/net state)
                                          :environment-props (:program/props state)})
        at (System/nanoTime)
        result (relation/completed (relation/run (:net compiled) (:props compiled)))]
    (assoc result :compiled compiled :domain (vec domain) :edges edges :edges-id edge-id
           :reach (relation/facts (net/network-cell-strongest (:network result) (:cell compiled)))
           :compile-ms (/ (- at started) 1e6))))

(defn add-edges [state more]
  (let [edges (edges-in (:domain state) more)
        [tasks network] (relation/apply-patch nil (message/message (:edges-id state) (relation/relation edges))
                                            (:network state))
        result (relation/completed (relation/run network tasks))]
    (merge state result {:edges (sets/union (:edges state) edges)
                         :reach (relation/facts (net/network-cell-strongest
                                                (:network result) (:cell (:compiled state))))})))

(defn closure-oracle
  "Independent positive-path BFS per node; never used to produce runtime facts."
  [domain edges]
  (let [neighbors (reduce (fn [index [a b]] (update index a (fnil conj #{}) b)) {} edges)]
    (into #{}
          (mapcat (fn [start]
                    (loop [todo (seq (get neighbors start)) seen #{}]
                      (if (empty? todo)
                        (map #(vector start %) seen)
                        (let [node (first todo)]
                          (if (contains? seen node)
                            (recur (next todo) seen)
                            (recur (concat (next todo) (get neighbors node)) (conj seen node))))))))
          domain)))

(defn answers-oracle [domain edges]
  (let [reach (closure-oracle domain edges)]
    (vec (sort (for [a domain b domain c domain
                    :when (and (contains? reach [a b]) (contains? reach [b c]))]
                [a b c])))))

(defn reachable? [facts a b]
  (contains? (relation/facts facts) [a b]))

(def reachable-operator
  (operator/operator-closure
   {:name ::reachable?
    :install (fn [network arguments output]
               (if (= 3 (count arguments))
                 (let [primitive (prop/primitive-propagator
                                  ::reachable? (fn [& values]
                                                 (if (apply value/any-unusable-values? values)
                                                   value/nothing
                                                   (apply reachable? values))))]
                   (supported/install (supported/from-primitive primitive) network arguments output))
                 (throw (ex-info "reachable? expects facts, source and target"
                                 {:arguments arguments}))))}))

(defn query-extension [reach]
  (extension/extension-bundle
   {:id ::query :bindings [['facts (relation/relation reach)] ['reachable? reachable-operator]]
    :effects []}))

(defn dataflow-edges [network ordered-cells]
  (let [nodes (zipmap ordered-cells (range 1 (inc (count ordered-cells))))]
    (into #{}
          (mapcat (fn [[id entry]]
                    (if (prop/prop? entry)
                      (let [{:keys [inputs outputs]} (get (:graph network) id)]
                        (for [from inputs to outputs
                              :when (and (contains? nodes from) (contains? nodes to))]
                          [(get nodes from) (get nodes to)]))
                      [])))
          (:env network))))

(defn dataflow-fixture []
  (let [cells (vec (repeatedly 5 ids/new-node-id))
        [input left right sum output] cells
        installers [((prop/concrete-primitive-propagator ::inc inc) input left)
                    ((prop/concrete-primitive-propagator ::double #(* 2 %)) input right)
                    ((prop/concrete-primitive-propagator ::sum +) left right sum)
                    ((prop/concrete-primitive-propagator ::inc inc) sum output)]
        installed (reduce (fn [{:keys [network tasks]} install]
                            (let [[id network] (install network)]
                              {:network network :tasks (conj tasks id)}))
                          {:network (nb/seed-cell (nb/install-cells cells) input 3) :tasks []} installers)
        result (runner/run-network (:tasks installed) (:network installed))]
    (if (= :completed (:status result))
      {:id :dataflow-fork-join :domain [1 2 3 4 5]
       :edges (dataflow-edges (:network result) cells)
       :subject-network (:network result) :subject-cells cells
       :names [:input :left :right :sum :output]
       :values (mapv #(net/network-cell-strongest (:network result) %) cells)}
      (throw (ex-info "Dataflow fixture failed" {} (:error result))))))

(defn scenario [id]
  (case id
    :graph-chain {:id id :domain [1 2 3 4] :edges #{[1 2] [2 3] [3 4]}}
    :graph-cycle {:id id :domain [1 2 3] :edges #{[1 2] [2 3] [3 1]}}
    :graph-disconnected {:id id :domain [1 2 3 4] :edges #{[1 2] [3 4]}}
    :dataflow-fork-join (dataflow-fixture)
    (throw (ex-info "Unknown knowledge scenario" {:scenario id}))))
