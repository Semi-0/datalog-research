(ns examples.lain.rule-fixedpoint.validation-reference
  "Benchmark/test oracle: the original compiled validation of indexed bindings.
  Not selected by the normal solver or a compatibility runtime."
  (:require [examples.lain.rule-fixedpoint.matching :as matching]
            [examples.lain.rule-fixedpoint.model :as model]
            [examples.lain.rule-fixedpoint.query :as query]))

(defn matching-result [mode request]
  (cond
    (some empty? (:tables request)) {:bindings #{} :probes 0}
    (and (= 1 (count (get-in request [:rule :body])))
         (nil? (get-in request [:rule :guard])))
    {:bindings (model/full-bindings (if (:require-delta? request)
                                      (assoc request :tables (:deltas request)) request))
     :probes (count (first (:tables request)))}
    :else
    (let [question (query/instantiate (:query request) request)
          result (matching/indexed mode request)]
      (update result :bindings
              #(into #{} (filter (fn [binding]
                                  (query/accepted? question
                                                   (query/encode request (model/padded request binding))))) %)))))
