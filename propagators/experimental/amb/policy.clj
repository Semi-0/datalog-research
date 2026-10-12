(ns propagators.experimental.amb.policy
  "Deterministic strategy and choice transitions. All state is explicit data."
  (:require [clojure.set :as set]
            [propagators.experimental.amb.contracts :as contracts]
            [propagators.experimental.amb.learning :as learning]))

(defn advance-choice [record request]
  (cond
    (not= (:source record) (:target request)) record
    (= request (:handled-request record)) record
    (not= (:timestamp record) (:expected-timestamp request)) record
    :else
    (let [index (:next-index request)]
      (when-not (and (nat-int? index) (< index (count (:domain record))))
        (throw (ex-info "Repair alternative is outside the choice domain" {:request request})))
      (case (:action request)
        :withdraw
        (if (= :active (:status record))
          (assoc record :status :retracted :timestamp (inc (:timestamp record))
                        :handled-request request)
          record)
        :choose
        (if (= :retracted (:status record))
          (assoc record :status :active :index index
                        :timestamp (inc (:timestamp record)) :handled-request request)
          record)
        (throw (ex-info "Unknown addressed AMB repair action" {:request request}))))))

(defn- conflicts [knowledge assignment context]
  (filter #(and (learning/applicable? % context)
                (set/subset? (:literals %) assignment)) knowledge))

(defn- admissible-index [record records knowledge context]
  (let [source (:source record)
        others (disj (contracts/assignment records) [source (:index record)])
        relevant (filter #(some (fn [[id _]] (= source id)) (:literals %)) knowledge)]
    (first (filter #(and (not= % (:index record))
                        (not (learning/blocked? relevant (conj others [source %]) context)))
                   (range (count (:domain record)))))))

(defn- repair [records knowledge context]
  (let [active-conflicts (conflicts knowledge (contracts/assignment records) context)
        participating (into #{} (map first) (mapcat :literals active-conflicts))]
    (cond
      (some #(empty? (:literals %)) active-conflicts) {:action :unsatisfiable}
      (empty? active-conflicts) {:action :idle}
      :else
      (or (first (keep (fn [record]
                        (if (contains? participating (:source record))
                          (let [index (admissible-index record records knowledge context)]
                            (if (some? index)
                              {:action :withdraw :target (:source record)
                               :expected-timestamp (:timestamp record) :next-index index
                               :reason (set active-conflicts)}
                              nil))
                          nil)) (reverse records)))
          (throw (ex-info "Exhausted choices require a resolved no-good"
                          {:conflicts active-conflicts}))))))

(defn decide [records knowledge previous context]
  (let [records (contracts/choices records)
        target (first (filter #(= (:source %) (:target previous)) records))]
    (cond
      (and (= :withdraw (:action previous)) target
           (= :retracted (:status target))
           (= previous (:handled-request target)))
      (assoc previous :action :choose :expected-timestamp (:timestamp target))
      (and (contains? #{:withdraw :choose} (:action previous)) target
           (not= previous (:handled-request target))) previous
      (some #(= :retracted (:status %)) records) {:action :idle}
      :else (repair records knowledge context))))
