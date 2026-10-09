(ns propagators.stdlib.arithmetic.provenance
  (:refer-clojure :exclude [+ - * /])
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as obj]
            [propagators.ids :refer [new-node-id]]
            [propagators.layered.procedure :as layer]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def p:union
  (prop/primitive-propagator
   :stdlib/provenance-union
   (fn [current left right]
     (if (and (set? left) (set? right))
       (set/union
        (if (set? current) current #{})
        left
        right)
       value/nothing))))

(defn- argument-provenance [argument]
  (let [provenance (obj/slot-value argument :provenance)
        base (obj/slot-value argument :base)]
    (set/union (if (set? provenance) provenance #{})
               (value/contradiction-provenance base))))

(defn- result-provenance [network ports]
  (let [previous (net/network-cell-strongest network (:previous-output-id ports))
        current (obj/slot-value previous :provenance)]
    (apply set/union (if (set? current) current #{})
           (map argument-provenance (layer/read-arguments network ports)))))

(defn arithmetic-provenance-closure []
  {:net net/empty-net
   :f (fn [_ inputs outputs network]
        (let [ports (layer/ports inputs outputs)
              arguments (layer/read-arguments network ports)]
          (if (some #(contains? (obj/public-slot-keys %) :provenance) arguments)
            (let [installer
                  (prop/construct-propagator
                   :stdlib/arithmetic-provenance
                   (fn [_inputs _outputs current]
                     [(message/message (:layer-result-id ports)
                                       (result-provenance current ports))])
                   (conj (:argument-ids ports) (:previous-output-id ports))
                   [(:layer-result-id ports)])]
              (layer/publish (second (installer network)) :provenance
                             (:layer-result-id ports) (:live-result-id ports)))
            network)))})

(def +
  (arithmetic-provenance-closure))

(def -
  (arithmetic-provenance-closure))

(def *
  (arithmetic-provenance-closure))

(def /
  (arithmetic-provenance-closure))
