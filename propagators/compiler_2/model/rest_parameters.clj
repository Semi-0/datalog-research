(ns propagators.compiler-2.model.rest-parameters
  "Rest signature data, separate from ordinary fixed-arity closure data.")

(defn parameter-spec
  [parameters]
  (when-not (and (vector? parameters) (every? symbol? parameters))
    (throw (ex-info "Parameters must be a vector of symbols"
                    {:parameters parameters})))
  (let [position (.indexOf parameters '&)]
    (cond
      (= -1 position)
      {:required parameters :rest nil}

      (or (not= 1 (count (filter #{'&} parameters)))
          (not= position (- (count parameters) 2)))
      (throw (ex-info "Rest parameters require one & followed by one final name"
                      {:parameters parameters}))

      :else
      (let [required (subvec parameters 0 position)
            rest-name (peek parameters)
            names (conj required rest-name)]
        (when-not (= (count names) (count (distinct names)))
          (throw (ex-info "Rest parameter names must be distinct"
                          {:parameters parameters})))
        {:required required :rest rest-name}))))
