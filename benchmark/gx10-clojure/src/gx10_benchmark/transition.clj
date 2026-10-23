(ns gx10-benchmark.transition
  "Memory settlement is evidence, never the acknowledgement of an unload request."
  (:require [gx10-benchmark.session :as session]))

(def default-policy
  {:settle-ms 10000 :deadline-ms 180000 :poll-ms 1000
   :variation-bytes (* 256 1024 1024) :baseline-shortfall-bytes (* 2 session/gib)})

(defn clean-sample? [{:keys [resident-models owned-processes endpoint-up?
                            available-bytes physical-bytes]}]
  (and (vector? resident-models) (empty? resident-models)
       (vector? owned-processes) (empty? owned-processes)
       (false? endpoint-up?)
       (integer? available-bytes) (integer? physical-bytes)
       (pos? physical-bytes)
       (<= (session/memory-reserve physical-bytes) available-bytes physical-bytes)))

(defn settled? [samples baseline policy]
  (let [amounts (map :available-bytes samples)]
    (and (seq samples)
         (every? clean-sample? samples)
         (>= (- (:monotonic-ms (peek samples)) (:monotonic-ms (first samples)))
             (:settle-ms policy))
         (<= (- (apply max amounts) (apply min amounts)) (:variation-bytes policy))
         (or (nil? baseline)
             (<= (- baseline (apply min amounts)) (:baseline-shortfall-bytes policy))))))

(defn settle!
  "Injected monotonic clock and sleep make transitions deterministic under test.
   record! receives every sample, including those rejected by the gate."
  [sample! now! sleep! record! baseline policy]
  (let [policy (merge default-policy policy)
        started (now!)]
    (loop [window []]
      (let [time (now!)
            sample (assoc (sample!) :monotonic-ms time)
            clean? (clean-sample? sample)
            updated (if clean? (conj window sample) [])
            ;; At most 181 samples under the default deadline. Keeping the
            ;; boundary avoids falsely accepting nine seconds with clock jitter.
            stable (if (and (seq updated)
                            (> (- (apply max (map :available-bytes updated))
                                  (apply min (map :available-bytes updated)))
                               (:variation-bytes policy)))
                     [sample] updated)]
        (record! sample)
        (cond
          (settled? stable baseline policy) (assoc sample :verified? true)
          (>= (- time started) (:deadline-ms policy))
          (throw (ex-info "Unload/memory settlement did not establish a clean baseline"
                          {:outcome :transition-failed :last-sample sample
                           :baseline baseline :elapsed-ms (- time started)}))
          :else (do (sleep! (:poll-ms policy)) (recur stable)))))))
