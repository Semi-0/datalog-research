(ns experiments.functional-network.compiler
  "Functional-network language entry points over the existing parser and CPS engine.
   Compilation declares topology and returns a result cell; it never runs effects."
  (:require [experiments.functional-network :as language]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as cps]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.env :as env]
            [propagators.ids :as ids]))

(def parse-form language/parse-form)
(def parse-source language/parse-source)

(defn default-env []
  (env/bind-local (basis/default-env) 'apply language/apply-operator))

(defn- children [expression]
  (case (ast/type expression)
    :literal []
    :symbol []
    :sequence (ast/body expression)
    :let-cell [(ast/body expression)]
    :let (conj (mapv second (ast/bindings expression)) (ast/body expression))
    :when-topology [(ast/condition expression) (ast/body expression)]
    :def (if-let [body (ast/body expression)] [body] [])
    :apply (into [(ast/operator expression)] (ast/args expression))
    :network
    (if (nil? (ast/output expression))
      [(ast/body expression)]
      (throw (ex-info "Functional networks derive outputs from their body return"
                      {:type :network :output (ast/output expression)})))
    (throw (ex-info "Construct is outside the functional-network core"
                    {:type (ast/type expression)}))))

(defn- validate-expression [expression]
  ;; Iterative validation keeps the entry point stack-safe like CPS compilation.
  (loop [pending [expression]]
    (if (seq pending)
      (recur (into (pop pending) (children (peek pending))))
      expression)))

(defn- declaration-key [expression]
  ;; Compound AST objects include implementation identity. Seeds use only syntax.
  (loop [pending [expression] syntax []]
    (if (seq pending)
      (let [node (peek pending)
            nested (children node)
            fields [(ast/type node) (ast/value node) (ast/name node)
                    (ast/names node) (ast/inputs node)
                    (if (= :let (ast/type node))
                      (mapv first (ast/bindings node))
                      [])
                    (count nested)]]
        (recur (into (pop pending) nested) (conj syntax fields)))
      syntax)))

(defn compile-expr
  ([expression] (compile-expr expression (default-env) {}))
  ([expression compiler-env] (compile-expr expression compiler-env {}))
  ([expression compiler-env options]
   (let [expression (validate-expression (ast/ast expression))
         compiled
         (cps/compile-expr
          expression compiler-env
          (assoc (merge {:seed [:experiment/functional-network
                               (declaration-key expression)]}
                        options)
                 :compiler language/expression-compiler))]
     (if (ids/node-id? (:cell compiled))
       compiled
       (throw (ex-info "Every expression must compile to a result cell"
                       {:type (ast/type expression) :cell (:cell compiled)}))))))

(defn compile-form
  ([form] (compile-expr (parse-form form)))
  ([form compiler-env] (compile-expr (parse-form form) compiler-env))
  ([form compiler-env options]
   (compile-expr (parse-form form) compiler-env options)))

(defn compile-source
  ([source] (compile-expr (parse-source source)))
  ([source compiler-env] (compile-expr (parse-source source) compiler-env))
  ([source compiler-env options]
   (compile-expr (parse-source source) compiler-env options)))

(defn compile-program
  "Compile ordered reader forms as one sequence; source reading remains parser-owned."
  ([forms] (compile-program forms (default-env) {}))
  ([forms compiler-env] (compile-program forms compiler-env {}))
  ([forms compiler-env options]
   (if (seq forms)
     (compile-expr (apply ast/sequence* (map parse-form forms)) compiler-env options)
     (throw (ex-info "A program requires at least one expression" {:forms forms})))))
