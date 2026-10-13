(ns examples.lain.amb.brute-force
  "Compile and observe the recursive Lain enumeration; no host tuple generator."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [propagators.cells.bool4 :as bool4]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.datastructures.compound-object :as object]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.runner :as runner]))

(def source (slurp (io/resource "examples/lain/amb/brute_force.lain")))

(defn source-for [n]
  (if (and (integer? n) (pos? n))
    (str/replace source "(list 1 2 3 4 5)" (str "(list " (str/join " " (range 1 (inc n))) ")"))
    (throw (ex-info "Positive finite bound required" {:bound n}))))

(defn list-cells [network root]
  (loop [id root result [] seen #{}]
    (if (= :compiler-2/list-empty (net/network-cell-strongest network id))
      result
      (let [head (object/existing-slot-cell-id network :car id)
            tail (object/existing-slot-cell-id network :cdr id)]
        (if (and head tail (not (contains? seen id)))
          (recur tail (conj result head) (conj seen id))
          (throw (ex-info "Brute force result is pending or cyclic" {:cell id})))))))

(defn answers [network root]
  (vec (sort (map (fn [row] (mapv #(net/network-cell-strongest network %)
                                  (list-cells network row)))
                  (list-cells network root)))))

(defn execute [compiled limits]
  (let [started (System/nanoTime) steps (atom 0) parent runner/*advance-transform*
        guard (fn [advance]
                (let [advance (parent advance)]
                  (fn [state continuations]
                    (if (or (tq/queue-empty? (:tasks state))
                            (and (< (swap! steps inc) (:activations limits))
                                 (< (- (System/nanoTime) started) (* 1e6 (:ms limits)))))
                      (advance state continuations)
                      (throw (ex-info "Lain brute force experiment bound reached"
                                      {:experiment/bounded true :steps @steps
                                       :nodes (count (net/net-env (:network state)))}))))))
        result (binding [runner/*advance-transform* guard]
                 (runner/run-network (:props compiled) (:net compiled)))]
    (case (:status result)
      :completed {:status :completed :answers (answers (:network result) (:cell compiled))
                  :network (:network result) :steps @steps}
      :failed (if (:experiment/bounded (ex-data (:error result)))
                {:status :bounded :diagnostic (ex-data (:error result))}
                (throw (:error result)))
      (throw (ex-info "Unknown brute force runtime status" {:status (:status result)})))))

(defn compile-source [source]
  (let [session (extension/install-session-extension
                 (state/empty-state)
                 (extension/extension-bundle
                  {:id ::boolean :bindings [['and (basis/primitive-operator bool4/and)]] :effects []}) {})]
    (compiler/compile-source source (:program/env session)
                             {:net (:program/net session) :environment-props (:program/props session)})))

(defn compile-bound [n] (compile-source (source-for n)))
