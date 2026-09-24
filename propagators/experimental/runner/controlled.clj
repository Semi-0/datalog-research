(ns propagators.experimental.runner.controlled
  "Cooperative pause, resume, and forked history for an async iterator.")

(defn- visible-state
  [control]
  (select-keys control
               [:mode :active-branch :next-branch-id
                :generation :branches :terminal]))

(defn- current-frame
  [{:keys [active-branch branches]}]
  (let [{:keys [frames cursor]} (get branches active-branch)]
    (get frames cursor)))

(defn- append-frame
  [control state]
  (let [branch-id (:active-branch control)]
    (update-in control
               [:branches branch-id]
               (fn [{:keys [frames]}]
                 {:frames (conj frames state)
                  :cursor (count frames)}))))

(defn- deliver-pause-waiters!
  [waiters value]
  (doseq [waiter waiters]
    (deliver waiter value)))

(defn- fail-control!
  [{:keys [lock control*]} generation error]
  (let [completion
        (locking lock
          (let [control @control*]
            (if (or (not= generation (:generation control))
                    (contains? #{:completed :failed :closed} (:mode control)))
              nil
              (let [next-control (assoc control
                                        :mode :failed
                                        :terminal {:status :failed :error error}
                                        :pause-waiters [])]
                (reset! control* next-control)
                {:fail (get-in control [:handlers :fail])
                 :waiters (:pause-waiters control)
                 :snapshot (visible-state next-control)}))))]
    (if completion
      (do
        (deliver-pause-waiters! (:waiters completion) (:snapshot completion))
        ((:fail completion) error))
      nil)))

(declare dispatch! complete! continued!)

(defn- dispatch!
  [{:keys [schedule advance] :as context} generation state]
  (try
    (schedule
     (fn []
       (try
         (advance state
                  {:done #(complete! context generation %)
                   :fail #(fail-control! context generation %)
                   :continue #(continued! context generation %)})
         (catch Throwable error
           (fail-control! context generation error)))))
    (catch Throwable error
      (fail-control! context generation error))))

(defn- complete!
  [{:keys [lock control*]} generation network]
  (let [completion
        (locking lock
          (let [control @control*]
            (if (or (not= generation (:generation control))
                    (contains? #{:completed :failed :closed} (:mode control)))
              nil
              (let [next-control (assoc control
                                        :mode :completed
                                        :terminal {:status :completed
                                                   :network network}
                                        :pause-waiters [])]
                (reset! control* next-control)
                {:done (get-in control [:handlers :done])
                 :waiters (:pause-waiters control)
                 :snapshot (visible-state next-control)}))))]
    (if completion
      (do
        (deliver-pause-waiters! (:waiters completion) (:snapshot completion))
        ((:done completion) network))
      nil)))

(defn- continue-transition!
  [control* control state]
  (let [with-frame (append-frame control state)]
    (if (= :pausing (:mode control))
      (let [paused (assoc with-frame :mode :paused :pause-waiters [])]
        (reset! control* paused)
        (deliver-pause-waiters! (:pause-waiters control) (visible-state paused))
        nil)
      (do
        (reset! control* with-frame)
        [(:generation control) state]))))

(defn- continued!
  [{:keys [lock control*] :as context} generation state]
  (let [next-dispatch
        (locking lock
          (let [control @control*]
            (if (or (not= generation (:generation control))
                    (contains? #{:paused :completed :failed :closed}
                               (:mode control)))
              nil
              (continue-transition! control* control state))))]
    (if next-dispatch
      (apply dispatch! context next-dispatch)
      nil)))

(defn- start-control!
  [{:keys [lock control*] :as context} initial-state handlers]
  (let [generation
        (locking lock
          (let [control @control*]
            (if (= :idle (:mode control))
              (let [branch-id (:next-branch-id control)
                    next-control (assoc control
                                        :mode :running
                                        :active-branch branch-id
                                        :next-branch-id (inc branch-id)
                                        :handlers handlers
                                        :branches {branch-id
                                                   {:frames [initial-state]
                                                    :cursor 0}})]
                (reset! control* next-control)
                (:generation next-control))
              (throw (ex-info "controller already started"
                              {:mode (:mode control)})))))]
    (dispatch! context generation initial-state)
    :scheduled))

(defn- pause-control!
  [{:keys [lock control*]}]
  (let [waiter (promise)
        immediate
        (locking lock
          (let [control @control*]
            (cond
              (= :running (:mode control))
              (do
                (swap! control*
                       #(-> %
                            (assoc :mode :pausing)
                            (update :pause-waiters conj waiter)))
                nil)

              (= :pausing (:mode control))
              (do
                (swap! control* update :pause-waiters conj waiter)
                nil)

              :else
              (visible-state control))))]
    (if immediate
      (deliver waiter immediate)
      nil)
    waiter))

(defn- resume-control!
  [{:keys [lock control*] :as context}]
  (let [[generation state]
        (locking lock
          (let [control @control*]
            (if (= :paused (:mode control))
              (let [next-control (-> control
                                     (assoc :mode :running)
                                     (update :generation inc))]
                (reset! control* next-control)
                [(:generation next-control) (current-frame next-control)])
              (throw (ex-info "resume requires paused controller"
                              {:mode (:mode control)})))))]
    (dispatch! context generation state)
    :scheduled))

(defn- back-control!
  [{:keys [lock control*]} steps]
  (locking lock
    (let [control @control*
          source-id (:active-branch control)
          {:keys [frames cursor]} (get-in control [:branches source-id])
          target (- cursor steps)]
      (cond
        (not= :paused (:mode control))
        (throw (ex-info "back requires paused controller"
                        {:mode (:mode control)}))

        (or (neg? steps) (neg? target))
        (throw (ex-info "back steps outside retained history"
                        {:steps steps :cursor cursor}))

        :else
        (let [branch-id (:next-branch-id control)
              branch {:frames (subvec frames 0 (inc target))
                      :cursor target}
              next-control (-> control
                               (assoc :active-branch branch-id)
                               (assoc :next-branch-id (inc branch-id))
                               (update :generation inc)
                               (assoc-in [:branches branch-id] branch))]
          (reset! control* next-control)
          {:status :forked
           :source-branch source-id
           :branch branch-id
           :cursor target
           :state (current-frame next-control)})))))

(defn- control-snapshot
  [{:keys [lock control*]}]
  (locking lock
    (visible-state @control*)))

(defn- close-control!
  [{:keys [lock control* close-scheduler] :as context}]
  (let [waiters
        (locking lock
          (let [control @control*]
            (reset! control*
                    (-> control
                        (assoc :mode :closed)
                        (update :generation inc)
                        (assoc :pause-waiters [])))
            (:pause-waiters control)))]
    (deliver-pause-waiters! waiters (control-snapshot context))
    (if close-scheduler
      (close-scheduler)
      nil)
    :closed))

(defn controlled-scheduler-runner
  "Return a stateful controller around `advance` and `schedule`.

  The optional `close-scheduler` hook is called by `:close!`."
  ([schedule advance]
   (controlled-scheduler-runner schedule nil advance))
  ([schedule close-scheduler advance]
   (let [lock (Object.)
         control* (atom {:mode :idle
                         :active-branch nil
                         :next-branch-id 0
                         :generation 0
                         :branches {}
                         :pause-waiters []})
         context {:lock lock
                  :control* control*
                  :schedule schedule
                  :advance advance
                  :close-scheduler close-scheduler}]
     {:start! #(start-control! context %1 %2)
      :pause! #(pause-control! context)
      :resume! #(resume-control! context)
      :back! #(back-control! context %)
      :snapshot #(control-snapshot context)
      :close! #(close-control! context)})))
