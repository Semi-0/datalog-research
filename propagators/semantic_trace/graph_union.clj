(ns propagators.semantic-trace.graph-union
  "Explicit graph composition over independently published contributions."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as object]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.gur :as gur]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.graph-facts :as facts]
            [propagators.stdlib.premise-state :as state]))

(defn inbox-id [output] (gur/stable-node-id [::inbox output]))
(defn contribution-id [output producer] (gur/stable-node-id [::contribution output producer]))

(defn- revision [previous source]
  (inc (reduce max 0 (keep #(when (= source (:source %)) (:timestamp %))
                           (state/states-of previous)))))

(defn graph-of
  "Read the source projection of a composed graph, leaving plain inputs unchanged."
  [input]
  (let [base (if (datum/layer-present? input :base) (datum/layer-value input :base) input)]
    (if (capture/fragment? base)
      (facts/source-projection (capture/root-graph base))
      base)))

(defn- exclusive-merge [left right]
  (cond
    (value/nothing? left) right
    (= left right) left
    :else value/contradiction))

(defn- current-contributions [inputs]
  (let [states (apply support/join (map state/states-of inputs))]
    (mapv #(collection/strongest-value
            (collection/content {:base (datum/layer-value % :base)
                                 :support (datum/support-of %)
                                 :premise-state states})
            exclusive-merge)
          inputs)))

(defn- compose-current [inputs]
  (let [current (current-contributions inputs)
        bases (map #(datum/layer-value % :base) current)
        conflicts (filter value/contradiction? bases)
        graphs (keep #(when-not (value/unusable? %) (::graph %)) bases)]
    {:graph (if (seq conflicts)
              (apply trace/graph-union conflicts)
              (apply trace/graph-union {} graphs))
     :support (apply support/combine (map datum/support-of current))
     :states (apply support/join (map state/states-of current))}))

(defn- captured-graph [graph output premise dependencies states]
  (if (value/unusable? graph)
    graph
    (let [observation [::observation output (:timestamp premise)]
          premises (conj dependencies premise)
          scope (-> (facts/from-graph observation :root graph premises)
                    (facts/update-premise-state (conj states premise)))]
      (capture/fragment
       {:observation observation :scopes {:root scope} :support premises
        :provenance [{:capture/source output :capture/revision (:timestamp premise)}]}))))

(defn- publish-result [network output graph dependencies states captured?]
  (let [previous (net/network-cell-strongest network output)
        without-output #(set (remove (fn [p] (= output (:source p))) %))]
    (if (and (= graph (graph-of previous))
             (= dependencies (without-output (datum/support-of previous)))
             (= states (without-output (state/states-of previous))))
      []
      (let [premise {:source output :timestamp (revision previous output)
                     :premises-status :active}
            base (if captured?
                   (captured-graph graph output premise dependencies states)
                   graph)]
        [(message/message output
                          (collection/content {:base base
                                               :support (conj dependencies premise)
                                               :premise-state (conj states premise)}))]))))

(defn- union-activation [inbox output]
  (fn [_inputs _outputs network]
    (let [slots (net/network-cell-strongest network inbox)
          inputs (mapv #(net/network-cell-strongest
                         network (:cell (object/accessor-source-slot-value slots %)))
                       (sort-by pr-str (object/accessor-slot-keys slots)))
          {:keys [graph support states]} (compose-current inputs)]
      (publish-result network output graph support states false))))

(defn publish
  "Publish a graph contribution owned by producer. Retraction uses `nothing`.
  Contributions retain independent support; graph union is an explicit task."
  [network output producer graph dependencies states]
  (let [inbox (inbox-id output)
        source (contribution-id output producer)
        previous (if (contains? (net/net-env network) source)
                   (net/network-cell-strongest network source)
                   value/nothing)
        base (if (value/unusable? graph) graph {::graph graph})
        premise {:source source :timestamp (revision previous source)
                 :premises-status :active}
        without-source #(set (remove (fn [p] (= source (:source p))) %))]
    (if (and (= base (datum/layer-value previous :base))
             (= dependencies (without-source (datum/support-of previous)))
             (= states (without-source (state/states-of previous))))
      []
      [(patch/declare-cell output)
     (patch/declare-cell inbox)
     (patch/declare-cell source)
     (patch/declare-propagator
      (gur/stable-node-id [::union output]) ::union [inbox] [output]
      (union-activation inbox output))
     (patch/declare-propagator
      (gur/stable-node-id [::notify output producer]) ::notify [source] [inbox]
      (fn [_inputs _outputs current]
        [(message/message inbox
                          (object/as-accessor-network
                           {producer {:cell source
                                      :revision (state/states-of
                                                 (net/network-cell-strongest current source))}}))]))
     (message/message source
                      (collection/content {:base base :support (conj dependencies premise)
                                           :premise-state (conj states premise)}))])))

(defn publish-derived
  "Publish an immutable, observation-qualified graph with its input support and states."
  [network output graph inputs]
  (publish-result network output graph
                  (apply support/combine (map datum/support-of inputs))
                  (apply support/join (map state/states-of inputs)) true))
