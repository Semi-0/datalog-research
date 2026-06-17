(ns propagators.gur.subenv.examples
  "Concrete Fibonacci and cons-list probes for lexical sub-env GUR."
  (:require [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur.subenv.env :as env]
            [propagators.gur.subenv.frame :as frame]
            [propagators.gur.subenv.queue :as queue]
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

(defn- numeric-primitive
  [f]
  (prop/primitive-propagator
   (fn [& values]
     (cond
       (some value/contradiction? values)
       value/contradiction

       (some value/nothing? values)
       value/nothing

       :else
       (try
         (apply f values)
         (catch Exception _
           value/contradiction))))))

(def p:+ (numeric-primitive +))
(def p:- (numeric-primitive -))
(def p:* (numeric-primitive *))
(def p:quot (numeric-primitive quot))

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

(defn- contextual-recur-installer
  [recur-fn]
  (fn [& ids]
    (let [arg-ids (vec (butlast ids))
          out-id (last ids)]
      (fn [n]
        (recur-fn n arg-ids out-id)))))

(defn- contextual-apply-installer
  [apply-fn]
  (fn [closure-id & ids]
    (let [arg-ids (vec (butlast ids))
          out-id (last ids)]
      (fn [n]
        (apply-fn n closure-id arg-ids out-id)))))

(defn- p:car-out
  [collection-id out-id]
  (obj/p:car out-id collection-id))

(defn- p:cdr-out
  [collection-id out-id]
  (obj/p:cdr out-id collection-id))

(defn- example-installers
  [{:keys [apply-fn recur-fn]}]
  (cond-> (merge (compile/default-installers)
                 {'p:+ p:+
                  'p:- p:-
                  'p:* p:*
                  'p:quot p:quot
                  'p:+-present p:+-present
                  'p:fib-base? p:fib-base?
                  'p:empty-list? p:empty-list?
                  'p:list-node-value? p:list-node-value?
                  'p:even? p:even?
                  'obj/p:car obj/p:car
                  'obj/p:cdr obj/p:cdr
                  'obj/p:cons obj/p:cons
                  'car p:car-out
                  'cdr p:cdr-out
                  'cons obj/p:cons})
    recur-fn (assoc 'ctx/recur (contextual-recur-installer recur-fn))
    apply-fn (assoc 'ctx/apply (contextual-apply-installer apply-fn))))

(defn- topology-result
  [ctx]
  {:net (:net ctx)
   :prop-ids (:props ctx)})

(defn- bind-env-locals
  [ctx name->sym]
  (assoc ctx
         :net
         (reduce-kv
          (fn [n name sym]
            (env/bind n name (compile/cell-ref ctx sym)))
          (:net ctx)
          name->sym)))

(def p:fib-base?
  (value-predicate
   (fn [v]
     (if (and (integer? v) (not (neg? v)))
       (<= v 1)
       value/contradiction))))

(defn fib-definition
  [{frame-net :network
    [n-id] :args
    out-id :out
    recur-fn :recur}]
  (topology-result
   (compile/eval-net-with-bindings
    frame-net
    (example-installers {:recur-fn recur-fn})
    {'n n-id
     'out out-id}
    '(do
       (seed one 1)
       (seed two 2)
       (-> (::fib-base? n) base?)
       (-> (::not base?) recur?)
       (p:id
        (cond
          base? n
          recur? (::+ (::recur (switch recur? (::- n one)))
                      (::recur (switch recur? (::- n two)))))
        out)))))

(defn fib-closure []
  (frame/def-recursive :fib fib-definition))

(defn factorial-definition
  [{frame-net :network
    [n-id] :args
    out-id :out
    recur-fn :recur}]
  (topology-result
   (compile/eval-net-with-bindings
    frame-net
    (example-installers {:recur-fn recur-fn})
    {'n n-id
     'out out-id}
    '(do
       (seed one 1)
       (-> (::fib-base? n) base?)
       (-> (::not base?) recur?)
       (p:id
        (cond
          base? one
          recur? (::* n (::recur (switch recur? (::- n one)))))
        out)))))

(defn factorial-closure []
  (frame/def-recursive :factorial factorial-definition))

(defn int-sqrt-search-definition
  [{frame-net :network
    [n-id lo-id hi-id] :args
    out-id :out
    recur-fn :recur}]
  (topology-result
   (compile/eval-net-with-bindings
    frame-net
    (example-installers {:recur-fn recur-fn})
    {'n n-id
     'lo lo-id
     'hi hi-id
     'out out-id}
    '(do
       (seed one 1)
       (seed two 2)
       (-> (::<= hi lo) done?)
       (-> (::not done?) search?)
       (-> (::quot (::+ lo hi one) two) mid)
       (-> (::* mid mid) square)
       (-> (::<= square n) fits?)
       (-> (::not fits?) too-big?)
       (-> (::and search? fits?) search-fits?)
       (-> (::and search? too-big?) search-too-big?)
       (-> (::- mid one) hi*)
       (p:id
        (cond
          done? lo
          search-fits? (::recur (switch search-fits? n)
                                 (switch search-fits? mid)
                                 (switch search-fits? hi))
          search-too-big? (::recur (switch search-too-big? n)
                                    (switch search-too-big? lo)
                                    (switch search-too-big? hi*)))
        out)))))

(defn int-sqrt-search-closure []
  (frame/def-recursive :int-sqrt-search int-sqrt-search-definition))

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

(defn map-list-definition
  [{frame-net :network
    [list-id mapper-id acc-id] :args
    out-id :out
    apply-fn :apply
    recur-fn :recur}]
  (let [expr (list 'do
                   '(-> (::empty-list? list) list-empty?)
                   '(-> (::not list-empty?) list-more?)
                   '(-> (switch list-empty? acc) done-list)
                   '(p:id done-list out)
                   '(-> (switch list-more? list) node)
                   '(-> (::car node) head)
                   '(-> (::cdr node) rest)
                   '(p:id (::apply mapper head) mapped)
                   '(-> (::empty-list? rest) rest-empty?)
                   '(-> (::not rest-empty?) rest-more?)
                   '(-> (switch rest-empty? acc) done-rest)
                   '(-> (switch rest-more? rest) rest-recur)
                   '(p:id done-rest mapped-rest)
                   '(p:id (::recur rest-recur mapper acc) mapped-rest)
                   '(-> (::cons mapped mapped-rest) mapped-node)
                   '(p:id (switch list-more? mapped-node) out))]
    (-> (compile/eval-net-with-bindings
         frame-net
         (example-installers {:apply-fn apply-fn
                              :recur-fn recur-fn})
         {'list list-id
          'mapper mapper-id
          'acc acc-id
          'out out-id}
         expr)
        (bind-env-locals {:head 'head
                          :rest 'rest
                          :mapped 'mapped
                          :mapped-rest 'mapped-rest
                          :node 'node
                          :mapped-node 'mapped-node})
        topology-result)))

(defn map-list-closure []
  (frame/def-recursive :map-list map-list-definition))

(defn map-list-fib-definition
  [{frame-net :network
    [list-id] :args
    out-id :out
    apply-fn :apply}]
  (let [expr (list 'do
                   (list 'seed 'map-list (map-list-closure))
                   (list 'seed 'fib (fib-closure))
                   (list 'seed 'acc empty-list)
                   '(ctx/apply map-list list fib acc out))]
    (topology-result
     (compile/eval-net-with-bindings
      frame-net
      (example-installers {:apply-fn apply-fn})
      {'list list-id
       'out out-id}
      expr))))

(defn map-list-fib-closure []
  (frame/def-recursive :map-list-fib
                       {:export-nested-accessors? true}
                       map-list-fib-definition))

(defn sum-step-definition
  [{frame-net :network
    [acc-id value-id] :args
    out-id :out}]
  (topology-result
   (compile/eval-net-with-bindings
    frame-net
    (example-installers {})
    {'acc acc-id
     'value value-id
     'out out-id}
    '(p:id (::+ acc value) out))))

(defn sum-step-closure []
  (frame/def-recursive :sum-step sum-step-definition))

(defn sum-present-step-definition
  [{frame-net :network
    [acc-id value-id] :args
    out-id :out}]
  (topology-result
   (compile/eval-net-with-bindings
    frame-net
    (example-installers {})
    {'acc acc-id
     'value value-id
     'out out-id}
    '(p:id (::+-present acc value) out))))

(defn sum-present-step-closure []
  (frame/def-recursive :sum-present-step sum-present-step-definition))

(defn even-predicate-definition
  [{frame-net :network
    [value-id] :args
    out-id :out}]
  (topology-result
   (compile/eval-net-with-bindings
    frame-net
    (example-installers {})
    {'value value-id
     'out out-id}
    '(p:id (::even? value) out))))

(defn even-predicate-closure []
  (frame/def-recursive :even-predicate even-predicate-definition))

(defn reduce-list-definition
  [{frame-net :network
    [list-id step-id acc-id] :args
    out-id :out
    apply-fn :apply
    recur-fn :recur}]
  (let [expr (list 'do
                   '(-> (::empty-list? list) list-empty?)
                   '(-> (::not list-empty?) list-more?)
                   '(p:id (switch list-empty? acc) out)
                   '(-> (switch list-more? list) node)
                   '(-> (::car node) head)
                   '(-> (::cdr node) rest)
                   '(p:id (::apply step acc head) next-acc)
                   '(-> (::empty-list? rest) rest-empty?)
                   '(-> (::not rest-empty?) rest-more?)
                   '(p:id (switch rest-empty? next-acc) out)
                   '(p:id (::recur (switch rest-more? rest) step next-acc) out))]
    (-> (compile/eval-net-with-bindings
         frame-net
         (example-installers {:apply-fn apply-fn
                              :recur-fn recur-fn})
         {'list list-id
          'step step-id
          'acc acc-id
          'out out-id}
         expr)
        (bind-env-locals {:head 'head
                          :rest 'rest
                          :next-acc 'next-acc
                          :node 'node})
        topology-result)))

(defn reduce-list-closure []
  (frame/def-recursive :reduce-list reduce-list-definition))

(defn prefix-reduce-list-definition
  [{frame-net :network
    [list-id step-id acc-id] :args
    out-id :out
    apply-fn :apply
    recur-fn :recur}]
  (let [expr (list 'do
                   '(-> (::empty-list? list) list-empty?)
                   '(-> (::not list-empty?) list-more?)
                   '(p:id (switch list-empty? acc) out)
                   '(-> (switch list-more? list) node)
                   '(-> (::car node) head)
                   '(-> (::cdr node) rest)
                   '(p:id (::apply step acc head) next-acc)
                   '(-> (::empty-list? rest) rest-empty?)
                   '(-> (::nothing? rest) rest-unknown?)
                   '(-> (::or rest-empty? rest-unknown?) rest-done?)
                   '(p:id (switch rest-done? next-acc) out)
                   '(-> (::not rest-done?) rest-more?)
                   '(p:id (::recur (switch rest-more? rest) step next-acc) out))]
    (-> (compile/eval-net-with-bindings
         frame-net
         (example-installers {:apply-fn apply-fn
                              :recur-fn recur-fn})
         {'list list-id
          'step step-id
          'acc acc-id
          'out out-id}
         expr)
        (bind-env-locals {:head 'head
                          :rest 'rest
                          :next-acc 'next-acc
                          :node 'node})
        topology-result)))

(defn prefix-reduce-list-closure []
  (frame/def-recursive :prefix-reduce-list prefix-reduce-list-definition))

(defn filter-list-definition
  [{frame-net :network
    [list-id predicate-id acc-id] :args
    out-id :out
    apply-fn :apply
    recur-fn :recur}]
  (let [expr (list 'do
                   '(-> (::empty-list? list) list-empty?)
                   '(-> (::not list-empty?) list-more?)
                   '(p:id (switch list-empty? acc) out)
                   '(-> (switch list-more? list) node)
                   '(-> (::car node) head)
                   '(-> (::cdr node) rest)
                   '(p:id (::apply predicate head) keep?)
                   '(-> (::empty-list? rest) rest-empty?)
                   '(-> (::not rest-empty?) rest-more?)
                   '(p:id (switch rest-empty? acc) filtered-rest)
                   '(p:id (::recur (switch rest-more? rest) predicate acc) filtered-rest)
                   '(-> (::cons head filtered-rest) kept-node)
                   '(-> (::not keep?) drop?)
                   '(p:id
                     (cond
                       keep? kept-node
                       drop? filtered-rest)
                     branch)
                   '(p:id (switch list-more? branch) out))]
    (-> (compile/eval-net-with-bindings
         frame-net
         (example-installers {:apply-fn apply-fn
                              :recur-fn recur-fn})
         {'list list-id
          'predicate predicate-id
          'acc acc-id
          'out out-id}
         expr)
        (bind-env-locals {:head 'head
                          :rest 'rest
                          :filtered-rest 'filtered-rest
                          :node 'node
                          :kept-node 'kept-node})
        topology-result)))

(defn filter-list-closure []
  (frame/def-recursive :filter-list filter-list-definition))

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
