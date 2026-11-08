(ns propagators.compiler-2.stream-extension-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compiler-2.session :as s]
            [propagators.compiler-2.session.host :as host]
            [propagators.compiler-2.compiler.basis :as b]
            [propagators.datastructures.support-collection :as sc]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.cells.value :as value]))
(defn stamp [id epoch status]
  (sc/content {:base :premise :support #{{:source id :timestamp epoch :premises-status status}}}))
(defn scoped [s target name]
  (net/network-cell-strongest (s/network s) (s/scoped-binding-cell s target name)))
(deftest scoped-monotone-admission
  (let [base (s/extend-session (s/create-session) (s/session-extension {:id :parent :files {"parent" "(define x 1)"}}))
        child (b/stable-node-id :test :child)
        ext (s/session-extension {:id :scope :frames {child {:parent (s/environment base) :bindings []}}
                                  :target child :files {"a" "(define x 7) (define f (network [y] (+ x y)))"}})
        first (s/extend-session base ext)
        second (s/extend-session first (s/session-extension {:id :b :target child :files {"b" "(define answer (f 3))"}}))]
    (is (= 10 (data/payload (scoped second child 'answer))))
    (is (= (s/environment base) (s/environment second)))
    (is (= (s/session-identity base) (s/session-identity second)))
    (is (= (s/network second) (s/network (s/extend-session second ext))))
    (is (= 1 (data/payload (s/binding-value second 'x))))
    (is (thrown? Exception (s/extend-session second (s/session-extension {:id :bad :target child :files {"bad" "(define partial 9) (define x 8)"}}))))
    (is (thrown? Exception (s/scoped-binding-cell second child 'partial)))))
(deftest support-withdrawal-recovery
  (let [base (s/create-session) child (b/stable-node-id :support :child)
        model (b/stable-node-id :model) round (b/stable-node-id :round) emission (b/stable-node-id :emission)
        first (s/extend-session base
                                (s/session-extension {:id :supported :target child :support-cell emission
                                                      :cells {model (stamp model 1 :active) round (stamp round 1 :active)}
                                                      :derivations {emission {:base :source :sources [model round]}}
                                                      :frames {child {:parent (s/environment base) :bindings []}}
                                                      :files {"a" "(define x 10) (define f (network [y] (+ x y))) (define answer (f 2))"}}))
        second (:session (s/advance first [(s/cell-input round (stamp round 2 :retracted))]))
        stale (:session (s/advance second [(s/cell-input round (stamp round 1 :active))]))
        third (:session (s/advance stale [(s/cell-input round (stamp round 3 :active))]))]
    (is (= 12 (data/payload (scoped first child 'answer))))
    (is (= #{model round} (set (map :source (datum/support-of (scoped first child 'x))))))
    (doseq [session [second stale] name '[x f answer]]
      (is (value/unusable? (scoped session child name))))
    (is (= 12 (data/payload (scoped third child 'answer))))
    (is (= (set (keys (net/net-env (s/network first)))) (set (keys (net/net-env (s/network third))))))))
(deftest queued-admission-and-close
  (let [owner (host/open! (s/create-session) {:auto-advance? false})]
    (try
      (let [receipt (host/submit-extension! owner (s/session-extension {:id :queued :files {"a" "(define queued 4)"}}))]
        (is (not (realized? receipt)))
        (host/step! owner)
        (is (= :admitted (:status @receipt)))
        (is (= 4 (data/payload (s/binding-value (host/current-session owner) 'queued)))))
      (let [receipt (host/submit-extension! owner (s/session-extension {:id :closed :files {"b" "(define late 5)"}}))]
        (host/close! owner)
        (is (= :rejected (:status @receipt))))
      (finally (host/close! owner)))))
(deftest compound-members-and-explicit-writes
  (let [base (s/create-session) target (b/stable-node-id :compound :target)
        premise (b/stable-node-id :compound :premise)
        ext (s/session-extension {:id :compound :target target :support-cell premise
                                  :cells {premise (stamp premise 1 :active)}
                                  :frames {target {:parent (s/environment base) :bindings []}}
                                  :files {"compound" "(define direct-out) (-> 40 direct-out) (define out) (define write-port (network [port] (define port 41) port)) (write-port out)
                                   (define pair-for (network [x] (list x (+ x 1))))
                                   (define pair (pair-for 2)) (define left (car pair)) (define right (car (cdr pair)))"}})
        before (s/extend-session base ext)
        after (:session (s/advance before [(s/cell-input premise (stamp premise 2 :retracted))]))]
    (is (= 41 (data/payload (scoped before target 'out))))
    (is (= 40 (data/payload (scoped before target 'direct-out))))
    (is (= 2 (data/payload (scoped before target 'left))))
    (is (= 3 (data/payload (scoped before target 'right))))
    (doseq [name '[out direct-out left right]] (is (value/unusable? (scoped after target name))))))
(deftest input-support-does-not-retarget-old-code
  (let [base (s/create-session) target (b/stable-node-id :pinned :target)
        source (b/stable-node-id :pinned :source) derived (b/stable-node-id :pinned :derived)
        stamp-value (fn [v epoch] (sc/content {:base v :support #{{:source source :timestamp epoch :premises-status :active}}}))
        before (s/extend-session base
                                 (s/session-extension {:id :pinned :target target :support-cell derived
                                                       :cells {source (stamp-value 1 1)}
                                                       :derivations {derived {:base :generated :sources [source] :expected {source 1}}}
                                                       :frames {target {:parent (s/environment base) :bindings []}}
                                                       :files {"pinned" "(define answer 42)"}}))
        after (:session (s/advance before [(s/cell-input source (stamp-value 2 2))]))]
    (is (= 42 (data/payload (scoped before target 'answer))))
    (is (value/unusable? (scoped after target 'answer)))))
(deftest independent-justifications-survive-single-withdrawal
  (let [base (s/create-session) target (b/stable-node-id :alternatives :target)
        a (b/stable-node-id :alternative :a) b (b/stable-node-id :alternative :b)
        first (s/extend-session base (s/session-extension
                                      {:id :alternative-a :target target :support-cell a
                                       :cells {a (stamp a 1 :active) b (stamp b 1 :active)}
                                       :frames {target {:parent (s/environment base) :bindings []}}
                                       :files {"alternative-a" "(define shared 42)"}}))
        second (s/extend-session first (s/session-extension
                                        {:id :alternative-b :target target :support-cell b
                                         :files {"alternative-b" "(define shared 42)"}}))
        withdrawn (:session (s/advance second [(s/cell-input a (stamp a 2 :retracted))]))
        both (:session (s/advance withdrawn [(s/cell-input b (stamp b 2 :retracted))]))]
    (is (= 42 (data/payload (scoped withdrawn target 'shared))))
    (is (value/unusable? (scoped both target 'shared)))))
(deftest withdrawal-queued-before-admission-wins
  (let [premise (b/stable-node-id :queued :premise) target (b/stable-node-id :queued :target)
        base (s/create-session)
        ready (s/extend-session base (s/session-extension {:id :ready
                                                           :cells {premise (stamp premise 1 :active)}
                                                           :frames {target {:parent (s/environment base) :bindings []}}}))
        owner (host/open! ready {:auto-advance? false})]
    (try
      (host/submit! owner (s/cell-input premise (stamp premise 2 :retracted)))
      (let [receipt (host/submit-extension! owner (s/session-extension
                                                   {:id :too-late :target target :support-cell premise
                                                    :files {"too-late" "(define late 99)"}}))]
        (host/step! owner)
        (is (= :rejected (:status @receipt)))
        (is (thrown? Exception (s/scoped-binding-cell (host/current-session owner) target 'late))))
      (finally (host/close! owner)))))
(deftest late-primitives-target-the-lexical-child
  (let [base (s/extend-session (s/create-session) (s/session-extension {:id :native-parent :files {"native-parent" "(define x 1)"}}))
        target (b/stable-node-id :late-primitive)
        child (s/extend-session base (s/session-extension {:id :child-frame
                                                           :frames {target {:parent (s/environment base) :bindings []}}}))
        extended (s/extend-session child (s/session-extension {:id :late-native :target target
                                                               :primitives {'late-add (b/primitive-operator inc) 'x 10}}))
        result (s/extend-session extended (s/session-extension {:id :use-late :target target
                                                                :files {"use-late" "(define answer (late-add 9))"}}))]
    (is (= 10 (data/payload (scoped result target 'answer))))
    (is (thrown? Exception (s/binding-cell result 'late-add)))
    (is (= 10 (data/payload (scoped result target 'x))))
    (is (= 1 (data/payload (s/binding-value result 'x))))))
