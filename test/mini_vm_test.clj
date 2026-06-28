(ns mini-vm-test
  (:require [clojure.test :refer [deftest is testing]]
            [mini_vm :as vm]))

(defn- machine
  []
  (-> (vm/make-new-machine)
      (vm/machine-install-operations {'= =
                                      '* *
                                      '- -
                                      '+ +})))

(deftest immutable-assembler-attaches-lazy-execution-procs
  (testing "update-insts returns new instructions with delayed execution procedures"
    (let [{:keys [instructions labels]}
          (vm/assemble '[start
                         (assign n (const 1))
                         done]
                       (-> (machine)
                           (vm/machine-allocate-register 'n)))]
      (is (= {'start 0 'done 1} labels))
      (is (= '((assign n (const 1)))
             (map vm/instruction-text instructions)))
      (is (every? #(instance? clojure.lang.Delay (second %)) instructions))
      (is (every? fn? (map vm/instruction-execution-proc instructions))))))

(deftest loop-controller-runs-with-immutable-pc-index
  (testing "factorial loop uses label indexes rather than mutable instruction tails"
    (let [controller '[loop
                       (test (op =) (reg n) (const 1))
                       (branch (label done))
                       (assign val (op *) (reg val) (reg n))
                       (assign n (op -) (reg n) (const 1))
                       (goto (label loop))
                       done]
          result (-> (machine)
                     (vm/machine-allocate-register 'n 5)
                     (vm/machine-allocate-register 'val 1)
                     (vm/install-controller controller)
                     vm/execute-machine)]
      (is (= 120 (vm/machine-lookup-register result 'val)))
      (is (= 1 (vm/machine-lookup-register result 'n)))
      (is (= 5 (vm/machine-lookup-register result 'pc))))))

(deftest save-and-restore-update-immutable-machine
  (testing "stack operations return updated machine values"
    (let [controller '[(assign a (const 10))
                       (save a)
                       (assign a (const 99))
                       (restore b)]
          result (-> (machine)
                     (vm/machine-allocate-register 'a)
                     (vm/machine-allocate-register 'b)
                     (vm/install-controller controller)
                     vm/execute-machine)]
      (is (= 99 (vm/machine-lookup-register result 'a)))
      (is (= 10 (vm/machine-lookup-register result 'b)))
      (is (empty? (vm/machine-stack result))))))

(deftest make-machine-builds-runnable-controller
  (testing "high-level constructor installs registers, operations, and controller"
    (let [controller '[(assign x (const 40))
                       (assign y (const 2))
                       (assign out (op +) (reg x) (reg y))]
          result (vm/start
                  (vm/make-machine '[x y out]
                                   {'+ +}
                                   controller))]
      (is (= 42 (vm/get-register-contents result 'out)))
      (is (= 3 (vm/get-register-contents result 'pc))))))

(deftest traced-runner-prints-each-step
  (testing "execute-machine-traced prints instruction and state transitions"
    (let [controller '[(assign x (const 1))
                       (assign y (op +) (reg x) (const 2))]
          m (vm/make-machine '[x y] {'+ +} controller)
          out (with-out-str (vm/execute-machine-traced m))]
      (is (re-find #"step 000 \| pc 00 \| \(assign x \(const 1\)\)" out))
      (is (re-find #"before  regs:" out))
      (is (re-find #"step 001 \| pc 01 \| \(assign y \(op \+\) \(reg x\) \(const 2\)\)" out))
      (is (re-find #"after   regs:" out)))))
