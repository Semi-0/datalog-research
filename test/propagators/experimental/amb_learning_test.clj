(ns propagators.experimental.amb-learning-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.experimental.amb.contracts :as c]
            [propagators.experimental.amb.learning :as l]
            [propagators.experimental.amb.policy :as p]
            [propagators.ids :as ids]))

(defn record [source] (c/initial-choice source [1 2]))
(defn nogood [literals] {:literals (set literals) :context #{}})

(deftest declaration-validation
  (let [a (ids/new-node-id)]
    (doseq [domain [[] [0 1] [1 1] [1 "2"]]]
      (is (thrown? clojure.lang.ExceptionInfo (c/initial-choice a domain))))
    (is (thrown? clojure.lang.ExceptionInfo (c/choices [(record a) (record a)])))))

(deftest learning-is-logical-and-idempotent
  (let [[a b] (repeatedly 2 ids/new-node-id)
        pair (nogood [[a 0] [b 0]]) single (nogood [[a 0]])]
    (is (= #{single} (l/minimize #{pair single})))
    (is (= #{pair} (l/minimize [pair pair])))
    (is (l/blocked? #{pair} #{[a 0] [b 0]} #{}))
    (is (not (l/blocked? #{pair} #{[a 0] [b 1]} #{})))))

(deftest failure-versions-and-context-are-exact
  (let [[a external] (repeatedly 2 ids/new-node-id)
        current (record a)
        ctx {:source external :timestamp 1 :premises-status :active}
        failure {:status :failed :support #{(c/premise current) ctx}}]
    (is (= {:literals #{[a 0]} :context #{ctx}}
           (l/certificate->nogood failure [current] #{ctx})))
    (is (nil? (l/certificate->nogood failure [(assoc current :timestamp 2)] #{ctx})))
    (is (nil? (l/certificate->nogood failure [current] #{(assoc ctx :timestamp 2)})))
    (is (nil? (l/certificate->nogood {:status :pending} [current] #{})))
    (is (nil? (l/certificate->nogood
               (update failure :support conj (assoc ctx :timestamp 2)) [current] #{ctx})))))

(deftest exhaustion-resolves-without-own-choice
  (let [[a b] (repeatedly 2 ids/new-node-id)
        knowledge #{(nogood [[a 0] [b 0]]) (nogood [[a 0] [b 1]])}
        closed (l/closure knowledge [(record a) (record b)])]
    (is (= #{(nogood [[a 0]])} closed))
    (is (= #{(nogood [])}
           (l/closure (conj closed (nogood [[a 1]])) [(record a) (record b)])))
    (is (= #{} (l/resolve-choice #{(nogood [[a 0] [b 0]])} (record b))))))

(deftest repair-handshake-replay-and-unrelated-choice
  (let [[a b other] (repeatedly 3 ids/new-node-id)
        records [(record a) (record b) (record other)]
        knowledge #{(nogood [[a 0] [b 0]])}
        withdraw (p/decide records knowledge nil #{})
        withdrawn (p/advance-choice (record b) withdraw)
        choose (p/decide [(record a) withdrawn (record other)] knowledge withdraw #{})
        chosen (p/advance-choice withdrawn choose)]
    (is (= b (:target withdraw)))
    (is (= :retracted (:status withdrawn)))
    (is (= :choose (:action choose)))
    (is (= [1 3 :active] ((juxt :index :timestamp :status) chosen)))
    (is (= chosen (p/advance-choice chosen choose)))
    (is (= chosen (p/advance-choice chosen withdraw)))
    (is (= (record other) (p/advance-choice (record other) withdraw)))
    (is (= {:action :idle}
           (p/decide [(record a) chosen (record other)] knowledge choose #{})))
    (is (= {:action :unsatisfiable}
           (p/decide records #{(nogood [])} nil #{})))))
