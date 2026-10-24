(ns propagators.compiler-2.runtime.boundary.display
  "Generic runtime display-update normalization."
  (:require [propagators.datastructures.compound-information :as information]
            [propagators.stdlib.source-publication :as publication]
            [propagators.stdlib.premise-publication :as supported]
            [propagators.datastructures.layered-value :as datum]
            [propagators.semantic-trace :as semantic-trace]))

(defn update-value
  "Preserve supported information; publish raw display values through TTMS."
  [display-id source-id tick payload]
  (cond
    (information/semantic-kind payload) payload
    (datum/layer-present? payload :support) (supported/content payload)
    :else (publication/active source-id tick payload)))
