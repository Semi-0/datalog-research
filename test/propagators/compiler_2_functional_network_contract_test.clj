(ns propagators.compiler-2-functional-network-contract-test
  "Executable contract checks; these are not an all-context equivalence proof."
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.runtime.returned-outputs :as experiment]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.runtime.linked-application :as linked]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2-functional-network-test :as fixture]
            [propagators.cells.value :as value]
            [propagators.gur :as gur]
            [propagators.gur.flat :as flat]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn result [source]
  (let [compiled (compiler/compile-source source)]
    (net/network-cell-strongest (fixture/run compiled) (:cell compiled))))

(defn direct-connections [network source target]
  (into []
        (keep (fn [[id {:keys [inputs outputs]}]]
                (if (and (= #{source} inputs) (= #{target} outputs))
                  id
                  nil)))
        (net/net-graph network)))

(deftest positional-call-and-list-apply-agree
  (let [compiled (compiler/compile-source
                  "(let [] (define f (network (x y) (+ x y)))
                     (list (f 2 3) (apply f (list 2 3))))")]
    (is (= [5 5] (fixture/values (fixture/run compiled) (:cell compiled))))))

(deftest both-call-shapes-reject-invalid-arity
  (doseq [call ["(f 2)" "(apply f (list 2))"]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"invalid arity"
         (result (str "(let [] (define f (network (x y) (+ x y))) " call ")"))))))

(deftest captured-lexical-value-can-arrive-after-application
  (let [compiled (compiler/compile-source
                  "(let-cell [bias]
                     (define f (network (x) (+ x bias)))
                     (list bias (f 40)))")
        initial (fixture/run compiled)
        [bias answer] (fixture/list-ids initial (:cell compiled))
        seeded (nb/seed-cell initial bias 2)
        activated (fixture/wake seeded [bias])]
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (= 42 (net/network-cell-strongest activated answer)))))

(deftest returned-closure-retains-live-captured-input
  (let [compiled (compiler/compile-source
                  "(let-cell [bias]
                     (define make (network (captured)
                       (network (x) (+ captured x))))
                     (define f (make bias)) (list bias (f 40)))")
        initial (fixture/run compiled)
        [bias answer] (fixture/list-ids initial (:cell compiled))
        seeded (nb/seed-cell initial bias 2)
        activated (fixture/wake seeded [bias])]
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (= 42 (net/network-cell-strongest activated answer)))))

(deftest every-returned-list-position-has-a-real-output-boundary
  (let [compiled (compiler/compile-source
                  "((network (x) (list x x (+ x 1))) 4)")
        network (fixture/run compiled)
        members (fixture/list-ids network (:cell compiled))
        ports (->> (experiment/outputs network)
                   (sort-by (comp second key))
                   (mapv val))]
    (is (= 3 (count members) (count ports)))
    (is (= 3 (count (set ports))))
    (is (= [4 4 5] (mapv #(net/network-cell-strongest network %) ports)))
    (doseq [[member port] (map vector members ports)]
      (is (= 1 (count (direct-connections network member port)))))))

(deftest output-boundary-stays-live-when-member-value-arrives-late
  (let [compiled (compiler/compile-source
                  "(let-cell [source]
                     (list source ((network (x) (list x)) source)))")
        initial (fixture/run compiled)
        [source] (fixture/list-ids initial (:cell compiled))
        [port] (vals (experiment/outputs initial))
        seeded (nb/seed-cell initial source 42)
        activated (fixture/wake seeded [source])
        repeated (fixture/wake activated [source])]
    (is (some? port))
    (is (value/nothing? (net/network-cell-strongest initial port)))
    (is (= 42 (net/network-cell-strongest activated port)))
    (is (= (experiment/outputs initial) (experiment/outputs activated)))
    (is (= (net/net-graph activated) (net/net-graph repeated)))))

(deftest member-ports-extend-topology-without-changing-primary-return
  (let [compiled (compiler/compile-source
                  "((network (x) (list (+ x 1))) 4)")
        network (fixture/run compiled)
        frames (get (net/network-dict-entry network gur/name-bindings-key)
                    flat/frame-scope)
        application (first (filter #(= (:cell compiled) (peek %)) (keys frames)))
        apply-id (gur/stable-node-id [application :apply-prop])
        declared-outputs (:outputs (get (net/net-graph network) apply-id))
        ports (vals (experiment/outputs network))]
    (is (some? application))
    (is (contains? declared-outputs (:cell compiled)))
    (is (= 1 (count ports)))
    (is (every? declared-outputs ports))
    (is (every? #(contains? (:inputs (get (net/net-graph network) %)) apply-id)
                ports))))
