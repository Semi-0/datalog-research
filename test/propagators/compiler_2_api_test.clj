(ns propagators.compiler-2-api-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compiler-2.api :as api]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.env :as env]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(declare live-environment)

(defn- result-value [compiled]
  (net/network-cell-strongest
   (nb/run-propagators (:network compiled) (:installed-propagator-ids compiled))
   (:result-cell compiled)))

(deftest canonical-reader-forms-use-public-cps-entrypoints
  (let [[environment options] (live-environment)]
    (is (= 5 (result-value
              (api/compile-form '((network (x) (+ x 1)) 4) environment options))))
    (is (= 42 (result-value
               (api/compile-program '[(define answer 42) answer] environment options))))))

(defn- live-environment []
  (let [environment-id (ids/new-node-id)
        declared (env/declare-root net/empty-net
                                   environment-id
                                   (basis/default-bindings))]
    [environment-id
     {:net (:net declared)
      :environment-props (:props declared)}]))

(deftest compile-form-exposes-stable-result
  (let [[environment options] (live-environment)
        result (api/compile-form 42 environment options)]
    (is (map? (:network result)))
    (is (ids/node-id? (:environment result)))
    (is (vector? (:installed-propagator-ids result)))
    (is (vector? (:application-metadata result)))
    (is (= [] (:diagnostics result)))))

(deftest compiler-api-rejects-invalid-input
  (let [[environment options] (live-environment)]
    (testing "nil is a literal expression returning a cell"
      (let [compiled (api/compile-form nil environment options)]
        (is (ids/node-id? (:result-cell compiled)))
        (is (nil? (result-value compiled)))))
    (testing "non-string source is an explicit error"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"must be a string"
                            (api/compile-source 42 environment {}))))
    (testing "invalid options are explicit errors"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"options must be a map"
                            (api/compile-form 42 environment []))))))
