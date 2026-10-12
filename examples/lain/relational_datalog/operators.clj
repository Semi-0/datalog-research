(ns examples.lain.relational-datalog.operators
  "Generic set-collection wiring. Rule predicates and projections are Lain networks."
  (:require [examples.lain.relational-datalog.runtime :as relation]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.closure-value :as closure]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.gur.flat :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-patch :as patch]))

(defn- stable-id [parts] (gur/stable-node-id (into [::collection] parts)))

(defn product [left right]
  (relation/relation
   (set (for [a (relation/facts left) b (relation/facts right)] [a b]))))

(defn- publish [mode tuple result output]
  (fn [_inputs _outputs network]
    (let [v (net/network-cell-strongest network result)]
      (cond
        (value/contradiction? v)
        (throw (ex-info "Collection callback produced contradiction" {:mode mode :tuple tuple}))
        (value/nothing? v) []
        (= mode :map) [(message output (relation/relation #{v}))]
        (true? v) [(message output (relation/relation #{tuple}))]
        (false? v) []
        :else (throw (ex-info "Filter callback must return a Boolean" {:value v}))))))

(defn entry-patches
  "Declare one stable callback application and publisher for a tuple."
  [{:keys [mode callback context output declaration]} tuple]
  (let [key [mode callback context output tuple]
        argument (stable-id [key :argument])
        result (stable-id [key :result])
        args (if (closure/implicit-return-output? (closure/closure-output declaration))
               [context argument]
               [context argument result])]
    [(patch/declare-cell argument)
     (message argument tuple)
     (gur/apply-closure-effect callback args result)
     (patch/declare-propagator (stable-id [key :publish]) ::publish [result] [output]
                               (publish mode tuple result output))]))

(defn collection-call
  "Validate a collection invocation; nil means its declaration/data is pending."
  [mode network {:keys [inputs outputs context-id]}]
  (let [[callback source] inputs
        output (first outputs)
        declaration (application/callable-declaration (net/network-cell-strongest network callback))
        collection (net/network-cell-strongest network source)]
    (cond
      (not= 2 (count inputs)) (throw (ex-info "map/filter requires callback and relation" {:inputs inputs}))
      (or (value/unusable? collection) (value/unusable? declaration)) nil
      (not (and (closure/closure-info? declaration)
                (= 1 (count (closure/closure-inputs declaration)))
                (= 1 (count (closure/closure-output declaration)))))
      (throw (ex-info "Callback must be a one-input, one-output Lain network" {:declaration declaration}))
      :else
      {:mode mode :callback callback :context context-id :output output
       :declaration declaration :facts (relation/facts collection)})))

(defn- collection-messages [mode network call]
  (let [descriptor (collection-call mode network call)]
    (if (nil? descriptor)
      []
      (into [(message (:output descriptor) relation/empty-relation)]
            (mapcat #(entry-patches descriptor %)
                    (sort-by pr-str (:facts descriptor)))))))

(defn collection-operator [mode]
  (if (contains? #{:map :filter} mode)
    (operator/propagator-operator
     {:name [::collection mode]
      :messages (fn [network call] (collection-messages mode network call))})
    (throw (ex-info "Unknown collection mode" {:mode mode}))))

(defn bindings []
  (into (basis/default-bindings)
         {'tuple (basis/primitive-operator (fn [a b] [a b]))
          'at (basis/primitive-operator nth)
          'relation (basis/primitive-operator (fn [tuple] (relation/relation #{tuple})))
          'empty-relation (basis/primitive-operator (fn [] relation/empty-relation))
          'product (basis/primitive-operator product)
          'map (collection-operator :map)
          'filter (collection-operator :filter)}))
