(ns propagators.compiler-2.operators.closure-fold
  "Finite pure folds through the current Compiler-2 callable protocol."
  (:require [propagators.cells.cell :as cell]
            [propagators.compiler-2.model.closure-value :as closure]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.reducer-subnet :as reducer]
            [propagators.datastructures.support :as support]
            [propagators.experimental.visualization.data :as data]
            [propagators.graph :as graph]
            [propagators.gur :as gur]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.stdlib.premise-state :as state]))

(defn- frozen-cells [network]
  (let [cells (into {} (filter (fn [[_ entry]] (cell/cell? entry))) (net/net-env network))]
    (gur/vm-net (net/net (into {} (map #(vector % (graph/blank-node))) (keys cells)) cells
                         (net/net-dict-or-empty network)))))

(defn- arguments [declaration acc update out]
  (let [inputs (closure/closure-inputs declaration) outputs (closure/closure-output declaration)]
    (cond
      (not= 2 (count inputs))
      (throw (ex-info "reduce callback requires two inputs" {:inputs inputs}))
      (closure/implicit-return-output? outputs) [acc update]
      (= 1 (count outputs)) [acc update out]
      :else (throw (ex-info "reduce callback requires one output" {:outputs outputs})))))

(defn- step [template previous update]
  (let [next (reducer/fold-updates template (data/payload previous) [update])]
    {:base (data/payload next)
     :support (support/combine (datum/support-of previous) (datum/support-of next))
     :membership (support/combine (datum/membership-of previous) (datum/membership-of next))
     :premise-state (support/join (state/states-of previous) (state/states-of next))}))

(defn run [network callback context init values output]
  (let [callable (data/payload (net/network-cell-strongest network callback))
        declaration (application/callable-declaration callable)]
    (if (closure/closure-info? declaration)
      (let [id #(gur/stable-node-id [::fold output %])
            acc (id :acc) update (id :update) out (id :out)
            base (reduce nb/ensure-cell (frozen-cells network) [acc update out])
            ports (arguments declaration acc update out)
            effect (gur/apply-closure-effect callback (into [context] ports) out)
            [_ installed] (patch/apply-root-patch effect base)
            template (-> installed (net/assoc-net-dict-entry :acc acc)
                         (net/assoc-net-dict-entry :update update) (net/assoc-net-dict-entry :out out))]
        (reduce (partial step template) init
                (map-indexed (fn [index v] {:slot index :value v}) values)))
      (throw (ex-info "reduce callback must be a Lain network" {})))))
