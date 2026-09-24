(ns propagators.experimental.runner.integration-examples
  "Compound-value and accumulating-GUR fixtures run by the generic iterator."
  (:require [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.experimental.runner.examples :as examples]
            [propagators.gur.accumulating :as acc]
            [propagators.gur.subenv :as subenv]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as msg]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def p:fib-base?
  (prop/primitive-propagator
   (fn [v]
     (cond
       (value/contradiction? v) value/contradiction
       (value/nothing? v) value/nothing
       :else (<= v 1)))))

(defn lazy-cons-cell-value
  [head tail]
  (obj/as-accessor-network {:car head :cdr tail}))

(defn lazy-cons-list-value
  [values]
  (reduce (fn [tail head]
            (lazy-cons-cell-value head tail))
          value/nothing
          (reverse values)))

(defn- installers
  [runtime]
  (acc/contextual-installers
   (merge (compile/default-installers)
          {'p:fib-base? p:fib-base?
           'obj/p:car obj/p:car
           'obj/p:cdr obj/p:cdr
           'obj/p:cons obj/p:cons
           'cons obj/p:cons})
   runtime))

(acc/def-recursive experimental-double
  [n out]
  {:installers installers}
  (::+ n n))

(acc/def-recursive experimental-fib
  [n out]
  {:installers installers}
  (let [one 1
        two 2
        base? (::fib-base? n)
        recur? (::not base?)]
    (cond
      base? n
      recur? (::+ (::recur (switch recur? (::- n one)))
                  (::recur (switch recur? (::- n two)))))))

(acc/def-recursive experimental-map-list
  [list mapper acc-list out]
  {:installers installers}
  (let-cell [head rest mapped-rest]
    (obj/p:car head list)
    (obj/p:cdr rest list)
    (let [mapped (::apply mapper head)
          mapped-node (::cons mapped mapped-rest)]
      (when rest
        (p:id (::recur rest mapper acc-list) mapped-rest))
      mapped-node)))

(defn route-owner-ids
  [network]
  (->> (net/net-dict-or-empty network)
       vals
       (keep (fn [route]
               (if (and (vector? route)
                        (= :dispatch/subenv (first route)))
                 (second route)
                 nil)))
       set))

(defn run-accumulated-closure
  [closure arg-values]
  (let [closure-id (ids/new-node-id)
        arg-ids (vec (repeatedly (count arg-values) ids/new-node-id))
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure closure)
               (nb/install-cell out-id))
        n1 (reduce (fn [network [id v]]
                     (nb/install-cell network id v v))
                   n0
                   (map vector arg-ids arg-values))
        [props n2] ((acc/p:apply-closure closure-id arg-ids out-id) n1)
        result (examples/normal-runner
                {:network n2
                 :tasks (tq/enqueue-all tq/empty-queue props)})
        network (:network result)
        applied-net-id (net/network-dict-entry
                        network
                        (acc/application-key closure-id arg-ids out-id))]
    {:result result
     :props props
     :network network
     :out-id out-id
     :applied-net-id applied-net-id}))

(defn- list-slot
  [v slot-key]
  (cond
    (value/unusable? v) v
    (and (net/net? v) (obj/accessor-source-slot-present? v slot-key))
    (obj/accessor-source-slot-value v slot-key)
    (net/net? v) (or (obj/slot-value v slot-key) value/nothing)
    :else value/nothing))

(defn list->vec
  [value]
  (loop [current value
         remaining 32
         result []]
    (cond
      (zero? remaining) result
      (subenv/empty-list? current) result
      (value/unusable? current) result
      :else (recur (list-slot current :cdr)
                   (dec remaining)
                   (conj result (list-slot current :car))))))

(defn- dispatch-route-value
  [network route]
  (case (first route)
    :dispatch/subenv
    (let [[_ owner-id local-id] route]
      (net/network-cell-strongest
       (net/network-cell-strongest network owner-id)
       local-id))
    :dispatch/local
    (net/network-cell-strongest network (second route))
    (throw (ex-info "unknown dispatch route" {:route route}))))

(defn rest-target-with-nothing
  [network owner-id]
  (some (fn [[target route]]
          (if (and (vector? target)
                   (= :env/ref (first target))
                   (= :rest (nth target 2 nil))
                   (vector? route)
                   (= :dispatch/subenv (first route))
                   (= owner-id (second route))
                   (value/nothing? (dispatch-route-value network route)))
            target
            nil))
        (net/net-dict-or-empty network)))

(defn route-late-cdr
  [network target values]
  (let [[tasks routed]
        (core/eval-cell* (net/net-dict-or-empty network)
                         (msg/message target (lazy-cons-list-value values))
                         network)]
    (:network (examples/normal-runner {:network routed :tasks tasks}))))
