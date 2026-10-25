(ns propagators.stdlib.conditional
  "Select one value through the existing base, support and premise-state procedures."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.stdlib.premise-publication :as publication]
            [propagators.ids :as ids]
            [propagators.gur :as gur]
            [propagators.layered :as layered]
            [propagators.layered.procedure :as layer-procedure]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.support :as support]
            [propagators.stdlib.observation :as observation]
            [propagators.stdlib.premise-state :as state]))

(defn- base-of [x]
  (if (datum/layer-present? x :base) (datum/layer-value x :base) x))

(def ^:private last-base-procedure
  {:net net/empty-net
   :f (fn [_ inputs outputs network]
        (second ((apply (prop/primitive-propagator ::last-base
                         (fn [& bases] (last bases)))
                        (concat inputs outputs)) network)))})

(defn- install-procedure [network]
  (let [base-procedure (layer-procedure/base last-base-procedure)
        [procedure base-id support-id state-id] (repeatedly 4 ids/new-node-id)
        prepared (-> network
                     (nb/install-cell base-id base-procedure base-procedure)
                     (nb/install-cell support-id support/procedure support/procedure)
                     (nb/install-cell state-id state/procedure state/procedure))
        base (layered/install-layered-procedure! prepared procedure :base base-id)
        supported (layered/install-layered-procedure! (:net base) procedure :support support-id)
        stateful (layered/install-layered-procedure! (:net supported) procedure :premise-state state-id)
        props (mapv :prop [base supported stateful])]
    [(:net stateful) procedure props]))

(defn- publish [result]
  ;; With only plain arguments the runtime runs only the base layer. Its support
  ;; is explicitly empty, not a freshly invented source. Extra layers still fail.
  (let [base (base-of result)]
    (cond
      ;; Keep information-free pending output at bottom. A support collection
      ;; here would conflict with a later plain callable; supported pending
      ;; observations and premise-state updates must still be published.
      (and (value/nothing? base)
           (empty? (datum/support-of result))
           (empty? (state/states-of result))) value/nothing
      ;; Static callable selection needs no candidate gate or extra body frame.
      (and (gur/recursive-closure? base)
           (empty? (datum/support-of result))
           (empty? (state/states-of result))) base
      (datum/layer-present? result :premise-state) (publication/content result)
      :else (collection/content
             (if (datum/layer-present? result :support)
               result
               {:base base :support #{}})))))

(defn- application-activation [procedure arguments output]
  ;; Use the public installer to obtain its activation once. This scratch graph
  ;; is discarded: only the selector is installed into the caller's topology.
  (let [prepared (nb/install-cells (concat [procedure output] arguments))
        [id scratch] ((layered/p:apply-layered procedure arguments output) prepared)]
    (prop/compose-activation (prop/prop-f (net/network-env-lookup scratch id))
                             (message/lift-message publish))))

(defn- output-activation [procedure condition then-input else-input output]
  (let [pending (application-activation procedure [condition] output)
        then (application-activation procedure [condition then-input] output)
        otherwise (application-activation procedure [condition else-input] output)]
    (fn [inputs outputs network]
      (let [condition-value (net/network-cell-strongest network condition)]
        (if (value/contradiction? (base-of condition-value))
          (observation/activation [condition] [output] network)
          (let [activate (cond
                           (value/unusable? condition-value) pending
                           (base-of condition-value) then
                           :else otherwise)]
            (activate inputs outputs network)))))))

(defn install-outputs
  "Compose [condition then-input else-input output] selections through one procedure."
  [network selections]
  (let [inputs (vec (distinct (mapcat #(take 3 %) selections)))
        outputs (mapv last selections)
        prepared (reduce nb/ensure-cell network (concat inputs outputs))
        [prepared procedure layer-props] (install-procedure prepared)
        activations (mapv #(apply output-activation procedure %) selections)
        activate (prop/compose-activation
                  (fn [inputs outputs current]
                    (into [] (mapcat #(% inputs outputs current)) activations))
                  publication/transport-states)
        [id installed] ((prop/construct-propagator
                         ::conditional activate (into [procedure] inputs) outputs) prepared)]
    [installed (into [id] layer-props)]))

(defn install
  "Install a conditional value selector. Unselected inputs do not supply support."
  [network condition then-input else-input output]
  (let [[installed props] (install-outputs network [[condition then-input else-input output]])]
    [installed props output]))
