(ns propagators.stdlib.membership
  "Pure primitive application preserving value and membership dependencies."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.datastructures.support :as support]
            [propagators.stdlib.premise-state :as state]))

(defn payload [x]
  (if (and (datum/layer-present? x :base) (datum/layer-present? x :support))
    (datum/layer-value x :base)
    x))

(defn apply-value [f arguments]
  (let [states (apply support/join (map state/states-of arguments))
        bases (mapv payload arguments)
        inputs (apply support/combine (map datum/support-of arguments))
        guards (apply support/combine (map datum/membership-of arguments))]
    (if (apply value/any-unusable-values? arguments)
      (membership/content {:premise-state states})
      (let [result (try (apply f bases)
                        (catch Exception error
                          (value/contradiction-with-provenance
                           #{{:operation :membership/application :reason (.getMessage error)}})))]
        (membership/content {:base result :support inputs :membership guards :premise-state states})))))
