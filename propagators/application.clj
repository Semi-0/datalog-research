(ns propagators.application
  "Shared application combinators for procedure-like propagators."
  (:require [propagators.cells.diff :as diff]
            [propagators.cells.value :as value]
            [propagators.dispatch :as dispatch]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn make-frame
  []
  {:result-bank-id (ids/new-node-id)
   :reduced-out-id (ids/new-node-id)})

(defn cell-strongest-or-nothing
  [n id]
  (if (contains? (net/net-env n) id)
    (net/network-cell-strongest n id)
    value/nothing))

(defn cell-values
  [n ids]
  (mapv #(net/network-cell-strongest n %) ids))

(defn cells-usable?
  [n ids]
  (not (apply value/any-unusable-values? (cell-values n ids))))

(defn copied-cell
  ([outer-net id]
   (copied-cell outer-net id identity))
  ([outer-net id normalize]
   (let [v (normalize (net/network-cell-strongest outer-net id))]
     {:id id :value v})))

(defn value-cell
  [id v]
  {:id id :value v})

(defn empty-cell
  [id]
  {:id id})

(defn- install-cell-spec
  [n spec]
  (let [{:keys [id]} spec]
    (if (contains? spec :value)
      (nb/install-cell n id (:value spec) (:value spec))
      (nb/install-cell n id))))

(defn build-frame-net
  [{:keys [result-bank-id reduced-out-id]} cell-specs]
  (-> (reduce install-cell-spec net/empty-net cell-specs)
      (nb/install-cell reduced-out-id)
      (dispatch/install-result-bank result-bank-id)))

(defn- resolve-trace
  [trace application]
  (if (fn? trace)
    (trace application)
    trace))

(defn build-branch-application
  [{:keys [frame cell-specs install-branches reducer-install trace]}]
  (let [frame (or frame (make-frame))
        branch-app (install-branches (build-frame-net frame cell-specs) frame)
        app (merge frame
                   branch-app)
        app' (assoc app
                    :reducer-install (reducer-install app))]
    (assoc app' :trace (resolve-trace trace app'))))

(defn- report-trace!
  [phase trace n]
  (when trace
    (when-let [report! (requiring-resolve
                        'propagators.application-debugger/report!)]
      (report! phase trace n))))

(defn run-reduced-application
  [{:keys [net branch-prop-ids reducer-install trace]}]
  (let [after-branches (nb/run-propagators net branch-prop-ids)
        _ (report-trace! :after-branches trace after-branches)
        [reducer-prop-ids reducer-net] (reducer-install after-branches)
        after (nb/run-propagators reducer-net reducer-prop-ids)]
    (report-trace! :after-reducer trace after)
    after))

(defn diff-reduced-output
  [outer-net after-net reduced-out-id out-id]
  (diff/diff-cells [reduced-out-id] [out-id] after-net outer-net))
