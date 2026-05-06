(ns deprecated.differential-v3
  "Minimal v3 (versions + frontiers) from Materialize “Differential from scratch”.
  Collections are [[value w] ...] with integer multiplicity. Messages carry
  explicit versions; frontier v means no further batches at versions < v.")

;;; ---------------------------------------------------------------------------
;;; Collections (v0-style multiset)
;;; ---------------------------------------------------------------------------

(defn consolidate
  "Fold pairs into a map tail-recursively; drop zero weights; sorted vector."
  [pairs]
  (loop [ps pairs acc {}]
    (if (empty? ps)
      (vec (sort-by first
                    (for [[k w] acc :when (not (zero? w))]
                      [k w])))
      (let [[k w] (first ps)
            n (+ (get acc k 0) w)
            acc' (if (zero? n) (dissoc acc k) (assoc acc k n))]
        (recur (rest ps) acc')))))

(defn c-concat [a b] (consolidate (into a b)))

(defn c-neg [xs] (mapv (fn [[k w]] [k (- w)]) xs))

(defn c-map [f xs] (consolidate (mapv (fn [[k w]] [(f k) w]) xs)))

;;; ---------------------------------------------------------------------------
;;; Versioned stream: :data / :frontier
;;; ---------------------------------------------------------------------------

(defn data [v c] {:op :data :v v :c c})
(defn frontier [v] {:op :frontier :v v})

(defn msg-key [m]
  [(:v m) (if (= :data (:op m)) 0 1)])

(defn sort-msgs [xs] (vec (sort-by msg-key xs)))

(defn merge-msgs
  "Tail-recursive merge of two msg streams already sorted by msg-key."
  [a b]
  (loop [a a b b out (transient [])]
    (cond
      (empty? a) (into (persistent! out) b)
      (empty? b) (into (persistent! out) a)
      (neg? (compare (msg-key (first a)) (msg-key (first b))))
      (recur (rest a) b (conj! out (first a)))
      :else (recur a (rest b) (conj! out (first b))))))

;;; ---------------------------------------------------------------------------
;;; Unary linear operator — article: emit f(Δ) at the same version, forward
;;; frontier messages unchanged.
;;; ---------------------------------------------------------------------------

(defn unary-linear
  [f msgs]
  (loop [ms msgs out (transient [])]
    (if (empty? ms)
      (persistent! out)
      (let [m (first ms)]
        (case (:op m)
          :data (recur (rest ms) (conj! out (data (:v m) (f (:c m)))))
          :frontier (recur (rest ms) (conj! out (frontier (:v m)))))))))

;;; ---------------------------------------------------------------------------
;;; v3 concat — physically merge two streams (any order per batch). Optional:
;;; track per-input frontiers and emit output frontier = min when both known.
;;; ---------------------------------------------------------------------------

(defn concat-stream
  "v3 concat: merge two sorted edges — batches may arrive out of global order;
  losslessly interleaves all (:data v Δ) and (:frontier v) messages."
  [a b]
  (merge-msgs a b))

;;; ---------------------------------------------------------------------------
;;; Integrate differences up to version t (integer line)
;;; ---------------------------------------------------------------------------

(defn integrate-upto
  "∑ { Δ_v | v ≤ t }; ignores :frontier for the sum."
  [msgs t]
  (loop [ms msgs acc []]
    (if (empty? ms)
      (consolidate acc)
      (let [m (first ms)]
        (case (:op m)
          :frontier (recur (rest ms) acc)
          :data (recur (rest ms)
                       (if (<= (:v m) t)
                         (into acc (:c m))
                         acc)))))))

;;; ---------------------------------------------------------------------------
;;; Demo + linearity spot-check (tail-recursive loops above)
;;; ---------------------------------------------------------------------------

(defn- demo []
  (println ";; unary-linear: map inc on each batch, frontiers preserved")
  (let [in [(data 0 [[1 2] [2 1]])
            (frontier 1)
            (data 2 [[3 1]])
            (data 1 [[1 -2] [3 1]])
            (frontier 3)]
        out (unary-linear #(c-map inc %) in)]
    (run! prn out))

  (println "\n;; concat-stream: merge A and B like blog out-of-order example")
  (let [a (sort-msgs [(data 0 [[:a 1]])
                      (data 2 [[:d 1]])
                      (data 1 [[:b 1]])
                      (frontier 3)])
        b (sort-msgs [(data 1 [[:c 1]])
                      (data 0 [[:z 1]])
                      (frontier 2)])]
    (run! prn (concat-stream a b)))

  (println "\n;; integrate-upto + linearity: f(∑Δ) pieces via unary-linear")
  (let [f #(c-map (partial * 10) %)
        in [(data 0 [[5 1]])
            (data 1 [[5 -1] [7 2]])
            (frontier 2)]
        stepped (unary-linear f in)
        ;; Σ Δ at t=1 is [[7 2]] after consolidating 5
        whole (integrate-upto in 1)]
    (println "integrated at v<=1:" whole)
    (println "f on integrated:" (f whole))
    (println "batches from unary:" (filter #(= :data (:op %)) stepped)))
  nil)

(defn -main [& _] (demo))
