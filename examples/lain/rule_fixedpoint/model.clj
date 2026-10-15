(ns examples.lain.rule-fixedpoint.model
  "Finite binary rule declarations and correlated bindings; no execution policy."
  (:require [clojure.set :as sets]))

(defn variables [rule]
  (vec (distinct (mapcat second (:body rule)))))

(defn checked-rule [rule]
  (let [atoms (cons (:head rule) (:body rule)) vars (variables rule)]
    (if (and (keyword? (:id rule)) (<= 1 (count (:body rule)) 2)
             (<= (count vars) 3)
             (every? (fn [[predicate terms]]
                       (and (keyword? predicate) (vector? terms) (= 2 (count terms))
                            (every? keyword? terms))) atoms)
             (sets/subset? (set (second (:head rule))) (set vars)))
      rule
      (throw (ex-info "Expected a finite binary rule with at most three variables" {:rule rule})))))

(defn checked-catalog [rules]
  (let [rules (mapv checked-rule rules)]
    (if (and (seq rules) (= (count rules) (count (set (map :id rules)))))
      rules
      (throw (ex-info "A fixed catalog requires distinct rule identities" {:rules rules})))))

(defn relation [predicate left right]
  (if (every? keyword? [predicate left right])
    [predicate [left right]]
    (throw (ex-info "A relation requires a predicate and two keyword variables"
                    {:predicate predicate :variables [left right]}))))

(defn rule [id head & body]
  (checked-rule {:id id :head head :body (vec body) :guard nil}))

(defn guarded-rule [declaration guard]
  (assoc (checked-rule declaration) :guard guard))

(defn finite-domain [options sources]
  (let [domain (if (contains? options :domain) (vec (:domain options))
                   (vec (distinct (mapcat :tuple (vals sources)))))
        nodes (set domain)]
    (if (and (= (count domain) (count nodes))
             (every? (fn [{:keys [predicate tuple]}]
                       (and (keyword? predicate) (vector? tuple) (= 2 (count tuple))
                            (every? #(contains? nodes %) tuple))) (vals sources)))
      domain
      (throw (ex-info "Distinct finite nodes and binary source declarations within that domain required" {})))))

(defn copy-rule [from to]
  (checked-rule {:id (keyword (str (name from) "-to-" (name to)))
                 :head [to [:x :y]] :body [[from [:x :y]]]}))

(defn step-rule
  ([head left right] (step-rule head left right nil))
  ([head left right guard]
   (checked-rule {:id (keyword (str (name head) "-via-" (name left) "-" (name right)))
                  :head [head [:x :z]] :body [[left [:x :y]] [right [:y :z]]]
                  :guard guard})))

(defn bind-tuple [binding terms tuple]
  (reduce (fn [result [variable v]]
            (if (nil? result)
              nil
              (if (contains? result variable)
                (if (= (get result variable) v) result nil)
                (assoc result variable v))))
          binding (map vector terms tuple)))

(defn project [rule binding]
  (mapv binding (second (:head rule))))

(defn accepts? [request values]
  (let [vars (:variables request) binding (zipmap vars values)]
    (and (every? (fn [[variable v]] (= v (get binding variable))) (:demand request))
         (every? (fn [[[_ terms] table]]
                   (contains? table (mapv binding terms)))
                 (map vector (get-in request [:rule :body]) (:tables request)))
         (if (:require-delta? request)
           (some (fn [[[_ terms] table]] (contains? table (mapv binding terms)))
                 (map vector (get-in request [:rule :body]) (:deltas request)))
           true)
         ;; The existing AMB family has exactly three source cells. Padding is
         ;; fixed, never additional witnesses or additional DB facts.
         (every? #(= (first (:domain request)) %) (drop (count vars) values)))))

(defn extend-bindings [bindings atom table]
  (let [terms (second atom)]
    (into #{} (keep identity)
          (for [binding bindings tuple table] (bind-tuple binding terms tuple)))))

(defn full-bindings [request]
  (reduce (fn [bindings [atom table]] (extend-bindings bindings atom table))
          #{(:demand request)} (map vector (get-in request [:rule :body]) (:tables request))))

(defn changed-windows [tables deltas]
  ;; Occurrence identity matters: the same predicate can appear twice.
  (mapv (fn [index] (assoc tables index (nth deltas index))) (range (count tables))))

(defn padded [request binding]
  (into (mapv binding (:variables request))
        (repeat (- 3 (count (:variables request))) (first (:domain request)))))
