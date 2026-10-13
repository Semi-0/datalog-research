(ns propagators.experimental.amb-strategy-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.amb.benchmark :as oracle]
            [examples.lain.amb.checkpoint-question :as question]
            [examples.lain.amb.checkpoint-search :as checkpoint]
            [examples.lain.amb.domain-refinement :as domain]
            [examples.lain.amb.incremental-learning :as incremental]
            [examples.lain.amb.network-search :as search]
            [propagators.cells.value :as value]
            [propagators.experimental.amb.contracts :as contracts]
            [propagators.experimental.amb.learning :as learning]
            [propagators.experimental.amb.observation :as observation]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(deftest delta-resolution-equals-full-closure
  (let [[a b c] (repeatedly 3 ids/new-node-id)
        records (mapv #(contracts/initial-choice % [1 2 3]) [a b c])
        rejected (for [i (range 3) j (range 3) k (range 3)]
                   {:literals #{[a i] [b j] [c k]} :context #{}})]
    (doseq [ordered [rejected (reverse rejected) (concat (drop 7 rejected) (take 7 rejected))]]
      (loop [remaining (seq ordered) state incremental/empty-state known #{}]
        (if remaining
          (let [nogood (first remaining)
                next-state (incremental/incorporate state nogood records)
                reference (learning/closure (conj known nogood) records)]
            (is (= reference (:knowledge next-state)))
            (is (= next-state (incremental/incorporate next-state nogood records)))
            (recur (next remaining) next-state reference))
          (is (= #{{:literals #{} :context #{}}} known)))))))

(deftest delta-learning-preserves-stale-and-context-rules
  (let [[source external] (repeatedly 2 ids/new-node-id)
        record (contracts/initial-choice source [1 2])
        ctx {:source external :timestamp 1 :premises-status :active}
        failure {:status :failed :support #{(contracts/premise record) ctx}}
        state (incremental/learn value/nothing failure [record] #{ctx})]
    (is (= #{(learning/certificate->nogood failure [record] #{ctx})} (:knowledge state)))
    (is (= state (incremental/learn state failure [(assoc record :timestamp 2)] #{ctx})))
    (is (= state (incremental/learn state failure [record] #{(assoc ctx :timestamp 2)})))
    (is (= state (incremental/learn state {:status :pending} [record] #{ctx})))))

(deftest incremental-learner-runs-inside-existing-amb
  (doseq [n [4 5]]
    (let [built (incremental/build n)
          result (runner/run-network (:tasks built) (:network built))
          knowledge (observation/cell-base (:network result) (get-in built [:cells :knowledge]))]
      (is (= :completed (:status result)) (some-> (:error result) ex-message))
      (is (= (:solution (oracle/chronological n))
             (let [v (observation/cell-base (:network result) (get-in built [:cells :output]))]
               (if (vector? v) v nil))))
      (is (= knowledge (:knowledge (observation/cell-base (:network result) (:incremental-state built)))))
      (is (seq knowledge)))))

(deftest domain-intersection-laws-and-bidirectional-contraction
  (let [a (domain/domain [1 2 3]) b (domain/domain [2 3 4]) c (domain/domain [3 4])]
    (is (= a (domain/intersect a a)))
    (is (= (domain/intersect a b) (domain/intersect b a)))
    (is (= (domain/intersect (domain/intersect a b) c)
           (domain/intersect a (domain/intersect b c))))
    (is (= [#{2 3} #{1 2}] (domain/projections (fn [x y] (= x (inc y))) [#{1 2 3} #{1 2 3}])))
    (is (= [#{} #{}] (domain/projections (fn [x y] (= x (inc y))) [#{1} #{2}])))))

(deftest domain-network-preserves-all-bounded-solutions
  (doseq [n [1 4 5 8 12]]
    (let [built (domain/build n)
          state (domain/settle (select-keys built [:network :tasks]))
          domains (mapv #(domain/candidates (net/network-cell-strongest (:network state) %)) (:sources built))
          expected (oracle/exhaustive n)]
      (is (every? (fn [tuple] (every? true? (map contains? domains tuple))) expected))
      (is (= (empty? expected) (= :failed (:status (domain/inspect built state)))))
      (is (tq/queue-empty? (:tasks state)))
      (is (= (:network state) (:network (domain/settle (assoc state :tasks (:tasks built)))))))))

(deftest multiple-domain-contractors-propagate-through-chains-and-cycles
  (let [[x y z] (repeatedly 3 ids/new-node-id)
        initial (reduce #(nb/seed-cell %1 %2 (domain/domain [1 2 3])) (nb/install-cells [x y z]) [x y z])
        [p1 n1] ((domain/p:relation (fn [x y] (= x (inc y))) [x y]) initial)
        [p2 n2] ((domain/p:relation (fn [y z] (= y (inc z))) [y z]) n1)
        result (domain/settle {:network n2 :tasks (tq/into-queue [p1 p2])})
        [p3 cyclic] ((domain/p:relation (fn [z x] (= z (inc x))) [z x]) n2)
        failed (domain/settle {:network cyclic :tasks (tq/into-queue [p1 p2 p3])})]
    (is (= [#{3} #{2} #{1}] (mapv #(domain/candidates (net/network-cell-strongest (:network result) %)) [x y z])))
    (is (every? empty? (map #(domain/candidates (net/network-cell-strongest (:network failed) %)) [x y z])))))

(deftest network-owned-checkpoints-equal-external-dfs
  (doseq [n [1 4 5 8]]
    (let [question (question/build n)
          original (:network question)
          built (search/build question)
          result (runner/run-network (:tasks built) (:network built))
          answer (search/answer built (:network result))
          baseline (checkpoint/run question :checkpoint)]
      (is (= :completed (:status result)))
      (is (= (:solution baseline) (:solution answer)))
      (is (= (:status baseline) (:status answer)))
      (is (= (get-in baseline [:metrics :decisions]) (get-in answer [:frontier :decisions])))
      (is (= (get-in baseline [:metrics :leaves]) (get-in answer [:frontier :leaves])))
      (is (= 3 (count (filter prop/prop? (vals (net/net-env (:network built)))))))
      (is (= (net/net-graph (:network built)) (net/net-graph (:network result))))
      (is (= (set (keys (net/net-env (:network built))))
             (set (keys (net/net-env (:network result))))))
      (is (identical? original (:network question)))
      (is (= (:network result) (runner/completed-network (runner/run-network (:tasks built) (:network result))))))))

(deftest domain-refinement-composes-with-network-owned-search
  (doseq [n [1 4 5 8 12]]
    (let [question (domain/build n) built (search/build question)
          result (runner/run-network (:tasks built) (:network built))
          answer (search/answer built (:network result))]
      (is (= :completed (:status result)))
      (is (= (:solution (oracle/chronological n)) (:solution answer)))
      (is (= (empty? (oracle/exhaustive n)) (= :exhausted (:status answer))))
      ;; 1..12 retains two triples: the controller must choose, not only publish
      ;; a contractor's singleton solution. Smaller cases settle without guesses.
      (is (= (if (= 12 n) 1 0) (get-in answer [:frontier :decisions])))
      (is (= (net/net-graph (:network built)) (net/net-graph (:network result)))))))

(deftest contractor-errors-remain-errors-not-unsatisfiability
  (let [question (domain/build 5 (fn [_ _ _] nil))
        built (search/build question)
        result (runner/run-network (:tasks built) (:network built))]
    (is (= :failed (:status result)))
    (is (instance? Throwable (:error result)))
    (is (= :running (:status (observation/cell-base (:network result) (:frontier built)))))
    (is (not= :exhausted (:status result)))))

(deftest duplicate-and-stale-job-results-do-not-advance-frontier
  (let [question (question/build 5)
        frontier {:status :running :pending [{:id 7}] :last-result 6}
        stale {:id 5 :status :failed} duplicate {:id 6 :status :failed}]
    (is (= frontier (search/update-frontier question frontier stale)))
    (is (= frontier (search/update-frontier question frontier duplicate)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Relation must decide"
                         (domain/projections (fn [_] nil) [#{1}])))))
