(ns propagators.effectful-execution
  "Helpers for activation-local effectful subnet execution."
  (:require [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.core :refer [run-tasks]]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.stdlib :as stdlib]))

(defn- effect-tap-key [outer-id]
  [:effect-tap outer-id])

(defn- remove-effect-tap [subnet outer-id]
  (net/net-with-dict subnet (dissoc (net/net-dict-or-empty subnet)
                                    (effect-tap-key outer-id))))

(defn- install-effect-tap [subnet updated* outer-id]
  (let [avatar-id (net/network-dict-entry subnet outer-id)
        [tap-id subnet'] (((stdlib/mark-updated-tap updated* outer-id) avatar-id) subnet)]
    (net/assoc-net-dict-entry subnet' (effect-tap-key outer-id) tap-id)))

(defn hook-output-taps
  "Install fresh activation-local taps for `outer-ids`."
  [subnet outer-ids updated*]
  (reduce
   (fn [n outer-id]
     (-> n
         (remove-effect-tap outer-id)
         (install-effect-tap updated* outer-id)))
   subnet
   outer-ids))

(defn effectful-tasks
  "Build a task queue from seed cell ids in `subnet`."
  [subnet seed-ids]
  (tq/into-queue (pop-inputs seed-ids (net/net-graph subnet))))

(defn execute-subnet
  "Run `exec-net` with activation-local hooks.

  `hook-subnet` receives `[subnet updated*]`.
  `seed-ids` receives the hooked subnet and returns cell ids to seed."
  [exec-net hook-subnet seed-ids]
  (let [updated* (atom #{})
        subnet (hook-subnet exec-net updated*)
        tasks (effectful-tasks subnet (seed-ids subnet))]
    [exec-net (run-tasks tasks subnet) updated*]))

(defn project-stable-cells
  "Copy stable cell entries from executed `after` back into `stable-net`."
  [stable-net after cell-ids]
  (reduce
   (fn [n cell-id]
     (net/assoc-net-cell n cell-id (net/network-lookup-cell after cell-id)))
   stable-net
   cell-ids))
