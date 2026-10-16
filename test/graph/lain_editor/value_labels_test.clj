(ns graph.lain-editor.value-labels-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [graph.lain-editor.value-labels :as labels]
            [graph.lain-editor.value-grid :as grid]
            [graph.lain-editor.structural-value :as structure]
            [graph.lain-editor.runtime :as runtime]
            [graph.lain-editor.inspection :as inspection]
            [graph.lain-editor.display :as display]
            [propagators.cells.value :as value]
            [propagators.datastructures.event :as event]
            [propagators.compiler-2.runtime.session.input :as input]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.operators.graph-modifiers :as graph-modifiers]
            [propagators.experimental.visualization.ttms :as ttms]
            [propagators.network-builder :as builder]
            [propagators.ids :as ids]
            [propagators.datastructures.compound-object :as object]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.semantic-trace :as trace]
            [propagators.relationship-observer :as observer]
            [propagators.semantic-trace.modifiers :as modifiers]))

(defn request [editors]
  {:pane "a" :generation (:generation (get @editors "a"))
   :display {:cell "@display" :mode "graph" :direction "both"}})

(deftest scalar-and-every-public-layer-are-readable-without-network-internals
  (doseq [[v expected] [[7 ["7"]] [false ["false"]] [nil ["nil"]]
                        [value/nothing ["the-nothing"]] [value/contradiction ["the-contradiction"]]]]
    (is (= expected (labels/lines v))))
  (let [layers {:base 7 :unit "ms" :quality :measured}
        expected [":base 7" ":quality :measured" ":unit \"ms\""]]
    (is (= expected (labels/lines layers)))
    (is (= expected (labels/lines (object/compound-object layers))))
    (is (= expected (labels/lines (object/as-accessor-network layers)))))
  (is (= [":base nil" ":unit nil"] (labels/lines (object/compound-object {:base nil :unit nil}))))
  (doseq [[v expected] [[value/nothing ":base the-nothing"]
                        [value/contradiction ":base the-contradiction"]]]
    (is (= [expected] (labels/lines (object/compound-object {:base v})))))
  (let [text (str/join " " (labels/lines (object/compound-object {:base {:nested false} :source #{:a :b}})))]
    (is (str/includes? text ":nested false"))
    (is (not (str/includes? text "propagators.network.Net")))))

(deftest modifier-is-idempotent-and-preserves-topology-payloads-and-scope
  (let [graph (assoc (trace/graph-union {:nodes {:a 'a :p '+} :node-kinds {:a :cell :p :propagator}
                                        :values {:a false} :edges [[:a :p]]}) :dataflow/scope :root)
        decorated (modifiers/with-values graph)]
    (is (= graph (dissoc decorated :graph/modifiers)))
    (is (= decorated (modifiers/with-values decorated)))
    (is (identical? (:values graph) (:values decorated))))
  (let [graph (modifiers/with-values (trace/graph-union {:nodes {:a 'a} :edges []}))]
    (is (= #{:cell-values} (:graph/modifiers (trace/graph-union graph))))
    (is (= #{:cell-values} (:graph/modifiers (trace/trace-graph graph {:node :a :direction :both}))))
    (is (= #{:cell-values} (:graph/modifiers (modifiers/inherit (trace/graph-union {}) graph)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expects a semantic graph" (modifiers/with-values 7))))

(deftest composed-operator-displays-values-without-leaking-instrumentation
  (let [editors (atom {})]
    (try
      (doseq [filename ["values.lain" "layers.lain"]]
        (runtime/run! editors {:pane "a" :source (slurp (str "examples/lain/value_tracing/" filename))})
        (let [network (:program/net @(:session (get @editors "a")))
              graph (:graph (inspection/inspect editors (request editors)))
              cells (filter #(= "cell" (:kind %)) (:nodes graph))
              props (filter #(= "propagator" (:kind %)) (:nodes graph))]
          (is (every? #(seq (:valueLines %)) cells))
          (is (not-any? #(contains? % :valueLines) props))
          (is (= (if (= filename "values.lain") #{"+" "->"} #{"p:slot" "->"})
                 (set (map :label props))))
          (is (= #{:cell-values} (:graph/modifiers (data/payload (net/network-cell-strongest network display/cell-id)))))
          (is (identical? network (:program/net @(:session (get @editors "a")))))))
      (finally (runtime/close! editors)))))

(deftest higher-order-modifier-and-child-view-inherit-values
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source
        "(define decorate relationship:with-values)
         (define combine (network [a b] (+ a b)))
         (define input 3)
         (def-cells result)
         (-> (combine input 4) result)
         (display (decorate (relationship:roots-semantic input)))"})
      (let [req (request editors) graph (:graph (inspection/inspect editors req))
            call (first (filter #(= "combine" (:label %)) (:nodes graph)))
            child (:graph (inspection/inspect editors (assoc req :parent (:id call))))]
        (is (every? :valueLines (filter #(= "cell" (:kind %)) (:nodes child))))
        (is (some #(and (= "cell" (:kind %)) (= ["7"] (:valueLines %))) (:nodes graph)))
        (is (not-any? #(= "decorate" (:label %)) (:nodes graph))))
      (finally (runtime/close! editors)))))

(deftest late-input-updates-the-existing-visible-cell
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source (slurp "examples/lain/value_tracing/pending.lain")})
      (let [before (:graph (inspection/inspect editors (request editors)))
            input (first (filter #(= "input" (:label %)) (:nodes before)))]
        (is (= ["the-nothing"] (:valueLines input)))
        (let [session (:session (get @editors "a"))
              id (env/resolve-binding-id (:program/net @session) (:program/env @session) 'input)]
          (input/commit-runtime-input! session {:runtime/input :cell-message :cell-id id :update 7}))
        (let [after (:graph (inspection/inspect editors (request editors)))
              updated (first (filter #(= (:id input) (:id %)) (:nodes after)))]
          (is (= ["7"] (:valueLines updated)))
          (is (some #(and (= "result" (:label %)) (= ["8"] (:valueLines %))) (:nodes after)))
          (is (= (set (map :id (:nodes before))) (set (map :id (:nodes after)))))))
      (finally (runtime/close! editors)))))

(deftest modifier-operator-handles-pending-invalid-and-supported-inputs
  (let [[source output] (repeatedly 2 ids/new-node-id)
        activate (operator/operator-activate (graph-modifiers/with-values-operator))
        empty-network (builder/install-cells [source output])
        graph (trace/graph-union {:nodes {:a 'a} :node-kinds {:a :cell} :values {:a 7} :edges []})]
    (is (empty? (activate empty-network nil [source] output)))
    (doseq [arguments [[] [source source]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expects one graph"
                           (activate empty-network nil arguments output))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expects a semantic graph"
                         (activate (builder/seed-cell empty-network source 7) nil [source] output)))
    (let [supported (ttms/publication graph [])
          network (builder/seed-cell empty-network source supported)
          update (:value (first (activate network nil [source] output)))]
      (is (ttms/supported? update))
      (is (= #{:cell-values} (:graph/modifiers (data/payload update))))
      (is (= (:nodes graph) (:nodes (data/payload update)))))))

(deftest nested-container-previews-and-tooltips-never-print-runtime-network-records
  (let [network (object/compound-object {:base 7 :unit "ms"})
        values [{:tick network} [network] #{network}
                (object/compound-object {:base {:tick network}})]]
    (doseq [v values]
      (let [text (str/join " " (labels/lines v))
            graph (trace/graph-union {:nodes {:time 'time} :node-kinds {:time :cell}
                                      :values {:time v} :edges []})
            output (inspection/graph-json graph)]
        (is (not (str/includes? text "propagators.network.Net")))
        (is (not (str/includes? text ":graph")))
        (is (not (str/includes? (get-in output [:nodes 0 :value]) "propagators.network.Net")))
        (is (= v (get-in graph [:values :time])))))))

(deftest direct-display-uses-the-public-layer-formatter
  (let [id (ids/new-node-id)
        layers {:base 7 :unit "ms" :quality :measured}]
    (doseq [v [layers (object/compound-object layers) (object/as-accessor-network layers)]]
      (let [network (builder/install-cell net/empty-net id v v)
            result (inspection/display-value network id)]
        (is (= ":base 7\n:quality :measured\n:unit \"ms\"" result))
        (is (not (str/includes? result "propagators.network.Net")))
        (is (= v (net/network-cell-strongest network id)))))
    (doseq [[v expected] [[false "false"] [nil "nil"]
                          [value/nothing "the-nothing"] [value/contradiction "the-contradiction"]]]
      (is (= expected (inspection/display-value (builder/install-cell net/empty-net id v v) id)))))
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source "(display {:base 7 :unit \"ms\" :quality :measured})"})
      (is (= ":base 7\n:quality :measured\n:unit \"ms\""
             (:value (inspection/inspect editors (assoc (request editors) :display {:cell "@display" :mode "value"})))))
      (runtime/run! editors {:pane "a" :source "(display (list 1 2 3))"})
      (is (= "[1 2 3]" (:value (inspection/inspect editors
                                   (assoc (request editors) :display {:cell "@display" :mode "value"})))))
      (finally (runtime/close! editors)))))

(deftest saved-layered-frames-use-the-direct-renderer-without-changing-recorded-values
  (let [v (object/compound-object {:base 7 :unit "ms" :quality :measured})
        graph (modifiers/with-values
               (trace/graph-union {:nodes {:sample 'sample} :node-kinds {:sample :cell}
                                   :values {:sample v} :edges []}))
        replay {:replay/type :semantic
                :replay/frames [{:sequence 0 :time 100 :elapsedMs 0 :graph graph
                                 :source-support #{:original}}]}
        node (get-in (inspection/replay-json replay) [:frames 0 :graph :nodes 0])
        id (ids/new-node-id)
        direct (inspection/display-value (builder/install-cell net/empty-net id v v) id)]
    (is (= direct (:value node)))
    (is (= (str/split-lines direct) (:valueLines node)))
    (is (identical? v (get-in replay [:replay/frames 0 :graph :values :sample])))
    (is (= #{:original} (get-in replay [:replay/frames 0 :source-support])))))

(deftest derived-event-layers-use-the-same-readable-display-everywhere
  (let [source (ids/new-node-id)
        tick (event/strongest-value (event/active-event source [:clock/source source] 3 1000))
        product (event/strongest-value (event/lift :product * [20 tick] [20 tick]))
        result (event/strongest-value (event/lift :sum + [10 product] [10 product]))]
    (doseq [[v base] [[tick 1000] [product 20000] [result 20010]]]
      (let [id (ids/new-node-id)
            network (builder/install-cell net/empty-net id v v)
            expected (inspection/display-value network id)
            graph (modifiers/with-values
                    (trace/graph-union {:nodes {:a 'a} :node-kinds {:a :cell}
                                        :values {:a v} :edges []}))
            node (first (:nodes (inspection/graph-json graph)))
            replay {:replay/frames [{:graph graph}]}]
        (is (str/starts-with? expected (str ":base " base "\n")))
        (is (str/includes? expected ":timestamp"))
        (is (str/includes? expected ":source-state :active"))
        (is (not (str/includes? expected "#propagators.ids.NodeId")))
        (is (= expected (:value node)))
        (is (= expected (get-in (inspection/replay-json replay) [:frames 0 :graph :nodes 0 :value])))
        (is (= (str/split-lines expected) (:valueLines node)))
        (is (identical? v (net/network-cell-strongest network id)))))))

(deftest reference-circles-resolve-through-semantic-binding-aliases
  (let [source (ids/new-node-id) visible (ids/new-node-id)
        source-key [[:outer] source] visible-key [[:outer] visible]
        graph {:nodes {visible-key 'time} :node-kinds {visible-key :cell}
               :node-aliases {source-key #{visible-key}} :values {} :edges []}
        node (first (:nodes (inspection/graph-json graph)))]
    (is (= #{(str "cell/" (:uuid source))
             (str "cell/" (:uuid visible))}
           (set (:referenceTokens node))))
    (is (= "time" (:label node)))
    (is (= {source-key #{visible-key}} (:node-aliases graph)))))

(deftest mini-grids-retain-complete-finite-values-and-public-slot-names
  (let [values (vec (range 30))
        nested {:base {:a {:b {:c {:d {:e "complete  value"}}}}} :items values}
        rows (grid/rows (object/compound-object nested))
        text (labels/format-value nested)]
    (is (= 30 (count (filter #(re-matches #"[0-9]+" (:key %)) rows))))
    (is (some #(= "29" (:value %)) rows))
    (is (some #(= "\"complete  value\"" (:value %)) rows))
    (is (str/includes? text "29"))
    (is (str/includes? text "complete  value"))
    (is (not (str/includes? text "…")))
    (is (every? #(not (str/starts-with? (:key %) ":")) rows))
    (is (= nested {:base {:a {:b {:c {:d {:e "complete  value"}}}}} :items values}))))

(deftest timestamp-grids-have-one-unconcatenated-row-per-source
  (let [a (ids/new-node-id) b (ids/new-node-id)
        left (event/strongest-value (event/active-event a [:clock/a a] 1 10))
        right (event/strongest-value (event/active-event b [:clock/b b] 7 20))
        result (event/strongest-value (event/lift :sum + [left right] [left right]))
        rows (grid/rows result)
        timestamps (filter :sourceToken rows)]
    (is (= #{"1" "7"} (set (map :value timestamps))))
    (is (= 2 (count timestamps)))
    (is (= 2 (count (set (map :sourceToken timestamps)))))
    (is (= #{(grid/cell-token a) (grid/cell-token b)} (set (map :sourceReference timestamps))))
    (is (= ["30"] (mapv :value (filter #(= "base" (:key %)) rows))))
    (is (not-any? #(str/includes? (:value %) ":input-id") rows))
    (is (not-any? #(str/includes? (:key %) ":slot") rows))))

(deftest linked-list-grids-share-structural-rows
  (let [rows (grid/rows (list 1 nil false))
        linked (:linkedList (first rows))]
    (is (= ["1" "nil" "false"] (mapv #(get-in % [0 :value]) (:items linked))))
    (is (= "empty" (get-in linked [:tail :kind])))
    (is (empty? (get-in (grid/rows '()) [0 :linkedList :items])))
    (is (not-any? :linkedList (grid/rows [1 2])))
    (is (not-any? :linkedList (grid/rows {:car 1 :cdr 2})))))

(deftest native-linked-lists-resolve-live-slots-and-explicit-missing-tails
  (let [linked (object/as-accessor-network
                {:car {:base 7 :unit "ms"}
                 :cdr (object/as-accessor-network
                        {:car 2 :cdr :compiler-2/list-empty})})
        result (:linkedList (first (grid/rows linked)))]
    (is (= 2 (count (:items result))))
    (is (= "empty" (get-in result [:tail :kind])))
    (is (= #{"base" "unit"} (set (map :key (first (:items result)))))))
  (doseq [[tail expected] [[value/nothing "pending"] [9 "improper"]]]
    (let [linked (object/as-accessor-network {:car 1 :cdr tail})
          result (:linkedList (first (grid/rows linked)))]
      (is (= expected (get-in result [:tail :kind])))
      (is (= 1 (count (:items result)))))))

(deftest compiled-linked-list-uses-live-or-captured-slots-without-mutation
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source "(display (list 1 {:base 2 :unit \"ms\"} (list 3 4)))"})
      (let [network (:program/net @(:session (get @editors "a")))
            raw (data/payload (net/network-cell-strongest network display/cell-id))
            live (:linkedList (first (grid/rows raw (structure/network-reader network))))
            captured {:values (into {} (map (fn [[id _]] [[[:outer] id] (net/network-cell-strongest network id)])) (net/net-env network))}
            saved (:linkedList (first (grid/rows raw (structure/snapshot-reader captured [:outer]))))
            missing (:linkedList (first (grid/rows raw))) ]
        (is (= 3 (count (:items live))))
        (is (= "empty" (get-in live [:tail :kind])))
        (is (= 2 (count (get-in live [:items 2 0 :linkedList :items]))))
        (is (= live saved))
        (is (= live (get-in (inspection/inspect editors (assoc-in (request editors) [:display :mode] "value"))
                           [:valueGrid 0 :linkedList])))
        (is (= "unavailable" (get-in missing [:tail :kind])))
        (is (identical? network (:program/net @(:session (get @editors "a"))))))
      (finally (runtime/close! editors)))))

(deftest inspector-formats-entire-content-and-strongest-independently
  (doseq [[content strongest] [[nil false]
                              [{:base [1 2 3] :unit "ms"} {:base 7 :unit "ms"}]
                              [(list 1 2 3) (list 4 5)]
                              [value/nothing value/contradiction]]]
    (let [id (ids/new-node-id)
          network (builder/install-cell net/empty-net id content strongest)
          description (inspection/describe network [[:outer] id])]
      (is (= (grid/rows content) (:contentGrid description)))
      (is (= (grid/rows strongest) (:valueGrid description)))
      (is (= content (net/network-cell-content network id)))
      (is (= strongest (net/network-cell-strongest network id))))))

(deftest recorded-node-content-is-captured-filtered-and-rendered-without-live-reads
  (let [id (ids/new-node-id) key [[:outer] id]
        content {:base [1 2 3] :unit "ms"}
        strongest {:base 3 :unit "ms"}
        network (builder/install-cell net/empty-net id content strongest)
        graph (modifiers/with-values (observer/snapshot network #{key}))
        selected (trace/trace-graph (trace/graph-union graph) {:node key :direction :both})
        recorded (get-in (inspection/replay-json {:replay/frames [{:graph selected}]}) [:frames 0 :graph :nodes 0])]
    (is (= content (get-in graph [:contents key])))
    (is (= {key content} (:contents selected)))
    (is (= (grid/rows content) (:contentGrid recorded)))
    (is (= (grid/rows strongest) (:valueGrid recorded)))
    (is (= (:contentGrid (inspection/describe network key)) (:contentGrid recorded)))
    (is (not (contains? (first (:nodes (inspection/graph-json (dissoc selected :contents)))) :contentGrid)))))

(deftest inspector-keeps-specialized-topology-and-replay-metadata-compact
  (doseq [[v expected] [[{:replay/type :semantic :replay/frames []} "0 recorded observations"]
                        [(trace/graph-union {:nodes {:a 'a} :edges []}) "semantic topology · 1 nodes"]]]
    (let [id (ids/new-node-id)
          network (builder/install-cell net/empty-net id v v)
          selected (inspection/describe network [[:outer] id])
          expected-grid [{:key "" :value expected :depth 0}]]
      (is (= expected-grid (:valueGrid selected)))
      (is (= expected-grid (:contentGrid selected))))))

(deftest snapshot-content-keeps-observed-graphs-opaque
  (let [id (ids/new-node-id) key [[:outer] id]
        graph (trace/graph-union {:nodes {:a 'a} :edges []})
        network (builder/install-cell net/empty-net id graph graph)
        sampled (observer/snapshot network #{key})]
    (is (= {:semantic-trace/value :graph} (get-in sampled [:values key])))
    (is (= {:semantic-trace/value :graph} (get-in sampled [:contents key])))
    (is (identical? graph (net/network-cell-content network id)))))
