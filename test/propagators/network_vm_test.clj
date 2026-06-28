(ns propagators.network-vm-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.gur-accumulating-test :as acc-test]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-vm :as vm]
            [propagators.network-vm.gur :as gur]
            [propagators.propagator :as prop]))

(defn- id
  [& parts]
  (gur/stable-node-id (into [:network-vm-test] parts)))

(defn- strongest
  [vm-state cell-id]
  (net/network-cell-strongest (:net vm-state) cell-id))

(defn- prop-count
  [vm-state]
  (count (filter prop/prop? (vals (net/net-env (:net vm-state))))))

(defn- run-vm-gur-closure
  [closure value]
  (let [request-id (id :request)
        closure-id (id :closure)
        arg-id (id :arg value)
        out-id (id :out value)
        initial (vm/apply-instructions
                 (vm/state)
                 [(vm/declare-cell request-id)
                  (vm/declare-cell closure-id)
                  (vm/declare-cell arg-id)
                  (vm/declare-cell out-id)
                  (vm/tell closure-id closure)
                  (vm/tell arg-id value)
                  (gur/apply-request request-id closure-id [arg-id] out-id)])
        final (gur/run-until-cold initial request-id 4096)]
    {:state final
     :out-id out-id
     :value (strongest final out-id)}))

(def factorial
  (gur/recursive-closure
   'factorial
   (fn [{:keys [app-key stable-id] recur-fn :recur} _arg-ids out-id [n]]
     (if (<= n 1)
       (let [base-prop (stable-id :base-prop)]
         [(vm/declare-prop base-prop [] [out-id] (gur/const-prop 1))
          (vm/schedule [:frame app-key] [base-prop] 0)])
       (let [next-id (stable-id :next)
             recur-out-id (stable-id :recur-out)
             mul-prop (stable-id :mul)]
         [(vm/declare-cell next-id)
          (vm/tell next-id (dec n))
          (vm/declare-cell recur-out-id)
          (recur-fn [next-id] recur-out-id)
          (vm/declare-prop mul-prop [recur-out-id] [out-id]
                           (gur/unary-prop #(* n %)))
          (vm/schedule [:frame app-key] [mul-prop] 0)])))))

(def fib
  (gur/recursive-closure
   'fib
   (fn [{:keys [app-key stable-id] recur-fn :recur} _arg-ids out-id [n]]
     (if (< n 2)
       (let [base-prop (stable-id :base-prop)]
         [(vm/declare-prop base-prop [] [out-id] (gur/const-prop n))
          (vm/schedule [:frame app-key] [base-prop] 0)])
       (let [n1-id (stable-id :n-1)
             n2-id (stable-id :n-2)
             out1-id (stable-id :out-1)
             out2-id (stable-id :out-2)
             sum-prop (stable-id :sum)]
         [(vm/declare-cell n1-id)
          (vm/declare-cell n2-id)
          (vm/tell n1-id (dec n))
          (vm/tell n2-id (- n 2))
          (vm/declare-cell out1-id)
          (vm/declare-cell out2-id)
          (recur-fn [n1-id] out1-id)
          (recur-fn [n2-id] out2-id)
          (vm/declare-prop sum-prop [out1-id out2-id] [out-id]
                           (gur/binary-prop +))
          (vm/schedule [:frame app-key] [sum-prop] 0)])))))

(deftest network-vm-declaration-instructions-are-idempotent
  (let [cell-id (id :cell)
        in-id (id :in)
        out-id (id :out)
        prop-id (id :prop)
        activate (gur/unary-prop inc)
        s (vm/apply-instructions
           (vm/state)
           [(vm/declare-cell cell-id)
            (vm/declare-cell cell-id)
            (vm/declare-prop prop-id [in-id] [out-id] activate)
            (vm/declare-prop prop-id [in-id] [out-id] activate)])]
    (is (= #{cell-id in-id out-id prop-id}
           (set (keys (net/net-env (:net s))))))
    (is (= 1 (prop-count s)))))

(deftest network-vm-tell-wakes-downstream-propagators
  (let [a-id (id :a)
        b-id (id :b)
        c-id (id :c)
        plus-id (id :plus)
        s0 (vm/apply-instructions
            (vm/state)
            [(vm/declare-prop plus-id [a-id b-id] [c-id] (gur/binary-prop +))
             (vm/tell a-id 1)
             (vm/tell b-id 2)])
        s1 (vm/advance s0)
        s2 (vm/run-until-cold s1)]
    (is (pos? (vm/temperature s0)))
    (is (pos? (vm/temperature s1)))
    (is (zero? (vm/temperature s2)))
    (is (= 3 (strongest s2 c-id)))))

(deftest network-vm-schedule-index-controls-reruns
  (let [counter (atom 0)
        out-id (id :scheduled-out)
        prop-id (id :scheduled-prop)
        activate (fn [_inputs outputs _network]
                   (swap! counter inc)
                   [(message (first outputs) @counter)])
        s0 (vm/apply-instructions
            (vm/state)
            [(vm/declare-prop prop-id [] [out-id] activate)
             (vm/schedule [:cause] [prop-id] 0)
             (vm/schedule [:cause] [prop-id] 0)])
        s1 (vm/run-until-cold s0)
        s2 (vm/run-until-cold
            (vm/apply-instruction s1
                                  (vm/schedule [:cause] [prop-id] 0)))
        s3 (vm/run-until-cold
            (vm/apply-instruction s2
                                  (vm/schedule [:cause] [prop-id] 1)))]
    (is (= 1 (strongest s1 out-id)))
    (is (= 1 (strongest s2 out-id)))
    (is (= 2 @counter))
    (is (value/contradiction? (strongest s3 out-id)))))

(deftest network-vm-late-tell-reheats-cold-state
  (let [cell-id (id :late-cell)
        s0 (vm/run-until-cold
            (vm/apply-instruction (vm/state) (vm/declare-cell cell-id)))
        s1 (vm/apply-instruction s0 (vm/tell cell-id 9))
        s2 (vm/run-until-cold s1)]
    (is (zero? (vm/temperature s0)))
    (is (= 1 (vm/temperature s1)))
    (is (= 9 (strongest s2 cell-id)))
    (is (zero? (vm/temperature s2)))))

(deftest network-vm-gur-computes-scalar-recursion
  (testing "factorial and fib compile recursion into VM declarations"
    (is (= 120 (:value (run-vm-gur-closure factorial 5))))
    (is (= 5 (:value (run-vm-gur-closure fib 5))))))

(deftest network-vm-gur-matches-current-accumulating-scalar-output
  (let [run-acc (deref (resolve 'propagators.gur-accumulating-test/run-closure))]
    (is (= (:value (run-acc acc-test/factorial [5]))
           (:value (run-vm-gur-closure factorial 5))))
    (is (= (:value (run-acc acc-test/fib [5]))
           (:value (run-vm-gur-closure fib 5))))))

(deftest network-vm-gur-when-is-lazy-and-idempotent
  (let [condition-id (id :when-condition)
        out-id (id :when-out)
        prop-id (id :when-prop)
        when-key [:test/when condition-id]
        body [(vm/declare-prop prop-id [] [out-id] (gur/const-prop 42))
              (vm/schedule [:when when-key] [prop-id] 0)]
        s0 (vm/apply-instructions
            (vm/state)
            [(vm/declare-cell condition-id)
             (vm/declare-cell out-id)])
        s1 (gur/expand-when s0 when-key condition-id body)
        s2 (vm/run-until-cold
            (vm/apply-instruction s1 (vm/tell condition-id true)))
        s3 (vm/run-until-cold (gur/expand-when s2 when-key condition-id body))
        prop-count-after (prop-count s3)
        s4 (vm/run-until-cold (gur/expand-when s3 when-key condition-id body))]
    (is (= s0 s1))
    (is (= 42 (strongest s3 out-id)))
    (is (= prop-count-after (prop-count s4)))))

(deftest network-vm-gur-rerun-after-quiescence-does-not-grow
  (let [{:keys [state]} (run-vm-gur-closure fib 5)
        counts {:env (count (net/net-env (:net state)))
                :graph (count (net/net-graph (:net state)))
                :tasks (vm/pending-task-count state)
                :props (prop-count state)}
        state* (gur/run-until-cold state (id :request) 4096)
        counts* {:env (count (net/net-env (:net state*)))
                 :graph (count (net/net-graph (:net state*)))
                 :tasks (vm/pending-task-count state*)
                 :props (prop-count state*)}]
    (is (= counts counts*))))

(deftest network-vm-gur-does-not-directly-merge-cell-entries
  (is (not (re-find #"merge-cell-entry"
                    (slurp "propagators/network_vm/gur.clj")))))
