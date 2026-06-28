(ns propagators.primitive-compiler.recursive)

(defn recursive-closure
  "Compile-facing entry point for source-level GUR recursive closures.

  The implementation stays in `propagators.gur.accumulating.source`; resolving it
  lazily keeps this namespace from depending on the experiment namespace during
  compiler load.
  "
  ([name params body]
   ((requiring-resolve 'propagators.gur.accumulating.source/source-recursive-closure)
    name
    params
    body))
  ([name params body installer-fn]
   ((requiring-resolve 'propagators.gur.accumulating.source/source-recursive-closure)
    name
    params
    body
    installer-fn)))

(defmacro def-recursive
  "Define a source-level GUR recursive closure with compiler DSL syntax.

  Optional leading body map:
  - `:name` overrides the closure identity keyword.
  - `:installers` supplies a runtime -> installer-map function.
  - `:seed-values` seeds host values into source cells before the body.
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
