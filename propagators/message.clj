(ns propagators.message
  (:require [propagators.helpers.tagged :refer [tagged?]]))

(def message? (tagged? :message))
(defn message [node-id cell-value] [:message node-id cell-value])
(defn message-id [m] (nth m 1))
(defn message-value [m] (nth m 2))
