(ns propagators.experimental.networked-codec-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.networked-environment.codec :as codec]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(deftest record-roundtrip-keeps-all-four-fields
  (let [id (ids/new-node-id)
        content {:base 10 :support #{{:source id :timestamp 1 :premises-status :active}}}
        n (net/net {id (graph/node #{} #{})}
                   {id (cell/cell :x content content)}
                   {:x id :nested (net/net {} {} {:child true})}
                   {[:outer id] (graph/node #{} #{})})
        registry (codec/registry {})
        encoded (codec/encode registry (with-meta n {:test true}))
        decoded (codec/decode registry (:text encoded))]
    (is (= n (:value decoded)))
    (is (= {:test true} (meta (:value decoded))))
    (is (empty? (:substitutions encoded)))
    (is (empty? (:substitutions decoded)))
    (is (instance? propagators.network.Net (:value decoded)))))

(deftest registry-is-explicit-and-decoding-never-runs-functions
  (let [calls (atom 0)
        f #(swap! calls inc)
        registry (codec/registry {:known f})
        encoded (codec/encode registry {:known f :unknown (fn [] :opaque)})
        decoded (codec/decode registry (:text encoded))
        absent (codec/decode (codec/registry {}) (:text encoded))]
    (is (identical? f (get-in decoded [:value :known])))
    (is (= value/nothing (get-in decoded [:value :unknown])))
    (is (= value/nothing (get-in absent [:value :known])))
    (is (= [[:unknown]] (:substitutions encoded)))
    (is (= [[:known]] (:substitutions absent)))
    (is (zero? @calls))))

(deftest unavailable-activation-is-data-not-an-execution-request
  (let [registry (codec/registry {})
        original (prop/prop :opaque (fn [& _] (throw (Exception. "must not run"))))
        restored (:value (codec/decode registry (:text (codec/encode registry original))))]
    (is (instance? propagators.propagator.Propagator restored))
    (is (= :opaque (:name restored)))
    (is (= value/nothing (:activate restored)))))

(deftest unknown-wire-tags-and-reader-code-are-rejected
  (is (thrown? Exception (codec/decode (codec/registry {}) "[:unrecognized 1]")))
  (is (thrown? Exception (codec/decode (codec/registry {}) "#=(System/exit 0)"))))
