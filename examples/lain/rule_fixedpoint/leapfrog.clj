(ns examples.lain.rule-fixedpoint.leapfrog
  "Correctness adapter only: pure LFTJ, one relation per body occurrence."
  (:require [leapfrog-pure :as leapfrog]
            [examples.lain.rule-fixedpoint.model :as model]))

(defn occurrence [variables atom table index]
  (let [terms (second atom) order (vec (filter (set terms) variables))
        tuples (into #{} (keep (fn [tuple]
                                (let [binding (model/bind-tuple {} terms tuple)]
                                  (if binding (mapv binding order) nil)))) table)]
    (leapfrog/make-relation index order tuples)))

(defn bindings [request]
  (let [variables (:variables request)
        ;; Existing LFTJ compares string keys. Ordinals avoid conflating, e.g.,
        ;; the number 1 and the string "1", without changing that implementation.
        nodes (vec (distinct (mapcat #(mapcat identity %) (:tables request))))
        ordinals (zipmap nodes (range 1 (inc (count nodes))))
        tables (mapv #(into #{} (map (fn [tuple] (mapv ordinals tuple))) %) (:tables request))
        relations (mapv #(occurrence variables %1 %2 %3)
                        (get-in request [:rule :body]) tables (range))
        invalid (leapfrog/validate-join-order relations variables)]
    (if invalid
      (throw (ex-info "Unsupported pure Leapfrog join order" invalid))
      (into #{} (comp (map #(zipmap variables (map (fn [ordinal] (nth nodes (dec ordinal))) %)))
                      (filter #(every? (fn [[variable v]] (= v (get % variable))) (:demand request))))
            (leapfrog/lftj relations variables :tuples)))))
