(ns propagators.layered.procedure
  "Common declaration helpers. Specialized layer semantics belong to callers."
  (:require [propagators.datastructures.compound-object.core :as obj]
            [propagators.cells.value :as value]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.message :as message]
            [propagators.propagator :as prop]))

(defn ports [inputs outputs]
  (let [[operator previous live & arguments] inputs]
    {:operator-id operator :previous-output-id previous
     :live-result-id live :argument-ids (vec arguments)
     :layer-result-id (first outputs)}))

(defn read-arguments [network ports]
  (mapv #(net/network-cell-strongest network %) (:argument-ids ports)))

(defn publish [network layer output bank]
  (let [install (requiring-resolve 'propagators.layered/p:layer)]
    (second ((install layer output bank) network))))

(defn project-bases [network arguments]
  (reduce (fn [{:keys [net ids]} argument-id]
            (let [id (ids/new-node-id)
                  argument (net/network-cell-strongest net argument-id)
                  base (if (obj/slot-cell-id argument :base)
                         (obj/slot-value argument :base)
                         value/nothing)]
              {:net (nb/install-cell net id base base) :ids (conj ids id)}))
          {:net network :ids []} arguments))

(defn base [closure]
  (assoc closure
   :f (fn [closure-net inputs outputs network]
        (let [ports (ports inputs outputs)
              projected (project-bases network (:argument-ids ports))
              declared ((:f closure) closure-net (:ids projected)
                        outputs (:net projected))]
          (publish declared :base (:layer-result-id ports)
                   (:live-result-id ports))))))

(defn argument-layer
  "Compose argument observation, caller-owned eligibility, and publication.
  `combine` receives a vector of whole datums. No output feedback is wired."
  [layer interested? combine]
  {:net net/empty-net
   :f (fn [_ inputs outputs network]
        (let [ports (ports inputs outputs)
              arguments (read-arguments network ports)]
          (if (interested? arguments)
            (let [installer
                  (prop/construct-propagator
                   [:layered/arguments layer]
                   (fn [_inputs _outputs current]
                     [(message/message
                       (:layer-result-id ports)
                       (combine (read-arguments current ports)))])
                   (:argument-ids ports)
                   [(:layer-result-id ports)])
                  installed (second (installer network))]
              (publish installed layer (:layer-result-id ports)
                       (:live-result-id ports)))
            network)))})
