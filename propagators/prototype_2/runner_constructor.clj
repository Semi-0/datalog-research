(ns propagators.prototype-2.runner-constructor
  "Minimal continuation-based constructor for one prototype-2 network step.

  Mirrors `propagators.runner-constructor`, adapted to Network.queue (no patch
  layer). Evaluators are plugged in; GC / observation belong in wrappers via
  `:done` (or a done-transform around the iterator).")

(defn network-iterator-constructor
  "Construct one CPS network iteration over `Network.queue`.

  `task-policy` —
    :queue-empty?  (fn [queue] → bool)
    :take-task     (fn [network] → [prop-id network-with-remaining-queue])

  `evaluate-propagator` — (fn [network prop-id] → network)

  Continuations: `:done` final-network | `:fail` error | `:continue` state."
  [{:keys [queue-empty? take-task]} evaluate-propagator]
  (fn [{:keys [network] :as state}
       {:keys [done fail continue]}]
    (let [q (:queue network)]
      (if (queue-empty? q)
        (done network)
        (try
          (let [[prop-id network] (take-task network)
                network (evaluate-propagator network prop-id)]
            (continue (assoc state :network network)))
          (catch Throwable error
            (fail error)))))))

(defn with-done-transform
  "Wrap an iterator so `:done` sees `(f final-network)` before the caller."
  [iterator f]
  (fn [state handlers]
    (iterator state
              (assoc handlers
                     :done (fn [network]
                             ((:done handlers) (f network)))))))
