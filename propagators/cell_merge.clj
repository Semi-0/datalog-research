(ns propagators.cell-merge
  "Merge and update logic for `CellValue`s."
  (:require [propagators.cell-value :as v]))

(defn cell-equal?
  [a b]
  (v/cell-value-equal? a b))

(defn cell-merge
  "Merge two `CellValue`s. Incompatible partials become `contradiction`."
  [content update]
  (cond
    (v/nothing? content) update
    (v/nothing? update) content
    (v/contradiction? content) v/contradiction
    (v/contradiction? update) v/contradiction
    (cell-equal? content update) content
    :else v/contradiction))

;; backward-compatible alias
(def generic-merge cell-merge)

(defn cell-updated?
  [new old]
  (not (cell-equal? new old)))

(defn cell-strongest
  [x]
  x)

;; in contradiction because we freeze the entire runtime
;; so we can simulate different possibilities to handle it
(defmulti handle-contradiction 
  (fn [tasks _node env] [tasks env]))

(defmethod handle-contradiction :default
  [_node env]
  env)
