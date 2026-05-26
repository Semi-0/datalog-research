(ns propagators.network
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.helpers.tagged :refer [tagged?]]
            [propagators.ids :refer [new-node-id]]))

(def net? (tagged? :net))
(def empty-dict {:avatars-in {} :avatars-out {}})
(defn net
  ([graph env] [:net graph env empty-dict])
  ([graph env dict] [:net graph env dict]))
(def empty-net (net {} {}))
(def empty-network empty-net)
(defn network? [x] (net? x))

(defn net-graph [n] (nth n 1))
(defn net-env [n] (nth n 2))
(defn net-dict [n]
  (if (< (count n) 4)
    empty-dict
    (nth n 3)))
(defn net-with-graph [n graph] (net graph (net-env n) (net-dict n)))
(defn net-with-env [n env] (net (net-graph n) env (net-dict n)))
(defn net-with-dict [n dict] (net (net-graph n) (net-env n) dict))

(defn as-net
  "Coerce `[:net g e d]`, legacy `[:net g e]`, or `[g e]` to `[:net g e d]`."
  [x]
  (if (net? x)
    (let [g (nth x 1)
          e (nth x 2)
          d0 (if (< (count x) 4) empty-dict (nth x 3))
          d (if (and (map? d0) (contains? d0 :avatars-in)) d0 empty-dict)]
      (net g e d))
    (net (first x) (second x))))

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
    (if (and (map? d) (contains? d :avatars-in))
      d
      empty-dict)))

(defn clear-dict [n] (net-with-dict n empty-dict))
(defn assoc-avatar-in [n outer inner]
  (net-with-dict n
    (update (net-dict-or-empty n) :avatars-in assoc outer inner)))
(defn assoc-avatar-out [n outer inner]
  (net-with-dict n
    (update (net-dict-or-empty n) :avatars-out assoc outer inner)))
(defn lookup-inner-in [n outer] (get-in (net-dict-or-empty n) [:avatars-in outer]))
(defn lookup-inner-out [n outer] (get-in (net-dict-or-empty n) [:avatars-out outer]))
(defn inner-ids-in [n] (vals (:avatars-in (net-dict-or-empty n))))
(defn inner-ids-out [n] (vals (:avatars-out (net-dict-or-empty n))))

(defn network-env-lookup
  "Env entry for cell or propagator at `node-id` token `[:node-id …]`."
  [network node-id]
  (env-get (net-env network) (graph/node-id node-id)))

(defn network-cell-strongest [network node-id]
  (cell/cell-strongest (network-env-lookup network node-id)))

(defn network-cell-content [network node-id]
  (cell/cell-content (network-env-lookup network node-id)))

(def network-lookup-cell network-env-lookup)

(def network-lookup-propagator network-env-lookup)

(defn update-net-cell
  "Apply `message` (CellValue) to cell at `id`; returns updated net."
  [n id msg]
  (let [e (net-env n)
        cur (env-get e id)
        content' (merge/cell-merge (cell/cell-content cur) msg n)
        strongest' (merge/strongest-value content' n)]
    (assoc-net-cell n id (cell/cell content' strongest'))))

(defn construct-cell
  ([]
   (construct-cell (new-node-id)))
  ([id]
   (fn [arg]
     (let [net (as-net arg)
           n (-> net
                 (assoc-net-node id (graph/blank-node))
                 (assoc-net-cell id (cell/cell value/nothing value/nothing)))]
       [id n])))
  ([id content strongest]
   (fn [arg]
     (let [net (as-net arg)
           n (-> net
                 (assoc-net-node id (graph/blank-node))
                 (assoc-net-cell id (cell/cell content strongest)))]
       [id n]))))

(defn install-net
  "Run installer `f` (`f` takes a net, returns `[id net']`). Returns the new net."
  [n f]
  (second (f n)))

(defn seed-net-cell
  "Install or update cell `id` on `n`. With content/strongest, seeds that cell value."
  ([n id]
   (install-net n (construct-cell id)))
  ([n id content strongest]
   (install-net n (construct-cell id content strongest))))
