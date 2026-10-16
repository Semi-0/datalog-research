(ns propagators.experimental.ttms-compound-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-compound :as compound]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- premise [source timestamp status]
  {:source source :timestamp timestamp :premises-status status})

(defn- observed [source timestamp base]
  (let [p (premise source timestamp :active)]
    (collection/content {:base base :support #{p} :premise-state #{p}})))

(defn- publish [network target content]
  (let [[tasks patched] (core/eval-cell target (message/message target content) network)]
    (nb/run-propagators patched tasks)))

(defn- setup []
  (let [[owner x y] (repeatedly 3 ids/new-node-id)
        [px n1] ((compound/p:slot :x x owner) (nb/install-cells [owner x y]))
        [py n2] ((compound/p:slot :y y owner) n1)]
    {:owner owner :x x :y y :tasks [px py]
     :network (nb/run-propagators n2 [px py])}))

(defn- base [network id]
  (let [v (datum/layer-value (net/network-cell-strongest network id) :base)]
    (if (evidence/evidence-set? v)
      (evidence/strongest v)
      v)))

(deftest fresh-compound-removes-unsupported-structure-through-bidirectional-access
  (let [{:keys [owner x y tasks network]} (setup)
        old (observed owner 1 (obj/compound-object {:x 10 :y 20}))
        fresh (observed owner 2 (obj/compound-object {:x 7}))
        initial (publish network owner old)
        updated (publish initial owner fresh)
        stale-child (observed owner 1 20)
        replayed (-> updated (publish y stale-child) (publish owner old))]
    (is (= 10 (base initial x)))
    (is (= 20 (base initial y)))
    (is (= 7 (base updated x)))
    (is (value/nothing? (base updated y)))
    (is (= #{:x} (set (obj/public-slot-keys (base updated owner)))))
    (is (= #{(premise owner 2 :active)}
           (datum/layer-value (net/network-cell-strongest updated y) :premise-state)))
    (is (= (net/net-env updated) (net/net-env replayed)))
    (is (= updated (nb/run-propagators updated tasks)))
    (is (= (set (keys (net/net-env network))) (set (keys (net/net-env updated)))))))

(deftest slot-to-parent-updates-preserve-the-real-source
  (let [{:keys [owner x tasks network]} (setup)
        after (publish network x (observed x 1 42))
        parent (net/network-cell-strongest after owner)]
    (is (= 42 (obj/slot-value (base after owner) :x)))
    (is (= #{(premise x 1 :active)} (datum/support-of parent)))
    (is (= after (nb/run-propagators after tasks)))
    (is (= (set (keys (net/net-env network))) (set (keys (net/net-env after)))))))

(deftest equal-payload-with-new-support-still-invalidates-old-membership
  (let [{:keys [owner x y tasks network]} (setup)
        initial (publish network owner (observed owner 1 (obj/compound-object {:x 10 :y 20})))
        after (publish initial owner (observed owner 2 (obj/compound-object {:x 10})))]
    (is (= 10 (base after x)))
    (is (= #{(premise owner 2 :active)}
           (datum/support-of (net/network-cell-strongest after x))))
    (is (value/nothing? (base after y)))
    (is (= after (publish after y (observed owner 1 20))))
    (is (= after (nb/run-propagators after tasks)))))

(deftest compatible-same-version-compound-refinement-keeps-the-interface
  (let [{:keys [owner x y tasks network]} (setup)
        initial (publish network owner (observed owner 1 (obj/compound-object {:x 10})))
        after (publish initial owner (observed owner 1 (obj/compound-object {:x 10 :y 20})))]
    (is (= 10 (base after x)))
    (is (= 20 (base after y)))
    (is (= #{:x :y} (set (obj/public-slot-keys (base after owner)))))
    (is (= after (nb/run-propagators after tasks)))))

(deftest newer-update-returning-from-slot-supersedes-stale-parent
  (let [{:keys [owner x y tasks network]} (setup)
        initial (publish network owner (observed owner 1 (obj/compound-object {:x 10 :y 20})))
        after (publish initial x (observed owner 2 99))]
    (is (= 99 (base after x)))
    (is (value/nothing? (base after y)))
    (is (= #{:x} (set (obj/public-slot-keys (base after owner)))))
    (is (= #{(premise owner 2 :active)}
           (datum/support-of (net/network-cell-strongest after owner))))
    (is (= after (nb/run-propagators after tasks)))))

(deftest independent-membership-is-not-erased-by-another-source
  (let [{:keys [owner x y tasks network]} (setup)
        initial (-> network
                    (publish owner (observed owner 1 (obj/compound-object {:x 10})))
                    (publish y (observed y 1 20)))
        after (publish initial owner (observed owner 2 (obj/compound-object {:x 7})))]
    (is (= 7 (base after x)))
    (is (= 20 (base after y)))
    (is (= #{:x :y} (set (obj/public-slot-keys (base after owner)))))
    (is (= #{(premise y 1 :active)}
           (datum/support-of (net/network-cell-strongest after y))))
    (is (= after (nb/run-propagators after tasks)))))

(deftest same-version-conflicts-and-provenanced-contradictions-remain-visible
  (let [{:keys [owner x network]} (setup)
        after-first (publish network owner (observed owner 1 (obj/compound-object {:x 10})))
        conflict (publish after-first owner (observed owner 1 (obj/compound-object {:x 20})))
        repaired (publish conflict owner (observed owner 2 (obj/compound-object {:x 7})))
        problem (value/contradiction-with-provenance #{:sensor})
        explicit (publish repaired owner (observed owner 3 (obj/compound-object {:x problem})))]
    (is (value/contradiction? (base conflict x)))
    (is (= #{(premise owner 1 :active)}
           (datum/support-of (net/network-cell-strongest conflict x))))
    (is (= 7 (base repaired x)))
    (is (= problem (base explicit x)))))

(deftest retraction-recovery-and-stale-alias-feedback
  (let [{:keys [owner x y tasks network]} (setup)
        old (observed owner 1 (obj/compound-object {:x 10 :y 20}))
        initial (publish network owner old)
        withdrawn (publish initial owner
                           (collection/content {:premise-state #{(premise owner 2 :retracted)}}))
        replayed (publish withdrawn y (observed owner 1 20))
        restored (publish replayed owner (observed owner 3 (obj/compound-object {:x 5})))]
    (doseq [id [x y]]
      (is (value/nothing? (base replayed id)))
      (is (= #{(premise owner 2 :retracted)}
             (datum/layer-value (net/network-cell-strongest replayed id) :premise-state))))
    (is (= 5 (base restored x)))
    (is (value/nothing? (base restored y)))
    (is (= restored (nb/run-propagators restored tasks)))))

(deftest nested-access-inherits-the-parent-membership-version
  (let [[owner scope x] (repeatedly 3 ids/new-node-id)
        [ps n1] ((compound/p:slot :scope scope owner) (nb/install-cells [owner scope x]))
        [px n2] ((compound/p:slot :x x scope) n1)
        network (nb/run-propagators n2 [ps px])
        nested #(obj/compound-object {:scope (obj/compound-object {:x %})})
        initial (publish network owner (observed owner 1 (nested 10)))
        changed (publish initial owner (observed owner 2 (nested 7)))
        removed (publish changed owner (observed owner 3 (obj/compound-object {})))
        replayed (publish removed x (observed owner 1 10))]
    (is (= 10 (base initial x)))
    (is (= 7 (base changed x)))
    (is (= #{(premise owner 2 :active)}
           (datum/support-of (net/network-cell-strongest changed x))))
    (is (value/nothing? (base replayed x)))
    (is (= #{(premise owner 3 :active)}
           (datum/layer-value (net/network-cell-strongest replayed x) :premise-state)))
    (is (= replayed (nb/run-propagators replayed [ps px])))
    (is (= (set (keys (net/net-env network))) (set (keys (net/net-env replayed)))))))

(deftest unsupported-inputs-are-explicit
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires TTMS content"
                       (compound/slot-content :x {:x 1})))
  (let [owner (ids/new-node-id)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must contain a network"
                         (compound/slot-content :x (observed owner 1 10))))))
