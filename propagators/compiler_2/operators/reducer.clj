(ns propagators.compiler-2.operators.reducer
  "Compiler-2 GUR closures composed into reducer-subnet merge networks."
  (:require [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.gur.accumulating :as gur]
            [propagators.gur.accumulating.core :as gur-core]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn- reducer-arg-ids
  [closure-info acc-id update-id out-id]
  (let [inputs (closure-value/closure-inputs closure-info)
        outputs (output-symbols (closure-value/closure-output closure-info))]
    (cond
      (and (= 2 (count inputs)) (= 1 (count outputs)))
      [acc-id update-id out-id]

      (and (= 2 (count inputs)) (empty? outputs))
      [acc-id update-id])))

(defn- copy-boundary-cell
  [target-net source-net cell-id]
  (cond
    (contains? (net/net-env source-net) cell-id)
    (nb/copy-cell target-net source-net cell-id)

    :else
    (nb/ensure-cell target-net cell-id)))

(defn- captured-boundary-net
  [source-net callable]
  (let [captured-ids (gur-core/captured-cell-ids callable)
        captured-values (mapv #(gur-core/strongest-or-nothing source-net %)
                              captured-ids)
        boundary-ids (gur-core/boundary-cell-ids source-net
                                                 captured-ids
                                                 captured-values)]
    (reduce #(copy-boundary-cell %1 source-net %2)
            net/empty-net
            boundary-ids)))

(defn closure-merge-net
  "Return a reducer merge-net for a canonical Compiler 2 GUR closure.

  The closure receives `[acc next]`; internally reducer-subnet still uses
  `:update` for the second input cell."
  [source-net closure-id callable {:keys [seed reducer-id-key reducer-id]}]
  (let [seed (or seed [:compiler-2/reducer closure-id])
        acc-id (h/stable-node-id seed :acc)
        update-id (h/stable-node-id seed :update)
        out-id (h/stable-node-id seed :out)
        closure-cell-id (h/stable-node-id seed :closure)
        arg-ids (reducer-arg-ids callable acc-id update-id out-id)]
    (cond
      (some? arg-ids)
      (let [n0 (-> (captured-boundary-net source-net callable)
                   (nb/install-cell acc-id)
                   (nb/install-cell update-id)
                   (nb/install-cell out-id)
                   (nb/install-cell closure-cell-id callable callable))
            [_prop-ids n1] ((gur/p:apply-closure closure-cell-id arg-ids out-id)
                            n0)
            merge-net (-> n1
                          (net/assoc-net-dict-entry :acc acc-id)
                          (net/assoc-net-dict-entry :update update-id)
                          (net/assoc-net-dict-entry :out out-id))]
        (cond
          (some? reducer-id-key)
          (net/assoc-net-dict-entry merge-net reducer-id-key reducer-id)

          :else
          merge-net))

      :else
      nil)))
