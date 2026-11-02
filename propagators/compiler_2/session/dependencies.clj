(ns propagators.compiler-2.session.dependencies
  "Fixed host capabilities. Mutable transport resources never enter propagators.")

(defprotocol Dependency
  (start! [dependency context emit!])
  (normalize-output [dependency external-input]))

(deftype CallbackDependency [start normalize])

(extend-type CallbackDependency
  Dependency
  (start! [dependency context emit!]
    ((.-start ^CallbackDependency dependency) context emit!))
  (normalize-output [dependency external-input]
    ((.-normalize ^CallbackDependency dependency) external-input)))

(defn callback-dependency
  "start! may emit zero or many updates, synchronously or asynchronously.
  Return nil for no owned resource, or a cleanup function returning {:status :closed}.
  Completion is explicit via context's :complete!; returning does not finish a stream."
  ([start] (callback-dependency start identity))
  ([start normalize]
   (when-not (and (fn? start) (fn? normalize))
     (throw (ex-info "Dependency start and normalizer must be functions" {})))
   (CallbackDependency. start normalize)))
