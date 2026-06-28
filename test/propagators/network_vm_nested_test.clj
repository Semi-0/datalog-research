(ns propagators.network-vm-nested-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-vm.nested :as nvm]
            [propagators.network-vm.nested.gur :as ngur]
            [propagators.propagator :as prop]))

(defn- id
  [& parts]
  (nvm/stable-node-id (into [:network-vm-nested-test] parts)))

(defn- target
  [child-id]
  [:cell child-id])

(defn- strongest-in-net
  [n cell-id]
  (net/network-cell-strongest n cell-id))

(defn- child-strongest
  [vm-state child-id cell-id]
  (strongest-in-net (nvm/child-net vm-state child-id) cell-id))

(defn- prop-count
  [n]
  (count (filter prop/prop? (vals (net/net-env n)))))

(defn- topology-counts
  [n]
  {:graph (count (net/net-graph n))
   :env (count (net/net-env n))
   :dict (count (net/net-dict-or-empty n))
   :props (prop-count n)})

(defn- declare-cells
  [t ids]
  (mapv #(nvm/declare-cell t %) ids))

(defn- read-list-prefix
  [vm-state child-id root-id max-count]
  (loop [state vm-state
         current-id root-id
         i 0
         values []]
    (if (= i max-count)
      {:state state :values values}
      (let [car-id (id :reader root-id i :car)
            cdr-id (id :reader root-id i :cdr)
            t (target child-id)
            state* (-> state
                       (nvm/apply-effects
                        [(nvm/declare-cell t car-id)
                         (nvm/declare-cell t cdr-id)
                         (nvm/install-topology t
                                               [:reader root-id i :car]
                                               (obj/p:car car-id current-id))
                         (nvm/install-topology t
                                               [:reader root-id i :cdr]
                                               (obj/p:cdr cdr-id current-id))])
                       (nvm/run-until-cold 200000))
            child (nvm/child-net state* child-id)
            car-value (strongest-in-net child car-id)
            cdr-value (strongest-in-net child cdr-id)]
        (cond
          (value/unusable? car-value)
          {:state state* :values values}

          (value/nothing? cdr-value)
          {:state state* :values (conj values car-value)}

          :else
          (recur state* cdr-id (inc i) (conj values car-value)))))))

(def double-value
  (ngur/recursive-closure
   'double-value
   (fn [{:keys [stable-id]} [value-id] out-id]
     [(nvm/declare-prop :self
                        (stable-id :double)
                        [value-id]
                        [out-id]
                        (ngur/unary-prop #(* 2 %)))])))

(def bidirectional-id
  (ngur/recursive-closure
   'bidirectional-id
   (fn [{:keys [stable-id]} [value-id] out-id]
     [(nvm/declare-prop :self
                        (stable-id :id-forward)
                        [value-id]
                        [out-id]
                        (ngur/unary-prop identity))
      (nvm/declare-prop :self
                        (stable-id :id-backward)
                        [out-id]
                        [value-id]
                        (ngur/unary-prop identity))])))

(def bidirectional-double-value
  (ngur/recursive-closure
   'bidirectional-double-value
   (fn [{:keys [stable-id]} [value-id] out-id]
     [(nvm/declare-prop :self
                        (stable-id :double-forward)
                        [value-id]
                        [out-id]
                        (ngur/unary-prop #(* 2 %)))
      (nvm/declare-prop :self
                        (stable-id :double-backward)
                        [out-id]
                        [value-id]
                        (ngur/unary-prop #(/ % 2)))])))

(def unused-acc-list :unused)

(def map-list
  (ngur/recursive-closure
   'map-list
   (fn [{:keys [app-key stable-id apply]
         recur-fn :recur
         when-fn :when} [list-id mapper-id acc-id] out-id]
     (let [head-id (stable-id :head)
           rest-id (stable-id :rest)
           mapped-id (stable-id :mapped)
           mapped-rest-id (stable-id :mapped-rest)]
       (into
        [(nvm/declare-cell :self head-id)
         (nvm/declare-cell :self rest-id)
         (nvm/declare-cell :self mapped-id)
         (nvm/declare-cell :self mapped-rest-id)
         (nvm/install-topology :self
                               [app-key :car]
                               (obj/p:car head-id list-id))
         (nvm/install-topology :self
                               [app-key :cdr]
                               (obj/p:cdr rest-id list-id))
         (apply mapper-id [head-id] mapped-id)
         (nvm/install-topology :self
                               [app-key :cons]
                               (obj/p:cons mapped-id mapped-rest-id out-id))]
        [(when-fn [app-key :when-rest]
                  rest-id
                  (fn []
                    [(recur-fn [rest-id mapper-id acc-id] mapped-rest-id)]))])))))

(defn- source-list-effects
  [child-id values terminal-value seed-heads?]
  (let [t (target child-id)
        values (vec values)
        len (count values)
        heads (mapv #(id child-id :source % :head) (range len))
        colls (mapv #(id child-id :source % :coll) (range len))
        terminal-id (id child-id :source :terminal)
        cells (vec (concat heads colls [terminal-id]))
        cons-effects
        (mapv (fn [i]
                (nvm/install-topology
                 t
                 [child-id :source :cons i]
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
                               (nvm/tell t (heads i) v))
                             values))
          true
          (conj (nvm/tell t terminal-id terminal-value)))]
    {:root-id (first colls)
     :head-id (first heads)
     :effects (vec (concat (declare-cells t cells)
                           cons-effects
                           seed-effects))}))

(defn- run-nested-map-chain
  ([mapper values depth]
   (run-nested-map-chain mapper values depth true value/nothing))
  ([mapper values depth seed-heads? terminal-value]
   (let [child-id (id :chain depth mapper seed-heads?)
         t (target child-id)
         op-id (id child-id :op)
         mapper-id (id child-id :mapper)
         acc-id (id child-id :acc)
         out-ids (mapv #(id child-id :out %) (range depth))
         {:keys [root-id head-id effects]} (source-list-effects child-id
                                                                values
                                                                terminal-value
                                                                seed-heads?)
         apply-effects (mapv (fn [i]
                               (let [in-id (if (zero? i)
                                             root-id
                                             (out-ids (dec i)))]
                                 (ngur/apply-closure-effect
                                  t
                                  op-id
                                  [in-id mapper-id acc-id]
                                  (out-ids i))))
                             (range depth))
         initial-effects (vec (concat [(nvm/declare-cell :self child-id)
                                       (nvm/declare-cell t op-id)
                                       (nvm/declare-cell t mapper-id)
                                       (nvm/declare-cell t acc-id)
                                       (nvm/tell t op-id map-list)
                                       (nvm/tell t mapper-id mapper)
                                       (nvm/tell t acc-id unused-acc-list)]
                                      effects
                                      (declare-cells t out-ids)
                                      apply-effects))
         final (nvm/run-until-cold
                (nvm/apply-effects (nvm/state) initial-effects)
                400000)]
     {:state final
      :child-id child-id
      :head-id head-id
      :out-id (peek out-ids)})))

(deftest nested-vm-targeted-declaration-and-writeback
  (let [child-id (id :basic-child)
        a-id (id :basic-a)
        b-id (id :basic-b)
        prop-id (id :basic-prop)
        t (target child-id)
        s0 (nvm/apply-effects
            (nvm/state)
            [(nvm/declare-cell :self child-id)
             (nvm/declare-cell t a-id)
             (nvm/declare-cell t b-id)
             (nvm/tell t a-id 1)
             (nvm/declare-prop t prop-id [a-id] [b-id]
                               (ngur/unary-prop inc))])
        s1 (nvm/run-until-cold s0)]
    (is (= 2 (child-strongest s1 child-id b-id)))
    (is (net/net? (strongest-in-net (:net s1) child-id)))
    (is (= 1 (prop-count (nvm/child-net s1 child-id))))))

(deftest nested-vm-duplicate-declaration-is-idempotent
  (let [child-id (id :dupe-child)
        a-id (id :dupe-a)
        b-id (id :dupe-b)
        prop-id (id :dupe-prop)
        t (target child-id)
        effects [(nvm/declare-cell :self child-id)
                 (nvm/declare-prop t prop-id [a-id] [b-id]
                                   (ngur/unary-prop inc))]
        s1 (nvm/run-until-cold (nvm/apply-effects (nvm/state) effects))
        counts (topology-counts (nvm/child-net s1 child-id))
        s2 (nvm/run-until-cold (nvm/apply-effects s1 effects))
        counts* (topology-counts (nvm/child-net s2 child-id))]
    (is (= counts counts*))))

(deftest nested-vm-late-child-message-reheats-child-vm
  (let [child-id (id :late-child)
        a-id (id :late-a)
        b-id (id :late-b)
        prop-id (id :late-prop)
        t (target child-id)
        s0 (nvm/run-until-cold
            (nvm/apply-effects
             (nvm/state)
             [(nvm/declare-cell :self child-id)
              (nvm/declare-prop t prop-id [a-id] [b-id]
                                (ngur/unary-prop inc))]))
        s1 (nvm/apply-effect s0 (nvm/tell t a-id 4))
        s2 (nvm/run-until-cold s1)]
    (is (zero? (nvm/temperature s0)))
    (is (pos? (nvm/temperature s1)))
    (is (= 5 (child-strongest s2 child-id b-id)))
    (is (zero? (nvm/temperature s2)))))

(deftest nested-vm-map-list-hops-over-live-pcons-source
  (doseq [depth [1 5 10 15]]
    (let [{:keys [state child-id out-id]} (run-nested-map-chain
                                           double-value
                                           [1 1 1 1 1]
                                           depth)
          {:keys [values]} (read-list-prefix state child-id out-id 6)]
      (is (= (vec (repeat 5 (long (Math/pow 2 depth))))
             values)
          (str "mapper chain depth " depth)))))

(deftest nested-vm-bidirectional-identity-hops-output-car-to-source
  (doseq [depth [2 5]]
    (let [{:keys [state child-id head-id out-id]} (run-nested-map-chain
                                                   bidirectional-id
                                                   [1]
                                                   depth
                                                   false
                                                   value/nothing)
          out-head-id (id child-id :output-write :head)
          t (target child-id)
          state* (-> state
                     (nvm/apply-effects
                      [(nvm/declare-cell t out-head-id)
                       (nvm/install-topology t
                                             [child-id :output-write :car]
                                             (obj/p:car out-head-id out-id))
                       (nvm/tell t out-head-id 9)])
                     (nvm/run-until-cold 200000))
          {:keys [values]} (read-list-prefix state* child-id out-id 1)]
      (is (= 9 (child-strongest state* child-id head-id))
          (str "depth " depth))
      (is (= [9] values) (str "depth " depth)))))

(deftest nested-vm-bidirectional-double-hop-output-car-to-source
  (let [{:keys [state child-id head-id out-id]} (run-nested-map-chain
                                                 bidirectional-double-value
                                                 [1]
                                                 5
                                                 false
                                                 value/nothing)
        out-head-id (id child-id :output-write :double-head)
        t (target child-id)
        state* (-> state
                   (nvm/apply-effects
                    [(nvm/declare-cell t out-head-id)
                     (nvm/install-topology t
                                           [child-id :output-write :double-car]
                                           (obj/p:car out-head-id out-id))
                     (nvm/tell t out-head-id 32)])
                   (nvm/run-until-cold 200000))
        {:keys [values]} (read-list-prefix state* child-id out-id 1)]
    (is (= 1 (child-strongest state* child-id head-id)))
    (is (= [32] values))))

(deftest nested-vm-late-cdr-attachment-wakes-map-chain
  (let [{:keys [state child-id out-id]} (run-nested-map-chain
                                         double-value
                                         [1]
                                         3)
        t (target child-id)
        late-head-id (id child-id :late-tail :head)
        late-terminal-id (id child-id :late-tail :terminal)
        late-coll-id (id child-id :late-tail :coll)
        source-tail-id (id child-id :source :terminal)
        attach-prop-id (id child-id :late-tail :attach)
        state* (-> state
                   (nvm/apply-effects
                    [(nvm/declare-cell t late-head-id)
                     (nvm/declare-cell t late-terminal-id)
                     (nvm/declare-cell t late-coll-id)
                     (nvm/install-topology t
                                           [child-id :late-tail :cons]
                                           (obj/p:cons late-head-id
                                                       late-terminal-id
                                                       late-coll-id))
                     (nvm/tell t late-head-id 7)
                     (nvm/declare-prop t
                                       attach-prop-id
                                       [late-coll-id]
                                       [source-tail-id]
                                       (ngur/unary-prop identity))])
                   (nvm/run-until-cold 300000))
        {:keys [values]} (read-list-prefix state* child-id out-id 3)]
    (is (= [8 56] values))))
