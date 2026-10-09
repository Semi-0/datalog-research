(ns propagators.network-facts.support
  (:require [clojure.set :as set]
            [propagators.network-facts.export :as e]
            [propagators.network-facts.import :as i]
            [propagators.network :as net]
            [propagators.message :refer [message]]
            [propagators.propagator :as prop]))

(defn copy-factory [_ inputs outputs]
  (fn [_ _ network]
    [(message (first outputs) (net/network-cell-strongest network (first inputs)))]))

(defn recipe-facts [prop-id kind factory parameters inputs outputs]
  (let [entity [:env prop-id] r [:recipe prop-id]]
    (into #{[entity :propagator/recipe r] [r :recipe/kind kind] [r :recipe/factory factory]}
          (concat
           (mapcat (fn [[key value id?]]
                     (let [p [:parameter prop-id key]]
                       [[r :recipe/parameter p] [p :parameter/key key]
                        [p :parameter/value value] [p :parameter/id? id?]])) parameters)
           (mapcat (fn [[direction nodes]]
                     (mapcat (fn [position id]
                               (let [p [:port prop-id direction position]]
                                 [[r (keyword "recipe" (str (name direction) "-port")) p]
                                  [p :port/position position] [p :port/cell id]]))
                             (range) nodes))
                   [[:input inputs] [:output outputs]])))))

(defn copy-prop [network from to]
  ((prop/construct-propagator :copy (copy-factory {} [from] [to]) [from] [to]) network))

(defn entity-closure [index entities]
  (loop [current (set entities)]
    (let [next (into current
                     (mapcat (fn [entity]
                               (mapcat #(e/values-at index entity %)
                                       [:record/field :propagator/recipe :recipe/input-port
                                        :recipe/output-port :recipe/parameter]))) current)]
      (if (= current next) current (recur next)))))

(defn removal-facts [facts node-ids]
  (let [index (e/index-facts facts)
        entities (entity-closure index (mapcat #(vector [:env %] [:graph %]) node-ids))]
    (into #{} (filter (fn [[entity attribute value]]
                       (or (contains? entities entity)
                           (and (contains? #{:net/graph-node :net/env-entry
                                             :graph/input :graph/output} attribute)
                                (contains? entities value))))) facts)))

(defn replace-value [facts entity attribute value]
  (conj (into #{} (remove (fn [[e a _]] (and (= e entity) (= a attribute)))) facts)
        [entity attribute value]))

(defn reason [f]
  (try (f) :accepted (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))

(defn contraction [facts retired survivor removed-props rule]
  (let [index (e/index-facts facts)
        prop-removals (removal-facts facts removed-props)
        retired-entities (entity-closure index [[:env retired] [:graph retired]])
        cell-removals (into #{} (filter (fn [[entity attribute value]]
                                         (or (contains? retired-entities entity)
                                             (and (contains? #{:net/graph-node :net/env-entry} attribute)
                                                  (contains? retired-entities value))))) facts)
        redirects (into #{} (map (fn [[_ attribute value]]
                                   [[:graph survivor] attribute value]))
                        (filter (fn [[entity attribute value]]
                                  (and (= [:graph retired] entity)
                                       (contains? #{:graph/input :graph/output} attribute)
                                       (not (contains? (set removed-props) (second value))))) facts))]
    {:rule rule :remove-facts (set/union prop-removals cell-removals)
     :add-facts redirects :id-map {retired survivor}}))
