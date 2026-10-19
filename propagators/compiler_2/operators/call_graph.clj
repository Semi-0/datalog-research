(ns propagators.compiler-2.operators.call-graph
  "Reactive call graphs derived from closure syntax and connected topology."
  (:require [propagators.cells.value :as value]
            [propagators.cells.cell :as cell]
            [propagators.compiler-2.operators.call-graph-syntax :as syntax]
            [propagators.compiler-2.operators.call-graph-relations :as relations]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.compiler-2.runtime.declaration-effects :as nb]
            [propagators.propagator :as prop]
            [propagators.semantic-trace :as semantic-trace])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(defn- stable-node-id
  [& seed]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:compiler-2/call-graph] seed))
               StandardCharsets/UTF_8))))

(defn graph-cell-id
  "Stable cell receiving realized call facts for one closure cell."
  [closure-id]
  (stable-node-id :closure closure-id :graph))

(defn- operator-label
  [operator-expr]
  (if (= :symbol (ast/type operator-expr))
    (str (ast/name operator-expr))
    (str (ast/type operator-expr))))

(defn call-sites
  "Applications in this body only; nested callable declarations are opaque."
  ([expr] (syntax/call-sites expr #{}))
  ([expr bound] (syntax/call-sites expr bound)))

(defn- empty-graph []
  (semantic-trace/graph-union
   {:nodes {} :node-aliases {} :values {} :node-ui {} :expansions {} :edges []}))

(defn- closure-label
  [closure-info]
  (str "closure " (pr-str (closure-value/closure-inputs closure-info))))

(defn- potential-callee-id
  [network closure-id closure-info {:keys [operator path bound?]}]
  (if (and (= :symbol (ast/type operator)) (not bound?))
    (or (env/lexical-binding-id network
                                (ast/name operator)
                                (closure-value/closure-env closure-info))
        (stable-node-id :callee closure-id path))
    (stable-node-id :callee closure-id path)))

(defn- callable-name [network id fallback]
  (let [entry (net/network-env-lookup network id)]
    (if (and (cell/cell? entry) (not (cell/anonymous-name? (cell/cell-name entry))))
      (str (cell/cell-name entry))
      fallback)))

(defn- callable-environment [candidate]
  (when (application/compiler-callable? candidate)
    (get candidate application/captured-environment-key)))

(defn- network-callable-environment [network id]
  (let [candidate (net/network-cell-strongest network id)]
    (when-not (value/unusable? candidate)
      (callable-environment candidate))))

(defn potential-call-graph
  "Build the potential call graph stored in one closure body."
  [network closure-id closure-info]
  (relations/project
   (reduce
    (fn [graph {:keys [path operator-label] :as site}]
      (let [call-id (stable-node-id :potential closure-id path)
            callee-id (potential-callee-id network closure-id closure-info site)
            callee-label (if (= closure-id callee-id)
                           (closure-label closure-info)
                           operator-label)]
        (semantic-trace/graph-union
         graph
         {:nodes (assoc {closure-id (callable-name network closure-id (closure-label closure-info))
                         call-id operator-label}
                        callee-id callee-label)
          :node-kinds (assoc {closure-id :cell call-id :propagator} callee-id :cell)
          :dataflow/representation :callgraph
          :values {call-id {:call/status :potential
                            :call/path path
                            :call/operator operator-label}}
          :graph/facts
          (relations/facts
           [:call-from call-id closure-id]
           [:call-to call-id callee-id]
           [:call-status call-id :potential]
           [:call-path call-id path]
           [:closure-environment closure-id (closure-value/closure-env closure-info)]
           [:closure-environment callee-id
            (network-callable-environment network callee-id)]
           [:central-environment (closure-value/closure-env closure-info)])
          :edges [[closure-id call-id] [call-id callee-id]]})))
    (semantic-trace/graph-union
     {:nodes {closure-id (callable-name network closure-id (closure-label closure-info))}
      :node-kinds {closure-id :cell}
      :dataflow/representation :callgraph})
    (call-sites (closure-value/closure-body closure-info)
                (set (closure-value/closure-inputs closure-info))))))

(defn- realized-callee-label
  [operator]
  (let [declaration (application/callable-declaration operator)]
    (cond
      (closure-value/closure-info? declaration)
      (closure-label declaration)

      (operator-value/operator-closure? operator)
      (str (or (operator-value/operator-name operator) :primitive))

      :else
      "callable")))

(defn realized-call-graph
  "Build one realized call fact from connected application topology."
  ([caller-id application-id operator-id operator]
   (realized-call-graph caller-id application-id operator-id operator nil))
  ([caller-id application-id operator-id operator call-label]
   (realized-call-graph caller-id application-id operator-id operator call-label {}))
  ([caller-id application-id operator-id operator call-label
    {:keys [path caller-environment-id callee-environment-id frame-id]}]
   (let [callee-label (cond
                        (= caller-id operator-id)
                        "closure"

                        :else
                        (realized-callee-label operator))
         application-label (cond
                             (string? call-label)
                             call-label

                             :else
                             callee-label)]
    (semantic-trace/graph-union
     {:nodes (assoc {caller-id "closure"
                     application-id application-label}
                    operator-id callee-label)
      :node-kinds (assoc {caller-id :cell application-id :propagator} operator-id :cell)
      :dataflow/representation :callgraph
      :values {application-id (cond-> {:call/status :realized
                                       :call/application application-id
                                       :call/operator operator-id}
                                path (assoc :call/path path))}
      :graph/facts
      (relations/facts
       [:call-from application-id caller-id]
       [:call-to application-id operator-id]
       [:call-status application-id :realized]
       [:call-path application-id path]
       [:call-application application-id application-id]
       [:application-frame application-id frame-id]
       [:frame-environment frame-id callee-environment-id]
       [:closure-environment caller-id caller-environment-id]
       [:closure-environment operator-id callee-environment-id]
       [:central-environment caller-environment-id])
      :edges [[caller-id application-id]
              [application-id operator-id]]}))))

(defn- application-call-messages
  [caller-id application-id operator-id call-label path graph-id network]
  (let [operator (net/network-cell-strongest network operator-id)]
    (if (value/unusable? operator)
      []
      (let [caller (net/network-cell-strongest network caller-id)
            topology (application/application-topology network application-id)
            closure-application?
            (closure-value/closure-info?
             (application/callable-declaration operator))]
        [(message graph-id
                  (realized-call-graph
                   caller-id application-id operator-id operator call-label
                   {:path path
                    :caller-environment-id (callable-environment caller)
                    :callee-environment-id
                    (or (:captured-environment-id topology)
                        (callable-environment operator))
                    :frame-id (or (:frame-id topology)
                                  (when closure-application?
                                    (application/lexical-frame-id
                                     application-id)))}))]))))

(defn p:application-call
  "Publish one connected application into its caller's stable graph cell."
  ([caller-id application-id operator-id call-label]
   (p:application-call caller-id application-id operator-id call-label nil))
  ([caller-id application-id operator-id call-label path]
   (let [graph-id (graph-cell-id caller-id)]
    (fn [network]
      (let [network (reduce nb/ensure-cell network
                            [caller-id operator-id graph-id])
            [prop-id network]
            ((prop/construct-propagator
              (stable-node-id :application application-id :prop)
              :compiler-2/call-graph-application
              (fn [_inputs _outputs current-net]
                (application-call-messages caller-id application-id operator-id
                                           call-label path
                                           graph-id current-net))
              [caller-id operator-id]
              [graph-id])
             network)]
        [prop-id network])))))

(defn- call-graph-messages
  [closure-id graph-id out-id network]
  (let [closure (net/network-cell-strongest network closure-id)
        closure-info (application/callable-declaration closure)
        realized (net/network-cell-strongest network graph-id)]
    (if-not (closure-value/closure-info? closure-info)
      []
      [(message out-id
                (relations/project
                 (semantic-trace/graph-union
                  (potential-call-graph network closure-id closure-info)
                  realized)))])))

(defn p:call-graph
  "Construct a named propagator exposing potential and realized closure calls."
  [closure-id out-id]
  (let [graph-id (graph-cell-id closure-id)]
    (fn [network]
      (let [network (reduce nb/ensure-cell network [closure-id graph-id out-id])
            [prop-id network]
            ((prop/construct-propagator
              (stable-node-id :closure closure-id :prop)
              :compiler-2/call-graph
              (fn [_inputs _outputs current-net]
                (call-graph-messages closure-id graph-id out-id current-net))
              [closure-id graph-id]
              [out-id])
             network)]
        [prop-id network]))))

(defn call-graph-operator
  []
  (operator-value/propagator-operator
   {:name 'call-graph
    :output-selector
    (fn [arg-ids fallback-id]
      (let [[_closure-id explicit-out-id] (vec arg-ids)]
        [(or explicit-out-id fallback-id)]))
    :input-selector
    (fn [arg-ids _fallback-id _context-id]
      (let [[closure-id _explicit-out-id] (vec arg-ids)]
        (when-not (and closure-id (<= 1 (count arg-ids) 2))
          (throw (ex-info "call-graph expects a closure and optional output"
                          {:arg-ids arg-ids})))
        [closure-id (graph-cell-id closure-id)]))
    :activate
    (fn [network inputs outputs _context-id]
      (let [[closure-id graph-id] inputs]
        (call-graph-messages closure-id graph-id (first outputs) network)))}))
