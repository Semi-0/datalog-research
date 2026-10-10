(ns propagators.compiler-2-declaration-effects-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.model.env :as env]
            [propagators.core :as core]
            [propagators.gur :as gur]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(deftest cells-emit-at-declaration-and-preserve-parent-view
  (let [parent (declarations/begin net/empty-net)
        [a b] (repeatedly 2 ids/new-node-id)
        child (-> parent (declarations/ensure-cell a) (declarations/seed-cell a 42)
                  (declarations/ensure-cell b))]
    (is (= {:effects [] :messages []} (declarations/result parent)))
    (is (= #{(gur/declare-cell a) (gur/declare-cell b)}
           (set (:effects (declarations/result child)))))
    (is (= [(message a 42)] (:messages (declarations/result child))))
    (is (= net/empty-net (declarations/discard parent)))))

(deftest delayed-body-starts-a-fresh-declaration-window
  (let [[a b] (repeatedly 2 ids/new-node-id)
        parent (declarations/ensure-cell (declarations/begin net/empty-net) a)
        child (declarations/ensure-cell (declarations/begin parent) b)]
    (is (= [(gur/declare-cell b)] (:effects (declarations/result child))))
    (is (= [(gur/declare-cell a)] (:effects (declarations/result parent))))
    (is (contains? (net/net-env child) a))))

(deftest one-declaration-publishes-only-the-final-seed
  (let [id (ids/new-node-id)
        prepared (-> (declarations/begin net/empty-net)
                     (declarations/seed-cell id {:body :ordinary})
                     (declarations/seed-cell id {:body :wrapped}))
        result (declarations/result prepared)
        [_ replayed] (core/eval-activation-result result net/empty-net)]
    (is (= [(message id {:body :wrapped})] (:messages result)))
    (is (= {:body :wrapped} (net/network-cell-strongest replayed id)))))

(deftest registered-installers-emit-their-own-boundary
  (let [[input output] (repeatedly 2 ids/new-node-id)
        prepared (-> (declarations/begin net/empty-net)
                     (declarations/install-cell input 7 7)
                     (declarations/ensure-cell output))
        [id installed] (((prop/concrete-primitive-propagator :test/inc inc)
                          input output) prepared)
        recorded (declarations/register-props installed [id])
        effects (declarations/result recorded)
        [tasks replayed] (core/eval-activation-result effects net/empty-net)
        completed (runner/completed-network (runner/run-network tasks replayed))]
    (is (= 8 (net/network-cell-strongest completed output)))
    (is (= 1 (count (filter #(= :network/declare-propagator (:op %)) (:effects effects)))))
    (is (= effects (declarations/result (declarations/register-props recorded [id]))))))

(deftest lexical-publication-uses-the-touched-frame
  (let [[frame binding] (repeatedly 2 ids/new-node-id)
        indexed (net/assoc-net-dict-entry net/empty-net env/lexical-topology-key
                  {:frames {frame {:bindings {'x #{binding}} :current-bindings {'x binding}}}})
        recorded (declarations/register-frame (declarations/begin indexed) frame)
        result (declarations/result recorded)
        [_ published] (core/eval-activation-result result indexed)]
    (is (= (set (env/lexical-topology-effects indexed)) (set (:effects result))))
    (is (= {:effects [] :messages []}
           (declarations/result (declarations/register-frame
                                 (declarations/begin published) frame))))))

(deftest compiler-effects-replay-without-the-compiled-net
  (let [compiled (compiler/compile-source "(+ 2 3)")
        [tasks declared] (core/eval-activation-result
                          (select-keys compiled [:effects :messages]) net/empty-net)
        completed (runner/completed-network (runner/run-network tasks declared))]
    (is (seq (:effects compiled)))
    (is (= 5 (net/network-cell-strongest completed (:cell compiled))))
    (is (nil? (net/network-dict-entry (:net compiled) declarations/buffer-key)))
    (is (nil? (net/network-dict-entry completed declarations/buffer-key)))))
