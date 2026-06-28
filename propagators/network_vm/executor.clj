(ns propagators.network-vm.executor
  "Executor for the experimental monotone network VM."
  (:require [propagators.core :as core]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-vm.instructions :as instr]
            [propagators.propagator :as prop]))

(defn state
  ([] (state net/empty-net))
  ([n]
   {:net n
    :messages []
    :tasks {}
    :cursor {}
    :emitted []}))

(defn- task-ids
  [tasks]
  (loop [q (tq/into-queue tasks)
         ids []]
    (if (tq/queue-empty? q)
      ids
      (let [[id q*] (tq/pop-task q)]
        (recur q* (conj ids id))))))

(defn- add-task
  [tasks cause prop-ids index]
  (if (empty? prop-ids)
    tasks
    (reduce (fn [m prop-id]
              (update m cause
                      (fn [entry]
                        {:prop-ids (conj (set (:prop-ids entry)) prop-id)
                         :indexes (conj (set (:indexes entry)) index)})))
            tasks
            prop-ids)))

(defn- pending-task
  [{:keys [tasks cursor]}]
  (reduce-kv
   (fn [best cause {:keys [indexes] :as entry}]
     (let [consumed (get cursor cause #{})]
       (reduce (fn [best index]
                 (if (contains? consumed index)
                   best
                   (let [candidate {:cause cause
                                    :index index
                                    :prop-ids (vec (:prop-ids entry))
                                    :sort-key [(pr-str cause) (pr-str index)]}]
                     (if (or (nil? best)
                             (neg? (compare (:sort-key candidate)
                                            (:sort-key best))))
                       candidate
                       best))))
               best
               indexes)))
   nil
   tasks))

(defn pending-task-count
  [vm-state]
  (reduce-kv
   (fn [n cause {:keys [indexes]}]
     (+ n (count (remove (get (:cursor vm-state) cause #{}) indexes))))
   0
   (:tasks vm-state)))

(defn temperature
  [vm-state]
  (+ (count (:messages vm-state))
     (pending-task-count vm-state)))

(defn cold?
  [vm-state]
  (zero? (temperature vm-state)))

(defn apply-instruction
  [vm-state instruction]
  (case (:network-vm/op instruction)
    :declare-cell
    (update vm-state :net nb/ensure-cell (:id instruction))

    :declare-prop
    (let [{:keys [id inputs outputs activate]} instruction
          n0 (reduce nb/ensure-cell (:net vm-state)
                     (distinct (concat inputs outputs)))
          [_ n1] ((prop/construct-propagator id activate inputs outputs) n0)]
      (assoc vm-state :net n1))

    :bind-name
    (update vm-state :net
            net/update-net-dict-entry
            instr/name-bindings-key
            #(assoc-in (or % {}) [(:scope instruction) (:name instruction)]
                       (:id instruction)))

    :tell
    (update vm-state :messages conj
            ((requiring-resolve 'propagators.message/message)
             (:id instruction)
             (:value instruction)))

    :schedule
    (update vm-state :tasks add-task
            (:cause instruction)
            (:prop-ids instruction)
            (:index instruction))

    (throw (ex-info "unknown network VM instruction"
                    {:instruction instruction}))))

(defn apply-instructions
  [vm-state instructions]
  (reduce apply-instruction vm-state instructions))

(defn- schedule-cell-tasks
  [vm-state msg tasks]
  (update vm-state :tasks
          add-task
          [:tell (:id msg)]
          (task-ids tasks)
          (hash (:value msg))))

(defn- advance-message
  [vm-state]
  (let [msg (first (:messages vm-state))
        remaining (subvec (vec (:messages vm-state)) 1)
        [tasks n*] (core/eval-cell* (net/net-dict-or-empty (:net vm-state))
                                    msg
                                    (:net vm-state))]
    (-> vm-state
        (assoc :net n*
               :messages remaining)
        (schedule-cell-tasks msg tasks))))

(defn- advance-task
  [vm-state task]
  (let [n* (core/run-tasks (tq/enqueue-all tq/empty-queue (:prop-ids task))
                           (:net vm-state))]
    (-> vm-state
        (assoc :net n*)
        (update-in [:cursor (:cause task)] (fnil conj #{}) (:index task)))))

(defn advance
  [vm-state]
  (cond
    (seq (:messages vm-state))
    (advance-message vm-state)

    (pending-task vm-state)
    (advance-task vm-state (pending-task vm-state))

    :else
    vm-state))

(defn run-until-cold
  ([vm-state] (run-until-cold vm-state 1024))
  ([vm-state max-steps]
   (loop [remaining max-steps
          current vm-state]
     (cond
       (cold? current) current
       (zero? remaining) (throw (ex-info "network VM exceeded step budget"
                                         {:max-steps max-steps
                                          :temperature (temperature current)}))
       :else (recur (dec remaining) (advance current))))))
