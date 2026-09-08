(ns propagators.compiler-2.runtime.application-layers
  "Adapt explicit layered application inputs and preserve scoped provenance.

  Ordinary compiler lookup returns raw bindings. These adapters also serve
  explicit lexical/provenance APIs and injected compilers with scoped results."
  (:require [clojure.set :as set]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.runtime.topology-effects :as topology-effects]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.message :as msg :refer [message]]
            [propagators.network :as net]))

(defn unwrap-operator [v]
  (scope-source/unwrap v))

(defn application-scope
  "Select one compatible lexical application context and combine provenance."
  [values]
  (let [scoped (filterv scope-source/scope-value? values)
        chains (set (map scope-source/context-chain scoped))]
    (if (and (seq scoped) (= 1 (count chains)))
      (let [chain (first chains)
            rank #(let [i (.lastIndexOf ^java.util.List chain
                                        (scope-source/source-scope %))]
                    (if (neg? i) -1 i))
            candidate (apply max-key rank scoped)]
        {:candidate candidate
         :dependencies (apply set/union
                              (map scope-source/dependencies scoped))})
      nil)))

(defn- scoped-result
  "Refine an already-scoped result without turning ordinary values into scopes."
  [{:keys [dependencies]} result]
  (if (scope-source/scope-value? result)
    (scope-source/add-dependencies result dependencies)
    result))

(defn- scope-result-message
  [scope result-id m]
  (if (= result-id (msg/message-id m))
    (message result-id (scoped-result scope (msg/message-value m)))
    m))

(defn scope-activation-result
  [scope result-id result]
  (if (not scope)
    result
    (cond
      (map? result) (update result :messages
                            #(mapv (partial scope-result-message scope result-id)
                                   (or % [])))
      (sequential? result) (mapv (partial scope-result-message scope result-id)
                                 result)
      :else result)))

(defn- application-base-id
  [application-id role]
  (h/stable-node-id :compiler-2 :application-base application-id role))

(defn- base-reader-declaration-key
  [application-id role]
  [:application-base-reader application-id role])

(defn- base-reader-declared?
  [network application-id role]
  (topology-effects/declared?
   network
   (base-reader-declaration-key application-id role)))

(defn- declare-base-reader
  [network application-id role source-id base-id]
  (let [declaration-key (base-reader-declaration-key application-id role)]
    (topology-effects/declare-once
     network
     declaration-key
     base-id
     #(layered/declare-layer-reader %
                                    declaration-key
                                    scope-source/base-layer
                                    source-id
                                    base-id))))

(defn source-specs
  [application-id operator-id arg-ids]
  (into [{:role :operator
          :source-id operator-id
          :base-id (application-base-id application-id :operator)}]
        (map-indexed
         (fn [index arg-id]
           {:role [:arg index]
            :source-id arg-id
            :base-id (application-base-id application-id [:arg index])})
         arg-ids)))

(defn- addressable-source?
  [network source-id]
  (layered/layer-addressable? (h/strongest-or-nothing network source-id)
                              scope-source/base-layer))

(defn pending-base-readers
  [network application-id specs]
  (filterv (fn [{:keys [role source-id]}]
             (and (addressable-source? network source-id)
                  (nil? (layered/layer-parent-id network
                                                 source-id
                                                 scope-source/base-layer))
                  (not (base-reader-declared? network application-id role))))
           specs))

(defn- merge-activation-results
  [left right]
  {:effects (into (vec (:effects left)) (:effects right))
   :messages (into (vec (:messages left)) (:messages right))})

(defn declare-pending-base-readers
  [network application-id specs]
  (reduce (fn [result {:keys [role source-id base-id]}]
            (merge-activation-results
             result
             (declare-base-reader network
                                  application-id
                                  role
                                  source-id
                                  base-id)))
          {:effects [] :messages []}
          specs))

(defn evaluation-id
  [network {:keys [source-id base-id]}]
  (let [parent-id (layered/layer-parent-id network source-id
                                          scope-source/base-layer)]
    (cond
      parent-id parent-id
      (addressable-source? network source-id) base-id
      :else source-id)))

(defn result-value-id
  "Resolve an explicit scoped result; raw compiler results keep their address."
  [network result-id]
  (let [answer (h/strongest-or-nothing network result-id)
        addressed-id (if (scope-source/scope-value? answer)
                       (scope-source/binding-address answer)
                       nil)
        parent-id (layered/layer-parent-id network result-id
                                            scope-source/base-layer)]
    (cond
      (and (ids/node-id? addressed-id)
           (contains? (net/net-env network) addressed-id)) addressed-id
      parent-id parent-id
      :else result-id)))
