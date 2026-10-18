(ns graph.lain-editor.amb-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.display :as display]
            [graph.lain-editor.inspection :as inspection]
            [graph.lain-editor.runtime :as editor]
            [graph.lain-editor.value-grid :as grid]
            [propagators.compiler-2.model.env :as env]
            [propagators.experimental.amb.learning-view :as view]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.network-history :as history]
            [propagators.experimental.visualization.data :as data]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(defn- source [filename]
  (slurp (str "examples/lain/amb/editor/" filename ".lain")))

(defn- inspect [editors id mode]
  (inspection/inspect editors {:pane id :generation (:generation (get @editors id))
                              :display {:cell "@display" :mode mode :direction "both"}}))

(defn- value-at [session symbol]
  (let [network (:program/net @session)
        id (env/resolve-binding-id network (:program/env @session) symbol)]
    (observation/cell-base network id)))

(deftest no-good-view-retains-logical-context-and-exact-source-identities
  (let [choice (ids/new-node-id)
        context-source (ids/new-node-id)
        knowledge #{{:literals #{[choice 2]}
                     :context #{{:source context-source :timestamp 7 :premises-status :active}}}}
        value (view/learning-view knowledge)
        rows (grid/rows value)]
    (is (= knowledge (view/knowledge value)))
    (is (= value (view/learning-view knowledge)))
    (is (= rows (grid/rows value)))
    (is (some #(and (= "reject alternative" (:key %)) (= "2" (:value %))
                    (= (grid/cell-token choice) (:sourceReference %))) rows))
    (is (some #(and (= "context premise" (:key %)) (= "7 · active" (:value %))
                    (= (grid/cell-token context-source) (:sourceReference %))) rows))
    (is (not-any? #(= "unsatisfiable under context" (:value %)) rows))
    (is (some #(= "unsatisfiable under context" (:value %))
              (grid/rows (view/learning-view #{{:literals #{} :context #{}}}))))
    (is (= [{:key "learned no-goods" :value "0" :depth 0}]
           (grid/rows (view/learning-view #{}))))
    (is (thrown? clojure.lang.ExceptionInfo (view/learning-view #{:invalid})))))

(deftest session-local-learning-display-waits-learns-and-survives-quiescent-replay
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "search" :source (source "search")})
      (editor/run! editors {:pane "plain" :source "(display 42)"})
      (is (= :bool4/nothing (value-at (:session (get @editors "search")) 'knowledge)))
      (is (not-any? #(= "reject alternative" (:key %))
                    (:valueGrid (inspect editors "search" "value"))))
      (let [before (:session (get @editors "search"))
            domain (env/resolve-binding-id (:program/net @before) (:program/env @before) 'domain)]
        (editor/extend! editors {:pane "search" :generation (:generation (get @editors "search"))
                                :source "(-> (list 1 2) domain)"})
        (let [session (:session (get @editors "search"))
              network (:program/net @session)
              known (value-at session 'knowledge)
              rows (:valueGrid (inspect editors "search" "value"))
              tasks (keep (fn [[id entry]] (when (prop/prop? entry) id)) (net/net-env network))
              settled (runner/completed-network (runner/run-network tasks network))]
          (is (= domain (env/resolve-binding-id network (:program/env @session) 'domain)))
          (is (= 2 (value-at session 'chosen)))
          (is (= #{0} (set (mapcat #(map second (:literals %)) known))))
          (is (some #(= "reject alternative" (:key %)) rows))
          (is (true? (= network settled))
              (pr-str {:graph-unchanged (= (net/net-graph network) (net/net-graph settled))
                       :changed-env-entries
                       (count (filter (fn [[id value]] (not= value (get (net/net-env settled) id)))
                                      (net/net-env network)))}))
          (is (= "42" (:value (inspect editors "plain" "value"))))
          (is (= :bool4/nothing (value-at before 'knowledge)))))
      (finally (editor/close! editors)))))

(deftest exhausted-search-displays-the-empty-no-good
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "empty" :source (source "exhausted")
                            :extensions ["(-> (list 1 2) domain)"]})
      (let [session (:session (get @editors "empty"))]
        (is (= #{{:literals #{} :context #{}}} (value-at session 'knowledge)))
        (is (= :unsatisfiable (:action (value-at session 'repairs))))
        (is (some #(= "unsatisfiable under context" (:value %))
                  (:valueGrid (inspect editors "empty" "value")))))
      (finally (editor/close! editors)))))

(deftest existing-recorder-and-reducer-retain-published-learning-frames
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "replay" :source (source "replay")})
      (let [initial (get-in (inspect editors "replay" "graph") [:replay :frames])]
        (is (seq initial))
        (editor/extend! editors {:pane "replay" :generation (:generation (get @editors "replay"))
                                :source "(-> (list 1 2) domain)"})
        (let [session (:session (get @editors "replay"))
              network (:program/net @session)
              frames (get-in (inspect editors "replay" "graph") [:replay :frames])
              captured (history/field
                        (data/payload (net/network-cell-strongest network display/cell-id))
                        :replay/frames)]
          (is (> (count frames) (count initial)))
          (is (= initial (subvec frames 0 (count initial))))
          (is (some #(some (fn [row] (= "reject alternative" (:key row))) (:valueGrid %))
                    (mapcat #(get-in % [:graph :nodes]) frames)))
          (is (some #(seq (get-in % [:graph :contents])) captured))
          (is (= frames (get-in (inspect editors "replay" "graph") [:replay :frames])))
          (is (identical? network (:program/net @session)))))
      (finally (editor/close! editors)))))
