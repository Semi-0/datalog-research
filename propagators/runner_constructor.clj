(ns propagators.runner-constructor
  "Minimal continuation-based constructor for one network evaluation step.")

(defn network-iterator-constructor
  "Construct one CPS network iteration.

  `task-policy` owns task selection and admission. Evaluators select either
  `:success` or `:fail`; this iterator selects `:done`, `:fail`, or `:continue`.
  Execution, history, observation, and validation belong in wrappers/drivers."
  [{:keys [tasks-empty? take-task add-tasks]}
   evaluate-propagator
   evaluate-patches]
  (fn [{:keys [network tasks] :as state}
       {:keys [done fail continue]}]
    (if (tasks-empty? tasks)
      (done network)
      (let [[propagator-id remaining-tasks] (take-task network tasks)]
        (evaluate-propagator
         propagator-id
         network
         {:success
          (fn [patches propagated-network]
            (evaluate-patches
             patches
             propagated-network
             {:success
              (fn [new-tasks patched-network]
                (continue
                 (assoc state
                        :network patched-network
                        :tasks (add-tasks remaining-tasks new-tasks))))
              :fail fail}))
          :fail fail})))))

(defn direct-propagator->cps
  "Adapt `(fn [propagator-id network] [patches network])` to the CPS contract."
  [evaluate-propagator]
  (fn [propagator-id network {:keys [success fail]}]
    (let [result (try
                   {:value (evaluate-propagator propagator-id network)}
                   (catch Throwable error
                     {:error error}))]
      (if (contains? result :error)
        (fail (:error result))
        (let [[patches propagated-network] (:value result)]
          (success patches propagated-network))))))

(defn direct-patches->cps
  "Adapt `(fn [patches network] [tasks network])` to the CPS contract."
  [evaluate-patches]
  (fn [patches network {:keys [success fail]}]
    (let [result (try
                   {:value (evaluate-patches patches network)}
                   (catch Throwable error
                     {:error error}))]
      (if (contains? result :error)
        (fail (:error result))
        (let [[new-tasks patched-network] (:value result)]
          (success new-tasks patched-network))))))
