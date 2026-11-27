(ns propagators.prototype-2.rx-cell-test
  "Cell-as-rx: add-content = spine extend + tms-reduce / incremental tms-step."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.ids :as ids]
            [propagators.prototype-2.church :as church]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.rx-cell :as rx]
            [propagators.prototype-2.support-tag :as stag]))

(deftest tms-reduce-project-matches-spine-fold
  (testing "host tms-project agrees with project-tip over a spine"
    (let [src (ids/new-node-id)
          s1 (stag/stamp src 1)
          s2 (stag/stamp src 2)
          s3 (stag/stamp src 3)
          network net/empty-network
          {network :network o1 :cell} (stag/observation-from-values network 1 s1)
          {network :network o2 :cell} (stag/observation-from-values network 2 s2)
          {network :network o3 :cell} (stag/observation-from-values network 3 s3)
          [empty network] (church/empty-list-cell network)
          {network :network list :list} (stag/extend-tip network empty o1)
          {network :network list :list} (stag/extend-tip network list o2)
          {network :network list :list} (stag/extend-tip network list o3)
          via-tip (stag/project-tip network list)
          via-reduce (stag/tms-project [[3 s3] [2 s2] [1 s1]])]
      (is (= [3 s3] via-tip))
      (is (= via-tip via-reduce)))))

(deftest rx-cell-add-content-incremental-merge
  (testing "redundant older tip does not change projected content"
    (let [src (ids/new-node-id)
          s1 (stag/stamp src 1)
          s2 (stag/stamp src 2)
          network net/empty-network
          {network :network cell :cell} (rx/empty-rx network)
          alerts (atom 0)
          cell (rx/add-neighbor cell (fn [_] (swap! alerts inc)))
          {network :network cell :cell changed? :changed?}
          (rx/add-content-values network cell 10 s1)]
      (is changed?)
      (is (= 1 @alerts))
      (is (= 10 (rx/content-base cell)))
      (let [{network :network cell :cell changed? :changed?}
            (rx/add-content-values network cell 10 s2)]
        (is changed? "newer stamp dominates → alert")
        (is (= 2 @alerts))
        (is (= s2 (rx/content-support cell)))
        (let [{_ :network cell :cell changed? :changed?}
              (rx/add-content-values network cell 10 s1)]
          (is (false? changed?) "older stamp dominated → silent")
          (is (= 2 @alerts) "no neighbor alert")
          (is (= s2 (rx/content-support cell)))
          (is (= 10 (rx/content-base cell)))))))
  (testing "reproject full tms-reduce agrees with incremental cache"
    (let [src (ids/new-node-id)
          s1 (stag/stamp src 1)
          s2 (stag/stamp src 2)
          network net/empty-network
          {network :network cell :cell} (rx/empty-rx network)
          {network :network cell :cell} (rx/add-content-values network cell 1 s1)
          {network :network cell :cell} (rx/add-content-values network cell 2 s2)
          cached (rx/content cell)
          {cell :cell} (rx/reproject network cell)]
      (is (= cached (rx/content cell)))
      (is (= [2 s2] (stag/project-tip network (rx/spine-id cell)))))))

(deftest rx-cell-ddb-style-choice-tips
  (testing "choice repairs publish tips; projected content is current chosen"
    (let [src (ids/new-node-id)
          d1 (stag/stamp src 1)
          d2 (stag/stamp src 2)
          network net/empty-network
          {network :network cell :cell} (rx/empty-rx network)
          {network :network cell :cell} (rx/add-content-values network cell 1 d1)
          {network :network cell :cell changed? :changed?}
          (rx/add-content-values network cell 2 d2)]
      (is changed?)
      (is (= 2 (rx/content-base cell)))
      (is (= d2 (rx/content-support cell)))
      ;; nogood on d1 would not move tip — d2 still strongest
      (let [{cell :cell changed? :changed?}
            (rx/add-content-values network cell 1 (stag/stamp src 1 :retracted))]
        (is (false? changed?))
        (is (= 2 (rx/content-base cell)))))))

(deftest rx-add-content-prop-in-network
  (testing "compound propagator extends rx-cell via observation cell"
    (let [src (ids/new-node-id)
          s1 (stag/stamp src 1)
          network net/empty-network
          {network :network rx0 :cell} (rx/empty-rx network)
          {network :network obs :cell} (stag/observation-from-values network 42 s1)
          c-rx (net/new-cell-id "rx")
          c-out (net/new-cell-id "rx-out")
          network (-> network
                      (church/tell-cell c-rx rx0)
                      (rx/install-add-content-prop c-rx obs c-out)
                      ev/run-tasks)
          out (net/cell-value network c-out)]
      (is (rx/rx-cell? out))
      (is (= 42 (rx/content-base out)))
      (is (= s1 (rx/content-support out))))))
