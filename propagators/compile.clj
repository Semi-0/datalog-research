(ns propagators.compile
  "Compile and extend propagator networks.

  Compile-time: `compile-net`, `let-cell`.
  Runtime: `net-let` — declare cells by symbol, install propagators like function calls."
  (:require [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn default-installers
  "Installer map (lazy resolve avoids compile ↔ stdlib cycle)."
  []
  {'prop/id (requiring-resolve 'propagators.stdlib.prop/id)
   'p:id (requiring-resolve 'propagators.stdlib.prop/id)})

;; Re-export for manual threading
(def install-net net/install-net)
(def seed-net-cell net/seed-net-cell)

(defn- ctx0
  ([installers]
   (ctx0 net/empty-net installers))
  ([n installers]
   {:net n
    :installers installers
    :props []
    :value nil}))

(defn- self-evaluating? [x]
  (or (nil? x)
      (number? x)
      (string? x)
      (keyword? x)
      (boolean? x)
      (set? x)
      (map? x)
      (vector? x)))

(defn bind-var
  "Bind compiler symbol `sym` to value/id `v` in the network dict."
  [n sym v]
  (net/assoc-net-dict-entry n sym v))

(defn bind-vars
  "Bind compiler symbols to values/ids in the network dict."
  [n sym->value]
  (reduce-kv bind-var n sym->value))

(defn- resolve-symbol
  "Symbols are cell vars. Missing symbols create fresh empty cells."
  [ctx sym]
  (if-let [v (net/network-dict-entry (:net ctx) sym)]
    [ctx v]
    (let [id (new-node-id)
          n' (-> (:net ctx)
                 (nb/install-cell id)
                 (bind-var sym id))]
      [(assoc ctx :net n') id])))

(defn- lookup-inst [ctx sym]
  (let [v (or (get (:installers ctx) sym)
              (throw (ex-info "unknown installer"
                              {:inst sym
                               :known (keys (:installers ctx))})))]
    (if (var? v) @v v)))

(defn- prop-ids [installed-id]
  (if (sequential? installed-id)
    (vec installed-id)
    [installed-id]))

(defn run-net-let
  "Runtime network builder.

  `cell-binds`: `[[sym id] | [sym id content strongest] ...]`
  `prop-forms`: `((installer arg-sym ...) ...)`
  Returns updated net."
  [n installers cell-binds prop-forms]
  (let [[n' cells]
        (reduce
         (fn [[n acc] entry]
           (let [[sym id & seed] entry
                 n' (if (empty? seed)
                      (net/seed-net-cell n id)
                      (net/seed-net-cell n id (first seed) (second seed)))]
             [n' (assoc acc sym id)]))
         [n {}]
         cell-binds)]
    (reduce
     (fn [n form]
       (let [[inst & arg-syms] form
             ids (mapv #(get cells %) arg-syms)
             inst-fn (or (get installers inst)
                         (throw (ex-info "unknown installer" {:inst inst})))]
         (net/install-net n (apply inst-fn ids))))
     n'
     prop-forms)))

(defn install-and-run
  "Install one propagator installer on `n` and run the installed propagator ids."
  [n installer]
  (let [[installed-id n'] (installer n)]
    (nb/run-propagators n' (prop-ids installed-id))))

(defn- cell-bind-entry [entry]
  (let [[sym id & seed] entry]
    `(list '~sym ~id ~@seed)))

(defmacro net-let
  "Thread net through cell bindings and propagator installs.

  Example:
  ```clojure
  (net-let n
    [[a input-id content strongest]
     [b output-id content' strongest']]
    (p:id a b)
    (p:id b a))
  ```

  Custom installers: call `run-net-let` directly."
  [n cell-binds & prop-forms]
  `(run-net-let ~n (default-installers)
                (vector ~@(map cell-bind-entry cell-binds))
                '~prop-forms))

(declare eval-expr)

(defn- eval-seq [ctx exprs]
  (reduce
   (fn [[ctx _] expr]
     (eval-expr ctx expr))
   [ctx nil]
   exprs))

(defn- eval-let-cell [ctx [_ syms & body]]
  (let [ctx'
        (reduce
         (fn [ctx sym]
           (first (resolve-symbol ctx sym)))
         ctx
         syms)]
    (eval-seq ctx' body)))

(defn- eval-do [ctx [_ & body]]
  (eval-seq ctx body))

(defn- eval-seed [ctx [_ cell-expr value-expr]]
  (let [[ctx' cell-id] (eval-expr ctx cell-expr)
        [ctx'' value] (eval-expr ctx' value-expr)
        n' (nb/seed-cell (:net ctx'') cell-id value)]
    [(-> ctx''
         (assoc :net n')
         (assoc :value cell-id))
     cell-id]))

(defn- eval-application [ctx form]
  (let [[op & args] form
        installer (lookup-inst ctx op)
        [ctx' argv]
        (reduce
         (fn [[ctx values] arg]
           (let [[ctx' v] (eval-expr ctx arg)]
             [ctx' (conj values v)]))
         [ctx []]
         args)
        [installed-id n'] ((apply installer argv) (:net ctx'))
        ids (prop-ids installed-id)]
    [(-> ctx'
         (assoc :net n')
         (update :props into ids)
         (assoc :value installed-id))
     installed-id]))

(defn- eval-expr [ctx expr]
  (cond
    (self-evaluating? expr)
    [(assoc ctx :value expr) expr]

    (symbol? expr)
    (let [[ctx' v] (resolve-symbol ctx expr)]
      [(assoc ctx' :value v) v])

    (and (seq? expr) (= 'let-cell (first expr)))
    (eval-let-cell ctx expr)

    (and (seq? expr) (= 'do (first expr)))
    (eval-do ctx expr)

    (and (seq? expr) (= 'seed (first expr)))
    (eval-seed ctx expr)

    (seq? expr)
    (eval-application ctx expr)

    :else
    (throw (ex-info "unsupported expression" {:expr expr}))))

(defn eval-net*
  "Evaluate multiple top-level expressions against network `n`."
  [n installers exprs]
  (let [[ctx value] (eval-seq (ctx0 n installers) exprs)]
    (assoc ctx :value value)))

(defn eval-net
  "Evaluate one network expression."
  ([expr]
   (eval-net net/empty-net (default-installers) expr))
  ([n expr]
   (eval-net n (default-installers) expr))
  ([n installers expr]
   (let [[ctx value] (eval-expr (ctx0 n installers) expr)]
     (assoc ctx :value value))))

(defn eval-net-with-bindings
  "Evaluate one network expression after binding symbols into network `n`."
  [n installers sym->value expr]
  (eval-net (bind-vars n sym->value) installers expr))

(defn eval-layered
  "Evaluate one network expression with an explicit installer map and pre-bound symbols.

  This is intentionally installer-driven rather than tied to `propagators.layered`,
  so tests and higher-level builders can supply the layered vocabulary they want."
  [n installers sym->value expr]
  (eval-net-with-bindings n installers sym->value expr))

(defmacro net-build
  "Evaluate body expressions against `n` using default installers."
  [n & body]
  `(eval-net* ~n (default-installers) '~body))

(defn- symbol-bindings [n]
  (into {}
        (filter (fn [[k _]] (symbol? k)))
        (net/net-dict-or-empty n)))

(defn compile-net
  ([expr]
   (compile-net expr (default-installers)))
  ([expr installers]
   (let [{:keys [net props value] :as ctx}
         (eval-net net/empty-net installers expr)]
     (assoc ctx
            :graph (net/net-graph net)
            :env (net/net-env net)
            :cells (symbol-bindings net)
            :props props
            :value value))))

(defn cell-ref [compiled sym]
  (or (get-in compiled [:cells sym])
      (when-let [n (:net compiled)]
        (net/network-dict-entry n sym))
      (throw (ex-info "unbound cell" {:sym sym}))))

(defn prop-ref [compiled idx] (nth (:props compiled) idx))
