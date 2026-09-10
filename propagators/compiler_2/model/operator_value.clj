(ns propagators.compiler-2.model.operator-value
  "Explicit compiler-2 operator closures.

  These values replace env-bound primitive functions that previously carried
  compiler hooks in Clojure metadata. Every first-class callable cell contains
  a canonical accumulating-GUR closure. Its retained declaration remains
  inspectable through `operator-declaration`; application installs topology
  through the accumulating-GUR runner.
  "
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur.accumulating.core :as gur-core]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as stdlib-prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def operator-kind :compiler-2/operator-closure)

(def kind-slot :operator/kind)
(def input-selector-slot :operator/input-selector)
(def install-slot :operator/install)
(def static-installer-slot :operator/static-installer)
(def compiler-activate-slot :operator/compiler-activate)
(def direct-installer-slot :operator/direct-installer)
(def direct-compiler-slot :operator/direct-compiler)
(def activate-slot :operator/activate)
(def output-selector-slot :operator/output-selector)
(def contextual?-slot :operator/contextual?)
(def name-slot :operator/name)

(defn fixed-boundary-cell-ids
  [& cell-ids]
  (let [cell-ids (vec (filter ids/node-id? cell-ids))]
    (fn [_network _arg-ids]
      cell-ids)))

(def effect-boundary-dict-keys
  [:program/epoch :runtime/commit-tick])

(defn- stable-declaration-id
  [name]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str [:compiler-2/operator name])
               StandardCharsets/UTF_8))))

(defn operator-declaration
  [operator]
  (cond
    (gur-core/recursive-closure? operator)
    (get operator gur-core/declaration-key value/nothing)

    :else
    operator))

(defn- unsupported-application
  [network out-id name]
  (let [network* (nb/ensure-cell network out-id)
        [prop-id declared]
        ((prop/construct-propagator
          [:compiler-2/unsupported-dynamic-operator name]
          (fn [_inputs _outputs _network]
            [(message out-id value/contradiction)])
          []
          [out-id])
         network*)]
    {:net declared
     :prop-ids [prop-id]}))

(declare operator-call)

(defn- install-result-projection
  [network prop-ids selected-out-id application-out-id]
  (cond
    (or (not (ids/node-id? selected-out-id))
        (= selected-out-id application-out-id))
    {:net network
     :prop-ids (vec prop-ids)}

    :else
    (let [[projection-id projected]
          ((stdlib-prop/id selected-out-id application-out-id) network)]
      {:net projected
       :prop-ids (conj (vec prop-ids) projection-id)})))

(defn- install-declared-activation
  [declaration network arg-ids out-id context-id]
  (let [activate (obj/slot-value declaration activate-slot)
        name (obj/slot-value declaration name-slot)]
    (cond
      (fn? activate)
      (let [{:keys [inputs outputs]}
            (operator-call declaration arg-ids out-id context-id)
            prepared (reduce nb/ensure-cell network (concat inputs outputs))
            [prop-id declared]
            ((prop/construct-propagator
              name
              (fn [_inputs _outputs current-net]
                (activate current-net context-id (vec arg-ids) out-id))
              inputs
              outputs)
             prepared)]
        (install-result-projection declared
                                   [prop-id]
                                   (first outputs)
                                   out-id))

      :else
      (unsupported-application network out-id name))))

(defn- install-declared-operator
  [declaration network arg-ids out-id context-id]
  (let [install (obj/slot-value declaration install-slot)
        activate (obj/slot-value declaration activate-slot)
        contextual? (true? (obj/slot-value declaration contextual?-slot))
        name (obj/slot-value declaration name-slot)]
    (cond
      (and contextual? (fn? activate))
      (install-declared-activation declaration network arg-ids out-id context-id)

      (and contextual? (not (fn? activate)))
      (unsupported-application network out-id name)

      (fn? install)
      (let [[declared prop-ids installed-out-id]
            (install network (vec arg-ids) out-id)]
        (cond
          (seq prop-ids)
          (install-result-projection declared
                                     prop-ids
                                     installed-out-id
                                     out-id)

          (fn? activate)
          (install-declared-activation declaration declared arg-ids out-id context-id)

          :else
          (install-result-projection declared
                                     []
                                     installed-out-id
                                     out-id)))

      (fn? activate)
      (install-declared-activation declaration network arg-ids out-id context-id)

      :else
      (unsupported-application network out-id name))))

(defn operator-closure
  [{:keys [input-selector install static-installer direct-installer direct-compiler
           activate compiler-activate output-selector contextual? name
           captured-cell-ids
           boundary-cell-ids application-boundary-cell-ids
           boundary-output-cell-ids boundary-dict-keys]}]
  (let [canonical-installer
        (cond
          (fn? install) install
          (fn? static-installer) static-installer
          :else nil)
        declaration
        (obj/compound-object
         {kind-slot operator-kind
          input-selector-slot input-selector
          install-slot canonical-installer
          static-installer-slot static-installer
          direct-installer-slot direct-installer
          direct-compiler-slot direct-compiler
          activate-slot activate
          compiler-activate-slot compiler-activate
          output-selector-slot output-selector
          contextual?-slot (true? contextual?)
          name-slot name})
        declaration-name (cond
                           (some? name) name
                           :else :anonymous)]
    (gur-core/recursive-closure
     [:compiler-2/operator declaration-name]
     (fn [context network arg-ids out-id]
       (let [application-declaration (:application-declaration context)
             context-id (cond
                          (map? application-declaration)
                          (:context-id application-declaration)

                          (nil? application-declaration)
                          nil

                          :else
                          (throw (ex-info "invalid GUR application declaration"
                                          {:declaration application-declaration})))]
         (install-declared-operator declaration network arg-ids out-id context-id)))
     {:declaration-id (stable-declaration-id declaration-name)
      :declaration declaration
      :captured-cell-ids (vec (or captured-cell-ids []))
      :boundary-cell-ids boundary-cell-ids
      :application-boundary-cell-ids application-boundary-cell-ids
      :boundary-output-cell-ids boundary-output-cell-ids
      :boundary-dict-keys boundary-dict-keys
      :declare-frame? gur-core/declare-topology-when-operator-usable})))

(defn operator-closure?
  [x]
  (and (not (value/unusable? x))
       (not (value/contradiction? x))
       (= operator-kind
          (obj/slot-value (operator-declaration x) kind-slot))))

(defn operator-install [operator]
  (obj/slot-value (operator-declaration operator) install-slot))

(defn operator-static-installer [operator]
  (obj/slot-value (operator-declaration operator) static-installer-slot))

(defn operator-compiler-activate [operator]
  (obj/slot-value (operator-declaration operator) compiler-activate-slot))

(defn operator-direct-installer [operator]
  (obj/slot-value (operator-declaration operator) direct-installer-slot))

(defn operator-direct-compiler [operator]
  (obj/slot-value (operator-declaration operator) direct-compiler-slot))

(defn operator-activate [operator]
  (obj/slot-value (operator-declaration operator) activate-slot))

(defn operator-input-selector [operator]
  (obj/slot-value (operator-declaration operator) input-selector-slot))

(defn operator-output-selector [operator]
  (obj/slot-value (operator-declaration operator) output-selector-slot))

(defn operator-contextual? [operator]
  (true? (obj/slot-value (operator-declaration operator) contextual?-slot)))

(defn operator-name
  [operator]
  (obj/slot-value (operator-declaration operator) name-slot))

(defn- output-ids
  [selected]
  (cond
    (nil? selected) []
    (vector? selected) selected
    (sequential? selected) (vec selected)
    :else [selected]))

(defn operator-output-ids
  [operator arg-ids fallback-id]
  (let [select-output (operator-output-selector operator)]
    (cond
      (fn? select-output)
      (output-ids (select-output arg-ids fallback-id))

      :else
      (output-ids [fallback-id]))))

(defn operator-output-id
  [operator arg-ids fallback-id]
  (first (operator-output-ids operator arg-ids fallback-id)))

(defn- default-input-selector
  [arg-ids _fallback-id _context-id]
  (vec arg-ids))

(defn- default-output-selector
  [_arg-ids fallback-id]
  [fallback-id])

(defn operator-call
  [operator arg-ids fallback-id context-id]
  (let [select-inputs (or (operator-input-selector operator)
                          default-input-selector)
        inputs (vec (select-inputs arg-ids fallback-id context-id))
        outputs (operator-output-ids operator arg-ids fallback-id)]
    {:arg-ids (vec arg-ids)
     :inputs inputs
     :outputs outputs
     :out-id (first outputs)
     :context-id context-id}))

(defn propagator-operator
  "Construct an operator closure backed by `prop/construct-propagator`.

  Selectors compute input and output cells, then `activate` receives those cells
  and emits messages. A topology installer may instead declare an existing
  concrete propagator composition.
  "
  [{:keys [name
           input-selector
           output-selector
           compiler-activate
           activate
           prepare-network
           topology-installer
           contextual?
           captured-cell-ids
           boundary-cell-ids
           application-boundary-cell-ids
           boundary-output-cell-ids
           boundary-dict-keys]}]
  (let [select-output (or output-selector default-output-selector)
        select-input (or input-selector default-input-selector)
        call (fn [arg-ids fallback-id context-id]
               (let [inputs (vec (select-input arg-ids fallback-id context-id))
                     outputs (output-ids (select-output arg-ids fallback-id))]
                 {:arg-ids (vec arg-ids)
                  :inputs inputs
                  :outputs outputs
                  :out-id (first outputs)
                  :context-id context-id}))
        activate-call (fn [network call-map]
                        (cond
                          (fn? activate)
                          (activate network
                                    (:inputs call-map)
                                    (:outputs call-map)
                                    (:context-id call-map))

                          :else
                          (throw (ex-info "propagator operator has no activation"
                                          {:name name
                                           :call call-map}))))
        generated-install
        (fn [network arg-ids fallback-id]
          (let [{:keys [inputs outputs out-id] :as call-map}
                (call arg-ids fallback-id nil)
                network (reduce nb/ensure-cell network
                                (concat inputs outputs))
                network (cond
                          (fn? prepare-network)
                          (prepare-network network call-map)

                          :else network)
                [prop-id network']
                ((prop/construct-propagator
                  (or name :compiler-2/propagator-operator)
                  (fn [_inputs _outputs current-net]
                    (activate-call current-net call-map))
                  inputs
                  outputs)
                 network)]
            [network' [prop-id] out-id]))
        install (cond
                  (fn? topology-installer) topology-installer
                  :else generated-install)
        activate (fn [network _context-id arg-ids fallback-id]
                   (activate-call
                    network
                    (call arg-ids fallback-id _context-id)))]
    (operator-closure
     {:name name
      :input-selector input-selector
      :install install
      :static-installer install
      :compiler-activate compiler-activate
      :activate activate
      :output-selector select-output
      :contextual? contextual?
      :captured-cell-ids captured-cell-ids
      :boundary-cell-ids boundary-cell-ids
      :application-boundary-cell-ids application-boundary-cell-ids
      :boundary-output-cell-ids boundary-output-cell-ids
      :boundary-dict-keys boundary-dict-keys})))
