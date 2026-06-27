(ns propagators.gur.accumulating.runner.executor
  "Private execution machinery for accumulated GUR network values.

  The mailbox is the child-to-owner accumulated network fragment stored in the
  applied-net cell. It is declaration data; runner cursors stay in local atoms."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.diff :as diff]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.gur.accumulating.core :as acc]
            [propagators.gur.accumulating.facts :as facts]
            [propagators.gur.accumulating.runner.instrumentation :as instr]
            [propagators.gur.accumulating.runner.tasks :as runner-tasks]
            [propagators.gur.subenv.output :as output]
            [propagators.gur.subenv.queue :as queue]
            [propagators.gur.subenv.scoped-slot :as scoped-slot]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(def ^:private max-child-steps 65536)

(defn reset-mailbox
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
  [parent-net acc-net applied-net-id import-ids external-output-ids boundary-ids]
  (let [n0 (reduce #(import-parent-cell %1 parent-net %2)
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
                  (facts/add-task-facts
                   n
                   [:boundary id]
                   (runner-tasks/indexed-prop-ids n)
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
    (when (and (not (facts/frame-declared? n nil app-key))
               (acc/recursive-closure? closure))
      (acc/build-frame-fragment n
                                closure-id
                                arg-ids
                                applied-net-id
                                out-id
                                closure
                                arg-values
                                out-value))))

(defn- fully-expanded-requests?
  [requests expanded?]
  (every? expanded? (keys requests)))

(defn- expand-application-requests
  [n applied-net-id request-cache request-scan-cache]
      (let [requests (facts/application-requests n)
        cached @request-scan-cache]
    (if (and (identical? requests (:requests cached))
             (:fully-expanded? cached))
      n
      (let [expanded? @request-cache]
        (if (fully-expanded-requests? requests expanded?)
          (do
            (reset! request-scan-cache {:requests requests
                                        :fully-expanded? true})
            n)
          (let [expanded-net
                (reduce (fn [current [app-key request]]
                          (cond
                            (contains? expanded? app-key)
                            current

                            (facts/frame-declared? current nil app-key)
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
                        requests)]
            (when (fully-expanded-requests? requests @request-cache)
              (reset! request-scan-cache {:requests requests
                                          :fully-expanded? true}))
            expanded-net))))))

(defn- run-accumulated-child
  [child-net runner-state applied-net-id]
  ;; ponytail: task facts grow monotonically; this cursor is primitive-local
  ;; runtime state and only records which task indexes have been consumed.
  (let [{:keys [task-cursor
                request-cache
                request-scan-cache
                prop-state-cache
                prop-io-cache]} runner-state
        timed instr/timed-phase]
    (loop [remaining max-child-steps
           current (timed :run-child/expand-initial
                          #(expand-application-requests child-net
                                                        applied-net-id
                                                        request-cache
                                                        request-scan-cache))]
      (let [pending (timed :run-child/next-pending
                           #(runner-tasks/next-pending-task-fact
                             current
                             @task-cursor))]
        (cond
          (zero? remaining)
          (throw (ex-info "accumulating GUR child run exceeded step budget"
                          {:max-steps max-child-steps}))

          (nil? pending)
          current

          :else
          (let [{:keys [task-key index prop-ids]} pending
                ran (timed :run-child/run-props
                           #(binding [instr/*current-task-fact* (select-keys pending
                                                                             [:task-key
                                                                              :index])]
                              (runner-tasks/run-props-with-state-cache
                               current
                               prop-ids
                               prop-state-cache
                               prop-io-cache)))
                next (timed :run-child/expand-after-task
                            #(expand-application-requests ran
                                                          applied-net-id
                                                          request-cache
                                                          request-scan-cache))]
            (swap! task-cursor
                   update
                   task-key
                   (fnil conj #{})
                   index)
            (recur (dec remaining) next)))))))

(defn- mailbox-with-task-facts
  [child-net applied-net-id mailbox mailbox-index]
  (let [mailbox-props (runner-tasks/indexed-prop-ids mailbox)
        request-only? (and (seq (facts/application-requests mailbox))
                           (empty? mailbox-props)
                           (empty? (net/net-env mailbox))
                           (empty? (net/net-graph mailbox)))]
    (if request-only?
      mailbox
      (facts/add-task-facts mailbox
                            [:mailbox applied-net-id]
                            (distinct (concat (runner-tasks/indexed-prop-ids child-net)
                                              mailbox-props))
                            mailbox-index))))

(defn- prune-expanded-requests
  [child-net mailbox]
  (let [requests (facts/application-requests mailbox)]
    (if (empty? requests)
      mailbox
      (let [requests* (into {}
                            (remove (fn [[app-key _request]]
                                      (facts/frame-declared? child-net
                                                             nil
                                                             app-key)))
                            requests)]
        (net/net-with-dict
         mailbox
         (cond-> (net/net-dict-or-empty mailbox)
           (empty? requests*) (dissoc facts/application-request-index-key)
           (seq requests*) (assoc facts/application-request-index-key requests*)))))))

(defn- mailbox-work?
  [mailbox]
  (seq (net/net-dict-or-empty mailbox)))

(defn- settle-accumulated-child
  [runner-state parent-net child-net applied-net-id]
  ;; ponytail: owner-local reconciliation; do not publish a half-merged mailbox
  ;; and wait for a later runner turn to discover its tasks.
  (let [{:keys [mailbox-epoch]} runner-state
        timed instr/timed-phase]
    (loop [remaining 64
           current child-net]
      (when (zero? remaining)
        (throw (ex-info "accumulating GUR mailbox reconciliation exceeded step budget"
                        {:max-steps 64})))
      (let [child1 (timed :settle/run-child
                          #(run-accumulated-child current
                                                  runner-state
                                                  applied-net-id))
            mailbox (timed :settle/read-mailbox
                           #(acc/strongest-or-nothing child1 applied-net-id))]
        (if (net/net? mailbox)
          (let [mailbox0 (timed :settle/prune-mailbox
                                #(prune-expanded-requests child1 mailbox))]
            (if-not (mailbox-work? mailbox0)
              child1
              (let [mailbox* (timed :settle/mailbox-task-facts
                                    #(mailbox-with-task-facts child1
                                                             applied-net-id
                                                             mailbox0
                                                             (swap! mailbox-epoch inc)))
                    child2 (timed :settle/reset-mailbox
                                  #(reset-mailbox child1 applied-net-id))
                    merged (timed :settle/merge-mailbox
                                  #(merge/strongest-value
                                    (merge/cell-merge child2 mailbox* parent-net)
                                    parent-net))]
                (if (= merged child2)
                  child2
                  (recur (dec remaining) merged)))))
          child1)))))

(defn run-accumulated-messages
  [runner-state parent-net applied-net-id import-ids external-output-ids boundary-ids]
  (let [acc0 (acc/strongest-or-nothing parent-net applied-net-id)]
    (if (or (value/nothing? acc0)
            (not (net/net? acc0)))
      []
      (let [timed instr/timed-phase
            acc1 (timed :boundary-tasks
                        #(add-boundary-task-facts parent-net
                                                  acc0
                                                  (concat import-ids external-output-ids)))
            child0 (timed :prepare-run-net
                          #(prepare-run-net parent-net
                                            acc1
                                            applied-net-id
                                            import-ids
                                            external-output-ids
                                            boundary-ids))
            child1 (timed :settle-child
                          #(settle-accumulated-child runner-state
                                                     parent-net
                                                     child0
                                                     applied-net-id))
            boundary-output-ids (vec (distinct (concat import-ids
                                                       external-output-ids)))
            diff-view (timed :externalize-output
                             #(output/externalize-output-cells child1
                                                               boundary-output-ids))
            output-msgs (timed :output-diff
                               #(vec (diff/diff-internal-output-cells
                                      diff-view
                                      parent-net
                                      boundary-output-ids)))
            accessor-msgs (timed :accessor-export
                                 #(scoped-slot/direct-child-accessor-messages-for-neighbors
                                   parent-net
                                   child1))
            external-msgs (timed :external-messages
                                 #(queue/external-messages child1))
            child2 (timed :clear-runtime-mailboxes
                          #(-> child1
                               queue/clear-external-messages
                               (reset-mailbox applied-net-id)))]
        (cond-> (vec (concat output-msgs
                             accessor-msgs
                             external-msgs))
          (not= acc0 child2)
          (conj (message applied-net-id child2)))))))

(defn runner-input-token
  [parent-net input-ids]
  (mapv (fn [id]
          (get (net/net-env parent-net) id))
        input-ids))

(defn same-input-token?
  [a b]
  (and (= (count a) (count b))
       (every? true? (map identical? a b))))

(defn cached-boundary-cell-ids
  [runner-state parent-net import-ids external-output-ids]
  (let [ids (vec (distinct (concat import-ids external-output-ids)))
        token (runner-input-token parent-net ids)
        boundary-cache (:boundary-cache runner-state)
        cached @boundary-cache]
    (if (same-input-token? token (:token cached))
      (:ids cached)
      (let [boundary-ids (acc/boundary-cell-ids
                          parent-net
                          ids
                          (map #(acc/strongest-or-nothing parent-net %)
                               ids))]
        (reset! boundary-cache {:token token :ids boundary-ids})
        boundary-ids))))
