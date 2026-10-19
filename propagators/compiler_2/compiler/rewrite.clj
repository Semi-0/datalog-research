(ns propagators.compiler-2.compiler.rewrite
  "Pure surface-to-canonical Compiler 2 term rewrites."
  (:require [propagators.compiler-2.language.ast :as ast]))

(defn let-cell->let
  [expr]
  (ast/let* (mapv (fn [name] [name nil]) (ast/names expr))
            (ast/body expr)))

(defn conditional->application
  "Select a suspended branch, then invoke only that branch's body."
  [selector condition then-expression else-expression]
  (ast/app
   (ast/app (ast/lit selector) condition
            (ast/network [] then-expression)
            (ast/network [] else-expression))))

(defn compound->network
  [expr]
  (ast/network (ast/inputs expr) (ast/output expr) (ast/body expr)))

(defn def-net->def
  [expr]
  (ast/def* (ast/name expr)
            (ast/network (ast/inputs expr)
                         (ast/output expr)
                         (ast/body expr))))

(defn def-constraint->def
  [expr]
  (let [applicants (ast/inputs expr)
        body (ast/body expr)
        result (if (some? (peek applicants))
                 (ast/sequence* body (ast/sym (peek applicants)))
                 body)]
    (ast/def* (ast/name expr)
              (ast/network applicants result))))
