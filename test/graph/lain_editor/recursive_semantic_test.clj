(ns graph.lain-editor.recursive-semantic-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.runtime :as editor]
            [graph.lain-editor.inspection :as inspection]
            [graph.lain-editor.semantic-capture :as retained]
            [propagators.semantic-trace.capture :as capture]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(def source
  "(load-lain \"examples/lain/network_replay/collect-history.lain\")
   (define inner (network (x) (+ x 1)))
   (define outer (network (x) (inner (+ x 2))))
   (define input 5)
   (define computed (outer input))
   (display (collect-semantic-history computed 0 600000))")

(defn inspect [editors & [extra]]
  (inspection/inspect editors
                      (merge {:pane "recursive" :generation (:generation (get @editors "recursive"))
                              :display {:cell "@display" :mode "graph" :direction "both"}}
                             extra)))

(deftest captured-history-and-deep-refinement-use-the-original
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "recursive" :source source})
      (let [session (:session (get @editors "recursive"))
            frames (get-in (inspect editors) [:replay :frames])
            frame (last (filter #(some :semanticScope (get-in % [:graph :nodes])) frames))
            graph (:graph frame)
            node (first (filter :semanticScope (:nodes graph)))
            observation (:semanticObservation graph)
            child (with-redefs [inspection/semantic-graph
                                (fn [& _] (throw (ex-info "Historical expansion read live topology" {})))
                                inspection/describe
                                (fn [& _] (throw (ex-info "Historical expansion inspected live cell" {})))]
                    (:graph (inspect editors {:parent (:id node) :recordedObservation observation})))
            nested (first (filter :semanticScope (:nodes child)))]
        (is (seq frames))
        (is observation)
        (is (seq (:nodes child)))
        (is nested)
        (is (seq (:nodes (:graph (inspect editors {:parent (:id nested)
                                                  :recordedObservation observation})))))
        (let [after (get-in (inspect editors) [:replay :frames])]
          (is (> (count after) (count frames)))
          (is (= frames (subvec after 0 (count frames)))))
        (let [network (:program/net @session)
              tasks (keep (fn [[id p]] (when (prop/prop? p) id)) (net/net-env network))
              settled (runner/completed-network (runner/run-network tasks network))]
          (is (true? (= network settled))
              (pr-str {:changed-entries
                       (mapv (fn [[id entry]]
                               {:id id :name (:name entry)})
                             (filter (fn [[id entry]] (not= entry (get (net/net-env settled) id)))
                                     (net/net-env network)))
                       :environment-growth (- (count (net/net-env settled)) (count (net/net-env network)))})))
        (let [original-storage (retained/storage session)
              previous-sources @original-storage]
          (editor/extend! editors {:pane "recursive" :generation (:generation (get @editors "recursive"))
                                  :source "(define additional 42)"})
          (let [replacement (:session (get @editors "recursive"))
                storage (retained/storage replacement)]
            (is (not (identical? original-storage storage)))
            (is (empty? @original-storage))
            (is (every? #(contains? @storage %) (keys previous-sources)))
            (is (seq (:nodes (:graph (inspect editors {:parent (:id node)
                                                       :recordedObservation observation}))))))))
      (finally (editor/close! editors)))))

(deftest failed-candidate-cleans-up-retention-without-touching-the-active-session
  (let [editors (atom {}) candidate-storage (atom nil)]
    (try
      (editor/run! editors {:pane "recursive" :source source})
      (let [pane (get @editors "recursive")
            session (:session pane)
            network (:program/net @session)
            sources @(retained/storage session)
            load editor/load-checked!]
        (with-redefs [editor/load-checked!
                      (fn [candidate text]
                        (load candidate text)
                        (reset! candidate-storage (retained/storage candidate))
                        (throw (ex-info "Candidate rejected after acquisition" {})))]
          (is (thrown? Exception
                       (editor/extend! editors
                                       {:pane "recursive" :generation (:generation pane)
                                        :source "(define candidate-only (outer 9))
                                                 (collect-semantic-history candidate-only 0 600000)"}))))
        (is (identical? session (:session (get @editors "recursive"))))
        (is (identical? network (:program/net @session)))
        (is (= sources @(retained/storage session)))
        (is (some? @candidate-storage))
        (is (empty? @@candidate-storage)))
      (finally (editor/close! editors)))))
