(ns propagators.layered-dispatcher-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.application :as application]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.layered.dispatcher :as dispatcher]
            [propagators.layered.procedure :as procedure]
            [propagators.layered.runtime :as runtime]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.stdlib.provenance-arithmetic :as arithmetic]))

(defn- attach [network procedure-id layer closure]
  (let [id (ids/new-node-id)
        prepared (nb/install-cell network id closure closure)]
    (:net (layered/install-layered-procedure! prepared procedure-id layer id))))

(defn- fixture [extensions previous]
  (let [{network :net operator :proc}
        (arithmetic/+ net/empty-net {:provenance? false})
        [left right output] (repeatedly 3 ids/new-node-id)
        extended (reduce (fn [network [layer closure]]
                           (attach network operator layer closure))
                         network extensions)
        network (-> extended
                    (nb/install-cell left 2 2)
                    (nb/install-cell right 3 3)
                    (nb/install-cell output previous previous))]
    {:network network
     :ports {:operator-id operator :argument-ids [left right] :output-id output}}))

(defn- execute [fixture]
  (let [app (dispatcher/build (runtime/prepare (:network fixture) (:ports fixture)))
        after (application/run-reduced-application app)]
    {:app app :network after
     :value (net/network-cell-value after (:reduced-out-id app))}))

(defn- observe-output [layer source]
  {:net net/empty-net
   :f (fn [_ inputs outputs network]
        (let [ports (procedure/ports inputs outputs)
              input (get ports source)
              result (:layer-result-id ports)
              read-id (ids/new-node-id)
              prepared (nb/install-cell network read-id)
              accessed (second ((layered/p:base read-id input) prepared))
              installer
              ((prop/concrete-primitive-propagator
                [:test/output-reader layer] identity) read-id result)]
          (procedure/publish (second (installer accessed)) layer result
                             (:live-result-id ports))))})

(deftest unfamiliar-layers-own-their-eligibility
  (let [publish (procedure/argument-layer
                 :novel (constantly true)
                 (fn [arguments] (mapv #(obj/slot-value % :base) arguments)))
        omit (procedure/argument-layer :omit (constantly false) (constantly :bad))
        initial (fixture [[:novel publish] [:omit omit]] value/nothing)
        result (execute initial)]
    (is (= 5 (obj/slot-value (:value result) :base)))
    (is (= [2 3] (obj/slot-value (:value result) :novel)))
    (is (not (contains? (obj/public-slot-keys (:value result)) :omit)))
    (is (value/nothing?
         (net/network-cell-value (:network initial) (get-in initial [:ports :output-id]))))))

(deftest previous-and-live-output-are-distinct
  (let [previous (obj/compound-object {:base 100})
        initial (fixture [[:old (observe-output :old :previous-output-id)]
                          [:new (observe-output :new :live-result-id)]] previous)
        result (execute initial)]
    (is (= 100 (obj/slot-value (:value result) :old)))
    (is (= 5 (obj/slot-value (:value result) :new)))
    (is (= 5 (obj/slot-value (:value result) :base)))
    (is (= previous
           (net/network-cell-value (:network initial) (get-in initial [:ports :output-id]))))))

(deftest only-used-output-references-create-read-edges
  (let [closure (procedure/argument-layer :novel (constantly true) (constantly :yes))
        initial (fixture [[:novel closure]] value/nothing)
        prepared (runtime/prepare (:network initial) (:ports initial))
        app (dispatcher/build prepared)
        argument-reader (some (fn [[id entry]]
                                (if (= [:layered/arguments :novel] (:name entry)) id nil))
                              (net/net-env (:net app)))
        inputs (graph/node-input-ids (graph/get-node (net/net-graph (:net app)) argument-reader))]
    (is (= (set (get-in prepared [:ports :argument-ids])) inputs))
    (is (not (contains? inputs (get-in prepared [:ports :previous-output-id]))))
    (is (not (contains? inputs (:result-bank-id app))))))

(deftest quiescent-rerun-does-not-grow-persistent-topology
  (let [initial (fixture [[:novel (procedure/argument-layer
                                 :novel (constantly true) (constantly :yes))]] value/nothing)
        {:keys [operator-id argument-ids output-id]} (:ports initial)
        [task network] ((layered/p:apply-layered operator-id argument-ids output-id)
                        (:network initial))
        first-run (nb/run-propagators network [task])
        second-run (nb/run-propagators first-run [task])]
    (is (= (set (keys (net/net-env first-run)))
           (set (keys (net/net-env second-run)))))
    (is (= (net/net-graph first-run) (net/net-graph second-run)))
    (is (= :yes (obj/slot-value (net/network-cell-value second-run output-id) :novel)))))

(deftest invalid-topology-result-fails-explicitly
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"must return a Net"
       (execute (fixture [[:invalid {:net net/empty-net :f (fn [_ _ _ _] nil)}]]
                         value/nothing)))))

(deftest base-adapter-preserves-declaration-metadata
  (let [closure {:net net/empty-net :boundary {:inputs [:x] :outputs [:y]}
                 :ir {:op :test/declaration}
                 :f (fn [_ _ _ network] network)}
        adapted (procedure/base closure)]
    (is (= (dissoc closure :f) (dissoc adapted :f)))
    (is (fn? (:f adapted)))))

(deftest result-reader-feedback-is-bounded
  (let [steps (atom 0)
        wrapper (fn [advance]
                  (fn [state handlers]
                    (if (< (swap! steps inc) 1000)
                      (advance state handlers)
                      (throw (ex-info "Feedback did not reach quiescence" {})))))
        initial (fixture [[:echo (observe-output :echo :live-result-id)]] value/nothing)
        result (binding [runner/*advance-transform* wrapper] (execute initial))]
    (is (= 5 (obj/slot-value (:value result) :echo)))
    (is (< @steps 1000))))
