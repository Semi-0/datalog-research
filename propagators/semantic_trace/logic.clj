(ns propagators.semantic-trace.logic
  "Finite positive Datalog strata, evaluated by the repository's Leapfrog engine."
  (:require [leapfrog-pure :as leapfrog]))

(def ownership-rules
  [{:head [:owner [:node :app]]
    :body [[:parent [:node :app]] [:application [:app]]]}
   {:head [:owner [:node :app]]
    :body [[:parent [:node :wrapper]] [:transparent [:wrapper]] [:owner [:wrapper :app]]]}])

(def visibility-rules
  [{:head [:visible [:app]]
    :body [[:root-call [:app]] [:root-view [:token]]]}
   {:head [:visible [:app]]
    :body [[:owner [:app :parent]] [:opened [:parent]] [:eligible [:app]]]}])

(def projection-rules
  [{:head [:producer [:cell :app]]
    :body [[:output [:cell :app]] [:visible [:app]]]}
   {:head [:producer [:target :app]]
    :body [[:copy-back [:target :source]] [:producer [:source :app]]]}
   {:head [:edge [:source :target]]
    :body [[:candidate-edge [:app :source :target]] [:visible [:app]]]}
   {:head [:edge [:cell :app]]
    :body [[:operator-use [:cell :app]] [:visible [:app]] [:producer [:cell :producer]]]}
   {:head [:edge [:source :target]]
    :body [[:copy-back [:target :source]] [:producer [:source :app]]]}])

(def network-rules
  (into (vec (butlast projection-rules))
        [{:head [:edge [:source :copy]]
          :body [[:copy-node [:copy :source :target]] [:producer [:source :app]]]}
         {:head [:edge [:copy :target]]
          :body [[:copy-node [:copy :source :target]] [:producer [:source :app]]]}]))

(def contraction-rules
  [{:head [:alias [:source :target]]
    :body [[:contractible-copy [:source :target]] [:primary-result [:source]]
            [:unnamed [:source]] [:named [:target]] [:sole-successor [:source :target]]]}])

(def binding-rules
  [{:head [:binding-alias [:source :target]]
    :body [[:definition [:source :copy :target]] [:source-cell [:source]]
           [:sole-consumer [:source :copy]]]}])

(def inout-port-rules
  [{:head [:port-alias [:port :caller :copy]]
    :body [[:member-output [:app :port]]
           [:return-copy [:app :port :copy :caller]]
           [:argument [:app :caller]]
           [:sole-consumer [:port :copy]]]}])

(def reciprocal-edge-rules
  [{:head [:reciprocal [:source :target]]
    :body [[:edge [:source :target]] [:reverse-edge [:source :target]]]}])

(def reachability-rules
  [{:head [:reachable [:node]] :body [[:seed [:node]]]}
   {:head [:reachable [:target]]
    :body [[:adjacent [:source :target]] [:reachable [:source]]]}
   ;; Undirected reachability implies the other endpoint is reachable too.
   {:head [:selected-edge [:source :target]]
    :body [[:edge [:source :target]] [:reachable [:source]]]}])

(def return-rules
  [{:head [:return-reachable [:node]] :body [[:return [:node]]]}
   {:head [:return-reachable [:source]]
    :body [[:predecessor [:target :source]] [:return-reachable [:target]]]}
   {:head [:return-edge [:source :target]]
    :body [[:predecessor [:target :source]] [:return-reachable [:target]]]}])

(defn run
  "Intern terms bijectively: Leapfrog's string comparator must never identify a
  symbol with a same-spelled string or conflate qualified runtime identities."
  [facts rules]
  (doseq [rule rules]
    (let [predicates (map first (:body rule))]
      (when-not (= (count predicates) (count (distinct predicates)))
        (throw (ex-info "Leapfrog rules require distinct predicate occurrences" {:rule rule})))))
  (let [domain (vec (distinct (mapcat identity (mapcat val facts))))
        ids (zipmap domain (range))
        encoded (into {} (map (fn [[predicate tuples]]
                               [predicate (into #{} (map #(mapv ids %)) tuples)])) facts)
        result (leapfrog/semi-naive encoded rules)]
    (into {} (map (fn [[predicate tuples]]
                    [predicate (into #{} (map #(mapv (partial nth domain) %)) tuples)])) result)))
