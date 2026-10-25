;; Diagnostic for (list ...) with an unresolved final tail. Run from repo root:
;; clojure -Sdeps '{:aliases {:probe {:extra-paths ["test" "editor/src"]}}}' -M:probe experiments/compiler-2/compound-if-probes/compiler2-open-tail-controls.clj
(require '[propagators.compiler-2-functional-network-gur-test :as g]
         '[propagators.cells.value :as value]
         '[propagators.experimental.visualization.data :as data])

(def open-map
  "(define map-open (network [f xs]
     (let-cell [answer]
       (when xs (define answer (cons (f (car xs)) (map-open f (cdr xs)))))
       answer)))")

(def open-filter
  "(define filter-open (network [predicate xs]
     (let-cell [answer]
       (when xs
         (let [head (car xs) tail (filter-open predicate (cdr xs)) keep (predicate head)]
           (when (switch true keep) (define answer (cons head tail)))
           (when (switch true (not keep)) (define answer tail))))
       answer)))")

(def results (atom []))
(defn probe [label definitions expression expected]
  (let [work (future
               (try
                 (let [actual (data/payload (g/scalar definitions expression))]
                   {:case label :expected expected
                    :actual (if (or (number? actual) (keyword? actual) (boolean? actual)) actual :compound)
                    :pass (= expected actual)})
                 (catch Throwable error {:case label :error (.getMessage error) :pass false})))
        result (deref work 20000 ::timeout)]
    (if (= ::timeout result)
      (do (future-cancel work)
          (swap! results conj {:case label :timeout-ms 20000 :pass false})
          (prn (peek @results)))
      (do (swap! results conj result) (prn result)))))

(probe :empty-list "" "(list)" value/nothing)
(probe :final-tail "" "(cdr (cdr (cdr (list 1 2 3))))" value/nothing)
(probe :empty-through-call "(define relay (network [x] x))" "(relay (list))" value/nothing)
(probe :map-empty (str open-map g/mappers) "(map-open increment (list))" value/nothing)
(probe :mapped-final-tail (str open-map g/mappers)
       "(cdr (cdr (cdr (map-open increment (list 1 2 3)))))" value/nothing)
(doseq [[kind definitions mapper]
        [[:stdlib g/map-list "map-list"] [:open-guard open-map "map-open"]]
        [stages expression expected]
        [[1 (str "(" mapper " increment (list 1 2 3))") [2 3 4]]
         [2 (str "(" mapper " double (" mapper " increment (list 1 2 3)))") [4 6 8]]
         [3 (str "(" mapper " decrement (" mapper " double (" mapper " increment (list 1 2 3))))") [3 5 7]]]
        index (range 3)]
  (probe [kind stages index] (str definitions g/mappers)
         (str "(car " (nth (iterate #(str "(cdr " % ")") expression) index) ")")
         (nth expected index)))
(doseq [[index expected] [[0 5] [1 7]]]
  (probe [:map-filter-map index] (str open-map open-filter g/mappers)
         (str "(car " (nth (iterate #(str "(cdr " % ")")
                            "(map-open increment (filter-open (network [x] (>= x 4)) (map-open double (list 1 2 3))))") index) ")")
         expected))
(prn {:checks (count @results) :failures (count (remove :pass @results))})
(shutdown-agents)
(System/exit (if (every? :pass @results) 0 1))
