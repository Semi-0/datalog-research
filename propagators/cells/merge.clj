(ns propagators.cells.merge
  "Merge, strongest selection, and contradiction handling."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]))

(defn- compound-data?*
  "Lazy resolve avoids load cycle: merge → compound_data → network → merge."
  [x]
  (boolean
   (when x
     (try
       ((requiring-resolve 'propagators.datastructures.compound_data/compound-data?) x)
       (catch Exception _ false)))))

(def cell-equal? value/cell-value-equal?)

(defmulti cell-updated?
  (fn [_new _old _network] :default))

(defmethod cell-updated? :default
  [new old _network]
  (not (cell-equal? new old)))

(defmulti cell-merge
  (fn [content update _network]
    (if (compound-data?* update)
      :compound-data
      :default)))

;; we have 2 option to run the network
;; either we can run the network inside the cell-merge
;; or cell strongest
;; i think its better in cell-merge
;; because then we are merge networks together

;; so we would have 2 condition
;; 1. is content is already an existing network
;; so we can see that the updates
;; simply run the network 
;; with updates
;; or we dont have existing network
;; then we expands the upate into a network
;; or maybe we shall treat the compound propagator as partial information?
;; it could be either head or tail?

;; nevertheless we should expand a internal network in here 
;; and with a translation dict for the avatar network
;; so we can express
;; Placeholder: compound pair merge delegates to default until implemented.
(defmethod cell-merge :compound-data
  [content update network]
  ((get-method cell-merge :default) content update network))


(defmethod cell-merge :default
  [content update _network]
  (cond
    (value/nothing? content) update
    (value/nothing? update) content
    (value/contradiction? content) value/contradiction
    (value/contradiction? update) value/contradiction
    (= content update) content
    :else value/contradiction))

(def generic-merge cell-merge)

(defmulti strongest-value
  (fn [x _network] (if (cell/cell? x) :cell :content)))

(defmethod strongest-value :cell
  [c _network]
  (nth c 2))

(defmethod strongest-value :content
  [x _network]
  x)

(defmulti handle-contradiction
  (fn [tasks _node env] [tasks env]))

(defmethod handle-contradiction :default
  [tasks _node env]
  [tasks env])
