(ns propagators.lexical-compound
  "Lexical IO compound boundary.

  This is the non-diff compound path: parent values enter a child network through
  declared IO inbox records, child evaluation runs through the evaluator
  continuation, and child outbox records become ordinary parent messages."
  (:require [propagators.cells.value :as value]
            [propagators.io :as io]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.reality :as reality]
            [propagators.runtime :as runtime]))

(defn- input-route
  [[io-id parent-id child-id]]
  {:io-id io-id :parent-id parent-id :child-id child-id})

(defn- output-route
  [[io-id parent-id]]
  [io-id parent-id])

(defn- inject-input
  [child-net parent-net {:keys [io-id parent-id child-id]}]
  (let [v (net/network-cell-strongest parent-net parent-id)]
    (if (value/unusable? v)
      child-net
      (reality/inject-input child-net io-id child-id v))))

(defn- translate-outbox
  [output-map records]
  (keep (fn [{:keys [id message] :as record}]
          (cond
            (contains? output-map id)
            (message/message (get output-map id)
                             (message/message-value message))

            (= :escaped id)
            message

            :else
            nil))
        records))

(defn activate
  [child-net-id input-routes output-routes]
  (let [inputs (mapv input-route input-routes)
        outputs (into {} (map output-route output-routes))]
    (fn [_input-ids _output-ids parent-net]
      (let [child-value (net/network-cell-strongest parent-net child-net-id)]
        (if-not (net/network? child-value)
          [(message/message child-net-id value/contradiction)]
          (let [scope child-net-id
                child0 (reduce #(inject-input %1 parent-net %2)
                               child-value
                               inputs)
                child1 (runtime/continue child0)
                [records child2] (io/drain-outbox child1)]
            (into (vec (translate-outbox outputs records))
                  [(message/message child-net-id
                                    (io/stored-lexical-env child2))
                   (io/io-delivery
                    :replace-lexical-envs
                    (assoc (io/lexical-envs parent-net)
                           scope
                           (io/stored-lexical-env child2)))])))))))

(defn p:lexical-compound
  "Run a child network through declared IO boundary routes.

  `input-routes` are `[io-id parent-cell-id child-cell-id]`.
  `output-routes` are `[io-id parent-cell-id]`.
  The child network cell is both an input and output because evaluation updates
  the child network value."
  [child-net-id input-routes output-routes]
  (let [parent-inputs (mapv second input-routes)
        parent-outputs (mapv second output-routes)]
    (prop/construct-propagator
     (activate child-net-id input-routes output-routes)
     (into [child-net-id] parent-inputs)
     (into [child-net-id] parent-outputs))))
