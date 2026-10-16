(ns propagators.datastructures.compound-object.membership-slot
  "Bidirectional slot transport for membership-aware compound evidence."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as obj]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- project-observation [slot {:keys [base] :as observation}]
  (cond
    (value/nothing? base) nil
    (value/contradiction? base) observation
    (net/network? base)
    (if (contains? (obj/public-slot-keys base) slot)
      (assoc observation :base (obj/slot-value base slot))
      nil)
    :else (throw (ex-info "Membership slot requires a compound base" {:slot slot :base base}))))

(defn activation [slot slot-id parent-id]
  (let [template (obj/compound-object {slot value/nothing})
        inner (net/network-dict-entry template slot)]
    (fn [_inputs _outputs network]
      (let [parent (membership/merge-content value/nothing (net/network-cell-content network parent-id))
            child (membership/merge-content value/nothing (net/network-cell-content network slot-id))
            projected (into #{} (keep #(project-observation slot %)) (:membership/observations parent))
            incoming (set/difference (:membership/observations child) projected)
            embedded (into #{} (comp (remove #(value/nothing? (:base %)))
                                     (map #(update % :base (partial nb/seed-cell template inner)))) incoming)]
        [(message slot-id {:membership/observations projected :membership/states (membership/frontier parent)})
         (message parent-id {:membership/observations embedded :membership/states (membership/frontier child)})]))))
