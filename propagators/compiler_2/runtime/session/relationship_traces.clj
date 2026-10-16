(ns propagators.compiler-2.runtime.session.relationship-traces
  "Refresh native relationship observation at successful source-load boundaries."
  (:require [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.compiler-2.runtime.inspection.temperature :as temperature]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.relationship-observer :as observer]))

(defn refresh! [session]
  (state/mutate-session!
   session
   (fn [current]
     (let [[ids prepared] (observer/refresh-roots (:program/net current))
           [observed network] (temperature/run-propagators
                               current :relationship/refresh prepared ids)]
       (effects/perform-boundary-effects (assoc observed :program/net network))))))
