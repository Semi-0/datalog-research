(ns propagators.layered.runtime
  "Explicit datum preparation and scope adaptation outside layer dispatch."
  (:require [clojure.set :as set]
            [propagators.application :as application]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.named-network :as named]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.ids :as ids]
            [propagators.layered.dispatcher :as dispatcher]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(defn- layer-object-net
  [layer->value]
  (reduce
   (fn [n [layer-name v]]
     (nb/add-named-cell n layer-name v))
   (net/net-with-dict net/empty-net {:slot-index {}})
   layer->value))

(defn- object-layer-values
  [v]
  (into {}
        (map (fn [layer-name] [layer-name (obj/slot-value v layer-name)]))
        (obj/public-slot-keys v)))

(defn- add-provenance
  [layered-value provenance]
  (if (empty? provenance)
    layered-value
    (let [layers (object-layer-values layered-value)]
      (layer-object-net
       (assoc layers
              :provenance
              (set/union (if (set? (:provenance layers))
                           (:provenance layers)
                           #{})
                         provenance))))))

(defn- normalize-layered-value
  [v]
  (let [scoped? (scope-source/scope-value? v)
        base (if scoped? (scope-source/base-value v) v)
        layered-value (cond
                        (named/named-network? base) base
                        (value/nothing? base) (net/net-with-dict net/empty-net
                                                               {:slot-index {}})
                        :else (layer-object-net {:base base}))]
    (if scoped?
      (add-provenance layered-value (scope-source/dependencies v))
      layered-value)))

(defn- layer-values-from-object
  [v]
  (cond
    (named/named-network? v)
    (object-layer-values v)

    (value/unusable? v)
    {}

    :else
    {:base v}))

(defn- copy-outer-cell
  ([n outer-net id]
   (copy-outer-cell n outer-net id identity))
  ([n outer-net id normalize]
   (cond
     (contains? (net/net-env n) id)
     n

     (contains? (net/net-env outer-net) id)
     (let [v (normalize (net/network-cell-strongest outer-net id))]
       (nb/install-cell n id v v))

     :else
     (nb/install-cell n id))))

(defn- strongest-or-nothing
  [n id]
  (if (contains? (net/net-env n) id)
    (net/network-cell-strongest n id)
    value/nothing))

(defn- declared-procedure-layer-ids
  [outer-net proc-id]
  (->> (merge-with merge
                   (obj/slot-declarations-for outer-net proc-id)
                   (obj/accessor-declarations-for outer-net proc-id))
       (mapcat (fn [[layer-name parent->declaration]]
                 (map (fn [closure-id] [layer-name closure-id])
                      (keys parent->declaration))))
       (sort-by (fn [[layer-name closure-id]]
                  [(pr-str layer-name) (pr-str closure-id)]))
       vec))

(defn materialize-layered-cell-value
  [outer-net object-id]
  (let [declared-layers (declared-procedure-layer-ids outer-net object-id)]
    (if (empty? declared-layers)
      (normalize-layered-value (strongest-or-nothing outer-net object-id))
      (let [empty-object (obj/empty-compound-object)
            n0 (nb/install-cell net/empty-net object-id empty-object empty-object)
            n1 (reduce (fn [acc [_layer-name layer-value-id]]
                         (copy-outer-cell acc outer-net layer-value-id))
                       n0
                       declared-layers)
            [slot-prop-ids n2]
            (reduce
             (fn [[prop-ids acc] [layer-name layer-value-id]]
               (let [[prop-id acc'] ((obj/p:legacy-slot layer-name
                                                         layer-value-id
                                                         object-id)
                                     acc)]
                 [(conj prop-ids prop-id) acc']))
             [[] n1]
             declared-layers)
            materialized-net
            (runner/completed-network
             (runner/run-network slot-prop-ids n2))]
        (strongest-or-nothing materialized-net object-id)))))

(defn- materialize-procedure
  [outer-net proc-id]
  (let [raw-value (strongest-or-nothing outer-net proc-id)
        scoped? (scope-source/scope-value? raw-value)
        proc-value (if scoped?
                     (scope-source/base-value raw-value)
                     (materialize-layered-cell-value outer-net proc-id))]
    {:value proc-value
     :layer-values (layer-values-from-object proc-value)
     :operator-provenance (if scoped?
                            (scope-source/dependencies raw-value)
                            #{})}))


(defn prepare [network ports]
  (let [{:keys [operator-id argument-ids output-id]} ports
        procedure (materialize-procedure network operator-id)
        previous-id (ids/new-node-id)
        specifications
        (into [(application/value-cell operator-id (:value procedure))
               (application/value-cell previous-id
                                       (materialize-layered-cell-value network output-id))]
              (map (fn [id]
                     (application/value-cell id
                       (materialize-layered-cell-value network id))))
              argument-ids)]
    {:ports (assoc ports :previous-output-id previous-id)
     :cell-specs specifications :procedures (:layer-values procedure)}))

(defn- scope-dependencies [network ids]
  (reduce (fn [dependencies id]
            (let [datum (strongest-or-nothing network id)]
              (if (scope-source/scope-value? datum)
                (set/union dependencies (scope-source/dependencies datum))
                dependencies)))
          #{} ids))

(defn scope-publication [ports]
  (fn [patches _inputs _outputs network]
    (let [dependencies (scope-dependencies
                        network (into [(:operator-id ports)] (:argument-ids ports)))]
      (if (empty? dependencies)
        patches
        (mapv (fn [patch]
                (if (message/message? patch)
                  (update patch :value
                          (fn [datum]
                            (add-provenance (normalize-layered-value datum)
                                            dependencies)))
                  patch))
              patches)))))

(defn activation [operator-id arguments output-id]
  (let [ports {:operator-id operator-id :argument-ids (vec arguments)
               :output-id output-id}]
    (prop/compose-activation
     (fn [_inputs _outputs network]
       (let [procedure (strongest-or-nothing network operator-id)]
         (if (value/nothing? procedure)
           []
           (let [prepared (prepare network ports)
                 app (dispatcher/build prepared)
                 after (application/run-reduced-application app)
                 result-id (:reduced-out-id app)]
             (application/diff-reduced-output network after result-id output-id)))))
     (scope-publication ports))))
