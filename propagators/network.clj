(ns propagators.network
  (:require [propagators.cells.cell :as cell]
            [propagators.graph :as graph]))

(defrecord Net [graph env dict])

(defn net?
  [x]
  (and (map? x)
       (contains? x :graph)
       (contains? x :env)
       (contains? x :dict)
       (map? (:graph x))
       (map? (:env x))
       (map? (:dict x))))

(def empty-dict {})
(defn net
  ([graph env] (->Net graph env empty-dict))
  ([graph env dict]
   (->Net graph env (if (map? dict) dict empty-dict))))
(def empty-net (net {} {}))
(def empty-network empty-net)
(defn network? [x] (net? x))

(defn net-graph [n] (:graph n))
(defn net-env [n] (:env n))
(defn net-dict [n] (:dict n))
(defn net-with-graph [n graph] (net graph (net-env n) (net-dict n)))
(defn net-with-env [n env] (net (net-graph n) env (net-dict n)))
(defn net-with-dict [n dict] (net (net-graph n) (net-env n) dict))

(defn as-net
  "Coerce network-shaped input to net record."
  [x]
  (cond
    (net? x)
    (let [d0 (net-dict x)
          d (if (map? d0) d0 empty-dict)]
      (net (net-graph x) (net-env x) d))

    (and (map? x) (contains? x :graph) (contains? x :env))
    (let [d0 (:dict x)
          d (if (map? d0) d0 empty-dict)]
      (net (:graph x) (:env x) d))

    (and (sequential? x) (<= 2 (count x)))
    (let [g (first x)
          e (second x)
          d0 (nth x 2 nil)
          d (if (map? d0) d0 empty-dict)]
      (net g e d))

    :else
    (throw (ex-info "cannot coerce to network"
                    {:value x :type (type x)}))))

(def empty-env {})
(defn env? [x] (map? x))
(defn env-get [env id] (get env id))
(defn assoc-env [env id entry] (assoc env id entry))

(defn assoc-net-node [n id node]
  (net-with-graph n (graph/assoc-graph (net-graph n) id node)))

(defn assoc-net-cell [n id c]
  (net-with-env n (assoc-env (net-env n) id c)))

(defn assoc-net-prop [n id p]
  (net-with-env n (assoc-env (net-env n) id p)))

(defn dict? [x] (map? x))
(defn net-dict-or-empty [n]
  (let [d (net-dict n)]
    (if (map? d)
      d
      empty-dict)))

(defn assoc-net-dict-entry
  "Associate one named dictionary entry on `n`."
  [n k v]
  (net-with-dict n (assoc (net-dict-or-empty n) k v)))

(defn update-net-dict-entry
  "Update one named dictionary entry on `n`."
  [n k f & args]
  (net-with-dict n (apply update (net-dict-or-empty n) k f args)))

(defn network-dict-entry
  "Lookup one named dictionary entry on `n`."
  [n k]
  (get (net-dict-or-empty n) k))

(defn network-indexed-ids
  "Lookup ids recorded under nested dictionary index `index-key` / `entry-key`."
  [n index-key entry-key]
  (get-in (net-dict-or-empty n) [index-key entry-key] #{}))

(defn network-dict-keys-tagged
  "Dictionary keys that are vectors beginning with `tag`."
  [n tag]
  (->> (keys (net-dict-or-empty n))
       (filter #(and (vector? %) (= tag (first %))))
       set))

(defn clear-dict [n] (net-with-dict n empty-dict))
(defn assoc-avatar-in [n outer inner]
  (net-with-dict n
    (update (net-dict-or-empty n) :avatars-in (fnil assoc {}) outer inner)))
(defn assoc-avatar-out [n outer inner]
  (net-with-dict n
    (update (net-dict-or-empty n) :avatars-out (fnil assoc {}) outer inner)))
(defn lookup-inner-in [n outer] (get-in (net-dict-or-empty n) [:avatars-in outer]))
(defn lookup-inner-out [n outer] (get-in (net-dict-or-empty n) [:avatars-out outer]))
(defn inner-ids-in [n] (vals (get (net-dict-or-empty n) :avatars-in {})))
(defn inner-ids-out [n] (vals (get (net-dict-or-empty n) :avatars-out {})))

(defn network-env-lookup
  "Env entry for cell or propagator at `node-id` token `[:node-id …]`."
  [network node-id]
  (env-get (net-env network) (graph/node-id node-id)))

(defn network-cell-strongest [network node-id]
  (cell/cell-strongest (network-env-lookup network node-id)))

(defn network-cell-content [network node-id]
  (cell/cell-content (network-env-lookup network node-id)))

(def network-cell-value network-cell-strongest)

(def network-lookup-cell network-env-lookup)

(def network-lookup-propagator network-env-lookup)

(defn install-net
  "Run installer `f` (`f` takes a net, returns `[id net']`). Returns the new net."
  [n f]
  (second (f n)))

(defn seed-net-cell
  "Install or update cell `id` on `n`. With content/strongest, seeds that cell value."
  ([n id]
   (install-net n (cell/construct-cell id)))
  ([n id content strongest]
   (install-net n (cell/construct-cell id content strongest))))
