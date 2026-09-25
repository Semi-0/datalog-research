(ns propagators.combinator
  "Small, domain-neutral control-flow combinators.")

(defn branch
  "Build an ordered predicate/handler branch with a final fallback.

  Predicates and handlers receive every invocation argument. The first matching
  handler runs; if none match, the final fallback runs."
  [& clauses]
  (when-not (and (pos? (count clauses))
                 (odd? (count clauses)))
    (throw (ex-info
            "branch requires predicate/handler pairs followed by a fallback"
            {:clause-count (count clauses)})))
  (fn [& inputs]
    (loop [remaining clauses]
      (if (= 1 (count remaining))
        (apply (first remaining) inputs)
        (let [[predicate handler & more] remaining]
          (if (apply predicate inputs)
            (apply handler inputs)
            (recur more)))))))
