(ns propagators.tms-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.datastructures.tms :as tms]
            [propagators.install :as i]
            [propagators.network :as net]
            [propagators.network-vm.flat :as fvm]
            [propagators.network-vm.flat.gur :as fgur]))

(defn- node-id
  [& parts]
  (fvm/stable-node-id (into [:tms-test] parts)))

(defn- strongest
  [n cell-id]
  (net/network-cell-strongest n cell-id))

(defn- apply-effects
  ([effects]
   (apply-effects (fvm/vm-net) effects))
  ([n effects]
   (let [[tasks n*] (core/eval-effects effects n)]
     (core/run-tasks tasks n*))))

(defn- merge-updates
  [& updates]
  (reduce (fn [content update]
            (merge/cell-merge content update net/empty-net))
          value/nothing
          updates))

(defn- reduced-view
  [content]
  (-> content
      (merge/strongest-value net/empty-net)
      reducer/reduced-result))

(defn- source-list-effects
  [run-key values]
  (let [values (vec values)
        len (count values)
        heads (mapv #(node-id run-key :head %) (range len))
        colls (mapv #(node-id run-key :coll %) (range len))
        terminal-id (node-id run-key :terminal)]
    {:root-id (first colls)
     :effects (vec
               (concat
                (mapv fvm/declare-cell (concat heads colls [terminal-id]))
                (mapv (fn [idx]
                        (fvm/install-topology
                         [run-key :cons idx]
                         (obj/p:cons (heads idx)
                                     (if (= idx (dec len))
                                       terminal-id
                                       (colls (inc idx)))
                                     (colls idx))))
                      (range len))
                (map-indexed (fn [idx v]
                               (fvm/tell (heads idx) v))
                             values)
                [(fvm/tell terminal-id value/nothing)]))}))

(def collect-tms-claims
  (fgur/recursive-declaration
   'collect-tms-claims
   (fn [ctx [claims-id tms-id] out-id]
     (-> ctx
         (i/$ {:claims claims-id
               :tms tms-id
               :out out-id})
         (i/car :claim :claims)
         (i/cdr :rest :claims)
        (i/reducer-slot :truth
                         tms/reducer-net
                         [:claim (:scope ctx)]
                         :claim
                         :tms)
         (i/tell :out true)
         (i/when :rest
           (i/recur [:rest :tms] :out))))))

(deftest tms-view-activates-supported-claims
  (let [view (tms/tms-view
              {(tms/claim-slot-key :c1)
               (tms/claim :c1 :answer 10 #{:a})
               (tms/premise-slot-key :a 0)
               (tms/premise-state :a 0 true)})]
    (is (= #{:a} (tms/active-premises view)))
    (is (= 10 (tms/proposition-value view :answer)))))

(deftest tms-reducer-cell-projects-active-conflict
  (let [content (merge-updates
                 (tms/premise-update :truth :a 0 true)
                 (tms/premise-update :truth :b 0 true)
                 (tms/claim-update :truth
                                   (tms/claim :c1 :answer 10 #{:a}))
                 (tms/claim-update :truth
                                   (tms/claim :c2 :answer 20 #{:b})))
        view (reduced-view content)
        entry (tms/proposition-entry-for view :answer)]
    (is (= #{:a :b} (tms/active-premises view)))
    (is (= :conflict (:tms/status entry)))
    (is (= value/contradiction
           (tms/proposition-value view :answer)))))

(deftest later-premise-state-changes-tms-strongest-without-deleting-content
  (let [content (merge-updates
                 (tms/premise-update :truth :a 0 true)
                 (tms/claim-update :truth
                                   (tms/claim :c1 :answer 10 #{:a})))
        before (merge/strongest-value content net/empty-net)
        content* (merge/cell-merge content
                                   (tms/premise-update :truth :a 1 false)
                                   net/empty-net)
        after (merge/strongest-value content* net/empty-net)]
    (is (= 10 (tms/proposition-value (reducer/reduced-result before)
                                     :answer)))
    (is (value/nothing?
         (tms/proposition-value (reducer/reduced-result after)
                                :answer)))
    (is (not= (reducer/reduced-epoch before)
              (reducer/reduced-epoch after)))))

(deftest installer-helpers-project-current-active-value
  (let [n (-> (i/context net/empty-net [:tms-installer])
              (i/tell :active true)
              (i/tell :claim-value 42)
              (i/tms-premise :truth :a 0 :active :tms)
              (i/tms-claim :truth :c1 :answer #{:a} :claim-value :tms)
              (i/tms-proposition :answer :tms :out)
              (i/run))
        out-id (i/cell-id (i/context n [:tms-installer]) :out)]
    (is (= 42 (strongest n out-id)))))

(deftest recursive-linked-list-claims-feed-tms-reducer-without-materializing-list
  (let [run-key [:recursive-claims]
        claims [(tms/claim :c1 :answer 10 #{:a})
                (tms/claim :c2 :answer 20 #{:b})]
        {:keys [root-id effects]} (source-list-effects run-key claims)
        closure-id (node-id run-key :closure)
        tms-id (node-id run-key :tms)
        out-id (node-id run-key :out)
        a-active-id (node-id run-key :a-active)
        b-active-id (node-id run-key :b-active)
        n (apply-effects
           (vec (concat [(fvm/declare-cell closure-id)
                         (fvm/declare-cell tms-id)
                         (fvm/declare-cell out-id)
                         (fvm/declare-cell a-active-id)
                         (fvm/declare-cell b-active-id)
                         (fvm/tell closure-id collect-tms-claims)
                         (fvm/tell a-active-id true)
                         (fvm/tell b-active-id false)
                         (fvm/install-topology [run-key :premise :a]
                                               (tms/p:tms-premise :truth
                                                                  :a
                                                                  0
                                                                  a-active-id
                                                                  tms-id))
                         (fvm/install-topology [run-key :premise :b]
                                               (tms/p:tms-premise :truth
                                                                  :b
                                                                  0
                                                                  b-active-id
                                                                  tms-id))]
                        effects
                        [(fgur/apply-closure-effect closure-id
                                                    [root-id tms-id]
                                                    out-id)])))
        view (-> (strongest n tms-id) reducer/reduced-result)
        b-active-late-id (node-id run-key :b-active-late)
        n* (apply-effects n [(fvm/declare-cell b-active-late-id)
                             (fvm/tell b-active-late-id true)
                             (fvm/install-topology [run-key :premise :b :late]
                                                   (tms/p:tms-premise :truth
                                                                      :b
                                                                      1
                                                                      b-active-late-id
                                                                      tms-id))])
        view* (-> (strongest n* tms-id) reducer/reduced-result)]
    (is (= 10 (tms/proposition-value view :answer)))
    (is (= value/contradiction
           (tms/proposition-value view* :answer)))))

(deftest raw-tms-projection-does-not-claim-to-retract-ordinary-output
  (is (re-find #"not retracted"
               (slurp "propagators/datastructures/tms.clj"))))
