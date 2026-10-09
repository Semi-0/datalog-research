(ns propagators.premise-transport-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.premise-publication :as publication]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.layered.procedure :as layer-procedure]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.stdlib.prop :as standard]
            [propagators.stdlib.premise-state :as state]
            [propagators.stdlib.support :as support]))

(defn- p [source epoch status]
  {:source source :timestamp epoch :premises-status status})
(defn- state-update [& states]
  (collection/content {:premise-state (set states)}))
(defn- observed [base & supports]
  (collection/content {:base base :support (set supports)}))
(defn- project [content]
  (merge/strongest-value content net/empty-net))
(defn- publish [network target content]
  (let [[tasks n] (core/eval-cells [(message/message target content)] network)]
    (nb/run-propagators n tasks)))

(deftest state-is-not-a-value-observation-or-unrelated-dependency
  (let [[a b] (repeatedly 2 ids/new-node-id)
        a1 (p a 1 :active) b2 (p b 2 :retracted)
        content (collection/merge-content (observed 10 a1) (state-update b2))
        v (project content)]
    (is (= #{} (:support/observations (state-update b2))))
    (is (= 10 (datum/layer-value v :base)))
    (is (= #{a1} (datum/support-of v)))
    (is (= #{a1 b2} (datum/layer-value v :premise-state)))
    (is (not (value/unusable? v)))
    (let [withdrawn (collection/merge-content content (state-update (p a 2 :retracted)))
          active (collection/merge-content withdrawn (state-update (p a 3 :active)))
          restored (collection/merge-content active (observed 20 (p a 3 :active)))]
      (is (value/nothing? (datum/layer-value (project withdrawn) :base)))
      (is (value/nothing? (datum/layer-value (project active) :base)))
      (is (= 20 (datum/layer-value (project restored) :base)))
      (is (= #{(p a 3 :active)} (datum/support-of (project restored)))))))

(deftest state-merge-laws-and-conflicting-status
  (let [source (ids/new-node-id)
        a (observed 10 (p source 1 :active))
        b (state-update (p source 2 :retracted))
        c (state-update (p source 3 :active))
        join collection/merge-content]
    (is (= (join a b) (join b a)))
    (is (= (join (join a b) c) (join a (join b c))))
    (is (= (join a b) (join (join a b) b)))
    (is (= (join a c) (join (join a c) (state-update (p source 1 :active)))))
    (let [conflict (project (join a (state-update (p source 1 :retracted))))]
      (is (value/unusable? conflict))
      (is (= #{(p source 1 :active) (p source 1 :retracted)}
             (datum/layer-value conflict :premise-state))))))

(defn- install-procedure [network calls]
  (let [procedure (ids/new-node-id)
        base {:net net/empty-net
              :f (fn [_ inputs outputs n]
                   (second ((apply (prop/concrete-primitive-propagator
                                     (fn [x] (swap! calls inc) (inc x)))
                                   (concat inputs outputs)) n)))}]
    (reduce
     (fn [[n proc-id] [layer closure]]
       (let [id (ids/new-node-id)
             n (nb/install-cell n id closure closure)]
         [(:net (layered/install-layered-procedure! n proc-id layer id)) proc-id]))
     [network procedure]
     [[:base (layer-procedure/base base)] [:support support/procedure]
      [:premise-state state/procedure]])))

(defn- install-call [network procedure input output]
  (let [[id n] ((layered/p:apply-layered procedure [input] output) network)
        application (net/network-env-lookup n id)]
    [id (net/assoc-net-prop n id
                           (assoc application :activate
                             (prop/compose-activation (prop/prop-f application)
                               (message/lift-message publication/content))))]))

(deftest layered-procedures-transport-without-running-unusable-base
  (doseq [blocked [value/nothing value/contradiction]]
    (let [[source middle out] (repeatedly 3 ids/new-node-id)
          calls (atom 0)
          [n procedure] (install-procedure (nb/install-cells [source middle out]) calls)
          [_ n] (install-call n procedure source middle)
          [_ n] (install-call n procedure middle out)
          initial (collection/merge-content (observed 10 (p source 1 :active)) (state-update))
          ready (publish n source initial)
          count-before @calls
          unusable (publish ready source
                            (collection/merge-content (observed blocked (p source 2 :active)) (state-update)))
          withdrawn (publish unusable source (state-update (p source 3 :retracted)))
          revived (publish withdrawn source (state-update (p source 4 :active)))
          count-after @calls
          recovered (publish revived source (observed 20 (p source 4 :active)))]
      (is (= 12 (datum/layer-value (net/network-cell-strongest ready out) :base)))
      (is (= count-before count-after) "Base callbacks must not run on unusable input")
      (doseq [[network epoch status] [[unusable 2 :active] [withdrawn 3 :retracted] [revived 4 :active]]]
        (let [v (net/network-cell-strongest network out)]
          (is (value/nothing? (datum/layer-value v :base)))
          (is (= #{(p source epoch status)} (datum/layer-value v :premise-state)))))
      (is (= 22 (datum/layer-value (net/network-cell-strongest recovered out) :base)))
      (is (= recovered (publish recovered source (state-update (p source 4 :active))))))))

(deftest publication-suppresses-unusable-payload-not-state
  (let [source (ids/new-node-id) states #{(p source 1 :active)}]
    (doseq [base [value/nothing value/contradiction]]
      (let [result (publication/content (obj/compound-object {:base base :support states :premise-state states}))]
        (is (= #{} (:support/observations result)))
        (is (= states (:support/states result)))))))

(deftest unchanged-payload-still-wakes-state-transport
  (let [[source other middle out] (repeatedly 4 ids/new-node-id)
        calls (atom 0)
        [n procedure] (install-procedure (nb/install-cells [source other middle out]) calls)
        [_ n] (install-call n procedure source middle)
        [_ n] (install-call n procedure middle out)
        ready (publish n source (collection/merge-content (observed 10 (p source 1 :active)) (state-update)))
        changed (publish ready source (state-update (p other 2 :retracted)))
        v (net/network-cell-strongest changed out)]
    (is (= 12 (datum/layer-value v :base)))
    (is (= #{(p source 1 :active)} (datum/support-of v)))
    (is (= #{(p source 1 :active) (p other 2 :retracted)} (datum/layer-value v :premise-state)))
    (is (not (value/unusable? v)))
    (is (= changed (publish changed source (state-update (p other 2 :retracted)))))))

(deftest real-bidirectional-slots-and-cycle-carry-state-only-updates
  (let [[a b c owner] (repeatedly 4 ids/new-node-id)
        [x n1] ((obj/p:network-slot :x a owner) (nb/install-cells [a b c owner]))
        [y n2] ((obj/p:network-slot :x b owner) n1)
        [z n3] ((standard/id b c) n2)
        [w n4] ((standard/id c a) n3)
        steps (atom 0)]
    (binding [runner/*advance-transform*
              (fn [advance]
                (fn [s ks]
                  (if (> (swap! steps inc) 2000)
                    ((:fail ks) (ex-info "State transport did not quiesce" {}))
                    (advance s ks))))]
      (let [n (nb/run-propagators n4 [x y z w])
            ready (publish n a (collection/merge-content (observed 10 (p a 1 :active)) (state-update)))
            withdrawn (publish ready b (state-update (p a 2 :retracted)))
            restored (publish withdrawn a (observed 20 (p a 3 :active)))]
        (doseq [id [a b c]]
          (is (value/nothing? (datum/layer-value (net/network-cell-strongest withdrawn id) :base)))
          (is (= #{(p a 2 :retracted)} (datum/layer-value (net/network-cell-strongest withdrawn id) :premise-state)))
          (is (= 20 (datum/layer-value (net/network-cell-strongest restored id) :base))))
        (is (= restored (nb/run-propagators restored [x y z w])))
        (is (= (net/net-graph n) (net/net-graph restored)))))))

(deftest partial-layered-datum-publishes-without-base
  (let [source (ids/new-node-id)
        premises #{(p source 1 :retracted)}
        input (obj/compound-object {:premise-state premises})
        result (publication/content input)]
    (is (not (datum/layer-present? input :base)))
    (is (= {:support/observations #{} :support/states premises} result))))

(deftest compiled-branch-and-arithmetic-preserve-state-not-unselected-dependencies
  (let [[c a b] (repeatedly 3 ids/new-node-id)
        n (reduce (fn [n [id base]]
                    (publish n id (collection/merge-content (observed base (p id 1 :active)) (state-update))))
                  (nb/install-cells [c a b]) [[c true] [a 10] [b 20]])
        compiled (compiler/compile-expr-with-bindings
                  (parser/parse-string "(+ (if c a b) 1)")
                  (into (vec (basis/default-bindings))
                        (concat (extension/extension-bindings primitives/session-extension)
                                [['c (env/cell-binding c)] ['a (env/cell-binding a)] ['b (env/cell-binding b)]]))
                  {:net n})
        ready (nb/run-propagators (:net compiled) (:props compiled))
        inactive-retracted (publish ready b (state-update (p b 2 :retracted)))
        selected-retracted (publish inactive-retracted a (state-update (p a 2 :retracted)))
        revived (publish selected-retracted a (state-update (p a 3 :active)))
        recovered (publish revived a (observed 30 (p a 3 :active)))
        switched (publish recovered c (observed false (p c 2 :active)))
        b-restored (publish switched b (observed 40 (p b 3 :active)))
        out (:cell compiled)
        read #(net/network-cell-strongest % out)]
    (is (= 11 (datum/layer-value (read ready) :base)))
    (is (= 11 (datum/layer-value (read inactive-retracted) :base)))
    (is (= #{(p c 1 :active) (p a 1 :active)} (datum/support-of (read inactive-retracted))))
    (is (contains? (datum/layer-value (read inactive-retracted) :premise-state) (p b 2 :retracted)))
    (doseq [n [selected-retracted revived switched]]
      (is (value/nothing? (datum/layer-value (read n) :base))))
    (is (= 31 (datum/layer-value (read recovered) :base)))
    (is (= 41 (datum/layer-value (read b-restored) :base)))
    (is (= #{(p c 2 :active) (p b 3 :active)} (datum/support-of (read b-restored))))))

(deftest same-epoch-payload-conflict-recovers-by-newer-source-information
  (let [[source middle out] (repeatedly 3 ids/new-node-id)
        calls (atom 0)
        [n procedure] (install-procedure (nb/install-cells [source middle out]) calls)
        [_ n] (install-call n procedure source middle)
        [_ n] (install-call n procedure middle out)
        a1 (p source 1 :active)
        ready (publish n source (collection/merge-content (observed 10 a1) (state-update)))
        before @calls
        conflict (publish ready source (observed 20 a1))
        conflict-calls @calls
        repaired (publish conflict source (observed 30 (p source 2 :active)))
        withdrawn (publish conflict source (state-update (p source 2 :retracted)))
        restored (publish withdrawn source (observed 40 (p source 3 :active)))]
    (is (= 12 (datum/layer-value (net/network-cell-strongest ready out) :base)))
    (is (value/contradiction? (datum/layer-value (net/network-cell-strongest conflict source) :base)))
    (is (= before conflict-calls) "No value computation on contradictory input")
    ;; A local payload conflict is not an implicit source retraction. Explicit
    ;; newer source information repairs the chain without inventing an epoch.
    (doseq [[network expected epoch] [[repaired 32 2] [restored 42 3]]]
      (let [result (net/network-cell-strongest network out)]
        (is (= expected (datum/layer-value result :base)))
        (is (= #{(p source epoch :active)} (datum/support-of result)))
        (is (not (value/unusable? result)))
        (is (= network (publish network source (observed 20 a1))))))
    (let [result (net/network-cell-strongest withdrawn out)]
      (is (value/nothing? (datum/layer-value result :base)))
      (is (= #{(p source 2 :retracted)} (datum/layer-value result :premise-state))))))
