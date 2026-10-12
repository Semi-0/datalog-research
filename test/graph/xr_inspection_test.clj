(ns graph.xr-inspection-test
  (:require [clojure.test :refer [deftest is]]
            [graph.json :as json]
            [graph.xr-inspection :as codec]
            [graph.xr-network-inlet :as inlet]
            [graph.xr-server :as server]
            [graph.xr-runtime :as xr]
            [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.visualization.ttms :as ttms]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(deftest full-net-is-an-ordinary-ttms-base
  (let [id (ids/new-node-id)
        first-net (nb/install-cell net/empty-net id 1 1)
        second-net (nb/install-cell net/empty-net id 2 2)
        start (inlet/inlet)
        first (inlet/observe start first-net)
        second (inlet/observe first second-net)
        stale (inlet/publish second 1 :active first-net)
        withdrawn (inlet/publish stale 3 :retracted value/nothing)
        restored (inlet/publish withdrawn 4 :active second-net)]
    (is (identical? first-net (ttms/payload (inlet/current first))))
    (is (identical? first (inlet/observe first first-net)))
    (is (identical? first (inlet/observe first
                                       (assoc first-net :env (net/net-env first-net)))))
    (is (= second-net (ttms/payload (inlet/current stale))))
    (is (= :withdrawn (ttms/status (inlet/current withdrawn))))
    (is (= second-net (ttms/payload (inlet/current restored))))
    (is (= #{{:source (:source start) :timestamp 4 :premises-status :active}}
           (datum/support-of (inlet/current restored))))
    (is (not (contains? (net/net-env second-net) (:source start))))
    (is (= 1 (count (get (net/network-cell-content (:network restored) (:source start))
                         :support/observations))))))

(deftest same-version-conflict-keeps-dependencies
  (let [start (inlet/inlet)
        a (nb/install-cell net/empty-net (ids/new-node-id) 1 1)
        b (nb/install-cell net/empty-net (ids/new-node-id) 2 2)
        conflicted (-> start (inlet/publish 1 :active a) (inlet/publish 1 :active b))]
    (is (= :contradiction (ttms/status (inlet/current conflicted))))
    (is (= 1 (count (datum/support-of (inlet/current conflicted)))))
    (is (= 2 (count (:support/observations
                    (net/network-cell-content (:network conflicted) (:source start))))))
    (is (= b (ttms/payload (inlet/current (inlet/publish conflicted 2 :active b)))))))

(deftest named-network-observation-is-atomic-and-repeated-reads-are-stable
  (let [id (ids/new-node-id)
        network (net/assoc-net-dict-entry (nb/install-cell net/empty-net id 2 2) 'source id)
        first (inlet/observe-context (inlet/inlet) network "generation")
        again (inlet/observe-context first network "generation")]
    (is (identical? network (ttms/payload (inlet/current first))))
    (is (identical? first again))
    (is (identical? (:encoded-context first) (:encoded-context again)))
    (is (= "ready" (get-in first [:encoded-context :status])))))

(deftest completed-publication-captures-one-session-state
  (let [id (ids/new-node-id)
        a (nb/install-cell net/empty-net id 1 1)
        b (nb/install-cell net/empty-net id 2 2)
        session (atom {:program/net a :environment/generation "a"})
        observed (atom (inlet/inlet))]
    (with-redefs [xr/handle-command!
                  (fn [s _]
                    (is (Thread/holdsLock s))
                    (is (Thread/holdsLock observed))
                    {:effects [] :widgets {}})]
      (let [first (#'server/latest-effect-payload session observed)
            repeat (#'server/latest-effect-payload session observed)]
        (is (= "a" (get-in first [:context :generation])))
        (is (identical? (:context first) (:context repeat)))
        (is (identical? a (ttms/payload (inlet/current @observed))))
        (locking session (reset! session {:program/net b :environment/generation "b"}))
        (let [next (#'server/latest-effect-payload session observed)]
          (is (= "b" (get-in next [:context :generation])))
          (is (identical? b (ttms/payload (inlet/current @observed)))))))))

(deftest inspection-codec-preserves-types-and-shared-data
  (let [id (ids/new-node-id)
        shared {:base value/contradiction :support #{{:source id :timestamp 9
                                                     :premises-status :retracted}}}
        encoded (codec/encode {:graph {} :env {id {:content shared :strongest shared}}
                               :dict {[:scope 'name] id :large 9007199254740993N}
                               :relationship {} :function (fn [] (throw (Exception. "must not run")))})
        round-trip (json/read-json (json/write-json encoded))
        objects (vals (:objects round-trip))]
    (is (= (update encoded :objects
                   #(into {} (map (fn [[k v]] [(keyword k) v])) %))
           round-trip))
    (is (= 1 (count (filter #(= "set" (:tag %)) objects))))
    (is (some #(= "vector" (:tag %)) objects))
    (is (re-find #"9007199254740993" (json/write-json encoded)))
    (is (re-find #"opaque" (json/write-json encoded)))
    (is (re-find #"premises-status" (json/write-json encoded)))
    (is (= {:path [:outer] :id (str (:uuid id))} (codec/source-reference [[:outer] id])))))

(deftest transport-sharing-preserves-sequence-types-and-source-identities
  (let [a (ids/new-node-id) b (ids/new-node-id)
        encoded (codec/encode [[:x] (list :x) {:payload 10} (into {} [[:payload 10]]) a b])
        children (:items (get-in encoded [:objects "0"]))]
    (is (not= (first children) (second children)))
    (is (= (nth children 2) (nth children 3)))
    (is (not= (nth children 4) (nth children 5)))))

(deftest deeply-retained-context-is-stack-safe
  (let [nested (nth (iterate #(hash-map :previous %) :leaf) 3000)
        encoded (codec/encode nested)]
    (is (= 3000 (count (:objects encoded))))
    (is (contains? (:objects encoded) (get-in encoded [:root :ref])))
    (is (= 3001 (count (:objects (codec/encode #{nested})))))))
