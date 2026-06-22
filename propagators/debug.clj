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
