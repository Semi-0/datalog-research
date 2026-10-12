(ns propagators.experimental.amb.observation
  "Read finite declared lists and premise versions; never mutate their owners."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.network :as net]
            [propagators.stdlib.premise-state :as state]))

(defn base [v]
  (if (datum/layer-present? v :base) (datum/layer-value v :base) v))

(defn cell-base [network id]
  (base (net/network-cell-strongest network id)))

(defn- slot [network owner key]
  (let [parents (obj/accessor-parent-ids owner key)]
    (cond
      (= 1 (count parents))
      {:value (net/network-cell-strongest network (first parents)) :watch (set parents)}
      (empty? parents)
      {:value (obj/accessor-source-slot-value owner key) :watch #{}}
      :else (throw (ex-info "AMB list slot has multiple parents" {:slot key :parents parents})))))

(defn list-snapshot [network root]
  (loop [current (net/network-cell-strongest network root)
         items [] watches #{root} seen #{}]
    (let [v (base current)]
      (cond
        (value/unusable? current) {:ready? false :items items :watches watches}
        (= :compiler-2/list-empty v) {:ready? true :items items :watches watches}
        (vector? v) {:ready? true :items (into items v) :watches watches}
        (not (obj/accessor-network? v))
        (throw (ex-info "AMB expects a finite list" {:value v}))
        (contains? seen v) (throw (ex-info "AMB list is cyclic" {}))
        :else
        (let [head (slot network v :car) tail (slot network v :cdr)
              watched (into (into watches (:watch head)) (:watch tail))]
          (if (value/unusable? (:value head))
            {:ready? false :items items :watches watched}
            (recur (:value tail) (conj items (base (:value head)))
                   watched (conj seen v))))))))

(defn context [network sources]
  (apply support/join
         (map #(state/states-of (net/network-cell-strongest network %)) sources)))

(defn current-support? [network premises]
  (and (support/support? premises) (not (support/unusable? premises))
       (every? (fn [{:keys [source] :as premise}]
                 (let [current (state/states-of (net/network-cell-strongest network source))]
                   (= #{premise} (set (filter #(= source (:source %)) current)))))
               premises)))
