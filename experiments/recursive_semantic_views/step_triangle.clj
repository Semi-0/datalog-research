(ns experiments.recursive-semantic-views.step-triangle
  "Bounded diagnostic driver over the standard iterator."
  (:refer-clojure :exclude [run!])
  (:require [graph.lain-editor.runtime.session :as editor]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.model.env :as env]
            [propagators.debug :as debug]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.network-history :as history]
            [propagators.experimental.visualization.data :as data]
            [propagators.helpers.task-queue :as tasks]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(defn- recorded-labels [network]
  (vec (for [[id entry] (net/net-env network)
             :when (and (prop/prop? entry) (= ::history/record (prop/prop-name entry)))
             output (:outputs (get (net/net-graph network) id))
             :let [recording (data/payload (net/network-cell-strongest network output))
                   graph (:graph (peek (history/field recording :history/frames)))]]
         (sort (map str (vals (:nodes graph)))))))

(defn- frame-count [network]
  (reduce + 0
          (for [[id entry] (net/net-env network)
                :when (and (prop/prop? entry) (= ::history/record (prop/prop-name entry)))
                output (:outputs (get (net/net-graph network) id))]
            (count (history/field (data/payload (net/network-cell-strongest network output))
                                  :history/frames)))))

(defn- summary [network environment step started]
  {:step step :elapsed-ms (long (/ (- (System/nanoTime) started) 1e6))
   :recorded-frames (frame-count network)
   :sides (mapv #(observation/cell-base network (env/resolve-binding-id network environment %))
                '[a b c])})

(defn run!
  "Run at most 20,000 outer transitions / 60 seconds, printing progress.
  Bounds are cooperative: one activation may itself run a nested reducer."
  [profile?]
  (let [pane (editor/prepare (slurp "examples/lain/amb/editor/pythagorean.lain") [])
        session (:session pane)]
    (try
      (let [program @session
            environment (:program/env program)
            compiled (compiler/compile-source
                      "(-> (list 3 4 5) domain)" environment
                      {:net (:program/net program) :environment-props (:program/props program)})
            started (System/nanoTime)
            profile (debug/activation-profile)]
        (let [profile-run (if profile? #(debug/call-with-activation-profile profile %) (fn [f] (f)))
              result
          (profile-run
           (fn []
        (loop [state {:network (:net compiled) :tasks (tasks/into-queue (:props compiled))}
               step 0]
          (when (zero? (mod step 100))
            (prn (summary (:network state) environment step started))
            (flush))
          (if (or (>= step 20000) (> (- (System/nanoTime) started) 60000000000))
            (assoc (summary (:network state) environment step started) :status :bounded)
            (let [selected (runner/network-iterator)
                  advance (if profile? (runner/*advance-transform* selected) selected)
                  next (advance state {:done #(hash-map :status :completed :network %)
                                       :fail #(hash-map :status :failed :error %)
                                       :continue #(hash-map :status :continue :state %)})]
              (case (:status next)
                :continue (recur (:state next) (inc step))
                :completed (assoc (summary (:network next) environment step started)
                                  :status :completed :recorded-labels (recorded-labels (:network next)))
                :failed (throw (:error next))))))))]
          (cond-> result profile?
            (assoc :profile (let [report (debug/activation-profile-report profile)]
                              {:totals (:totals report) :by-name (vec (take 10 (:by-name report)))})))))
      (finally (editor/close-session! session)))))

(defn -main [& args]
  (prn (run! (= "profile" (first args)))))
