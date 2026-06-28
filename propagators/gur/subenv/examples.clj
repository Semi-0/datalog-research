(ns propagators.gur.subenv.examples
  "Concrete Fibonacci and cons-list probes for lexical sub-env GUR."
  (:require [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur.subenv.frame :as frame]
            [propagators.gur.subenv.queue :as queue]
            [propagators.gur.subenv.source :as source]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- value-predicate
  [pred]
  (prop/primitive-propagator
   (fn [v]
     (cond
       (value/contradiction? v) value/contradiction
       (value/nothing? v) value/nothing
       :else (pred v)))))

(def p:+-present
  (prop/primitive-propagator
   (fn [acc value]
     (cond
       (or (value/contradiction? acc)
           (value/contradiction? value))
       value/contradiction

       (value/nothing? acc)
       value/nothing

       (value/nothing? value)
       acc

       :else
       (try
         (+ acc value)
         (catch Exception _
           value/contradiction))))))

(declare p:fib-base? p:empty-list? p:list-node-value? p:even?)

(defn- example-base-installers []
  (merge (compile/default-installers)
         {'p:+-present p:+-present
          'p:fib-base? p:fib-base?
          'p:empty-list? p:empty-list?
          'p:list-node-value? p:list-node-value?
          'p:even? p:even?
          'obj/p:car obj/p:car
          'obj/p:cdr obj/p:cdr
          'obj/p:cons obj/p:cons
          'cons obj/p:cons}))

(defn- example-installers
  [runtime]
  (source/contextual-installers (example-base-installers) runtime))

(def p:fib-base?
  (value-predicate
   (fn [v]
     (if (and (integer? v) (not (neg? v)))
       (<= v 1)
       value/contradiction))))

(source/def-recursive fib
  [n out]
  {:installers example-installers}
  (let [one 1
        two 2
        base? (::fib-base? n)
        recur? (::not base?)]
    (cond
      base? n
      recur? (::+ (::recur (switch recur? (::- n one)))
                  (::recur (switch recur? (::- n two)))))))

(defn fib-closure []
  fib)

(source/def-recursive factorial
  [n out]
  {:installers example-installers}
  (let [one 1
        base? (::fib-base? n)
        recur? (::not base?)]
    (cond
      base? one
      recur? (::* n (::recur (switch recur? (::- n one)))))))

(defn factorial-closure []
  factorial)

(source/def-recursive int-sqrt-search
  [n lo hi out]
  {:installers example-installers}
  (let [one 1
        two 2
        done? (::<= hi lo)
        search? (::not done?)
        mid (::quot (::+ lo hi one) two)
        square (::* mid mid)
        fits? (::<= square n)
        too-big? (::not fits?)
        search-fits? (::and search? fits?)
        search-too-big? (::and search? too-big?)
        hi* (::- mid one)]
    (cond
      done? lo
      search-fits? (::recur (switch search-fits? n)
                            (switch search-fits? mid)
                            (switch search-fits? hi))
      search-too-big? (::recur (switch search-too-big? n)
                               (switch search-too-big? lo)
                               (switch search-too-big? hi*)))))

(defn int-sqrt-search-closure []
  int-sqrt-search)

(def empty-list (obj/as-accessor-network {}))

(defn empty-list?
  [v]
  (and (net/net? v)
       (obj/accessor-network? v)
       (empty? (obj/accessor-slot-keys v))))

(defn cons-cell-value
  [head tail]
  (obj/as-accessor-network {:car head :cdr tail}))

(defn cons-list-value
  [values]
  (reduce (fn [tail head]
            (cons-cell-value head tail))
          empty-list
          (reverse values)))

(defn- accessor-slot-value
  [v slot-key]
  (cond
    (value/unusable? v)
    v

    (and (net/net? v) (obj/accessor-source-slot-present? v slot-key))
    (obj/accessor-source-slot-value v slot-key)

    :else
    value/nothing))

(defn list-node-value?
  [v]
  (and (not (empty-list? v))
       (not (value/unusable? v))
       (not (value/contradiction? v))
       (not (value/unusable? (accessor-slot-value v :car)))
       (not (value/contradiction? (accessor-slot-value v :cdr)))))

(def p:empty-list?
  (value-predicate empty-list?))

(def p:list-node-value?
  (value-predicate list-node-value?))

(def p:even?
  (value-predicate
   (fn [v]
     (if (integer? v)
       (even? v)
       value/contradiction))))

(source/def-recursive map-list
  [list mapper acc out]
  {:installers example-installers}
  (let-cell [head rest]
    (let [list-empty? (::empty-list? list)
          list-more? (::not list-empty?)
          node (switch list-more? list)]
      (obj/p:car head node)
      (obj/p:cdr rest node)
      (let [mapped (::apply mapper head)
            rest-empty? (::empty-list? rest)
            rest-more? (::not rest-empty?)
            mapped-rest (cond
                          rest-empty? acc
                          rest-more? (::recur (switch rest-more? rest)
                                              mapper
                                              acc))
            mapped-node (::cons mapped mapped-rest)]
        (cond
          list-empty? acc
          list-more? mapped-node)))))

(defn map-list-closure []
  map-list)

(source/def-recursive map-list-fib
  [list out]
  {:installers example-installers
   :seed-values {map-list map-list
                 fib fib
                 acc empty-list}}
  (::apply map-list list fib acc))

(defn map-list-fib-closure []
  map-list-fib)

(source/def-recursive sum-step
  [acc value out]
  {:installers example-installers}
  (::+ acc value))

(defn sum-step-closure []
  sum-step)

(source/def-recursive sum-present-step
  [acc value out]
  {:installers example-installers}
  (::+-present acc value))

(defn sum-present-step-closure []
  sum-present-step)

(source/def-recursive even-predicate
  [value out]
  {:installers example-installers}
  (::even? value))

(defn even-predicate-closure []
  even-predicate)

(source/def-recursive reduce-list
  [list step acc out]
  {:installers example-installers}
  (let-cell [head rest]
    (let [list-empty? (::empty-list? list)
          list-more? (::not list-empty?)
          node (switch list-more? list)]
      (obj/p:car head node)
      (obj/p:cdr rest node)
      (let [next-acc (::apply step acc head)
            rest-empty? (::empty-list? rest)
            rest-more? (::not rest-empty?)]
        (cond
          list-empty? acc
          rest-empty? next-acc
          rest-more? (::recur (switch rest-more? rest) step next-acc))))))

(defn reduce-list-closure []
  reduce-list)

(source/def-recursive prefix-reduce-list
  [list step acc out]
  {:installers example-installers}
  (let-cell [head rest]
    (let [list-empty? (::empty-list? list)
          list-more? (::not list-empty?)
          node (switch list-more? list)]
      (obj/p:car head node)
      (obj/p:cdr rest node)
      (let [next-acc (::apply step acc head)
            rest-empty? (::empty-list? rest)
            rest-unknown? (::nothing? rest)
            rest-done? (::or rest-empty? rest-unknown?)
            rest-more? (::not rest-done?)]
        (cond
          list-empty? acc
          rest-done? next-acc
          rest-more? (::recur (switch rest-more? rest) step next-acc))))))

(defn prefix-reduce-list-closure []
  prefix-reduce-list)

(source/def-recursive filter-list
  [list predicate acc out]
  {:installers example-installers}
  (let-cell [head rest]
    (let [list-empty? (::empty-list? list)
          list-more? (::not list-empty?)
          node (switch list-more? list)]
      (obj/p:car head node)
      (obj/p:cdr rest node)
      (let [keep? (::apply predicate head)
            rest-empty? (::empty-list? rest)
            rest-more? (::not rest-empty?)
            filtered-rest (cond
                            rest-empty? acc
                            rest-more? (::recur (switch rest-more? rest)
                                                predicate
                                                acc))
            kept-node (::cons head filtered-rest)
            drop? (::not keep?)
            branch (cond
                     keep? kept-node
                     drop? filtered-rest)]
        (cond
          list-empty? acc
          list-more? branch)))))

(defn filter-list-closure []
  filter-list)

(defn run-fib
  [n-value]
  (let [closure-id (ids/new-node-id)
        n-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id (fib-closure) (fib-closure))
               (nb/install-cell n-id n-value n-value)
               (nb/install-cell out-id))
        [props n1] ((frame/p:apply-closure closure-id [n-id] out-id) n0)
        n2 (queue/run-props n1 props)]
    {:net n2
     :closure-id closure-id
     :n-id n-id
     :out-id out-id
     :value (frame/strongest-or-nothing n2 out-id)}))

(defn run-factorial
  [n-value]
  (let [closure-id (ids/new-node-id)
        n-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id (factorial-closure) (factorial-closure))
               (nb/install-cell n-id n-value n-value)
               (nb/install-cell out-id))
        [props n1] ((frame/p:apply-closure closure-id [n-id] out-id) n0)
        n2 (queue/run-props n1 props)]
    {:net n2
     :closure-id closure-id
     :n-id n-id
     :out-id out-id
     :value (frame/strongest-or-nothing n2 out-id)}))

(defn run-int-sqrt
  [n-value]
  (let [closure-id (ids/new-node-id)
        n-id (ids/new-node-id)
        lo-id (ids/new-node-id)
        hi-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id
                                (int-sqrt-search-closure)
                                (int-sqrt-search-closure))
               (nb/install-cell n-id n-value n-value)
               (nb/install-cell lo-id 0 0)
               (nb/install-cell hi-id n-value n-value)
               (nb/install-cell out-id))
        [props n1] ((frame/p:apply-closure closure-id
                                           [n-id lo-id hi-id]
                                           out-id)
                    n0)
        n2 (queue/run-props n1 props)]
    {:net n2
     :closure-id closure-id
     :n-id n-id
     :lo-id lo-id
     :hi-id hi-id
     :out-id out-id
     :value (frame/strongest-or-nothing n2 out-id)}))

(defn run-map-list-fib
  [values]
  (let [fib-id (ids/new-node-id)
        map-id (ids/new-node-id)
        list-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        list-value (cons-list-value values)
        n0 (-> net/empty-net
               (nb/install-cell fib-id (fib-closure) (fib-closure))
               (nb/install-cell map-id (map-list-closure) (map-list-closure))
               (nb/install-cell list-id list-value list-value)
               (nb/install-cell acc-id empty-list empty-list)
               (nb/install-cell out-id))
        [props n1] ((frame/p:apply-closure map-id
                                           [list-id fib-id acc-id]
                                           out-id)
                    n0)
        n2 (queue/run-props n1 props)]
    {:net n2
     :fib-id fib-id
     :map-id map-id
     :list-id list-id
     :acc-id acc-id
     :out-id out-id
     :value (frame/strongest-or-nothing n2 out-id)}))

(defn run-nested-map-list-fib
  [values]
  (let [map-id (ids/new-node-id)
        mapper-id (ids/new-node-id)
        list-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        list-value (cons-list-value values)
        n0 (-> net/empty-net
               (nb/install-cell map-id (map-list-closure) (map-list-closure))
               (nb/install-cell mapper-id (map-list-fib-closure) (map-list-fib-closure))
               (nb/install-cell list-id list-value list-value)
               (nb/install-cell acc-id empty-list empty-list)
               (nb/install-cell out-id))
        [props n1] ((frame/p:apply-closure map-id
                                           [list-id mapper-id acc-id]
                                           out-id)
                    n0)
        n2 (queue/run-props n1 props)]
    {:net n2
     :map-id map-id
     :mapper-id mapper-id
     :list-id list-id
     :acc-id acc-id
     :out-id out-id
     :value (frame/strongest-or-nothing n2 out-id)}))

(defn run-reduce-list-sum
  [values]
  (let [reduce-id (ids/new-node-id)
        step-id (ids/new-node-id)
        list-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        list-value (cons-list-value values)
        n0 (-> net/empty-net
               (nb/install-cell reduce-id (reduce-list-closure) (reduce-list-closure))
               (nb/install-cell step-id (sum-step-closure) (sum-step-closure))
               (nb/install-cell list-id list-value list-value)
               (nb/install-cell acc-id 0 0)
               (nb/install-cell out-id))
        [props n1] ((frame/p:apply-closure reduce-id
                                           [list-id step-id acc-id]
                                           out-id)
                    n0)
        n2 (queue/run-props n1 props)]
    {:net n2
     :reduce-id reduce-id
     :step-id step-id
     :list-id list-id
     :acc-id acc-id
     :out-id out-id
     :value (frame/strongest-or-nothing n2 out-id)}))

(defn run-filter-list-even
  [values]
  (let [filter-id (ids/new-node-id)
        predicate-id (ids/new-node-id)
        list-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        list-value (cons-list-value values)
        n0 (-> net/empty-net
               (nb/install-cell filter-id (filter-list-closure) (filter-list-closure))
               (nb/install-cell predicate-id
                                (even-predicate-closure)
                                (even-predicate-closure))
               (nb/install-cell list-id list-value list-value)
               (nb/install-cell acc-id empty-list empty-list)
               (nb/install-cell out-id))
        [props n1] ((frame/p:apply-closure filter-id
                                           [list-id predicate-id acc-id]
                                           out-id)
                    n0)
        n2 (queue/run-props n1 props)]
    {:net n2
     :filter-id filter-id
     :predicate-id predicate-id
     :list-id list-id
     :acc-id acc-id
     :out-id out-id
     :value (frame/strongest-or-nothing n2 out-id)}))
