(ns propagators.experimental.runner.amb-concurrent-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.amb.builder :as builder]
            [examples.lain.amb.worker-driver :as workers]
            [experiments.amb-search.concurrent-benchmark :as benchmark]
            [experiments.amb-search.concurrent-runner :as concurrent]
            [propagators.cells.value :as value]
            [propagators.experimental.amb.observation :as observation]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- fixture [cells installers]
  (reduce (fn [built installer]
            (let [[id network] (installer (:network built))]
              {:network network :tasks (conj (:tasks built) id)}))
          {:network (nb/install-cells cells) :tasks []} installers))

(defn- counted-run [execute]
  (let [calls (atom 0)
        activate runner/evaluate-propagator
        result (with-redefs [runner/evaluate-propagator
                            (fn [id network handlers]
                              (swap! calls inc)
                              (activate id network handlers))]
                 (execute))]
    {:result result :activations @calls}))

(deftest worker-activations-overlap
  (let [[x y] (repeatedly 2 ids/new-node-id)
        ready (CountDownLatch. 2)
        make-prop (fn [output]
                    (prop/construct-propagator
                     ::overlap
                     (fn [_ _ _]
                       (.countDown ready)
                       (when-not (.await ready 2 TimeUnit/SECONDS)
                         (throw (ex-info "Workers did not overlap" {})))
                       [(message/message output 1)]) [] [output]))
        built (fixture [x y] [(make-prop x) (make-prop y)])
        accesses (zipmap (:tasks built) [(concurrent/access [] [x]) (concurrent/access [] [y])])
        pool (workers/pool 2)]
    (try
      (let [result (concurrent/run pool 2 built (fn [_ id] (get accesses id)))]
        (is (= 2 (count (:threads result))))
        (is (= [1 1] (mapv #(observation/cell-base (:network result) %) [x y]))))
      (finally (workers/close! pool)))))

(deftest dependent-activation-waits-for-its-input-without-recomputation
  (doseq [early-error? [false true]]
    (let [[x y] (repeatedly 2 ids/new-node-id)
          seen (atom []) calls (atom 0)
          first-prop (prop/construct-propagator
                      ::first (fn [_ _ _] [(message/message x 1)]) [] [x])
          second-prop (prop/construct-propagator
                       ::second
                       (fn [_ _ network]
                         (swap! calls inc)
                         (let [input (observation/cell-base network x)]
                           (if (value/nothing? input)
                             (if early-error?
                               (throw (ex-info "Dependency was evaluated before its writer" {}))
                               [(message/message y 99)])
                             [(message/message y (inc input))]))) [x] [y])
          built (fixture [x y] [first-prop second-prop])
          accesses (zipmap (:tasks built) [(concurrent/access [] [x]) (concurrent/access [x] [y])])
          pool (workers/pool 2)
          apply-patches runner/evaluate-patches]
      (try
        (let [result (with-redefs [runner/evaluate-patches
                                  (fn [evaluated network handlers]
                                    (apply-patches
                                     evaluated network
                                     (update handlers :success
                                             (fn [success]
                                               (fn [tasks updated]
                                                 (swap! seen conj (observation/cell-base updated y))
                                                 (success tasks updated))))))]
                       (concurrent/run pool 2 built (fn [_ id] (get accesses id))))]
          (is (= 2 (observation/cell-base (:network result) y)))
          (is (not-any? #{99} @seen))
          (is (= 2 (get-in result [:metrics :commits])) "pending tasks retain normal deduplication")
          (is (= 1 @calls) "the dependent activation is calculated exactly once"))
        (finally (workers/close! pool))))))

(deftest current-activation-error-preserves-identity
  (let [error (ex-info "Current failure" {})
        built (fixture [] [(prop/construct-propagator ::fail (fn [_ _ _] (throw error)) [] [])])
        pool (workers/pool 1)]
    (try
      (is (identical? error (try (concurrent/run pool 1 built) nil (catch Throwable actual actual))))
      (finally (workers/close! pool)))))

(deftest amb-fifo-answer-learning-and-retraction-parity
  (let [pool (workers/pool 4)]
    (try
      (doseq [n [4 5]
              :let [built (builder/build n builder/pythagorean?)
                    reference (counted-run #(runner/completed-network
                                            (runner/run-network (:tasks built) (:network built))))
                    expected (benchmark/signature built (:result reference))]
              width [2 4]]
        (let [measured (counted-run #(concurrent/run pool width built (benchmark/access-plan built)))
              result (:result measured)
              replay (runner/completed-network (runner/run-network (:tasks built) (:network result)))]
          (is (= expected (benchmark/signature built (:network result))))
          (is (= expected (benchmark/signature built replay)))
          (is (= (:activations reference) (:activations measured))
              "concurrent admission performs exactly the FIFO activation count, including nested propagation")
          (is (> (get-in result [:metrics :max-batch]) 1))
          (is (pos? (get-in result [:metrics :parallel-batches])))))
      (finally (workers/close! pool)))))
