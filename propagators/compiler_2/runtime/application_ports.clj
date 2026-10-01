(ns propagators.compiler-2.runtime.application-ports
  "Read-only application interfaces, independent of graph selection and rendering."
  (:require [clojure.spec.alpha :as s]
            [propagators.cells.value :as value]
            [propagators.combinator :as combinator]
            [propagators.compiler-2.model.closure-value :as closure]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.rest-application :as rest-application]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.scope-source :as scope]
            [propagators.datastructures.tms.core :as tms]
            [propagators.ids :as ids]
            [propagators.network :as net]))

(s/def ::inputs (s/coll-of ids/node-id? :kind vector?))
(s/def ::outputs (s/coll-of ids/node-id? :kind vector?))
(s/def ::routing #{:application :forward :bidirectional})
(s/def ::ports (s/keys :req-un [::inputs ::outputs ::routing]))

(defn callable-value
  "Explicit observation projection; does not mutate source content or evidence."
  [network operator-id]
  (let [v (scope/unwrap (net/network-cell-strongest network operator-id))]
    (dependency/unwrap
     (tms/distributed-base-value
      (if (tms/distributed-content? v)
        (tms/strongest-distributed-value v)
        v)))))

(defn fixed-closure-ports
  [declaration {:keys [argument-ids result-id]}]
  (let [{:keys [input-ids outputs]}
        (application/closure-call declaration argument-ids result-id)]
    {:inputs input-ids
     :outputs (if (seq outputs) (mapv :outer-id outputs) [result-id])
     :routing :application}))

(defn- rest-ports
  [callable {:keys [argument-ids result-id] :as app}]
  (let [signature (get-in callable [rest-application/rest-callable-key :parameters])]
    (when (< (count argument-ids) (count (:required signature)))
      (throw (ex-info "Rest closure application has invalid arity"
                      {:application-id (:application-id app)
                       :parameters signature :argument-ids argument-ids})))
    {:inputs (vec argument-ids) :outputs [result-id] :routing :application}))

(defn- primitive-ports
  [callable {:keys [argument-ids result-id context-id]}]
  (let [call (operator/operator-call callable argument-ids result-id context-id)
        name (operator/operator-name callable)]
    (assoc (select-keys call [:inputs :outputs])
           :routing (cond
                      (= '-> name) :forward
                      (= '<-> name) :bidirectional
                      :else :application))))

(defn- closure-ports
  [callable app]
  (let [ports (fixed-closure-ports (application/callable-declaration callable) app)
        name (:gur.flat/name callable)]
    (if (and (vector? name) (= :compiler-2/constraint (first name)))
      (update ports :outputs #(vec (distinct (concat % (:inputs ports)))))
      ports)))

(defn- reject-callable
  [_callable app]
  (throw (ex-info "Unsupported application declaration for port inspection"
                  (select-keys app [:application-id :operator-id]))))

(def inspect-ports
  (combinator/branch
   (fn [callable _] (value/unusable? callable))
   (fn [_ _] value/nothing)
   (fn [callable _] (rest-application/rest-callable? callable)) rest-ports
   (fn [callable _] (operator/operator-closure? callable)) primitive-ports
   (fn [callable _] (closure/closure-info? (application/callable-declaration callable)))
   closure-ports
   reject-callable))

(defn application-ports
  [network {:keys [operator-id] :as app}]
  (inspect-ports (callable-value network operator-id) app))

(s/fdef application-ports
  :args (s/cat :network map? :application map?)
  :ret (s/or :pending value/nothing? :ready ::ports))
