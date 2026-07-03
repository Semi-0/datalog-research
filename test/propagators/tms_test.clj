(ns propagators.tms-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.behavior :as behavior]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.datastructures.tms :as tms]
            [propagators.gur.subenv.env :as env]
            [propagators.install :as i]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.gur.flat :as fvm]
            [propagators.gur.flat :as fgur]
            [propagators.scoped-address :as scoped]))

(defn- node-id
  [& parts]
  (fvm/stable-node-id (into [:tms-test] parts)))

(defn- strongest
  [n cell-id]
  (net/network-cell-strongest n cell-id))

(defn- topology
  [install-key cell-ids installer]
  (i/installer-effects (fvm/vm-net) install-key cell-ids installer))

(defn- apply-effects
  ([effects]
   (apply-effects (fvm/vm-net) effects))
  ([n effects]
   (let [[tasks n*] (core/eval-activation-result effects n)]
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

(defn- support
  ([premise]
   (support premise [:premise premise]))
  ([premise source]
   (tms/support premise source :test/support)))

(defn- behavior-current-value
  [behavior-value]
  (let [summary (behavior/strongest-value behavior-value)]
    (if (value/unusable? summary)
      summary
      (obj/slot-value summary behavior/base-layer))))

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
                        (topology
                         [run-key :cons idx]
                         [(heads idx)
                          (if (= idx (dec len))
                            terminal-id
                            (colls (inc idx)))
                          (colls idx)]
                         (obj/p:cons
                          (heads idx)
                          (if (= idx (dec len))
                            terminal-id
                            (colls (inc idx)))
                          (colls idx))))
                      (range len))
                (map-indexed (fn [idx v]
                               (message (heads idx) v))
                             values)
                [(message terminal-id value/nothing)]))}))

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
                         tms/merge-net
                         tms/strongest-net
                         [:claim (:scope ctx)]
                         :claim
                         :tms)
         (i/tell :out true)
         (i/when :rest
           (i/recur [:rest :tms] :out))))))

(deftest tms-view-activates-supported-claims
  (let [raw-slots {(tms/claim-slot-key :c1)
                (tms/claim :c1 :answer 10 [(support :a)])
                   (tms/premise-slot-key :a 0)
                   (tms/premise-state :a 0 true)}
        raw-view (tms/tms-view raw-slots)
        slots (tms/merge-slots
               {}
               raw-slots)
        view (tms/tms-view slots)]
    (is (value/nothing? (tms/proposition-value raw-view :answer))
        "TMS strongest consumes merge-normalized latest-premise slots only.")
    (is (= #{:a} (tms/active-premises view)))
    (is (= (tms/premise-state-data (tms/premise-state :a 0 true))
           (tms/premise-state-data
            (get slots (tms/latest-premise-slot-key :a)))))
    (is (= 10 (tms/proposition-value view :answer)))))

(deftest tms-facts-are-compound-objects-with-slotful-supports
  (let [source (scoped/name-ref [:scope :child] 'x)
        s (support :a source)
        c (tms/claim :c1 :answer 10 [s])
        p (tms/premise-state :a 0 true)]
    (is (= #{:tms/kind :tms/claim-id :tms/proposition :tms/value :tms/supports}
           (obj/public-slot-keys c)))
    (is (= #{:tms/kind :support/premise :support/source :support/kind}
           (obj/public-slot-keys s)))
    (is (= #{:tms/kind :tms/premise :tms/epoch :tms/active?}
           (obj/public-slot-keys p)))
    (is (tms/claim? c))
    (is (tms/support? s))
    (is (tms/premise-state? p))
    (is (= :c1 (tms/claim-id c)))
    (is (= [s] (tms/support-objects c)))
    (is (= #{:a} (tms/supports c)))
    (is (= source (tms/support-source s)))))

(deftest compound-support-source-reuses-subenv-routing-address
  (let [scope [:scope :child]
        local-id (node-id :routing :local)
        owner-id (node-id :routing :owner)
        source (scoped/name-ref scope 'x)
        s (support :a source)
        child-net (-> net/empty-net
                      (nb/ensure-cell local-id)
                      (env/extend-env scope)
                      (env/bind 'x local-id))
        directory (env/register-subenv-from-owner net/empty-net
                                                  owner-id
                                                  child-net)]
    (is (= source (tms/support-source s)))
    (is (= [:dispatch/subenv owner-id local-id]
           (env/resolve-dispatch directory source net/empty-net)))))

(deftest tms-reducer-cell-projects-active-conflict
  (let [content (merge-updates
                 (tms/premise-update :truth :a 0 true)
                 (tms/premise-update :truth :b 0 true)
                 (tms/claim-update :truth
                                   (tms/claim :c1 :answer 10 [(support :a)]))
                 (tms/claim-update :truth
                                   (tms/claim :c2 :answer 20 [(support :b)])))
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
                                   (tms/claim :c1 :answer 10 [(support :a)])))
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

(deftest tms-selects-between-behavior-valued-claims
  (let [left (behavior/latest-value 0 :left #{[:definition :left]})
        right (behavior/latest-value 0 :right #{[:definition :right]})
        content (merge-updates
                 (tms/premise-update :truth :left 0 true)
                 (tms/premise-update :truth :right 0 false)
                 (tms/claim-update :truth
                                   (tms/claim :left-behavior
                                              :behavior
                                              left
                                              [(support :left)]))
                 (tms/claim-update :truth
                                   (tms/claim :right-behavior
                                              :behavior
                                              right
                                              [(support :right)])))
        left-view (reduced-view content)
        content* (merge-updates content
                                (tms/premise-update :truth :left 1 false)
                                (tms/premise-update :truth :right 1 true))
        right-view (reduced-view content*)
        content** (merge-updates content*
                                 (tms/premise-update :truth :left 2 true))
        conflict-view (reduced-view content**)]
    (is (= :left
           (behavior-current-value
            (tms/proposition-value left-view :behavior))))
    (is (= :right
           (behavior-current-value
            (tms/proposition-value right-view :behavior))))
    (is (= value/contradiction
           (tms/proposition-value conflict-view :behavior)))
    (is (= #{(tms/claim-slot-key :left-behavior)
             (tms/claim-slot-key :right-behavior)
             (tms/premise-slot-key :left 0)
             (tms/premise-slot-key :left 1)
             (tms/premise-slot-key :left 2)
             (tms/premise-slot-key :right 0)
             (tms/premise-slot-key :right 1)
             (tms/latest-premise-slot-key :left)
             (tms/latest-premise-slot-key :right)}
           (set (keys (reducer/reducer-slots content**)))))))

(deftest premise-source-cell-feeds-tms-and-later-epoch-retracts-projection
  (let [premise-id (node-id :premise-source :premise)
        active-id (node-id :premise-source :active)
        inactive-id (node-id :premise-source :inactive)
        tms-id (node-id :premise-source :tms)
        tms-cell (tms/tms-cell :truth)
        claim (tms/claim :c1 :answer 42 [(support :dynamic)])
        n0 (-> net/empty-net
               (nb/install-cell premise-id :dynamic :dynamic)
               (nb/install-cell active-id true true)
               (nb/install-cell inactive-id)
               (nb/install-cell tms-id tms-cell (reducer/strongest tms-cell)))
        [claim-tasks n1] (core/eval-cell tms-id
                                          (message tms-id
                                                   (tms/claim-update :truth
                                                                     claim))
                                          n0)
        n2 (core/run-tasks claim-tasks n1)
        [active-prop n3] ((tms/p:tms-premise-source :truth
                                                     premise-id
                                                     0
                                                     active-id
                                                     tms-id)
                          n2)
        [inactive-prop n4] ((tms/p:tms-premise-source :truth
                                                       premise-id
                                                       1
                                                       inactive-id
                                                       tms-id)
                            n3)
        n5 (nb/run-propagators n4 [active-prop inactive-prop])
        active-view (-> (strongest n5 tms-id) reducer/reduced-result)
        [inactive-tasks n6] (core/eval-cell inactive-id
                                            (message inactive-id false)
                                            n5)
        n7 (core/run-tasks inactive-tasks n6)
        inactive-view (-> (strongest n7 tms-id) reducer/reduced-result)]
    (is (= #{:dynamic} (tms/active-premises active-view)))
    (is (= 42 (tms/proposition-value active-view :answer)))
    (is (value/nothing? (tms/proposition-value inactive-view :answer)))
    (is (= #{(tms/claim-slot-key :c1)
             (tms/premise-slot-key :dynamic 0)
             (tms/premise-slot-key :dynamic 1)
             (tms/latest-premise-slot-key :dynamic)}
           (set (keys (reducer/reducer-slots
                       (net/network-cell-content n7 tms-id))))))))

(deftest installer-helpers-project-current-active-value
  (let [n (-> (i/context net/empty-net [:tms-installer])
              (i/tell :active true)
              (i/tell :claim-value 42)
              (i/tms-premise :truth :a 0 :active :tms)
              (i/tms-claim :truth :c1 :answer [(support :a)] :claim-value :tms)
              (i/tms-proposition :answer :tms :out)
              (i/run))
        out-id (i/cell-id (i/context n [:tms-installer]) :out)]
    (is (= 42 (strongest n out-id)))))

(deftest recursive-linked-list-claims-feed-tms-reducer-without-materializing-list
  (let [run-key [:recursive-claims]
        claims [(tms/claim :c1 :answer 10 [(support :a)])
                (tms/claim :c2 :answer 20 [(support :b)])]
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
                         (message closure-id collect-tms-claims)
                         (message a-active-id true)
                         (message b-active-id false)
                         (topology [run-key :premise :a]
                                   [a-active-id tms-id]
                                   (tms/p:tms-premise :truth
                                                      :a
                                                      0
                                                      a-active-id
                                                      tms-id))
                         (topology [run-key :premise :b]
                                   [b-active-id tms-id]
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
                             (message b-active-late-id true)
                             (topology [run-key :premise :b :late]
                                       [b-active-late-id tms-id]
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
