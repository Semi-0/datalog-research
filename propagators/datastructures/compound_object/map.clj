(ns propagators.datastructures.compound-object.map
  "Experimental map helpers over compound-object public slots."
  (:require [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.datastructures.compound-object.core :as core]
            [propagators.datastructures.compound-object.network-slot :as network-slot]
            [propagators.datastructures.compound-object.slot :as slot]
            [propagators.gur :as gur]
            [propagators.ids :as ids]
            [propagators.io :as io]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.recursive :as recursive]
            [propagators.stdlib.prop :as stdlib-prop]))

(def accessor-map-props-key :accessor-recursive-map/props)
(def accessor-map-branches-key :accessor-recursive-map/branches)
(def accessor-map-output-key :accessor-recursive-map/output)
(def accessor-map-frame-spec-key :accessor-recursive-map/frame-spec)

(def unusable-result ::unusable)

(defn- mappable-slot-keys
  [source-net]
  (->> (core/public-slot-keys source-net)
       (remove #{:count})
       (sort-by pr-str)
       vec))

(defn- slot-values
  [source-net]
  (keep (fn [slot-key]
          (let [v (core/slot-value source-net slot-key)]
            (when-not (value/unusable? v)
              [slot-key v])))
        (mappable-slot-keys source-net)))

(defn- preserve-read-only-slots
  [source-net mapped]
  (if-let [count-value (core/slot-value source-net :count)]
    (if (value/unusable? count-value)
      mapped
      (assoc mapped :count count-value))
    mapped))

(defn- run-self-refining-slot
  [closure-value slot-value]
  (let [closure-id (ids/new-node-id)
        in-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure-value closure-value)
               (nb/install-cell in-id slot-value slot-value)
               (nb/install-cell out-id))
        [prop-id n1] ((recursive/p:self-refining-recursive-compound
                       closure-id
                       in-id
                       out-id)
                      n0)
        n2 (nb/run-propagators n1 [prop-id])]
    {:closure (net/network-cell-strongest n2 closure-id)
     :value (net/network-cell-strongest n2 out-id)}))

(defn- run-accumulating-slot
  [closure-value acc-value slot-value]
  (let [closure-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        in-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (cond-> (-> net/empty-net
                       (nb/install-cell closure-id closure-value closure-value)
                       (nb/install-cell acc-id)
                       (nb/install-cell in-id slot-value slot-value)
                       (nb/install-cell out-id))
             (not (value/unusable? acc-value))
             (nb/seed-cell acc-id acc-value))
        [prop-id n1] ((recursive/p:accumulating-recursive-compound
                       closure-id
                       in-id
                       acc-id
                       out-id)
                      n0)
        n2 (nb/run-propagators n1 [prop-id])]
    {:acc (net/network-cell-strongest n2 acc-id)
     :value (net/network-cell-strongest n2 out-id)}))

(defn- mapped-output
  [source-net mapped]
  (core/compound-object (preserve-read-only-slots source-net mapped)))

(defn- compound-source-net
  [v]
  (let [source-net (core/compound-object v)]
    (when-not (value/contradiction? source-net)
      source-net)))

(declare map-self-refining-value map-accumulating-value)

(defn- map-self-refining-compound
  [closure-value source-net]
  (let [{:keys [closure mapped]}
        (reduce (fn [{:keys [closure mapped]} [slot-key slot-value]]
                  (let [{next-closure :closure result :value}
                        (map-self-refining-value closure slot-value)]
                    {:closure next-closure
                     :mapped (if (value/unusable? result)
                               mapped
                               (assoc mapped slot-key result))}))
                {:closure closure-value
                 :mapped {}}
                (slot-values source-net))]
    {:closure closure
     :value (mapped-output source-net mapped)}))

(defn- map-self-refining-value
  [closure-value v]
  (if-let [source-net (compound-source-net v)]
    (map-self-refining-compound closure-value source-net)
    (run-self-refining-slot closure-value v)))

(defn- map-accumulating-compound
  [closure-value acc-value source-net]
  (let [{:keys [acc mapped]}
        (reduce (fn [{:keys [acc mapped]} [slot-key slot-value]]
                  (let [{next-acc :acc result :value}
                        (map-accumulating-value closure-value acc slot-value)]
                    {:acc next-acc
                     :mapped (if (value/unusable? result)
                               mapped
                               (assoc mapped slot-key result))}))
                {:acc acc-value
                 :mapped {}}
                (slot-values source-net))]
    {:acc acc
     :value (mapped-output source-net mapped)}))

(defn- map-accumulating-value
  [closure-value acc-value v]
  (if-let [source-net (compound-source-net v)]
    (map-accumulating-compound closure-value acc-value source-net)
    (run-accumulating-slot closure-value acc-value v)))

(defn- self-refining-map-activation
  [closure-id source-id out-id]
  (fn [_inputs _outputs network]
    (let [closure-value (net/network-cell-strongest network closure-id)
          source-value (net/network-cell-strongest network source-id)]
      (cond
        (or (value/unusable? closure-value)
            (value/unusable? source-value))
        []

        :else
        (let [source-net (core/compound-object source-value)]
          (if (value/contradiction? source-net)
            [(message out-id value/contradiction)]
            (let [{:keys [closure value]}
                  (map-self-refining-compound closure-value source-net)]
              [(message closure-id closure)
               (message out-id value)])))))))

(defn- accumulating-map-activation
  [closure-id acc-id source-id out-id]
  (fn [_inputs _outputs network]
    (let [closure-value (net/network-cell-strongest network closure-id)
          acc-value (net/network-cell-strongest network acc-id)
          source-value (net/network-cell-strongest network source-id)]
      (cond
        (or (value/unusable? closure-value)
            (value/unusable? source-value))
        []

        :else
        (let [source-net (core/compound-object source-value)]
          (if (value/contradiction? source-net)
            [(message out-id value/contradiction)]
            (let [{:keys [acc value]}
                  (map-accumulating-compound closure-value acc-value source-net)]
              [(message acc-id acc)
               (message out-id value)])))))))

(defn p:map-slots-with-recursive-closure
  [closure-id source-id out-id]
  (prop/construct-propagator
   (self-refining-map-activation closure-id source-id out-id)
   [closure-id source-id]
   [closure-id out-id]))

(defn p:map-slots-with-recursive-accumulator
  [closure-id acc-id source-id out-id]
  (prop/construct-propagator
   (accumulating-map-activation closure-id acc-id source-id out-id)
   [closure-id acc-id source-id]
   [acc-id out-id]))

(defn- shape-from-value
  [v]
  (if-let [source-net (compound-source-net v)]
    (if-let [count-value (core/slot-value source-net :count)]
      {:type :vector
       :children (mapv (fn [slot-key]
                         [slot-key
                          (shape-from-value
                           (core/slot-value source-net slot-key))])
                       (range count-value))}
      {:type :map
       :children (mapv (fn [slot-key]
                         [slot-key
                          (shape-from-value
                           (core/slot-value source-net slot-key))])
                       (mappable-slot-keys source-net))})
    {:type :leaf
     :value v}))

(defn- declare-leaf-recursive-map
  [network mode closure-id acc-id leaf-value]
  (let [in-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> network
               (nb/install-cell in-id leaf-value leaf-value)
               (nb/install-cell out-id))
        [prop-id n1] (case mode
                       :self-refining
                       ((recursive/p:self-refining-recursive-compound
                         closure-id
                         in-id
                         out-id)
                        n0)

                       :accumulating
                       ((recursive/p:accumulating-recursive-compound
                         closure-id
                         in-id
                         acc-id
                         out-id)
                        n0))]
    {:net n1
     :shape {:type :leaf :id out-id}
     :prop-ids [prop-id]}))

(defn- declare-leaf-recursive-map-cell
  [network mode closure-id acc-id in-id out-id]
  (let [[prop-id n1] (case mode
                       :self-refining
                       ((recursive/p:self-refining-recursive-compound
                         closure-id
                         in-id
                         out-id)
                        network)

                       :accumulating
                       ((recursive/p:accumulating-recursive-compound
                         closure-id
                         in-id
                         acc-id
                         out-id)
                        network))]
    {:net n1
     :prop-ids [prop-id]}))

(declare declare-recursive-map-shape)

(defn- declare-children
  [network mode closure-id acc-id children]
  (reduce (fn [{:keys [net children prop-ids]} [slot-key child]]
            (let [{n* :net shape* :shape prop-ids* :prop-ids}
                  (declare-recursive-map-shape
                   net
                   mode
                   closure-id
                   acc-id
                   child)]
              {:net n*
               :children (conj children [slot-key shape*])
               :prop-ids (into prop-ids prop-ids*)}))
          {:net network
           :children []
           :prop-ids []}
          children))

(defn- declare-recursive-map-shape
  [network mode closure-id acc-id shape]
  (case (:type shape)
    :leaf
    (declare-leaf-recursive-map network
                                mode
                                closure-id
                                acc-id
                                (:value shape))

    :vector
    (let [{:keys [net children prop-ids]}
          (declare-children network
                            mode
                            closure-id
                            acc-id
                            (:children shape))]
      {:net net
       :shape {:type :vector :children children}
       :prop-ids prop-ids})

    :map
    (let [{:keys [net children prop-ids]}
          (declare-children network
                            mode
                            closure-id
                            acc-id
                            (:children shape))]
      {:net net
       :shape {:type :map :children children}
       :prop-ids prop-ids})))

(defn- realize-mapped-shape
  [network shape]
  (case (:type shape)
    :leaf
    (let [v (net/network-cell-strongest network (:id shape))]
      (if (value/unusable? v)
        unusable-result
        v))

    :vector
    (let [values (mapv (fn [[_ child]]
                         (realize-mapped-shape network child))
                       (:children shape))]
      (if (some #{unusable-result} values)
        unusable-result
        values))

    :map
    (reduce (fn [acc [slot-key child]]
              (if (= unusable-result acc)
                acc
                (let [v (realize-mapped-shape network child)]
                  (if (= unusable-result v)
                    unusable-result
                    (assoc acc slot-key v)))))
            {}
            (:children shape))))

(defn- shape-leaf-ids
  [shape]
  (case (:type shape)
    :leaf [(:id shape)]
    (:vector :map) (mapcat (comp shape-leaf-ids second)
                           (:children shape))))

(defn- shape-leaf-count
  [shape]
  (case (:type shape)
    :leaf 1
    (:vector :map) (reduce + 0 (map (comp shape-leaf-count second)
                                    (:children shape)))))

(defn- assemble-mapped-output
  [shape out-id]
  (let [leaf-ids (vec (shape-leaf-ids shape))]
    (prop/construct-propagator
     (fn [_inputs _outputs network]
       (let [v (realize-mapped-shape network shape)]
         (if (= unusable-result v)
           []
           [(message out-id (core/compound-object v))])))
     leaf-ids
     [out-id])))

(defn- install-slot-accessor
  [network slot-key parent-id collection-id]
  ((slot/p:slot slot-key parent-id collection-id) network))

(declare declare-source-shape-accessors)

(defn- declare-source-children
  [network source-id children]
  (reduce
   (fn [{:keys [net prop-ids leaves]} [slot-key child-shape]]
     (let [child-id (ids/new-node-id)
           n0 (nb/install-cell net child-id)
           [slot-prop n1] (install-slot-accessor n0 slot-key child-id source-id)
           {n2 :net prop-ids* :prop-ids leaves* :leaves}
           (declare-source-shape-accessors n1 child-id child-shape)]
       {:net n2
        :prop-ids (into (conj prop-ids slot-prop) prop-ids*)
        :leaves (into leaves leaves*)}))
   {:net network
    :prop-ids []
    :leaves []}
   children))

(defn- declare-source-vector-count
  [network source-id count-value]
  (let [count-id (ids/new-node-id)
        n0 (nb/install-cell network count-id count-value count-value)
        [slot-prop n1] (install-slot-accessor n0 :count count-id source-id)]
    {:net n1
     :prop-ids [slot-prop]}))

(defn- declare-source-shape-accessors
  [network source-id shape]
  (case (:type shape)
    :leaf
    {:net (nb/seed-cell network source-id (:value shape))
     :prop-ids []
     :leaves [source-id]}

    :vector
    (let [{n1 :net count-props :prop-ids}
          (declare-source-vector-count network
                                       source-id
                                       (count (:children shape)))
          {n2 :net child-props :prop-ids leaves :leaves}
          (declare-source-children n1 source-id (:children shape))]
      {:net n2
       :prop-ids (into count-props child-props)
       :leaves leaves})

    :map
    (if (empty? (:children shape))
      {:net (nb/seed-cell network source-id (core/empty-compound-object))
       :prop-ids []
       :leaves []}
      (declare-source-children network source-id (:children shape)))))

(declare declare-accessor-recursive-map-shape)

(defn- declare-accessor-output-count
  [network out-id count-value]
  (let [count-id (ids/new-node-id)
        n0 (nb/install-cell network count-id count-value count-value)
        [slot-prop n1] (install-slot-accessor n0 :count count-id out-id)]
    {:net n1
     :prop-ids [slot-prop]}))

(defn- declare-accessor-map-children
  [network mode closure-id acc-id source-id out-id children]
  (reduce
   (fn [{:keys [net prop-ids]} [slot-key child-shape]]
     (let [child-source-id (ids/new-node-id)
           child-out-id (ids/new-node-id)
           n0 (-> net
                  (nb/install-cell child-source-id)
                  (nb/install-cell child-out-id))
           [source-slot-prop n1]
           (install-slot-accessor n0 slot-key child-source-id source-id)
           {n2 :net child-props :prop-ids}
           (declare-accessor-recursive-map-shape n1
                                                 mode
                                                 closure-id
                                                 acc-id
                                                 child-source-id
                                                 child-out-id
                                                 child-shape)
           [out-slot-prop n3]
           (install-slot-accessor n2 slot-key child-out-id out-id)]
       {:net n3
        :prop-ids (into (conj prop-ids source-slot-prop)
                        (conj (vec child-props) out-slot-prop))}))
   {:net network
    :prop-ids []}
   children))

(defn- declare-accessor-recursive-map-shape
  [network mode closure-id acc-id source-id out-id shape]
  (case (:type shape)
    :leaf
    (declare-leaf-recursive-map-cell network
                                     mode
                                     closure-id
                                     acc-id
                                     source-id
                                     out-id)

    :vector
    (let [{n1 :net count-props :prop-ids}
          (declare-accessor-output-count network
                                         out-id
                                         (count (:children shape)))
          {n2 :net child-props :prop-ids}
          (declare-accessor-map-children n1
                                         mode
                                         closure-id
                                         acc-id
                                         source-id
                                         out-id
                                         (:children shape))]
      {:net n2
       :prop-ids (into count-props child-props)})

    :map
    (if (empty? (:children shape))
      {:net (nb/seed-cell network out-id (core/empty-compound-object))
       :prop-ids []}
      (declare-accessor-map-children network
                                     mode
                                     closure-id
                                     acc-id
                                     source-id
                                     out-id
                                     (:children shape)))))

(defn- install-accessor-nested-recursive-map
  [network mode closure-id acc-id source-id source-shape out-id]
  (let [{:keys [net prop-ids]}
        (declare-accessor-recursive-map-shape network
                                             mode
                                             closure-id
                                             acc-id
                                             source-id
                                             out-id
                                             source-shape)]
    {:net net
     :prop-ids (vec prop-ids)
     :leaf-count (shape-leaf-count source-shape)
     :shape source-shape}))

(defn- install-declared-nested-recursive-map
  [network mode closure-id acc-id source-value out-id]
  (let [source-shape (shape-from-value source-value)
        source-id (ids/new-node-id)
        n0 (nb/install-cell network source-id)
        {n1 :net source-props :prop-ids}
        (declare-source-shape-accessors n0 source-id source-shape)
        {n2 :net map-props :prop-ids leaf-count :leaf-count shape :shape}
        (install-accessor-nested-recursive-map n1
                                               mode
                                               closure-id
                                               acc-id
                                               source-id
                                               source-shape
                                               out-id)]
    {:net n2
     :prop-ids (into (vec source-props) map-props)
     :leaf-count leaf-count
     :shape shape
     :source-id source-id}))

(defn install-accessor-nested-recursive-map-with-closure
  "Declare a nested recursive map over an existing compound-object cell.

  Traversal and output assembly are both built with p:slot accessors. The
  `source-shape` value supplies only the known slot shape."
  [network closure-id source-id source-shape out-id]
  (install-accessor-nested-recursive-map network
                                         :self-refining
                                         closure-id
                                         nil
                                         source-id
                                         (shape-from-value source-shape)
                                         out-id))

(defn install-accessor-nested-recursive-map-with-accumulator
  "Declare a nested recursive map with an explicit accumulator over an existing
  compound-object cell."
  [network closure-id acc-id source-id source-shape out-id]
  (install-accessor-nested-recursive-map network
                                         :accumulating
                                         closure-id
                                         acc-id
                                         source-id
                                         (shape-from-value source-shape)
                                         out-id))

(defn install-declared-nested-recursive-map-with-closure
  "Declare a nested recursive map as topology, without running it.

  `source-value` supplies the nested map/vector shape and leaf inputs. The
  returned `:prop-ids` must be run by the caller."
  [network closure-id source-value out-id]
  (install-declared-nested-recursive-map network
                                         :self-refining
                                         closure-id
                                         nil
                                         source-value
                                         out-id))

(defn install-declared-nested-recursive-map-with-accumulator
  "Declare a nested recursive map with an explicit accumulator, without running it.

  `source-value` supplies the nested map/vector shape and leaf inputs. The
  returned `:prop-ids` must be run by the caller."
  [network closure-id acc-id source-value out-id]
  (install-declared-nested-recursive-map network
                                         :accumulating
                                         closure-id
                                         acc-id
                                         source-value
                                         out-id))

(def ^:private nested-map-scope-key :compound/nested-recursive-map)
(def ^:private child-output-key [:compound/nested-recursive-map :child-output])

(defn- frame-source-ref
  [scope]
  (io/name-ref scope :source))

(defn- frame-closure-ref
  [scope]
  (io/name-ref scope :closure))

(defn- frame-acc-ref
  [scope]
  (io/name-ref scope :acc-in))

(defn- child-output-dict-key
  [slot-key]
  [child-output-key slot-key])

(defn- child-output-fragment
  [slot-key v]
  (let [id (ids/new-node-id)]
    (-> net/empty-net
        (nb/install-cell id v v)
        (net/assoc-net-dict-entry (child-output-dict-key slot-key) id))))

(defn- fragment-child-value
  [children-net slot-key]
  (if (net/network? children-net)
    (if-let [id (net/network-dict-entry children-net
                                        (child-output-dict-key slot-key))]
      (net/network-cell-strongest children-net id)
      value/nothing)
    value/nothing))

(defn- accessor-source-net?
  [source-net]
  (and (net/network? source-net)
       (network-slot/accessor-network? source-net)))

(defn- source-slot-value
  [source-net slot-key]
  (let [v (if (accessor-source-net? source-net)
            (network-slot/source-slot-value source-net slot-key)
            (core/slot-value source-net slot-key))]
    (if (nil? v) value/nothing v)))

(defn- source-slot-keys
  [source-net]
  (let [count-value (source-slot-value source-net :count)]
    (if (and (integer? count-value)
             (not (value/unusable? count-value)))
      (vec (range count-value))
      (->> (if (accessor-source-net? source-net)
             (network-slot/accessor-slot-keys source-net)
             (core/public-slot-keys source-net))
           (remove #{:count})
           (sort-by pr-str)
           vec))))

(defn- nested-map-compound-source-net
  [v]
  (let [source-net (core/compound-object v)]
    (when-not (value/contradiction? source-net)
      source-net)))

(defn- target-message
  [target v]
  (case (:type target)
    :outer
    (message (:out-id target) v)

    :child
    (message (:parent-children-id target)
             (child-output-fragment (:slot-key target) v))))

(declare nested-recursive-map-frame)

(defn- child-frame-deliveries
  [scope parent-children-id outer-acc-id closure-value acc-value source-net slot-key]
  (let [child-scope (conj scope slot-key)
        child-frame (nested-recursive-map-frame
                     child-scope
                     {:type :child
                      :parent-children-id parent-children-id
                      :slot-key slot-key
                      :acc-id outer-acc-id})
        slot-value (source-slot-value source-net slot-key)]
    (cond-> [(io/io-delivery :assoc-lexical-env [child-scope child-frame])
             (message (frame-closure-ref child-scope) closure-value)]
      (not (value/unusable? acc-value))
      (conj (message (frame-acc-ref child-scope) acc-value))

      (not (value/unusable? slot-value))
      (conj (message (frame-source-ref child-scope) slot-value)))))

(defn- dispatch-children-activation
  [scope source-id closure-id acc-id parent-children-id outer-acc-id]
  (fn [_inputs _outputs network]
    (let [source-value (net/network-cell-strongest network source-id)
          closure-value (net/network-cell-strongest network closure-id)
          acc-value (net/network-cell-strongest network acc-id)]
      (cond
        (or (value/unusable? source-value)
            (value/unusable? closure-value))
        []

        (nested-map-compound-source-net source-value)
        (let [source-net (nested-map-compound-source-net source-value)]
          (vec (mapcat #(child-frame-deliveries scope
                                                parent-children-id
                                                outer-acc-id
                                                closure-value
                                                acc-value
                                                source-net
                                                %)
                       (source-slot-keys source-net))))

        :else
        [(message (:leaf-source (net/net-dict-or-empty network)) source-value)]))))

(defn- child-values
  [children-net slot-keys]
  (mapv #(fragment-child-value children-net %) slot-keys))

(defn- mapped-compound-output
  [source-net slot-keys values]
  (let [count-value (source-slot-value source-net :count)]
    (if (and (integer? count-value)
             (not (value/unusable? count-value)))
      (core/compound-object (mapv second
                                  (sort-by first
                                           (map vector slot-keys values))))
      (if (empty? slot-keys)
        (core/empty-compound-object)
        (core/compound-object (into {}
                                    (map vector slot-keys values)))))))

(defn- assemble-children-activation
  [target source-id children-id]
  (fn [_inputs _outputs network]
    (let [source-value (net/network-cell-strongest network source-id)
          children-value (net/network-cell-strongest network children-id)]
      (if-let [source-net (and (not (value/unusable? source-value))
                               (nested-map-compound-source-net source-value))]
        (let [slot-keys (source-slot-keys source-net)
              values (child-values children-value slot-keys)]
          (cond
            (some value/contradiction? values)
            [(target-message target value/contradiction)]

            (some value/unusable? values)
            []

            :else
            [(target-message target
                             (mapped-compound-output source-net slot-keys values))]))
        []))))

(defn- leaf-output-activation
  [target leaf-out-id]
  (fn [_inputs _outputs network]
    (let [v (net/network-cell-strongest network leaf-out-id)]
      (if (value/unusable? v)
        []
        [(target-message target v)]))))

(defn- acc-output-activation
  [acc-out-id outer-acc-id]
  (fn [_inputs _outputs network]
    (let [v (net/network-cell-strongest network acc-out-id)]
      (if (or (nil? outer-acc-id)
              (value/unusable? v))
        []
        [(message outer-acc-id v)]))))

(defn- acc-seed-activation
  [acc-in-id leaf-acc-id]
  (fn [_inputs _outputs network]
    (let [v (net/network-cell-strongest network acc-in-id)]
      (if (value/unusable? v)
        []
        [(message leaf-acc-id v)]))))

(defn- nested-recursive-map-frame
  [scope target]
  (let [source-id (ids/new-node-id)
        closure-id (ids/new-node-id)
        acc-in-id (ids/new-node-id)
        children-id (ids/new-node-id)
        leaf-source-id (ids/new-node-id)
        leaf-out-id (ids/new-node-id)
        leaf-acc-id (ids/new-node-id)
        base (-> net/empty-net
                 (nb/install-cell source-id)
                 (nb/install-cell closure-id)
                 (nb/install-cell acc-in-id)
                 (nb/install-cell children-id)
                 (nb/install-cell leaf-source-id)
                 (nb/install-cell leaf-out-id)
                 (nb/install-cell leaf-acc-id)
                 (net/assoc-net-dict-entry :source source-id)
                 (net/assoc-net-dict-entry :closure closure-id)
                 (net/assoc-net-dict-entry :acc-in acc-in-id)
                 (net/assoc-net-dict-entry :children children-id)
                 (net/assoc-net-dict-entry :leaf-source leaf-source-id)
                 (net/assoc-net-dict-entry :leaf-out leaf-out-id)
                 (net/assoc-net-dict-entry :leaf-acc leaf-acc-id)
                 (net/assoc-net-dict-entry :scope scope))
        [dispatch-prop n1]
        ((prop/construct-propagator
          (dispatch-children-activation scope
                                        source-id
                                        closure-id
                                        acc-in-id
                                        children-id
                                        (:acc-id target))
          [source-id closure-id acc-in-id]
          [leaf-source-id])
         base)
        [leaf-prop n2]
        ((recursive/p:accumulating-recursive-compound
          closure-id
          leaf-source-id
          leaf-acc-id
          leaf-out-id)
         n1)
        [acc-seed-prop n3]
        ((prop/construct-propagator
          (acc-seed-activation acc-in-id leaf-acc-id)
          [acc-in-id]
          [leaf-acc-id])
         n2)
        [leaf-output-prop n4]
        ((prop/construct-propagator
          (leaf-output-activation target leaf-out-id)
          [leaf-out-id]
          [])
         n3)
        [acc-output-prop n5]
        ((prop/construct-propagator
          (acc-output-activation leaf-acc-id (:acc-id target))
          [leaf-acc-id]
          [])
         n4)
        [assemble-prop n6]
        ((prop/construct-propagator
          (assemble-children-activation target source-id children-id)
          [source-id children-id]
          [])
         n5)]
    (-> n6
        (net/update-net-dict-entry :frame/props
                                   #(into (or % [])
                                          [dispatch-prop
                                           leaf-prop
                                           acc-seed-prop
                                           leaf-output-prop
                                           acc-output-prop
                                           assemble-prop])))))

(defn- nested-recursive-map-activation
  [root-scope closure-id acc-id source-id out-id]
  (fn [_inputs _outputs network]
    (let [closure-value (net/network-cell-strongest network closure-id)
          acc-value (net/network-cell-strongest network acc-id)
          source-value (net/network-cell-strongest network source-id)]
      (if (or (value/unusable? closure-value)
              (value/unusable? source-value))
        []
        (let [root-frame (nested-recursive-map-frame
                          root-scope
                          {:type :outer
                           :out-id out-id
                           :acc-id acc-id})]
          (cond-> [(io/io-delivery :assoc-lexical-env [root-scope root-frame])
                   (message (frame-closure-ref root-scope) closure-value)]
            (not (value/unusable? acc-value))
            (conj (message (frame-acc-ref root-scope) acc-value))

            true
            (conj (message (frame-source-ref root-scope) source-value))))))))

(defn p:nested-recursive-map
  "Map a recursive closure over arbitrarily nested compound-object leaves.

  The live graph is fixed at install time. Dynamic traversal is represented by
  lexical subenv network values addressed through `io/name-ref`; frame
  evaluation communicates only with messages and evaluator IO deliveries."
  [closure-id acc-id source-id out-id]
  (let [prop-id (ids/new-node-id)
        root-scope [nested-map-scope-key prop-id]]
    (fn [network]
      ((prop/construct-propagator
        prop-id
        (nested-recursive-map-activation root-scope
                                         closure-id
                                         acc-id
                                         source-id
                                         out-id)
        [closure-id acc-id source-id]
        [acc-id out-id])
       network))))

(defn- declared-slot-parent-id
  [network source-id slot-key]
  (->> (get-in (net/network-dict-entry network core/slot-declarations-key)
               [source-id slot-key])
       keys
       (sort-by pr-str)
       first))

(defn- cell-present?
  [network id]
  (contains? (net/net-env network) id))

(defn- strongest-or-nothing
  [network id]
  (if (cell-present? network id)
    (net/network-cell-strongest network id)
    value/nothing))

(defn- content-or-nothing
  [network id]
  (if (cell-present? network id)
    (net/network-cell-content network id)
    value/nothing))

(defn- accessor-shell
  [network source-id]
  (let [v (strongest-or-nothing network source-id)]
    (when-not (value/unusable? v)
      (let [shell (network-slot/as-accessor-network v)]
        (when-not (value/contradiction? shell)
          shell)))))

(defn- shell-slot-parent-id
  [network source-id slot-key]
  (when-let [shell (accessor-shell network source-id)]
    (->> (network-slot/accessor-parent-ids shell slot-key)
         (sort-by pr-str)
         first)))

(defn- list-slot-parent-id
  [network source-id slot-key]
  (or (declared-slot-parent-id network source-id slot-key)
      (shell-slot-parent-id network source-id slot-key)))

(defn- list-node?
  [network source-id]
  (and (list-slot-parent-id network source-id :car)
       (list-slot-parent-id network source-id :cdr)))

(defn- list-ready-value?
  [source-value]
  (when-not (value/unusable? source-value)
    (let [shell (network-slot/as-accessor-network source-value)]
      (and (not (value/contradiction? shell))
           (seq (network-slot/accessor-parent-ids shell :car))
           (seq (network-slot/accessor-parent-ids shell :cdr))))))

(defn- terminal-accessor-copy-activation
  [source-id out-id]
  (fn [_inputs _outputs network]
    (let [source-value (strongest-or-nothing network source-id)]
      (cond
        (value/nothing? source-value)
        []

        (value/contradiction? source-value)
        [(message out-id value/contradiction)]

        (list-ready-value? source-value)
        []

        :else
        [(message out-id source-value)]))))

(declare install-accessor-recursive-list-map
         install-accessor-recursive-value-map
         install-accessor-recursive-leaf-map)

(defn- accessor-list-reachable-ids
  ([network source-id]
   (accessor-list-reachable-ids network source-id #{}))
  ([network source-id visited]
   (if (contains? visited source-id)
     []
     (let [visited* (conj visited source-id)
           car-id (list-slot-parent-id network source-id :car)
           cdr-id (list-slot-parent-id network source-id :cdr)]
       (cond-> [source-id]
         car-id (conj car-id)
         cdr-id (conj cdr-id)
         cdr-id (into (accessor-list-reachable-ids network cdr-id visited*)))))))

(defn- cell-snapshots
  [network ids]
  (into {}
        (keep (fn [id]
                (when (cell-present? network id)
                  [id {:content (content-or-nothing network id)
                       :strongest (strongest-or-nothing network id)}])))
        ids))

(defn- install-snapshot-cell
  [network id {:keys [content strongest]}]
  (if (cell-present? network id)
    network
    (nb/install-cell network id content strongest)))

(defn- install-snapshot-cells
  [network snapshots]
  (reduce-kv install-snapshot-cell network snapshots))

(defn- accessor-frame-expander-net
  [spec snapshots]
  (net/net-with-dict
   net/empty-net
   {accessor-map-frame-spec-key spec
    :accessor-recursive-map/cell-snapshots snapshots}))

(defn- accessor-frame-expander-f
  [closure-net _input-ids _output-ids declaration-net]
  (let [{:keys [closure-id acc-id source-id out-id visited scope]}
        (net/network-dict-entry closure-net accessor-map-frame-spec-key)
        kind (or (:kind (net/network-dict-entry closure-net
                                                accessor-map-frame-spec-key))
                 :list)
        snapshots (net/network-dict-entry closure-net
                                          :accessor-recursive-map/cell-snapshots)
        n0 (install-snapshot-cells declaration-net snapshots)
        {:keys [net prop-ids]}
        (case kind
          :leaf
          (install-accessor-recursive-leaf-map n0
                                               closure-id
                                               acc-id
                                               source-id
                                               out-id)

          :value
          (install-accessor-recursive-value-map n0
                                                closure-id
                                                acc-id
                                                source-id
                                                out-id
                                                (set visited)
                                                scope)

          (install-accessor-recursive-list-map n0
                                               closure-id
                                               acc-id
                                               source-id
                                               out-id
                                               (set visited)
                                               scope))
        {n1 :net boundary-props :prop-ids}
        (gur/install-boundary net
                              {:inputs [[:source source-id]
                                        [:closure closure-id]
                                        [:acc acc-id]]
                               :outputs [[:out out-id]
                                         [:acc-out acc-id]
                                         [:source source-id]]})
        all-props (into (vec prop-ids) boundary-props)]
    (-> n1
        (net/update-net-dict-entry accessor-map-props-key
                                   #(into (vec (or % [])) all-props))
        (net/assoc-net-dict-entry gur/prop-ids-key all-props)
        (net/assoc-net-dict-entry accessor-map-output-key out-id))))

(defn- accessor-frame-expander
  [spec snapshots]
  (closure/closure accessor-frame-expander-f
                   (accessor-frame-expander-net spec snapshots)))

(defn- lazy-accessor-frame-activation
  [closure-id acc-id source-id out-id branch-id condition-id expander-id visited]
  (fn [_inputs _outputs network]
    (let [source-value (strongest-or-nothing network source-id)
          branch-value (strongest-or-nothing network branch-id)
          ready? (list-ready-value? source-value)]
      (cond
        (net/network? branch-value)
        []

        (value/nothing? source-value)
        []

        (value/contradiction? source-value)
        [(message condition-id value/contradiction)]

        (not ready?)
        []

        :else
        (let [reachable (distinct
                         (into [closure-id acc-id out-id]
                               (accessor-list-reachable-ids network source-id)))
              snapshots (cell-snapshots network reachable)
              spec {:closure-id closure-id
                    :acc-id acc-id
                    :source-id source-id
                    :out-id out-id
                    :visited (vec visited)
                    :scope (gur/frame-scope branch-id)
                    :kind :list}]
          [(message condition-id true)
           (message expander-id (accessor-frame-expander spec snapshots))])))))

(defn- accessor-network-value?
  [v]
  (and (not (value/unusable? v))
       (let [shell (network-slot/as-accessor-network v)]
         (and (not (value/contradiction? shell))
              (network-slot/accessor-network? shell)))))

(defn- value-accessor-frame-activation
  [closure-id acc-id source-id out-id branch-id condition-id expander-id visited]
  (fn [_inputs _outputs network]
    (let [source-value (strongest-or-nothing network source-id)
          branch-value (strongest-or-nothing network branch-id)]
      (cond
        (net/network? branch-value)
        []

        (value/nothing? source-value)
        []

        (value/contradiction? source-value)
        [(message out-id value/contradiction)]

        (list-ready-value? source-value)
        (let [reachable (distinct
                         (into [closure-id acc-id out-id]
                               (accessor-list-reachable-ids network source-id)))
              snapshots (cell-snapshots network reachable)
              spec {:closure-id closure-id
                    :acc-id acc-id
                    :source-id source-id
                    :out-id out-id
                    :visited (vec visited)
                    :scope (gur/frame-scope branch-id)
                    :kind :list}]
          [(message condition-id true)
           (message expander-id (accessor-frame-expander spec snapshots))])

        (accessor-network-value? source-value)
        [(message out-id source-value)]

        :else
        (let [snapshots (cell-snapshots network [closure-id acc-id source-id out-id])
              spec {:closure-id closure-id
                    :acc-id acc-id
                    :source-id source-id
                    :out-id out-id
                    :visited (vec visited)
                    :scope (gur/frame-scope branch-id)
                    :kind :leaf}]
          [(message condition-id true)
           (message expander-id (accessor-frame-expander spec snapshots))])))))

(defn- install-accessor-value-dispatch-continuation
  [network closure-id acc-id source-id out-id visited]
  (let [condition-id (ids/new-node-id)
        expander-id (ids/new-node-id)
        template-id (ids/new-node-id)
        branch-id (ids/new-node-id)
        n0 (-> network
               (nb/install-cell condition-id)
               (nb/install-cell expander-id)
               (nb/install-cell template-id net/empty-net net/empty-net)
               (nb/install-cell branch-id))
        [condition-prop n1]
        ((prop/construct-propagator
          (value-accessor-frame-activation closure-id
                                           acc-id
                                           source-id
                                           out-id
                                           branch-id
                                           condition-id
                                           expander-id
                                           visited)
          [closure-id acc-id source-id branch-id]
          [condition-id expander-id out-id])
         n0)
        [branch-prop n2]
        ((closure/p:when-apply-network condition-id
                                       expander-id
                                       template-id
                                       branch-id)
         n1)
        [runner-prop n3]
        ((gur/p:run-frame branch-id
                          [[:source source-id source-id]
                           [:closure closure-id closure-id]
                           [:acc acc-id acc-id]]
                          [[:out out-id]
                           [:acc-out acc-id]
                           [:source source-id]])
         n2)]
    {:net (-> n3
              (net/update-net-dict-entry accessor-map-branches-key
                                         (fnil conj []) branch-id))
     :prop-ids [condition-prop branch-prop runner-prop]
     :branch-id branch-id}))

(defn- install-lazy-accessor-continuation
  [network closure-id acc-id source-id out-id visited]
  (let [condition-id (ids/new-node-id)
        expander-id (ids/new-node-id)
        template-id (ids/new-node-id)
        branch-id (ids/new-node-id)
        n0 (-> network
               (nb/install-cell condition-id)
               (nb/install-cell expander-id)
               (nb/install-cell template-id net/empty-net net/empty-net)
               (nb/install-cell branch-id))
        [condition-prop n1]
        ((prop/construct-propagator
          (lazy-accessor-frame-activation closure-id
                                          acc-id
                                          source-id
                                          out-id
                                          branch-id
                                          condition-id
                                          expander-id
                                          visited)
          [closure-id acc-id source-id branch-id]
          [condition-id expander-id])
         n0)
        [branch-prop n2]
        ((closure/p:when-apply-network condition-id
                                       expander-id
                                       template-id
                                       branch-id)
         n1)
        [runner-prop n3]
        ((gur/p:run-frame branch-id
                          [[:source source-id source-id]
                           [:closure closure-id closure-id]
                           [:acc acc-id acc-id]]
                          [[:out out-id]
                           [:acc-out acc-id]
                           [:source source-id]])
         n2)]
    {:net (-> n3
              (net/update-net-dict-entry accessor-map-branches-key
                                         (fnil conj []) branch-id))
     :prop-ids [condition-prop branch-prop runner-prop]
     :branch-id branch-id}))

(defn- install-accessor-recursive-value-map
  [network closure-id acc-id source-id out-id visited scope]
  (if (and (not (contains? visited source-id))
           (list-node? network source-id))
    (install-accessor-recursive-list-map network
                                         closure-id
                                         acc-id
                                         source-id
                                         out-id
                                         visited
                                         scope)
    (if scope
      (install-accessor-value-dispatch-continuation network
                                                    closure-id
                                                    acc-id
                                                    source-id
                                                    out-id
                                                    visited)
      (install-accessor-recursive-leaf-map network
                                           closure-id
                                           acc-id
                                           source-id
                                           out-id))))

(defn- install-accessor-recursive-leaf-map
  [network closure-id acc-id source-id out-id]
  (let [[leaf-prop n1] ((recursive/p:accumulating-recursive-compound
                         closure-id
                         source-id
                         acc-id
                         out-id)
                        network)]
    {:net n1
     :prop-ids [leaf-prop]}))

(defn- slot-subscriber-opts
  [scope cell-id]
  (if scope
    {:subscriber-refs [(io/cell-ref scope cell-id)]
     :notify-subscribers? false}
    {}))

(defn- install-accessor-recursive-list-map
  [network closure-id acc-id source-id out-id visited scope]
  (cond
    (contains? visited source-id)
    (let [[prop-id n1] ((stdlib-prop/id source-id out-id) network)]
      {:net n1
       :prop-ids [prop-id]})

    (list-node? network source-id)
    (let [car-id (if scope
                   (ids/new-node-id)
                   (list-slot-parent-id network source-id :car))
          cdr-id (if scope
                   (ids/new-node-id)
                   (list-slot-parent-id network source-id :cdr))
          mapped-car-id (ids/new-node-id)
          mapped-cdr-id (ids/new-node-id)
          n0 (cond-> network
               scope (nb/install-cell car-id)
               scope (nb/install-cell cdr-id)
               true (nb/install-cell mapped-car-id)
               true (nb/install-cell mapped-cdr-id))
          [source-car-prop n0a]
          (if scope
            ((network-slot/p:network-slot :car
                                          car-id
                                          source-id
                                          (slot-subscriber-opts scope car-id))
             n0)
            [nil n0])
          [source-cdr-prop n0b]
          (if scope
            ((network-slot/p:network-slot :cdr
                                          cdr-id
                                          source-id
                                          (slot-subscriber-opts scope cdr-id))
             n0a)
            [nil n0a])
          {n1 :net car-props :prop-ids}
          (install-accessor-recursive-value-map n0b
                                                closure-id
                                                acc-id
                                                car-id
                                                mapped-car-id
                                                (conj visited source-id)
                                                scope)
          {n2 :net cdr-props :prop-ids}
          (install-accessor-recursive-list-map n1
                                               closure-id
                                               acc-id
                                               cdr-id
                                               mapped-cdr-id
                                               (conj visited source-id)
                                               scope)
          [[car-prop cdr-prop] n3]
          ((network-slot/p:network-cons mapped-car-id mapped-cdr-id out-id) n2)]
      {:net n3
       :prop-ids (into (cond-> []
                          source-car-prop (conj source-car-prop)
                          source-cdr-prop (conj source-cdr-prop))
                       (into (vec car-props)
                             (into cdr-props [car-prop cdr-prop])))})

    :else
    (let [[prop-id n1] ((prop/construct-propagator
                         (terminal-accessor-copy-activation source-id out-id)
                         [source-id]
                         [out-id])
                        network)
          {n2 :net lazy-props :prop-ids}
          (install-lazy-accessor-continuation n1
                                              closure-id
                                              acc-id
                                              source-id
                                              out-id
                                              visited)]
      {:net n2
       :prop-ids (into [prop-id] lazy-props)})))

(defn p:accessor-recursive-map
  "Declare an accessor-native recursive map over a `p:cons`/`p:car`/`p:cdr` list.

  This is declaration-first: it walks visible accessor topology from declarations
  or collection shell values, installs recursive leaf applications for each
  scalar `:car`, recursively declares nested `:car` compounds that already have
  accessor topology, and assembles output with `p:cons` accessor topology.
  Terminal cdr cells get a lazy branch-network continuation for later shell
  topology."
  [closure-id acc-id source-id out-id]
  (fn [network]
    (let [{:keys [net prop-ids]}
          (install-accessor-recursive-list-map network
                                               closure-id
                                               acc-id
                                               source-id
                                               out-id
                                               #{}
                                               nil)]
      [prop-ids net])))
