(ns propagators.compiler-2-functional-network-gur-test
  "Language-level GUR programs, without Fibonacci or HOP host specializations."
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.runtime.returned-outputs :as experiment]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.runtime.linked-application :as linked]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2-functional-network-test :as fixture]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur :as gur]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]
            [propagators.network-builder :as nb]))

(def fibonacci
  "(define fib
     (network (n)
       (let-cell [answer]
         (when (switch true (< n 2)) (define answer n))
         (when (switch true (>= n 2))
           (define answer (+ (fib (- n 1)) (fib (- n 2)))))
         answer)))")

(def selected-fibonacci
  "(define fib
     (network (n)
       ((if (< n 2)
          (network () n)
          (network () (+ (fib (- n 1)) (fib (- n 2))))))))")

(def map-list
  "(define map-list
     (network (f xs)
       (let-cell [answer]
         (when (switch true (= xs :compiler-2/list-empty))
           (define answer (list)))
         (when (switch true (not (= xs :compiler-2/list-empty)))
           (define answer
             (cons (f (car xs)) (map-list f (cdr xs)))))
         answer)))")

(def filter-list
  "(define filter-list
     (network (predicate xs)
       (let-cell [answer]
         (when (switch true (= xs :compiler-2/list-empty))
           (define answer (list)))
         (when (switch true (not (= xs :compiler-2/list-empty)))
           (let [head (car xs)
                 tail (filter-list predicate (cdr xs))
                 keep (predicate head)]
             (when (switch true keep) (define answer (cons head tail)))
             (when (switch true (not keep)) (define answer tail))))
         answer)))")

(def zip-with
  "(define zip-with
     (network (f xs ys)
       (let-cell [answer]
         (when (switch true (= xs :compiler-2/list-empty))
           (define answer (list)))
         (when (switch true (not (= xs :compiler-2/list-empty)))
           (when (switch true (= ys :compiler-2/list-empty))
             (define answer (list)))
           (when (switch true (not (= ys :compiler-2/list-empty)))
             (define answer
               (cons (f (car xs) (car ys))
                     (zip-with f (cdr xs) (cdr ys))))))
         answer)))")

(def mappers
  "(define increment (network (x) (+ x 1)))
   (define double (network (x) (* x 2)))
   (define decrement (network (x) (- x 1)))")

(defn compile-program [definitions expression]
  (compiler/compile-source (str "(let [] " definitions " " expression ")")))

(defn scalar [definitions expression]
  (let [compiled (compile-program definitions expression)]
    (net/network-cell-strongest (fixture/run compiled) (:cell compiled))))

(defn list-result [definitions expression]
  (let [compiled (compile-program definitions expression)]
    (fixture/values (fixture/run compiled) (:cell compiled))))

(defn- callable-application-key [network callable]
  (let [frames (get (net/network-dict-entry network gur/name-bindings-key)
                    [:gur.flat :frames])
        matches (for [[[_ closure-id :as key] _] frames
                      :when (= callable
                               (net/network-cell-strongest network closure-id))]
                  key)]
    (is (= 1 (count matches)) "One application of the selected callable")
    (first matches)))

(defn- application-prop [network callable]
  (gur/stable-node-id [(callable-application-key network callable) :apply-prop]))

(defn- descendant? [network ancestor target]
  (let [relationships (net/net-relationship network)]
    (loop [pending [ancestor] seen #{}]
      (if-let [node (peek pending)]
        (if (= node target)
          true
          (recur (into (pop pending)
                       (remove seen (relationship/children relationships node)))
                 (conj seen node)))
        false))))

(deftest late-operator-registers-child-and-parent-output-ports
  (let [compiled (compiler/compile-source
                  "(let-cell [n late]
                     (define definition (network (x) (list (+ x 1))))
                     (define invoke (network (f x) (f x)))
                     (list n late definition invoke (invoke late n)))")
        initial (fixture/run compiled)
        [input operator definition invoke answer]
        (fixture/list-ids initial (:cell compiled))
        callable (net/network-cell-strongest initial definition)
        parent-key (callable-application-key initial
                     (net/network-cell-strongest initial invoke))
        parent (relationship/node-key [:outer]
                                      (gur/stable-node-id [parent-key :apply-prop]))
        declared (fixture/wake (nb/seed-cell initial operator callable) [operator])
        child-key (callable-application-key declared callable)
        child-id (gur/stable-node-id [child-key :apply-prop])
        child (relationship/node-key [:outer] child-id)
        ports (experiment/outputs declared)
        completed (fixture/wake (nb/seed-cell declared input 4) [input])
        repeated (fixture/wake completed [operator input])]
    (is (value/nothing? (net/network-cell-strongest initial operator)))
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (empty? (experiment/outputs initial)))
    ;; The pending application already belongs to its parent before its callable arrives.
    (is (contains? (net/net-graph initial) child-id))
    (is (contains? (relationship/children (net/net-relationship initial) parent) child))
    (is (empty? (relationship/children (net/net-relationship initial) child)))
    (is (seq (relationship/children (net/net-relationship declared) child)))
    (is (= 2 (count ports)))
    (doseq [[key owner] [[parent-key parent] [child-key child]]]
      (let [port (get ports [key 0])]
        (is (some? port))
        (let [owner-id (second owner)]
          (is (contains? (:outputs (get (net/net-graph declared) owner-id)) port))
          (is (contains? (:inputs (get (net/net-graph declared) port)) owner-id)))
        (is (descendant? declared owner (relationship/node-key [:outer] port)))
        (is (value/nothing? (net/network-cell-strongest declared port)))
        (is (= 5 (net/network-cell-strongest completed port)))))
    (is (= [5] (fixture/values completed answer)))
    (is (= ports (experiment/outputs completed) (experiment/outputs repeated)))
    (is (= (net/net-graph completed) (net/net-graph repeated)))
    (is (= (net/net-relationship completed) (net/net-relationship repeated)))))

(deftest higher-order-application-records-children-before-input-arrives
  (let [compiled (compiler/compile-source
                  "(let-cell [n]
                     (define increment (network (x) (+ x 1)))
                     (define invoke (network (f x) (f x)))
                     (list n invoke increment (invoke increment n)))")
        initial (fixture/run compiled)
        [input invoke-cell increment-cell answer]
        (fixture/list-ids initial (:cell compiled))
        invoke-id (application-prop initial (net/network-cell-strongest initial invoke-cell))
        increment-id (application-prop initial (net/network-cell-strongest initial increment-cell))
        parent (relationship/node-key [:outer] invoke-id)
        child (relationship/node-key [:outer] increment-id)
        relationships (net/net-relationship initial)
        grandchildren (relationship/children relationships child)
        addition-ids (for [[id _] (net/net-graph initial)
                           :when (= :primitive (prop/prop-name
                                        (net/network-lookup-propagator initial id)))]
                       id)
        result (fixture/wake (nb/seed-cell initial input 4) [input])
        repeated (fixture/wake result [input])]
    ;; The callable and cell addresses suffice; the numeric input is still empty.
    (is (value/nothing? (net/network-cell-strongest initial input)))
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (contains? (relationship/children relationships parent) child))
    (is (contains? (relationship/parents relationships child) parent))
    (is (= 1 (count addition-ids)))
    (is (some #(contains? grandchildren %)
              (relationship/parents relationships
                                    (relationship/node-key [:outer] (first addition-ids)))))
    (is (= 5 (net/network-cell-strongest result answer)))
    (is (= grandchildren
           (relationship/children (net/net-relationship result) child)))
    (is (every? (fn [[id node]]
                  (if (prop/prop? (get (net/net-env initial) id))
                    (= node (get (net/net-graph result) id))
                    true))
                (net/net-graph initial)))
    (is (= (net/net-graph result) (net/net-graph repeated)))
    (is (= (net/net-relationship result) (net/net-relationship repeated)))))

(deftest fibonacci-base-cases
  (is (= 0 (scalar fibonacci "(fib 0)")))
  (is (= 1 (scalar fibonacci "(fib 1)"))))

(deftest fibonacci-recursively-declares-both-branches
  (is (= 3 (scalar fibonacci "(fib 4)"))))

(deftest fibonacci-applies-only-the-selected-network
  (is (= 8 (scalar selected-fibonacci "(fib 6)"))))

(deftest fibonacci-waits-for-a-late-input
  (let [compiled (compiler/compile-source
                  (str "(let-cell [n] " fibonacci " (list n (fib n)))"))
        initial (fixture/run compiled)
        [n answer] (fixture/list-ids initial (:cell compiled))
        seeded (nb/seed-cell initial n 3)
        activated (fixture/wake seeded [n])]
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (= 2 (net/network-cell-strongest activated answer)))))

(deftest three-map-stages-compose-through-compound-lists
  (is (= [3 5 7]
         (list-result (str map-list mappers)
                      "(map-list decrement
                         (map-list double
                           (map-list increment (list 1 2 3))))"))))

(deftest map-filter-map-compose-through-compound-lists
  (is (= [5 7]
         (list-result (str map-list filter-list mappers)
                      "(map-list increment
                         (filter-list (network (x) (>= x 4))
                           (map-list double (list 1 2 3))))"))))

(deftest higher-order-network-combines-two-lists-then-maps
  (is (= [12 23]
         (list-result (str map-list zip-with mappers)
                      "(map-list increment
                         (zip-with (network (a b) (+ a b))
                           (list 1 2) (list 10 20)))"))))

(deftest late-tail-grows-a-three-stage-pipeline-without-repeat-growth
  (let [compiled
        (compiler/compile-source
         (str "(let-cell [tail] " map-list mappers
              " (define xs (cons 1 tail))"
              " (define ys (map-list decrement"
              "   (map-list double (map-list increment xs))))"
              " (list tail ys))"))
        initial (fixture/run compiled)
        [tail output] (fixture/list-ids initial (:cell compiled))
        first-member (obj/existing-slot-cell-id initial :car output)
        [head end] (mapv gur/stable-node-id [[:gur-test :head] [:gur-test :end]])
        prepared (reduce nb/ensure-cell initial [head end])
        [props attached] ((obj/p:cons head end tail) prepared)
        seeded (-> attached (nb/seed-cell head 2)
                   (nb/seed-cell end basis/list-empty-marker))
        activated (nb/run-propagators seeded
                                     (concat props (nb/neighbor-propagator-ids seeded tail)))
        repeated (fixture/wake activated [tail head])]
    (is (= 3 (net/network-cell-strongest initial first-member)))
    (is (= [3 5] (fixture/values activated output)))
    (is (> (count (get (net/network-dict-entry activated gur/name-bindings-key)
                       [:gur.flat :frames]))
           3))
    (is (< (count (net/net-graph initial)) (count (net/net-graph activated))))
    (is (every? (fn [[key id]] (= id (get (experiment/outputs activated) key)))
                (experiment/outputs initial)))
    (is (= (net/net-graph activated) (net/net-graph repeated)))
    (is (= (set (keys (net/net-env activated)))
           (set (keys (net/net-env repeated)))))))
