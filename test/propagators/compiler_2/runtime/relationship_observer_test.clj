(ns propagators.compiler-2.runtime.relationship-observer-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.model.env :as cenv]
            [propagators.compiler-2.runtime.extensions.relationship-xr :as relationship-xr]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.network :as net]
            [propagators.relationship :as relationship]
            [propagators.relationship-observer :as relationship-observer]
            [propagators.semantic-trace :as semantic-trace]))

(deftest relationship-extension-compiles-a-composed-root-observer
  (let [session
        (loader/load-session-from-source
          "(def-cells a b graph)
          (<-> 1 a)
          (-> (+ a 1) b)
          (relationship:roots a graph)
          (xr:io graph)"
         {:client-id "relationship-test"
          :extensions [(relationship-xr/extension)]})
        network (:program/net @session)
        graph-id (cenv/resolve-binding-id network
                                           (:program/env @session)
                                           'graph)
        a-id (cenv/resolve-binding-id network (:program/env @session) 'a)
        b-id (cenv/resolve-binding-id network (:program/env @session) 'b)
        traced (net/network-cell-strongest network graph-id)
        traced-ids (set (map second (keys (:nodes traced))))]
    (is (semantic-trace/semantic-trace-graph? traced))
    (is (contains? traced-ids a-id))
    (is (contains? traced-ids b-id))
    (is (every? #(empty? (relationship/parents
                          (net/net-relationship network) %))
                (keys (:nodes traced))))
    (is (not-any? #(= relationship-observer/observer-name %)
                  (vals (:nodes traced))))
    (is (seq (:edges traced)))
    (is (seq (get-in @session [:xr :effects])))
    (is (= "/api/relationships"
           relationship-xr/relationship-endpoint))))
