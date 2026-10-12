(ns examples.lain.naive-datalog.experiment
  "Host code only compiles, drives the existing runner, and observes results."
  (:require [clojure.test :as test :refer [deftest is]]
            [clojure.string :as string]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.datastructures.compound-object :as obj]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]))

(def directory (str (.getParent (java.io.File. *file*)) "/"))
(def definitions (slurp (str directory "datalog.lain")))
(def budget-ms (* 1000 (Long/parseLong
                       (or (System/getenv "LAIN_DATALOG_SECONDS") "90"))))
(def budget-steps (Long/parseLong
                   (or (System/getenv "LAIN_DATALOG_STEPS") "100000")))

(defn list-ids [network root]
  (loop [id root result [] seen #{}]
    (if (= basis/list-empty-marker (net/network-cell-strongest network id))
      result
      (let [head (obj/existing-slot-cell-id network :car id)
            tail (obj/existing-slot-cell-id network :cdr id)]
        (if (and head tail (not (contains? seen id)))
          (recur tail (conj result head) (conj seen id))
          (throw (ex-info "Pending or cyclic result list" {:cell id})))))))

(defn rows [network root]
  (mapv (fn [row]
          (mapv #(net/network-cell-strongest network %)
                (list-ids network row)))
        (list-ids network root)))

(defn values [result]
  (mapv #(net/network-cell-strongest (:network result) %)
        (list-ids (:network result) (:cell result))))

(defn execute [expression]
  (let [started (System/nanoTime)
        compiled (compiler/compile-source (str "(let [] " definitions " " expression ")"))
        _ (prn {:phase :compiled :nodes (count (net/net-env (:net compiled)))})
        steps (atom 0)
        transform (fn [advance]
                    (fn [state handlers]
                      (if (or (tq/queue-empty? (:tasks state))
                              (and (< (swap! steps inc) budget-steps)
                                   (< (- (System/nanoTime) started) (* budget-ms 1000000))))
                        (advance state handlers)
                        (throw (ex-info "Experiment budget reached"
                                        {:steps @steps
                                         :nodes (count (net/net-env (:network state)))
                                         :ms (/ (- (System/nanoTime) started) 1e6)})))))
        network (binding [runner/*advance-transform* transform]
                  (nb/run-propagators (:net compiled) (:props compiled)))]
    {:network network :cell (:cell compiled) :nodes (count (net/net-env network))
     :steps @steps :ms (/ (- (System/nanoTime) started) 1e6)}))

(deftest prototype-boundaries
  (is (< (count (string/split-lines definitions)) 100))
  (let [forms (tree-seq coll? seq (read-string (str "(do " definitions ")")))
        networks (filter #(and (seq? %) (= 'network (first %))) forms)]
    (is (seq networks))
    (is (every? #(<= (count (second %)) 4) networks))))

(deftest higher-order-collections
  (is (= [12 13]
         (values (execute "(let [offset 10]
                             (map (network [x] (+ x offset))
                               (filter (network [x] (> x 1)) (list 1 2 3))))")))))

(deftest binding-compatibility
  (is (= [true false true false]
         (values (execute "(list
           (compatible? (list (list :x 1) (list :x 1)))
           (compatible? (list (list :x 1) (list :x 2)))
           (compatible? (list (list 7 7)))
           (compatible? (list (list 7 8))))")))))

(deftest fresh-binding-and-source-isolation
  (is (= [7 7]
         (values (execute "(let-cell [source]
           (-> 7 source)
           (list (bind-term :x (list (list :x source) (list :x source))) source))"))))
  (is (= [true 7]
         (values (execute "(let-cell [source local]
           (-> 7 source) (-> source local) (-> 8 local)
           (list (contradiction? local) source))")))))

(deftest duplicate-elimination
  (let [result (execute "(unique (list (list :edge 1 2)
                                      (list :edge 1 2)
                                      (list :edge 2 3)))")]
    (is (= [[:edge 1 2] [:edge 2 3]]
           (rows (:network result) (:cell result))))))

(deftest one-rule-derivation
  (let [result (execute (slurp (str directory "query.lain")))]
    (is (= [[:path 1 2]] (rows (:network result) (:cell result))))
    (prn (assoc (select-keys result [:steps :ms :nodes]) :test :one-rule))))

(deftest ^:stress two-atom-join
  (let [result (execute "(derive
           (database (list (list :left 1 2) (list :right 2 3)))
           (list (list :path :x :z)
             (list (list :left :x :y) (list :right :y :z))))")]
    (is (= [[:path 1 3]] (rows (:network result) (:cell result))))
    (prn (assoc (select-keys result [:steps :ms :nodes]) :test :two-atom-join))))

(deftest empty-fixed-point
  (let [result (execute "(p:slot :facts (datalog (database (list)) (list)))")]
    (is (= [] (rows (:network result) (:cell result))))))

(deftest ^:stress mutual-recursion-fixed-point
  (let [result (execute "(p:slot :facts
           (datalog (database (list (list :seed)))
             (list
               (list (list :ready) (list (list :seed)))
               (list (list :seed) (list (list :ready))))))")]
    (is (= #{[:seed] [:ready]} (set (rows (:network result) (:cell result)))))
    (prn (assoc (select-keys result [:steps :ms :nodes]) :test :mutual-recursion))))

(deftest ^:stress path-fixed-point
  (let [result (execute (slurp (str directory "path.lain")))]
    (is (= #{[:edge 1 2] [:edge 2 3] [:path 1 2] [:path 2 3] [:path 1 3]}
           (set (rows (:network result) (:cell result)))))
    (prn (select-keys result [:steps :ms :nodes]))))

(defn run-selected [name]
  (let [targets (if name
                  [(ns-resolve 'examples.lain.naive-datalog.experiment (symbol name))]
                  (->> (ns-publics 'examples.lain.naive-datalog.experiment)
                       vals
                       (filter #(and (:test (meta %)) (not (:stress (meta %)))))
                       (sort-by #(str (:name (meta %))))))]
    (if (every? #(and % (:test (meta %))) targets)
      (binding [test/*report-counters* (ref test/*initial-report-counters*)]
        (test/test-vars targets)
        (let [result @test/*report-counters*]
          (test/do-report (assoc result :type :summary))
          result))
      (throw (ex-info "Unknown experiment test" {:name name})))))

(defn -main [& _args]
  (if-let [probe (System/getenv "LAIN_DATALOG_PROBE")]
    (let [result (execute probe)]
      (prn (select-keys result [:steps :ms]))
      (case (System/getenv "LAIN_DATALOG_FORMAT")
        "rows" (prn (rows (:network result) (:cell result)))
        "values" (prn (values result))
        nil (prn (net/network-cell-strongest (:network result) (:cell result)))
        (throw (ex-info "Unknown probe format" {})))
      (shutdown-agents))
    (let [result (run-selected (System/getenv "LAIN_DATALOG_TEST"))]
      (shutdown-agents)
      (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))))
