(ns graph.lain-editor.tracer-gallery-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [graph.json :as json]
            [graph.lain-editor.display :as display]
            [graph.lain-editor.inspection :as inspection]
            [graph.lain-editor.runtime :as runtime]
            [graph.lain-editor.workspace :as workspace]
            [propagators.compiler-2.runtime.session.clock :as clock]
            [propagators.network :as net]))

(def directory "examples/lain/tracer_gallery/")

(def required-surface
  {"01-current-session" ["(trace result :both traced)"]
   "02-semantic" ["relationship:semantic" "relationship:with-values"]
   "03-roots-dataflow" ["relationship:roots" "relationship:dataflow"]
   "04-roots-semantic" ["relationship:roots-semantic"]
   "05-children" ["relationship:children"]
   "06-downstream" ["observe-dependents-retained"]
   "07-both" ["observe-semantic-retained" ":both"]
   "08-caller-view" ["relationship:caller-view" "relationship:with-values"]
   "09-replay" ["collect-semantic-history"]
   "10-live-observer" ["observe-semantic" ":upstream"]
   "11-clock-history" ["clock-in 500" "collect-semantic-history"]
   "12-call-graph" ["call-graph pipeline" "pipeline 4"]})

(defn- inspect-pane [editors pane]
  (inspection/inspect
   editors
   {:pane (:id pane)
    :generation (:generation (get @editors (:id pane)))
    :display (:display pane)}))

(deftest workspace-covers-and-runs-the-public-tracer-surfaces
  (let [document (json/read-json (slurp (str directory "workspace.json")))
        files (into #{}
                    (comp (filter #(.isFile %))
                          (map #(.getName %))
                          (filter #(str/ends-with? % ".lain")))
                    (.listFiles (io/file directory)))
        editors (atom {})]
    (is (= document (workspace/validate document)))
    (is (= (set (keys required-surface)) (set (map :id (:panes document)))))
    (is (= files (set (map :filename (:panes document)))))
    (doseq [{:keys [id filename source executedSource]} (:panes document)]
      (testing id
        (is (= source executedSource (slurp (str directory filename))))
        (doseq [required (get required-surface id)]
          (is (str/includes? source required)))))
    (try
      (is (= (set (keys required-surface))
             (set (keys (:generations (runtime/restore! editors document))))))
      (let [pane (get @editors "11-clock-history")
            session (:session pane)
            subscription-id (first (keys (:clock/subscriptions @session)))
            before (count (get-in (inspect-pane editors
                                                (some #(when (= "11-clock-history" (:id %)) %)
                                                      (:panes document)))
                                  [:replay :frames]))]
        (clock/stop-all! session)
        (is subscription-id)
        (clock/tick-clock! session subscription-id (constantly 4242))
        (is (< before
               (count (get-in (inspect-pane editors
                                            (some #(when (= "11-clock-history" (:id %)) %)
                                                  (:panes document)))
                              [:replay :frames])))))
      (doseq [pane (:panes document)]
        (testing (:id pane)
          (let [result (inspect-pane editors pane)]
            (is (= "ready" (:status result)))
            (if (contains? #{"09-replay" "11-clock-history"} (:id pane))
              (is (seq (get-in result [:replay :frames])))
              (is (seq (get-in result [:graph :nodes]))))
            (when (= "01-current-session" (:id pane))
              (is (= 1 (count (:trace/subscriptions
                               @(:session (get @editors (:id pane)))))))
              (is (some #(= "+" (:label %)) (get-in result [:graph :nodes]))))
            (when (= "05-children" (:id pane))
              (is (= "child" (get-in result [:graph :scope :kind]))))
            (when (= "12-call-graph" (:id pane))
              (let [network (:program/net
                             @(:session (get @editors (:id pane))))
                    graph (display/payload
                           (net/network-cell-strongest network display/cell-id))
                    statuses (set (keep :call/status (vals (:values graph))))]
                (is (= "callgraph" (get-in result [:graph :representation])))
                (is (= #{:potential :realized} statuses))
                (is (some #(= "shift" (:label %))
                          (get-in result [:graph :nodes])))
                (is (some #(= "square" (:label %))
                          (get-in result [:graph :nodes]))))))))
      (finally
        (runtime/close! editors)))))
