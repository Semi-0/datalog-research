(ns graph.lain-editor.top-down-scanner-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.top-down.scanner :as scanner]
            [graph.lain-editor.runtime :as editor]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.experimental.visualization.data :as data]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- binding-value [session name]
  (let [state @session network (:program/net state)
        id (env/resolve-binding-id network (:program/env state) name)]
    (data/payload (net/network-cell-strongest network id))))

(deftest empty-cells-are-classified-by-declared-role-at-a-checkpoint
  (let [id (ids/new-node-id) empty (nb/install-cells [id])
        before (observation/frozen-snapshot empty)
        after (observation/frozen-snapshot (nb/seed-cell empty id 7))
        conflict (observation/frozen-snapshot (nb/seed-cell empty id value/contradiction))]
    (doseq [[role expected] [[:implementation :unresolved-implementation] [:derived :waiting] [:input :awaiting-input]]]
      (is (= expected (:status (scanner/scan-node before id "component" role)))))
    (is (= :available (:status (scanner/scan-node after id "component" :input))))
    (is (= :awaiting-input (:status (scanner/scan-node before id "component" :input))))
    (is (= :contradiction (:status (scanner/scan-node conflict id "component" :derived))))
    (is (thrown? clojure.lang.ExceptionInfo (scanner/scan-node nil id "component" :input)))
    (is (thrown? clojure.lang.ExceptionInfo (scanner/scan-node before id "component" :unknown)))
    (is (thrown? clojure.lang.ExceptionInfo (scanner/scan-node before (ids/new-node-id) "absent" :input)))))

(deftest ordinary-lain-extension-observes-an-unimplemented-callable-and-its-refinement
  (let [source "(define answer (missing-component 2))"
        session (:session (editor/prepare source []))
        extension (str "(load-lain \"propagators/compiler_2/runtime/lain/semantic_traversal.lain\")\n"
                       "(load-primitive-environment \"examples/lain/top_down/scanner.clj\" :examples.lain.top-down.scanner/primitive-bindings)\n"
                       "(define inspect-hole (network (snapshot node) (top-down:scan-node snapshot node \"missing-component\" :implementation)))\n"
                       "(define report (observe-network missing-component inspect-hole))")]
    (try
      (editor/load-checked! session extension)
      (let [before (binding-value session 'report)]
        (is (= :unresolved-implementation (:status before)))
        (is (value/nothing? (binding-value session 'answer)))
        (editor/load-checked! session "(define missing-component (network (x) (+ x 1)))")
        (is (= :available (:status (binding-value session 'report))))
        (is (= 3 (binding-value session 'answer)))
        (is (= :unresolved-implementation (:status before))))
      (finally (editor/close-session! session)))))
