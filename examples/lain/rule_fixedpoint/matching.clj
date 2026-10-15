(ns examples.lain.rule-fixedpoint.matching
  "Occurrence-indexed relational matching. Strategies return correlated bindings."
  (:require [clojure.set :as sets]
            [examples.lain.rule-fixedpoint.model :as model]))

(defn index-table [terms table]
  (reduce (fn [index tuple]
            (reduce (fn [result [variable v]]
                      (update-in result [variable v] (fnil conj #{}) tuple))
                    index (map vector terms tuple)))
          {} table))

(defn candidates [index table binding]
  (let [restrictions (keep (fn [[variable v]]
                             (if (contains? index variable)
                               (get-in index [variable v] #{}) nil)) binding)]
    (if (seq restrictions) (apply sets/intersection restrictions) table)))

(defn update-index [index terms removed added]
  (let [without (reduce (fn [index tuple]
                          (reduce (fn [index [variable v]] (update-in index [variable v] disj tuple))
                                  index (map vector terms tuple))) index removed)]
    (merge-with #(merge-with sets/union %1 %2) without (index-table terms added))))

(defn refresh-indexes [request previous]
  (mapv (fn [i]
          (let [table (nth (:tables request) i) old (get-in previous [:tables i] #{})
                terms (second (nth (get-in request [:rule :body]) i))]
            (update-index (get-in previous [:indexes i] {}) terms
                          (sets/difference old table) (sets/difference table old))))
        (range (count (:tables request)))))

(defn join-window [request tables]
  (reduce (fn [{:keys [bindings probes]} [i atom table]]
            (let [terms (second atom)
                  index (if (and (:indexes request) (= table (nth (:tables request) i)))
                          (nth (:indexes request) i) (index-table terms table))
                  selected (mapv #(candidates index table %) bindings)]
              {:bindings (into #{} (keep identity)
                               (mapcat (fn [binding tuples]
                                         (map #(model/bind-tuple binding terms %) tuples))
                                       bindings selected))
               :probes (+ probes (reduce + 0 (map count selected)))}))
          {:bindings #{(:demand request)} :probes 0}
          (map vector (range) (get-in request [:rule :body]) tables)))

(defn indexed [mode request]
  (let [windows (case mode
                  :indexed-full [(:tables request)]
                  :indexed-delta (if (:initial? request)
                                   [(:tables request)]
                                   (model/changed-windows (:tables request) (:deltas request)))
                  (throw (ex-info "Unknown indexed matcher" {:strategy mode})))
        results (mapv #(join-window request %) windows)
        bindings (apply sets/union #{} (map :bindings results))]
    {:bindings (if (:require-delta? request)
                 (into #{} (filter #(model/accepts? request (mapv % (:variables request)))) bindings)
                 bindings)
     :probes (reduce + 0 (map :probes results))}))
