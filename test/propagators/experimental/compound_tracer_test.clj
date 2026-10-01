(ns propagators.experimental.compound-tracer-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]
            [propagators.helpers.task-queue :as tq]
            [propagators.propagator :as prop]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.experimental.tracer-topology-test :as fixture]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.visualization.extension :as extension]
            [propagators.experimental.visualization.observation :as observation]
            [propagators.network :as net]
            [propagators.relationship :as relationship]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.runner :as runner]
            [propagators.relationship-observer :as observer]))

(def program "examples/lain/visualization_combinators/topology_scenarios/composed.lain")
(def declarations
  [['shift ['source] ['source] ['prepared]]
   ['stage ['prepared 'left] ['prepared] ['left]]
   ['pipeline ['left 'result] ['left] ['result]]])

(defn application-key [app]
  [[:outer] (observation/application-propagator app)])

(defn occurrence [state operator arguments]
  (let [matches (filter #(and (= (fixture/binding-id state operator) (:operator-id %))
                              (= (mapv (partial fixture/binding-id state) arguments)
                                 (:argument-ids %)))
                        (application/application-topologies (:program/net state)))]
    (is (= 1 (count matches)))
    (first matches)))

(defn child-projection [network parent]
  (dataflow/child-dataflow-graph network parent))

(defn connection-path [edges from target]
  (let [adjacency (reduce (fn [g [a b]] (-> g (update a (fnil conj #{}) b)
                                           (update b (fnil conj #{}) a))) {} edges)]
    (loop [pending (conj clojure.lang.PersistentQueue/EMPTY [from]) visited #{from}]
      (if (empty? pending)
        nil
        (let [path (peek pending) node (last path)]
          (if (= target node)
            path
            (let [next-nodes (set/difference (get adjacency node #{}) visited)]
              (recur (into (pop pending) (map #(conj path %) next-nodes))
                     (into visited next-nodes)))))))))

(deftest composed-high-level-chain
  (doseq [[mode options] fixture/environments]
    (testing (name mode)
      (let [state @(loader/load-session-from-file program options)
            expected (set (mapcat #(fixture/expected-application-edges state %) declarations))
            network (:program/net state)
            roots (observer/snapshot network (observer/root-node-keys network))
            route (connection-path (:edges roots) (fixture/node-key state 'source)
                                   (fixture/node-key state 'other))]
        (is (seq route) "Diagnose operational connectivity of unrelated computation")
        (println "ROOT-CONNECTION" mode (mapv (:nodes roots) route))
        (fixture/assert-graph (fixture/graph-value state) expected declarations)
        (fixture/assert-live-result state {'source 2 'other 10} ['result 84100]
                                    expected declarations)))))

(defn child-applications [network parent]
  (let [children (relationship/children (net/net-relationship network) (application-key parent))]
    (filterv #(contains? children (application-key %))
             (application/application-topologies network))))

(defn label [network app]
  (get-in (observer/snapshot network [(application-key app)]) [:nodes (application-key app)]))

(defn unique-call [network apps name arguments]
  (let [matches (filter #(and (= name (label network %)) (= arguments (:argument-ids %))) apps)]
    (is (= 1 (count matches)) (str "Unique child call " name))
    (first matches)))

(defn local-port [network app symbol]
  (env/resolve-binding-id network (:frame-id app) symbol))

(defn key-of [id] [[:outer] id])

(defn stage-oracle [network app]
  (let [children (child-applications network app)
        x (local-port network app 'x)
        out (local-port network app 'out)
        shift (unique-call network children 'shift [x])
        square (unique-call network children 'square [(:result-id shift)])
        path [(key-of x) (application-key shift) (key-of (:result-id shift))
              (application-key square) (key-of out)]]
    {:edges (set (map vec (partition 2 1 path))) :calls [shift square]
     :x (key-of x) :out (key-of out)}))

(defn pipeline-oracle [network app]
  (let [children (child-applications network app)
        x (local-port network app 'x)
        out (local-port network app 'out)
        first-calls (filter #(and (= 'stage (label network %))
                                 (= x (first (:argument-ids %)))) children)
        _ (is (= 1 (count first-calls)))
        first-call (first first-calls)
        middle (second (:argument-ids first-call))
        second-call (unique-call network children 'stage [middle out])
        path [(key-of x) (application-key first-call) (key-of middle)
              (application-key second-call) (key-of out)]]
    {:edges (set (map vec (partition 2 1 path))) :calls [first-call second-call]
     :x (key-of x) :out (key-of out)}))

(defn check-subnetwork [network parent oracle expected-value]
  (let [{:keys [edges calls x out]} oracle
        projected (child-projection network (application-key parent))
        actual (set (:edges projected))
        visible-calls (set (for [[id kind] (:node-kinds projected)
                                :when (= :propagator kind)] id))]
    (is (set/subset? edges actual) "Every intended inner connection exists")
    (is (= (set (map application-key calls)) visible-calls) "No sibling or parent applications")
    (is (= expected-value (data/payload (get (:values projected) out))))
    (is (= edges actual) "Clean inner graph has no compiler return-routing detours")
    (is (= (set (mapcat identity edges)) (set (keys (:nodes projected)))))
    (println "SUBNETWORK" "expected-edges" (count edges) "actual-edges" (count actual)
             "local-labels" [(get (:nodes projected) x) (get (:nodes projected) out)])))

(deftest exact-selected-and-nested-subnetworks
  (doseq [[mode options] fixture/environments]
    (testing (name mode)
      (let [state (fixture/inject @(loader/load-session-from-file program options) {'source 2})
            network (:program/net state)
            stage (occurrence state 'stage ['prepared 'left])
            pipeline (occurrence state 'pipeline ['left 'result])
            pipeline-expected (pipeline-oracle network pipeline)
            [first-stage second-stage] (:calls pipeline-expected)]
        (testing "selected stage"
          (check-subnetwork network stage (stage-oracle network stage) 16))
        (testing "selected pipeline"
          (check-subnetwork network pipeline pipeline-expected 84100))
        (doseq [[label app result] [["first nested stage" first-stage 289]
                                  ["second nested stage" second-stage 84100]]]
          (testing label (check-subnetwork network app (stage-oracle network app) result)))
        (is (empty? (set/intersection (set (map application-key (child-applications network first-stage)))
                                     (set (map application-key (child-applications network second-stage))))))
        (is (not= (local-port network first-stage 'x) (local-port network second-stage 'x)))))))

(defn bounded-run [f]
  (let [steps (atom 0) activations (atom {}) started (System/nanoTime)]
    (binding [runner/*advance-transform*
              (fn [advance]
                (fn [state continuations]
                  (if-let [[id _] (tq/pop-task (:tasks state))]
                    (let [p (net/network-env-lookup (:network state) id)]
                      (swap! activations update (prop/prop-name p) (fnil inc 0)))
                    nil)
                  (if (or (> (swap! steps inc) 30000)
                          (> (- (System/nanoTime) started) 30000000000))
                    (throw (ex-info "Recursive test exceeded its verification budget"
                                    {:steps @steps :activations (take 8 (sort-by (comp - val) @activations))}))
                    (advance state continuations))))]
      (f))))

(defn one-match [apps predicate description]
  (let [matches (filter predicate apps)]
    (is (= 1 (count matches)) description)
    (first matches)))

(defn primitive-edges [app]
  (concat (map #(vector (key-of %) (application-key app)) (:argument-ids app))
          [[(application-key app) (key-of (:result-id app))]]))

(defn recursive-oracle [network parent n]
  (let [apps (application/application-topologies network)
        x (local-port network parent 'a) out (local-port network parent 'out)
        find-op (fn [name] (one-match apps #(and (= name (label network %))
                                               (= x (first (:argument-ids %)))) (str name " in frame")))
        comparisons (mapv find-op ['<= '>])
        switches (mapv (fn [comparison]
                         (one-match apps #(and (= 'switch (label network %))
                                               (= (:result-id comparison) (second (:argument-ids %))))
                                    "Guard switch")) comparisons)
        guards (concat comparisons switches)]
    (doseq [comparison comparisons]
      (is (= 1 (data/payload (net/network-cell-strongest network (second (:argument-ids comparison)))))))
    (if (> n 1)
      (let [decrement (find-op '-)
            recursive (unique-call network apps 'down [(:result-id decrement) out])]
        {:calls (concat guards [decrement recursive])
         :edges (set (concat (mapcat primitive-edges (concat guards [decrement]))
                             [[(key-of (:result-id decrement)) (application-key recursive)]
                              [(application-key recursive) (key-of out)]]))})
      {:calls guards
       :edges (set (concat (mapcat primitive-edges guards) [[(key-of x) (key-of out)]]))})))

(deftest recursive-occurrences
  (doseq [[mode options] (concat [[:ttms-observation-only {:extensions [extension/ttms-extension]}]]
                               fixture/environments)]
    (testing (name mode)
      (let [state (bounded-run #(fixture/inject
                    @(loader/load-session-from-file
                       "examples/lain/visualization_combinators/topology_scenarios/recursive.lain" options)
                    {'input 4}))
            network (:program/net state)
            calls (filter #(= 'down (label network %)) (application/application-topologies network))
            active (filter #(number? (data/payload (net/network-cell-strongest network
                                                   (first (:argument-ids %))))) calls)
            expected (set (fixture/expected-application-edges state
                            ['down ['input 'result] ['input] ['result]]))]
        (is (= 1 (data/payload (net/network-cell-strongest network (fixture/binding-id state 'result)))))
        (is (= 4 (count active)))
        (is (= 4 (count (set (map :frame-id active)))))
        (fixture/assert-graph (fixture/graph-value state) expected [1])
        (let [again (fixture/inject state {'input 4})]
          (is (= (set (keys (net/net-env network)))
                 (set (keys (net/net-env (:program/net again))))))
          (is (= (fixture/graph-value state) (fixture/graph-value again))))
        (doseq [app active]
          (let [n (data/payload (net/network-cell-strongest network (first (:argument-ids app))))
                graph (child-projection network (application-key app))
                {:keys [edges calls]} (recursive-oracle network app n)]
            (println "RECURSIVE-DIFF" n
                     "extra" (mapv #(mapv (:nodes graph) %) (set/difference (set (:edges graph)) edges))
                     "missing" (count (set/difference edges (set (:edges graph)))))
            (fixture/assert-graph graph edges calls)
            (is (= 'a (get (:nodes graph) (key-of (local-port network app 'a)))))
            (is (= 'out (get (:nodes graph) (key-of (local-port network app 'out)))))
            (println "RECURSIVE" mode
                     n "edges" (count (:edges graph))
                     (frequencies (vals (:nodes graph))))))))))

(deftest ttms-recursion-without-tracer-control
  (let [source (str/replace
                 (slurp "examples/lain/visualization_combinators/topology_scenarios/recursive.lain")
                 #"(?m)^\(relationship:.*\)\n?" "")
        state (bounded-run #(fixture/inject
                              @(loader/load-session-from-source source (:ttms fixture/environments))
                              {'input 4}))]
    (is (= 1 (data/payload (net/network-cell-strongest (:program/net state)
                           (fixture/binding-id state 'result)))))))
