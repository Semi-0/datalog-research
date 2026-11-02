(ns propagators.semantic-trace.capture-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.ids :as ids]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.session.clock :as clock]
            [propagators.datastructures.compound-object :as object]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.visualization.data :as data]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as builder]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.merge :as tracer-merge]
            [propagators.semantic-trace.observation :as tracer]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.network-patch :as patch]
            [propagators.relationship-observer :as roots]
            [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.semantic-trace.retention :as retention]
            [propagators.compiler-2.runtime.ids :as runtime-ids]))

(defn- fragment [observation facts]
  (capture/fragment
   {:observation observation
    :scopes {:root {:nodes {:n :node}
                    :node-kinds {:n :cell}
                    :values {:n facts}
                    :edges []}}}))

(deftest capture-join-is-an-aci-information-join
  (let [a (fragment :observation {:a 1})
        b (fragment :observation {:b 2})
        c (fragment :observation {:c 3})]
    (is (= a (capture/join a a nil)))
    (is (= a (capture/join value/nothing a nil)))
    (is (= a (capture/join a value/nothing nil)))
    (is (= (capture/join a b nil) (capture/join b a nil)))
    (is (= (capture/join (capture/join a b nil) c nil)
           (capture/join a (capture/join b c nil) nil)))))

(deftest conflicts-are-local-and-order-independent
  (let [a (fragment :observation {:slot 1})
        b (fragment :observation {:slot 2})
        c (fragment :observation {:slot 3})
        left (capture/join (capture/join a b nil) c nil)
        right (capture/join a (capture/join c b nil) nil)
        conflict (get-in (capture/scope left :root) [:values :n])]
    (is (= left right))
    (is (capture/fragment? left))
    (is (value/contradiction? conflict))
    (is (not (value/contradiction? left))))
  (is (value/contradiction?
       (capture/join (fragment :first {:a 1})
                     (fragment :second {:a 1})
                     nil))))

(deftest capture-join-accumulates-topology-without-merging-payload-shapes
  (let [left (capture/fragment
              {:observation :observation
               :scopes {:root {:nodes {:a :a :b :b}
                               :values {:a {:payload/a 1}}
                               :edges [[:a :b]]}}})
        right (capture/fragment
               {:observation :observation
                :scopes {:root {:nodes {:a :a :b :b :c :c}
                                :values {:a {:payload/b 2}}
                                :edges [[:b :c]]}}})
        joined (capture/join left right nil)]
    (is (= #{[:a :b] [:b :c]}
           (set (:edges (capture/scope joined :root)))))
    (is (value/contradiction?
         (get-in (capture/scope joined :root) [:values :a])))))

(deftest frozen-capture-retains-public-slot-cells-outside-visible-topology
  (let [root (ids/new-node-id)
        slot-parent (ids/new-node-id)
        accessor (object/accessor-declaration :car slot-parent)
        network (-> net/empty-net
                    (builder/install-cell root accessor accessor)
                    (builder/install-cell slot-parent 42 42))
        graph {:semantic-trace/graph true
               :nodes {[[:outer] root] :root}
               :node-kinds {[[:outer] root] :cell}
               :values {[[:outer] root] accessor}
               :edges []}
        source (observation/frozen-snapshot
                network {:observation/id :frozen})
        captured (capture/capture-fragment
                  source {:observation :frozen :graph graph :scope :root})]
    (is (= 42 (get-in (capture/scope captured :root)
                      [:values [[:outer] slot-parent]])))
    (is (= #{[[:outer] root]}
           (set (keys (:nodes (capture/scope captured :root)))))
        "Slot cells are captured as structural facts, not rendered nodes")))

(deftest frozen-slot-cycles-and-shared-references-preserve-identities
  (let [root (ids/new-node-id) other (ids/new-node-id)
        root-value (object/accessor-declaration :cdr other)
        other-value (object/accessor-declaration :cdr root)
        network (-> net/empty-net
                    (builder/install-cell root root-value root-value)
                    (builder/install-cell other other-value other-value))
        source (observation/frozen-snapshot network {:observation/id :cyclic})
        graph {:semantic-trace/graph true :nodes {[[:outer] root] 'root}
               :node-kinds {[[:outer] root] :cell}
               :values {[[:outer] root] root-value} :edges []}
        request {:observation :cyclic :graph graph :scope :root}
        captured (capture/capture-fragment source request)
        newer (builder/install-cell network other 99 99)]
    (is (= #{[[:outer] root] [[:outer] other]}
           (set (keys (:values (capture/scope captured :root))))))
    (is (= other-value (get-in (capture/scope captured :root) [:values [[:outer] other]])))
    (is (= 99 (net/network-cell-strongest newer other)))
    (is (= captured (capture/capture-fragment source request))
        "Later live state cannot repair or replace historical evidence")))


(defn- settle [network result]
  (let [[tasks installed] (declarations/eval-activation-result result network)]
    (runner/completed-network (runner/run-network tasks installed))))

(defn- evidence [captured premises]
  (tracer-merge/fragment-evidence captured premises premises))

(deftest capture-representation-and-ordinary-runner-composition
  (let [output (ids/new-node-id)
        a (fragment :same {:a 1}) b (fragment :same {:a 1})
        more (capture/fragment {:observation :same :scopes {:child {:nodes {:x 'x}}}})
        base net/empty-net
        run #(settle base [(tracer-merge/submit output :first (evidence a #{}))
                           (tracer-merge/submit output :second (evidence more #{}))])
        jobs [(future (run)) (future (run))]]
    (is (capture/fragment? a))
    (is (= :same (capture/observation-id a)))
    (is (nil? (:semantic-trace/graph a)))
    (is (= a b))
    (doseq [job jobs]
      (let [network @job
            joined (data/payload (net/network-cell-strongest network output))]
        (is (= (capture/scopes (capture/join a more nil)) (capture/scopes joined)))
        (is (= network (settle network (tracer-merge/submit output :second (evidence more #{})))))
        (is (= network (runner/completed-network
                        (runner/run-network (keep (fn [[id p]] (when (prop/prop? p) id))
                                                  (net/net-env network)) network))))))))

(deftest fragment-order-duplicates-and-observation-separation
  (let [a (fragment :first 1)
        b (capture/fragment {:observation :first :scopes {:child {:nodes {:b 'b}}}})
        c (fragment :second 2)
        project (fn [fragments]
                  (tracer-merge/joined-groups
                   (mapv (fn [v] (collection/strongest-value (evidence v #{})
                                  (fn [a b] (capture/join a b nil)))) fragments)))]
    (is (= (project [a b]) (project [b a a])))
    (let [groups (:groups (project [a b c]))]
      (is (= #{:first :second} (set (keys groups))))
      (is (= (capture/join a b nil) (get-in groups [:first :capture])))
      (is (= c (get-in groups [:second :capture]))))))

(deftest ttms-retraction-recovery-and-incomparable-support
  (let [output (ids/new-node-id) source (ids/new-node-id) unrelated (ids/new-node-id)
        premise (fn [epoch status] {:source source :timestamp epoch :premises-status status})
        a (fragment :first 1) b (fragment :second 2)
        initial (settle net/empty-net
                        (tracer-merge/submit output :root (evidence a #{(premise 1 :active)})))
        withdrawn (settle initial
                          (message/message (tracer-merge/fragment-cell-id (tracer-merge/inbox-id output) :root)
                                           (collection/content {:premise-state #{(premise 2 :retracted)}})))
        recovered (settle withdrawn
                         (tracer-merge/submit output :new-root (evidence b #{(premise 3 :active)})))
        read #(net/network-cell-strongest % output)]
    (is (capture/equivalent? a (data/payload (read initial))))
    (is (value/nothing? (data/payload (read withdrawn))))
    (is (capture/equivalent? b (data/payload (read recovered))))
    (is (contains? (datum/layer-value (read withdrawn) :premise-state) (premise 2 :retracted)))
    (is (not (contains? (datum/support-of (read recovered)) (premise 1 :active))))
    (let [other (settle recovered
                        (tracer-merge/submit output :independent
                                             (evidence a #{{:source unrelated :timestamp 1 :premises-status :active}})))]
      (is (value/contradiction? (data/payload (read other))))
      (is (= #{source unrelated output} (set (map :source (datum/support-of (read other)))))))))

(deftest equal-version-fragment-conflict-stays-local
  (let [out (ids/new-node-id) source (ids/new-node-id)
        premises #{{:source source :timestamp 1 :premises-status :active}}
        network (settle net/empty-net
                        [(tracer-merge/submit out :left (evidence (fragment :one 1) premises))
                         (tracer-merge/submit out :right (evidence (fragment :one 2) premises))])
        captured (data/payload (net/network-cell-strongest network out))]
    (is (capture/fragment? captured))
    (is (value/contradiction? (get-in (capture/scope captured :root) [:values :n])))))

(deftest dynamically-declared-descendants-observe-multiple-generations
  (let [root (ids/new-node-id) direction (ids/new-node-id) output (ids/new-node-id)
        parent (ids/new-node-id) child (ids/new-node-id) grandchild (ids/new-node-id)
        leaf (ids/new-node-id)
        [_ network] ((prop/construct-propagator
                      parent :parent
                      (fn [& _]
                        [(patch/declare-propagator
                          child :child [root] [leaf]
                          (fn [& _]
                            [(patch/declare-cell leaf)
                             (patch/declare-propagator
                              grandchild :grandchild [root] [leaf]
                              (fn [& _] [(message/message leaf 42)]))]))])
                      [root] [leaf])
                     (-> net/empty-net (builder/install-cell root 1 1)
                         (builder/install-cell leaf)
                         (builder/install-cell direction :both :both)))
        [installed ids _] (tracer/install-observer network root direction output)
        network (runner/completed-network (runner/run-network (conj ids parent) installed))
        captured (data/payload (net/network-cell-strongest network output))]
    (is (= 42 (net/network-cell-strongest network leaf)))
    (is (capture/fragment? captured))
    (let [request (first (filter #(= (capture/observation-id captured)
                                   (get-in % [:boundary/payload :observation]))
                                 (effects/outbox-effects network)))
          frozen (get-in request [:boundary/payload :source])]
      (is (= 42 (observation/snapshot-value frozen leaf)))
      (is (seq (:node/inputs (observation/snapshot-neighbors frozen grandchild)))))
    (is (<= 3 (second (capture/observation-id captured))))
    (is (= network (runner/completed-network (runner/run-network [parent child grandchild] network))))))

(deftest same-slot-equal-version-conflicts-and-recovery
  (let [out (ids/new-node-id) source (ids/new-node-id)
        premise (fn [epoch] #{{:source source :timestamp epoch :premises-status :active}})
        a (fragment :one 1) b (fragment :one 2)
        initial (settle net/empty-net (tracer-merge/submit out :slot (evidence a (premise 1))))
        conflict (settle initial (tracer-merge/submit out :slot (evidence b (premise 1))))
        recovered (settle conflict (tracer-merge/submit out :slot (evidence b (premise 2))))]
    (is (value/contradiction? (data/payload (net/network-cell-strongest conflict out))))
    (is (capture/equivalent? b (data/payload (net/network-cell-strongest recovered out))))))

(deftest asynchronous-clock-acquisition-uses-the-standard-runner
  (let [sessions [(runtime/new-session) (runtime/new-session)]
        root (ids/new-node-id) direction (ids/new-node-id) out (ids/new-node-id)
        subscription :capture-clock]
    (try
      (doseq [session sessions]
        (runtime/install-session-extension!
         session retention/extension {:outbox-id (runtime-ids/boundary-outbox-id)})
        (let [base (-> (:program/net @session)
                       (builder/install-cell root)
                       (builder/install-cell direction :both :both))
              [installed _ _] (tracer/install-observer base root direction out)]
          (swap! session assoc :program/net installed
                 :clock/subscriptions {subscription {:clock/id subscription :clock/target-id root}})))
      (let [jobs (mapv #(future (clock/tick-clock! % subscription (constantly 1000))) sessions)]
        (doseq [job jobs] @job))
      (doseq [session sessions]
        (let [network (:program/net @session)
              captured (data/payload (net/network-cell-strongest network out))]
          (is (capture/fragment? captured))
          (is (= 1 (get-in @session [:clock/epochs subscription])))
          (is (= 1 (count @(get @session retention/storage-key))))
          (is (nil? (net/network-dict-entry network [:runner :network-iterator])))))
      (is (not (identical? (get @(first sessions) retention/storage-key)
                          (get @(second sessions) retention/storage-key))))
      (finally (doseq [session sessions] (runtime/stop-clocks! session))))))

(deftest acquisition-transports-direction-versions-and-withdrawal
  (let [root (ids/new-node-id) direction (ids/new-node-id) out (ids/new-node-id)
        source (ids/new-node-id)
        premise (fn [epoch status] #{{:source source :timestamp epoch :premises-status status}})
        direction-value (fn [epoch] (collection/content {:base :both :support (premise epoch :active)
                                                         :premise-state (premise epoch :active)}))
        [installed tasks _] (tracer/install-observer
                             (-> net/empty-net (builder/install-cell root 7 7)
                                 (builder/install-cell direction)) root direction out)
        initial (settle installed (message/message direction (direction-value 1)))
        advanced (settle initial (message/message direction (direction-value 2)))
        withdrawn (settle advanced (message/message direction
                                                   (collection/content {:premise-state (premise 3 :retracted)})))
        recovered (settle withdrawn (message/message direction (direction-value 4)))
        read #(net/network-cell-strongest % out)]
    (is (capture/fragment? (data/payload (read initial))))
    (is (contains? (datum/support-of (read advanced)) (first (premise 2 :active))))
    (is (not (contains? (datum/support-of (read advanced)) (first (premise 1 :active)))))
    (is (value/nothing? (data/payload (read withdrawn))))
    (is (capture/fragment?
         (tracer-merge/joined-observation withdrawn (tracer-merge/inbox-id out)
                                          (capture/observation-id (data/payload (read advanced))))))
    (is (capture/fragment? (data/payload (read recovered))))
    (is (contains? (datum/support-of (read recovered)) (first (premise 4 :active))))
    (is (= recovered (runner/completed-network (runner/run-network tasks recovered))))))

(deftest source-load-refresh-composes-observation-onto-new-declaring-parents
  (let [root (ids/new-node-id) direction (ids/new-node-id) out (ids/new-node-id)
        parent (ids/new-node-id) child (ids/new-node-id) leaf (ids/new-node-id)
        [installed tasks _] (tracer/install-observer
                             (-> net/empty-net (builder/install-cell root 1 1)
                                 (builder/install-cell direction :both :both)) root direction out)
        initial (runner/completed-network (runner/run-network tasks installed))
        loaded (settle initial
                       (patch/declare-propagator
                        parent :loaded-parent [root] [leaf]
                        (fn [& _]
                          [(patch/declare-propagator child :loaded-child [root] [leaf]
                                                     (fn [& _] [(message/message leaf 99)]))])))
        [watches refreshed] (roots/refresh-roots loaded)
        final (runner/completed-network (runner/run-network (conj (vec watches) parent) refreshed))]
    (is (= 99 (net/network-cell-strongest final leaf)))
    (is (> (second (capture/observation-id (data/payload (net/network-cell-strongest final out))))
           (second (capture/observation-id (data/payload (net/network-cell-strongest initial out))))))
    (is (capture/fragment? (data/payload (net/network-cell-strongest final out))))))

(deftest a-declared-cell-without-a-new-propagator-retains-its-frozen-value
  (let [root (ids/new-node-id) direction (ids/new-node-id) out (ids/new-node-id)
        parent (ids/new-node-id) leaf (ids/new-node-id)
        [_ base] ((prop/construct-propagator
                   parent :cell-declarer
                   (fn [& _] [(patch/declare-cell leaf) (message/message leaf 42)])
                   [root] [])
                  (-> net/empty-net (builder/install-cell root 1 1)
                      (builder/install-cell direction :both :both)))
        [installed tasks _] (tracer/install-observer base root direction out)
        network (runner/completed-network (runner/run-network (conj tasks parent) installed))
        captured (data/payload (net/network-cell-strongest network out))
        request (first (filter #(= (capture/observation-id captured)
                                  (get-in % [:boundary/payload :observation]))
                               (effects/outbox-effects network)))
        frozen (get-in request [:boundary/payload :source])]
    (is (capture/fragment? captured))
    (is (= 42 (observation/snapshot-value frozen leaf)))
    (is (= network (runner/completed-network (runner/run-network [parent] network))))))
