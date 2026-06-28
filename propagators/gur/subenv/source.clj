(ns propagators.gur.subenv.source
  "Source-level DSL helpers for lexical sub-env GUR."
  (:require [propagators.compile :as compile]
            [propagators.gur.subenv.env :as env]
            [propagators.gur.subenv.frame :as frame]
            [propagators.network :as net]))

(defn contextual-recur-installer
  [recur-fn]
  (fn [& ids]
    (let [arg-ids (vec (butlast ids))
          out-id (last ids)]
      (fn [n]
        (recur-fn n arg-ids out-id)))))

(defn contextual-apply-installer
  [apply-fn]
  (fn [closure-id & ids]
    (let [arg-ids (vec (butlast ids))
          out-id (last ids)]
      (fn [n]
        (apply-fn n closure-id arg-ids out-id)))))

(defn contextual-installers
  [installers {:keys [apply recur]}]
  (cond-> installers
    apply (assoc 'ctx/apply (contextual-apply-installer apply))
    recur (assoc 'ctx/recur (contextual-recur-installer recur))))

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

(defn- bind-local-aliases
  [n sym value]
  (-> n
      (env/bind sym value)
      (env/bind (keyword (name sym)) value)))

(defn- bind-compiled-locals
  [ctx]
  (assoc ctx
         :net
         (reduce-kv
          (fn [n sym value]
            (if (and (symbol? sym) (bindable-cell? n value))
              (bind-local-aliases n sym value)
              n))
          (:net ctx)
          (net/net-dict-or-empty (:net ctx)))))

(defn topology-result
  [ctx]
  {:net (:net ctx)
   :prop-ids (:props ctx)})

(defn recursive-definition
  "Lower a GUR source body to a frame definition.

  `params` is a vector whose last symbol is the output cell. `installer-fn`
  receives the runtime frame map and returns the installer map loaded for the
  source body.
  "
  [params body installer-fn]
  (let [params (vec params)
        arg-syms (vec (butlast params))
        out-sym (last params)]
    (when-not (and (seq params) (every? symbol? params))
      (throw (ex-info "def-recursive params must be symbols"
                      {:params params})))
    (fn [{frame-net :network
          arg-ids :args
          out-id :out
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
          bind-compiled-locals
          topology-result))))

(defn recursive-closure
  ([name params body]
   (recursive-closure name params body default-installers))
  ([name params body installer-fn]
   (frame/def-recursive name
                        (recursive-definition params body installer-fn))))

(defmacro def-recursive
  "Define a deprecated subenv recursive closure value from source DSL.

  This macro keeps the same option shape as `propagators.compile/def-recursive`
  so legacy subenv examples can stay on the old implementation after the compile
  facade moves to accumulating GUR.
  "
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
         (recursive-closure ~closure-name
                            '~params
                            ~body-code
                            ~installer-fn))
      `(def ~name
         (recursive-closure ~closure-name
                            '~params
                            ~body-code)))))
