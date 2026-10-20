(ns graph.lain-editor.stack-scene-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.inspection :as inspection]
            [graph.lain-editor.runtime :as editor]
            [graph.lain-editor.stack-primitives :as stack]
            [graph.lain-editor.stack-scene :as scene]
            [propagators.datastructures.compound-object :as object]))

(defn node [identity content children]
  (object/as-accessor-network
   {:type :stack :identity identity :content content
    :children (stack/linked-value children)}))

(deftest spatial-scene-preserves-planes-hierarchy-and-references
  (let [reference (object/as-accessor-network {:type :stack-ref :identity :child})
        root (node :root {:nodes {} :edges []}
                   [(node :child {:nodes {} :edges []} []) reference])
        compiled (scene/compile-scene root)]
    (is (= :root (:root compiled)))
    (is (= [[:root 0 0] [:child 1 1]]
           (mapv (juxt :identity :depth :order) (:planes compiled))))
    (is (= [{:from :root :to :child :kind :child}
            {:from :root :to :child :kind :reference}]
           (:edges compiled)))
    (is (empty? (:diagnostics compiled)))
    (let [portable (inspection/stack-scene-json root)]
      (is (= ":root" (:root portable)))
      (is (= [":root" ":child"] (mapv :identity (:planes portable))))
      (is (= ["child" "reference"] (mapv :kind (:edges portable)))))))

(deftest graph-inspection-selects-stack-scene-without-semantic-rescanning
  (let [editors (atom {})
        source (str "(def-cells s0 work s1 owned s2 s3 s4 finished result) "
                    "(stack:begin-traversal :observation :root s0) "
                    "(stack:take-pending s0 work s1) "
                    "(stack:register-occurrence s1 work :root "
                    "  {:semantic-trace/graph true :nodes {} :edges []} owned s2) "
                    "(stack:connect-child-outputs s2 :root (list) s3) "
                    "(stack:enqueue-child-work s3 :root (list) s4) "
                    "(stack:finish-traversal s4 finished) "
                    "(stack:traversal-result finished result)")]
    (try
      (let [run (editor/run! editors {:pane "stack" :source source})
            result (inspection/inspect
                    editors {:pane "stack" :generation (:generation run)
                             :display {:cell "result" :mode "graph"
                                       :direction "both"}})]
        (is (= ":root" (get-in result [:stackScene :root])))
        (is (= 1 (count (get-in result [:stackScene :planes]))))
        (is (nil? (:graph result))))
      (finally
        (editor/close! editors)))))
