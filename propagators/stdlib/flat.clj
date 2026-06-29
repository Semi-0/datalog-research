(ns propagators.stdlib.flat
  "Standard recursive list closures implemented with flat GUR."
  (:require [propagators.gur.flat :as flat]
            [propagators.install :as i]))

(def unused-acc-list :unused)

(def map-list
  (flat/recursive-declaration
   'map-list
   (fn [ctx [list-id mapper-id acc-id] out-id]
     (-> ctx
         (i/$ {:xs list-id
               :mapper mapper-id
               :acc acc-id
               :out out-id})
         (i/car :head :xs)
         (i/cdr :rest :xs)
         (i/>> :mapper :head :mapped)
         (i/cons :mapped :mapped-rest :out)
         (i/when :rest
           (i/recur [:rest :mapper :acc] :mapped-rest))))))

(def filter-list
  ;; ponytail: flat GUR has no branch primitive yet; predicates return a kept value or nothing.
  (flat/recursive-declaration
   'filter-list
   (fn [ctx [list-id predicate-id acc-id] out-id]
     (-> ctx
         (i/$ {:xs list-id
               :predicate predicate-id
               :acc acc-id
               :out out-id})
         (i/car :head :xs)
         (i/cdr :rest :xs)
         (i/>> :predicate :head :filtered)
         (i/cons :filtered :filtered-rest :out)
         (i/when :rest
           (i/recur [:rest :predicate :acc] :filtered-rest))))))
