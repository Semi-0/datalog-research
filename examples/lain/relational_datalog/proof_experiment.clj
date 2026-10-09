(ns examples.lain.relational-datalog.proof-experiment
  "Bounded Lain diamond with separate TTMS proofs and no hidden premise registry."
  (:require [examples.lain.relational-datalog.proof-operators :as operators]
            [examples.lain.relational-datalog.proof-relation :as relation]
            [examples.lain.relational-datalog.runtime :as runtime]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.support-collection :as collection]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(def directory "examples/lain/relational_datalog/")
(def source (slurp (str directory "proofs.lain")))
(def run-options {:evaluate-patches relation/evaluate-patches})

(defn initialize []
  (let [sources (zipmap [:ab :bc :ad :dc] (repeatedly 4 ids/new-node-id))
        bottom (collection/content {:premise-state #{}})
        initial (reduce #(nb/install-cell %1 %2 bottom value/nothing)
                        net/empty-net (vals sources))
        bindings (into (vec (basis/default-bindings))
                       (concat (extension/extension-bindings operators/session-extension)
                               (map (fn [[key id]] [(symbol (name key)) (env/cell-binding id)])
                                    sources)))
        compiled (compiler/compile-expr-with-bindings
                  (parser/parse-string source) bindings {:net initial})
        result (runtime/completed
                (runtime/run (:net compiled) (:props compiled) run-options))]
    (assoc result :sources sources :cell (:cell compiled))))

(defn observation [source epoch status base]
  (let [premises #{{:source source :timestamp epoch :premises-status status}}]
    (collection/content {:base base :support premises :premise-state premises})))

(defn publish
  "Apply a batch of [source-name timestamp status edge] updates, then quiesce."
  ([state updates] (publish state updates {}))
  ([state updates options]
   (let [[tasks network]
         (reduce
          (fn [[tasks network] [key epoch status edge]]
            (let [id (get (:sources state) key)]
              (if (some? id)
                (let [[more next-network]
                      (relation/apply-patch nil (message/message id (observation id epoch status edge))
                                            network)]
                  [(tq/merge-queues tasks more) next-network])
                (throw (ex-info "Unknown experiment source" {:source key})))))
          [tq/empty-queue (:network state)] updates)
         result (runtime/completed (runtime/run network tasks (merge run-options options)))]
     (assoc result :sources (:sources state) :cell (:cell state)))))

(def active-updates
  [[:ab 1 :active [:a :b]] [:bc 1 :active [:b :c]]
   [:ad 1 :active [:a :d]] [:dc 1 :active [:d :c]]])

(defn view [state] (relation/view (:network state) (:cell state)))

(defn summary [state]
  (let [view (view state)
        source-names (into {} (map (fn [[name id]] [id name])) (:sources state))]
    {:facts (:facts view)
     :proofs (mapv (fn [[id proof]]
                    {:cell id :status (:status proof)
                     :sources (into #{} (map #(get source-names (:source %))) (:support proof))})
                  (:proofs view))
     :binding-statuses (frequencies (map :status (vals (operators/bindings-in (:network state)))))
     :steps (:steps state) :ms (:ms state)
     :nodes (count (net/net-env (:network state)))}))

(defn demo []
  (let [empty (initialize)
        active (publish empty active-updates)
        one (publish active [[:bc 2 :retracted value/nothing]])
        none (publish one [[:dc 2 :retracted value/nothing]])
        restored (publish none [[:bc 3 :active [:b :c]]])]
    (mapv (fn [[stage state]] {:stage stage :result (summary state)})
          [[:pending empty] [:active active] [:one-withdrawn one]
           [:both-withdrawn none] [:reactivated restored]])))

(defn -main [& _]
  (try
    (doseq [stage (demo)] (prn stage))
    (shutdown-agents)
    (catch Throwable error
      (.printStackTrace error)
      (shutdown-agents)
      (System/exit 1))))
