(ns benchmark.event-projection)
(defn project-events [initial events]
  (when-not (map? initial) (throw (ex-info "Invalid state" {:type :invalid-state})))
  (reduce (fn [state [index event]]
            (let [invalid (fn [] (throw (ex-info "Invalid event" {:type :invalid-event :index index :event event})))]
              (when-not (vector? event) (invalid))
              (case (first event)
                :put (if (= 3 (count event)) (assoc state (nth event 1) (nth event 2)) (invalid))
                :delete (if (= 2 (count event)) (dissoc state (nth event 1)) (invalid))
                :increment (if (= 3 (count event))
                             (let [[_ key delta] event value (get state key 0)]
                               (if (and (integer? value) (integer? delta)) (assoc state key (+ value delta)) (invalid)))
                             (invalid))
                (invalid))))
          initial (map-indexed vector events)))
