(ns propagators.cells.cell-protocol
  "Fallback partial-information handlers for dependency, scope, and intensity.
  Semantic domains dispatch once through cells.merge before reaching this layer."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.intensity :as intensity]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.network-cache :as cache]))

(def empty-content ::empty-content)
(def ^:private handled-key ::handled?)
(def ^:private handled-value-key ::value)

(defn- handled [v] {handled-key true handled-value-key v})
(defn handled? [v] (and (map? v) (true? (get v handled-key))))
(defn handled-value [v] (get v handled-value-key))
(defn empty-content? [v] (= empty-content v))

(defn- direct-dependency-merge [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (dependency/dependency-content? content))
             (dependency/dependency-value? update))
    (cache/stat! :cell-protocol/direct-dependency-merge)
    (handled
     (dependency/merge-content
      (if (or (empty-content? content) (value/nothing? content)) value/nothing content)
      update))))

(defn- direct-dependency-strongest [content]
  (when (dependency/dependency-content? content)
    (cache/stat! :cell-protocol/direct-dependency-strongest)
    (handled (dependency/strongest-value content))))

(defn- direct-scope-source-merge [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (scope-source/scope-content? content))
             (scope-source/scope-content? update))
    (cache/stat! :cell-protocol/direct-scope-source-merge)
    (handled
     (scope-source/merge-content
      (if (or (empty-content? content) (value/nothing? content)) value/nothing content)
      update))))

(defn- direct-scope-source-strongest [content]
  (when (scope-source/scope-content? content)
    (cache/stat! :cell-protocol/direct-scope-source-strongest)
    (handled (scope-source/strongest-value content))))

(defn- direct-intensity-merge [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (intensity/intensity-content? content))
             (intensity/intensity-value? update))
    (cache/stat! :cell-protocol/direct-intensity-merge)
    (handled
     (intensity/merge-content
      (if (or (empty-content? content) (value/nothing? content)) value/nothing content)
      update))))

(defn- direct-intensity-strongest [content]
  (when (intensity/intensity-content? content)
    (cache/stat! :cell-protocol/direct-intensity-strongest)
    (handled (intensity/strongest-value content))))

(defn try-cell-merge
  "Try fallback partial-information handlers. Semantic domains are owned by cells.merge."
  [_network content update]
  (or (direct-dependency-merge content update)
      (direct-scope-source-merge content update)
      (direct-intensity-merge content update)))

(defn try-cell-strongest [_network content]
  (or (direct-dependency-strongest content)
      (direct-scope-source-strongest content)
      (direct-intensity-strongest content)))
