(ns propagators.cells.cell-protocol
  "Network-local generic merge/strongest protocol."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.behavior :as behavior]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.event :as event]
            [propagators.datastructures.intensity :as intensity]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms :as tms]
            [propagators.generic-procedure :as generic]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-cache :as cache]))

(def merge-generic-key :cell/merge-generic)
(def strongest-generic-key :cell/strongest-generic)
(def direct-standard-protocols-key :cell/direct-standard-protocols?)

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
      (:value
       (cache/cached
        [:cell-protocol/generic-value dict-key generic-id
         (net/network-cell-strongest network generic-id)
         (net/net-dict-or-empty network)]
        #(do
           (cache/stat! :cell-protocol/materialize-generic)
           (generic/materialize-generic-procedure network generic-id))))
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

(defn- event-bearing?
  [v]
  (or (event/event-content? v)
      (event/event-fact? v)
      (event/event-projection? v)))

(defn- direct-event-merge
  [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (event-bearing? content))
             (event-bearing? update))
    (cache/stat! :cell-protocol/direct-event-merge)
    (handled
     (event/merge-content
      (if (or (empty-content? content)
              (value/nothing? content))
        value/nothing
        content)
      update))))

(defn- direct-event-strongest
  [content]
  (when (event/event-content? content)
    (cache/stat! :cell-protocol/direct-event-strongest)
    (handled (event/strongest-value content))))

(defn- direct-behavior-merge
  [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (behavior/behavior-content? content))
             (behavior/behavior-value? update))
    (cache/stat! :cell-protocol/direct-behavior-merge)
    (handled
     (behavior/merge-content
      (if (or (empty-content? content)
              (value/nothing? content))
        value/nothing
        content)
      update))))

(defn- direct-behavior-strongest
  [content]
  (when (behavior/behavior-content? content)
    (cache/stat! :cell-protocol/direct-behavior-strongest)
    (handled (behavior/strongest-value content))))

(defn- direct-tms-merge
  [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (tms/distributed-value? content))
             (tms/distributed-value? update))
    (cache/stat! :cell-protocol/direct-tms-merge)
    (handled
     (tms/merge-distributed-content
      (if (or (empty-content? content)
              (value/nothing? content))
        value/nothing
        content)
      update))))

(defn- direct-tms-strongest
  [content]
  (when (tms/distributed-value? content)
    (cache/stat! :cell-protocol/direct-tms-strongest)
    (handled (tms/strongest-distributed-value content))))

(defn- direct-dependency-merge
  [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (dependency/dependency-content? content))
             (dependency/dependency-value? update))
    (cache/stat! :cell-protocol/direct-dependency-merge)
    (handled
     (dependency/merge-content
      (if (or (empty-content? content)
              (value/nothing? content))
        value/nothing
        content)
      update))))

(defn- direct-dependency-strongest
  [content]
  (when (dependency/dependency-content? content)
    (cache/stat! :cell-protocol/direct-dependency-strongest)
    (handled (dependency/strongest-value content))))

(defn- direct-scope-source-merge
  [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (scope-source/scope-content? content))
             (scope-source/scope-value? update))
    (cache/stat! :cell-protocol/direct-scope-source-merge)
    (handled
     (scope-source/merge-content
      (if (or (empty-content? content)
              (value/nothing? content))
        value/nothing
        content)
      update))))

(defn- direct-scope-source-strongest
  [content]
  (when (scope-source/scope-content? content)
    (cache/stat! :cell-protocol/direct-scope-source-strongest)
    (handled (scope-source/strongest-value content))))

(defn- direct-intensity-merge
  [content update]
  (when (and (or (empty-content? content)
                 (value/nothing? content)
                 (intensity/intensity-content? content))
             (intensity/intensity-value? update))
    (cache/stat! :cell-protocol/direct-intensity-merge)
    (handled
     (intensity/merge-content
      (if (or (empty-content? content)
              (value/nothing? content))
        value/nothing
        content)
      update))))

(defn- direct-intensity-strongest
  [content]
  (when (intensity/intensity-content? content)
    (cache/stat! :cell-protocol/direct-intensity-strongest)
    (handled (intensity/strongest-value content))))

(defn- direct-standard-merge
  [content update]
  (or (direct-event-merge content update)
      (direct-behavior-merge content update)
      (direct-tms-merge content update)
      (direct-dependency-merge content update)
      (direct-scope-source-merge content update)
      (direct-intensity-merge content update)))

(defn- direct-standard-strongest
  [content]
  (or (direct-event-strongest content)
      (direct-behavior-strongest content)
      (direct-tms-strongest content)
      (direct-dependency-strongest content)
      (direct-scope-source-strongest content)
      (direct-intensity-strongest content)))

(defn- direct-standard-protocols?
  [network]
  (true? (net/network-dict-entry network direct-standard-protocols-key)))

(defn try-cell-merge
  "Try the network-local merge generic. Returns handled result map or nothing."
  [network content update]
  (or (direct-standard-merge content update)
      (when-not (direct-standard-protocols? network)
        (apply-protocol-generic network
                                merge-generic-key
                                [(encode-merge-content content) update]))))

(defn try-cell-strongest
  "Try the network-local strongest generic. Returns handled result map or nothing."
  [network content]
  (or (direct-standard-strongest content)
      (when-not (direct-standard-protocols? network)
        (apply-protocol-generic network strongest-generic-key [content]))))

(defn prefer-direct-standard-protocols
  "Mark a network as using only the built-in direct cell protocol handlers.

  Custom network-local generic handlers still work in networks that do not set
  this marker."
  [n]
  (net/assoc-net-dict-entry n direct-standard-protocols-key true))

(defn prefer-generic-standard-protocols
  "Remove the direct standard protocol marker.

  This is mainly useful for benchmarks and experiments that compare the generic
  dispatcher path against the direct standard protocol path."
  [n]
  (net/net-with-dict n (dissoc (net/net-dict-or-empty n)
                               direct-standard-protocols-key)))

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

(defn install-tms-distributed-protocol
  "Install distributed TMS annotation methods into the network-local
  merge/strongest generics."
  []
  (fn [n]
    (let [[merge-props n1]
          ((define-merge-handler
             (generic/match-cells-pred
              #(or (empty-content? %)
                   (tms/distributed-value? %))
              tms/distributed-value?)
             (generic/handler-closure
              (fn [content update]
                (protocol-result
                 (tms/merge-distributed-content
                  (if (empty-content? content) value/nothing content)
                  update)))))
           n)
          [strongest-props n2]
          ((define-strongest-handler
             (generic/match-cells-pred tms/distributed-value?)
             (generic/handler-closure
              (fn [content]
                (protocol-result (tms/strongest-distributed-value content)))))
           n1)]
      [(into (vec merge-props) strongest-props) n2])))

(defn install-behavior-protocol
  "Install sparse behavior reducer output methods into the network-local
  merge/strongest generics."
  []
  (fn [n]
    (let [[merge-props n1]
          ((define-merge-handler
             (generic/match-cells-pred
              #(or (empty-content? %)
                   (behavior/behavior-content? %))
              behavior/behavior-value?)
             (generic/handler-closure
              (fn [content update]
                (protocol-result
                 (behavior/merge-content
                  (if (empty-content? content) value/nothing content)
                  update)))))
           n)
          [strongest-props n2]
          ((define-strongest-handler
             (generic/match-cells-pred behavior/behavior-content?)
             (generic/handler-closure
              (fn [content]
                (protocol-result (behavior/strongest-value content)))))
           n1)]
      [(into (vec merge-props) strongest-props) n2])))

(defn install-event-protocol
  "Install source-aware event partial-information handlers."
  []
  (fn [n]
    (let [[merge-props n1]
          ((define-merge-handler
             (generic/match-cells-pred
              #(or (empty-content? %)
	                   (event/event-content? %)
	                   (event/event-fact? %)
                     (event/event-projection? %))
	              #(or (event/event-content? %)
	                   (event/event-fact? %)
                     (event/event-projection? %)))
             (generic/handler-closure
              (fn [content update]
                (protocol-result
                 (event/merge-content
                  (if (empty-content? content) value/nothing content)
                  update)))))
           n)
          [strongest-props n2]
          ((define-strongest-handler
             (generic/match-cells-pred event/event-content?)
             (generic/handler-closure
              (fn [content]
                (protocol-result (event/strongest-value content)))))
           n1)]
      [(into (vec merge-props) strongest-props) n2])))
