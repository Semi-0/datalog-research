(ns examples.lain.relational-datalog.clause-terms-test
  (:require [clojure.string :as string]
            [clojure.test :as test :refer [deftest is]]
            [examples.lain.relational-datalog.clause-terms :as e]
            [examples.lain.relational-datalog.proof-experiment :as proofs]
            [examples.lain.relational-datalog.proof-operators :as operators]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.network :as net]))

(def fixtures
  (delay
    (into {}
          (map (fn [mode]
                 (let [initial (e/initialize mode)
                       updates (e/updates mode proofs/active-updates)]
                   [mode {:initial initial :updates updates
                          :active (e/publish initial updates)}])))
          [:compound :list])))

(defn live [state]
  (filter #(= :active (:status %)) (vals (:proofs (proofs/view state)))))

(defn topology [state] (set (keys (net/net-env (:network state)))))

(deftest checked-compound-and-linked-term-contracts
  (doseq [[mode {:keys [initial active]}] @fixtures]
    (let [withdrawn (e/publish active [[:bc 2 :retracted value/nothing]
                                     [:dc 2 :retracted value/nothing]])
          restored (e/publish withdrawn (e/updates mode [[:bc 3 :active [:b :c]]]))
          pending (e/publish initial
                             (e/updates mode [[:ab 1 :active [:a value/nothing]]
                                              [:bc 1 :active [:b :c]]]))]
      (is (= #{} (:facts (proofs/view initial))))
      (is (= #{[:a :c]} (:facts (proofs/view active))))
      (is (= 2 (count (live active))))
      (is (= 1 (count (filter #(= :contradictory (:status %))
                             (vals (:proofs (proofs/view active)))))))
      (is (= #{} (:facts (proofs/view withdrawn))))
      (is (= #{[:a :c]} (:facts (proofs/view restored))))
      (is (= #{} (:facts (proofs/view pending))))
      (is (= (topology initial) (topology restored)))
      (doseq [proof (vals (:proofs (proofs/view active)))
              premise (:support proof)]
        (is (contains? (set (vals (:sources initial))) (:source premise)))
        (is (= 1 (:timestamp premise)))))))

(deftest private-bindings-and-exact-source-support
  (doseq [[_mode {:keys [initial active]}] @fixtures]
    (let [bindings (operators/bindings-in (:network active))
          expected (set (map (fn [keys]
                               (set (map #(hash-map :source (get (:sources active) %)
                                                   :timestamp 1 :premises-status :active)
                                         keys)))
                             [[:ab :bc] [:ad :dc]]))]
      (is (= 3 (count bindings)))
      (is (= expected (set (map :support (live active)))))
      (is (= 1 (count (filter #(= :contradictory (:status %)) (vals bindings)))))
      (is (= #{} (:facts (proofs/view initial)))))))

(deftest slot-observation-does-not-write-back-to-facts
  (doseq [[mode {:keys [active updates]}] @fixtures]
    (doseq [[key _epoch _status original] updates]
      (let [projected (net/network-cell-strongest (:network active) (get (:sources active) key))
            ;; The source's outer TTMS datum and its base compound are distinct.
            base (obj/slot-value projected :base)
            readers (:readers (e/configuration mode))]
        (doseq [[_name slot] readers]
          ;; Nested named-network evidence is projected by its own strongest rule.
          (is (= (obj/slot-value original slot)
                 (obj/slot-value
                  (if (evidence/evidence-set? base)
                    (evidence/strongest base)
                    base)
                  slot))))))))

(deftest duplicate-and-stale-publication
  (doseq [[_mode {:keys [active updates]}] @fixtures]
    (let [duplicate (e/publish active updates)
          withdrawn (e/publish active [[:bc 2 :retracted value/nothing]
                                       [:dc 2 :retracted value/nothing]])
          stale (e/publish withdrawn updates)]
      (is (= (:network active) (:network duplicate)))
      (is (zero? (:steps duplicate)))
      (is (= (:network withdrawn) (:network stale)))
      (is (zero? (:steps stale))))))

(deftest late-fields-and-missing-tail-stay-pending
  (doseq [[mode {:keys [initial]}] @fixtures]
    (let [pending (e/publish initial
                             (e/updates mode [[:ab 1 :active [:a value/nothing]]
                                              [:bc 1 :active [:b :c]]]))
          repaired (e/publish pending (e/updates mode [[:ab 2 :active [:a :b]]]))]
      (is (empty? (live pending)))
      (is (= #{[:a :c]} (:facts (proofs/view repaired))))
      (is (= 1 (count (live repaired))))
      (is (= (topology initial) (topology repaired)))))
  (let [initial (get-in @fixtures [:list :initial])
        pending (e/publish initial
                           [[:ab 1 :active (obj/compound-object {:car :a :cdr value/nothing})]
                            (first (e/updates :list [[:bc 1 :active [:b :c]]]))])]
    (is (= #{} (:facts (proofs/view pending))))))

(deftest declarations-and-boundaries
  (is (thrown? clojure.lang.ExceptionInfo (e/configuration :unknown)))
  (is (thrown? clojure.lang.ExceptionInfo (e/term :compound [:a])))
  (is (thrown? clojure.lang.ExceptionInfo (e/term :unknown [:a :b])))
  (doseq [file ["compound_clause.lain" "linked_clause.lain"
               "clause_terms.clj" "clause_terms_test.clj"]]
    (let [text (slurp (str e/directory file))]
      (is (< (count (string/split-lines text)) 300) file)))
  (doseq [file ["clause_terms.clj" "clause_terms_test.clj"]]
    (let [forms (read-string (str "(" (slurp (str e/directory file)) ")"))
          functions (filter #(and (seq? %) (contains? #{'defn 'defn- 'fn} (first %)))
                            (tree-seq coll? seq forms))
          args (mapcat #(filter vector? (rest %)) functions)]
      (is (every? #(<= (count %) 4) args) file))))

(defn -main [& _]
  (let [result (test/run-tests 'examples.lain.relational-datalog.clause-terms-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
