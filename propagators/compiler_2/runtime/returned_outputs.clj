(ns propagators.compiler-2.runtime.returned-outputs
  "Declare one stable outbound port for each returned linked-list member."
  (:require [propagators.compiler-2.runtime.list-interface :as list-interface]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def output-scope :compiler-2/network-outputs)
(def status-scope :compiler-2/network-output-status)

(defn- complete-output-effects [application-id count shape]
  (let [id (gur/stable-node-id [application-id :output-status])]
    {:effects [(gur/declare-cell id)
               (gur/bind-name status-scope application-id id)]
     :messages [(message id {:status :complete :count count :shape shape})]}))

(defn- copy-effect [scope source target]
  (gur/declare-prop
   (gur/stable-node-id [scope source target])
   [:compiler-2/boundary scope source target]
   [source] [target]
   (fn [_ _ network]
     (let [content (net/network-cell-content network source)]
       (if (value/unusable? content)
         []
         [(message target content)])))))

(declare scan-output-effect)

(defn copy-output-boundary [_context scope source target]
  [(copy-effect scope source target)])

(defn- output-boundary-effects [context project routes application-id position head]
  (let [port-id (gur/stable-node-id [application-id :output-port position])]
    (into [(gur/declare-cell port-id)
           (gur/bind-name output-scope [application-id position] port-id)
           (copy-effect [application-id :outbound position] head port-id)]
          (if-let [outer (get routes head)]
            (project context [application-id :caller position] port-id outer)
            []))))

(defn- output-list-body [project routes application-id position seen]
  (fn [context [list-id] _]
    (let [network (:network context)]
      (when (contains? seen list-id)
        (throw (ex-info "Cyclic output interfaces are unsupported" {:list-id list-id})))
      (case (list-interface/list-shape network list-id)
        :cons
        (let [head (obj/existing-slot-cell-id network :car list-id)
              tail (obj/existing-slot-cell-id network :cdr list-id)]
          {:effects
           (into (output-boundary-effects context project routes application-id position head)
                 (scan-output-effect project routes application-id (inc position) tail
                                     (conj seen list-id)))})
        :empty (complete-output-effects application-id position :list)
        :wait {:effects []}
        :scalar
        (if (zero? position)
          (complete-output-effects application-id 0 :scalar)
          (throw (ex-info "Output interface has a non-list tail"
                          {:list-id list-id :position position})))))))

(defn- scan-output-effect
  ([project routes application-id position list-id]
   (scan-output-effect project routes application-id position list-id #{}))
  ([project routes application-id position list-id seen]
   ;; Availability waits for list topology, not for the values of its members.
   (let [closure-id (gur/stable-node-id [application-id :output-scanner position])
         out-id (gur/stable-node-id [application-id :output-scan-result position])
         condition-id (gur/stable-node-id [application-id :output-ready position])
         key [application-id :outputs position]]
     (into (list-interface/list-readiness-effects key list-id condition-id)
           [(gur/declare-cell closure-id)
            (gur/declare-cell out-id)
            ;; Each recursive step has a distinct position and semantic identity.
            (gur/when-effect
             key condition-id
             (fn []
               {:messages
                [(message
                  closure-id
                  (gur/recursive-closure
                   [:compiler-2/outputs position]
                   (output-list-body project routes application-id position seen)))]
                :effects [(gur/apply-closure-effect closure-id [list-id] out-id)]}))]))))

(defn register-returned-outputs
  ([declared routes application-id result-id]
   (register-returned-outputs declared copy-output-boundary routes application-id result-id))
  ([declared project routes application-id result-id]
   (update declared :effects into
           (scan-output-effect project routes application-id 0 result-id))))

(defn- inbound-routes [network]
  (reduce-kv
   (fn [routes id {:keys [inputs outputs]}]
     (let [entry (get (net/net-env network) id)
           name (when (prop/prop? entry) (prop/prop-name entry))]
       (if (and (vector? name)
                (= :compiler-2/application (first name))
                (= :inbound (nth name 2 nil)))
         (if (and (= 1 (count inputs)) (= 1 (count outputs)))
           (assoc routes (first outputs) (first inputs))
           (throw (ex-info "Inbound boundary must connect two cells" {:boundary id})))
         routes)))
   {} (net/net-graph network)))

(defn- definition-route-effects [context project routes application-id target]
  (loop [source target seen #{} effects []]
    (if-let [outer (get routes source)]
      (if (contains? seen source)
        (throw (ex-info "Cyclic invocation input routing" {:target target}))
        (recur outer (conj seen source)
               (into effects (project context [application-id :defined-input source outer]
                                      source outer))))
      effects)))

(defn- defined-input-effects [context project effects routes application-id]
  ;; A definition targeting an input cell is a visible connection back to its
  ;; caller. Inspect declaration names, never the value stored in that cell.
  (->> effects
       (filter #(= :network/declare-propagator (:op %)))
       (mapcat (fn [{:keys [name outputs]}]
                 (if (and (vector? name)
                          (= :compiler-2/definition (first name)))
                   (definition-route-effects context project routes application-id (first outputs))
                   [])))
       vec))


(defn register-application-return
  ([declared context result-id]
   (register-application-return declared context result-id copy-output-boundary))
  ([declared context result-id project]
  (let [application-id (:app-key context)
        effects (:effects declared)
        routes
        (into (inbound-routes (:network context))
              (keep (fn [{:keys [op name inputs outputs]}]
                      (when (and (= :network/declare-propagator op)
                                 (vector? name)
                                 (= :compiler-2/application (first name))
                                 (= :inbound (nth name 2 nil)))
                        [(first outputs) (first inputs)])))
              effects)]
    (-> declared
        (update :effects into (defined-input-effects context project effects routes application-id))
        (register-returned-outputs project routes application-id result-id)))))

(defn outputs [network]
  (get (net/network-dict-entry network gur/name-bindings-key) output-scope {}))
