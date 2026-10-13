(ns examples.lain.amb.solver-lain
  "One unchanged Lain program with a swappable solve binding."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [examples.lain.amb.solver-operator :as solver]
            [examples.lain.amb.checkpoint-search :as checkpoint]
            [examples.lain.amb.solver-strategy :as strategy]
            [propagators.cells.value :as value]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.publication :as publication]
            [propagators.core :as core]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]))

(def source (slurp (io/resource "examples/lain/amb/solver.lain")))

(defn source-for [n]
  (if (and (integer? n) (pos? n))
    (str/replace source "(list 1 2 3 4 5)" (str "(list " (str/join " " (range 1 (inc n))) ")"))
    (throw (ex-info "Positive finite bound required" {:bound n}))))

(defn compile-source [source]
  (let [solver-cell (ids/new-node-id)
        session (-> (state/empty-state)
                    (update :program/net nb/ensure-cell solver-cell)
                    (extension/install-session-extension ttms/session-extension {})
                    (extension/install-session-extension (solver/session-extension {:workers 4}) {})
                    (extension/install-session-extension
                     (extension/extension-bundle
                      {:id ::selection :bindings [['solver (env/cell-binding solver-cell)]] :effects []}) {}))]
    (assoc (compiler/compile-source source (:program/env session)
                                   {:net (:program/net session) :environment-props (:program/props session)})
           :solver-cell solver-cell :source source)))

(defn run-state [compiled state]
  (let [result (runner/run-network (:tasks state) (:network state))]
    (case (:status result)
      :completed
      (let [answer (observation/cell-base (:network result) (:cell compiled))]
        (cond
          (vector? answer) {:status :completed :answers answer :network (:network result)}
          (value/nothing? answer) {:status :pending :network (:network result)}
          :else (throw (ex-info "Compiled solver did not publish an answer vector" {}))))
      :failed (throw (:error result))
      (throw (ex-info "Unknown compiled solver runtime status" {:status (:status result)})))))

(defn prepare [compiled]
  (checkpoint/settle {:network (:net compiled) :tasks (:props compiled)}))

(defn select-and-run [compiled network choice]
  (let [emitted (publication/snapshot-message network (:solver-cell compiled) (strategy/select choice))
        [fresh updated] (core/eval-cell (:solver-cell compiled) (update emitted :value collection/content) network)]
    (run-state compiled {:network updated :tasks (tq/merge-queues (tq/into-queue (:props compiled)) fresh)})))
