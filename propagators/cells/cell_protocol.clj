(ns propagators.cells.cell-protocol
  "Network-local generic merge/strongest protocol."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.behavior :as behavior]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.event :as event]
            [propagators.datastructures.intensity :as intensity]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms :as tms]
            [propagators.network :as net]
            [propagators.network-cache :as cache]))

(def direct-standard-protocols-key :cell/direct-standard-protocols?)

(def empty-content ::empty-content)

(def ^:private handled-key ::handled?)
(def ^:private handled-value-key ::value)

(defn- handled
  [v]
  {handled-key true
   handled-value-key v})

(defn handled?
  [v]
  (and (map? v) (true? (get v handled-key))))

(defn handled-value
  [v]
  (get v handled-value-key))

(defn empty-content?
  [v]
  (= empty-content v))

(defn- event-bearing?
  [v]
  (or (event/event-content? v)
      (event/event-fact? v)
      (event/event-projection? v)))

(defn- normalize-empty-content
  [content]
  (if (or (empty-content? content)
          (value/nothing? content))
    value/nothing
    content))

(defn- direct-event-protocol-merge
  [content update]
  (handled
   (event/merge-content (normalize-empty-content content) update)))

(defn- direct-event-protocol-strongest
  [content]
  (handled (event/strongest-value content)))

(def ^:private direct-protocol-registry
  {event/protocol-id
   {:merge direct-event-protocol-merge
    :strongest direct-event-protocol-strongest}})

(defn- value-protocol-id
  [v]
  (event/protocol-id-of v))

(defn- direct-protocol-merge
  [content update]
  (when-let [protocol-id (value-protocol-id update)]
    (let [content-protocol-id (value-protocol-id content)]
      (when (or (empty-content? content)
                (value/nothing? content)
                (= protocol-id content-protocol-id))
        (when-let [merge-fn (get-in direct-protocol-registry
                                    [protocol-id :merge])]
          (cache/stat! [:cell-protocol/direct-protocol-merge protocol-id])
          (merge-fn content update))))))

(defn- direct-protocol-strongest
  [content]
  (when-let [protocol-id (value-protocol-id content)]
    (when-let [strongest-fn (get-in direct-protocol-registry
                                    [protocol-id :strongest])]
      (cache/stat! [:cell-protocol/direct-protocol-strongest protocol-id])
      (strongest-fn content))))

(defn- direct-event-merge
  [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (event-bearing? content))
             (event-bearing? update))
    (cache/stat! :cell-protocol/direct-event-merge)
    (handled
     (event/merge-content
      (if (or (empty-content? content)
              (value/nothing? content))
        value/nothing
        content)
      update))))

(defn- direct-event-strongest
  [content]
  (when (event/event-content? content)
    (cache/stat! :cell-protocol/direct-event-strongest)
    (handled (event/strongest-value content))))

(defn- direct-behavior-merge
  [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (behavior/behavior-content? content))
             (behavior/behavior-value? update))
    (cache/stat! :cell-protocol/direct-behavior-merge)
    (handled
     (behavior/merge-content
      (if (or (empty-content? content)
              (value/nothing? content))
        value/nothing
        content)
      update))))

(defn- direct-behavior-strongest
  [content]
  (when (behavior/behavior-content? content)
    (cache/stat! :cell-protocol/direct-behavior-strongest)
    (handled (behavior/strongest-value content))))

(defn- direct-tms-merge
  [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (tms/distributed-value? content))
             (tms/distributed-value? update))
    (cache/stat! :cell-protocol/direct-tms-merge)
    (handled
     (tms/merge-distributed-content
      (if (or (empty-content? content)
              (value/nothing? content))
        value/nothing
        content)
      update))))

(defn- direct-tms-strongest
  [content]
  (when (tms/distributed-value? content)
    (cache/stat! :cell-protocol/direct-tms-strongest)
    (handled (tms/strongest-distributed-value content))))

(defn- direct-dependency-merge
  [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (dependency/dependency-content? content))
             (dependency/dependency-value? update))
    (cache/stat! :cell-protocol/direct-dependency-merge)
    (handled
     (dependency/merge-content
      (if (or (empty-content? content)
              (value/nothing? content))
        value/nothing
        content)
      update))))

(defn- direct-dependency-strongest
  [content]
  (when (dependency/dependency-content? content)
    (cache/stat! :cell-protocol/direct-dependency-strongest)
    (handled (dependency/strongest-value content))))

(defn- direct-scope-source-merge
  [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (scope-source/scope-content? content))
             (scope-source/scope-content? update))
    (cache/stat! :cell-protocol/direct-scope-source-merge)
    (handled
     (scope-source/merge-content
      (if (or (empty-content? content)
              (value/nothing? content))
        value/nothing
        content)
      update))))

(defn- direct-scope-source-strongest
  [content]
  (when (scope-source/scope-content? content)
    (cache/stat! :cell-protocol/direct-scope-source-strongest)
    (handled (scope-source/strongest-value content))))

(defn- direct-intensity-merge
  [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (intensity/intensity-content? content))
             (intensity/intensity-value? update))
    (cache/stat! :cell-protocol/direct-intensity-merge)
    (handled
     (intensity/merge-content
      (if (or (empty-content? content)
              (value/nothing? content))
        value/nothing
        content)
      update))))

(defn- direct-intensity-strongest
  [content]
  (when (intensity/intensity-content? content)
    (cache/stat! :cell-protocol/direct-intensity-strongest)
    (handled (intensity/strongest-value content))))

(defn- direct-standard-merge
  [content update]
  (or (direct-protocol-merge content update)
      (direct-event-merge content update)
      (direct-behavior-merge content update)
      (direct-tms-merge content update)
      (direct-dependency-merge content update)
      (direct-scope-source-merge content update)
      (direct-intensity-merge content update)))

(defn- direct-standard-strongest
  [content]
  (or (direct-protocol-strongest content)
      (direct-event-strongest content)
      (direct-behavior-strongest content)
      (direct-tms-strongest content)
      (direct-dependency-strongest content)
      (direct-scope-source-strongest content)
      (direct-intensity-strongest content)))

(defn try-cell-merge
  "Try the built-in partial-information merge handlers."
  [_network content update]
  (direct-standard-merge content update))

(defn try-cell-strongest
  "Try the built-in partial-information strongest handlers."
  [_network content]
  (direct-standard-strongest content))

(defn prefer-direct-standard-protocols
  "Mark a network as using the built-in direct cell protocol handlers."
  [n]
  (net/assoc-net-dict-entry n direct-standard-protocols-key true))
