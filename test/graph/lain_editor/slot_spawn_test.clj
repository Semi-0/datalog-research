(ns graph.lain-editor.slot-spawn-test
  (:require [clojure.test :refer [deftest is testing]]
            [graph.lain-editor.frontend.model :as model]
            [graph.lain-editor.runtime :as runtime]
            [graph.lain-editor.server :as server]
            [graph.lain-editor.slot-spawn :as slots]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]))

(defn- binding-value [pane name]
  (let [state @(:session pane)
        network (:program/net state)
        id (env/resolve-binding-id network (:program/env state) name)]
    (data/payload (net/network-cell-strongest network id))))

(deftest plans-callable-slots-without-filling-inputs-or-known-operators
  (let [pane (runtime/prepare
              "(define queue) (define consumed (consume-task agents queue))
               (define other (consume-task agents queue))
               (define first (zero)) (define second (one 1))
               (define available (+ 2 3))" [])]
    (try
      (let [state @(:session pane)
            planned (slots/plan (:program/net state) (:program/env state))]
        (is (= [{:name "consume-task" :inputCount 2 :callCount 2}
                {:name "one" :inputCount 1 :callCount 1}
                {:name "zero" :inputCount 0 :callCount 1}]
               (:slots planned)))
        (is (empty? (:skipped planned)))
        (is (= '(define consume-task (network [input-1 input-2] (define out) out))
               (read-string (:source planned))))
        (is (value/nothing? (binding-value pane 'queue)))
        (is (value/nothing? (binding-value pane 'agents)))
        (is (= 5 (binding-value pane 'available))))
      (finally (runtime/close-session! (:session pane))))))

(deftest spawn-slot-applies-an-ordinary-replayable-extension-once
  (let [editors (atom {}) source "(define queue) (define consumed (consume-task agents queue))"]
    (try
      (let [{:keys [generation]} (runtime/run! editors {:pane "target" :source source})
            _ (runtime/run! editors {:pane "neighbor" :source source})
            neighbor (get @editors "neighbor")
            original (get @editors "target")
            result (server/command! editors {:op "spawn-slot" :pane "target" :generation generation})
            updated (get @editors "target")
            state @(:session updated)
            network (:program/net state)
            repeated (server/command! editors {:op "spawn-slot" :pane "target" :generation (:generation result)})]
        (is (= [{:name "consume-task" :inputCount 2 :callCount 1}] (:slots result)))
        (is (not= generation (:generation result)))
        (is (= source (:source updated)))
        (is (= [(:source result)] (:extensions updated)))
        (is (identical? neighbor (get @editors "neighbor")))
        (is (value/nothing? (binding-value neighbor 'consume-task)))
        (is (value/nothing? (binding-value updated 'consumed)))
        (is (value/nothing? (binding-value updated 'queue)))
        (let [operator-id (env/resolve-binding-id network (:program/env state) 'consume-task)
              calls (filter #(= operator-id (:operator-id %)) (application/application-topologies network))]
          (is (= [2] (mapv #(count (:argument-ids %)) calls))))
        (is (empty? (:slots repeated)))
        (is (= (:generation result) (:generation repeated)))
        (is (identical? updated (get @editors "target")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Pane changed"
                             (runtime/spawn-slots! editors {:pane "target" :generation generation})))
        (let [replayed (runtime/prepare (:source updated) (:extensions updated))]
          (try
            (is (value/nothing? (binding-value replayed 'consumed)))
            (is (empty? (:slots (slots/plan (:program/net @(:session replayed))
                                           (:program/env @(:session replayed))))))
            (finally (runtime/close-session! (:session replayed)))))
        (is (not (identical? original updated))))
      (finally (runtime/close! editors)))))

(deftest conflicting-arities-do-not-partially-extend-the-block
  (let [editors (atom {})]
    (try
      (let [{:keys [generation]} (runtime/run! editors {:pane "conflict" :source "(a 1) (b 1) (b 1 2)"})
            original (get @editors "conflict")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"b.*conflicting input counts.*1 2"
                             (runtime/spawn-slots! editors {:pane "conflict" :generation generation})))
        (is (identical? original (get @editors "conflict")))
        (is (value/nothing? (binding-value original 'a)))
        (is (empty? (:extensions original))))
      (finally (runtime/close! editors)))))

(deftest lexical-callable-parameters-are-reported-without-root-definitions
  (let [pane (runtime/prepare "(define apply-one (network [f] (f 1))) (define callback) (apply-one callback)" [])]
    (try
      (let [state @(:session pane)
            planned (slots/plan (:program/net state) (:program/env state))]
        (is (empty? (:slots planned)))
        (is (seq (:skipped planned))))
      (finally (runtime/close-session! (:session pane))))))

(deftest pending-derived-callables-are-not-replaced-by-stubs
  (let [pane (runtime/prepare "(define f (factory missing)) (f 1)" [])]
    (try
      (let [state @(:session pane)
            planned (slots/plan (:program/net state) (:program/env state))]
        (is (= [{:name "factory" :inputCount 1 :callCount 1}] (:slots planned)))
        (is (= [{:name "f" :reason "pending-producer"}] (:skipped planned))))
      (finally (runtime/close-session! (:session pane))))))

(deftest spawn-slot-command-requires-a-current-ordinary-runtime
  (let [document (model/initial-workspace)
        pane (first (:panes document)) id (:id pane)
        command (fn [doc generations busy?]
                  (first (filter #(= :spawn-slot (:action %)) (model/commands doc id generations busy?))))
        committed (model/update-pane document id assoc :source "(missing 1)" :executedSource "(missing 1)")]
    (is (= "Spawn slot" (:label (command document {} false))))
    (is (not (:enabled (command document {} false))))
    (is (:enabled (command committed {id "generation"} false)))
    (doseq [changed [(model/update-pane committed id assoc :source "(missing 2)")
                     (model/update-pane committed id assoc :extensionDraft "(define pending)")
                     (model/update-pane committed id assoc :extensions ["(define x 1)"] :extensionEdits ["(define x 2)"])
                     (model/update-pane committed id assoc :development true)]]
      (is (not (:enabled (command changed {id "generation"} false)))))
    (is (not (:enabled (command committed {id "generation"} true))))))
