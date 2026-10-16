(ns propagators.membership-collection-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.message :refer [message]]
            [propagators.propagator :as prop]
            [propagators.stdlib.observation :as observation]
            [propagators.stdlib.supported-procedure :as supported]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.datastructures.support-collection :as legacy]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- p [source epoch status]
  {:source source :timestamp epoch :premises-status status})

(defn- project [content network]
  (let [v (merge/strongest-value content network)]
    (into {} (map #(vector % (datum/layer-value v %))) [:base :support :membership :premise-state])))

(defn- base [content network]
  (let [v (:base (project content network))]
    (if (evidence/evidence-set? v) (evidence/strongest v) v)))

(deftest dependency-shrink-remains-usable-under-the-generic-handler
  (let [[a b] (repeatedly 2 ids/new-node-id)
        n (nb/install-cells [a b])
        a1 (p a 1 :active) a2 (p a 2 :retracted) b1 (p b 1 :active)
        old (membership/content {:base (obj/compound-object [7 8])
                                 :support #{a1 b1} :membership #{a1 b1}})
        fresh (membership/content {:base (obj/compound-object [8])
                                   :support #{b1} :membership #{a2 b1}})
        joined (merge/cell-merge old fresh n)
        projected (project joined n)]
    (is (= 1 (obj/slot-value (base joined n) :count)))
    (is (= 8 (obj/slot-value (base joined n) 0)))
    (is (= #{b1} (:support projected)))
    (is (= #{a2 b1} (:membership projected)))
    (is (not (value/unusable? projected)))
    (is (= joined (merge/cell-merge joined old n)))
    (is (= 2 (count (:membership/observations joined))))))

(deftest merge-laws-and-arrival-order
  (let [[a b] (repeatedly 2 ids/new-node-id)
        a1 (p a 1 :active) a2 (p a 2 :retracted) b1 (p b 1 :active)
        updates [(membership/merge-content value/nothing value/nothing)
                 (membership/content {:base 7 :support #{a1} :membership #{}})
                 (membership/content {:base 8 :support #{b1} :membership #{a2}})
                 (membership/content {:base value/contradiction :support #{a1} :membership #{b1}})
                 (membership/content {:premise-state #{a2 b1}})]]
    (doseq [a updates]
      (is (= a (membership/merge-content a a)))
      (doseq [b updates]
        (is (= (membership/merge-content a b) (membership/merge-content b a)))
        (doseq [c updates]
          (is (= (membership/merge-content (membership/merge-content a b) c)
                 (membership/merge-content a (membership/merge-content b c)))))))))

(deftest absence-is-not-an-available-value
  (let [a (ids/new-node-id) ar (p a 2 :retracted)
        selected (membership/content {:base 8 :support #{} :membership #{ar}})
        required (membership/content {:base 8 :support #{ar} :membership #{}})]
    (is (= 8 (:base (project selected net/empty-net))))
    (is (value/nothing? (:base (project required net/empty-net))))))

(deftest current-conflicts-keep-provenance-and-recover
  (let [a (ids/new-node-id)
        problem (value/contradiction-with-provenance #{:sensor})
        old (membership/content {:base problem :support #{(p a 1 :active)} :membership #{}})
        fresh (membership/content {:base 9 :support #{(p a 2 :active)} :membership #{}})]
    (is (= problem (:base (project old net/empty-net))))
    (is (= 9 (:base (project (merge/cell-merge old fresh net/empty-net) net/empty-net))))))

(deftest stale-membership-and-same-version-state-conflicts-are-not-selected
  (let [a (ids/new-node-id) a1 (p a 1 :active) ar (p a 1 :retracted)
        old (membership/content {:base 7 :support #{} :membership #{a1}})
        newer (membership/content {:premise-state #{(p a 2 :retracted)}})
        conflict (membership/content {:premise-state #{ar}})]
    (doseq [update [newer conflict]]
      (is (value/nothing? (:base (project
                                 (membership/merge-content old update) net/empty-net)))))))

(defn- publish [n id update]
  (let [[tasks next] (core/eval-cell id (message id update) n)]
    (nb/run-propagators next tasks)))

(deftest identity-and-layered-computation-preserve-membership-guards
  (let [[a b source output copied] (repeatedly 5 ids/new-node-id)
        n (nb/install-cells [a b source output copied])
        [installed tasks _] (supported/install
                             (supported/from-primitive (prop/concrete-primitive-propagator inc))
                             n [source] output)
        [copy-id linked] ((observation/identity ::copy output copied) installed)
        guard (p a 2 :retracted) positive (p b 1 :active)
        initial (nb/run-propagators linked (conj tasks copy-id))
        after (publish initial source (membership/content {:base 8 :support #{positive} :membership #{guard}}))
        result (project (net/network-cell-content after copied) after)]
    (is (= 9 (:base result)))
    (is (= #{positive} (:support result)))
    (is (= #{guard} (:membership result)))
    (is (membership/content? (net/network-cell-content after copied)))
    (is (= after (nb/run-propagators after (conj tasks copy-id))))))

(deftest ordinary-slot-access-uses-membership-aware-transport
  (let [[owner slot] (repeatedly 2 ids/new-node-id)
        empty (membership/content {:premise-state #{}})
        n (nb/seed-cell (nb/install-cells [owner slot]) owner empty)
        [access installed] ((obj/p:slot :x slot owner) n)
        initial (nb/run-propagators installed [access])
        old (membership/content {:base (obj/compound-object {:x 10})
                                 :support #{(p owner 1 :active)} :membership #{}})
        fresh (membership/content {:base (obj/compound-object {}) :support #{}
                                   :membership #{(p owner 2 :retracted)}})
        seeded (publish initial owner old)
        removed (publish seeded owner fresh)
        replayed (publish removed slot (membership/content {:base 10 :support #{(p owner 1 :active)}
                                                            :membership #{}}))]
    (is (= 10 (datum/layer-value (net/network-cell-strongest seeded slot) :base)))
    (is (value/nothing? (datum/layer-value (net/network-cell-strongest removed slot) :base)))
    (is (= removed replayed))
    (is (= removed (nb/run-propagators removed [access])))))

(deftest mixed-value-and-membership-versions-block-computation
  (let [a (ids/new-node-id)]
    (is (value/any-unusable-values?
         {:base 7 :support #{(p a 1 :active)}}
         {:base 8 :support #{} :membership #{(p a 2 :retracted)}}))))

(deftest invalid-membership-data-is-rejected-at-the-boundary
  (doseq [datum [17 nil {:base 8 :support #{} :membership #{} :extra true}
                 {:base 8 :support #{:invalid} :membership #{}}]]
    (is (thrown? clojure.lang.ExceptionInfo (membership/content datum)))))

(deftest generic-mixed-evidence-merge-is-symmetric-and-idempotent
  (let [source (ids/new-node-id)
        old (legacy/content {:base 7 :support #{(p source 1 :active)}})
        fresh (membership/content {:base 9 :support #{(p source 2 :active)} :membership #{}})
        forward (merge/cell-merge old fresh net/empty-net)
        reverse (merge/cell-merge fresh old net/empty-net)]
    (is (= forward reverse))
    (is (membership/content? forward))
    (is (= 2 (count (:membership/observations forward))))
    (is (= 9 (:base (project forward net/empty-net))))
    (is (= forward (merge/cell-merge forward old net/empty-net)))
    (is (= forward (merge/cell-merge forward fresh net/empty-net)))
    (is (= forward (merge/cell-merge forward forward net/empty-net))))
  (let [a (legacy/content {:base 7 :support #{}})
        b (membership/content {:base 7 :support #{} :membership #{}})]
    (is (= (merge/cell-merge a b net/empty-net) (merge/cell-merge b a net/empty-net)))))

(deftest mixed-merge-trees-have-equivalent-current-projections
  (let [[a b] (repeatedly 2 ids/new-node-id)
        values [(legacy/content {:base 7 :support #{(p a 1 :active)}})
                (legacy/content {:base 9 :support #{(p a 2 :active)}})
                (membership/content {:base 9 :support #{(p a 2 :active)} :membership #{(p b 1 :retracted)}})
                (membership/content {:premise-state #{(p a 3 :retracted)}})]
        join #(merge/cell-merge %1 %2 net/empty-net)
        view #(project % net/empty-net)]
    (doseq [a values b values]
      (is (= (join a b) (join b a)))
      (doseq [c values]
        (is (= (view (join (join a b) c)) (view (join a (join b c)))))))))

(deftest conflicting-value-claims-remain-evidence-before-projection
  (let [source (ids/new-node-id) premise (p source 1 :active)
        a (legacy/content {:base 7 :support #{premise}})
        b (membership/content {:base 8 :support #{premise} :membership #{}})
        joined (merge/cell-merge a b net/empty-net)
        result (project joined net/empty-net)]
    (is (= 2 (count (:membership/observations joined))))
    (is (value/contradiction? (:base result)))
    (is (= #{premise} (:support result)))))

(deftest cell-merge-failures-return-explained-contradiction
  (let [valid (membership/content {:base 7 :support #{} :membership #{}})]
    (doseq [invalid [17 {:membership/observations :invalid :membership/states #{}}]]
      (doseq [[a b] [[valid invalid] [invalid valid]]]
        (let [result (merge/cell-merge a b net/empty-net)
              reason (first (value/contradiction-provenance result))]
          (is (value/contradiction? result))
          (is (= :cell/merge (:operation reason)))
          (is (= (set [a b]) (:operands reason)))
          (is (string? (:exception/message reason)))))))
  (doseq [error [(ex-info "rejected contribution" {:reason :test}) (AssertionError. "broken handler")]]
    (with-redefs [merge/built-in-cell-merge (fn [& _] (throw error))]
      (let [a (legacy/content {:base 7 :support #{}})
            b (legacy/content {:base 8 :support #{}})
            result (merge/cell-merge a b net/empty-net)
            reason (first (value/contradiction-provenance result))]
        (is (value/contradiction? result))
        (is (= (.getMessage error) (:exception/message reason)))
        (is (= (ex-data error) (:exception/data reason)))))))

(deftest explicit-contradiction-preserves-reasons-in-both-evidence-domains
  (let [problem (value/contradiction-with-provenance #{:original-cause})]
    (doseq [content [(legacy/content {:base 7 :support #{}})
                    (membership/content {:base 7 :support #{} :membership #{}})]]
      (is (= problem (merge/cell-merge content problem net/empty-net)))
      (is (= problem (merge/cell-merge problem content net/empty-net))))))
