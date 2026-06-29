(ns propagators.gur.flat
  "Small GUR-on-flat-network-effects experiment.

  This tests recursive HOP as delayed main-network topology. It is separate
  from the canonical accumulating GUR and from the nested network VM."
  (:require [propagators.application :as app]
            [propagators.cells.value :as value]
            [propagators.gur.flat.effects :as effects]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-vm.instructions :as instr]))

(def root-key effects/root-key)
(def cell-index-key effects/cell-index-key)
(def prop-index-key effects/prop-index-key)
(def name-bindings-key effects/name-bindings-key)

(def stable-node-id effects/stable-node-id)
(def vm-net effects/vm-net)

(def declare-cell instr/declare-cell)
(def declare-prop instr/declare-prop)
(def bind-name instr/bind-name)

(def recursive-closure-tag :gur.flat/recursive-closure?)
(def frame-scope [:gur.flat :frames])
(def when-scope [:gur.flat :when])

(defn recursive-closure
  [name body]
  {recursive-closure-tag true
   :gur.flat/name name
   :gur.flat/body body})

(defn recursive-closure?
  [x]
  (and (map? x)
       (true? (get x recursive-closure-tag))
       (ifn? (:gur.flat/body x))))

(defn application-key
  [closure-id arg-ids out-id]
  [:gur.flat/application closure-id (vec arg-ids) out-id])

(defn frame-declared?
  [n app-key]
  (boolean
   (get-in (net/network-dict-entry n name-bindings-key)
           [frame-scope app-key])))

(defn when-declared?
  [n when-key]
  (boolean
   (get-in (net/network-dict-entry n name-bindings-key)
           [when-scope when-key])))

(defn- declare-frame-marker
  [app-key out-id]
  (bind-name frame-scope app-key out-id))

(declare apply-closure-effect when-effect)

(defn- application-inputs
  [closure-id arg-ids out-id]
  (distinct (into [closure-id out-id] arg-ids)))

(defn- application-outputs
  [arg-ids out-id]
  (distinct (into [out-id] arg-ids)))

(defn- unusable-closure?
  [closure]
  (or (value/nothing? closure)
      (value/contradiction? closure)))

(defn- frame-bootstrap
  [app-key closure-id out-id]
  [(declare-frame-marker app-key out-id)
   (bind-name app-key :self closure-id)
   (bind-name app-key :out out-id)])

(defn- frame-context
  [network closure-id app-key]
  {:closure-id closure-id
   :app-key app-key
   :network network
   :stable-id (fn [& parts] (stable-node-id (into [app-key] parts)))
   :apply (fn [closure-id arg-ids out-id]
            (apply-closure-effect closure-id arg-ids out-id))
   :recur (fn [arg-ids out-id]
            (apply-closure-effect closure-id arg-ids out-id))
   :when (fn [when-key condition-id body-f]
           (when-effect when-key condition-id body-f))})

(defn- apply-closure-activation
  [closure-id arg-ids out-id app-key]
  (fn [_inputs _outputs network]
    (let [closure (app/cell-strongest-or-nothing network closure-id)]
      (cond
        (unusable-closure? closure)
        []

        (not (recursive-closure? closure))
        [(message out-id value/contradiction)]

        (frame-declared? network app-key)
        []

        :else
        (let [ctx (frame-context network closure-id app-key)
              body (:gur.flat/body closure)]
          [(frame-bootstrap app-key closure-id out-id)
           (body ctx arg-ids out-id)])))))

(defn apply-closure-effect
  "Declare a recursive closure application into the main network.

  The application prop listens to closure, args, and out. Including out as an
  input lets late output writes wake bidirectional body topology."
  [closure-id arg-ids out-id]
  (let [app-key (application-key closure-id arg-ids out-id)
        prop-id (stable-node-id [app-key :apply-prop])]
    (declare-prop prop-id
                      (application-inputs closure-id arg-ids out-id)
                      (application-outputs arg-ids out-id)
                      (apply-closure-activation closure-id
                                                (vec arg-ids)
                                                out-id
                                                app-key))))

(defn when-effect
  [when-key condition-id body-f]
  (let [prop-id (stable-node-id [:when when-key :prop])
        activate (fn [_inputs _outputs network]
                   (let [condition (app/cell-strongest-or-nothing network condition-id)]
                     (cond
                       (value/nothing? condition) []
                       (value/contradiction? condition) []
                       (when-declared? network when-key) []
                       :else [(bind-name when-scope
                                             when-key
                                             condition-id)
                              (body-f)])))]
    (declare-prop prop-id [condition-id] [] activate)))

(defn- install-context
  [network app-key]
  ((requiring-resolve 'propagators.install/context) network app-key))

(defn- install-result
  [ctx]
  ((requiring-resolve 'propagators.install/result) ctx))

(defn recursive-declaration
  "Recursive closure whose body writes through `propagators.install`.

  `body` receives an install context, argument ids, and output id. It returns an
  updated install context; emitted effects become the recursive frame topology.
  "
  [name body]
  (recursive-closure
   name
   (fn [{:keys [network app-key apply recur when]} arg-ids out-id]
     (let [ctx (assoc (install-context network app-key)
                      :apply apply
                      :recur recur
                      :when when)]
       (install-result (body ctx arg-ids out-id))))))

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
