;; Diagnostic only: suppress an information-free pending selector publication.
;; No production conditional code is changed. Same classpath as the other probes.
(require '[propagators.compiler-2-functional-network-gur-test :as g]
         '[propagators.cells.value :as value]
         '[propagators.datastructures.layered-value :as datum]
         '[propagators.stdlib.premise-state :as state]
         '[propagators.stdlib.conditional]
         '[propagators.experimental.visualization.data :as data])

(def definition
  "(define m (network [f xs] (if (value? xs) (cons (f (car xs)) (m f (cdr xs))) (list))))")

(def publish-var (ns-resolve 'propagators.stdlib.conditional 'publish))
(def publish @publish-var)
(def suppressed (atom 0))
(defn skip-information-free-pending [result]
  (let [base (if (datum/layer-present? result :base) (datum/layer-value result :base) result)]
    (if (and (value/nothing? base)
             (empty? (datum/support-of result))
             (empty? (state/states-of result)))
      (do (swap! suppressed inc) value/nothing)
      (publish result))))

(def checks (atom []))
(doseq [[stages expression expected]
        [[1 "(m increment (list 1 2 3))" [2 3 4]]
         [2 "(m double (m increment (list 1 2 3)))" [4 6 8]]
         [3 "(m decrement (m double (m increment (list 1 2 3))))" [3 5 7]]]
        index (range 3)]
  (let [expression (str "(car " (nth (iterate #(str "(cdr " % ")") expression) index) ")")
        actual (with-redefs-fn {publish-var skip-information-free-pending}
                 #(data/payload (g/scalar (str definition g/mappers) expression)))
        result {:stages stages :index index :expected (nth expected index)
                :actual actual :pass (= (nth expected index) actual)}]
    (swap! checks conj result)
    (prn result)))
(prn {:checks (count @checks) :failures (count (remove :pass @checks)) :suppressed @suppressed})
(shutdown-agents)
(System/exit (if (every? :pass @checks) 0 1))
