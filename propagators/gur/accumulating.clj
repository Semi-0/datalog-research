(ns propagators.gur.accumulating
  "Parallel accumulating GUR experiment with one network-valued owner cell."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :as diff]
            [propagators.cells.merge :as merge]
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
(def task-index-key [:gur/accumulating :tasks])
(def frame-applied-prefix [:gur/accumulating :applied])
(def frame-scope-prefix [:gur/accumulating :scope])
(def ^:private max-child-steps 65536)

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

(defn- frame-applied?
  [runtime-net applied-net-id app-key]
  (or (true? (net/network-dict-entry runtime-net (frame-applied-key app-key)))
      (let [applied-net (strongest-or-nothing runtime-net applied-net-id)]
        (and (net/net? applied-net)
             (true? (net/network-dict-entry applied-net
                                            (frame-applied-key app-key)))))))

(defn- frame-scope-key
  [app-key]
  (conj frame-scope-prefix app-key))

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

(defn- normalize-body-result
  [result]
  (if (and (map? result) (contains? result :net))
    {:net (:net result)
     :prop-ids (vec (:prop-ids result))}
    {:net result
     :prop-ids []}))

(declare p:accumulate-apply-closure)
(declare p:when-topology)

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
            (add-task-facts [:frame app-key] prop-ids)
            (net/assoc-net-dict-entry (frame-applied-key app-key) true)
            (net/assoc-net-dict-entry (frame-scope-key app-key) scope))))))

(defn- strip-compiler-symbols
  [n]
  (net/net-with-dict
   n
   (into {}
         (remove (fn [[k _v]] (symbol? k)))
         (net/net-dict-or-empty n))))

(defn- missing-closure-input?
  [closure arg-values]
  (or (value/unusable? closure)
      (apply value/any-unusable-values? arg-values)))

(defn- accumulate-apply-messages
  [runtime-net closure-id arg-ids applied-net-id out-id]
  (let [closure (strongest-or-nothing runtime-net closure-id)
        arg-values (mapv #(strongest-or-nothing runtime-net %) arg-ids)
        app-key (application-key closure-id arg-ids out-id)]
    (cond
      (missing-closure-input? closure arg-values)
      []

      (frame-applied? runtime-net applied-net-id app-key)
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

(defn- reset-outbox
  [n applied-net-id]
  (-> n
      (nb/ensure-cell applied-net-id)
      (nb/seed-cell applied-net-id value/nothing)))

(defn- import-parent-cell
  [child-net parent-net id]
  (let [child-entry (get (net/net-env child-net) id)
        parent-entry (get (net/net-env parent-net) id)]
    (if (and (cell/cell? child-entry) (cell/cell? parent-entry))
      (net/assoc-net-cell child-net
                          id
                          (merge/merge-cell-entry child-entry
                                                  (cell/cell-content parent-entry)
                                                  parent-net))
      child-net)))

(defn- prepare-run-net
  [parent-net acc-net applied-net-id import-ids external-output-ids]
  (let [n0 (reduce #(import-parent-cell %1 parent-net %2)
                   (reset-outbox acc-net applied-net-id)
                   import-ids)]
    (reduce (fn [n external-id]
              (-> n
                  (net/assoc-avatar-out external-id external-id)
                  (import-parent-cell parent-net external-id)))
            n0
            external-output-ids)))

(defn- accumulated-prop-ids
  [n]
  (->> (net/net-env n)
       (keep (fn [[id entry]]
               (when (and (prop/prop? entry)
                          (contains? (net/net-graph n) id))
                 id)))
       (sort-by pr-str)
       vec))

(defn- pending-task-facts
  [n task-cursor]
  (->> (net/network-dict-entry n task-index-key)
       (mapcat (fn [[task-key {:keys [prop-ids indexes prop-id]}]]
                 (let [ordered (vec (sort-by pr-str indexes))
                       consumed (set (get task-cursor task-key []))]
                   (for [index ordered
                         :when (not (contains? consumed index))]
                     {:task-key task-key
                      :index index
                      :prop-ids (vec (sort-by pr-str
                                               (or prop-ids #{prop-id})))}))))
       ;; Boundary tasks use the current declared prop index. Run declaration
       ;; tasks first so a boundary index does not get consumed before the props
       ;; it should wake have been added.
       (sort-by (juxt #(if (= :boundary (first (:task-key %))) 1 0)
                      (comp pr-str :task-key)
                      (comp pr-str :index)))
       vec))

(defn- indexed-prop-ids
  [n]
  (vec (sort-by pr-str (net/network-dict-entry n frame-prop-index-key))))

(defn- same-cell-value?
  [a b]
  (and (cell/cell? a)
       (cell/cell? b)
       (= (cell/cell-content a) (cell/cell-content b))
       (= (cell/cell-strongest a) (cell/cell-strongest b))))

(defn- add-boundary-task-facts
  [parent-net acc-net boundary-ids]
  (reduce (fn [n id]
            (if (and (ids/node-id? id)
                     (contains? (net/net-env parent-net) id))
              (let [entry (net/network-env-lookup parent-net id)]
                (if (same-cell-value? entry (get (net/net-env n) id))
                  n
                  (add-task-facts n
                                  [:boundary id]
                                  (indexed-prop-ids n)
                                  (hash {:content (cell/cell-content entry)
                                         :strongest (cell/cell-strongest entry)}))))
              n))
          acc-net
          boundary-ids))

(defn- run-accumulated-child
  [child-net task-cursor]
  ;; ponytail: task facts grow monotonically; this cursor is primitive-local
  ;; runtime state and only records which task indexes have been consumed.
  (loop [remaining max-child-steps
         current child-net]
    (let [pending (first (pending-task-facts current @task-cursor))]
      (cond
        (zero? remaining)
        (throw (ex-info "accumulating GUR child run exceeded step budget"
                        {:max-steps max-child-steps}))

        (nil? pending)
        current

        :else
        (let [{:keys [task-key index prop-ids]} pending
              next (queue/run-props current prop-ids)]
          (swap! task-cursor
                 update
                 task-key
                 #(vec (distinct (conj (or % []) index))))
          (recur (dec remaining) next))))))

(defn- outbox-with-task-facts
  [child-net applied-net-id outbox]
  (add-task-facts outbox
                  [:outbox applied-net-id]
                  (distinct (concat (indexed-prop-ids child-net)
                                    (indexed-prop-ids outbox)))
                  (hash (pr-str outbox))))

(defn- settle-accumulated-child
  [task-cursor parent-net child-net applied-net-id]
  ;; ponytail: owner-local reconciliation; do not publish a half-merged outbox
  ;; and wait for a later runner turn to discover its tasks.
  (loop [remaining 64
         current child-net]
    (when (zero? remaining)
      (throw (ex-info "accumulating GUR outbox reconciliation exceeded step budget"
                      {:max-steps 64})))
    (let [child1 (run-accumulated-child current task-cursor)
          outbox (strongest-or-nothing child1 applied-net-id)]
      (if (net/net? outbox)
        (let [outbox* (outbox-with-task-facts child1 applied-net-id outbox)
              child2 (reset-outbox child1 applied-net-id)
              merged (merge/strongest-value
                      (merge/cell-merge child2 outbox* parent-net)
                      parent-net)]
          (if (= merged child2)
            child2
            (recur (dec remaining) merged)))
        child1))))

(defn- run-accumulated-messages
  [task-cursor parent-net applied-net-id import-ids external-output-ids]
  (let [acc0 (strongest-or-nothing parent-net applied-net-id)]
    (if-not (net/net? acc0)
      []
      (let [acc1 (add-boundary-task-facts parent-net
                                          acc0
                                          (concat import-ids external-output-ids))
            child0 (prepare-run-net parent-net
                                    acc1
                                    applied-net-id
                                    import-ids
                                    external-output-ids)
            child1 (settle-accumulated-child task-cursor
                                             parent-net
                                             child0
                                             applied-net-id)
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
                       queue/clear-external-messages
                       (reset-outbox applied-net-id))]
        (cond-> (vec (concat output-msgs
                              accessor-msgs
                              external-msgs))
          (not= acc0 child2)
          (conj (message applied-net-id child2)))))))

(defn p:run-accumulated-network
  ([applied-net-id external-output-ids]
   (p:run-accumulated-network applied-net-id [] external-output-ids))
  ([applied-net-id import-ids external-output-ids]
   (let [import-ids (vec import-ids)
         external-output-ids (vec external-output-ids)
         inputs (vec (distinct (concat [applied-net-id]
                                       import-ids
                                       external-output-ids)))
         task-cursor (atom {})]
     (prop/construct-propagator
      (fn [_inputs _outputs parent-net]
        (run-accumulated-messages task-cursor
                                  parent-net
                                  applied-net-id
                                  import-ids
                                  external-output-ids))
      inputs
      (into [applied-net-id] external-output-ids)))))

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
            [runner-prop n2] ((p:run-accumulated-network applied-net-id
                                                         arg-ids
                                                         [out-id])
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
                 ((:recur runtime) n arg-ids out-id)))))

    (:when runtime)
    (assoc 'ctx/when
           (fn [condition-id bindings body-expr installers]
             (fn [n]
               ((:when runtime)
                n
                condition-id
                bindings
                body-expr
                installers))))))

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
  {:net (strip-compiler-symbols (:net ctx))
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
