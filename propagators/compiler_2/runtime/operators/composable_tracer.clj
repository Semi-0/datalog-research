(ns propagators.compiler-2.runtime.operators.composable-tracer
  "Factories return caller-installed operators, preserving the actual root identity."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.operators.retained-relationship :as retained]
            [propagators.datastructures.compound-object :as object]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.semantic-trace.tracer-modifiers :as modifiers]
            [propagators.semantic-trace.facts :as facts]
            [propagators.semantic-trace.high-level :as high-level]))

(defn- tracer [mode selected]
  (operator/operator-closure
   {:name [::tracer mode]
    :application-installer
    (fn [network roots output context]
      (when-not (= 1 (count roots))
        (throw (ex-info "A configured tracer expects one root" {})))
      (let [sources (vec (sort-by pr-str (facts/root-seeds network roots)))
            captured (gur/stable-node-id [::acquisition mode sources])
            [installed observed _]
            (if (contains? (net/net-env network) captured)
              [network [] captured]
              (retained/install (declarations/ensure-cell network captured)
                                mode sources captured context))
            id (gur/stable-node-id [::view mode sources selected output])
            activate (fn [_ _ current]
                       (let [content (net/network-cell-content current captured)
                             graph (data/payload (net/network-cell-strongest current captured))]
                         (if (value/unusable? graph)
                           (data/state-messages output [content])
                           [(message output
                                     (data/supported [::view output]
                                                     (high-level/derive-view (modifiers/apply-modifiers graph selected))
                                                     #{captured} [content]))])))
            [pid result] ((prop/construct-propagator id ::view activate [captured] [output]) installed)]
        [(declarations/register-props result [pid]) (conj (vec observed) pid) output]))}))

(defn factory [mode]
  (operator/propagator-operator
   {:name [::factory mode]
    :activate
    (fn [network inputs [output] _]
      (let [contents (mapv #(net/network-cell-content network %) inputs)
            values (mapv #(data/payload (net/network-cell-strongest network %)) inputs)]
        (cond
          (some value/contradiction? values)
          [(message output (data/supported [::factory output] value/contradiction (set inputs) contents))]
          (some value/nothing? values) (data/state-messages output contents)
          :else [(message output
                          (data/supported [::factory output]
                                          (tracer mode (modifiers/normalize-supported values contents))
                                          (set inputs) contents))])))}))

(defn- scan-selection
  "Follow the built-in car/cdr protocol. Always reread the root, including on old watches."
  [network source]
  (loop [ref (data/reference source) seen #{} selected #{} watches #{source} contents []]
    (when (contains? seen ref)
      (throw (ex-info "Cyclic selector list" {})))
    (let [content (data/read-source network ref)
          candidate (data/payload (data/read-strongest network ref))
          watches (into watches (data/watch-ids network ref))
          contents (conj contents content)]
      (cond
        (value/contradiction? candidate)
        {:value value/contradiction :watches watches :contents contents}
        (value/nothing? candidate)
        {:value value/nothing :watches watches :contents contents}
        (= basis/list-empty-marker candidate)
        {:value (modifiers/selection selected) :watches watches :contents contents}
        (object/accessor-network? candidate)
        (let [head (update ref :source/slots conj :car)
              tail (update ref :source/slots conj :cdr)
              item (data/payload (data/read-strongest network head))
              head-content (data/read-source network head)
              watches (into watches (data/watch-ids network head))]
          (if (value/unusable? item)
            {:value item :watches watches :contents (conj contents head-content)}
            (recur tail (conj seen ref) (conj selected item) watches
                   (conj contents head-content))))
        :else (throw (ex-info "modifier:select requires a built-in Lain list" {}))))))

(declare select-activation)

(defn- select-activation [source output]
  (fn [_ _ network]
    (let [{:keys [value watches contents]} (scan-selection network source)
          watch-id (gur/stable-node-id [::selection output watches])
          evidence (if (value/nothing? value)
                     (data/state-messages output contents)
                     [(message output (data/supported [::selection output] value watches contents))])]
      (conj (vec evidence)
            (patch/declare-propagator watch-id ::selection (vec watches) [output]
                                      (select-activation source output))))))

(defn select-operator []
  (operator/propagator-operator
   {:name 'modifier:select
    :input-selector (fn [arguments _ _]
                      (when-not (= 1 (count arguments))
                        (throw (ex-info "modifier:select expects one built-in list" {})))
                      (vec arguments))
    :activate (fn [network [source] [output] _]
                ((select-activation source output) nil nil network))}))

(defn declaration-operator []
  (operator/propagator-operator
   {:name 'modifier:declaration
    :input-selector (fn [arguments _ _]
                      (when-not (= 1 (count arguments))
                        (throw (ex-info "modifier:declaration expects one kind" {})))
                      (vec arguments))
    :activate
    (fn [network [source] [output] _]
      (let [content (net/network-cell-content network source)
            kind (data/payload (net/network-cell-strongest network source))]
        (cond
          (value/nothing? kind) (data/state-messages output [content])
          (value/contradiction? kind)
          [(message output (data/supported [::declaration output] kind #{source} [content]))]
          :else
          [(message output
                    (data/supported [::declaration output]
                                    (modifiers/declaration (keyword kind))
                                    #{source} [content]))])))}))

(defn bindings []
  [['tracer:roots (factory :roots)]
   ['tracer:semantic (factory :semantic)]
   ['tracer:children (factory :children)]
   ['tracer:dataflow (factory :dataflow)]
   ['modifier:declaration (declaration-operator)]
   ['modifier:select (select-operator)]])
