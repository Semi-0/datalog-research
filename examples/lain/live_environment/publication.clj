(ns examples.lain.live-environment.publication
  "File exports use existing supported computation; no publication merge policy."
  (:require [propagators.cell-evaluator :as evaluator]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network-builder :as builder]
            [propagators.propagator :as prop]
            [propagators.stdlib.supported-procedure :as supported]))

(def export-procedure
  (supported/from-primitive
   (prop/primitive-propagator ::export (fn [_generation result] result))))

(defn install [network exports epoch]
  (reduce
   (fn [{:keys [network tasks]} {:keys [private public]}]
     (let [token (ids/new-node-id)
           premise #{{:source public :timestamp epoch :premises-status :active}}
           [_ seeded] (evaluator/evaluate-all
                       [(message/message token
                                         (collection/content
                                          {:base true :support premise :premise-state premise}))]
                       (builder/ensure-cell network token))
           [installed props _] (supported/install export-procedure seeded [token private] public)]
       {:network installed :tasks (into tasks props)}))
   {:network network :tasks []} exports))

(defn withdrawal [public-ids epoch]
  (mapv (fn [id]
          (message/message id
                           (collection/content
                            {:premise-state #{{:source id :timestamp epoch
                                               :premises-status :retracted}}})))
        public-ids))
