(ns propagators.datastructures.tms.legacy
  "Legacy centralized reducer-cell TMS compatibility surface."
  (:require [propagators.datastructures.tms.core]))

(doseq [[sym v] (ns-publics 'propagators.datastructures.tms.core)]
  (intern *ns* sym @v))
