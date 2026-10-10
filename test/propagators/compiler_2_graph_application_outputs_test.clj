(ns propagators.compiler-2-graph-application-outputs-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.experimental.visualization.observation :as observation]
            [propagators.compiler-2-functional-network-test :as fixture]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.application-ports :as ports]
            [propagators.experimental.visualization.data :as data]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(deftest output-observation-needs-only-a-propagator-graph
  (let [[id input result member] (repeatedly 4 ids/new-node-id)
        network (reduce nb/ensure-cell net/empty-net [input result member])
        [_ initial] ((prop/construct-propagator id :test/opaque (fn [_ _ _] [])
                       [input] [result input]) network)
        reference (data/reference id)
        [_ extended] (runner/apply-patch nil
                       (patch/extend-propagator-outputs id [member]) initial)]
    ;; No callable, declaration, or named-output index is present.
    (is (= #{(data/reference result) (data/reference input)}
           (observation/declared-interface initial reference :outputs)))
    (is (= #{(data/reference result) (data/reference input) (data/reference member)}
           (observation/declared-interface extended reference :outputs)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"propagator reference"
                         (observation/declared-interface extended
                           (data/reference member) :outputs)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown interface direction"
                         (observation/declared-interface extended reference :unknown)))))

(deftest named-member-ports-require-actual-output-edges
  (let [compiled (compiler/compile-source "((network () (list 1 2)))")
        network (fixture/run compiled)
        topology (application/application-topology-for-result network (:cell compiled))
        inspected (ports/application-ports network topology)
        id (observation/application-propagator topology)
        member (first (:member-outputs inspected))
        node (get (net/net-graph network) id)
        disconnected (net/net-with-graph network
                       (assoc (net/net-graph network) id
                              (graph/node (:inputs node) (disj (:outputs node) member))))]
    (is (= 2 (count (:member-outputs inspected))))
    (is (= :complete (:output-status inspected)))
    (is (every? #(contains? (:outputs node) %) (:outputs inspected)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require graph connections"
                         (ports/application-ports disconnected topology)))))

(deftest network-outputs-are-visible-through-generic-neighbors
  (let [compiled (compiler/compile-source "((network (x) (list x (+ x 1))) 4)")
        network (fixture/run compiled)
        topology (application/application-topology-for-result network (:cell compiled))
        id (observation/application-propagator topology)
        inspected (ports/application-ports network topology)
        observed (observation/declared-interface network (data/reference id) :outputs)]
    (is (= [4 5] (fixture/values network (:cell compiled))))
    (doseq [member (:member-outputs inspected)]
      (is (contains? observed (data/reference member)))
      (is (some #{id} (nb/neighbor-propagator-ids network member))))
    (is (contains? observed (data/reference (:cell compiled))))
    (is (every? #(contains? observed (data/reference %)) (:argument-ids topology)))))
