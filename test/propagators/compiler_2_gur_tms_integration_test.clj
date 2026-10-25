(ns propagators.compiler-2-gur-tms-integration-test
  "Public compiler/runtime checks: distributed TMS and TTMS are distinct transports."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2-functional-network-test :as fixture]
            [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms :as tms]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]
            [propagators.stdlib.source-publication :as publication]))

(use-fixtures :each
  (fn [test]
    (let [steps (atom 0)]
      (binding [runner/*advance-transform*
                (runner/compose-advance-transforms
                 runner/*advance-transform*
                 (fn [advance]
                   (fn [state continuations]
                     (when (> (swap! steps inc) 100000)
                       (throw (ex-info "GUR TMS integration did not settle" {})))
                     (advance state continuations))))]
        (test)))))

(defn- advance [network id update]
  (let [[tasks updated] (core/eval-cell id (message id update) network)]
    (nb/run-propagators updated tasks)))

(defn- gur-frames [network]
  (get (net/network-dict-entry network gur/name-bindings-key) [:gur.flat :frames]))

(defn- ttms-program [source]
  (let [input (ids/new-node-id)
        compiled (compiler/compile-expr-with-bindings
                  (parser/parse-string source)
                  (conj (vec (basis/default-bindings)) ['input (env/cell-binding input)])
                  {:net (nb/ensure-cell net/empty-net input)})]
    {:input input :output (:cell compiled) :tasks (:props compiled)
     :network (fixture/run compiled)}))

(defn- payload [network id]
  (data/payload (net/network-cell-strongest network id)))

(deftest ttms-revisions-retract-and-recover-through-gur-scalar-calls
  (let [{:keys [input output tasks network]}
        (ttms-program
         "(let []
            (define increment (network [x] (+ x 1)))
            (define double (network [x] (* x 2)))
            (double (increment input)))")
        active (advance network input (publication/active ::input 1 3))
        changed (advance active input (publication/active ::input 2 4))
        withdrawn (advance changed input (publication/retracted ::input 3))
        recovered (advance withdrawn input (publication/active ::input 4 5))
        stale (advance recovered input (publication/active ::input 1 3))
        replay (nb/run-propagators stale tasks)]
    (is (seq (gur-frames network)) "The test uses ordinary GUR application frames")
    (is (value/nothing? (payload network output)))
    (is (= 8 (payload active output)))
    (is (= 10 (payload changed output)))
    (is (value/nothing? (payload withdrawn output)))
    (is (= 12 (payload recovered output)))
    (is (= 12 (payload stale output)))
    (is (= #{{:source (publication/source-id ::input) :timestamp 4 :premises-status :active}}
           (datum/support-of (net/network-cell-strongest stale output))))
    (is (= (net/net-graph active) (net/net-graph recovered)))
    (is (= (net/net-env stale) (net/net-env replay)))
    (is (= 8 (payload active output)) "Prior network snapshots remain immutable")))

(deftest ttms-supported-conditional-selects-gur-callables-and-recovers
  (let [{:keys [input output tasks network]}
        (ttms-program
         "(let []
            (define increment (network [x] (+ x 1)))
            (define double (network [x] (* x 2)))
            (if (> input 3) (increment input) (double input)))")
        first-branch (advance network input (publication/active ::condition 1 3))
        second-branch (advance first-branch input (publication/active ::condition 2 4))
        withdrawn (advance second-branch input (publication/retracted ::condition 3))
        recovered (advance withdrawn input (publication/active ::condition 4 5))
        replay (nb/run-propagators recovered tasks)]
    (is (value/nothing? (payload network output)))
    (is (= 6 (payload first-branch output)))
    (is (= 5 (payload second-branch output)))
    (is (value/nothing? (payload withdrawn output)))
    (is (= 6 (payload recovered output)))
    (is (seq (gur-frames recovered)))
    (is (= (net/net-graph recovered) (net/net-graph replay)))
    (is (= (net/net-env recovered) (net/net-env replay)))))

(deftest ttms-revisions-retract-and-recover-through-returned-cons
  (let [{:keys [input output tasks network]}
        (ttms-program
         "(let []
            (define pair (network [x] (cons (+ x 1) (cons (* x 2) (list)))))
            (define relay (network [x] x))
            (define result (relay (pair input)))
            (+ (car result) (car (cdr result))))")
        active (advance network input (publication/active ::compound-input 1 3))
        changed (advance active input (publication/active ::compound-input 2 4))
        withdrawn (advance changed input (publication/retracted ::compound-input 3))
        recovered (advance withdrawn input (publication/active ::compound-input 4 5))
        stale (advance recovered input (publication/active ::compound-input 1 3))
        replay (nb/run-propagators stale tasks)
        second-replay (nb/run-propagators replay tasks)]
    (is (seq (gur-frames network)))
    (is (value/nothing? (payload network output)))
    (is (= 10 (payload active output)))
    (is (= 13 (payload changed output)))
    (is (value/nothing? (payload withdrawn output)))
    (is (= 16 (payload recovered output)))
    (is (= 16 (payload stale output) (payload replay output) (payload second-replay output)))
    (is (= #{{:source (publication/source-id ::compound-input) :timestamp 4 :premises-status :active}}
           (datum/support-of (net/network-cell-strongest stale output))
           (datum/support-of (net/network-cell-strongest replay output))))
    (is (= (net/net-graph active) (net/net-graph recovered) (net/net-graph replay)))
    (is (= (set (keys (net/net-env stale))) (set (keys (net/net-env replay)))))
    (is (= (gur-frames stale) (gur-frames replay)))
    ;; A first explicit all-prop replay normalizes two inner accessor-cell names
    ;; in the immutable `result` alias (NodeId -> :cell/anonymous). Their content,
    ;; strongest values, slot topology and live public output do not change.
    ;; The second replay must be fully idempotent, including those cached names.
    (is (true? (= (net/net-env replay) (net/net-env second-replay))))
    (is (= 10 (payload active output)) "Earlier snapshots retain their values")))

(deftest supported-reverse-slot-writes-preserve-unrelated-partial-slots
  (let [{:keys [input output tasks network]}
        (ttms-program
         "(let-cell [leaf unrelated]
            (define owner (cons leaf (cons unrelated (list))))
            (define relay (network [x] x))
            (define view (relay owner))
            (-> input (car view))
            (list (car owner) unrelated))")
        [selected unrelated] (fixture/list-ids network output)
        active (advance network input (publication/active ::reverse-input 1 3))
        changed (advance active input (publication/active ::reverse-input 2 4))
        withdrawn (advance changed input (publication/retracted ::reverse-input 3))
        recovered (advance withdrawn input (publication/active ::reverse-input 4 5))
        replay (nb/run-propagators recovered tasks)]
    (is (value/nothing? (payload network selected)))
    (is (= 3 (payload active selected)))
    (is (= 4 (payload changed selected)))
    (is (value/nothing? (payload withdrawn selected)))
    (is (= 5 (payload recovered selected) (payload replay selected)))
    (doseq [snapshot [network active changed withdrawn recovered replay]]
      (is (value/nothing? (payload snapshot unrelated))
          "Writing through one view must not resolve an unrelated slot"))
    (is (= (net/net-graph active) (net/net-graph recovered) (net/net-graph replay)))
    (is (= #{{:source (publication/source-id ::reverse-input) :timestamp 4 :premises-status :active}}
           (datum/support-of (net/network-cell-strongest replay selected))))))

(deftest distributed-tms-premise-controls-gur-composed-scalar-output
  (let [compiled (compiler/compile-source
                  "(let-cell [x out]
                     (premise-input 5 :input/x 0 x)
                     (define op (premise-closure
                                  (network [x]
                                    (define increment (network [v] (+ v 1)))
                                    (* (increment x) 2))
                                  :definition/op 0))
                     (-> (op x) out)
                     out)")
        network (fixture/run compiled)
        output (:cell compiled)
        withdrawn (advance network output (tms/distributed-premise-update :definition/op 1 false))
        recovered (advance withdrawn output (tms/distributed-premise-update :definition/op 2 true))
        replay (nb/run-propagators recovered (:props compiled))
        base (fn [n] (-> (net/network-cell-strongest n output)
                         scope-source/unwrap tms/distributed-base-value))]
    (is (seq (gur-frames network)))
    (is (= 12 (base network)))
    (is (value/nothing? (base withdrawn)))
    (is (= 12 (base recovered)))
    (is (tms/distributed-content? (net/network-cell-content recovered output)))
    (is (= (net/net-graph network) (net/net-graph recovered)))
    (is (= (net/net-env recovered) (net/net-env replay)))
    (is (= 12 (base network)))))
