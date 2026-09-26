(ns propagators.experimental.visualization.collections
  "Higher-order collection topology, declared by ordinary propagators."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.closure-value :as closure]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.datastructures.compound-object :as obj]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur.flat :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.semantic-trace :as trace]))

(defn stable-id [& parts] (gur/stable-node-id (into [::collection] parts)))

(defn- header [out kind source graph-source]
  (message out (data/fragment {:collection/type kind :collection/id out
                               :collection/sources #{source}
                               :collection/graph-source graph-source})))

(defn- source-reader [target ref]
  (fn [_ _ network]
    (let [v (data/read-source network ref)
          base (data/payload (data/read-strongest network ref))]
      (if (value/unusable? base)
        []
        [(message target (data/supported [::source target] base #{ref} [v]))]))))

(defn- reader-patch [network target ref]
  (patch/declare-propagator
   (stable-id :reader target (data/watch-ids network ref)) ::source
   (data/watch-ids network ref) [target] (source-reader target ref)))

(declare discover)

(defn- list-step [source out ref index]
  (fn [_ _ network]
    (let [v (data/payload (data/read-source network ref))]
      (cond
        (or (value/unusable? v) (nil? v) (= '() v) (= basis/list-empty-marker v)) []
        (not (map? v))
        (throw (ex-info "Collection is neither a graph nor a compound list" {:value v}))
        :else
        (let [head (update ref :source/slots conj :car)
              tail (update ref :source/slots conj :cdr)
              identity head
              item (stable-id out identity :value)]
          [(reader-patch network item head)
           (patch/declare-propagator
            (stable-id out :tail tail (data/watch-ids network tail)) ::list-step
            (data/watch-ids network tail) [out] (list-step source out tail (inc index)))
           (message out (data/fragment
                         {[:element identity] {:identity identity :order index
                                               :value item :sources #{head} :gates []}}))])))))

(defn- discover [source out network]
  (let [ref (data/reference source)
        v (data/payload (net/network-cell-strongest network source))]
    (cond
      (data/collection? v) [(message out v)]
      (trace/semantic-trace-graph? v)
      (into [(header out :graph ref ref)]
            (mapcat
             (fn [identity]
               (let [[path id] identity
                     source-ref (data/reference path id [])
                     item (stable-id out identity :value)]
                 [(patch/declare-cell item)
                  (message item (data/supported [::node item] source-ref #{source-ref} []))
                  (message out (data/fragment
                                {[:element identity] {:identity identity :order (pr-str identity) :value item
                                                      :sources #{source-ref} :gates []}}))]))
             (sort-by pr-str (keys (:nodes v)))))
      :else
      [(header out :list ref nil)
       (patch/declare-propagator (stable-id out :root) ::list-step
                                 [source] [out] (list-step source out ref 0))])))

(defn- gate-reader [entry argument]
  (prop/concrete-propagator
   (fn [_ _ network]
     (if (= :included (data/decision network entry))
       (let [content (net/network-cell-content network (:value entry))
             gate-contents (mapv #(net/network-cell-content network %) (:gates entry))]
         [(message argument (data/supported [::argument argument]
                                            (data/payload (net/network-cell-strongest network (:value entry)))
                                            (:sources entry) (into [content] gate-contents)))])
       []))))

(defn- callback-arguments [declaration context argument result]
  (if (closure/implicit-return-output? (closure/closure-output declaration))
    [context argument]
    [context argument result]))

(defn- entry-patches [mode callback declaration context output entry]
  (let [identity (:identity entry)
        argument (stable-id output identity :argument)
        result (stable-id output identity :result)
        mapped (stable-id output identity :mapped)
        descriptor (case mode
                     :map (assoc entry :value mapped)
                     :filter (update entry :gates conj result))]
    [(patch/declare-propagator (stable-id output identity :gate) ::argument
                               (data/entry-inputs entry) [argument]
                               (gate-reader entry argument))
     (gur/apply-closure-effect callback (callback-arguments declaration context argument result) result)
     (patch/declare-propagator
      (stable-id output identity :retain-sources) ::result [argument result] [mapped]
      (prop/concrete-propagator
       (fn [_ _ network]
         [(message mapped
                   (data/supported [::mapped mapped]
                                   (data/payload (net/network-cell-strongest network result))
                                   (:sources entry)
                                   (mapv #(net/network-cell-content network %) [argument result])))])))
     (message output (data/fragment {[:element identity] descriptor}))]))

(defn- transform [mode callback source context output]
  (prop/concrete-propagator
   (fn [_ _ network]
     (let [collection (data/payload (net/network-cell-strongest network source))
           declaration (application/callable-declaration
                        (data/payload (net/network-cell-strongest network callback)))]
       (when-not (and (closure/closure-info? declaration)
                      (= 1 (count (closure/closure-inputs declaration)))
                      (= 1 (count (closure/closure-output declaration))))
         (throw (ex-info "Collection callback must be a one-input, one-output network definition" {})))
       (if (data/collection? collection)
         (into [(header output (data/field collection :collection/type)
                         (data/reference source) (data/field collection :collection/graph-source))]
               (mapcat #(entry-patches mode callback declaration context output %) (data/entries collection)))
         (throw (ex-info "Collection adapter produced an invalid declaration" {})))))))

(defn collection-operator [mode]
  (operator/propagator-operator
   {:name [::operator mode]
    :activate
    (fn [network inputs outputs context]
      (when-not (= 2 (count inputs))
        (throw (ex-info "map/filter expects callback and collection" {:inputs inputs})))
      (let [[callback source] inputs
            output (first outputs)
            adapted (stable-id output :source)]
        [(patch/declare-propagator (stable-id output :discover) ::discover [source] [adapted]
                                  (prop/concrete-propagator
                                   (fn [_ _ current] (discover source adapted current))))
         (patch/declare-propagator (stable-id output :transform) mode [callback adapted] [output]
                                  (transform mode callback adapted context output))]))}))

(defn transpose-operator []
  (operator/propagator-operator
   {:name ::transpose
    :activate
    (fn [network [source kind :as inputs] [out] _]
      (when-not (= 2 (count inputs))
        (throw (ex-info "transpose expects collection and target kind" {:inputs inputs})))
      (if (prop/concrete-inputs? network inputs)
        (let [target (data/payload (net/network-cell-strongest network kind))
              collection (data/payload (net/network-cell-strongest network source))]
          (when-not (= :list target)
            (throw (ex-info "transpose currently supports :list" {:target target})))
          (when-not (data/collection? collection)
            (throw (ex-info "transpose expects a collection declaration" {:value collection})))
          [(message out
                    (data/fragment
                     (into {:collection/type :list :collection/id out
                            :collection/sources #{(data/reference source)}
                            :collection/graph-source (data/field collection :collection/graph-source)}
                           (map (fn [entry] [[:element (:identity entry)] entry])
                                (data/entries collection)))))])
        []))}))
