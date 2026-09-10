(ns propagators.compiler-2-call-graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as helpers]
            [propagators.compiler-2.main :as compiler]
            [propagators.compiler-2.model.application-value :as application-value]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.operators.call-graph :as call-graph]
            [propagators.gur.accumulating.facts :as facts]
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

(defn- accumulated-networks
  [network]
  (keep (fn [[id _entry]]
          (let [candidate (strongest network id)]
            (cond
              (net/net? candidate)
              candidate

              :else
              nil)))
        (net/net-env network)))

(defn- accumulated-application-declarations
  [network]
  (for [child (accumulated-networks network)
        declaration (vals (facts/application-declarations child))]
    declaration))

(defn- node-id-with-label
  [graph label]
  (some (fn [[id candidate]]
          (cond
            (= label candidate)
            id

            :else
            nil))
        (:nodes graph)))

(defn- potential-callee-id
  [graph operator-label]
  (let [call-id (node-id-with-label graph (str "potential " operator-label))]
    (some (fn [[from to]]
            (cond
              (= from call-id)
              to

              :else
              nil))
          (:edges graph))))

(defn- potential-caller-id
  [graph operator-label]
  (let [call-id (node-id-with-label graph (str "potential " operator-label))]
    (some (fn [[from to]]
            (cond
              (= to call-id)
              from

              :else
              nil))
          (:edges graph))))

(defn- accumulated-prop-names
  [network]
  (set
   (for [child (accumulated-networks network)
         [_id entry] (net/net-env child)
         :when (prop/prop? entry)]
     (prop/prop-name entry))))

(defn- graph-statuses
  [graph]
  (set (keep :call/status (vals (:values graph)))))

(deftest call-graph-is-a-canonical-primitive
  (is (operator-value/operator-closure?
       (env/lookup (helpers/default-env) 'call-graph)))
  (is (operator-value/operator-closure?
       (env/lookup (helpers/default-env) 'p:call-graph)))
  (let [compiled (compiler/compile-source
                  "(call-graph (:: [x] (+ x 1)))")
        network (run-compiled compiled)
        graph (strongest network (:cell compiled))]
    (is (= #{:potential} (graph-statuses graph)))
    (is (contains? (set (vals (:nodes graph))) "+"))))

(deftest potential-call-graph-represents-recursion-as-a-cycle
  (let [compiled (compiler/compile-source
                  "(let-cell [self graph]
                     (def-net self [x] [out]
                       (self x out))
                     (call-graph self graph)
                     graph)")
        network (run-compiled compiled)
        graph (strongest network (:cell compiled))
        self-id (potential-caller-id graph "self")
        recursive-call-id (some (fn [[id label]]
                                  (when (= "potential self" label) id))
                                (:nodes graph))]
    (is (some? self-id))
    (is (some? recursive-call-id))
    (is (contains? (set (:edges graph)) [self-id recursive-call-id]))
    (is (contains? (set (:edges graph)) [recursive-call-id self-id]))))

(deftest realized-recursion-also-remains-a-finite-cycle
  (let [closure-id (ids/new-node-id)
        application-id (ids/new-node-id)
        args-id (ids/new-node-id)
        out-id (ids/new-node-id)
        context-id (ids/new-node-id)
        compiled (compiler/compile-source "(:: [x] x)")
        closure (strongest (:net compiled) (:cell compiled))
        application (application-value/application-object
                     {:operator-ast 'self
                      :operator-cell closure-id
                      :args-id args-id
                      :arg-ids []
                      :output-id out-id
                      :context-id context-id
                      :caller-id closure-id})
        graph (call-graph/realized-call-graph closure-id application-id
                                              application closure-id closure)]
    (is (contains? (set (:edges graph)) [closure-id application-id]))
    (is (contains? (set (:edges graph)) [application-id closure-id]))))

(deftest direct-gur-closure-applications-publish-realized-call-facts
  (let [compiled (compiler/compile-source
                  "(let-cell [f out graph]
                     (def-net f [x] [out]
                       (+ x 1))
                     (f 2 out)
                     (call-graph f graph)
                     graph)")
        network (run-compiled compiled)
        graph (strongest network (:cell compiled))
        application-declarations
        (accumulated-application-declarations network)]
    (is (contains? (graph-statuses graph) :potential))
    (is (contains? (graph-statuses graph) :realized))
    (is (seq application-declarations))
    (is (some #{"call +"} (vals (:nodes graph))))
    (is (contains? (accumulated-prop-names network)
                   :compiler-2/call-graph-application))))

(deftest late-operator-arrival-refines-the-call-graph
  (let [compiled (compiler/compile-source
                  "(let-cell [g outer out graph]
                     (def-net outer [x] [out]
                       (g x out))
                     (outer 2 out)
                     (call-graph outer graph)
                     graph)")
        waiting (run-compiled compiled)
        graph-id (:cell compiled)
        waiting-graph (strongest waiting graph-id)
        g-id (potential-callee-id waiting-graph "g")
        callee-compiled (compiler/compile-source
                         "(network [x] [out] (+ x 1))")
        callee (strongest (:net callee-compiled) (:cell callee-compiled))
        with-callee (nb/seed-cell waiting g-id callee)
        settled (nb/run-propagators
                 with-callee
                 (nb/neighbor-propagator-ids with-callee g-id))
        settled-graph (strongest settled graph-id)
        waiting-labels (set (vals (:nodes waiting-graph)))
        settled-labels (set (vals (:nodes settled-graph)))]
    (is (not (contains? waiting-labels "call g")))
    (is (not (value/unusable? callee)))
    (is (contains? settled-labels "call g"))))

(deftest pure-call-site-extraction-is-independent-of-runtime-state
  (let [compiled (compiler/compile-source
                  "(:: [x] (+ (* x 2) 1))")
        closure (strongest (:net compiled) (:cell compiled))
        labels (mapv :operator-label
                     (call-graph/call-sites
                      (closure-value/closure-body closure)))]
    (is (= ["->" "+" "*"] labels))))
