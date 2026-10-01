(ns propagators.experimental.ttms-view-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.visualization.extension :as extension]
            [propagators.experimental.visualization.layered-primitives :as tracked]
            [propagators.experimental.visualization.interaction :as interaction]
            [propagators.experimental.visualization.selection :as selection]
            [propagators.experimental.visualization.trace :as trace-ops]
            [propagators.experimental.visualization.ttms :as ttms]
            [propagators.experimental.visualization-composition-test :as fixture]
            [propagators.experimental.view-collections-test :as views]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.ids :as ids]
            [propagators.visualizer :as visualizer]
            [propagators.semantic-trace :as trace]))

(defn- session [code]
  @(loader/load-session-from-source code
     {:extensions [extension/ttms-extension primitives/session-extension tracked/ttms-extension]}))

(defn- premise [id epoch status]
  {:source id :timestamp epoch :premises-status status})

(defn- commit [s id update]
  (let [live (atom s)]
    (runtime/commit-runtime-input! live
      {:runtime/input :xr/message :cell-id id :update update})
    @live))

(defn- publish [s symbol base epoch]
  (let [id (fixture/binding-id s symbol)
        p (premise id epoch :active)]
    (commit s id (collection/content {:base base :support #{p} :premise-state #{p}}))))

(defn- withdraw [s symbol epoch]
  (let [id (fixture/binding-id s symbol)]
    (commit s id (collection/content {:premise-state #{(premise id epoch :retracted)}}))))

(defn- values [s]
  (mapv :payload (:items (views/result s 'result))))

(deftest publication-identity-does-not-serialize-the-raw-payload
  (let [raw (proxy [Object] []
              (toString [] (throw (ex-info "Raw payload must not be printed for identity" {}))))
        origin (data/reference (ids/new-node-id))
        first (ttms/dependency-datum :publication raw #{origin})
        next (ttms/dependency-datum :publication :replacement #{origin})]
    (is (identical? raw (dependency/unwrap first)))
    (is (= #{origin} (dependency/sources first)))
    (is (= :replacement (dependency/unwrap next)))
    (is (= (net/net-dict-or-empty first) (net/net-dict-or-empty next)))))

(deftest map-identity-withdraws-and-recovers-without-new-topology
  (let [initial (session "(def-cells seed) (def result (map (:: [x] x) (list seed)))")
        a (publish initial 'seed 10 1)
        b (publish a 'seed 20 2)
        off (withdraw b 'seed 3)
        on (publish off 'seed 30 4)
        n (:program/net on)
        ids (keep (fn [[id v]] (when (prop/prop? v) id)) (net/net-env n))]
    (is (= [10] (values a)))
    (is (= [20] (values b)))
    (is (= [] (values off)))
    (is (= [30] (values on)))
    (is (= (net/net-graph (:program/net a)) (net/net-graph n)))
    (is (= (values on) (values (assoc on :program/net (nb/run-propagators n ids)))))
    (is (= (mapv :identity (:candidates (views/result a 'result)))
           (mapv :identity (:candidates (views/result on 'result)))))))

(deftest filter-state-crosses-a-subsequent-constant-map
  (let [initial (session "(def-cells accepted)
                         (def result (map (:: [x] 9) (filter (:: [x] accepted) (list 4))))")
        yes (publish initial 'accepted true 1)
        no (publish yes 'accepted false 2)
        off (withdraw no 'accepted 3)
        on (publish off 'accepted true 4)]
    (is (= [9] (values yes)))
    (is (= [] (values no)))
    (is (= [] (values off)))
    (is (= [9] (values on)))
    (is (= [:excluded] (mapv :membership (:candidates (views/result no 'result)))))
    (is (= [:pending] (mapv :membership (:candidates (views/result off 'result)))))))

(deftest arithmetic-callback-preserves-ttms-and-provenance
  (let [initial (session "(def-cells seed captured)
                         (def result (map (:: [x] (+ x captured)) (list seed)))")
        a (-> initial (publish 'seed 4 1) (publish 'captured 3 1))
        off (withdraw a 'captured 2)
        on (publish off 'captured 5 3)]
    (is (= [7] (values a)))
    (is (= [] (values off)))
    (is (= [9] (values on)))
    (let [row (first (:candidates (views/result on 'result)))
          v (net/network-cell-strongest (:program/net on) (:value row))]
      (is (= #{(premise (fixture/binding-id on 'seed) 1 :active)
               (premise (fixture/binding-id on 'captured) 3 :active)}
             (datum/support-of v)))
      (is (seq (:sources row))))))

(deftest graph-removal-reappearance-and-withdrawal-preserve-identities
  (let [[a b] (mapv #(vector [:outer] %) (repeatedly 2 ids/new-node-id))
        graph #(trace/graph-union {:nodes (zipmap % (map pr-str %))
                                  :edges (if (= 2 (count %)) [[a b]] [])})
        initial (session "(def-cells graph) (def result (filter (:: [x] true) graph))")
        both (publish initial 'graph (graph [a b]) 1)
        removed (publish both 'graph (graph [b]) 2)
        off (withdraw removed 'graph 3)
        back (publish off 'graph (graph [a b]) 4)
        rows #(views/result % 'result)]
    (is (= 2 (count (:items (rows both)))))
    (is (= [b] (mapv :identity (:items (rows removed)))))
    (is (empty? (:edges (rows removed))))
    (is (empty? (:items (rows off))))
    (is (= (mapv :identity (:items (rows both))) (mapv :identity (:items (rows back)))))
    (is (= [[a b]] (:edges (rows back))))
    (is (= (net/net-graph (:program/net both)) (net/net-graph (:program/net back))))))

(defn- command [s row]
  (let [v (last (interaction/published-views s))]
    {:view-id (pr-str (:view/id v)) :item-id (pr-str (:identity row))
     :epoch (:view/epoch v) :revision (:view/revision v) :generation (:view/generation v)}))

(deftest published-selection-and-clear-use-ttms-and-reject-stale-commands
  (let [initial (session "(def-cells selection)
                         (def result (selectable (map (:: [x] x) (list 1 2)) selection))
                         (def zoom (map (:: [x] x) (focus result selection)))
                         (xr:io result)")
        row (first (:items (views/result initial 'result)))
        cmd (command initial row)
        selected (fixture/seed initial 'selection (:update (interaction/selection-input initial cmd)))
        cleared (fixture/seed selected 'selection
                 (:update (interaction/selection-input selected (assoc cmd :clear? true))))
        restored (fixture/seed cleared 'selection (:update (interaction/selection-input cleared cmd)))
        zoom #(mapv :payload (:items (views/result % 'zoom)))
        id (fixture/binding-id initial 'selection)]
    (is (= [1] (zoom selected)))
    (is (= [] (zoom cleared)))
    (is (= [1] (zoom restored)))
    (is (selection/source? id (net/network-cell-content (:program/net restored) id)))
    (is (= 4 (selection/next-sequence id (net/network-cell-content (:program/net restored) id))))
    (doseq [bad [(assoc cmd :generation "old") (assoc cmd :revision "old")
                 (assoc cmd :item-id "not-published") (assoc cmd :epoch -1)]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Stale or unpublished"
                           (interaction/selection-input restored bad))))
    (let [conflict (-> initial
                       (fixture/seed 'selection (selection/selection-update id 1 :a))
                       (fixture/seed 'selection (selection/selection-update id 1 :b)))]
      (is (value/contradiction? (data/payload (net/network-cell-strongest (:program/net conflict) id)))))))

(deftest ttms-graph-view-clears-and-recovers-without-unioning-snapshots
  (let [initial (session "(def-cells graph) (xr:io graph)")
        a [[:outer] (ids/new-node-id)] b [[:outer] (ids/new-node-id)]
        graph #(trace/graph-union {:nodes {% (pr-str %)} :edges []})
        first (publish initial 'graph (graph a) 1)
        replaced (publish first 'graph (graph b) 2)
        off (withdraw replaced 'graph 3)
        on (publish off 'graph (graph a) 4)
        view #(last (interaction/published-views %))]
    (is (= #{a} (set (keys (:nodes (:view/graph (view first)))))))
    (is (= #{b} (set (keys (:nodes (:view/graph (view replaced)))))))
    (is (= :withdrawn (:view/status (view off))))
    (is (empty? (:nodes (:view/graph (view off)))))
    (is (= :ready (:view/status (view on))))
    (is (= #{a} (set (keys (:nodes (:view/graph (view on)))))))))

(deftest dataflow-errors-retain-reasons-and-fresh-input-repairs
  (let [initial (session "(def-cells graph output) (relationship:dataflow graph output)")
        bad (publish initial 'graph :not-a-graph 1)
        output (fixture/binding-id bad 'output)
        result (net/network-cell-strongest (:program/net bad) output)
        corrected (publish bad 'graph (trace/graph-union {}) 2)
        current (net/network-cell-strongest (:program/net corrected) output)]
    (is (value/contradiction? (data/payload result)))
    (is (some #(= "relationship:dataflow expects a relationship graph" (:reason %))
              (value/contradiction-provenance (data/payload result))))
    (is (= #{(premise (fixture/binding-id bad 'graph) 1 :active)} (datum/support-of result)))
    (is (trace/semantic-trace-graph? (data/payload current)))
    (is (= #{(premise (fixture/binding-id bad 'graph) 2 :active)} (datum/support-of current)))))

(deftest complete-trace-source-stamping-is-idempotent
  (let [initial (session "(def-cells a b raw projected)
                         (def-net f [x] [y] (-> (+ x 1) y)) (f a b) (-> 4 a)
                         (relationship:roots a b raw) (relationship:dataflow raw projected)")
        n (:program/net initial)
        source (fixture/binding-id initial 'raw)
        current (net/network-cell-strongest n source)
        graph (data/payload current)
        stamped (trace-ops/next-source source current graph :active)
        ids (keep (fn [[id p]] (when (prop/prop? p) id)) (net/net-env n))
        rerun (nb/run-propagators n ids)]
    (is (trace/semantic-trace-graph? graph))
    (is (= (datum/support-of current) (:support stamped)))
    (is (= (net/network-cell-content n source) (net/network-cell-content rerun source)))
    (is (seq (:nodes (data/payload (net/network-cell-strongest n
                                  (fixture/binding-id initial 'projected))))))))
