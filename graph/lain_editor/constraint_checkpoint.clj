(ns graph.lain-editor.constraint-checkpoint
  "Read-only, session-local checkpoints for the constraint-first experiment.

  Capture consumes already-maintained outcomes. It never acquires observations,
  runs propagation, or scans the network for constraints."
  (:require [propagators.compiler-2.runtime.tui.block-model :as block-model]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def outcome-statuses
  #{:satisfied :violated :pending :contradictory :inactive :withdrawn})

(defn constraint-outcome
  [{:keys [constraint/id specification/revision status required?] :as outcome}]
  (when-not (and (some? id) (some? revision) (contains? outcome-statuses status)
                 (boolean? required?))
    (throw (ex-info "Invalid constraint outcome" {:outcome outcome})))
  outcome)

(defn- rate [numerator denominator]
  (when (pos? denominator) (/ numerator denominator)))

(defn outcome-summary [outcomes membership-complete?]
  (let [active (remove #(contains? #{:inactive :withdrawn} (:status %)) outcomes)
        required (filter :required? active)
        counts (frequencies (map :status required))
        required-count (count required)]
    {:required-count required-count
     :membership-complete? (boolean membership-complete?)
     :satisfied (get counts :satisfied 0)
     :violated (get counts :violated 0)
     :pending (get counts :pending 0)
     :contradictory (get counts :contradictory 0)
     :inactive-or-withdrawn (count (filter #(contains? #{:inactive :withdrawn}
                                                       (:status %)) outcomes))
     :required-constraint-satisfaction
     (rate (get counts :satisfied 0) required-count)}))

(defn fixture-summary [expectations outcomes]
  (let [by-id (into {} (map (juxt :constraint/id identity)) outcomes)
        checked (keep (fn [[id expected]]
                        (when-let [observed (get by-id id)]
                          {:constraint/id id :expected expected
                           :observed (:status observed)
                           :agrees? (= expected (:status observed))}))
                      expectations)
        checked (vec checked)
        agreements (count (filter :agrees? checked))]
    {:checked (count checked)
     :declared (count expectations)
     :agreements agreements
     :fixture-expectation-agreement (rate agreements (count checked))
     :results checked}))

(defn- active-implementations [state]
  (->> (block-model/all-blocks state)
       (keep (fn [block]
               (when-let [record (peek (vec (:version-history block)))]
                 {:client-id (:client-id block) :block-id (:block-id block)
                  :index (:index block) :version (:version record)
                  :commit-id (:commit-id record) :premise-id (:premise-id record)})))
       (sort-by (juxt :client-id :index))
       vec))

(defn- retained-counts [state]
  (let [environment (vals (net/net-env (:program/net state)))]
    {:nodes (count environment)
     :cells (count (remove prop/prop? environment))
     :propagators (count (filter prop/prop? environment))}))

(defn checkpoint
  "Capture already-supported state. `outcomes` is the maintained result index;
  callers must not synthesize it by a full validation scan during inspection."
  [{:keys [id specification-revision state outcomes membership-complete?
           fixture-expectations diagnostics evidence timing]}]
  (let [started (System/nanoTime)
        outcomes (mapv constraint-outcome outcomes)
        ids (mapv :constraint/id outcomes)]
    (when-not (and (some? id) (some? specification-revision) (map? state))
      (throw (ex-info "Checkpoint needs id, specification revision, and state" {})))
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "Constraint outcome IDs must be unique" {:ids ids})))
    (when-not (every? #(= specification-revision (:specification/revision %)) outcomes)
      (throw (ex-info "Constraint outcome revision mismatch"
                      {:specification-revision specification-revision})))
    (let [captured {:checkpoint/id id
                    :specification/revision specification-revision
                    :runtime/commit-tick (:runtime/commit-tick state)
                    :implementation-versions (active-implementations state)
                    :commit-log (vec (:versioned/commit-log state))
                    :network/state (:program/net state)
                    :network/retained (retained-counts state)
                    :constraint/outcomes outcomes
                    :constraint/summary (outcome-summary outcomes membership-complete?)
                    :fixture/summary (fixture-summary fixture-expectations outcomes)
                    :diagnostics (vec diagnostics)
                    :evidence (vec evidence)
                    :timing timing}]
      (assoc captured :checkpoint/capture-ns (- (System/nanoTime) started)))))

(defn checkpoint-id [checkpoint] (:checkpoint/id checkpoint))
(defn specification-revision [checkpoint] (:specification/revision checkpoint))
(defn constraint-outcomes [checkpoint] (:constraint/outcomes checkpoint))
(defn constraint-summary [checkpoint] (:constraint/summary checkpoint))
(defn fixture-agreement [checkpoint] (:fixture/summary checkpoint))
(defn network-state [checkpoint] (:network/state checkpoint))

(defn portable-summary
  "Drop live network capability before persistence or report serialization."
  [checkpoint]
  (dissoc checkpoint :network/state))
