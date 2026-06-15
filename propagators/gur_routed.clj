(ns propagators.gur-routed
  "Parallel routed-message experiment for general unbounded recursion.

  Frames communicate with their parent through explicit reality IO records.
  Child networks emit pure topology declaration values; the parent runner
  translates those declarations into idempotent parent-side topology installs."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.io :as io]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.reality :as reality]
            [propagators.recursive :as recursive]
            [propagators.runtime :as runtime]
            [propagators.stdlib.prop :as stdlib-prop]))

(def prop-ids-key :gur-routed/prop-ids)
(def installed-declarations-key :gur-routed/installed-declarations)
(def routed-map-props-key :gur-routed/accessor-map-props)
(def routed-map-frames-key :gur-routed/accessor-map-frames)

(def declaration-id-key :gur-routed/id)
(def declaration-op-key :gur-routed/op)
(def declaration-args-key :gur-routed/args)

(def default-topology-io-id :topology)

(defn topology-declaration
  "Build a pure topology declaration value for a routed frame outbox."
  [id op args]
  {declaration-id-key id
   declaration-op-key op
   declaration-args-key args})

(defn topology-declaration?
  [x]
  (and (map? x)
       (contains? x declaration-id-key)
       (contains? x declaration-op-key)
       (contains? x declaration-args-key)))

(defn- declaration-id [declaration] (get declaration declaration-id-key))
(defn- declaration-op [declaration] (get declaration declaration-op-key))
(defn- declaration-args [declaration] (get declaration declaration-args-key))

(defn- input-route
  [[io-id parent-id child-id]]
  {:io-id io-id :parent-id parent-id :child-id child-id})

(defn- output-route
  [[io-id parent-id]]
  [io-id parent-id])

(defn- prop-ids
  [frame-net]
  (vec (or (net/network-dict-entry frame-net prop-ids-key) [])))

(defn install-boundary
  "Install reality IO ports into a routed frame and record their prop ids."
  [network {:keys [inputs outputs]}]
  (let [{n1 :net input-props :prop-ids}
        (reduce
         (fn [{:keys [net prop-ids]} [io-id child-id]]
           (let [[prop-id n] ((reality/p:reality-in io-id child-id)
                              (nb/ensure-cell net child-id))]
             {:net n
              :prop-ids (conj prop-ids prop-id)}))
         {:net network :prop-ids []}
         inputs)
        {n2 :net output-props :prop-ids}
        (reduce
         (fn [{:keys [net prop-ids]} [io-id child-id]]
           (let [[prop-id n] ((reality/p:reality-out io-id child-id)
                              (nb/ensure-cell net child-id))]
             {:net n
              :prop-ids (conj prop-ids prop-id)}))
         {:net n1 :prop-ids []}
         outputs)
        all-props (into input-props output-props)]
    {:net (net/update-net-dict-entry n2
                                     prop-ids-key
                                     #(into (vec (or % [])) all-props))
     :prop-ids all-props}))

(defn- inject-input
  [child-net parent-net {:keys [io-id parent-id child-id]}]
  (let [v (net/network-cell-strongest parent-net parent-id)]
    (if (value/unusable? v)
      child-net
      (reality/inject-input child-net io-id child-id v))))

(defn- translate-outbox
  [output-map topology-io-id records]
  (keep (fn [{:keys [id message]}]
          (cond
            (= topology-io-id id)
            nil

            (contains? output-map id)
            (message/message (get output-map id)
                             (message/message-value message))

            (= :escaped id)
            message

            :else
            nil))
        records))

(declare default-declaration-installer)

(defn- topology-installer-delivery
  [declaration->installer declaration]
  (let [payload (declaration->installer declaration)]
    (when payload
      (io/io-delivery :apply-topology-installer payload))))

(defn- topology-deliveries
  [declaration->installer topology-io-id records]
  (keep (fn [{:keys [id message]}]
          (when (= topology-io-id id)
            (let [declaration (message/message-value message)]
              (when-not (value/unusable? declaration)
                (topology-installer-delivery declaration->installer
                                             declaration)))))
        records))

(defn run-routed-frame
  "Run a frame network value through declared IO routes."
  ([frame-net parent-net input-routes output-routes topology-io-id]
   (run-routed-frame frame-net
                     parent-net
                     input-routes
                     output-routes
                     topology-io-id
                     default-declaration-installer))
  ([frame-net parent-net input-routes output-routes topology-io-id
    declaration->installer]
   (let [inputs (mapv input-route input-routes)
         outputs (into {} (map output-route output-routes))
         child0 (reduce #(inject-input %1 parent-net %2) frame-net inputs)
         child1 (runtime/continue (io/enqueue-props child0 (prop-ids child0)))
         [records child2] (io/drain-outbox child1)]
     {:messages (vec (translate-outbox outputs topology-io-id records))
      :topology-deliveries (vec (topology-deliveries declaration->installer
                                                     topology-io-id
                                                     records))
      :frame-net (io/stored-lexical-env child2)})))

(defn p:routed-run-frame
  "Run a network-valued frame cell through explicit message routes.

  `input-routes` are `[io-id parent-cell-id child-cell-id]`.
  `output-routes` are `[io-id parent-cell-id]`.
  `topology-io-id` identifies child outbox records that carry topology
  declarations for parent-side installation.
  "
  ([frame-net-id input-routes output-routes topology-io-id]
   (p:routed-run-frame frame-net-id
                       input-routes
                       output-routes
                       topology-io-id
                       default-declaration-installer))
  ([frame-net-id input-routes output-routes topology-io-id
    declaration->installer]
   (let [inputs (mapv input-route input-routes)
         parent-inputs (mapv :parent-id inputs)
         parent-outputs (mapv second output-routes)]
     (prop/construct-propagator
      (fn [_input-ids _output-ids parent-net]
        (let [frame-value (net/network-cell-strongest parent-net frame-net-id)]
          (cond
            (value/nothing? frame-value)
            []

            (not (net/network? frame-value))
            [(message/message frame-net-id value/contradiction)]

            :else
            (let [{:keys [messages topology-deliveries frame-net]}
                  (run-routed-frame frame-value
                                    parent-net
                                    input-routes
                                    output-routes
                                    topology-io-id
                                    declaration->installer)]
              (into (conj messages (message/message frame-net-id frame-net))
                    topology-deliveries)))))
      (into [frame-net-id] parent-inputs)
      (into [frame-net-id] parent-outputs)))))

(defn install-routed-frame
  [network {:keys [frame-id frame-net input-routes output-routes topology-io-id
                   declaration-installer]
            :or {topology-io-id default-topology-io-id}}]
  (let [n0 (if (contains? (net/net-env network) frame-id)
             network
             (nb/install-cell network frame-id frame-net frame-net))
        [runner-prop n1] ((p:routed-run-frame frame-id
                                              input-routes
                                              output-routes
                                              topology-io-id
                                              (or declaration-installer
                                                  default-declaration-installer))
                          n0)]
    {:net n1
     :prop-ids [runner-prop]}))

(defn- cell-present?
  [network id]
  (and (ids/node-id? id)
       (contains? (net/net-env network) id)))

(defn- strongest-or-nothing
  [network id]
  (if (cell-present? network id)
    (net/network-cell-strongest network id)
    value/nothing))

(defn- declared-slot-parent-id
  [network source-id slot-key]
  (->> (get-in (net/net-dict-or-empty network)
               [obj/slot-declarations-key source-id slot-key])
       keys
       (sort-by pr-str)
       first))

(defn- accessor-shell
  [network source-id]
  (let [v (strongest-or-nothing network source-id)]
    (when-not (value/unusable? v)
      (let [shell (obj/as-accessor-network v)]
        (when-not (value/contradiction? shell)
          shell)))))

(defn- shell-slot-parent-id
  [network source-id slot-key]
  (when-let [shell (accessor-shell network source-id)]
    (->> (obj/accessor-parent-ids shell slot-key)
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
    (let [shell (obj/as-accessor-network source-value)]
      (and (not (value/contradiction? shell))
           (seq (obj/accessor-parent-ids shell :car))
           (seq (obj/accessor-parent-ids shell :cdr))))))

(defn- accessor-network-value?
  [v]
  (and (not (value/unusable? v))
       (let [shell (obj/as-accessor-network v)]
         (and (not (value/contradiction? shell))
              (obj/accessor-network? shell)))))

(declare install-routed-accessor-map-topology)

(defn- routed-map-declaration
  [closure-id acc-id source-id out-id visited]
  (topology-declaration
   [:gur-routed/accessor-map source-id out-id]
   :install-accessor-map
   {:closure-id closure-id
    :acc-id acc-id
    :source-id source-id
    :out-id out-id
    :visited (vec visited)}))

(defn- wait-frame-net
  [closure-id acc-id source-id out-id visited topology-io-id]
  (let [source-in-id (ids/new-node-id)
        topology-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell source-in-id)
               (nb/install-cell topology-id))
        [watch-prop n1]
        ((prop/construct-propagator
          (fn [_inputs _outputs n]
            (let [source-value (strongest-or-nothing n source-in-id)]
              (cond
                (value/nothing? source-value)
                []

                (value/contradiction? source-value)
                [(message/message topology-id value/contradiction)]

                (list-ready-value? source-value)
                [(message/message topology-id
                                  (routed-map-declaration closure-id
                                                          acc-id
                                                          source-id
                                                          out-id
                                                          visited))]

                (not (accessor-network-value? source-value))
                [(message/message topology-id
                                  (routed-map-declaration closure-id
                                                          acc-id
                                                          source-id
                                                          out-id
                                                          visited))]

                :else
                [])))
          [source-in-id]
          [topology-id])
         n0)
        {n2 :net boundary-props :prop-ids}
        (install-boundary n1
                          {:inputs [[:source source-in-id]]
                           :outputs [[topology-io-id topology-id]]})]
    {:net (net/update-net-dict-entry n2
                                     prop-ids-key
                                     #(into (vec (or % []))
                                            (into [watch-prop]
                                                  boundary-props)))
     :source-in-id source-in-id}))

(defn- install-wait-frame
  [network closure-id acc-id source-id out-id visited]
  (let [frame-id (ids/new-node-id)
        {frame-net :net source-in-id :source-in-id}
        (wait-frame-net closure-id
                        acc-id
                        source-id
                        out-id
                        visited
                        default-topology-io-id)
        {:keys [net prop-ids]}
        (install-routed-frame network
                              {:frame-id frame-id
                               :frame-net frame-net
                               :input-routes [[:source source-id source-in-id]]
                               :output-routes []
                               :topology-io-id default-topology-io-id})]
    {:net (net/update-net-dict-entry net
                                     routed-map-frames-key
                                     (fnil conj [])
                                     frame-id)
     :prop-ids prop-ids}))

(defn- terminal-copy-activation
  [source-id out-id]
  (fn [_inputs _outputs network]
    (let [source-value (strongest-or-nothing network source-id)]
      (cond
        (value/nothing? source-value)
        []

        (value/contradiction? source-value)
        [(message/message out-id value/contradiction)]

        (list-ready-value? source-value)
        []

        :else
        [(message/message out-id source-value)]))))

(defn- install-terminal-copy
  [network source-id out-id]
  (let [[prop-id n1] ((prop/construct-propagator
                       (terminal-copy-activation source-id out-id)
                       [source-id]
                       [out-id])
                      network)]
    {:net n1
     :prop-ids [prop-id]}))

(defn- install-leaf-map
  [network closure-id acc-id source-id out-id]
  (let [n0 (nb/ensure-cell network out-id)
        [prop-id n1] ((recursive/p:accumulating-recursive-compound
                       closure-id
                       source-id
                       acc-id
                       out-id)
                      n0)]
    {:net n1
     :prop-ids [prop-id]}))

(defn- merge-install-results
  [& results]
  {:net (:net (last results))
   :prop-ids (vec (mapcat :prop-ids results))})

(defn- install-routed-accessor-value-map
  [network closure-id acc-id source-id out-id visited]
  (if (and (not (contains? visited source-id))
           (list-node? network source-id))
    (install-routed-accessor-map-topology network
                                          closure-id
                                          acc-id
                                          source-id
                                          out-id
                                          visited)
    (install-leaf-map network closure-id acc-id source-id out-id)))

(defn- install-routed-accessor-map-topology
  [network closure-id acc-id source-id out-id visited]
  (let [n0 (nb/ensure-cell network out-id)]
    (cond
      (contains? visited source-id)
      (let [[prop-id n1] ((stdlib-prop/id source-id out-id) n0)]
        {:net n1
         :prop-ids [prop-id]})

      (list-node? n0 source-id)
      (let [car-id (list-slot-parent-id n0 source-id :car)
            cdr-id (list-slot-parent-id n0 source-id :cdr)
            mapped-car-id (ids/new-node-id)
            mapped-cdr-id (ids/new-node-id)
            n1 (-> n0
                   (nb/install-cell mapped-car-id)
                   (nb/install-cell mapped-cdr-id))
            car-result (install-routed-accessor-value-map n1
                                                          closure-id
                                                          acc-id
                                                          car-id
                                                          mapped-car-id
                                                          (conj visited
                                                                source-id))
            cdr-result (install-routed-accessor-map-topology (:net car-result)
                                                             closure-id
                                                             acc-id
                                                             cdr-id
                                                             mapped-cdr-id
                                                             (conj visited
                                                                   source-id))
            [[car-prop cdr-prop] n2]
            ((obj/p:network-cons mapped-car-id mapped-cdr-id out-id)
             (:net cdr-result))]
        {:net n2
         :prop-ids (into (vec (:prop-ids car-result))
                         (into (vec (:prop-ids cdr-result))
                               [car-prop cdr-prop]))})

      (accessor-network-value? (strongest-or-nothing n0 source-id))
      (let [copy-result (install-terminal-copy n0 source-id out-id)
            wait-result (install-wait-frame (:net copy-result)
                                            closure-id
                                            acc-id
                                            source-id
                                            out-id
                                            visited)]
        (merge-install-results copy-result wait-result))

      (value/nothing? (strongest-or-nothing n0 source-id))
      (install-wait-frame n0 closure-id acc-id source-id out-id visited)

      :else
      (install-leaf-map n0 closure-id acc-id source-id out-id))))

(defn- declaration-installer-payload
  [declaration installer]
  {:id (declaration-id declaration)
   :installed-key installed-declarations-key
   :installer installer})

(defn default-declaration-installer
  [declaration]
  (when-not (topology-declaration? declaration)
    (throw (ex-info "unknown routed topology declaration"
                    {:declaration declaration})))
  (case (declaration-op declaration)
    :install-frame
    (declaration-installer-payload
     declaration
     (fn [network]
       (install-routed-frame network (declaration-args declaration))))

    :install-accessor-map
    (declaration-installer-payload
     declaration
     (fn [network]
       (let [{:keys [closure-id acc-id source-id out-id visited]}
             (declaration-args declaration)]
         (install-routed-accessor-map-topology network
                                               closure-id
                                               acc-id
                                               source-id
                                               out-id
                                               (set visited)))))

    (throw (ex-info "unsupported routed topology declaration op"
                    {:declaration declaration}))))

(defn p:routed-accessor-recursive-map
  "Experimental route-driven recursive map over live accessor cons cells."
  [closure-id acc-id source-id out-id]
  (fn [network]
    (let [{:keys [net prop-ids]}
          (install-routed-accessor-map-topology network
                                                closure-id
                                                acc-id
                                                source-id
                                                out-id
                                                #{})]
      [prop-ids
       (net/update-net-dict-entry net
                                  routed-map-props-key
                                  #(into (vec (or % [])) prop-ids))])))
