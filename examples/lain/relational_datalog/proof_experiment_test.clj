(ns examples.lain.relational-datalog.proof-experiment-test
  (:require [clojure.string :as string]
            [clojure.test :as test :refer [deftest is]]
            [examples.lain.relational-datalog.proof-experiment :as e]
            [examples.lain.relational-datalog.proof-operators :as operators]
            [examples.lain.relational-datalog.proof-relation :as relation]
            [examples.lain.relational-datalog.runtime :as runtime]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(def initial (delay (e/initialize)))
(def active (delay (e/publish @initial e/active-updates)))

(defn- topology [state] (set (keys (net/net-env (:network state)))))

(defn- live [state]
  (into {} (filter #(= :active (:status (val %)))) (:proofs (e/view state))))

(defn- support-for [state keys]
  (into #{} (map (fn [key] {:source (get (:sources state) key)
                           :timestamp 1 :premises-status :active})) keys))

(defn- candidate-with [state keys]
  (let [support (support-for state keys)]
    (into {} (filter #(= support (:support (val %)))) (:proofs (e/view state)))))

(defn- permutations [items]
  (if (empty? items)
    [[]]
    (mapcat (fn [item]
              (map #(into [item] %) (permutations (remove #{item} items))))
            items)))

(deftest lain-proofs-preserve-distinct-identities-and-source-cells
  (is (= #{} (:facts (e/view @initial))))
  (is (= #{:pending} (set (map :status (vals (:proofs (e/view @initial)))))))
  (is (= #{[:a :c]} (:facts (e/view @active))))
  (is (= 2 (count (live @active))))
  (is (= #{(support-for @active [:ab :bc]) (support-for @active [:ad :dc])}
         (set (map :support (vals (live @active))))))
  (doseq [proof (vals (live @active)) premise (:support proof)]
    (let [source (:source premise)]
      (is (contains? (net/net-env (:network @active)) source))
      (is (= 1 (:timestamp premise)))
      (is (not (value/unusable? (net/network-cell-strongest (:network @active) source)))))))

(deftest conflicts-stay-local-and-keep-exact-dependencies
  (let [rejected (candidate-with @active [:ab :dc])
        bindings (operators/bindings-in (:network @active))
        conflict (filter #(= :contradictory (:status %)) (vals bindings))]
    (is (= 1 (count rejected)))
    (is (= :contradictory (:status (first (vals rejected)))))
    (is (value/contradiction? (:base (first (vals rejected)))))
    (is (= 3 (count bindings)))
    (is (= 1 (count conflict)))
    (is (= (support-for @active [:ab :dc]) (:support (first conflict))))
    (is (= 2 (count (live @active))))
    (is (value/contradiction? (merge/cell-merge :b :d net/empty-net)))))

(deftest withdrawal-reactivation-and-update
  (let [one (e/publish @active [[:bc 2 :retracted value/nothing]])
        none (e/publish one [[:dc 2 :retracted value/nothing]])
        restored (e/publish none [[:bc 3 :active [:b :c]]])
        changed (e/publish restored [[:bc 4 :active [:b :z]]])]
    (is (= #{[:a :c]} (:facts (e/view one))))
    (is (= 1 (count (live one))))
    (is (= (support-for one [:ad :dc]) (:support (first (vals (live one))))))
    (is (= #{} (:facts (e/view none))))
    (is (empty? (live none)))
    (is (= #{[:a :c]} (:facts (e/view restored))))
    (is (= 1 (count (live restored))))
    (is (= #{[:a :z]} (:facts (e/view changed))))
    (is (= #{3} (into #{} (keep #(if (= (get (:sources restored) :bc) (:source %))
                                 (:timestamp %) nil))
                       (:support (first (vals (live restored)))))))
    (is (every? #(= (topology @initial) (topology %))
                [@active one none restored changed]))))

(deftest pending-is-not-a-success-and-conflict-can-recover
  (let [left (e/publish @initial [(first e/active-updates)])
        conflict (e/publish left [(last e/active-updates)])
        recovered (e/publish conflict [[:dc 2 :active [:b :z]]])]
    (is (= #{} (:facts (e/view left))))
    (is (empty? (live left)))
    (is (= #{} (:facts (e/view conflict))))
    (is (empty? (live conflict)))
    (is (= :contradictory (:status (first (vals (candidate-with conflict [:ab :dc]))))))
    (is (= #{[:a :z]} (:facts (e/view recovered))))
    (is (= 1 (count (live recovered))))
    (is (= (topology @initial) (topology recovered)))))

(deftest changed-bindings-reject-and-recover-without-reinstallation
  (let [changed (e/publish @active [[:dc 2 :active [:b :z]]])
        withdrawn (e/publish changed [[:ab 2 :retracted value/nothing]])
        restored (e/publish withdrawn [[:ab 3 :active [:a :b]]])]
    (is (= #{[:a :c] [:a :z]} (:facts (e/view changed))))
    (is (= 2 (count (live changed))))
    (is (= #{:active :contradictory}
           (set (map :status (vals (:proofs (e/view changed)))))))
    (is (= #{} (:facts (e/view withdrawn))))
    (is (= #{[:a :c] [:a :z]} (:facts (e/view restored))))
    (is (= 2 (count (live restored))))
    (is (= (topology @initial) (topology restored)))))

(deftest all-source-arrival-orders
  (doseq [order (permutations e/active-updates)]
    (let [state (reduce #(e/publish %1 [%2]) @initial order)]
      (is (= #{[:a :c]} (:facts (e/view state))) (pr-str order))
      (is (= 2 (count (live state))) (pr-str order))
      (is (= :contradictory (:status (first (vals (candidate-with state [:ab :dc])))))
          (pr-str order))
      (is (= (topology @initial) (topology state))))))

(deftest withdrawal-order-simultaneous-updates-and-stale-replay
  (doseq [order [[[:bc 2 :retracted value/nothing] [:dc 2 :retracted value/nothing]]
                [[:dc 2 :retracted value/nothing] [:bc 2 :retracted value/nothing]]]]
    (let [none (reduce #(e/publish %1 [%2]) @active order)
          simultaneous (e/publish @active order)
          stale (e/publish none e/active-updates)]
      (is (= #{} (:facts (e/view none))))
      (is (= (e/view none) (e/view simultaneous)))
      (is (= (:network none) (:network stale)))
      (is (zero? (:steps stale)))))
  (let [changed (e/publish @active [[:ab 2 :active [:a :x]] [:bc 2 :active [:x :z]]])]
    (is (= #{[:a :z] [:a :c]} (:facts (e/view changed))))
    (is (= 2 (count (live changed))))
    (is (= (topology @initial) (topology changed)))))

(deftest duplicate-quiescent-and-explicit-observer-reruns
  (let [state @active
        duplicate (e/publish state e/active-updates)
        quiet (runtime/run (:network state) [] e/run-options)
        observers (keep (fn [[id entry]]
                          (if (= :examples.lain.relational-datalog.proof-operators/proof-relation
                                 (:name entry)) id nil))
                        (net/net-env (:network state)))
        rerun (runtime/run (:network state) observers e/run-options)]
    (is (seq observers))
    (is (= (:network state) (:network duplicate)))
    (is (zero? (:steps duplicate)))
    (is (= :completed (:status quiet)))
    (is (= (:network state) (:network quiet)))
    (is (= :completed (:status rerun)))
    (is (= (:network state) (:network rerun)))))

(deftest immutable-branching
  (let [before @active
        branch (e/publish before [[:bc 2 :retracted value/nothing]
                                 [:dc 2 :retracted value/nothing]])]
    (is (= #{} (:facts (e/view branch))))
    (is (= #{[:a :c]} (:facts (e/view before))))
    (is (= 2 (count (live before))))
    (is (= #{} (:facts (e/view @initial))))))

(deftest rejected-candidate-never-publishes-a-transient-success
  ;; Instrumentation belongs to the test, outside Net and inference policy.
  (doseq [updates [e/active-updates (reverse e/active-updates)]]
    (let [observed (atom [])
          evaluator
          (fn [evaluated network continuations]
            (relation/evaluate-patches
             evaluated network
             (assoc continuations :success
                    (fn [tasks updated]
                      (swap! observed conj
                             (:proofs (relation/view updated (:cell @initial))))
                      ((:success continuations) tasks updated)))))
          state (e/publish @initial updates {:evaluate-patches evaluator})
          rejected-id (first (keys (candidate-with state [:ab :dc])))]
      (is (pos? (count @observed)))
      (is (some? rejected-id))
      (is (every? #(not= :active (:status (get % rejected-id))) @observed)))))

(deftest proof-relation-algebra-and-ordinary-patch-parity
  (let [[a b p q] (repeatedly 4 ids/new-node-id)
        old (relation/relation {p (e/observation a 1 :active [:a :c])})
        other (relation/relation {q (e/observation b 1 :active [:a :c])})
        retract (relation/relation {p (e/observation a 2 :retracted value/nothing)})
        join relation/merge-content]
    (is (= (join old other) (join other old)))
    (is (= (join (join old other) retract) (join old (join other retract))))
    (is (= old (join old old)))
    (is (= old (join value/nothing old)))
    (is (= #{[:a :c]}
           (get-in (relation/strongest (join old other) net/empty-net)
                   [:experiment/live-relation :facts])))
    (is (= #{[:a :c]}
           (get-in (relation/strongest (join (join old other) retract) net/empty-net)
                   [:experiment/live-relation :facts])))
    (is (= #{} (get-in (relation/strongest retract net/empty-net)
                       [:experiment/live-relation :facts])))
    (is (thrown? clojure.lang.ExceptionInfo (relation/relation {:not-cell value/nothing})))
    (is (thrown? clojure.lang.ExceptionInfo (join old 7)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (relation/strongest
                  (relation/relation {p (e/observation a 1 :active 7)}) net/empty-net))))
  (let [id (ids/new-node-id) network (nb/install-cell net/empty-net id)
        update (message/message id 7)]
    (is (= (runtime/apply-patch nil update network)
           (relation/apply-patch nil update network)))))

(deftest overlapping-proofs-are-not-compacted-against-each-other
  (let [[a b p q] (repeatedly 4 ids/new-node-id)
        short (e/observation a 1 :active [:a :c])
        support #{{:source a :timestamp 1 :premises-status :active}
                  {:source b :timestamp 1 :premises-status :active}}
        long (collection/content {:base [:a :c] :support support :premise-state support})
        combined (relation/merge-content (relation/relation {p short})
                                         (relation/relation {q long}))
        withdrawn (relation/merge-content
                   combined (relation/relation {q (e/observation b 2 :retracted value/nothing)}))]
    (is (= #{p q} (set (keys (:experiment/proof-evidence combined)))))
    (is (= 2 (count (get-in (relation/strongest combined net/empty-net)
                            [:experiment/live-relation :proofs]))))
    (is (= #{[:a :c]} (get-in (relation/strongest withdrawn net/empty-net)
                              [:experiment/live-relation :facts])))))

(deftest invalid-updates-and-patch-failures-are-explicit
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown experiment source"
                       (e/publish @initial [[:missing 1 :active [:a :b]]])))
  (is (thrown? clojure.lang.ExceptionInfo (e/publish @initial [[:ab 1 :active 7]])))
  (let [outcomes (atom [])
        continuations {:success (fn [_tasks _network] (swap! outcomes conj :success))
                       :fail (fn [_error] (swap! outcomes conj :fail))}]
    (relation/evaluate-patches {:emitter nil :activation-result {:op :unsupported}}
                              net/empty-net continuations)
    (is (= [:fail] @outcomes)))
  (is (thrown? clojure.lang.ExceptionInfo
               (relation/apply-patch nil
                                     (message/message (ids/new-node-id) relation/empty-relation)
                                     net/empty-net))))

(deftest sizes-and-new-function-arities
  (is (< (count (string/split-lines e/source)) 100))
  (doseq [file ["proof_relation.clj" "proof_operators.clj" "proof_experiment.clj"
               "proof_experiment_test.clj"]]
    (let [text (slurp (str e/directory file))
          forms (read-string (str "(" text ")"))
          functions (filter #(and (seq? %) (contains? #{'defn 'defn- 'fn} (first %)))
                            (tree-seq coll? seq forms))
          args (mapcat #(filter vector? (rest %)) functions)]
      (is (< (count (string/split-lines text)) 300) file)
      (is (every? #(<= (count %) 4) args) file))))

(defn -main [& _]
  (let [result (test/run-tests 'examples.lain.relational-datalog.proof-experiment-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
