(ns propagators.semantic-trace.capture-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cell-evaluator :as evaluator]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.ids :as ids]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.session.state :as state]
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
            [propagators.semantic-trace.session-runner :as session-runner]))

(deftest supported-capture-survives-compiler-rewrapping
  (let [source (ids/new-node-id)
        captured (capture/fragment {:observation :original :scopes {:root {:nodes {:x 'x} :edges []}}})
        evidence (collection/content {:base captured
                                      :support #{{:source source :timestamp 1 :premises-status :active}}})
        publication (capture/publication evidence)
        rewrapped (collection/content publication)
        combined (session-runner/combined-capture-merge)
        output (ids/new-node-id)
        evaluate (evaluator/make-cell-evaluator combined)
        [_ settled] (evaluate (message/message output rewrapped) (builder/install-cell net/empty-net output))]
    (is (= #{:base :support} (object/public-slot-keys publication)))
    (is (= evidence (capture/retained-evidence publication)))
    (is (= captured (data/payload (net/network-cell-strongest settled output))))
    (is (= (datum/support-of publication)
           (datum/support-of (net/network-cell-strongest settled output))))
    (is (identical? publication (combined publication rewrapped net/empty-net)))
    (let [withdrawn (combined publication
                              (collection/content {:premise-state #{{:source source :timestamp 2
                                                                    :premises-status :retracted}}})
                              net/empty-net)]
      (is (value/nothing? (data/payload withdrawn)))
      (is (seq (datum/layer-value withdrawn :premise-state))))))

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

(deftest combined-merge-recognizes-only-the-capture-domain
  (let [delegations (atom [])
        combined (session-runner/combined-capture-merge
                  capture/capture-pair?
                  capture/join)
        a (fragment :observation {:a 1})]
    (is (= a (combined value/nothing a nil)))
    (is (= a (combined a value/nothing nil)))
    (is (= 3 (combined 3 3 net/empty-net)))
    (is (= value/contradiction (combined 3 4 net/empty-net)))
    (is (empty? @delegations))))

(deftest evaluator-constructor-preserves-default-and-isolates-merge-policy
  (let [id (ids/new-node-id)
        network (builder/install-cell net/empty-net id)
        update (message/message id 7)
        [_ default-network] (evaluator/evaluate update network)
        calls (atom [])
        custom (evaluator/make-cell-evaluator
                (fn [content incoming n]
                  (swap! calls conj [content incoming n])
                  (merge/cell-merge content incoming n)))
        [_ custom-network] (custom update network)]
    (is (= (net/network-cell-content default-network id)
           (net/network-cell-content custom-network id)))
    (is (= (net/network-cell-strongest default-network id)
           (net/network-cell-strongest custom-network id)))
    (is (= 1 (count @calls)))
    (is (nil? (net/network-dict-entry network runner/network-iterator-key)))
    (is (ifn? (runner/selected-network-iterator
               (runner/with-network-iterator
                network
                (session-runner/make-session-iterator)))))))

(deftest session-owned-network-selects-capture-merge-without-global-leakage
  (let [output (ids/new-node-id)
        propagator-id (ids/new-node-id)
        a (fragment :observation {:a 1})
        b (fragment :observation {:b 2})
        base (builder/install-cell net/empty-net output)
        [_ installed]
        ((prop/construct-propagator
          propagator-id
          :capture/publisher
          (fn [_inputs _outputs _network]
            [(message/message output a) (message/message output b)])
          [] [output])
         base)
        ordinary (runner/completed-network
                  (runner/run-network [propagator-id] installed))
        extended-input
        (runner/with-network-iterator
         installed
         (session-runner/make-session-iterator))
        extended (runner/completed-network
                  (runner/run-network [propagator-id] extended-input))]
    (is (not (capture/fragment?
              (net/network-cell-content ordinary output))))
    (is (capture/fragment?
         (net/network-cell-content extended output)))
    (is (nil? (net/network-dict-entry installed runner/network-iterator-key)))
    (is (nil? (net/network-dict-entry
               (runner/without-network-iterator extended)
               runner/network-iterator-key)))))

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

(deftest capture-operand-orders-and-concurrent-runner-ownership
  (let [a (fragment :same {:a 1}) b (fragment :same {:b 2})
        merge-capture (session-runner/combined-capture-merge)
        output (ids/new-node-id) publisher (ids/new-node-id)
        [_ base] ((prop/construct-propagator
                   publisher :publish
                   (fn [& _] [(message/message output a) (message/message output b)])
                   [] [output])
                  (builder/install-cell net/empty-net output))
        iterator (session-runner/make-session-iterator)
        extended (runner/with-network-iterator base iterator)
        run #(runner/completed-network (runner/run-network [publisher] %))
        extended-job (future (run extended))
        ordinary-job (future (run base))]
    (doseq [[left right] [[a value/contradiction] [value/contradiction a]]]
      (is (value/contradiction? (merge-capture left right nil))))
    (is (capture/fragment? (net/network-cell-strongest @extended-job output)))
    (is (not (capture/fragment? (net/network-cell-strongest @ordinary-job output))))
    (is (identical? iterator (runner/selected-network-iterator @extended-job)))
    (is (= (net/network-cell-content @extended-job output)
           (net/network-cell-content (run @extended-job) output))
        "Resumed work selects its own runner without a calling-thread binding")))

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

(deftest asynchronous-clock-entry-selects-the-owning-session-runner
  (let [extended (runtime/new-session) ordinary (runtime/new-session)
        input (ids/new-node-id) output (ids/new-node-id) publisher (ids/new-node-id)
        subscription-id :capture-test/clock
        iterator (session-runner/make-session-iterator)
        a (fragment :clock-observation {:a 1}) b (fragment :clock-observation {:b 2})]
    (doseq [session [extended ordinary]]
      (state/ensure-session-state! session)
      (let [base (-> (:program/net @session)
                     (builder/install-cell input)
                     (builder/install-cell output))
            [_ installed] ((prop/construct-propagator
                            publisher :clock-capture
                            (fn [& _] [(message/message output a) (message/message output b)])
                            [input] [output]) base)]
        (swap! session assoc :program/net installed
               :clock/subscriptions {subscription-id {:clock/id subscription-id :clock/target-id input}})))
    (runtime/install-network-iterator! extended iterator)
    (try
      (let [first-job (future (clock/tick-clock! extended subscription-id (constantly 1000)))
            second-job (future (clock/tick-clock! ordinary subscription-id (constantly 1000)))]
        @first-job @second-job
        (is (capture/fragment? (net/network-cell-strongest (:program/net @extended) output)))
        (is (not (capture/fragment? (net/network-cell-strongest (:program/net @ordinary) output))))
        (is (identical? iterator (runner/selected-network-iterator (:program/net @extended))))
        (is (= 1 (get-in @extended [:clock/epochs subscription-id])))
        (swap! extended state/remove-network-iterator)
        (is (nil? (net/network-dict-entry (:program/net @extended) runner/network-iterator-key))))
      (finally (runtime/stop-clocks! extended) (runtime/stop-clocks! ordinary)))))
