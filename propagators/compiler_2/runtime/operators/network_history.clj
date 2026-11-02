(ns propagators.compiler-2.runtime.operators.network-history
  "Ordinary operators for recording, selecting, and reducing network observations."
  (:require [propagators.cells.merge :as merge]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.operators.closure-fold :as fold]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.datastructures.support :as support]
            [propagators.experimental.network-history :as history]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.experimental.visualization.data :as data]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-state :as state]))

(defn- primitive [name f]
  (primitives/scalar-operator name (prop/concrete-primitive-propagator f)))

(defn- record-operator []
  (operator/operator-closure
   {:name 'history:record
    :install (fn [network inputs out]
               (if (= 1 (count inputs))
                 (let [prepared (nb/ensure-cell network out)
                       prepared (net/assoc-net-cell prepared out
                                  (merge/merge-cell-entry (net/network-env-lookup prepared out)
                                    (membership/content {:premise-state #{}}) prepared))
                       [id installed] ((history/p:record (first inputs) out) prepared)]
                   [installed [id] out])
                 (throw (ex-info "history:record expects one graph cell" {}))))}))

(defn- record-latest-operator []
  (operator/operator-closure
   {:name 'history:record-latest
    :install (fn [network inputs out]
               (when-not (= 2 (count inputs))
                 (throw (ex-info "history:record-latest expects a graph and capacity" {})))
               (let [prepared (nb/ensure-cell network out)
                     prepared (net/assoc-net-cell prepared out
                                (merge/merge-cell-entry (net/network-env-lookup prepared out)
                                  (membership/content {:premise-state #{}}) prepared))
                     [id installed] ((history/p:record-latest (first inputs) (second inputs) out) prepared)]
                 [installed [id] out]))}))

(defn- replay-publisher [recording out]
  (prop/construct-propagator
   ::replay
   (fn [_inputs _outputs network]
     (if (prop/concrete-inputs? network [recording])
       (let [input (net/network-cell-strongest network recording)
             value (data/payload input)
             replay (history/replay (history/field value :history/frames))]
         [(message out (membership/content
                        {:base replay :support (datum/support-of input)
                         :membership (datum/membership-of input)
                         :premise-state (state/states-of input)}))])
       []))
   [recording] [out]))

(defn- latest-operator []
  (operator/operator-closure
   {:name 'history:latest
    :install
    (fn [network inputs out]
      (when-not (= 2 (count inputs))
        (throw (ex-info "history:latest expects a graph and capacity" {})))
      (let [recording (ids/new-node-id)
            prepared (reduce (fn [network id]
                               (let [network (nb/ensure-cell network id)]
                                 (net/assoc-net-cell network id
                                   (merge/merge-cell-entry (net/network-env-lookup network id)
                                     (membership/content {:premise-state #{}}) network))))
                             network [recording out])
            [recorder recorded] ((history/p:record-latest (first inputs) (second inputs) recording) prepared)
            [publisher installed] ((replay-publisher recording out) recorded)]
        [installed [recorder publisher] out]))}))

(defn- finite-values [v]
  (cond
    (vector? v) v
    (and (net/net? v) (nat-int? (history/field v :count)))
    (mapv #(history/field v %) (range (history/field v :count)))
    :else (throw (ex-info "reduce requires a finite vector" {:class (str (class v))}))))

(defn- reduce-operator []
  (operator/propagator-operator
   {:name 'reduce
    :activate
    (fn [network inputs [output] context]
      (if (= 3 (count inputs))
        (if (prop/concrete-inputs? network inputs)
          (let [[callback init source] inputs
                result (fold/run network callback context
                                 (data/payload (net/network-cell-strongest network init))
                                 (finite-values (data/payload (net/network-cell-strongest network source))) output)
                arguments (conj (mapv (partial net/network-cell-strongest network) inputs) result)]
            [(message output (membership/content
                              {:base (data/payload result)
                               :support (apply support/combine (map datum/support-of arguments))
                               :membership (apply support/combine (map datum/membership-of arguments))
                               :premise-state (apply support/join (map state/states-of arguments))}))])
          [])
        (throw (ex-info "reduce expects callback, initial value, and frame vector" {}))))}))

(def extension
  (extension/extension-bundle
   {:id ::history
    :bindings [['history:record (record-operator)]
               ['history:record-latest (record-latest-operator)]
               ['history:latest (latest-operator)]
               ['history:between (primitive 'history:between history/between)]
               ['history:empty (primitive 'history:empty history/empty-replay)]
               ['history:append (primitive 'history:append history/append-frame)]
               ['reduce (reduce-operator)]]
    :effects []}))
