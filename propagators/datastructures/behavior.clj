(ns propagators.datastructures.behavior
  "Compatibility facade for behavior datastructure namespaces."
  (:require [propagators.datastructures.behavior.core]))

(doseq [[sym v] (ns-publics 'propagators.datastructures.behavior.core)]
  (intern *ns* sym @v))
