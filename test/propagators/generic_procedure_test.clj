(ns propagators.generic-procedure-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.compile :as compile]
            [propagators.datastructures.compound-object :as obj]
            [propagators.debugger :as debugger]
            [propagators.generic-procedure :as generic]
            [propagators.ids :as ids]
            [propagators.datastructures.named-network :as named]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- installed-cells
  [& ids]
  (reduce nb/install-cell net/empty-net ids))

(defn- run-props
  [n prop-ids]
  (nb/run-propagators n prop-ids))

(defn- install-only
  [n installer]
  (second (installer n)))

(defn- initialize-generic
  [n generic-id default-id]
  (install-only n (generic/make-generic-propagator generic-id default-id)))

(defn- apply-generic
  [n generic-id arg-ids out-id]
  (compile/install-and-run n (generic/p:apply-generic generic-id arg-ids out-id)))

(defn- one-arg-generic-net
  [arg-value]
  (let [generic-id (ids/new-node-id)
        arg-id (ids/new-node-id)
        default-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> (installed-cells generic-id arg-id default-id out-id)
               (nb/seed-cell arg-id arg-value)
               (nb/seed-cell default-id value/nothing))
        n1 (initialize-generic n0 generic-id default-id)]
    {:net n1
     :generic-id generic-id
     :arg-id arg-id
     :default-id default-id
     :out-id out-id}))

(defn- two-arg-generic-net
  [left-value right-value]
  (let [generic-id (ids/new-node-id)
        left-id (ids/new-node-id)
        right-id (ids/new-node-id)
        default-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> (installed-cells generic-id left-id right-id default-id out-id)
               (nb/seed-cell left-id left-value)
               (nb/seed-cell right-id right-value)
               (nb/seed-cell default-id value/nothing))
        n1 (initialize-generic n0 generic-id default-id)]
    {:net n1
     :generic-id generic-id
     :left-id left-id
     :right-id right-id
     :default-id default-id
     :out-id out-id}))

(defn- empty-one-arg-generic-net
  []
  (let [generic-id (ids/new-node-id)
        default-id (ids/new-node-id)
        n0 (-> (installed-cells generic-id default-id)
               (nb/seed-cell default-id value/nothing))
        n1 (initialize-generic n0 generic-id default-id)]
    {:net n1
     :generic-id generic-id
     :default-id default-id}))

(defn- define-exact-match-handlers
  [n generic-id handler-count]
  (reduce
   (fn [acc i]
     (install-only
      acc
      (generic/define-generic-propagator-handler
       generic-id
       (generic/match-cells-pred #(= i %))
       (generic/handler-closure (fn [x] [:exact i x])))))
   n
   (range handler-count)))

(defn- apply-generic-round
  [n generic-id arg-value]
  (let [arg-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> n
               (nb/install-cell arg-id)
               (nb/install-cell out-id)
               (nb/seed-cell arg-id arg-value))
        n1 (apply-generic n0 generic-id [arg-id] out-id)]
    {:net n1
     :out-id out-id
     :value (net/network-cell-strongest n1 out-id)}))

(defn- apply-generic-rounds
  [n generic-id arg-values]
  (reduce
   (fn [{:keys [net values]} arg-value]
     (let [round (apply-generic-round net generic-id arg-value)]
       {:net (:net round)
        :values (conj values (:value round))}))
   {:net n :values []}
   arg-values))

(defn- nested-generic-handler
  [inner-net inner-generic-id]
  (closure/closure
   (fn [closure-net input-ids output-ids network]
     (let [[arg-id] input-ids
           [out-id] output-ids
           n0 (named/join network closure-net)
           [_ n1] ((generic/p:apply-generic inner-generic-id [arg-id] out-id) n0)]
       n1))
   inner-net))

(deftest apply-closure-installs-compound-propagator
  (testing "closure helper applies a closure-valued cell"
    (let [closure-id (ids/new-node-id)
          in-id (ids/new-node-id)
          out-id (ids/new-node-id)
          closure-value (generic/handler-closure inc)
          n0 (-> (installed-cells closure-id in-id out-id)
                 (nb/seed-cell closure-id closure-value)
                 (nb/seed-cell in-id 10))
          [apply-prop n1] ((closure/p:apply-closure closure-id in-id out-id) n0)
          n2 (run-props n1 [apply-prop])]
      (is (= 11 (net/network-cell-strongest n2 out-id))))))

(deftest compile-symbols-resolve-from-network-dict
  (testing "eval-layered can use bindings already stored in the network dict"
    (let [in-id (ids/new-node-id)
          out-id (ids/new-node-id)
          n0 (-> (installed-cells in-id out-id)
                 (compile/bind-vars {'in in-id 'out out-id}))
          ctx (compile/eval-layered
               n0
               {'p/inc (prop/primitive-propagator inc)}
               {}
               '(p/inc in out))
          n1 (-> (:net ctx)
                 (nb/seed-cell in-id 10)
                 (run-props (:props ctx)))]
      (is (= 11 (net/network-cell-strongest n1 out-id))))))

(deftest make-generic-propagator-declares-select-one-policy
  (testing "default and fixed policy are declared without materializing the generic cell"
    (let [{:keys [net generic-id arg-id out-id]} (one-arg-generic-net :x)
          declarations (obj/accessor-declarations-for net generic-id)
          n2 (apply-generic net generic-id [arg-id] out-id)]
      (is (contains? declarations :generic/default))
      (is (contains? declarations :generic/policy))
      (is (nil? (obj/slot-value (net/network-cell-strongest net generic-id)
                                :generic/default)))
      (is (= value/nothing (net/network-cell-strongest n2 out-id))))))

(deftest generic-method-branch-is-compound-slot-object
  (testing "method extension declares branch slot topology without materializing it"
    (let [{:keys [net generic-id arg-id out-id]} (one-arg-generic-net 10)
          pred (generic/predicate-closure number?)
          handler (generic/handler-closure inc)
          n2 (install-only
              net
              (generic/define-generic-propagator-handler
               generic-id
               (generic/match-cells [pred])
               handler))
          method-entry (first (filter (fn [[slot-key _parent->declaration]]
                                        (and (vector? slot-key)
                                             (= :generic/method (first slot-key))))
                                      (obj/accessor-declarations-for n2 generic-id)))
          branch-id (first (keys (val method-entry)))
          branch-declarations (obj/accessor-declarations-for n2 branch-id)
          n3 (apply-generic n2 generic-id [arg-id] out-id)]
      (is (some? method-entry))
      (is (= #{:method/predicates :method/matcher :method/handler}
             (set (keys branch-declarations))))
      (is (nil? (obj/slot-value (net/network-cell-strongest n2 generic-id)
                                (key method-entry))))
      (is (= 11 (net/network-cell-strongest n3 out-id))))))

(deftest generic-default-slot-preserves-nothing
  (testing "slot-based default attachment keeps the-nothing as a real no-match default"
    (let [generic-id (ids/new-node-id)
          arg-id (ids/new-node-id)
          default-id (ids/new-node-id)
          out-id (ids/new-node-id)
          n0 (-> (installed-cells generic-id arg-id default-id out-id)
                 (nb/seed-cell arg-id :x)
                 (nb/seed-cell default-id value/nothing))
          n1 (initialize-generic n0 generic-id default-id)
          declarations (obj/accessor-declarations-for n1 generic-id)
          n2 (apply-generic n1 generic-id [arg-id] out-id)]
      (is (contains? declarations :generic/default))
      (is (= value/nothing (net/network-cell-strongest n2 out-id))))))

;; TODO(generic first-order refinement): suspended. This should revise the
;; generic application answer after a late handler and later input update. Today
;; the first concrete answer commits to the output cell; the later answer needs
;; first-order refining information instead of an ordinary overwrite.
#_(deftest installed-generic-application-observes-late-handler-with-nothing-default
    (testing "an existing generic application is woken by later handler attachment"
      (let [generic-id (ids/new-node-id)
            arg-id (ids/new-node-id)
            default-id (ids/new-node-id)
            out-id (ids/new-node-id)
            n0 (-> (installed-cells generic-id arg-id default-id out-id)
                   (nb/seed-cell arg-id "x")
                   (nb/seed-cell default-id value/nothing))
            n1 (initialize-generic n0 generic-id default-id)
            [apply-prop n2] ((generic/p:apply-generic generic-id [arg-id] out-id) n1)
            n3 (run-props n2 [apply-prop])
            n4 (install-only
                n3
                (generic/define-generic-propagator-handler
                 generic-id
                 (generic/match-cells-pred string?)
                 (generic/handler-closure (fn [x] [:string x]))))
            n5 (-> n4
                   (nb/seed-cell arg-id "y")
                   (run-props [apply-prop]))]
        (is (= value/nothing (net/network-cell-strongest n3 out-id)))
        (is (= [:string "y"] (net/network-cell-strongest n5 out-id))))))

(deftest generic-propagator-selects-one-matching-method
  (testing "one defined method with true matcher emits its handler result"
    (let [{:keys [net generic-id arg-id out-id]} (one-arg-generic-net 10)
          n2 (install-only
              net
              (generic/define-generic-propagator-handler
               generic-id
               (generic/match-cells-pred number?)
               (generic/handler-closure (fn [x] [:number x]))))
          n3 (apply-generic n2 generic-id [arg-id] out-id)]
      (is (= [:number 10] (net/network-cell-strongest n3 out-id))))))

(deftest generic-handler-before-initializer-is-visible-on-apply
  (testing "method declaration can happen before generic initialization"
    (let [generic-id (ids/new-node-id)
          arg-id (ids/new-node-id)
          default-id (ids/new-node-id)
          out-id (ids/new-node-id)
          n0 (-> (installed-cells generic-id arg-id default-id out-id)
                 (nb/seed-cell arg-id 10)
                 (nb/seed-cell default-id value/nothing))
          n1 (install-only
              n0
              (generic/define-generic-propagator-handler
               generic-id
               (generic/match-cells-pred number?)
               (generic/handler-closure (fn [x] [:number x]))))
          n2 (initialize-generic n1 generic-id default-id)
          n3 (apply-generic n2 generic-id [arg-id] out-id)]
      (is (= [:number 10] (net/network-cell-strongest n3 out-id))))))

(deftest define-generic-propagator-handler-supports-scheme-like-api
  (testing "handler API accepts matcher predicates and a handler closure"
    (let [{:keys [net generic-id left-id right-id out-id]}
          (two-arg-generic-net 10 20)
          n1 (install-only
              net
              (generic/define-generic-propagator-handler
                generic-id
                (generic/match-cells-pred number? number?)
                (generic/handler-closure +)))
          n2 (apply-generic n1 generic-id [left-id right-id] out-id)]
      (is (= 30 (net/network-cell-strongest n2 out-id))))))

(deftest debugger-reports-generic-dispatch
  (testing "generic debugger reports predicates, handler result, and selected value"
    (let [events (atom [])
          {:keys [net generic-id arg-id out-id]} (one-arg-generic-net 10)
          n2 (install-only
              net
              (generic/define-generic-propagator-handler
               generic-id
               (generic/match-cells-pred number?)
               (generic/handler-closure inc)))]
      (try
        (debugger/set-sink! #(swap! events conj %))
        (debugger/enable!)
        (apply-generic n2 generic-id [arg-id] out-id)
        (let [method-event (first (filter #(= :generic/method (:event %)) @events))
              selected-event (first (filter #(= :generic/selected (:event %)) @events))]
          (is (some? (:method-key method-event)))
          (is (= [true] (:predicate-results method-event)))
          (is (= true (:matched? method-event)))
          (is (= [10] (:filtered-args method-event)))
          (is (= 11 (:handler-result method-event)))
          (is (= 11 (:result-bank-value method-event)))
          (is (= 11 (:selected-value selected-event))))
        (finally
          (debugger/disable!)
          (debugger/reset-sink!))))))

(deftest generic-propagator-emits-nothing-when-no-method-matches
  (testing "false matcher leaves result bank empty, so select-one emits no concrete result"
    (let [{:keys [net generic-id arg-id out-id]} (one-arg-generic-net "x")
          n2 (install-only
              net
              (generic/define-generic-propagator-handler
               generic-id
               (generic/match-cells-pred number?)
               (generic/handler-closure (fn [x] [:number x]))))
          n3 (apply-generic n2 generic-id [arg-id] out-id)]
      (is (= value/nothing (net/network-cell-strongest n3 out-id))))))

(deftest generic-propagator-contradicts-when-two-methods-match
  (testing "two usable branch results reduce to contradiction"
    (let [{:keys [net generic-id arg-id out-id]} (one-arg-generic-net 10)
          n2 (install-only
              net
              (generic/define-generic-propagator-handler
               generic-id
               (generic/match-cells-pred number?)
               (generic/handler-closure (fn [x] [:number x]))))
          n3 (install-only
              n2
              (generic/define-generic-propagator-handler
               generic-id
               (generic/match-cells-pred (constantly true))
               (generic/handler-closure (fn [x] [:any x]))))
          n4 (apply-generic n3 generic-id [arg-id] out-id)]
      (is (= :bool4/contradiction (net/network-cell-strongest n4 out-id))))))

(deftest late-generic-method-extension-affects-later-applications
  (testing "a later merged method branch changes later generic applications"
    (let [{:keys [net generic-id arg-id out-id]} (one-arg-generic-net "x")
          n2 (install-only
              net
              (generic/define-generic-propagator-handler
               generic-id
               (generic/match-cells-pred number?)
               (generic/handler-closure (fn [x] [:number x]))))
          first-app (apply-generic n2 generic-id [arg-id] out-id)
          n3 (install-only
              n2
              (generic/define-generic-propagator-handler
               generic-id
               (generic/match-cells-pred string?)
               (generic/handler-closure (fn [x] [:string x]))))
          second-app (apply-generic n3 generic-id [arg-id] out-id)]
      (is (= value/nothing (net/network-cell-strongest first-app out-id)))
      (is (= [:string "x"] (net/network-cell-strongest second-app out-id))))))

(deftest nested-generic-procedure-dispatch-is-materialized-during-outer-apply
  (testing "outer generic handler can apply an inner generic whose handlers were only declared"
    (let [{inner-net :net inner-generic-id :generic-id}
          (empty-one-arg-generic-net)
          inner-net (install-only
                     inner-net
                     (generic/define-generic-propagator-handler
                      inner-generic-id
                      (generic/match-cells-pred number?)
                      (generic/handler-closure (fn [x] [:inner x]))))
          outer-generic-id (ids/new-node-id)
          arg-id (ids/new-node-id)
          default-id (ids/new-node-id)
          out-id (ids/new-node-id)
          n0 (-> (installed-cells outer-generic-id arg-id default-id out-id)
                 (nb/seed-cell arg-id 7)
                 (nb/seed-cell default-id value/nothing))
          n1 (initialize-generic n0 outer-generic-id default-id)
          n2 (install-only
              n1
              (generic/define-generic-propagator-handler
               outer-generic-id
               (generic/match-cells-pred number?)
               (nested-generic-handler inner-net inner-generic-id)))
          n3 (apply-generic n2 outer-generic-id [arg-id] out-id)]
      (is (= [:inner 7] (net/network-cell-strongest n3 out-id))))))

(deftest generic-propagator-pressure-dispatches-ten-handlers-over-multiple-rounds
  (testing "one generic cell with 10 handlers dispatches repeated fresh applications"
    (let [{:keys [net generic-id]} (empty-one-arg-generic-net)
          n1 (define-exact-match-handlers net generic-id 10)
          rounds [0 5 9 3 7 42]
          result (apply-generic-rounds n1 generic-id rounds)]
      (is (= [[:exact 0 0]
              [:exact 5 5]
              [:exact 9 9]
              [:exact 3 3]
              [:exact 7 7]
              value/nothing]
             (:values result))))))

(deftest generic-propagator-pressure-dispatches-fifty-handlers-over-multiple-rounds
  (testing "50 predicates and handlers stay correct over 50 dispatch rounds"
    (let [{:keys [net generic-id]} (empty-one-arg-generic-net)
          n1 (define-exact-match-handlers net generic-id 50)
          rounds (vec (concat (range 50) [100]))
          result (apply-generic-rounds n1 generic-id rounds)
          expected (vec (concat (mapv (fn [i] [:exact i i]) (range 50))
                                [value/nothing]))]
      (is (= expected (:values result))))))

(deftest generic-operator-wraps-apply-generic
  (testing "operator convenience installs the same generic application"
    (let [{:keys [net generic-id arg-id out-id]} (one-arg-generic-net 10)
          n2 (install-only
              net
              (generic/define-generic-propagator-handler
               generic-id
               (generic/match-cells-pred number?)
               (generic/handler-closure inc)))
          [apply-prop n3] (((generic/p:generic-operator generic-id) arg-id out-id) n2)
          n4 (run-props n3 [apply-prop])]
      (is (= 11 (net/network-cell-strongest n4 out-id))))))
