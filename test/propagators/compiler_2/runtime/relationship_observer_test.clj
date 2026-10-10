(ns propagators.compiler-2.runtime.relationship-observer-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.model.env :as cenv]
            [propagators.compiler-2.runtime.extensions.relationship-xr :as relationship-xr]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.network :as net]
            [propagators.relationship :as relationship]
            [propagators.relationship-observer :as relationship-observer]
            [propagators.runner :as runner]
            [propagators.semantic-trace :as semantic-trace]))

(defn- load-observed [source options]
  (let [steps (atom 0)]
    (binding [runner/*advance-transform*
              (fn [advance]
                (fn [state continuations]
                  (when (> (swap! steps inc) 5000)
                    (throw (ex-info "Observer did not reach quiescence" {})))
                  (advance state continuations)))]
      (loader/load-session-from-source source options))))

(deftest relationship-extension-compiles-a-composed-root-observer
  (let [session
        (load-observed
          "(define a (cell)) (define b (cell)) (define graph (cell))
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
        traced-ids (set (map second (keys (:nodes traced))))
        labels (set (vals (:nodes traced)))]
    (is (semantic-trace/semantic-trace-graph? traced))
    (is (contains? traced-ids a-id))
    (is (contains? traced-ids b-id))
    (is (not (contains? traced-ids graph-id)))
    (is (every? #(empty? (relationship/parents
                          (net/net-relationship network) %))
                (keys (:nodes traced))))
    (is (not-any? #(= relationship-observer/observer-name %)
                  (vals (:nodes traced))))
    (is (contains? labels '+))
    (is (contains? labels '->))
    (is (seq (:edges traced)))
    (is (seq (get-in @session [:xr :effects])))
    (is (= "/api/relationships"
           relationship-xr/relationship-endpoint))))
