;; Inspect settled application inputs without traversing/materializing whole objects.
(require '[propagators.compiler-2-functional-network-gur-test :as g]
         '[propagators.compiler-2-functional-network-test :as fixture]
         '[propagators.compiler-2.runtime.application :as application]
         '[propagators.compiler-2.model.env :as env]
         '[propagators.cells.cell :as cell]
         '[propagators.cells.value :as value]
         '[propagators.datastructures.layered-value :as datum]
         '[propagators.datastructures.compound-object :as obj]
         '[propagators.experimental.visualization.data :as data]
         '[propagators.network :as net]
         '[propagators.propagator :as prop]
         '[propagators.stdlib.conditional])

(defn candidate-summary [v]
  (let [base (if (datum/layer-present? v :base) (datum/layer-value v :base) v)]
    {:base (cond (value/contradiction? base) :contradiction
                 (map? base) (if (:gur.flat/body base) :callable :map)
                 (net/net? base) :compound
                 (or (nil? base) (keyword? base) (boolean? base) (number? base)) base
                 :else (str (class base)))
     :contradiction (value/contradiction? v)
     :support-count (count (datum/support-of v))}))

(defn run-observed [compiled]
  (let [events (atom [])
        publish-var (ns-resolve 'propagators.stdlib.conditional 'publish)
        publish @publish-var
        network (with-redefs-fn
                  {publish-var (fn [result]
                                 (let [published (publish result)]
                                   (swap! events conj {:before (candidate-summary result)
                                                       :after (candidate-summary published)})
                                   published))}
                  #(fixture/run compiled))]
    [network @events]))

(defn brief [network id]
  (when id
    (let [v (data/payload (net/network-cell-strongest network id))]
      (cond
        (net/net? v) :compound
        (map? v) (if (:gur.flat/body v) :callable :map)
        (vector? v) (let [base (datum/layer-value v :base)]
                      {:wrapper true :contradiction (value/contradiction? v)
                       :support-count (count (datum/support-of v))
                       :base (cond (net/net? base) :compound
                                   (map? base) (if (:gur.flat/body base) :callable :map)
                                   (or (keyword? base) (boolean? base) (number? base)) base
                                   :else (str (class base)))})
        (or (keyword? v) (number? v) (boolean? v)) v
        :else (str (class v))))))

(doseq [[label definition]
        [[:bare "(define m (network [f xs] (if (value? xs) (cons (f (car xs)) (m f (cdr xs))) (list))))"]
         [:guarded "(define m (network [f xs] (let-cell [answer] (when xs (define answer (if (value? xs) (cons (f (car xs)) (m f (cdr xs))) (list)))) answer)))"]]]
  (let [compiled (g/compile-program (str definition g/mappers) "(m increment (list 1 2 3))")
        [network events] (run-observed compiled)]
    (prn {:case label :kind :selector-publications :events events})
    (prn {:case label :kind :recursive-inputs
          :inputs (vec (for [{:keys [operator-id argument-ids result-id]} (application/application-topologies network)
                            :let [operator (data/payload (net/network-cell-strongest network operator-id))]
                            :when (= '[f xs] (:compiler-2/input-description operator))]
                        (let [xs (second argument-ids)
                              head (obj/existing-slot-cell-id network :car xs)
                              tail (obj/existing-slot-cell-id network :cdr xs)]
                          {:head (brief network head) :tail (brief network tail)
                           :input (brief network xs) :output (brief network result-id)})))})
    (prn {:case label :kind :selectors
          :nodes (vec (for [[id entry] (net/net-env network)
                            :when (and (prop/prop? entry)
                                       (= :propagators.stdlib.conditional/conditional (prop/prop-name entry)))
                            :let [node (get (net/net-graph network) id)]]
                        {:inputs (mapv #(brief network %) (:inputs node))
                         :outputs (mapv #(brief network %) (:outputs node))}))})
    (prn {:case label :kind :named-xs
          :cells (vec (for [[id entry] (net/net-env network)
                           :when (and (cell/cell? entry) (= 'xs (cell/cell-name entry)))]
                       {:value (brief network id)
                        :head (brief network (obj/existing-slot-cell-id network :car id))
                        :incoming (vec (for [p (:inputs (get (net/net-graph network) id))
                                            :let [entry (net/network-env-lookup network p)]
                                            :when (prop/prop? entry)]
                                        {:name (prop/prop-name entry)
                                         :sources (mapv #(brief network %) (:inputs (get (net/net-graph network) p)))}))}))})))
(shutdown-agents)
