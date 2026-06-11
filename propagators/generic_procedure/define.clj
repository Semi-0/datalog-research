(ns propagators.generic-procedure.define
  (:require [propagators.datastructures.compound-object :as obj]
            [propagators.generic-procedure.constants :as constants]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- value-cell
  [n v]
  (let [id (ids/new-node-id)]
    [id (nb/install-cell n id v v)]))

(defn- predicate-vector-installer
  [predicate-ids predicates-id]
  (apply (prop/primitive-propagator vector)
         (conj (vec predicate-ids) predicates-id)))

(defn- install-method-attachment
  [n generic-id method-key predicate-ids matcher-id handler-id]
  (let [predicates-id (ids/new-node-id)
        branch-id (ids/new-node-id)
        n0 (-> n
               (nb/install-cell predicates-id)
               (nb/install-cell branch-id))
        [predicates-prop n1] ((predicate-vector-installer predicate-ids predicates-id) n0)
        [predicates-slot-prop n2] ((obj/p:slot :method/predicates predicates-id branch-id) n1)
        [matcher-slot-prop n3] ((obj/p:slot :method/matcher matcher-id branch-id) n2)
        [handler-slot-prop n4] ((obj/p:slot :method/handler handler-id branch-id) n3)
        [method-slot-prop n5] ((obj/p:slot (vector constants/method-tag method-key)
                                           branch-id
                                           generic-id)
                               n4)]
    [[predicates-prop
      predicates-slot-prop
      matcher-slot-prop
      handler-slot-prop
      method-slot-prop]
     n5]))

(defn make-generic-propagator
  "Initialize `generic-id` with fixed v1 select-one policy and `default-id`.

  Returns an installer. The default remains a normal slot in the generic
  procedure value, so later updates to `default-id` merge through the generic
  cell like any other named-network extension."
  [generic-id default-id]
  (fn [n]
    (let [policy-id (ids/new-node-id)
          n0 (nb/install-cell n policy-id
                              constants/select-one-policy-tag
                              constants/select-one-policy-tag)
          [policy-prop n1] ((obj/p:slot constants/policy-slot policy-id generic-id) n0)
          [default-prop n2] ((obj/p:slot constants/default-slot default-id generic-id) n1)]
      [[policy-prop default-prop] n2])))

(defn- define-generic-propagator*
  "Merge one method branch into an initialized generic procedure cell.

  `predicate-ids` are ordered per-argument predicate closure cells.
  `arg-matcher-id` is a closure cell over predicate result booleans.
  `handler-id` is a closure cell over the filtered arguments."
  [generic-id method-key predicate-ids arg-matcher-id handler-id]
  (fn [n]
    (install-method-attachment n
                               generic-id
                               method-key
                               predicate-ids
                               arg-matcher-id
                               handler-id)))

(defn define-generic-propagator
  "Merge one method branch into an initialized generic procedure cell.

  `predicate-ids` are ordered per-argument predicate closure cells.
  `arg-matcher-id` is a closure cell over predicate result booleans.
  `handler-id` is a closure cell over the filtered arguments."
  [generic-id predicate-ids arg-matcher-id handler-id]
  (define-generic-propagator* generic-id
                              (constants/generated-method-key)
                              predicate-ids
                              arg-matcher-id
                              handler-id))

(defn- define-generic-propagator-handler*
  "Merge one generic handler into `generic-id`.

  `applicability` is built with `match-cells` or `match-cells-pred`.
  `handler` may be a handler closure value or a cell id containing one."
  [generic-id method-key applicability handler]
  (fn [n]
    (let [[predicate-ids n1]
          (reduce
           (fn [[ids acc] predicate-value]
             (let [[predicate-id acc'] (value-cell acc predicate-value)]
               [(conj ids predicate-id) acc']))
           [[] n]
           (:predicate-closures applicability))
          [matcher-id n2] (value-cell n1 (:matcher-closure applicability))
          [handler-id n3] (if (ids/node-id? handler)
                            [handler n2]
                            (value-cell n2 handler))]
      (install-method-attachment n3
                                 generic-id
                                 method-key
                                 predicate-ids
                                 matcher-id
                                 handler-id))))

(defn define-generic-propagator-handler
  "Merge one generic handler into `generic-id`.

  `applicability` is built with `match-cells` or `match-cells-pred`.
  `handler` may be a handler closure value or a cell id containing one."
  [generic-id applicability handler]
  (define-generic-propagator-handler* generic-id
                                      (constants/generated-method-key)
                                      applicability
                                      handler))
