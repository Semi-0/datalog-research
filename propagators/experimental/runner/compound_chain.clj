(ns propagators.experimental.runner.compound-chain
  "Isolated comparison of port-dispatched and atomic-closure compound chains."
  (:require [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.experimental.runner.drivers :as drivers]
            [propagators.experimental.runner.examples :as examples]
            [propagators.experimental.runner.task-policy :as task-policy]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.message :as msg]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner-constructor :as constructor]
            [propagators.stdlib.prop :as std-prop]))

(def ^:dynamic *metrics* nil)

(defn- record!
  [metric]
  (if (some? *metrics*)
    (swap! *metrics* update metric (fnil inc 0))
    nil))

(defn compose-combinator
  "Build an ordered predicate/handler dispatcher with a final fallback.

  Predicates and handlers receive all dispatch arguments."
  [& clauses]
  (if (and (odd? (count clauses)) (pos? (count clauses)))
    (fn [& inputs]
      (loop [remaining clauses]
        (if (= 1 (count remaining))
          (apply (first remaining) inputs)
          (let [predicate (first remaining)
                handler (second remaining)]
            (if (apply predicate inputs)
              (apply handler inputs)
              (recur (nnext remaining)))))))
    (throw (ex-info "compose-combinator requires predicate/handler pairs and a fallback"
                    {:clause-count (count clauses)}))))

(defn port-patch
  [port-id patch-value]
  {::patch-type ::port
   ::port-id port-id
   ::value patch-value})

(defn port-patch?
  [patch & _]
  (= ::port (::patch-type patch)))

(defn cell-patch?
  [patch & _]
  (msg/message? patch))

(defn- port-key
  [port-id]
  [::port port-id])

(defn- register-port
  [network port-id cell-id]
  (net/assoc-net-dict-entry network (port-key port-id) cell-id))

(defn- apply-cell-patch
  [patch network]
  (record! :cell-patches)
  (core/eval-cell* (net/net-dict-or-empty network) patch network))

(defn- apply-port-patch
  [patch network]
  (let [port-id (::port-id patch)
        target (net/network-dict-entry network (port-key port-id))]
    (if (some? target)
      (do
        (record! :port-patches)
        (core/eval-cell* (net/net-dict-or-empty network)
                         (msg/message target (::value patch))
                         network))
      (throw (ex-info "unknown experimental port" {:port-id port-id})))))

(defn- reject-patch
  [patch _network]
  (throw (ex-info "unsupported experimental patch" {:patch patch})))

(def patch-dispatch
  (compose-combinator cell-patch? apply-cell-patch
                      port-patch? apply-port-patch
                      reject-patch))

(defn evaluate-patches
  "Apply cell and port patches in order, accumulating newly scheduled tasks."
  [patches network]
  (loop [remaining (seq patches)
         tasks tq/empty-queue
         current network]
    (if (nil? remaining)
      [tasks current]
      (let [[new-tasks next-network] (patch-dispatch (first remaining) current)]
        (recur (next remaining)
               (tq/merge-queues tasks new-tasks)
               next-network)))))

(def patch-evaluator-cps
  (fn [patches network {:keys [success fail]}]
    (try
      (let [[new-tasks patched-network] (evaluate-patches patches network)]
        (success new-tasks patched-network))
      (catch Throwable error
        (fail error)))))

(defn- complete-run
  [result]
  (case (:status result)
    :completed (:network result)
    :failed (throw (:error result))
    (throw (ex-info "unknown runner result" {:result result}))))

(def port-runner
  (drivers/trampoline-runner
   (constructor/network-iterator-constructor
    task-policy/fifo-task-policy
    examples/propagator-evaluator-cps
    patch-evaluator-cps)))

(defn- identity-closure
  []
  (let [input-id (new-node-id)
        output-id (new-node-id)
        n0 (nb/install-cells [input-id output-id])
        [propagator-id n1] (nb/install-propagator n0 (std-prop/id input-id output-id))]
    {::closure-type ::identity
     ::network n1
     ::tasks (tq/enqueue tq/empty-queue propagator-id)
     ::input input-id
     ::output output-id}))

(defn- ported-closure-propagator
  [closure-id input-id output-id port-id]
  (prop/construct-propagator
   ::ported-closure
   (fn [_inputs _outputs network]
     (let [closure (net/network-cell-value network closure-id)
           input-value (net/network-cell-value network input-id)]
       (cond
         (not= ::identity (::closure-type closure))
         (throw (ex-info "unsupported ported closure" {:closure closure}))

         (value/unusable? input-value)
         []

         :else
         [(port-patch port-id input-value)])))
   [closure-id input-id]
   [output-id]))

(defn- apply-closure-propagator
  [closure-id input-id output-id]
  (prop/construct-propagator
   ::apply-closure
   (fn [_inputs _outputs _network]
     (throw (ex-info "apply-closure requires the experimental evaluator" {})))
   [closure-id input-id]
   [output-id]))

(defn- application-key
  [propagator-id]
  [::application propagator-id])

(defn- apply-closure-propagator?
  [propagator-id network & _]
  (= ::apply-closure
     (prop/prop-name (net/network-lookup-propagator network propagator-id))))

(defn- evaluate-ordinary-propagator
  [propagator-id network continuations]
  (examples/propagator-evaluator-cps propagator-id network continuations))

(defn- run-inner-closure
  [closure input-value]
  (if (= ::identity (::closure-type closure))
    (let [[seeded tasks] (nb/seed-cell! (::network closure)
                                        (::tasks closure)
                                        (::input closure)
                                        input-value)
          completed (complete-run (examples/normal-runner {:network seeded :tasks tasks}))]
      (net/network-cell-value completed (::output closure)))
    (throw (ex-info "unsupported atomic closure" {:closure closure}))))

(defn- evaluate-apply-closure
  [propagator-id network {:keys [success fail]}]
  (let [{:keys [closure-id input-id output-id]}
        (net/network-dict-entry network (application-key propagator-id))
        closure (net/network-cell-value network closure-id)
        input-value (net/network-cell-value network input-id)]
    (if (value/unusable? input-value)
      (success [] network)
      (try
        (record! :inner-runs)
        (success [(msg/message output-id (run-inner-closure closure input-value))]
                 network)
        (catch Throwable error
          (fail error))))))

(def propagator-dispatch
  (compose-combinator apply-closure-propagator? evaluate-apply-closure
                      evaluate-ordinary-propagator))

(defn propagator-evaluator-cps
  [propagator-id network continuations]
  (record! :outer-activations)
  (propagator-dispatch propagator-id network continuations))

(def closure-runner
  (drivers/trampoline-runner
   (constructor/network-iterator-constructor
    task-policy/fifo-task-policy
    propagator-evaluator-cps
    patch-evaluator-cps)))

(defn- install-propagator!
  [{:keys [network tasks] :as state} installer]
  (let [[propagator-id next-network] (nb/install-propagator network installer)]
    (assoc state
           :network next-network
           :tasks (tq/enqueue tasks propagator-id))))

(defn- install-port-stage
  [state input-id output-id]
  (let [closure-id (new-node-id)
        port-id (new-node-id)
        closure (identity-closure)
        network (-> (:network state)
                    (nb/install-cell closure-id closure closure)
                    (register-port port-id output-id))]
    (install-propagator! (assoc state :network network)
                         (ported-closure-propagator closure-id input-id output-id port-id))))

(defn- install-closure-stage
  [state input-id output-id]
  (let [closure-id (new-node-id)
        closure (identity-closure)
        network (nb/install-cell (:network state) closure-id closure closure)
        [propagator-id next-network]
        (nb/install-propagator network
                               (apply-closure-propagator closure-id input-id output-id))]
    (-> state
        (assoc :network (net/assoc-net-dict-entry
                         next-network
                         (application-key propagator-id)
                         {:closure-id closure-id
                          :input-id input-id
                          :output-id output-id}))
        (update :tasks tq/enqueue propagator-id))))

(defn build-experimental-chain
  "Build a linear port or atomic-closure chain without executing it."
  [variant depth]
  (if (pos? depth)
    (let [cells (vec (repeatedly (inc depth) new-node-id))
          initial {:network (nb/install-cells cells)
                   :tasks tq/empty-queue}
          install-stage (case variant
                          :port install-port-stage
                          :closure install-closure-stage
                          (throw (ex-info "unknown experimental variant"
                                          {:variant variant})))
          built (reduce (fn [state index]
                          (install-stage state (cells index) (cells (inc index))))
                        initial
                        (range depth))]
      (assoc built :input (first cells) :output (last cells) :variant variant))
    (throw (ex-info "chain depth must be positive" {:depth depth}))))

(defn build-vanilla-chain
  "Build the existing p:cons/cdr/car deep-accessor chain."
  [depth]
  (if (pos? depth)
    (let [sentinel (new-node-id)
          heads (vec (repeatedly depth new-node-id))
          collections (vec (repeatedly depth new-node-id))
          output (new-node-id)
          n0 (nb/install-cells (concat heads collections [sentinel output]))
          cons-state
          (reduce
           (fn [{:keys [network tasks]} index]
             (let [tail (if (< index (dec depth))
                          (collections (inc index))
                          sentinel)
                   [[car-prop cdr-prop] next-network]
                   ((obj/p:cons (heads index) tail (collections index)) network)]
               {:network next-network
                :tasks (tq/enqueue-all tasks [car-prop cdr-prop])}))
           {:network n0 :tasks tq/empty-queue}
           (range depth))
          accessor-installers
          (concat (map (fn [index]
                         (obj/p:cdr (collections (inc index)) (collections index)))
                       (range (dec depth)))
                  [(obj/p:car output (last collections))])
          built (reduce install-propagator! cons-state accessor-installers)]
      (assoc built
             :input (last heads)
             :output output
             :variant :vanilla))
    (throw (ex-info "chain depth must be positive" {:depth depth}))))

(defn build-chain
  [variant depth]
  (case variant
    :vanilla (build-vanilla-chain depth)
    :port (build-experimental-chain :port depth)
    :closure (build-experimental-chain :closure depth)
    (throw (ex-info "unknown chain variant" {:variant variant}))))

(defn- run-state
  [variant state]
  (case variant
    :vanilla (nb/run-propagators (:network state) (:tasks state))
    :port (complete-run (port-runner state))
    :closure (complete-run (closure-runner state))
    (throw (ex-info "unknown chain variant" {:variant variant}))))

(defn prepare-chain
  "Build and settle an unseeded chain. Returns an immutable update fixture."
  [variant depth]
  (let [built (build-chain variant depth)]
    (assoc built
           :network (run-state variant built)
           :tasks tq/empty-queue)))

(defn run-update
  "Seed a prepared chain and run it to quiescence, returning result and metrics."
  ([prepared] (run-update prepared 30))
  ([prepared input-value]
   (let [metrics (atom {})
         [seeded tasks] (nb/seed-cell! (:network prepared)
                                       tq/empty-queue
                                       (:input prepared)
                                       input-value)
         final-network (binding [*metrics* metrics]
                         (run-state (:variant prepared)
                                    {:network seeded :tasks tasks}))
         output-value (net/network-cell-value final-network (:output prepared))]
     {:ok (= input-value output-value)
      :value output-value
      :network final-network
      :metrics @metrics})))

(defn run-chain
  "Build, settle, update, and validate one chain."
  ([variant depth] (run-chain variant depth 30))
  ([variant depth input-value]
   (run-update (prepare-chain variant depth) input-value)))

(defn topology-metrics
  [prepared]
  (let [entries (vals (net/net-env (:network prepared)))]
    {:cells (count (remove prop/prop? entries))
     :propagators (count (filter prop/prop? entries))
     :dict-entries (count (net/net-dict-or-empty (:network prepared)))}))
