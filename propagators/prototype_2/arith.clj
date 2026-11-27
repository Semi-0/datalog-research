(ns propagators.prototype-2.arith
  "Binary arithmetic as pending primitive propagators."
  (:require [propagators.prototype-2.construct :as ctor]))

(defn- binop [f]
  (fn [a b] (f a b)))

(defn install-add [network a b out]
  (ctor/pending-propagator-constructor network (binop +) [[a b] out]))

(defn install-sub [network a b out]
  (ctor/pending-propagator-constructor network (binop -) [[a b] out]))

(defn install-mul [network a b out]
  (ctor/pending-propagator-constructor network (binop *) [[a b] out]))

(defn install-div [network a b out]
  (ctor/pending-propagator-constructor network (binop /) [[a b] out]))

(def installers
  {'+ install-add
   '- install-sub
   '* install-mul
   '/ install-div})
