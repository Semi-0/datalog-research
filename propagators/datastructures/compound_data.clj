(ns propagators.datastructures.compound_data
  (:require [propagators.network :as net]
            [propagators.cells.cell :as cell]))
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

(def p:cons
  (net/primitive-propagator
    (fn [head-val rest-val]
      {:head head-val
       :rest rest-val})))

(def p:car (net/primitive-propagator (fn [dict] (cell/cell-strongest (:head dict)))))
(def p:cdr (net/primitive-propagator (fn [dict] (cell/cell-strongest (:rest dict)))))