(ns propagators.stdlib.source-publication
  "Versioned external-source publications in ordinary TTMS content."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(defn source-id
  "Normalize a deliberate external source identity to a stable NodeId."
  [source]
  (when (nil? source)
    (throw (ex-info "Source identity is required" {})))
  (if (ids/node-id? source)
    source
    (ids/->NodeId
     (UUID/nameUUIDFromBytes
      (.getBytes (pr-str [::source source]) StandardCharsets/UTF_8)))))

(defn datum
  "Construct one source's value and current state at an explicit revision."
  [source revision base status]
  (when-not (contains? #{:active :retracted} status)
    (throw (ex-info "Unsupported source status" {:status status})))
  (let [premise {:source (source-id source) :timestamp revision :premises-status status}]
    {:base base :support #{premise} :premise-state #{premise}}))

(defn active [source revision base]
  (collection/content (datum source revision base :active)))

(defn retracted [source revision]
  (collection/content (datum source revision value/nothing :retracted)))
