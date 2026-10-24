(ns graph.lain-editor.inspection.amb-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.runtime.display :as display]
            [graph.lain-editor.inspection.core :as inspection]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.inspection.value-grid :as grid]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.application-ports :as ports]
            [propagators.gur :as gur]
            [propagators.graph :as graph]
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

(deftest amb-output-argument-retains-its-declared-direction
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "ports" :source (source "search")})
      (let [session (:session (get @editors "ports"))
            network (:program/net @session)
            resolve-id #(env/resolve-binding-id network (:program/env @session) %)
            decision (resolve-id 'decision)
            app (first (filter #(= decision (last (:argument-ids %)))
                               (application/application-topologies network)))
            interface (ports/application-ports network app)
            graph (:graph (inspection/inspect editors
                            {:pane "ports" :generation (:generation (get @editors "ports"))
                             :display {:cell "chosen" :mode "graph" :direction "both"}}))
            qualify #(pr-str [[:outer] %])
            call (qualify (gur/stable-node-id [(:application-id app) :apply-prop]))
            edges (set (map (juxt :from :to) (:edges graph)))
            prop-id (gur/stable-node-id [(:application-id app) :apply-prop])
            declared (get (net/net-graph network) prop-id)
            disconnected (net/net-with-graph network
                           (assoc (net/net-graph network) prop-id
                                  (graph/node (:inputs declared) (disj (:outputs declared) decision))))]
        (is (some? app))
        (is (= [(resolve-id 'domain) (resolve-id 'repairs)] (:inputs interface)))
        (is (= [(:result-id app) decision] (:outputs interface)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require graph connections"
                             (ports/application-ports disconnected app)))
        (is (contains? edges [call (qualify decision)]))
        (is (not (contains? edges [(qualify decision) call]))))
      (finally (editor/close! editors)))))

(deftest editor-pythagorean-search-finds-a-triangle-without-a-display-wrapper
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "triangle" :source (source "pythagorean")
                            :extensions ["(-> (list 3 4 5) domain)"]})
      (let [session (:session (get @editors "triangle"))]
        (is (= [3 4 5] (mapv #(value-at session %) '[a b c])))
        (is (true? (value-at session 'valid)))
        (is (= :idle (:action (value-at session 'repairs))))
        (is (seq (value-at session 'knowledge)))
        (let [frames (get-in (inspect editors "triangle" "graph") [:replay :frames])
              graph (:graph (last frames))
              calls (set (map :id (filter #(and (= "propagator" (:kind %)) (= "amb" (:label %)))
                                          (:nodes graph))))
              decisions (set (map :id (filter #(contains? #{"choice-a" "choice-b" "choice-c"} (:label %))
                                              (:nodes graph))))]
          (is (> (count frames) 1))
          (is (= 3 (count calls)))
          (is (= 3 (count decisions)))
          (is (= decisions (into #{} (keep #(when (and (calls (:from %)) (decisions (:to %)))
                                             (:to %))) (:edges graph)))))
        (is (= [3 4 5]
               (:items (observation/list-snapshot (:program/net @session)
                         (env/resolve-binding-id (:program/net @session)
                                                (:program/env @session) 'triangle))))))
      (finally (editor/close! editors)))))

(deftest learning-values-use-generic-structural-rows
  (let [choice (ids/new-node-id)
        context-source (ids/new-node-id)
        knowledge #{{:literals #{[choice 2]}
                     :context #{{:source context-source :timestamp 7 :premises-status :active}}}}
        value (view/learning-view knowledge)
        rows (grid/rows value)
        raw-rows (grid/rows knowledge)]
    (is (= knowledge (view/knowledge value)))
    (is (= value (view/learning-view knowledge)))
    (is (= rows (grid/rows value)))
    (is (some #(= "amb.view/knowledge" (:key %)) rows))
    (is (every? (set rows) (map #(update % :depth inc) raw-rows)))
    (is (some #(= "literals" (:key %)) raw-rows))
    (is (some #(= (grid/cell-token choice) (:value %)) raw-rows))
    (is (some #(= (grid/cell-token context-source) (:value %)) raw-rows))
    (is (some #(and (= "timestamp" (:key %)) (= "7" (:value %))) raw-rows))
    (is (some #(and (= "1" (:key %)) (= "2" (:value %))) raw-rows))
    (is (= [{:key "" :value "#{}" :depth 0}] (grid/rows #{})))
    (is (= [{:key "0" :value "" :depth 0}
            {:key "context" :value "#{}" :depth 1}
            {:key "literals" :value "#{}" :depth 1}]
           (grid/rows #{{:literals #{} :context #{}}})))
    (is (not-any? #(contains? #{"reject alternative" "learned no-goods" "forbidden combination"}
                             (:key %)) rows))
    (is (thrown? clojure.lang.ExceptionInfo (view/learning-view #{:invalid})))))

(deftest session-local-learning-display-waits-learns-and-survives-quiescent-replay
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "search" :source (source "search")})
      (editor/run! editors {:pane "plain" :source "(display 42)"})
      (is (= :bool4/nothing (value-at (:session (get @editors "search")) 'knowledge)))
      (is (not-any? #(= "literals" (:key %))
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
          (is (some #(= "literals" (:key %)) rows))
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
        (is (some #(and (= "literals" (:key %)) (= "#{}" (:value %)))
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
          (is (some #(some (fn [row] (= "literals" (:key row))) (:valueGrid %))
                    (mapcat #(get-in % [:graph :nodes]) frames)))
          (is (some #(seq (:contents (propagators.semantic-trace.capture/scope (:graph %) :root))) captured))
          (is (every? #(propagators.semantic-trace.capture/fragment? (:graph %)) captured))
          ;; This fixture records primitive AMB dataflow, not a compound expansion.
          ;; Node-level semanticScope is an optional nested-scope link.
          (is (every? #(= ":root" (get-in % [:graph :semanticScope])) frames))
          (is (every? #(seq (get-in % [:graph :semanticObservation])) frames))
          (is (some #(and (= "propagator" (:kind %)) (= "amb" (:label %)))
                    (mapcat #(get-in % [:graph :nodes]) frames)))
          (is (every? (fn [{:keys [graph]}]
                        (let [node-ids (set (map :id (:nodes graph)))]
                          (every? (fn [{:keys [from to]}]
                                    (and (contains? node-ids from)
                                         (contains? node-ids to)))
                                  (:edges graph))))
                      frames))
          (is (= 2 (value-at session 'chosen)))
          (is (true? (value-at session 'valid)))
          (is (= :idle (:action (value-at session 'repairs))))
          (is (= #{0} (set (mapcat #(map second (:literals %))
                                   (value-at session 'knowledge)))))
          (is (= frames (get-in (inspect editors "replay" "graph") [:replay :frames])))
          (is (identical? network (:program/net @session)))))
      (finally (editor/close! editors)))))
