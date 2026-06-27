(ns propagators.gur.accumulating.ids
  "Deterministic node IDs for accumulated GUR declaration fragments."
  (:require [propagators.ids :as ids])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(defn stable-node-id
  [parts]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:gur/accumulating] parts))
               StandardCharsets/UTF_8))))

(defn- stable-node-id-from-string
  [s]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes (.getBytes s StandardCharsets/UTF_8))))

(defn stable-id-generator
  [seed]
  (let [prefix (pr-str [:gur/accumulating seed])
        counter (atom 0)]
    (fn []
      (stable-node-id-from-string
       (str prefix ":" (swap! counter inc))))))
