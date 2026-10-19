(ns experiments.amb-search.support-probe
  "Matched diagnostic: change failure observation, retain the completion gate."
  (:require [examples.lain.amb.learning-checkpoint-benchmark :as benchmark]
            [examples.lain.amb.checkpoint-proofs :as proofs]
            [examples.lain.amb.parallel-benchmark :as profiling]
            [examples.lain.amb.repair-enumeration :as repair]
            [propagators.experimental.amb.learning :as learning]
            [propagators.experimental.amb.primitives :as amb]
            [propagators.helpers.task-queue :as tasks]
            [propagators.ids :as ids]))

(defn failure-question [question mode]
  (case mode
    :gated question
    :required-result
    (let [failure (ids/new-node-id)
          [id network] ((amb/p:require (proofs/required-result question) failure)
                        (:network question))]
      (-> question
          (assoc :network network)
          (update :tasks tasks/enqueue id)
          (assoc-in [:cells :failure] failure)))))

(defn sample [fixture mode]
  (let [question (failure-question (benchmark/question-of (get-in fixture [:state :network])) mode)
        reasons (atom #{})
        convert learning/certificate->nogood
        result (with-redefs [learning/certificate->nogood
                            (fn [& args]
                              (let [reason (apply convert args)]
                                (when reason (swap! reasons conj reason))
                                reason))]
                 (profiling/profile #(repair/run (repair/extend-question question))))]
    ;; Acceptance and tuple publication still use the original complete gate.
    (assert (= (:expected fixture) (:answers result)))
    (assert (benchmark/sound-nogoods? question (:logical-nogoods result) (:expected fixture)))
    {:mode mode :answers (count (:answers result)) :profile (:profile result)
     :observed-failure-widths (frequencies (map (comp count :literals) @reasons))
     :retained-failure-widths (frequencies (map (comp count :literals) (:logical-nogoods result)))}))

(defn -main [& _]
  (try
    (doseq [shape [:prefix :suffix]
            :let [fixture (benchmark/prepare shape)]]
      (doseq [mode [:gated :required-result]] (sample fixture mode))
      (doseq [mode [:gated :required-result]]
        (prn (assoc (sample fixture mode) :shape shape))
        (flush)))
    (finally (shutdown-agents))))
