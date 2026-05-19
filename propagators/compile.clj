(ns propagators.compile
  "Compile quoted network expressions (r2-style: `compile*` + context).

  Context `ctx`: `{:cells {sym→id} :installers :graph :env :props}`."
  (:require [clojure.core.match :refer [match]]
            [propagators.graph :refer [node]]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :refer [construct-cell primitive-propagator]]))

(def ^:private p:id (primitive-propagator (fn [x] x)))

(def default-installers {'p:id p:id})

(defn- ctx0 [installers]
  {:cells {} :installers installers :graph {} :env {} :props []})

(defn- lookup-cell [sym {:keys [cells]}]
  (or (get cells sym)
      (throw (ex-info "unbound cell" {:sym sym :known (keys cells)}))))

(defn- lookup-inst [sym {:keys [installers]}]
  (or (get installers sym)
      (throw (ex-info "unknown installer" {:inst sym :known (keys installers)}))))

(defn- binding-pairs
  "Racket `([x e] …)` or flat Clojure `[x e x e …]`."
  [bindings]
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

(defn- bind-cell [sym ctx]
  (let [id (new-node-id)
        [_ [g e]] ((construct-cell id) [(:graph ctx) (:env ctx)])
        g (assoc g id (node id #{} #{}))]
    (-> ctx (assoc :graph g :env e) (assoc-in [:cells sym] id))))

(defn- compile*
  [exp ctx]
  (cond
    (prop-apply? exp ctx)
    (let [[inst & arg-syms] exp
          ids (mapv #(lookup-cell % ctx) arg-syms)
          [pid [g e]] (((lookup-inst inst ctx) ids) [(:graph ctx) (:env ctx)])]
      (-> ctx (assoc :graph g :env e) (update :props conj pid)))

    (do+? exp)
    (reduce (fn [ctx form] (compile* form ctx)) ctx (rest exp))

    :else
    (match exp
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
  ([expr] (compile-net expr default-installers))
  ([expr installers] (compile* expr (ctx0 installers))))

(defn cell-ref [compiled sym] (lookup-cell sym compiled))

(defn prop-ref [compiled idx] (nth (:props compiled) idx))
