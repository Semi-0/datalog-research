(ns examples.lain.visualization-combinators.ttms-compiled-benchmark
  "Compiled arithmetic/if chains. Timings include propagation, not rendering."
  (:require [clojure.string :as str]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.runner :as runner]))

(defn- source [length kind]
  (str "(def-cells source condition)\n(def result (let-cell ["
       (str/join " " (map #(str "v" %) (range 1 (inc length)))) "]\n"
       (str/join "\n"
         (for [i (range 1 (inc length))]
           (let [previous (if (= i 1) "source" (str "v" (dec i)))
                 expression (case kind
                              :arithmetic (str "(-> (+ " previous " 1) v" i ")")
                              :branch (str "(-> (if condition " previous " 0) v" i ")"))]
             expression)))
       "\nv" length "))"))

(defn- observation [id epoch status base]
  (let [premise {:source id :timestamp epoch :premises-status status}]
    (if (= status :active)
      (collection/content {:base base :support #{premise} :premise-state #{premise}})
      (collection/content {:premise-state #{premise}}))))

(defn- advance [network updates]
  (let [counter (atom 0)
        start (System/nanoTime)
        result (binding [runner/*advance-transform*
                         (fn [f]
                           (fn [s ks]
                             (if ((:tasks-empty? runner/fifo-task-policy) (:tasks s))
                               (f s ks)
                               (do (swap! counter inc) (f s ks)))))]
                 (let [[tasks patched] (core/eval-cells updates network)]
                   (runner/completed-network (runner/run-network tasks patched))))]
    {:network result :milliseconds (/ (- (System/nanoTime) start) 1e6)
     :activations @counter
     :node-growth (- (count (net/net-env result)) (count (net/net-env network)))}))

(defn measure [length kind ttms?]
  (let [start (System/nanoTime)
        options (if ttms? {:extensions [primitives/session-extension]} {})
        s @(loader/load-session-from-source (source length kind) options)
        setup-ms (/ (- (System/nanoTime) start) 1e6)
        binding-id #(env/resolve-binding-id (:program/net s) (:program/env s) %)
        input (binding-id 'source) condition (binding-id 'condition)
        output (binding-id 'result)
        stages (if ttms?
                 [[:initial 1 :active 10] [:update 2 :active 20]
                  [:withdrawal 3 :retracted value/nothing] [:recovery 4 :active 7]]
                 [[:initial 1 :active 10]])
        final (reduce
                (fn [{:keys [network rows]} [label epoch status base]]
                  (let [update (if ttms? (observation input epoch status base) base)
                        condition-value (if ttms? (observation condition 1 :active true) true)
                        result (advance network [(message/message input update)
                                                 (message/message condition condition-value)])
                        strongest (net/network-cell-strongest (:network result) output)
                        actual (if ttms? (datum/layer-value strongest :base) strongest)
                        expected (cond
                                   (= status :retracted) value/nothing
                                   (= kind :arithmetic) (+ base length)
                                   :else base)]
                    (assert (= expected actual) {:phase label :expected expected :actual actual})
                    {:network (:network result)
                     :rows (conj rows (assoc (dissoc result :network) :phase label))}))
                {:network (:program/net s) :rows []} stages)]
    {:length length :kind kind :mode (if ttms? :ttms :plain)
     :setup-ms setup-ms :phases (:rows final)
     :output-observations (count (:support/observations
                                  (net/network-cell-content (:network final) output)))}))

(defn -main [& sizes]
  (doseq [kind [:arithmetic :branch] ttms? [false true]] (measure 3 kind ttms?))
  (doseq [length (if (seq sizes) (map parse-long sizes) [10 100 1000])
          kind [:arithmetic :branch] ttms? [false true]]
    (prn (measure length kind ttms?)))
  (shutdown-agents))
