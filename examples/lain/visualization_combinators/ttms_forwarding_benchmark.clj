(ns examples.lain.visualization-combinators.ttms-forwarding-benchmark
  "Plain/TTMS identity transport only, not compiled arithmetic or XR throughput."
  (:require [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]
            [propagators.stdlib.prop :as standard]))

(defn- chain [length]
  (let [cells (vec (repeatedly (inc length) ids/new-node-id))
        n (reduce (fn [n [a b]] (second ((standard/id a b) n)))
                  (nb/install-cells cells) (partition 2 1 cells))]
    {:network n :source (first cells) :output (peek cells)}))

(defn- phase [network source output update expected instrument?]
  (let [activations (atom 0)
        started (System/nanoTime)
        transform (if instrument?
                    (fn [advance]
                      (fn [state ks]
                        (if ((:tasks-empty? runner/fifo-task-policy) (:tasks state))
                          (advance state ks)
                          (do (swap! activations inc) (advance state ks)))))
                    identity)
        result (binding [runner/*advance-transform* transform]
                 (let [[tasks n] (core/eval-cells [(message/message source update)] network)]
                   (runner/completed-network (runner/run-network tasks n))))
        elapsed (/ (- (System/nanoTime) started) 1e6)
        strongest (net/network-cell-strongest result output)
        actual (if (collection/content? update) (datum/layer-value strongest :base) strongest)]
    (assert (= expected actual) {:expected expected :actual actual})
    {:network result :milliseconds elapsed
     :activations (if instrument? @activations nil)
     :node-growth (- (count (net/net-env result)) (count (net/net-env network)))}))

(defn measure [length ttms? instrument?]
  (let [started (System/nanoTime)
        {:keys [network source output]} (chain length)
        setup-ms (/ (- (System/nanoTime) started) 1e6)
        stages (if ttms?
                 [[:initial 1 :active 10] [:update 2 :active 20]
                  [:withdrawal 3 :retracted value/nothing] [:recovery 4 :active 7]]
                 [[:initial 1 :active 10]])
        result (reduce
                (fn [{:keys [network rows]} [label epoch status base]]
                  (let [update (if ttms?
                                 (collection/content {:base base :support #{{:source source :timestamp epoch :premises-status status}}})
                                 base)
                        result (phase network source output update base instrument?)]
                    {:network (:network result) :rows (conj rows (assoc (dissoc result :network) :phase label))}))
                {:network network :rows []} stages)]
    {:length length :mode (if ttms? :ttms :plain) :instrumented? instrument?
     :setup-ms setup-ms :phases (:rows result)
     :output-observations (if ttms?
                            (count (:support/observations (net/network-cell-content (:network result) output)))
                            nil)}))

(defn -main [& sizes]
  (doseq [ttms? [false true]] (measure 10 ttms? false))
  (doseq [length (if (seq sizes) (map parse-long sizes) [10 100 1000])
          ttms? [false true]
          instrument? [false true]]
    (prn (measure length ttms? instrument?)))
  (shutdown-agents))
