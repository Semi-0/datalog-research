(ns propagators.compiler-2-topology-effects-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.runtime.topology-effects :as topology]
            [propagators.compiler-2.model.env :as env]
            [propagators.core :as core]
            [propagators.gur :as gur]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- indexed-frame []
  (let [[frame source chain parent binding] (repeatedly 5 ids/new-node-id)
        network (net/assoc-net-dict-entry net/empty-net env/lexical-topology-key
                  {:frames {frame {:scope/source-id source :scope/chain-id chain
                                   :parent-id parent :bindings {'x #{binding}}
                                   :current-bindings {'x binding}}}})]
    {:net network :frame frame :binding binding}))

(deftest unpublished-index-is-published-even-when-base-index-is-identical
  (let [{network :net} (indexed-frame)
        diff (topology/network-diff network network [])
        expected (env/lexical-topology-effects network)
        [_ published] (core/eval-activation-result diff network)]
    (is (= (set expected) (set (:effects diff))))
    (is (empty? (:messages diff)))
    (is (= {:effects [] :messages []}
           (topology/network-diff published published [])))))

(deftest only-new-lexical-binding-is-emitted
  (let [{network :net frame :frame} (indexed-frame)
        [_ base] (core/eval-activation-result
                   {:effects (env/lexical-topology-effects network)} network)
        id (ids/new-node-id)
        compiled (net/update-net-dict-entry base env/lexical-topology-key
                   #(-> % (assoc-in [:frames frame :bindings 'y] #{id})
                          (assoc-in [:frames frame :current-bindings 'y] id)))
        diff (topology/network-diff base compiled [])]
    (is (= #{(gur/bind-name env/lexical-topology-scope [:binding frame 'y id] id)
             (gur/bind-name env/lexical-topology-scope [:current-binding frame 'y] id)}
           (set (:effects diff))))
    (is (empty? (:messages diff)))))

(deftest changed-current-binding-is-not-silently-filtered
  (let [{network :net frame :frame} (indexed-frame)
        [_ base] (core/eval-activation-result
                   {:effects (env/lexical-topology-effects network)} network)
        id (ids/new-node-id)
        compiled (net/update-net-dict-entry base env/lexical-topology-key
                   #(assoc-in % [:frames frame :current-bindings 'x] id))]
    (is (= [(gur/bind-name env/lexical-topology-scope [:current-binding frame 'x] id)]
           (:effects (topology/network-diff base compiled []))))))

(deftest single-cell-scan-preserves-declarations-and-messages
  (let [[old waiting fresh empty-cell] (repeatedly 4 ids/new-node-id)
        base (-> net/empty-net (nb/install-cell old 42 42) (nb/ensure-cell waiting))
        compiled (-> base (nb/seed-cell waiting 7) (nb/install-cell fresh 9 9)
                         (nb/ensure-cell empty-cell))
        diff (topology/network-diff base compiled [])]
    (is (= #{(gur/declare-cell fresh) (gur/declare-cell empty-cell)} (set (:effects diff))))
    (is (= #{(message waiting 7) (message fresh 9)} (set (:messages diff))))
    (is (= 2 (count (:messages diff))))
    (is (= {:effects [] :messages []}
           (topology/network-diff compiled compiled [])))))
