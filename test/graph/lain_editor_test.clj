(ns graph.lain-editor-test
  (:require [clojure.test :refer [deftest is]]
            [graph.json :as json]
            [graph.lain-editor.inspection :as inspection]
            [graph.lain-editor.runtime :as editor]
            [graph.lain-editor.server :as server]
            [graph.lain-editor.workspace :as workspace]
            [propagators.network :as net]
            [propagators.compiler-2.model.env :as env]))

(defn pane [id source]
  {:id id :title id :filename "" :source source :executedSource source
   :extensions [] :extensionDraft "" :outputRatio 0.45
   :display {:cell "result" :mode "value" :direction "upstream"}})

(defn document [left right]
  {:format "lain-tiled-editor" :version 1 :panes [left right]
   :layout {:type "split" :axis "row" :ratio 0.6
            :first {:type "pane" :id (:id left)} :second {:type "pane" :id (:id right)}}})

(defn inspect-pane [editors id display]
  (inspection/inspect editors {:pane id :generation (:generation (get @editors id))
                              :display display}))

(def display {:cell "result" :mode "value" :direction "upstream"})

(deftest isolated-runs-and-atomic-failure
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "a" :source "(define result 11)"})
      (editor/run! editors {:pane "b" :source "(define result 22)"})
      (is (= "11" (:value (inspect-pane editors "a" display))))
      (is (= "22" (:value (inspect-pane editors "b" display))))
      (let [before @editors]
        (is (thrown? Exception (editor/run! editors {:pane "a" :source "(define result"})))
        (is (= before @editors)))
      (editor/run! editors {:pane "a" :source "(define result 33)"})
      (is (= "33" (:value (inspect-pane editors "a" display))))
      (is (= "22" (:value (inspect-pane editors "b" display))))
      (finally (editor/close! editors)))))

(deftest incremental-extension-preserves-existing-identities
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "a" :source "(define source) (define result (+ source 1))"})
      (let [before (get @editors "a")
            ids (set (keys (net/net-env (:program/net @(:session before)))))
            generation (:generation before)]
        (editor/extend! editors {:pane "a" :generation generation :source "(-> 10 source)"})
        (is (= "11" (:value (inspect-pane editors "a" display))))
        (is (every? (set (keys (net/net-env (:program/net @(:session (get @editors "a")))))) ids))
        (is (thrown-with-msg? Exception #"Pane changed"
                             (editor/extend! editors {:pane "a" :generation generation :source ""}))))
      (let [before @editors]
        (is (thrown? Exception
                     (editor/extend! editors {:pane "a" :generation (:generation (get before "a"))
                                              :source "(define scratch 8) (define)"})))
        (is (= before @editors)))
      (finally (editor/close! editors)))))

(deftest json-replay-and-workspace-rollback
  (let [editors (atom {})
        left (assoc (pane "a" "(define source) (define result (+ source 1))")
                    :extensions ["(-> 10 source)"] :source "unrun draft")
        saved (json/read-json (json/write-json (document left (pane "b" "(define result 22)"))))]
    (try
      (editor/restore! editors saved)
      (is (= "11" (:value (inspect-pane editors "a" display))))
      (is (= "22" (:value (inspect-pane editors "b" display))))
      (let [before @editors]
        (is (thrown? Exception (editor/restore! editors (assoc-in saved [:panes 1 :executedSource] "("))))
        (is (= before @editors))
        (is (thrown? Exception (workspace/validate (assoc-in saved [:layout :second :id] "a")))))
      (finally (editor/close! editors)))))

(deftest real-semantic-topology-and-child-inspection
  (let [editors (atom {})
        source "(define shift (network [x] (+ x 1)))
                (define square (network [x] (* x x)))
                (define stage (network [x] (square (shift x))))
                (define source 4) (define result (stage source))"]
    (try
      (editor/run! editors {:pane "a" :source source})
      (let [result (inspect-pane editors "a" (assoc display :mode "graph"))
            graph (:graph result)
            call (first (filter #(= "propagator" (:kind %)) (:nodes graph)))
            request {:pane "a" :generation (:generation result) :display display}
            child (:graph (inspection/inspect editors (assoc request :parent (:id call))))]
        (is (= "25" (:value result)))
        (is (= 1 (count (filter #(= "propagator" (:kind %)) (:nodes graph)))))
        (is (= "stage" (:label call)))
        (is (= #{"shift" "square"}
               (set (map :label (filter #(= "propagator" (:kind %)) (:nodes child))))))
        (is (= "propagator" (get-in (inspection/inspect editors (assoc request :node (:id call)))
                                    [:selected :kind])))
        (is (thrown? Exception (inspection/inspect editors (assoc request :node "invented")))))
      (finally (editor/close! editors)))))

(deftest http-assets-and-json-errors
  (let [service (server/start! 0)
        base (str "http://127.0.0.1:" (:port service))]
    (try
      (is (.contains (slurp base) "Tiled Lain editors"))
      (is (.contains (slurp base) "/compiled/app.js"))
      (is (.contains (slurp (str base "/compiled/app.js")) "javelin"))
      (is (thrown? Exception (server/command! (:editors service) {:op "unknown"})))
      (finally ((:close service))))))

(deftest compound-display-is-a-read-only-projection
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "a" :source "(define result (list 1 2 3))"})
      (let [session (:session (get @editors "a"))
            before (:program/net @session)]
        (is (= "[1 2 3]" (:value (inspect-pane editors "a" display))))
        (is (identical? before (:program/net @session))))
      (finally (editor/close! editors)))))

(deftest program-display-is-live-local-and-expression-shaped
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "a" :source "(def-cells input) (define returned (display (+ input 1)))"})
      (editor/run! editors {:pane "b" :source "(display 22)"})
      (is (= "waiting" (:status (inspect-pane editors "a" (assoc display :cell "@display")))))
      (is (= "22" (:value (inspect-pane editors "b" (assoc display :cell "@display")))))
      (let [generation (:generation (get @editors "a"))
            reply (editor/extend! editors {:pane "a" :generation generation :source "(-> 5 input)"})]
        (is (= "value" (:displayMode reply)))
        (is (= "6" (:value (inspect-pane editors "a" (assoc display :cell "@display")))))
        (let [state @(:session (get @editors "a"))
              network (:program/net state)
              id (env/resolve-binding-id network (:program/env state) 'returned)]
          (is (= 6 (net/network-cell-strongest network id))))
        (is (= "22" (:value (inspect-pane editors "b" (assoc display :cell "@display"))))))
      (let [before @editors]
        (is (thrown? Exception (editor/run! editors {:pane "a" :source "(display 99) (define)"})))
        (is (= before @editors))
        (is (= "6" (:value (inspect-pane editors "a" (assoc display :cell "@display"))))) )
      (doseq [source ["(display)" "(display 1 2)"]]
        (is (thrown? Exception (editor/run! editors {:pane "a" :source source}))))
      (finally (editor/close! editors)))))

(deftest program-display-selects-semantic-topology-and-replays
  (let [editors (atom {})
        source "(def-cells a b raw) (-> 3 a) (-> (+ a 4) b)
                (relationship:roots a raw) (display (relationship:dataflow raw))"
        saved (document (pane "a" source) (pane "b" "(display 22)"))]
    (try
      (is (= "graph" (:displayMode (editor/run! editors {:pane "a" :source source}))))
      (let [result (inspect-pane editors "a" (assoc display :cell "@display" :mode "graph"))]
        (is (contains? (set (map :label (get-in result [:graph :nodes]))) "+"))
        (is (seq (get-in result [:graph :edges]))))
      (is (= {"a" "graph" "b" "value"} (:displayModes (editor/restore! editors saved))))
      (is (= "22" (:value (inspect-pane editors "b" (assoc display :cell "@display")))))
      (is (seq (get-in (inspect-pane editors "a" (assoc display :cell "@display" :mode "graph")) [:graph :edges])))
      (finally (editor/close! editors)))))

(deftest display-is-callable-inside-networks-and-can-be-inspected-explicitly
  (let [editors (atom {})]
    (try
      (doseq [source ["(define result 7) ((network [x] (display x)) result)"
                      "(define result 7) ((network [show x] (show x)) display result)"]]
        (editor/run! editors {:pane "a" :source source})
        (is (= "7" (:value (inspect-pane editors "a" (assoc display :cell "@display")))))
        (is (= "7" (:value (inspect-pane editors "a" display)))))
      (finally (editor/close! editors)))))
