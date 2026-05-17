(ns differential-leapfrog.trie
  (:require [leapfrog :as lf]))

(defn empty-trie []
  {:weight 0
   :children (sorted-map-by lf/key-cmp)})

(defn trie? [x]
  (and (map? x) (contains? x :weight) (contains? x :children)))

(defn- node-empty? [node]
  (and (zero? (long (:weight node)))
       (empty? (:children node))))

(defn add-tuple
  ([trie tuple weight]
   (if (zero? (long weight))
     trie
     (let [trie (or trie (empty-trie))]
       (if (empty? tuple)
         (update trie :weight + (long weight))
         (let [k (first tuple)
               child (get-in trie [:children k] (empty-trie))
               child' (add-tuple child (rest tuple) weight)]
           (if (node-empty? child')
             (update trie :children dissoc k)
             (assoc-in trie [:children k] child'))))))))

(defn add-tuples [trie tuples]
  (reduce (fn [t [tuple weight]]
            (add-tuple t tuple weight))
          (or trie (empty-trie))
          tuples))

(defn weight-at [trie tuple]
  (loop [node trie
         tuple tuple]
    (cond
      (nil? node) 0
      (empty? tuple) (:weight node)
      :else (recur (get-in node [:children (first tuple)]) (rest tuple)))))

(defn support-tuples
  ([trie] (support-tuples [] trie))
  ([prefix trie]
   (let [here (when (pos? (long (:weight trie))) [prefix])
         below (mapcat (fn [[k child]]
                         (support-tuples (conj prefix k) child))
                       (:children trie))]
     (vec (concat here below)))))

(defn nonzero-tuples
  ([trie] (nonzero-tuples [] trie))
  ([prefix trie]
   (let [here (when-not (zero? (long (:weight trie))) [prefix])
         below (mapcat (fn [[k child]]
                         (nonzero-tuples (conj prefix k) child))
                       (:children trie))]
     (vec (concat here below)))))

(defn from-weighted-rel [rel]
  (add-tuples (empty-trie) rel))

(defn to-relation [name vars trie]
  (lf/make-relation name vars (support-tuples trie)))
