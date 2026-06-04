(ns propagators.stdlib.layered
  "Layered procedure operator installers (`layered/+`, `layered/-`, …)."
  (:refer-clojure :exclude [+ - * /])
  (:require [propagators.layered :as layered]))

(defn +
  "Layered operator installer backed by `procedure-id`."
  [procedure-id]
  (layered/p:layered-operator procedure-id))

(defn -
  "Layered operator installer backed by `procedure-id`."
  [procedure-id]
  (layered/p:layered-operator procedure-id))

(defn *
  "Layered operator installer backed by `procedure-id`."
  [procedure-id]
  (layered/p:layered-operator procedure-id))

(defn /
  "Layered operator installer backed by `procedure-id`."
  [procedure-id]
  (layered/p:layered-operator procedure-id))
