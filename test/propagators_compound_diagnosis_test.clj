(ns propagators-compound-diagnosis-test
  "Proof tests for compound inner scheduling (avatar boundaries).
  Run: clj -M:test propagators-compound-diagnosis-test"
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell :refer [construct-cell]]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.closure :as closure]
            [propagators.propagator :refer [compound-propagator]]
            [propagators.core :as core :refer [run-tasks]]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.stdlib.boundary :refer [bi-sync-closure]]))

(defn- boundary-nodes [closure-cell-id nodes]
  (vec (remove #(= closure-cell-id %) nodes)))

(defn- build-single-bi-sync-compound []
  (let [cells (vec (repeatedly 2 new-node-id))
        [c0 c1] cells
        k-in (new-node-id)
        cv bi-sync-closure
        n (reduce (fn [net id] (second ((construct-cell id) net)))
                  net/empty-net
                  (into cells [k-in]))
        n (net/assoc-net-cell n k-in (cell/cell cv cv))
        [compound-prop n'] ((compound-propagator k-in [c0 c1] [c0 c1]) n)]
    {:net n' :c0 c0 :c1 c1 :k-in k-in :compound-prop compound-prop}))

(defn- run-prop [n prop-id]
  (run-tasks (tq/enqueue tq/empty-queue prop-id) n))

(defn- net-before-inner-run-tasks
  "Same wiring as `compound-activate` through `apply-network-closure`, before `run-tasks`."
  [seed-val]
  (let [{:keys [net c0 c1 k-in compound-prop]} (build-single-bi-sync-compound)
        ins (boundary-nodes k-in (into [k-in] [c0 c1]))
        outs [c0 c1]
        n-seed (net/assoc-net-cell net c0 (cell/cell seed-val seed-val))
        n* (-> n-seed
               net/clear-dict
               (closure/create-boundary-outputs outs)
               (closure/create-boundary-inputs ins))
        net' (closure/apply-network-closure bi-sync-closure n*)
        boundary-inputs (vec (net/inner-ids-in net'))]
    {:net net' :ins ins :outs outs :boundary-inputs boundary-inputs :compound-prop compound-prop}))

(defn- run-tasks-with-step-budget!
  [max-steps state tasks n]
  (loop [ts (tq/into-queue tasks)
         n' n
         steps 0]
    (cond
      (tq/queue-empty? ts)
      (do (vreset! state {:exhausted? false :steps steps :queue-left 0})
          n')

      (>= steps max-steps)
      (do (vreset! state {:exhausted? true :steps steps :queue-left (count (:task-queue/q ts))})
          n')

      :else
      (let [[current-id remaining] (tq/pop-task ts)
            [next-tasks next-n] (core/eval-propagator current-id remaining n')]
        (recur next-tasks next-n (inc steps))))))

(deftest compound-pop-inputs-on-avatars-excludes-self
  (testing "fix: inner run-tasks seeds from input avatars, not parent-wired real cells"
    (let [{:keys [net boundary-inputs compound-prop ins]}
          (net-before-inner-run-tasks 7)
          g (net/net-graph net)
          avatar-tasks (vec (pop-inputs boundary-inputs g))
          real-tasks (vec (pop-inputs ins g))]
      (is (not (some #{compound-prop} avatar-tasks))
          "avatars only link to p:nothing + closure wiring, not the compound")
      (is (some #{compound-prop} real-tasks)
          "real boundary cells still output to the compound in the parent graph")
      (is (>= (count (filter #{compound-prop} real-tasks)) 2)))))

(deftest compound-inner-run-tasks-terminates-on-avatar-seed
  (testing "inner fixpoint from pop-inputs boundary-inputs reaches fixpoint"
    (let [state (volatile! nil)]
      (with-redefs [core/run-tasks (fn [tasks n] (run-tasks-with-step-budget! 40 state tasks n))]
        (let [{:keys [net boundary-inputs]} (net-before-inner-run-tasks 7)
              tasks (pop-inputs boundary-inputs (net/net-graph net))]
          (run-tasks tasks net)
          (let [{:keys [exhausted? steps]} @state]
            (is (false? exhausted?))
            (is (< steps 40) (str "finished in " steps " steps"))))))))

(deftest compound-second-activate-still-propagates
  (testing "second run-prop still updates when boundary inputs unchanged"
    (let [{:keys [net c0 c1 compound-prop]} (build-single-bi-sync-compound)
          n1 (-> net (net/assoc-net-cell c0 (cell/cell 7 7)) (run-prop compound-prop))
          n2 (run-prop n1 compound-prop)]
      (is (= 7 (cell/cell-strongest (net/env-get (net/net-env n2) c0))))
      (is (= 7 (cell/cell-strongest (net/env-get (net/net-env n2) c1)))))))

(deftest compound-activate-inner-seed-uses-avatars-not-real-ins
  (testing "the fix is which node-ids compound-activate passes to pop-inputs (avatars, not ins)"
    (let [{:keys [net boundary-inputs ins compound-prop]}
          (net-before-inner-run-tasks 7)
          g (net/net-graph net)]
      (is (not= ins boundary-inputs))
      (is (not (some #{compound-prop} (pop-inputs boundary-inputs g))))
      (is (some #{compound-prop} (pop-inputs ins g))
          "real ins still touch the compound in the graph; only the inner seed choice avoids the loop"))))
