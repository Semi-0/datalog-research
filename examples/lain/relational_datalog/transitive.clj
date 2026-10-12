(ns examples.lain.relational-datalog.transitive
  "Executable transitive-clause tests. The host oracle never drives inference."
  (:require [clojure.set :as sets]
            [clojure.string :as string]
            [clojure.test :as test :refer [deftest is]]
            [examples.lain.relational-datalog.experiment :as experiment]
            [examples.lain.relational-datalog.runtime :as relation]
            [propagators.network :as net]))

(def source (slurp (str experiment/directory "transitive.lain")))
(def initial (delay (experiment/initialize source)))

(defn tuples [state key]
  (relation/facts
   (net/network-cell-strongest (:network state) (experiment/slot state key))))

(defn extensions [reachable edges]
  (set (for [[x y] reachable [middle z] edges :when (= y middle)] [x z])))

(defn closure
  "Independent, host-side specification of finite positive transitive closure."
  [edges]
  (loop [reachable (set edges)]
    (let [next (sets/union reachable (extensions reachable edges))]
      (if (= next reachable)
        reachable
        (recur next)))))

(defn verify [state edges]
  (let [expected (closure edges)]
    (is (= :completed (:status state)))
    (is (= (set edges) (tuples state :edge)))
    (is (= expected (tuples state :reach)))
    (is (= (extensions expected edges) (tuples state :extension)))))

(deftest finite-transitive-clauses
  (verify @initial #{})
  (doseq [edges [#{[:a :b] [:b :c] [:c :d]}
                 #{[:a :b] [:a :c] [:b :d] [:c :d]}
                 #{[:a :b] [:b :c] [:c :a]}
                 #{[:a :b] [:x :y]}]]
    (let [state (experiment/add-edges @initial edges)]
      (verify state edges)
      (prn {:case edges :reach (sort (tuples state :reach))
            :extensions (count (tuples state :extension))
            :steps (:steps state) :ms (:ms state)
            :nodes (count (experiment/topology state))}))))

(deftest late-bridge-and-duplicate-proofs
  (let [first (experiment/add-edges @initial #{[:a :b] [:c :d]})
        bridged (experiment/add-edges first #{[:b :c]})
        duplicate (experiment/add-edges bridged #{[:a :b] [:b :c] [:c :d]})
        rerun (experiment/completed (relation/run (:network bridged) []))]
    (verify first #{[:a :b] [:c :d]})
    (verify bridged #{[:a :b] [:b :c] [:c :d]})
    (is (contains? (tuples bridged :reach) [:a :d]))
    (is (= (:network bridged) (:network duplicate)))
    (is (= (:network bridged) (:network rerun)))
    (is (zero? (:steps duplicate)))
    (is (zero? (:steps rerun)))))

(deftest all-fact-arrival-orders
  (doseq [edges [[[:a :b] [:b :c] [:c :d]]
                 [[:a :b] [:c :d] [:b :c]]
                 [[:b :c] [:a :b] [:c :d]]
                 [[:b :c] [:c :d] [:a :b]]
                 [[:c :d] [:a :b] [:b :c]]
                 [[:c :d] [:b :c] [:a :b]]]]
    (verify (reduce #(experiment/add-edges %1 [%2]) @initial edges) edges)))

(deftest small-declarative-program
  (is (< (count (string/split-lines source)) 100)))

(defn demo []
  (let [edges #{[:a :b] [:b :c] [:c :d]}
        state (experiment/add-edges @initial edges)]
    {:edges (sort edges)
     :reach (sort (tuples state :reach))
     :extension (sort (tuples state :extension))
     :steps (:steps state) :ms (:ms state)
     :nodes (count (experiment/topology state))}))

(defn -main [& args]
  (try
    (case (first args)
      "demo" (do (prn (demo)) (shutdown-agents))
      nil (let [result (test/run-tests 'examples.lain.relational-datalog.transitive)]
            (shutdown-agents)
            (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))
      (throw (ex-info "Use no arguments for tests, or demo" {:args args})))
    (catch Throwable error
      (.printStackTrace error)
      (shutdown-agents)
      (System/exit 1))))
