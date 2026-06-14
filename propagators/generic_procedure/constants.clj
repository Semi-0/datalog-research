(ns propagators.generic-procedure.constants
  (:require [propagators.ids :as ids]))

(def default-slot :generic/default)
(def policy-slot :generic/policy)
(def method-tag :generic/method)
(def select-one-policy-tag :select-one)

(defn generated-method-key
  []
  [:generic/handler (ids/new-node-id)])

(defn method-slot?
  [slot-key]
  (and (vector? slot-key)
       (= method-tag (first slot-key))
       (= 2 (count slot-key))))
