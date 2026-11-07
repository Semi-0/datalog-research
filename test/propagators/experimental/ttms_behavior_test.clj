(ns propagators.experimental.ttms-behavior-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-behavior :as behavior]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.observation :as observation]))

(defn- premise [source version status]
  {:source source :timestamp version :premises-status status})

(defn- evidence
  ([source version base] (evidence source version :active base))
  ([source version status base]
   (let [p (premise source version status)]
     (collection/content {:base base :support #{p} :premise-state #{p}}))))

(defn- merge-template []
  (let [[acc update out] (repeatedly 3 ids/new-node-id)
        initial (-> (nb/install-cells [acc update out])
                    (net/assoc-net-dict-entry :acc acc)
                    (net/assoc-net-dict-entry :update update)
                    (net/assoc-net-dict-entry :out out))
        [_ installed]
        ((prop/construct-propagator
          ::sum
          (fn [_ _ network]
            [(message out (+ (net/network-cell-strongest network acc)
                             (:value (net/network-cell-strongest network update))))])
          [acc update] [out]) initial)]
    installed))

(defn- publish [network target content]
  (let [[tasks patched] (core/eval-cell target (message target content) network)]
    (nb/run-propagators patched tasks)))

(defn- base [network id]
  (datum/layer-value (net/network-cell-strongest network id) :base))

(defn- setup []
  (let [[a b state out downstream] (repeatedly 5 ids/new-node-id)
        template (merge-template)
        [p installed] ((behavior/p:behavior {:a a :b b} template 0 state out)
                       (nb/install-cells [a b state out downstream]))
        [q wired] ((observation/identity ::forward out downstream) installed)]
    {:a a :b b :state state :out out :downstream downstream :template template
     :tasks (conj p q) :network (nb/run-propagators wired (conj p q))}))

(deftest connected-withdrawal-recovery-and-dependency-shrink
  (let [{:keys [a b state out downstream tasks network]} (setup)
        n1 (publish network a (evidence a 1 7))
        n2 (publish n1 b (evidence b 1 8))
        n3 (publish n2 a (evidence a 2 :retracted value/nothing))
        n4 (publish n3 a (evidence a 3 9))
        n5 (publish n4 b (evidence b 2 :retracted value/nothing))
        n6 (publish n5 a (evidence a 4 :retracted value/nothing))]
    (is (= [0 7 15 8 17 9 0] (mapv #(base % out) [network n1 n2 n3 n4 n5 n6])))
    (is (= [0 7 15 8 17 9 0] (mapv #(base % downstream) [network n1 n2 n3 n4 n5 n6])))
    (testing "original premises accompany the behavior's membership revision"
      (let [s (datum/support-of (net/network-cell-strongest n3 out))]
        (is (contains? s (premise b 1 :active)))
        (is (= #{state b} (set (map :source s))))))
    (is (= n4 (publish n4 a (evidence a 1 7))))
    (is (= n4 (publish n4 a (evidence a 3 9))))
    (is (= n4 (nb/run-propagators n4 tasks)))
    (is (= (set (keys (net/net-env network))) (set (keys (net/net-env n6))))))
  (testing "two independent instances do not share revisions or data"
    (let [x (setup) y (setup)
          updated (publish (:network x) (:a x) (evidence (:a x) 1 30))]
      (is (= 30 (base updated (:out x))))
      (is (= 0 (base (:network y) (:out y)))))))

(deftest compact-state-discards-old-payloads-and-keeps-replay-frontier
  (let [source (ids/new-node-id)
        states (reductions (fn [state n]
                             (behavior/advance state {:x (evidence source n n)}))
                           behavior/empty-state (range 1 1001))
        final (last states)
        withdrawal (behavior/advance final {:x (evidence source 1001 :retracted value/nothing)})
        stale (behavior/advance withdrawal {:x (evidence source 1 1)})]
    (is (= [{:slot :x :value 1000}] (:values (behavior/current final))))
    (is (every? #(<= (count (get-in % [:contributions :x :support/observations])) 1) states))
    (is (= 1 (count (:frontier final))))
    (is (= [] (:values (behavior/current withdrawal))))
    (is (= withdrawal stale))
    (is (= [{:slot :x :value 1}] (:values (behavior/current (second states)))))))

(deftest state-only-withdrawal-and-bring-in-require-fresh-evidence
  (let [source (ids/new-node-id)
        first-state (behavior/advance behavior/empty-state {:x (evidence source 1 7)})
        retract (collection/content {:premise-state #{(premise source 2 :retracted)}})
        withdrawn (behavior/advance first-state {:x retract})
        bring-in (collection/content {:premise-state #{(premise source 3 :active)}})
        empty-again (behavior/advance withdrawn {:x bring-in})
        restored (behavior/advance empty-again {:x (evidence source 3 7)})]
    (is (empty? (get-in withdrawn [:contributions :x :support/observations])))
    (is (= [] (:values (behavior/current empty-again))))
    (is (= [{:slot :x :value 7}] (:values (behavior/current restored))))))

(deftest conflicts-remain-inspectable-until-a-newer-version
  (let [source (ids/new-node-id)
        a (behavior/advance behavior/empty-state {:x (evidence source 1 7)})
        conflict (behavior/advance a {:x (evidence source 1 8)})
        repaired (behavior/advance conflict {:x (evidence source 2 9)})]
    (is (= 2 (count (get-in conflict [:contributions :x :support/observations]))))
    (is (value/contradiction? (:base (behavior/project conflict (merge-template) 0))))
    (is (= 9 (:base (behavior/project repaired (merge-template) 0))))))

(deftest global-source-frontier-invalidates-other-slots
  (let [source (ids/new-node-id)
        a (behavior/advance behavior/empty-state
                            {:x (evidence source 1 7) :y (evidence source 1 8)})
        b (behavior/advance a {:x (evidence source 2 9)})]
    (is (= [{:slot :x :value 9}] (:values (behavior/current b))))
    (is (empty? (get-in b [:contributions :y :support/observations])))))

(deftest compaction-is-associative-commutative-and-idempotent
  (let [source (ids/new-node-id)
        updates [{:x (evidence source 1 7)} {:x (evidence source 2 8)}
                 {:x (evidence source 2 9)}
                 {:x (evidence source 3 :retracted value/nothing)}]
        join (fn [a b] (behavior/advance a (:contributions b)))
        states (mapv #(behavior/advance behavior/empty-state %) updates)]
    (doseq [a states]
      (is (= a (join a a)))
      (doseq [b states]
        (is (= (join a b) (join b a)))
        (doseq [c states]
          (is (= (join (join a b) c) (join a (join b c)))))))))

(deftest one-time-merge-network-is-reusable-and-discarded
  (let [template (merge-template)
        snapshot (pr-str template)
        a (ids/new-node-id) b (ids/new-node-id)
        state (behavior/advance behavior/empty-state
                                {:a (evidence a 1 7) :b (evidence b 1 8)})]
    (is (= 15 (:base (behavior/project state template 0))))
    (is (= 25 (:base (behavior/project state template 10))))
    (is (= snapshot (pr-str template)))
    (is (value/nothing? (net/network-cell-strongest template (net/network-dict-entry template :out))))))

(deftest owned-cells-cannot-alias-inputs
  (let [a (ids/new-node-id) b (ids/new-node-id)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (behavior/p:behavior {:a a} (merge-template) 0 a b)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (behavior/p:behavior {:a a} (merge-template) 0 b b)))))

(deftest contradictory-source-status-is-not-an-empty-behavior
  (let [source (ids/new-node-id)
        active (behavior/advance behavior/empty-state {:x (evidence source 1 7)})
        conflict (behavior/advance active {:x (evidence source 1 :retracted value/nothing)})
        projection (behavior/project conflict (merge-template) 0)]
    (is (= 2 (count (:frontier conflict))))
    (is (value/contradiction? (:base projection)))
    (is (= (:frontier conflict) (:support projection)))
    (is (= 8 (:base (behavior/project
                     (behavior/advance conflict {:x (evidence source 2 8)})
                     (merge-template) 0))))))

(deftest connected-state-compaction-does-not-grow-topology
  (let [{:keys [a state out network]} (setup)
        after (reduce #(publish %1 a (evidence a %2 %2)) network (range 1 101))
        content (net/network-cell-content after state)
        retained (reducer/reducer-slots content)]
    (is (= 100 (base after out)))
    (is (reducer/reducer-cell? content))
    (is (= 1 (count (get-in retained [:contributions :a :support/observations]))))
    (is (= (count (net/net-env network)) (count (net/net-env after))))))

(deftest cell-content-and-strongest-own-the-behavior-without-a-coordinator
  (let [id (ids/new-node-id) source (ids/new-node-id)
        networks (behavior/reducer-networks id (merge-template) 0)
        update-cell (fn [network input]
                      (publish network id (behavior/candidates id networks {:x input})))
        initial (nb/install-cells [id])
        first-net (update-cell initial (evidence source 1 7))
        same-value (update-cell first-net (evidence source 2 7))
        withdrawn (update-cell same-value (evidence source 3 :retracted value/nothing))
        recovered (update-cell withdrawn (evidence source 4 9))
        strongest #(net/network-cell-strongest % id)
        result #(-> % strongest reducer/reduced-result :base)]
    (is (= [7 7 0 9] (mapv result [first-net same-value withdrawn recovered])))
    (is (not= (reducer/reduced-epoch (strongest first-net))
              (reducer/reduced-epoch (strongest same-value))))
    (is (= recovered (update-cell recovered (evidence source 1 7))))
    (is (= recovered (update-cell recovered (evidence source 4 9))))
    (is (= #{id} (set (keys (net/net-env recovered)))))
    (is (= 7 (result first-net)))
    (is (contains? (reducer/reduced-dependence (strongest recovered))
                   (premise source 4 :active)))))
