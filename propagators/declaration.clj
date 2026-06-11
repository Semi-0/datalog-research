(ns propagators.declaration
  "Derived declaration combinators built from primitive propagators."
  (:require [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.cursor :as cursor]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.prop :as stdlib-prop]))

(def reducer-props-key :decl/reducer-props)
(def reducer-branch-key :decl/reducer-branch)
(def reducer-item-key :decl/reducer-item)
(def reducer-rest-key :decl/reducer-rest)
(def reducer-next-result-key :decl/reducer-next-result)
(def reducer-bound-step-key :decl/reducer-bound-step)
(def current-item closure/current-item)

(defn closure
  "Wrap a pure declaration transformer as a network closure.

  The transformer receives `closure-net` and the accumulator network.
  "
  [f]
  (closure/closure
   (fn [closure-net _input-ids _output-ids declaration-net]
     (f closure-net declaration-net))
   net/empty-net))

(defn compose
  [& expanders]
  (fn [closure-net declaration-net]
    (reduce (fn [n expander] (expander closure-net n))
            declaration-net
            expanders)))

(defn record
  [k v]
  (fn [_closure-net declaration-net]
    (net/update-net-dict-entry declaration-net k (fnil conj []) v)))

(defn install-prop
  [installer prop-key]
  (fn [_closure-net declaration-net]
    (let [[prop-id n] (installer declaration-net)]
      (net/update-net-dict-entry n prop-key (fnil conj []) prop-id))))

(defn item-slot-key
  [item]
  (:slot-key item))

(defn item-path
  [item]
  (:path item))

(defn item-source-id
  [item]
  (:source-id item))

(defn slot-accessor
  "Declaration transformer that installs an accessor for the current item.

  `parent-id-f` receives the current item and returns the parent/output cell id
  used for the declared accessor.
  "
  [parent-id-f prop-key]
  (fn [closure-net declaration-net]
    (let [item (current-item closure-net)
          slot-key (item-slot-key item)
          source-id (item-source-id item)
          parent-id (parent-id-f item)]
      (if (and slot-key source-id parent-id)
        (let [[prop-id n] ((obj/p:slot slot-key parent-id source-id)
                           (nb/ensure-cell declaration-net parent-id))]
          (net/update-net-dict-entry n prop-key (fnil conj []) prop-id))
        declaration-net))))

(defn- install-all
  [n installers]
  (reduce
   (fn [{:keys [net prop-ids]} installer]
     (let [[prop-id n*] (installer net)]
       {:net n*
        :prop-ids (into prop-ids
                        (if (sequential? prop-id)
                          (vec prop-id)
                          [prop-id]))}))
   {:net n :prop-ids []}
   installers))

(declare reduce-cursor)

(defn- reduce-next-step-closure
  [cursor-id step-id acc-id out-id]
  (closure/closure
   (fn [_closure-net _input-ids _output-ids declaration-net]
     (let [item-id (ids/new-node-id)
           rest-id (ids/new-node-id)
           bound-step-id (ids/new-node-id)
           next-result-id (ids/new-node-id)
           n0 (-> declaration-net
                  (nb/ensure-cell cursor-id)
                  (nb/ensure-cell step-id)
                  (nb/ensure-cell acc-id)
                  (nb/ensure-cell out-id)
                  (nb/install-cell item-id)
                  (nb/install-cell rest-id)
                  (nb/install-cell bound-step-id)
                  (nb/install-cell next-result-id))
           {:keys [net prop-ids]}
           (install-all
            n0
            [(cursor/p:car item-id cursor-id)
             (cursor/p:cdr rest-id cursor-id)
             (closure/p:bind-network step-id item-id bound-step-id)
             (closure/p:apply-network bound-step-id acc-id next-result-id)
             (reduce-cursor rest-id step-id next-result-id out-id)])]
       (-> net
           (net/update-net-dict-entry reducer-props-key
                                      #(into (vec (or % [])) prop-ids))
           (net/assoc-net-dict-entry reducer-item-key item-id)
           (net/assoc-net-dict-entry reducer-rest-key rest-id)
           (net/assoc-net-dict-entry reducer-bound-step-key bound-step-id)
           (net/assoc-net-dict-entry reducer-next-result-key next-result-id))))
   net/empty-net))

(defn reduce-cursor
  "Derived cursor reducer installer.

  This installs the two-exit reducer frame from primitive propagators:
  `done?` sends the accumulator to `out-id`; `more?` applies a next-step
  expander and emits the branch network under `reducer-branch-key`.
  "
  [cursor-id step-id acc-id out-id]
  (fn [n]
    (let [done-id (ids/new-node-id)
          more-id (ids/new-node-id)
          next-step-id (ids/new-node-id)
          branch-id (ids/new-node-id)
          next-step (reduce-next-step-closure cursor-id step-id acc-id out-id)
          n0 (-> n
                 (nb/install-cell done-id)
                 (nb/install-cell more-id)
                 (nb/install-cell next-step-id next-step next-step)
                 (nb/install-cell branch-id))
          {:keys [net prop-ids]}
          (install-all
           n0
           [(stdlib-prop/nothing? cursor-id done-id)
            (stdlib-prop/not done-id more-id)
            (stdlib-prop/when acc-id done-id out-id)
            (closure/p:when-apply-network more-id next-step-id acc-id branch-id)])]
      [prop-ids
       (-> net
           (net/update-net-dict-entry reducer-props-key
                                      #(into (vec (or % [])) prop-ids))
           (net/assoc-net-dict-entry reducer-branch-key branch-id))])))

(defn for-each-cursor
  [cursor-id step-id acc-id out-id]
  (reduce-cursor cursor-id step-id acc-id out-id))

(defn map-cursor
  [cursor-id step-id acc-id out-id]
  (reduce-cursor cursor-id step-id acc-id out-id))

(defn reduce-slots
  [source-id step-id acc-id out-id]
  (fn [n]
    (let [cursor-id (ids/new-node-id)
          n0 (nb/install-cell n cursor-id)
          [slot-prop n1] ((obj/p:slot-cursor source-id cursor-id) n0)
          [reduce-props n2] ((reduce-cursor cursor-id step-id acc-id out-id) n1)
          prop-ids (into [slot-prop] (if (sequential? reduce-props)
                                       reduce-props
                                       [reduce-props]))]
      [prop-ids
       (net/update-net-dict-entry n2 reducer-props-key
                                  #(into (vec (or % [])) prop-ids))])))

(defn map-slots
  [source-id step-id acc-id out-id]
  (reduce-slots source-id step-id acc-id out-id))
