(ns propagators.gur.subenv
  "General unbounded recursion through lexical sub-env dispatch.

  This experiment keeps the scheduler domain-agnostic: parent-to-child delivery
  routes through the owner network-valued cell, and child-to-parent outputs use
  the existing boundary diff path.
  "
  (:require [clojure.set :as set]
            [propagators.boundary :as boundary]
            [propagators.cells.cell :as cell]
            [propagators.cells.diff :as diff]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.named-network :as named]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :refer [message message-id message-value]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as stdlib]))

(def scope-key [:env/scope])
(def parent-scope-key [:env/parent-scope])
(def child-queue-key [:gur/child-queue])
(def recursive-closure-tag :gur/recursive-closure?)

(defn scope-ref [scope] [:env/scope scope])
(defn name-ref [scope name] [:env/ref scope name])
(defn cell-ref [scope local-id] [:env/cell-ref scope local-id])
(defn bind-key [name] [:env/bind name])
(defn application-key [closure-id arg-ids out-id]
  [:gur/application closure-id (vec arg-ids) out-id])

(defn extend-env
  ([n scope] (extend-env n scope nil))
  ([n scope parent-scope]
   (cond-> (net/assoc-net-dict-entry n scope-key scope)
     parent-scope (net/assoc-net-dict-entry parent-scope-key parent-scope))))

(defn bind
  [n name local-id]
  (net/assoc-net-dict-entry n (bind-key name) local-id))

(defn current-scope [n] (net/network-dict-entry n scope-key))

(defn- bind-entry?
  [k]
  (and (vector? k)
       (= 2 (count k))
       (= :env/bind (first k))))

(defn bindings
  [child-net]
  (->> (net/net-dict-or-empty child-net)
       (keep (fn [[k local-id]]
               (when (and (bind-entry? k) (ids/node-id? local-id))
                 [(second k) local-id])))))

(defn- env-dispatch-key?
  [k]
  (and (vector? k)
       (contains? #{:env/scope :env/ref :env/cell-ref} (first k))
       (> (count k) 1)))

(defn- env-dispatch-scope
  [k]
  (when (env-dispatch-key? k)
    (second k)))

(defn register-subenv-from-owner
  [parent-net owner-id child-net]
  (if-let [scope (and (net/net? child-net)
                      (net/network-dict-entry child-net scope-key))]
    (let [with-direct-bindings
          (reduce
           (fn [n [name local-id]]
             (-> n
                 (net/assoc-net-dict-entry (scope-ref scope) owner-id)
                 (net/assoc-net-dict-entry (name-ref scope name)
                                           [:dispatch/subenv owner-id local-id])
                 (net/assoc-net-dict-entry (cell-ref scope local-id)
                                           [:dispatch/subenv owner-id local-id])))
           parent-net
           (bindings child-net))]
      (reduce-kv
       (fn [n k _v]
         (if (and (env-dispatch-key? k)
                  (not= scope (env-dispatch-scope k)))
           (net/assoc-net-dict-entry n k [:dispatch/subenv-ref owner-id k])
           n))
       with-direct-bindings
       (net/net-dict-or-empty child-net)))
    parent-net))

(defn maybe-register-subenv
  [parent-net owner-id strongest]
  (if (and (net/net? strongest)
           (net/network-dict-entry strongest scope-key))
    (register-subenv-from-owner parent-net owner-id strongest)
    parent-net))

(defn- directory-map
  [directory]
  (if (net/net? directory)
    (net/net-dict-or-empty directory)
    (or directory {})))

(defn resolve-dispatch
  [directory target _parent-net]
  (let [entry (get (directory-map directory) target)]
    (cond
      (and (vector? entry) (= :dispatch/subenv (first entry)))
      entry

      (and (vector? entry) (= :dispatch/subenv-ref (first entry)))
      entry

      (and (vector? entry) (= :dispatch/local (first entry)))
      entry

      (ids/node-id? entry)
      [:dispatch/local entry]

      (ids/node-id? target)
      [:dispatch/local target]

      :else
      (throw (ex-info "unresolvable cell dispatch target"
                      {:target target :entry entry})))))

(defn- task-ids
  [tasks]
  (cond
    (nil? tasks)
    []

    (ids/node-id? tasks)
    [tasks]

    (tq/task-queue? tasks)
    (loop [q tasks
           ids []]
      (if (tq/queue-empty? q)
        ids
        (let [[id q'] (tq/pop-task q)]
          (recur q' (conj ids id)))))

    (or (sequential? tasks) (set? tasks))
    (vec tasks)

    :else
    (throw (ex-info "unsupported child task collection" {:tasks tasks}))))

(defn- queue-state
  [q]
  {:scheduled (set (:scheduled q))
   :ran (set (:ran q))})

(defn queue-child-props
  [child-net tasks]
  (let [tokens (set (map (fn [prop-id] [prop-id (ids/new-node-id)])
                         (task-ids tasks)))]
    (if (empty? tokens)
      child-net
      (net/update-net-dict-entry
       child-net
       child-queue-key
       (fn [q]
         (update (queue-state q) :scheduled set/union tokens))))))

(defn pending-run-tokens
  [child-net]
  (let [{:keys [scheduled ran]} (queue-state
                                 (net/network-dict-entry child-net
                                                         child-queue-key))]
    (->> (set/difference scheduled ran)
         (sort-by pr-str)
         vec)))

(defn pending-prop-ids
  [child-net]
  (->> (pending-run-tokens child-net)
       (map first)
       distinct
       vec))

(defn- mark-child-run-tokens-ran
  [child-net tokens]
  (let [tokens (set tokens)]
    (if (empty? tokens)
      child-net
      (net/update-net-dict-entry
       child-net
       child-queue-key
       (fn [q]
         (let [q* (queue-state q)]
           (-> q*
               (update :scheduled set/union tokens)
               (update :ran set/union tokens))))))))

(defn mark-child-props-ran
  [child-net prop-ids]
  (let [prop-id-set (set prop-ids)
        tokens (->> (pending-run-tokens child-net)
                    (filter #(contains? prop-id-set (first %)))
                    set)]
    (mark-child-run-tokens-ran child-net tokens)))

(defn- all-scheduled-run-tokens
  [child-net]
  (->> (net/network-dict-entry child-net child-queue-key)
       queue-state
       :scheduled
       (sort-by pr-str)
       vec))

(defn clear-child-queue
  [child-net]
  (mark-child-run-tokens-ran child-net (all-scheduled-run-tokens child-net)))

(defn run-child-queue
  [child-net]
  (let [pending-tokens (pending-run-tokens child-net)
        pending-props (->> pending-tokens
                           (map first)
                           distinct
                           (sort-by pr-str)
                           vec)]
    (if (empty? pending-props)
      child-net
      (let [run-tasks (requiring-resolve 'propagators.core/run-tasks)
            after (run-tasks (tq/enqueue-all tq/empty-queue pending-props)
                             child-net)]
        (mark-child-run-tokens-ran after pending-tokens)))))

(declare externalize-output-value)

(defn- accessor-slot-parent-value
  [child-net accessor-value slot-key]
  (when-let [parent-id (->> (obj/accessor-parent-ids accessor-value slot-key)
                            (filter #(contains? (net/net-env child-net) %))
                            (sort-by pr-str)
                            first)]
    (net/network-cell-strongest child-net parent-id)))

(defn- externalize-accessor-value
  [child-net accessor-value]
  (let [slots (obj/accessor-slot-keys accessor-value)
        source-slots
        (reduce
         (fn [acc slot-key]
           (let [parent-v (accessor-slot-parent-value child-net
                                                       accessor-value
                                                       slot-key)
                 v (if (or (nil? parent-v)
                           (value/unusable? parent-v))
                     (if (obj/accessor-source-slot-present? accessor-value slot-key)
                       (obj/accessor-source-slot-value accessor-value slot-key)
                       parent-v)
                     parent-v)]
             (if (value/unusable? v)
               acc
               (assoc acc slot-key (externalize-output-value child-net v)))))
         {}
         slots)]
    (if (empty? source-slots)
      accessor-value
      (obj/as-accessor-network source-slots))))

(defn externalize-output-value
  [child-net v]
  (if (and (net/net? v) (obj/accessor-network? v))
    (externalize-accessor-value child-net v)
    v))

(defn externalize-output-cells
  [child-net external-output-ids]
  (reduce
   (fn [n external-id]
     (if-let [inner-id (net/lookup-inner-out n external-id)]
       (let [v (net/network-cell-strongest n inner-id)
             v* (externalize-output-value n v)]
         (if (= v v*)
           n
           (nb/seed-cell n inner-id v*)))
       n))
   child-net
   external-output-ids))

(declare eval-cell*)

(defn network-update
  [msg child-net]
  (let [eval-cell (requiring-resolve 'propagators.core/eval-cell)
        [tasks child-net'] (eval-cell (message-id msg) msg child-net)]
    {:network child-net'
     :tasks tasks}))

(defn network-update*
  [msg child-net]
  (let [[tasks child-net'] (eval-cell* (net/net-dict-or-empty child-net)
                                       msg
                                       child-net)]
    {:network child-net'
     :tasks tasks}))

(defn eval-cell*
  [directory msg parent-net]
  (let [eval-cell (requiring-resolve 'propagators.core/eval-cell)]
    (case (first (resolve-dispatch directory (message-id msg) parent-net))
      :dispatch/local
      (let [[_ cell-id] (resolve-dispatch directory (message-id msg) parent-net)]
        (eval-cell cell-id (message cell-id (message-value msg)) parent-net))

      :dispatch/subenv
      (let [[_ owner-id local-id] (resolve-dispatch directory
                                                    (message-id msg)
                                                    parent-net)
            owner-cell (net/network-lookup-cell parent-net owner-id)
            child-net (cell/cell-strongest owner-cell)]
        (if-not (net/net? child-net)
          (eval-cell owner-id (message owner-id value/contradiction) parent-net)
          (let [child-msg (message local-id (message-value msg))
                {:keys [network tasks]} (network-update child-msg child-net)
                update-msg (message owner-id (queue-child-props network tasks))]
            (eval-cell owner-id update-msg parent-net))))

      :dispatch/subenv-ref
      (let [[_ owner-id _target] (resolve-dispatch directory
                                                   (message-id msg)
                                                   parent-net)
            owner-cell (net/network-lookup-cell parent-net owner-id)
            child-net (cell/cell-strongest owner-cell)]
        (if-not (net/net? child-net)
          (eval-cell owner-id (message owner-id value/contradiction) parent-net)
          (let [{:keys [network tasks]} (network-update* msg child-net)
                update-msg (message owner-id (queue-child-props network tasks))]
            (eval-cell owner-id update-msg parent-net)))))))

(defn p:run-subenv-frame
  [owner-id external-output-ids]
  (let [external-output-ids (vec external-output-ids)]
    (prop/construct-propagator
     (fn [_inputs _outputs parent-net]
       (let [child0 (net/network-cell-strongest parent-net owner-id)]
         (if-not (net/net? child0)
           []
           (let [child1 (-> child0
                            run-child-queue
                            (externalize-output-cells external-output-ids))
                 output-msgs (vec (diff/diff-internal-output-cells
                                    child1
                                    parent-net
                                    external-output-ids))]
             (cond-> output-msgs
               (not= child0 child1)
               (conj (message owner-id (clear-child-queue child1))))))))
     [owner-id]
     (into [owner-id] external-output-ids))))

(defn- copy-boundary-cell
  [child-net parent-net id]
  (cond
    (contains? (net/net-env child-net) id)
    child-net

    (contains? (net/net-env parent-net) id)
    (nb/install-cell child-net
                     id
                     (net/network-cell-content parent-net id)
                     (net/network-cell-strongest parent-net id))

    :else
    (nb/ensure-cell child-net id)))

(defn install-frame-boundary
  [parent-net child-net input-ids output-ids]
  (let [outer-ids (vec (distinct (concat input-ids output-ids)))]
    (-> (reduce #(copy-boundary-cell %1 parent-net %2) child-net outer-ids)
        (boundary/create-boundary-outputs output-ids)
        (boundary/create-boundary-inputs input-ids))))

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

(defn- frame-key
  [closure arg-values frame-id]
  [:gur/frame (:gur/name closure) (vec arg-values) frame-id])

(defn applied?
  [frame-net frame-key]
  (true? (net/network-dict-entry frame-net [:gur/applied frame-key])))

(defn mark-applied
  [frame-net frame-key]
  (net/assoc-net-dict-entry frame-net [:gur/applied frame-key] true))

(defn- applied-closure-key
  [frame-key]
  [:gur/applied-closure frame-key])

(defn accumulate-applied-closure
  [frame-net frame-key closure]
  (if (net/network-dict-entry frame-net (applied-closure-key frame-key))
    frame-net
    (let [id (ids/new-node-id)]
      (-> frame-net
          (nb/install-cell id closure closure)
          (net/assoc-net-dict-entry (applied-closure-key frame-key) id)))))

(defn- normalize-body-result
  [result]
  (if (and (map? result) (contains? result :net))
    {:net (:net result)
     :prop-ids (vec (:prop-ids result))}
    {:net result
     :prop-ids []}))

(defn- strongest-or-nothing
  [n id]
  (if (contains? (net/net-env n) id)
    (net/network-cell-strongest n id)
    value/nothing))

(defn- build-frame-net
  [parent-net frame-id closure arg-ids out-id arg-values current-frame]
  (let [scope (frame-key closure arg-values frame-id)
        base (extend-env (if (net/net? current-frame)
                           current-frame
                           net/empty-net)
                         scope)
        with-boundary (install-frame-boundary parent-net base arg-ids [out-id])
        inner-args (mapv (partial net/lookup-inner-in with-boundary) arg-ids)
        out-inner (net/lookup-inner-out with-boundary out-id)
        self-id (ids/new-node-id)
        n0 (-> with-boundary
               (nb/install-cell self-id closure closure)
               (bind :self self-id)
               (bind :out out-inner))
        n1 (reduce (fn [n [i id]]
                     (bind n [:arg i] id))
                   n0
                   (map-indexed vector inner-args))
        body-result (normalize-body-result
                     ((:gur/body closure)
                      {:closure closure
                       :self-id self-id
                       :frame-id frame-id
                       :scope scope
                       :arg-values (vec arg-values)}
                      n1
                      inner-args
                      out-inner))]
    (-> (:net body-result)
        (accumulate-applied-closure scope closure)
        (mark-applied scope)
        (queue-child-props (:prop-ids body-result)))))

(defn p:apply-closure
  [closure-id arg-ids out-id]
  (let [arg-ids (vec arg-ids)
        frame-id (ids/new-node-id)
        inputs (into [closure-id] arg-ids)]
    (fn [network]
      (let [n0 (reduce nb/ensure-cell network (conj inputs out-id frame-id))
            activate
            (fn [_inputs _outputs parent-net]
              (let [closure (strongest-or-nothing parent-net closure-id)
                    arg-values (mapv #(strongest-or-nothing parent-net %) arg-ids)
                    current-frame (strongest-or-nothing parent-net frame-id)]
                (cond
                  (or (value/unusable? closure)
                      (apply value/any-unusable-values? arg-values))
                  []

                  (not (recursive-closure? closure))
                  [(message out-id value/contradiction)]

                  (and (net/net? current-frame)
                       (applied? current-frame
                                 (frame-key closure arg-values frame-id)))
                  []

                  :else
                  [(message frame-id
                            (build-frame-net parent-net
                                             frame-id
                                             closure
                                             arg-ids
                                             out-id
                                             arg-values
                                             current-frame))])))
            [apply-prop n1] ((prop/construct-propagator activate
                                                        inputs
                                                        [frame-id])
                             n0)
            [runner-prop n2] ((p:run-subenv-frame frame-id [out-id]) n1)]
        [[apply-prop runner-prop]
         (net/assoc-net-dict-entry n2
                                   (application-key closure-id arg-ids out-id)
                                   frame-id)]))))

(defn p:contextual-apply
  [closure-id arg-ids out-id]
  (p:apply-closure closure-id arg-ids out-id))

(defn p:contextual-recur
  [self-id arg-ids out-id]
  (p:apply-closure self-id arg-ids out-id))

(defn contextual-api
  [ctx]
  {:apply (fn [network closure-id arg-ids out-id]
            ((p:contextual-apply closure-id arg-ids out-id) network))
   :recur (fn [network arg-ids out-id]
            ((p:contextual-recur (:self-id ctx) arg-ids out-id) network))})

(defn def-recursive
  "Build a recursive closure with contextual apply/recur bound to each frame.

  `closure` receives a map containing `:apply`, `:recur`, `:network`, `:args`,
  `:out`, and `:ctx`, and returns either a network or
  `{:net frame-net :prop-ids [...]}`.
  "
  [name closure]
  (recursive-closure
   name
   (fn [ctx frame-net arg-ids out-id]
     (closure (assoc (contextual-api ctx)
                     :ctx ctx
                     :network frame-net
                     :args arg-ids
                     :out out-id)))))

(defn fib-body
  [{:keys [self-id]} frame-net [n-id] out-id]
  (let [n-value (strongest-or-nothing frame-net n-id)]
    (cond
      (or (value/nothing? n-value)
          (value/contradiction? n-value))
      {:net frame-net :prop-ids []}

      (not (and (integer? n-value) (not (neg? n-value))))
      {:net (nb/seed-cell frame-net out-id value/contradiction)
       :prop-ids []}

      (<= n-value 1)
      {:net (nb/seed-cell frame-net out-id n-value)
       :prop-ids []}

      :else
      (let [one (ids/new-node-id)
            two (ids/new-node-id)
            n1 (ids/new-node-id)
            n2 (ids/new-node-id)
            f1 (ids/new-node-id)
            f2 (ids/new-node-id)
            n0 (-> frame-net
                   (nb/install-cell one 1 1)
                   (nb/install-cell two 2 2)
                   (nb/install-cell n1)
                   (nb/install-cell n2)
                   (nb/install-cell f1)
                   (nb/install-cell f2))
            [minus1-prop n1*] ((stdlib/- n-id one n1) n0)
            [minus2-prop n2*] ((stdlib/- n-id two n2) n1*)
            [fib1-props n3] ((p:contextual-recur self-id [n1] f1) n2*)
            [fib2-props n4] ((p:contextual-recur self-id [n2] f2) n3)
            [plus-prop n5] ((stdlib/+ f1 f2 out-id) n4)]
        {:net n5
         :prop-ids (into [minus1-prop minus2-prop]
                         (into (vec fib1-props)
                               (into (vec fib2-props) [plus-prop])))}))))

(defn fib-definition
  [{frame-net :network
    [n-id] :args
    out-id :out
    recur-fn :recur}]
  (let [n-value (strongest-or-nothing frame-net n-id)]
    (cond
      (or (value/nothing? n-value)
          (value/contradiction? n-value))
      {:net frame-net :prop-ids []}

      (not (and (integer? n-value) (not (neg? n-value))))
      {:net (nb/seed-cell frame-net out-id value/contradiction)
       :prop-ids []}

      (<= n-value 1)
      {:net (nb/seed-cell frame-net out-id n-value)
       :prop-ids []}

      :else
      (let [one (ids/new-node-id)
            two (ids/new-node-id)
            n1 (ids/new-node-id)
            n2 (ids/new-node-id)
            f1 (ids/new-node-id)
            f2 (ids/new-node-id)
            n0 (-> frame-net
                   (nb/install-cell one 1 1)
                   (nb/install-cell two 2 2)
                   (nb/install-cell n1)
                   (nb/install-cell n2)
                   (nb/install-cell f1)
                   (nb/install-cell f2))
            [minus1-prop n1*] ((stdlib/- n-id one n1) n0)
            [minus2-prop n2*] ((stdlib/- n-id two n2) n1*)
            [fib1-props n3] (recur-fn n2* [n1] f1)
            [fib2-props n4] (recur-fn n3 [n2] f2)
            [plus-prop n5] ((stdlib/+ f1 f2 out-id) n4)]
        {:net n5
         :prop-ids (into [minus1-prop minus2-prop]
                         (into (vec fib1-props)
                               (into (vec fib2-props) [plus-prop])))}))))

(defn fib-closure []
  (def-recursive :fib fib-definition))

(def empty-list {::empty true})

(defn empty-list?
  [v]
  (or (= empty-list v)
      (and (map? v)
           (true? (get v ::empty)))
      (and (net/net? v)
           (empty? (obj/accessor-slot-keys v)))))

(defn cons-list-value
  [values]
  (reduce (fn [tail head]
            {:car head :cdr tail})
          empty-list
          (reverse values)))

(defn- source-slot-value
  [v slot-key]
  (cond
    (value/unusable? v)
    v

    (and (net/net? v) (obj/accessor-source-slot-present? v slot-key))
    (obj/accessor-source-slot-value v slot-key)

    (net/net? v)
    (or (obj/slot-value v slot-key) value/nothing)

    (map? v)
    (get v slot-key value/nothing)

    :else
    value/nothing))

(defn list-node-value?
  [v]
  (and (not (empty-list? v))
       (not (value/unusable? v))
       (not (value/contradiction? v))
       (not (value/unusable? (source-slot-value v :car)))
       (not (value/contradiction? (source-slot-value v :cdr)))))

(defn map-list-body
  [{:keys [self-id]} frame-net [list-id mapper-id acc-id] out-id]
  (let [list-value (strongest-or-nothing frame-net list-id)
        head-value (source-slot-value list-value :car)
        rest-value (source-slot-value list-value :cdr)
        head-id (ids/new-node-id)
        rest-id (ids/new-node-id)
        mapped-id (ids/new-node-id)
        mapped-rest-id (ids/new-node-id)
        n0 (-> frame-net
               (nb/install-cell head-id)
               (nb/install-cell rest-id)
               (nb/install-cell mapped-id)
               (nb/install-cell mapped-rest-id)
               (bind :head head-id)
               (bind :rest rest-id)
               (bind :mapped mapped-id)
               (bind :mapped-rest mapped-rest-id))
        [car-prop n1] ((obj/p:car head-id list-id) n0)
        [cdr-prop n2] ((obj/p:cdr rest-id list-id) n1)
        [mapper-props n3] (if (list-node-value? head-value)
                            (let [nested-acc-id (ids/new-node-id)
                                  n (nb/install-cell n2
                                                     nested-acc-id
                                                     empty-list
                                                     empty-list)]
                              ((p:contextual-recur self-id
                                                   [head-id mapper-id nested-acc-id]
                                                   mapped-id)
                               n))
                            ((p:contextual-apply mapper-id [head-id] mapped-id)
                             n2))
        [tail-props n4] (if (empty-list? rest-value)
                          (let [[copy-prop n] ((stdlib/id acc-id mapped-rest-id) n3)]
                            [[copy-prop] n])
                          ((p:contextual-recur self-id
                                               [rest-id mapper-id acc-id]
                                               mapped-rest-id)
                           n3))
        [[car-out-prop cdr-out-prop] n5] ((obj/p:cons mapped-id
                                                       mapped-rest-id
                                                       out-id)
                                          n4)]
    {:net n5
     :prop-ids (into [car-prop cdr-prop]
                     (into (vec mapper-props)
                           (into (vec tail-props)
                                 [car-out-prop cdr-out-prop])))}))

(defn map-list-definition
  [{frame-net :network
    [list-id mapper-id acc-id] :args
    out-id :out
    apply-fn :apply
    recur-fn :recur}]
  (let [list-value (strongest-or-nothing frame-net list-id)
        head-value (source-slot-value list-value :car)
        rest-value (source-slot-value list-value :cdr)
        head-id (ids/new-node-id)
        rest-id (ids/new-node-id)
        mapped-id (ids/new-node-id)
        mapped-rest-id (ids/new-node-id)
        n0 (-> frame-net
               (nb/install-cell head-id)
               (nb/install-cell rest-id)
               (nb/install-cell mapped-id)
               (nb/install-cell mapped-rest-id)
               (bind :head head-id)
               (bind :rest rest-id)
               (bind :mapped mapped-id)
               (bind :mapped-rest mapped-rest-id))
        [car-prop n1] ((obj/p:car head-id list-id) n0)
        [cdr-prop n2] ((obj/p:cdr rest-id list-id) n1)
        [mapper-props n3] (if (list-node-value? head-value)
                            (let [nested-acc-id (ids/new-node-id)
                                  n (nb/install-cell n2
                                                     nested-acc-id
                                                     empty-list
                                                     empty-list)]
                              (recur-fn n
                                        [head-id mapper-id nested-acc-id]
                                        mapped-id))
                            (apply-fn n2 mapper-id [head-id] mapped-id))
        [tail-props n4] (if (empty-list? rest-value)
                          (let [[copy-prop n] ((stdlib/id acc-id mapped-rest-id) n3)]
                            [[copy-prop] n])
                          (recur-fn n3 [rest-id mapper-id acc-id] mapped-rest-id))
        [[car-out-prop cdr-out-prop] n5] ((obj/p:cons mapped-id
                                                       mapped-rest-id
                                                       out-id)
                                          n4)]
    {:net n5
     :prop-ids (into [car-prop cdr-prop]
                     (into (vec mapper-props)
                           (into (vec tail-props)
                                 [car-out-prop cdr-out-prop])))}))

(defn map-list-closure []
  (def-recursive :map-list map-list-definition))

(defn run-fib
  [n-value]
  (let [closure-id (ids/new-node-id)
        n-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id (fib-closure) (fib-closure))
               (nb/install-cell n-id n-value n-value)
               (nb/install-cell out-id))
        [props n1] ((p:apply-closure closure-id [n-id] out-id) n0)
        run-tasks (requiring-resolve 'propagators.core/run-tasks)
        n2 (run-tasks (tq/enqueue-all tq/empty-queue props) n1)]
    {:net n2
     :closure-id closure-id
     :n-id n-id
     :out-id out-id
     :value (strongest-or-nothing n2 out-id)}))

(defn run-map-list-fib
  [values]
  (let [fib-id (ids/new-node-id)
        map-id (ids/new-node-id)
        list-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell fib-id (fib-closure) (fib-closure))
               (nb/install-cell map-id (map-list-closure) (map-list-closure))
               (nb/install-cell list-id (cons-list-value values) (cons-list-value values))
               (nb/install-cell acc-id empty-list empty-list)
               (nb/install-cell out-id))
        [props n1] ((p:apply-closure map-id [list-id fib-id acc-id] out-id) n0)
        run-tasks (requiring-resolve 'propagators.core/run-tasks)
        n2 (run-tasks (tq/enqueue-all tq/empty-queue props) n1)]
    {:net n2
     :fib-id fib-id
     :map-id map-id
     :list-id list-id
     :acc-id acc-id
     :out-id out-id
     :value (strongest-or-nothing n2 out-id)}))
