(ns graph.lain-editor.network-replay-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [graph.json :as json]
            [graph.lain-editor.inspection :as inspection]
            [graph.lain-editor.value-grid :as grid]
            [graph.lain-editor.structural-value :as structure]
            [graph.lain-editor.runtime :as editor]
            [graph.lain-editor.display :as pane-display]
            [propagators.experimental.visualization.data :as data]
            [propagators.network-builder :as builder]
            [propagators.ids :as ids]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.input :as input]
            [propagators.compiler-2.runtime.session.clock :as clock]
            [propagators.datastructures.event :as event]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.experimental.network-history :as history]
            [propagators.network :as net]))

(def display {:cell "@display" :mode "graph" :direction "both"})

(defn- inspect
  ([editors] (inspect editors "replay"))
  ([editors id]
   (inspection/inspect editors {:pane id :generation (:generation (get @editors id)) :display display})))

(defn- send-input! [editors version value]
  (let [session (:session (get @editors "replay"))
        state @session
        id (env/resolve-binding-id (:program/net state) (:program/env state) 'input)
        premise {:source id :timestamp version :premises-status :active}]
    (input/commit-runtime-input!
     session {:runtime/input :cell-message :cell-id id
              :update (membership/content {:base value :support #{premise} :membership #{}})})))

(defn- values-named [frames label]
  (into #{} (comp (mapcat #(get-in % [:graph :nodes]))
                 (filter #(= label (:label %))) (map :value)) frames))

(deftest real-lain-reducer-records-values-and-topology-without-rewriting-the-past
  (let [editors (atom {})]
    (try
      (is (= "graph" (:displayMode
                       (editor/run! editors {:pane "replay"
                         :source (slurp "examples/lain/network_replay/replay.lain")}))))
      (let [initial (get-in (inspect editors) [:replay :frames])]
        (is (seq initial))
        (send-input! editors 1 7)
        (let [first-update (get-in (inspect editors) [:replay :frames])]
          (is (> (count first-update) (count initial)))
          (is (= initial (subvec first-update 0 (count initial))))
          (is (some #(re-find #"8" %) (values-named first-update "result"))))
        (send-input! editors 2 9)
        (let [second-update (get-in (inspect editors) [:replay :frames])
              before-net (:program/net @(:session (get @editors "replay")))]
          (is (some #(re-find #"10" %) (values-named second-update "result")))
          (is (re-find #":timestamp 2" (:provenance (last second-update))))
          (is (= second-update (get-in (inspect editors) [:replay :frames])))
          (is (identical? before-net (:program/net @(:session (get @editors "replay")))))
          (editor/extend! editors {:pane "replay" :generation (:generation (get @editors "replay"))
                                  :source "(def-cells doubled)\n(-> (* input 2) doubled)"})
          (let [extended (get-in (inspect editors) [:replay :frames])]
            (is (> (count extended) (count second-update)))
            (is (= second-update (subvec extended 0 (count second-update))))
            (is (empty? (values-named second-update "doubled")))
            (is (seq (values-named extended "doubled")))
            (is (apply <= (map :elapsedMs extended)))
            (is (= (range (count extended)) (map :sequence extended))))))
      (finally (editor/close! editors)))))

(defn- shape [frame]
  (let [graph (:graph frame)
        labels (into {} (map #(vector (:id %) [(:kind %) (:label %)])) (:nodes graph))]
    {:nodes (set (vals labels))
     :edges (set (map #(vector (labels (:from %)) (labels (:to %))) (:edges graph)))}))

(deftest semantic-history-wrapper-keeps-the-caller-abstraction
  (let [editors (atom {})
        source (slurp "examples/lain/network_replay/replay.lain")
        direct (str/replace source "(collect-semantic-history input 0 60000)"
                 "(collect-history (relationship:with-values (relationship:roots-semantic input)) 0 60000)")
        nested (str/replace source "(display\n  (collect-semantic-history input 0 60000))"
                 "(define arbitrary-reader (network (x) (collect-semantic-history x 0 60000)))\n(display (arbitrary-reader input))")]
    (try
      (doseq [[id program] [["direct" direct] ["wrapped" source] ["nested" nested]]]
        (editor/run! editors {:pane id :source program}))
      (let [frames #(get-in (inspect editors %) [:replay :frames])
            expected (shape (last (frames "direct")))]
        (is (= #{["cell" "input"] ["cell" "result"] ["cell" "RESULT"]
                 ["cell" "CONSTANT"] ["propagator" "+"] ["propagator" "->"]} (:nodes expected)))
        (doseq [id ["wrapped" "nested"]]
          (is (= 1 (count (frames id))))
          (is (= expected (shape (last (frames id)))))))
      (finally (editor/close! editors)))))

(deftest clock-history-retains-original-ticks-and-provenance
  (let [pane (editor/prepare (slurp "examples/lain/network_replay/clock.lain") [])
        editors (atom {"clock" pane}) session (:session pane)]
    (try
      (is (= 1 (count (:clock/subscriptions @session))))
      (let [[id subscription] (first (:clock/subscriptions @session))]
        (doseq [time [1000 2000 3000]] (clock/tick-clock! session id (constantly time)))
        (let [frames (get-in (inspect editors "clock") [:replay :frames])
              network (:program/net @session)
              value (net/network-cell-strongest network
                      (env/resolve-binding-id network (:program/env @session) 'time))
              facts (event/projection-facts value)
              lines (fn [frame] (:valueLines (first (filter #(= "time" (:label %)) (get-in frame [:graph :nodes])))))]
          (is (= 4 (count frames)))
          (is (= #{"clock-in" "time" "CONSTANT"} (set (map :label (get-in (last frames) [:graph :nodes])))))
          (is (= [":base 1000" ":source-state :active" ":timestamp 1"] (lines (second frames))))
          (is (= [":base 3000" ":source-state :active" ":timestamp 3"] (lines (last frames))))
          (let [captured (history/field
                          (data/payload (net/network-cell-strongest network pane-display/cell-id))
                          :replay/frames)]
            (doseq [[recorded rendered] (map vector captured frames)
                    [id captured-value] (get-in recorded [:graph :values])]
              (let [node (first (filter #(= (pr-str id) (:id %)) (get-in rendered [:graph :nodes])))
                    local-id (ids/new-node-id)
                    local (builder/install-cell net/empty-net local-id captured-value captured-value)
                    expected (inspection/display-value local local-id)]
                (is (contains? (get-in recorded [:graph :contents]) id))
                (is (= (grid/rows (data/payload (get-in recorded [:graph :contents id]))
                                  (structure/snapshot-reader (:graph recorded) (first id)))
                       (:contentGrid node)))
                (is (= expected (:value node)))
                (is (= (str/split-lines expected) (:valueLines node)))
                (is (not (str/includes? (:value node) "propagators.network.Net"))))))
          (is (= #{(:clock/source-id subscription)} (set (map event/source facts))))
          (is (= #{(:clock/target-id subscription)} (set (map event/input-id facts))))
          (is (= #{3} (set (map event/timestamp facts))))
          (is (= frames (get-in (inspect editors "clock") [:replay :frames])))
          (is (identical? network (:program/net @session)))))
      (finally (editor/close! editors)))))

(deftest lain-fold-uses-current-callable-protocol-and-vector-order
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "replay"
        :source "(def-cells values)\n(define scale 10)\n(define append-digit
                   (network (acc next)
                     (+ (* acc scale) (p:slot :value next))))
                 (display (reduce append-digit 0 values))"})
      (let [session (:session (get @editors "replay"))
            state @session
            id (env/resolve-binding-id (:program/net state) (:program/env state) 'values)]
        (input/commit-runtime-input! session {:runtime/input :cell-message :cell-id id
                                              :update [1 2 3 4 5 6 7 8 9 0 1 2]}))
      (is (= "123456789012" (:value (inspect editors))))
      (finally (editor/close! editors)))))

(deftest recording-deduplicates-and-selects-inclusive-time-ranges
  (let [graph {:semantic-trace/graph true :nodes {} :edges #{}}
        first-recording (history/next-recording nil graph #{:original} 1000)
        unchanged (history/next-recording first-recording graph #{:other} 1001)
        changed (history/next-recording unchanged (assoc graph :nodes {:a "a"}) #{} 1020)
        backwards (history/next-recording changed (assoc graph :nodes {:b "b"}) #{} 1010)]
    (is (identical? first-recording unchanged))
    (is (= [0 20 20] (mapv :elapsedMs (:history/frames backwards))))
    (is (= #{:original} (:source-support (first (:history/frames backwards)))))
    (is (= [1 2] (mapv :sequence (history/between backwards 20 20))))
    (is (empty? (history/between backwards 1 19)))
    (doseq [[start end] [[-1 10] [2 1] [nil 1]]]
      (is (thrown? Exception (history/between backwards start end))))))

(deftest reducer-rejects-invalid-boundaries-and-keeps-the-empty-initial-value
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "replay"
        :source "(define step (network (acc next) acc))\n(display (reduce step 7 []))"})
      (is (= "7" (:value (inspect editors))))
      (doseq [[source message]
              [["(display (reduce (network (acc next) acc) 0 1))" #"finite vector"]
               ["(display (reduce (network (acc) acc) 0 []))" #"two inputs"]
               ["(history:record 7)" #"semantic tracer graph"]]]
        (is (thrown-with-msg? Exception message
                             (editor/run! editors {:pane "replay" :source source}))))
      (finally (editor/close! editors)))))

(deftest saved-workspace-loads-the-history-library
  (let [editors (atom {})
        document (json/read-json (slurp "examples/lain/network_replay/workspace.json"))
        id "network-replay"
        inspect-pane #(inspection/inspect editors
                        {:pane id :generation (:generation (get @editors id)) :display display})]
    (try
      (is (= "graph" (get-in (editor/restore! editors document) [:displayModes id])))
      (let [initial (get-in (inspect-pane) [:replay :frames])]
        (is (seq initial))
        (editor/extend! editors {:pane id :generation (:generation (get @editors id))
                                :source "(-> 7 input)"})
        (let [updated (get-in (inspect-pane) [:replay :frames])]
          (is (> (count updated) (count initial)))
          (is (= initial (subvec updated 0 (count initial))))
          (is (contains? (values-named [(last updated)] "result") "8"))))
      (finally (editor/close! editors)))))

(deftest rerunning-edited-extension-steps-rebuilds-the-history-in-order
  (let [editors (atom {}) source (slurp "examples/lain/network_replay/replay.lain")
        topology "(def-cells doubled) (-> (* input 2) doubled)"]
    (try
      (doseq [[initial expected] [[7 "14"] [9 "18"]]]
        (editor/run! editors {:pane "replay" :source source
                              :extensions [(str "(-> " initial " input)") topology]})
        (let [frames (get-in (inspect editors) [:replay :frames])
              last-nodes (get-in (last frames) [:graph :nodes])]
          (is (not-any? #(= "doubled" (:label %)) (get-in (first frames) [:graph :nodes])))
          (is (some #(and (= "doubled" (:label %)) (= expected (:value %))) last-nodes))
          (is (every? :valueLines (filter #(= "cell" (:kind %)) last-nodes)))))
      (let [before @editors]
        (is (thrown? Exception (editor/run! editors {:pane "replay" :source source :extensions ["("]})))
        (is (= before @editors)))
      (finally (editor/close! editors)))))
