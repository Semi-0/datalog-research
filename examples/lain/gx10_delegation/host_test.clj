(ns gx10-delegation.host-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [graph.lain-editor.runtime :as editor]
            [gx10-delegation.host :as host]))

(defn with-program [path f]
  (let [candidate (editor/prepare (slurp path) [])]
    (try (f candidate)
         (finally (editor/close-session! (:session candidate))))))

(deftest constraint-fixtures
  (with-program "examples/lain/gx10_delegation/fixtures.lain"
    (fn [candidate]
      (is (true? (host/binding-value candidate 'clean-ready)))
      (doseq [binding '[unload-not-proven memory-not-settled reserve-crossed sandbox-not-verified]]
        (is (false? (host/binding-value candidate binding))))
      (is (thrown? Exception (host/binding-value candidate 'pending-admission))))))

(deftest compiled-workflow-replay
  (with-program "examples/lain/gx10_delegation/delegation.lain"
    (fn [candidate]
      (let [workflow (host/binding-value candidate 'result)
            events (host/replay! workflow)]
        (is (= events (host/replay! workflow)))
        (is (= [:flash :ornith-nvfp4 :flash]
               (mapv :arguments (filter #(= :model/enter (:operation %)) events))))
        (is (= 4 (first (:arguments (first (filter #(= :pi/parallel (:operation %)) events))))))
        (doseq [operation [:model/exit :model/verify-unloaded]]
          (let [seen (atom [])]
            (is (thrown? Exception
                         (host/execute! workflow
                           (fn [request]
                             (swap! seen conj request)
                             (if (= operation (:operation request))
                               {:outcome :transition-failed}
                               {:outcome :passed})))))
            (is (= [:flash] (mapv :arguments
                              (filter #(= :model/enter (:operation %)) @seen))))))))))

(deftest cleanup-on-agent-failure
  (let [seen (atom [])]
    (is (thrown? Exception
          (host/execute! [:with-model :flash [:step :plan :pi/plan :task]]
            (fn [request]
              (swap! seen conj (:operation request))
              (if (= :pi/plan (:operation request))
                {:outcome :runtime-error}
                {:outcome :passed})))))
    (is (= [:model/enter :pi/plan :model/exit] @seen))))

(deftest reject-unbounded-or-unknown-work
  (doseq [workflow [[:step :workers :pi/parallel [5 [:normalize]]]
                    [:step :unknown :shell/run "anything"]
                    [:with-model :unknown [:step :plan :pi/plan :task]]]]
    (is (thrown? Exception (host/validate! workflow)))))

(deftest live-admission-is-evaluated-by-lain
  (is (true? (host/admission? [true true true true])))
  (doseq [facts [[false true true true] [true false true true]
                 [true true false true] [true true true false]]]
    (is (false? (host/admission? facts))))
  (is (thrown? Exception (host/admission? [nil true true true]))))

(deftest subprocess-protocol
  (with-program "examples/lain/gx10_delegation/delegation.lain"
    (fn [candidate]
      (let [program "import sys,json\nfor line in sys.stdin:\n r=json.loads(line)\n print(json.dumps({'outcome':'passed','request':r,'detail':{'admission':[True,True,True,True]}},ensure_ascii=False),flush=True)\n"
            receipt (host/run-host! (host/binding-value candidate 'result)
                                   ["python3" "-u" "-c" program])]
        (is (= :passed (:outcome receipt)))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'gx10-delegation.host-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
