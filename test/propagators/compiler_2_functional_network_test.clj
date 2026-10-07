(ns propagators.compiler-2-functional-network-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compiler-2.runtime.returned-outputs :as experiment]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.runtime.linked-application :as linked]
            [propagators.compiler-2.cps-core :as language]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur :as gur]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(def addition-definition
  "(define constrain-+
     (network (a b c)
       (-> (+ a b) c)
       (-> (- c b) a)
       (-> (- c a) b)
       (list a b c)))")

(defn source [seeds]
  (str "(let-cell [a b c] " addition-definition " " seeds
       " (constrain-+ a b c) (list a b c))"))

(defn run [compiled]
  (nb/run-propagators (:net compiled) (:props compiled)))

(defn list-ids [network root]
  (loop [id root result []]
    (if (= basis/list-empty-marker (net/network-cell-strongest network id))
      result
      (let [head (obj/existing-slot-cell-id network :car id)
            tail (obj/existing-slot-cell-id network :cdr id)]
        (if (and head tail)
          (recur tail (conj result head))
          (throw (ex-info "List lacks addressable topology" {:id id})))))))

(defn values [network root]
  (mapv #(net/network-cell-strongest network %) (list-ids network root)))

(def constraint-builder
  "(define define-constraint
     (network (name definition-network)
       (define name
         (network (args)
           (apply definition-network args)
           args))))")

(defn constructed-source [seeds]
  (str "(let-cell [a b c constructed] " addition-definition " " constraint-builder
       " (define-constraint constructed constrain-+) " seeds
       " (constructed (list a b c)) (list a b c))"))

(deftest ordinary-apply-builds-a-constraint-in-all-three-directions
  (doseq [seeds ["(-> 2 a) (-> 3 b)"
                 "(-> 3 b) (-> 5 c)"
                 "(-> 2 a) (-> 5 c)"]]
    (testing seeds
      (let [compiled (language/compile-source (constructed-source seeds))
            result (run compiled)]
        (is (= [2 3 5] (values result (:cell compiled))))
        (is (= 6 (count (experiment/outputs result))))))))

(deftest ordinary-apply-constructor-preserves-late-member-values
  (let [compiled (language/compile-source (constructed-source ""))
        initial (run compiled)
        [a b c] (list-ids initial (:cell compiled))
        seeded (-> initial (nb/seed-cell b 3) (nb/seed-cell c 5))
        result (nb/run-propagators seeded
                                   (concat (nb/neighbor-propagator-ids seeded b)
                                           (nb/neighbor-propagator-ids seeded c)))]
    (is (every? value/nothing? (values initial (:cell compiled))))
    (is (= 6 (count (experiment/outputs initial))))
    (is (= 2 (net/network-cell-strongest result a)))))

(deftest ordinary-apply-constructor-does-not-grow-on-reactivation
  (let [compiled (language/compile-source (constructed-source "(-> 2 a) (-> 3 b)"))
        result (run compiled)
        repeated (nb/run-propagators result (:props compiled))]
    (is (= (set (keys (net/net-env result)))
           (set (keys (net/net-env repeated)))))
    (is (= (experiment/outputs result) (experiment/outputs repeated)))))

(deftest scalar-expression-return
  (let [compiled (language/compile-source
                  "((network (a b) (+ a b)) 2 3)")
        result (run compiled)]
    (is (= 5 (net/network-cell-strongest result (:cell compiled))))
    (is (empty? (experiment/outputs result)))))

(deftest addition-in-all-three-directions
  (doseq [seeds ["(-> 2 a) (-> 3 b)"
                 "(-> 3 b) (-> 5 c)"
                 "(-> 2 a) (-> 5 c)"]]
    (testing seeds
      (let [compiled (language/compile-source (source seeds))
            result (run compiled)]
        (is (= [2 3 5] (values result (:cell compiled))))
        (is (= 3 (count (experiment/outputs result))))))))

(deftest production-network-exposes-returned-parameters
  (let [compiled (compiler/compile-expr-with-bindings
                  (parser/parse-string (source "(-> 3 b) (-> 5 c)"))
                  (basis/default-bindings) {:seed :experiment/baseline})
        result (run compiled)
        [a b c] (values result (:cell compiled))]
    (is (= 2 a))
    (is (= [3 5] [b c]))))

(deftest unavailable-members-still-register-ports-and-wake-later
  (let [compiled (language/compile-source (source ""))
        initial (run compiled)
        [a b c] (list-ids initial (:cell compiled))
        with-a (nb/seed-cell initial a 2)
        with-b (nb/seed-cell with-a b 3)
        result (nb/run-propagators
                with-b (concat (nb/neighbor-propagator-ids with-b a)
                               (nb/neighbor-propagator-ids with-b b)))]
    (is (= 3 (count (experiment/outputs initial))))
    (is (every? value/nothing? (values initial (:cell compiled))))
    (is (= 5 (net/network-cell-strongest result c)))))

(deftest equivalent-reactivation-does-not-grow-topology
  (let [compiled (language/compile-source (source "(-> 2 a) (-> 3 b)"))
        result (run compiled)
        repeated (nb/run-propagators result (:props compiled))]
    (is (= (set (keys (net/net-env result)))
           (set (keys (net/net-env repeated)))))
    (is (= (experiment/outputs result) (experiment/outputs repeated)))))

(deftest higher-order-apply-keeps-empty-argument-cells
  (let [source (str "(let-cell [a b c] " addition-definition
                    " (-> 3 b) (-> 5 c)"
                    " (apply constrain-+ (list a b c)) (list a b c))")
        compiled (language/compile-source source)
        result (run compiled)]
    (is (= [2 3 5] (values result (:cell compiled))))))

(deftest returned-closure-captures-network-and-applies-cell-list
  (let [source (str "(let-cell [a b c] " addition-definition
                    " (define wrap (network (definition-network)"
                    "   (network (x y z)"
                    "     (apply definition-network (list x y z))"
                    "     (list x y z))))"
                    " (define relation (wrap constrain-+))"
                    " (-> 3 b) (-> 5 c) (relation a b c) (list a b c))")
        compiled (language/compile-source source)
        result (run compiled)]
    (is (= [2 3 5] (values result (:cell compiled))))))

(deftest computed-output-cell-is-an-inspectable-port
  (let [compiled (language/compile-source
                  "((network (a b) (list (+ a b))) 2 3)")
        result (run compiled)
        ports (vals (experiment/outputs result))]
    (is (= [5] (values result (:cell compiled))))
    (is (= 1 (count ports)))
    (is (= 5 (net/network-cell-strongest result (first ports))))))

(deftest declaration-is-a-callable-receipt-before-application
  (let [compiled (language/compile-source "(network (a) (+ a 1))")
        receipt (net/network-cell-strongest (:net compiled) (:cell compiled))]
    (is (gur/recursive-closure? receipt))
    (is (= '[a] (:compiler-2/input-description receipt)))
    (is (= :body-return (:compiler-2/output-interface receipt)))
    (is (empty? (experiment/outputs (:net compiled))))))

(deftest definition-returns-a-binding-receipt
  (let [compiled (language/compile-source "(define answer 42)")
        result (run compiled)
        receipt (net/network-cell-strongest result (:cell compiled))]
    (is (= :binding (:declaration/kind receipt)))
    (is (= 42 (net/network-cell-strongest result (:binding/target receipt))))))

(deftest definition-waits-for-a-late-source
  (let [compiled (language/compile-source "(let-cell [source target] (define target source))")
        initial (run compiled)
        receipt (net/network-cell-strongest initial (:cell compiled))
        source (:binding/source receipt)
        seeded (nb/seed-cell initial source 42)
        result (nb/run-propagators seeded (nb/neighbor-propagator-ids seeded source))]
    (is (value/nothing? (net/network-cell-strongest initial (:binding/target receipt))))
    (is (= 42 (net/network-cell-strongest result (:binding/target receipt))))))

(deftest definition-can-bind-a-closure-without-classifying-it
  (let [compiled (language/compile-source "(define f (network (a) (+ a 1)))")
        result (run compiled)
        receipt (net/network-cell-strongest result (:cell compiled))]
    (is (= :binding (:declaration/kind receipt)))
    (is (gur/recursive-closure?
         (net/network-cell-strongest result (:binding/target receipt))))))

(deftest definition-target-can-be-a-caller-cell
  (let [compiled (language/compile-source
                  "(let-cell [destination]
                     (define bind-value (network (target value) (define target value)))
                     (bind-value destination 42) destination)")
        result (run compiled)]
    (is (= 42 (net/network-cell-strongest result (:cell compiled))))))

(deftest definition-target-can-receive-a-closure
  (let [compiled (language/compile-source
                  "(let-cell [destination]
                     (define bind-value (network (target value) (define target value)))
                     (bind-value destination (network (x) (+ x 1)))
                     (destination 4))")
        result (run compiled)]
    (is (= 5 (net/network-cell-strongest result (:cell compiled))))))

(deftest definition-target-survives-nested-application
  (let [compiled (language/compile-source
                  "(let-cell [destination]
                     (define bind-value (network (target value) (define target value)))
                     (define forward (network (target value) (bind-value target value)))
                     (forward destination 42) destination)")
        result (run compiled)]
    (is (= 42 (net/network-cell-strongest result (:cell compiled))))))

(deftest empty-list-registers-no-member-ports
  (let [compiled (language/compile-source "((network () (list)))")
        result (run compiled)]
    (is (= basis/list-empty-marker (net/network-cell-strongest result (:cell compiled))))
    (is (empty? (experiment/outputs result)))))

(deftest applications-have-independent-output-ports
  (let [compiled (language/compile-source
                  "(let [] (define f (network (x) (list (+ x 1))))
                     (list (f 1) (f 10)))")
        result (run compiled)
        [first-call second-call] (list-ids result (:cell compiled))]
    (is (= [2] (values result first-call)))
    (is (= [11] (values result second-call)))
    (is (= 2 (count (experiment/outputs result))))
    (is (= 2 (count (set (vals (experiment/outputs result))))))))

(defn install-effects [network result]
  (let [[_ installed] (core/eval-activation-result result network)
        props (mapv :id (filter #(= :network/declare-propagator (:op %)) (:effects result)))]
    {:net installed :props props}))

(defn output-fixture []
  (let [[root head tail] (mapv gur/stable-node-id
                              [[:output-fixture :root] [:output-fixture :head]
                               [:output-fixture :tail]])
        network (reduce nb/ensure-cell net/empty-net [root head tail])
        compiled (install-effects
                  network (experiment/register-returned-outputs
                           {:effects []} {} :output-fixture root))]
    (assoc compiled :root root :head head :tail tail)))

(defn wake [network cells]
  (nb/run-propagators network
                      (mapcat #(nb/neighbor-propagator-ids network %) cells)))

(deftest output-registration-waits-for-late-list-structure
  (let [{:keys [root head tail] :as fixture} (output-fixture)
        initial (run fixture)
        [props declared] ((obj/p:cons head tail root) initial)
        seeded (nb/seed-cell declared tail basis/list-empty-marker)
        result (nb/run-propagators seeded props)]
    (is (empty? (experiment/outputs initial)))
    (is (= 1 (count (experiment/outputs result))))
    (is (value/nothing? (net/network-cell-strongest result
                                                  (first (vals (experiment/outputs result))))))))

(deftest output-registration-extends-only-the-late-tail
  (let [{:keys [root head tail] :as fixture} (output-fixture)
        [props declared] ((obj/p:cons head tail root) (:net fixture))
        initial (nb/run-propagators declared (concat (:props fixture) props))
        [next-head end] (mapv gur/stable-node-id [[:output-fixture :next] [:output-fixture :end]])
        prepared (reduce nb/ensure-cell initial [next-head end])
        [tail-props with-tail] ((obj/p:cons next-head end tail) prepared)
        seeded (nb/seed-cell with-tail end basis/list-empty-marker)
        result (nb/run-propagators seeded tail-props)
        repeated (wake result [root tail])]
    (is (= 1 (count (experiment/outputs initial))))
    (is (= 2 (count (experiment/outputs result))))
    (is (= (experiment/outputs result) (experiment/outputs repeated)))
    (is (= (set (keys (net/net-env result))) (set (keys (net/net-env repeated)))))))

(deftest malformed-output-tail-is-reported
  (let [{:keys [root head tail] :as fixture} (output-fixture)
        [props declared] ((obj/p:cons head tail root) (:net fixture))
        seeded (nb/seed-cell declared tail 99)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-list tail"
                         (nb/run-propagators seeded (concat (:props fixture) props))))))

(deftest cyclic-output-interface-is-reported
  (let [{:keys [root head] :as fixture} (output-fixture)
        [props declared] ((obj/p:cons head root root) (:net fixture))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cyclic output"
                         (nb/run-propagators declared (concat (:props fixture) props))))))

(deftest contradictory-output-waits
  (let [{:keys [root] :as fixture} (output-fixture)
        result (run (update fixture :net nb/seed-cell root value/contradiction))]
    (is (empty? (experiment/outputs result)))))

(deftest linked-list-application-waits-for-late-arguments
  (let [compiled (language/compile-source
                  "(let-cell [a b] (define f (network (x y) (+ x y)))
                     (list a b (apply f (list a b))))")
        initial (run compiled)
        [a b answer] (list-ids initial (:cell compiled))
        seeded (-> initial (nb/seed-cell a 2) (nb/seed-cell b 3))
        result (wake seeded [a b])]
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (= 5 (net/network-cell-strongest result answer)))))

(deftest installed-application-wakes-when-operator-arrives
  (let [compiled (language/compile-source
                  "(let-cell [f result] (define result (f 4))
                     (define f (network (x) (+ x 1))) result)")
        result (run compiled)]
    (is (= 5 (net/network-cell-strongest result (:cell compiled))))))

(defn input-fixture []
  (let [compiled (language/compile-source "(network (a b) (+ a b))")
        [context root head tail next-head end answer]
        (mapv gur/stable-node-id
              (map #(vector :input-fixture %) [:context :root :head :tail :next :end :answer]))
        prepared (reduce nb/ensure-cell (:net compiled)
                         [context root head tail next-head end answer])
        seeded (-> prepared (nb/seed-cell context true)
                   (nb/seed-cell head 2) (nb/seed-cell next-head 3))
        application (install-effects
                     seeded
                     {:effects (linked/linked-list-application-effects
                                (:cell compiled) context root answer)})]
    (assoc application :root root :head head :tail tail
           :next-head next-head :end end :answer answer)))

(deftest application-waits-for-a-late-input-tail
  (let [{:keys [root head tail next-head end answer] :as fixture} (input-fixture)
        [props declared] ((obj/p:cons head tail root) (:net fixture))
        initial (nb/run-propagators declared (concat props (:props fixture)))
        [tail-props extended] ((obj/p:cons next-head end tail) initial)
        seeded (nb/seed-cell extended end basis/list-empty-marker)
        result (nb/run-propagators seeded tail-props)
        repeated (wake result [root tail])]
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (= 5 (net/network-cell-strongest result answer)))
    (is (= (set (keys (net/net-env result))) (set (keys (net/net-env repeated)))))))

(deftest application-waits-for-a-partially-declared-input-cons
  (let [{:keys [root head tail next-head end answer] :as fixture} (input-fixture)
        [head-prop with-head] ((obj/p:slot :car head root) (:net fixture))
        initial (nb/run-propagators with-head (conj (:props fixture) head-prop))
        [tail-prop with-tail] ((obj/p:slot :cdr tail root) initial)
        [props extended] ((obj/p:cons next-head end tail) with-tail)
        seeded (nb/seed-cell extended end basis/list-empty-marker)
        result (nb/run-propagators seeded (conj props tail-prop))]
    (is (value/nothing? (net/network-cell-strongest initial answer)))
    (is (= 5 (net/network-cell-strongest result answer)))))

(deftest malformed-input-interface-is-reported
  (let [{:keys [root] :as fixture} (input-fixture)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a topology-backed linked list"
                         (run (update fixture :net nb/seed-cell root 99))))))

(deftest contradictory-input-interface-waits
  (let [{:keys [root answer] :as fixture} (input-fixture)
        result (run (update fixture :net nb/seed-cell root value/contradiction))]
    (is (value/nothing? (net/network-cell-strongest result answer)))))

(deftest computed-input-description-reports-the-capability-gap
  (try
    (language/compile-source
     "(define define-constraint
        (network (name args definition-network)
          (define name (network args (apply definition-network args) args))))")
    (is false "Computed input descriptions must not silently specialize")
    (catch clojure.lang.ExceptionInfo error
      (is (= :computed-input-description (:capability (ex-data error)))))))
