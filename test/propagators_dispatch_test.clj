(ns propagators-dispatch-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.dispatch :as dispatch]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- installed-cells
  [& ids]
  (reduce nb/install-cell net/empty-net ids))

(defn- procedure-extension
  [slot->closure]
  (reduce
   (fn [n [slot-key closure-value]]
     (let [slot-id (ids/new-node-id)]
       (-> n
           (net/seed-net-cell slot-id closure-value closure-value)
           (net/assoc-net-dict-entry slot-key slot-id))))
   net/empty-net
   slot->closure))

(defn- predicate-closure
  [pred]
  (closure/closure
   (fn [_closure-net input-ids output-ids network]
     (let [[out-id] output-ids
           [_ n'] ((apply (prop/primitive-propagator pred) (conj (vec input-ids) out-id))
                   network)]
       n'))
   net/empty-net))

(defn- handler-closure
  [f]
  (closure/closure
   (fn [_closure-net input-ids output-ids network]
     (let [[out-id] output-ids
           guarded-f (fn [& args]
                       (if (apply value/any-unusable-values? args)
                         value/nothing
                         (apply f args)))
           [_ n'] ((apply (prop/primitive-propagator guarded-f) (conj (vec input-ids) out-id))
                   network)]
       n'))
   net/empty-net))

(defn- extend-procedure
  [n proc-id extension]
  (let [extension-id (ids/new-node-id)
        n0 (nb/install-cell n extension-id)]
    (:net (layered/install-layered-procedure! n0 proc-id extension-id extension))))

(defn- generic-apply-net
  [outer-net proc-id arg-ids out-id handler-keys default-id]
  (let [proc-value (net/network-cell-strongest outer-net proc-id)
        result-bank-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell proc-id proc-value proc-value)
               (#(reduce
                  (fn [acc id]
                    (let [v (net/network-cell-strongest outer-net id)]
                      (nb/install-cell acc id v v)))
                  %
                  (conj (vec arg-ids) default-id out-id)))
               (dispatch/install-result-bank result-bank-id))
        [branch-net branch-props]
        (reduce
         (fn [[n prop-ids] handler-key]
           (let [predicate-key (keyword "predicate" (name handler-key))
                 predicate-closure-id (ids/new-node-id)
                 handler-closure-id (ids/new-node-id)
                 predicate-result-id (ids/new-node-id)
                 filtered-arg-ids (vec (repeatedly (count arg-ids) ids/new-node-id))
                 handler-result-id (ids/new-node-id)
                 n1 (reduce nb/install-cell
                            n
                            (into [predicate-closure-id
                                   handler-closure-id
                                   predicate-result-id
                                   handler-result-id]
                                  filtered-arg-ids))
                 [predicate-slot-prop n2] ((layered/p:layer predicate-key predicate-closure-id proc-id) n1)
                 [handler-slot-prop n3] ((layered/p:layer handler-key handler-closure-id proc-id) n2)
                 [predicate-prop n4] ((prop/compound-propagator predicate-closure-id
                                                                 arg-ids
                                                                 [predicate-result-id])
                                      n3)
                 [filter-props n5]
                 (reduce
                  (fn [[filters acc] [arg-id filtered-id]]
                    (let [[filter-prop acc'] ((dispatch/p:filter predicate-result-id arg-id filtered-id)
                                              acc)]
                      [(conj filters filter-prop) acc']))
                  [[] n4]
                  (map vector arg-ids filtered-arg-ids))
                 [handler-prop n6] ((prop/compound-propagator handler-closure-id
                                                               filtered-arg-ids
                                                               [handler-result-id])
                                    n5)
                 [result-slot-prop n7] ((layered/p:layer handler-key handler-result-id result-bank-id) n6)]
             [n7 (into prop-ids
                       (conj filter-props
                             predicate-slot-prop
                             handler-slot-prop
                             predicate-prop
                             handler-prop
                             result-slot-prop))]))
         [n0 []]
         handler-keys)]
    {:net branch-net
     :branch-props branch-props
     :result-bank-id result-bank-id}))

(defn- run-generic-application
  [outer-net proc-id arg-ids out-id handler-keys default-id]
  (let [{:keys [net branch-props result-bank-id]} (generic-apply-net outer-net
                                                                      proc-id
                                                                      arg-ids
                                                                      out-id
                                                                      handler-keys
                                                                      default-id)
        after-branches (nb/run-propagators net branch-props)
        [reducer-props reduced-net] ((dispatch/reduce-results
                                      (dispatch/select-one-policy handler-keys default-id)
                                      result-bank-id
                                      out-id)
                                     after-branches)]
    (nb/run-propagators reduced-net reducer-props)))

(deftest filter-forwards-only-when-predicate-is-true
  (testing "truthy predicate forwards input to output"
    (let [pred (ids/new-node-id)
          in (ids/new-node-id)
          out (ids/new-node-id)
          n0 (installed-cells pred in out)
          [filter-prop n1] ((dispatch/p:filter pred in out) n0)
          n2 (-> n1
                 (nb/seed-cell pred true)
                 (nb/seed-cell in :ok)
                 (nb/run-propagators [filter-prop]))]
      (is (= :ok (net/network-cell-strongest n2 out)))))

  (testing "false predicate drops input"
    (let [pred (ids/new-node-id)
          in (ids/new-node-id)
          out (ids/new-node-id)
          n0 (installed-cells pred in out)
          [filter-prop n1] ((dispatch/p:filter pred in out) n0)
          n2 (-> n1
                 (nb/seed-cell pred false)
                 (nb/seed-cell in :ok)
                 (nb/run-propagators [filter-prop]))]
      (is (= :bool4/nothing (net/network-cell-strongest n2 out))))))

(deftest layered-object-policy-copies-result-bank-slots
  (testing "result-bank slots reduce into the output layered object"
    (let [bank (ids/new-node-id)
          out (ids/new-node-id)
          base-value (ids/new-node-id)
          n0 (-> (installed-cells out base-value)
                 (dispatch/install-result-bank bank))
          [bank-slot-prop n1] ((layered/p:layer :base base-value bank) n0)
          [reducer-props n2] ((dispatch/reduce-results
                               (dispatch/layered-object-policy [:base])
                               bank
                               out)
                              n1)
          n3 (-> n2
                 (nb/seed-cell base-value 42)
                 (nb/run-propagators (into [bank-slot-prop] reducer-props)))
          out-object (net/network-cell-strongest n3 out)]
      (is (= 42 (net/network-cell-strongest
                 out-object
                 (net/network-dict-entry out-object :base)))))))

(deftest select-one-policy-supports-generic-style-reduction
  (testing "one usable slot is selected"
    (let [bank (ids/new-node-id)
          default (ids/new-node-id)
          out (ids/new-node-id)
          handler-result (ids/new-node-id)
          n0 (-> (installed-cells default out handler-result)
                 (dispatch/install-result-bank bank)
                 (nb/seed-cell default :default))
          [slot-prop n1] ((layered/p:layer :handler/add-numbers handler-result bank) n0)
          [reducer-props n2] ((dispatch/reduce-results
                               (dispatch/select-one-policy [:handler/add-numbers] default)
                               bank
                               out)
                              n1)
          n3 (-> n2
                 (nb/seed-cell handler-result 7)
                 (nb/run-propagators (into [slot-prop] reducer-props)))]
      (is (= 7 (net/network-cell-strongest n3 out)))))

  (testing "no usable slots falls back to default"
    (let [bank (ids/new-node-id)
          default (ids/new-node-id)
          out (ids/new-node-id)
          n0 (-> (installed-cells default out)
                 (dispatch/install-result-bank bank)
                 (nb/seed-cell default :default))
          [reducer-props n1] ((dispatch/reduce-results
                               (dispatch/select-one-policy [:handler/missing] default)
                               bank
                               out)
                              n0)
          n2 (-> n1
                 (nb/run-propagators reducer-props))]
      (is (= :default (net/network-cell-strongest n2 out)))))

  (testing "multiple usable slots produce contradiction"
    (let [bank (ids/new-node-id)
          default (ids/new-node-id)
          out (ids/new-node-id)
          left-result (ids/new-node-id)
          right-result (ids/new-node-id)
          n0 (-> (installed-cells default out left-result right-result)
                 (dispatch/install-result-bank bank)
                 (nb/seed-cell default :default))
          [left-slot-prop n1] ((layered/p:layer :handler/left left-result bank) n0)
          [right-slot-prop n2] ((layered/p:layer :handler/right right-result bank) n1)
          [reducer-props n3] ((dispatch/reduce-results
                               (dispatch/select-one-policy [:handler/left :handler/right] default)
                               bank
                               out)
                              n2)
          n4 (-> n3
                 (nb/seed-cell left-result :left)
                 (nb/seed-cell right-result :right)
                 (nb/run-propagators (into [left-slot-prop right-slot-prop] reducer-props)))]
      (is (= :bool4/contradiction (net/network-cell-strongest n4 out))))))

(deftest generic-procedure-prototype-runs-merged-predicate-and-handler-topology
  (testing "one merged predicate/handler extension selects its handler"
    (let [proc-id (ids/new-node-id)
          arg-id (ids/new-node-id)
          default-id (ids/new-node-id)
          out-id (ids/new-node-id)
          n0 (-> (installed-cells proc-id arg-id default-id out-id)
                 (nb/seed-cell arg-id 10)
                 (nb/seed-cell default-id :default))
          n1 (extend-procedure
              n0
              proc-id
              (procedure-extension
               {:predicate/number (predicate-closure number?)
                :handler/number (handler-closure (fn [x] [:number x]))}))
          app-net (run-generic-application n1
                                           proc-id
                                           [arg-id]
                                           out-id
                                           [:handler/number]
                                           default-id)]
      (is (= [:number 10] (net/network-cell-strongest app-net out-id)))))

  (testing "no applicable merged predicate falls back to default"
    (let [proc-id (ids/new-node-id)
          arg-id (ids/new-node-id)
          default-id (ids/new-node-id)
          out-id (ids/new-node-id)
          n0 (-> (installed-cells proc-id arg-id default-id out-id)
                 (nb/seed-cell arg-id "x")
                 (nb/seed-cell default-id :default))
          n1 (extend-procedure
              n0
              proc-id
              (procedure-extension
               {:predicate/number (predicate-closure number?)
                :handler/number (handler-closure (fn [x] [:number x]))}))
          app-net (run-generic-application n1
                                           proc-id
                                           [arg-id]
                                           out-id
                                           [:handler/number]
                                           default-id)]
      (is (= :default (net/network-cell-strongest app-net out-id)))))

  (testing "late merged predicate/handler extension affects later applications"
    (let [proc-id (ids/new-node-id)
          arg-id (ids/new-node-id)
          default-id (ids/new-node-id)
          out-id (ids/new-node-id)
          n0 (-> (installed-cells proc-id arg-id default-id out-id)
                 (nb/seed-cell arg-id "x")
                 (nb/seed-cell default-id :default))
          n1 (extend-procedure
              n0
              proc-id
              (procedure-extension
               {:predicate/number (predicate-closure number?)
                :handler/number (handler-closure (fn [x] [:number x]))}))
          first-app (run-generic-application n1
                                             proc-id
                                             [arg-id]
                                             out-id
                                             [:handler/number]
                                             default-id)
          n2 (extend-procedure
              n1
              proc-id
              (procedure-extension
               {:predicate/string (predicate-closure string?)
                :handler/string (handler-closure (fn [x] [:string x]))}))
          second-app (run-generic-application n2
                                              proc-id
                                              [arg-id]
                                              out-id
                                              [:handler/number :handler/string]
                                              default-id)]
      (is (= :default (net/network-cell-strongest first-app out-id)))
      (is (= [:string "x"] (net/network-cell-strongest second-app out-id))))))
