(ns examples.lain.subagents.assignment-model
  "Pure assignment scheduling derived from identified, ordered harness facts."
  (:require [examples.lain.subagents.facts :as facts]))

(defrecord Schedule [session assignments priority active rejections])

(def terminal-statuses #{:completed :assessed :cancelled :failed :rejected})

(defn empty-schedule [session]
  (facts/empty-log session)
  (->Schedule session {} [] nil []))

(defn session-id [schedule] (:session schedule))
(defn assignment-ids [schedule] (:priority schedule))
(defn active-assignment-id [schedule] (:active schedule))
(defn assignment-info [schedule assignment-id]
  (get (:assignments schedule) assignment-id))
(defn rejections [schedule] (:rejections schedule))
(defn assignment-infos [schedule]
  (mapv #(assignment-info schedule %) (assignment-ids schedule)))
(defn pending-assignment-ids [schedule]
  (filterv #(= :pending (:status (assignment-info schedule %)))
           (assignment-ids schedule)))

(defn- nonempty-string? [value] (and (string? value) (seq value)))
(defn- require-id [event]
  (when-not (nonempty-string? (:assignment-id event))
    (throw (ex-info "Assignment fact needs a nonempty assignment ID"
                    {:event-id (:id event) :kind (:kind event)})))
  (:assignment-id event))

(defn- register-submission [schedule event]
  (let [id (require-id event)
        task (:task event)]
    (when-not (nonempty-string? task)
      (throw (ex-info "Assignment submission needs task text" {:event-id (:id event)})))
    (if (assignment-info schedule id)
      schedule
      (-> schedule
          (assoc-in [:assignments id]
                    {:assignment-id id :task task :status :pending
                     :submitted event :evidence event})
          (update :priority conj id)))))

(defn- contradictory-submissions [events]
  (mapcat
   (fn [[id submissions]]
     (for [left submissions right submissions
           :when (and (neg? (compare (:id left) (:id right)))
                      (not= (:task left) (:task right)))]
       {:assignment-id id
        :event-ids [(:id left) (:id right)]
        :order (max (:order left) (:order right))
        :reason :conflicting-submission}))
   (sort-by key (group-by :assignment-id events))))

(defn- reject-conflicts [schedule events]
  (reduce
   (fn [current rejection]
     (-> current
         (assoc-in [:assignments (:assignment-id rejection) :status] :rejected)
         (update :rejections conj rejection)))
   schedule
   (contradictory-submissions events)))

(defn- set-active [schedule event]
  (let [id (require-id event)
        entry (assignment-info schedule id)
        active (active-assignment-id schedule)]
    (cond
      (nil? entry) schedule
      (or (:execution-terminal? entry)
          (and (terminal-statuses (:status entry)) (not= :rejected (:status entry)))) schedule
      (and active (not= active id))
      (throw (ex-info "More than one assignment is in flight"
                      {:active-assignment-id active :assignment-id id :event-id (:id event)}))
      :else
      (cond-> (-> schedule
                  (assoc :active id)
                  (assoc-in [:assignments id :runtime-phase] (:kind event)))
        (not= :rejected (:status entry))
        (assoc-in [:assignments id :status] :running)
        (not= :rejected (:status entry))
        (assoc-in [:assignments id :evidence] event)))))

(defn- finish [schedule event status field]
  (let [id (require-id event)
        entry (assignment-info schedule id)]
    (cond
      (nil? entry) schedule
      (= :rejected (:status entry))
      (cond-> (assoc-in schedule [:assignments id :execution-terminal?] true)
        (= id (active-assignment-id schedule)) (assoc :active nil))
      (terminal-statuses (:status entry)) schedule
      :else
      (cond-> (-> schedule
                  (assoc-in [:assignments id :status] status)
                  (assoc-in [:assignments id :evidence] event))
        (= id (active-assignment-id schedule)) (assoc :active nil)
        field (assoc-in [:assignments id field] (get event field))))))

(defn- attach-context [schedule event]
  (let [id (require-id event)]
    (when-not (map? (:context event))
      (throw (ex-info "Harbor context must be a bounded context map" {:event-id (:id event)})))
    (if (assignment-info schedule id)
      (assoc-in schedule [:assignments id :context] (:context event))
      schedule)))

(defn- attach-assessment [schedule event]
  (let [id (require-id event)
        entry (assignment-info schedule id)]
    (when-not (map? (:assessment event))
      (throw (ex-info "Harbor assessment must be an assessment map" {:event-id (:id event)})))
    (if (= :completed (:status entry))
      (-> schedule
          (assoc-in [:assignments id :status] :assessed)
          (assoc-in [:assignments id :assessment] (:assessment event))
          (assoc-in [:assignments id :evidence] event))
      schedule)))

(defn- reorder [schedule event]
  (let [ids (:assignment-ids event)]
    (when-not (and (vector? ids) (every? nonempty-string? ids)
                   (= (count ids) (count (distinct ids))))
      (throw (ex-info "Assignment order must contain distinct assignment IDs"
                      {:event-id (:id event)})))
    (let [pending (set (pending-assignment-ids schedule))
          preferred (filterv pending ids)
          remaining (remove (set preferred) (assignment-ids schedule))]
      (assoc schedule :priority (into preferred remaining)))))

(defn- cancel [schedule event]
  (let [id (require-id event)
        entry (assignment-info schedule id)]
    (if (= :pending (:status entry))
      (finish schedule event :cancelled nil)
      schedule)))

(defn- apply-event [schedule event]
  (case (:kind event)
    :assignment/reordered (reorder schedule event)
    :assignment/cancel-requested (cancel schedule event)
    :runtime/requested (set-active schedule event)
    :runtime/started (set-active schedule event)
    :runtime/uncertain (set-active schedule event)
    :runtime/completed
    (do
      (when-not (contains? event :result)
        (throw (ex-info "Runner completion needs a collected result" {:event-id (:id event)})))
      (finish schedule event :completed :result))
    :runtime/cancelled
    (do
      (when-not (true? (:cleanup-verified event))
        (throw (ex-info "Runtime cancellation needs verified cleanup" {:event-id (:id event)})))
      (finish schedule event :cancelled nil))
    :runtime/failed (finish schedule event :failed :failure)
    :harbor/context (attach-context schedule event)
    :harbor/assessed (attach-assessment schedule event)
    schedule))

(defn derive-schedule
  "Derive a current view. Missing source facts remain pending; they are not invented.
  Submissions establish identities before observations are folded, permitting late
  delivery while terminal facts absorb later launch/progress observations."
  [event-log]
  (when-not (facts/log? event-log)
    (throw (ex-info "Scheduling requires a harness fact log" {})))
  (let [events (facts/ordered-facts event-log)
        submissions (filterv #(= :assignment/submitted (:kind %)) events)
        initial (-> (reduce register-submission (empty-schedule (facts/session-id event-log)) submissions)
                    (reject-conflicts submissions))]
    (reduce apply-event initial events)))

(defn next-offer
  "Return the current bounded execution interface, or nil until preparation exists.
  Claimed/uncertain work remains selected even when pending priorities change."
  [schedule]
  (let [id (or (active-assignment-id schedule)
               (first (pending-assignment-ids schedule)))
        entry (assignment-info schedule id)]
    (when (and entry (not (terminal-statuses (:status entry))) (map? (:context entry)))
      {:session (session-id schedule) :assignment-id id
       :task (:task entry) :context (:context entry)})))

(defn preparation-inputs [schedule]
  (filterv #(and (not (terminal-statuses (:status %)))
                 (not (contains? % :context)))
           (assignment-infos schedule)))

(defn grading-inputs [schedule]
  (filterv #(and (= :completed (:status %))
                 (contains? % :result)
                 (map? (:context %)))
           (assignment-infos schedule)))
