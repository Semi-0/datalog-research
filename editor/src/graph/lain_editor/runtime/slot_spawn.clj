(ns graph.lain-editor.runtime.slot-spawn
  "Derive unresolved callable slots from current application declarations."
  (:require [clojure.string :as str]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.experimental.visualization.data :as data]
            [propagators.graph :as graph]
            [propagators.network :as net]))

(defn- slot-source [binding arity]
  (let [inputs (mapv #(symbol (str "input-" (inc %))) (range arity))]
    (str "(define " binding "\n"
         "  (network " (pr-str inputs) "\n"
         "    (define out)\n"
         "    out))")))

(defn- unresolved-calls [network]
  (filter (fn [{:keys [operator-id]}]
            (and operator-id
                 (value/nothing?
                  (data/payload (net/network-cell-strongest network operator-id)))))
          (application/application-topologies network)))

(defn plan
  "Plan one stub per unresolved, root-addressable callable binding.
  Ordinary missing inputs are excluded. Conflicting call arities reject the plan."
  [network environment]
  (let [names (env/binding-names network)
        topology (net/net-graph network)
        groups (group-by :operator-id (unresolved-calls network))
        classified
        (mapv (fn [[id calls]]
                (let [binding (get names id)
                      addressable? (and (symbol? binding)
                                        (= id (env/resolve-binding-id network environment binding)))
                      reason (cond
                               (not addressable?) "not-root-addressable"
                               (seq (graph/node-input-ids (graph/get-node topology id))) "pending-producer"
                               :else nil)]
                  {:binding binding :id id :calls calls :reason reason}))
              groups)
        eligible (sort-by (comp str :binding) (remove :reason classified))
        slots
        (mapv (fn [{:keys [binding calls]}]
                (let [arities (into (sorted-set) (map #(count (:argument-ids %))) calls)]
                  (when-not (= 1 (count arities))
                    (throw (ex-info (str "Cannot spawn slot " binding ": conflicting input counts " (vec arities))
                                    {:binding binding :arities (vec arities)})))
                  {:name (str binding) :inputCount (first arities) :callCount (count calls)}))
              eligible)]
    {:source (str/join "\n\n" (map #(slot-source (:name %) (:inputCount %)) slots))
     :slots slots
     :skipped (mapv (fn [{:keys [binding reason]}]
                      {:name (when binding (str binding)) :reason reason})
                    (sort-by (comp str :binding) (filter :reason classified)))}))
