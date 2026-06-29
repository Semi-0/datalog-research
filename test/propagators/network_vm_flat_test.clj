(ns propagators.network-vm-flat-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.install :as i]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-vm.flat :as fvm]
            [propagators.network-vm.flat.gur :as fgur]
            [propagators.propagator :as prop]))

(defn- id
  [& parts]
  (fvm/stable-node-id (into [:network-vm-flat-test] parts)))

(defn- strongest
  [vm-state cell-id]
  (net/network-cell-strongest (:net vm-state) cell-id))

(defn- prop-count
  [vm-state]
  (count (filter prop/prop? (vals (net/net-env (:net vm-state))))))

(defn- topology-counts
  [vm-state]
  {:graph (count (net/net-graph (:net vm-state)))
   :env (count (net/net-env (:net vm-state)))
   :dict (count (net/net-dict-or-empty (:net vm-state)))
   :props (prop-count vm-state)})

(defn- declare-cells
  [ids]
  (mapv fvm/declare-cell ids))

(defn- apply-kernel-effects
  [vm-state effects]
  (let [[tasks n] (core/eval-effects effects (:net vm-state))]
    {:net (core/run-tasks tasks n)}))

(defn- run-kernel-effects
  [effects]
  (apply-kernel-effects {:net (fvm/vm-net)} effects))

(defn- read-list-prefix
  [vm-state root-id max-count]
  (loop [state vm-state
         current-id root-id
         i 0
         values []]
    (if (= i max-count)
      {:state state :values values}
      (let [car-id (id :reader root-id i :car)
            cdr-id (id :reader root-id i :cdr)
            state* (apply-kernel-effects
                    state
                    [(fvm/declare-cell car-id)
                     (fvm/declare-cell cdr-id)
                     (fvm/install-topology [:reader root-id i :car]
                                           (obj/p:car car-id current-id))
                     (fvm/install-topology [:reader root-id i :cdr]
                                           (obj/p:cdr cdr-id current-id))])
            car-value (strongest state* car-id)
            cdr-value (strongest state* cdr-id)]
        (cond
          (value/unusable? car-value)
          {:state state* :values values}

          (value/nothing? cdr-value)
          {:state state* :values (conj values car-value)}

          :else
          (recur state* cdr-id (inc i) (conj values car-value)))))))

(defn- read-list-prefix-including-nothing
  [vm-state root-id max-count]
  (loop [state vm-state
         current-id root-id
         i 0
         values []]
    (if (= i max-count)
      {:state state :values values}
      (let [car-id (id :reader :including-nothing root-id i :car)
            cdr-id (id :reader :including-nothing root-id i :cdr)
            state* (apply-kernel-effects
                    state
                    [(fvm/declare-cell car-id)
                     (fvm/declare-cell cdr-id)
                     (fvm/install-topology [:reader :including-nothing root-id i :car]
                                           (obj/p:car car-id current-id))
                     (fvm/install-topology [:reader :including-nothing root-id i :cdr]
                                           (obj/p:cdr cdr-id current-id))])
            car-value (strongest state* car-id)
            cdr-value (strongest state* cdr-id)]
        (cond
          (value/contradiction? car-value)
          {:state state* :values values}

          (value/nothing? cdr-value)
          {:state state* :values (conj values car-value)}

          :else
          (recur state* cdr-id (inc i) (conj values car-value)))))))

(def double-value
  (fgur/recursive-declaration
   'double-value
   (fn [ctx [value-id] out-id]
     (-> ctx
         (i/$ {:value value-id
               :out out-id})
         (i/tell :two 2)
         (i/* :value :two :out)))))

(def bidirectional-id
  (fgur/recursive-declaration
   'bidirectional-id
   (fn [ctx [value-id] out-id]
     (-> ctx
         (i/$ {:value value-id
               :out out-id})
         (i/copy :value :out)
         (i/copy :out :value)))))

(def bidirectional-double-value
  (fgur/recursive-declaration
   'bidirectional-double-value
   (fn [ctx [value-id] out-id]
     (-> ctx
         (i/$ {:value value-id
               :out out-id})
         (i/tell :two 2)
         (i/* :value :two :out)
         (i// :out :two :value)))))

(def unused-acc-list :unused)

(def map-list
  (fgur/recursive-declaration
   'map-list
   (fn [ctx [list-id mapper-id acc-id] out-id]
     (-> ctx
         (i/$ {:xs list-id
               :mapper mapper-id
               :acc acc-id
               :out out-id})
         (i/car :head :xs)
         (i/cdr :rest :xs)
         (i/>> :mapper :head :mapped)
         (i/cons :mapped :mapped-rest :out)
         (i/when :rest
           (i/recur [:rest :mapper :acc] :mapped-rest))))))

(def factorial-value
  (fgur/recursive-closure
   'factorial-value
   (fn [{:keys [stable-id]
         recur-fn :recur} [n-id] out-id]
     (let [step-id (stable-id :factorial-step)]
       [(fvm/declare-prop
         step-id
         [n-id]
         [out-id]
         (fn [_inputs _outputs network]
           (let [n (net/network-cell-strongest network n-id)]
             (cond
               (value/unusable? n)
               []

               (<= n 1)
               [(message out-id 1)]

               :else
               (let [next-id (stable-id :next)
                     recur-out-id (stable-id :recur-out)
                     mul-id (stable-id :mul)]
                 [(fvm/declare-cell next-id)
                  (fvm/declare-cell recur-out-id)
                  (fvm/tell next-id (dec n))
                  (recur-fn [next-id] recur-out-id)
                  (fvm/declare-prop mul-id
                                     [recur-out-id]
                                     [out-id]
                                     (fgur/unary-prop #(* n %)))])))))]))))

(def fibonacci-value
  (fgur/recursive-closure
   'fibonacci-value
   (fn [{:keys [stable-id]
         recur-fn :recur} [n-id] out-id]
     (let [step-id (stable-id :fibonacci-step)]
       [(fvm/declare-prop
         step-id
         [n-id]
         [out-id]
         (fn [_inputs _outputs network]
           (let [n (net/network-cell-strongest network n-id)]
             (cond
               (value/unusable? n)
               []

               (< n 2)
               [(message out-id n)]

               :else
               (let [n-1-id (stable-id :n-1)
                     n-2-id (stable-id :n-2)
                     fib-1-id (stable-id :fib-1)
                     fib-2-id (stable-id :fib-2)
                     plus-id (stable-id :plus)]
                 [(fvm/declare-cell n-1-id)
                  (fvm/declare-cell n-2-id)
                  (fvm/declare-cell fib-1-id)
                  (fvm/declare-cell fib-2-id)
                  (fvm/tell n-1-id (dec n))
                  (fvm/tell n-2-id (- n 2))
                  (recur-fn [n-1-id] fib-1-id)
                  (recur-fn [n-2-id] fib-2-id)
                  (fvm/declare-prop plus-id
                                     [fib-1-id fib-2-id]
                                     [out-id]
                                     (fgur/binary-prop +))])))))]))))

(def even-or-nothing
  (fgur/recursive-declaration
   'even-or-nothing
   (fn [ctx [value-id] out-id]
     (-> ctx
         (i/$ {:value value-id
               :out out-id})
         (i/prop :even-or-nothing
                 [:value]
                 [:out]
                 (fgur/unary-prop #(if (even? %)
                                     %
                                     value/nothing)))))))

(def lexical-x-lookup
  (fgur/recursive-closure
   'lexical-x-lookup
   (fn [{:keys [app-key stable-id]
         recur-fn :recur
         when-fn :when} [env-id] out-id]
     (let [x-id (stable-id :x)
           parent-id (stable-id :parent)]
       [(fvm/declare-cell x-id)
        (fvm/declare-cell parent-id)
        (fvm/install-topology [app-key :x]
                              (obj/p:slot :x x-id env-id))
        (fvm/install-topology [app-key :parent]
                              (obj/p:slot :parent parent-id env-id))
        (fvm/declare-prop (stable-id :current-x)
                           [x-id]
                           [out-id]
                           (fgur/unary-prop identity))
        (when-fn [app-key :when-parent]
                  parent-id
                  (fn []
                    [(recur-fn [parent-id] out-id)]))]))))

(defn- source-list-effects
  [run-key values terminal-value seed-heads?]
  (let [values (vec values)
        len (count values)
        heads (mapv #(id run-key :source % :head) (range len))
        colls (mapv #(id run-key :source % :coll) (range len))
        terminal-id (id run-key :source :terminal)
        cells (vec (concat heads colls [terminal-id]))
        cons-effects
        (mapv (fn [i]
                (fvm/install-topology
                 [run-key :source :cons i]
                 (obj/p:cons (heads i)
                             (if (= i (dec len))
                               terminal-id
                               (colls (inc i)))
                             (colls i))))
              (range len))
        seed-effects
        (cond-> []
          seed-heads?
          (into (map-indexed (fn [i v]
                               (fvm/tell (heads i) v))
                             values))
          true
          (conj (fvm/tell terminal-id terminal-value)))]
    {:root-id (first colls)
     :head-id (first heads)
     :effects (vec (concat (declare-cells cells)
                           cons-effects
                           seed-effects))}))

(defn- run-flat-map-chain
  ([mapper values depth]
   (run-flat-map-chain mapper values depth true value/nothing))
  ([mapper values depth seed-heads? terminal-value]
   (let [run-key [:chain depth mapper seed-heads?]
         op-id (id run-key :op)
         mapper-id (id run-key :mapper)
         acc-id (id run-key :acc)
         out-ids (mapv #(id run-key :out %) (range depth))
         {:keys [root-id head-id effects]} (source-list-effects run-key
                                                                values
                                                                terminal-value
                                                                seed-heads?)
         apply-effects (mapv (fn [i]
                               (let [in-id (if (zero? i)
                                             root-id
                                             (out-ids (dec i)))]
                                 (fgur/apply-closure-effect
                                  op-id
                                  [in-id mapper-id acc-id]
                                  (out-ids i))))
                             (range depth))
         initial-effects (vec (concat [(fvm/declare-cell op-id)
                                       (fvm/declare-cell mapper-id)
                                       (fvm/declare-cell acc-id)
                                       (fvm/tell op-id map-list)
                                       (fvm/tell mapper-id mapper)
                                       (fvm/tell acc-id unused-acc-list)]
                                      effects
                                      (declare-cells out-ids)
                                      apply-effects))
         final (run-kernel-effects initial-effects)]
     {:state final
      :head-id head-id
      :out-id (peek out-ids)})))

(defn- run-flat-closure
  [run-key closure arg-values]
  (let [closure-id (id run-key :closure)
        arg-ids (mapv #(id run-key :arg %) (range (count arg-values)))
        out-id (id run-key :out)
        effects (vec (concat [(fvm/declare-cell closure-id)
                              (fvm/declare-cell out-id)
                              (fvm/tell closure-id closure)]
                             (declare-cells arg-ids)
                             (mapv (fn [arg-id value]
                                     (fvm/tell arg-id value))
                                   arg-ids
                                   arg-values)
                             [(fgur/apply-closure-effect closure-id
                                                         arg-ids
                                                         out-id)]))]
    {:state (run-kernel-effects effects)
     :out-id out-id}))

(defn- run-flat-filter-chain
  [values depth]
  (let [run-key [:filter-chain depth]
        op-id (id run-key :op)
        mapper-id (id run-key :mapper)
        acc-id (id run-key :acc)
        out-ids (mapv #(id run-key :out %) (range depth))
        {:keys [root-id effects]} (source-list-effects run-key
                                                       values
                                                       value/nothing
                                                       true)
        apply-effects (mapv (fn [i]
                              (let [in-id (if (zero? i)
                                            root-id
                                            (out-ids (dec i)))]
                                (fgur/apply-closure-effect
                                 op-id
                                 [in-id mapper-id acc-id]
                                 (out-ids i))))
                            (range depth))
        initial-effects (vec (concat [(fvm/declare-cell op-id)
                                      (fvm/declare-cell mapper-id)
                                      (fvm/declare-cell acc-id)
                                      (fvm/tell op-id map-list)
                                      (fvm/tell mapper-id even-or-nothing)
                                      (fvm/tell acc-id unused-acc-list)]
                                     effects
                                     (declare-cells out-ids)
                                     apply-effects))]
    {:state (run-kernel-effects initial-effects)
     :out-id (peek out-ids)}))

(defn- lexical-env-effects
  [run-key]
  (let [parent-env-id (id run-key :parent-env)
        parent-x-id (id run-key :parent-x)
        child-env-id (id run-key :child-env)
        child-parent-id (id run-key :child-parent)
        child-x-id (id run-key :child-x)]
    {:parent-env-id parent-env-id
     :parent-x-id parent-x-id
     :child-env-id child-env-id
     :child-parent-id child-parent-id
     :child-x-id child-x-id
     :effects [(fvm/declare-cell parent-env-id)
               (fvm/declare-cell parent-x-id)
               (fvm/declare-cell child-env-id)
               (fvm/declare-cell child-parent-id)
               (fvm/declare-cell child-x-id)
               (fvm/install-topology [run-key :parent-x-slot]
                                     (obj/p:slot :x parent-x-id parent-env-id))
               (fvm/install-topology [run-key :child-x-slot]
                                     (obj/p:slot :x child-x-id child-env-id))
               (fvm/install-topology [run-key :child-parent-slot]
                                     (obj/p:slot :parent
                                                 child-parent-id
                                                 child-env-id))
               (fvm/declare-prop (id run-key :parent-env-link)
                                 [parent-env-id]
                                 [child-parent-id]
                                 (fgur/unary-prop identity))]}))

(defn- run-lexical-lookup
  [run-key extra-effects]
  (let [{:keys [child-env-id effects] :as env} (lexical-env-effects run-key)
        lookup-id (id run-key :lookup)
        out-id (id run-key :out)
        initial-effects (vec (concat [(fvm/declare-cell lookup-id)
                                      (fvm/declare-cell out-id)
                                      (fvm/tell lookup-id lexical-x-lookup)]
                                     effects
                                     extra-effects
                                     [(fgur/apply-closure-effect
                                       lookup-id
                                       [child-env-id]
                                       out-id)]))]
    (assoc env
           :state (run-kernel-effects initial-effects)
           :out-id out-id)))

(deftest flat-effects-declarations-are-idempotent
  (let [a-id (id :basic-a)
        b-id (id :basic-b)
        prop-id (id :basic-prop)
        effects [(fvm/declare-prop prop-id [a-id] [b-id]
                                   (fgur/unary-prop inc))]
        s1 (run-kernel-effects effects)
        counts (topology-counts s1)
        s2 (apply-kernel-effects s1 effects)
        counts* (topology-counts s2)]
    (is (= counts counts*))))

(deftest flat-effects-scalar-recursion
  (let [{fact-state :state fact-out :out-id}
        (run-flat-closure [:scalar :factorial 5]
                          factorial-value
                          [5])
        {fib-state :state fib-out :out-id}
        (run-flat-closure [:scalar :fibonacci 6]
                          fibonacci-value
                          [6])]
    (is (= 120 (strongest fact-state fact-out)))
    (is (= 8 (strongest fib-state fib-out)))))

(deftest flat-effects-filter-as-map-over-live-pcons-source
  (doseq [depth [1 5 10]]
    (let [{:keys [state out-id]} (run-flat-filter-chain [1 2 3 4 5 6]
                                                        depth)
          {:keys [values]} (read-list-prefix-including-nothing state out-id 8)]
      (is (= [value/nothing 2 value/nothing 4 value/nothing 6] values)
          (str "filter-as-map chain depth " depth)))))

(deftest flat-effects-lexical-parent-late-binding-exposes-routing-gap
  (let [run-key [:lexical :parent-late]
        {:keys [state out-id parent-x-id]}
        (run-lexical-lookup run-key [])
        state* (apply-kernel-effects state [(fvm/tell parent-x-id 15)])]
    (is (value/nothing? (strongest state out-id)))
    (is (value/nothing? (strongest state* out-id))
        "Parent env slot updates do not yet wake the recursive lexical lookup.")))

(deftest flat-effects-lexical-child-late-binding-exposes-selection-gap
  (let [run-key [:lexical :child-late-gap]
        {:keys [state out-id child-x-id]}
        (run-lexical-lookup run-key
                            [(fvm/tell (id run-key :parent-x) 10)])
        state* (apply-kernel-effects state [(fvm/tell child-x-id 20)])]
    (is (= 10 (strongest state out-id)))
    (is (value/contradiction? (strongest state* out-id))
        "This flat probe has recursive traversal, but not lexical precedence refinement yet.")))

(deftest flat-effects-map-list-hops-over-live-pcons-source
  (doseq [depth [1 5 10 15]]
    (let [{:keys [state out-id]} (run-flat-map-chain
                                  double-value
                                  [1 1 1 1 1]
                                  depth)
          {:keys [values]} (read-list-prefix state out-id 6)]
      (is (= (vec (repeat 5 (long (Math/pow 2 depth))))
             values)
          (str "mapper chain depth " depth)))))

(deftest flat-effects-bidirectional-identity-hops-output-car-to-source
  (doseq [depth [2 5]]
    (let [{:keys [state head-id out-id]} (run-flat-map-chain
                                          bidirectional-id
                                          [1]
                                          depth
                                          false
                                          value/nothing)
          out-head-id (id :output-write depth :head)
          state* (apply-kernel-effects
                  state
                  [(fvm/declare-cell out-head-id)
                   (fvm/install-topology [:output-write depth :car]
                                         (obj/p:car out-head-id out-id))
                   (fvm/tell out-head-id 9)])
          {:keys [values]} (read-list-prefix state* out-id 1)]
      (is (= 9 (strongest state* head-id))
          (str "depth " depth))
      (is (= [9] values) (str "depth " depth)))))

(deftest flat-effects-bidirectional-double-hop-output-car-to-source
  (let [{:keys [state head-id out-id]} (run-flat-map-chain
                                        bidirectional-double-value
                                        [1]
                                        5
                                        false
                                        value/nothing)
        out-head-id (id :output-write :double-head)
        state* (apply-kernel-effects
                state
                [(fvm/declare-cell out-head-id)
                 (fvm/install-topology [:output-write :double-car]
                                       (obj/p:car out-head-id out-id))
                 (fvm/tell out-head-id 32)])
        {:keys [values]} (read-list-prefix state* out-id 1)]
    (is (= 1 (strongest state* head-id)))
    (is (= [32] values))))

(deftest flat-effects-late-cdr-attachment-wakes-map-chain
  (let [{:keys [state out-id]} (run-flat-map-chain
                                double-value
                                [1]
                                3)
        late-head-id (id :late-tail :head)
        late-terminal-id (id :late-tail :terminal)
        late-coll-id (id :late-tail :coll)
        source-tail-id (id [:chain 3 double-value true] :source :terminal)
        attach-prop-id (id :late-tail :attach)
        state* (apply-kernel-effects
                state
                [(fvm/declare-cell late-head-id)
                 (fvm/declare-cell late-terminal-id)
                 (fvm/declare-cell late-coll-id)
                 (fvm/install-topology [:late-tail :cons]
                                       (obj/p:cons late-head-id
                                                   late-terminal-id
                                                   late-coll-id))
                 (fvm/tell late-head-id 7)
                 (fvm/declare-prop attach-prop-id
                                   [late-coll-id]
                                   [source-tail-id]
                                   (fgur/unary-prop identity))])
        {:keys [values]} (read-list-prefix state* out-id 3)]
    (is (= [8 56] values))))

(deftest flat-effects-rerun-after-quiescence-does-not-grow
  (let [{:keys [state]} (run-flat-map-chain double-value [1 1 1 1 1] 15)
        counts (topology-counts state)
        state* {:net (core/run-tasks [] (:net state))}
        counts* (topology-counts state*)]
    (is (= counts counts*))))

(deftest flat-effects-gur-does-not-directly-merge-cell-entries
  (is (not (re-find #"merge-cell-entry"
                    (slurp "propagators/network_vm/flat/gur.clj")))))
