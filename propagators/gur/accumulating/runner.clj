(ns propagators.gur.accumulating.runner
  "Runtime executor for accumulated GUR network values."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :as diff]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.graph :as graph]
            [propagators.gur.accumulating.core :as acc]
            [propagators.gur.subenv.output :as output]
            [propagators.gur.subenv.queue :as queue]
            [propagators.gur.subenv.scoped-slot :as scoped-slot]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def ^:private max-child-steps 65536)

(def ^:dynamic *prop-run-observer*
  "Optional debug hook called with runner prop scheduling events.
  Events are maps with :event, :prop-id, and :net; :ran events also include
  :elapsed-ns. This is runtime instrumentation only, not recursive semantics."
  nil)

(def ^:dynamic *phase-observer*
  "Optional debug hook called with coarse runner phase timings."
  nil)

(def ^:dynamic *current-task-fact* nil)

(defn- observe-prop-run!
  [event]
  (when-let [observer *prop-run-observer*]
    (observer (cond-> event
                *current-task-fact*
                (assoc :task-fact *current-task-fact*)))))

(defmacro timed-phase
  [phase & body]
  `(if *phase-observer*
     (let [started# (System/nanoTime)
           result# (do ~@body)]
       (*phase-observer* {:phase ~phase
                          :elapsed-ns (- (System/nanoTime) started#)})
       result#)
     (do ~@body)))

(defn- reset-mailbox
  [n applied-net-id]
  (-> n
      (nb/ensure-cell applied-net-id)
      (nb/seed-cell applied-net-id value/nothing)))

(defn- import-parent-cell
  [child-net parent-net id]
  (let [child-entry (get (net/net-env child-net) id)
        parent-entry (get (net/net-env parent-net) id)]
    (if (and (cell/cell? child-entry) (cell/cell? parent-entry))
      (net/assoc-net-cell child-net
                          id
                          (merge/merge-cell-entry child-entry
                                                  (cell/cell-content parent-entry)
                                                  parent-net))
      child-net)))

(defn- prepare-run-net
  [parent-net acc-net applied-net-id import-ids external-output-ids]
  (let [boundary-ids (acc/boundary-cell-ids
                      parent-net
                      (distinct (concat import-ids external-output-ids))
                      (map #(acc/strongest-or-nothing parent-net %)
                           (concat import-ids external-output-ids)))
        n0 (reduce #(import-parent-cell %1 parent-net %2)
                   (reduce nb/ensure-cell
                           (reset-mailbox acc-net applied-net-id)
                           boundary-ids)
                   boundary-ids)]
    (reduce (fn [n external-id]
              (-> n
                  (net/assoc-avatar-out external-id external-id)
                  (import-parent-cell parent-net external-id)))
            n0
            (distinct (concat import-ids external-output-ids)))))

(defn- accumulated-prop-ids
  [n]
  (->> (net/net-env n)
       (keep (fn [[id entry]]
               (when (and (prop/prop? entry)
                          (contains? (net/net-graph n) id))
                 id)))
       (sort-by pr-str)
       vec))

(defn- pending-sort-key
  [task-key index]
  ;; Boundary tasks use the current declared prop index. Run declaration tasks
  ;; first so a boundary index does not get consumed before the props it should
  ;; wake have been added.
  [(if (= :boundary (first task-key)) 1 0)
   (pr-str task-key)
   (pr-str index)])

(defn- earlier-pending?
  [a b]
  (or (nil? b)
      (neg? (compare (:sort-key a) (:sort-key b)))))

(defn- next-pending-task-fact
  [n task-cursor]
  (let [best (reduce (fn [best [task-key {:keys [indexes] :as entry}]]
                       (let [consumed (get task-cursor task-key #{})]
                         (reduce (fn [best index]
                                   (if (contains? consumed index)
                                     best
                                     (let [candidate {:task-key task-key
                                                      :index index
                                                      :entry entry
                                                      :sort-key (pending-sort-key
                                                                 task-key
                                                                 index)}]
                                       (if (earlier-pending? candidate best)
                                         candidate
                                         best))))
                                 best
                                 indexes)))
                     nil
                     (net/network-dict-entry n acc/task-index-key))]
    (when best
      (let [{:keys [prop-ids prop-id]} (:entry best)]
        {:task-key (:task-key best)
         :index (:index best)
         :prop-ids (vec (or prop-ids #{prop-id}))}))))

(defn- indexed-prop-ids
  [n]
  (vec (net/network-dict-entry n acc/frame-prop-index-key)))

(defn- cell-state
  [n id]
  (let [entry (net/network-env-lookup n id)]
    (if (cell/cell? entry)
      [:cell (cell/cell-strongest entry)]
      [:entry entry])))

(defn- prop-observed-ids
  [n prop-id prop-io-cache]
  (if-let [ids (get @prop-io-cache prop-id)]
    ids
    (when-let [node (graph/get-node (net/net-graph n) prop-id)]
      (let [entry (net/env-get (net/net-env n) prop-id)
            observed-ids (if (= :inputs (:observe entry))
                           (graph/node-input-ids node)
                           (distinct (concat (graph/node-input-ids node)
                                             (graph/node-output-ids node))))
            ids (vec (sort-by pr-str observed-ids))]
        (swap! prop-io-cache assoc prop-id ids)
        ids))))

(defn- prop-observed-state
  [n prop-id prop-io-cache]
  (when-let [ids (prop-observed-ids n prop-id prop-io-cache)]
    (mapv (fn [id] [id (cell-state n id)]) ids)))

(defn- run-props-with-state-cache
  [n prop-ids prop-state-cache prop-io-cache]
  (loop [tasks (tq/enqueue-all tq/empty-queue prop-ids)
         current n]
    (if (tq/queue-empty? tasks)
      current
      (let [[prop-id remaining] (tq/pop-task tasks)
            observed (prop-observed-state current prop-id prop-io-cache)]
        (observe-prop-run! {:event :considered
                            :prop-id prop-id
                            :net current})
        (if (and observed (= observed (get @prop-state-cache prop-id)))
          (do
            (observe-prop-run! {:event :skipped
                                :prop-id prop-id
                                :net current})
            (recur remaining current))
          (let [before-env-count (count (net/net-env current))
                before-dict-count (count (net/net-dict-or-empty current))
                started (System/nanoTime)
                [next-tasks next-net] (core/eval-propagator prop-id
                                                            remaining
                                                            current)
                elapsed-ns (- (System/nanoTime) started)
                after-env-count (count (net/net-env next-net))
                after-dict-count (count (net/net-dict-or-empty next-net))
                observed* (prop-observed-state next-net prop-id prop-io-cache)]
            (observe-prop-run! {:event :ran
                                :prop-id prop-id
                                :net current
                                :elapsed-ns elapsed-ns
                                :env-delta (- after-env-count before-env-count)
                                :dict-delta (- after-dict-count before-dict-count)
                                :changed? (not= next-net current)})
            (when observed*
              (swap! prop-state-cache assoc prop-id observed*))
            (recur next-tasks next-net)))))))

(defn- same-cell-value?
  [a b]
  (and (cell/cell? a)
       (cell/cell? b)
       (= (cell/cell-content a) (cell/cell-content b))
       (= (cell/cell-strongest a) (cell/cell-strongest b))))

(defn- add-boundary-task-facts
  [parent-net acc-net boundary-ids]
  (reduce (fn [n id]
            (if (and (ids/node-id? id)
                     (contains? (net/net-env parent-net) id))
              (let [entry (net/network-env-lookup parent-net id)]
                (if (same-cell-value? entry (get (net/net-env n) id))
                  n
                  (acc/add-task-facts
                   n
                   [:boundary id]
                   (indexed-prop-ids n)
                   (hash {:content (cell/cell-content entry)
                          :strongest (cell/cell-strongest entry)}))))
              n))
          acc-net
          boundary-ids))

(defn- expandable-request
  [n applied-net-id app-key {:keys [closure-id arg-ids out-id]}]
  (let [closure (acc/strongest-or-nothing n closure-id)
        arg-values (mapv #(acc/strongest-or-nothing n %) arg-ids)
        out-value (acc/strongest-or-nothing n out-id)]
    (when (and (not (acc/frame-declared? n nil app-key))
               (acc/recursive-closure? closure))
      (acc/build-frame-fragment n
                                closure-id
                                arg-ids
                                applied-net-id
                                out-id
                                closure
                                arg-values
                                out-value))))

(defn- expand-application-requests
  [n applied-net-id request-cache]
  (let [requests (acc/application-requests n)
        expanded? @request-cache]
    (if (every? expanded? (keys requests))
      n
      (reduce (fn [current [app-key request]]
                (cond
                  (contains? expanded? app-key)
                  current

                  (acc/frame-declared? current nil app-key)
                  (do
                    (swap! request-cache conj app-key)
                    current)

                  :else
                  (if-let [fragment (expandable-request current
                                                        applied-net-id
                                                        app-key
                                                        request)]
                    (do
                      (swap! request-cache conj app-key)
                      (merge/strongest-value
                       (merge/cell-merge current fragment current)
                       current))
                    current)))
              n
              (sort-by (comp pr-str key) requests)))))

(defn- run-accumulated-child
  [child-net task-cursor request-cache prop-state-cache prop-io-cache applied-net-id]
  ;; ponytail: task facts grow monotonically; this cursor is primitive-local
  ;; runtime state and only records which task indexes have been consumed.
  (loop [remaining max-child-steps
         current (timed-phase :run-child/expand-initial
                   (expand-application-requests child-net
                                                applied-net-id
                                                request-cache))]
    (let [pending (timed-phase :run-child/next-pending
                    (next-pending-task-fact current @task-cursor))]
      (cond
        (zero? remaining)
        (throw (ex-info "accumulating GUR child run exceeded step budget"
                        {:max-steps max-child-steps}))

        (nil? pending)
        current

        :else
        (let [{:keys [task-key index prop-ids]} pending
              ran (timed-phase :run-child/run-props
                    (binding [*current-task-fact* (select-keys pending
                                                               [:task-key
                                                                :index])]
                      (run-props-with-state-cache current
                                                  prop-ids
                                                  prop-state-cache
                                                  prop-io-cache)))
              next (timed-phase :run-child/expand-after-task
                     (expand-application-requests ran
                                                  applied-net-id
                                                  request-cache))]
          (swap! task-cursor
                 update
                 task-key
                 (fnil conj #{})
                 index)
          (recur (dec remaining) next))))))

(defn- mailbox-with-task-facts
  [child-net applied-net-id mailbox mailbox-index]
  (acc/add-task-facts mailbox
                      [:mailbox applied-net-id]
                      (distinct (concat (indexed-prop-ids child-net)
                                        (indexed-prop-ids mailbox)))
                      mailbox-index))

(defn- prune-expanded-requests
  [child-net mailbox]
  (let [requests (acc/application-requests mailbox)]
    (if (empty? requests)
      mailbox
      (let [requests* (into {}
                            (remove (fn [[app-key _request]]
                                      (acc/frame-declared? child-net
                                                           nil
                                                           app-key)))
                            requests)]
        (net/net-with-dict
         mailbox
         (cond-> (net/net-dict-or-empty mailbox)
           (empty? requests*) (dissoc acc/application-request-index-key)
           (seq requests*) (assoc acc/application-request-index-key requests*)))))))

(defn- mailbox-work?
  [mailbox]
  (seq (net/net-dict-or-empty mailbox)))

(defn- settle-accumulated-child
  [task-cursor request-cache prop-state-cache prop-io-cache mailbox-epoch parent-net child-net applied-net-id]
  ;; ponytail: owner-local reconciliation; do not publish a half-merged mailbox
  ;; and wait for a later runner turn to discover its tasks.
  (loop [remaining 64
         current child-net]
    (when (zero? remaining)
      (throw (ex-info "accumulating GUR mailbox reconciliation exceeded step budget"
                      {:max-steps 64})))
    (let [child1 (timed-phase :settle/run-child
                   (run-accumulated-child current
                                          task-cursor
                                          request-cache
                                          prop-state-cache
                                          prop-io-cache
                                          applied-net-id))
          mailbox (timed-phase :settle/read-mailbox
                    (acc/strongest-or-nothing child1 applied-net-id))]
      (if (net/net? mailbox)
        (let [mailbox0 (timed-phase :settle/prune-mailbox
                         (prune-expanded-requests child1 mailbox))]
          (if-not (mailbox-work? mailbox0)
            child1
            (let [mailbox* (timed-phase :settle/mailbox-task-facts
                             (mailbox-with-task-facts child1
                                                      applied-net-id
                                                      mailbox0
                                                      (swap! mailbox-epoch inc)))
                  child2 (timed-phase :settle/reset-mailbox
                           (reset-mailbox child1 applied-net-id))
                  merged (timed-phase :settle/merge-mailbox
                           (merge/strongest-value
                            (merge/cell-merge child2 mailbox* parent-net)
                            parent-net))]
              (if (= merged child2)
                child2
                (recur (dec remaining) merged)))))
        child1))))

(defn- run-accumulated-messages
  [task-cursor request-cache prop-state-cache prop-io-cache mailbox-epoch parent-net applied-net-id import-ids external-output-ids]
  (let [acc0 (acc/strongest-or-nothing parent-net applied-net-id)]
    (if-not (net/net? acc0)
      []
      (let [acc1 (timed-phase :boundary-tasks
                   (add-boundary-task-facts parent-net
                                            acc0
                                            (concat import-ids external-output-ids)))
            child0 (timed-phase :prepare-run-net
                     (prepare-run-net parent-net
                                      acc1
                                      applied-net-id
                                      import-ids
                                      external-output-ids))
            child1 (timed-phase :settle-child
                     (settle-accumulated-child task-cursor
                                               request-cache
                                               prop-state-cache
                                               prop-io-cache
                                               mailbox-epoch
                                               parent-net
                                               child0
                                               applied-net-id))
            boundary-output-ids (vec (distinct (concat import-ids
                                                       external-output-ids)))
            diff-view (timed-phase :externalize-output
                        (output/externalize-output-cells child1
                                                         boundary-output-ids))
            output-msgs (timed-phase :output-diff
                          (vec (diff/diff-internal-output-cells
                                diff-view
                                parent-net
                                boundary-output-ids)))
            accessor-msgs (timed-phase :accessor-export
                            (scoped-slot/direct-child-accessor-messages-for-neighbors
                             parent-net
                             child1))
            external-msgs (timed-phase :external-messages
                            (queue/external-messages child1))
            child2 (timed-phase :clear-runtime-mailboxes
                     (-> child1
                         queue/clear-external-messages
                         (reset-mailbox applied-net-id)))]
        (cond-> (vec (concat output-msgs
                              accessor-msgs
                              external-msgs))
          (not= acc0 child2)
          (conj (message applied-net-id child2)))))))

(defn p:run-accumulated-network
  ([applied-net-id external-output-ids]
   (p:run-accumulated-network applied-net-id [] external-output-ids))
  ([applied-net-id import-ids external-output-ids]
   (let [import-ids (vec import-ids)
         external-output-ids (vec external-output-ids)
         inputs (vec (distinct (concat [applied-net-id]
                                       import-ids
                                       external-output-ids)))
         task-cursor (atom {})
         request-cache (atom #{})
         prop-state-cache (atom {})
         prop-io-cache (atom {})
         ;; ponytail: runner-local scheduling token; not recursive semantics.
         mailbox-epoch (atom 0)]
     (prop/construct-propagator
      (fn [_inputs _outputs parent-net]
        (run-accumulated-messages task-cursor
                                  request-cache
                                  prop-state-cache
                                  prop-io-cache
                                  mailbox-epoch
                                  parent-net
                                  applied-net-id
                                  import-ids
                                  external-output-ids))
      inputs
      (into [applied-net-id] (distinct (concat import-ids
                                               external-output-ids)))))))
