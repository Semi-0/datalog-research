(ns examples.lain.amb.search-budget
  "Experiment-only cooperative bounds. Counters are instrumentation, never Net state."
  (:require [propagators.helpers.task-queue :as tq]))

(defn scope [{:keys [milliseconds activations] :as limits}]
  (if (and (integer? milliseconds) (pos? milliseconds)
           (integer? activations) (pos? activations))
    {:limits limits :started (System/nanoTime) :counts (atom 0)}
    (throw (ex-info "Positive elapsed-time and activation limits required" {:limits limits}))))

(defn usage [{:keys [started counts]}]
  {:elapsed-ms (/ (- (System/nanoTime) started) 1e6) :activations @counts})

(defn transform [{:keys [limits counts] :as scope}]
  (fn [advance]
    (fn [state continuations]
      (let [_ (if (tq/queue-empty? (:tasks state)) nil (swap! counts inc))
            measured (usage scope)]
        (if (or (.isInterrupted (Thread/currentThread))
                (> (:elapsed-ms measured) (:milliseconds limits))
                (> (:activations measured) (:activations limits)))
          (throw (ex-info "Experiment search budget exhausted"
                          {:type ::exhausted :limits limits :usage measured}))
          (advance state continuations))))))

(defn exhausted? [error]
  (loop [current error]
    (if (nil? current)
      false
      (if (= ::exhausted (:type (ex-data current)))
        true
        (recur (.getCause ^Throwable current))))))
