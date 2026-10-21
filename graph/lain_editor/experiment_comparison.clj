(ns graph.lain-editor.experiment-comparison
  "Normalization and clean reconstruction for the stack experiment.

  Comparison deliberately excludes generated checkpoint IDs, timings, runtime
  node IDs, and timestamps. It retains semantic outcomes, diagnostics, support
  validity, active implementation source/version coordinates, and stack scene
  structure."
  (:require [clojure.walk :as walk]
            [graph.lain-editor.development-session :as development-session]
            [graph.lain-editor.constraint-checkpoint :as checkpoint]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.ids :as ids]))

(defn- generated-id? [candidate]
  (or (ids/node-id? candidate)
      (instance? java.util.UUID candidate)))

(defn- without-generated-ids [value]
  (walk/postwalk #(if (generated-id? %) :generated-id %) value))

(defn- graph-node-ids [graph]
  (into #{}
        (concat (keys (:nodes graph))
                (keys (:node-kinds graph))
                (keys (:values graph))
                (mapcat identity (:edges graph)))))

(defn- node-description [graph node]
  (without-generated-ids
   {:kind (get (:node-kinds graph) node)
    :label (get (:nodes graph) node)
    :value (get (:values graph) node)}))

(defn- colors-for [descriptions]
  (let [palette (->> (vals descriptions)
                     distinct
                     (sort-by pr-str)
                     (map-indexed (fn [index description] [description index]))
                     (into {}))]
    (into {} (map (fn [[node description]] [node (palette description)]))
          descriptions)))

(defn- refine-node-colors [graph nodes base]
  (loop [colors (colors-for base)
         remaining (count nodes)]
    (if (zero? remaining)
      colors
      (let [incoming (group-by second (:edges graph))
            outgoing (group-by first (:edges graph))
            descriptions
            (into {}
                  (map (fn [node]
                         [node {:base (base node)
                                :incoming (->> (incoming node)
                                               (map (comp colors first))
                                               sort vec)
                                :outgoing (->> (outgoing node)
                                               (map (comp colors second))
                                               sort vec)}]))
                  nodes)
            next-colors (colors-for descriptions)]
        (if (= colors next-colors)
          colors
          (recur next-colors (dec remaining)))))))

(defn- semantic-graph-normalization [graph]
  (let [nodes (graph-node-ids graph)
        base (into {} (map (fn [node] [node (node-description graph node)])) nodes)
        colors (refine-node-colors graph nodes base)]
    {:colors colors
     :normalized
     {:nodes (->> nodes
                  (map (fn [node] {:color (colors node)
                                   :description (base node)}))
                  frequencies
                  (sort-by (comp pr-str key))
                  vec)
      :edges (->> (:edges graph)
                  (map (fn [[source target]] [(colors source) (colors target)]))
                  frequencies
                  (sort-by (comp pr-str key))
                  vec)
      :partial? (boolean (or (:scene/partial? graph) (:partial graph)))
      :diagnostics (without-generated-ids
                    (or (:scene/diagnostics graph) (:diagnostics graph) []))}}))

(defn normalize-semantic-graph
  "Canonicalize a semantic graph without retaining generated runtime IDs.
  Directed topology, node meaning, multiplicity, partialness, and diagnostics
  remain comparable across independent constructions."
  [graph]
  (:normalized (semantic-graph-normalization graph)))

(defn normalize-stack-scene
  "Canonicalize a compiled stack scene for incremental/clean comparison.
  Expansion anchors are represented by the canonical color of their source
  node in the parent plane rather than by a generated runtime ID."
  [scene]
  (let [plane-normalizations
        (into {}
              (map (fn [{:keys [identity graph]}]
                     [identity (semantic-graph-normalization graph)]))
              (:planes scene))
        plane-index (into {}
                          (map-indexed (fn [index plane]
                                         [(:identity plane) index]))
                          (:planes scene))]
    {:stack-scene? (boolean (:stackScene scene))
     :root (plane-index (:root scene))
     :planes (mapv (fn [{:keys [identity depth order]}]
                     {:depth depth
                      :order order
                      :graph (get-in plane-normalizations [identity :normalized])})
                   (:planes scene))
     :edges (->> (:edges scene)
                 (map (fn [{:keys [from source-node to kind]}]
                        {:from (plane-index from)
                         :source-node (get-in plane-normalizations
                                              [from :colors source-node])
                         :to (plane-index to)
                         :kind kind}))
                 (sort-by pr-str)
                 vec)
     :diagnostics (without-generated-ids (:diagnostics scene))}))

(defn- support-validity [outcome]
  {:present? (contains? outcome :support)
   :supported? (and (contains? outcome :support) (seq (:support outcome)))})

(defn normalize-outcome [outcome]
  (assoc (select-keys outcome
                      [:constraint/id :specification/revision :status :required?
                       :reason :dependencies :identity :diagnostics])
         :support-validity (support-validity outcome)))

(defn normalize-checkpoint [checkpoint]
  {:specification/revision (:specification/revision checkpoint)
   :implementation-versions
   (mapv #(select-keys % [:client-id :index :version])
         (:implementation-versions checkpoint))
   :constraint/outcomes (->> (:constraint/outcomes checkpoint)
                             (map normalize-outcome)
                             (sort-by (comp str :constraint/id))
                             vec)
   :constraint/summary (:constraint/summary checkpoint)
   :fixture/summary (dissoc (:fixture/summary checkpoint) :results)
   :diagnostics (:diagnostics checkpoint)
   :evidence-validity
   (->> (:evidence checkpoint)
        (map #(select-keys % [:evidence/id :status :constraint/id :dependencies]))
        (sort-by (comp str :evidence/id))
        vec)})

(defn compare-checkpoints [incremental clean]
  (let [left (normalize-checkpoint incremental)
        right (normalize-checkpoint clean)]
    {:agrees? (= left right)
     :incremental left
     :clean right
     :mismatched-sections
     (->> (keys left) (filter #(not= (get left %) (get right %))) vec)}))

(defn clean-reconstruct!
  "Replay a portable ledger into a fresh network and capture the supplied
  maintained outcome/evidence index. No live capability is returned."
  [{:keys [instance specification-revision outcomes membership-complete?
           fixture-expectations diagnostics evidence]}]
  (let [construction-started (System/nanoTime)
        session (development-session/new-session)
        construction-ns (- (System/nanoTime) construction-started)]
    (try
      (let [replay-started (System/nanoTime)
            replay (runtime/import-instance! session instance)
            replay-ns (- (System/nanoTime) replay-started)
            captured (checkpoint/checkpoint
                      {:id :clean-reconstruction
                       :specification-revision specification-revision
                       :state @session :outcomes outcomes
                       :membership-complete? membership-complete?
                       :fixture-expectations fixture-expectations
                       :diagnostics diagnostics :evidence evidence
                       :timing {:initial-network-construction-ns construction-ns
                                :clean-replay-ns replay-ns}})]
        {:replay (select-keys replay [:status :commit-count])
         :checkpoint (checkpoint/portable-summary captured)})
      (finally
        (runtime/stop-clocks! session)))))
