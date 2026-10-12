(ns examples.lain.amb.builder
  "Headless builder parity: same four search propagators, opaque vector output."
  (:require [propagators.compiler-2.model.operator-value :as operator]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.debug :as debug]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.primitives :as amb]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.stdlib.supported-procedure :as supported]))

(defn pythagorean? [a b c]
  (if (value/any-unusable-values? a b c)
    value/nothing
    (and (<= a b c) (= (+ (* a a) (* b b)) (* c c)))))

(defn- calculation [name f network call]
  (let [[arguments output] call
        primitive (prop/primitive-propagator
                   name (fn [& values]
                          (if (apply value/any-unusable-values? values)
                            value/nothing
                            (apply f values))))
        [installed tasks _] (supported/install (supported/from-primitive primitive)
                                               network arguments output)]
    {:network installed :tasks tasks}))

(defn build [n predicate]
  (let [[domain repairs choices valid failure knowledge tuple output] (repeatedly 8 ids/new-node-id)
        candidates (vec (repeatedly 3 ids/new-node-id))
        controls (vec (repeatedly 3 ids/new-node-id))
        initial (nb/seed-cell (nb/install-cells
                              (concat [domain repairs choices valid failure knowledge tuple output]
                                      candidates controls)) domain (vec (range 1 (inc n))))
        choice-state (reduce (fn [{:keys [network tasks]} [source control]]
                               (let [[id installed] ((amb/p:amb domain repairs control source) network)]
                                 {:network installed :tasks (conj tasks id)}))
                             {:network initial :tasks []} (map vector candidates controls))
        computation (reduce (fn [{:keys [network tasks]} [name f call]]
                              (let [result (calculation name f network call)]
                                (update result :tasks #(into tasks %)))) choice-state
                            [[::choices vector [controls choices]]
                             [::constraint predicate [candidates valid]]
                             [::tuple vector [candidates tuple]]])
        search (reduce (fn [{:keys [network tasks]} installer]
                         (let [[id installed] (installer network)]
                           {:network installed :tasks (conj tasks id)})) computation
                       [(amb/p:require valid failure)
                        (amb/p:learn failure choices knowledge)
                        (amb/p:backtrack choices knowledge repairs)])
        switch (get (into {} (extension/extension-bindings ttms/session-extension)) 'switch)
        [installed tasks _] ((operator/operator-install switch)
                             (:network search) [tuple valid] output)]
    {:network installed :tasks (into (:tasks search) tasks)
     :cells {:candidates candidates :controls controls :valid valid
             :failure failure :knowledge knowledge :repairs repairs :output output}}))

(defn run [built]
  (let [profile (debug/activation-profile)
        started (System/nanoTime)
        result (debug/call-with-activation-profile
                profile #(runner/run-network (:tasks built) (:network built)))
        network (:network result)
        report (debug/activation-profile-report profile)]
    (assoc built :result result :profile report
           :solution (observation/cell-base network (get-in built [:cells :output]))
           :run-ms (/ (- (System/nanoTime) started) 1e6))))

(defn -main [& arguments]
  (let [n (if (seq arguments) (Long/parseLong (first arguments)) 5)
        {:keys [result solution run-ms profile cells]} (run (build n pythagorean?))]
    (prn {:runtime-status (:status result) :solution solution :run-ms run-ms
          :activations (get-in profile [:totals :calls])
          :knowledge (observation/cell-base (:network result) (:knowledge cells))
          :repair (observation/cell-base (:network result) (:repairs cells))})
    (if (= :failed (:status result)) (throw (:error result)) nil)))
