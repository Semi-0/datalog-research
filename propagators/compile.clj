(ns propagators.compile
  "Compile and extend propagator networks.

  Compile-time: `compile-net`, `let-cell`.
  Runtime: `net-let` — declare cells by symbol, install propagators like function calls."
  (:require [clojure.core.match :refer [match]]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]))

(defn default-installers
  "Installer map (lazy resolve avoids compile ↔ stdlib cycle)."
  []
  {'p:id (requiring-resolve 'propagators.stdlib/p:id)})

;; Re-export for manual threading
(def install-net net/install-net)
(def seed-net-cell net/seed-net-cell)

(defn- ctx0 [installers]
  {:cells {} :installers installers :graph {} :env {} :props []})

(defn- lookup-cell [sym {:keys [cells]}]
  (or (get cells sym)
      (throw (ex-info "unbound cell" {:sym sym :known (keys cells)}))))

(defn- lookup-inst [sym {:keys [installers]}]
  (or (get installers sym)
      (throw (ex-info "unknown installer" {:inst sym :known (keys installers)}))))

(defn- binding-pairs [bindings]
  (if (and (seq bindings) (vector? (first bindings)))
    (mapv vec bindings)
    (do
      (when (odd? (count bindings))
        (throw (ex-info "let bindings must be sym/init pairs" {:bindings bindings})))
      (mapv vec (partition 2 bindings)))))

(defn- do+? [x]
  (and (seq? x) (= 'do (first x)) (< 1 (count x))))

(defn- prop-apply? [x ctx]
  (and (seq? x)
       (let [op (first x)]
         (and (symbol? op) (contains? (:installers ctx) op)))))

(defn- net-of-ctx [{:keys [graph env]}]
  (net/net graph env))

(defn- ctx-of-net [ctx n]
  (-> ctx
      (assoc :graph (net/net-graph n))
      (assoc :env (net/net-env n))))

(defn- with-net [ctx f]
  (ctx-of-net ctx (f (net-of-ctx ctx))))

(defn- bind-cell [sym ctx]
  (let [id (new-node-id)
        ctx' (with-net ctx #(net/seed-net-cell % id))]
    (assoc-in ctx' [:cells sym] id)))

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
         (net/install-net n (inst-fn ids))))
     n'
     prop-forms)))

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

(defn- compile* [exp ctx]
  (cond
    (prop-apply? exp ctx)
    (let [[inst & arg-syms] exp
          ids (mapv #(lookup-cell % ctx) arg-syms)
          [pid n'] (((lookup-inst inst ctx) ids) (net-of-ctx ctx))
          ctx' (ctx-of-net ctx n')]
      (-> ctx' (update :props conj pid)))

    (do+? exp)
    (reduce (fn [ctx form] (compile* form ctx)) ctx (rest exp))

    :else
    (match exp
      (['let-cell syms body] :seq)
      (let [bindings (vec (mapcat (fn [sym] [sym '(cell)]) syms))
            let-form (list 'let bindings body)]
        (compile* let-form ctx))

      (['let bindings body] :seq)
      (let [ctx' (reduce
                  (fn [ctx [sym init]]
                    (if (= init '(cell))
                      (bind-cell sym ctx)
                      (throw (ex-info "let binding must be (cell)" {:sym sym :init init}))))
                  ctx
                  (binding-pairs bindings))]
        (compile* body ctx'))

      (['do] :seq)
      ctx

      :else
      (throw (ex-info "unsupported form" {:form exp})))))

(defn compile-net
  ([expr] (compile-net expr (default-installers)))
  ([expr installers] (compile* expr (ctx0 installers))))

(defn cell-ref [compiled sym] (lookup-cell sym compiled))

(defn prop-ref [compiled idx] (nth (:props compiled) idx))
