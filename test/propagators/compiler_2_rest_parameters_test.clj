(ns propagators.compiler-2-rest-parameters-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.main :as compiler]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.rest-application :as rest-application]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]))

(defn run-compiled [compiled]
  (runner/completed-network
   (runner/run-network (:props compiled) (:net compiled))))

(defn evaluate [source]
  (let [compiled (compiler/compile-source source)]
    (net/network-cell-strongest (run-compiled compiled) (:cell compiled))))

(defn seed-and-run [network id candidate]
  (let [[network tasks] (nb/seed-cell! network tq/empty-queue id candidate)]
    (runner/completed-network (runner/run-network tasks network))))

(deftest rest-parameter-validation
  (doseq [source ["(network [&] 1)" "(network [x & xs y] x)"
                  "(network [& xs & ys] xs)" "(network [x & x] x)"
                  "(network [& [xs]] 1)" "(network [1 & xs] 1)"
                  "(network [x & xs out] (-> x out) (list out))"
                  "(define f (network [x & xs out] (-> x out) (list out)))"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (compiler/compile-source source)) source)))

(deftest anonymous-networks-have-inputs-only
  (is (= 4 (evaluate "((network [x] (+ x 1)) 3)")))
  (doseq [source ["((network [x] x))" "((network [x] x) 3 4)"
                  "((network [] 1) 2)" "((network [x & xs] x))"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid arity"
                          (evaluate source)) source))
  (is (= 3 (evaluate "(let-cell [out] (define f (network [x y] (-> x y) (list y))) (f 3 out) out)")))
  (is (= 3 (evaluate "(let-cell [out] ((network [x y] (-> x y) (list y)) 3 out) out)"))))

(deftest rest-lists-cover-zero-one-and-many
  (is (= :compiler-2/list-empty (evaluate "(list)")))
  (is (= :compiler-2/list-empty (evaluate "((network [& xs] xs))")))
  (is (= :compiler-2/list-empty (evaluate "((network [x & xs] xs) 9)")))
  (is (= 4 (evaluate "((network [& xs] (car xs)) 4)")))
  (is (= 4 (evaluate "((network [& xs] (car xs)) 4)")))
  (is (= 5 (evaluate "((network [x & xs] (+ x (car xs))) 2 3)")))
  (is (= 6 (evaluate "((network [& xs] (car (cdr (cdr xs)))) 4 5 6)")))
  (is (= :compiler-2/list-empty
         (evaluate "((network [& xs] (cdr xs)) 4)"))))

(deftest rest-wrapper-retains-an-ordinary-fixed-arity-closure
  (let [wrapped (evaluate "(network [x & xs] x)")
        {:keys [callable parameters]}
        (get wrapped rest-application/rest-callable-key)
        declaration (application/callable-declaration callable)]
    (is (rest-application/rest-callable? wrapped))
    (is (application/compiler-callable? callable))
    (is (not (rest-application/rest-callable? callable)))
    (is (= {:required '[x] :rest 'xs} parameters))
    (is (= '[x xs] (closure-value/closure-inputs declaration)))
    (is (= declaration (application/callable-declaration wrapped)))))

(deftest application-branch-delegates-ordinary-callables-and-rejects-unknowns
  (let [ordinary (evaluate "(network [x] x)")
        calls (atom [])
        observed (assoc ordinary :gur.flat/body
                        (fn [& args] (swap! calls conj args) :delegated))]
    (is (not (rest-application/rest-callable? ordinary)))
    (is (= :delegated
           (rest-application/apply-callable observed :context [:arg] :result)))
    (is (= '[(:context [:arg] :result)] @calls))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported"
                          (rest-application/apply-callable {} nil [] nil)))))

(deftest rest-application-tracing-retains-original-arguments
  (doseq [[source arity] [["((network [& xs] 1))" 0]
                         ["((network [x & xs] x) 1 2 3)" 3]]]
    (let [compiled (compiler/compile-source source)
          network (run-compiled compiled)
          topology (application/application-topology-for-result network (:cell compiled))
          invocation (nth (:application-id topology) 2)]
      (is (= arity (count (:argument-ids topology))))
      (is (= (subvec invocation 1) (:argument-ids topology))))))

(def pipeline-definitions
  "(define walk (network [x stages out] (when (switch true (= stages :compiler-2/list-empty)) (-> x out)) (-> (when (switch true (not (= stages :compiler-2/list-empty))) (walk ((car stages) x) (cdr stages) out)) out) (list out)))\n(define pipe (network [x & stages] (let-cell [out] (walk x stages out) out)))\n(define walk-right (network [x stages out] (when (switch true (= stages :compiler-2/list-empty)) (-> x out)) (-> (when (switch true (not (= stages :compiler-2/list-empty))) (let-cell [tail-result] (walk-right x (cdr stages) tail-result) (-> ((car stages) tail-result) out))) out) (list out)))\n(define compose (network [& stages] (network [x] (let-cell [out] (walk-right x stages out) out))))\n(define inc1 (network [x] (+ x 1)))\n(define double (network [x] (* x 2)))")

(deftest lain-defined-pipelines-and-returned-closures
  (doseq [[call expected] [["(pipe 3)" 3]
                           ["(pipe 3 inc1)" 4]
                           ["(pipe 3 inc1 double inc1 double)" 18]
                           ["((compose) 3)" 3]
                           ["((compose inc1 double inc1 double) 3)" 15]
                           [(str "(pipe 3 " (str/join " " (repeat 12 "inc1")) ")") 15]]]
    (is (= expected (evaluate (str "(let-cell [] " pipeline-definitions call ")"))) call))
  (is (= 15 (evaluate
             "(let-cell [] (define make (network [bias] (network [x & xs] (+ bias (+ x (car xs)))))) ((make 10) 2 3))"))))

(defn external-input [name source]
  (let [id (ids/new-node-id)
        root (env/declare-root
              (nb/ensure-cell net/empty-net id) (ids/new-node-id)
              (conj (vec (basis/default-bindings)) [name (env/cell-binding id)]))
        compiled (compiler/compile-source source (:env root)
                                          {:net (:net root)
                                           :environment-props (:props root)})]
    {:id id :root root :compiled compiled :network (run-compiled compiled)}))

(deftest rest-elements-stay-reactive-and-topology-is-stable
  (let [{:keys [id compiled network]}
        (external-input 'later "((network [& xs] (+ (car xs) 1)) later)")
        ready (seed-and-run network id 4)
        again (runner/completed-network (runner/run-network (:props compiled) ready))]
    (is (= :bool4/nothing (net/network-cell-strongest network (:cell compiled))))
    (is (= 5 (net/network-cell-strongest ready (:cell compiled))))
    (is (= (set (keys (net/net-env ready))) (set (keys (net/net-env again)))))
    (is (= (net/net-graph ready) (net/net-graph again)))))

(deftest rest-can-contain-a-late-network-definition
  (let [{:keys [id root compiled network]}
        (external-input 'stage "((network [& stages] ((car stages) 3)) stage)")
        definition (compiler/compile-source "(network [x] (+ x 1))" (:env root)
                                            {:net network :seed [:late-stage]})
        declared (run-compiled definition)
        ready (seed-and-run declared id
                            (net/network-cell-strongest declared (:cell definition)))]
    (is (= :bool4/nothing (net/network-cell-strongest network (:cell compiled))))
    (is (= 4 (net/network-cell-strongest ready (:cell compiled))))))

(deftest rest-list-does-not-grant-writeback-to-caller
  (let [{:keys [id compiled network]}
        (external-input 'source "((network [& xs] (-> 7 (car xs))) source)")]
    (is (= 7 (net/network-cell-strongest network (:cell compiled))))
    (is (= :bool4/nothing (net/network-cell-strongest network id)))))

(deftest variadic-operator-itself-may-arrive-late
  (let [{:keys [id root compiled network]}
        (external-input 'later "(later 3 4 5)")
        definition (compiler/compile-source "(network [x & xs] (+ x (car xs)))"
                                            (:env root)
                                            {:net network :seed [:late-variadic]})
        declared (run-compiled definition)
        ready (seed-and-run declared id
                            (net/network-cell-strongest declared (:cell definition)))]
    (is (= :bool4/nothing (net/network-cell-strongest network (:cell compiled))))
    (is (= 7 (net/network-cell-strongest ready (:cell compiled))))))

(deftest example-file-compiles-without-environment-extension
  (is (= 36 (evaluate
             (str "(let-cell [] "
                  (slurp "examples/lain/variadic-composition.lain")
                  " (+ piped (+ composed identity-result)))")))))
