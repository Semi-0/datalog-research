(ns propagators.layered.dispatcher
  "Layer-blind topology declaration and result assembly."
  (:require [propagators.application :as application]
            [propagators.cells.value :as value]
            [propagators.dispatch :as dispatch]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn declare-layer [procedure ports application-net]
  (let [{:keys [operator-id previous-output-id live-result-id
                argument-ids layer-result-id]} ports
        inputs (into [operator-id previous-output-id live-result-id] argument-ids)
        declared ((:f procedure) (:net procedure) inputs
                  [layer-result-id] application-net)]
    (if (net/net? declared)
      declared
      (throw (ex-info "Layer procedure must return a Net"
                      {:returned declared})))))

(defn- install-layer [state entry]
  (let [[layer procedure] entry
        result-id (ids/new-node-id)
        prepared (nb/install-cell (:net state) result-id)]
    (cond
      (value/nothing? procedure) state
      (and (map? procedure) (fn? (:f procedure)) (net/net? (:net procedure)))
      (-> state
          (assoc :net
                 (declare-layer procedure
                                (assoc (:ports state) :layer-result-id result-id)
                                prepared))
          (assoc-in [:results layer] result-id))
      :else
      (throw (ex-info "Invalid layer procedure"
                      {:layer layer :procedure procedure})))))

(defn- assemble [network bank results]
  (let [declared (obj/accessor-slot-keys (net/network-cell-value network bank))]
    (reduce-kv (fn [object layer result]
                 (if (contains? declared layer)
                   (nb/add-named-cell object layer
                                      (net/network-cell-value network result))
                   object))
               (net/net-with-dict net/empty-net {:slot-index {}})
               results)))

(defn- assembly-policy [results]
  (fn [network bank output]
    (let [installer
          (prop/construct-propagator
           :layered/assemble
           (fn [_inputs _outputs current]
             [(message/message output (assemble current bank results))])
           (into [bank] (vals results)) [output])
          [id installed] (installer network)]
      [[id] installed])))

(defn build [prepared]
  (let [{:keys [ports cell-specs procedures]} prepared]
    (application/build-branch-application
     {:cell-specs cell-specs
      :install-branches
      (fn [network frame]
        (let [ports (assoc ports :live-result-id (:result-bank-id frame))
              initial-ids (set (keys (net/net-env network)))
              declared (reduce install-layer {:net network :ports ports :results {}}
                               (sort-by (comp pr-str key) procedures))
              installed (:net declared)
              tasks (->> (net/net-env installed)
                         (keep (fn [[id entry]]
                                 (if (and (not (contains? initial-ids id))
                                          (prop/prop? entry))
                                   id
                                   nil)))
                         (sort-by pr-str)
                         vec)]
          {:net installed :branch-prop-ids tasks :results (:results declared)
           :active-layers (vec (sort-by pr-str (keys procedures)))}))
      :reducer-install
      (fn [{:keys [results result-bank-id reduced-out-id]}]
        (dispatch/reduce-results (assembly-policy results)
                                 result-bank-id reduced-out-id))
      :trace
      (fn [{:keys [active-layers result-bank-id reduced-out-id]}]
        {:trace/type :layered :result-bank-id result-bank-id
         :slots active-layers :reduced-out-id reduced-out-id})})))

(defn evaluate [prepared]
  (application/run-reduced-application (build prepared)))
