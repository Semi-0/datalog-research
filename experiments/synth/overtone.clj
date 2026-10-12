(ns experiments.synth.overtone
  "Loaded only by the explicit audio example alias. Never by the default tests."
  (:require [overtone.core :as o]))

(defn start! []
  (let [owned-server? (not (o/server-connected?))]
    (when owned-server? (o/boot-external-server))
    (let [group (o/group "lain-case-study")
          tone (o/synth "lain-case-study-tone"
                 [frequency 440 duration 0.18 amplitude 0.08]
                 (o/out 0 (o/pan2 (* amplitude (o/sin-osc frequency)
                                      (o/env-gen (o/perc 0.01 duration) :action o/FREE)) 0)))]
      {:play-note! (fn [{:keys [frequency duration amplitude]}]
                     (tone [:tail group] :frequency frequency
                           :duration duration :amplitude amplitude))
       :clear! (fn [] (o/group-clear group))
       :close (fn []
                (o/kill group)
                (when owned-server? (o/kill-server)))})))
