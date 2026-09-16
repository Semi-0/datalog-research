(ns propagators.compiler-2.runtime.operators.web-bridge
  "Compiler-2 primitive bridge operators for independent web clients."
  (:require [propagators.compiler-2.runtime.session.state :as state]
            [propagators.compiler-2.runtime.bridge.web :as bridge]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as cenv]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-common.core :as common]
            [propagators.compiler-common.cps :as cps]
            [propagators.datastructures.event :as event]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- strongest
  [network id]
  (if (and id (contains? (net/net-env network) id))
    (net/network-cell-strongest network id)
    value/nothing))

(defn- compile-role-operands
  [compile-k state forms roles continuation]
  (let [base-path (:path state)
        forms (vec forms)
        roles (vec roles)]
    (letfn [(step [compiled-state position bindings]
              (if (= position (count forms))
                (cps/continue continuation compiled-state bindings)
                (cps/call
                 compile-k
                 (h/child (common/with-path compiled-state base-path)
                          (nth roles position))
                 (nth forms position)
                 (fn [next-state binding]
                   #(step (common/with-path next-state base-path)
                          (inc position)
                          (conj bindings binding))))))]
      (step state 0 []))))

(defn- add-prop
  [state inputs outputs activate prop-key]
  (let [prop-id (apply state/stable-node-id :web :bridge prop-key)
        n0 (reduce nb/ensure-cell (:net state) (concat inputs outputs))
        [installed-id n1] ((prop/construct-propagator prop-id
                                                      activate
                                                      inputs
                                                      outputs)
                           n0)]
    [(-> state
         (assoc :net n1)
         (h/add-props [installed-id]))
     installed-id]))

(defn- base-value
  [v]
  (cond
    (event/event-projection? v)
    (let [facts (event/projection-facts v)]
      (if (= 1 (count facts))
        (event/event-value (first facts))
        v))

    :else
    v))

(defn- install-static-relation
  [state from-id to-id prop-key]
  (first
   (add-prop state
             [from-id]
             [to-id]
             (fn [_inputs _outputs network]
               (let [from (strongest network from-id)]
                 (cond-> []
                   (not (value/unusable? from))
                   (conj (message to-id from)))))
             prop-key)))

(defn runtime-clients-operator []
  (operator-value/operator-closure
   {:name 'runtime:clients
    :compiler-operands
    (fn [compile-k state operand-forms out-id continuation]
      (let [forms (vec operand-forms)]
        (if (= 1 (count forms))
          (cps/call
           compile-k
           (h/child state :runtime-clients)
           (first forms)
           (fn [compiled-state target-binding]
             (let [target-id (or (cenv/binding-id target-binding) out-id)
                   source-id (bridge/client-list-source-id)
                   installed
                   (install-static-relation
                    (common/with-path compiled-state (:path state))
                    source-id
                    target-id
                    [:clients source-id target-id])]
               (cps/continue continuation
                             installed
                             (cenv/cell-binding target-id)))))
          (throw
           (ex-info "runtime:clients expects one target cell"
                    {:operand-forms forms})))))}))

(defn- route-messages
  [from to pipe out-id]
  [(message out-id (bridge/client-handle to))])

(defn runtime-client-pipe-operator []
  (operator-value/operator-closure
   {:name 'runtime:client-pipe
    :compiler-operands
    (fn [compile-k state operand-forms out-id continuation]
      (let [forms (vec operand-forms)]
        (if (contains? #{2 3 4} (count forms))
          (let [compile-forms
                (cond-> [(nth forms 0)
                         (nth forms 1)
                         (or (nth forms 2 nil) bridge/default-pipe)]
                  (= 4 (count forms))
                  (conj (nth forms 3)))
                roles
                (cond-> [:runtime-client-pipe-from
                         :runtime-client-pipe-to
                         :runtime-client-pipe-name]
                  (= 4 (count forms))
                  (conj :runtime-client-pipe-out))]
            (compile-role-operands
             compile-k state compile-forms roles
             (fn [compiled-state bindings]
               (let [binding-ids (mapv cenv/binding-id bindings)
                     from-id (nth binding-ids 0)
                     to-id (nth binding-ids 1)
                     pipe-id (nth binding-ids 2)
                     target-id (or (nth binding-ids 3 nil) out-id)
                     [installed _prop-id]
                     (add-prop
                      compiled-state
                      [from-id to-id pipe-id]
                      [target-id]
                      (fn [_inputs _outputs network]
                        (let [from (base-value (strongest network from-id))
                              to (base-value (strongest network to-id))
                              pipe (base-value (strongest network pipe-id))]
                          (if (or (value/unusable? from)
                                  (value/unusable? to)
                                  (value/unusable? pipe))
                            []
                            (route-messages from to pipe target-id))))
                      [:client-pipe from-id to-id pipe-id target-id])]
                 (cps/continue continuation
                               installed
                               (cenv/cell-binding target-id))))))
          (throw
           (ex-info
            "runtime:client-pipe expects from-client, to-client, optional pipe, and optional output"
            {:operand-forms operand-forms})))))}))
