(ns leapfrog-pure
  "Leapfrog triejoin and semi-naive Datalog without mutable iterators.

  Same public surface as `leapfrog`, with immutable iterators (no `!` —
  these return new iterator values instead of mutating atoms)."
  (:require [clojure.set :as set]))

;; ------------------------------------------------------------
;; Persistent trie
;; ------------------------------------------------------------

(defn key-cmp [a b]
  (compare (str a) (str b)))

(defn empty-trie []
  (sorted-map-by key-cmp))

(defn trie-insert [trie tuple]
  (if (empty? tuple)
    trie
    (let [k  (first tuple)
          ks (rest tuple)]
      (assoc trie k
             (trie-insert
              (get trie k (empty-trie))
              ks)))))

(defn trie-from-tuples [tuples]
  (reduce trie-insert (empty-trie) tuples))

(defn- binsearch-first-gte-idx
  "Index of the leftmost key in sorted `keys` with key >= `target` (key-cmp order).
  Returns (count keys) when every key is < target."
  [keys target]
  (loop [lo 0
         hi (count keys)]
    (if (= lo hi)
      lo
      (let [mid (quot (+ lo hi) 2)
            c   (key-cmp (nth keys mid) target)]
        (if (neg? c)
          (recur (inc mid) hi)
          (recur lo mid))))))

(defn- trie-drop-before
  "Drop keys strictly less than `target`. O(log n) via binary search on sorted keys."
  [trie target]
  (let [basis (empty-trie)]
    (if (empty? trie)
      basis
      (let [ks  (vec (keys trie))
            idx (binsearch-first-gte-idx ks target)]
        (if (= idx (count ks))
          basis
          (into basis (map (fn [k] [k (get trie k)]) (subvec ks idx))))))))

;; ------------------------------------------------------------
;; Immutable trie iterator
;; ------------------------------------------------------------
;;
;; Iterator state (value in each relation's `:iter` field):
;;   {:vars  relation variable order
;;    :depth binding depth in :vars
;;    :node  trie node at current depth}

(defn make-iter [trie]
  {:depth 0
   :node  trie})

(defn- iter-with-vars [relation]
  (assoc (make-iter (:trie relation))
         :vars (:vars relation)))

(defn iter-end? [it]
  (not (seq (:node it))))

(defn iter-key [it]
  (some-> it :node seq first key))

(defn iter-open [it value]
  {:vars  (:vars it)
   :depth (inc (:depth it))
   :node  (get (:node it) value)})

(defn iter-next [it]
  (update it :node #(dissoc % (iter-key it))))

(defn iter-seek [it target]
  (update it :node #(trie-drop-before % target)))

(defn iter-depth [it]
  (:depth it))

;; ------------------------------------------------------------
;; Relation
;; ------------------------------------------------------------

(defn make-relation [name vars tuples]
  {:name name
   :vars vars
   :trie (trie-from-tuples tuples)})

(defn make-state [rel]
  (assoc rel :iter (iter-with-vars rel)))

(defn current-var [state]
  (nth (:vars state) (iter-depth (:iter state)) nil))

(defn active-states [states var]
  (filter #(= (current-var %) var) states))

(defn- active-pairs [states var]
  (vec (keep-indexed
        (fn [i state]
          (when (= (current-var state) var)
            [i state]))
        states)))

(defn- assoc-active-iters [states active-pairs iters]
  (reduce (fn [states [idx it]]
            (assoc-in states [idx :iter] it))
          states
          (map vector (map first active-pairs) iters)))

(defn validate-join-order
  "Return nil when `join-vars` can drive leapfrog on `relations`, else an error map.
  Symbolic check only (no tuple data)."
  [relations join-vars]
  (let [rel-vars (mapv :vars relations)]
    (loop [depths (vec (repeat (count relations) 0))
           remaining join-vars]
      (cond
        (empty? remaining) nil
        :else
        (let [var (first remaining)
              active (vec (keep-indexed
                            (fn [i d]
                              (when (and (< d (count (rel-vars i)))
                                         (= var (nth (rel-vars i) d)))
                                i))
                            depths))]
          (if (empty? active)
            {:var var :join-vars join-vars :relation-vars rel-vars}
            (recur (reduce (fn [ds i] (update ds i inc)) depths active)
                   (rest remaining))))))))

(defn invalid-join-order-ex
  ([relations join-vars]
   (invalid-join-order-ex relations join-vars nil))
  ([relations join-vars rule]
   (let [detail (validate-join-order relations join-vars)]
     (ex-info
      "no relation active for join variable — :join-vars order is incompatible with body :vars"
      (cond-> {:join-vars join-vars
               :relation-vars (mapv :vars relations)
               :var (:var detail)}
        rule (assoc :rule rule))))))

;; ------------------------------------------------------------
;; Leapfrog intersection at one variable
;; ------------------------------------------------------------

(defn any-end? [iters]
  (some iter-end? iters))

(defn sort-iters [iters]
  (sort-by iter-key key-cmp iters))

(defn leapfrog-key [iters]
  (loop [cs (vec (sort-iters iters))]
    (cond
      (empty? cs)
      (throw (ex-info "no iterators for leapfrog join" {}))

      (some iter-end? cs)
      nil

      :else
      (let [least-it     (first cs)
            greatest-it  (last cs)
            least-key    (iter-key least-it)
            greatest-key (iter-key greatest-it)]
        (if (= least-key greatest-key)
          least-key
          (recur (vec (sort-iters
                       (assoc cs 0 (iter-seek least-it greatest-key))))))))))

(defn advance-one [iters]
  (when-not (or (empty? iters) (any-end? iters))
    (let [least-it (first (sort-iters iters))]
      (mapv (fn [it]
              (if (= (iter-key it) (iter-key least-it))
                (iter-next it)
                it))
            iters))))

(defn- leapfrog-values [iters]
  (lazy-seq
    (when-some [value (leapfrog-key iters)]
      (cons value (leapfrog-values (advance-one iters))))))

;; ------------------------------------------------------------
;; Leapfrog Triejoin
;; ------------------------------------------------------------

(defn result-from-env [vars env mode]
  (case mode
    :bindings (mapv (fn [v] [v (get env v)]) vars)
    :tuples   (mapv env vars)
    (throw (ex-info "unknown result mode" {:mode mode}))))

(defn lftj
  ([relations vars]
   (lftj relations vars :bindings))

  ([relations vars mode]
   (let [states (mapv make-state relations)]
     (letfn [(search [remaining-vars states env]
               (if (empty? remaining-vars)
                 [(result-from-env vars env mode)]

                 (let [var       (first remaining-vars)
                       rest-vars (rest remaining-vars)
                       active    (active-pairs states var)]
                   (if (empty? active)
                     [] ; no active relation — failed branch (backtrack)
                     (mapcat
                    (fn [value]
                      (let [env* (assoc env var value)]
                        (if (empty? rest-vars)
                          (search rest-vars states env*)
                          (let [active-iters (mapv (comp :iter second) active)
                                opened-iters (mapv #(iter-open % value) active-iters)
                                states'      (assoc-active-iters states active opened-iters)]
                            (search rest-vars states' env*)))))
                      (leapfrog-values (mapv (comp :iter second) active)))))))]
       (search vars states {})))))

;; ------------------------------------------------------------
;; Tiny semi-naive Datalog
;; ------------------------------------------------------------

(defn body-vars [body]
  (vec (distinct (mapcat second body))))

(defn relation-from-atom [facts [pred vars]]
  (make-relation pred vars (get facts pred #{})))

(defn project-tuple [from-vars to-vars tuple]
  (let [env (zipmap from-vars tuple)]
    (mapv env to-vars)))

(defn eval-rule-with-sources [sources rule]
  (let [[head-pred head-vars] (:head rule)
        body                 (:body rule)
        join-vars            (body-vars body)
        relations            (mapv #(relation-from-atom sources %) body)
        _                    (when (validate-join-order relations join-vars)
                               (throw (invalid-join-order-ex relations join-vars rule)))
        joined-tuples        (lftj relations join-vars :tuples)]
    {head-pred
     (set (map #(project-tuple join-vars head-vars %) joined-tuples))}))

(defn eval-rule-delta [facts delta rule delta-idx]
  (let [body    (:body rule)
        sources (reduce-kv
                 (fn [acc idx [pred _]]
                   (assoc acc pred
                          (get (if (= idx delta-idx) delta facts)
                               pred
                               #{})))
                 {}
                 (vec body))]
    (eval-rule-with-sources sources rule)))

(defn semi-naive-step [facts delta rules]
  (let [candidate-maps
        (for [rule rules
              idx  (range (count (:body rule)))
              :let [[pred _] (nth (:body rule) idx)]
              :when (seq (get delta pred))]
          (eval-rule-delta facts delta rule idx))

        derived (reduce (partial merge-with into) {} candidate-maps)]
    (into {}
          (map (fn [[pred tuples]]
                 [pred (set/difference tuples (get facts pred #{}))])
               derived))))

(defn semi-naive [facts rules]
  (loop [facts facts
         delta facts]
    (let [new-delta (semi-naive-step facts delta rules)]
      (if (every? empty? (vals new-delta))
        facts
        (recur (merge-with into facts new-delta)
               new-delta)))))

(def path-rules
  [{:head [:path [:x :y]]
    :body [[:edge [:x :y]]]}
   {:head [:path [:x :z]]
    :body [[:path [:x :y]]
           [:edge [:y :z]]]}])
