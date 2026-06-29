(ns propagators.reducer-cell-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.datastructures.compound-object :as obj]
            [propagators.install :as i]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.gur.flat :as fvm]
            [propagators.gur.flat :as fgur]
            [propagators.propagator :as prop]))

(defn- node-id
  [& parts]
  (fvm/stable-node-id (into [:reducer-cell-test] parts)))

(defn- strongest
  [n cell-id]
  (net/network-cell-strongest n cell-id))

(defn- topology
  [install-key cell-ids installer]
  (i/installer-effects (fvm/vm-net) install-key cell-ids installer))

(defn- projection-net
  [tag project & {:keys [explicit-meta?]}]
  (let [slots-id (node-id tag :slots)
        out-id (node-id tag :out)
        dep-id (node-id tag :dependence)
        epoch-id (node-id tag :epoch)
        output-ids (cond-> [out-id]
                     explicit-meta? (conj dep-id epoch-id))
        n0 (cond-> net/empty-net
             true (nb/install-cell slots-id)
             true (nb/install-cell out-id)
             explicit-meta? (nb/install-cell dep-id)
             explicit-meta? (nb/install-cell epoch-id))
        [_ n1] ((prop/construct-propagator
                 (fn [_inputs _outputs network]
                   (let [slots (net/network-cell-strongest network slots-id)
                         {:keys [out dependence epoch]} (project slots)]
                     (cond-> [(message out-id out)]
                       explicit-meta? (conj (message dep-id dependence)
                                            (message epoch-id epoch)))))
                 [slots-id]
                 output-ids)
                n0)]
    (cond-> n1
      true (net/assoc-net-dict-entry :slots slots-id)
      true (net/assoc-net-dict-entry :out out-id)
      explicit-meta? (net/assoc-net-dict-entry :dependence dep-id)
      explicit-meta? (net/assoc-net-dict-entry :epoch epoch-id))))

(defn- slot-merge-net
  [tag]
  (let [content-id (node-id tag :content)
        update-id (node-id tag :update)
        out-id (node-id tag :merge-out)
        n0 (-> net/empty-net
               (nb/install-cell content-id)
               (nb/install-cell update-id)
               (nb/install-cell out-id))
        [_ n1] ((prop/construct-propagator
                 (fn [_inputs _outputs network]
                   (let [content (net/network-cell-strongest network content-id)
                         update (net/network-cell-strongest network update-id)
                         content* (if (value/nothing? content) {} content)
                         update* (if (value/nothing? update) {} update)
                         merged (reduce-kv
                                 (fn [slots k v]
                                   (if (contains? slots k)
                                     (if (= (get slots k) v)
                                       slots
                                       (reduced value/contradiction))
                                     (assoc slots k v)))
                                 content*
                                 update*)]
                     [(message out-id merged)]))
                 [content-id update-id]
                 [out-id])
                n0)]
    (-> n1
        (net/assoc-net-dict-entry :content content-id)
        (net/assoc-net-dict-entry :update update-id)
        (net/assoc-net-dict-entry :out out-id))))

(def default-merge-net
  (slot-merge-net :default-merge))

(def map-reducer-net
  (projection-net :map (fn [slots] {:out (or slots {})})))

(def meta-reducer-net
  (projection-net :meta
                  (fn [slots]
                    {:out (count slots)
                     :dependence #{[:selected (-> slots keys sort first)]}
                     :epoch [:count (count slots)]})
                  :explicit-meta? true))

(def lexical-reducer-net
  (projection-net
   :lexical
   (fn [slots]
     (let [best (->> slots
                     (filter (fn [[k _]]
                               (and (vector? k)
                                    (= :x (first k)))))
                     (map val)
                     (sort-by :distance)
                     first)]
       {:out (:value best)
        :dependence #{[:scope (:scope best)]}
        :epoch [:lexical (some-> best :scope)]}))
   :explicit-meta? true))

(def collect-reducer-net
  (projection-net
   :collect
   (fn [slots]
     {:out (mapv val (sort-by (comp pr-str key) slots))})))

(defn- apply-effects
  [effects]
  (let [[tasks n] (core/eval-activation-result effects (fvm/vm-net))]
    (core/run-tasks tasks n)))

(defn- source-list-effects
  [run-key values]
  (let [values (vec values)
        len (count values)
        heads (mapv #(node-id run-key :head %) (range len))
        colls (mapv #(node-id run-key :coll %) (range len))
        terminal-id (node-id run-key :terminal)]
    {:root-id (first colls)
     :effects (vec
               (concat
                (mapv fvm/declare-cell (concat heads colls [terminal-id]))
                (mapv (fn [i]
                        (topology
                         [run-key :cons i]
                         [(heads i)
                          (if (= i (dec len))
                            terminal-id
                            (colls (inc i)))
                          (colls i)]
                         (obj/p:cons
                          (heads i)
                          (if (= i (dec len))
                            terminal-id
                            (colls (inc i)))
                          (colls i))))
                      (range len))
                (map-indexed (fn [i v]
                               (message (heads i) v))
                             values)
                [(message terminal-id value/nothing)]))}))

(def collect-list-values
  (fgur/recursive-declaration
   'collect-list-values
   (fn [ctx [xs-id reducer-id] out-id]
     (-> ctx
         (i/$ {:xs xs-id
               :reducer reducer-id
               :out out-id})
         (i/car :head :xs)
         (i/cdr :rest :xs)
        (i/reducer-slot :collect
                         default-merge-net
                         collect-reducer-net
                         [:head (:scope ctx)]
                         :head
                         :reducer)
         (i/tell :out true)
         (i/when :rest
           (i/recur [:rest :reducer] :out))))))

(deftest empty-reducer-returns-reduced-value
  (let [v (merge/strongest-value (reducer/reducer-cell :r
                                                       default-merge-net
                                                       map-reducer-net)
                                net/empty-net)]
    (is (reducer/reduced-value? v))
    (is (= {} (reducer/reduced-result v)))
    (is (= #{[:reducer/id :r]} (reducer/reduced-dependence v)))))

(deftest reducer-cell-retains-explicit-merge-and-strongest-nets
  (let [content (reducer/reducer-cell :r default-merge-net map-reducer-net)]
    (is (= default-merge-net (reducer/merge-net content)))
    (is (= map-reducer-net (reducer/strongest-net content)))
    (is (not (contains? content :reducer/net)))))

(deftest slot-updates-merge-into-one-reducer-cell
  (let [a (reducer/reducer-slot-update :r default-merge-net map-reducer-net :a 1)
        b (reducer/reducer-slot-update :r default-merge-net map-reducer-net :b 2)
        merged (merge/cell-merge a b net/empty-net)]
    (is (reducer/reducer-cell? merged))
    (is (= {:a 1 :b 2} (reducer/reducer-slots merged)))
    (is (= {:a 1 :b 2}
           (-> merged
               (merge/strongest-value net/empty-net)
               reducer/reduced-result)))))

(deftest repeated-slot-update-is-idempotent
  (let [update (reducer/reducer-slot-update :r default-merge-net map-reducer-net :a 1)
        merged (merge/cell-merge update update net/empty-net)]
    (is (= update merged))))

(deftest conflicting-slot-values-contradict
  (let [a (reducer/reducer-slot-update :r default-merge-net map-reducer-net :a 1)
        b (reducer/reducer-slot-update :r default-merge-net map-reducer-net :a 2)]
    (is (= value/contradiction
           (merge/cell-merge a b net/empty-net)))))

(deftest different-reducer-ids-contradict
  (let [a (reducer/reducer-slot-update :r1 default-merge-net map-reducer-net :a 1)
        b (reducer/reducer-slot-update :r2 default-merge-net map-reducer-net :b 2)]
    (is (= value/contradiction
           (merge/cell-merge a b net/empty-net)))))

(deftest strongest-uses-explicit-dependence-and-epoch-when-present
  (let [content (reducer/reducer-cell :r default-merge-net meta-reducer-net {:a 1 :b 2})
        reduced (merge/strongest-value content net/empty-net)]
    (is (= 2 (reducer/reduced-result reduced)))
    (is (= #{[:selected :a]} (reducer/reduced-dependence reduced)))
    (is (= [:count 2] (reducer/reduced-epoch reduced)))))

(deftest changing-slots-changes-default-epoch
  (let [a (merge/strongest-value (reducer/reducer-cell :r
                                                       default-merge-net
                                                       map-reducer-net
                                                       {:a 1})
                                net/empty-net)
        b (merge/strongest-value (reducer/reducer-cell :r
                                                       default-merge-net
                                                       map-reducer-net
                                                       {:a 2})
                                net/empty-net)]
    (is (not= (reducer/reduced-epoch a)
              (reducer/reduced-epoch b)))))

(deftest reduced-result-primitive-projects-ordinary-result
  (let [value-id (node-id :primitive :value)
        reducer-id (node-id :primitive :reducer)
        out-id (node-id :primitive :out)
        n0 (-> net/empty-net
               (nb/install-cell value-id)
               (nb/install-cell reducer-id)
               (nb/install-cell out-id))
        [_ n1] ((reducer/p:reducer-slot :r
                                        default-merge-net
                                        map-reducer-net
                                        :a
                                        value-id
                                        reducer-id)
                n0)
        [_ n2] ((reducer/p:reduced-result reducer-id out-id)
                n1)
        [tasks n3] (core/eval-cells [(message value-id 7)] n2)
        n4 (core/run-tasks tasks n3)]
    (is (= {:a 7} (strongest n4 out-id)))))

(deftest install-helpers-emit-slots-and-project-result
  (let [n (-> (i/context net/empty-net [:install-reducer])
              (i/tell :value 9)
              (i/reducer-slot :r default-merge-net map-reducer-net :a :value :reducer)
              (i/reduced-result :reducer :out)
              (i/run))
        out-id (i/cell-id (i/context n [:install-reducer]) :out)]
    (is (= {:a 9} (strongest n out-id)))))

(deftest lexical-candidate-reducer-selects-child-without-contradiction
  (let [parent {:scope :parent :distance 1 :value 10}
        child {:scope :child :distance 0 :value 20}
        content (-> (reducer/reducer-slot-update :lex
                                                 default-merge-net
                                                 lexical-reducer-net
                                                 [:x :parent]
                                                 parent)
                    (merge/cell-merge
                     (reducer/reducer-slot-update :lex
                                                  default-merge-net
                                                  lexical-reducer-net
                                                  [:x :child]
                                                  child)
                     net/empty-net))
        reduced (merge/strongest-value content net/empty-net)]
    (is (reducer/reduced-value? reduced))
    (is (= 20 (reducer/reduced-result reduced)))
    (is (= #{[:scope :child]} (reducer/reduced-dependence reduced)))))

(deftest flat-gur-traversal-can-emit-reducer-slots
  (let [run-key [:flat-gur :reducer-collect]
        closure-id (node-id run-key :closure)
        reducer-id (node-id run-key :reducer)
        out-id (node-id run-key :out)
        {:keys [root-id effects]} (source-list-effects run-key [1 2 3])
        n (apply-effects
           (vec (concat [(fvm/declare-cell closure-id)
                         (fvm/declare-cell reducer-id)
                         (fvm/declare-cell out-id)
                         (message closure-id collect-list-values)]
                        effects
                        [(fgur/apply-closure-effect closure-id
                                                    [root-id reducer-id]
                                                    out-id)])))
        reduced (strongest n reducer-id)]
    (is (reducer/reduced-value? reduced))
    (is (= 3 (count (reducer/reduced-result reduced))))
    (is (= #{1 2 3} (set (reducer/reduced-result reduced))))))

(deftest reducer-cell-namespace-does-not-call-merge-cell-entry
  (is (not (re-find #"merge-cell-entry"
                    (slurp "propagators/datastructures/reducer_cell.clj")))))
