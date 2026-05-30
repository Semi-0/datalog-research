(ns propagators-compound-object-bench
  "Benchmark baseline vs optimized compound-object slot propagation.

  Usage:
    clojure -M:compound-object-bench
    clojure -M:compound-object-bench redundant 1000"
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.effectful-execution :as effect]
            [propagators.effectful-sync :as sync]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.message :refer [message message-id]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def ^:dynamic *baseline-stats* nil)

(defn- inc-baseline! [k]
  (when *baseline-stats*
    (swap! *baseline-stats* update k (fnil inc 0))))

(defn- baseline-slot-seed-ids [subnet slot-key]
  (sync/indexed-seed-ids subnet
                         :slot-index
                         slot-key
                         (net/network-dict-entry subnet slot-key)))

(defn- baseline-stable-slot-cell-ids [exec-net slot-key]
  (sync/indexed-stable-cell-ids exec-net
                                :slot-index
                                slot-key
                                (net/network-dict-entry exec-net slot-key)))

(defn- baseline-slot-output-taps [subnet slot-key updated*]
  (effect/hook-output-taps subnet
                           (net/network-indexed-ids subnet :slot-index slot-key)
                           updated*))

(defn baseline-attach-slot-sync
  [collection-net slot-key parent-id parent-net]
  (let [n (-> collection-net
              (#'obj/ensure-slot-cell slot-key)
              (sync/ensure-parent-avatar :slot-index slot-key parent-id parent-net))
        dict (net/net-dict-or-empty n)
        avatar-id (get dict parent-id)
        slot-id (get dict slot-key)]
    (sync/attach-indexed-bi-sync n obj/slot-sync-key slot-key parent-id avatar-id slot-id)))

(defn- baseline-execute-slot-subnet
  [collection-net slot-key parent-id parent-net]
  (inc-baseline! :subnet-executions)
  (-> (baseline-attach-slot-sync collection-net slot-key parent-id parent-net)
      (effect/execute-subnet
       #(baseline-slot-output-taps %1 slot-key %2)
       #(baseline-slot-seed-ids % slot-key))))

(defn- baseline-sync-slot-messages
  [collection-id slot-key [exec-net collection-net' updated*]]
  (let [collection-message (message collection-id
                                    (effect/project-stable-cells
                                     exec-net
                                     collection-net'
                                     (baseline-stable-slot-cell-ids exec-net slot-key)))
        parent-messages (sync/updated-parent-messages collection-net' updated*)]
    (into [collection-message] parent-messages)))

(defn baseline-p:slot
  [slot-key parent-id collection-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [collection-net (-> network
                              (net/network-cell-strongest collection-id)
                              obj/ensure-cons-net)]
       (if (value/contradiction? collection-net)
         [(message collection-id value/contradiction)]
         (->> (baseline-execute-slot-subnet collection-net slot-key parent-id network)
              (baseline-sync-slot-messages collection-id slot-key)))))
   [parent-id collection-id]
   [parent-id collection-id]))

(defn baseline-p:car [elem-id collection-id]
  (baseline-p:slot :car elem-id collection-id))

(defn baseline-p:cdr [elem-id collection-id]
  (baseline-p:slot :cdr elem-id collection-id))

(defn baseline-p:cons
  [head-id tail-id collection-id]
  (fn [network]
    (let [[car-prop n] (nb/install-propagator network (baseline-p:car head-id collection-id))
          [cdr-prop n] (nb/install-propagator n (baseline-p:cdr tail-id collection-id))]
      [[car-prop cdr-prop] n])))

(def variants
  {:baseline {:p:car baseline-p:car
              :p:cdr baseline-p:cdr
              :p:cons baseline-p:cons
              :attach baseline-attach-slot-sync}
   :optimized {:p:car obj/p:car
               :p:cdr obj/p:cdr
               :p:cons obj/p:cons
               :attach obj/attach-slot-sync}})

(defn- ms [ns] (/ (double ns) 1e6))

(defn- fmt [x]
  (String/format java.util.Locale/US "%.3f" (to-array [(double x)])))

(defn- mean [xs]
  (/ (reduce + 0 xs) (count xs)))

(defn- median [xs]
  (let [xs (vec (sort xs))]
    (nth xs (quot (count xs) 2))))

(defn- time-ns [f]
  (let [t0 (System/nanoTime)
        result (f)]
    {:result result :ns (- (System/nanoTime) t0)}))

(defn- run-props [n prop-ids]
  (nb/run-propagators n prop-ids))

(defn- seed! [n id v]
  (nb/seed-cell n id v))

(defn- install-prop! [[n tasks prop-ids prop-collections] [installer collection-id]]
  (let [[prop-id n'] (nb/install-propagator n installer)]
    [n'
     (tq/enqueue tasks prop-id)
     (conj prop-ids prop-id)
     (assoc prop-collections prop-id collection-id)]))

(defn- instrumented-eval-propagator
  [stats prop-collection-ids current-id tasks n]
  (let [collection-id (get prop-collection-ids current-id)
        slot? (some? collection-id)
        current-node (graph/get-node (net/net-graph n) current-id)
        inputs (graph/node-input-ids current-node)
        outputs (graph/node-output-ids current-node)
        f (prop/prop-f (net/env-get (net/net-env n) current-id))
        messages (f inputs outputs n)]
    (when slot?
      (swap! stats update :slot-activations (fnil inc 0))
      (let [collection-count (count (filter #(= collection-id (message-id %))
                                            messages))]
        (swap! stats update :collection-messages (fnil + 0) collection-count)
        (swap! stats update :parent-messages (fnil + 0) (- (count messages) collection-count))))
    (let [[poped new-net] (core/eval-cells messages n)]
      [(tq/merge-queues tasks poped) new-net])))

(defn- measured-run
  [variant collection-ids prop-collection-ids f]
  (let [stats (atom {})
        coll-ids (set collection-ids)
        orig-eval-cell core/eval-cell
        execute-slot-subnet-var #'obj/execute-slot-subnet
        orig-execute-slot-subnet @execute-slot-subnet-var
        run (fn []
              (with-redefs [core/eval-cell
                            (fn [id msg n]
                              (let [before (when (coll-ids id)
                                             (net/network-cell-strongest n id))
                                    [tasks n'] (orig-eval-cell id msg n)
                                    after (when (coll-ids id)
                                            (net/network-cell-strongest n' id))]
                                (when (and (coll-ids id)
                                           (not (merge/cell-updated? after before n)))
                                  (swap! stats update :collection-noop-messages (fnil inc 0)))
                                (when (and (coll-ids id)
                                           (merge/cell-updated? after before n))
                                  (swap! stats update :collection-updates (fnil inc 0)))
                                [tasks n']))
                            core/eval-propagator
                            (fn [current-id tasks n]
                              (instrumented-eval-propagator stats prop-collection-ids current-id tasks n))]
                (let [{:keys [result ns]} (time-ns f)]
                  (assoc @stats :result result :ns ns))))]
    (if (= variant :baseline)
      (binding [*baseline-stats* stats]
        (run))
      (with-redefs-fn {execute-slot-subnet-var
                       (fn [& args]
                         (swap! stats update :subnet-executions (fnil inc 0))
                         (apply orig-execute-slot-subnet args))}
        run))))

(defn- bench-iters
  [variant label collection-ids prop-collection-ids warmup iters f]
  (dotimes [_ warmup]
    (measured-run variant collection-ids prop-collection-ids f))
  (let [samples (vec (repeatedly iters #(measured-run variant collection-ids prop-collection-ids f)))
        nss (map :ns samples)
        stat-keys [:slot-activations :subnet-executions :guarded-skips
                   :collection-messages :collection-updates :collection-noop-messages
                   :parent-messages]
        totals (reduce
                (fn [m sample]
                  (reduce (fn [m k] (update m k (fnil + 0) (long (get sample k 0))))
                          m
                          stat-keys))
                {}
                samples)]
    (merge {:variant variant
            :label label
            :iters iters
            :mean-ms (ms (mean nss))
            :median-ms (ms (median nss))
            :min-ms (ms (apply min nss))
            :max-ms (ms (apply max nss))
            :last-result (:result (last samples))}
           totals
           (when (= variant :optimized)
             {:guarded-skips (- (long (:slot-activations totals 0))
                                (long (:subnet-executions totals 0)))}))))

(defn- print-row [row]
  (println
   (str (name (:variant row))
        "\t" (:label row)
        "\titers=" (:iters row)
        "\tmedian=" (fmt (:median-ms row)) "ms"
        "\tmean=" (fmt (:mean-ms row)) "ms"
        "\tmin=" (fmt (:min-ms row)) "ms"
        "\tmax=" (fmt (:max-ms row)) "ms"
        "\tactivations=" (get row :slot-activations 0)
        "\tsubnets=" (get row :subnet-executions 0)
        "\tskips=" (get row :guarded-skips 0)
        "\tcoll-msgs=" (get row :collection-messages 0)
        "\tcoll-updates=" (get row :collection-updates 0)
        "\tcoll-noops=" (get row :collection-noop-messages 0)
        "\tparent-msgs=" (get row :parent-messages 0)
        "\tok=" (boolean (:ok (:last-result row))))))

(defn- build-one-cons [variant]
  (let [{:keys [p:cons]} (variants variant)
        head (new-node-id)
        tail (new-node-id)
        coll (new-node-id)
        n (nb/install-cells [head tail coll])
        [[car-prop cdr-prop] n] ((p:cons head tail coll) n)]
    {:net n
     :head head
     :tail tail
     :coll coll
     :props [car-prop cdr-prop]
     :prop-collections {car-prop coll
                        cdr-prop coll}}))

(defn- redundant-workload [variant repeats]
  (let [{:keys [net head tail coll props prop-collections]} (build-one-cons variant)
        converged (-> net
                      (seed! head 10)
                      (seed! tail 20)
                      (run-props props))]
    {:collection-ids [coll]
     :prop-collection-ids prop-collections
     :run (fn []
            (let [n' (nth (iterate #(run-props % props) converged) repeats)
                  coll-net (net/network-cell-value n' coll)]
              {:ok (and (= 10 (obj/slot-strongest coll-net :car))
                        (= 20 (obj/slot-strongest coll-net :cdr)))}))}))

(defn- build-deep-accessor [variant depth]
  (let [{:keys [p:cons p:car p:cdr]} (variants variant)
        sentinel (new-node-id)
        ids (vec (repeatedly (+ (* 2 depth) 1) new-node-id))
        coll-ids (mapv #(nth ids (+ (* 2 %) 1)) (range depth))
        head-ids (mapv #(nth ids (* 2 %)) (range depth))
        n0 (nb/install-cells (conj ids sentinel))
        {:keys [net props prop-collections]}
        (reduce
         (fn [{:keys [net props prop-collections]} i]
           (let [h (head-ids i)
                 c (coll-ids i)
                 t (if (< i (dec depth)) (coll-ids (inc i)) sentinel)
                 [[car-prop cdr-prop] n'] ((p:cons h t c) net)]
             {:net n'
              :props (conj props car-prop cdr-prop)
              :prop-collections (assoc prop-collections
                                       car-prop c
                                       cdr-prop c)}))
         {:net n0 :props [] :prop-collections {}}
         (range depth))
        out (new-node-id)
        n1 (nb/install-cell net out)
        installers (concat
                    (map (fn [i] [(p:cdr (coll-ids (inc i)) (coll-ids i))
                                  (coll-ids i)])
                         (range (dec depth)))
                    [[(p:car out (last coll-ids)) (last coll-ids)]])
        [n2 tasks accessor-props accessor-prop-collections]
        (reduce install-prop! [n1 tq/empty-queue [] {}] installers)]
    {:net n2
     :tasks tasks
     :props (into props accessor-props)
     :prop-collections (merge prop-collections accessor-prop-collections)
     :head (last head-ids)
     :out out
     :collection-ids coll-ids}))

(defn- deep-workload [variant depth]
  (let [{:keys [net tasks props prop-collections head out collection-ids]} (build-deep-accessor variant depth)]
    {:collection-ids collection-ids
     :prop-collection-ids prop-collections
     :run (fn []
            (let [[n tasks] (nb/seed-cell! net tasks head 30)
                  n' (core/run-tasks tasks n)]
              {:ok (= 30 (net/network-cell-value n' out))}))}))

(defn- wide-workload [variant fanout]
  (let [{:keys [p:car attach]} (variants variant)
        parents (vec (repeatedly fanout new-node-id))
        coll (new-node-id)
        n0 (nb/install-cells (conj parents coll))
        [prop-ids prop-collections n1] (reduce
                       (fn [[ids prop-collections n] parent]
                         (let [[prop-id n'] ((p:car parent coll) n)]
                           [(conj ids prop-id)
                            (assoc prop-collections prop-id coll)
                            n']))
                       [[] {} n0]
                       parents)
        coll-net (reduce #(attach %1 :car %2 n1) (obj/empty-cons-net) parents)
        slot-id (net/network-dict-entry coll-net :car)
        coll-value (net/assoc-net-cell coll-net slot-id (cell/cell 77 77))
        n2 (nb/seed-cell n1 coll coll-value)]
    {:collection-ids [coll]
     :prop-collection-ids prop-collections
     :run (fn []
            (let [n' (run-props n2 [(first prop-ids)])]
              {:ok (every? #(= 77 (net/network-cell-value n' %)) parents)}))}))

(defn- mixed-workload [variant]
  (let [{:keys [net head tail props prop-collections coll]} (build-one-cons variant)
        converged (-> net
                      (seed! head 10)
                      (seed! tail 20)
                      (run-props props))]
    {:collection-ids [coll]
     :prop-collection-ids prop-collections
     :run (fn []
            (let [n1 (run-props converged props)
                  n2 (-> n1
                         (seed! head 10)
                         (run-props props))
                  n3 (run-props n2 props)
                  coll-net (net/network-cell-value n3 coll)]
              {:ok (and (= 10 (obj/slot-strongest coll-net :car))
                        (= 20 (obj/slot-strongest coll-net :cdr)))}))}))

(defn- run-scenario [scenario size]
  (let [workload (case scenario
                   :redundant #(redundant-workload % size)
                   :deep #(deep-workload % size)
                   :wide #(wide-workload % size)
                   :mixed #(mixed-workload %))
        opts (case scenario
               :wide {:warmup 2 :iters 8}
               :deep {:warmup 2 :iters 8}
               {:warmup 3 :iters 15})]
    (println (str "\nscenario=" (name scenario) " size=" size))
    (doseq [variant [:baseline :optimized]]
      (let [{:keys [collection-ids prop-collection-ids run]} (workload variant)
            row (bench-iters variant
                             (str (name scenario) "-" size)
                             collection-ids
                             prop-collection-ids
                             (:warmup opts)
                             (:iters opts)
                             run)]
        (print-row row)))))

(def default-scenarios
  [[:redundant 100]
   [:deep 3]
   [:deep 10]
   [:wide 1]
   [:wide 10]
   [:wide 100]
   [:mixed 1]])

(defn -main [& args]
  (println "compound-object baseline vs optimized benchmark")
  (if (seq args)
    (let [scenario (keyword (first args))
          size (Long/parseLong (or (second args) "1"))]
      (run-scenario scenario size))
    (doseq [[scenario size] default-scenarios]
      (run-scenario scenario size))))
