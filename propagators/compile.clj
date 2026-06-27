(ns propagators.compile
  "Compatibility facade for the primitive compiler.

  New implementation code lives under `propagators.primitive-compiler.*`.
  Existing callers can keep requiring `propagators.compile`."
  (:require [propagators.primitive-compiler.core :as pc]
            [propagators.primitive-compiler.eval :as eval]
            [propagators.primitive-compiler.installers :as installers]
            [propagators.primitive-compiler.recursive :as recursive]
            [propagators.primitive-compiler.runtime :as runtime]))

(def default-installers installers/default-installers)

(def recursive-closure recursive/recursive-closure)

(defmacro def-recursive
  [name params & body]
  `(recursive/def-recursive ~name ~params ~@body))

(def install-net runtime/install-net)
(def seed-net-cell runtime/seed-net-cell)
(def run-net-let runtime/run-net-let)
(def install-and-run runtime/install-and-run)

(defmacro net-let
  [n cell-binds & prop-forms]
  `(runtime/net-let ~n ~cell-binds ~@prop-forms))

(def bind-var pc/bind-var)
(def bind-vars pc/bind-vars)

(def eval-net* eval/eval-net*)
(def eval-net eval/eval-net)
(def eval-net-with-bindings eval/eval-net-with-bindings)
(def eval-net-output-with-bindings eval/eval-net-output-with-bindings)
(def eval-layered eval/eval-layered)

(defmacro net-build
  [n & body]
  `(eval/net-build ~n ~@body))

(def compile-net eval/compile-net)
(def cell-ref eval/cell-ref)
(def prop-ref eval/prop-ref)
