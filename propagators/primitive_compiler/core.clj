(ns propagators.primitive-compiler.core
  (:require [propagators.ids :as ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn ctx0
  ([installers]
   (ctx0 net/empty-net installers))
  ([n installers]
   {:net n
    :installers installers
    :props []
    :value nil}))

(defn self-evaluating? [x]
  (or (nil? x)
      (number? x)
      (string? x)
      (keyword? x)
      (boolean? x)
      (set? x)
      (map? x)
      (vector? x)))

(defn bind-var
  "Bind compiler symbol `sym` to value/id `v` in the network dict."
  [n sym v]
  (net/assoc-net-dict-entry n sym v))

(defn bind-vars
  "Bind compiler symbols to values/ids in the network dict."
  [n sym->value]
  (reduce-kv bind-var n sym->value))

(defn symbol-bindings [n]
  (into {}
        (filter (fn [[k _]] (symbol? k)))
        (net/net-dict-or-empty n)))

(defn resolve-symbol
  "Symbols are cell vars. Missing symbols create fresh empty cells."
  [ctx sym]
  (if (contains? (net/net-dict-or-empty (:net ctx)) sym)
    [ctx (net/network-dict-entry (:net ctx) sym)]
    (let [id (new-node-id)
          n' (-> (:net ctx)
                 (nb/install-cell id)
                 (bind-var sym id))]
      [(assoc ctx :net n') id])))

(defn bind-fresh-cell
  [ctx sym]
  (let [id (new-node-id)
        n' (-> (:net ctx)
               (nb/install-cell id)
               (bind-var sym id))]
    (assoc ctx :net n')))

(defn keyword-installer-candidates
  [kw]
  (let [n (name kw)]
    [kw
     (symbol n)
     (symbol (str "p:" n))
     (symbol "ctx" n)
     (symbol "obj" (str "p:" n))
     (symbol "prop" n)]))

(defn installer-candidates
  [op]
  (if (keyword? op)
    (keyword-installer-candidates op)
    [op]))

(defn lookup-inst [ctx op]
  (let [installers (:installers ctx)
        k (some #(when (contains? installers %) %) (installer-candidates op))
        v (cond
            k
            (get installers k)

            (and (keyword? op)
                 (contains? #{"apply" "recur"} (name op)))
            (throw (ex-info "contextual recursive op used outside recursive body"
                            {:op op
                             :candidates (installer-candidates op)}))

            :else
            (throw (ex-info "unknown installer"
                            {:inst op
                             :candidates (installer-candidates op)
                             :known (keys installers)})))]
    (cond
      (var? v) @v
      (symbol? v) (lookup-inst ctx v)
      :else v)))

(defn prop-ids [installed-id]
  (if (sequential? installed-id)
    (vec installed-id)
    [installed-id]))

(defn fresh-cell
  [ctx]
  (let [id (new-node-id)
        n' (nb/install-cell (:net ctx) id)]
    [(assoc ctx :net n') id]))

(defn bind-symbol-to-value
  [ctx sym value]
  (assoc ctx :net (bind-var (:net ctx) sym value)))

(defn plausible-cell-id? [v]
  (or (ids/node-id? v)
      (keyword? v)
      (symbol? v)
      (string? v)
      (number? v)
      (uuid? v)))

(defn cell-id? [ctx v]
  (and (plausible-cell-id? v)
       (contains? (net/net-env (:net ctx)) v)))

(defn value-cell
  [ctx v]
  (if (cell-id? ctx v)
    [ctx v]
    (let [[ctx' id] (fresh-cell ctx)
          n' (nb/seed-cell (:net ctx') id v)]
      [(assoc ctx' :net n') id])))
