(ns propagators.experimental.amb.primitives
  "Four ordinary propagators. Runner and production cell semantics are unchanged."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.experimental.amb.contracts :as contracts]
            [propagators.experimental.amb.learning :as learning]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.policy :as policy]
            [propagators.experimental.amb.publication :as publication]
            [propagators.gur.flat :as gur]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-state :as state]))

(defn- install [name activation inputs outputs]
  (fn [network]
    ((prop/construct-propagator name (publication/lift activation) inputs outputs)
     (reduce nb/ensure-cell network (concat inputs outputs)))))

(defn- watch-patches [network watches descriptor]
  (let [{:keys [name activation outputs]} descriptor]
    (into []
          (keep (fn [cell]
                  (let [id (gur/stable-node-id [::watch name outputs cell])]
                    (if (contains? (net/net-env network) id)
                      nil
                      (patch/declare-propagator id [::watch name] [cell] outputs
                                                (publication/lift activation))))))
          watches)))

(defn- choice-activation [domain repairs control output]
  (fn [_ _ network]
    (let [snapshot (observation/list-snapshot network domain)]
      (if (:ready? snapshot)
        (let [previous (observation/cell-base network control)
              record (if (value/nothing? previous)
                       (contracts/initial-choice output (:items snapshot))
                       (contracts/checked ::contracts/choice previous))
              request (observation/cell-base network repairs)]
          (when-not (= (:domain record) (:items snapshot))
            (throw (ex-info "Changing an AMB domain is outside this experiment" {})))
          (publication/choice-messages network (policy/advance-choice record request) output control))
        []))))

(defn p:amb [domain repairs control output]
  (install ::amb (choice-activation domain repairs control output)
           [domain repairs] [output control]))

(defn- failure-activation [predicate output]
  (fn [_ _ network]
    (let [current (net/network-cell-strongest network predicate)
          base (observation/base current)
          premises (datum/support-of current)
          certificate (cond
                        (value/unusable? current) {:status :pending}
                        (not (observation/current-support? network premises)) {:status :pending}
                        (false? base) {:status :failed :support premises}
                        (true? base) {:status :satisfied}
                        :else (throw (ex-info "require expects a Boolean predicate" {:value base})))]
      [(update (publication/snapshot-message network output certificate)
               :value (fn [v] (update v :premise-state support/join (state/states-of current))))])))

(defn p:require [predicate output]
  (install ::require (failure-activation predicate output) [predicate] [output]))

(defn- knowledge-context [network knowledge certificate]
  (let [sources (into #{} (map :source)
                      (concat (:support certificate) (mapcat :context knowledge)))]
    (observation/context network sources)))

(defn- learning-activation [failure choices output]
  (fn activate [_ _ network]
    (let [snapshot (observation/list-snapshot network choices)
          watchers (watch-patches network (:watches snapshot)
                                 {:name ::learn :activation activate :outputs [output]})]
      (if (:ready? snapshot)
        (let [records (contracts/choices (:items snapshot))
              previous (observation/cell-base network output)
              knowledge (if (value/nothing? previous) #{} previous)
              certificate (observation/cell-base network failure)
              context (knowledge-context network knowledge certificate)
              result (learning/learn knowledge certificate records context)]
          (conj watchers (publication/snapshot-message network output result)))
        watchers))))

(defn p:learn [failure choices output]
  (install ::learn (learning-activation failure choices output) [failure choices] [output]))

(defn- strategy-activation [choices knowledge output]
  (fn activate [_ _ network]
    (let [snapshot (observation/list-snapshot network choices)
          known (observation/cell-base network knowledge)
          watchers (watch-patches network (:watches snapshot)
                                 {:name ::backtrack :activation activate :outputs [output]})]
      (if (and (:ready? snapshot) (not (value/unusable? known)))
        (let [context (knowledge-context network known nil)
              previous (observation/cell-base network output)
              request (policy/decide (:items snapshot) known previous context)]
          (conj watchers (publication/snapshot-message network output request)))
        watchers))))

(defn p:backtrack [choices knowledge output]
  (install ::backtrack (strategy-activation choices knowledge output) [choices knowledge] [output]))
