(ns propagators.debugger
  "Opt-in dispatch debugging hooks for propagator procedure application.")

(defn terminal-sink
  [event]
  (println (pr-str event)))

(defonce ^:private state
  (atom {:enabled? false
         :sink terminal-sink}))

(defn enable!
  []
  (swap! state assoc :enabled? true)
  nil)

(defn disable!
  []
  (swap! state assoc :enabled? false)
  nil)

(defn enabled?
  []
  (:enabled? @state))

(defn set-sink!
  [sink]
  (swap! state assoc :sink sink)
  nil)

(defn reset-sink!
  []
  (swap! state assoc :sink terminal-sink)
  nil)

(defmacro with-debugger
  [& body]
  `(let [old-state# @state]
     (try
       (enable!)
       ~@body
       (finally
         (reset! state old-state#)))))

(defn report!
  [event data]
  (when (enabled?)
    ((:sink @state) (assoc data :event event)))
  nil)
