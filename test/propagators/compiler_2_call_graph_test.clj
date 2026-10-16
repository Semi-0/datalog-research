(ns propagators.compiler-2-call-graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.compiler.basis :as helpers]
            [propagators.compiler-2.main :as compiler]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.operators.call-graph :as call-graph]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- run-compiled
  [compiled]
  (nb/run-propagators (:net compiled) (:props compiled)))

(defn- strongest
  [network id]
  (net/network-cell-strongest network id))

(defn- binding-id
  [network sym]
  (let [ids (keep (fn [[id name]] (when (= sym name) id))
                  (env/binding-names network))]
    (when (= 1 (count ids)) (first ids))))

(defn- graph-statuses
  [graph]
  (set (keep :call/status (vals (:values graph)))))

(defn- default-operator
  [sym]
  (some (fn [[name operator]]
          (when (= name sym) operator))
        (helpers/default-bindings)))

(deftest call-graph-is-a-primitive-and-a-named-propagator
  (is (operator-value/operator-closure?
       (default-operator 'call-graph)))
  (is (operator-value/operator-closure?
       (default-operator 'p:call-graph)))
  (let [compiled (compiler/compile-source
                  "(call-graph (network [x] (+ x 1)))")
        network (run-compiled compiled)
        graph (strongest network (:cell compiled))
        prop-names (set (keep (fn [[_ entry]]
                                (when (prop/prop? entry)
                                  (prop/prop-name entry)))
                              (net/net-env network)))]
    (is (= #{:potential} (graph-statuses graph)))
    (is (contains? (set (vals (:nodes graph))) "+"))
    (is (contains? prop-names 'call-graph))))

(deftest potential-call-graph-represents-recursion-as-a-cycle
  (let [compiled (compiler/compile-source
                  "(let-cell [self graph] (define self (network [x out] (-> (self x out) out) (list out))) (call-graph self graph) graph)")
        network (run-compiled compiled)
        graph (strongest network (:cell compiled))
        self-id (binding-id network 'self)
        recursive-call-id (some (fn [[id label]]
                                  (when (and (= "self" label) (= :potential (get-in graph [:values id :call/status]))) id))
                                (:nodes graph))]
    (is (some? self-id))
    (is (some? recursive-call-id))
    (is (contains? (set (:edges graph)) [self-id recursive-call-id]))
    (is (contains? (set (:edges graph)) [recursive-call-id self-id]))))

(deftest realized-recursion-also-remains-a-finite-cycle
  (let [closure-id (ids/new-node-id)
        application-id (ids/new-node-id)
        compiled (compiler/compile-source "(network [x] x)")
        closure (strongest (:net compiled) (:cell compiled))
        graph (call-graph/realized-call-graph closure-id
                                              application-id
                                              closure-id
                                              closure)]
    (is (contains? (set (:edges graph)) [closure-id application-id]))
    (is (contains? (set (:edges graph)) [application-id closure-id]))))

(deftest connected-closure-applications-publish-realized-call-facts
  (let [compiled (compiler/compile-source
                  "(let-cell [f out graph] (define f (network [x out] (-> (+ x 1) out) (list out))) (f 2 out) (call-graph f graph) graph)")
        network (run-compiled compiled)
        graph (strongest network (:cell compiled))]
    (is (contains? (graph-statuses graph) :potential))
    (is (contains? (graph-statuses graph) :realized))
    (is (some (fn [[id info]] (and (= :realized (:call/status info)) (= "+" (get-in graph [:nodes id])))) (:values graph)))
    (is (some #{:compiler-2/call-graph-application}
              (keep (fn [[_ entry]]
                      (when (prop/prop? entry) (prop/prop-name entry)))
                    (net/net-env network))))))

(deftest late-operator-arrival-refines-the-call-graph
  (let [compiled (compiler/compile-source
                  "(let-cell [g outer out graph] (define outer (network [x out] (-> (g x out) out) (list out))) (outer 2 out) (call-graph outer graph) graph)")
        waiting (run-compiled compiled)
        graph-id (:cell compiled)
        waiting-graph (strongest waiting graph-id)
        g-id (binding-id waiting 'g)
        callee-compiled (compiler/compile-source
                         "(network [x out] (-> (+ x 1) out) (list out))")
        callee (strongest (:net callee-compiled) (:cell callee-compiled))
        with-callee (nb/seed-cell waiting g-id callee)
        settled (nb/run-propagators
                 with-callee
                 (nb/neighbor-propagator-ids with-callee g-id))
        settled-graph (strongest settled graph-id)
        realized-labels (fn [graph] (set (for [[id info] (:values graph) :when (= :realized (:call/status info))] (get-in graph [:nodes id]))))
        waiting-labels (realized-labels waiting-graph)
        settled-labels (realized-labels settled-graph)]
    (is (not (contains? waiting-labels "g")))
    (is (not (value/unusable? callee)))
    (is (contains? settled-labels "g"))))

(deftest pure-call-site-extraction-is-independent-of-runtime-state
  (let [compiled (compiler/compile-source
                  "(network [x] (+ (* x 2) 1))")
        closure (strongest (:net compiled) (:cell compiled))
        labels (mapv :operator-label
                     (call-graph/call-sites
                      (closure-value/closure-body
                       (application/callable-declaration closure))))]
    (is (= ["->" "+" "*"] labels))))

(deftest potential-calls-stop-at-nested-closure-boundaries
  (let [body (ast/network ['x] (ast/app '+ 'x 1))]
    (is (empty? (call-graph/call-sites body)))))

(deftest potential-computed-callees-have-call-site-identity
  (let [compiled (compiler/compile-source "(network [x] (+ ((if true + -) x 1) ((if false + -) x 2)))")
        network (:net compiled)
        closure-id (:cell compiled)
        declaration (application/callable-declaration (strongest network closure-id))
        graph (call-graph/potential-call-graph network closure-id declaration)
        computed (for [[id info] (:values graph)
                       :when (= ":apply" (:call/operator info))]
                   (second (first (filter #(= id (first %)) (:edges graph)))))]
    (is (= 2 (count computed)))
    (is (= 2 (count (set computed))))
    (is (= #{:cell :propagator} (set (vals (:node-kinds graph)))))
    (is (every? (fn [[a b]] (not= (get-in graph [:node-kinds a]) (get-in graph [:node-kinds b]))) (:edges graph)))))

(deftest closure-parameters-do-not-resolve-to-outer-bindings
  (let [compiled (compiler/compile-expr
                  (ast/sequence*
                   (ast/def* 'f (ast/network ['x] (ast/app '+ 'x 1)))
                   (ast/network ['f 'x] (ast/app 'f 'x))))
        network (run-compiled compiled)
        closure-id (:cell compiled)
        declaration (application/callable-declaration (strongest network closure-id))
        graph (call-graph/potential-call-graph network closure-id declaration)
        outer-f (binding-id network 'f)
        sites (set (for [[id info] (:values graph) :when (= "f" (:call/operator info))] id))]
    (is (= 1 (count sites)))
    (is (not-any? #(and (sites (first %)) (= outer-f (second %))) (:edges graph)))))

(deftest identity-closure-retains-its-isolated-callable
  (let [compiled (compiler/compile-source "(network [x] x)")
        network (:net compiled)
        graph (call-graph/potential-call-graph network (:cell compiled)
                (application/callable-declaration (strongest network (:cell compiled))))]
    (is (contains? (:nodes graph) (:cell compiled)))))
