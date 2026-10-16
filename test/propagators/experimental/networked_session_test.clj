(ns propagators.experimental.networked-session-test
  (:require [clojure.test :refer [deftest is]]
            [examples.lain.networked-environment.codec :as codec]
            [examples.lain.networked-environment.model :as model]
            [examples.lain.networked-environment.session :as session]
            [examples.lain.networked-environment.tracing :as tracing]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.datastructures.layered-value :as datum]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]))

(def producer-source
  "(define x (input :shared))
   (define twice (network [v] (+ v v)))
   (define y (+ x 1))
   (define z (twice y))
   (output :env runtime-env)
   (output :answer z)")

(defn inject [prepared value]
  (let [source (ids/new-node-id)
        id (get-in (session/ports prepared) [:shared :cell])
        datum (model/next-source source value/nothing value :active)]
    (swap! (:session prepared) update :program/net model/apply-inputs [(message id datum)])))

(deftest standard-session-extension-and-lain-program
  (let [identity (session/process-identity)
        p (session/prepare identity producer-source)
        state @(:session p)
        before (:program/net state)]
    (is (= #{:shared :env :answer} (set (keys (session/ports p)))))
    (runtime/install-session-extension!
     (:session p) (:extension p) {:outbox-id (runtime-ids/boundary-outbox-id)})
    (is (= before (:program/net @(:session p))))
    (is (net/net? before))))

(deftest compiled-computation-and-pure-semantic-tracing
  (let [p (session/prepare (session/process-identity) producer-source)]
    (inject p 10)
    (is (= 22 (model/payload (session/port-value p :answer))))
    (let [context (session/context p)
          reference (tracing/reference context "z")
          upstream (tracing/trace-reference reference :upstream)
          downstream (tracing/trace-reference (tracing/reference context "x") :downstream)]
      (is (seq (:edges upstream)))
      (is (seq (:edges downstream)))
      (is (some #{'twice} (vals (:nodes upstream))))
      (is (= context (:context reference))))))
