(ns propagators.cells.cell-protocol
  "Network-local generic merge/strongest protocol."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.intensity :as intensity]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.generic-procedure :as generic]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(def merge-generic-key :cell/merge-generic)
(def strongest-generic-key :cell/strongest-generic)

(def no-match ::no-match)
(def empty-content ::empty-content)

(def ^:private handled-key ::handled?)
(def ^:private handled-value-key ::value)
(def ^:private result-tag ::result)

(defn protocol-result
  "Wrap a protocol handler result so unusable values can pass through
  select-one generic reduction."
  [v]
  [result-tag v])

(defn- unwrap-protocol-result
  [v]
  (if (and (vector? v)
           (= result-tag (first v))
           (= 2 (count v)))
    (second v)
    v))

(defn- protocol-result? [v]
  (and (vector? v)
       (= result-tag (first v))
       (= 2 (count v))))

(defn- handled
  [v]
  {handled-key true
   handled-value-key v})

(defn handled?
  [v]
  (and (map? v) (true? (get v handled-key))))

(defn handled-value
  [v]
  (get v handled-value-key))

(defn empty-content?
  [v]
  (= empty-content v))

(defn- generic-value
  [network dict-key]
  (let [generic-id (net/network-dict-entry network dict-key)]
    (if (and generic-id (contains? (net/net-env network) generic-id))
      (:value (generic/materialize-generic-procedure network generic-id))
      value/nothing)))

(defn- apply-protocol-generic
  [network dict-key arg-values]
  (let [generic (generic-value network dict-key)]
    (if (value/unusable? generic)
      value/nothing
      (let [result ((requiring-resolve
                     'propagators.generic-procedure/apply-generic-value)
                    generic
                    arg-values)
            unwrapped (unwrap-protocol-result result)]
        (cond
          (protocol-result? result) (handled unwrapped)
          (= no-match unwrapped) value/nothing
          (value/nothing? unwrapped) value/nothing
          :else (handled unwrapped))))))

(defn- encode-merge-content
  [content]
  (if (value/nothing? content)
    empty-content
    content))

(defn try-cell-merge
  "Try the network-local merge generic. Returns handled result map or nothing."
  [network content update]
  (apply-protocol-generic network
                          merge-generic-key
                          [(encode-merge-content content) update]))

(defn try-cell-strongest
  "Try the network-local strongest generic. Returns handled result map or nothing."
  [network content]
  (apply-protocol-generic network strongest-generic-key [content]))

(defn install-cell-protocol
  "Install network-local merge and strongest generic procedure cells."
  []
  (fn [n]
    (let [merge-id (ids/new-node-id)
          strongest-id (ids/new-node-id)
          default-id (ids/new-node-id)
          n0 (-> n
                 (nb/install-cell merge-id)
                 (nb/install-cell strongest-id)
                 (nb/install-cell default-id value/nothing value/nothing))
          [merge-props n1] ((generic/make-generic-propagator merge-id default-id) n0)
          [strongest-props n2] ((generic/make-generic-propagator strongest-id default-id) n1)]
      [(into (vec merge-props) strongest-props)
       (-> n2
           (net/assoc-net-dict-entry merge-generic-key merge-id)
           (net/assoc-net-dict-entry strongest-generic-key strongest-id))])))

(defn- protocol-generic-id
  [n dict-key]
  (or (net/network-dict-entry n dict-key)
      (throw (ex-info "cell protocol generic is not installed"
                      {:dict-key dict-key
                       :dict (net/net-dict-or-empty n)}))))

(defn define-merge-handler
  [applicability handler]
  (fn [n]
    ((generic/define-generic-propagator-handler
      (protocol-generic-id n merge-generic-key)
      applicability
      handler)
     n)))

(defn define-strongest-handler
  [applicability handler]
  (fn [n]
    ((generic/define-generic-propagator-handler
      (protocol-generic-id n strongest-generic-key)
      applicability
      handler)
     n)))

(defn install-intensity-protocol
  "Install intensity partial-information methods into the network-local
  merge/strongest generics."
  []
  (fn [n]
    (let [[merge-props n1]
          ((define-merge-handler
             (generic/match-cells-pred
              #(or (empty-content? %)
                   (intensity/intensity-content? %))
             intensity/intensity-value?)
             (generic/handler-closure
              (fn [content update]
                (protocol-result
                 (intensity/merge-content
                  (if (empty-content? content) value/nothing content)
                  update)))))
           n)
          [strongest-props n2]
          ((define-strongest-handler
             (generic/match-cells-pred intensity/intensity-content?)
             (generic/handler-closure
              (fn [content]
                (protocol-result (intensity/strongest-value content)))))
           n1)]
      [(into (vec merge-props) strongest-props) n2])))

(defn install-scope-source-protocol
  "Install scope-source partial-information methods into the network-local
  merge/strongest generics."
  []
  (fn [n]
    (let [[merge-props n1]
          ((define-merge-handler
             (generic/match-cells-pred
              #(or (empty-content? %)
                   (scope-source/scope-content? %))
              scope-source/scope-value?)
             (generic/handler-closure
              (fn [content update]
                (protocol-result
                 (scope-source/merge-content
                  (if (empty-content? content) value/nothing content)
                  update)))))
           n)
          [strongest-props n2]
          ((define-strongest-handler
             (generic/match-cells-pred scope-source/scope-content?)
             (generic/handler-closure
              (fn [content]
                (protocol-result (scope-source/strongest-value content)))))
           n1)]
      [(into (vec merge-props) strongest-props) n2])))

(defn install-dependency-protocol
  "Install dependency partial-information methods into the network-local
  merge/strongest generics."
  []
  (fn [n]
    (let [[merge-props n1]
          ((define-merge-handler
             (generic/match-cells-pred
              #(or (empty-content? %)
                   (dependency/dependency-content? %))
              dependency/dependency-value?)
             (generic/handler-closure
              (fn [content update]
                (protocol-result
                 (dependency/merge-content
                  (if (empty-content? content) value/nothing content)
                  update)))))
           n)
          [strongest-props n2]
          ((define-strongest-handler
             (generic/match-cells-pred dependency/dependency-content?)
             (generic/handler-closure
              (fn [content]
                (protocol-result (dependency/strongest-value content)))))
           n1)]
      [(into (vec merge-props) strongest-props) n2])))
