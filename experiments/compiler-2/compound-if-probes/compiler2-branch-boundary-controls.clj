;; Compare compound returns through when, if, and explicit zero-argument networks.
;; Same classpath/run command as compiler2-open-tail-controls.clj.
(require '[propagators.compiler-2-functional-network-gur-test :as g]
         '[propagators.experimental.visualization.data :as data])

(def cases
  [[:when "(define m (network [f xs] (let-cell [answer] (when xs (define answer (cons (f (car xs)) (m f (cdr xs)))) ) answer)))"]
   [:when-if "(define m (network [f xs] (let-cell [answer] (when xs (define answer (if true (cons (f (car xs)) (m f (cdr xs))) (list)))) answer)))"]
   [:when-thunk "(define m (network [f xs] (let-cell [answer] (when xs (define answer ((network [] (cons (f (car xs)) (m f (cdr xs))))))) answer)))"]
   [:when-identity "(define relay (network [v] v)) (define m (network [f xs] (let-cell [answer] (when xs (define answer (relay (cons (f (car xs)) (m f (cdr xs)))))) answer)))"]
   [:when-if-prebuilt "(define m (network [f xs] (let-cell [answer] (when xs (let [built (cons (f (car xs)) (m f (cdr xs)))] (define answer (if true built (list))))) answer)))"]
   [:value-if "(define m (network [f xs] (if (value? xs) (cons (f (car xs)) (m f (cdr xs))) (list))))"]
   [:network-if "(define m (network [f xs] (if (network? xs) (cons (f (car xs)) (m f (cdr xs))) (list))))"]
   [:when-value-if "(define m (network [f xs] (let-cell [answer] (when xs (define answer (if (value? xs) (cons (f (car xs)) (m f (cdr xs))) (list)))) answer)))"]
   [:when-if-false "(define m (network [f xs] (let-cell [answer] (when xs (define answer (if false (list) (cons (f (car xs)) (m f (cdr xs)))))) answer)))"]])

(doseq [[label definitions] cases index (range 3)]
  (let [expression (str "(car " (nth (iterate #(str "(cdr " % ")") "(m increment (list 1 2 3))") index) ")")
        work (future
               (try
                 (let [actual (data/payload (g/scalar (str definitions g/mappers) expression))]
                   {:case label :index index :expected (+ 2 index)
                    :actual (if (or (number? actual) (keyword? actual)) actual :compound)
                    :pass (= (+ 2 index) actual)})
                 (catch Throwable error {:case label :index index :error (.getMessage error)})))
        result (deref work 20000 ::timeout)]
    (if (= ::timeout result)
      (do (future-cancel work) (prn {:case label :index index :timeout-ms 20000}))
      (prn result))))
(shutdown-agents)
