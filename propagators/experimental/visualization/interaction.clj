(ns propagators.experimental.visualization.interaction
  "Validate selection against published declarations, not client-supplied cell IDs."
  (:require [propagators.experimental.visualization.selection :as selection]
            [propagators.network :as net]
            [propagators.visualizer :as visualizer]))

(defn- leaves [view]
  (if (= :juxtapose (:view/type view))
    (mapcat leaves (:view/resolved-children view))
    [view]))

(defn generation-view [state view]
  (if (= :juxtapose (:view/type view))
    (update view :view/resolved-children #(mapv (partial generation-view state) %))
    (assoc view :view/generation (:environment/generation state))))

(defn published-views [state]
  (mapcat (fn [effect]
            (if (= :xr/present-view (:boundary/kind effect))
              (leaves (generation-view state
                        (visualizer/resolve-view (:program/net state)
                         (get-in effect [:boundary/payload :view]))))
              []))
          (get-in state [:xr :effects])))

(defn selection-input [state {:keys [view-id item-id epoch revision generation clear?] :as command}]
  (let [view (last (filter #(= view-id (pr-str (:view/id %))) (published-views state)))
        row (first (filter #(= item-id (pr-str (:identity %)))
                           (get-in view [:view/collection :items])))
        control (:view/selection-cell view)]
    (when-not (and view control (or (= true clear?) row) (= epoch (:view/epoch view))
                   (some? generation) (= generation (:view/generation view))
                   (= revision (:view/revision view)))
      (throw (ex-info "Stale or unpublished view selection" {:command command})))
    (let [content (net/network-cell-content (:program/net state) control)
          sequence (selection/next-sequence control content)]
      {:runtime/input :xr/message :cell-id control :command command
       :update (if (= true clear?)
                 (selection/clear-update control sequence)
                 (selection/selection-update control sequence (:identity row)))})))
