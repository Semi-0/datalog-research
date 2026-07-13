(ns propagators.debug
  "Small console tracer for propagation task queues."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.graph :as graph]
            [propagators.gur.subenv.env :as env]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :refer [message-id message-value]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- queue-items
  [tasks]
  (loop [q (tq/into-queue tasks)
         xs []]
    (if (tq/queue-empty? q)
      xs
      (let [[id q'] (tq/pop-task q)]
        (recur q' (conj xs id))))))

(defn simple-id-renderer
  "Return a per-run renderer that labels NodeIds as n1, n2, ..."
  []
  (let [labels (atom {})]
    (fn render [x]
      (cond
        (ids/node-id? x)
        (or (get @labels x)
            (get (swap! labels assoc x (str "n" (inc (count @labels)))) x))

        (vector? x)
        (str "[" (str/join " " (map render x)) "]")

        :else
        (pr-str x)))))

(defn- render-data
  [render-id x]
  (pr-str (walk/postwalk #(if (ids/node-id? %)
                            (symbol (render-id %))
                            %)
                         x)))

(defn- short-id
  [render-id x]
  (cond
    (ids/node-id? x) (render-id x)
    (vector? x) (str "[" (str/join " " (map #(short-id render-id %) x)) "]")
    :else (pr-str x)))

(defn- short-text
  [s]
  (let [s (str s)]
    (if (> (count s) 96)
      (str (subs s 0 93) "...")
      s)))

(defn- value-summary
  [render-id v]
  (cond
    (value/nothing? v) "nothing"
    (value/contradiction? v) "contradiction"

    (and (net/net? v) (obj/accessor-network? v))
    (let [slots (obj/accessor-slot-keys v)
          slot-counts (into {}
                            (map (fn [slot]
                                   [slot (count (obj/accessor-parent-ids v slot))]))
                            slots)]
      (str "accessor{source=" (vec (keys (obj/accessor-source-slots v)))
           ", slots=" slot-counts "}"))

    (net/net? v)
    (str "net{cells=" (count (net/net-env v))
         ", routes=" (count (net/net-dict-or-empty v)) "}")

    :else (short-text (render-data render-id v))))

(defn- cell-summary
  [render-id n id]
  (let [entry (get (net/net-env n) id)]
    (str (short-id render-id id) "="
         (if (cell/cell? entry)
           (value-summary render-id (cell/cell-strongest entry))
           (short-text (render-data render-id entry))))))

(defn- route-summary
  [render-id n target]
  (try
    (short-text (render-data render-id
                             (env/resolve-dispatch (net/net-dict-or-empty n)
                                                   target
                                                   n)))
    (catch Exception e
      (str "unresolved " (short-text (ex-message e))))))

(defn- emit!
  [log-fn line]
  (log-fn line))

(defn activation-profile
  "Create mutable aggregation state for opt-in propagator timing."
  []
  (atom {:totals {}
         :by-propagator {}
         :by-name {}}))

(def ^:dynamic *activation-profile-state* nil)
(def ^:dynamic *activation-profile-depth* 0)
(def ^:dynamic *activation-child-elapsed-ns* nil)

(defn- add-activation
  [stat event]
  (let [elapsed-ns (long (or (:elapsed-ns event) 0))
        exclusive-ns (long (or (:exclusive-ns event) elapsed-ns))]
    (-> (or stat {})
        (update :calls (fnil inc 0))
        (update :errors (fnil + 0) (if (:error event) 1 0))
        (update :elapsed-ns (fnil + 0) elapsed-ns)
        (update :exclusive-ns (fnil + 0) exclusive-ns)
        (update :max-elapsed-ns (fnil max 0) elapsed-ns)
        (update :max-exclusive-ns (fnil max 0) exclusive-ns))))

(defn record-activation!
  [profile {:keys [prop-id prop-name] :as event}]
  (swap! profile
         (fn [state]
           (-> state
               (update :totals add-activation event)
               (update-in [:by-propagator prop-id]
                          #(assoc (add-activation % event)
                                  :prop-id prop-id
                                  :prop-name prop-name
                                  :input-ids (:input-ids event)
                                  :output-ids (:output-ids event)))
               (update-in [:by-name prop-name]
                          #(assoc (add-activation % event)
                                  :prop-name prop-name)))))
  nil)

(defn- activation-event
  [network prop-id elapsed-ns child-ns error]
  (let [node (graph/get-node (net/net-graph network) prop-id)
        propagator (net/env-get (net/net-env network) prop-id)
        input-ids (vec (graph/node-input-ids node))
        output-ids (vec (graph/node-output-ids node))]
    (cond-> {:prop-id prop-id
             :prop-name (prop/prop-name propagator)
             :depth *activation-profile-depth*
             :input-ids input-ids
             :output-ids output-ids
             :input-count (count input-ids)
             :output-count (count output-ids)
             :elapsed-ns elapsed-ns
             :exclusive-ns (max 0 (- elapsed-ns child-ns))}
      error (assoc :error (str (class error))))))

(defn run-tasks-profiled
  "Debug task runner that times the unchanged `core/eval-propagator` extension point."
  [tasks network]
  (loop [tasks (tq/into-queue tasks)
         current network]
    (if (tq/queue-empty? tasks)
      current
      (let [[prop-id remaining] (tq/pop-task tasks)
            parent-child-ns *activation-child-elapsed-ns*
            child-ns (volatile! 0)
            started (System/nanoTime)
            outcome (try
                      {:result
                       (binding [*activation-profile-depth*
                                 (inc *activation-profile-depth*)
                                 *activation-child-elapsed-ns* child-ns]
                         (core/eval-propagator prop-id remaining current))}
                      (catch Throwable t
                        {:error t}))
            elapsed-ns (- (System/nanoTime) started)
            error (:error outcome)]
        (when parent-child-ns
          (vswap! parent-child-ns + elapsed-ns))
        (record-activation! *activation-profile-state*
                            (activation-event current
                                              prop-id
                                              elapsed-ns
                                              @child-ns
                                              error))
        (if error
          (throw error)
          (let [[next-tasks next-net] (:result outcome)]
            (recur next-tasks next-net)))))))

(defn call-with-activation-profile
  [profile f]
  (binding [*activation-profile-state* profile]
    (with-redefs [core/run-tasks run-tasks-profiled]
      (f))))

(defmacro with-activation-profile
  [profile & body]
  `(call-with-activation-profile ~profile (fn [] ~@body)))

(defn- ns->ms
  [n]
  (/ (double (or n 0)) 1000000.0))

(defn- activation-row
  [total-exclusive-ns stat]
  (let [calls (long (or (:calls stat) 0))
        exclusive-ns (long (or (:exclusive-ns stat) 0))]
    (-> stat
        (assoc :elapsed-ms (ns->ms (:elapsed-ns stat))
               :exclusive-ms (ns->ms exclusive-ns)
               :max-elapsed-ms (ns->ms (:max-elapsed-ns stat))
               :max-exclusive-ms (ns->ms (:max-exclusive-ns stat))
               :average-exclusive-ms (if (pos? calls)
                                       (/ (ns->ms exclusive-ns) calls)
                                       0.0)
               :exclusive-percent (if (pos? total-exclusive-ns)
                                    (* 100.0
                                       (/ (double exclusive-ns)
                                          total-exclusive-ns))
                                    0.0))
        (dissoc :elapsed-ns :exclusive-ns
                :max-elapsed-ns :max-exclusive-ns))))

(defn- ranked-activations
  [total-exclusive-ns stats]
  (->> (vals stats)
       (map #(activation-row total-exclusive-ns %))
       (sort-by (juxt :exclusive-ms :calls) #(compare %2 %1))
       vec))

(defn activation-profile-report
  "Return propagator timings ranked by exclusive time.

  Inclusive `:elapsed-ms` contains nested `run-tasks`; `:exclusive-ms`
  subtracts directly nested propagator activations and is the useful hot-path
  ranking."
  [profile]
  (let [{:keys [totals by-propagator by-name]} @profile
        total-exclusive-ns (long (or (:exclusive-ns totals) 0))]
    {:totals (activation-row total-exclusive-ns totals)
     :by-propagator (ranked-activations total-exclusive-ns by-propagator)
     :by-name (ranked-activations total-exclusive-ns by-name)}))

(defn print-activation-profile!
  "Print the top propagator ids and names from `profile`."
  ([profile] (print-activation-profile! profile 20))
  ([profile limit]
   (let [{:keys [totals by-propagator by-name]}
         (activation-profile-report profile)]
     (prn {:activation-profile/totals totals})
     (doseq [row (take limit by-name)]
       (prn (assoc row :activation-profile/group :name)))
     (doseq [row (take limit by-propagator)]
       (prn (assoc row :activation-profile/group :propagator))))
   nil))

(defn- dict-paths-to
  ([dictionary target]
   (dict-paths-to [] dictionary target))
  ([path x target]
   (cond
     (= x target) [path]
     (map? x) (mapcat (fn [[k v]]
                        (dict-paths-to (conj path k) v target))
                      x)
     (sequential? x) (mapcat (fn [[index v]]
                               (dict-paths-to (conj path index) v target))
                             (map-indexed vector x))
     :else [])))

(defn- profiled-cell-description
  [network render-id id]
  {:id id
   :dict-paths (vec (dict-paths-to (net/net-dict-or-empty network) id))
   :value (value-summary render-id
                         (if (contains? (net/net-env network) id)
                           (net/network-cell-strongest network id)
                           value/nothing))})

(defn describe-profiled-propagator
  "Describe one ranked profile row against the final network."
  [network row]
  (let [render-id (simple-id-renderer)]
    (assoc row
           :inputs (mapv #(profiled-cell-description network render-id %)
                         (:input-ids row))
           :outputs (mapv #(profiled-cell-description network render-id %)
                          (:output-ids row)))))

(defn- eval-cells-debug
  [render-id messages n log-fn]
  (loop [ms messages
         tasks tq/empty-queue
         n' n]
    (if (empty? ms)
      [tasks n']
      (let [msg (first ms)
            target (message-id msg)
            _ (emit! log-fn
                     (str "      msg " (short-id render-id target)
                          " route=" (route-summary render-id n' target)
                          " value=" (value-summary render-id (message-value msg))))
            [popped new-n] (core/eval-cell* (net/net-dict-or-empty n') msg n')]
        (recur (rest ms) (tq/merge-queues tasks popped) new-n)))))

(defn- eval-propagator-debug
  [render-id step current-id tasks n log-fn snapshot-fn]
  (let [node (graph/get-node (net/net-graph n) current-id)
        inputs (vec (graph/node-input-ids node))
        outputs (vec (graph/node-output-ids node))
        f (prop/prop-f (net/env-get (net/net-env n) current-id))
        messages (vec (f inputs outputs n))
        _ (emit! log-fn
                 (str "[" step "] task " (short-id render-id current-id)
                      " in={" (str/join ", " (map #(cell-summary render-id n %) inputs)) "}"
                      " out={" (str/join ", " (map #(cell-summary render-id n %) outputs)) "}"
                      " messages=" (count messages)))
        [popped new-net] (eval-cells-debug render-id messages n log-fn)
        merged (tq/merge-queues tasks popped)]
    (when-let [snapshot (and snapshot-fn (snapshot-fn new-net))]
      (emit! log-fn (str "      watch " snapshot)))
    (when (seq (queue-items popped))
      (emit! log-fn
             (str "      queued " (mapv #(short-id render-id %)
                                         (queue-items popped)))))
    [merged new-net]))

(defn run-tasks-debug
  "Run `tasks` like `core/run-tasks`, printing each propagator step.

  Options:
  - `:log-fn` receives each rendered line, defaults to `println`
  - `:snapshot` is `(fn [network] \"...\")`, printed after each step when truthy
  - `:id-fn` renders ids, defaults to per-run n1/n2 labels
  - `:max-steps` stops runaway traces, defaults to 1000
  "
  ([tasks n] (run-tasks-debug tasks n {}))
  ([tasks n {:keys [log-fn snapshot max-steps id-fn]
             :or {log-fn println
                  max-steps 1000}}]
   (let [render-id (or id-fn (simple-id-renderer))]
     (loop [step 1
            ts (tq/into-queue tasks)
            n' n]
       (cond
         (tq/queue-empty? ts)
         n'

         (> step max-steps)
         (throw (ex-info "debug task run exceeded max steps" {:max-steps max-steps}))

         :else
         (let [[current-id remaining] (tq/pop-task ts)
               [tasks* n*] (eval-propagator-debug render-id
                                                  step
                                                  current-id
                                                  remaining
                                                  n'
                                                  log-fn
                                                  snapshot)]
           (recur (inc step) tasks* n*)))))))
