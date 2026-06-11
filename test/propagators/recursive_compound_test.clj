(ns propagators.recursive-compound-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.compile :as compile]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.named-network :as named]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.recursive :as recursive]
            [propagators.stdlib.prop :as stdlib-prop]))

(defn- strongest
  [n id]
  (net/network-cell-strongest n id))

(defn- seed-output
  [n out-id v]
  (nb/seed-cell n out-id v))

(defn- run-installed
  [n prop-ids]
  (nb/run-propagators n prop-ids))

(defn- eval-dsl
  [n sym->value expr]
  (compile/eval-layered n (compile/default-installers) sym->value expr))

(defn- eval-and-run
  [n sym->value expr]
  (let [{:keys [net props] :as ctx} (eval-dsl n sym->value expr)]
    (assoc ctx :net (run-installed net props))))

(defn- fib-step
  [branch-builds]
  (fn [{:keys [self-id input-ids output-ids network]}]
    (let [[n-id] input-ids
          [out-id] output-ids
          n-value (strongest network n-id)]
      (cond
        (value/unusable? n-value)
        network

        (not (and (integer? n-value) (not (neg? n-value))))
        (seed-output network out-id value/contradiction)

        :else
        (let [base? (<= n-value 1)
              {:keys [net]} (eval-and-run
                             network
                             {'n n-id
                              'out out-id
                              'base-value base?}
                             '(do
                                (let-cell [base?]
                                  (seed base? base-value)
                                  (prop/switch n base? out))))
              n2 net]
          (if-not (value/nothing? (strongest n2 out-id))
            n2
            (do
              (swap! branch-builds inc)
              (let [ctx (eval-and-run
                         n2
                         {'self self-id
                          'out out-id
                          'n-minus-1 (dec n-value)
                          'n-minus-2 (- n-value 2)}
                         '(do
                            (let-cell [n-1 n-2 fib-1 fib-2]
                              (seed n-1 n-minus-1)
                              (seed n-2 n-minus-2)
                              (recursive/p:recursive-compound self n-1 fib-1)
                              (recursive/p:recursive-compound self n-2 fib-2))))
                    n6 (:net ctx)
                    fib-1-id (compile/cell-ref ctx 'fib-1)
                    fib-2-id (compile/cell-ref ctx 'fib-2)
                    fib-1 (strongest n6 fib-1-id)
                    fib-2 (strongest n6 fib-2-id)]
                (cond
                  (or (value/contradiction? fib-1)
                      (value/contradiction? fib-2))
                  (seed-output n6 out-id value/contradiction)

                  (or (value/unusable? fib-1)
                      (value/unusable? fib-2))
                  n6

                  :else
                  (:net (eval-and-run
                         n6
                         {'fib-1 fib-1-id
                          'fib-2 fib-2-id
                          'out out-id}
                         '(prop/+ fib-1 fib-2 out))))))))))))

(defn- fib-recursive-closure
  ([]
   (fib-recursive-closure {}))
  ([opts]
   (let [branch-builds (or (:branch-builds opts) (atom 0))]
     (recursive/recursive-closure (fib-step branch-builds)
                                  (select-keys opts [:max-depth])))))

(defn- run-fib
  ([n-value]
   (run-fib n-value {}))
  ([n-value opts]
   (let [closure-value (fib-recursive-closure opts)
         ctx (eval-and-run
              net/empty-net
              {'closure-value closure-value
               'n-value n-value}
              '(do
                 (let-cell [fib n out]
                   (seed fib closure-value)
                   (seed n n-value)
                   (recursive/p:recursive-compound fib n out))))
         out-id (compile/cell-ref ctx 'out)]
     {:net (:net ctx)
      :out-id out-id
      :value (strongest (:net ctx) out-id)})))

(defn- run-fib-unusable-input
  []
  (let [closure-value (fib-recursive-closure)
        ctx (eval-and-run
             net/empty-net
             {'closure-value closure-value}
             '(do
                (let-cell [fib n out]
                  (seed fib closure-value)
                  (recursive/p:recursive-compound fib n out))))
        out-id (compile/cell-ref ctx 'out)]
    {:net (:net ctx)
     :out-id out-id
     :value (strongest (:net ctx) out-id)}))

(defn- wrapper-closure
  [fib-closure-value]
  (closure/closure
   (fn [_closure-net input-ids output-ids network]
     (let [[n-id] input-ids
           [out-id] output-ids
           ctx (eval-and-run
                network
                {'fib-value fib-closure-value
                 'n n-id
                 'out out-id}
                '(do
                   (let-cell [fib]
                     (seed fib fib-value)
                     (recursive/p:recursive-compound fib n out))))]
       (:net ctx)))
   net/empty-net))

(defn- named-frame-value
  [frame-net frame slot]
  (when-let [id (net/network-dict-entry frame-net
                                        (recursive/frame-dict-key frame slot))]
    (net/network-cell-strongest frame-net id)))

(defn- recursive-call-form
  [mode arg-sym out-sym]
  (if (= mode :self-refining)
    (list 'recursive/p:self-refining-recursive-compound 'self arg-sym out-sym)
    (list 'recursive/p:accumulating-recursive-compound 'self arg-sym 'acc out-sym)))

(defn- fib-frame-step
  [mode branch-builds]
  (fn [{:keys [self-id acc-id input-ids output-ids network]}]
    (let [[n-id] input-ids
          [out-id] output-ids
          n-value (strongest network n-id)
          frame [:fib n-value]]
      (cond
        (value/unusable? n-value)
        {:network network}

        (not (and (integer? n-value) (not (neg? n-value))))
        {:network (seed-output network out-id value/contradiction)
         :frame-fragment
         (recursive/frame-fragment frame {:input n-value
                                          [:status :contradiction] true})}

        (<= n-value 1)
        (let [ctx (eval-dsl
                   network
                   {'n n-id
                    'out out-id}
                   '(do
                      (let-cell [base?]
                        (seed base? true)
                        (prop/switch n base? out))))
              n2 (run-installed (:net ctx) (:props ctx))]
          {:network n2
           :frame-fragment
           (recursive/frame-fragment frame {:input n-value
                                            :output n-value
                                            [:status :done] true})})

        :else
        (do
          (swap! branch-builds inc)
          (let [ctx (eval-and-run
                     network
                     (cond-> {'self self-id
                              'out out-id
                              'n-minus-1 (dec n-value)
                              'n-minus-2 (- n-value 2)}
                       acc-id (assoc 'acc acc-id))
                     (list 'do
                           (list 'let-cell
                                 '[n-1 n-2 fib-1 fib-2]
                                 '(seed n-1 n-minus-1)
                                 '(seed n-2 n-minus-2)
                                 (recursive-call-form mode 'n-1 'fib-1)
                                 (recursive-call-form mode 'n-2 'fib-2))))
                n6 (:net ctx)
                fib-1-id (compile/cell-ref ctx 'fib-1)
                fib-2-id (compile/cell-ref ctx 'fib-2)
                fib-1 (strongest n6 fib-1-id)
                fib-2 (strongest n6 fib-2-id)
                fragment (recursive/frame-fragment
                          frame
                          {:input n-value
                           [:child 0] [:fib (dec n-value)]
                           [:child 1] [:fib (- n-value 2)]
                           :combine :+
                           [:status :expanded] true})]
            (cond
              (or (value/contradiction? fib-1)
                  (value/contradiction? fib-2))
              {:network (seed-output n6 out-id value/contradiction)
               :frame-fragment fragment}

              (or (value/unusable? fib-1)
                  (value/unusable? fib-2))
              {:network n6
               :frame-fragment fragment}

              :else
              {:network (:net (eval-and-run
                               n6
                               {'fib-1 fib-1-id
                                'fib-2 fib-2-id
                                'out out-id}
                               '(prop/+ fib-1 fib-2 out)))
               :frame-fragment
               (named/join
                fragment
                (recursive/frame-fragment frame {:output (+ fib-1 fib-2)
                                                  [:status :done] true}))})))))))

(defn- fib-frame-closure
  [mode opts]
  (let [branch-builds (or (:branch-builds opts) (atom 0))]
    (recursive/recursive-closure (fib-frame-step mode branch-builds)
                                 (select-keys opts [:max-depth]))))

(defn- run-self-refining-fib
  [n-value]
  (let [closure-value (fib-frame-closure :self-refining {})
        ctx (eval-and-run
             net/empty-net
             {'closure-value closure-value
              'n-value n-value}
             '(do
                (let-cell [fib n out]
                  (seed fib closure-value)
                  (seed n n-value)
                  (recursive/p:self-refining-recursive-compound fib n out))))
        fib-id (compile/cell-ref ctx 'fib)
        out-id (compile/cell-ref ctx 'out)
        closure-value (strongest (:net ctx) fib-id)]
    {:net (:net ctx)
     :fib-id fib-id
     :out-id out-id
     :closure closure-value
     :frame-net (closure/closure-net closure-value)
     :value (strongest (:net ctx) out-id)}))

(defn- run-accumulating-fib
  [n-value]
  (let [closure-value (fib-frame-closure :accumulating {})
        ctx (eval-and-run
             net/empty-net
             {'closure-value closure-value
              'n-value n-value}
             '(do
                (let-cell [fib acc n out]
                  (seed fib closure-value)
                  (seed n n-value)
                  (recursive/p:accumulating-recursive-compound fib n acc out))))
        acc-id (compile/cell-ref ctx 'acc)
        out-id (compile/cell-ref ctx 'out)]
    {:net (:net ctx)
     :acc-id acc-id
     :out-id out-id
     :frame-net (strongest (:net ctx) acc-id)
     :value (strongest (:net ctx) out-id)}))

(defn- run-self-refining-map-fib
  [xs]
  (let [closure-value (fib-frame-closure :self-refining {})
        closure-id (ids/new-node-id)
        source-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure-value closure-value)
               (nb/install-cell source-id xs xs)
               (nb/install-cell out-id))
        [prop-id n1] ((obj/p:map-slots-with-recursive-closure
                       closure-id
                       source-id
                       out-id)
                      n0)
        n2 (nb/run-propagators n1 [prop-id])
        closure-value (strongest n2 closure-id)]
    {:net n2
     :out-id out-id
     :value (strongest n2 out-id)
     :frame-net (closure/closure-net closure-value)}))

(defn- run-accumulating-map-fib
  [xs]
  (let [closure-value (fib-frame-closure :accumulating {})
        closure-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        source-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure-value closure-value)
               (nb/install-cell acc-id)
               (nb/install-cell source-id xs xs)
               (nb/install-cell out-id))
        [prop-id n1] ((obj/p:map-slots-with-recursive-accumulator
                       closure-id
                       acc-id
                       source-id
                       out-id)
                      n0)
        n2 (nb/run-propagators n1 [prop-id])]
    {:net n2
     :acc-id acc-id
     :out-id out-id
     :value (strongest n2 out-id)
     :frame-net (strongest n2 acc-id)}))

(defn- run-declared-self-refining-map-fib
  [xs]
  (let [closure-value (fib-frame-closure :self-refining {})
        closure-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure-value closure-value)
               (nb/install-cell out-id))
        {:keys [net prop-ids leaf-count]}
        (obj/install-declared-nested-recursive-map-with-closure
         n0
         closure-id
         xs
         out-id)
        n2 (nb/run-propagators net prop-ids)
        closure-value (strongest n2 closure-id)]
    {:net n2
     :out-id out-id
     :leaf-count leaf-count
     :value (strongest n2 out-id)
     :frame-net (closure/closure-net closure-value)}))

(defn- run-declared-accumulating-map-fib
  [xs]
  (let [closure-value (fib-frame-closure :accumulating {})
        closure-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure-value closure-value)
               (nb/install-cell acc-id)
               (nb/install-cell out-id))
        {:keys [net prop-ids leaf-count]}
        (obj/install-declared-nested-recursive-map-with-accumulator
         n0
         closure-id
         acc-id
         xs
         out-id)
        n2 (nb/run-propagators net prop-ids)]
    {:net n2
     :acc-id acc-id
     :out-id out-id
     :leaf-count leaf-count
     :value (strongest n2 out-id)
     :frame-net (strongest n2 acc-id)}))

(defn- named-equivalent?
  [a b]
  (and (= true (named/named-network->= a b))
       (= true (named/named-network->= b a))))

(defn- named-idempotent?
  [n]
  (named-equivalent? (named/join n n) n))

(defn- compound->data
  [v]
  (let [source-net (obj/compound-object v)]
    (if (value/contradiction? source-net)
      v
      (if-let [count-value (obj/slot-value source-net :count)]
        (mapv #(compound->data (obj/slot-value source-net %))
              (range count-value))
        (into {}
              (map (fn [slot-key]
                     [slot-key (compound->data
                                (obj/slot-value source-net slot-key))])
                   (sort-by pr-str (obj/public-slot-keys source-net))))))))

(defn- reduce-compound-leaves
  [f init v]
  (let [source-net (obj/compound-object v)]
    (if (value/contradiction? source-net)
      (f init v)
      (if-let [count-value (obj/slot-value source-net :count)]
        (reduce (fn [acc slot-key]
                  (reduce-compound-leaves
                   f
                   acc
                   (obj/slot-value source-net slot-key)))
                init
                (range count-value))
        (reduce (fn [acc slot-key]
                  (reduce-compound-leaves
                   f
                   acc
                   (obj/slot-value source-net slot-key)))
                init
                (sort-by pr-str (obj/public-slot-keys source-net)))))))

(def ^:private expansion-prop-ids-key [:experiment :prop-ids])
(def ^:private expansion-out-id-key [:experiment :out-id])
(def ^:private expansion-acc-id-key [:experiment :acc-id])
(def ^:private expansion-leaf-count-key [:experiment :leaf-count])
(def ^:private expansion-child-prop-id-key [:experiment :child-prop-id])
(def ^:private expansion-child-out-id-key [:experiment :child-out-id])

(defn- slot-path-value
  [v path]
  (reduce obj/slot-value v path))

(defn- assert-nested-fib-map
  [value]
  (is (= 0 (slot-path-value value [:left 0])))
  (is (= 1 (slot-path-value value [:left 1])))
  (is (= 1 (slot-path-value value [:left 2])))
  (is (= 3 (slot-path-value value [:left :count])))
  (is (= 2 (slot-path-value value [:right :a])))
  (is (= 3 (slot-path-value value [:right :b 0])))
  (is (= 5 (slot-path-value value [:right :b 1])))
  (is (= 2 (slot-path-value value [:right :b :count])))
  (is (= 0 (slot-path-value value [:right :empty :count]))))

(defn- compound-net
  [v]
  (let [source-net (obj/compound-object v)]
    (when-not (value/contradiction? source-net)
      source-net)))

(defn- compound-slot-keys
  [source-net]
  (if-let [count-value (obj/slot-value source-net :count)]
    (vec (range count-value))
    (->> (obj/public-slot-keys source-net)
         (remove #{:count})
         (sort-by pr-str)
         vec)))

(defn- install-source-object
  [network source-id source]
  (cond
    (vector? source)
    (let [count-id (ids/new-node-id)
          n0 (nb/install-cell network count-id (count source) (count source))
          [count-prop n1] ((obj/p:legacy-slot :count count-id source-id) n0)]
      (reduce-kv
       (fn [{:keys [net prop-ids leaf-ids]} slot-key child-value]
         (let [child-id (ids/new-node-id)
               n2 (nb/install-cell net child-id)
               [slot-prop n3] ((obj/p:legacy-slot slot-key child-id source-id) n2)
               {n4 :net child-props :prop-ids child-leaves :leaf-ids}
               (install-source-object n3 child-id child-value)]
           {:net n4
            :prop-ids (into (conj prop-ids slot-prop) child-props)
            :leaf-ids (into leaf-ids child-leaves)}))
       {:net n1
        :prop-ids [count-prop]
        :leaf-ids []}
       source))

    (map? source)
    (if (empty? source)
      {:net (nb/seed-cell network source-id (obj/empty-compound-object))
       :prop-ids []
       :leaf-ids []}
      (reduce
       (fn [{:keys [net prop-ids leaf-ids]} [slot-key child-value]]
         (let [child-id (ids/new-node-id)
               n0 (nb/install-cell net child-id)
               [slot-prop n1] ((obj/p:legacy-slot slot-key child-id source-id) n0)
               {n2 :net child-props :prop-ids child-leaves :leaf-ids}
               (install-source-object n1 child-id child-value)]
           {:net n2
            :prop-ids (into (conj prop-ids slot-prop) child-props)
            :leaf-ids (into leaf-ids child-leaves)}))
       {:net network
        :prop-ids []
        :leaf-ids []}
       (sort-by (comp pr-str key) source)))

    :else
    {:net (nb/seed-cell network source-id source)
     :prop-ids []
     :leaf-ids [source-id]}))

(defn- install-prop-sum
  [network input-ids out-id]
  (cond
    (empty? input-ids)
    {:net (seed-output network out-id 0)
     :prop-ids []}

    (= 1 (count input-ids))
    (let [[prop-id n1] ((stdlib-prop/id (first input-ids) out-id) network)]
      {:net n1
       :prop-ids [prop-id]})

    :else
    (let [[first-id & rest-ids] input-ids]
      (loop [n network
             acc-id first-id
             remaining rest-ids
             prop-ids []]
        (if-let [next-input-id (first remaining)]
          (let [sum-id (ids/new-node-id)
                n0 (nb/install-cell n sum-id)
                [prop-id n1] ((stdlib-prop/+ acc-id next-input-id sum-id) n0)]
            (recur n1
                   sum-id
                   (next remaining)
                   (conj prop-ids prop-id)))
          (let [[prop-id n1] ((stdlib-prop/id acc-id out-id) n)]
            {:net n1
             :prop-ids (conj prop-ids prop-id)}))))))

(defn- direct-nested-sum-step
  []
  (fn [{:keys [self-id input-ids output-ids network]}]
    (let [[source-id] input-ids
          [out-id] output-ids
          source-value (strongest network source-id)]
      (cond
        (value/unusable? source-value)
        {:network network}

        (number? source-value)
        (let [{:keys [net prop-ids]} (install-prop-sum network [source-id] out-id)]
          {:network (run-installed net prop-ids)})

        (compound-net source-value)
        (let [source-net (compound-net source-value)
              slot-keys (compound-slot-keys source-net)
              {:keys [net child-out-ids prop-ids]}
              (reduce (fn [{:keys [net child-out-ids prop-ids]} slot-key]
                        (let [child-in-id (ids/new-node-id)
                              child-out-id (ids/new-node-id)
                              n0 (-> net
                                     (nb/install-cell child-in-id)
                                     (nb/install-cell child-out-id))
                              [slot-prop n1] ((obj/p:legacy-slot slot-key
                                                          child-in-id
                                                          source-id)
                                              n0)
                              [prop-id n2] ((recursive/p:recursive-compound
                                             self-id
                                             child-in-id
                                             child-out-id)
                                            n1)]
                          {:net n2
                           :child-out-ids (conj child-out-ids child-out-id)
                           :prop-ids (conj prop-ids slot-prop prop-id)}))
                      {:net network
                       :child-out-ids []
                       :prop-ids []}
                      slot-keys)]
          (let [after-children (run-installed net prop-ids)
                child-values (mapv #(strongest after-children %) child-out-ids)]
            (cond
              (empty? slot-keys)
              {:network (seed-output after-children out-id 0)}

              (some value/contradiction? child-values)
              {:network (seed-output after-children out-id value/contradiction)}

              (some value/unusable? child-values)
              {:network after-children}

              :else
              (let [{:keys [net prop-ids]}
                    (install-prop-sum after-children child-out-ids out-id)]
                {:network (run-installed net prop-ids)}))))

        :else
        {:network (seed-output network out-id value/contradiction)}))))

(defn- run-direct-nested-sum
  [source]
  (let [closure-value (recursive/recursive-closure (direct-nested-sum-step))
        closure-id (ids/new-node-id)
        source-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure-value closure-value)
               (nb/install-cell source-id)
               (nb/install-cell out-id))
        {n1 :net source-props :prop-ids}
        (install-source-object n0 source-id source)
        [prop-id n1] ((recursive/p:recursive-compound
                       closure-id
                       source-id
                       out-id)
                      n1)
        n2 (run-installed n1 (conj (vec source-props) prop-id))]
    {:net n2
     :out-id out-id
     :value (strongest n2 out-id)}))

(defn- direct-nested-map-fib-step
  []
  (fn [{:keys [self-id input-ids output-ids network]}]
    (let [[source-id] input-ids
          [out-id] output-ids
          source-value (strongest network source-id)]
      (cond
        (value/unusable? source-value)
        {:network network}

        (number? source-value)
        (let [fib-value (fib-recursive-closure)
              fib-id (ids/new-node-id)
              n0 (nb/install-cell network
                                  fib-id
                                  fib-value
                                  fib-value)
              [prop-id n1] ((recursive/p:recursive-compound
                             fib-id
                             source-id
                             out-id)
                            n0)]
          {:network (run-installed n1 [prop-id])})

        (compound-net source-value)
        (let [source-net (compound-net source-value)
              slot-keys (compound-slot-keys source-net)
              count-value (obj/slot-value source-net :count)
              {:keys [net child-outs prop-ids]}
              (reduce (fn [{:keys [net child-outs prop-ids]} slot-key]
                        (let [child-in-id (ids/new-node-id)
                              child-out-id (ids/new-node-id)
                              n0 (-> net
                                     (nb/install-cell child-in-id)
                                     (nb/install-cell child-out-id))
                              [source-slot-prop n1]
                              ((obj/p:legacy-slot slot-key child-in-id source-id) n0)
                              [child-prop n2]
                              ((recursive/p:recursive-compound
                                self-id
                                child-in-id
                                child-out-id)
                               n1)]
                          {:net n2
                           :child-outs (conj child-outs [slot-key child-out-id])
                           :prop-ids (conj prop-ids source-slot-prop child-prop)}))
                      {:net network
                       :child-outs []
                       :prop-ids []}
                      slot-keys)
              after-children (run-installed net prop-ids)
              child-values (mapv #(strongest after-children (second %)) child-outs)]
          (cond
            (and (empty? slot-keys) (nil? count-value))
            {:network (seed-output after-children
                                   out-id
                                   (obj/empty-compound-object))}

            (some value/contradiction? child-values)
            {:network (seed-output after-children out-id value/contradiction)}

            (some value/unusable? child-values)
            {:network after-children}

            :else
            (let [{n0 :net output-props :prop-ids}
                  (if (nil? count-value)
                    {:net after-children :prop-ids []}
                    (let [count-id (ids/new-node-id)
                          n0 (nb/install-cell after-children
                                              count-id
                                              count-value
                                              count-value)
                          [count-prop n1] ((obj/p:legacy-slot :count count-id out-id)
                                           n0)]
                      {:net n1 :prop-ids [count-prop]}))
                  {n1 :net output-props* :prop-ids}
                  (reduce (fn [{:keys [net prop-ids]} [slot-key child-out-id]]
                            (let [[out-slot-prop n1]
                                  ((obj/p:legacy-slot slot-key child-out-id out-id) net)]
                              {:net n1
                               :prop-ids (conj prop-ids out-slot-prop)}))
                          {:net n0
                           :prop-ids output-props}
                          child-outs)]
              {:network (run-installed n1 output-props*)})))

        :else
        {:network (seed-output network out-id value/contradiction)}))))

(defn- run-direct-nested-map-fib
  [source]
  (let [closure-value (recursive/recursive-closure (direct-nested-map-fib-step))
        closure-id (ids/new-node-id)
        source-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure-value closure-value)
               (nb/install-cell source-id)
               (nb/install-cell out-id))
        {n1 :net source-props :prop-ids}
        (install-source-object n0 source-id source)
        [prop-id n2] ((recursive/p:recursive-compound
                       closure-id
                       source-id
                       out-id)
                      n1)
        n3 (run-installed n2 (conj (vec source-props) prop-id))]
    {:net n3
     :out-id out-id
     :value (strongest n3 out-id)}))

(defn- collect-accessor-leaves
  [network source-id source]
  (cond
    (vector? source)
    (reduce-kv
     (fn [{:keys [net prop-ids leaf-ids]} slot-key child-value]
       (let [child-id (ids/new-node-id)
             n0 (nb/install-cell net child-id)
             [slot-prop n1] ((obj/p:legacy-slot slot-key child-id source-id) n0)
             {n2 :net child-props :prop-ids child-leaves :leaf-ids}
             (collect-accessor-leaves n1 child-id child-value)]
         {:net n2
          :prop-ids (into (conj prop-ids slot-prop) child-props)
          :leaf-ids (into leaf-ids child-leaves)}))
     {:net network
      :prop-ids []
      :leaf-ids []}
     source)

    (map? source)
    (reduce
     (fn [{:keys [net prop-ids leaf-ids]} [slot-key child-value]]
       (let [child-id (ids/new-node-id)
             n0 (nb/install-cell net child-id)
             [slot-prop n1] ((obj/p:legacy-slot slot-key child-id source-id) n0)
             {n2 :net child-props :prop-ids child-leaves :leaf-ids}
             (collect-accessor-leaves n1 child-id child-value)]
         {:net n2
          :prop-ids (into (conj prop-ids slot-prop) child-props)
          :leaf-ids (into leaf-ids child-leaves)}))
     {:net network
      :prop-ids []
      :leaf-ids []}
     (sort-by (comp pr-str key) source))

    :else
    {:net network
     :prop-ids []
     :leaf-ids [source-id]}))

(defn- install-declared-nested-sum
  [network source out-id]
  (let [source-id (ids/new-node-id)
        n0 (nb/install-cell network source-id)
        {n1 :net source-props :prop-ids source-leaves :leaf-ids}
        (install-source-object n0 source-id source)
        {n2 :net accessor-props :prop-ids leaf-ids :leaf-ids}
        (collect-accessor-leaves n1 source-id source)
        leaf-values (mapv #(strongest n2 %) source-leaves)
        invalid? (some (complement number?) leaf-values)]
    (if invalid?
      {:net (seed-output n2 out-id value/contradiction)
       :prop-ids []
       :leaf-count (count leaf-ids)}
      (let [{:keys [net prop-ids]} (install-prop-sum n2 leaf-ids out-id)]
        {:net net
         :prop-ids (into (into (vec source-props) accessor-props) prop-ids)
         :leaf-count (count leaf-ids)}))))

(defn- apply-network-expansion
  [expander]
  (let [expander-id (ids/new-node-id)
        template-id (ids/new-node-id)
        expanded-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell expander-id expander expander)
               (nb/install-cell template-id net/empty-net net/empty-net)
               (nb/install-cell expanded-id))
        [prop-id n1] ((closure/p:apply-network expander-id template-id expanded-id)
                      n0)
        n2 (run-installed n1 [prop-id])]
    (strongest n2 expanded-id)))

(defn- network-expansion-map-expander
  [source]
  (closure/closure
   (fn [_closure-net _input-ids _output-ids declaration-net]
     (let [closure-value (fib-frame-closure :accumulating {})
           closure-id (ids/new-node-id)
           acc-id (ids/new-node-id)
           out-id (ids/new-node-id)
           n0 (-> declaration-net
                  (nb/install-cell closure-id closure-value closure-value)
                  (nb/install-cell acc-id)
                  (nb/install-cell out-id))
           {:keys [net prop-ids leaf-count]}
           (obj/install-declared-nested-recursive-map-with-accumulator
            n0
            closure-id
            acc-id
            source
            out-id)]
       (-> net
           (net/assoc-net-dict-entry expansion-prop-ids-key prop-ids)
           (net/assoc-net-dict-entry expansion-out-id-key out-id)
           (net/assoc-net-dict-entry expansion-acc-id-key acc-id)
           (net/assoc-net-dict-entry expansion-leaf-count-key leaf-count))))
   net/empty-net))

(defn- network-expansion-sum-expander
  [source]
  (closure/closure
   (fn [_closure-net _input-ids _output-ids declaration-net]
     (let [out-id (ids/new-node-id)
           n0 (nb/install-cell declaration-net out-id)
           {:keys [net prop-ids leaf-count]}
           (install-declared-nested-sum n0 source out-id)]
       (-> net
           (net/assoc-net-dict-entry expansion-prop-ids-key prop-ids)
           (net/assoc-net-dict-entry expansion-out-id-key out-id)
           (net/assoc-net-dict-entry expansion-leaf-count-key leaf-count))))
   net/empty-net))

(defn- run-expanded-network
  [expanded]
  (let [prop-ids (net/network-dict-entry expanded expansion-prop-ids-key)
        out-id (net/network-dict-entry expanded expansion-out-id-key)
        after (run-installed expanded prop-ids)]
    {:net after
     :out-id out-id
     :leaf-count (net/network-dict-entry expanded expansion-leaf-count-key)
     :value (strongest after out-id)}))

(defn- when-network-expander
  [frame slot-values]
  (closure/closure
   (fn [_closure-net _input-ids _output-ids declaration-net]
     (named/join declaration-net
                 (recursive/frame-fragment frame slot-values)))
   net/empty-net))

(defn- run-when-network
  [condition expander acc-net]
  (let [condition-id (ids/new-node-id)
        expander-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell condition-id condition condition)
               (nb/install-cell expander-id expander expander)
               (nb/install-cell acc-id acc-net acc-net)
               (nb/install-cell out-id))
        [prop-id n1] ((closure/p:when-network condition-id expander-id acc-id out-id)
                      n0)
        n2 (run-installed n1 [prop-id])]
    {:net n2
     :out-id out-id
     :value (strongest n2 out-id)}))

(defn- recursive-when-frame-expander
  [frame child-enabled?]
  (closure/closure
   (fn [_closure-net _input-ids _output-ids declaration-net]
     (let [base (named/join declaration-net
                            (recursive/frame-fragment
                             frame
                             {:input frame
                              [:status :declared] true}))
           child-condition-id (ids/new-node-id)
           child-expander-id (ids/new-node-id)
           child-acc-id (ids/new-node-id)
           child-out-id (ids/new-node-id)
           child-expander (when-network-expander
                           [:child frame]
                           {:input [:child frame]
                            [:status :declared] true})
           n0 (-> base
                  (nb/install-cell child-condition-id
                                   child-enabled?
                                   child-enabled?)
                  (nb/install-cell child-expander-id
                                   child-expander
                                   child-expander)
                  (nb/install-cell child-acc-id base base)
                  (nb/install-cell child-out-id))
           [child-prop-id n1] ((closure/p:when-network
                                child-condition-id
                                child-expander-id
                                child-acc-id
                                child-out-id)
                               n0)]
       (-> n1
           (net/assoc-net-dict-entry expansion-child-prop-id-key child-prop-id)
           (net/assoc-net-dict-entry expansion-child-out-id-key child-out-id))))
   net/empty-net))

(deftest recursive-fibonacci-values
  (testing "concrete numeric fibonacci values"
    (is (= 0 (:value (run-fib 0))))
    (is (= 1 (:value (run-fib 1))))
    (is (= 1 (:value (run-fib 2))))
    (is (= 5 (:value (run-fib 5))))
    (is (= 55 (:value (run-fib 10))))))

(deftest recursive-compound-wires-through-compile-dsl
  (testing "compile DSL installs and runs recursive compound propagators"
    (is (= 3 (:value (run-fib 4))))))

(deftest compile-dsl-exposes-frame-template-primitives
  (testing "compile DSL can declare recursive frame arithmetic and boolean guards"
    (let [ctx (eval-and-run
               net/empty-net
               {'n-value 3
                'one-value 1}
               '(do
                  (let-cell [n one n-1 base? recur? gated]
                    (seed n n-value)
                    (seed one one-value)
                    (prop/- n one n-1)
                    (prop/<= n one base?)
                    (prop/not base? recur?)
                    (prop/switch n-1 recur? gated))))
          n-1-id (compile/cell-ref ctx 'n-1)
          base-id (compile/cell-ref ctx 'base?)
          recur-id (compile/cell-ref ctx 'recur?)
          gated-id (compile/cell-ref ctx 'gated)]
      (is (= 2 (strongest (:net ctx) n-1-id)))
      (is (= false (strongest (:net ctx) base-id)))
      (is (= true (strongest (:net ctx) recur-id)))
      (is (= 2 (strongest (:net ctx) gated-id)))))

  (testing "switch does not treat Bool4 nothing as enabled"
    (let [ctx (eval-and-run
               net/empty-net
               {'x-value 9}
               '(do
                  (let-cell [x enabled? out]
                    (seed x x-value)
                    (prop/switch x enabled? out))))
          out-id (compile/cell-ref ctx 'out)]
      (is (= value/nothing (strongest (:net ctx) out-id))))))

(deftest apply-network-closure-expands-network-as-data
  (testing "compile DSL can apply a declaration closure to a network-valued cell"
    (let [expander (closure/closure
                    (fn [_closure-net _input-ids _output-ids declaration-net]
                      (named/join
                       declaration-net
                       (recursive/frame-fragment
                        [:frame :demo]
                        {:output 42
                         [:status :declared] true})))
                    net/empty-net)
          ctx (eval-and-run
               net/empty-net
               {'expander-value expander
                'template-value net/empty-net}
               '(do
                  (let-cell [expander template out]
                    (seed expander expander-value)
                    (seed template template-value)
                    (closure/p:apply-network expander template out))))
          out-id (compile/cell-ref ctx 'out)
          expanded (strongest (:net ctx) out-id)]
      (is (= 42 (named-frame-value expanded [:frame :demo] :output)))
      (is (= true (named-frame-value expanded
                                     [:frame :demo]
                                     [:status :declared])))
      (is (named-idempotent? expanded)))))

(deftest when-network-conditionally-expands-network-as-data
  (testing "false condition passes through the accumulator without invoking expander"
    (let [called? (atom false)
          acc (recursive/frame-fragment [:frame :base] {:output 1})
          expander (closure/closure
                    (fn [_closure-net _input-ids _output-ids declaration-net]
                      (reset! called? true)
                      (named/join declaration-net
                                  (recursive/frame-fragment
                                   [:frame :expanded]
                                   {:output 2})))
                    net/empty-net)
          result (run-when-network false expander acc)
          out (:value result)]
      (is (= false @called?))
      (is (= 1 (named-frame-value out [:frame :base] :output)))
      (is (nil? (named-frame-value out [:frame :expanded] :output)))))

  (testing "nothing condition waits without emitting an output"
    (let [acc (recursive/frame-fragment [:frame :base] {:output 1})
          expander (when-network-expander [:frame :expanded] {:output 2})
          result (run-when-network value/nothing expander acc)]
      (is (= value/nothing (:value result)))))

  (testing "true condition applies the expander"
    (let [acc (recursive/frame-fragment [:frame :base] {:output 1})
          expander (when-network-expander [:frame :expanded] {:output 2})
          result (run-when-network true expander acc)
          out (:value result)]
      (is (= 1 (named-frame-value out [:frame :base] :output)))
      (is (= 2 (named-frame-value out [:frame :expanded] :output)))))

  (testing "contradiction and non-network results contradict"
    (let [expander (when-network-expander [:frame :expanded] {:output 2})
          bad-expander (closure/closure
                        (fn [_closure-net _input-ids _output-ids _declaration-net]
                          :not-a-network)
                        net/empty-net)
          contradiction-result (run-when-network value/contradiction
                                                 expander
                                                 net/empty-net)
          non-network-acc-result (run-when-network true expander :not-a-network)
          non-network-expanded-result (run-when-network true
                                                        bad-expander
                                                        net/empty-net)]
      (is (= value/contradiction (:value contradiction-result)))
      (is (= value/contradiction (:value non-network-acc-result)))
      (is (= value/contradiction (:value non-network-expanded-result))))))

(deftest when-network-is-available-from-compile-dsl
  (testing "old compile DSL can install conditional network expansion"
    (let [expander (when-network-expander [:frame :expanded] {:output 42})
          template (recursive/frame-fragment [:frame :base] {:output 1})
          ctx (eval-and-run
               net/empty-net
               {'ready-value true
                'expander-value expander
                'template-value template}
               '(do
                  (let-cell [ready expander template out]
                    (seed ready ready-value)
                    (seed expander expander-value)
                    (seed template template-value)
                    (closure/p:when-network ready expander template out))))
          out-id (compile/cell-ref ctx 'out)
          expanded (strongest (:net ctx) out-id)]
      (is (= 1 (named-frame-value expanded [:frame :base] :output)))
      (is (= 42 (named-frame-value expanded [:frame :expanded] :output))))))

(deftest when-network-supports-lazy-recursive-frame-expansion
  (testing "child declaration is absent until a guarded child expander runs"
    (let [root-expander (recursive-when-frame-expander [:root] true)
          root-expanded (:value (run-when-network true root-expander net/empty-net))
          child-prop-id (net/network-dict-entry root-expanded expansion-child-prop-id-key)
          child-out-id (net/network-dict-entry root-expanded expansion-child-out-id-key)]
      (is (= true (named-frame-value root-expanded [:root] [:status :declared])))
      (is (nil? (named-frame-value root-expanded
                                   [:child [:root]]
                                   [:status :declared])))
      (let [after-child (run-installed root-expanded [child-prop-id])
            child-expanded (strongest after-child child-out-id)]
        (is (= true (named-frame-value child-expanded
                                       [:child [:root]]
                                       [:status :declared]))))))

  (testing "false child guard passes the frame accumulator through unchanged"
    (let [root-expander (recursive-when-frame-expander [:root] false)
          root-expanded (:value (run-when-network true root-expander net/empty-net))
          child-prop-id (net/network-dict-entry root-expanded expansion-child-prop-id-key)
          child-out-id (net/network-dict-entry root-expanded expansion-child-out-id-key)
          after-child (run-installed root-expanded [child-prop-id])
          child-expanded (strongest after-child child-out-id)]
      (is (= true (named-frame-value child-expanded [:root] [:status :declared])))
      (is (nil? (named-frame-value child-expanded
                                   [:child [:root]]
                                   [:status :declared]))))))

(deftest when-network-can-guard-nested-compound-map-expansion
  (testing "guarded expander emits nested map topology only when enabled"
    (let [source {:left [0 1]
                  :right {:a 2}}
          expander (network-expansion-map-expander source)
          disabled (:value (run-when-network false expander net/empty-net))
          enabled (:value (run-when-network true expander net/empty-net))
          result (run-expanded-network enabled)]
      (is (nil? (net/network-dict-entry disabled expansion-prop-ids-key)))
      (is (= 3 (:leaf-count result)))
      (is (= {:left [0 1]
              :right {:a 1}}
             (compound->data (:value result)))))))

(deftest recursive-fibonacci-lazy-base-branch
  (testing "base branch fills output without building recursive topology"
    (let [branch-builds (atom 0)
          result (run-fib 1 {:branch-builds branch-builds})]
      (is (= 1 (:value result)))
      (is (= 0 @branch-builds)))))

(deftest recursive-fibonacci-failure-cases
  (testing "negative input contradicts"
    (is (= value/contradiction (:value (run-fib -1)))))

  (testing "unusable input leaves output empty"
    (is (= value/nothing (:value (run-fib-unusable-input)))))

  (testing "depth overflow contradicts recursive input"
    (is (= value/contradiction (:value (run-fib 2 {:max-depth 1}))))))

(deftest recursive-propagator-inside-normal-compound
  (testing "a normal compound closure can run one recursive compound application"
    (let [wrapper-value (wrapper-closure (fib-recursive-closure))
          ctx (eval-and-run
               net/empty-net
               {'wrapper-value wrapper-value
                'n-value 7}
               '(do
                  (let-cell [wrapper n out]
                    (seed wrapper wrapper-value)
                    (seed n n-value)
                    (closure/p:apply-closure wrapper n out))))
          out-id (compile/cell-ref ctx 'out)]
      (is (= 13 (strongest (:net ctx) out-id))))))

(deftest compatible-closures-merge-retained-named-networks
  (testing "closure merge reuses named-network join on retained closure nets"
    (let [f (fn [_closure-net _input-ids _output-ids network] network)
          weak-net (recursive/frame-fragment [:fib 2] {:input 2})
          strong-net (named/join
                      weak-net
                      (recursive/frame-fragment
                       [:fib 2]
                       {:output 1 [:status :done] true}))
          weak (closure/closure f weak-net)
          strong (closure/closure f strong-net)
          merged (merge/cell-merge weak strong net/empty-net)
          strongest (merge/strongest-value merged net/empty-net)]
      (is (closure/closure? strongest))
      (is (= 1 (named-frame-value (closure/closure-net strongest)
                                  [:fib 2]
                                  :output)))
      (is (= false (merge/cell-updated? strongest strong net/empty-net)))))

  (testing "different closure functions do not merge"
    (let [left (closure/closure (fn [_ _ _ network] network)
                                (recursive/frame-fragment [:left]
                                                          {[:status :done] true}))
          right (closure/closure (fn [_ _ _ network] network)
                                 (recursive/frame-fragment [:right]
                                                           {[:status :done] true}))]
      (is (= value/contradiction
             (merge/cell-merge left right net/empty-net))))))

(deftest recursive-frame-accumulation-designs-compute-fibonacci
  (testing "self-refining closure and explicit accumulator compute the same values"
    (doseq [[n expected] [[0 0] [1 1] [2 1] [5 5] [10 55]]]
      (is (= expected (:value (run-self-refining-fib n))))
      (is (= expected (:value (run-accumulating-fib n)))))))

(deftest recursive-frame-accumulation-designs-retain-semantic-frames
  (testing "self-refining closure stores frame declarations in the closure net"
    (let [{:keys [frame-net]} (run-self-refining-fib 5)]
      (is (contains? (recursive/frame-index frame-net) [:fib 5]))
      (is (contains? (recursive/frame-index frame-net) [:fib 3]))
      (is (= 5 (named-frame-value frame-net [:fib 5] :output)))
      (is (= true (named-frame-value frame-net [:fib 5] [:status :done])))))

  (testing "explicit accumulator stores the same semantic frame declarations"
    (let [{:keys [frame-net]} (run-accumulating-fib 5)]
      (is (contains? (recursive/frame-index frame-net) [:fib 5]))
      (is (contains? (recursive/frame-index frame-net) [:fib 3]))
      (is (= 5 (named-frame-value frame-net [:fib 5] :output)))
      (is (= true (named-frame-value frame-net [:fib 5] [:status :done]))))))

(deftest recursive-frame-accumulation-is-monotone-and-idempotent
  (testing "self-refining closure frame declarations accumulate by subsumption"
    (let [small (:frame-net (run-self-refining-fib 3))
          large (:frame-net (run-self-refining-fib 5))]
      (is (= true (named/named-network->= large small)))
      (is (named-idempotent? small))
      (is (named-idempotent? large))))

  (testing "explicit accumulator frame declarations accumulate by subsumption"
    (let [small (:frame-net (run-accumulating-fib 3))
          large (:frame-net (run-accumulating-fib 5))]
      (is (= true (named/named-network->= large small)))
      (is (named-idempotent? small))
      (is (named-idempotent? large)))))

(deftest recursive-frame-accumulation-designs-map-over-compound-vector
  (testing "self-refining closure maps recursive fibonacci over vector slots"
    (let [{:keys [value frame-net]} (run-self-refining-map-fib [0 1 2 3 4 5])]
      (is (= [0 1 1 2 3 5]
             (mapv #(obj/slot-value value %) (range 6))))
      (is (= 6 (obj/slot-value value :count)))
      (is (contains? (recursive/frame-index frame-net) [:fib 5]))))

  (testing "explicit accumulator maps recursive fibonacci over vector slots"
    (let [{:keys [value frame-net]} (run-accumulating-map-fib [0 1 2 3 4 5])]
      (is (= [0 1 1 2 3 5]
             (mapv #(obj/slot-value value %) (range 6))))
      (is (= 6 (obj/slot-value value :count)))
      (is (contains? (recursive/frame-index frame-net) [:fib 5])))))

(deftest recursive-frame-accumulation-designs-map-and-reduce-nested-compound
  (let [source {:left [0 1 2]
                :right {:a 3
                        :b [4 5]}}
        expected {:left [0 1 1]
                  :right {:a 2
                          :b [3 5]}}]
    (testing "self-refining closure maps recursive fibonacci through nested slots"
      (let [{:keys [value frame-net]} (run-self-refining-map-fib source)]
        (is (= expected (compound->data value)))
        (is (= 12 (reduce-compound-leaves + 0 value)))
        (is (contains? (recursive/frame-index frame-net) [:fib 5]))
        (is (named-idempotent? frame-net))))

    (testing "explicit accumulator maps recursive fibonacci through nested slots"
      (let [{:keys [value frame-net]} (run-accumulating-map-fib source)]
        (is (= expected (compound->data value)))
        (is (= 12 (reduce-compound-leaves + 0 value)))
        (is (contains? (recursive/frame-index frame-net) [:fib 5]))
        (is (named-idempotent? frame-net))))

    (testing "declared self-refining topology maps without internal runs"
      (let [{:keys [value frame-net leaf-count]}
            (run-declared-self-refining-map-fib source)]
        (is (= expected (compound->data value)))
        (is (= 6 leaf-count))
        (is (= 12 (reduce-compound-leaves + 0 value)))
        (is (contains? (recursive/frame-index frame-net) [:fib 5]))
        (is (named-idempotent? frame-net))))

    (testing "declared accumulating topology maps without internal runs"
      (let [{:keys [value frame-net leaf-count]}
            (run-declared-accumulating-map-fib source)]
        (is (= expected (compound->data value)))
        (is (= 6 leaf-count))
        (is (= 12 (reduce-compound-leaves + 0 value)))
        (is (contains? (recursive/frame-index frame-net) [:fib 5]))
        (is (named-idempotent? frame-net))))))

(deftest direct-recursive-activation-handles-nested-map-and-reduce
  (let [source {:left [0 1 2]
                :right {:a 3
                        :b [4 5]
                        :empty []}}]
    (testing "direct recursive activation maps scalar and first-level vector slots"
      (let [{:keys [value]} (run-direct-nested-map-fib source)]
        (is (= 0 (slot-path-value value [:left 0])))
        (is (= 1 (slot-path-value value [:left 1])))
        (is (= 1 (slot-path-value value [:left 2])))
        (is (= 3 (slot-path-value value [:left :count])))
        (is (= 2 (slot-path-value value [:right :a])))))

    (testing "direct recursive activation is not robust for nested vector outputs"
      (let [{:keys [value]} (run-direct-nested-map-fib source)]
        (is (= value/contradiction (slot-path-value value [:right :b])))
        (is (= value/contradiction (slot-path-value value [:right :empty])))))

    (testing "direct recursive activation reduces nested numeric leaves"
      (let [{:keys [value]} (run-direct-nested-sum source)]
        (is (= 15 value))))))

(deftest network-valued-expansion-handles-nested-map-and-reduce-functionally
  (let [source {:left [0 1 2]
                :right {:a 3
                        :b [4 5]
                        :empty []}}]
    (testing "apply-network can emit nested map topology that is run later"
      (let [expanded (apply-network-expansion
                      (network-expansion-map-expander source))
            {:keys [value leaf-count]} (run-expanded-network expanded)]
        (is (= 6 leaf-count))
        (assert-nested-fib-map value)))

    (testing "apply-network can emit nested reduce topology that is run later"
      (let [expanded (apply-network-expansion
                      (network-expansion-sum-expander source))
            {:keys [value leaf-count]} (run-expanded-network expanded)]
        (is (= 6 leaf-count))
        (is (= 15 value))))))

(deftest network-valued-expansion-topology-is-functional-but-not-idempotent-yet
  (let [source {:left [0 1]
                :right {:a 2}}
        expander (network-expansion-sum-expander source)
        first-expanded (apply-network-expansion expander)
        second-expanded (apply-network-expansion expander)]
    (testing "both expansions compute the same result"
      (is (= 3 (:value (run-expanded-network first-expanded))))
      (is (= 3 (:value (run-expanded-network second-expanded)))))

    (testing "fresh topology ids make repeated expansion structurally different"
      (is (not= (net/network-dict-entry first-expanded expansion-prop-ids-key)
                (net/network-dict-entry second-expanded expansion-prop-ids-key)))
      (is (not= (net/network-dict-entry first-expanded expansion-out-id-key)
                (net/network-dict-entry second-expanded expansion-out-id-key))))))
