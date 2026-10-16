(ns propagators.experimental.ttms-application-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [examples.lain.amb.experiment :as amb-example]
            [examples.lain.amb.premise-selection :as old-example]
            [examples.lain.amb.native-premise-selection :as example]
            [propagators.core :as core]
            [propagators.experimental.ttms-application.publication :as publication]
            [propagators.experimental.ttms-application.ports :as ports]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.ids :as ids]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-common.cps :as cps]
            [propagators.runner :as runner]
            [propagators.compiler-2-functional-network-test :as fixture]
            [propagators.cells.value :as value]
            [propagators.stdlib.premise-state :as state]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.contracts :as contracts]
            [propagators.experimental.amb.observation :as observation]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn publish [network source epoch status base]
  (let [content (collection/content {:base base :support #{{:source source :timestamp epoch
                                                          :premises-status status}}})
        [tasks updated] (core/eval-cell source (message source content) network)]
    (nb/run-propagators updated tasks)))

(defn slot-id [network owner key]
  (let [id (obj/existing-slot-cell-id network key owner)]
    (if (some? id) id
        (throw (ex-info "Missing returned member" {:owner owner :key key})))))

(defn member-ids [network root]
  (loop [root root out []]
    (if (= :compiler-2/list-empty (net/network-cell-strongest network root))
      out
      (recur (slot-id network root :cdr) (conj out (slot-id network root :car))))))

(deftest ordinary-callable-search-now-backtracks
  (let [run (amb-example/run (old-example/source false :callable 15))
        report (old-example/report run)]
    (is (= :completed (:runtime-status report)))
    (is (= [2 15] (:solution report)))
    (is (= 3 (get-in report [:choices 0 :timestamp])))
    (is (not (:call-result-contradiction? report)))))

(deftest native-closure-search-preserves-definition-and-choice-support
  (let [run (example/run)
        network (get-in run [:result :network])
        root (get-in run [:compiled :cell])
        result root
        current (net/network-cell-strongest network result)
        record (first (amb-example/choice-records network))
        definition (get-in run [:sources 'definition-ten])
        replay (nb/run-propagators network (get-in run [:compiled :props]))]
    (is (= :completed (get-in run [:result :status])))
    (is (= 2 (get-in run [:snapshot :value])))
    (is (get-in run [:snapshot :ready?]))
    (is (= #{(contracts/premise record)
             {:source definition :timestamp 1 :premises-status :active}}
           (datum/support-of current)))
    (is (= (net/net-env network) (net/net-env replay)))
    (is (= (net/net-graph network) (net/net-graph replay)))))

(deftest constant-body-withdraws-and-recovers-with-definition
  (let [run (example/run "(let [] (define f (ttms-premise-closure (network (x) 15) definition-ten)) (f 5))")
        root (get-in run [:compiled :cell])
        source (get-in run [:sources 'definition-ten])
        original (get-in run [:result :network])
        withdrawn (publish original source 2 :retracted 'definition-ten)
        recovered (publish withdrawn source 3 :active 'definition-ten)
        stale (publish recovered source 1 :active 'definition-ten)]
    (doseq [[network expected epoch status] [[original 15 1 :active]
                                            [withdrawn :nothing 2 :retracted]
                                            [recovered 15 3 :active]
                                            [stale 15 3 :active]]]
      (let [v (net/network-cell-strongest network root)]
        (is (= expected (if (value/nothing? (observation/base v))
                         :nothing (observation/base v))))
        (is (= #{{:source source :timestamp epoch :premises-status status}}
               (state/states-of v)))))
    (is (= (set (keys (net/net-env original))) (set (keys (net/net-env recovered)))))))

(deftest returned-members-are-gated-without-wrapping-list-structure
  (let [run (example/run "(let [] (define f (ttms-premise-closure (network (x) (list x (+ x 1))) definition-one)) (f 5))")
        original (get-in run [:result :network]) root (get-in run [:compiled :cell])
        source (get-in run [:sources 'definition-one])
        withdrawn (publish original source 2 :retracted 'definition-one)
        recovered (publish withdrawn source 3 :active 'definition-one)]
    (is (= :completed (get-in run [:result :status])))
    (is (= [5 6] (:items (observation/list-snapshot original root))))
    (is (not (:ready? (observation/list-snapshot withdrawn root))))
    (is (= [5 6] (:items (observation/list-snapshot recovered root))))
    (doseq [member (member-ids recovered root)]
      (is (= #{{:source source :timestamp 3 :premises-status :active}}
             (datum/support-of (net/network-cell-strongest recovered member)))))
    (is (= (set (keys (net/net-env original))) (set (keys (net/net-env recovered)))))))

(deftest incompatible-call-interfaces-fail-precisely
  (let [source (-> example/source
                   (str/replace "(network (x) (+ x 10))" "(network (x y) (+ x y))"))
        run (example/run source)]
    (is (= :failed (get-in run [:result :status])))
    (is (= "Supported closure application has invalid arity"
           (some-> (get-in run [:result :error]) ex-message)))))

(deftest primitive-callables-and-explicit-closure-outputs-are-gated
  (doseq [source ["(let [] (define f (ttms-premise-closure + definition-one)) (f 5 6))"
                  "(let-cell [out] (define f (ttms-premise-closure (network [x y] (define y (+ x 1)) y) definition-one)) (f 5 out))"]]
    (let [run (example/run source)
          root (get-in run [:compiled :cell])
          original (get-in run [:result :network])
          owner (get-in run [:sources 'definition-one])
          withdrawn (publish original owner 2 :retracted 'definition-one)]
      (is (= :completed (get-in run [:result :status])))
      (is (contains? #{6 11} (observation/cell-base original root)))
      (is (= #{{:source owner :timestamp 1 :premises-status :active}}
             (datum/support-of (net/network-cell-strongest original root))))
      (is (value/nothing? (observation/cell-base withdrawn root))))))

(deftest explicit-input-writes-have-a-live-caller-gate
  (let [run (example/run "(let-cell [x] (define f (ttms-premise-closure (network (x) (define x 7) x) definition-one)) (f x) (list x))")
        original (get-in run [:result :network])
        root (get-in run [:compiled :cell])
        owner (get-in run [:sources 'definition-one])
        withdrawn (publish original owner 2 :retracted 'definition-one)
        recovered (publish withdrawn owner 3 :active 'definition-one)]
    (is (= :completed (get-in run [:result :status])))
    (is (= [7] (:items (observation/list-snapshot original root))))
    (is (not (:ready? (observation/list-snapshot withdrawn root))))
    (is (= [7] (:items (observation/list-snapshot recovered root))))
    (is (= (net/net-graph original) (net/net-graph recovered)))))

(deftest late-member-arrives-through-existing-ports
  (let [run (example/run "(let-cell [x] (define f (ttms-premise-closure (network (v) (list v (+ v 1))) definition-one)) (list x (f x)))")
        root (get-in run [:compiled :cell])
        initial (get-in run [:result :network])
        [input output] (member-ids initial root)
        complete (fixture/wake (nb/seed-cell initial input 9) [input])
        replay (fixture/wake complete [input])]
    (is (= :completed (get-in run [:result :status])))
    (is (not (:ready? (observation/list-snapshot initial output))))
    (is (= [9 10] (:items (observation/list-snapshot complete output))))
    (is (= (member-ids initial output) (member-ids complete output)))
    (is (= (net/net-graph complete) (net/net-graph replay)))
    (is (= (net/net-env complete) (net/net-env replay)))))

(deftest nested-returned-closure-retains-captured-environment
  (let [run (example/run "(let [] (define make (ttms-premise-closure (network (captured) (network (x) (+ captured x))) definition-one)) (define f (make 10)) (f 5))")
        original (get-in run [:result :network])
        root (get-in run [:compiled :cell])
        owner (get-in run [:sources 'definition-one])
        withdrawn (publish original owner 2 :retracted 'definition-one)
        recovered (publish withdrawn owner 3 :active 'definition-one)]
    (is (= :completed (get-in run [:result :status])))
    (is (= 15 (observation/cell-base original root)))
    (is (value/nothing? (observation/cell-base withdrawn root)))
    (is (= 15 (observation/cell-base recovered root)))
    (is (= (net/net-graph original) (net/net-graph recovered)))))

(deftest no-satisfying-callable-exhausts-with-contextual-evidence
  (let [run (example/run (str/replace example/source "(= (procedure 5) 15)" "(= (procedure 5) 99)"))
        record (first (amb-example/choice-records (get-in run [:result :network])))]
    (is (= :completed (get-in run [:result :status])))
    (is (= 1 (:index record)))
    (is (some #(= {:action :unsatisfiable} %)
              (amb-example/cell-bases (get-in run [:result :network]))))
    (is (not (get-in run [:snapshot :ready?])))))

(deftest recursive-supported-closure-keeps-finite-stable-frames
  (let [run (example/run "(let [] (define down (ttms-premise-closure (network (n) ((if (< n 1) (network () n) (network () (down (- n 1)))))) definition-one)) (down 3))")
        original (get-in run [:result :network])
        root (get-in run [:compiled :cell])
        owner (get-in run [:sources 'definition-one])
        withdrawn (publish original owner 2 :retracted 'definition-one)
        recovered (publish withdrawn owner 3 :active 'definition-one)
        replay (nb/run-propagators recovered (get-in run [:compiled :props]))]
    (is (= :completed (get-in run [:result :status])))
    (is (= 0 (observation/cell-base original root)))
    (is (value/nothing? (observation/cell-base withdrawn root)))
    (is (= 0 (observation/cell-base recovered root)))
    (is (= (net/net-graph original) (net/net-graph recovered)))
    (is (= (net/net-env recovered) (net/net-env replay)))))

(deftest delayed-body-keeps-the-original-custom-compiler
  (let [calls (atom 0)
        custom (cps/make-compiler
                (cps/compose-rules
                 (cps/on (fn [expr] (= 7 (obj/slot-value expr ast/value-slot)))
                         (fn [compile-k state _expr k]
                           (swap! calls inc)
                           (cps/call compile-k state (ast/lit 19) k)))
                 compiler/compiler-dispatch))
        prepared (with-redefs [compiler/default-compiler custom]
                   (example/prepare "(let [] (define f (ttms-premise-closure (network (x) 7) definition-one)) (f 5))"))
        before @calls
        compiled (:compiled prepared)
        result (runner/run-network (:props compiled) (:net compiled))]
    (is (zero? before))
    (is (= :completed (:status result)))
    (is (= 19 (observation/cell-base (:network result) (:cell compiled))))
    (is (= 1 @calls))))

(deftest publication-gate-retains-contradiction-evidence
  ;; Test the handler's gate directly: ordinary compiler transport can suppress
  ;; bare contradictions before this boundary on the current committed runtime.
  (let [run (example/run "(ttms-premise-closure (network (x) x) definition-one)")
        original (get-in run [:result :network])
        operator (get-in run [:compiled :cell])
        [source target] (repeatedly 2 ids/new-node-id)
        config {:operator operator
                :key (ports/candidate-key (observation/cell-base original operator))}
        prepared (-> original (nb/ensure-cell source) (nb/ensure-cell target)
                     (nb/seed-cell source value/contradiction))
        [id installed] ((publication/publisher config source target) prepared)
        network (nb/run-propagators installed [id])
        owner (get-in run [:sources 'definition-one])
        v (net/network-cell-strongest network target)
        withdrawn (publish network owner 2 :retracted 'definition-one)]
    (is (= :completed (get-in run [:result :status])))
    (is (value/contradiction? (observation/base v)))
    (is (= #{{:source owner :timestamp 1 :premises-status :active}}
           (datum/support-of v)))
    (is (value/nothing? (observation/cell-base withdrawn target)))))

(deftest changing-return-interface-is-rejected-before-merging-structure
  (let [run (example/run "(let [] (define f (ttms-premise-closure (if (= definition-one 1) (network (x) (list x x)) (network (x) (list x))) definition-one)) (f 5))")
        network (get-in run [:result :network])
        owner (get-in run [:sources 'definition-one])]
    (is (= :completed (get-in run [:result :status])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                         #"Supported candidates have incompatible return interfaces"
                         (publish network owner 2 :active 1)))))

(deftest primitive-explicit-output-argument-is-gated
  (let [[procedure input output result context owner] (repeatedly 6 ids/new-node-id)
        callable (operator/propagator-operator
                  {:name ::explicit-output
                   :input-selector (fn [args _ _] [(first args)])
                   :output-selector (fn [args _] [(second args)])
                   :activate (fn [network [in] [out] _]
                               [(message out (inc (net/network-cell-strongest network in)))])})
        premise {:source owner :timestamp 1 :premises-status :active}
        token (collection/content {:base :definition :support #{premise}})
        op (collection/content {:base callable :support #{premise}})
        base (-> (reduce nb/ensure-cell net/empty-net [procedure input output result context owner])
                 (nb/seed-cell input 5)
                 (nb/install-cell owner token (collection/strongest-value token (fn [_ v] v)))
                 (nb/install-cell procedure op (collection/strongest-value op (fn [_ v] v))))
        [installed _] (application/install-application
                       {:net base :props [] :context-id context :application/app-id ::primitive}
                       procedure [input output] result)
        network (nb/run-propagators (:net installed) (:props installed))]
    (is (= 5 (net/network-cell-strongest network input)))
    (doseq [id [output result]]
      (is (= 6 (observation/cell-base network id)))
      (is (= #{premise} (datum/support-of (net/network-cell-strongest network id)))))))

(deftest nested-returned-lists-gate-leaf-members
  (let [run (example/run "(let [] (define f (ttms-premise-closure (network (x) (list (list x (+ x 1)))) definition-one)) (f 5))")
        network (get-in run [:result :network])
        root (get-in run [:compiled :cell])
        nested (first (member-ids network root))
        owner (get-in run [:sources 'definition-one])
        withdrawn (publish network owner 2 :retracted 'definition-one)
        recovered (publish withdrawn owner 3 :active 'definition-one)]
    (is (= :completed (get-in run [:result :status])))
    (is (= [5 6] (:items (observation/list-snapshot network nested))))
    (is (not (:ready? (observation/list-snapshot withdrawn nested))))
    (is (= [5 6] (:items (observation/list-snapshot recovered nested))))
    (is (= (net/net-graph network) (net/net-graph recovered)))))
