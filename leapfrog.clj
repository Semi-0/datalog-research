(ns leapfrog
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

;; ------------------------------------------------------------
;; Mutable trie iterator
;; ------------------------------------------------------------
;;
;; The trie itself is immutable.
;; The iterator is a mutable cursor over the trie.
;;
;; Iterator state:
;;   {:branches current-branch-seq
;;    :stack    parent-branch-seqs}

(defn make-iter [trie]
  (atom {:branches (seq trie)
         :stack []}))

(defn iter-branches [it]
  (:branches @it))

(defn iter-stack [it]
  (:stack @it))

(defn iter-end? [it]
  (nil? (:branches @it)))

(defn iter-key [it]
  (when-let [branches (:branches @it)]
    (key (first branches))))

(defn iter-child [it]
  (when-let [branches (:branches @it)]
    (val (first branches))))

(defn iter-next! [it]
  (swap! it update :branches #(seq (next %)))
  it)

(defn iter-seek! [it target]
  ;; Move to first key >= target at current depth.
  (swap! it update :branches
         (fn [branches]
           (seq
            (drop-while
             (fn [entry]
               (neg? (key-cmp (key entry) target)))
             branches))))
  it)

(defn iter-open! [it]
  ;; Descend under the current key.
  (let [{:keys [branches stack]} @it]
    (when-not branches
      (throw (ex-info "cannot open exhausted iterator" {})))
    (reset! it
            {:branches (seq (val (first branches)))
             :stack    (conj stack branches)}))
  it)

(defn iter-up! [it]
  ;; Return to parent depth.
  (let [{:keys [stack]} @it]
    (when (empty? stack)
      (throw (ex-info "iterator has no parent level" {})))
    (reset! it
            {:branches (peek stack)
             :stack    (pop stack)}))
  it)

(defn iter-depth [it]
  (count (:stack @it)))

;; ------------------------------------------------------------
;; Relation
;; ------------------------------------------------------------
;;
;; Relation:
;;   {:name :R
;;    :vars [:x :y]
;;    :trie ...}
;;
;; State:
;;   same relation metadata, plus one mutable iterator.

(defn make-relation [name vars tuples]
  {:name name
   :vars vars
   :trie (trie-from-tuples tuples)})

(defn make-state [rel]
  (assoc rel :iter (make-iter (:trie rel))))

(defn current-var [state]
  (let [depth (iter-depth (:iter state))]
    (nth (:vars state) depth nil)))

(defn active-states [states var]
  (filter #(= (current-var %) var) states))

(defn validate-join-order
  "Return nil when `join-vars` can drive leapfrog on `relations`, else an error map."
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
;; Snapshot / restore
;; ------------------------------------------------------------
;;
;; Because iterators are mutable, every recursive branch must
;; restore cursor state before trying the next branch.

(defn snapshot-states [states]
  (mapv
   (fn [state]
     (let [it (:iter state)]
       [it @it]))
   states))

(defn restore-states! [snapshots]
  (doseq [[it snapshot] snapshots]
    (reset! it snapshot)))

;; ------------------------------------------------------------
;; Leapfrog intersection at one variable
;; ------------------------------------------------------------

(defn any-end? [iters]
  (some iter-end? iters))

(defn sort-iters [iters]
  (sort-by iter-key key-cmp iters))

(defn leapfrog-key! [iters]
  ;; Mutate iterators until they meet at one common key.
  ;;
  ;; Returns:
  ;;   common key, if intersection is non-empty
  ;;   nil, if one iterator is exhausted
  (loop []
    (cond
      (empty? iters)
      (throw (ex-info "no iterators for leapfrog join" {}))

      (any-end? iters)
      nil

      :else
      (let [sorted-iters (sort-iters iters)
            least-it     (first sorted-iters)
            greatest-it  (last sorted-iters)
            least-key    (iter-key least-it)
            greatest-key (iter-key greatest-it)]
        (if (= least-key greatest-key)
          least-key
          (do
            (iter-seek! least-it greatest-key)
            (recur)))))))

(defn advance-one! [iters]
  ;; After finishing one common key, move one iterator past it.
  ;; The next leapfrog-key! will pull the others forward.
  (when-not (or (empty? iters) (any-end? iters))
    (iter-next! (first (sort-iters iters)))))

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
   (let [states  (mapv make-state relations)
         results (atom [])]

     (letfn [(emit! [env]
               (swap! results conj
                      (result-from-env vars env mode)))

             ;; Recursive backtracking:
             ;;
             ;;   choose value for current variable
             ;;   open active iterators under that value
             ;;   recursively solve remaining variables
             ;;   restore cursor state
             ;;   advance to next value
             ;;
             ;; A branch is consistent when active iterators have
             ;; a common key. A branch dies when leapfrog-key! returns nil.
             (search! [remaining-vars env]
               (if (empty? remaining-vars)
                 (emit! env)

                 (let [var          (first remaining-vars)
                       rest-vars    (rest remaining-vars)
                       entry-snap   (snapshot-states states)
                       active       (vec (active-states states var))
                       active-iters (mapv :iter active)]

                   (do
                     (if (empty? active-iters)
                       nil ; no active relation — failed branch (backtrack)
                       (loop []
                         (let [value (leapfrog-key! active-iters)]
                           (when (some? value)
                             (let [before-child (snapshot-states states)
                                   env*         (assoc env var value)]

                               (if (empty? rest-vars)
                                 (emit! env*)
                                 (do
                                   ;; Commit var = value.
                                   (doseq [it active-iters]
                                     (iter-open! it))
                                   (search! rest-vars env*)
                                   (doseq [it active-iters]
                                     (iter-up! it))))

                               (restore-states! before-child)
                               (advance-one! active-iters)
                               (recur))))))
                     ;; Leave this recursive frame exactly as we entered it.
                     (restore-states! entry-snap)))))]

       (search! vars {})
       @results))))

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



(def R
  (make-relation
   :R
   [:x :y]
   [[1 2]
    [1 3]
    [2 2]
    [3 4]]))

(def S
  (make-relation
   :S
   [:y :z]
   [[2 5]
    [2 6]
    [3 5]
    [4 9]]))

(def T
  (make-relation
   :T
   [:x :z]
   [[1 5]
    [1 7]
    [2 6]
    [3 9]]))

;; Default join has no trace output (only the returned vector).
;; Per-step logs: add {:trace? true} to the opts map (4th arg).
;; Example:
  ;; (lftj [R S T] [:x :y :z] :bindings {:trace? true})
(println (lftj [R S T] [:x :y :z]))

(def edge-facts
  {:edge #{[1 2]
           [2 3]
           [3 4]}})

(def path-rules
  [{:head [:path [:x :y]]
    :body [[:edge [:x :y]]]}
   {:head [:path [:x :z]]
    :body [[:path [:x :y]]
           [:edge [:y :z]]]}])

(def datalog-demo
  (semi-naive edge-facts path-rules))

(println (sort (:path datalog-demo)))