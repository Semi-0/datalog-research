(ns graph.xr-inspection
  "Typed inspection encoding. Never evaluates or dereferences executable values."
  (:require [propagators.ids :as ids])
  (:import [java.util HashMap IdentityHashMap UUID]
           [java.time Instant]))

(defn source-reference [id]
  (cond
    (ids/node-id? id) {:path [:outer] :id (str (:uuid id))}
    (and (vector? id) (= 2 (count id)) (vector? (first id))
         (ids/node-id? (second id)))
    {:path (mapv (fn [part]
                   (if (and (vector? part) (= :cell (first part)))
                     {:cell (str (:uuid (second part)))}
                     part))
                 (first id))
     :id (str (:uuid (second id)))}
    :else nil))

(defn encode
  "Preserve typed keys, records and shared structure in a finite object table."
  [value]
  (let [seen (IdentityHashMap.) objects (atom {}) pending (atom []) next-id (atom 0)]
    (letfn [(object [x tag contents]
              (if (.containsKey seen x)
                {:ref (.get seen x)}
                (let [id (str (dec (swap! next-id inc)))]
                  ;; Queue expansion rather than recursively walking nested Nets.
                  (.put seen x id)
                  (swap! pending conj [id tag contents])
                  {:ref id})))
            (visit [x]
              (cond
                (or (nil? x) (boolean? x) (string? x)) x
                (ids/node-id? x) {:tag "node-id" :value (str (:uuid x))}
                (keyword? x) {:tag "keyword" :value (subs (str x) 1)}
                (symbol? x) {:tag "symbol" :value (str x)}
                (number? x) {:tag "number" :class (.getName (class x)) :value (str x)}
                (instance? UUID x) {:tag "uuid" :value (str x)}
                (instance? Instant x) {:tag "instant" :value (str x)}
                (instance? java.util.Date x) {:tag "date" :value (str (.getTime ^java.util.Date x))}
                (map? x) (object x (if (record? x) (.getName (class x)) "map")
                                  #(hash-map :entries (mapv (fn [[k v]] [(visit k) (visit v)]) x)))
                (set? x) (object x "set" #(hash-map :items (vec (sort-by pr-str (mapv visit x)))))
                (vector? x) (object x "vector" #(hash-map :items (mapv visit x)))
                (list? x) (object x "list" #(hash-map :items (mapv visit x)))
                :else {:tag "opaque" :class (.getName (class x))}))]
      (let [root (visit value)]
        (loop []
          (if (empty? @pending)
            nil
            (let [[id tag contents] (peek @pending)]
              (swap! pending pop)
              (swap! objects assoc id (assoc (contents) :tag tag))
              (recur))))
        ;; Deduplicate encoded shapes only: keys contain scalar tags and refs,
        ;; never recursively compared Net/Cell records.
        (let [shapes (HashMap.) aliases (atom {})
              reference (fn [x] (if (and (map? x) (:ref x))
                                  {:ref (get @aliases (:ref x) (:ref x))} x))
              normalize (fn [encoded]
                          (cond
                            (:entries encoded)
                            (update encoded :entries #(mapv (fn [[k v]] [(reference k) (reference v)]) %))
                            (:items encoded) (update encoded :items #(mapv reference %))
                            :else encoded))
              unique (reduce (fn [result [id encoded]]
                               (let [encoded (normalize encoded)]
                                 (if (.containsKey shapes encoded)
                                   (do (swap! aliases assoc id (.get shapes encoded)) result)
                                   (do (.put shapes encoded id) (assoc result id encoded)))))
                             {} (sort-by #(Long/parseLong (key %)) > @objects))]
          {:root (reference root)
           :objects (into {} (map (fn [[id encoded]] [id (normalize encoded)])) unique)})))))
