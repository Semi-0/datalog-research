(ns graph.lain-editor.inspection.high-level-value-history-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.inspection.core :as inspection]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.inspection.structural-value :as structure]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.datastructures.compound-object :as object]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.experimental.network-history :as history]
            [propagators.experimental.visualization.data :as data]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as builder]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.high-level :as high-level]
            [propagators.semantic-trace.modifiers :as modifiers]))

(defn- fixture []
  (let [[user framework runtime status pending conflict alias]
        (repeatedly 7 ids/new-node-id)
        owner (-> (object/empty-accessor-network)
                  (object/register-accessor-parent :status status)
                  (object/register-accessor-parent :pending pending)
                  (object/register-accessor-parent :conflict conflict))
        keys (mapv #(vector [:outer] %) [user framework runtime])
        premise (fn [revision] {:source status :timestamp revision :premises-status :active})
        captured (fn [revision]
                   (let [supported (membership/strongest-value
                                    (membership/content
                                      {:base revision :support #{(premise revision)} :membership #{}})
                                    (fn [_ candidate] candidate) identity)
                         contradiction (value/contradiction-with-provenance #{{:reason :fixture}})
                         network (reduce (fn [network [id candidate]]
                                           (builder/install-cell network id candidate candidate))
                                         net/empty-net
                                         [[user owner] [framework 20] [runtime 30]
                                          [status supported] [pending value/nothing]
                                          [conflict contradiction]])
                         graph {:semantic-trace/graph true
                                :nodes (zipmap keys ["user" "evaluation-framework" "runtime"])
                                :node-kinds (zipmap keys (repeat :cell))
                                :values (zipmap keys [owner 20 30])
                                :edges [[(first keys) (second keys)]
                                        [(second keys) (last keys)]]}
                         source (observation/frozen-snapshot network {:observation/id revision})]
                     (capture/capture-fragment source {:graph graph :observation revision})))]
    {:keys keys :status status :pending pending :conflict conflict :alias alias
     :premise premise :capture captured}))

(defn- project [captured]
  (-> (high-level/view captured ["user" "evaluation-framework" "runtime"])
      modifiers/with-values))

(defn- status-row [graph]
  (let [rows (->> (:nodes (inspection/graph-json graph))
                  (filter #(= "user" (:label %))) first :valueGrid)
        field-rows (drop-while #(not= "status" (:key %)) rows)
        header (first field-rows)]
    ;; The existing visualizer exposes supported datums as base/support rows.
    (if (seq (:value header))
      header
      (->> (rest field-rows)
           (take-while #(> (:depth %) (:depth header)))
           (filter #(= "base" (:key %))) first))))

(deftest projection-preserves-captured-public-field-state-without-visible-field-nodes
  (let [{:keys [keys status pending conflict premise] take-capture :capture} (fixture)
        original (take-capture 7)
        projected (project original)
        graph (capture/root-graph projected)
        reader (structure/snapshot-reader graph [:outer])]
    (is (= (set keys) (set (clojure.core/keys (:nodes graph)))))
    (is (= 3 (count (:nodes (inspection/graph-json projected)))))
    (is (= "7" (:value (status-row projected))))
    (is (= 7 (data/payload (:value (reader status)))))
    (is (= #{(premise 7)} (datum/support-of (:value (reader status)))))
    (is (true? (:found? (reader pending))))
    (is (value/nothing? (:value (reader pending))))
    (is (true? (:found? (reader conflict))))
    (is (value/contradiction? (:value (reader conflict))))
    (is (= (capture/observation-id original) (capture/observation-id projected)))
    (is (= (capture/support original) (capture/support projected)))))

(deftest projection-preserves-frozen-reference-aliases
  (let [{:keys [status alias] take-capture :capture} (fixture)
        captured (capture/update-scope (take-capture 8) :root
                   #(assoc % :node-aliases {[[:outer] alias] #{[[:outer] status]}}))
        graph (capture/root-graph (project captured))
        reader (structure/snapshot-reader graph [:outer])]
    (is (true? (:found? (reader alias))))
    (is (= 8 (data/payload (:value (reader alias)))))
    (is (= 3 (count (:nodes graph))))))

(deftest main-composition-renders-public-field-values-through-the-unchanged-replay-consumer
  (let [editors (atom {}) pane "main-fields"]
    (try
      (editor/run! editors {:pane pane :source (slurp "examples/lain/subagents/composition.lain")})
      (let [result (inspection/inspect editors
                     {:pane pane :generation (:generation (get @editors pane))
                      :display {:cell "@display" :mode "graph" :direction "both"}})
            frames (get-in result [:replay :frames])
            nodes (get-in (last frames) [:graph :nodes])
            rows (mapcat :valueGrid nodes)]
        (is (seq frames))
        (is (<= (count frames) 10))
        (is (= #{"user" "evaluation-framework" "runtime"} (set (map :label nodes))))
        (is (seq rows))
        (doseq [row rows]
          (is (not= "unavailable" (:value row)) (str "Missing captured field " (:key row)))))
      (finally (editor/close! editors)))))

(deftest accumulator-retains-frozen-field-values-while-rendering-only-three-components
  (let [{take-capture :capture} (fixture)
        advance (fn [recording revision]
                  (history/next-bounded-recording recording
                    (project (take-capture revision)) #{} (+ 1000 revision) 10))
        first-recording (advance nil 0)
        first-frame (first (history/field first-recording :history/frames))
        first-rendered (inspection/graph-json (:graph first-frame))
        recording (reduce advance first-recording (range 1 15))
        frames (history/field recording :history/frames)]
    (is (= 10 (count frames)))
    (is (= (range 5 15) (map :sequence frames)))
    (is (= (map str (range 5 15)) (map #(-> % :graph status-row :value) frames)))
    (is (every? #(= 3 (count (:nodes (inspection/graph-json (:graph %))))) frames))
    (is (= "0" (:value (status-row (:graph first-frame)))))
    (is (= first-rendered (inspection/graph-json (:graph first-frame))))))
