(ns experiments.amb-search.constraint-fixtures
  "Larger pure questions, with the existing AMB/learn/backtrack control loop."
  (:require [examples.lain.amb.decomposed-question :as decomposed]
            [propagators.cells.bool4 :as bool4]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.amb.primitives :as amb]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.helpers.task-queue :as queue]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.supported-procedure :as supported]))

(defn install-calculation [built [name f inputs output]]
  (let [primitive (prop/primitive-propagator
                   name (fn [& arguments]
                          (if (apply value/any-unusable-values? arguments)
                            value/nothing (apply f arguments))))
        [network tasks _] (supported/install (supported/from-primitive primitive)
                                             (:network built) inputs output)]
    (-> built (assoc :network network)
        (update :tasks #(queue/merge-queues (queue/into-queue %) (queue/into-queue tasks))))))

(defn- install-control [built installer]
  (let [[id network] (installer (:network built))]
    (-> built (assoc :network network) (update :tasks queue/enqueue id))))

(defn attach-search [question]
  (let [[domain repairs choices knowledge] (repeatedly 4 ids/new-node-id)
        sources (:sources question)
        controls (mapv (fn [_] (ids/new-node-id)) sources)
        installed (reduce nb/ensure-cell (:network question)
                          (concat [domain repairs choices knowledge] controls))
        initial (assoc question :network (nb/seed-cell installed domain (:domain question))
                                :tasks (queue/into-queue (:tasks question)))
        chosen (reduce install-control initial
                       (map #(amb/p:amb domain repairs %1 %2) controls sources))
        recorded (install-calculation chosen [::choices vector controls choices])
        learned (install-control recorded (amb/p:learn (get-in question [:cells :failure]) choices knowledge))
        repaired (install-control learned (amb/p:backtrack choices knowledge repairs))]
    (update repaired :cells merge {:candidates sources :controls controls
                                  :knowledge knowledge :repairs repairs})))

(defn decomposed-pythagorean [n]
  (attach-search (decomposed/build n)))

(defn- pair-constraints [built [i j]]
  (let [sources (:sources built)
        [column distance diagonal valid] (repeatedly 4 ids/new-node-id)
        prepared (update built :network #(reduce nb/ensure-cell % [column distance diagonal valid]))
        distance-between (- j i)
        operations [[::different-column not= [(nth sources i) (nth sources j)] column]
                    [::row-distance (fn [a b] (abs (- a b))) [(nth sources i) (nth sources j)] distance]
                    [::different-diagonal #(not= % distance-between) [distance] diagonal]
                    [::pair-valid bool4/and [column diagonal] valid]]]
    (-> (reduce install-calculation prepared operations)
        (update :constraints conj valid))))

(defn- combine-constraint [built next-input]
  (let [output (ids/new-node-id)]
    (-> built
        (update :network nb/ensure-cell output)
        (install-calculation [::conjunction bool4/and [(:required built) next-input] output])
        (assoc :required output))))

(defn queens [n]
  (when-not (and (integer? n) (<= 2 n 6))
    (throw (ex-info "Queens fixture supports 2 through 6 choices" {:size n})))
  (let [sources (vec (repeatedly n ids/new-node-id))
        [valid failure tuple output] (repeatedly 4 ids/new-node-id)
        initial {:network (nb/install-cells (concat sources [valid failure tuple output]))
                 :tasks queue/empty-queue :sources sources :domain (vec (range 1 (inc n)))
                 :cells {:valid valid :failure failure :tuple tuple :output output} :constraints []}
        paired (reduce pair-constraints initial (for [i (range n) j (range (inc i) n)] [i j]))
        combined (reduce combine-constraint (assoc paired :required (first (:constraints paired)))
                         (rest (:constraints paired)))
        complete (install-calculation combined [::complete (fn [& values] (last values))
                                               (conj sources (:required combined)) valid])
        tupled (install-calculation complete [::tuple vector sources tuple])
        required (install-control tupled (amb/p:require valid failure))
        switch (get (into {} (extension/extension-bindings ttms/session-extension)) 'switch)
        [network tasks _] ((operator/operator-install switch) (:network required) [tuple valid] output)]
    (attach-search (assoc required :network network :tasks (queue/merge-queues (:tasks required)
                                                                            (queue/into-queue tasks))))))

(defn valid-queens? [tuple]
  (every? (fn [[i j]]
            (let [a (nth tuple i) b (nth tuple j)]
              (and (not= a b) (not= (abs (- a b)) (- j i)))))
          (for [i (range (count tuple)) j (range (inc i) (count tuple))] [i j])))

(defn queens-oracle [n]
  (filterv valid-queens?
           (reduce (fn [tuples _] (for [tuple tuples v (range 1 (inc n))] (conj tuple v)))
                   [[]] (range n))))
