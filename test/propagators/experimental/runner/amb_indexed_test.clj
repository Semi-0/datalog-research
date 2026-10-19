(ns propagators.experimental.runner.amb-indexed-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.amb.builder :as builder]
            [experiments.amb-search.concurrent-benchmark :as inspection]
            [experiments.amb-search.indexed-runner :as indexed]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.stdlib.premise-state :as state]
            [propagators.stdlib.supported-procedure :as supported]))

(defn- premise [source timestamp status]
  {:source source :timestamp timestamp :premises-status status})

(defn- base [network id]
  (datum/layer-value (net/network-cell-strongest network id) :base))

(defn- evidence [v support]
  (collection/content {:base v :support support :premise-state support}))

(defn- install-step [built from to]
  (let [primitive (prop/primitive-propagator
                   ::increment (fn [x] (if (value/unusable? x) value/nothing (inc x))))
        [network tasks _] (supported/install (supported/from-primitive primitive)
                                             (:network built) [from] to)]
    (-> built (assoc :network network) (update :tasks into tasks))))

(defn- with-event [built current id v support]
  (let [[writer network]
        ((prop/construct-propagator
          ::event (fn [_ _ _] [(message/message id (evidence v support))]) [] [id]) current)]
    (assoc built :network network :tasks [writer])))

(defn- chain []
  (let [[a b c] (repeatedly 3 ids/new-node-id)
        initial {:network (nb/install-cells [a b c]) :tasks [] :cells {:candidates [a]}}
        built (-> initial (install-step a b) (install-step b c))
        event (with-event built (:network built) a 1 #{(premise a 1 :active)})]
    {:a a :b b :c c :built (update event :tasks into (:tasks built))}))

(deftest direct-invalidation-and-recovery-preserve-immutable-history
  (let [{:keys [a b c built]} (chain)
        initial (runner/completed-network (indexed/run built))
        index-before (indexed/index-summary initial)
        withdrawn (indexed/run (with-event built initial a value/nothing #{(premise a 2 :retracted)}))
        network (:network withdrawn)
        recovered (runner/completed-network
                   (indexed/run (with-event built network a 5 #{(premise a 3 :active)})))]
    (is (= [1 2 3] (mapv #(base initial %) [a b c])))
    (is (= :completed (:status withdrawn)))
    (is (every? #(value/nothing? (base network %)) [a b c]))
    (is (= #{(premise a 2 :retracted)} (state/states-of (net/network-cell-strongest network c))))
    (is (= 2 (get-in withdrawn [:index-stats :direct-projections])))
    (is (= 2 (get-in withdrawn [:index-stats :avoided-derivations])))
    (is (= [5 6 7] (mapv #(base recovered %) [a b c])))
    (is (= #{(premise a 3 :active)} (datum/support-of (net/network-cell-strongest recovered c))))
    (is (= [1 2 3] (mapv #(base initial %) [a b c])))
    (is (= index-before (indexed/index-summary initial)))))

(deftest alternative-evidence-remains-usable-and-rederives-its-consumer
  (let [[a b out derived] (repeatedly 4 ids/new-node-id)
        pa (premise a 1 :active) pb (premise b 1 :active)
        contents {a (evidence 10 #{pa}) b (evidence 11 #{pb})
                  out (collection/merge-content (evidence 20 #{pa}) (evidence 20 #{pb}))}
        initial (reduce-kv (fn [network id content]
                             (nb/install-cell network id content (merge/strongest-value content network)))
                           (nb/install-cells [a b out derived]) contents)
        built (install-step {:network initial :tasks [] :cells {:candidates [a b]}} out derived)
        settled (runner/completed-network (indexed/run built))
        withdrawn (indexed/run (with-event built settled a value/nothing #{(premise a 2 :retracted)}))
        last-withdrawn (indexed/run (with-event built (:network withdrawn) b value/nothing
                                               #{(premise b 2 :retracted)}))]
    (is (= 20 (base (:network withdrawn) out)))
    (is (= 21 (base (:network withdrawn) derived)))
    (is (= #{pb} (datum/support-of (net/network-cell-strongest (:network withdrawn) derived))))
    (is (= 0 (get-in withdrawn [:index-stats :avoided-derivations] 0))
        "the surviving alternative must actually recompute its consumer's support")
    (is (value/nothing? (base (:network last-withdrawn) out)))
    (is (value/nothing? (base (:network last-withdrawn) derived)))
    (is (= #{a b} (collection/referenced-sources (net/network-cell-content (:network last-withdrawn) out))))))

(deftest stale-retractions-and-equal-version-conflicts-are-not-rewritten
  (let [{:keys [a c built]} (chain)
        initial (runner/completed-network (indexed/run built))
        newer (runner/completed-network
               (indexed/run (with-event built initial a 5 #{(premise a 3 :active)})))
        stale (indexed/run (with-event built newer a value/nothing #{(premise a 2 :retracted)}))
        conflict (indexed/run (with-event built (:network stale) a value/nothing #{(premise a 3 :retracted)}))
        recovered (indexed/run (with-event built (:network conflict) a 8 #{(premise a 4 :active)}))]
    (is (= 7 (base (:network stale) c)))
    (is (= 0 (get-in stale [:index-stats :withdrawals] 0)))
    (is (value/nothing? (base (:network conflict) c)))
    (is (= #{(premise a 3 :active) (premise a 3 :retracted)}
           (state/states-of (net/network-cell-strongest (:network conflict) c))))
    (is (= 10 (base (:network recovered) c)))))

(deftest indexed-amb-preserves-search-and-only-publishes-valid-triples
  (doseq [n [4 5]
          :let [built (builder/build n builder/pythagorean?)
                reference (runner/completed-network (runner/run-network (:tasks built) (:network built)))
                seen (atom []) apply-patch runner/apply-patch
                result (with-redefs [runner/apply-patch
                                     (fn [emitter operation network]
                                       (let [[_tasks updated :as result] (apply-patch emitter operation network)
                                             output (base updated (get-in built [:cells :output]))]
                                         (when (vector? output) (swap! seen conj output))
                                         result))]
                         (indexed/run built))]]
    (is (= :completed (:status result)) (str (:error result)))
    (is (= (inspection/signature built reference) (inspection/signature built (:network result))))
    (is (every? #(apply builder/pythagorean? %) @seen))
    (is (pos? (get-in result [:index-stats :avoided-derivations])))
    (let [rerun (runner/completed-network (runner/run-network (:tasks built) (:network result)))]
      (is (= (inspection/signature built reference) (inspection/signature built rerun))))))
