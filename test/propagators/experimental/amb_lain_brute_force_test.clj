(ns propagators.experimental.amb-lain-brute-force-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [examples.lain.amb.brute-force :as brute]
            [examples.lain.amb.solver-lain :as solver]
            [propagators.network-cache :as cache]))

(defn constraint-form [source]
  (first (filter #(and (seq? %) (= 'define (first %)) (= 'constraint (second %)))
                 (drop 2 (read-string source)))))

(defn candidate-source [a b c]
  (let [definitions (str/join " " (map pr-str (take 4 (drop 2 (read-string brute/source)))))]
    (str "(let [] " definitions
         " (flat-map (network (a) (flat-map (network (b)"
         " (flat-map (network (c) ((if (constraint a b c)"
         " (network () (list (list a b c))) (network () (list))))) (list " c ")))"
         " (list " b "))) (list " a ")))")))

(deftest identical-question-and-lain-enumeration
  (is (= (constraint-form solver/source) (constraint-form brute/source)))
  (is (str/includes? brute/source "(define flat-map"))
  (is (str/includes? brute/source "(constraint a b c)"))
  (is (= 3 (count (filter #(and (seq? %) (= 'network (first %))
                               (contains? #{'(a) '(b) '(c)} (second %)))
                         (tree-seq coll? seq (read-string brute/source)))))))

(deftest rejected-and-accepted-tuples-are-evaluated-in-lain
  (binding [cache/*cache* nil]
    (doseq [[tuple expected] [[[1 1 1] []] [[3 4 5] [[3 4 5]]]]]
      (let [source (apply candidate-source tuple)
            result (brute/execute (brute/compile-source source) {:ms 30000 :activations 100000})]
        (is (= :completed (:status result)))
        (is (= expected (:answers result)))
        (is (pos? (:steps result)))))))

(deftest bounding-is-not-exhaustion
  (let [result (brute/execute (brute/compile-bound 2) {:ms 30000 :activations 1})]
    (is (= :bounded (:status result)))
    (is (nil? (:answers result)))
    (is (true? (get-in result [:diagnostic :experiment/bounded])))))
