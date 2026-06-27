(ns propagators.gur.accumulating.core
  "Accumulating GUR closure values and declaration-time frame accumulation."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.gur.subenv.env :as env]
            [propagators.gur.subenv.queue :as queue]
            [propagators.gur.subenv.scoped-slot :as scoped-slot]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def recursive-closure-tag :gur/accumulating-recursive-closure?)
(def frame-index-key [:gur/accumulating :frames])
(def frame-prop-index-key [:gur/accumulating :props])
(def task-index-key [:gur/accumulating :tasks])
(def application-request-index-key [:gur/accumulating :application-requests])
(def frame-scope-prefix [:gur/accumulating :scope])
(def frame-declared-prefix [:gur/accumulating :frame-declared])

(defn add-task-facts
  ([n cause tasks]
   (add-task-facts n cause tasks 0))
  ([n cause tasks index]
   (let [prop-ids (queue/task-ids tasks)]
     (if (empty? prop-ids)
       n
       (net/update-net-dict-entry
        n
        task-index-key
        (fn [task-map]
          (reduce (fn [m prop-id]
                    (update m cause
                            (fn [entry]
                              {:prop-ids (conj (set (:prop-ids entry)) prop-id)
                               :indexes (conj (set (:indexes entry)) index)})))
                  (or task-map {})
                  prop-ids)))))))

(defn application-key
  [closure-id arg-ids out-id]
  [:gur/application closure-id (vec arg-ids) out-id])

(defn application-request-fragment
  [closure-id arg-ids out-id]
  (let [app-key (application-key closure-id arg-ids out-id)]
    (net/assoc-net-dict-entry
     net/empty-net
     application-request-index-key
     {app-key {:closure-id closure-id
               :arg-ids (vec arg-ids)
               :out-id out-id}})))

(defn application-requests
  [n]
  (or (net/network-dict-entry n application-request-index-key) {}))

(defn application-requested?
  [n app-key]
  (contains? (application-requests n) app-key))

(defn recursive-closure
  [name body-fn]
  {recursive-closure-tag true
   :gur/name name
   :gur/body body-fn})

(defn recursive-closure?
  [x]
  (and (map? x)
       (true? (get x recursive-closure-tag))
       (ifn? (get x :gur/body))))

(defn strongest-or-nothing
  [n id]
  (if (contains? (net/net-env n) id)
    (net/network-cell-strongest n id)
    value/nothing))

(defn stable-node-id
  [parts]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:gur/accumulating] parts))
               StandardCharsets/UTF_8))))

(defn stable-id-generator
  [seed]
  (let [counter (atom 0)]
    (fn []
      (stable-node-id [seed (swap! counter inc)]))))

(defn frame-scope-key
  [app-key]
  (conj frame-scope-prefix app-key))

(defn strip-compiler-symbols
  [n]
  (net/net-with-dict
   n
   (into {}
         (remove (fn [[k _v]] (symbol? k)))
         (net/net-dict-or-empty n))))

(defn bind-local-alias
  [n scope name id]
  (-> n
      (env/bind-in-scope scope name id)
      (env/bind-in-scope scope (keyword (clojure.core/name name)) id)))

(defn boundary-cell-ids
  [source-net ids values]
  (distinct
   (concat ids
           (mapcat #(scoped-slot/accessor-parent-cell-ids source-net %)
                   values))))

(defn- frame-scope
  [closure app-key]
  [:gur/accumulating-frame (:gur/name closure) app-key])

(defn frame-declared-key
  [app-key]
  (conj frame-declared-prefix app-key))

(defn frame-declared?
  [runtime-net applied-net app-key]
  (or (true? (net/network-dict-entry runtime-net (frame-declared-key app-key)))
      (and (net/net? applied-net)
           (true? (net/network-dict-entry applied-net
                                          (frame-declared-key app-key))))))

(defn- when-applied-key
  [when-key]
  [:gur/accumulating :when-applied when-key])

(defn- when-applied?
  [runtime-net when-key]
  (true? (net/network-dict-entry runtime-net (when-applied-key when-key))))

(defn- ensure-cell-value
  [n id v]
  (-> n
      (nb/ensure-cell id)
      (nb/seed-cell id v)))

(defn- copy-runtime-cell
  [n runtime-net id]
  (if-let [entry (and (ids/node-id? id)
                      (contains? (net/net-env runtime-net) id)
                      (net/network-env-lookup runtime-net id))]
    (if (cell/cell? entry)
      (cond-> (-> n
                  (nb/ensure-cell id)
                  (net/assoc-net-cell id (cell/cell (cell/cell-content entry)
                                                    (cell/cell-strongest entry))))
        (and (contains? (net/net-dict-or-empty runtime-net) frame-index-key)
             (contains? (net/net-graph runtime-net) id))
        (net/assoc-net-node id (get (net/net-graph runtime-net) id)))
      n)
    (nb/ensure-cell n id)))

(defn- copy-boundary-cells
  [frame-net runtime-net ids values]
  (reduce #(copy-runtime-cell %1 runtime-net %2)
          frame-net
          (boundary-cell-ids runtime-net ids values)))

(defn- bind-frame-args
  [frame-net scope arg-ids]
  (reduce (fn [n [i id]]
            (env/bind-in-scope n scope [:arg i] id))
          frame-net
          (map-indexed vector arg-ids)))

(defn- normalize-body-result
  [result]
  (if (and (map? result) (contains? result :net))
    {:net (:net result)
     :prop-ids (vec (:prop-ids result))}
    {:net result
     :prop-ids []}))

(declare p:accumulate-apply-closure)
(declare p:when-topology)

(defn contextual-api
  [{:keys [self-id applied-net-id]}]
  {:apply (fn [network closure-id arg-ids out-id]
            ((p:accumulate-apply-closure closure-id
                                         arg-ids
                                         applied-net-id
                                         out-id)
             network))
   :recur (fn [network arg-ids out-id]
            ((p:accumulate-apply-closure self-id
                                         arg-ids
                                         applied-net-id
                                         out-id)
             network))
   :when (fn [network condition-id bindings body-expr installers]
           ((p:when-topology condition-id
                             bindings
                             body-expr
                             installers
                             applied-net-id)
            network))})

(defn- frame-context
  [closure self-id applied-net-id scope arg-values]
  {:closure closure
   :self-id self-id
   :applied-net-id applied-net-id
   :scope scope
   :arg-values (vec arg-values)})

(defn build-frame-fragment
  [runtime-net closure-id arg-ids applied-net-id out-id closure arg-values out-value]
  (let [app-key (application-key closure-id arg-ids out-id)
        scope (frame-scope closure app-key)
        self-id (stable-node-id [app-key :self])]
    ;; ponytail: body declaration uses global id generation; pin it per frame or
    ;; repeated accumulation grows topology.
    (with-redefs [ids/new-node-id (stable-id-generator app-key)]
      (let [base (-> net/empty-net
                     (copy-runtime-cell runtime-net closure-id)
                     (copy-boundary-cells runtime-net
                                          (conj (vec arg-ids) out-id)
                                          (conj (vec arg-values) out-value))
                     (nb/ensure-cell applied-net-id)
                     (ensure-cell-value self-id closure)
                     (env/bind-in-scope scope :self self-id)
                     (env/bind-in-scope scope :out out-id)
                     (bind-frame-args scope arg-ids))
            ctx (frame-context closure self-id applied-net-id scope arg-values)
            body-result (normalize-body-result
                         ((:gur/body closure)
                          ctx
                          base
                          arg-ids
                          out-id))
            prop-ids (:prop-ids body-result)]
        (-> (:net body-result)
            (net/assoc-net-dict-entry (frame-declared-key app-key) true)
            (net/update-net-dict-entry frame-index-key #(conj (or % #{}) app-key))
            (net/update-net-dict-entry frame-prop-index-key
                                       #(into (or % #{}) (queue/task-ids prop-ids)))
            (add-task-facts [:frame app-key] prop-ids)
            (net/assoc-net-dict-entry (frame-scope-key app-key) scope))))))

(defn- enough-information-to-apply?
  [closure arg-values out-value]
  (and (not (value/unusable? closure))
       (or (not (apply value/any-unusable-values? arg-values))
           (not (value/unusable? out-value)))))

(defn- apply-state
  [runtime-net closure-id arg-ids out-id]
  {:closure (strongest-or-nothing runtime-net closure-id)
   :arg-values (mapv #(strongest-or-nothing runtime-net %) arg-ids)
   :out-value (strongest-or-nothing runtime-net out-id)})

(defn- accumulate-apply-messages
  [runtime-net closure-id arg-ids applied-net-id out-id]
  (let [app-key (application-key closure-id arg-ids out-id)
        applied-net (strongest-or-nothing runtime-net applied-net-id)
        {:keys [closure arg-values out-value]}
        (apply-state runtime-net closure-id arg-ids out-id)]
    (cond
      (frame-declared? runtime-net applied-net app-key)
      []

      (or (application-requested? runtime-net app-key)
          (and (net/net? applied-net)
               (application-requested? applied-net app-key)))
      []

      (not (enough-information-to-apply? closure arg-values out-value))
      []

      (not (recursive-closure? closure))
      []

      :else
      [(message applied-net-id
                (application-request-fragment closure-id arg-ids out-id))])))

;; Accumulating GUR has one owner frame network. Expanding a child request can
;; wake parent-frame props again, so the runner owns duplicate-work prevention.

(defn p:accumulate-apply-closure
  [closure-id arg-ids applied-net-id out-id]
  (let [arg-ids (vec arg-ids)
        inputs (vec (distinct (conj (into [closure-id] arg-ids) out-id)))]
    (fn [network]
      (let [n0 (reduce nb/ensure-cell network
                       (conj (into inputs [applied-net-id]) out-id))
            [prop-id n1] ((prop/construct-propagator
                           (fn [_inputs _outputs runtime-net]
                             (accumulate-apply-messages runtime-net
                                                        closure-id
                                                        arg-ids
                                                        applied-net-id
                                                        out-id))
                           inputs
                           [applied-net-id])
                          n0)
            entry (net/network-lookup-propagator n1 prop-id)]
        [prop-id (net/assoc-net-prop n1 prop-id (assoc entry :observe :inputs))]))))

(defn- delayed-body-fragment
  [runtime-net when-key bindings body-expr installers]
  ;; ponytail: deterministic body ids keep repeated non-nothing checks idempotent.
  (with-redefs [ids/new-node-id (stable-id-generator [when-key :body])]
    (let [{:keys [net props]} (compile/eval-net-with-bindings
                               runtime-net
                               installers
                               bindings
                               body-expr)]
      (-> net
          strip-compiler-symbols
          (net/update-net-dict-entry frame-prop-index-key
                                     #(into (or % #{}) (queue/task-ids props)))
          (add-task-facts [:when when-key] props)
          (net/assoc-net-dict-entry (when-applied-key when-key) true)))))

(defn p:when-topology
  "Presence-gated topology builder. `nothing` waits; any other value builds."
  [condition-id bindings body-expr installers applied-net-id]
  (let [prop-id (ids/new-node-id)
        when-key [:gur/accumulating :when prop-id]]
    (prop/construct-propagator
     prop-id
     (fn [_inputs _outputs runtime-net]
       (let [condition (strongest-or-nothing runtime-net condition-id)]
         (cond
           (value/nothing? condition)
           []

           (value/contradiction? condition)
           [(message applied-net-id value/contradiction)]

           (when-applied? runtime-net when-key)
           []

           :else
           [(message applied-net-id
                     (delayed-body-fragment runtime-net
                                            when-key
                                            bindings
                                            body-expr
                                            installers))])))
     [condition-id]
     [applied-net-id])))
