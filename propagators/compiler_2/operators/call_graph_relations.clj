(ns propagators.compiler-2.operators.call-graph-relations
  "Datalog-derived call direction and lexical-environment relationships."
  (:require [propagators.ids :as ids]
            [propagators.semantic-trace.logic :as logic])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def rules
  [{:head [:calls [:caller :callee :occurrence :status]]
    :body [[:call-from [:occurrence :caller]]
           [:call-to [:occurrence :callee]]
           [:call-status [:occurrence :status]]]}
   {:head [:called-by [:callee :caller :occurrence :status]]
    :body [[:calls [:caller :callee :occurrence :status]]]}
   {:head [:environment-callable [:environment :callable]]
    :body [[:closure-environment [:callable :environment]]]}
   {:head [:call-environment [:occurrence :environment]]
    :body [[:call-to [:occurrence :callee]]
           [:closure-environment [:callee :environment]]]}
   {:head [:call-environment [:occurrence :environment]]
    :body [[:call-application [:occurrence :application]]
           [:application-frame [:application :frame]]
           [:frame-environment [:frame :environment]]]}
   {:head [:environment-frame [:environment :frame]]
    :body [[:frame-environment [:frame :environment]]]}])

(defn facts
  "Construct a fact relation map, omitting tuples containing absent values."
  [& entries]
  (reduce (fn [result [predicate & tuple]]
            (if (every? some? tuple)
              (update result predicate (fnil conj #{}) (vec tuple))
              result))
          {}
          entries))

(defn derive-relations
  [facts]
  (logic/run facts rules))

(defn- relation-node-id [kind from to]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str [:compiler-2/call-graph-relation kind from to])
               StandardCharsets/UTF_8))))

(defn- display-reference [nodes id]
  {:id id :label (get nodes id (pr-str id))})

(defn- add-reference [graph node-id key reference]
  (update-in graph [:values node-id key] (fnil conj #{}) reference))

(defn- add-call
  [graph [caller callee occurrence status]]
  (let [from (display-reference (:nodes graph) caller)
        to (display-reference (:nodes graph) callee)]
    (-> graph
        (update-in [:values occurrence] merge
                   {:call/status status
                    :call/from from
                    :call/to to
                    :call/called-by from})
        (add-reference caller :call/calls to)
        (add-reference callee :call/called-by from))))

(defn- add-environment-callable
  [graph central-environments [environment callable]]
  (let [central? (contains? central-environments environment)
        label (if central? "central environment" "captured environment")
        capture-id (relation-node-id :captures environment callable)]
    (-> graph
        (update :nodes assoc environment label capture-id "captures")
        (update :node-kinds assoc environment :cell capture-id :propagator)
        (update-in [:values environment] merge
                   {:environment/role (if central? :central :captured)})
        (update-in [:values capture-id] merge
                   {:relationship/type :environment/captures})
        (add-reference environment :environment/callables
                       (display-reference (:nodes graph) callable))
        (update-in [:values callable] merge
                   {:call/environment (display-reference {environment label}
                                                         environment)})
        (update :edges into [[environment capture-id]
                             [capture-id callable]]))))

(defn- add-environment-frame
  [graph [environment frame]]
  (let [extends-id (relation-node-id :extends environment frame)]
    (-> graph
        (update :nodes assoc frame "call frame" extends-id "extends")
        (update :node-kinds assoc frame :cell extends-id :propagator)
        (update-in [:values frame] merge
                   {:frame/environment (display-reference (:nodes graph)
                                                          environment)})
        (update-in [:values extends-id] merge
                   {:relationship/type :environment/extends})
        (update :edges into [[environment extends-id]
                             [extends-id frame]]))))

(defn- add-call-environment
  [graph application-by-call [occurrence environment]]
  (let [reference (display-reference (:nodes graph) environment)
        graph (update-in graph [:values occurrence] merge
                         {:call/environment reference})]
    (if-let [application (get application-by-call occurrence)]
      (if-let [frame (some (fn [[candidate frame]]
                            (when (= application candidate) frame))
                          (get-in graph [:graph/facts :application-frame]))]
        (update graph :edges conj [frame occurrence])
        graph)
      graph)))

(defn project
  "Derive directional and environment views while preserving the base graph."
  [graph]
  (let [facts (:graph/facts graph)
        derived (derive-relations facts)
        central-environments (set (map first (:central-environment facts)))
        application-by-call (into {} (:call-application facts))]
    (-> (reduce add-call graph (:calls derived))
        (#(reduce (fn [result relation]
                    (add-environment-callable result central-environments relation))
                  % (:environment-callable derived)))
        (#(reduce add-environment-frame % (:environment-frame derived)))
        (#(reduce (fn [result relation]
                    (add-call-environment result application-by-call relation))
                  % (:call-environment derived)))
        (update :edges #(vec (distinct %)))
        (assoc :graph/relations
               (select-keys derived
                            [:calls :called-by :environment-callable
                             :call-environment :environment-frame])))))
