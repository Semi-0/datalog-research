(ns propagators.gur.accumulating.source
  "Source DSL bridge for accumulating GUR."
  (:require [propagators.compile :as compile]
            [propagators.gur.accumulating.core :as acc]
            [propagators.network :as net]))

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
              (acc/bind-local-alias n scope sym value)
              n))
          (:net ctx)
          (net/net-dict-or-empty (:net ctx)))))

(defn- topology-result
  [ctx]
  {:net (acc/strip-compiler-symbols (:net ctx))
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
      (-> (compile/eval-net-output-with-bindings
           frame-net
           (installer-fn runtime)
           (assoc (zipmap arg-syms arg-ids) out-sym out-id)
           (body-expr body)
           out-id)
          (bind-compiled-locals scope)
          topology-result))))

(defn source-recursive-closure
  ([name params body]
   (source-recursive-closure name params body default-installers))
  ([name params body installer-fn]
   (acc/recursive-closure
    name
    (fn [ctx frame-net arg-ids out-id]
      ((recursive-definition params body installer-fn)
       (assoc (acc/contextual-api ctx)
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
    (let [closure-expr (if installer-fn
                         `(source-recursive-closure ~closure-name
                                                    '~params
                                                    ~body-code
                                                    ~installer-fn)
                         `(source-recursive-closure ~closure-name
                                                    '~params
                                                    ~body-code))]
      `(def ~name
         ~closure-expr))))
