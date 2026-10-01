(ns propagators.experimental.visualization.selection
  "A dedicated TTMS injection cell for ordered selection/clear. No reducer."
  (:require [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]))

(defn initial [id]
  (when-not (ids/node-id? id)
    (throw (ex-info "Selection requires a cell ID" {:id id})))
  (collection/content {:premise-state #{}}))

(defn source? [id content]
  (and (collection/content? content)
       (contains? content :support/states)
       (every? #(and (= id (:source %)) (pos-int? (:timestamp %)))
               (concat (:support/states content)
                       (mapcat :support (:support/observations content))))))

(defn next-sequence [id content]
  (when-not (source? id content)
    (throw (ex-info "View control is not a dedicated TTMS selection source" {:id id})))
  (inc (reduce max 0
               (map :timestamp
                    (concat (:support/states content)
                            (mapcat :support (:support/observations content)))))))

(defn- premise [id sequence status]
  (when-not (and (ids/node-id? id) (pos-int? sequence))
    (throw (ex-info "Invalid selection source/version" {:id id :sequence sequence})))
  {:source id :timestamp sequence :premises-status status})

(defn selection-update [id sequence identity]
  (let [p (premise id sequence :active)]
    (collection/content {:base identity :support #{p} :premise-state #{p}})))

(defn clear-update [id sequence]
  (collection/content {:premise-state #{(premise id sequence :retracted)}}))
