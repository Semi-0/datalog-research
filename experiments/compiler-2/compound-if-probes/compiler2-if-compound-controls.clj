(require '[propagators.compiler-2.cps-core :as compiler]
         '[propagators.compiler-2-combinator-test :as combinator]
         '[propagators.experimental.visualization.data :as data]
         '[propagators.network :as net]
         '[propagators.network-builder :as builder])
(defn evaluate [source]
  (let [compiled (compiler/compile-source source)
        network (builder/run-propagators (:net compiled) (:props compiled))]
    (data/payload (net/network-cell-strongest network (:cell compiled)))))
(doseq [[label source expected]
        [[:list-control "(car (list 1 2))" 1]
         [:if-returns-list "(car (if true (list 1 2) (list 3 4)))" 1]
         [:if-returns-list-through-call "(car ((network [x] x) (if true (list 1 2) (list 3 4))))" 1]
         [:if-before-two-slot-hops "(car (cdr (if true (list 1 2) (list 3 4))))" 2]
         [:if-after-list-access "(if true (car (list 1 2)) (car (list 3 4)))" 1]]]
  (try
    (let [actual (evaluate source)]
      (prn {:case label :pass (= expected actual) :expected expected
            :actual (if (or (keyword? actual) (number? actual)) actual (str (class actual)))}))
    (catch Exception e (prn {:case label :error (.getMessage e)}))))
(doseq [[label expression expected]
        [[:struct-control "(struct :nested (struct :value 10))" 10]
         [:if-returns-struct "(if true (struct :nested (struct :value 10)) (struct :nested (struct :value 20)))" 10]
         [:if-returns-struct-through-call "((network [x] x) (if true (struct :nested (struct :value 10)) (struct :nested (struct :value 20))))" 10]]]
  (try
    (let [loaded (combinator/session (str "(define answer (|> " expression " (slot :nested) (slot :value)))"))
          actual (combinator/binding-value loaded 'answer)]
      (prn {:case label :pass (= expected actual) :expected expected
            :actual (if (or (keyword? actual) (number? actual)) actual (str (class actual)))}))
    (catch Exception e (prn {:case label :error (.getMessage e)}))))
