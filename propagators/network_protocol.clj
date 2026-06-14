(ns propagators.network-protocol
  (:require [propagators.network :as net]))

(defprotocol Network
  (network? [x])
  (network-view [x]))

(defn primitive-network?
  [x]
  (net/network? x))

(extend-type propagators.network.Net
  Network
  (network? [_] true)
  (network-view [x] (net/clear-io (net/as-net x))))

(extend-type clojure.lang.IPersistentMap
  Network
  (network? [x] (net/network? x))
  (network-view [x]
    (when (net/network? x)
      (net/clear-io (net/as-net x)))))

(extend-type nil
  Network
  (network? [_] false)
  (network-view [_] nil))

(extend-type Object
  Network
  (network? [_] false)
  (network-view [_] nil))
