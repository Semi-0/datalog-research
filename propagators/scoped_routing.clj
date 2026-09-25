(ns propagators.scoped-routing
  "Generic routing for scoped addresses through network-valued owner cells."
  (:require [clojure.set :as set]
            [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.scoped-address :as scoped])
  (:import [java.util Collections IdentityHashMap]))

(def scope-key [:env/scope])
(def scopes-key [:env/scopes])
(def local-scopes-key [:env/local-scopes])
(def child-queue-key [:scoped-routing/child-queue])
(def external-messages-key [:scoped-routing/external-messages])
(def dispatch-tags
  #{:dispatch/local :dispatch/subenv :dispatch/subenv-ref :dispatch/external})

(def scope-ref scoped/scope-ref)
(def name-ref scoped/name-ref)
(def cell-ref scoped/cell-ref)

(defn bind-key [name] [:env/bind name])

(defn extend-env
  [network scope]
  (net/assoc-net-dict-entry network scope-key scope))

(defn bind
  [network name local-id]
  (net/assoc-net-dict-entry network (bind-key name) local-id))

(defn bind-in-scope
  [network scope name local-id]
  (-> network
      (net/update-net-dict-entry scopes-key
                                 #(assoc-in (or % {}) [scope name] local-id))
      (net/update-net-dict-entry local-scopes-key
                                 #(update (or %) local-id (fnil conj #{}) scope))))

(defn- bind-entry?
  [key]
  (and (vector? key)
       (= 2 (count key))
       (= :env/bind (first key))))

(defn bindings
  [child-network]
  (->> (net/net-dict-or-empty child-network)
       (keep (fn [[key local-id]]
               (when (and (bind-entry? key)
                          (ids/node-id? local-id))
                 [(second key) local-id])))))

(defn scoped-bindings
  [child-network]
  (mapcat (fn [[scope entries]]
            (keep (fn [[name local-id]]
                    (when (ids/node-id? local-id)
                      [scope name local-id]))
                  entries))
          (or (net/network-dict-entry child-network scopes-key) {})))

(defn- subenv-scope [network]
  (when (net/net? network)
    (net/network-dict-entry network scope-key)))

(defn- scoped-bindings? [network]
  (seq (net/network-dict-entry network scopes-key)))

(defn- register-binding
  [parent owner-id scope [name local-id]]
  (-> parent
      (net/assoc-net-dict-entry (name-ref scope name)
                                [:dispatch/subenv owner-id local-id])
      (net/assoc-net-dict-entry (cell-ref scope local-id)
                                [:dispatch/subenv owner-id local-id])))

(defn register-subenv-from-owner
  [parent owner-id child]
  (let [scope (subenv-scope child)
        registered
        (if scope
          (reduce #(register-binding %1 owner-id scope %2)
                  (net/assoc-net-dict-entry parent (scope-ref scope) owner-id)
                  (bindings child))
          parent)]
    (reduce (fn [network [binding-scope name local-id]]
              (-> network
                  (net/assoc-net-dict-entry (scope-ref binding-scope) owner-id)
                  (register-binding owner-id binding-scope [name local-id])))
            registered
            (scoped-bindings child))))

(defn maybe-register-subenv
  [parent owner-id strongest]
  (if (and (net/net? strongest)
           (or (subenv-scope strongest) (scoped-bindings? strongest)))
    (register-subenv-from-owner parent owner-id strongest)
    parent))

(defn- directory-map
  [directory]
  (if (net/net? directory)
    (net/net-dict-or-empty directory)
    (or directory {})))

(defn resolve-dispatch
  [directory target parent]
  (let [entry (get (directory-map directory) target)]
    (cond
      (and (vector? entry) (contains? dispatch-tags (first entry))) entry
      (ids/node-id? entry) [:dispatch/local entry]
      (ids/node-id? target) [:dispatch/local target]
      (and (scoped/address? target)
           (or (subenv-scope parent) (scoped-bindings? parent)))
      [:dispatch/external target]
      :else
      (throw (ex-info "unresolvable cell dispatch target"
                      {:target target :entry entry})))))

(defn- accessor-value? [candidate]
  (and (net/net? candidate) (obj/accessor-network? candidate)))

(defn- source-cell-value [source id]
  (when (and (ids/node-id? id) (contains? (net/net-env source) id))
    (cell/cell-strongest (net/network-lookup-cell source id))))

(defn- accessor-parent-cell-ids
  [source candidate]
  (let [seen (Collections/newSetFromMap (IdentityHashMap.))]
    (letfn [(walk [value]
              (if (or (not (accessor-value? value)) (.contains seen value))
                #{}
                (do
                  (.add seen value)
                  (let [direct (->> (obj/accessor-slot-keys value)
                                    (mapcat #(obj/accessor-parent-ids value %))
                                    (filter ids/node-id?)
                                    set)]
                    (into direct
                          (concat (mapcat #(walk (source-cell-value source %)) direct)
                                  (mapcat walk
                                          (vals (obj/accessor-source-slots value)))))))))]
      (walk candidate))))

(defn- import-accessor-parent-cells
  [target source candidate]
  (reduce
   (fn [network id]
     (if-not (contains? (net/net-env source) id)
       network
       (let [source-cell (net/network-lookup-cell source id)]
         (if (contains? (net/net-env network) id)
           (let [target-cell (net/network-lookup-cell network id)]
             (net/assoc-net-cell
              network id
              (merge/merge-cell-entry target-cell
                                      (cell/cell-content source-cell)
                                      source)))
           (nb/install-cell network id
                            (cell/cell-content source-cell)
                            (cell/cell-strongest source-cell))))))
   target
   (accessor-parent-cell-ids source candidate)))

(defn- task-ids
  [tasks]
  (cond
    (nil? tasks) []
    (ids/node-id? tasks) [tasks]
    (tq/task-queue? tasks)
    (loop [queue tasks ids []]
      (if (tq/queue-empty? queue)
        ids
        (let [[id remaining] (tq/pop-task queue)]
          (recur remaining (conj ids id)))))
    (or (sequential? tasks) (set? tasks)) (vec tasks)
    :else (throw (ex-info "unsupported child task collection" {:tasks tasks}))))

(defn- queue-child-props
  [child tasks]
  (let [tokens (set (map (fn [id] [id (ids/new-node-id)]) (task-ids tasks)))]
    (if (empty? tokens)
      child
      (net/update-net-dict-entry
       child child-queue-key
       (fn [queue]
         (-> (or queue {:scheduled #{} :ran #{}})
             (update :scheduled set/union tokens)))))))

(defn- queue-external-message [child cell-message]
  (net/update-net-dict-entry child external-messages-key
                             #(conj (set %) cell-message)))

(declare evaluate)

(defn- route-through-owner
  [evaluate-cell parent owner-id routed-message child-update]
  (let [child (some-> (net/network-lookup-cell parent owner-id)
                      cell/cell-strongest)]
    (cond
      (value/contradiction? child) [tq/empty-queue parent]
      (not (net/net? child))
      (throw (ex-info "scoped route owner does not hold a network"
                      {:owner-id owner-id :owner-value child}))
      :else
      (let [prepared (import-accessor-parent-cells
                      child parent (message/message-value routed-message))
            [tasks updated-child] (child-update prepared)]
        (evaluate-cell owner-id
                       (message/message owner-id
                                        (queue-child-props updated-child tasks))
                       parent)))))

(defn evaluate
  [directory cell-message parent]
  (let [evaluate-cell
        (requiring-resolve 'propagators.cell-evaluator/evaluate-cell)
        route (resolve-dispatch directory (message/message-id cell-message) parent)]
    (case (first route)
      :dispatch/local
      (let [[_ id] route]
        (evaluate-cell id
                       (message/message id (message/message-value cell-message))
                       parent))

      :dispatch/subenv
      (let [[_ owner-id local-id] route
            child-message
            (message/message local-id (message/message-value cell-message))]
        (route-through-owner
         evaluate-cell parent owner-id child-message
         (fn [child]
           (evaluate-cell local-id child-message child))))

      :dispatch/subenv-ref
      (let [[_ owner-id target] route
            child-message
            (message/message target (message/message-value cell-message))]
        (route-through-owner
         evaluate-cell parent owner-id child-message
         (fn [child]
           (evaluate (net/net-dict-or-empty child) child-message child))))

      :dispatch/external
      (let [[_ target] route]
        [tq/empty-queue
         (queue-external-message
          parent (message/message target (message/message-value cell-message)))])

      (throw (ex-info "unknown scoped dispatch route" {:route route})))))
