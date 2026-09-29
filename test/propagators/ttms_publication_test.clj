(ns propagators.ttms-publication-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.relationship-observer :as observer]
            [propagators.stdlib.prop :as standard]))

(deftest explicit-source-epochs-are-change-sensitive
  (let [source (ids/new-node-id)
        a (publication/next-source-datum source value/nothing 10 :active)
        b (publication/next-source-datum source a 20 :active)
        r (publication/next-source-datum source b value/nothing :retracted)
        restored (publication/next-source-datum source r 10 :active)]
    (is (= [1 2 3 4] (mapv #(-> % :support first :timestamp) [a b r restored])))
    (is (= a (publication/next-source-datum source a 10 :active)))
    (is (= r (publication/next-source-datum source r value/nothing :retracted)))
    (is (every? #(= source (:source (first (:support %)))) [a b r restored]))
    (is (thrown? clojure.lang.ExceptionInfo
                 (publication/next-source-datum (ids/new-node-id) a 20 :active)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (publication/next-source-datum source 10 20 :active)))))

(deftest source-transform-preserves-other-patches-and-rejects-ambiguous-publication
  (let [[source other] (repeatedly 2 ids/new-node-id)
        network (nb/install-cells [source other])
        transform (publication/stamp-source source :active)
        patch (with-meta (assoc (message/message source 10) :extra :kept) {:test true})
        other-patch (message/message other :untouched)
        declaration {:op :network/declare-cell :id (ids/new-node-id)}
        result (transform [patch other-patch declaration] [] [] network)]
    (is (= [other-patch declaration] (subvec result 1)))
    (is (= :kept (:extra (first result))))
    (is (= (meta patch) (meta (first result))))
    (is (= (type patch) (type (first result))))
    (is (= [] (transform [] [] [] network)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (transform [patch patch] [] [] network)))))

(defn- update-source [network source base epoch]
  (let [update (collection/content
                {:base base :support #{{:source source :timestamp epoch :premises-status :active}}})
        [tasks patched] (core/eval-cell source (message/message source update) network)]
    (nb/run-propagators patched tasks)))

(deftest pure-complete-trace-and-reactive-publication-compose
  (let [[input copied injection output trigger] (repeatedly 5 ids/new-node-id)
        initial (nb/install-cells [input copied injection output trigger])
        [copy n1] ((standard/id input copied) initial)
        [forward n2] ((standard/id injection output) n1)
        sample (observer/snapshot-of (observer/connected-root-node-keys [input]))
        id (ids/new-node-id)
        [watch n3] ((publication/p:observe id copied injection sample) n2)
        active (update-source n3 input 10 1)
        updated (update-source active input 20 2)
        rerun (nb/run-propagators updated [watch])
        read #(net/network-cell-strongest % output)
        current (read updated)]
    (is (= (sample updated) (datum/layer-value current :base)))
    (is (= #{injection} (set (map :source (datum/support-of current)))))
    (is (= 2 (:timestamp (first (datum/support-of current)))))
    (is (= (net/net-env updated) (net/net-env rerun)))
    (is (= 1 (count (:support/observations (net/network-cell-content updated injection)))))
    (is (= (set (keys (net/net-env n3))) (set (keys (net/net-env updated)))))
    (let [withdraw (prop/compose-activation
                    (fn [_ _ _] [(message/message injection value/nothing)])
                    (publication/stamp-source injection :retracted)
                    (message/lift-message collection/content))
          patch (first (withdraw [] [] updated))
          [tasks n4] (core/eval-cell injection patch updated)
          retracted (nb/run-propagators n4 tasks)
          restored (nb/run-propagators retracted [watch])]
      (is (value/unusable? (read retracted)))
      (is (not (value/unusable? (read restored))))
      (is (= 4 (:timestamp (first (datum/support-of (read restored))))))
      (is (= (sample restored) (datum/layer-value (read restored) :base))))))
