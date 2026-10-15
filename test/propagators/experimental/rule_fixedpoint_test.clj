(ns propagators.experimental.rule-fixedpoint-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [examples.lain.amb.solver-strategy :as strategy]
            [examples.lain.amb.worker-driver :as driver]
            [examples.lain.amb.search-budget :as budget]
            [examples.lain.relational-datalog.proof-relation :as relation]
            [examples.lain.rule-fixedpoint.catalog :as catalog]
            [examples.lain.rule-fixedpoint.channels :as channels]
            [examples.lain.rule-fixedpoint.contractor :as contractor]
            [examples.lain.rule-fixedpoint.engine :as engine]
            [examples.lain.rule-fixedpoint.evidence :as evidence]
            [examples.lain.rule-fixedpoint.fixtures :as fixtures]
            [examples.lain.rule-fixedpoint.model :as model]
            [examples.lain.rule-fixedpoint.matching :as matching]
            [examples.lain.rule-fixedpoint.leapfrog :as leapfrog]
            [examples.lain.rule-fixedpoint.solvers :as solvers]
            [examples.lain.rule-fixedpoint.query :as query]
            [examples.lain.rule-fixedpoint.limits :as limits]
            [examples.lain.amb.domain-refinement :as domain]
            [propagators.cells.value :as value]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]))

(def reach-rules [(model/copy-rule :edge :reach)
                  (model/step-rule :reach :reach :edge nil)])

(defn source-specs [edges]
  (into {} (map-indexed (fn [i tuple] [(keyword (str "edge-" i)) {:predicate :edge :tuple tuple}]) edges)))

(defn build [edges mode]
  (engine/prepare reach-rules {:strategy mode :domain (vec (sort (set (mapcat identity edges))))}
                  (source-specs edges)))

(defn completed [world]
  (let [result (engine/run world)]
    (if (= :completed (:status result)) result (throw (:error result)))))

(deftest finite-declarations-and-correlated-bindings
  (is (thrown? clojure.lang.ExceptionInfo
               (model/checked-rule {:id :bad :head [:x [:a :d]] :body [[:x [:a :b]]]})))
  (is (= {:x 1 :y 2} (model/bind-tuple {} [:x :y] [1 2])))
  (is (nil? (model/bind-tuple {} [:x :x] [1 2])))
  (is (nil? (model/bind-tuple {:x 3} [:x :y] [1 2]))))

(deftest recursive-derivation-and-stable-topology
  (doseq [mode [:indexed-full :indexed-delta]]
    (let [built (build [[0 1] [1 2] [2 3]] mode)
          initial-size (count (net/net-env (:network built)))
          result (completed (engine/seed built))
          rerun (completed result)]
      (is (= #{[0 1] [1 2] [2 3] [0 2] [1 3] [0 3]} (engine/facts result :reach)))
      (is (= (:network result) (:network rerun)))
      ;; TTMS datum slots may be added; a quiescent rerun must add nothing.
      (is (<= initial-size (:topology (engine/metrics result))))
      (is (zero? (:steps rerun))))))

(deftest cycle-withdrawal-does-not-self-support
  (doseq [mode [:indexed-full :indexed-delta]]
    (let [rules [(model/copy-rule :seed :p) (model/copy-rule :p :q) (model/copy-rule :q :p)]
          built (engine/prepare rules {:strategy mode} {:ground {:predicate :seed :tuple [1 2]}})
          live (completed (engine/seed built))
          withdrawn (completed (engine/inject live :ground value/nothing {:timestamp 2 :status :retracted}))
          restored (completed (engine/inject withdrawn :ground [1 2] {:timestamp 3 :status :active}))]
      (is (= #{[1 2]} (engine/facts live :q)))
      (is (empty? (engine/facts withdrawn :seed)))
      (is (empty? (engine/facts withdrawn :p)))
      (is (empty? (engine/facts withdrawn :q)))
      (is (= #{[1 2]} (engine/facts restored :q))))))

(deftest diamond-alternative-proofs-and-incremental-restoration
  (doseq [mode [:indexed-full :indexed-delta]]
    (let [live (completed (engine/seed (build [[0 1] [1 3] [0 2] [2 3]] mode)))
          withdrawn (completed (engine/inject live :edge-0 value/nothing {:timestamp 2 :status :retracted}))
          removed (completed (engine/inject withdrawn :edge-2 value/nothing {:timestamp 2 :status :retracted}))
          restored (completed (engine/inject removed :edge-0 [0 1] {:timestamp 3 :status :active}))
          proofs (:proofs (relation/view (:network live) (get-in live [:cells :relations :reach])))]
      (is (= 2 (count (filter #(and (= :active (:status %)) (= [0 3] (:base %))) (vals proofs)))))
      (is (contains? (engine/facts withdrawn :reach) [0 3]))
      (is (not (contains? (engine/facts removed :reach) [0 3])))
      (is (contains? (engine/facts restored :reach) [0 3])))))

(deftest lain-rule-catalog-and-swappable-matchers
  (let [catalog (catalog/compile-source (slurp (io/resource "examples/lain/rule_fixedpoint/reach.lain")))
        options (solvers/attach (:rules catalog)
                                {:strategy :indexed-full :domain [0 1 2]
                                 :demands {(:id (second (:rules catalog))) {:z 2}}} catalog)
        initial (completed (engine/seed (engine/prepare (:rules catalog) options (source-specs [[0 1] [1 2]]))))
        pool (driver/pool 4)]
    (try
      (binding [strategy/*executor* pool]
        (reduce
         (fn [previous mode]
           (let [world (completed (engine/select-strategy previous mode))]
             (is (= #{[0 1] [1 2] [0 2]} (engine/facts world :reach)) (str mode))
             (is (= (get-in initial [:cells :sources]) (get-in world [:cells :sources])))
             (is (= (:topology (engine/metrics initial)) (:topology (engine/metrics world))))
             (is (= (mapv #(net/network-cell-content (:network initial) %)
                          (vals (get-in initial [:cells :relations])))
                    (mapv #(net/network-cell-content (:network world) %)
                          (vals (get-in initial [:cells :relations])))))
             (is (every? #(= mode (:strategy (channels/base (:network world) %)))
                         (vals (get-in world [:cells :progress]))))
             (is (> (:matcher-calls (engine/metrics world)) (:matcher-calls (engine/metrics previous))))
             world)) initial solvers/modes)
        (is (thrown? clojure.lang.ExceptionInfo (engine/select-strategy initial :unprepared))))
      (finally (driver/close! pool)))))

(deftest shared-lain-guard-and-backward-head-demand
  (let [rule (assoc (model/step-rule :result :left :right "(network (x y z) (= (+ x y) z))")
                    :id :arithmetic-guard)
        sources {:a {:predicate :left :tuple [1 2]}
                 :b {:predicate :right :tuple [2 3]}
                 :c {:predicate :right :tuple [2 4]}}
        options (solvers/attach [rule] {:strategy :indexed-delta :domain [1 2 3 4] :demand {:x 1}} {})
        result (completed (engine/seed (engine/prepare [rule] options sources)))]
    (is (= #{[1 3]} (engine/facts result :result)))
    (is (= #{[2 3] [2 4]} (engine/facts result :right)))
    (is (not (contains? (engine/facts result :result) [1 4])))))

(deftest repeated-predicate-occurrences-and-leapfrog-parity
  (let [rule (model/step-rule :p :p :p nil)
        request {:rule rule :variables (model/variables rule) :demand {} :initial? false
                 :tables [#{[0 1] [1 2] [2 3]} #{[0 1] [1 2] [2 3]}]
                 :deltas [#{[0 1]} #{[2 3]}]}]
    (is (= #{ {:x 0 :y 1 :z 2} {:x 1 :y 2 :z 3}}
           (:bindings (matching/indexed :indexed-delta request))))
    (is (= (:bindings (matching/indexed :indexed-full request)) (leapfrog/bindings request)))
    (is (= #{{:x 1 :y 2 :z 3}}
           (leapfrog/bindings (assoc request :demand {:x 1}))))))

(defn completed-domain [world]
  (let [result (contractor/run world)]
    (if (= :completed (:status result)) result (throw (:error result)))))

(deftest bidirectional-supported-domain-contractor
  (let [bounded (completed-domain (contractor/seed (contractor/prepare 8)))
        known (-> bounded
                  (contractor/inject :y [2] {:timestamp 1 :status :active})
                  (contractor/inject :z [5] {:timestamp 1 :status :active}) completed-domain)
        withdrawn (completed-domain (contractor/inject known :y [] {:timestamp 2 :status :retracted}))
        restored (completed-domain (contractor/inject withdrawn :y [1] {:timestamp 3 :status :active}))]
    (is (= [#{3} #{2} #{5}] (mapv domain/candidates (contractor/domains known))))
    (is (= [(set (range 6)) (set (range 6)) #{5}]
           (mapv domain/candidates (contractor/domains withdrawn))))
    (is (= [#{4} #{1} #{5}] (mapv domain/candidates (contractor/domains restored))))
    (is (= (:network restored) (:network (completed-domain restored))))))

(deftest impossible-domain-is-local-and-recovers
  (let [known (-> (contractor/seed (contractor/prepare 4))
                  (contractor/inject :y [4] {:timestamp 1 :status :active})
                  (contractor/inject :z [1] {:timestamp 1 :status :active}) completed-domain)
        repaired (completed-domain (contractor/inject known :y [0] {:timestamp 2 :status :active}))]
    (is (every? value/contradiction? (contractor/domains known)))
    (is (= [#{1} #{0} #{1}] (mapv domain/candidates (contractor/domains repaired))))))

(deftest exact-grounding-and-no-invented-source-epochs
  (let [built (build [[0 1] [1 2]] :indexed-delta)
        live (completed (engine/seed built))
        proof (first (filter #(= [0 2] (:base %))
                             (vals (:proofs (relation/view (:network live) (get-in live [:cells :relations :reach]))))))
        expected (set (concat (vals (get-in live [:cells :sources])) (vals (get-in live [:cells :authorities]))))
        duplicate (completed (engine/inject live :edge-0 [0 1] {:timestamp 1 :status :active}))]
    (is (= expected (set (map :source (:support proof)))))
    (is (every? #(= 1 (:timestamp %)) (:support proof)))
    (is (every? #(contains? (net/net-env (:network live)) %) expected))
    (is (= (count (net/net-env (:network built))) (count (net/net-env (:network live)))))
    (is (= (:network live) (:network duplicate)))))

(deftest rule-authority-withdrawal-and-reactivation
  (doseq [mode [:indexed-full :indexed-delta]]
    (let [live (completed (engine/seed (build [[0 1] [1 2]] mode)))
          rule (:id (second reach-rules))
          withdrawn (completed (engine/rule-state live rule {:timestamp 2 :status :retracted}))
          restored (completed (engine/rule-state withdrawn rule {:timestamp 3 :status :active}))]
      (is (not (contains? (engine/facts withdrawn :reach) [0 2])))
      (is (= (engine/facts live :reach) (engine/facts restored :reach))))))

(deftest same-tuple-new-proof-is-a-real-delta
  (doseq [mode [:indexed-full :indexed-delta]]
    (let [specs {:a {:predicate :edge :tuple [0 1]} :b {:predicate :edge :tuple [0 1]}
                 :c {:predicate :edge :tuple [1 2]}}
          built (engine/prepare reach-rules {:strategy mode} specs)
          first (-> built (engine/inject :a [0 1] {:timestamp 1 :status :active})
                    (engine/inject :c [1 2] {:timestamp 1 :status :active}) completed)
          second (completed (engine/inject first :b [0 1] {:timestamp 1 :status :active}))
          one (completed (engine/inject second :a value/nothing {:timestamp 2 :status :retracted}))
          none (completed (engine/inject one :b value/nothing {:timestamp 2 :status :retracted}))
          proofs (vals (:proofs (relation/view (:network second) (get-in second [:cells :relations :reach]))))]
      (is (= 2 (count (filter #(and (= :active (:status %)) (= [0 2] (:base %))) proofs))))
      (is (contains? (engine/facts one :reach) [0 2]))
      (is (not (contains? (engine/facts none :reach) [0 2]))))))

(deftest control-demand-narrows-matches-without-abduction
  (let [rule (model/step-rule :answer :left :right nil)
        specs {:a {:predicate :left :tuple [1 2]} :b {:predicate :left :tuple [3 2]}
               :c {:predicate :right :tuple [2 4]}}
        live (completed (engine/seed (engine/prepare [rule] {:strategy :indexed-delta :demand {:x 1}} specs)))
        moved (completed (engine/restrict live (:id rule) {:x 3}))
        absent (completed (engine/restrict moved (:id rule) {:x 99}))
        state #(channels/base (:network %) (get-in % [:cells :progress (:id rule)]))]
    (is (= #{{:x 1 :y 2 :z 4}} (:bindings (state live))))
    (is (= #{{:x 3 :y 2 :z 4}} (:bindings (state moved))))
    (is (empty? (:bindings (state absent))))
    (is (= #{[1 2] [3 2]} (engine/facts absent :left)))
    (is (not (contains? (engine/facts absent :answer) [99 4])))))

(deftest contradictions-withdraw-and-repair-without-becoming-facts
  (let [live (completed (engine/seed (build [[0 1] [1 2]] :indexed-delta)))
        contradictory (completed (engine/inject live :edge-0 value/contradiction {:timestamp 2 :status :active}))
        repaired (completed (engine/inject contradictory :edge-0 [0 1] {:timestamp 3 :status :active}))
        delayed (completed (engine/inject repaired :edge-0 [2 0] {:timestamp 1 :status :active}))]
    (is (not (contains? (engine/facts contradictory :reach) [0 2])))
    (is (= (engine/facts live :reach) (engine/facts repaired :reach)))
    (is (= (engine/facts repaired :reach) (engine/facts delayed :reach)))))

(deftest graph-fixtures-match-independent-bfs
  (doseq [fixture [(fixtures/chain 4) (fixtures/diamond 2)
                   (fixtures/graph-case :cycle (range 4) [[0 1] [1 2] [2 3] [3 0]])
                   (fixtures/graph-case :disconnected (range 6) [[0 1] [1 2] [4 5]])]]
    (let [result (completed (engine/seed (engine/prepare reach-rules {:strategy :indexed-delta} (:sources fixture))))]
      (is (= (get-in fixture [:expected :reach]) (engine/facts result :reach))))))

(deftest initially-empty-database-accepts-later-grounding
  (let [empty (completed (build [[0 1] [1 2]] :indexed-delta))
        populated (completed (engine/seed empty))
        withdrawn (completed (engine/inject populated :edge-0 value/nothing {:timestamp 2 :status :retracted}))]
    (is (empty? (engine/facts empty :reach)))
    (is (zero? (:proofs (engine/metrics empty))))
    (is (= #{[0 1] [1 2] [0 2]} (engine/facts populated :reach)))
    (is (= #{[1 2]} (engine/facts withdrawn :reach)))))

(deftest guard-contract-and-lexical-capture
  (let [compiled (catalog/compile-source
                  "(let-cell [] (define limit 2) (rule-set
                    (step-rule :answer :left :right
                      (network (a b c) (and (< a limit) (= (+ a b) c))))))")
        rules (:rules compiled)
        specs {:a {:predicate :left :tuple [1 2]} :b {:predicate :right :tuple [2 3]}
               :c {:predicate :left :tuple [2 1]} :d {:predicate :right :tuple [1 3]}}
        options (solvers/attach rules {:strategy :indexed-delta :domain [1 2 3]} compiled)
        result (completed (engine/seed (engine/prepare rules options specs)))]
    (is (= #{[1 3]} (engine/facts result :answer)))
    (is (thrown? clojure.lang.ExceptionInfo (engine/prepare rules {:strategy :indexed-full} specs)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (query/compile-template (model/step-rule :answer :left :right (fn [_ _ _] true)) {})))
    (is (thrown? clojure.lang.ExceptionInfo (model/checked-catalog [(first rules) (first rules)])))))

(deftest unresolved-guard-is-not-a-false-predicate
  (let [rule (model/step-rule :answer :left :right "(network (x y z) (let-cell [pending] pending))")
        options (solvers/attach [rule] {:strategy :indexed-full :domain [1 2 3]} {})
        specs {:a {:predicate :left :tuple [1 2]} :b {:predicate :right :tuple [2 3]}}
        result (engine/run (engine/seed (engine/prepare [rule] options specs)))]
    (is (= :failed (:status result)))
    (is (instance? Throwable (:error result)))
    (is (empty? (engine/facts result :answer)))))

(deftest leapfrog-preserves-heterogeneous-source-identity
  (let [rule (model/step-rule :answer :left :right nil)
        request {:rule rule :variables (model/variables rule) :demand {}
                 :tables [#{[1 :via] ["1" :via]} #{[:via :end]}]}]
    (is (= #{{:x 1 :y :via :z :end} {:x "1" :y :via :z :end}} (leapfrog/bindings request)))))

(deftest aggregate-budget-is-explicit-not-empty-answers
  (let [scope (budget/scope (assoc limits/defaults :activations 1))
        advance ((limits/transform scope) (fn [state _] state))
        state {:network net/empty-net :tasks (tq/into-queue [(ids/new-node-id)])}]
    (is (= state (advance state {})))
    (let [error (try (advance state {}) nil (catch Throwable error error))]
      (is (some? error))
      (is (limits/exhausted? error)))))

(deftest same-epoch-payload-conflict-invalidates-dependent-proofs
  (let [live (completed (engine/seed (build [[0 1] [1 2]] :indexed-delta)))
        conflict (completed (engine/inject live :edge-0 [2 0] {:timestamp 1 :status :active}))
        repaired (completed (engine/inject conflict :edge-0 [0 1] {:timestamp 2 :status :active}))
        source (get-in conflict [:cells :sources :edge-0])
        proofs (vals (:proofs (relation/view (:network conflict) (get-in conflict [:cells :relations :reach]))))]
    (is (= #{[1 2]} (engine/facts conflict :edge)))
    (is (not (contains? (engine/facts conflict :reach) [0 2])))
    (is (some #(and (= :contradictory (:status %))
                    (some (fn [premise] (= source (:source premise))) (:support %))
                    (every? (fn [premise] (= 1 (:timestamp premise))) (:support %))) proofs))
    (is (= (engine/facts live :reach) (engine/facts repaired :reach)))))

(deftest proof-identity-is-canonical-not-printed-map-order
  (let [rule (model/step-rule :head :left :right nil)
        a (ids/->NodeId (java.util.UUID. 0 0))
        b (ids/->NodeId (java.util.UUID. 0 4294967297))
        pa {:source a :timestamp 1 :premises-status :active}
        pb {:source b :timestamp 1 :premises-status :active}
        forward (array-map :x 1 :y 2 :z 3)
        reverse (array-map :z 3 :y 2 :x 1)]
    (is (= (hash a) (hash b)))
    (is (= (evidence/proof-id rule forward #{pa}) (evidence/proof-id rule reverse #{pa})))
    (is (= (evidence/proof-id rule forward (into #{} [pa pb]))
           (evidence/proof-id rule forward (into #{} [pb pa]))))
    (is (= (evidence/proof-id rule forward #{pa})
           (evidence/proof-id rule forward #{(assoc pa :timestamp 2 :premises-status :retracted)})))))

(deftest domain-representatives-stabilize-equal-node-encodings
  (let [specs (source-specs [[(bigint 0) (bigint 1)] [(bigint 1) (bigint 2)]])
        world (completed (engine/seed (engine/prepare reach-rules
                                                     {:strategy :indexed-delta :domain [0 1 2]} specs)))]
    (is (= #{[0 1] [1 2] [0 2]} (engine/facts world :reach)))
    (is (every? #(instance? Long %) (mapcat identity (engine/facts world :reach))))))
