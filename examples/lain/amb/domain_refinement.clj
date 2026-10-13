(ns examples.lain.amb.domain-refinement
  "Opt-in finite-domain intersection and extensional relation contractors.
  No production cell semantics or runner constructors are changed."
  (:require [clojure.set :as set]
            [examples.lain.amb.builder :as builder]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.combinator :as combinator]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.runner-constructor :as constructor]))

(defn domain [candidates] {:experiment/domain (set candidates)})
(defn domain? [v] (and (map? v) (= #{:experiment/domain} (set (keys v)))
                      (set? (:experiment/domain v))))
(defn candidates [v]
  (if (domain? v) (:experiment/domain v)
      (throw (ex-info "Expected an experimental finite domain" {:value v}))))

(defn intersect [old update]
  (cond
    (value/nothing? update) old
    (value/nothing? old) update
    :else (domain (set/intersection (candidates old) (candidates update)))))

(defn- domain-message? [_ patch _network]
  (and (message/message? patch) (domain? (message/message-value patch))))

(defn- apply-domain [_ patch network]
  (let [id (message/message-id patch) entry (net/network-env-lookup network id)]
    (if (cell/cell? entry)
      (let [old (cell/cell-content entry)
            result (intersect old (message/message-value patch))
            updated (net/assoc-net-cell network id (cell/cell (cell/cell-name entry) result result))
            tasks (if (= old result) tq/empty-queue
                      (tq/enqueue-all tq/empty-queue
                                      (graph/node-output-ids (graph/get-node (net/net-graph updated) id))))]
        [tasks updated])
      (throw (ex-info "Domain message requires a declared cell" {:cell id})))))

(def apply-patch (combinator/branch domain-message? apply-domain runner/apply-patch))

(defn- evaluate-patches [evaluated network {:keys [success fail]}]
  (try
    (if (runner/evaluated-activation? evaluated)
      (let [{:keys [effects messages]} (patch/normalize-activation-return (:activation-result evaluated))
            [tasks updated] (reduce (fn [[tasks current] patch]
                                      (let [[fresh next-net] (apply-patch (:emitter evaluated) patch current)]
                                        [(tq/merge-queues tasks fresh) next-net]))
                                    [tq/empty-queue network] (concat effects messages))]
        (success tasks updated))
      (fail (ex-info "Expected evaluated domain activation" {})))
    (catch Throwable error (fail error))))

(defn settle [state]
  (let [advance (runner/*advance-transform*
                 (constructor/network-iterator-constructor runner/fifo-task-policy
                                                           runner/evaluate-propagator evaluate-patches))]
    (letfn [(bounce [current]
              (fn [] (advance current
                              {:done #(assoc current :network % :tasks tq/empty-queue)
                               :fail #(throw (ex-info "Domain propagation failed" {} %))
                               :continue bounce})))]
      (assoc state :network (:network (trampoline bounce state)) :tasks tq/empty-queue))))

(defn tell [{:keys [network tasks] :as state} source v]
  (let [[fresh updated] (apply-patch source (message/message source (domain [v])) network)]
    (settle (assoc state :network updated :tasks (tq/merge-queues tasks fresh)))))

(defn tuples [domains]
  (reduce (fn [rows values] (vec (for [row rows v (sort values)] (conj row v))))
          [[]] domains))

(defn projections [predicate domains]
  (let [initial (vec (repeat (count domains) #{}))]
    (reduce (fn [result tuple]
              (let [accepted (apply predicate tuple)]
                (cond
                  (true? accepted) (mapv conj result tuple)
                  (false? accepted) result
                  :else (throw (ex-info "Relation must decide a concrete tuple" {:tuple tuple :result accepted})))))
            initial (tuples domains))))

(defn p:relation [predicate ports]
  (prop/construct-propagator
   ::relation
   (prop/concrete-propagator
    (fn [_ _ network]
      (let [domains (mapv #(candidates (net/network-cell-strongest network %)) ports)
            narrowed (projections predicate domains)]
        (mapv #(message/message %1 (domain %2)) ports narrowed))))
   ports ports))

(defn inspect [question state]
  (let [network (:network state)
        domains (mapv #(candidates (net/network-cell-strongest network %)) (:sources question))]
    (cond
      (some empty? domains) {:status :failed}
      (every? #(= 1 (count %)) domains)
      (let [tuple (mapv first domains)]
        (if (true? (apply (:predicate question) tuple))
          {:status :satisfied :solution tuple}
          (throw (ex-info "Singleton domains violate the declared relation" {:tuple tuple}))))
      :else {:status :pending})))

(defn choices [question state]
  (let [network (:network state)]
    (if-let [source (first (filter #(< 1 (count (candidates (net/network-cell-strongest network %))))
                                  (:sources question)))]
      {:source source :values (vec (sort (candidates (net/network-cell-strongest network source))))}
      (throw (ex-info "No unresolved finite domain" {})))))

(defn build
  ([n] (build n builder/pythagorean?))
  ([n predicate]
   (if (and (integer? n) (pos? n))
     (let [sources (vec (repeatedly 3 ids/new-node-id))
           initial (reduce #(nb/seed-cell %1 %2 (domain (range 1 (inc n))))
                           (nb/install-cells sources) sources)
           [id network] ((p:relation predicate sources) initial)]
       {:network network :tasks (tq/into-queue [id]) :sources sources
        :domain (vec (range 1 (inc n))) :predicate predicate :kind :domain})
     (throw (ex-info "Positive finite domain required" {:domain n})))))
