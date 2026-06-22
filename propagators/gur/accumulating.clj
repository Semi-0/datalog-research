(ns propagators.gur.accumulating
  "Parallel accumulating GUR experiment with one network-valued owner cell."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :as diff]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.gur.subenv.env :as env]
            [propagators.gur.subenv.output :as output]
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
(def frame-applied-prefix [:gur/accumulating :applied])
(def frame-scope-prefix [:gur/accumulating :scope])

(defn application-key
  [closure-id arg-ids out-id]
  [:gur/application closure-id (vec arg-ids) out-id])

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

(defn- stable-node-id
  [parts]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:gur/accumulating] parts))
               StandardCharsets/UTF_8))))

(defn- stable-id-generator
  [seed]
  (let [counter (atom 0)]
    (fn []
      (stable-node-id [seed (swap! counter inc)]))))

(defn- frame-scope
  [closure app-key]
  [:gur/accumulating-frame (:gur/name closure) app-key])

(defn- frame-applied-key
  [app-key]
  (conj frame-applied-prefix app-key))

(defn- frame-scope-key
  [app-key]
  (conj frame-scope-prefix app-key))

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

(defn- copy-argument-cells
  [frame-net runtime-net arg-ids arg-values]
  (let [accessor-parent-ids
        (->> arg-values
             (mapcat #(scoped-slot/accessor-parent-cell-ids runtime-net %))
             distinct)]
    (reduce #(copy-runtime-cell %1 runtime-net %2)
            frame-net
            (distinct (concat arg-ids accessor-parent-ids)))))

(defn- bind-local-alias
  [n scope name id]
  (-> n
      (env/bind-in-scope scope name id)
      (env/bind-in-scope scope (keyword (clojure.core/name name)) id)))

(defn- bind-frame-args
  [frame-net scope arg-ids]
  (reduce (fn [n [i id]]
            (env/bind-in-scope n scope [:arg i] id))
          frame-net
          (map-indexed vector arg-ids)))

(defn- queue-frame-props
  [n app-key run-key prop-ids]
  (let [tokens (set (map (fn [prop-id]
                           [prop-id
                            (stable-node-id [app-key :run prop-id run-key])])
                         (queue/task-ids prop-ids)))]
    (if (empty? tokens)
      n
      (net/update-net-dict-entry
       n
       queue/child-queue-key
       (fn [q]
         (let [q* {:scheduled (set (:scheduled q))
                   :ran (set (:ran q))}]
           (update q* :scheduled into tokens)))))))

(defn- normalize-body-result
  [result]
  (if (and (map? result) (contains? result :net))
    {:net (:net result)
     :prop-ids (vec (:prop-ids result))}
    {:net result
     :prop-ids []}))

(declare p:accumulate-apply-closure)

(defn- contextual-api
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
             network))})

(defn- frame-context
  [closure self-id applied-net-id scope arg-values]
  {:closure closure
   :self-id self-id
   :applied-net-id applied-net-id
   :scope scope
   :arg-values (vec arg-values)})

(defn- build-frame-fragment
  [runtime-net closure-id arg-ids applied-net-id out-id closure arg-values]
  (let [app-key (application-key closure-id arg-ids out-id)
        scope (frame-scope closure app-key)
        self-id (stable-node-id [app-key :self])]
    ;; ponytail: body declaration uses global id generation; pin it per frame or
    ;; repeated accumulation grows topology.
    (with-redefs [ids/new-node-id (stable-id-generator app-key)]
      (let [base (-> net/empty-net
                     (copy-argument-cells runtime-net arg-ids arg-values)
                     (copy-runtime-cell runtime-net closure-id)
                     (copy-runtime-cell runtime-net out-id)
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
            (net/update-net-dict-entry frame-index-key #(conj (or % #{}) app-key))
            (net/update-net-dict-entry frame-prop-index-key
                                       #(into (or % #{}) (queue/task-ids prop-ids)))
            (net/assoc-net-dict-entry (frame-applied-key app-key) true)
            (net/assoc-net-dict-entry (frame-scope-key app-key) scope)
            (queue-frame-props app-key arg-values prop-ids))))))

(defn- missing-closure-input?
  [closure arg-values]
  (or (value/unusable? closure)
      (apply value/any-unusable-values? arg-values)))

(defn- accumulate-apply-messages
  [runtime-net closure-id arg-ids applied-net-id out-id]
  (let [closure (strongest-or-nothing runtime-net closure-id)
        arg-values (mapv #(strongest-or-nothing runtime-net %) arg-ids)]
    (cond
      (missing-closure-input? closure arg-values)
      []

      (not (recursive-closure? closure))
      [(message out-id value/contradiction)]

      :else
      [(message applied-net-id
                (build-frame-fragment runtime-net
                                      closure-id
                                      arg-ids
                                      applied-net-id
                                      out-id
                                      closure
                                      arg-values))])))

(defn p:accumulate-apply-closure
  [closure-id arg-ids applied-net-id out-id]
  (let [arg-ids (vec arg-ids)
        inputs (into [closure-id] arg-ids)]
    (fn [network]
      (let [n0 (reduce nb/ensure-cell network
                       (conj (into inputs [applied-net-id]) out-id))]
        ((prop/construct-propagator
          (fn [_inputs _outputs runtime-net]
            (accumulate-apply-messages runtime-net
                                       closure-id
                                       arg-ids
                                       applied-net-id
                                       out-id))
          inputs
          [applied-net-id out-id])
         n0)))))

(defn- reset-outbox
  [n applied-net-id]
  (-> n
      (nb/ensure-cell applied-net-id)
      (nb/seed-cell applied-net-id value/nothing)))

(defn- prepare-run-net
  [acc-net applied-net-id external-output-ids]
  (reduce #(net/assoc-avatar-out %1 %2 %2)
          (reset-outbox acc-net applied-net-id)
          external-output-ids))

(defn- run-accumulated-messages
  [parent-net applied-net-id external-output-ids]
  (let [acc0 (strongest-or-nothing parent-net applied-net-id)]
    (if-not (net/net? acc0)
      []
      (let [child0 (prepare-run-net acc0 applied-net-id external-output-ids)
            child1 (queue/run-child-queue child0)
            outbox (strongest-or-nothing child1 applied-net-id)
            diff-view (output/externalize-output-cells child1 external-output-ids)
            output-msgs (vec (diff/diff-internal-output-cells
                              diff-view
                              parent-net
                              external-output-ids))
            accessor-msgs (scoped-slot/direct-child-accessor-messages-for-scopes
                           parent-net
                           child1
                           (env/scopes child1))
            external-msgs (queue/external-messages child1)
            child2 (-> child1
                       queue/clear-child-queue
                       queue/clear-external-messages
                       (reset-outbox applied-net-id))]
        (cond-> (vec (concat output-msgs
                              accessor-msgs
                              external-msgs))
          (not= acc0 child2)
          (conj (message applied-net-id child2))

          (net/net? outbox)
          (conj (message applied-net-id outbox)))))))

(defn p:run-accumulated-network
  [applied-net-id external-output-ids]
  (let [external-output-ids (vec external-output-ids)]
    (prop/construct-propagator
     (fn [_inputs _outputs parent-net]
       (run-accumulated-messages parent-net
                                 applied-net-id
                                 external-output-ids))
     [applied-net-id]
     (into [applied-net-id] external-output-ids))))

(defn p:apply-closure
  [closure-id arg-ids out-id]
  (let [arg-ids (vec arg-ids)
        applied-net-id (ids/new-node-id)]
    (fn [network]
      (let [n0 (reduce nb/ensure-cell network
                       (conj (into [closure-id applied-net-id] arg-ids) out-id))
            [apply-prop n1] ((p:accumulate-apply-closure closure-id
                                                         arg-ids
                                                         applied-net-id
                                                         out-id)
                             n0)
            [runner-prop n2] ((p:run-accumulated-network applied-net-id [out-id])
                              n1)]
        [[apply-prop runner-prop]
         (net/assoc-net-dict-entry n2
                                   (application-key closure-id arg-ids out-id)
                                   applied-net-id)]))))

(defn contextual-installers
  [installers runtime]
  (cond-> installers
    (:apply runtime)
    (assoc 'ctx/apply
           (fn [closure-id & ids]
             (let [arg-ids (vec (butlast ids))
                   out-id (last ids)]
               (fn [n]
                 ((:apply runtime) n closure-id arg-ids out-id)))))

    (:recur runtime)
    (assoc 'ctx/recur
           (fn [& ids]
             (let [arg-ids (vec (butlast ids))
                   out-id (last ids)]
               (fn [n]
                 ((:recur runtime) n arg-ids out-id)))))))

(defn default-installers
  [runtime]
  (contextual-installers (compile/default-installers) runtime))

(defn- body-expr
  [body]
  (if (and (seq? body) (= 'do (first body)))
    body
    (list 'do body)))

(defn- source-expr
  [body out-sym]
  (list 'p:id (body-expr body) out-sym))

(defn- bindable-cell?
  [n id]
  (contains? (net/net-env n) id))

(defn- bind-compiled-locals
  [ctx scope]
  (assoc ctx
         :net
         (reduce-kv
          (fn [n sym value]
            (if (and (symbol? sym) (bindable-cell? n value))
              (bind-local-alias n scope sym value)
              n))
          (:net ctx)
          (net/net-dict-or-empty (:net ctx)))))

(defn- topology-result
  [ctx]
  {:net (net/net-with-dict
         (:net ctx)
         (into {}
               (remove (fn [[k _v]] (symbol? k)))
               (net/net-dict-or-empty (:net ctx))))
   :prop-ids (:props ctx)})

(defn recursive-definition
  [params body installer-fn]
  (let [params (vec params)
        arg-syms (vec (butlast params))
        out-sym (last params)]
    (when-not (and (seq params) (every? symbol? params))
      (throw (ex-info "recursive frame argument symbols required"
                      {:params params})))
    (fn [{frame-net :network
          arg-ids :args
          out-id :out
          scope :scope
          :as runtime}]
      (when-not (= (count arg-syms) (count arg-ids))
        (throw (ex-info "recursive frame argument count mismatch"
                        {:params params
                         :args arg-ids})))
      (-> (compile/eval-net-with-bindings
           frame-net
           (installer-fn runtime)
           (assoc (zipmap arg-syms arg-ids) out-sym out-id)
           (source-expr body out-sym))
          (bind-compiled-locals scope)
          topology-result))))

(defn source-recursive-closure
  ([name params body]
   (source-recursive-closure name params body default-installers))
  ([name params body installer-fn]
   (recursive-closure
    name
    (fn [ctx frame-net arg-ids out-id]
      ((recursive-definition params body installer-fn)
       (assoc (contextual-api ctx)
              :ctx ctx
              :network frame-net
              :args arg-ids
              :out out-id
              :scope (:scope ctx)))))))

(defmacro def-recursive
  [name params & body]
  (let [[opts body] (if (map? (first body))
                      [(first body) (rest body)]
                      [{} body])
        closure-name (or (:name opts) (keyword name))
        installer-fn (:installers opts)
        seed-values (:seed-values opts)
        do-sym (symbol "do")
        seed-sym (symbol "seed")
        body-code (if (seq seed-values)
                    `(list* '~do-sym
                            (concat
                             (list ~@(map (fn [[sym value-expr]]
                                            `(list '~seed-sym '~sym ~value-expr))
                                          seed-values))
                             '~body))
                    `'(~do-sym ~@body))]
    (if installer-fn
      `(def ~name
         (source-recursive-closure ~closure-name
                                   '~params
                                   ~body-code
                                   ~installer-fn))
      `(def ~name
         (source-recursive-closure ~closure-name
                                   '~params
                                   ~body-code)))))
