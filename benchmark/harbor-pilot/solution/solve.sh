#!/bin/bash
set -eu
cat > /app/src/pilot.clj <<'CLJ'
(ns pilot)
(defn stable-distinct-by [key-fn values]
  (second
   (reduce (fn [[seen result] value]
             (let [key (key-fn value)]
               (if (contains? seen key)
                 [seen result]
                 [(conj seen key) (conj result value)])))
           [#{} []] values)))
CLJ
