(ns examples.lain.live-environment.ownership
  "Execution-local installation receipts. Mutable observation stays outside Net."
  (:require [examples.lain.live-environment.application :as application]
            [propagators.datastructures.layered-value :as datum]
            [propagators.helpers.task-queue :as queue]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(defn record-installation [ledger lifetime before after]
  (reduce-kv
   (fn [recorded id entry]
     (if (contains? (:env before) id)
       recorded
       (assoc-in recorded [:nodes id]
                 {:lifetime lifetime
                  :activation (when (prop/prop? entry) (prop/prop-f entry))})))
   (update ledger :dictionary-keys (fnil into #{})
           (keep (fn [[key value]]
                   (when (not= value (get (:dict before) key)) key)))
           (:dict after))
   (:env after)))

(defn- selected-lifetime [ledger id network]
  (let [inherited (get-in ledger [:nodes id :lifetime] #{})
        context (application/call-context id network)]
    (if context
      (let [selected (net/network-cell-strongest network (:source-id context))]
        (into inherited
              (keep (fn [{:keys [source timestamp]}]
                      (get-in ledger [:sources source timestamp])))
              (datum/support-of selected)))
      inherited)))

(defn- observe-step [ledger advance]
  (fn [{:keys [network tasks] :as state} continuations]
    (if (queue/queue-empty? tasks)
      (advance state continuations)
      (let [[id _] (queue/pop-task tasks)
            lifetime (selected-lifetime @ledger id network)
            continue (:continue continuations)]
        (advance state
                 (assoc continuations :continue
                        (fn [next-state]
                          (swap! ledger record-installation lifetime
                                 network (:network next-state))
                          (continue next-state))))))))

(defn run
  "Run existing tasks and return [Net receipts]. Receipts never enter Net."
  [ledger network tasks]
  (let [recorded (atom ledger)
        transform (runner/compose-advance-transforms
                   #(observe-step recorded %) application/advance-transform
                   runner/*advance-transform*)]
    (binding [runner/*advance-transform* transform]
      [(runner/completed-network (runner/run-network tasks network)) @recorded])))

(defn retired-nodes [ledger generation]
  (into #{} (keep (fn [[id {:keys [lifetime]}]]
                    (when (contains? lifetime generation) id)))
        (:nodes ledger)))

(defn forget [ledger removed]
  (update ledger :nodes #(apply dissoc % removed)))
