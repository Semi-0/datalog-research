(ns propagators-layered-bench
  "Comparable layered-chain fixtures. No benchmark state is stored in Net."
  (:require [propagators.cells.value :as value]
            [propagators.cell-evaluator :as cell-evaluator]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.named-network :as named]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.helpers.task-queue :as tq]
            [propagators.layered :as layered]
            [propagators.layered.procedure :as procedure]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.stdlib.provenance-arithmetic :as arithmetic]
            [propagators.stdlib.support :as support]))

(defn- install-support [network procedure]
  (let [id (ids/new-node-id)
        prepared (nb/install-cell network id support/procedure support/procedure)]
    (:net (layered/install-layered-procedure! prepared procedure :support id))))

(defn- publish-supported [network id]
  (let [application (net/network-env-lookup network id)]
    (net/assoc-net-prop
     network id
     (assoc application :activate
            (prop/compose-activation
             (prop/prop-f application)
             (message/lift-message collection/content))))))

(defn- source-value [fixture base timestamp status]
  (case (:mode fixture)
    :base base
    :nested base
    :provenance (obj/compound-object {:base base :provenance #{:source}})
    :support (obj/compound-object
              {:base base :support #{{:source (:source fixture)
                                     :timestamp timestamp
                                     :premises-status status}}})
    (throw (ex-info "Unknown benchmark mode" {:fixture fixture}))))

(defn- nested-procedure [network]
  (let [{inner-net :net inner :operator}
        (arithmetic/+ net/empty-net {:provenance? false})
        closure {:net inner-net
                 :f (fn [closure-net inputs outputs current]
                      (let [[left right] inputs
                            [out] outputs
                            result (ids/new-node-id)
                            joined (nb/install-cell (named/join current closure-net) result)
                            [_ applied] ((inner left right result) joined)]
                        (second ((layered/p:base out result) applied))))}
        ;; The fixture adapts its base closure to the implementation's contract;
        ;; neither branch is a production compatibility path.
        closure (if (ns-resolve 'propagators.layered 'materialize-layered-cell-value)
                  closure
                  (procedure/base closure))
        [operator closure-id] (repeatedly 2 ids/new-node-id)
        prepared (nb/install-cell network closure-id closure closure)
        installed (layered/install-layered-procedure! prepared operator :base closure-id)]
    {:net (:net installed) :proc operator}))

(defn build [mode length]
  (let [{network :net procedure :proc}
        (if (= mode :nested)
          (nested-procedure net/empty-net)
          (arithmetic/+ net/empty-net {:provenance? (= mode :provenance)}))
        prepared (if (= mode :support)
                   (install-support network procedure)
                   network)
        source (ids/new-node-id)
        constant (ids/new-node-id)
        nodes (vec (repeatedly length ids/new-node-id))
        initial (-> (reduce nb/install-cell prepared (conj nodes source))
                    (nb/install-cell constant 1 1))
        [built tasks]
        (reduce (fn [[current tasks] [input output]]
                  (let [[task installed]
                        ((layered/p:apply-layered procedure [input constant] output)
                         current)
                        published (if (= mode :support)
                                    (publish-supported installed task)
                                    installed)]
                    [published (conj tasks task)]))
                [initial []]
                (map vector (cons source nodes) nodes))]
    {:mode mode :length length :network built :tasks tasks
     :source source :output (last nodes)}))

(defn- run-seeded [fixture timestamp base]
  (let [datum (source-value fixture base timestamp :active)
        seeded (if (= :support (:mode fixture))
                 (second (cell-evaluator/evaluate
                          (message/message (:source fixture) (collection/content datum))
                          (:network fixture)))
                 (nb/seed-cell (:network fixture) (:source fixture) datum))]
    (nb/run-propagators seeded (:tasks fixture))))

(defn- assert-result [fixture network expected]
  (let [projected (net/network-cell-value network (:output fixture))]
    (assert (= expected (datum/layer-value projected :base)))
    network))

(defn- inject [fixture network update]
  (let [[base timestamp status] update
        datum (source-value fixture base timestamp status)
        [tasks changed] (cell-evaluator/evaluate
                         (message/message (:source fixture) (collection/content datum))
                         network)]
    (nb/run-propagators changed tasks)))

(defn- reactive-run [fixture initial]
  (let [updated (assert-result fixture (inject fixture initial [20 2 :active])
                               (+ 20 (:length fixture)))
        withdrawn (inject fixture updated [value/nothing 3 :retracted])
        projection (net/network-cell-value withdrawn (:output fixture))
        restored (assert-result fixture (inject fixture withdrawn [30 4 :active])
                                (+ 30 (:length fixture)))]
    (assert (value/unusable? projection))
    (assert (some #(= :retracted (:premises-status %)) (datum/support-of projection)))
    (assert (= #{4} (set (map :timestamp
                              (datum/support-of (net/network-cell-value restored (:output fixture)))))))
    restored))

(declare thunk)

(defn profile [fixture scenario]
  (let [run (thunk fixture scenario)
        counts (atom {:activations 0 :introduced-nodes 0 :max-frame-nodes 0})
        transform
        (fn [advance]
          (fn [state handlers]
            (if (tq/queue-empty? (:tasks state))
              (advance state handlers)
              (do
                (swap! counts update :activations inc)
                (swap! counts update :max-frame-nodes max
                       (count (net/net-env (:network state))))
                (advance state
                         (update handlers :continue
                                 (fn [continue]
                                   (fn [next-state]
                                     (swap! counts update :introduced-nodes +
                                            (- (count (net/net-env (:network next-state)))
                                               (count (net/net-env (:network state)))))
                                     (continue next-state)))))))))]
    (binding [runner/*advance-transform* transform]
      (let [after (run)]
        (assoc @counts :settled-outer-nodes (count (net/net-env after)))))))

(defn- thunk [fixture scenario]
  (case scenario
    :initial #(assert-result fixture (run-seeded fixture 1 10)
                             (+ 10 (:length fixture)))
    :rerun (let [settled (run-seeded fixture 1 10)]
             #(assert-result fixture (nb/run-propagators settled (:tasks fixture))
                             (+ 10 (:length fixture))))
    :reactive (let [settled (run-seeded fixture 1 10)]
                #(reactive-run fixture settled))
    (throw (ex-info "Unknown benchmark scenario" {:scenario scenario}))))

(defn- percentile [samples fraction]
  (let [ordered (vec (sort samples))
        index (dec (long (Math/ceil (* fraction (count ordered)))))]
    (nth ordered (max 0 index))))

(defn measure [fixture scenario options]
  (let [run (thunk fixture scenario)]
    (dotimes [_ (:warmup options)] (run))
    (let [samples (mapv (fn [_]
                          (let [start (System/nanoTime)]
                            (run)
                            (/ (- (System/nanoTime) start) 1e6)))
                        (range (:iterations options)))]
      {:mode (:mode fixture) :length (:length fixture) :scenario scenario
       :samples-ms samples :median-ms (percentile samples 0.5)
       :p95-ms (percentile samples 0.95)
       :outer-nodes (count (net/net-env (:network fixture)))})))

(defn -main [& arguments]
  (let [[label mode length iterations] arguments
        options {:warmup 3 :iterations (Long/parseLong (or iterations "20"))}
        started (System/nanoTime)
        reactive? (= mode "reactive")
        fixture (build (if reactive? :support (keyword mode)) (Long/parseLong length))
        construction-ms (/ (- (System/nanoTime) started) 1e6)]
    (prn {:label label :construction-ms construction-ms
          :mode mode :length length})
    (doseq [scenario (if reactive? [:reactive] [:initial :rerun])]
      (prn (assoc (measure fixture scenario options) :label label
                  :profile (profile fixture scenario))))))
