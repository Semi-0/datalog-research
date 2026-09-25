(ns propagators.experimental.runner.compound-trace
  "Additive causal tracing for propagators installed in nested network values."
  (:require [propagators.cells.cell :as cell]
            [propagators.core :as core]
            [propagators.experimental.runner.drivers :as drivers]
            [propagators.experimental.runner.examples :as examples]
            [propagators.experimental.runner.task-policy :as task-policy]
            [propagators.network-patch :as patch]
            [propagators.message :as msg]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runner-constructor :as constructor]))

(def default-max-depth 32)

(declare semantic-snapshot)

(defn- semantic-entry
  [entry depth max-depth]
  (cond
    (prop/prop? entry)
    {:entry-type :propagator
     :propagator-kind (prop/prop-name entry)}

    (cell/cell? entry)
    {:entry-type :cell
     :name (:name entry)
     :content (semantic-snapshot (:content entry) (inc depth) max-depth)
     :strongest (semantic-snapshot (:strongest entry) (inc depth) max-depth)}

    :else entry))

(defn semantic-snapshot
  "Remove activation-function identity while preserving observable Net data."
  ([value] (semantic-snapshot value 0 default-max-depth))
  ([value depth max-depth]
   (cond
     (neg? max-depth)
     (throw (ex-info "max snapshot depth must be non-negative"
                     {:max-depth max-depth}))

     (and (net/net? value) (<= depth max-depth))
     {:graph (net/net-graph value)
      :dict (net/net-dict-or-empty value)
      :env (into {}
                 (map (fn [[id entry]]
                        [id (semantic-entry entry depth max-depth)]))
                 (net/net-env value))}

     (net/net? value)
     ::depth-limit

     :else value)))

(defn- prop-entries
  [network path]
  (into {}
        (keep (fn [[id entry]]
                (if (prop/prop? entry)
                  [[path id]
                   {:propagator-id id
                    :propagator-kind (prop/prop-name entry)
                    :network-path path}]
                  nil)))
        (net/net-env network)))

(defn- nested-networks
  [network]
  (keep (fn [[id entry]]
          (if (cell/cell? entry)
            (let [strongest (cell/cell-strongest entry)]
              (if (net/net? strongest)
                [id strongest]
                nil))
            nil))
        (net/net-env network)))

(defn propagator-inventory
  "Index propagators in `network` and recursively nested network-valued cells."
  ([network] (propagator-inventory network default-max-depth))
  ([network max-depth]
   (letfn [(walk [current path depth]
             (let [local (prop-entries current path)]
               (if (< depth max-depth)
                 (reduce (fn [inventory [cell-id nested]]
                           (merge inventory
                                  (walk nested
                                        (conj path [:cell cell-id])
                                        (inc depth))))
                         local
                         (nested-networks current))
                 local)))]
     (if (neg? max-depth)
       (throw (ex-info "max trace depth must be non-negative"
                       {:max-depth max-depth}))
       (walk network [:outer] 0)))))

(defn- inventory-at
  [network path max-depth]
  (letfn [(walk [current current-path depth]
            (let [local (prop-entries current current-path)]
              (if (< depth max-depth)
                (reduce (fn [inventory [cell-id nested]]
                          (merge inventory
                                 (walk nested
                                       (conj current-path [:cell cell-id])
                                       (inc depth))))
                        local
                        (nested-networks current))
                local)))]
    (walk network path 0)))

(defn spawn-diff
  "Return propagators present in `after` but absent from `before`."
  ([before after] (spawn-diff before after default-max-depth))
  ([before after max-depth]
   (let [before-inventory (propagator-inventory before max-depth)
         after-inventory (propagator-inventory after max-depth)]
     (->> (keys after-inventory)
          (remove #(contains? before-inventory %))
          (sort-by pr-str)
          (mapv after-inventory)))))

(defn- inventory-diff
  [before-inventory after-inventory]
  (->> (keys after-inventory)
       (remove #(contains? before-inventory %))
       (sort-by pr-str)
       (mapv after-inventory)))

(defn- nested-cell-inventory
  [network cell-id max-depth]
  (let [entry (net/network-env-lookup network cell-id)]
    (if (cell/cell? entry)
      (let [strongest (cell/cell-strongest entry)]
        (if (net/net? strongest)
          (inventory-at strongest [:outer [:cell cell-id]] max-depth)
          {}))
      {})))

(defn- direct-cell-target?
  [before after target-id]
  (or (cell/cell? (net/network-env-lookup before target-id))
      (cell/cell? (net/network-env-lookup after target-id))))

(defn scoped-spawn-diff
  "Find spawns only at outer scope and directly messaged network-valued cells.

  Effects and indirect addresses conservatively fall back to the full diff."
  ([activation-result before after]
   (scoped-spawn-diff activation-result before after default-max-depth))
  ([activation-result before after max-depth]
   (let [{:keys [messages effects]}
         (patch/normalize-activation-return activation-result)
         target-ids (set (map msg/message-id messages))]
     (if (or (seq effects)
             (not-every? #(direct-cell-target? before after %) target-ids))
       (spawn-diff before after max-depth)
       (let [outer-before (prop-entries before [:outer])
             outer-after (prop-entries after [:outer])
             outer-spawns (inventory-diff outer-before outer-after)
             nested-spawns
             (mapcat (fn [cell-id]
                       (inventory-diff
                        (nested-cell-inventory before cell-id max-depth)
                        (nested-cell-inventory after cell-id max-depth)))
                     target-ids)]
         (vec (concat outer-spawns nested-spawns)))))))

(defn activation-envelope
  [origin result]
  {::origin origin
   ::activation-result result})

(defn activation-envelope?
  [value]
  (and (map? value)
       (contains? value ::origin)
       (contains? value ::activation-result)))

(defn- propagator-origin
  [propagator-id network]
  (let [propagator (net/network-lookup-propagator network propagator-id)]
    {:propagator-id propagator-id
     :propagator-kind (prop/prop-name propagator)
     :network-path [:outer]}))

(defn originating-propagator-evaluator
  "CPS evaluator that adds causal origin without changing activation results."
  [propagator-id network {:keys [success fail]}]
  (try
    (let [[result propagated-network]
          (examples/evaluate-propagator propagator-id network)]
      (success (activation-envelope
                (propagator-origin propagator-id network)
                result)
               propagated-network))
    (catch Throwable error
      (fail error))))

(defn traced-patch-evaluator
  "Apply an originated activation result and report newly installed propagators."
  ([observe] (traced-patch-evaluator observe default-max-depth))
  ([observe max-depth]
   (fn [envelope network {:keys [success fail]}]
     (if (activation-envelope? envelope)
       (try
         (let [[new-tasks patched-network]
               (core/eval-activation-result (::activation-result envelope) network)
               origin (::origin envelope)
               spawns (spawn-diff network patched-network max-depth)]
           (doseq [spawn spawns]
             (observe {:event :propagator/spawned
                       :cause origin
                       :spawn spawn}))
           (success new-tasks patched-network))
         (catch Throwable error
           (fail error)))
       (fail (ex-info "traced evaluator expected an activation envelope"
                      {:value envelope}))))))

(defn scoped-traced-patch-evaluator
  "Trace only scopes that an activation result can directly modify."
  ([observe] (scoped-traced-patch-evaluator observe default-max-depth))
  ([observe max-depth]
   (fn [envelope network {:keys [success fail]}]
     (if (activation-envelope? envelope)
       (try
         (let [activation-result (::activation-result envelope)
               [new-tasks patched-network]
               (core/eval-activation-result activation-result network)
               origin (::origin envelope)
               spawns (scoped-spawn-diff activation-result
                                         network
                                         patched-network
                                         max-depth)]
           (doseq [spawn spawns]
             (observe {:event :propagator/spawned
                       :cause origin
                       :spawn spawn}))
           (success new-tasks patched-network))
         (catch Throwable error
           (fail error)))
       (fail (ex-info "scoped traced evaluator expected an activation envelope"
                      {:value envelope}))))))

(defn traced-network-iterator
  ([observe] (traced-network-iterator observe default-max-depth))
  ([observe max-depth]
   (constructor/network-iterator-constructor
    task-policy/fifo-task-policy
    originating-propagator-evaluator
    (traced-patch-evaluator observe max-depth))))

(defn traced-runner
  ([observe] (traced-runner observe default-max-depth))
  ([observe max-depth]
   (drivers/trampoline-runner
    (traced-network-iterator observe max-depth))))

(defn scoped-traced-runner
  ([observe] (scoped-traced-runner observe default-max-depth))
  ([observe max-depth]
   (drivers/trampoline-runner
    (constructor/network-iterator-constructor
     task-policy/fifo-task-policy
     originating-propagator-evaluator
     (scoped-traced-patch-evaluator observe max-depth)))))

(defn run-with-trace
  "Run one outer network state, returning the runner result and spawn events."
  ([state] (run-with-trace state default-max-depth))
  ([state max-depth]
   (let [events (atom [])
         result ((traced-runner #(swap! events conj %) max-depth) state)]
     {:result result
      :events @events})))

(defn run-with-scoped-trace
  "Run with message-targeted tracing and conservative fallback behavior."
  ([state] (run-with-scoped-trace state default-max-depth))
  ([state max-depth]
   (let [events (atom [])
         result ((scoped-traced-runner #(swap! events conj %) max-depth) state)]
     {:result result
      :events @events})))

(defn trace-summary
  [events]
  {:event-count (count events)
   :by-cause-kind (frequencies (map #(get-in % [:cause :propagator-kind]) events))
   :by-spawn-kind (frequencies (map #(get-in % [:spawn :propagator-kind]) events))
   :by-network-depth (frequencies
                      (map #(count (get-in % [:spawn :network-path])) events))})
