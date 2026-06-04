(ns propagators.compile-2
  "Facade for the organized compile-2 implementation."
  (:refer-clojure :exclude [compile])
  (:require [propagators.compiler-2.ast :as ast]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :as h]
            [propagators.compiler-2.main :as main]))

(def lit ast/lit)
(def sym ast/sym)
(def app ast/app)
(def app-> ast/app->)
(def do* ast/do*)
(def let-cell ast/let-cell)
(def compound ast/compound)
(def let-compound ast/let-compound)
(def ast ast/ast)

(def env-depth-key env/env-depth-key)
(def cell-binding env/cell-binding)
(def cell-binding? env/cell-binding?)
(def compound-binding env/compound-binding)
(def compound-binding? env/compound-binding?)
(def set-env-depth env/set-depth)
(def enter-scope env/enter-scope)
(def sub-env env/sub-env)
(def bind env/bind)
(def bind-local env/bind-local)
(def p:sub-env env/p:sub-env)
(def p:bind-local env/p:bind-local)
(def lookup-entry env/lookup-entry)
(def lookup env/lookup)

(def installer-operator h/installer-operator)
(def primitive-operator h/primitive-operator)
(def default-env h/default-env)

(def compiler-result-key main/compiler-result-key)
(def compiler-props-key main/compiler-props-key)
(def compile-expr main/compile-expr)
(def compiled-result main/compiled-result)
(def compiled-props main/compiled-props)
(def p:compile-expr main/p:compile-expr)
