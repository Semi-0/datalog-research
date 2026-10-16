(ns propagators.stdlib.supported-procedure
  "Layered computation and publication, independent of compiler declarations."
  (:require [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.support :as premises]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.layered.procedure :as procedure]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-publication :as publication]
            [propagators.stdlib.premise-state :as state]
            [propagators.stdlib.support :as support]))

(defn from-primitive [primitive]
  {:net net/empty-net
   :f (fn [_ inputs outputs network]
        (second ((apply primitive (concat inputs outputs)) network)))})

(defn publish
  ([result] (publish result []))
  ([result arguments]
   (cond
     (some #(datum/layer-present? % :membership) arguments)
     (publication/content {:base (datum/layer-value result :base)
                           :support (datum/support-of result)
                           :membership (apply premises/combine (map datum/membership-of arguments))
                           :premise-state (apply premises/join (map state/states-of arguments))})
     (datum/layer-present? result :premise-state) (publication/content result)
     :else
     (collection/content
      (if (datum/layer-present? result :support)
        result
        {:base (datum/layer-value result :base) :support #{}})))))

(defn install
  "Install existing base, support and state procedures. Return [Net props output].
  Callers own scheduling and declaration recording; no compiler dependency."
  [base network arguments output]
  (let [base (procedure/base base)
        [procedure base-id support-id state-id] (repeatedly 4 ids/new-node-id)
        prepared (-> (reduce nb/ensure-cell network (conj (vec arguments) output))
                     (nb/install-cell base-id base base)
                     (nb/install-cell support-id support/procedure support/procedure)
                     (nb/install-cell state-id state/procedure state/procedure))
        base-layer (layered/install-layered-procedure! prepared procedure :base base-id)
        supported (layered/install-layered-procedure! (:net base-layer) procedure :support support-id)
        stateful (layered/install-layered-procedure! (:net supported) procedure :premise-state state-id)
        layer-props (mapv :prop [base-layer supported stateful])
        [id installed] ((layered/p:apply-layered procedure arguments output) (:net stateful))
        application (net/network-env-lookup installed id)
        activation (prop/compose-activation (prop/prop-f application)
                                            (fn [patches inputs outputs current]
                                              ((message/lift-message
                                                #(publish % (mapv (partial net/network-cell-strongest current) arguments)))
                                               patches inputs outputs current))
                                            publication/transport-states)]
    [(net/assoc-net-prop installed id (assoc application :activate activation))
     (into [id] layer-props) output]))
