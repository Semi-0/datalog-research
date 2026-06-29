(ns propagators.network-vm.nested.gur
  "Small GUR-on-nested-network-VM experiment.

  This is deliberately separate from the canonical accumulating GUR. It tests
  whether recursive HOP topology can be expressed as effects over a network VM."
  (:require [propagators.application :as app]
            [propagators.cells.value :as value]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-vm.nested :as nvm]))

(def recursive-closure-tag :network-vm.nested.gur/recursive-closure?)
(def frame-scope [:network-vm.nested.gur :frames])
(def when-scope [:network-vm.nested.gur :when])

(defn recursive-closure
  [name body]
  {recursive-closure-tag true
   :network-vm.nested.gur/name name
   :network-vm.nested.gur/body body})

(defn recursive-closure?
  [x]
  (and (map? x)
       (true? (get x recursive-closure-tag))
       (ifn? (:network-vm.nested.gur/body x))))

(defn application-key
  [closure-id arg-ids out-id]
  [:network-vm.nested.gur/application closure-id (vec arg-ids) out-id])

(defn frame-declared?
  [n app-key]
  (boolean
   (get-in (net/network-dict-entry n nvm/name-bindings-key)
           [frame-scope app-key])))

(defn when-declared?
  [n when-key]
  (boolean
   (get-in (net/network-dict-entry n nvm/name-bindings-key)
           [when-scope when-key])))

(defn- declare-frame-marker
  [app-key out-id]
  (nvm/bind-name :self frame-scope app-key out-id))

(declare apply-closure-effect when-effect)

(defn- frame-context
  [closure-id app-key]
  {:closure-id closure-id
   :app-key app-key
   :stable-id (fn [& parts] (nvm/stable-node-id (into [app-key] parts)))
   :apply (fn [closure-id arg-ids out-id]
            (apply-closure-effect :self closure-id arg-ids out-id))
   :recur (fn [arg-ids out-id]
            (apply-closure-effect :self closure-id arg-ids out-id))
   :when (fn [when-key condition-id body-f]
           (when-effect :self when-key condition-id body-f))})

(defn- apply-closure-activation
  [closure-id arg-ids out-id app-key]
  (fn [_inputs _outputs network]
    (let [closure (app/cell-strongest-or-nothing network closure-id)]
      (cond
        (or (value/nothing? closure)
            (value/contradiction? closure))
        []

        (not (recursive-closure? closure))
        [(message out-id value/contradiction)]

        (frame-declared? network app-key)
        []

        :else
        (let [ctx (frame-context closure-id app-key)
              body (:network-vm.nested.gur/body closure)]
          (into [(declare-frame-marker app-key out-id)
                 (nvm/bind-name :self app-key :self closure-id)
                 (nvm/bind-name :self app-key :out out-id)]
                (body ctx arg-ids out-id)))))))

(defn apply-closure-effect
  "Declare a recursive closure application in `target`.

  The application prop listens to closure, args, and out. Including out as an
  input boundary lets late output writes wake already-installed bidirectional
  body topology."
  [target closure-id arg-ids out-id]
  (let [app-key (application-key closure-id arg-ids out-id)
        prop-id (nvm/stable-node-id [app-key :apply-prop])
        inputs (distinct (into [closure-id out-id] arg-ids))
        outputs (distinct (into [out-id] arg-ids))]
    (nvm/declare-prop target
                      prop-id
                      inputs
                      outputs
                      (apply-closure-activation closure-id
                                                (vec arg-ids)
                                                out-id
                                                app-key))))

(defn when-effect
  [target when-key condition-id body-f]
  (let [prop-id (nvm/stable-node-id [:when when-key :prop])
        activate (fn [_inputs _outputs network]
                   (let [condition (app/cell-strongest-or-nothing network condition-id)]
                     (cond
                       (value/nothing? condition) []
                       (value/contradiction? condition) []
                       (when-declared? network when-key) []
                       :else (into [(nvm/bind-name :self
                                                   when-scope
                                                   when-key
                                                   condition-id)]
                                   (body-f)))))]
    (nvm/declare-prop target prop-id [condition-id] [] activate)))

(defn const-prop
  [v]
  (fn [_inputs outputs _network]
    [(message (first outputs) v)]))

(defn unary-prop
  [f]
  (fn [inputs outputs network]
    (let [v (app/cell-strongest-or-nothing network (first inputs))]
      (if (value/unusable? v)
        []
        [(message (first outputs) (f v))]))))

(defn binary-prop
  [f]
  (fn [inputs outputs network]
    (let [a (app/cell-strongest-or-nothing network (first inputs))
          b (app/cell-strongest-or-nothing network (second inputs))]
      (if (or (value/unusable? a) (value/unusable? b))
        []
        [(message (first outputs) (f a b))]))))
