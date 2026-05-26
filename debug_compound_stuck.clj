;; One-off debug: prove why compound run-tasks hangs (not loaded in prod).
(ns debug-compound-stuck
  (:require [propagators.network :as net]
            [propagators.graph :as graph]
            [propagators.core :as core]
            [propagators.propagator :as prop]
            [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.stdlib :refer [bi-sync-closure]]
            [propagators.closure :as closure]
            [propagators.network :refer [construct-cell]]))

(def ^:dynamic *step* 0)
(def ^:dynamic *max-steps* 35)

(defn- run-tasks-instrumented [tasks n]
  (loop [ts (tq/into-queue tasks)
         n' n
         step *step*]
    (cond
      (tq/queue-empty? ts) n'
      (>= step *max-steps*)
      (do (println "  STOP: hit max-steps (infinite loop)") n')
      :else
      (let [[current-id remaining] (tq/pop-task ts)
            v (get (net/net-env n') current-id)]
        (println (format "  step %3d | %s | queue-left %d"
                         step
                         (cond (prop/prop? v) "prop" (cell/cell? v) "cell" :else "?")
                         (count (:task-queue/q remaining))))
        (when (prop/prop? v)
          (let [node (graph/get-node (net/net-graph n') current-id)]
            (println "    in " (graph/node-input-ids node))
            (println "    out" (graph/node-output-ids node))))
        (let [[next-tasks next-n] (core/eval-propagator current-id remaining n')]
          (recur next-tasks next-n (inc step)))))))

(defn- build-single-compound []
  (let [cells (vec (repeatedly 3 new-node-id))
        [c0 c1] cells
        k-in (new-node-id)
        cv bi-sync-closure
        n (reduce (fn [net id] (second ((construct-cell id) net)))
                  net/empty-net
                  (into cells [k-in]))
        n (net/assoc-net-cell n k-in (cell/cell cv cv))
        [p n'] ((closure/compound-propagator k-in [c0 c1] [c0 c1]) n)]
    {:net n' :c0 c0 :c1 c1 :compound-prop p :k-in k-in}))

(defn -main []
  (alter-var-root #'core/run-tasks (constantly run-tasks-instrumented))
  (let [{:keys [net c0 c1 k-in compound-prop]} (build-single-compound)
        activate (prop/prop-f (get (net/net-env net) compound-prop))
        input-ids (into [k-in] [c0 c1])
        output-ids (into [k-in] [c0 c1])
        n-seed (net/assoc-net-cell net c0 (cell/cell 7 7))
        boundary-in (#'closure/boundary-nodes k-in input-ids)
        out (#'closure/boundary-nodes k-in output-ids)
        [boundary-out net*] (#'closure/create-boundary-outputs n-seed out)
        net' (closure/apply-network-closure bi-sync-closure boundary-in boundary-out net*)
        popped (vec (pop-inputs boundary-in (net/net-graph net')))]
    (println "=== pop-inputs boundary-in after bi-sync ===")
    (println "ids:" popped)
    (println "includes compound propagator?" (boolean (some #{compound-prop} popped)))
    (println)
    (println "=== compound activate (instrumented run-tasks inside) ===")
    (binding [*step* 0]
      (activate input-ids output-ids n-seed))
    (println "FINISHED OK")))
