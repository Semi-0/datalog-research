(ns propagators.network-vm-flat-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
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

(def double-value
  (fgur/recursive-closure
   'double-value
   (fn [{:keys [stable-id]} [value-id] out-id]
     [(fvm/declare-prop (stable-id :double)
                        [value-id]
                        [out-id]
                        (fgur/unary-prop #(* 2 %)))])))

(def bidirectional-id
  (fgur/recursive-closure
   'bidirectional-id
   (fn [{:keys [stable-id]} [value-id] out-id]
     [(fvm/declare-prop (stable-id :id-forward)
                        [value-id]
                        [out-id]
                        (fgur/unary-prop identity))
      (fvm/declare-prop (stable-id :id-backward)
                        [out-id]
                        [value-id]
                        (fgur/unary-prop identity))])))

(def bidirectional-double-value
  (fgur/recursive-closure
   'bidirectional-double-value
   (fn [{:keys [stable-id]} [value-id] out-id]
     [(fvm/declare-prop (stable-id :double-forward)
                        [value-id]
                        [out-id]
                        (fgur/unary-prop #(* 2 %)))
      (fvm/declare-prop (stable-id :double-backward)
                        [out-id]
                        [value-id]
                        (fgur/unary-prop #(/ % 2)))])))

(def unused-acc-list :unused)

(def map-list
  (fgur/recursive-closure
   'map-list
   (fn [{:keys [app-key stable-id apply]
         recur-fn :recur
         when-fn :when} [list-id mapper-id acc-id] out-id]
     (let [head-id (stable-id :head)
           rest-id (stable-id :rest)
           mapped-id (stable-id :mapped)
           mapped-rest-id (stable-id :mapped-rest)]
       (into
        [(fvm/declare-cell head-id)
         (fvm/declare-cell rest-id)
         (fvm/declare-cell mapped-id)
         (fvm/declare-cell mapped-rest-id)
         (fvm/install-topology [app-key :car]
                               (obj/p:car head-id list-id))
         (fvm/install-topology [app-key :cdr]
                               (obj/p:cdr rest-id list-id))
         (apply mapper-id [head-id] mapped-id)
         (fvm/install-topology [app-key :cons]
                               (obj/p:cons mapped-id mapped-rest-id out-id))]
        [(when-fn [app-key :when-rest]
                  rest-id
                  (fn []
                    [(recur-fn [rest-id mapper-id acc-id] mapped-rest-id)]))])))))

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
  (let [{:keys [state]} (run-flat-map-chain double-value [1 1 1 1 1] 5)
        counts (topology-counts state)
        state* {:net (core/run-tasks [] (:net state))}
        counts* (topology-counts state*)]
    (is (= counts counts*))))

(deftest flat-effects-gur-does-not-directly-merge-cell-entries
  (is (not (re-find #"merge-cell-entry"
                    (slurp "propagators/network_vm/flat/gur.clj")))))
