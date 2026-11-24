(ns propagators.compiler-2.primitive-import
  "Shared syntax for live and prepared native module imports.")

(defn parse-arguments [[file selector revision :as args]]
  (let [arity (count args)
        default? (or (= 1 arity) (and (= 2 arity) (integer? selector)))]
    (cond
      (not (<= 1 arity 3))
      (throw (ex-info "Expected a module file, an optional entry and optional revision" {:reason :invalid-arity}))

      (not (string? file))
      (throw (ex-info "load-primitive-environment expects a file path" {:reason :invalid-file :file file}))

      (and (not default?) (not (qualified-keyword? selector)))
      (throw (ex-info "primitive entry must be a namespaced keyword" {:reason :invalid-entry :entry selector}))

      :else
      [file (when-not default? selector) (or (when default? selector) revision 0)])))
