(ns propagators.datastructures.compound-object
  "Public facade for compound-object slots and reducers."
  (:require [propagators.datastructures.compound-object.core :as core]
            [propagators.datastructures.compound-object.merge :as compound-merge]
            [propagators.datastructures.compound-object.network-slot :as network-slot]
            [propagators.datastructures.compound-object.reduce :as reduce]
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

(def slot-declarations slot/slot-declarations)
(def slot-declarations-for slot/slot-declarations-for)
(def attach-slot-sync slot/attach-slot-sync)
(def sync-slot-messages slot/sync-slot-messages)
(def ^:deprecated p:legacy-slot slot/p:slot)

(def accessor-network? compound-merge/accessor-network?)
(def as-accessor-network compound-merge/as-accessor-network)
(def empty-accessor-network compound-merge/empty-accessor-network)
(def accessor-declaration compound-merge/accessor-declaration)
(def refine-accessor-network compound-merge/refine-accessor-network)
(def accessor-slot-keys compound-merge/accessor-slot-keys)
(def accessor-parent-ids compound-merge/accessor-parent-ids)
(def accessor-source-slots compound-merge/source-slots)
(def accessor-source-slot-present? compound-merge/source-slot-present?)
(def accessor-source-slot-value compound-merge/source-slot-value)
(def accessor-declarations-for compound-merge/accessor-declarations-for)
(def attach-network-slot-sync compound-merge/attach-network-slot-sync)
(def register-accessor-parent compound-merge/register-accessor-parent)
(def existing-slot-cell-id network-slot/existing-slot-cell-id)
(def install-slot-access network-slot/install-slot-access)
(def p:network-slot network-slot/p:network-slot)
(def p:network-car network-slot/p:network-car)
(def p:network-cdr network-slot/p:network-cdr)
(def p:network-cons network-slot/p:network-cons)
(def p:slot network-slot/p:network-slot)
(def p:car network-slot/p:network-car)
(def p:cdr network-slot/p:network-cdr)
(def p:cons network-slot/p:network-cons)

(def p:reduce reduce/p:reduce)
