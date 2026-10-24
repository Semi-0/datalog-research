(ns graph.lain-editor.inspection.semantic-scene-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.inspection.semantic-capture :as retained]
            [graph.lain-editor.inspection.semantic-scene :as scene]
            [propagators.cells.value :as value]
            [propagators.semantic-trace.capture :as capture]))

(def graph
  {:semantic-trace/graph true
   :nodes {:a "A" :b "B" :c "C"}
   :node-kinds {:a :cell :b :propagator :c :cell}
   :values {:a 1 :b value/nothing :c 1}
   :edges [[:a :b] [:b :c] [:c :b]]
   :semantic-capture/nested-scopes {:b :body}})

(deftest compiler-preserves-cycles-shared-values-and-scope-identities
  (let [captured (capture/fragment
                  {:observation :observation
                   :scopes {:root graph :body (assoc graph :edges [[:a :b]])}})
        root (scene/compile-scene captured)
        body (scene/compile-scene captured :body)]
    (is (= [[:a :b] [:b :c] [:c :b]] (:scene/edges root)))
    (is (= 1 (:value (first (:scene/nodes root)))))
    (is (= 1 (:value (last (:scene/nodes root)))))
    (is (= :body (:nested-scope (second (:scene/nodes root)))))
    (is (= :body (:scene/scope body)))
    (is (false? (:scene/partial? root)))))

(deftest legacy-and-missing-scopes-are-explicitly-partial
  (is (:scene/partial? (scene/compile-scene graph)))
  (let [captured (capture/fragment {:observation :observation :scopes {:root graph}})
        missing (scene/compile-scene captured :unknown)]
    (is (:scene/partial? missing))
    (is (= :missing (get-in missing [:scene/diagnostics 0 :kind])))))

(deftest preview-and-detail-share-one-layout-contract
  (let [compiled (scene/compile-scene
                  (capture/fragment {:observation :observation
                                     :scopes {:root graph}}))
        request (scene/layout-request compiled {:a [60 40] :b [44 44] :c [60 40]})
        preview (scene/scale-preview {:a [-100 -100] :b [0 0] :c [100 100]})]
    (is (not (contains? request :algorithm)))
    (is (not (contains? request :clearance)))
    (is (= (:scene/edges compiled) (:edges request)))
    (is (every? (fn [[_ [x y]]]
                  (and (<= 0 x 200) (<= 0 y 120)))
                preview))))

(deftest refinement-is-immutable-and-closing-removes-the-source
  (let [source (reify capture/SemanticCaptureSource
                 (capture-fragment [_ request]
                   (capture/fragment
                    {:observation :observation
                     :scopes {(:scope request) graph}
                     :provenance [{:capture/refinement (:refinement request)}]})))
        session (atom {})
        initial (capture/fragment
                 {:observation :observation :scopes {:root graph}})]
    (retained/retain! session :observation source)
    (let [refined (retained/refine session initial {:scope :body})]
      (is (nil? (capture/scope initial :body)))
      (is (= graph (capture/scope refined :body))))
    (retained/close! session)
    (let [unavailable (retained/refine session initial {:scope :body})]
      (is (= :missing
             (get-in (capture/scope unavailable :body)
                     [:semantic-capture/diagnostics 0 :kind]))))))
