(ns propagators.network-vm.gur
  "Tiny GUR-on-network-VM experiment.

  This is not the canonical GUR implementation. It exists to test whether
  recursive declaration can be explained as monotone VM instructions."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-vm :as vm]
            [propagators.network-vm.instructions :as instr])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def recursive-closure-tag :network-vm.gur/recursive-closure?)
(def application-request-index-key [:network-vm.gur :application-requests])
(def frame-scope [:network-vm.gur :frames])
(def when-scope [:network-vm.gur :when])

(defn stable-node-id
  [parts]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:network-vm.gur] parts))
               StandardCharsets/UTF_8))))

(defn recursive-closure
  [name body]
  {recursive-closure-tag true
   :network-vm.gur/name name
   :network-vm.gur/body body})

(defn recursive-closure?
  [x]
  (and (map? x)
       (true? (get x recursive-closure-tag))
       (ifn? (:network-vm.gur/body x))))

(defn application-key
  [closure-id arg-ids out-id]
  [:network-vm.gur/application closure-id (vec arg-ids) out-id])

(defn application-request-fragment
  [closure-id arg-ids out-id]
  (let [app-key (application-key closure-id arg-ids out-id)]
    (net/assoc-net-dict-entry
     net/empty-net
     application-request-index-key
     {app-key {:closure-id closure-id
               :arg-ids (vec arg-ids)
               :out-id out-id}})))

(defn apply-request
  [request-cell-id closure-id arg-ids out-id]
  (vm/tell request-cell-id
           (application-request-fragment closure-id arg-ids out-id)))

(defn strongest-or-nothing
  [n id]
  (let [entry (get (net/net-env n) id)]
    (if (cell/cell? entry)
      (cell/cell-strongest entry)
      value/nothing)))

(defn- application-requests
  [n request-cell-id]
  (let [request-net (strongest-or-nothing n request-cell-id)]
    (if (net/net? request-net)
      (or (net/network-dict-entry request-net application-request-index-key) {})
      {})))

(defn frame-declared?
  [vm-state app-key]
  (boolean
   (get-in (net/network-dict-entry (:net vm-state) instr/name-bindings-key)
           [frame-scope app-key])))

(defn when-declared?
  [vm-state when-key]
  (boolean
   (get-in (net/network-dict-entry (:net vm-state) instr/name-bindings-key)
           [when-scope when-key])))

(defn- declare-frame-marker
  [app-key out-id]
  (vm/bind-name frame-scope app-key out-id))

(defn- frame-context
  [request-cell-id closure-id app-key]
  {:request-cell-id request-cell-id
   :closure-id closure-id
   :app-key app-key
   :stable-id (fn [& parts] (stable-node-id (into [app-key] parts)))
   :apply (fn [closure-id arg-ids out-id]
            (apply-request request-cell-id closure-id arg-ids out-id))
   :recur (fn [arg-ids out-id]
            (apply-request request-cell-id closure-id arg-ids out-id))})

(defn- expandable-request
  [vm-state request-cell-id app-key {:keys [closure-id arg-ids out-id]}]
  (let [n (:net vm-state)
        closure (strongest-or-nothing n closure-id)
        arg-values (mapv #(strongest-or-nothing n %) arg-ids)]
    (when (and (recursive-closure? closure)
               (not (some value/unusable? arg-values)))
      (let [ctx (frame-context request-cell-id closure-id app-key)
            body (:network-vm.gur/body closure)
            body-instructions (body ctx arg-ids out-id arg-values)]
        (into [(declare-frame-marker app-key out-id)
               (vm/bind-name app-key :self closure-id)
               (vm/bind-name app-key :out out-id)]
              body-instructions)))))

(defn expand-application-requests
  [vm-state request-cell-id]
  (reduce (fn [state [app-key request]]
            (if (frame-declared? state app-key)
              state
              (if-let [instructions (expandable-request state
                                                        request-cell-id
                                                        app-key
                                                        request)]
                (vm/apply-instructions state instructions)
                state)))
          vm-state
          (application-requests (:net vm-state) request-cell-id)))

(defn run-until-cold
  ([vm-state request-cell-id]
   (run-until-cold vm-state request-cell-id 1024))
  ([vm-state request-cell-id max-steps]
   (loop [remaining max-steps
          current vm-state]
     (when (zero? remaining)
       (throw (ex-info "network VM GUR exceeded step budget"
                       {:max-steps max-steps
                        :temperature (vm/temperature current)})))
     (let [settled (vm/run-until-cold current max-steps)
           expanded (expand-application-requests settled request-cell-id)]
       (if (and (vm/cold? expanded)
                (= (:net settled) (:net expanded)))
         expanded
         (recur (dec remaining) expanded))))))

(defn expand-when
  [vm-state when-key condition-id body-instructions]
  (let [condition (strongest-or-nothing (:net vm-state) condition-id)]
    (cond
      (value/nothing? condition) vm-state
      (when-declared? vm-state when-key) vm-state
      :else (vm/apply-instructions
             vm-state
             (into [(vm/bind-name when-scope when-key condition-id)]
                   body-instructions)))))

(defn const-prop
  [value]
  (fn [_inputs outputs _network]
    [(message (first outputs) value)]))

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
