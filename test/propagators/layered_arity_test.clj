(ns propagators.layered-arity-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]))

(def new-files
  ["propagators/layered/dispatcher.clj" "propagators/layered/runtime.clj"
   "propagators/layered/procedure.clj" "propagators_layered_bench.clj"
   "test/propagators/layered_dispatcher_test.clj"
   "test/propagators/layered_arity_test.clj"])

(def modified-definitions
  {"propagators/layered.clj" #{'p:apply-layered}
   "propagators/stdlib/provenance_arithmetic.clj" #{'closures}
   "propagators/stdlib/arithmetic.clj" #{'base-extension}
   "propagators/stdlib/arithmetic/provenance.clj"
   #{'argument-provenance 'result-provenance 'arithmetic-provenance-closure}
   "propagators/stdlib/arithmetic/intensity.clj" #{'arithmetic-intensity-closure}
   "propagators/stdlib/support.clj" #{'procedure}
   "propagators/stdlib/premise_state.clj" #{'procedure}
   "propagators/experimental/ttms_primitives.clj" #{'install-call}
   "propagators/experimental/ttms_branching.clj" #{'install-procedure}
   "propagators/experimental/visualization/layered_primitives.clj"
   #{'sources-closure 'install-procedure}
   "test/propagators/layered_procedure_test.clj"
   #{'units-closure-value 'install-procedure-layer-value}
   "test/propagators/support_glitch_test.clj" #{'addition}
   "test/propagators/premise_transport_test.clj" #{'install-procedure}
   "examples/lain/visualization_combinators/support_retraction_demo.clj" #{'addition}})

(defn forms [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (loop [result []]
      (let [form (read {:eof ::end} reader)]
        (if (= ::end form)
          result
          (recur (conj result form)))))))

(defn- parameter-vectors [form]
  (let [kind (first form)
        body (if (contains? #{'defn 'defn-} kind)
               (drop 2 form)
               (rest form))
        body (drop-while #(or (symbol? %) (string? %) (map? %)) body)]
    (if (vector? (first body))
      [(first body)]
      (keep (fn [arity]
              (if (and (seq? arity) (vector? (first arity)))
                (first arity)
                nil)) body))))

(defn violations [form]
  (let [found (atom [])]
    (walk/prewalk
     (fn [node]
       (if (and (seq? node) (contains? #{'defn 'defn- 'fn 'fn*} (first node)))
         (doseq [parameters (parameter-vectors node)]
           (let [counted (count (remove #{'&} parameters))]
             (if (> counted 4)
               (swap! found conj {:parameters parameters :count counted})
               nil)))
         nil)
       node)
     form)
    @found))

(deftest four-argument-limit
  (doseq [path new-files
          form (forms path)]
    (is (empty? (violations form)) (str path ": " (violations form))))
  (doseq [[path selected] modified-definitions
          form (forms path)
          :when (and (seq? form) (contains? selected (second form)))]
    (is (empty? (violations form)) (str path ": " (violations form)))))
