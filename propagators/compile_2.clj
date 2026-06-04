(ns propagators.compile-2
  "Compound-object AST compiler with lexical compound declarations.

  This compiler treats AST nodes and environments as slot-readable compound
  objects. It produces ordinary propagator network data; lexical compound
  declarations compile to closure-valued cells and applications lower to normal
  compound propagators.
  "
  (:refer-clojure :exclude [compile])
  (:require [clojure.core :as core]
            [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.intensity :as intensity]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as stdlib-prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

;; AST constructors. They intentionally return ordinary Clojure data that can be
;; normalized by compound-object and read through slot accessors.

(defn lit [v] {:ast/type :literal :ast/value v})
(defn sym [s] {:ast/type :symbol :ast/name s})

(declare ast)

(defn app [op & args]
  {:ast/type :apply
   :ast/operator (ast op)
   :ast/args (mapv ast args)})

(defn app->
  "Application with an explicit output cell expression.

  `app` remains expression-oriented and allocates a fresh result cell. `app->`
  is propagator-oriented: compiled inputs wire into the supplied output cell.
  "
  [op args out]
  {:ast/type :apply
   :ast/operator (ast op)
   :ast/args (mapv ast args)
   :ast/output (ast out)})

(defn do* [& body]
  {:ast/type :do
   :ast/body (mapv ast body)})

(defn let-cell [names body]
  {:ast/type :let-cell
   :ast/names (vec names)
   :ast/body (ast body)})

(defn compound
  [{:keys [inputs output]} body]
  {:ast/type :compound
   :ast/inputs (vec inputs)
   :ast/output output
   :ast/body (ast body)})

(defn let-compound
  [name compound-expr body]
  {:ast/type :let-compound
   :ast/name name
   :ast/value (ast compound-expr)
   :ast/body (ast body)})

(defn ast
  [x]
  (cond
    (and (map? x) (contains? x :ast/type)) x
    (symbol? x) (sym x)
    :else (lit x)))

;; Environment and binding values.

(def env-depth-key :env/depth)
(def compiler-result-key :compiler/result)
(def compiler-props-key :compiler/props)

(defn cell-binding [id]
  {:binding/type :cell
   :binding/id id})

(defn operator-binding [apply-f]
  {:binding/type :operator
   :binding/apply apply-f})

(defn compound-binding [closure-id capture-ids]
  {:binding/type :compound
   :binding/id closure-id
   :binding/captures (vec capture-ids)})

(defn- object->map
  [x]
  (let [o (obj/compound-object x)]
    (into {}
          (map (fn [k] [k (obj/slot-value o k)]))
          (obj/public-slot-keys o))))

(defn- env-depth
  [env]
  (let [depth (obj/slot-value env env-depth-key)]
    (if (number? depth) depth 0)))

(defn- assoc-env-slot
  [env k v]
  (obj/compound-object (assoc (object->map env) k v)))

(defn set-env-depth
  [env depth]
  (assoc-env-slot env env-depth-key depth))

(defn enter-scope
  [env]
  (set-env-depth env (inc (env-depth env))))

(defn- slot-present?
  [env k]
  (contains? (obj/public-slot-keys env) k))

(defn bind
  "Bind `sym` to `binding` in compound-object env.

  Binding slots store intensity candidates. Higher-depth child bindings shadow
  lower-depth parent bindings by strongest intensity.
  "
  ([env sym binding]
   (bind env sym binding (env-depth env)))
  ([env sym binding depth]
   (let [candidate (intensity/intensity-value depth binding)
         existing? (slot-present? env sym)
         existing (when existing? (obj/slot-value env sym))
         merged (if existing?
                  (intensity/merge-content existing candidate)
                  candidate)]
     (assoc-env-slot env sym merged))))

(defn lookup-entry
  "Return {:binding b :intensity i} for a symbol in env, or nil."
  [env sym]
  (when (slot-present? env sym)
    (let [slot (obj/slot-value env sym)
          strongest (if (intensity/intensity-content? slot)
                      (intensity/strongest-value slot)
                      slot)]
      (cond
        (value/unusable? strongest) nil
        (intensity/intensity-value? strongest)
        {:binding (intensity/base-value strongest)
         :intensity (intensity/intensity strongest)}
        :else
        {:binding strongest
         :intensity nil}))))

(defn lookup [env sym]
  (:binding (lookup-entry env sym)))

(defn installer-operator
  "Wrap a normal propagator installer as a compile-2 operator.

  The installer receives all compiled argument cell ids plus a fresh output cell.
  This works for primitive installers and higher-level installers such as a
  layered operator that has already been specialized with its procedure cell.
  "
  [installer]
  (operator-binding
   (fn [network arg-ids out-id]
     (let [[prop-id network'] ((apply installer (conj (vec arg-ids) out-id))
                               network)]
       [network' [prop-id] out-id]))))

(defn primitive-operator
  "Wrap an ordinary pure function as a compile-2-local primitive operator.

  Unlike the project-wide primitive helper, this wrapper waits when any argument
  cell is unusable. That makes partial compile/evaluate examples isolated to
  this compiler without changing core runtime behavior.
  "
  [f]
  (operator-binding
   (fn [network arg-ids out-id]
     (let [[prop-id network']
           ((prop/construct-propagator
             (fn [_inputs _outputs current-net]
               (let [values (mapv #(net/network-cell-strongest current-net %)
                                  arg-ids)]
                 (if (apply value/any-unusable-values? values)
                   []
                   [(message out-id (apply f values))])))
             arg-ids
             [out-id])
            network)]
       [network' [prop-id] out-id]))))

(defn- bi-sync-operator
  []
  (operator-binding
   (fn [network arg-ids _out-id]
     (let [[a b] (vec arg-ids)]
       (when-not (and a b (= 2 (count arg-ids)))
         (throw (ex-info "<-> expects exactly two arguments" {:arg-ids arg-ids})))
       (let [[a->b network'] ((stdlib-prop/id a b) network)
             [b->a network''] ((stdlib-prop/id b a) network')]
         [network'' [a->b b->a] b])))))

(defn default-env
  []
  (-> (obj/empty-compound-object)
      (set-env-depth 0)
      (bind '+ (primitive-operator core/+) 0)
      (bind '- (primitive-operator core/-) 0)
      (bind '* (primitive-operator core/*) 0)
      (bind '/ (primitive-operator core//) 0)
      (bind 'switch (primitive-operator
                     (fn [x enabled?]
                       (if enabled? x value/nothing)))
            0)
      (bind '<-> (bi-sync-operator) 0)))

;; Compiler state and stable ids.

(defn- stable-node-id
  [& seed]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str seed) StandardCharsets/UTF_8))))

(defn- node-id
  [{:keys [seed path]} role]
  (stable-node-id seed path role))

(defn- ensure-cell
  [network id]
  (if (contains? (net/net-env network) id)
    network
    (nb/install-cell network id)))

(defn- seed-cell
  [network id v]
  (nb/seed-cell (ensure-cell network id) id v))

(defn- fresh-cell
  ([state role]
   (fresh-cell state role value/nothing))
  ([{:keys [net] :as state} role v]
   (let [id (node-id state role)]
     [(assoc state :net (if (value/nothing? v)
                          (ensure-cell net id)
                          (seed-cell net id v)))
      id])))

(defn- ast-slot
  [expr k]
  (obj/slot-value expr k))

(defn- child-state
  [state segment]
  (update state :path conj segment))

(defn- add-props
  [state prop-ids]
  (update state :props into prop-ids))

(defn- binding-id
  [binding]
  (:binding/id binding))

(defn- cellful-binding?
  [binding]
  (contains? #{:cell :compound} (:binding/type binding)))

(defn- binding-boundary-ids
  [binding]
  (case (:binding/type binding)
    :cell [(:binding/id binding)]
    :compound (into [(:binding/id binding)] (:binding/captures binding))
    []))

(defn- rebind-captured-binding
  [binding inner-ids]
  (case (:binding/type binding)
    :cell (cell-binding (first inner-ids))
    :compound (compound-binding (first inner-ids) (rest inner-ids))))

(defn- binding-cell-id
  [binding]
  (when (= :cell (:binding/type binding))
    (:binding/id binding)))

(declare compile-node)

(defn- free-symbols
  [expr]
  (case (ast-slot expr :ast/type)
    :literal #{}
    :symbol #{(ast-slot expr :ast/name)}
    :apply (apply set/union
                  (free-symbols (ast-slot expr :ast/operator))
                  (cond-> (mapv free-symbols (ast-slot expr :ast/args))
                    (slot-present? expr :ast/output)
                    (conj (free-symbols (ast-slot expr :ast/output)))))
    :do (apply set/union #{} (map free-symbols (ast-slot expr :ast/body)))
    :let-cell (set/difference
               (free-symbols (ast-slot expr :ast/body))
               (set (ast-slot expr :ast/names)))
    :compound (set/difference
               (free-symbols (ast-slot expr :ast/body))
               (set (conj (vec (ast-slot expr :ast/inputs))
                          (ast-slot expr :ast/output))))
    :let-compound (set/union
                   (free-symbols (ast-slot expr :ast/value))
                   (disj (free-symbols (ast-slot expr :ast/body))
                         (ast-slot expr :ast/name)))))

(defn- compile-literal
  [state expr]
  (let [[state' id] (fresh-cell state :literal (ast-slot expr :ast/value))]
    [state' (cell-binding id)]))

(defn- compile-symbol
  [{:keys [env] :as state} expr]
  (let [name (ast-slot expr :ast/name)]
    (if-let [binding (lookup env name)]
      [state binding]
      (let [[state' id] (fresh-cell state [:unbound name])
            env' (bind env name (cell-binding id))]
        [(assoc state' :env env') (cell-binding id)]))))

(defn- compile-do
  [state expr]
  (reduce
   (fn [[state _] [idx form]]
     (compile-node (child-state state [:do idx]) form))
   [state nil]
   (map-indexed vector (ast-slot expr :ast/body))))

(defn- compile-let-cell
  [state expr]
  (let [depth (env-depth (:env state))
        [state' env']
        (reduce
         (fn [[state env] [idx name]]
           (let [[state' id] (fresh-cell (child-state state [:let-cell idx name])
                                         :cell)]
             [state' (bind env name (cell-binding id) depth)]))
         [state (:env state)]
         (map-indexed vector (ast-slot expr :ast/names)))]
    (compile-node (assoc state' :env env') (ast-slot expr :ast/body))))

(defn- application-output
  [state expr]
  (if (slot-present? expr :ast/output)
    (let [[state' out-binding] (compile-node (child-state state :output)
                                             (ast-slot expr :ast/output))
          out-id (binding-cell-id out-binding)]
      (when-not out-id
        (throw (ex-info "application output must compile to a cell"
                        {:output out-binding
                         :expr expr})))
      [state' out-id])
    (fresh-cell state :result)))

(defn- install-operator-application
  [state expr op-binding arg-bindings]
  (let [arg-ids (mapv binding-cell-id arg-bindings)
        [state' out-id] (application-output state expr)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "operator arguments must compile to cells"
                      {:args arg-bindings})))
    (let [[network' prop-ids result-id]
          ((:binding/apply op-binding) (:net state') arg-ids out-id)]
      [(-> state'
           (assoc :net network')
           (add-props prop-ids))
       (cell-binding result-id)])))

(defn- install-compound-application
  [state expr op-binding arg-bindings]
  (let [arg-ids (mapv binding-cell-id arg-bindings)
        [state' out-id] (application-output state expr)
        closure-id (:binding/id op-binding)
        capture-ids (:binding/captures op-binding)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "compound arguments must compile to cells"
                      {:args arg-bindings})))
    (let [[prop-id network'] ((apply closure/p:apply-closure
                                     closure-id
                                     (concat capture-ids arg-ids [out-id]))
                              (:net state'))]
      [(-> state'
           (assoc :net network')
           (add-props [prop-id]))
       (cell-binding out-id)])))

(defn- install-deferred-closure-application
  [state expr op-binding arg-bindings]
  (let [arg-ids (mapv binding-cell-id arg-bindings)
        [state' out-id] (application-output state expr)
        closure-id (:binding/id op-binding)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "deferred compound arguments must compile to cells"
                      {:args arg-bindings})))
    (let [[prop-id network'] ((apply closure/p:apply-closure
                                     closure-id
                                     (concat arg-ids [out-id]))
                              (:net state'))]
      [(-> state'
           (assoc :net network')
           (add-props [prop-id]))
       (cell-binding out-id)])))

(defn- compile-apply
  [state expr]
  (let [[state' op-binding] (compile-node (child-state state :operator)
                                          (ast-slot expr :ast/operator))
        [state'' arg-bindings]
        (reduce
         (fn [[state acc] [idx arg-expr]]
           (let [[state' arg-binding] (compile-node
                                       (child-state state [:arg idx])
                                       arg-expr)]
             [state' (conj acc arg-binding)]))
         [state' []]
         (map-indexed vector (ast-slot expr :ast/args)))]
    (case (:binding/type op-binding)
      :operator (install-operator-application state'' expr op-binding arg-bindings)
      :compound (install-compound-application state'' expr op-binding arg-bindings)
      :cell (install-deferred-closure-application state'' expr op-binding arg-bindings)
      (throw (ex-info "application operator is not callable"
                      {:operator op-binding
                       :expr expr})))))

(defn- capture-descriptors
  [env local-symbols body]
  (->> (set/difference (free-symbols body) (set local-symbols))
       (keep (fn [sym]
               (let [entry (lookup-entry env sym)
                     binding (:binding entry)]
                 (when (cellful-binding? binding)
                   {:symbol sym
                    :binding binding
                    :ids (binding-boundary-ids binding)}))))
       vec))

(defn- installer-env
  [env]
  (reduce
   (fn [acc k]
     (if-let [{:keys [binding intensity]} (lookup-entry env k)]
       (if (= :operator (:binding/type binding))
         (bind acc k binding (or intensity 0))
         acc)
       acc))
   (set-env-depth (obj/empty-compound-object) 0)
   (obj/public-slot-keys env)))

(defn- compile-compound-value
  [{:keys [env] :as state} expr]
  (let [inputs (vec (ast-slot expr :ast/inputs))
        output (ast-slot expr :ast/output)
        body (ast-slot expr :ast/body)
        captures (capture-descriptors env (conj inputs output) body)
        capture-ids (mapcat :ids captures)
        closure-value
        (closure/closure
         (fn [_closure-net input-ids output-ids network]
           (let [capture-count (count capture-ids)
                 input-ids (vec input-ids)
                 capture-inner (subvec input-ids 0 capture-count)
                 arg-inner (subvec input-ids capture-count)
                 [out-inner] output-ids
                 captured-pairs
                 (loop [descriptors captures
                        remaining-ids capture-inner
                        acc []]
                   (if (empty? descriptors)
                     acc
                     (let [{:keys [symbol binding ids]} (first descriptors)
                           width (count ids)
                           current (subvec remaining-ids 0 width)]
                       (recur (rest descriptors)
                              (subvec remaining-ids width)
                              (conj acc [symbol
                                         (rebind-captured-binding binding current)])))))
                 closure-env
                 (reduce
                  (fn [env [sym binding]]
                    (bind env sym binding 0))
                  (enter-scope (installer-env env))
                  captured-pairs)
                 depth (env-depth closure-env)
                 closure-env'
                 (reduce
                  (fn [env [sym id]]
                    (bind env sym (cell-binding id) depth))
                  (bind closure-env output (cell-binding out-inner) depth)
                  (map vector inputs arg-inner))
                 [state' result-binding]
                 (compile-node {:net network
                                :env closure-env'
                                :seed [(:seed state) (:path state) :closure-run]
                                :path []
                                :props []}
                               body)
                 result-id (binding-cell-id result-binding)]
             (if (and result-id (not= result-id out-inner))
               (second ((stdlib-prop/id result-id out-inner) (:net state')))
               (:net state'))))
         net/empty-net)
        [state' closure-id] (fresh-cell state :closure closure-value)]
    [state' (compound-binding closure-id capture-ids)]))

(defn- compile-let-compound
  [state expr]
  (let [[state' compound-binding]
        (compile-compound-value (child-state state :compound)
                                (ast-slot expr :ast/value))
        name (ast-slot expr :ast/name)
        env' (bind (:env state') name compound-binding (env-depth (:env state')))]
    (compile-node (assoc state' :env env')
                  (ast-slot expr :ast/body))))

(defn- compile-node
  [state expr]
  (case (ast-slot expr :ast/type)
    :literal (compile-literal state expr)
    :symbol (compile-symbol state expr)
    :apply (compile-apply state expr)
    :do (compile-do state expr)
    :let-cell (compile-let-cell state expr)
    :compound (compile-compound-value state expr)
    :let-compound (compile-let-compound state expr)))

(defn- annotate-compiled-net
  [network result-binding props env]
  (-> network
      (net/assoc-net-dict-entry compiler-result-key (binding-id result-binding))
      (net/assoc-net-dict-entry compiler-props-key (vec props))
      (net/assoc-net-dict-entry :compiler/env env)))

(defn compile-expr
  "Compile compound-object-compatible AST data into a propagator network value."
  ([expr]
   (compile-expr expr (default-env)))
  ([expr env]
   (compile-expr expr env {}))
  ([expr env {:keys [net seed path]
              :or {net net/empty-net path []}}]
   (let [seed (or seed (ids/new-node-id))
         [state result-binding]
         (compile-node {:net net
                        :env env
                        :seed seed
                        :path path
                        :props []}
                       (ast expr))
         compiled-net (annotate-compiled-net (:net state)
                                             result-binding
                                             (:props state)
                                             (:env state))]
     {:net compiled-net
      :cell (binding-id result-binding)
      :binding result-binding
      :env (:env state)
      :props (:props state)})))

(defn compiled-result
  [compiled-net]
  (net/network-dict-entry compiled-net compiler-result-key))

(defn compiled-props
  [compiled-net]
  (net/network-dict-entry compiled-net compiler-props-key))

(defn p:compile-expr
  "Compile AST/env cells into a compiled network value.

  The compiler itself remains pure: activation reads the strongest AST and env
  values, runs `compile-expr` against the current network snapshot, and emits the
  compiled network as ordinary cell content.
  "
  [expr-id env-id out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [expr (net/network-cell-strongest network expr-id)
           env (net/network-cell-strongest network env-id)]
       (if (or (value/unusable? expr)
               (value/unusable? env))
         []
         (let [compiled (compile-expr expr
                                      env
                                      {:net network
                                       :seed [:compile-2 expr-id env-id]})]
           [(message out-id (:net compiled))]))))
   [expr-id env-id]
   [out-id]))
