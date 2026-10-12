(ns examples.lain.relational-datalog.experiment
  (:require [clojure.string :as string]
            [clojure.test :as test :refer [deftest is]]
            [examples.lain.relational-datalog.operators :as operators]
            [examples.lain.relational-datalog.runtime :as relation]
            [propagators.cells.value :as value]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.datastructures.compound-object :as obj]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.runner :as runner]))

(def directory "examples/lain/relational_datalog/")
(def source (slurp (str directory "path.lain")))

(defn completed [result]
  (if (= :completed (:status result))
    result
    (throw (ex-info "Experiment did not reach quiescence"
                    (select-keys result [:status :steps :ms]) (:error result)))))

(defn initialize
  ([program] (initialize program {}))
  ([program {:keys [bindings run-options]
             :or {bindings (operators/bindings) run-options {}}}]
   (let [started (System/nanoTime)
         compiled (compiler/compile-expr-with-bindings (parser/parse-string program) bindings)
         result (completed (relation/run (:net compiled) (:props compiled) run-options))]
     (assoc result :cell (:cell compiled) :run-options run-options
            :compile-and-run-ms (/ (- (System/nanoTime) started) 1e6)))))

(def initial (delay (initialize source)))

(defn slot [state key]
  (or (obj/existing-slot-cell-id (:network state) key (:cell state))
      (throw (ex-info "Missing fact-base slot" {:slot key}))))

(defn inject [state updates]
  (let [[tasks network]
        (reduce (fn [[tasks network] [id datum]]
                  (let [[more updated] (relation/apply-patch nil (message/message id datum) network)]
                    [(tq/merge-queues tasks more) updated]))
                [tq/empty-queue (:network state)] updates)
        result (completed (relation/run network tasks (or (:run-options state) {})))]
    (assoc result :cell (:cell state) :run-options (:run-options state))))

(defn add-edges [state tuples]
  (inject state [[(slot state :edge) (relation/relation (set tuples))]]))

(defn paths [state]
  (relation/facts (net/network-cell-strongest (:network state) (slot state :path))))

(defn topology [state] (set (keys (net/net-env (:network state)))))

(deftest relation-laws
  (let [a (relation/relation #{[1 2]}) b (relation/relation #{[2 3]})
        c (relation/relation #{[3 4]}) join relation/merge-relation]
    (is (= (join a b) (join b a)))
    (is (= (join a (join b c)) (join (join a b) c)))
    (is (= a (join a a)))
    (is (= a (join a value/nothing)))
    (is (= a (join value/nothing a)))
    (is (= a (relation/strongest-relation a)))
    (is (= relation/empty-relation (join value/nothing value/nothing)))
    (is (thrown? clojure.lang.ExceptionInfo (join a 7)))
    (is (thrown? clojure.lang.ExceptionInfo (join 7 a)))
    (is (thrown? clojure.lang.ExceptionInfo (relation/relation #{7})))
    (is (= #{[[1 2] [2 3]]} (relation/facts (operators/product a b))))))

(deftest ordinary-merge-is-unchanged
  (let [id (ids/new-node-id) original (nb/install-cell net/empty-net id)
        update (message/message id 7)
        [_ experiment] (relation/apply-patch nil update original)
        [_ production] (patch/apply-cell-patch nil update original)
        [_ conflicting] (relation/apply-patch nil (message/message id 8) experiment)]
    (is (= production experiment))
    (is (value/contradiction? (net/network-cell-strongest conflicting id)))
    (is (value/nothing? (net/network-cell-strongest original id)))
    (is (= :completed (:status (runner/run-network [] production))))))

(deftest runner-failure-and-budget
  (let [selected (atom [])
        handlers {:success (fn [_tasks _network] (swap! selected conj :success))
                  :fail (fn [error] (swap! selected conj [(:op (:patch (ex-data error))) :fail]))}]
    (relation/evaluate-patches {:emitter nil :activation-result {:op :unsupported}} net/empty-net handlers)
    (is (= [[:unsupported :fail]] @selected)))
  (let [result (relation/run net/empty-net [(ids/new-node-id)] {:max-steps 0 :max-ms 1000})]
    (is (= :failed (:status result)))
    (is (zero? (:steps result)))
    (is (= net/empty-net (:network result)))
    (is (= "Relation experiment budget reached" (.getMessage (:error result))))))

(deftest empty-and-recursive-closure
  (is (= #{} (paths @initial)))
  (let [result (add-edges @initial [[1 2] [2 3]])]
    (is (= #{[1 2] [2 3] [1 3]} (paths result)))
    (is (= #{[1 2] [2 3]}
           (relation/facts (net/network-cell-strongest (:network result) (slot result :edge)))))
    (is (= #{} (paths @initial)))
    (prn {:case :two-edge-closure :steps (:steps result) :ms (:ms result)
          :nodes (count (topology result)) :initial-nodes (count (topology @initial))
          :initial-steps (:steps @initial) :initial-ms (:compile-and-run-ms @initial)})))

(deftest late-facts-and-idempotence
  (let [first (add-edges @initial [[1 2]])
        next (add-edges first [[2 3]])
        duplicate (add-edges next [[1 2] [2 3]])
        rerun (completed (relation/run (:network next) []))]
    (is (= #{[1 2]} (paths first)))
    (is (= #{[1 2] [2 3] [1 3]} (paths next)))
    (is (= (:network next) (:network duplicate)))
    (is (zero? (:steps duplicate)))
    (is (= (topology next) (topology duplicate)))
    (is (= (:network next) (:network rerun)))
    (is (zero? (:steps rerun)))
    ;; Reactivating our collection machinery does not refresh compiler snapshots.
    (let [collection-props
          (filter #(contains? #{:examples.lain.relational-datalog.operators/publish
                                [:examples.lain.relational-datalog.operators/collection :map]
                                [:examples.lain.relational-datalog.operators/collection :filter]}
                              (:name (val %)))
                  (net/net-env (:network next)))
          reactivated (completed (relation/run (:network next) (map key collection-props)))]
      (is (seq collection-props))
      (is (true? (= (:network next) (:network reactivated)))))))

(deftest forced-whole-network-reactivation
  (let [before (add-edges @initial [[1 2] [2 3]])
        all-props (filter #(contains? (val %) :activate) (net/net-env (:network before)))
        after (assoc (completed (relation/run (:network before) (map key all-props))) :cell (:cell before))
        changed (filter #(not= (get (net/net-env (:network before)) %)
                              (get (net/net-env (:network after)) %)) (topology before))]
    (is (= (topology before) (topology after)))
    (is (= (paths before) (paths after)))
    (is (true? (= (net/net-graph (:network before)) (net/net-graph (:network after)))))
    (is (true? (= (net/net-dict (:network before)) (net/net-dict (:network after)))))
    ;; Existing compiler snapshot producers refresh only immutable Net payloads.
    (is (every? #(and (net/net? (net/network-cell-content (:network before) %))
                     (net/net? (net/network-cell-content (:network after) %))) changed))))

(deftest insertion-orders
  (let [orders [[[1 2] [2 3] [3 4]] [[1 2] [3 4] [2 3]]
                [[2 3] [1 2] [3 4]] [[2 3] [3 4] [1 2]]
                [[3 4] [1 2] [2 3]] [[3 4] [2 3] [1 2]]]
        expected #{[1 2] [2 3] [3 4] [1 3] [2 4] [1 4]}]
    (doseq [order orders]
      (let [result (reduce #(add-edges %1 [%2]) @initial order)
            duplicate (add-edges result order)]
        (is (= expected (paths result)) (str "Fact insertion order " order))
        (is (= (topology result) (topology duplicate)))
        (is (zero? (:steps duplicate)))))))

(deftest cycles-and-rejected-candidates
  (let [cycle (add-edges @initial [[1 2] [2 3] [3 1]])
        unrelated (add-edges @initial [[1 2] [8 9]])]
    (is (= (set (for [x [1 2 3] y [1 2 3]] [x y])) (paths cycle)))
    (is (= #{[1 2] [8 9]} (paths unrelated)))
    (prn {:case :three-node-cycle :steps (:steps cycle) :ms (:ms cycle)
          :nodes (count (topology cycle))})))

(deftest lexical-capture-and-late-callback
  (let [state (initialize
               "(let [offset 10]
                  (let-cell [callback gate input output db]
                    (-> (relation (tuple 1 2)) input)
                    (-> (empty-relation) output)
                    (when gate
                      (-> (network [row] (tuple (+ (at row 0) offset) (at row 1))) callback))
                    (-> (map callback input) output)
                    (p:slot :gate gate db)
                    (p:slot :result output db)
                    db))")
        before (net/network-cell-strongest (:network state) (slot state :result))
        after (inject state [[(slot state :gate) true]])]
    (is (= #{} (relation/facts before)))
    (is (= #{[11 2]}
           (relation/facts (net/network-cell-strongest (:network after) (slot after :result)))))))

(deftest unknown-predicate-stays-pending
  (let [state (initialize "(filter (network [row] :bool4/nothing) (relation (tuple 1 2)))")]
    (is (= #{} (relation/facts (net/network-cell-strongest (:network state) (:cell state)))))))

(deftest malformed-callbacks-fail-explicitly
  (doseq [program ["(map 7 (relation (tuple 1 2)))"
                   "(map (network [row] 7) (relation (tuple 1 2)))"
                   "(filter (network [row] 7) (relation (tuple 1 2)))"]]
    (is (thrown? clojure.lang.ExceptionInfo (initialize program)) program)))

(deftest existing-boundary-suppresses-callback-contradiction
  ;; Document the fixed runtime boundary, not a claim that contradiction is false.
  (let [state (initialize "(filter (network [row] :bool4/contradiction) (relation (tuple 1 2)))")
        network (:network state)
        publishers (filter #(= :examples.lain.relational-datalog.operators/publish (:name (val %)))
                           (net/net-env network))]
    (is (= #{} (relation/facts (net/network-cell-strongest network (:cell state)))))
    (is (= 1 (count publishers)))
    (is (some #(value/contradiction? (:strongest (val %))) (net/net-env network)))
    (is (every? #(value/nothing? (net/network-cell-strongest network %))
                (mapcat (comp :inputs val) publishers)))))

(deftest source-size-and-arity
  (is (< (count (string/split-lines source)) 100))
  (let [forms (tree-seq coll? seq (parser/read-form source))
        networks (filter #(and (seq? %) (= 'network (first %))) forms)]
    (is (= 3 (count networks)))
    (is (every? #(<= (count (second %)) 4) networks)))
  (doseq [file ["runtime.clj" "operators.clj" "experiment.clj" "transitive.clj"
               "semi_naive.clj" "semi_naive_test.clj" "benchmark.clj"
               "compare_engines.clj" "compare_engines_test.clj"]]
    (let [forms (read-string (str "(" (slurp (str directory file)) ")"))
          functions (filter #(and (seq? %) (contains? #{'defn 'defn- 'fn 'letfn} (first %)))
                            (tree-seq coll? seq forms))
          argument-vectors (mapcat #(filter vector? (rest %)) functions)]
      (is (every? #(<= (count %) 4) argument-vectors) (str "Maximum function arity in " file)))))

(defn -main [& args]
  (try
    (case (first args)
      "demo"
      (let [result (add-edges @initial [[1 2] [2 3]])]
        (prn {:edges [[1 2] [2 3]] :paths (sort (paths result))
              :steps (:steps result) :ms (:ms result) :nodes (count (topology result))})
        (shutdown-agents))
      nil
      (let [result (test/run-tests 'examples.lain.relational-datalog.experiment)]
        (shutdown-agents)
        (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))
      (throw (ex-info "Use no arguments for tests, or demo" {:args args})))
    (catch Throwable error
      (.printStackTrace error)
      (shutdown-agents)
      (System/exit 1))))
