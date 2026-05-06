(ns deprecated.differential-v5
  "Minimal v5: product partial order on version vectors, antichain frontiers,
  GLB (meet) of frontiers, integrate ∑(Δ_u | u≤v). Verifies Materialize v5
  couch/distinct merge correction."
  (:require [clojure.test :as t :refer [deftest is testing]]))

;;; ---------------------------------------------------------------------------
;;; Multiset [[k w] ...]
;;; ---------------------------------------------------------------------------

(defn consolidate [pairs]
  (loop [ps pairs acc {}]
    (if (empty? ps)
      (vec (sort-by first (for [[k w] acc :when (not (zero? w))] [k w])))
      (let [[k w] (first ps)
            n (+ (get acc k 0) w)
            acc' (if (zero? n) (dissoc acc k) (assoc acc k n))]
        (recur (rest ps) acc')))))

(defn c-concat [a b] (consolidate (into a b)))
(defn c-neg [xs] (mapv (fn [[k w]] [k (- w)]) xs))

;;; ---------------------------------------------------------------------------
;;; Product partial order (same arity)
;;; ---------------------------------------------------------------------------

(defn v-leq? [a b]
  (every? true? (map <= a b)))

(defn v-eq? [a b] (= a b))

(defn v-lt? [a b]
  (and (v-leq? a b) (not (v-eq? a b))))

(defn v-lub
  "Least upper bound (coordwise max) — the 'join' of two versions."
  [a b]
  (mapv max a b))

(defn minimal-antichain
  "From a finite set of versions, keep v iff no other u has u < v (strict PO).
  This is the GLB frontier construction from the article (union then drop non-minima)."
  [vers]
  (let [vs (vec (distinct vers))]
    (vec (for [v vs :when (not (some #(v-lt? % v) vs))] v))))

(defn frontier-leq-version?
  "Frontier antichain F allows version v iff ∃e∈F with e ≤ v (product order)."
  [frontier v]
  (boolean (some #(v-leq? % v) frontier)))

(defn frontier-leq-frontier?
  "F ≤ G iff upper(G) ⊆ upper(F). Equivalently: ∀g∈G, ∃f∈F : f ≤ g."
  [f g]
  (every? (fn [gv] (some (fn [fv] (v-leq? fv gv)) f)) g))

(defn glb-frontiers [f g]
  (minimal-antichain (into (vec f) g)))

;;; ---------------------------------------------------------------------------
;;; Integrate difference trace: coll(v) = ∑_{u ≤ v} Δ(u)
;;; ---------------------------------------------------------------------------

(defn integrate-upto
  "`diffs` is {version -> [[k w] ...]}. Sums all batches at keys u with u ≤ v."
  [diffs v]
  (reduce c-concat []
          (for [[u d] diffs :when (v-leq? u v)] d)))

(defn w-of [coll k]
  (or (some (fn [[x w]] (when (= x k) w)) coll) 0))

;;; ---------------------------------------------------------------------------
;;; Tests (article examples + PO sanity)
;;; ---------------------------------------------------------------------------

(deftest product-order
  (is (v-leq? [0 0] [1 1]))
  (is (v-leq? [0 1] [1 1]))
  (is (v-leq? [1 0] [1 1]))
  (is (not (v-leq? [1 0] [0 1])))
  (is (= [3 6] (v-lub [2 5] [3 6])))
  (is (= [4 5] (v-lub [2 5] [4 1]))))

(deftest article-antichain-fig
  (let [f [[2 5] [4 1]]]
    (is (frontier-leq-version? f [3 6]))
    (is (not (frontier-leq-version? f [3 3])))))

(deftest glb-drops-dominated
  ;; Union includes [0 0] below both [0 1] and [1 0] → only minimal point survives.
  (is (= #{[0 0]}
         (set (glb-frontiers [[0 1] [1 0] [0 0]] [[0 1] [1 0]]))))
  (is (= #{[0 1] [1 0]}
         (set (glb-frontiers [[0 1] [1 0]] [[0 1] [1 0]]))))
  (is (= #{[2 5] [4 1]}
         (set (glb-frontiers [[2 5] [4 1]] [[2 5] [4 1]])))))

(deftest frontier-order-known
  ;; F ≤ G  ⇔  ∀g∈G ∃f∈F : f ≤ g  (product order); blog’s numeric smoke.
  (is (frontier-leq-frontier? [[1 10] [2 8] [4 6]] [[2 9] [1 10]])))

(deftest couch-distinct-correction
  (testing "v5: two incomparable updates both add couch; PO sum needs −1 at join"
    (let [diffs {[0 0] [['chair 1] ['desk 1] ['towel 1]]
                 [1 0] [['couch 1]]
                 [0 1] [['couch 1]]
                 [1 1] [['couch -1]]}
          c11 (integrate-upto diffs [1 1])]
      (is (= 1 (w-of c11 'couch)))
      (is (= 1 (w-of c11 'chair)))
      (is (= 1 (w-of c11 'desk)))
      (is (= 1 (w-of c11 'towel))))))

(deftest diff-roundtrip-on-grid
  (testing "coll(v)=∑_{u≤v}Δ(u) on a 2×2 lower set"
    (let [verts (for [i (range 2) j (range 2)] [i j])
          coll {[0 0] [['x 1]]
                [0 1] [['x 2]]
                [1 0] [['x 2]]
                [1 1] [['x 3]]}
          order (fn [u v] (compare [(apply + u) u] [(apply + v) v]))
          sorted (sort order verts)
          diffs
          (loop [todo sorted acc {}]
            (if (empty? todo)
              acc
              (let [v (first todo)
                    below (reduce c-concat []
                            (for [u (keys acc) :when (v-lt? u v)]
                              (get acc u [])))
                    d (c-concat (get coll v []) (c-neg below))]
                (recur (rest todo) (assoc acc v d)))))]
      (doseq [v verts]
        (is (= (sort-by first (get coll v))
               (sort-by first (integrate-upto diffs v))))))))

(defn -main [& _]
  (let [{:keys [fail error pass test]} (t/run-tests 'deprecated.differential-v5)]
    (println "differential-v5 tests:" pass "pass," fail "fail," error "error")
    (when (or (pos? fail) (pos? error))
      (System/exit 1))))
