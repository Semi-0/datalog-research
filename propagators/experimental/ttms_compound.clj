(ns propagators.experimental.ttms-compound
  "Opt-in bidirectional compound access using ordinary TTMS evidence merges.
  Accessors preserve source versions; they never stamp reflected observations."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn evidence
  "Normalize TTMS input and retain its per-source frontier, including withdrawals."
  [content]
  (let [normalized (cond
                     (value/nothing? content) {:support/observations #{}}
                     (collection/content? content) (collection/merge-content content value/nothing)
                     :else (throw (ex-info "Experimental compound access requires TTMS content"
                                          {:content content})))
        frontier (apply support/join (get normalized :support/states #{})
                        (map :support (:support/observations normalized)))]
    (assoc normalized :support/states frontier)))

(defn current-observations
  "Select records against a frontier without converting old records into fresh ones."
  [observations frontier]
  (set
   (filter
    (fn [observation]
      (let [candidate (collection/content (assoc observation :premise-state frontier))
            projected (collection/strongest-value candidate (fn [_base update] update))]
        (= (:support observation) (datum/support-of projected))))
    observations)))

(defn- slot-observation [slot-key {:keys [base support]}]
  (cond
    (value/nothing? base) nil
    (value/contradiction? base) {:base base :support support}
    (net/network? base)
    (if (contains? (obj/public-slot-keys base) slot-key)
      {:base (obj/slot-value base slot-key) :support support}
      nil)
    :else (throw (ex-info "A compound observation must contain a network"
                          {:slot slot-key :base base}))))

(defn slot-content [slot-key content]
  (let [parent (evidence content)]
    {:support/observations (into #{} (keep #(slot-observation slot-key %))
                                (:support/observations parent))
     :support/states (:support/states parent)}))

(defn- embed-content [template slot-key child projected]
  (let [slot-id (net/network-dict-entry template slot-key)
        new-observations (set/difference (:support/observations child)
                                        (:support/observations projected))]
    {:support/observations
     (into #{}
           (comp (remove #(value/nothing? (:base %)))
                 (map #(update % :base (partial nb/seed-cell template slot-id))))
           new-observations)
     :support/states (:support/states child)}))

(defn p:slot
  "Constrain a TTMS compound and one slot in both directions.
  Slot existence inherits the observation's support. Repeated reads add no nodes."
  [slot-key slot-id compound-id]
  (let [template (obj/compound-object {slot-key value/nothing})]
    (prop/construct-propagator
     [::slot slot-key]
     (fn [_inputs _outputs network]
       (let [parent (net/network-cell-content network compound-id)
             child (evidence (net/network-cell-content network slot-id))
             projected (slot-content slot-key parent)
             embedded (embed-content template slot-key child projected)]
         [(message slot-id projected)
          (message compound-id embedded)]))
     [compound-id slot-id] [compound-id slot-id])))
