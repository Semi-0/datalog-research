(ns propagators.application
  "Shared application combinators for procedure-like propagators."
  (:require [propagators.cells.diff :as diff]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.debugger :as debugger]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

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

(defn run-branch-reducer
  "Run branch propagators, install reducer topology, then run reducer props.

  Optional callbacks are side-effect hooks for debugger/reporting code.
  "
  ([application]
   (run-branch-reducer application {}))
  ([{:keys [net branch-prop-ids reducer-install]}
    {:keys [after-branches after-reducer]}]
   (let [after-branches-net (nb/run-propagators net branch-prop-ids)
         _ (when after-branches (after-branches after-branches-net))
         [reducer-prop-ids reducer-net] (reducer-install after-branches-net)
         after (nb/run-propagators reducer-net reducer-prop-ids)]
     (when after-reducer (after-reducer after))
     after)))

(defn diff-reduced-output
  [outer-net after-net reduced-out-id out-id]
  (diff/diff-cells [reduced-out-id] [out-id] after-net outer-net))

(defn result-bank-slot-reporter
  [{:keys [event result-bank-id slots slot-key result-key]}]
  (fn [n]
    (when (debugger/enabled?)
      (let [result-bank (cell-strongest-or-nothing n result-bank-id)]
        (doseq [slot slots]
          (debugger/report!
           event
           {slot-key slot
            result-key (obj/slot-value result-bank slot)}))))))

(defn selected-value-reporter
  [event selected-key reduced-out-id]
  (fn [n]
    (debugger/report!
     event
     {selected-key (cell-strongest-or-nothing n reduced-out-id)})))
