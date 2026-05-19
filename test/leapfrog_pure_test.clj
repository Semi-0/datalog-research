(ns leapfrog-pure-test
  (:require [clojure.test :refer [deftest is]]
            [leapfrog :as lf]
            [leapfrog-pure :as pure]))

(def R (pure/make-relation :R [:x :y] [[1 2] [1 3] [2 2] [3 4]]))
(def S (pure/make-relation :S [:y :z] [[2 5] [2 6] [3 5] [4 9]]))
(def T (pure/make-relation :T [:x :z] [[1 5] [1 7] [2 6] [3 9]]))

(defn- legacy-R [] (lf/make-relation :R [:x :y] [[1 2] [1 3] [2 2] [3 4]]))
(defn- legacy-S [] (lf/make-relation :S [:y :z] [[2 5] [2 6] [3 5] [4 9]]))
(defn- legacy-T [] (lf/make-relation :T [:x :z] [[1 5] [1 7] [2 6] [3 9]]))

(deftest lftj-bindings-match-legacy
  (is (= (set (lf/lftj [(legacy-R) (legacy-S) (legacy-T)] [:x :y :z]))
         (set (pure/lftj [R S T] [:x :y :z])))))

(deftest lftj-tuples-match-legacy
  (is (= (set (lf/lftj [(legacy-R) (legacy-S) (legacy-T)] [:x :y :z] :tuples))
         (set (pure/lftj [R S T] [:x :y :z] :tuples)))))

(deftest bad-join-order-soft-fails-in-lftj
  (is (= [] (pure/lftj [R S T] [:z :x :y] :tuples))))

(deftest bad-join-order-throws-contextual-ex
  (let [rule {:head [:out [:x :y :z]]
              :body [[:R [:x :y]] [:S [:y :z]] [:T [:x :z]]]}]
    (is (some? (pure/validate-join-order [R S T] [:z :x :y])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"no relation active for join variable"
         (throw (pure/invalid-join-order-ex [R S T] [:z :x :y] rule))))))

(deftest semi-naive-path-rules-match-legacy
  (let [edges #{[1 2] [2 3] [3 4]}
        facts {:edge edges}]
    (is (= (lf/semi-naive facts lf/path-rules)
           (pure/semi-naive facts pure/path-rules)))))
