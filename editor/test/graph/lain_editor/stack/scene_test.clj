(ns graph.lain-editor.stack.scene-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.inspection.core :as inspection]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.stack.primitives :as stack]
            [graph.lain-editor.stack.scene :as scene]
            [propagators.datastructures.compound-object :as object]))

(defn expansion [source target]
  (object/as-accessor-network
   {:type :stack-expansion :source source :target target}))

(defn node [identity graph expansions]
  (object/as-accessor-network
   {:type :stack :identity identity :graph graph
    :expansions (stack/linked-value expansions)}))

(deftest spatial-scene-preserves-planes-hierarchy-and-references
  (let [reference (object/as-accessor-network {:type :stack-ref :identity :child})
        root (node :root {:nodes {} :edges []}
                   [(expansion :child-call
                               (node :child {:nodes {} :edges []} []))
                    (expansion :shared-call reference)])
        compiled (scene/compile-scene root)]
    (is (= :root (:root compiled)))
    (is (= [[:root 0 0] [:child 1 1]]
           (mapv (juxt :identity :depth :order) (:planes compiled))))
    (is (= [{:from :root :source-node :child-call
             :to :child :kind :expansion}
            {:from :root :source-node :shared-call
             :to :child :kind :reference}]
           (:edges compiled)))
    (is (empty? (:diagnostics compiled)))
    (let [portable (inspection/stack-scene-json root)]
      (is (= ":root" (:root portable)))
      (is (= [":root" ":child"] (mapv :identity (:planes portable))))
      (is (= [":child-call" ":shared-call"]
             (mapv :source-node (:edges portable))))
      (is (= ["expansion" "reference"] (mapv :kind (:edges portable)))))))

(deftest graph-inspection-selects-stack-scene-without-semantic-rescanning
  (let [editors (atom {})
        source (str "(def-cells s0 step work s1 s2 s3 s4 finished result) "
                    "(stack:begin-traversal :observation :root s0) "
                    "(stack:take-pending s0 step) "
                    "(stack:pending-work step work) "
                    "(stack:pending-remaining step s1) "
                    "(stack:register-occurrence s1 work :root "
                    "  {:semantic-trace/graph true :nodes {} :edges []} s2) "
                    "(stack:connect-expansion-outputs s2 :root (list) s3) "
                    "(stack:enqueue-expansion-work s3 :root (list) s4) "
                    "(stack:finish-traversal s4 finished) "
                    "(stack:traversal-result finished result) "
                    "(display result)")]
    (try
      (let [run (editor/run! editors {:pane "stack" :source source})
            result (inspection/inspect
                    editors {:pane "stack" :generation (:generation run)
                             :display {:cell "result" :mode "graph"
                                       :direction "both"}})]
        (is (= "graph" (:displayMode run))
            "an explicitly marked stack selects the spatial renderer")
        (is (= ":root" (get-in result [:stackScene :root])))
        (is (= 1 (count (get-in result [:stackScene :planes]))))
        (is (nil? (:graph result))))
      (finally
        (editor/close! editors)))))
