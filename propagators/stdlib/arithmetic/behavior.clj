(ns propagators.stdlib.arithmetic.behavior
  "Compatibility facade for behavior arithmetic."
  (:refer-clojure :exclude [+ - * /])
  (:require [propagators.datastructures.behavior.arithmetic]))

(doseq [[sym v] (ns-publics 'propagators.datastructures.behavior.arithmetic)]
  (intern *ns* sym @v))
