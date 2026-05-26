(ns propagators.datastructures.compound_data
  (:require [propagators.network :as net]
            [propagators.cells.cell :as cell]
            [clojure.core.match :refer [match]]
            [propagators.ids :as id]))

;; the problem is with the dependence tracking system
;; if dict has a dependences
;; we need to make sure that p:car 
;; and p:cdr does not emit the dependences of the array
;; we can like use similar method like compound propagator
;; to run the network inside

;; however if we just do the routing
;; its the same?
;; the main point is that 
;; when sub-elements updated the array
;; we don't need to alert all the propagator
;; perhaps we could do that in cons?

;; so then p:car and p:cons just send dict into the cell
;; then the cell maintain a internal lexical environment
;; and cons dispatch the network when the listener diffs from the internal network?
;; and listener constantly send them to the internal network to update themself

;; use cons to dispatch input
;; run the internal network inside cell or strongest

(defn complete-compound-data?
  "Both `[:head node-id]` and `[:tail node-id]` pairs."
  [x]
  (match [x]
    [([[:head (h :guard id/node-id?)] [:tail (t :guard id/node-id?)]] :seq)] true
    :else false))

(defn compound-data-head?
  "Singleton seq with only `[:head node-id]`."
  [x]
  (match [x]
    [([[:head (h :guard id/node-id?)]] :seq)] true
    :else false))

(defn compound-data-tail?
  "Singleton seq with only `[:tail node-id]`."
  [x]
  (match [x]
    [([[:tail (t :guard id/node-id?)]] :seq)] true
    :else false))

(defn partial-compound-data?
  "Head-only or tail-only compound slot (not complete)."
  [x]
  (or (compound-data-head? x) (compound-data-tail? x)))

(defn compound-data?
  [x]
  (or (complete-compound-data? x)
      (partial-compound-data? x)
      (compound-data-head? x)
      (compound-data-tail? x)))


;; so we just sent the cell?
(def p:cons
  (net/primitive-propagator
    (fn [head-val rest-val]
      {:head head-val
       :rest rest-val})))
     
(def p:car (net/primitive-propagator (fn [dict] (cell/cell-strongest (:head dict)))))
(def p:cdr (net/primitive-propagator (fn [dict] (cell/cell-strongest (:rest dict)))))
