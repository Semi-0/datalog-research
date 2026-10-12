(ns propagators.experimental.runner.profiling-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.experimental.runner.profiling :as profiling]
            [propagators.runner :as runner]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.ids :as ids]))

(deftest nested-time-is-exclusive
  (let [time (atom 0) profile (profiling/profiler #(deref time))
        child (profiling/phase profile :child #(swap! time + 3))
        parent (profiling/phase profile :parent
                                #(do (swap! time + 2) (child) (swap! time + 2) :result))]
    (is (= :result (parent)))
    (is (= {:calls 1 :total-ns 7 :self-ns 4 :max-ns 7}
           (get @(:timings profile) :parent)))
    (is (= 3 (get-in @(:timings profile) [:child :self-ns])))))

(deftest exceptions-retain-identity-and-record-time
  (let [profile (profiling/profiler) error (ex-info "failure" {})
        f (profiling/phase profile :f #(throw error))]
    (is (identical? error (try (f) (catch Throwable caught caught))))
    (is (= 1 (get-in @(:timings profile) [:f :calls])))))

(deftest profiled-runner-preserves-network-and-quiescence
  (let [a (ids/new-node-id) b (ids/new-node-id)
        network (-> net/empty-net (nb/ensure-cell a) (nb/ensure-cell b) (nb/seed-cell a 42))
        [id installed] (((prop/concrete-primitive-propagator :profile/identity identity) a b) network)
        expected (runner/run-network [id] installed)
        profile (profiling/profiler)
        actual (with-redefs [runner/network-iterator #(profiling/network-iterator profile)]
                 (runner/run-network [id] installed))]
    (is (= :completed (:status actual)))
    (is (= (:network expected) (:network actual)))
    (is (= 42 (net/network-cell-strongest (:network actual) b)))
    (is (pos? (:transitions (:steps (profiling/report profile)))))
    (is (some #(= :runner/patches (:label %)) (:timings (profiling/report profile))))))
