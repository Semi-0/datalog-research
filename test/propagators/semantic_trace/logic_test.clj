(ns propagators.semantic-trace.logic-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.relationship :as relationship]
            [propagators.cells.value :as value]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.facts :as facts]
            [propagators.semantic-trace.modifiers :as modifiers]
            [propagators.semantic-trace.logic :as logic]))

(deftest nearest-application-owns-one-level-through-arbitrary-wrappers
  (let [a [[:outer] :a] b [[:outer] :b] child [[:outer] :child]
        grandchild [[:outer] :grandchild] wrapper [[:outer] :wrapper]
        app-like-wrapper [[:outer] :looks-like-an-app]
        relationships (reduce (fn [r [parent node]] (relationship/relate r parent node)) {}
                              [[a wrapper] [wrapper child] [child app-like-wrapper] [app-like-wrapper grandchild]])
        apps (mapv #(hash-map :id % :eligible? true) [a b child grandchild])
        visible #(set (map first (:visible (facts/scope relationships apps % []))))]
    (is (= #{a b} (visible nil)))
    (is (= #{child} (visible a)))
    (is (= #{grandchild} (visible child)))
    (is (empty? (visible grandchild)))
    (is (= #{child} (visible wrapper)))
    (is (= #{grandchild} (visible app-like-wrapper)))))

(deftest closure-producer-follows-copies-but-static-operators-stay-implicit
  (let [edb {:visible #{[:factory] [:apply]}
             :output #{[:closure :factory] [:result :apply] [:hidden-value :hidden-call]}
             :copy-back #{[:binding :closure] [:alias :binding]}
             :operator-use #{[:static :factory] [:alias :apply]}
             :candidate-edge #{[:factory :bias :factory] [:factory :factory :closure]
                               [:apply :input :apply] [:apply :apply :result]
                               [:hidden-call :captured :hidden-call]}}
        derived (logic/run edb logic/projection-rules)]
    (is (contains? (:producer derived) [:alias :factory]))
    (is (contains? (:edge derived) [:alias :apply]))
    (is (not (contains? (:edge derived) [:static :factory])))
    (is (not (contains? (:edge derived) [:captured :hidden-call])))
    (is (not (contains? (:producer derived) [:hidden-value :hidden-call])))))

(deftest contraction-requires-a-single-named-destination
  (let [edb {:contractible-copy #{[:single :named] [:fanout :named] [:literal :named]}
             :primary-result #{[:single] [:fanout]}
             :unnamed #{[:single] [:fanout] [:literal]} :named #{[:named]}
             :sole-successor #{[:single :named] [:literal :named]}}]
    (is (= #{[:single :named]} (:alias (logic/run edb logic/contraction-rules))))))

(deftest leapfrog-term-interning-preserves-type-and-qualified-path
  (let [left [[:outer] :same] right [[:outer [:cell :nested]] :same]
        edb {:edge #{['x "x"] ["x" left] [left right]} :seed #{['x]}
             :adjacent #{['x "x"] ["x" 'x] ["x" left] [left "x"] [left right] [right left]}}
        result (logic/run edb logic/reachability-rules)]
    (is (= #{['x] ["x"] [left] [right]} (:reachable result)))
    (is (= (:edge edb) (:selected-edge result)))))

(deftest unsupported-predicate-occurrences-fail-explicitly
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"distinct predicate occurrences"
                       (logic/run {:edge #{[:a :b]}}
                                  [{:head [:path [:a :c]] :body [[:edge [:a :b]] [:edge [:b :c]]]}]))))

(deftest an-unversioned-empty-sample-cannot-erase-known-cell-information
  (doseq [known [2 nil false]]
    (let [empty-sample (trace/graph-union {:nodes {:cell 'cell} :values {:cell value/nothing}})
          known-sample (trace/graph-union {:nodes {:cell 'cell} :values {:cell known}})]
      (is (= known (get-in (trace/graph-union known-sample empty-sample) [:values :cell])))
      (is (= known (get-in (trace/graph-union empty-sample known-sample) [:values :cell])))))
  (is (value/nothing? (get-in (trace/graph-union {:values {:cell value/nothing}}) [:values :cell]))))

(deftest network-rules-retain-copy-propagators-and-cell-identities
  (let [edb {:visible #{[:call] [:route]}
             :output #{[:returned :call]}
             :candidate-edge #{[:call :input :call] [:call :call :returned]
                               [:route :named :route] [:route :route :result]}
             :copy-back #{[:named :returned]}
             :copy-node #{[:copy :returned :named]}}
        derived (logic/run edb logic/network-rules)]
    (is (= #{[:input :call] [:call :returned] [:returned :copy]
             [:copy :named] [:named :route] [:route :result]}
           (:edge derived)))
    (is (not (contains? (:edge derived) [:returned :named])))
    (is (empty? (:alias derived)))))

(deftest binding-elision-requires-a-private-source-and-sole-definition-consumer
  (let [edb {:definition #{[:private :bind :named] [:fanout :bind2 :other]
                           [:named-input :bind3 :alias] [:shared :bind4 :left] [:shared :bind5 :right]}
             :source-cell #{[:private] [:fanout] [:shared]}
             :sole-consumer #{[:private :bind] [:named-input :bind3]}}
        derived (logic/run edb logic/binding-rules)]
    (is (= #{[:private :named]} (:binding-alias derived)))))

(deftest inout-alias-requires-the-same-call-a-returned-member-and-one-consumer
  (let [edb {:member-output #{[:stats :sum-port] [:other :foreign-port] [:stats :output-only]
                             [:stats :fanout] [:stats :ordinary-port]}
             :return-copy #{[:stats :sum-port :copy :sum] [:stats :foreign-port :foreign-copy :sum]
                            [:stats :output-only :out-copy :out] [:stats :fanout :fan-copy :sum]
                            [:stats :ordinary-port :ordinary-copy :input]}
             :argument #{[:stats :sum]}
             :sole-consumer #{[:sum-port :copy] [:foreign-port :foreign-copy]
                              [:output-only :out-copy] [:ordinary-port :ordinary-copy]}}
        derived (logic/run edb logic/inout-port-rules)]
    (is (= #{[:sum-port :sum :copy]} (:port-alias derived)))))

(deftest reciprocal-rule-never-invents-the-reverse-of-a-one-way-edge
  (let [edb {:edge #{[:a :b] [:b :a] [:a :c]}
             :reverse-edge #{[:b :a] [:a :b] [:c :a]}}]
    (is (= #{[:a :b] [:b :a]} (:reciprocal (logic/run edb logic/reciprocal-edge-rules))))))

(deftest observer-ancestry-follows-the-producer-of-an-unowned-result
  (let [outer [[:outer] :outer] reader [[:outer] :reader]
        peer [[:outer] :peer] result [[:outer] :result]
        relationships (relationship/relate {} outer reader)
        apps [{:id outer :outputs [[[:outer] :history]]}
              {:id reader :outputs [result]}
              {:id peer :outputs [[[:outer] :value]]}]]
    (is (= #{outer reader} (facts/enclosing-applications relationships apps [result])))
    (is (empty? (facts/enclosing-applications relationships apps [])))
    (is (= #{peer} (facts/enclosing-applications relationships apps [[[:outer] :value]])))))

(deftest graph-merge-retains-all-observer-identities
  (let [a {:semantic-trace/graph true :dataflow/observer-outputs #{[[:outer] :a]}}
        b {:semantic-trace/graph true :dataflow/observer-outputs #{[[:outer] :b]}}]
    (is (= #{[[:outer] :a] [[:outer] :b]}
           (:dataflow/observer-outputs (trace/graph-union a b))))
    (is (= (trace/graph-union a b) (trace/graph-union b a)))))

(deftest caller-view-cuts-observation-calls-without-changing-the-original-graph
  (let [graph (trace/graph-union {:nodes {:root "input" :op "+" :value "result" :reader "reader" :history "history" :limit "limit"}
                                :edges [[:root :op] [:op :value] [:root :reader] [:limit :reader] [:reader :history]]
                                :dataflow/seeds #{:root} :dataflow/observer-applications #{:reader}})
        caller (modifiers/caller-view graph)]
    (is (= #{:root :op :value} (set (keys (:nodes caller)))))
    (is (= #{[:root :op] [:op :value]} (set (:edges caller))))
    (is (contains? (:nodes graph) :reader))
    (is (thrown? Exception (modifiers/caller-view {:semantic-trace/graph true})))))
