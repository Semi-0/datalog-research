(ns propagators-compound-profile
  "Profile compound chains.
  Usage:
    clj -M:propagators-profile [chain-len]           ; isolated compound-activate phases
    clj -M:propagators-profile propagate [chain-len] ; real middle-inject cost breakdown"
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :refer [diff-cells]]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.core :as core]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.message :as m :refer [message-value]]
            [propagators.network :as net :refer [construct-cell construct-propagator]]
            [propagators.propagator :as prop :refer [prop?]]
            [propagators.stdlib :refer [bi-sync-closure p:id]]))

(defn- ms [ns] (/ (double ns) 1e6))

(defn- fmt [x] (String/format java.util.Locale/US "%.3f" (to-array [(double x)])))

(defn- pct [part total]
  (if (zero? total) 0.0 (* 100.0 (/ (double part) (double total)))))

(defn- fmt-pct [x] (String/format java.util.Locale/US "%.1f%%" (to-array [(double x)])))

(def ^:dynamic *propagation-stats* nil)
(def ^:dynamic *run-tasks-depth* 0)
(def ^:dynamic *compound-prop-ids* nil)

(defn- swap-stats!
  ([f] (when *propagation-stats* (swap! *propagation-stats* f)))
  ([f & args] (when *propagation-stats* (apply swap! *propagation-stats* f args))))

;; --- independent duplicate of compound-activate (not used in production) ---

(defn- profile-boundary-nodes [closure-cell-id nodes]
  (vec (remove #(= closure-cell-id %) nodes)))

(defn- profile-ensure-boundaries [network ins outs cf]
  (let [t0 (System/nanoTime)
        [boundary-outputs net*] (closure/create-boundary-outputs network outs)
        [boundary-inputs net**] (closure/create-boundary-inputs net* ins)
        t1 (System/nanoTime)]
    (swap-stats! update :ensure-boundaries-ns + (- t1 t0))
    {:boundary-inputs boundary-inputs
     :boundary-outputs boundary-outputs
     :net net**
     :boundary {:ins ins :outs outs
                :in-avatars boundary-inputs :out-avatars boundary-outputs
                :f cf}}))

(defn- profile-timed-diff-cells
  [nodesA nodesB network-from network-to]
  (let [t0 (System/nanoTime)
        result (diff-cells nodesA nodesB network-from network-to)
        t1 (System/nanoTime)]
    (swap-stats! update :diff-cells-ns + (- t1 t0))
    (swap-stats! update :diff-cells-count inc)
    result))

(defn profile-compound-activate-breakdown
  "Profiling-only copy of compound-activate logic (not wired into production)."
  [closure-in-id input-ids output-ids network]
  (let [t0 (System/nanoTime)
        closure-cv (net/network-cell-strongest network closure-in-id)
        closure-payload (value/value-payload closure-cv)
        ins (profile-boundary-nodes closure-in-id input-ids)
        outs (vec output-ids)
        in-vals (mapv #(net/network-cell-strongest network %) ins)]
    (if (or (value/unusable? closure-cv)
            (value/any-unusable-values? in-vals)
            (nil? closure-payload))
      {:messages [] :breakdown {:skipped? true}}
      (let [cf (closure/closure-f closure-payload)
            t1 (System/nanoTime)
            {:keys [boundary-inputs boundary-outputs net]}
            (profile-ensure-boundaries network ins outs cf)
            t2 (System/nanoTime)
            net' (closure/apply-network-closure closure-payload
                                               boundary-inputs boundary-outputs net)
            t3 (System/nanoTime)
            net'' (core/run-tasks (pop-inputs boundary-inputs (net/net-graph net')) net')
            t4 (System/nanoTime)
            diffs (profile-timed-diff-cells boundary-outputs outs net'' network)
            t5 (System/nanoTime)]
        {:messages (vec diffs)
         :breakdown {:skipped? false
                     :ensure-boundaries-ns (- t2 t1)
                     :apply-closure-ns (- t3 t2)
                     :inner-run-tasks-ns (- t4 t3)
                     :diff-cells-ns (- t5 t4)
                     :total-ns (- t5 t0)}}))))

(defn- profile-compound-activate
  "Profiling copy of `compound-activate` for propagation benchmarks (same logic)."
  [closure-in-id]
  (fn [input-ids output-ids network]
    (let [closure-cv (net/network-cell-strongest network closure-in-id)
          closure-payload (value/value-payload closure-cv)
          ins (profile-boundary-nodes closure-in-id input-ids)
          outs (vec output-ids)
          in-vals (mapv #(net/network-cell-strongest network %) ins)]
      (if (or (value/unusable? closure-cv)
              (value/any-unusable-values? in-vals)
              (nil? closure-payload))
        []
        (let [{:keys [boundary-inputs boundary-outputs net]}
              (profile-ensure-boundaries network ins outs (closure/closure-f closure-payload))
              net' (closure/apply-network-closure closure-payload
                                                 boundary-inputs boundary-outputs net)
              net'' (core/run-tasks (pop-inputs boundary-inputs (net/net-graph net')) net')]
          (vec (profile-timed-diff-cells boundary-outputs outs net'' network)))))))

(defn- profile-compound-propagator [closure-in inputs outputs]
  (construct-propagator (profile-compound-activate closure-in)
                        (into [closure-in] inputs)
                        outputs))

;; --- chain builders ---

(defn- install-compound [n k-in left right]
  (let [[prop-id n'] ((profile-compound-propagator k-in [left right] [left right]) n)]
    [prop-id n']))

(defn- build-chain [chain-len]
  (let [cells (vec (repeatedly chain-len new-node-id))
        closures-in (vec (repeatedly (dec chain-len) new-node-id))
        cv bi-sync-closure
        n (reduce (fn [net id] (second ((construct-cell id) net)))
                  net/empty-net
                  (into cells closures-in))
        n2 (reduce #(net/assoc-net-cell %1 %2 (cell/cell cv cv)) n closures-in)
        [final-n props] (reduce
                         (fn [[n props] i]
                           (let [left (cells i)
                                 right (cells (inc i))
                                 [p n'] (install-compound n (closures-in i) left right)]
                             [n' (conj props p)]))
                         [n2 []]
                         (range (dec chain-len)))]
    {:net final-n :cells cells :props props :closures-in closures-in}))

(defn- build-chain-with-inject [chain-len]
  (let [inject-idx (quot chain-len 2)
        {:keys [net cells props]} (build-chain chain-len)
        mid (nth cells inject-idx)
        e (new-node-id)
        n (second ((construct-cell e) net))
        [e->mid n'] ((p:id e mid) n)]
    {:net n' :cells cells :props (set props) :inject-prop e->mid :inject-cell e :inject-idx inject-idx}))

(defn- seed-cell [n cell-id v]
  (net/assoc-net-cell n cell-id (cell/cell v v)))

;; --- isolated micro-profile (profile-compound-activate-breakdown) ---

(defn- run-one-compound-profiled [n prop-id k-in]
  (let [node (graph/get-node (net/net-graph n) prop-id)
        inputs (vec (graph/node-input-ids node))
        outputs (vec (graph/node-output-ids node))
        {:keys [messages breakdown]} (profile-compound-activate-breakdown k-in inputs outputs n)
        [tasks n'] (core/eval-cells messages n)]
    {:breakdown breakdown :n (core/run-tasks tasks n')}))

(defn- profile-isolated [chain-len]
  (println (str "\n=== isolated compound-activate (profiling copy) chain-len=" chain-len " ==="))
  (let [{:keys [net props closures-in cells]} (build-chain chain-len)
        seed 42
        n (seed-cell net (first cells) seed)
        {:keys [breakdown]} (run-one-compound-profiled n (first props) (closures-in 0))]
    (when-not (:skipped? breakdown)
      (let [total (:total-ns breakdown)]
        (println (str "  ensure-boundaries:      " (fmt (ms (:ensure-boundaries-ns breakdown))) " ms  "
                    (fmt-pct (pct (:ensure-boundaries-ns breakdown) total))))
        (println (str "  apply-network-closure:  " (fmt (ms (:apply-closure-ns breakdown))) " ms  "
                    (fmt-pct (pct (:apply-closure-ns breakdown) total))))
        (println (str "  inner run-tasks:        " (fmt (ms (:inner-run-tasks-ns breakdown))) " ms  "
                    (fmt-pct (pct (:inner-run-tasks-ns breakdown) total))))
        (println (str "  diff-cells:             " (fmt (ms (:diff-cells-ns breakdown))) " ms  "
                    (fmt-pct (pct (:diff-cells-ns breakdown) total))))))))

;; --- real propagation profile (middle inject) ---

(def ^:private empty-stats
  {:wall-ns 0
   :eval-propagator-ns 0 :eval-propagator-count 0
   :compound-prop-ns 0 :compound-prop-count 0
   :other-prop-ns 0 :other-prop-count 0
   :eval-cell-ns 0 :eval-cell-count 0
   :inner-run-tasks-ns 0 :inner-run-tasks-count 0
   :boundary-create-ns 0 :boundary-create-count 0
   :apply-closure-ns 0 :apply-closure-count 0
   :ensure-boundaries-ns 0
   :diff-cells-ns 0 :diff-cells-count 0
   :compound-f-ns 0 :compound-eval-cells-ns 0
   :eval-cell-merge-ns 0
   :eval-cell-assoc-ns 0
   :eval-cell-enqueue-ns 0
   :eval-cell-get-node-ns 0
   :eval-cell-updated-count 0
   :eval-cell-noop-count 0})

(defn- profile-eval-cell
  "Profiling copy of `eval-cell` with per-phase timings (not used in production)."
  [id msg n]
  (let [old (net/env-get (net/net-env n) id)
        t-merge (System/nanoTime)
        old-strongest (merge/strongest-value old n)
        content' (merge/cell-merge (cell/cell-content old) (message-value msg) n)
        strongest' (merge/strongest-value content' n)
        t-assoc (System/nanoTime)
        n' (net/assoc-net-cell n id (cell/cell content' strongest'))
        t-node (System/nanoTime)
        node (graph/get-node (net/net-graph n') id)
        t-enq (System/nanoTime)
        next-tasks (tq/enqueue-all tq/empty-queue (graph/node-output-ids node))
        t-done (System/nanoTime)
        updated? (merge/cell-updated? strongest' old-strongest n)
        result (if updated?
                 (if (value/contradiction? strongest')
                   (let [[tasks env] (merge/handle-contradiction next-tasks id (net/net-env n'))]
                     [tasks (net/net-with-env n' env)])
                   [next-tasks n'])
                 [tq/empty-queue n])]
    (swap-stats! update :eval-cell-ns + (- t-done t-merge))
    (swap-stats! update :eval-cell-count inc)
    (swap-stats! update :eval-cell-merge-ns + (- t-assoc t-merge))
    (swap-stats! update :eval-cell-assoc-ns + (- t-node t-assoc))
    (swap-stats! update :eval-cell-get-node-ns + (- t-enq t-node))
    (swap-stats! update :eval-cell-enqueue-ns + (- t-done t-enq))
    (if updated?
      (swap-stats! update :eval-cell-updated-count inc)
      (swap-stats! update :eval-cell-noop-count inc))
    result))

(defn- wrap-timed [k f]
  (fn [& args]
    (let [t0 (System/nanoTime)
          result (apply f args)
          dt (- (System/nanoTime) t0)]
      (swap-stats! update k (fnil + 0) dt)
      (swap-stats! update (keyword (str (name k) "-count")) (fnil + 0) 1)
      result)))

(defn- profile-middle-inject-propagation [chain-len]
  (let [{:keys [net props inject-prop inject-cell]} (build-chain-with-inject chain-len)
        stats (atom empty-stats)
        orig-eval-prop core/eval-propagator
        orig-run-tasks core/run-tasks
        orig-create-out closure/create-boundary-outputs
        orig-create-in closure/create-boundary-inputs
        orig-apply closure/apply-network-closure
        profiled-eval-prop
        (fn [current-id tasks n]
          (let [compound? (contains? *compound-prop-ids* current-id)
                t0 (System/nanoTime)]
            (if compound?
              (let [g (net/net-graph n)
                    e (net/net-env n)
                    current-node (graph/get-node g current-id)
                    inputs (graph/node-input-ids current-node)
                    outputs (graph/node-output-ids current-node)
                    f (prop/prop-f (net/env-get e current-id))
                    t1 (System/nanoTime)
                    messages (f inputs outputs n)
                    t2 (System/nanoTime)
                    [poped new-net] (core/eval-cells messages n)
                    t3 (System/nanoTime)
                    result [(tq/merge-queues tasks poped) new-net]
                    dt (- t3 t0)]
                (swap-stats! update :eval-propagator-ns + dt)
                (swap-stats! update :eval-propagator-count inc)
                (swap-stats! update :compound-prop-ns + dt)
                (swap-stats! update :compound-prop-count inc)
                (swap-stats! update :compound-f-ns + (- t2 t1))
                (swap-stats! update :compound-eval-cells-ns + (- t3 t2))
                result)
              (let [[t' n'] (orig-eval-prop current-id tasks n)
                    dt (- (System/nanoTime) t0)]
                (swap-stats! update :eval-propagator-ns + dt)
                (swap-stats! update :eval-propagator-count inc)
                (swap-stats! update :other-prop-ns + dt)
                (swap-stats! update :other-prop-count inc)
                [t' n']))))
        profiled-run-tasks
        (fn [tasks n]
          (let [depth *run-tasks-depth*
                t0 (System/nanoTime)
                result (binding [*run-tasks-depth* (inc depth)]
                         (orig-run-tasks tasks n))
                dt (- (System/nanoTime) t0)]
            (when (pos? depth)
              (swap-stats! update :inner-run-tasks-ns + dt)
              (swap-stats! update :inner-run-tasks-count inc))
            result))
        profiled-create-out (wrap-timed :boundary-create-ns orig-create-out)
        profiled-create-in (wrap-timed :boundary-create-ns orig-create-in)
        profiled-apply (wrap-timed :apply-closure-ns orig-apply)]
    (binding [*propagation-stats* stats
              *compound-prop-ids* props
              *run-tasks-depth* 0]
      (with-redefs [core/eval-propagator profiled-eval-prop
                    core/eval-cell profile-eval-cell
                    core/run-tasks profiled-run-tasks
                    closure/create-boundary-outputs profiled-create-out
                    closure/create-boundary-inputs profiled-create-in
                    closure/apply-network-closure profiled-apply]
        (let [t0 (System/nanoTime)
              _ (core/run-tasks (tq/enqueue tq/empty-queue inject-prop)
                                (seed-cell net inject-cell 42))
              wall (- (System/nanoTime) t0)]
          (swap! stats assoc :wall-ns wall)
          (let [s @stats
                wall (:wall-ns s)
                cmp (:compound-prop-ns s)
                nested (+ (:inner-run-tasks-ns s) (:boundary-create-ns s) (:apply-closure-ns s)
                          (:diff-cells-ns s) (:ensure-boundaries-ns s))
                cmp-other (max 0 (- (:compound-f-ns s) nested))
                sched (- wall (+ (:compound-prop-ns s) (:other-prop-ns s)))]
            (println (str "\n=== middle inject propagation profile chain-len=" chain-len " ==="))
            (println (str "  wall clock (one run-prop from inject):  " (fmt (ms wall)) " ms"))
            (println "")
            (println "  Note: compound/other eval times overlap (inner p:id inside compound).")
            (println "")
            (println "  Propagator eval-propagator (nested; may exceed 100% of wall):")
            (println (str "    compound activations:                 " (fmt (ms cmp))
                        "  (" (:compound-prop-count s) "x)"))
            (println (str "    other (mostly inner p:id):            " (fmt (ms (:other-prop-ns s)))
                        "  (" (:other-prop-count s) "x)"))
            (println (str "    eval-cell merge + enqueue:            " (fmt (ms (:eval-cell-ns s)))
                        "  " (fmt-pct (pct (:eval-cell-ns s) wall))
                        "  (" (:eval-cell-count s) "x)  <-- largest single bucket"))
            (println "")
            (println "  Inside compound activations (% of wall | % of compound time):")
            (println (str "    inner run-tasks:                      " (fmt (ms (:inner-run-tasks-ns s)))
                        "  " (fmt-pct (pct (:inner-run-tasks-ns s) wall))
                        " | " (fmt-pct (pct (:inner-run-tasks-ns s) cmp))))
            (println (str "    boundary create (cold):               " (fmt (ms (:boundary-create-ns s)))
                        "  " (fmt-pct (pct (:boundary-create-ns s) wall))
                        " | " (fmt-pct (pct (:boundary-create-ns s) cmp))))
            (println (str "    apply-network-closure (bi-sync):      " (fmt (ms (:apply-closure-ns s)))
                        "  " (fmt-pct (pct (:apply-closure-ns s) wall))
                        " | " (fmt-pct (pct (:apply-closure-ns s) cmp))))
            (println (str "    ensure-boundaries:                    " (fmt (ms (:ensure-boundaries-ns s)))
                        "  " (fmt-pct (pct (:ensure-boundaries-ns s) wall))
                        " | " (fmt-pct (pct (:ensure-boundaries-ns s) cmp))))
            (println (str "    diff-cells (boundary -> real):        " (fmt (ms (:diff-cells-ns s)))
                        "  " (fmt-pct (pct (:diff-cells-ns s) wall))
                        " | " (fmt-pct (pct (:diff-cells-ns s) cmp))))
            (println (str "    compound f body (activate logic):     " (fmt (ms (:compound-f-ns s)))
                        "  " (fmt-pct (pct (:compound-f-ns s) wall))
                        " | " (fmt-pct (pct (:compound-f-ns s) cmp))))
            (println (str "    compound eval-cells (after f):        " (fmt (ms (:compound-eval-cells-ns s)))
                        "  " (fmt-pct (pct (:compound-eval-cells-ns s) wall))
                        " | " (fmt-pct (pct (:compound-eval-cells-ns s) cmp))))
            (println (str "    compound f uninstrumented residual:   " (fmt (ms cmp-other))
                        "  " (fmt-pct (pct cmp-other wall))
                        " | " (fmt-pct (pct cmp-other cmp))))
            (println "")
            (println "  eval-cell breakdown (% of wall | % of eval-cell total):")
            (let [ec (:eval-cell-ns s)
                  merge-ns (:eval-cell-merge-ns s)
                  assoc-ns (:eval-cell-assoc-ns s)
                  get-ns (:eval-cell-get-node-ns s)
                  enq-ns (:eval-cell-enqueue-ns s)]
              (println (str "    cell-merge + strongest:               "
                            (fmt (ms merge-ns)) "  "
                            (fmt-pct (pct merge-ns wall)) " | "
                            (fmt-pct (pct merge-ns ec))))
              (println (str "    assoc-net-cell (new env map):         "
                            (fmt (ms assoc-ns)) "  "
                            (fmt-pct (pct assoc-ns wall)) " | "
                            (fmt-pct (pct assoc-ns ec))))
              (println (str "    get-node (graph lookup):              "
                            (fmt (ms get-ns)) "  "
                            (fmt-pct (pct get-ns wall)) " | "
                            (fmt-pct (pct get-ns ec))))
              (println (str "    enqueue-all (successor props):        "
                            (fmt (ms enq-ns)) "  "
                            (fmt-pct (pct enq-ns wall)) " | "
                            (fmt-pct (pct enq-ns ec))))
              (println (str "    cells that updated strongest:         "
                            (:eval-cell-updated-count s)))
              (println (str "    cells unchanged (noop, discard queue):  "
                            (:eval-cell-noop-count s))))))))))

(defn -main [& args]
  (let [[mode len-str] (if (= "propagate" (first args))
                        ["propagate" (second args)]
                        [nil (first args)])
        n (if len-str (Long/parseLong len-str) 1000)]
    (println "propagators compound profile")
    (if (= "propagate" mode)
      (profile-middle-inject-propagation n)
      (do (profile-isolated n)
          (profile-middle-inject-propagation n)))))
