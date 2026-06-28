(ns propagators.network-vm.flat.gur
  "Small GUR-on-flat-network-effects experiment.

  This tests recursive HOP as delayed main-network topology. It is separate
  from the canonical accumulating GUR and from the nested network VM."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-vm.flat :as fvm]))

(def recursive-closure-tag :network-vm.flat.gur/recursive-closure?)
(def frame-scope [:network-vm.flat.gur :frames])
(def when-scope [:network-vm.flat.gur :when])

(defn recursive-closure
  [name body]
  {recursive-closure-tag true
   :network-vm.flat.gur/name name
   :network-vm.flat.gur/body body})

(defn recursive-closure?
  [x]
  (and (map? x)
       (true? (get x recursive-closure-tag))
       (ifn? (:network-vm.flat.gur/body x))))

(defn application-key
  [closure-id arg-ids out-id]
  [:network-vm.flat.gur/application closure-id (vec arg-ids) out-id])

(defn- strongest-or-nothing
  [n id]
  (let [entry (get (net/net-env n) id)]
    (if (cell/cell? entry)
      (cell/cell-strongest entry)
      value/nothing)))

(defn frame-declared?
  [n app-key]
  (boolean
   (get-in (net/network-dict-entry n fvm/name-bindings-key)
           [frame-scope app-key])))

(defn when-declared?
  [n when-key]
  (boolean
   (get-in (net/network-dict-entry n fvm/name-bindings-key)
           [when-scope when-key])))

(defn- declare-frame-marker
  [app-key out-id]
  (fvm/bind-name frame-scope app-key out-id))

(declare apply-closure-effect when-effect)

(defn- frame-context
  [closure-id app-key]
  {:closure-id closure-id
   :app-key app-key
   :stable-id (fn [& parts] (fvm/stable-node-id (into [app-key] parts)))
   :apply (fn [closure-id arg-ids out-id]
            (apply-closure-effect closure-id arg-ids out-id))
   :recur (fn [arg-ids out-id]
            (apply-closure-effect closure-id arg-ids out-id))
   :when (fn [when-key condition-id body-f]
           (when-effect when-key condition-id body-f))})

(defn- apply-closure-activation
  [closure-id arg-ids out-id app-key]
  (fn [_inputs _outputs network]
    (let [closure (strongest-or-nothing network closure-id)]
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
              body (:network-vm.flat.gur/body closure)]
          (into [(declare-frame-marker app-key out-id)
                 (fvm/bind-name app-key :self closure-id)
                 (fvm/bind-name app-key :out out-id)]
                (body ctx arg-ids out-id)))))))

(defn apply-closure-effect
  "Declare a recursive closure application into the main network.

  The application prop listens to closure, args, and out. Including out as an
  input lets late output writes wake bidirectional body topology."
  [closure-id arg-ids out-id]
  (let [app-key (application-key closure-id arg-ids out-id)
        prop-id (fvm/stable-node-id [app-key :apply-prop])
        inputs (distinct (into [closure-id out-id] arg-ids))
        outputs (distinct (into [out-id] arg-ids))]
    (fvm/declare-prop prop-id
                      inputs
                      outputs
                      (apply-closure-activation closure-id
                                                (vec arg-ids)
                                                out-id
                                                app-key))))

(defn when-effect
  [when-key condition-id body-f]
  (let [prop-id (fvm/stable-node-id [:when when-key :prop])
        activate (fn [_inputs _outputs network]
                   (let [condition (strongest-or-nothing network condition-id)]
                     (cond
                       (value/nothing? condition) []
                       (value/contradiction? condition) []
                       (when-declared? network when-key) []
                       :else (into [(fvm/bind-name when-scope
                                                   when-key
                                                   condition-id)]
                                   (body-f)))))]
    (fvm/declare-prop prop-id [condition-id] [] activate)))

(defn const-prop
  [v]
  (fn [_inputs outputs _network]
    [(message (first outputs) v)]))

(defn unary-prop
  [f]
  (fn [inputs outputs network]
    (let [v (strongest-or-nothing network (first inputs))]
      (if (value/unusable? v)
        []
        [(message (first outputs) (f v))]))))

(defn binary-prop
  [f]
  (fn [inputs outputs network]
    (let [a (strongest-or-nothing network (first inputs))
          b (strongest-or-nothing network (second inputs))]
      (if (or (value/unusable? a) (value/unusable? b))
        []
        [(message (first outputs) (f a b))]))))
