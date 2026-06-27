(ns propagators.primitive-compiler.eval
  (:refer-clojure :exclude [eval])
  (:require [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.primitive-compiler.core :as pc]
            [propagators.primitive-compiler.installers :as installers]))

(declare eval-expr)

(defn- eval-cell-expr
  [ctx expr]
  (let [[ctx' v] (eval-expr ctx expr)]
    (pc/value-cell ctx' v)))

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
           (pc/bind-fresh-cell ctx sym))
         ctx
         syms)]
    (eval-seq ctx' body)))

(defn- eval-let [ctx [_ bindings & body]]
  (when-not (vector? bindings)
    (throw (ex-info "let expects a vector of bindings" {:bindings bindings})))
  (when (odd? (count bindings))
    (throw (ex-info "let expects symbol/expression pairs"
                    {:bindings bindings})))
  (let [[ctx' _]
        (reduce
         (fn [[ctx _] [sym expr]]
           (when-not (symbol? sym)
             (throw (ex-info "let binding name must be a symbol"
                             {:binding sym})))
           (let [[ctx' value] (eval-expr ctx expr)]
             [(pc/bind-symbol-to-value ctx' sym value) value]))
         [ctx nil]
         (partition 2 bindings))]
    (eval-seq ctx' body)))

(defn- eval-do [ctx [_ & body]]
  (eval-seq ctx body))

(declare eval-output-expr)

(defn- eval-output-seq [ctx exprs out-id]
  (if (seq exprs)
    (let [[ctx' _] (eval-seq ctx (butlast exprs))]
      (eval-output-expr ctx' (last exprs) out-id))
    (throw (ex-info "output expression requires a body" {:out-id out-id}))))

(defn- eval-bind [ctx [_ expr sym]]
  (when-not (symbol? sym)
    (throw (ex-info "-> target must be a symbol" {:target sym})))
  (let [[ctx' value] (eval-expr ctx expr)
        ctx'' (pc/bind-symbol-to-value ctx' sym value)]
    [(assoc ctx'' :value value) value]))

(defn- eval-seed [ctx [_ cell-expr value-expr]]
  (let [[ctx' cell-id] (eval-expr ctx cell-expr)
        [ctx'' value] (eval-expr ctx' value-expr)
        n' (nb/seed-cell (:net ctx'') cell-id value)]
    [(-> ctx''
         (assoc :net n')
         (assoc :value cell-id))
     cell-id]))

(defn- install-application [ctx op argv return-value]
  (let [installer (pc/lookup-inst ctx op)
        [installed-id n'] ((apply installer argv) (:net ctx))
        ids (pc/prop-ids installed-id)]
    [(-> ctx
         (assoc :net n')
         (update :props into ids)
         (assoc :value return-value))
     return-value]))

(defn- eval-application [ctx form]
  (let [[op & args] form
        installer (pc/lookup-inst ctx op)
        [ctx' argv]
        (reduce
         (fn [[ctx values] arg]
           (let [[ctx' v] (eval-expr ctx arg)]
             [ctx' (conj values v)]))
         [ctx []]
         args)
        [installed-id n'] ((apply installer argv) (:net ctx'))
        ids (pc/prop-ids installed-id)]
    [(-> ctx'
         (assoc :net n')
         (update :props into ids)
         (assoc :value installed-id))
     installed-id]))

(defn- eval-output-application [ctx form]
  (let [[op & args] form
        [ctx' argv]
        (reduce
         (fn [[ctx values] arg]
           (let [[ctx' v] (eval-cell-expr ctx arg)]
             [ctx' (conj values v)]))
         [ctx []]
         args)
        [ctx'' out-id] (pc/fresh-cell ctx')]
    (install-application ctx'' op (conj argv out-id) out-id)))

(defn- install-output-call [ctx op arg-cells out-id]
  (install-application ctx op (conj (vec arg-cells) out-id) out-id))

(defn- eval-switch [ctx [_ condition-expr value-expr & maybe-out]]
  (when-not (<= 0 (count maybe-out) 1)
    (throw (ex-info "switch expects condition, value, and optional output"
                    {:out-count (count maybe-out)})))
  (let [[ctx' condition-id] (eval-cell-expr ctx condition-expr)
        [ctx'' value-id] (eval-cell-expr ctx' value-expr)
        [ctx''' out-id] (if-let [out-expr (first maybe-out)]
                          (eval-cell-expr ctx'' out-expr)
                          (pc/fresh-cell ctx''))]
    (install-output-call ctx''' 'prop/switch [value-id condition-id] out-id)))

(defn- eval-output-direct-application [ctx form out-id]
  (let [[op & args] form
        [ctx' argv]
        (reduce
         (fn [[ctx values] arg]
           (let [[ctx' v] (eval-cell-expr ctx arg)]
             [ctx' (conj values v)]))
         [ctx []]
         args)]
    (install-output-call ctx' op argv out-id)))

(defn- eval-when [ctx [_ condition-expr & body]]
  (when-not (seq body)
    (throw (ex-info "when expects a condition and at least one body expression"
                    {:condition condition-expr})))
  (let [[ctx' condition-id] (eval-cell-expr ctx condition-expr)
        installer (pc/lookup-inst ctx' 'ctx/when)
        install-when (installer condition-id
                                (pc/symbol-bindings (:net ctx'))
                                (cons 'do body)
                                (:installers ctx))
        [installed-id n'] (install-when (:net ctx'))]
    [(-> ctx'
         (assoc :net n')
         (update :props into (pc/prop-ids installed-id))
         (assoc :value installed-id))
     installed-id]))

(defn- install-unconditional-output [ctx value-id out-id]
  (install-output-call ctx 'p:id [value-id] out-id))

(defn- install-conditional-output [ctx condition-id value-id out-id]
  (install-output-call ctx 'prop/switch [value-id condition-id] out-id))

(defn- eval-output-expr
  [ctx expr out-id]
  (cond
    (and (seq? expr) (= 'do (first expr)))
    (eval-output-seq ctx (rest expr) out-id)

    (and (seq? expr) (= 'let (first expr)))
    (let [[_ bindings & body] expr]
      (when-not (vector? bindings)
        (throw (ex-info "let expects a vector of bindings" {:bindings bindings})))
      (when (odd? (count bindings))
        (throw (ex-info "let expects symbol/expression pairs"
                        {:bindings bindings})))
      (let [pairs (vec (partition 2 bindings))
            [last-sym last-expr] (peek pairs)]
        (if (and (seq body)
                 (= last-sym (last body)))
          (let [[ctx' _]
                (reduce
                 (fn [[ctx _] [sym expr]]
                   (when-not (symbol? sym)
                     (throw (ex-info "let binding name must be a symbol"
                                     {:binding sym})))
                   (let [[ctx' value] (eval-expr ctx expr)]
                     [(pc/bind-symbol-to-value ctx' sym value) value]))
                 [ctx nil]
                 (pop pairs))
                [ctx'' _] (eval-output-expr ctx' last-expr out-id)
                ctx''' (pc/bind-symbol-to-value ctx'' last-sym out-id)
                [ctx'''' _] (eval-seq ctx''' (butlast body))]
            [(assoc ctx'''' :value out-id) out-id])
          (let [[ctx' _]
                (reduce
                 (fn [[ctx _] [sym expr]]
                   (when-not (symbol? sym)
                     (throw (ex-info "let binding name must be a symbol"
                                     {:binding sym})))
                   (let [[ctx' value] (eval-expr ctx expr)]
                     [(pc/bind-symbol-to-value ctx' sym value) value]))
                 [ctx nil]
                 pairs)]
            (eval-output-seq ctx' body out-id)))))

    (and (seq? expr) (= 'let-cell (first expr)))
    (let [[_ syms & body] expr
          ctx' (reduce
                (fn [ctx sym]
                  (pc/bind-fresh-cell ctx sym))
                ctx
                syms)]
      (eval-output-seq ctx' body out-id))

    (and (seq? expr)
         (not (contains? '#{let-cell -> seed switch when cond} (first expr))))
    (eval-output-direct-application ctx expr out-id)

    :else
    (let [[ctx' value-id] (eval-cell-expr ctx expr)]
      (install-unconditional-output ctx' value-id out-id))))

(defn- effective-condition [ctx prior-match-id condition-id]
  (if prior-match-id
    (let [[ctx' not-prior-id] (eval-output-application ctx (list 'prop/not prior-match-id))
          [ctx'' gated-id] (eval-output-application ctx' (list 'prop/and condition-id not-prior-id))]
      [ctx'' gated-id])
    [ctx condition-id]))

(defn- update-prior-match [ctx prior-match-id condition-id]
  (if prior-match-id
    (eval-output-application ctx (list 'prop/or prior-match-id condition-id))
    [ctx condition-id]))

(defn- eval-cond [ctx [_ & clauses]]
  (when (odd? (count clauses))
    (throw (ex-info "cond expects test/expression pairs" {:clauses clauses})))
  (let [[ctx' out-id] (pc/fresh-cell ctx)]
    (loop [ctx ctx'
           prior-match-id nil
           clauses clauses]
      (if (empty? clauses)
        [(assoc ctx :value out-id) out-id]
        (let [[test-expr value-expr & more] clauses]
          (if (= :else test-expr)
            (do
              (when (seq more)
                (throw (ex-info ":else must be the final cond clause"
                                {:remaining more})))
              (let [[ctx' value-id] (eval-cell-expr ctx value-expr)
                    [ctx'' condition-id] (if prior-match-id
                                           (eval-output-application ctx' (list 'prop/not prior-match-id))
                                           [ctx' nil])
                    [ctx''' _] (if condition-id
                                 (install-conditional-output ctx'' condition-id value-id out-id)
                                 (install-unconditional-output ctx'' value-id out-id))]
                [(assoc ctx''' :value out-id) out-id]))
            (let [[ctx' condition-id] (eval-cell-expr ctx test-expr)
                  [ctx'' effective-id] (effective-condition ctx' prior-match-id condition-id)
                  [ctx''' value-id] (eval-cell-expr ctx'' value-expr)
                  [ctx'''' _] (install-conditional-output ctx''' effective-id value-id out-id)
                  [ctx''''' next-prior-id] (update-prior-match ctx'''' prior-match-id condition-id)]
              (recur ctx''''' next-prior-id more))))))))

(defn- eval-expr [ctx expr]
  (cond
    (pc/self-evaluating? expr)
    [(assoc ctx :value expr) expr]

    (symbol? expr)
    (let [[ctx' v] (pc/resolve-symbol ctx expr)]
      [(assoc ctx' :value v) v])

    (and (seq? expr) (= 'let-cell (first expr)))
    (eval-let-cell ctx expr)

    (and (seq? expr) (= 'let (first expr)))
    (eval-let ctx expr)

    (and (seq? expr) (= 'do (first expr)))
    (eval-do ctx expr)

    (and (seq? expr) (= '-> (first expr)))
    (eval-bind ctx expr)

    (and (seq? expr) (= 'seed (first expr)))
    (eval-seed ctx expr)

    (and (seq? expr) (= 'switch (first expr)))
    (eval-switch ctx expr)

    (and (seq? expr) (= 'when (first expr)))
    (eval-when ctx expr)

    (and (seq? expr) (= 'cond (first expr)))
    (eval-cond ctx expr)

    (and (seq? expr) (keyword? (first expr)))
    (eval-output-application ctx expr)

    (seq? expr)
    (eval-application ctx expr)

    :else
    (throw (ex-info "unsupported expression" {:expr expr}))))

(defn eval-net*
  "Evaluate multiple top-level expressions against network `n`."
  [n installers exprs]
  (let [[ctx value] (eval-seq (pc/ctx0 n installers) exprs)]
    (assoc ctx :value value)))

(defn eval-net
  "Evaluate one network expression."
  ([expr]
   (eval-net net/empty-net (installers/default-installers) expr))
  ([n expr]
   (eval-net n (installers/default-installers) expr))
  ([n installers expr]
   (let [[ctx value] (eval-expr (pc/ctx0 n installers) expr)]
     (assoc ctx :value value))))

(defn eval-net-with-bindings
  "Evaluate one network expression after binding symbols into network `n`."
  [n installers sym->value expr]
  (eval-net (pc/bind-vars n sym->value) installers expr))

(defn eval-net-output-with-bindings
  "Evaluate expression `expr` after binding symbols into `n`, wiring the final
  value into existing cell `out-id` when the final expression can target output
  directly. Falls back to a `p:id` bridge for complex expression values."
  [n installers sym->value expr out-id]
  (let [[ctx value] (eval-output-expr (pc/ctx0 (pc/bind-vars n sym->value)
                                              installers)
                                      expr
                                      out-id)]
    (assoc ctx :value value)))

(defn eval-layered
  "Evaluate one network expression with an explicit installer map and pre-bound symbols.

  This is intentionally installer-driven rather than tied to `propagators.layered`,
  so tests and higher-level builders can supply the layered vocabulary they want."
  [n installers sym->value expr]
  (eval-net-with-bindings n installers sym->value expr))

(defmacro net-build
  "Evaluate body expressions against `n` using default installers."
  [n & body]
  `(eval-net* ~n (installers/default-installers) '~body))

(defn compile-net
  ([expr]
   (compile-net expr (installers/default-installers)))
  ([expr installers]
   (let [{:keys [net props value] :as ctx}
         (eval-net net/empty-net installers expr)]
     (assoc ctx
            :graph (net/net-graph net)
            :env (net/net-env net)
            :cells (pc/symbol-bindings net)
            :props props
            :value value))))

(defn cell-ref [compiled sym]
  (or (get-in compiled [:cells sym])
      (when-let [n (:net compiled)]
        (net/network-dict-entry n sym))
      (throw (ex-info "unbound cell" {:sym sym}))))

(defn prop-ref [compiled idx] (nth (:props compiled) idx))
