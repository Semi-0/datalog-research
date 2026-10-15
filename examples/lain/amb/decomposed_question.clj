(ns examples.lain.amb.decomposed-question
  "Builder control matching the arithmetic operations in solver.lain."
  (:require [propagators.cells.bool4 :as bool4]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.amb.primitives :as amb]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.supported-procedure :as supported]))

(defn operations [cells]
  (let [{:keys [a b c a2 b2 c2 sum equal ab bc ordered raw-valid valid]} cells]
    [[::ab <= [a b] ab] [::bc <= [b c] bc]
     [::ordered bool4/and [ab bc] ordered]
     [::a2 * [a a] a2] [::b2 * [b b] b2]
     [::sum + [a2 b2] sum] [::c2 * [c c] c2]
     [::equal = [sum c2] equal]
     [::constraint bool4/and [ordered equal] raw-valid]
     [::complete (fn [& values] (last values)) [a b c raw-valid] valid]]))

(defn- install-operation [state operation]
  (let [[name f arguments output] operation
        primitive (prop/primitive-propagator
                   name (fn [& values]
                          (if (apply value/any-unusable-values? values)
                            value/nothing (apply f values))))
        [network tasks _] (supported/install (supported/from-primitive primitive)
                                             (:network state) arguments output)]
    {:network network :tasks (into (:tasks state) tasks)}))

(defn build [n]
  (if (and (integer? n) (pos? n))
    (let [keys [:a :b :c :ab :bc :ordered :a2 :b2 :sum :c2 :equal :raw-valid
                :valid :failure :tuple :output]
          cells (zipmap keys (repeatedly ids/new-node-id))
          sources (mapv cells [:a :b :c])
          computed (reduce install-operation
                           {:network (nb/install-cells (vals cells)) :tasks []}
                           (operations cells))
          tuple-base (supported/from-primitive (prop/primitive-propagator ::tuple vector))
          [tuple-net tuple-tasks _] (supported/install tuple-base (:network computed) sources (:tuple cells))
          [require-id required] ((amb/p:require (:valid cells) (:failure cells)) tuple-net)
          switch (get (into {} (extension/extension-bindings ttms/session-extension)) 'switch)
          [network switch-tasks _] ((operator/operator-install switch) required
                                   [(:tuple cells) (:valid cells)] (:output cells))]
      {:network network :tasks (tq/into-queue (concat (:tasks computed) tuple-tasks [require-id] switch-tasks))
       :sources sources :domain (vec (range 1 (inc n))) :cells cells})
    (throw (ex-info "Positive finite bound required" {:bound n}))))
