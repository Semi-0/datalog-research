(ns graph.lain-editor.experiment-comparison
  "Normalization and clean reconstruction for the stack experiment.

  Comparison deliberately excludes generated checkpoint IDs, timings, runtime
  node IDs, and timestamps. It retains semantic outcomes, diagnostics, support
  validity, active implementation source/version coordinates, and stack scene
  structure."
  (:require [graph.lain-editor.development-session :as development-session]
            [graph.lain-editor.constraint-checkpoint :as checkpoint]
            [propagators.compiler-2.runtime :as runtime]))

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
