(ns propagators.cells.bool4
  "Four-valued logic: true, false, nothing (bottom), contradiction (top)."
  (:refer-clojure :exclude [>= not and or]))

(def nothing :bool4/nothing)
(def contradiction :bool4/contradiction)

(def all-values
  #{true false nothing contradiction})

(defn bool4? [x]
  (contains? all-values x))

(defn nothing? [x]
  (clojure.core/or (= x nothing)
                   (clojure.core/and (vector? x) (= :nothing (first x)))))

(defn contradiction? [x]
  (clojure.core/or (= x contradiction)
                   (clojure.core/and (vector? x) (= :contradiction (first x)))))

(defn unusable? [x]
  (clojure.core/or (nothing? x) (contradiction? x)))

(defmulti domain->=
  (fn [_a _b] :default))

(defmethod domain->= :default
  [a b]
  (= a b))

(defn >=
  "Bool4 lattice subsumption. `contradiction` is top; `nothing` is bottom."
  [a b]
  (cond
    (contradiction? a) true
    (contradiction? b) false
    (nothing? b) true
    (nothing? a) false
    :else (domain->= a b)))

(declare ->evidence evidence->bool4)

(defn join
  "Knowledge-lattice join: combine evidence from both Bool4 values."
  ([x] x)
  ([x y]
   (let [[xt? xf?] (->evidence x)
         [yt? yf?] (->evidence y)]
     (evidence->bool4
      [(clojure.core/or xt? yt?)
       (clojure.core/or xf? yf?)])))
  ([x y & more]
   (reduce join (join x y) more)))

(defn meet
  "Knowledge-lattice meet: keep only evidence present in both Bool4 values."
  ([x] x)
  ([x y]
   (let [[xt? xf?] (->evidence x)
         [yt? yf?] (->evidence y)]
     (evidence->bool4
      [(clojure.core/and xt? yt?)
       (clojure.core/and xf? yf?)])))
  ([x y & more]
   (reduce meet (meet x y) more)))

(defn ->evidence
  "Represent Bool4 as [true-evidence? false-evidence?]."
  [x]
  (cond
    (= x true)       [true false]
    (= x false)      [false true]
    (nothing? x)     [false false]
    (contradiction? x) [true true]
    :else
    (throw (ex-info "Not a Bool4 value" {:value x}))))

(defn evidence->bool4
  [[has-true? has-false?]]
  (cond
    (clojure.core/and has-true? has-false?) contradiction
    has-true? true
    has-false? false
    :else nothing))

(defn not [x]
  (let [[t? f?] (->evidence x)]
    (evidence->bool4 [f? t?])))

(defn and
  ([x] x)
  ([x y]
   (let [[xt? xf?] (->evidence x)
         [yt? yf?] (->evidence y)]
     (evidence->bool4
      [(clojure.core/and xt? yt?)
       (clojure.core/or xf? yf?)])))
  ([x y & more]
   (reduce and (and x y) more)))

(defn or
  ([x] x)
  ([x y]
   (let [[xt? xf?] (->evidence x)
         [yt? yf?] (->evidence y)]
     (evidence->bool4
      [(clojure.core/or xt? yt?)
       (clojure.core/and xf? yf?)])))
  ([x y & more]
   (reduce or (or x y) more)))

(defn implies
  "Bool4 implication: a -> b is equivalent to (or (not a) b)."
  [a b]
  (or (not a) b))