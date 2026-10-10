(ns propagators.compiler-2.api
  "Stable public compiler entrypoints."
  (:require [propagators.compiler-2.main :as compiler]))

(defn- compiler-result
  [compiled]
  {:network (:net compiled)
   :environment (:env compiled)
   :installed-propagator-ids (vec (:props compiled))
   :application-metadata (vec (:applications compiled))
   :result-cell (:cell compiled)
   :diagnostics []})

(defn- require-options
  [options]
  (if (map? options)
    options
    (throw (ex-info "compiler options must be a map"
                    {:options options}))))

(defn compile-form
  [form environment options]
  (compiler-result
   (compiler/compile-form form environment (require-options options))))

(defn compile-program
  [forms environment options]
  (compiler-result
   (compiler/compile-program forms environment (require-options options))))

(defn compile-source
  [source environment options]
  (if (string? source)
    (compiler-result
     (compiler/compile-source source environment (require-options options)))
    (throw (ex-info "compiler source must be a string"
                    {:source source}))))
