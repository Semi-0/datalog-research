(ns propagators.layered
  "Layered data/procedure support built from compound-object slots."
  (:require [clojure.set :as set]
            [propagators.application :as application]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.named-network :as named]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.dispatch :as dispatch]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as stdlib-prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(defn p:layer
  "Bidirectional sync between a layer value cell and a layered object slot."
  [layer-name layer-value-id layered-object-id]
  (obj/p:slot layer-name layer-value-id layered-object-id))

(defn p:base
  [base-value-id layered-object-id]
  (p:layer :base base-value-id layered-object-id))

(defn layer-addressable?
  "True when `v` declares `layer-name` as compound topology."
  [v layer-name]
  (and (named/named-network? v)
       (contains? (obj/accessor-slot-keys v) layer-name)))

(defn layer-parent-id
  "Return an already-declared outer cell for one live accessor layer."
  [network object-id layer-name]
  (->> (get (obj/accessor-declarations-for network object-id) layer-name)
       keys
       (filter #(contains? (net/net-env network) %))
       (sort-by pr-str)
       first))

(defn- stable-layer-id
  [& parts]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:layered/transport] parts))
               StandardCharsets/UTF_8))))

(defn- add-installer
  [{:keys [net props]} installer]
  (let [[prop-id installed] (installer net)]
    {:net installed :props (conj props prop-id)}))

(defn- add-layer-port
  [declared object-id layer-name port-id direction]
  (let [object-value (net/network-cell-strongest (:net declared) object-id)
        layered? (or (value/nothing? object-value)
                     (named/named-network? object-value))]
    (if layered?
      (add-installer declared (p:layer layer-name port-id object-id))
      (if (= :base layer-name)
        (case direction
          :source (add-installer declared (stdlib-prop/id object-id port-id))
          :target (add-installer declared (stdlib-prop/id port-id object-id))
          :both (-> declared
                    (add-installer (stdlib-prop/id object-id port-id))
                    (add-installer (stdlib-prop/id port-id object-id))))
        declared))))

(defn declare-forward-layer
  "Declare one directional link between corresponding object layers.

  Scalar sources remain scalar cells. Compound/accessor values are addressed
  through live slot topology and are never materialized."
  [network declaration-key layer-name from-id to-id]
  (let [from-layer-id (stable-layer-id declaration-key layer-name :from)
        to-layer-id (stable-layer-id declaration-key layer-name :to)
        declared {:net (-> network
                           (nb/ensure-cell from-layer-id)
                           (nb/ensure-cell to-layer-id))
                  :props []}]
    (-> declared
        (add-layer-port from-id layer-name from-layer-id :source)
        (add-layer-port to-id layer-name to-layer-id :target)
        (add-installer (stdlib-prop/id from-layer-id to-layer-id)))))

(defn declare-layer-reader
  "Declare a live layer projection into `out-id`.

  Raw scalar cells are already base-layer cells; compound values use slot
  topology. No value is copied into a host snapshot."
  [network declaration-key layer-name object-id out-id]
  (add-layer-port
   {:net (nb/ensure-cell network out-id) :props []}
   object-id
   layer-name
   out-id
   :source))

(defn declare-bidirectional-layer
  "Declare a shared live layer between two objects without snapshotting them."
  [network declaration-key layer-name left-id right-id]
  (let [layer-id (stable-layer-id declaration-key layer-name :shared)
        declared {:net (nb/ensure-cell network layer-id) :props []}]
    (-> declared
        (add-layer-port left-id layer-name layer-id :both)
        (add-layer-port right-id layer-name layer-id :both))))

(defn declare-forward-layer-chain
  [network declaration-key layer-name ids]
  (reduce (fn [{:keys [net props]} [index [from-id to-id]]]
            (let [declared (declare-forward-layer net
                                                  [declaration-key index]
                                                  layer-name
                                                  from-id
                                                  to-id)]
              {:net (:net declared)
               :props (into props (:props declared))}))
          {:net network :props []}
          (map-indexed vector (partition 2 1 ids))))

(defn declare-bidirectional-layer-chain
  [network declaration-key layer-name ids]
  (reduce (fn [{:keys [net props]} [index [left-id right-id]]]
            (let [declared (declare-bidirectional-layer
                            net [declaration-key index] layer-name left-id right-id)]
              {:net (:net declared)
               :props (into props (:props declared))}))
          {:net network :props []}
          (map-indexed vector (partition 2 1 ids))))

(defn transport-value
  "Move one complete information value through a layered boundary.

  Scope envelopes and accessor networks are opaque here. Their own cells and
  layered procedures retain authority for interpretation and selection."
  [source]
  source)

(defn forward-transport-messages
  "One-way layered transport. `read-update` owns content/history policy."
  [read-update network from-id to-id]
  (let [source (read-update network from-id)]
    (if (nil? source)
      []
      [(message to-id (transport-value source))])))

(defn bidirectional-transport-messages
  "Bidirectional layered transport without materializing either accessor."
  [read-update network left-id right-id]
  (into (forward-transport-messages read-update network left-id right-id)
        (forward-transport-messages read-update network right-id left-id)))

(defn p:layered-procedure
  "Attach one closure cell as a layer on a layered procedure cell."
  [layer-name closure-id proc-id]
  (p:layer layer-name closure-id proc-id))

(defn install-layered-procedure!
  "Declare a reactive procedure layer.

  This is topology only: it ensures the procedure and closure cells exist,
  installs the slot propagator, and runs it once so its declaration is merged
  into the procedure cell. It does not seed layer values."
  [n proc-id layer-name closure-id]
  (let [n0 (-> n
               (nb/ensure-cell proc-id)
               (nb/ensure-cell closure-id))
        [prop-id n1] ((p:layered-procedure layer-name closure-id proc-id) n0)]
    {:net (nb/run-propagators n1 [prop-id])
     :prop prop-id
     :closure closure-id}))

(declare p:apply-layered)

(defn p:layered-operator
  "Create a propagator installer backed by a layered-procedure cell."
  ([layered-procedure-id]
   (p:layered-operator :layered/apply layered-procedure-id))
  ([name layered-procedure-id]
   (fn [& node-ids]
     (let [nodes (vec node-ids)
           args (vec (butlast nodes))
           out (last nodes)]
       (p:apply-layered name layered-procedure-id args out)))))

(defn- layer-object-net
  [layer->value]
  (reduce
   (fn [n [layer-name v]]
     (nb/add-named-cell n layer-name v))
   (net/net-with-dict net/empty-net {:slot-index {}})
   layer->value))

(defn- object-layer-values
  [v]
  (->> (obj/public-slot-keys v)
       (keep (fn [layer-name]
               (let [layer-value (obj/slot-value v layer-name)]
                 (when (and (some? layer-value)
                            (not (value/unusable? layer-value)))
                   [layer-name layer-value]))))
       (into {})))

(defn- add-provenance
  [layered-value provenance]
  (if (value/contradiction? layered-value)
    (value/add-contradiction-provenance layered-value provenance)
    (if (empty? provenance)
    layered-value
    (let [layers (object-layer-values layered-value)]
      (layer-object-net
       (assoc layers
              :provenance
              (set/union (if (set? (:provenance layers))
                           (:provenance layers)
                           #{})
                         provenance)))))))

(defn- layered-base-value
  [v]
  (cond
    (scope-source/scope-value? v)
    (layered-base-value (scope-source/base-value v))

    (named/named-network? v)
    (obj/slot-value v :base)

    :else v))

(defn- layered-contradiction?
  [v]
  (value/contradiction? (layered-base-value v)))

(defn- value-provenance
  [v]
  (cond
    (scope-source/scope-value? v)
    (set/union (scope-source/dependencies v)
               (value-provenance (scope-source/base-value v)))

    (value/contradiction? v)
    (value/contradiction-provenance v)

    (named/named-network? v)
    (let [provenance (obj/slot-value v :provenance)
          base (obj/slot-value v :base)]
      (set/union (if (set? provenance) provenance #{})
                 (value/contradiction-provenance base)))

    :else #{}))

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

(defn- usable-layer-value?
  [v]
  (and (some? v)
       (not (value/unusable? v))))

(defn- layer-values-from-object
  [v]
  (cond
    (named/named-network? v)
    (object-layer-values v)

    (value/unusable? v)
    {}

    :else
    {:base v}))

(defn- available-branches
  [v]
  (set (keys (layer-values-from-object v))))

(defn- layer-present?
  [v layer-name]
  (contains? (available-branches v) layer-name))

(defn- install-layer-closure-cell
  [n proc-id layer-name layer-values]
  (let [closure-id (ids/new-node-id)]
    (if (contains? layer-values layer-name)
      {:closure-id closure-id
       :slot-prop-ids []
       :network (nb/install-cell n
                                 closure-id
                                 (get layer-values layer-name)
                                 (get layer-values layer-name))}
      (let [[slot-prop-id n'] ((p:layer layer-name closure-id proc-id)
                               (nb/install-cell n closure-id))]
        {:closure-id closure-id
         :slot-prop-ids [slot-prop-id]
         :network n'}))))

(defn- install-base-layer
  [n proc-id arg-ids result-bank-id layer-prop-ids layer-values]
  (let [{:keys [closure-id slot-prop-ids network]}
        (install-layer-closure-cell n proc-id :base layer-values)
        arg-base-ids (vec (repeatedly (count arg-ids) ids/new-node-id))
        out-base-id (ids/new-node-id)
        n1 (reduce
            (fn [acc id] (if (contains? (net/net-env acc) id) acc (nb/install-cell acc id)))
            network
            (into [out-base-id] arg-base-ids))
        [arg-slot-prop-ids n2]
        (reduce
         (fn [[prop-ids acc] [arg-id arg-base-id]]
           (let [[p acc'] ((p:base arg-base-id arg-id) acc)]
             [(conj prop-ids p) acc']))
         [[] n1]
         (map vector arg-ids arg-base-ids))
        [compound-prop-id n3] ((prop/compound-propagator closure-id arg-base-ids [out-base-id]) n2)
        [out-slot-prop-id n4] ((p:base out-base-id result-bank-id) n3)]
    [n4 (into layer-prop-ids
              (concat arg-slot-prop-ids
                      slot-prop-ids
                      [compound-prop-id out-slot-prop-id]))]))

(defn- install-non-base-layer
  [n proc-id arg-ids out-id result-bank-id layer-name layer-prop-ids layer-values]
  (let [{:keys [closure-id slot-prop-ids network]}
        (install-layer-closure-cell n proc-id layer-name layer-values)
        current-id (ids/new-node-id)
        result-id (ids/new-node-id)
        n1 (-> network
               (nb/install-cell current-id)
               (nb/install-cell result-id))
        [current-slot-prop-id n2] ((p:layer layer-name current-id out-id) n1)
        [compound-prop-id n3] ((prop/compound-propagator closure-id
                                                         (into [current-id] arg-ids)
                                                         [result-id])
                               n2)
        [result-slot-prop-id n4] ((p:layer layer-name result-id result-bank-id) n3)]
    [n4 (into layer-prop-ids
              (concat slot-prop-ids
                      [current-slot-prop-id
                       compound-prop-id
                       result-slot-prop-id]))]))

(defn- install-layer-application
  [n proc-id arg-ids out-id result-bank-id layer-name arg-values prop-ids layer-values]
  (cond
    (= layer-name :base)
    (conj (install-base-layer n proc-id arg-ids result-bank-id prop-ids layer-values) true)

    (some #(layer-present? % layer-name) arg-values)
    (conj (install-non-base-layer n
                                  proc-id
                                  arg-ids
                                  out-id
                                  result-bank-id
                                  layer-name
                                  prop-ids
                                  layer-values)
          true)

    :else
    [n prop-ids false]))

(defn- install-layer-branches
  [n frame proc-id arg-ids out-id layers arg-values layer-values]
  (let [{:keys [result-bank-id]} frame
        [branch-net branch-prop-ids active-layers]
        (reduce
         (fn [[n prop-ids active-layers] layer-name]
           (let [[n' prop-ids' installed?]
                 (install-layer-application n
                                            proc-id
                                            arg-ids
                                            out-id
                                            result-bank-id
                                            layer-name
                                            arg-values
                                            prop-ids
                                            layer-values)]
             [n' prop-ids' (cond-> active-layers installed? (conj layer-name))]))
         [n [] []]
         layers)]
    {:net branch-net
     :branch-prop-ids branch-prop-ids
     :active-layers active-layers}))

(declare materialize-layered-cell-value)

(defn- layered-cell-specs
  [outer-net proc-id arg-ids out-id proc-value]
  (into [(application/value-cell proc-id proc-value)
         (application/value-cell out-id
                                 (materialize-layered-cell-value outer-net out-id))]
        (map #(application/value-cell
               %
               (materialize-layered-cell-value outer-net %)))
        arg-ids))

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

(defn- materialize-layered-cell-value
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
            materialized-net (nb/run-propagators n2 slot-prop-ids)]
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

(defn- add-result-provenance
  [n result-id provenance]
  (let [result (strongest-or-nothing n result-id)]
    (if (and (empty? provenance)
             (not (layered-contradiction? result)))
      n
      (let [result' (if (layered-contradiction? result)
                      (value/contradiction-with-provenance
                       (set/union provenance (value-provenance result)))
                      (add-provenance (normalize-layered-value result)
                                      provenance))]
        (net/assoc-net-cell n result-id (cell/cell result' result'))))))

(defn- build-layered-application
  [outer-net proc-id arg-ids out-id layers arg-values layer-values proc-value]
  (application/build-branch-application
   {:cell-specs (layered-cell-specs outer-net proc-id arg-ids out-id proc-value)
    :install-branches
    (fn [n frame]
      (let [[n' materialization-props]
            (reduce
             (fn [[network prop-ids] [arg-id arg-value]]
               (reduce-kv
                (fn [[network prop-ids] layer-name layer-value]
                  (let [layer-id (ids/new-node-id)
                        network' (nb/install-cell network layer-id layer-value layer-value)
                        [prop-id network''] ((p:layer layer-name layer-id arg-id)
                                             network')]
                    [network'' (conj prop-ids prop-id)]))
                [network prop-ids]
                (layer-values-from-object arg-value)))
             [n []]
             (map vector arg-ids arg-values))
            materialized (nb/run-propagators n' materialization-props)
            branches (install-layer-branches materialized
                                               frame
                                               proc-id
                                               arg-ids
                                               out-id
                                               layers
                                               arg-values
                                               layer-values)]
        branches))
    :reducer-install (fn [{:keys [active-layers result-bank-id reduced-out-id]}]
                       (dispatch/reduce-results
                        (dispatch/layered-object-policy active-layers)
                        result-bank-id
                        reduced-out-id))
    :trace (fn [{:keys [active-layers result-bank-id reduced-out-id]}]
             {:trace/type :layered
              :result-bank-id result-bank-id
              :slots active-layers
              :reduced-out-id reduced-out-id})}))

(defn- layered-apply-activate
  [proc-id arg-ids out-id]
  (fn [_input-ids _output-ids outer-net]
    (let [raw-arg-values (application/cell-values outer-net arg-ids)
          raw-proc-value (strongest-or-nothing outer-net proc-id)]
      (if (or (value/nothing? raw-proc-value)
              (some value/nothing? raw-arg-values))
        []
        (let [arg-values (mapv #(materialize-layered-cell-value outer-net %)
                               arg-ids)
              {proc-value :value
               layer-values :layer-values
               operator-provenance :operator-provenance}
              (materialize-procedure outer-net proc-id)
              lexical-provenance
              (apply set/union
                     operator-provenance
                     (map (fn [id]
                            (let [v (strongest-or-nothing outer-net id)]
                              (if (scope-source/scope-value? v)
                                (scope-source/dependencies v)
                                #{})))
                          arg-ids))
              contradiction-provenance
              (apply set/union
                     lexical-provenance
                     (map value-provenance
                          (into [proc-value] arg-values)))
              layers (sort-by pr-str (keys layer-values))]
          (if (some layered-contradiction?
                    (into [proc-value] arg-values))
            [(message out-id
                      (value/contradiction-with-provenance
                       contradiction-provenance))]
            (if (empty? layers)
            []
            (let [{:keys [reduced-out-id] :as app}
                  (build-layered-application outer-net
                                             proc-id
                                             arg-ids
                                             out-id
                                             layers
                                             arg-values
                                             layer-values
                                             proc-value)
                  after (-> (application/run-reduced-application app)
                            (add-result-provenance reduced-out-id
                                                   lexical-provenance))]
              (application/diff-reduced-output outer-net after reduced-out-id out-id)))))))))

(defn p:apply-layered
  ([proc-id arg-ids out-id]
   (p:apply-layered :layered/apply proc-id arg-ids out-id))
  ([name proc-id arg-ids out-id]
   (prop/construct-propagator
    name
    (layered-apply-activate proc-id (vec arg-ids) out-id)
    (into [proc-id] arg-ids)
    [out-id])))
