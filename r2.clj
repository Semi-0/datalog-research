(ns r2
  "Tiny λ-calculator interpreter ported from Racket (`env0`, `ext-env`, `lookup`, `Closure`, `interp`, `r2`).
  Uses `clojure.core.match` for `interp` dispatch."
  (:require [clojure.core.match :refer [match]]))

;;; Environment: alist-style stack, newest binding first (like Racket `cons`).

(def env0 ())

(defn ext-env [x v env]
  (cons [x v] env))

(defn lookup [x env]
  (some (fn [[k val]] (when (= k x) val)) env))

;;; Closure: λ-expression plus defining environment

(defrecord Closure [exp env])

(def ^:private op-symbols '#{+ - * /})

(defn interp [exp env]
  (match exp
    (sym :guard symbol?)
    (let [v (lookup sym env)]
      (if (nil? v)
        (throw (ex-info "undefined variable" {:var sym}))
        v))

    (n :guard number?)
    n

    (['lambda ([_] :seq) _] :seq)
    (->Closure exp env)

    (['let ([[x e1]] :seq) body] :seq)
    (let [v1 (interp e1 env)]
      (interp body (ext-env x v1 env)))

    ([(op :guard #(contains? op-symbols %)) e1 e2] :seq)
    (let [v1 (interp e1 env)
          v2 (interp e2 env)]
      (case op
        + (+ v1 v2)
        - (- v1 v2)
        * (* v1 v2)
        / (/ v1 v2)))

    ([e1 e2] :seq)
    (let [v1 (interp e1 env)
          v2 (interp e2 env)]
      (if (instance? Closure v1)
        (match (:exp v1)
          (['lambda ([x] :seq) body] :seq)
          (interp body (ext-env x v2 (:env v1)))
          :else
          (throw (ex-info "malformed closure" {:lambda (:exp v1)})))
        (throw (ex-info "application of non-closure" {:value v1}))))

    :else
    (throw (ex-info "interp: unsupported expression" {:exp exp}))))

(defn r2 [exp]
  (interp exp env0))
