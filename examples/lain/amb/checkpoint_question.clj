(ns examples.lain.amb.checkpoint-question
  "One fixed supported constraint network; restoration policy lives elsewhere."
  (:require [examples.lain.amb.builder :as builder]
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

(defn- calculation [network arguments output descriptor]
  (let [{:keys [name f]} descriptor
        primitive (prop/primitive-propagator
                   name (fn [& values]
                          (if (apply value/any-unusable-values? values)
                            value/nothing
                            (apply f values))))]
    (supported/install (supported/from-primitive primitive) network arguments output)))

(defn build
  "The same three-variable predicate, tuple, require, and switch as AMB's builder.
  No choices, learn, or backtrack are installed. Both DFS modes reuse this Net."
  ([n] (build n builder/pythagorean?))
  ([n predicate]
   (if (and (integer? n) (pos? n))
     (let [[a b c valid failure tuple output] (repeatedly 7 ids/new-node-id)
           sources [a b c]
           initial (nb/install-cells (concat sources [valid failure tuple output]))
           [constraint-net constraint-tasks _]
           (calculation initial sources valid {:name ::constraint :f predicate})
           [tuple-net tuple-tasks _]
           (calculation constraint-net sources tuple {:name ::tuple :f vector})
           [require-id require-net] ((amb/p:require valid failure) tuple-net)
           switch (get (into {} (extension/extension-bindings ttms/session-extension)) 'switch)
           [network switch-tasks _] ((operator/operator-install switch) require-net [tuple valid] output)]
       {:network network
        :tasks (tq/into-queue (concat constraint-tasks tuple-tasks [require-id] switch-tasks))
        :sources sources :domain (vec (range 1 (inc n)))
        :cells {:valid valid :failure failure :tuple tuple :output output}})
     (throw (ex-info "Checkpoint question requires a positive finite domain" {:domain n})))))
