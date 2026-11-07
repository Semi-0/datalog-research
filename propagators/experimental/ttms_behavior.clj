(ns propagators.experimental.ttms-behavior
  "Opt-in current behavior over named TTMS contributions. No event archive or clock."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.reducer-cell :as cell-reducer]
            [propagators.datastructures.reducer-subnet :as reducer]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-compound :as compound]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def empty-state {:contributions {} :frontier #{}})

(defn advance
  "Join named evidence and discard strictly superseded payloads. Keep tombstones
  to reject replay. A source's revision applies across all named contributions."
  [state updates]
  (let [merged (merge-with collection/merge-content
                           (:contributions state)
                           (update-vals updates compound/evidence))
        frontier (apply support/join (:frontier state)
                        (map #(-> % compound/evidence :support/states) (vals merged)))]
    {:contributions
     (update-vals merged #(collection/retire-superseded
                           (assoc % :support/states frontier)))
     :frontier frontier}))

(defn- agree [a b]
  (cond
    (value/nothing? a) b
    (value/nothing? b) a
    (= a b) a
    :else value/contradiction))

(defn current
  "Current named values and original premises. Same-slot disagreement is a
  contradiction, never arbitrarily ordered into the reducer. Unknown slots wait."
  [state]
  (let [selected
        (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
              (keep (fn [[k evidence]]
                      (let [v (collection/strongest-value evidence agree)]
                        (when-not (value/nothing? (datum/layer-value v :base))
                          [k v]))))
              (:contributions state))]
    {:values (mapv (fn [[k v]] {:slot k :value (datum/layer-value v :base)}) selected)
     :support (apply support/combine (map datum/support-of (vals selected)))
     :conflicted? (not (support/compatible? (:frontier state)))
     :premise-state (:frontier state)}))

(defn project
  "Recompute the current fold through an immutable one-time merge template with
  ports :acc, :update, :out. Temporary topology never enters the active network.
  Order is by named slot, not arrival time. This is a behavior, not an event scan."
  [state merge-net initial]
  (let [{:keys [values support premise-state conflicted?]} (current state)
        result (if (or conflicted? (some #(value/contradiction? (:value %)) values))
                 value/contradiction
                 (reducer/fold-updates merge-net initial values))]
    {:base result
     :support (if conflicted? (support/combine support premise-state) support)
     :premise-state premise-state}))

(defn retain
  "Reducer-cell merge policy. Revision is immutable cell-local evidence about a
  changed candidate set, not a clock or an incoming event version."
  [content update]
  (let [old (merge empty-state (dissoc content :revision))
        next (advance old (:contributions update))
        revision (get content :revision 0)]
    (assoc next :revision (if (= old next) revision (inc revision)))))

(defn- template [inputs outputs calculate]
  (let [names (into (vec inputs) outputs)
        ports (zipmap names (repeatedly (count names) ids/new-node-id))
        initial (reduce-kv net/assoc-net-dict-entry
                           (nb/install-cells (vals ports)) ports)
        [_ installed]
        ((prop/construct-propagator
          ::template
          (fn [_ _ network]
            (let [values (update-vals (select-keys ports inputs)
                                      #(net/network-cell-strongest network %))
                  result (calculate values)]
              (mapv #(message (ports %) (get result %)) outputs)))
          (mapv ports inputs) (mapv ports outputs)) initial)]
    installed))

(defn reducer-networks
  "Specialize the existing reducer-cell with retention and strongest networks.
  No new cell type or merge dispatch. The user's fold network is a closed pure
  template returning an ordinary base value through :out."
  [id fold-net initial]
  {:merge-net
   (template [:content :update] [:out]
             (fn [{:keys [content update]}] {:out (retain content update)}))
   :strongest-net
   (template [:slots] [:out :dependence :epoch]
             (fn [{:keys [slots]}]
               (let [revision #{{:source id :timestamp (:revision slots)
                                 :premises-status :active}}
                     result (-> (project slots fold-net initial)
                                (update :support support/combine revision)
                                (update :premise-state support/join revision))]
                 {:out result :dependence (:support result) :epoch (:revision slots)})))})

(defn candidates
  "An ordinary reducer-cell update: its content carries candidates and networks.
  The kernel invokes retention on merge and projection on strongest."
  [id networks updates]
  (cell-reducer/reducer-cell id (:merge-net networks) (:strongest-net networks)
                             {:contributions updates}))

(defn p:behavior
  "Thin wiring to an existing reducer cell. Caller declares distinct cells.
  All retention and reduction happen in the cell's supplied networks, not here.
  Results carry original premises plus a cell-local membership revision."
  [inputs fold-net initial state-id output-id]
  (when (or (= state-id output-id)
            (some #{state-id output-id} (vals inputs)))
    (throw (ex-info "Behavior state and output require distinct owned cells" {})))
  (let [networks (reducer-networks state-id fold-net initial)
        collect (prop/construct-propagator
                 ::candidates
                 (fn [_ _ network]
                   [(message state-id
                             (candidates state-id networks
                                         (update-vals inputs #(net/network-cell-content network %))))])
                 (vec (vals inputs)) [state-id])
        publish (prop/construct-propagator
                 ::result
                 (fn [_ _ network]
                   (let [v (net/network-cell-strongest network state-id)]
                     (if (cell-reducer/reduced-value? v)
                       [(message output-id (collection/content (cell-reducer/reduced-result v)))]
                       [])))
                 [state-id] [output-id])]
    (fn [network]
      (let [[collect-id collected] (collect network)
            [publish-id installed] (publish collected)]
        [[collect-id publish-id] installed]))))
