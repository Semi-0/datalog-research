(ns propagators.compiler-2.operators.tms
  "Compiler-2 distributed TMS operators."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.gur :as gur]
            [propagators.gur.accumulating.core :as gur-core]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- unwrap-compiler-value
  [v]
  (-> v
      scope-source/unwrap
      tms/distributed-base-value
      dependency/unwrap))

(defn- retained-closure
  [callable]
  (let [callable* (unwrap-compiler-value callable)]
    (cond
      (gur-core/recursive-closure? callable*)
      callable*

      :else
      nil)))

(defn- retained-closure-info
  [closure]
  (cond
    (gur-core/recursive-closure? closure)
    (let [declaration (get closure gur-core/declaration-key)]
      (cond
        (closure-value/closure-info? declaration)
        declaration

        :else
        nil))

    :else
    nil))

(defn- retained-closure-boundary
  [closure-id closure]
  (cond
    (gur-core/recursive-closure? closure)
    (vec (distinct (cons closure-id
                         (gur-core/captured-cell-ids closure))))

      :else
    [closure-id]))

(defn closure-output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn single-output-id
  [closure-info arg-ids out-id]
  (let [outputs (closure-output-symbols
                 (closure-value/closure-output closure-info))]
    (if (= 1 (count outputs))
      (peek (vec arg-ids))
      out-id)))

(defn- tms-state-messages
  [closure-info network arg-ids out-id]
  (let [input-count (count (closure-value/closure-inputs closure-info))
        input-contents (mapv #(net/network-cell-content network %)
                             (take input-count arg-ids))
        state-update (tms/distributed-state-update input-contents)
        output-id (single-output-id closure-info arg-ids out-id)]
    (cond-> []
      (value/contradiction? state-update)
      (conj (message output-id value/contradiction))

      (and state-update (not (value/contradiction? state-update)))
      (conj (message output-id state-update)))))

(defn- install-tms-closure-call
  [closure-id closure-info network arg-ids out-id]
  (let [output-id (single-output-id closure-info arg-ids out-id)
        input-count (count (closure-value/closure-inputs closure-info))
        input-ids (vec (take input-count arg-ids))
        [apply-props applied]
        ((gur/p:apply-closure closure-id arg-ids out-id) network)
        [state-prop declared]
        ((prop/construct-propagator
          (h/stable-node-id :compiler-2/tms-closure-state
                            closure-id arg-ids output-id)
          (fn [_inputs _outputs current]
            (tms-state-messages closure-info current arg-ids out-id))
          input-ids
          [output-id])
         applied)]
    [declared (conj (vec apply-props) state-prop) output-id]))

(defn- tms-closure-value
  [closure-id closure closure-value]
  (operator-value/operator-closure
   {:name "tms-closure-value"
    :boundary-cell-ids (fn [_network _arg-ids]
                         (retained-closure-boundary closure-id closure))
    :output-selector (fn [arg-ids out-id]
                       [(single-output-id closure-value arg-ids out-id)])
    :install (fn [network arg-ids out-id]
               (install-tms-closure-call closure-id
                                         closure-value
                                         network
                                         arg-ids
                                         out-id))}))

(defn tms-closure-operator []
  (operator-value/operator-closure
   {:name "tms-closure"
    :install (fn [network _arg-ids out-id]
               [network [] out-id])
    :activate (fn [network _context-id arg-ids out-id]
                (let [[closure-id] (vec arg-ids)]
                  (cond
                    (not (and closure-id (= 1 (count arg-ids))))
                    (throw (ex-info "tms-closure expects one network closure"
                                    {:arg-ids arg-ids}))

                    :else
                    (let [callable (h/strongest-or-nothing network closure-id)
                          closure (retained-closure callable)
                          closure-info (retained-closure-info closure)]
                      (cond
                        (nil? closure-info)
                        []

                        :else
                        [(message out-id
                                  (tms-closure-value closure-id
                                                     closure
                                                     closure-info))])))))}))

(defn- single-output-call-plan
  [closure-id closure-info arg-ids out-id tag]
  (let [input-count (count (closure-value/closure-inputs closure-info))
        outputs (closure-output-symbols (closure-value/closure-output closure-info))
        arg-ids (vec arg-ids)]
    (cond
      (= 1 (count outputs))
      (when (= (count arg-ids) (inc input-count))
        (let [real-output-id (peek arg-ids)
              hidden-output-id (h/stable-node-id tag closure-id real-output-id arg-ids)]
          {:input-ids (subvec arg-ids 0 input-count)
           :real-output-id real-output-id
           :hidden-output-id hidden-output-id
           :inner-arg-ids (conj (subvec arg-ids 0 input-count)
                                hidden-output-id)}))

      (empty? outputs)
      (when (= (count arg-ids) input-count)
        (let [hidden-output-id (h/stable-node-id tag closure-id out-id arg-ids)]
          {:input-ids arg-ids
           :real-output-id out-id
           :hidden-output-id hidden-output-id
           :inner-arg-ids arg-ids}))

      :else nil)))

(declare p:distributed-premise-output)

(defn- distributed-premise-output-update
  [claim-id source output-update input-contents premise epoch]
  (let [premise-state (tms/distributed-premise-update premise epoch true)
        projected (cond
                    (nil? output-update) value/nothing
                    (tms/distributed-value? output-update)
                    (tms/strongest-distributed-value output-update)
                    :else output-update)
        result (tms/distributed-base-value projected)]
    (cond
      (value/contradiction? output-update) value/contradiction

      (value/unusable? result)
      (tms/distributed-state-update
       (cond-> (conj (vec input-contents) premise-state)
         (tms/distributed-value? output-update) (conj output-update)))

      :else
      (let [premise-claim (tms/distributed-input-update
                           [claim-id :premise]
                           result
                           premise
                           epoch
                           source)]
        (tms/distributed-result-update
         claim-id
         result
         (cond-> [premise-claim]
           (tms/distributed-value? output-update) (conj output-update)))))))

(defn- cell-update-or-nil
  [network id]
  (let [content (when (contains? (net/net-env network) id)
                  (net/network-cell-content network id))
        strongest (h/strongest-or-nothing network id)]
    (cond
      (and (some? content)
           (not (value/unusable? content))) content
      (not (value/unusable? strongest)) strongest
      :else nil)))

(defn distributed-premise-output-messages
  [claim-id source input-ids premise epoch private-output-id real-output-id network]
  (let [premise (unwrap-compiler-value premise)
        epoch (unwrap-compiler-value epoch)
        output-update (cell-update-or-nil network private-output-id)]
    (if (or (value/unusable? premise)
            (value/unusable? epoch))
      []
      (let [update (distributed-premise-output-update
                    claim-id
                    source
                    output-update
                    (mapv #(net/network-cell-content network %) input-ids)
                    premise
                    epoch)]
        (cond-> []
          update (conj (message real-output-id update)))))))

(defn p:distributed-premise-output
  "Project a retained closure private output into a premise-supported TMS output."
  [claim-id source input-ids premise epoch private-output-id real-output-id]
  (let [input-ids (vec input-ids)
        inputs (conj input-ids private-output-id)
        activate (fn [_inputs _outputs network]
                   (distributed-premise-output-messages
                    claim-id
                    source
                    input-ids
                    premise
                    epoch
                    private-output-id
                    real-output-id
                    network))]
    (prop/construct-propagator
     (h/stable-node-id :compiler-2/distributed-premise-output
                       claim-id
                       private-output-id
                       real-output-id)
     activate
     inputs
     [real-output-id])))

(defn- install-distributed-premise-closure-call
  [closure-id closure-info premise epoch network arg-ids out-id]
  (let [{:keys [input-ids real-output-id hidden-output-id inner-arg-ids]}
        (single-output-call-plan closure-id
                                 closure-info
                                 arg-ids
                                 out-id
                                 :compiler-2/distributed-premise-closure)]
    (cond
      (nil? real-output-id)
      [network [] out-id]

      :else
      (let [claim-id [:compiler-2/distributed-premise-closure
                      real-output-id
                      premise]
            [apply-props applied]
            ((gur/p:apply-closure closure-id inner-arg-ids hidden-output-id)
             network)
            [projection-prop declared]
            ((p:distributed-premise-output claim-id
                                           claim-id
                                           input-ids
                                           premise
                                           epoch
                                           hidden-output-id
                                           real-output-id)
             applied)]
        [declared
         (conj (vec apply-props) projection-prop)
         real-output-id]))))

(defn- distributed-premise-closure-value
  [closure-id closure closure-value premise epoch]
  (operator-value/operator-closure
   {:name "distributed-premise-closure-value"
    :boundary-cell-ids (fn [_network _arg-ids]
                         (retained-closure-boundary closure-id closure))
    :output-selector (fn [arg-ids out-id]
                       [(single-output-id closure-value arg-ids out-id)])
    :install (fn [network arg-ids out-id]
               (install-distributed-premise-closure-call closure-id
                                                          closure-value
                                                          premise
                                                          epoch
                                                          network
                                                          arg-ids
                                                          out-id))}))

(defn distributed-premise-closure-operator []
  (operator-value/operator-closure
   {:name "distributed-premise-closure"
    :install (fn [network _arg-ids out-id]
               [network [] out-id])
    :activate (fn [network _context-id arg-ids out-id]
                (let [[closure-id premise-id epoch-id] (vec arg-ids)]
                  (cond
                    (not (and closure-id premise-id epoch-id
                              (= 3 (count arg-ids))))
                    (throw (ex-info
                            "distributed-premise-closure expects closure, premise, and epoch"
                            {:arg-ids arg-ids}))

                    :else
                    (let [callable (h/strongest-or-nothing network closure-id)
                          closure (retained-closure callable)
                          closure-info (retained-closure-info closure)
                          premise (h/strongest-or-nothing network premise-id)
                          epoch (h/strongest-or-nothing network epoch-id)]
                      (cond
                        (or (nil? closure-info)
                            (value/unusable? premise)
                            (value/unusable? epoch))
                        []

                        :else
                        [(message out-id
                                  (distributed-premise-closure-value
                                   closure-id closure closure-info premise epoch))])))))}))

(defn- premise-input-messages
  [network value-id premise-id epoch-id out-id]
  (let [v (unwrap-compiler-value (net/network-cell-strongest network value-id))
        premise (unwrap-compiler-value (net/network-cell-strongest network premise-id))
        epoch (unwrap-compiler-value (net/network-cell-strongest network epoch-id))]
    (if (or (value/unusable? v)
            (value/unusable? premise)
            (value/unusable? epoch))
      []
      [(message out-id
                (tms/distributed-input-update
                 [:compiler-2/premise-input out-id value-id premise]
                 v
                 premise
                 epoch
                 [:compiler-2/premise-input out-id]))])))

(defn- premise-content-input-messages
  [network value-id premise-id epoch-id out-id]
  (let [v (net/network-cell-content network value-id)
        premise (unwrap-compiler-value (net/network-cell-strongest network premise-id))
        epoch (unwrap-compiler-value (net/network-cell-strongest network epoch-id))]
    (if (or (value/unusable? v)
            (value/unusable? premise)
            (value/unusable? epoch))
      []
      [(message out-id
                (tms/distributed-input-update
                 [:compiler-2/premise-content-input out-id value-id premise]
                 v
                 premise
                 epoch
                 [:compiler-2/premise-content-input out-id]))])))

(defn- premise-state-messages
  [active? network premise-id epoch-id out-id]
  (let [premise (unwrap-compiler-value (net/network-cell-strongest network premise-id))
        epoch (unwrap-compiler-value (net/network-cell-strongest network epoch-id))]
    (if (or (value/unusable? premise)
            (value/unusable? epoch))
      []
      [(message out-id (tms/distributed-premise-update premise epoch active?))])))

(defn premise-input-operator []
  (operator-value/propagator-operator
   {:name "premise-input"
    :output-selector (fn [[_value-id _premise-id _epoch-id out-id] fallback-id]
                       [(or out-id fallback-id)])
    :input-selector (fn [arg-ids fallback-id _context-id]
                      (let [[value-id premise-id epoch-id explicit-out-id] (vec arg-ids)
                            out-id (or explicit-out-id fallback-id)]
                        (when-not (and value-id premise-id epoch-id out-id
                                       (<= 3 (count arg-ids) 4))
                          (throw (ex-info "premise-input expects value, premise, epoch, and optional output"
                                          {:arg-ids arg-ids})))
                        [value-id premise-id epoch-id]))
    :activate (fn [network inputs outputs _context-id]
                (let [[value-id premise-id epoch-id] inputs
                      [out-id] outputs]
                  (premise-input-messages network
                                          value-id
                                          premise-id
                                          epoch-id
                                          out-id)))}))

(defn premise-content-input-operator []
  (operator-value/propagator-operator
   {:name "premise-content-input"
    :output-selector (fn [[_value-id _premise-id _epoch-id out-id] fallback-id]
                       [(or out-id fallback-id)])
    :input-selector (fn [arg-ids fallback-id _context-id]
                      (let [[value-id premise-id epoch-id explicit-out-id] (vec arg-ids)
                            out-id (or explicit-out-id fallback-id)]
                        (when-not (and value-id premise-id epoch-id out-id
                                       (<= 3 (count arg-ids) 4))
                          (throw (ex-info "premise-content-input expects value, premise, epoch, and optional output"
                                          {:arg-ids arg-ids})))
                        [value-id premise-id epoch-id]))
    :activate (fn [network inputs outputs _context-id]
                (let [[value-id premise-id epoch-id] inputs
                      [out-id] outputs]
                  (premise-content-input-messages network
                                                  value-id
                                                  premise-id
                                                  epoch-id
                                                  out-id)))}))

(defn premise-state-operator [active? name]
  (operator-value/propagator-operator
   {:name name
    :output-selector (fn [[_premise-id _epoch-id out-id] fallback-id]
                       [(or out-id fallback-id)])
    :input-selector (fn [arg-ids _fallback-id _context-id]
                      (let [[premise-id epoch-id out-id] (vec arg-ids)]
                        (when-not (and premise-id epoch-id out-id (= 3 (count arg-ids)))
                          (throw (ex-info (str name " expects premise, epoch, and output cell")
                                          {:arg-ids arg-ids})))
                        [premise-id epoch-id]))
    :activate (fn [network inputs outputs _context-id]
                (let [[premise-id epoch-id] inputs
                      [out-id] outputs]
                  (premise-state-messages active? network premise-id epoch-id out-id)))}))

(defn bind-distributed-tms-operators
  [compiler-env]
  (-> compiler-env
      (env/bind-at 'tms-closure (tms-closure-operator) 0)
      (env/bind-at 'premise-closure
                   (distributed-premise-closure-operator)
                   0)
      (env/bind-at 'distributed-premise-closure
                   (distributed-premise-closure-operator)
                   0)
      (env/bind-at 'premise-input (premise-input-operator) 0)
      (env/bind-at 'premise-content-input (premise-content-input-operator) 0)
      (env/bind-at 'premise-believe (premise-state-operator true "premise-believe") 0)
      (env/bind-at 'premise-retract (premise-state-operator false "premise-retract") 0)))
