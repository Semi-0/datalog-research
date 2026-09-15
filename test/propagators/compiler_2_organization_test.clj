(ns propagators.compiler-2-organization-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.compiler.handlers :as handlers]
            [propagators.compiler-2.compiler.predicates :as predicates]
            [propagators.compiler-2.main :as main]
            [propagators.compiler-2.runtime :as runtime]
            [graph.compiler-2-runtime :as runtime-shim]))

(deftest cps-is-the-canonical-production-compiler
  (is (identical? main/default-compiler compiler/default-compiler))
  (is (identical? main/compile* compiler/compile*))
  (is (nil? (get (ns-aliases 'propagators.compiler-2.cps-core)
                 'predicate-core)))
  (is (= 'propagators.compiler-2.compiler.handlers
         (-> #'handlers/compile-application meta :ns ns-name)))
  (is (= 'propagators.compiler-2.compiler.predicates
         (-> #'predicates/literal? meta :ns ns-name))))

(deftest materialized-compiler-adapters-are-absent
  (doseq [resource ["propagators/compiler_2/deprecated/legacy_core.clj"
                    "propagators/compiler_2/deprecated/compiler_core.clj"
                    "propagators/compiler_2/deprecated/core.clj"
                    "propagators/compiler_2/deprecated/synchronous.clj"
                    "propagators/compiler_2/predicate_core.clj"
                    "propagators/compiler_2/legacy.clj"]]
    (is (nil? (io/resource resource)) resource)))

(deftest live-runtime-is-owned-by-propagators
  (is (not (:deprecated (meta (find-ns
                               'propagators.compiler-2.runtime)))))
  (is (:deprecated (meta (find-ns 'graph.compiler-2-runtime))))
  (is (identical? runtime/new-session runtime-shim/new-session))
  (is (identical? runtime/compile-source! runtime-shim/compile-source!))
  (is (identical? runtime/commit-version! runtime-shim/commit-version!)))
