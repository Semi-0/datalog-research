(ns propagators.prototype-2.pythagorean-bench
  "Paired Pythagorean search across engines:

    :prototype-2           — host Clojure DDB loop (amb/solve-choices)
    :prototype-2-l2-ddb    — L-2 ddb-pyth tip-publish path (near-solution seeds)
    :prototype-2-subworlds — nested world spawn/abandon
    :lain-builder          — production Lain in-network amb

  Same problem: first triple in 1..n with a≤b≤c and a²+b²=c².
  L-2 DDB is measured from a one-repair near-solution (cold full search is
  network-heavy); host / subworlds / Lain still run cold from the domain start.

  Run: clojure -M -m propagators.prototype-2.pythagorean-bench [warmups samples]"
  (:require [examples.lain.amb.benchmark :as lain]
            [examples.lain.amb.builder :as builder]
            [propagators.prototype-2.amb :as amb]))

(defn- milliseconds [a b]
  (/ (double (- b a)) 1e6))

(defn- distribution [xs]
  (let [xs (vec (sort xs))
        n (count xs)]
    {:median (nth xs (quot n 2))
     :p10 (nth xs (quot n 10))
     :p90 (nth xs (min (dec n) (quot (* 9 n) 10)))
     :min (first xs)
     :max (peek xs)}))

(defn- usable-solution [x]
  (when (and (sequential? x) (= 3 (count x)) (every? number? x))
    (vec x)))

(defn- p2-once [n]
  (let [start (System/nanoTime)
        result (amb/pythagorean n)
        done (System/nanoTime)
        sol (when (= :satisfied (:status result)) (:value result))]
    (when-not (contains? #{:satisfied :unsatisfiable} (:status result))
      (throw (ex-info "p2 pythagorean failed" {:n n :result result})))
    {:solution (usable-solution sol)
     :run-ms (milliseconds start done)
     :steps (:steps result)
     :nogoods (count (:knowledge result))
     :status (:status result)
     :engine :prototype-2}))

(defn- p2-subworlds-once [n]
  (let [start (System/nanoTime)
        result (amb/pythagorean-subworlds n)
        done (System/nanoTime)
        sol (when (= :satisfied (:status result)) (:value result))]
    (when-not (contains? #{:satisfied :unsatisfiable} (:status result))
      (throw (ex-info "p2 subworlds pythagorean failed" {:n n :result result})))
    {:solution (usable-solution sol)
     :run-ms (milliseconds start done)
     :steps (:steps result)
     :spawned (:spawned result)
     :abandoned (:abandoned result)
     :status (:status result)
     :engine :prototype-2-subworlds}))

(defn- near-345-seeds
  "Seeds for [3 4 4] → one DDB repair → [3 4 5] when n≥5."
  [n]
  (let [d (vec (range 1 (inc n)))]
    {:ca (amb/choice-at d 2)
     :cb (amb/choice-at d 3)
     :cc (amb/choice-at d 3)}))

(defn- p2-l2-ddb-once
  "L-2 propagator-sense DDB. For n≥5 use near-solution seeds (one repair);
   smaller domains run cold (unsat / tiny search)."
  [n]
  (let [opts (when (>= n 5) (near-345-seeds n))
        result (amb/pythagorean-l2 n opts)]
    (when-not (contains? #{:satisfied :unsatisfiable} (:status result))
      (throw (ex-info "p2 l2-ddb pythagorean failed" {:n n :result result})))
    {:solution (usable-solution (:value result))
     :run-ms (:run-ms result)
     :compile-ms (:compile-ms result)
     :status (:status result)
     :engine :prototype-2-l2-ddb
     :seed (if opts :near-345 :cold)}))

(defn- lain-once [n]
  (let [built (builder/build n builder/pythagorean?)
        run (builder/run built)]
    (when-not (= :completed (get-in run [:result :status]))
      (throw (ex-info "lain pythagorean failed" {:n n :run run})))
    {:solution (usable-solution (:solution run))
     :run-ms (:run-ms run)
     :activations (get-in run [:profile :totals :calls])
     :status (get-in run [:result :status])
     :engine :lain-builder}))

(defn- measure [label once-fn n warmups samples]
  (dotimes [_ warmups] (once-fn n))
  (let [runs (mapv (fn [_] (once-fn n)) (range samples))
        solutions (mapv :solution runs)]
    (when-not (apply = solutions)
      (throw (ex-info "unstable solutions" {:label label :solutions solutions})))
    {:engine label
     :domain n
     :solution (first solutions)
     :warmups warmups
     :samples samples
     :run-ms (distribution (map :run-ms runs))
     :extra (dissoc (first runs) :solution :run-ms :engine)}))

(defn compare-domain [n warmups samples]
  (let [expected (usable-solution (first (lain/exhaustive n)))
        p2 (measure :prototype-2 p2-once n warmups samples)
        p2w (measure :prototype-2-subworlds p2-subworlds-once n warmups samples)
        ;; Cold L-2 DDB over unsat / large spaces is network-heavy; only time
        ;; the tip-publish repair path on near-solution seeds (n≥5).
        p2l2 (when (>= n 5)
               (measure :prototype-2-l2-ddb p2-l2-ddb-once n warmups samples))
        lain (measure :lain-builder lain-once n warmups samples)]
    (when-not (= expected (:solution p2) (:solution p2w) (:solution lain))
      (throw (ex-info "solution mismatch"
                      {:expected expected
                       :p2 (:solution p2)
                       :p2-subworlds (:solution p2w)
                       :lain (:solution lain)})))
    (when (and p2l2 (not= [3 4 5] (:solution p2l2)))
      (throw (ex-info "l2-ddb near-solution mismatch"
                      {:expected [3 4 5] :got (:solution p2l2)})))
    (cond-> {:domain n
             :expected expected
             :prototype-2 p2
             :prototype-2-subworlds p2w
             :lain-builder lain
             :run-ms-ratio-vs-lain
             (cond-> {:amb (let [a (get-in p2 [:run-ms :median])
                                 b (get-in lain [:run-ms :median])]
                             (when (and (number? a) (number? b) (pos? b))
                               (/ (double a) (double b))))
                      :subworlds (let [a (get-in p2w [:run-ms :median])
                                       b (get-in lain [:run-ms :median])]
                                   (when (and (number? a) (number? b) (pos? b))
                                     (/ (double a) (double b))))}
               p2l2 (assoc :l2-ddb-near
                           (let [a (get-in p2l2 [:run-ms :median])
                                 b (get-in lain [:run-ms :median])]
                             (when (and (number? a) (number? b) (pos? b))
                               (/ (double a) (double b))))))}
      p2l2 (assoc :prototype-2-l2-ddb p2l2))))

(defn -main [& [warmup-text sample-text]]
  (let [warmups (Long/parseLong (or warmup-text "3"))
        samples (Long/parseLong (or sample-text "7"))
        domains [4 5 20]]
    (println ";; p2 amb | p2 subworld-reset | p2 L-2 DDB (near) | Lain builder — Pythagorean")
    (println ";; warmups" warmups "samples" samples)
    (doseq [n domains]
      (let [row (compare-domain n warmups samples)]
        (prn row)))
    (shutdown-agents)))
