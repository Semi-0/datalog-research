(ns propagators.datastructures.compound-object.map
  "Experimental map helpers over compound-object public slots."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as core]
            [propagators.datastructures.compound-object.slot :as slot]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.recursive :as recursive]))

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
