(ns propagators.message)

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn message? [x] (tagged? x :message))
(defn message [node-id cell-value] [:message node-id cell-value])
(defn message-id [m] (nth m 1))
(defn message-value [m] (nth m 2))
