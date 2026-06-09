(ns propagators.datastructures.compound-object
  "Public facade for compound-object slots, reducers, and sequence helpers."
  (:require [propagators.datastructures.compound-object.core :as core]
            [propagators.datastructures.compound-object.map :as map]
            [propagators.datastructures.compound-object.reduce :as reduce]
            [propagators.datastructures.compound-object.sequence :as sequence]
            [propagators.datastructures.compound-object.slot :as slot]))

(def slot-sync-key core/slot-sync-key)
(def reduce-sync-key core/reduce-sync-key)
(def slot-declarations-key core/slot-declarations-key)
(def internal-metadata-prefix core/internal-metadata-prefix)
(def internal-metadata-key core/internal-metadata-key)

(def empty-compound-object core/empty-compound-object)
(def compound-object core/compound-object)
(def slot-cell-id core/slot-cell-id)
(def slot-strongest core/slot-strongest)
(def slot-content core/slot-content)
(def slot-value core/slot-value)
(def public-slot-keys core/public-slot-keys)

(def empty-cons-net sequence/empty-cons-net)
(def ensure-cons-net sequence/ensure-cons-net)
(def p:car sequence/p:car)
(def p:cdr sequence/p:cdr)
(def p:cons sequence/p:cons)

(def slot-declarations slot/slot-declarations)
(def slot-declarations-for slot/slot-declarations-for)
(def attach-slot-sync slot/attach-slot-sync)
(def sync-slot-messages slot/sync-slot-messages)
(def p:slot slot/p:slot)

(def p:reduce reduce/p:reduce)
(def p:map-slots-with-recursive-closure map/p:map-slots-with-recursive-closure)
(def p:map-slots-with-recursive-accumulator map/p:map-slots-with-recursive-accumulator)
(def install-declared-nested-recursive-map-with-closure
  map/install-declared-nested-recursive-map-with-closure)
(def install-declared-nested-recursive-map-with-accumulator
  map/install-declared-nested-recursive-map-with-accumulator)
(def install-accessor-nested-recursive-map-with-closure
  map/install-accessor-nested-recursive-map-with-closure)
(def install-accessor-nested-recursive-map-with-accumulator
  map/install-accessor-nested-recursive-map-with-accumulator)
