(ns gx10-benchmark.outcome
  "Pure evidence classification. Correctness, workflow and timing stay separate.")

(defn- later-finish? [events index]
  (boolean (some #(and (= :assistant-end (:type %))
                       (#{:stop :tool-use} (:stop %)))
                 (drop (inc index) events))))

(defn recovered-abort? [events index]
  (let [event (nth events index)
        request (:request event)
        corrections (filter #(and (= :anti-repeat (:type %))
                                   (= :corrected (:action %))
                                   (= :reasoning-repeat (:kind %))
                                   (= request (:request %))) events)]
    (and (some? request) (= :aborted (:stop event))
         (seq corrections) (later-finish? events index))))

(defn execution-state [{:keys [events exit-code final-response? interrupted?
                               resource-stopped? nontermination-evidence]}]
  (when-not (vector? events)
    (throw (ex-info "Agent events must be an ordered vector" {})))
  (let [terminal-repeat? (some #(and (= :anti-repeat (:type %))
                                     (= :stopped (:action %))
                                     (= :reasoning-repeat (:kind %))) events)
        failures (keep-indexed (fn [index event]
                                 (when (and (= :assistant-end (:type event))
                                            (not (#{:stop :tool-use} (:stop event))))
                                   index)) events)
        unresolved (remove #(recovered-abort? events %) failures)
        ended? (and (= 0 exit-code) (true? final-response?)
                    (= :agent-end (:type (peek events))))]
    (cond
      interrupted? :interrupted
      resource-stopped? :resource-stopped
      (and (:recognized? nontermination-evidence)
           (:matching-command-active? nontermination-evidence)) :nonterminating
      (some #(= :error (:type %)) events) :runtime-error
      terminal-repeat? :repetition-stopped
      (seq unresolved) :runtime-error
      (not ended?) :runtime-error
      (seq failures) :recovered
      :else :normal)))

(defn assertions-pass? [{:keys [assertions failures errors exit-code expected-assertions]}]
  (and (integer? assertions) (pos? assertions)
       (= 0 failures) (= 0 errors) (= 0 exit-code)
       (or (nil? expected-assertions) (= expected-assertions assertions))))

(defn result [execution evaluation workflow timing]
  (let [state (execution-state execution)
        correct? (boolean (assertions-pass? evaluation))
        completed? (boolean (#{:normal :recovered} state))]
    {:outcome (cond
                correct? :passed
                (#{:interrupted :resource-stopped :nonterminating :runtime-error} state) state
                (= :repetition-stopped state) :runtime-error
                :else :task-failed)
     :correct? correct?
     :execution state
     :workflow? (boolean (and completed? (:own-assertions-passed? workflow)
                              (:allowed-changes? workflow)
                              (:web-evidence? workflow)
                              (or (false? (:thinking-required? workflow))
                                  (:thinking-evidence? workflow))))
     :timing-valid? (boolean (and completed? (true? (:measurements-complete? timing))
                                 (integer? (:swap-delta timing)) (zero? (:swap-delta timing))
                                 (not (:resource-stopped? execution))
                                 (not (:interrupted? execution))
                                 (not (:reloaded? timing))))
     :evaluation evaluation
     :raw-execution execution
     :workflow-evidence workflow
     :timing-evidence timing}))

(defn queue-facts [result]
  [(true? (:cleanup-verified? result))
   (true? (:durable? result))
   (boolean (or (#{:normal :recovered} (:execution result))
                (and (= :repetition-stopped (:execution result))
                     (true? (:reload-exhausted? result)))))])

(defn reload-eligible? [result]
  (let [events (get-in result [:raw-execution :events])
        corrected (first (keep-indexed
                          (fn [index event]
                            (when (and (= :anti-repeat (:type event))
                                       (= :corrected (:action event))
                                       (= :reasoning-repeat (:kind event))) index)) events))]
    (and (= :repetition-stopped (:execution result))
         (true? (:cleanup-verified? result))
         (some? corrected)
         (boolean (some #(and (= :anti-repeat (:type %))
                              (= :stopped (:action %))
                              (= :reasoning-repeat (:kind %)))
                        (drop (inc corrected) events))))))
